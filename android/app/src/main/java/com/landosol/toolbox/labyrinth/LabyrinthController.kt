package com.landosol.toolbox.labyrinth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import com.landosol.toolbox.account.AccountListItem
import com.landosol.toolbox.account.AccountRepository
import com.landosol.toolbox.account.AccountCaptchaState
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.protocol.bilibili.BilibiliGameSession
import com.landosol.toolbox.protocol.bilibili.BilibiliNativeLoginCoordinator
import com.landosol.toolbox.protocol.bilibili.CaptchaProof
import com.landosol.toolbox.protocol.bilibili.GameSessionRegistry
import com.landosol.toolbox.protocol.bilibili.NativeLoginResult
import com.landosol.toolbox.protocol.labyrinth.BilibiliLabyrinthApi
import com.landosol.toolbox.protocol.labyrinth.LabyrinthFailureKind
import com.landosol.toolbox.protocol.labyrinth.LabyrinthOperationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LabyrinthCurrentOpeningReadStatus {
    NOT_READ,
    READING,
    LOGIN_VERIFICATION_REQUIRED,
    NO_ACTIVE_OPENING,
    TARGET,
    NOT_TARGET,
    PENDING_VERIFICATION,
    FAILED,
    CANCELLED,
}

data class LabyrinthUiState(
    val selectedAccount: AccountListItem? = null,
    val guildOptions: List<LabyrinthGuildOption> = LabyrinthRerollOptions.guilds,
    val selectedGuildId: Int = LabyrinthRerollOptions.DEFAULT_GUILD_ID,
    val availableDifficulties: List<Int> = (1..LabyrinthRerollOptions.MAX_DIFFICULTY).toList(),
    val selectedDifficulty: Int = LabyrinthRerollOptions.DEFAULT_DIFFICULTY,
    val perfectStart: Boolean = LabyrinthRerollOptions.DEFAULT_PERFECT_START,
    val routeEvaluationMode: LabyrinthRouteEvaluationMode = LabyrinthRerollOptions.DEFAULT_ROUTE_EVALUATION_MODE,
    val valueAllowance: Int = LabyrinthRerollOptions.DEFAULT_VALUE_ALLOWANCE,
    val thirdBlockChoice: LabyrinthThirdBlockChoice = LabyrinthRerollOptions.defaultThirdBlockChoice,
    val area3BossOptions: List<LabyrinthBossOption> = LabyrinthRerollOptions.area3Bosses,
    val selectedArea3BossIds: Set<Int> = LabyrinthRerollOptions.defaultArea3BossIds,
    val area5BossOptions: List<LabyrinthBossOption> = LabyrinthRerollOptions.area5Bosses,
    val selectedArea5BossIds: Set<Int> = LabyrinthRerollOptions.defaultArea5BossIds,
    val maxAttempts: String = "100",
    val rerollUntilFound: Boolean = true,
    val retireExisting: Boolean = true,
    val isWorking: Boolean = false,
    val progress: String? = null,
    val startedAtMillis: Long? = null,
    val settingsReady: Boolean = false,
    val routeBlockIds: List<Long> = emptyList(),
    val routeVerdict: LabyrinthRouteVerdict? = null,
    val verdictMessage: String? = null,
    val checkpointEnterId: Long? = null,
    val currentGuildId: Int? = null,
    val currentDifficulty: Int? = null,
    val currentOpeningReadStatus: LabyrinthCurrentOpeningReadStatus = LabyrinthCurrentOpeningReadStatus.NOT_READ,
    val currentOpeningReadMessage: String? = null,
    val captcha: AccountCaptchaState? = null,
    val message: String? = null,
)

private data class LabyrinthChromeState(
    val selectedGuildId: Int = LabyrinthRerollOptions.DEFAULT_GUILD_ID,
    val selectedDifficulty: Int = LabyrinthRerollOptions.DEFAULT_DIFFICULTY,
    val maxUnlockedDifficulty: Int? = null,
    val perfectStart: Boolean = LabyrinthRerollOptions.DEFAULT_PERFECT_START,
    val routeEvaluationMode: LabyrinthRouteEvaluationMode = LabyrinthRerollOptions.DEFAULT_ROUTE_EVALUATION_MODE,
    val valueAllowance: Int = LabyrinthRerollOptions.DEFAULT_VALUE_ALLOWANCE,
    val thirdBlockChoice: LabyrinthThirdBlockChoice = LabyrinthRerollOptions.defaultThirdBlockChoice,
    val selectedArea3BossIds: Set<Int> = LabyrinthRerollOptions.defaultArea3BossIds,
    val selectedArea5BossIds: Set<Int> = LabyrinthRerollOptions.defaultArea5BossIds,
    val maxAttempts: String = "100",
    val rerollUntilFound: Boolean = true,
    val retireExisting: Boolean = true,
    val isWorking: Boolean = false,
    val progress: String? = null,
    val startedAtMillis: Long? = null,
    val settingsReady: Boolean = false,
    val routeBlockIds: List<Long> = emptyList(),
    val routeVerdict: LabyrinthRouteVerdict? = null,
    val verdictMessage: String? = null,
    val checkpointEnterId: Long? = null,
    val currentGuildId: Int? = null,
    val currentDifficulty: Int? = null,
    val currentOpeningReadStatus: LabyrinthCurrentOpeningReadStatus = LabyrinthCurrentOpeningReadStatus.NOT_READ,
    val currentOpeningReadMessage: String? = null,
    val captcha: AccountCaptchaState? = null,
    val message: String? = null,
)

private enum class PendingLoginAction {
    START,
    CHECK_STATUS,
}

/**
 * Criteria used by the explicit "读取当前开局/重新验证" action.
 *
 * The selected controls are the user's current target. A checkpoint describes the result of an
 * older verification and must not silently replace newly selected guild/difficulty/route rules.
 * Only its attempt counter is safe to retain when it belongs to the same server run.
 */
internal data class LabyrinthCurrentOpeningCriteria(
    val difficulty: Int,
    val routePolicy: LabyrinthRoutePolicy,
    val attempt: Int,
)

/**
 * The game client's own login (返回标题 → 登录, every batch cycle) kicks the app's API session;
 * the next read answers "连接中断。回到标题界面。" as a REJECTED failure. top() is read-only, so a
 * fresh login and a retry is always safe; only a rejection that survives the retries is real.
 * 2026-09-17 user: 「读取当前开局」有时显示该提示并停在待验证状态。
 */
internal fun labyrinthReadRetriesWithFreshLogin(
    kind: LabyrinthFailureKind,
    sessionResets: Int,
    maxSessionResets: Int,
): Boolean = kind == LabyrinthFailureKind.REJECTED && sessionResets < maxSessionResets

internal fun labyrinthCurrentOpeningCriteria(
    selectedDifficulty: Int,
    selectedRoutePolicy: LabyrinthRoutePolicy,
    savedCheckpoint: LabyrinthRerollCheckpoint?,
    currentEnterId: Long?,
): LabyrinthCurrentOpeningCriteria = LabyrinthCurrentOpeningCriteria(
    difficulty = selectedDifficulty,
    routePolicy = selectedRoutePolicy,
    attempt = savedCheckpoint
        ?.takeIf { checkpoint -> checkpoint.enterId == currentEnterId }
        ?.attempt
        ?: 0,
)

/**
 * Revalidates the exact server snapshot immediately before a batch consumes an opening.
 *
 * Only an unadvanced opening is reusable. Once [currentBlockId][com.landosol.toolbox.protocol.labyrinth.LabyrinthResume.currentBlockId]
 * is present, the server no longer exposes enough state to reconstruct the acquired characters
 * and relics, so the batch must retire/reroll instead of pretending it can resume safely.
 */
internal fun labyrinthReusableOpeningSnapshotCheck(
    opening: com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpening,
    top: com.landosol.toolbox.protocol.labyrinth.LabyrinthTop,
    resume: com.landosol.toolbox.protocol.labyrinth.LabyrinthResume,
    route: LabyrinthRouteJson?,
): com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpeningCheck {
    val staleReason = when {
        top.enterId != opening.enterId || resume.enterId != opening.enterId -> "服务端当前 Enter ID 已变化"
        (resume.guildId ?: top.guildId) != opening.guildId -> "服务端当前公会已变化"
        top.difficulty != opening.difficulty -> "服务端当前难度已变化"
        resume.currentBlockId != null -> "当前挑战已经推进，无法恢复已获得角色和遗物"
        resume.map.isEmpty() -> "服务端未返回完整地图"
        route == null || route.enterId != opening.enterId -> "本机没有该开局的已保存路线"
        route.blockIds.isEmpty() || !resume.map.mapTo(hashSetOf()) { it.blockId }.containsAll(route.blockIds) ->
            "服务端地图与本机保存路线不一致"
        else -> null
    }
    return if (staleReason == null) {
        com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpeningCheck.Valid
    } else {
        com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpeningCheck.Stale(staleReason)
    }
}

/**
 * Batch targets own the guild selection. Saved reroll settings still supply route quality,
 * difficulty defaults and attempt limits, but their guild must never redirect a batch goal.
 */
internal fun labyrinthBatchRerollConfig(
    base: LabyrinthRerollConfig,
    batchGuildId: Int,
    batchDifficulty: Int,
): LabyrinthRerollConfig = base.copy(
    guildId = batchGuildId,
    difficulty = batchDifficulty,
    retireExisting = true,
    abandonExisting = true,
)

/**
 * The guild chosen beside "单独刷开局" belongs only to that launch. It deliberately replaces
 * the legacy persisted guild in the frozen config without writing the choice back to settings.
 */
internal fun labyrinthStandaloneRerollConfig(
    base: LabyrinthRerollConfig,
    launchGuildId: Int,
): LabyrinthRerollConfig = base.copy(guildId = launchGuildId)

class LabyrinthController(
    private val accountRepository: AccountRepository,
    private val sessionRegistry: GameSessionRegistry,
    private val database: AppDatabase,
    private val loginCoordinatorProvider: () -> BilibiliNativeLoginCoordinator,
    private val settingsStore: LabyrinthRerollSettingsStore,
    private val launchForeground: () -> Unit,
    /**
     * Runs before a reroll logs in to the game server. Returns a failure message to abort the
     * reroll, or null to go on. The application uses it to hard-close channel clients whose SDK
     * logs out when the client is taken over (ChannelSdkTraits.closesClientBeforeReroll).
     */
    private val beforeRerollLogin: suspend () -> String? = { null },
    /** Runs after a standalone (non-batch) reroll ends; the batch cold-starts the client itself. */
    private val afterStandaloneReroll: suspend () -> Unit = {},
) {
    // 缺少国服游戏包时，首页构造本 Controller 不应触发 BilibiliSDK 的包查询。
    // 协调器改为按需解析：仅在登录/验证码等真实操作走到时才求值。
    private val loginCoordinator by lazy { loginCoordinatorProvider() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var settingsAccountId: Long? = null
    private var frozenStart: Pair<AccountListItem, LabyrinthRerollConfig>? = null
    private var startRequested = false
    private var cancellationMessage: String? = null
    val hasActiveReroll: Boolean get() = chrome.value.isWorking && frozenStart != null

    private val chrome = MutableStateFlow(LabyrinthChromeState())
    private var runningJob: Job? = null
    private var pendingLoginAction: PendingLoginAction? = null
    @Volatile
    private var explicitlyReadInitialOpening:
        com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpening? = null

    init {
        scope.launch {
            accountRepository.observeAccounts().map { accounts ->
                accounts.firstOrNull(AccountListItem::isSelected)?.id
            }.distinctUntilChanged().collect { selectedAccountId ->
                if (selectedAccountId != settingsAccountId) {
                    runningJob?.cancelAndJoin()
                    chrome.value.captcha?.let { loginCoordinator.cancel(it.accountId) }
                    frozenStart = null
                    startRequested = false
                    pendingLoginAction = null
                    explicitlyReadInitialOpening = null
                    settingsAccountId = selectedAccountId
                    chrome.value = LabyrinthChromeState()
                    if (selectedAccountId != null) applySettings(settingsStore.load(selectedAccountId))
                }
                if (selectedAccountId == null) {
                    chrome.update {
                        it.copy(routeVerdict = null, verdictMessage = null, checkpointEnterId = null)
                    }
                } else {
                    refreshCheckpoint(selectedAccountId)
                }
            }
        }
    }

    val uiState: StateFlow<LabyrinthUiState> = combine(
        accountRepository.observeAccounts(),
        chrome,
    ) { accounts, current ->
        LabyrinthUiState(
            selectedAccount = accounts.firstOrNull { it.isSelected && it.id == settingsAccountId },
            selectedGuildId = current.selectedGuildId,
            availableDifficulties = (1..(
                current.maxUnlockedDifficulty ?: LabyrinthRerollOptions.MAX_DIFFICULTY
                )).toList(),
            selectedDifficulty = current.selectedDifficulty,
            perfectStart = current.perfectStart,
            routeEvaluationMode = current.routeEvaluationMode,
            valueAllowance = current.valueAllowance,
            thirdBlockChoice = current.thirdBlockChoice,
            selectedArea3BossIds = current.selectedArea3BossIds,
            selectedArea5BossIds = current.selectedArea5BossIds,
            maxAttempts = current.maxAttempts,
            rerollUntilFound = current.rerollUntilFound,
            retireExisting = current.retireExisting,
            isWorking = current.isWorking,
            progress = current.progress,
            startedAtMillis = current.startedAtMillis,
            settingsReady = current.settingsReady,
            routeBlockIds = current.routeBlockIds,
            routeVerdict = current.routeVerdict,
            verdictMessage = current.verdictMessage,
            checkpointEnterId = current.checkpointEnterId,
            currentGuildId = current.currentGuildId,
            currentDifficulty = current.currentDifficulty,
            currentOpeningReadStatus = current.currentOpeningReadStatus,
            currentOpeningReadMessage = current.currentOpeningReadMessage,
            captcha = current.captcha,
            message = current.message,
        )
    }.stateIn(scope, SharingStarted.Eagerly, LabyrinthUiState())

    fun selectGuild(guildId: Int) = updateConfig {
        if (LabyrinthRerollOptions.guilds.none { it.guildId == guildId }) this else copy(selectedGuildId = guildId)
    }

    fun selectDifficulty(difficulty: Int) = updateConfig {
        val maxUnlocked = maxUnlockedDifficulty ?: LabyrinthRerollOptions.MAX_DIFFICULTY
        if (difficulty !in 1..maxUnlocked) this else copy(selectedDifficulty = difficulty)
    }

    fun setPerfectStart(value: Boolean) = updateConfig { copy(perfectStart = value) }

    fun selectRouteEvaluationMode(value: LabyrinthRouteEvaluationMode) = updateConfig {
        copy(routeEvaluationMode = value)
    }

    fun setValueAllowance(value: Int) = updateConfig {
        if (value !in 0..LabyrinthRerollOptions.MAX_VALUE_ALLOWANCE) this else copy(valueAllowance = value)
    }

    fun selectThirdBlockChoice(value: LabyrinthThirdBlockChoice) = updateConfig {
        copy(thirdBlockChoice = value)
    }

    fun toggleArea3Boss(unitId: Int) = updateConfig {
        if (LabyrinthRerollOptions.area3Bosses.none { it.unitId == unitId }) this
        else copy(selectedArea3BossIds = selectedArea3BossIds.toggle(unitId))
    }

    fun toggleArea5Boss(unitId: Int) = updateConfig {
        if (LabyrinthRerollOptions.area5Bosses.none { it.unitId == unitId }) this
        else copy(selectedArea5BossIds = selectedArea5BossIds.toggle(unitId))
    }

    fun setMaxAttempts(value: String) = updateNumeric { copy(maxAttempts = value) }

    fun setRerollUntilFound(value: Boolean) = updateConfig { copy(rerollUntilFound = value) }

    fun setRetireExisting(value: Boolean) {
        updateConfig { copy(retireExisting = value) }
    }

    fun start(retreatConfirmed: Boolean = false, guildIdOverride: Int? = null) {
        if (chrome.value.isWorking || chrome.value.captcha != null || !chrome.value.settingsReady) return
        val account = uiState.value.selectedAccount
        if (account == null) {
            chrome.update { it.copy(message = "请先在账号库选择一个账号") }
            return
        }
        if (account.id != settingsAccountId) return
        explicitlyReadInitialOpening = null
        val base = parseConfig(account.id) ?: return
        val launchGuildId = guildIdOverride ?: base.guildId
        if (LabyrinthRerollOptions.guilds.none { it.guildId == launchGuildId }) {
            reportMessage("请选择有效的开局公会")
            return
        }
        val config = labyrinthStandaloneRerollConfig(base, launchGuildId)
        if (config.retireExisting && !retreatConfirmed) {
            reportMessage("开始前请确认允许彻底撤退现有开局")
            return
        }
        invalidateExplicitOpeningRead("单独刷开局即将改变服务端开局；完成后请重新读取")
        frozenStart = account to config
        chrome.update { it.copy(startedAtMillis = System.currentTimeMillis()) }
        requestFrozenStart()
    }

    /**
     * Internal handoff from live automation after three failed battle attempts. The strategy
     * itself is the user's authorization to abandon this run, so force retireExisting for this
     * one frozen task while preserving all other saved reroll criteria (v2/allowance/Boss/etc.).
     */
    fun startAfterBattleFailureReroll(accountId: Long): Boolean {
        if (chrome.value.isWorking || chrome.value.captcha != null || !chrome.value.settingsReady) {
            reportMessage("战斗失败后无法自动重刷：刷开局任务尚未就绪或已有任务运行")
            return false
        }
        val account = uiState.value.selectedAccount
        if (account == null || account.id != accountId || account.id != settingsAccountId) {
            reportMessage("战斗失败后无法自动重刷：当前账号已变化")
            return false
        }
        val config = parseConfig(account.id) ?: return false
        frozenStart = account to config.copy(retireExisting = true, abandonExisting = true)
        chrome.update {
            it.copy(
                startedAtMillis = System.currentTimeMillis(),
                message = "连续3次战斗失败，正在撤退当前开局并重新刷取",
            )
        }
        requestFrozenStart()
        return startRequested || chrome.value.isWorking
    }

    private fun requestFrozenStart() {
        if (frozenStart == null || chrome.value.isWorking) return
        startRequested = true
        chrome.update { it.copy(isWorking = true, progress = "正在启动后台刷取服务", message = null) }
        runCatching { launchForeground() }.onFailure {
            startRequested = false
            frozenStart = null
            chrome.update { state -> state.copy(isWorking = false, progress = null,
                message = "无法启动后台服务，请返回前台检查通知权限后重试：${it.message}") }
        }
    }

    /**
     * Batch-owned reroll: runs the same network workflow as the service path, awaits it, and
     * returns the enterId. The batch supplies guild/difficulty; every other saved criterion
     * (perfect start, allowance, Boss ids) comes from the account's saved reroll settings.
     * retireExisting is forced because the batch has already recorded the previous run.
     *
     * Not started via the foreground service: the batch runs inside the capture foreground
     * service's lifetime, which already keeps the process alive.
     */
    suspend fun rerollForBatch(
        accountId: Long,
        guildId: Int,
        difficulty: Int,
    ): com.landosol.toolbox.labyrinth.batch.LabyrinthBatchRerollResult {
        val failure = { message: String -> com.landosol.toolbox.labyrinth.batch.LabyrinthBatchRerollResult.Failure(message) }
        if (chrome.value.isWorking || chrome.value.captcha != null || !chrome.value.settingsReady) {
            return failure("刷开局任务尚未就绪或已有任务运行")
        }
        val account = uiState.value.selectedAccount
        if (account == null || account.id != accountId || account.id != settingsAccountId) {
            return failure("当前账号已变化")
        }
        val base = parseConfig(account.id) ?: return failure(chrome.value.message ?: "刷开局配置无效")
        // The batch has already recorded the previous run (cleared or lost); whatever the server
        // still holds is that run and must be retired, never resumed as "a matching opening".
        val config = labyrinthBatchRerollConfig(base, guildId, difficulty)
        chrome.update { it.copy(isWorking = true, message = null, progress = "批量：正在刷取 $guildId", routeBlockIds = emptyList()) }
        try {
            beforeRerollLogin()?.let { reason -> return failure(reason) }
            var sessionResets = 0
            while (true) {
                val session = ensureGameSession(account, PendingLoginAction.START)
                    ?: return failure(chrome.value.message ?: "无法登录游戏服")
                val workflow = LabyrinthRerollWorkflow(
                    api = BilibiliLabyrinthApi(session),
                    routeStore = RoomLabyrinthRouteStore(database),
                    checkpointStore = RoomLabyrinthRerollCheckpointStore(database),
                )
                val result = workflow.run(config) { progress ->
                    chrome.update {
                        val limit = if (progress.rerollUntilFound) "不限" else progress.maxAttempts.toString()
                        it.copy(progress = "批量 ${progress.attempt}/$limit · ${progress.stage}")
                    }
                }
                when (result) {
                    is LabyrinthRerollResult.Success -> {
                        chrome.update {
                            it.copy(
                                routeBlockIds = result.route.blockIds,
                                currentGuildId = config.guildId,
                                currentDifficulty = config.difficulty,
                                message = "批量：第 ${result.attempt} 次刷到目标路线",
                            )
                        }
                        return com.landosol.toolbox.labyrinth.batch.LabyrinthBatchRerollResult.Success(result.route.enterId)
                    }
                    is LabyrinthRerollResult.NeedsExistingRunDecision ->
                        return failure("检测到已有黎明界开局但未被撤退")
                    is LabyrinthRerollResult.Exhausted ->
                        return failure("已完成 ${result.attempts} 次尝试，未找到目标路线")
                    is LabyrinthRerollResult.Failure -> {
                        if (result.kind != LabyrinthFailureKind.REJECTED || sessionResets >= MAX_SESSION_RESETS) {
                            return failure(result.message)
                        }
                        sessionResets += 1
                        sessionRegistry.delete(account.id)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return failure(error.message.orEmpty().ifBlank { "黎明界刷取失败" }.take(200))
        } finally {
            withContext(NonCancellable) { runCatching { refreshCheckpoint(account.id) } }
            chrome.update { it.copy(isWorking = false, progress = null) }
        }
    }

    /**
     * Consumes the last explicit read exactly once at the external batch-start boundary.
     *
     * A mismatch also consumes the candidate: changing goals and tapping start must not leave a
     * stale authorization available for a later batch.
     */
    @Synchronized
    fun consumeReusableOpeningForBatch(
        expectedGuildId: Int,
        expectedDifficulty: Int,
    ): com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpening? {
        val candidate = explicitlyReadInitialOpening
        explicitlyReadInitialOpening = null
        return candidate?.takeIf { opening ->
            opening.guildId == expectedGuildId && opening.difficulty == expectedDifficulty
        }
    }

    /** Performs a fresh top + resume comparison before skipping the first reroll. */
    suspend fun verifyReusableOpeningForBatch(
        accountId: Long,
        opening: com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpening,
    ): com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpeningCheck {
        val account = uiState.value.selectedAccount
        if (account == null || account.id != accountId || account.id != settingsAccountId) {
            return com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpeningCheck.Failure("当前账号已变化")
        }
        // The explicit read that produced this opening already took the client's session; close
        // the client before it can meet 6002, so the batch cold-starts it instead.
        beforeRerollLogin()?.let { reason ->
            return com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpeningCheck.Failure(reason)
        }
        val session = sessionRegistry.read(accountId)
            ?: return com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpeningCheck.Failure(
                "读取当前开局使用的登录会话已失效，请重新读取",
            )
        val api = BilibiliLabyrinthApi(session)
        val top = when (val result = api.top()) {
            is LabyrinthOperationResult.Success -> result.value
            is LabyrinthOperationResult.Failure ->
                return com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpeningCheck.Failure(result.message)
        }
        val enterId = top.enterId
            ?: return com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpeningCheck.Stale(
                "服务端当前没有进行中的开局",
            )
        val resume = when (val result = api.resume(enterId)) {
            is LabyrinthOperationResult.Success -> result.value
            is LabyrinthOperationResult.Failure ->
                return com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpeningCheck.Failure(result.message)
        }
        val route = RoomLabyrinthRouteStore(database).loadLatest(accountId)
        return labyrinthReusableOpeningSnapshotCheck(opening, top, resume, route)
    }

    /** Only the foreground service may consume this explicitly authorized snapshot. */
    fun startFromService(): Boolean {
        if (!startRequested || runningJob?.isActive == true) return false
        startRequested = false
        val (account, config) = frozenStart ?: return false
        if (account.id != settingsAccountId) {
            chrome.update { it.copy(isWorking = false, message = "账号已切换，本次启动已取消") }
            return false
        }
        runningJob = scope.launch {
            chrome.update { it.copy(isWorking = true, message = null, routeBlockIds = emptyList()) }
            try {
                beforeRerollLogin()?.let { reason ->
                    chrome.update { it.copy(message = reason) }
                    return@launch
                }
                var sessionResets = 0
                while (true) {
                    val session = ensureGameSession(account, PendingLoginAction.START) ?: return@launch
                    val workflow = LabyrinthRerollWorkflow(
                        api = BilibiliLabyrinthApi(session),
                        routeStore = RoomLabyrinthRouteStore(database),
                        checkpointStore = RoomLabyrinthRerollCheckpointStore(database),
                    )
                    val workflowResult = workflow.run(config) { progress ->
                        chrome.update {
                            val limit = if (progress.rerollUntilFound) "不限" else progress.maxAttempts.toString()
                            it.copy(progress = "${progress.attempt}/$limit · ${progress.stage}")
                        }
                    }
                    when (val result = workflowResult) {
                        is LabyrinthRerollResult.Success -> {
                            chrome.update {
                                it.copy(
                                routeBlockIds = result.route.blockIds,
                                currentGuildId = config.guildId,
                                currentDifficulty = config.difficulty,
                                message = if (result.resumedExisting) {
                                    "已读取并保留现有黎明界路线"
                                } else {
                                    "第 ${result.attempt} 次刷到${if (config.routePolicy.perfectStart) "完美" else "目标"}路线，已保留当前开局"
                                },
                            )
                            }
                            return@launch
                        }
                        is LabyrinthRerollResult.NeedsExistingRunDecision -> {
                            chrome.update {
                                it.copy(message = "检测到已有黎明界开局；确认后勾选“允许撤退现有开局”再开始")
                            }
                            return@launch
                        }
                        is LabyrinthRerollResult.Exhausted -> {
                            chrome.update {
                                it.copy(
                                    currentGuildId = null,
                                    currentDifficulty = null,
                                    message = "已完成 ${result.attempts} 次尝试，未找到目标路线",
                                )
                            }
                            return@launch
                        }
                        is LabyrinthRerollResult.Failure -> {
                            if (result.kind != LabyrinthFailureKind.REJECTED || sessionResets >= MAX_SESSION_RESETS) {
                                chrome.update { it.copy(message = result.message) }
                                return@launch
                            }
                            sessionResets += 1
                            chrome.update {
                                it.copy(progress = "游戏服会话失效，正在从账号库重新登录（$sessionResets/${MAX_SESSION_RESETS}）")
                            }
                            sessionRegistry.delete(account.id)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                chrome.update { it.copy(message = cancellationMessage ?: "已停止；若服务端已生成开局，将保留当前状态") }
            } catch (failure: Throwable) {
                chrome.update { it.copy(message = failure.message.orEmpty().ifBlank { "黎明界任务失败" }.take(200)) }
            } finally {
                withContext(NonCancellable) { runCatching { refreshCheckpoint(account.id) } }
                chrome.update { it.copy(isWorking = false, progress = null) }
                // A captcha pause resumes this reroll later; the client stays closed until then.
                if (chrome.value.captcha == null) {
                    withContext(NonCancellable) { runCatching { afterStandaloneReroll() } }
                }
                if (chrome.value.captcha == null) frozenStart = null
                runningJob = null
                cancellationMessage = null
            }
        }
        return true
    }

    fun checkStatus() {
        if (chrome.value.isWorking || chrome.value.captcha != null || !chrome.value.settingsReady) return
        val account = uiState.value.selectedAccount
        if (account == null) {
            chrome.update { it.copy(message = "请先在账号库选择一个账号") }
            return
        }
        if (account.id != settingsAccountId) return
        // Only this explicit action can mint a reusable-opening authorization. Clear any older
        // authorization before touching the network so failures cannot leave it reusable.
        explicitlyReadInitialOpening = null
        chrome.update { it.copy(isWorking = true, message = null, progress = "读取黎明界状态") }
        runningJob = scope.launch {
            chrome.update {
                it.copy(
                    isWorking = true,
                    message = null,
                    progress = "读取黎明界状态",
                    currentOpeningReadStatus = LabyrinthCurrentOpeningReadStatus.READING,
                    currentOpeningReadMessage = "正在登录游戏服并读取当前黎明界状态",
                )
            }
            try {
                var session = ensureGameSession(account, PendingLoginAction.CHECK_STATUS) ?: run {
                    chrome.update { current ->
                        if (current.captcha != null) {
                            current.copy(
                                currentOpeningReadStatus = LabyrinthCurrentOpeningReadStatus.LOGIN_VERIFICATION_REQUIRED,
                                currentOpeningReadMessage = "登录需要验证；完成验证码后会自动继续读取",
                            )
                        } else {
                            val detail = current.message ?: "无法登录游戏服"
                            current.copy(
                                currentOpeningReadStatus = LabyrinthCurrentOpeningReadStatus.FAILED,
                                currentOpeningReadMessage = detail,
                            )
                        }
                    }
                    return@launch
                }
                var api = BilibiliLabyrinthApi(session)
                val checkpointStore = RoomLabyrinthRerollCheckpointStore(database)
                val savedCheckpoint = checkpointStore.load(account.id)
                var sessionResets = 0
                var topResult = api.top()
                while (true) {
                    val rejected = topResult as? LabyrinthOperationResult.Failure ?: break
                    if (!labyrinthReadRetriesWithFreshLogin(rejected.kind, sessionResets, MAX_SESSION_RESETS)) break
                    sessionResets += 1
                    val detail = rejected.message
                    chrome.update { it.copy(progress = "游戏服会话已失效（$detail），重新登录后重读") }
                    sessionRegistry.delete(account.id)
                    session = ensureGameSession(account, PendingLoginAction.CHECK_STATUS) ?: run {
                        chrome.update { current ->
                            if (current.captcha != null) {
                                current.copy(
                                    currentOpeningReadStatus = LabyrinthCurrentOpeningReadStatus.LOGIN_VERIFICATION_REQUIRED,
                                    currentOpeningReadMessage = "登录需要验证；完成验证码后会自动继续读取",
                                )
                            } else {
                                val loginFailureDetail = current.message ?: "重新登录游戏服失败"
                                current.copy(
                                    currentOpeningReadStatus = LabyrinthCurrentOpeningReadStatus.FAILED,
                                    currentOpeningReadMessage = loginFailureDetail,
                                )
                            }
                        }
                        return@launch
                    }
                    api = BilibiliLabyrinthApi(session)
                    topResult = api.top()
                }
                when (val result = topResult) {
                    is LabyrinthOperationResult.Success -> {
                        val top = result.value
                        val maxUnlocked = LabyrinthRerollOptions.maxUnlockedDifficulty(top.clearedDifficulties)
                        val criteria = labyrinthCurrentOpeningCriteria(
                            selectedDifficulty = chrome.value.selectedDifficulty,
                            selectedRoutePolicy = currentRoutePolicy(),
                            savedCheckpoint = savedCheckpoint,
                            currentEnterId = top.enterId,
                        )
                        val routePolicy = criteria.routePolicy
                        val targetDifficulty = criteria.difficulty
                        val attempt = criteria.attempt
                        val existingRoute = if (top.enterId != null) {
                            ExistingLabyrinthRouteResolver(api).resolve(top, routePolicy)
                        } else {
                            null
                        }
                        val routeBlockIds: List<Long>
                        var observedCurrentGuildId = top.guildId
                        var openingReadStatus = LabyrinthCurrentOpeningReadStatus.NO_ACTIVE_OPENING
                        var openingReadMessage = "读取完成：当前没有进行中的黎明界。自动执行将按批量目标先刷开局。"
                        val message = when (existingRoute) {
                            is ExistingLabyrinthRouteResult.Found -> {
                                val value = existingRoute.value
                                observedCurrentGuildId = value.guildId ?: top.guildId
                                val guildId = observedCurrentGuildId ?: LabyrinthRerollOptions.DEFAULT_GUILD_ID
                                // The resolver has already proved that the route matches the saved
                                // route policy, and the batch goal owns the guild: this read only
                                // proves route quality and difficulty, while
                                // consumeReusableOpeningForBatch compares the actual guild with the
                                // first batch goal at the external start boundary.
                                val isTarget = value.difficulty == targetDifficulty
                                val criteriaComparison =
                                    "当前难度 ${value.difficulty}；已保存目标难度 $targetDifficulty"
                                if (isTarget) {
                                    RoomLabyrinthRouteStore(database).save(
                                        config = LabyrinthRerollConfig(
                                            accountId = account.id,
                                            guildId = guildId,
                                            difficulty = value.difficulty,
                                            maxAttempts = 1,
                                            retireExisting = false,
                                            routePolicy = routePolicy,
                                        ),
                                        route = value.route,
                                        attempt = attempt,
                                        allNodes = value.allNodes,
                                        currentBlockId = value.currentBlockId,
                                    )
                                }
                                checkpointStore.save(
                                    LabyrinthRerollCheckpoint(
                                        accountId = account.id,
                                        attempt = attempt,
                                        enterId = value.route.enterId,
                                        guildId = guildId,
                                        difficulty = value.difficulty,
                                        policy = routePolicy,
                                        verdict = if (isTarget) {
                                            LabyrinthRouteVerdict.TARGET
                                        } else {
                                            LabyrinthRouteVerdict.NOT_TARGET
                                        },
                                        message = if (isTarget) {
                                            if (value.currentBlockId == null) {
                                                "现有开局符合路线和难度，且尚未推进"
                                            } else {
                                                "现有开局符合路线和难度，但已推进到节点 ${value.currentBlockId}"
                                            }
                                        } else {
                                            "现有开局难度与当前页面选项不一致：$criteriaComparison"
                                        },
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                                routeBlockIds = if (isTarget) value.route.blockIds else emptyList()
                                openingReadStatus = if (isTarget) {
                                    LabyrinthCurrentOpeningReadStatus.TARGET
                                } else {
                                    LabyrinthCurrentOpeningReadStatus.NOT_TARGET
                                }
                                openingReadMessage = if (isTarget) {
                                    "读取完成：路线和难度符合要求。此结果只用于确认状态；批量执行仍会准备新开局。"
                                } else {
                                    "读取完成：当前开局难度与刷开局设置不一致。"
                                }
                                // The batch goal owns the guild, and this read is the only place that
                                // mints the one-shot reuse authorization. It is consumed once at the
                                // external start boundary and revalidated against the server there.
                                if (isTarget && value.currentBlockId == null) {
                                    explicitlyReadInitialOpening =
                                        com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpening(
                                            enterId = value.route.enterId,
                                            guildId = guildId,
                                            difficulty = value.difficulty,
                                        )
                                }
                                if (isTarget) {
                                    if (value.currentBlockId == null) {
                                        "当前开局是尚未推进的目标路线；若首个批量目标公会一致，可用于下一次批量首轮（Enter ID 尾号 ${value.route.enterId % 10_000}）"
                                    } else {
                                        "当前开局是目标路线但已经推进；角色和遗物状态无法从服务端恢复，批量执行会重新刷取"
                                    }
                                } else {
                                    "当前开局可读取，但难度不是当前目标（$criteriaComparison）"
                                }
                            }

                            ExistingLabyrinthRouteResult.NoMatchingRoute -> {
                                val enterId = requireNotNull(top.enterId)
                                checkpointStore.save(
                                    LabyrinthRerollCheckpoint(
                                        accountId = account.id,
                                        attempt = attempt,
                                        enterId = enterId,
                                        guildId = top.guildId ?: LabyrinthRerollOptions.DEFAULT_GUILD_ID,
                                        difficulty = top.difficulty ?: targetDifficulty,
                                        policy = routePolicy,
                                        verdict = LabyrinthRouteVerdict.NOT_TARGET,
                                        message = "现有开局不符合当前页面选择的路线条件",
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                                routeBlockIds = emptyList()
                                openingReadStatus = LabyrinthCurrentOpeningReadStatus.NOT_TARGET
                                openingReadMessage = "读取完成：当前开局不符合已保存的路线条件。"
                                "当前开局不是目标路线"
                            }

                            is ExistingLabyrinthRouteResult.Failure -> {
                                val enterId = requireNotNull(top.enterId)
                                checkpointStore.save(
                                    LabyrinthRerollCheckpoint(
                                        accountId = account.id,
                                        attempt = attempt,
                                        enterId = enterId,
                                        guildId = top.guildId ?: LabyrinthRerollOptions.DEFAULT_GUILD_ID,
                                        difficulty = top.difficulty ?: targetDifficulty,
                                        policy = routePolicy,
                                        verdict = LabyrinthRouteVerdict.PENDING_VERIFICATION,
                                        message = existingRoute.message,
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                                routeBlockIds = emptyList()
                                openingReadStatus = LabyrinthCurrentOpeningReadStatus.PENDING_VERIFICATION
                                openingReadMessage = "已确认存在开局，但暂时无法完成路线验证：${existingRoute.message}"
                                "已确认存在开局，但暂时无法读取路线；保持待联网验证：${existingRoute.message}"
                            }

                            null -> {
                                checkpointStore.clear(account.id)
                                routeBlockIds = emptyList()
                                openingReadStatus = LabyrinthCurrentOpeningReadStatus.NO_ACTIVE_OPENING
                                openingReadMessage = "读取完成：当前没有进行中的黎明界。自动执行将按批量目标先刷开局。"
                                "黎明界状态正常，当前最高可挑战难度为 $maxUnlocked"
                            }
                        }
                        chrome.update {
                            it.copy(
                                maxUnlockedDifficulty = maxUnlocked,
                                selectedDifficulty = it.selectedDifficulty.coerceAtMost(maxUnlocked),
                                currentGuildId = observedCurrentGuildId.takeIf { top.enterId != null },
                                currentDifficulty = top.difficulty.takeIf { top.enterId != null },
                                routeBlockIds = routeBlockIds,
                                currentOpeningReadStatus = openingReadStatus,
                                currentOpeningReadMessage = openingReadMessage,
                                message = message,
                            )
                        }
                    }
                    is LabyrinthOperationResult.Failure -> {
                        val checkpoint = checkpointStore.load(account.id)
                        checkpoint?.let {
                            checkpointStore.save(
                                it.copy(
                                    verdict = LabyrinthRouteVerdict.PENDING_VERIFICATION,
                                    message = result.message,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        chrome.update {
                            it.copy(
                                message = result.message,
                                currentOpeningReadStatus = if (checkpoint == null) {
                                    LabyrinthCurrentOpeningReadStatus.FAILED
                                } else {
                                    LabyrinthCurrentOpeningReadStatus.PENDING_VERIFICATION
                                },
                                currentOpeningReadMessage = result.message,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                chrome.update {
                    it.copy(
                        message = "状态检查已取消",
                        currentOpeningReadStatus = LabyrinthCurrentOpeningReadStatus.CANCELLED,
                        currentOpeningReadMessage = "读取已取消",
                    )
                }
            } catch (failure: Throwable) {
                val detail = failure.message.orEmpty().ifBlank { "读取黎明界状态失败" }.take(200)
                chrome.update {
                    it.copy(
                        message = detail,
                        currentOpeningReadStatus = LabyrinthCurrentOpeningReadStatus.FAILED,
                        currentOpeningReadMessage = detail,
                    )
                }
            } finally {
                withContext(NonCancellable) {
                    runCatching { refreshCheckpoint(account.id, preserveExplicitReadOutcome = true) }
                }
                chrome.update { it.copy(isWorking = false, progress = null) }
                runningJob = null
            }
        }
    }

    fun submitCaptcha(validate: String) {
        val captcha = chrome.value.captcha ?: return
        if (chrome.value.isWorking) return
        val loginAction = pendingLoginAction
        chrome.update { it.copy(isWorking = true, message = null) }
        runningJob = scope.launch {
            chrome.update { it.copy(isWorking = true, message = null) }
            try {
                when (val result = loginCoordinator.completeCaptcha(captcha.accountId, CaptchaProof(validate))) {
                    is NativeLoginResult.Success -> {
                        accountRepository.updateGameUid(captcha.accountId, result.profile.viewerId.toString())
                        val action = pendingLoginAction
                        pendingLoginAction = null
                        chrome.update { it.copy(isWorking = false, captcha = null) }
                        when (action) {
                            PendingLoginAction.START -> requestFrozenStart()
                            PendingLoginAction.CHECK_STATUS -> scope.launch {
                                if (settingsAccountId == captcha.accountId) checkStatus()
                            }
                            null -> Unit
                        }
                    }

                    is NativeLoginResult.CaptchaRequired -> chrome.update {
                        it.copy(
                            isWorking = false,
                            captcha = captcha.copy(challenge = result.challenge, error = null),
                        )
                    }

                    is NativeLoginResult.Failure -> {
                        frozenStart = null
                        pendingLoginAction = null
                        chrome.update {
                            it.copy(
                                isWorking = false,
                                captcha = null,
                                message = result.message,
                                currentOpeningReadStatus = if (loginAction == PendingLoginAction.CHECK_STATUS) {
                                    LabyrinthCurrentOpeningReadStatus.FAILED
                                } else {
                                    it.currentOpeningReadStatus
                                },
                                currentOpeningReadMessage = if (loginAction == PendingLoginAction.CHECK_STATUS) {
                                    result.message
                                } else {
                                    it.currentOpeningReadMessage
                                },
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                chrome.update {
                    it.copy(
                        isWorking = false,
                        captcha = null,
                        message = "登录验证已取消",
                        currentOpeningReadStatus = if (loginAction == PendingLoginAction.CHECK_STATUS) {
                            LabyrinthCurrentOpeningReadStatus.CANCELLED
                        } else {
                            it.currentOpeningReadStatus
                        },
                        currentOpeningReadMessage = if (loginAction == PendingLoginAction.CHECK_STATUS) {
                            "登录验证已取消，尚未读取当前开局"
                        } else {
                            it.currentOpeningReadMessage
                        },
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                frozenStart = null
                pendingLoginAction = null
                val detail = failure.message.orEmpty().ifBlank { "登录验证失败" }.take(200)
                chrome.update {
                    it.copy(
                        isWorking = false,
                        captcha = null,
                        message = detail,
                        currentOpeningReadStatus = if (loginAction == PendingLoginAction.CHECK_STATUS) {
                            LabyrinthCurrentOpeningReadStatus.FAILED
                        } else {
                            it.currentOpeningReadStatus
                        },
                        currentOpeningReadMessage = if (loginAction == PendingLoginAction.CHECK_STATUS) {
                            detail
                        } else {
                            it.currentOpeningReadMessage
                        },
                    )
                }
            } finally {
                runningJob = null
            }
        }
    }

    fun reportCaptchaError(message: String) {
        chrome.update { current ->
            current.copy(captcha = current.captcha?.copy(error = message.take(120)))
        }
    }

    fun cancelCaptcha() {
        val accountId = chrome.value.captcha?.accountId ?: return
        val loginAction = pendingLoginAction
        frozenStart = null
        startRequested = false
        pendingLoginAction = null
        scope.launch {
            loginCoordinator.cancel(accountId)
            if (settingsAccountId == accountId) {
                pendingLoginAction = null
                chrome.update {
                    it.copy(
                        captcha = null,
                        isWorking = false,
                        message = "已取消本次登录验证",
                        currentOpeningReadStatus = if (loginAction == PendingLoginAction.CHECK_STATUS) {
                            LabyrinthCurrentOpeningReadStatus.CANCELLED
                        } else {
                            it.currentOpeningReadStatus
                        },
                        currentOpeningReadMessage = if (loginAction == PendingLoginAction.CHECK_STATUS) {
                            "登录验证已取消，尚未读取当前开局"
                        } else {
                            it.currentOpeningReadMessage
                        },
                    )
                }
            }
        }
    }

    fun stop() {
        startRequested = false
        frozenStart = null
        pendingLoginAction = null
        explicitlyReadInitialOpening = null
        runningJob?.cancel()
        // Cancellation is asynchronous: keep controls locked until the job's finally has finished.
        if (runningJob == null) chrome.update { it.copy(isWorking = false, progress = null) }
        // cancelCaptcha must read pendingLoginAction before it is cleared, otherwise a stopped
        // CHECK_STATUS captcha remains displayed forever as "waiting for verification".
        if (chrome.value.captcha != null) cancelCaptcha() else pendingLoginAction = null
    }

    fun stopWithReason(message: String) {
        cancellationMessage = message
        stop()
        reportMessage(message)
    }

    fun reportMessage(message: String) { chrome.update { it.copy(message = message) } }

    fun saveSettings(settings: LabyrinthRerollSettings) {
        if (chrome.value.isWorking || chrome.value.captcha != null) return
        val accountId = settingsAccountId ?: return
        settings.validationError()?.let { reportMessage(it); return }
        if (settings.difficulty > (chrome.value.maxUnlockedDifficulty ?: LabyrinthRerollOptions.MAX_DIFFICULTY)) {
            reportMessage("所选难度尚未解锁"); return
        }
        settingsStore.save(accountId, settings)
        explicitlyReadInitialOpening = null
        applySettings(settings)
        // A prior TARGET verdict was evaluated against the old difficulty/route policy.
        // Require an explicit fresh read before presenting it as the current server state.
        chrome.update {
            it.copy(
                routeVerdict = null,
                verdictMessage = null,
                checkpointEnterId = null,
                currentGuildId = null,
                currentDifficulty = null,
                routeBlockIds = emptyList(),
                currentOpeningReadStatus = LabyrinthCurrentOpeningReadStatus.NOT_READ,
                currentOpeningReadMessage = "设置已变化，请重新读取当前开局以更新路线判定",
                message = "刷开局设置已保存；请重新登录并读取当前开局",
            )
        }
    }

    private fun applySettings(settings: LabyrinthRerollSettings) {
        chrome.update { it.copy(selectedGuildId = settings.guildId, selectedDifficulty = settings.difficulty,
            perfectStart = settings.perfectStart,
            routeEvaluationMode = settings.routeEvaluationMode, valueAllowance = settings.valueAllowance,
            thirdBlockChoice = settings.thirdBlockChoice,
            selectedArea3BossIds = settings.area3BossIds, selectedArea5BossIds = settings.area5BossIds,
            maxAttempts = settings.maxAttempts, rerollUntilFound = settings.rerollUntilFound,
            retireExisting = settings.retireExisting, settingsReady = true) }
    }

    fun dismissMessage() {
        chrome.update { it.copy(message = null) }
    }

    private fun parseConfig(accountId: Long): LabyrinthRerollConfig? {
        val current = chrome.value
        val maxAttempts = current.maxAttempts.toIntOrNull()
        val error = when {
            LabyrinthRerollOptions.guilds.none { it.guildId == current.selectedGuildId } -> "请选择有效公会"
            current.selectedDifficulty !in 1..LabyrinthRerollOptions.MAX_DIFFICULTY -> "请选择有效难度"
            current.maxUnlockedDifficulty != null && current.selectedDifficulty > current.maxUnlockedDifficulty ->
                "所选难度尚未解锁"
            maxAttempts == null || maxAttempts !in 1..LabyrinthRerollOptions.MAX_ATTEMPTS ->
                "最大尝试次数必须在 1 到 ${LabyrinthRerollOptions.MAX_ATTEMPTS} 之间"
            else -> null
        }
        if (error != null) {
            chrome.update { it.copy(message = error) }
            return null
        }
        return uiSettings().toConfig(accountId)
    }

    private fun currentRoutePolicy(): LabyrinthRoutePolicy = chrome.value.let { current ->
        LabyrinthRoutePolicy(
            perfectStart = current.perfectStart,
            evaluationMode = current.routeEvaluationMode,
            valueAllowance = current.valueAllowance,
            thirdBlockChoice = current.thirdBlockChoice,
            area3BossIds = current.selectedArea3BossIds,
            area5BossIds = current.selectedArea5BossIds,
        )
    }

    private suspend fun ensureGameSession(
        account: AccountListItem,
        action: PendingLoginAction,
    ): BilibiliGameSession? {
        sessionRegistry.read(account.id)?.let { return it }
        chrome.update { it.copy(progress = "正在从账号库登录游戏服", message = null) }
        val material = runCatching { accountRepository.loadLoginMaterial(account.id) }
            .getOrElse { failure ->
                chrome.update {
                    it.copy(message = failure.message.orEmpty().ifBlank { "无法读取账号凭据" }.take(200))
                }
                return null
            }
        return when (val result = loginCoordinator.start(material)) {
            is NativeLoginResult.Success -> {
                accountRepository.updateGameUid(account.id, result.profile.viewerId.toString())
                pendingLoginAction = null
                chrome.update { it.copy(captcha = null) }
                sessionRegistry.read(account.id).also { session ->
                    if (session == null) chrome.update { it.copy(message = "登录成功，但游戏服会话未保存") }
                }
            }

            is NativeLoginResult.CaptchaRequired -> {
                pendingLoginAction = action
                chrome.update {
                    it.copy(
                        captcha = AccountCaptchaState(
                            accountId = account.id,
                            accountAlias = account.alias,
                            challenge = result.challenge,
                        ),
                        message = null,
                    )
                }
                null
            }

            is NativeLoginResult.Failure -> {
                chrome.update { it.copy(message = result.message) }
                null
            }
        }
    }

    private suspend fun refreshCheckpoint(
        accountId: Long,
        preserveExplicitReadOutcome: Boolean = false,
    ) {
        val checkpoint = RoomLabyrinthRerollCheckpointStore(database).load(accountId)
        chrome.update { current ->
            // A persisted route/checkpoint is diagnostic data, not proof that the opening still
            // exists. Only checkStatus() may promote this UI state to TARGET/NOT_TARGET.
            val restoredStatus = current.currentOpeningReadStatus.takeIf { preserveExplicitReadOutcome }
                ?: LabyrinthCurrentOpeningReadStatus.NOT_READ
            current.copy(
                routeVerdict = checkpoint?.verdict,
                verdictMessage = checkpoint?.message,
                checkpointEnterId = checkpoint?.enterId,
                currentOpeningReadStatus = restoredStatus,
                currentOpeningReadMessage = if (preserveExplicitReadOutcome) {
                    current.currentOpeningReadMessage
                } else {
                    "尚未显式读取当前开局；保存的路线记录不代表当前服务端状态"
                },
            )
        }
    }

    private fun invalidateExplicitOpeningRead(message: String) {
        chrome.update { current ->
            current.copy(
                currentOpeningReadStatus = LabyrinthCurrentOpeningReadStatus.NOT_READ,
                currentOpeningReadMessage = message,
                currentGuildId = null,
                currentDifficulty = null,
            )
        }
    }

    private fun updateConfig(transform: LabyrinthChromeState.() -> LabyrinthChromeState) {
        if (!chrome.value.isWorking && chrome.value.captcha == null && chrome.value.settingsReady) {
            explicitlyReadInitialOpening = null
            chrome.update { it.transform().copy(message = null) }
            val settings = uiSettings()
            if (settings.validationError() == null) settingsAccountId?.let { settingsStore.save(it, settings) }
        }
    }

    private fun updateNumeric(transform: LabyrinthChromeState.() -> LabyrinthChromeState) {
        updateConfig(transform)
    }

    private fun uiSettings() = chrome.value.let {
        LabyrinthRerollSettings(
            guildId = it.selectedGuildId,
            difficulty = it.selectedDifficulty,
            perfectStart = it.perfectStart,
            routeEvaluationMode = it.routeEvaluationMode,
            valueAllowance = it.valueAllowance,
            thirdBlockChoice = it.thirdBlockChoice,
            area3BossIds = it.selectedArea3BossIds,
            area5BossIds = it.selectedArea5BossIds,
            maxAttempts = it.maxAttempts,
            rerollUntilFound = it.rerollUntilFound,
            retireExisting = it.retireExisting,
        )
    }

    private companion object {
        const val MAX_SESSION_RESETS = 3
    }
}

private fun Set<Int>.toggle(value: Int): Set<Int> = if (value in this) this - value else this + value
