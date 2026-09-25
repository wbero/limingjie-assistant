package com.landosol.toolbox.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.view.WindowManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.landosol.toolbox.LandosolToolboxApplication
import com.landosol.toolbox.account.AccountViewModel
import com.landosol.toolbox.automation.accessibility.AccessibilityConnectionRegistry
import com.landosol.toolbox.automation.accessibility.LandosolAccessibilityService
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.LabyrinthUiState
import com.landosol.toolbox.labyrinth.LabyrinthController
import com.landosol.toolbox.ui.account.AccountScreen
import com.landosol.toolbox.ui.labyrinth.LabyrinthScreen
import com.landosol.toolbox.ui.labyrinth.LabyrinthStrategySettingsScreen
import com.landosol.toolbox.permissions.PermissionStatusReader
import com.landosol.toolbox.automation.capture.MediaProjectionCaptureService
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.capture.CaptureState
import com.landosol.toolbox.automation.overlay.AndroidAutomationNotificationHost
import com.landosol.toolbox.ui.permissions.PermissionCenterScreen
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class AppScreen {
    Accounts,
    Labyrinth,
    Permissions,
}
private data class TopLevelDestination(
    val screen: AppScreen,
    val label: String,
    val shortLabel: String,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(AppScreen.Labyrinth, "黎明界", "黎"),
    TopLevelDestination(AppScreen.Accounts, "账号库", "号"),
    TopLevelDestination(AppScreen.Permissions, "设置", "设"),
)

@Composable
fun LandosolToolboxApp() {
    val context = LocalContext.current
    val application = context.applicationContext as LandosolToolboxApplication
    val lifecycleOwner = LocalLifecycleOwner.current
    val captureScope = rememberCoroutineScope()
    var pendingCaptureAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val action = pendingCaptureAction
        pendingCaptureAction = null
        if (result.resultCode != android.app.Activity.RESULT_OK || result.data == null) return@rememberLauncherForActivityResult
        MediaProjectionCaptureService.start(context, result.resultCode, result.data!!)
        action ?: return@rememberLauncherForActivityResult
        captureScope.launch {
            val running = withTimeoutOrNull(CAPTURE_START_TIMEOUT_MILLIS) {
                CaptureStateRegistry.observe()
                    .filterIsInstance<CaptureState.Running>()
                    .first()
            }
            if (running != null) action()
        }
    }
    val beginCaptureRequest: ((() -> Unit) -> Unit) = { action ->
        if (CaptureStateRegistry.isActive()) {
            action()
        } else {
            pendingCaptureAction = action
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }
    }
    val taskNotificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingNotificationAction
        pendingNotificationAction = null
        if (granted && AndroidAutomationNotificationHost.isAvailable(context) && action != null) beginCaptureRequest(action)
    }
    val requestCaptureThen: ((() -> Unit) -> Unit) = { action ->
        if (AndroidAutomationNotificationHost.isAvailable(context)) {
            beginCaptureRequest(action)
        } else {
            pendingNotificationAction = action
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) taskNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(AndroidAutomationNotificationHost.settingsIntent(context))
        }
    }
    val requestCapture: () -> Unit = { beginCaptureRequest {} }
    DisposableEffect(lifecycleOwner, pendingNotificationAction) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME || pendingNotificationAction == null) return@LifecycleEventObserver
            if (!AndroidAutomationNotificationHost.isAvailable(context)) return@LifecycleEventObserver
            val action = pendingNotificationAction ?: return@LifecycleEventObserver
            pendingNotificationAction = null
            beginCaptureRequest(action)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val labyrinthViewModel = application.labyrinthController
    val labyrinthState by labyrinthViewModel.uiState.collectAsStateWithLifecycle()
    val entryRecognitionState by application.labyrinthEntryRecognitionSession.state.collectAsStateWithLifecycle()
    val sessionResetState by application.gameSessionResetWorkflow.state.collectAsStateWithLifecycle()
    val autoRunProgress by application.labyrinthAutoRunWorkflow.progress.collectAsStateWithLifecycle()
    val batchCheckpoint by application.labyrinthBatchController.state.collectAsStateWithLifecycle()
    val batchHaltReason by application.labyrinthBatchController.haltReason.collectAsStateWithLifecycle()
    val batchActive = batchCheckpoint?.stage in setOf(
        com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.REROLLING,
        com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION,
        com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.RUNNING_LABYRINTH,
        com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.RECORDING_RESULT,
    )
    KeepScreenAwake(
        enabled = entryRecognitionState.running ||
            sessionResetState.running ||
            autoRunProgress.running ||
            batchActive,
    )

    var screenName by rememberSaveable { mutableStateOf(AppScreen.Labyrinth.name) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val activity = context as? Activity
        if (activity?.intent?.getBooleanExtra("openLabyrinth", false) == true) {
            screenName = AppScreen.Labyrinth.name
            activity.intent.removeExtra("openLabyrinth")
        }
    }
    val requestedScreen = runCatching { AppScreen.valueOf(screenName) }.getOrNull()
    val screen = requestedScreen?.takeIf { candidate ->
        topLevelDestinations.any { it.screen == candidate }
    } ?: AppScreen.Labyrinth

    val configuration = LocalConfiguration.current
    val compactLandscape = isCompactLandscape(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    )
    val sideNavigation = shouldUseNavigationRail(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    )
    MaterialTheme(typography = if (compactLandscape) CompactLandscapeTypography else Typography()) {
        val destinationContent: @Composable (Modifier) -> Unit = { modifier ->
            Surface(modifier = modifier.fillMaxSize()) {
                when (screen) {
                    AppScreen.Accounts -> AccountRoute()
                    AppScreen.Labyrinth -> LabyrinthRoute(
                        application = application,
                        labyrinthViewModel = labyrinthViewModel,
                        state = labyrinthState,
                        entryRecognitionState = entryRecognitionState,
                        batchCheckpoint = batchCheckpoint,
                        batchHaltReason = batchHaltReason,
                        onRequestCapture = requestCaptureThen,
                    )
                    AppScreen.Permissions -> PermissionRoute(onStartCapture = requestCapture)
                }
            }
        }
        if (sideNavigation) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(modifier = Modifier.fillMaxHeight().width(64.dp)) {
                    topLevelDestinations.forEach { destination ->
                        NavigationRailItem(
                            selected = screen == destination.screen,
                            onClick = { screenName = destination.screen.name },
                            icon = {
                                Text(destination.shortLabel, style = MaterialTheme.typography.labelLarge)
                            },
                            label = {
                                Text(destination.label, style = MaterialTheme.typography.labelSmall)
                            },
                            alwaysShowLabel = true,
                        )
                    }
                }
                destinationContent(Modifier.weight(1f))
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(modifier = Modifier.height(64.dp)) {
                        topLevelDestinations.forEach { destination ->
                            NavigationBarItem(
                                selected = screen == destination.screen,
                                onClick = { screenName = destination.screen.name },
                                icon = {
                                    Text(destination.shortLabel, style = MaterialTheme.typography.labelLarge)
                                },
                                label = {
                                    Text(destination.label, style = MaterialTheme.typography.labelSmall)
                                },
                            )
                        }
                    }
                },
            ) { outerPadding ->
                destinationContent(Modifier.padding(outerPadding))
            }
        }
    }
}

@Composable
@SuppressLint("WakelockTimeout")
@Suppress("DEPRECATION")
private fun KeepScreenAwake(enabled: Boolean) {
    val context = LocalContext.current
    val activity = context as? Activity
    val wakeLock = remember(context) {
        context.getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK,
            "${context.packageName}:labyrinth-automation",
        )
    }
    DisposableEffect(activity, enabled, wakeLock) {
        if (enabled) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!wakeLock.isHeld) wakeLock.acquire()
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}

private const val CAPTURE_START_TIMEOUT_MILLIS = 5_000L

@Composable
private fun PermissionRoute(
    onStartCapture: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshToken by remember { mutableIntStateOf(0) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshToken++ }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshToken++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val captureState by CaptureStateRegistry.observe().collectAsStateWithLifecycle()
    val accessibilityConnected by AccessibilityConnectionRegistry.observe().collectAsStateWithLifecycle()
    val state = remember(refreshToken, captureState, accessibilityConnected) {
        PermissionStatusReader.read(context)
    }
    PermissionCenterScreen(
        state = state,
        onBack = null,
        onOpenAccessibilitySettings = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        onRequestNotifications = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(AndroidAutomationNotificationHost.settingsIntent(context))
            }
        },
        onStartCapture = onStartCapture,
        onStopCapture = {
            MediaProjectionCaptureService.stop(context)
            refreshToken++
        },
    )
}

@Composable
private fun LabyrinthRoute(
    application: LandosolToolboxApplication,
    labyrinthViewModel: LabyrinthController,
    state: LabyrinthUiState,
    entryRecognitionState: LabyrinthEntryRecognitionSessionState,
    batchCheckpoint: com.landosol.toolbox.labyrinth.batch.LabyrinthBatchCheckpoint?,
    batchHaltReason: com.landosol.toolbox.labyrinth.batch.LabyrinthBatchHaltReason?,
    onRequestCapture: ((() -> Unit) -> Unit),
) {
    val scope = rememberCoroutineScope()
    var notificationAccount by remember { mutableStateOf<Long?>(null) }
    var notificationGuildId by remember { mutableStateOf<Int?>(null) }
    var showStrategies by rememberSaveable { mutableStateOf(false) }
    var strategySaving by remember { mutableStateOf(false) }
    var strategyMessage by remember { mutableStateOf<String?>(null) }
    val savedStrategy by application.labyrinthStrategySettings.state.collectAsStateWithLifecycle()
    if (showStrategies) {
        LabyrinthStrategySettingsScreen(
            loadRoleRatings = application::loadLabyrinthRoleRatings,
            saved = savedStrategy,
            saving = strategySaving,
            message = strategyMessage,
            onClose = { showStrategies = false; strategyMessage = null },
            onSave = { settings ->
                if (!strategySaving) {
                    strategySaving = true
                    strategyMessage = null
                    scope.launch {
                        try {
                            application.labyrinthStrategySettings.save(settings)
                            strategyMessage = null
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            strategyMessage = failure.message ?: "保存失败，请重试"
                        } finally {
                            strategySaving = false
                        }
                    }
                }
            },
        )
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && notificationAccount == labyrinthViewModel.uiState.value.selectedAccount?.id) {
            labyrinthViewModel.start(retreatConfirmed = true, guildIdOverride = notificationGuildId)
        } else {
            labyrinthViewModel.reportMessage("未启动刷取：请允许通知权限并确认当前账号后重试")
        }
        notificationAccount = null
        notificationGuildId = null
    }
    LabyrinthScreen(
        onOpenStrategies = { showStrategies = true; strategyMessage = null },
        state = state,
        entryRecognitionState = entryRecognitionState,
        batchCheckpoint = batchCheckpoint,
        batchHaltReason = batchHaltReason,
        onBack = null,
        onCheckStatus = labyrinthViewModel::checkStatus,
        onSaveSettings = labyrinthViewModel::saveSettings,
        onStart = { guildId ->
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationAccount = state.selectedAccount?.id
                notificationGuildId = guildId
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (!androidx.core.app.NotificationManagerCompat.from(application).areNotificationsEnabled()) {
                labyrinthViewModel.reportMessage("通知被关闭，请在系统设置中允许通知后再开始后台刷取")
            } else labyrinthViewModel.start(retreatConfirmed = true, guildIdOverride = guildId)
        },
        onStop = labyrinthViewModel::stop,
        onDismissMessage = labyrinthViewModel::dismissMessage,
        onStartEntryRecognition = {
            onRequestCapture {
                scope.launch {
                    application.labyrinthEntryRecognitionSession.start(
                        accountId = state.selectedAccount?.id,
                    )
                }
            }
        },
        onStartEntryAutomation = {
            // 先检查无障碍实际连接状态，再申请 MediaProjection。否则服务未连接时，
            // 用户点击“执行入口流程”仍会先弹录屏授权并启动录屏。
            if (!LandosolAccessibilityService.isConnected()) {
                scope.launch {
                    application.labyrinthEntryRecognitionSession.startAutomation(
                        accountId = state.selectedAccount?.id,
                    )
                }
            } else {
                onRequestCapture {
                    scope.launch {
                        application.labyrinthEntryRecognitionSession.startAutomation(
                            accountId = state.selectedAccount?.id,
                        )
                    }
                }
            }
        },
        onStopEntryRecognition = {
            scope.launch { application.labyrinthEntryRecognitionSession.stop() }
        },
        onStartAutoRun = { goals ->
            if (!LandosolAccessibilityService.isConnected()) {
                labyrinthViewModel.reportMessage("无障碍服务未连接；若系统开关显示已开启，请关闭后重新开启")
            } else {
                onRequestCapture {
                    application.startLabyrinthAutoRun(
                        accountId = state.selectedAccount?.id,
                        goals = goals,
                    )
                }
            }
        },
        onStopAutoRun = { application.stopLabyrinthAutoRun() },
        onCaptchaSolved = labyrinthViewModel::submitCaptcha,
        onCaptchaError = labyrinthViewModel::reportCaptchaError,
        onCancelCaptcha = labyrinthViewModel::cancelCaptcha,
    )
}

@Composable
private fun AccountRoute() {
    val application = LocalContext.current.applicationContext as LandosolToolboxApplication
    val accountViewModel: AccountViewModel = viewModel(
        factory = AccountViewModel.Factory(
            application.accountRepository,
        ),
    )
    val state by accountViewModel.uiState.collectAsStateWithLifecycle()

    AccountScreen(
        state = state,
        onBack = null,
        onAdd = accountViewModel::openNewAccount,
        onEdit = accountViewModel::openEditAccount,
        onSelect = accountViewModel::selectAccount,
        onRequestDelete = accountViewModel::requestDelete,
        onDismissDelete = accountViewModel::dismissDelete,
        onConfirmDelete = accountViewModel::confirmDelete,
        onEditorChange = accountViewModel::updateEditor,
        onDismissEditor = accountViewModel::dismissEditor,
        onSaveEditor = accountViewModel::saveEditor,
        onDismissMessage = accountViewModel::dismissMessage,
    )
}
