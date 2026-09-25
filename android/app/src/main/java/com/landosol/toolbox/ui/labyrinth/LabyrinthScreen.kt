package com.landosol.toolbox.ui.labyrinth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.labyrinth.LabyrinthRerollSettings
import com.landosol.toolbox.labyrinth.rerollSettings
import com.landosol.toolbox.labyrinth.labyrinthRerollStatusText
import kotlinx.coroutines.delay
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchCheckpoint
import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchGoal
import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchGoalMode
import com.landosol.toolbox.labyrinth.LabyrinthGuildOption
import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchHaltReason
import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage
import com.landosol.toolbox.labyrinth.LabyrinthBossOption
import com.landosol.toolbox.labyrinth.LabyrinthCurrentOpeningReadStatus
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.LabyrinthRerollOptions
import com.landosol.toolbox.labyrinth.LabyrinthRouteEvaluationMode
import com.landosol.toolbox.labyrinth.LabyrinthRouteProgress
import com.landosol.toolbox.labyrinth.LabyrinthRouteVerdict
import com.landosol.toolbox.labyrinth.LabyrinthThirdBlockChoice
import com.landosol.toolbox.labyrinth.LabyrinthUiState
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.ui.account.GeetestCaptchaDialog
import com.landosol.toolbox.ui.CompactTopBar
import com.landosol.toolbox.ui.isCompactLandscape
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LabyrinthScreen(
    state: LabyrinthUiState,
    entryRecognitionState: LabyrinthEntryRecognitionSessionState,
    batchCheckpoint: LabyrinthBatchCheckpoint? = null,
    batchHaltReason: LabyrinthBatchHaltReason? = null,
    onBack: (() -> Unit)? = null,
    onSaveSettings: (LabyrinthRerollSettings) -> Unit,
    onCheckStatus: () -> Unit,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onDismissMessage: () -> Unit,
    onStartEntryRecognition: () -> Unit,
    onStartEntryAutomation: () -> Unit,
    onStopEntryRecognition: () -> Unit,
    onStartAutoRun: (List<LabyrinthBatchGoal>) -> Unit,
    onStopAutoRun: () -> Unit,
    onCaptchaSolved: (String) -> Unit,
    onCaptchaError: (String) -> Unit,
    onCancelCaptcha: () -> Unit,
    onOpenStrategies: () -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val compactWidth = configuration.screenWidthDp < 600
    val compactLandscape = isCompactLandscape(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    )
    var confirmRetreat by remember(state.selectedAccount?.id) { mutableStateOf(false) }
    var showRerollSettings by remember(state.selectedAccount?.id) { mutableStateOf(false) }
    var showAdvancedTools by rememberSaveable { mutableStateOf(false) }
    var standaloneGuildId by rememberSaveable(state.selectedAccount?.id) {
        mutableStateOf(LabyrinthRerollOptions.DEFAULT_GUILD_ID)
    }
    val requestStart: () -> Unit = {
        if (state.retireExisting) confirmRetreat = true else onStart(standaloneGuildId)
    }
    if (confirmRetreat) {
        AlertDialog(onDismissRequest = { confirmRetreat = false },
            title = { Text("允许彻底撤退当前开局？") },
            text = { Text("本次刷取可能放弃当前开局的进度和未领取奖励。只撤退已确认不符合目标的开局；状态不明确时会停止。") },
            confirmButton = {
                TextButton(onClick = { confirmRetreat = false; onStart(standaloneGuildId) }) {
                    Text("确认并开始")
                }
            },
            dismissButton = { TextButton(onClick = { confirmRetreat = false }) { Text("取消") } })
    }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val batchActive = batchCheckpoint?.stage in setOf(
        LabyrinthBatchStage.REROLLING,
        LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION,
        LabyrinthBatchStage.RUNNING_LABYRINTH,
        LabyrinthBatchStage.RECORDING_RESULT,
    )
    Scaffold(
        topBar = {
            CompactTopBar(
                title = "黎明界 · ${AppVersion.display}",
                onBack = onBack,
                actions = {
                    TextButton(
                        onClick = { showRerollSettings = true },
                        enabled = state.settingsReady && !state.isWorking && state.captcha == null,
                    ) {
                        Text(
                            if (compactWidth) "刷取" else "刷开局设置",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    TextButton(onClick = onOpenStrategies) {
                        Text(
                            if (compactWidth) "策略" else "策略设置",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (scrollState.value > 0) {
                    ExtendedFloatingActionButton(
                        onClick = { scope.launch { scrollState.animateScrollTo(0) } },
                    ) {
                        Text("回到顶部")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(
                    start = if (compactLandscape) 8.dp else 16.dp,
                    top = if (compactLandscape) 8.dp else 16.dp,
                    end = if (compactLandscape) 8.dp else 16.dp,
                    bottom = if (compactLandscape) 72.dp else 152.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (compactLandscape) 8.dp else 12.dp),
        ) {
            WorkflowHeader()
            CurrentOpeningStep(
                state = state,
                externalTaskActive = batchActive || entryRecognitionState.running,
                onCheckStatus = onCheckStatus,
                onStop = onStop,
            )
            HorizontalDivider()
            BatchRunCard(
                state = state,
                checkpoint = batchCheckpoint,
                haltReason = batchHaltReason,
                routeProgress = entryRecognitionState.routeProgress,
                entryRecognitionRunning = entryRecognitionState.running,
                onStart = onStartAutoRun,
                onStop = onStopAutoRun,
            )
            HorizontalDivider()
            AuxiliaryToolsSection(
                state = state,
                entryRecognitionState = entryRecognitionState,
                batchActive = batchActive,
                standaloneGuildId = standaloneGuildId,
                onStandaloneGuildSelected = { standaloneGuildId = it },
                onStartReroll = requestStart,
                onStopReroll = onStop,
                showAdvanced = showAdvancedTools,
                onToggleAdvanced = { showAdvancedTools = !showAdvancedTools },
            )
            if (showAdvancedTools) {
                EntryRecognitionCard(
                    state = entryRecognitionState,
                    startEnabled = !batchActive && !state.isWorking && state.captcha == null,
                    onStart = onStartEntryRecognition,
                    onStartAutomation = onStartEntryAutomation,
                    onStop = onStopEntryRecognition,
                )
            }

            state.progress?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            state.message?.let { message ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissMessage) { Text("关闭") }
                    }
                }
            }

            SupportFooter(uriHandler = uriHandler)
        }
    }

    if (showRerollSettings) {
        RerollSettingsDialog(
            state = state,
            onDismiss = { showRerollSettings = false },
            onSave = {
                onSaveSettings(it)
                showRerollSettings = false
            },
        )
    }

    state.captcha?.let { captcha ->
        GeetestCaptchaDialog(
            state = captcha,
            isWorking = state.isWorking,
            onSolved = onCaptchaSolved,
            onError = onCaptchaError,
            onDismiss = onCancelCaptcha,
        )
    }
}

@Composable
private fun WorkflowHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("自动执行", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "先登录并确认当前开局，再按批量目标连续执行。自动执行仍会准备新的目标开局。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkflowStepHeader(number: String, title: String, summary: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SupportFooter(uriHandler: androidx.compose.ui.platform.UriHandler) {
    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("黎明界助手 · 作者 wbero · 交流群 1065226139", style = MaterialTheme.typography.bodySmall)
        Text("测试版本，遇到异常请附上日志和复现步骤。", style = MaterialTheme.typography.bodySmall)
        ChoiceRow {
            TextButton(onClick = { uriHandler.openUri("http://127.0.0.1:8765/") }) { Text("实时日志") }
            TextButton(onClick = { uriHandler.openUri("http://127.0.0.1:8765/logs.zip") }) { Text("下载日志 ZIP") }
        }
    }
}

@Composable
private fun RerollSettingsDialog(
    state: LabyrinthUiState,
    onDismiss: () -> Unit,
    onSave: (LabyrinthRerollSettings) -> Unit,
) {
    var draft by remember { mutableStateOf(state.rerollSettings()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("刷开局设置 · 当前账号") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("保存后下次自动恢复；运行中使用开始时的固定配置。")
                Text(
                    "这里保存难度、路线、Boss、尝试次数和撤退规则。公会不属于全局设置：批量公会在步骤 2 选择，单独刷开局的公会在辅助工具中按次选择。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { draft = LabyrinthRerollSettings(guildId = state.selectedGuildId,
                    difficulty = LabyrinthRerollOptions.DEFAULT_DIFFICULTY.coerceAtMost(state.availableDifficulties.last()));
                    error = null }) { Text("恢复默认（保存后生效）") }
                RerollSettingsFields(
                    state = state.copy(selectedDifficulty = draft.difficulty, perfectStart = draft.perfectStart,
                        routeEvaluationMode = draft.routeEvaluationMode, valueAllowance = draft.valueAllowance,
                        thirdBlockChoice = draft.thirdBlockChoice, selectedArea3BossIds = draft.area3BossIds,
                        selectedArea5BossIds = draft.area5BossIds, maxAttempts = draft.maxAttempts,
                        rerollUntilFound = draft.rerollUntilFound, retireExisting = draft.retireExisting),
                    onDifficultySelected = { draft = draft.copy(difficulty = it) },
                    onPerfectStartChange = { draft = draft.copy(perfectStart = it) },
                    onRouteEvaluationModeSelected = { draft = draft.copy(routeEvaluationMode = it) },
                    onValueAllowanceSelected = { draft = draft.copy(valueAllowance = it) },
                    onThirdBlockChoiceSelected = { draft = draft.copy(thirdBlockChoice = it) },
                    onArea3BossToggle = { draft = draft.copy(area3BossIds = if (it in draft.area3BossIds) draft.area3BossIds - it else draft.area3BossIds + it) },
                    onArea5BossToggle = { draft = draft.copy(area5BossIds = if (it in draft.area5BossIds) draft.area5BossIds - it else draft.area5BossIds + it) },
                    onMaxAttemptsChange = { draft = draft.copy(maxAttempts = it) },
                    onRerollUntilFoundChange = { draft = draft.copy(rerollUntilFound = it) },
                    onRetireExistingChange = { draft = draft.copy(retireExisting = it) },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            error = draft.validationError() ?: if (draft.difficulty !in state.availableDifficulties) "所选难度尚未解锁" else null
            if (error == null) onSave(draft)
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RerollSettingsFields(
    state: LabyrinthUiState,
    onDifficultySelected: (Int) -> Unit,
    onPerfectStartChange: (Boolean) -> Unit,
    onRouteEvaluationModeSelected: (LabyrinthRouteEvaluationMode) -> Unit,
    onValueAllowanceSelected: (Int) -> Unit,
    onThirdBlockChoiceSelected: (LabyrinthThirdBlockChoice) -> Unit,
    onArea3BossToggle: (Int) -> Unit,
    onArea5BossToggle: (Int) -> Unit,
    onMaxAttemptsChange: (String) -> Unit,
    onRerollUntilFoundChange: (Boolean) -> Unit,
    onRetireExistingChange: (Boolean) -> Unit,
) {
    SelectionCard("难度", "检查当前开局后只显示已解锁难度") {
        ChoiceRow {
            state.availableDifficulties.forEach { difficulty ->
                FilterChip(
                    selected = difficulty == state.selectedDifficulty,
                    onClick = { onDifficultySelected(difficulty) },
                    label = { Text(difficulty.toString()) },
                    enabled = !state.isWorking,
                )
            }
        }
    }
    SelectionCard(
        title = "路线要求",
        description = "价值路线 v2 按当前地图自身上界选最大收益路径；旧版固定模板保留用于 A/B 对照。",
    ) {
        Text("路线判定", style = MaterialTheme.typography.labelLarge)
        ChoiceRow {
            LabyrinthRouteEvaluationMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == state.routeEvaluationMode,
                    onClick = { onRouteEvaluationModeSelected(mode) },
                    label = { Text(mode.label) },
                    enabled = !state.isWorking,
                )
            }
        }
        if (state.routeEvaluationMode == LabyrinthRouteEvaluationMode.VALUE_ROUTE) {
            Text("每个区域允许少拿几格", style = MaterialTheme.typography.labelLarge)
            ChoiceRow {
                (0..LabyrinthRerollOptions.MAX_VALUE_ALLOWANCE).forEach { allowance ->
                    FilterChip(
                        selected = allowance == state.valueAllowance,
                        onClick = { onValueAllowanceSelected(allowance) },
                        label = { Text(allowance.toString()) },
                        enabled = !state.isWorking,
                    )
                }
            }
            Text(
                "0=必须拿到该地图自身可达的全部贵重格；1=每区最多少1格。Boss仍是硬条件，不会用少拿额度换掉。",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            CheckOption(
                label = "完美开局（旧版模板）",
                checked = state.perfectStart,
                enabled = !state.isWorking,
                onCheckedChange = onPerfectStartChange,
            )
        }
        Text("区域 3 / 5 遗物 vs 事件", style = MaterialTheme.typography.labelLarge)
        ChoiceRow {
            LabyrinthThirdBlockChoice.entries.forEach { choice ->
                FilterChip(
                    selected = choice == state.thirdBlockChoice,
                    onClick = { onThirdBlockChoiceSelected(choice) },
                    label = { Text(choice.label) },
                    enabled = !state.isWorking,
                )
            }
        }
        Text(
            if (state.routeEvaluationMode == LabyrinthRouteEvaluationMode.VALUE_ROUTE)
                "价值路线中该偏好不绑定列号；选择“两者都行”时不会增加刷图门槛，同分路线优先遗物。"
            else
                "旧版模板中该条件仅在勾选完美开局时生效。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    BossSelectionCard(
        title = "区域 3 Boss",
        options = state.area3BossOptions,
        selectedIds = state.selectedArea3BossIds,
        enabled = !state.isWorking,
        onToggle = onArea3BossToggle,
    )
    BossSelectionCard(
        title = "区域 5 Boss",
        options = state.area5BossOptions,
        selectedIds = state.selectedArea5BossIds,
        enabled = !state.isWorking,
        onToggle = onArea5BossToggle,
    )
    CheckOption(
        label = "刷到出（持续刷新直到目标路线）",
        checked = state.rerollUntilFound,
        enabled = !state.isWorking,
        onCheckedChange = onRerollUntilFoundChange,
    )
    Text(
        "开启后忽略最大尝试次数。同一非目标开局撤退后若短暂残留，会退避并最多重试撤退 3 次；状态无法确认、出现不同开局或连续撤退失败时仍会安全停止。",
        style = MaterialTheme.typography.bodySmall,
    )
    NumericField(
        label = "最大尝试次数（1-${LabyrinthRerollOptions.MAX_ATTEMPTS}）",
        value = state.maxAttempts,
        enabled = !state.isWorking && !state.rerollUntilFound,
        onValueChange = onMaxAttemptsChange,
    )
    CheckOption(
        label = "允许彻底撤退当前开局（开始前确认）",
        checked = state.retireExisting,
        enabled = !state.isWorking,
        onCheckedChange = onRetireExistingChange,
    )
    Text(
        "进入请求发出后若连接中断，不会再次进入或撤退；应用会保留待验证检查点，重新登录后读取服务端现有开局再判定。",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun CurrentOpeningStep(
    state: LabyrinthUiState,
    externalTaskActive: Boolean,
    onCheckStatus: () -> Unit,
    onStop: () -> Unit,
) {
    val verdict = state.routeVerdict
    val readStatus = state.currentOpeningReadStatus
    val accountLabel = state.selectedAccount?.let { account ->
        "${account.alias}${account.gameUid?.let { " · UID $it" }.orEmpty()}"
    } ?: "尚未选择账号"
    val status = when {
        state.selectedAccount == null -> "请先到账号库选择账号"
        readStatus == LabyrinthCurrentOpeningReadStatus.NOT_READ -> "尚未读取当前开局"
        readStatus == LabyrinthCurrentOpeningReadStatus.READING -> "正在登录并读取当前开局"
        readStatus == LabyrinthCurrentOpeningReadStatus.LOGIN_VERIFICATION_REQUIRED -> "登录需要验证"
        readStatus == LabyrinthCurrentOpeningReadStatus.NO_ACTIVE_OPENING -> "读取完成：当前没有进行中的黎明界"
        readStatus == LabyrinthCurrentOpeningReadStatus.TARGET -> "当前路线和难度符合要求（仅用于状态确认）"
        readStatus == LabyrinthCurrentOpeningReadStatus.NOT_TARGET -> "当前开局不符合已保存的路线或难度条件"
        readStatus == LabyrinthCurrentOpeningReadStatus.PENDING_VERIFICATION -> "读取未完成，需要重新验证"
        readStatus == LabyrinthCurrentOpeningReadStatus.FAILED -> "读取失败"
        else -> "读取已取消"
    }
    WorkflowStepHeader(
        number = "1",
        title = "登录账号并读取当前开局",
        summary = "使用账号库凭据登录游戏服，读取并验证现有路线。",
    )
    Text(accountLabel, style = MaterialTheme.typography.titleSmall)
    Text(
        status,
        style = MaterialTheme.typography.bodyMedium,
        color = if (readStatus == LabyrinthCurrentOpeningReadStatus.TARGET) {
            MaterialTheme.colorScheme.primary
        } else if (readStatus == LabyrinthCurrentOpeningReadStatus.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    state.currentOpeningReadMessage?.let { detail ->
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (readStatus == LabyrinthCurrentOpeningReadStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
    val readInProgress = state.isWorking && readStatus == LabyrinthCurrentOpeningReadStatus.READING
    ChoiceRow {
        Button(onClick = onCheckStatus, enabled = !state.isWorking && !externalTaskActive) {
            Text(
                if (readStatus in setOf(
                        LabyrinthCurrentOpeningReadStatus.PENDING_VERIFICATION,
                        LabyrinthCurrentOpeningReadStatus.FAILED,
                        LabyrinthCurrentOpeningReadStatus.CANCELLED,
                    )
                ) {
                    "重新验证"
                } else if (readStatus == LabyrinthCurrentOpeningReadStatus.NOT_READ) {
                    "登录并读取"
                } else {
                    "重新读取"
                },
            )
        }
        if (readInProgress && !externalTaskActive) Button(onClick = onStop) { Text("停止") }
    }
    if (externalTaskActive) {
        Text("其他黎明界任务运行中，结束后才能重新读取。", style = MaterialTheme.typography.bodySmall)
    }
    if (verdict != null || state.currentGuildId != null) {
        val currentGuildName = state.currentGuildId?.let { guildId ->
            state.guildOptions.firstOrNull { it.guildId == guildId }?.name ?: "ID $guildId"
        }
        Text("判定：${verdict?.label ?: "尚未记录"}", style = MaterialTheme.typography.bodySmall)
        Text(
            "${currentGuildName ?: "未读取公会"} · 难度 ${state.currentDifficulty ?: "-"}" +
                (state.checkpointEnterId?.let { " · Enter ID 尾号 ${it % 10_000}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        state.verdictMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (verdict == LabyrinthRouteVerdict.PENDING_VERIFICATION) {
            Text(
                "状态未确认前不会自动重试进入或撤退。恢复网络后点“重新验证”。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    state.routeBlockIds.takeIf { it.isNotEmpty() }?.let {
        Text("已保存 ${it.size} 个路线节点", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AuxiliaryToolsSection(
    state: LabyrinthUiState,
    entryRecognitionState: LabyrinthEntryRecognitionSessionState,
    batchActive: Boolean,
    standaloneGuildId: Int,
    onStandaloneGuildSelected: (Int) -> Unit,
    onStartReroll: () -> Unit,
    onStopReroll: () -> Unit,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isWorking) {
        while (state.isWorking) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }
    val standaloneWorking = state.isWorking && !batchActive && !entryRecognitionState.running
    val standaloneEnabled = state.selectedAccount != null &&
        state.settingsReady &&
        state.captcha == null &&
        !batchActive &&
        !entryRecognitionState.running
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("辅助工具", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "这些操作不属于日常两步流程，用于单独准备开局或诊断识别。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("单独刷开局 · 本次公会", style = MaterialTheme.typography.labelLarge)
        ChoiceRow {
            state.guildOptions.take(5).forEach { guild ->
                FilterChip(
                    selected = standaloneGuildId == guild.guildId,
                    onClick = { onStandaloneGuildSelected(guild.guildId) },
                    label = { Text(guild.name) },
                    enabled = !state.isWorking && !batchActive && !entryRecognitionState.running,
                )
            }
        }
        Text(
            "只作用于这一次单独刷取，不保存到账号设置，也不会影响读取当前开局或批量目标。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ResponsiveActionLine(
            title = "单独刷开局",
            description = "使用上方本次公会和右上角保存的路线条件准备开局。",
        ) {
            Button(
                onClick = if (standaloneWorking) onStopReroll else onStartReroll,
                enabled = standaloneWorking || (!state.isWorking && standaloneEnabled),
            ) { Text(if (standaloneWorking) "停止" else "开始") }
        }
        if (standaloneWorking) {
            Text(labyrinthRerollStatusText(state, now), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onToggleAdvanced) {
            Text(if (showAdvanced) "收起识别工具" else "展开识别工具")
        }
    }
}

@Composable
private fun EntryRecognitionCard(
    state: LabyrinthEntryRecognitionSessionState,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onStartAutomation: () -> Unit,
    onStop: () -> Unit,
) {
    SelectionCard(
        "半自动路线执行",
        "程序负责进入黎明界、按保存路线点节点并推进结算；角色、战斗编组与遗物由你选择",
    ) {
        val result = state.lastResult
        if (result == null) {
            Text("等待识别帧", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(result.observation.state.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "置信度 ${"%.3f".format(result.observation.confidence)} · ${result.elapsedMillis} ms · 第 ${state.frameCount} 帧",
                style = MaterialTheme.typography.bodySmall,
            )
            if (result.nodeClassifications.isNotEmpty()) {
                Text(
                    result.nodeClassifications
                        .groupingBy { it.blockType }
                        .eachCount()
                        .entries
                        .sortedBy { it.key }
                        .joinToString("、") { (type, count) ->
                            "${LabyrinthNodeTypes.labelOf(type)}×$count"
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            result.battleTeamSelection?.let { team ->
                Text(
                    "编组筛选：${team.currentFilter.label} · 可见角色 ${team.visibleCharacters.size} · " +
                        "已识别 ${team.recognizedCharacterCount} · " +
                        if (team.scrollbar.canScroll) "需要时可滚动" else "滚动条未测得，仍可滑动",
                    style = MaterialTheme.typography.bodySmall,
                )
                team.selectedCharacters
                    .mapNotNull { it.displayName }
                    .takeIf(List<String>::isNotEmpty)
                    ?.let { Text("当前队伍：${it.joinToString("、")}", style = MaterialTheme.typography.bodySmall) }
            }
            if (result.matchedFeatures.isNotEmpty()) {
                Text(result.matchedFeatures.take(4).joinToString(), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.joinedCharacters.isNotEmpty()) {
            Text(
                "已记录队伍：" + state.joinedCharacters.joinToString("、") { it.displayName },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!state.dryRun || state.actionCount > 0) {
            Text(
                "已执行 ${state.actionCount} 次${state.lastActionLabel?.let { " · $it" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.running) {
            Button(onClick = onStop) { Text(if (state.dryRun) "停止识别" else "停止入口流程") }
        } else {
            ChoiceRow {
                TextButton(onClick = onStart, enabled = startEnabled) { Text("只读识别") }
                Button(onClick = onStartAutomation, enabled = startEnabled) { Text("执行入口流程") }
            }
        }
    }
}

@Composable
private fun BatchRunCard(
    state: LabyrinthUiState,
    checkpoint: LabyrinthBatchCheckpoint?,
    haltReason: LabyrinthBatchHaltReason?,
    routeProgress: LabyrinthRouteProgress?,
    entryRecognitionRunning: Boolean,
    onStart: (List<LabyrinthBatchGoal>) -> Unit,
    onStop: () -> Unit,
) {
    val guildOptions = state.guildOptions.take(5)
    val selectedDifficulty = state.selectedDifficulty
    val enabled = state.selectedAccount != null &&
        state.settingsReady &&
        !state.isWorking &&
        state.captcha == null &&
        !entryRecognitionRunning
    val active = checkpoint?.stage in setOf(
        LabyrinthBatchStage.REROLLING,
        LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION,
        LabyrinthBatchStage.RUNNING_LABYRINTH,
        LabyrinthBatchStage.RECORDING_RESULT,
    )
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        WorkflowStepHeader(
            number = "2",
            title = "自动执行",
            summary = "设置目标后连续执行；每轮均按批量目标准备新开局。",
        )
        Text(
            "难度 $selectedDifficulty · 后续轮次自动刷开局、重置客户端会话、执行并记录结果。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "下方批量目标是批量运行的唯一公会来源，与单独刷开局的临时选择无关。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        // Per-guild target text and mode; the guild list itself is fixed (top five).
        val counts = remember(guildOptions) {
            mutableStateOf(guildOptions.associate { it.guildId to "" })
        }
        val modes = remember(guildOptions) {
            mutableStateOf(guildOptions.associate { it.guildId to LabyrinthBatchGoalMode.CLEARS })
        }

        checkpoint?.let { cp ->
            Text(
                "批次 ${cp.batchId} · 阶段 ${cp.stage.name}" +
                    (cp.currentRunId?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            cp.goals.forEachIndexed { index, goal ->
                val name = guildOptions.firstOrNull { it.guildId == goal.guildId }?.name ?: "ID ${goal.guildId}"
                val marker = when {
                    index == cp.activeGoalIndex && cp.stage != LabyrinthBatchStage.COMPLETED -> "▶ "
                    goal.isSatisfied -> "✓ "
                    else -> "· "
                }
                val modeLabel = if (goal.mode == LabyrinthBatchGoalMode.CLEARS) "通关" else "开局"
                Text(
                    "$marker$name：$modeLabel ${goal.completedCount}/${goal.targetCount}" +
                        (if (goal.failedCount > 0) " · 放弃 ${goal.failedCount}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (cp.abnormalRuns > 0) {
                Text("异常中止 ${cp.abnormalRuns} 局（未计入）", style = MaterialTheme.typography.bodySmall)
            }
            cp.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            haltReason?.let {
                Text("停止原因：${it.name}", style = MaterialTheme.typography.bodySmall)
            }
        }
        routeProgress?.let { route ->
            Text(
                "本轮路线：区域${route.currentArea} · 已走 ${route.visitedCount}/${route.routeNodeCount}" +
                    (route.nextNodeLabel?.let { " · 下一节点 $it" } ?: "") +
                    if (route.complete) " · 已完成" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (active) {
            Button(onClick = onStop) { Text("停止批量执行") }
        } else {
            Text("执行目标（留空 = 跳过该公会）", style = MaterialTheme.typography.titleSmall)
            @Composable
            fun GoalEditor(guild: LabyrinthGuildOption, modifier: Modifier = Modifier) {
                val text = counts.value[guild.guildId].orEmpty()
                val mode = modes.value[guild.guildId] ?: LabyrinthBatchGoalMode.CLEARS
                BoxWithConstraints(modifier = modifier) {
                    val compact = maxWidth < 480.dp
                    @Composable fun CountField(modifier: Modifier) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { value ->
                                counts.value = counts.value + (guild.guildId to value.filter(Char::isDigit).take(2))
                            },
                            label = { Text(guild.name) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = modifier,
                        )
                    }
                    @Composable fun ModeChip() {
                        FilterChip(
                            selected = mode == LabyrinthBatchGoalMode.CLEARS,
                            onClick = {
                                modes.value = modes.value + (
                                    guild.guildId to if (mode == LabyrinthBatchGoalMode.CLEARS) {
                                        LabyrinthBatchGoalMode.ATTEMPTS
                                    } else {
                                        LabyrinthBatchGoalMode.CLEARS
                                    }
                                    )
                            },
                            label = { Text(if (mode == LabyrinthBatchGoalMode.CLEARS) "按通关计" else "按开局计") },
                        )
                    }
                    if (compact) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CountField(Modifier.fillMaxWidth())
                            ModeChip()
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CountField(Modifier.weight(1f))
                            ModeChip()
                        }
                    }
                }
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth >= 720.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        guildOptions.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                pair.forEach { guild -> GoalEditor(guild, Modifier.weight(1f)) }
                                if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        guildOptions.forEach { guild -> GoalEditor(guild, Modifier.fillMaxWidth()) }
                    }
                }
            }
            val goals = guildOptions.mapNotNull { guild ->
                counts.value[guild.guildId]?.toIntOrNull()?.takeIf { it >= 1 }?.let { count ->
                    LabyrinthBatchGoal(
                        guildId = guild.guildId,
                        targetCount = count,
                        mode = modes.value[guild.guildId] ?: LabyrinthBatchGoalMode.CLEARS,
                    )
                }
            }
            if (goals.isNotEmpty()) {
                Text(
                    "启动后将按首个批量目标公会及已保存的难度、路线要求准备新开局。读取当前开局不会跳过刷取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val total = goals.sumOf { it.targetCount }
            Button(
                onClick = { onStart(goals) },
                enabled = enabled && goals.isNotEmpty(),
            ) {
                Text(
                    when {
                        goals.isEmpty() -> "开始自动执行"
                        else -> "开始自动执行（${goals.size} 个公会 · 共 $total 轮）"
                    },
                )
            }
            if (!enabled) {
                Text("需要已选账号且刷开局设置就绪、无进行中的刷取任务。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SelectionCard(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            content()
        }
    }
}

@Composable
private fun ChoiceRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Keeps descriptive text and its action usable in narrow emulator and split-screen windows. */
@Composable
private fun ResponsiveActionLine(
    title: String,
    description: String,
    action: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val text: @Composable () -> Unit = {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (maxWidth < 480.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                text()
                action()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) { text() }
                action()
            }
        }
    }
}

@Composable
private fun BossSelectionCard(
    title: String,
    options: List<LabyrinthBossOption>,
    selectedIds: Set<Int>,
    enabled: Boolean,
    onToggle: (Int) -> Unit,
) {
    SelectionCard(title, "可多选；不选择任何 Boss 表示都可以。") {
        options.forEach { boss ->
            CheckOption(
                label = "【${boss.difficulty.label}】${boss.name}",
                checked = boss.unitId in selectedIds,
                enabled = enabled,
                onCheckedChange = { onToggle(boss.unitId) },
            )
        }
    }
}

@Composable
private fun CheckOption(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun NumericField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { changed -> if (changed.all(Char::isDigit)) onValueChange(changed) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
