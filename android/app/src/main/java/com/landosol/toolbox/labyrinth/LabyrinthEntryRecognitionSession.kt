package com.landosol.toolbox.labyrinth

import android.util.Log
import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationSessionStartResult
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CaptureFrameConsumerLease
import com.landosol.toolbox.automation.capture.CaptureFrameRegistrationResult
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.automation.overlay.AutomationOverlayBox
import com.landosol.toolbox.automation.overlay.AutomationOverlayCoordinator
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.automation.overlay.AutomationOverlaySessionHandler
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.automation.session.SessionBlockClassifier
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.automation.session.toSessionBlockScores
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LABYRINTH_BATTLE_CHARACTER_SAFE_CONFIDENCE
import com.landosol.toolbox.labyrinth.vision.NODE_SEARCH_MODE_DEFERRED
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFailureLayout
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFailureObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.labyrinthRelicDetailHoldsFrame
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopDialogState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeSession
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeActionPlanner
import com.landosol.toolbox.labyrinth.node.LabyrinthMapScanDirection
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeScanPlan
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeViewportScanner
import com.landosol.toolbox.labyrinth.node.labyrinthReferenceColumnPitch
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeSearchHint
import com.landosol.toolbox.labyrinth.node.finalBossPlatformClickRect
import com.landosol.toolbox.labyrinth.node.NodeAction
import com.landosol.toolbox.labyrinth.batch.LabyrinthRunTerminalEvent
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.NodePositionMapping
import com.landosol.toolbox.labyrinth.node.NodeTopologyBindingKind
import com.landosol.toolbox.labyrinth.node.NodeDebugLogger
import com.landosol.toolbox.labyrinth.node.NodeSessionState
import com.landosol.toolbox.labyrinth.node.NodeTemplateSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LabyrinthEntryRecognitionStatus { IDLE, RUNNING, STOPPED, ERROR }

/** 按已保存路线执行/回放的进度快照，供 UI 与悬浮窗展示。 */
data class LabyrinthRouteProgress(
    val currentArea: Int,
    val visitedCount: Int,
    val routeNodeCount: Int,
    val nextNodeLabel: String? = null,
    val nextNodeRect: EntryPixelRect? = null,
    val complete: Boolean = false,
)

enum class LabyrinthCombatKind { NORMAL, EX, BOSS }

/** Context carried across challenge, team editor, battle and result pages. */
data class LabyrinthCombatContext(
    val kind: LabyrinthCombatKind,
    val teamIndex: Int = 1,
    /** Values greater than three share the 3+ target combat model. */
    val targetCount: Int = 1,
) {
    init {
        require(teamIndex >= 1)
        require(targetCount >= 1)
    }
}

internal data class LabyrinthEffectiveScanFrameEvidence(
    val confirmedCharacterIds: Set<String>,
    val unsafeVisibleCharacters: List<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch>,
    val toleratedSelectedDimmedCount: Int,
)

/**
 * Selected roster cards are rendered dimmed by the game. Their portrait match can therefore fall
 * below the normal safety threshold even though the yellow checkmark and the authoritative current
 * member strip prove that this is an already-selected card. Such a dimmed card must not deadlock
 * the official "有效效果" scan. We only assign an identity when its id/suspected id reconciles to
 * a member that is independently present in the bottom strip; otherwise it is tolerated but not
 * added to the effective-role set.
 */
internal fun labyrinthEffectiveScanFrameEvidence(
    observation: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation,
): LabyrinthEffectiveScanFrameEvidence {
    val selectedMemberIds = observation.selectedCharacters.mapNotNull { match ->
        match.characterId?.let(::canonicalLabyrinthRoleId)
    }.toSet()
    val hasAuthoritativeSelectedMembers = observation.selectedCharacters.isNotEmpty()
    val confirmed = linkedSetOf<String>()
    val unsafe = mutableListOf<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch>()
    var toleratedSelectedDimmedCount = 0

    observation.visibleCharacters.forEach { match ->
        val id = match.characterId?.let(::canonicalLabyrinthRoleId)
        val safelyIdentified = id != null && match.trusted &&
            match.confidence >= LABYRINTH_BATTLE_CHARACTER_SAFE_CONFIDENCE
        if (safelyIdentified) {
            confirmed += requireNotNull(id)
            return@forEach
        }
        if (match.selected && hasAuthoritativeSelectedMembers) {
            toleratedSelectedDimmedCount++
            val reconciledId = sequenceOf(match.characterId, match.suspectedCharacterId)
                .filterNotNull()
                .map(::canonicalLabyrinthRoleId)
                .firstOrNull { it in selectedMemberIds }
            if (reconciledId != null) confirmed += reconciledId
            return@forEach
        }

        unsafe += match
    }

    return LabyrinthEffectiveScanFrameEvidence(
        confirmedCharacterIds = confirmed,
        unsafeVisibleCharacters = unsafe,
        toleratedSelectedDimmedCount = toleratedSelectedDimmedCount,
    )
}

/**
 * The node session advances the persisted cursor to the next area's START as soon as an area Boss
 * is entered. After an app restart the game may still be on that Boss challenge/team page, so the
 * immediately preceding selected-route Boss is still the active encounter until a later node is
 * confirmed.
 */
internal fun labyrinthPersistedCurrentNode(route: LabyrinthRouteJson?): LabyrinthNodeJson? {
    route ?: return null
    val currentBlockId = route.currentBlockId ?: return null
    return route.nodes.firstOrNull { it.blockId == currentBlockId }
}

internal fun labyrinthPersistedBossNode(route: LabyrinthRouteJson?): LabyrinthNodeJson? {
    route ?: return null
    val current = labyrinthPersistedCurrentNode(route) ?: return null
    val currentIndex = route.nodes.indexOfFirst { it.blockId == current.blockId }
    if (currentIndex < 0) return null
    if (current.blockType == LabyrinthNodeTypes.BOSS) return current
    if (current.blockType != LabyrinthNodeTypes.START || currentIndex == 0) return null
    val previous = route.nodes[currentIndex - 1]
    return previous.takeIf {
        it.blockType == LabyrinthNodeTypes.BOSS && it.area + 1 == current.area
    }
}

internal fun labyrinthBossEncounterStrategy(route: LabyrinthRouteJson?): LabyrinthExEncounterStrategy? {
    val boss = labyrinthPersistedBossNode(route) ?: return null
    val unitId = LabyrinthBossCatalog.unitIdForQuest(boss.area, boss.questId)
    return LabyrinthBossEncounterCatalog.forUnitId(unitId)
}

private enum class LabyrinthEffectiveCharacterScanStage {
    IDLE,
    SWITCH_TO_EFFECTIVE,
    SCANNING,
    RETURN_TO_ALL,
    COMPLETE,
}

internal fun labyrinthBattleRetryLimit(kind: LabyrinthCombatKind): Int = when (kind) {
    LabyrinthCombatKind.BOSS -> 1
    LabyrinthCombatKind.NORMAL,
    LabyrinthCombatKind.EX,
    -> 2
}

/**
 * Whether the retry-context stop conditions apply to a recognised battle-failure page.
 *
 * The blue "重新挑战" button of the three-button layout consumes the run's retry budget, so the
 * reroll-after-three-failures stop and the retry-limit / batch-limit stops belong to it. The
 * two-button layout's lower-right button reads "下一步": it advances the relic-revive chain, and
 * rerolling or ending the run there would throw away the run that was just revived. See
 * `android/docs/relic-revive-battle-failure-audit.md`, section 「3. 实现级设计」.
 */
internal fun labyrinthBattleFailureUsesRetryBudget(layout: LabyrinthBattleFailureLayout): Boolean =
    layout != LabyrinthBattleFailureLayout.TWO_BUTTON

/**
 * The post-entry tap a recognised battle-failure page offers.
 *
 * Both layouts expose the same lower-right blue button and therefore the same rect; only the kind
 * differs, and with it the accounting in `dispatchPostEntryTap`. Extracted so both layouts stay
 * pinned by tests instead of by reading the plan branch.
 */
internal fun labyrinthBattleFailureTapPlan(
    failure: LabyrinthBattleFailureObservation,
    pendingSingleBossFallbackToMulti: Boolean,
): LabyrinthPostEntryTapPlan = when (failure.layout) {
    LabyrinthBattleFailureLayout.TWO_BUTTON -> LabyrinthPostEntryTapPlan(
        LabyrinthPostEntryActionKind.BATTLE_FAILURE_NEXT,
        "战斗失败页(遗物复活)：下一步",
        failure.retryButtonRect,
    )
    LabyrinthBattleFailureLayout.THREE_BUTTON -> if (pendingSingleBossFallbackToMulti) {
        LabyrinthPostEntryTapPlan(
            LabyrinthPostEntryActionKind.BATTLE_RETRY_SWITCH_MULTI,
            "Boss单队达到设定重试次数：切换多队重新挑战",
            failure.retryButtonRect,
        )
    } else {
        LabyrinthPostEntryTapPlan(
            LabyrinthPostEntryActionKind.BATTLE_RETRY,
            "战斗失败：重新挑战",
            failure.retryButtonRect,
        )
    }
}

/** Number of automatic retry taps allowed before the current failed page becomes terminal. */
internal fun labyrinthEffectiveBattleRetryLimit(
    kind: LabyrinthCombatKind,
    rerollAfterThreeFailures: Boolean,
    singleBossFallbackRetryCount: Int? = null,
): Int = maxOf(
    labyrinthBattleRetryLimit(kind),
    if (rerollAfterThreeFailures) 2 else 0,
    if (kind == LabyrinthCombatKind.BOSS) singleBossFallbackRetryCount ?: 0 else 0,
)

/** battleRetryCount counts successful "重新挑战" taps; retryCount=2 means the third attempt failed. */
internal fun labyrinthShouldSwitchSingleBossToMulti(
    enabled: Boolean,
    kind: LabyrinthCombatKind,
    mode: LabyrinthBossTeamMode,
    battleRetryCount: Int,
    retryCountBeforeMulti: Int = 2,
): Boolean = enabled &&
    kind == LabyrinthCombatKind.BOSS &&
    mode == LabyrinthBossTeamMode.SINGLE_TEAM &&
    battleRetryCount >= retryCountBeforeMulti

internal fun labyrinthShouldRerollAfterBattleFailure(
    rerollAfterThreeFailures: Boolean,
    battleRetryCount: Int,
): Boolean = rerollAfterThreeFailures && battleRetryCount >= 2

/**
 * A batch-owned run that exhausts its retries is a formal FAILED_MAX_RETRY, never a stop on the
 * failure page. 2026-09-17 live (batch 20260917-031313 run001): 「刷开局」 was off in the
 * strategy, so the EX retry limit fell into the interactive "保留在失败页" stop, the batch
 * received FatalUnknown and halted FAILED with the game parked on 战斗失败. The batch owns
 * the reroll and the failure-page invalidation, so the run only has to end with the right
 * outcome; the strategy toggle governs interactive runs alone.
 */
internal fun labyrinthBatchOwnedRunEndsAtRetryLimit(
    batchOwnsRun: Boolean,
    battleRetryCount: Int,
    retryLimit: Int,
): Boolean = batchOwnsRun && battleRetryCount >= retryLimit

/**
 * Pages of the final settlement chain. When the entry planner reports one of them as "complete"
 * but no route executor is configured, the session must wait rather than stop: the game is still
 * inside a run and a stopped session would strand it on the score page.
 */
internal fun labyrinthCompletionWaitsWithoutRoute(state: LabyrinthEntryPageState): Boolean = state in setOf(
    LabyrinthEntryPageState.RUN_CLEAR_RESULT,
    LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
    LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
    LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
)

internal fun labyrinthBattleKindLabel(kind: LabyrinthCombatKind): String = when (kind) {
    LabyrinthCombatKind.NORMAL -> "普通战"
    LabyrinthCombatKind.EX -> "EX战"
    LabyrinthCombatKind.BOSS -> "Boss战"
}

/** A non-null message is also the safety gate that forbids post-entry auto clicks on this page. */
internal fun labyrinthManualInputMessage(state: LabyrinthEntryPageState): String? = when (state) {
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> "等待人工选择角色并确认"
    else -> null
}

/**
 * A number of reward flows show only a tappable character portrait between two recognized
 * pages. The portrait itself is intentionally allowed to be UNKNOWN, but only when the
 * previous page proves that a reward/animation chain is in progress.
 */
internal fun shouldArmCharacterAcquisitionFallback(
    previousPage: LabyrinthEntryPageState?,
    currentPage: LabyrinthEntryPageState,
    activeNodeType: Int? = null,
): Boolean {
    if (currentPage != LabyrinthEntryPageState.UNKNOWN) return false
    return when (previousPage) {
        LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
        LabyrinthEntryPageState.LINK_CHOICE,
        LabyrinthEntryPageState.EVENT_CHOICE,
        LabyrinthEntryPageState.EVENT_ANIMATION,
        -> true

        // Link rewards show an ITEM_REWARD popup containing the three granted characters and
        // then enter the portrait sequence. Normal/EX/Boss rewards are followed by a role
        // selection. A relic or plain event item reward can instead return directly to the map,
        // so those node types must not arm the fallback here.
        LabyrinthEntryPageState.ITEM_REWARD,
        LabyrinthEntryPageState.BATTLE_RESULT,
        -> activeNodeType in setOf(
            LabyrinthNodeTypes.LINK,
            LabyrinthNodeTypes.NORMAL_BATTLE,
            LabyrinthNodeTypes.EX_BATTLE,
            LabyrinthNodeTypes.BOSS,
        )

        else -> false
    }
}

/**
 * Some event nodes open the same full-roster shell used by the initial 3-character selector, but
 * ask for exactly one free recruit. Route context owns this ambiguity: once the node we actually
 * tapped is EVENT, an INITIAL_CHARACTER_SELECTION frame with an opening-style viewport must never
 * be delegated to the opening-roster planner.
 */
internal fun labyrinthEventFreeRoleSelectionOwnsFrame(
    pageState: LabyrinthEntryPageState,
    activeNodeType: Int?,
    roleRewardPage: Boolean,
    hasOpeningViewport: Boolean,
    shopChoiceImprintPending: Boolean = false,
    /**
     * Whether a route has passed the opening start. Once it has, a full-roster picker can only have been
     * opened by this run (an event's free pick or a shop 选择印记); the opening selector is over.
     *
     * The node-type and imprint-pending flags are better evidence when they survive, but they are
     * exactly what gets lost across the page churn a purchase causes, and losing them used to
     * leave the page with no owner at all rather than with a worse owner.
     */
    routeActive: Boolean = false,
): Boolean =
    pageState == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
        !roleRewardPage &&
        hasOpeningViewport &&
        (
            routeActive ||
                activeNodeType == LabyrinthNodeTypes.EVENT ||
                // A 选择印记 bought in the shop opens the same full-roster picker as an event's
                // free pick. Without this the frame fell through to the opening-roster handler,
                // which hunts for this guild's three fixed opening characters and reported
                // "已到初始角色列表底部，复核剩余目标 1/3" while the shop's picker waited
                // (2026-09-20 bundle 155513, 攻击型职能的选择印记).
                (activeNodeType == LabyrinthNodeTypes.SHOP && shopChoiceImprintPending)
            )

/**
 * Whether a route page that has produced no action should be given up on.
 *
 * Almost every page state has some bounded retry of its own, but those bounds only count *taps*.
 * A page that decides "wait" never dispatches anything, so it increments nothing and no timeout
 * applies: before this, only UNKNOWN had a deadline. Every other wait branch -- shop titles that
 * never resolve, an EX challenge page whose encounter is never recognized, an event whose OCR
 * never reaches the trusted threshold, a failed battle whose retry conditions never come true --
 * could hold a run open indefinitely with a fixed status line and nothing happening.
 *
 * "Progress" is deliberately generous: a dispatched action, a page change, or merely a different
 * status message all count, because a changing message means some sub-state advanced (a shop
 * round, an OCR confirmation, a scan stage). Only a page that is truly frozen -- same page, same
 * message, no action at all for [limitMillis] -- gives up, and then it stops with that message as
 * the diagnosis rather than hanging.
 */
internal fun labyrinthPageStallExpired(
    stalledMillis: Long,
    battlePage: Boolean,
    limitMillis: Long = POST_ENTRY_STALL_TIMEOUT_MILLIS,
): Boolean = when {
    // Battle has its own fixed 5-minute deadline in LabyrinthBattleWaitPolicy, and it is the only
    // page whose legitimate silence exceeds this budget.
    //
    // There used to be a second exemption here for "waiting for the operator to pick characters".
    // It was a hole, not a safety valve: manual selection is not a mode this build offers
    // (`manualCharacterSelection = false`), yet INITIAL_CHARACTER_SELECTION still falls back to
    // that message, so any hang on a roster page exempted itself from the watchdog -- which is
    // precisely the page that hung for 408 s in 2026-09-22 bundle 173753.
    battlePage -> false
    else -> stalledMillis >= limitMillis
}

/**
 * How long a hand-opened relic-detail popup may hold the route before the run stops.
 *
 * Generous on purpose: it exists only so an unattended run cannot be parked for ever, not to rush
 * anyone reading the list.
 */
internal const val RELIC_DETAIL_HOLD_TIMEOUT_MILLIS = 300_000L

/** How long the run asks for a human to close a stuck movement dialog before giving up. */
internal const val ORPHAN_MOVE_CONFIRMATION_MANUAL_GRACE_MILLIS = 60_000L

/**
 * Whether the single-action latch has been held long enough to be a fault rather than a wait.
 *
 * A dispatched gesture completes in well under a second; the executor is the only thing between
 * claiming the latch and releasing it. Holding it for a minute means the gesture call never came
 * back, and because every frame handler bails out while the latch is held, that state is
 * indistinguishable from a dead session: no clicks, no messages, no other timeout can fire.
 */
internal fun labyrinthActionLatchStuck(
    heldSinceMillis: Long,
    nowMillis: Long,
    limitMillis: Long = ACTION_LATCH_STUCK_TIMEOUT_MILLIS,
): Boolean = heldSinceMillis != Long.MIN_VALUE && nowMillis - heldSinceMillis >= limitMillis

/** A dispatched gesture that has not returned in this long is treated as never returning. */
internal const val ACTION_LATCH_STUCK_TIMEOUT_MILLIS = 60_000L

/**
 * How long a route page may show the same status with no action before the run stops.
 *
 * Long enough to clear the slowest legitimate silence: the shop's per-title OCR backstop is 20 s,
 * and a serial OCR queue can leave one shop slot waiting ~16 s for its turn, so a single
 * unchanged round can legitimately last around half a minute on a loaded device. 120 s leaves
 * several times that margin while still guaranteeing the run terminates.
 */
internal const val POST_ENTRY_STALL_TIMEOUT_MILLIS = 120_000L

internal enum class LabyrinthEventFreeRoleBlockedAction { WAIT, STOP }

/**
 * What a stalled event free-role page should do next.
 *
 * The page is answered from whatever is on screen: the roster only offers roles this run has not
 * got yet, so the top-scoring visible card is the answer and there is nothing to be gained by
 * walking the (very long) list. Short stalls are still ordinary -- the list animates and portraits
 * resolve a frame late -- so they wait. What must not happen is the unbounded wait this page used
 * to allow: an unreadable portrait or a recommendation that never cleared the safety bar parked
 * the run here with no timeout at all, so past [giveUpMillis] it stops with the reason instead.
 */
internal fun labyrinthEventFreeRoleBlockedAction(
    blockedMillis: Long,
    settleMillis: Long = EVENT_FREE_ROLE_SETTLE_MILLIS,
    giveUpMillis: Long = EVENT_FREE_ROLE_GIVE_UP_MILLIS,
): LabyrinthEventFreeRoleBlockedAction =
    if (blockedMillis >= giveUpMillis) {
        LabyrinthEventFreeRoleBlockedAction.STOP
    } else {
        LabyrinthEventFreeRoleBlockedAction.WAIT
    }

/** A stalled free-role viewport is still considered to be settling for this long. */
internal const val EVENT_FREE_ROLE_SETTLE_MILLIS = 3_000L
/** A free-role page that still cannot act after this long stops rather than hanging. */
internal const val EVENT_FREE_ROLE_GIVE_UP_MILLIS = 45_000L

private const val MAP_SCROLL_DURATION_MILLIS = 450L

/** Drag one viewport-sized segment while staying clear of the persistent bottom controls. */
internal fun labyrinthMapSwipe(
    frameWidth: Int,
    frameHeight: Int,
    direction: LabyrinthMapScanDirection,
    avoidRects: List<EntryPixelRect> = emptyList(),
): AutomationAction.Swipe? {
    if (frameWidth <= 0 || frameHeight <= 0) return null
    // Travel is measured in node columns, not in screen widths. The old gesture dragged 40% of
    // the frame (768 px at 1920) while one column is ~540 px, so a single swipe jumped 1.4
    // columns and could carry a node straight through the viewport; the scanner then could not
    // attribute what had moved (geometryReliable=false, uncertainSwipes) and the search burned
    // its budget. Staying under one pitch makes every column pass in front of at least one scan.
    val pitch = labyrinthReferenceColumnPitch(frameHeight)
    val travel = (pitch * MAP_SCROLL_PITCH_RATIO)
        .coerceAtMost(frameWidth * MAP_SCROLL_MAX_FRAME_RATIO.toDouble())
        .toFloat()
    val centerPx = frameWidth * 0.56f
    val (startPx, endPx) = when (direction) {
        LabyrinthMapScanDirection.FORWARD -> (centerPx + travel / 2f) to (centerPx - travel / 2f)
        LabyrinthMapScanDirection.BACKWARD -> (centerPx - travel / 2f) to (centerPx + travel / 2f)
    }
    val yRatios = listOf(0.58f, 0.46f, 0.34f, 0.70f, 0.24f)
    val y = yRatios
        .map { ratio -> frameHeight * ratio }
        .minByOrNull { candidateY ->
            avoidRects.sumOf { rect ->
                fun pointPenalty(x: Float): Int {
                    val margin = maxOf(24, minOf(rect.width, rect.height) / 12)
                    val insideX = x >= rect.left - margin && x <= rect.left + rect.width + margin
                    val insideY = candidateY >= rect.top - margin && candidateY <= rect.top + rect.height + margin
                    return if (insideX && insideY) 1 else 0
                }
                pointPenalty(startPx) + pointPenalty(endPx)
            }
        }
        ?: frameHeight * 0.58f
    return AutomationAction.Swipe(
        start = ScreenPoint(startPx, y),
        end = ScreenPoint(endPx, y),
        durationMillis = MAP_SCROLL_DURATION_MILLIS,
        holdMillis = MAP_GESTURE_HOLD_MILLIS,
    )
}

/** One scan step stays below a single column pitch so no column can slip past unscanned. */
private const val MAP_SCROLL_PITCH_RATIO = 0.80
/** Never exceed this share of the frame on unusually tall/narrow captures. */
private const val MAP_SCROLL_MAX_FRAME_RATIO = 0.34f
/** Hold still before lifting so the game reads ~0 velocity and does not fling the map. */
internal const val MAP_GESTURE_HOLD_MILLIS = 220L

private const val MAP_NUDGE_DURATION_MILLIS = 220L

/**
 * Small side-lane camera perturbation used when the route target should already be nearby but the
 * current background makes classification unstable. Unlike the segmented map scan this gesture
 * deliberately stays near one side and moves only a small fraction of the viewport.
 */
internal fun labyrinthMapNudge(
    frameWidth: Int,
    frameHeight: Int,
    direction: LabyrinthMapScanDirection,
    avoidRects: List<EntryPixelRect> = emptyList(),
): AutomationAction.Swipe? {
    if (frameWidth <= 0 || frameHeight <= 0) return null
    val (startX, endX) = when (direction) {
        LabyrinthMapScanDirection.FORWARD -> 0.82f to 0.70f
        LabyrinthMapScanDirection.BACKWARD -> 0.18f to 0.30f
    }
    val startPx = frameWidth * startX
    val endPx = frameWidth * endX
    val minX = minOf(startPx, endPx)
    val maxX = maxOf(startPx, endPx)
    val y = listOf(0.58f, 0.46f, 0.34f, 0.70f, 0.24f)
        .map { frameHeight * it }
        .minByOrNull { candidateY ->
            avoidRects.count { rect ->
                val margin = maxOf(24, minOf(rect.width, rect.height) / 12)
                val crossesX = maxX >= rect.left - margin && minX <= rect.left + rect.width + margin
                val crossesY = candidateY >= rect.top - margin && candidateY <= rect.top + rect.height + margin
                crossesX && crossesY
            }
        } ?: frameHeight * 0.58f
    return AutomationAction.Swipe(
        start = ScreenPoint(startPx, y),
        end = ScreenPoint(endPx, y),
        durationMillis = MAP_NUDGE_DURATION_MILLIS,
        holdMillis = MAP_GESTURE_HOLD_MILLIS,
    )
}

/** center -> left -> center -> right -> center, then repeat. */
/**
 * The nudge cycle is BACKWARD, FORWARD, FORWARD, BACKWARD: it returns the camera to where it
 * started, so a further cycle cannot expose anything the previous one failed to classify.
 * 2026-09-15/18 bundles show it repeating 90 and 170 times on one node. Stop after a whole
 * number of cycles and re-arm the segmented scan, which does move the camera somewhere new.
 */
internal fun labyrinthNodeRecoveryNudgeExhausted(
    completedNudges: Int,
    maximumNudges: Int = MAX_NODE_RECOVERY_NUDGES,
): Boolean = completedNudges >= maximumNudges

/** Whole nudge cycles (multiple of 4) before the search falls back to scrolling again. */
internal const val MAX_NODE_RECOVERY_NUDGES = 8

internal fun labyrinthNodeRecoveryNudgeDirection(completedNudges: Int): LabyrinthMapScanDirection {
    require(completedNudges >= 0)
    return when (completedNudges % 4) {
        0, 3 -> LabyrinthMapScanDirection.BACKWARD
        else -> LabyrinthMapScanDirection.FORWARD
    }
}

/** Compatibility helper retained for policy tests and callers that explicitly need later columns. */
internal fun labyrinthForwardMapSwipe(frameWidth: Int, frameHeight: Int): AutomationAction.Swipe? =
    labyrinthMapSwipe(frameWidth, frameHeight, LabyrinthMapScanDirection.FORWARD)

/**
 * Bounded fallback when no visual node survives classification and therefore no viewport
 * signature exists. Follow the protocol column first, then reverse for the second half of the
 * retry budget so an unknown camera position cannot leave execution waiting forever.
 */
internal fun labyrinthBlindMapScanPlan(
    currentNodeId: Long,
    targetLogicalColumn: Int,
    completedAttempts: Int,
    reverseAfterAttempts: Int,
): LabyrinthNodeScanPlan {
    require(completedAttempts >= 0)
    require(reverseAfterAttempts > 0)
    val currentLogicalColumn = (currentNodeId / 100L % 100L).toInt()
    val preferred = when {
        targetLogicalColumn > currentLogicalColumn -> LabyrinthMapScanDirection.FORWARD
        targetLogicalColumn < currentLogicalColumn -> LabyrinthMapScanDirection.BACKWARD
        else -> LabyrinthMapScanDirection.FORWARD
    }
    val direction = if (completedAttempts < reverseAfterAttempts) {
        preferred
    } else {
        when (preferred) {
            LabyrinthMapScanDirection.FORWARD -> LabyrinthMapScanDirection.BACKWARD
            LabyrinthMapScanDirection.BACKWARD -> LabyrinthMapScanDirection.FORWARD
        }
    }

    return LabyrinthNodeScanPlan(
        direction = direction,
        reason = "blind-empty-classifications currentColumn=$currentLogicalColumn " +
            "targetColumn=$targetLogicalColumn completed=$completedAttempts",
    )
}

/** Only a recognized destination page proves that the game accepted a map-node tap. */
internal fun labyrinthConfirmsNodeEntry(state: LabyrinthEntryPageState): Boolean = when (state) {
    LabyrinthEntryPageState.CHARACTER_JOINED,
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
    LabyrinthEntryPageState.LINK_CHOICE,
    LabyrinthEntryPageState.RELIC_CHOICE,
    LabyrinthEntryPageState.EVENT_CHOICE,
    LabyrinthEntryPageState.EVENT_ANIMATION,
    LabyrinthEntryPageState.ITEM_REWARD,
    LabyrinthEntryPageState.SHOP,
    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
    LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
    LabyrinthEntryPageState.BATTLE_CHALLENGE,
    LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
    LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
    LabyrinthEntryPageState.BATTLE_RESULT,
    LabyrinthEntryPageState.RUN_CLEAR_RESULT,
    LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
    LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
    LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
    -> true

    else -> false
}

/**
 * A generic recognized page is not enough to advance the route: the destination must also be
 * compatible with the node that was clicked. This protects the route cursor from stale popups
 * and from a map false positive that happens to be followed by another page.
 */
internal fun labyrinthNodeEntryMatchesExpectedType(
    blockType: Int,
    pageState: LabyrinthEntryPageState,
): Boolean = when (blockType) {
    LabyrinthNodeTypes.NORMAL_BATTLE,
    LabyrinthNodeTypes.EX_BATTLE,
    LabyrinthNodeTypes.BOSS,
    -> pageState in setOf(
        LabyrinthEntryPageState.BATTLE_CHALLENGE,
        LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
        LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
        LabyrinthEntryPageState.BATTLE_FAILED,
        LabyrinthEntryPageState.BATTLE_RESULT,
    )

    // A link node opens LINK_CHOICE, but that page can be gone before the next sampled frame:
    // the imprint pick is followed by the three-character ITEM_REWARD popup and then the role
    // reward selection. Those pages are already accepted by labyrinthConfirmsNodeEntry; refusing
    // them here left the route cursor one node behind while the reward chain kept running
    // (2026-09-16 11:29 bundle: link#20601 cleared, role picked, cursor still at 20501).
    LabyrinthNodeTypes.LINK -> pageState in setOf(
        LabyrinthEntryPageState.LINK_CHOICE,
        LabyrinthEntryPageState.ITEM_REWARD,
        LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
        LabyrinthEntryPageState.CHARACTER_JOINED,
    )
    LabyrinthNodeTypes.RELIC -> pageState == LabyrinthEntryPageState.RELIC_CHOICE
    LabyrinthNodeTypes.EVENT -> pageState in setOf(
        LabyrinthEntryPageState.EVENT_CHOICE,
        LabyrinthEntryPageState.EVENT_ANIMATION,
        LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
        LabyrinthEntryPageState.CHARACTER_JOINED,
        LabyrinthEntryPageState.ITEM_REWARD,
    )
    LabyrinthNodeTypes.SHOP -> pageState in setOf(
        LabyrinthEntryPageState.SHOP,
        LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
        LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
        LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
    )
    else -> false
}

/** Only a stable, actionable destination may override stale route node metadata. */
internal fun labyrinthObservedDestinationType(
    state: LabyrinthEntryPageState,
    expectedType: Int? = null,
): Int? = when (state) {
    LabyrinthEntryPageState.SHOP,
    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
    LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION -> LabyrinthNodeTypes.SHOP
    LabyrinthEntryPageState.EVENT_CHOICE,
    LabyrinthEntryPageState.EVENT_ANIMATION -> LabyrinthNodeTypes.EVENT
    LabyrinthEntryPageState.LINK_CHOICE -> LabyrinthNodeTypes.LINK
    LabyrinthEntryPageState.RELIC_CHOICE -> LabyrinthNodeTypes.RELIC
    LabyrinthEntryPageState.BATTLE_CHALLENGE,
    LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
    LabyrinthEntryPageState.BATTLE_IN_PROGRESS ->
        expectedType.takeIf { it in setOf(LabyrinthNodeTypes.BOSS, LabyrinthNodeTypes.EX_BATTLE) }
            ?: LabyrinthNodeTypes.NORMAL_BATTLE
    // Generic reward/transition screens do not identify the node that produced them.
    else -> null
}

/** Coarse signature used to detect that a map swipe produced no viewport movement. */
internal fun labyrinthNodeViewportSignature(classifications: List<NodeClassification>): String =
    classifications
        .mapNotNull { node ->
            node.screenRect?.let { rect ->
                listOf(
                    node.blockType,
                    if (node.isClickable) 1 else 0,
                    (rect.left + rect.width / 2) / NODE_VIEWPORT_SIGNATURE_BUCKET_PX,
                    (rect.top + rect.height / 2) / NODE_VIEWPORT_SIGNATURE_BUCKET_PX,
                ).joinToString(":")
            }
        }
        .sorted()
        .joinToString("|")

/** Only read-only diagnostics may draw the normal target box into frames captured by MediaProjection. */
internal fun labyrinthShowsNextNodeOverlayBox(dryRun: Boolean): Boolean = dryRun

/**
 * Node diagnostics are drawn only while the session is paused. While running, MediaProjection
 * can capture the annotation layer and feed its colored boxes back into node recognition.
 */
internal fun labyrinthShowsPausedNodeDiagnostics(paused: Boolean): Boolean = paused

private const val MIN_NODE_SELECTION_FRAMES_BEFORE_TRANSITION_RETRY = 2

/**
 * Node recognition can take roughly as long as the transition timeout on a dense map. One stale
 * map frame captured around the tap must not erase the pending transition before the next frame
 * can observe the movement dialog.
 */
internal fun labyrinthKeepsPendingNodeTransition(
    elapsedMillis: Long,
    timeoutMillis: Long,
    nodeSelectionFramesSinceAction: Int,
): Boolean =
    elapsedMillis < timeoutMillis ||
        nodeSelectionFramesSinceAction < MIN_NODE_SELECTION_FRAMES_BEFORE_TRANSITION_RETRY

/**
 * Whether the completed "有效效果" roster sweep of the node being left is still the answer for the
 * node being entered. The sweep is a property of the encounter, which an EX fight and its Boss
 * share; any other transition is a different encounter or no encounter at all.
 */
internal fun labyrinthKeepsEffectiveScanAcrossNodes(previousNodeType: Int?, nextNodeType: Int): Boolean =
    previousNodeType == LabyrinthNodeTypes.EX_BATTLE && nextNodeType == LabyrinthNodeTypes.BOSS

/**
 * Order-independent fingerprint of the owned roster. The filtered list only shows owned roles, so
 * a sweep taken before a role joined can be stale even though the encounter is unchanged.
 */
internal fun labyrinthEffectiveScanRosterStamp(characterIds: Collection<String>): Long =
    characterIds.map(::canonicalLabyrinthRoleId).sorted().fold(characterIds.size.toLong()) { acc, id ->
        acc * 1_000_003L + id.hashCode()
    }

/**
 * Whether a node-page frame captured now would actually be read by [handleNodeSelectionFrame].
 *
 * The frame is thrown away in two situations: the map is still settling after a popup was closed
 * (`settleRemainingMillis > 0`), and the session is still waiting for the game to react to a node
 * tap (`pendingTransitionHeld`). The typed/full node scan costs 3-8 s per frame on a dense map;
 * 2026-09-20 bundle 225220 spent 75 s of its 130 s node-stage budget scanning 42 frames that ended
 * in one of those two early returns. Skipping the scan there changes no decision: the frame that
 * would have been read next is the first one that passes both gates, and the tracker still sees it.
 */
internal fun labyrinthNodeScanConsumable(
    settleRemainingMillis: Long,
    pendingTransitionHeld: Boolean,
): Boolean = settleRemainingMillis <= 0L && !pendingTransitionHeld

/**
 * Milliseconds a post-entry tap's cooldown still has to run when a frame captured at
 * [frameTimestampMillis] is being decided; `<= 0` means the frame may act.
 *
 * The cooldown used to count from the capture time of the frame that *decided* the previous tap.
 * The tap itself lands asynchronously, 0.3-1.1 s later on the 2026-09-20 bundle 225220 device,
 * and a joined-character popup needs a further ~0.3-0.8 s to fade. So a frame captured before
 * the tap had even reached the screen — or 292 ms after it — still showed the popup, passed a
 * 650 ms cooldown measured from the older stamp, and closed it a second time. Three of the eight
 * popups in that run were double-closed; one second tap went through the fading popup onto the
 * map node behind it and raised a movement dialog that then had to be cancelled.
 *
 * Counting from the moment the gesture actually landed ([landedAtMillis]) when that is later
 * than the deciding frame makes the cooldown mean what it says: time the game has had to react.
 */
internal fun labyrinthPostEntryCooldownRemaining(
    frameTimestampMillis: Long,
    dispatchedAtMillis: Long,
    landedAtMillis: Long,
    intervalMillis: Long,
): Long {
    if (dispatchedAtMillis == Long.MIN_VALUE) return 0L
    val reference = maxOf(dispatchedAtMillis, landedAtMillis)
    return intervalMillis - (frameTimestampMillis - reference)
}

/**
 * After a node or its movement-confirmation button was tapped, the game can render one or more
 * transitional frames before its semantic page becomes recognizable. Those frames must not erase
 * the pending route transition; otherwise the game can enter/finish a node while the local route
 * cursor remains one step behind.
 *
 * This does not authorize any click. It only keeps the already-dispatched semantic transition
 * alive until a real destination page confirms it or the bounded entry timeout expires.
 */
internal fun labyrinthPreservesPendingNodeTransitionAcrossPage(
    pageState: LabyrinthEntryPageState,
    confirmationDispatchedAtMillis: Long?,
    nowMillis: Long,
    timeoutMillis: Long,
    dispatchedAtMillis: Long? = null,
): Boolean {
    val startedAt = confirmationDispatchedAtMillis ?: dispatchedAtMillis ?: return false
    val elapsed = (nowMillis - startedAt).coerceAtLeast(0L)
    if (elapsed >= timeoutMillis) return false
    return pageState in setOf(
        LabyrinthEntryPageState.UNKNOWN,
        LabyrinthEntryPageState.NODE_MAP_VIEW,
        LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
        LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
    )
}

/**
 * While a destination-type mismatch is still being tolerated (see NODE_ENTRY_MISMATCH_STABLE_FRAMES),
 * the pending transition must survive the page change that carried the mismatched frame.
 * Otherwise the tolerance never gets a second frame to look at: the semantic page invalidates the
 * proposal immediately, and receipt recovery is refused whenever the source node has more than one
 * reachable successor (2026-09-16 17:40 bundle: link#10503 read as RELIC_CHOICE for one frame,
 * then LINK_CHOICE with no pending transition; reachable=[10501,10502,10503]).
 */
internal fun labyrinthPreservesPendingNodeTransitionForEntryMismatch(
    mismatchFrames: Int,
    stableFrames: Int,
    confirmationDispatchedAtMillis: Long?,
    nowMillis: Long,
    timeoutMillis: Long,
    dispatchedAtMillis: Long? = null,
): Boolean {
    if (mismatchFrames <= 0 || mismatchFrames >= stableFrames) return false
    val startedAt = confirmationDispatchedAtMillis ?: dispatchedAtMillis ?: return false
    return (nowMillis - startedAt).coerceAtLeast(0L) < timeoutMillis
}

/** Same physical node across frames: centre drift within 18% of the crop (at least 16 px). */
internal fun labyrinthNodeClickRectsAreStable(
    previous: EntryPixelRect?,
    current: EntryPixelRect?,
): Boolean {
    if (previous == null || current == null) return previous == current
    val previousCenterX = previous.left + previous.width / 2
    val previousCenterY = previous.top + previous.height / 2
    val currentCenterX = current.left + current.width / 2
    val currentCenterY = current.top + current.height / 2
    val xTolerance = maxOf(16, minOf(previous.width, current.width) * 18 / 100)
    val yTolerance = maxOf(16, minOf(previous.height, current.height) * 18 / 100)
    return kotlin.math.abs(previousCenterX - currentCenterX) <= xTolerance &&
        kotlin.math.abs(previousCenterY - currentCenterY) <= yTolerance
}

/** A complete, ordered column binding is the strongest slot evidence the mapper produces. */
internal fun labyrinthNodeMappingHasStrongOrderedTopology(
    mapping: NodePositionMapping,
    minimumConfidence: Double = 0.90,
): Boolean = !mapping.routeSemanticRepair &&
    mapping.topologyBindingKind == NodeTopologyBindingKind.FULL_COLUMN &&
    (mapping.topologyConfidence ?: 0.0) >= minimumConfidence

/**
 * A route target located by a strong FULL_COLUMN binding moments ago must not be re-bound to a
 * different screen rect by weaker evidence while the camera has not moved.
 *
 * 2026-09-16 19:17 bundle: link#30402 was bound at (802,350) inside a fully detected three-node
 * column (topology 0.99). Two seconds later the map's glow animation left a single "link" crop
 * straddling rows 1 and 2 at (820,220); route-semantic-repair adopted it as 30402, three such
 * frames satisfied the stability gate, and the tap at y=332 landed on the event node above.
 * Without a map gesture in between, one lone crop carries no row-order evidence and cannot
 * outrank the complete column that was just seen.
 */
internal fun labyrinthNodeClickContradictsRecentStrongBinding(
    rememberedRect: EntryPixelRect?,
    rememberedAtMillis: Long,
    nowMillis: Long,
    memoryMillis: Long,
    candidateRect: EntryPixelRect?,
    candidateHasStrongOrderedTopology: Boolean,
): Boolean {
    if (rememberedRect == null || candidateRect == null) return false
    if (candidateHasStrongOrderedTopology) return false
    if (rememberedAtMillis == Long.MIN_VALUE) return false
    if ((nowMillis - rememberedAtMillis).coerceAtLeast(0L) >= memoryMillis) return false
    return !labyrinthNodeClickRectsAreStable(rememberedRect, candidateRect)
}

private enum class LabyrinthRunTerminalOutcome { CLEARED, FAILED_MAX_RETRY }

private data class PendingNodeTransition(
    val blockId: Long,
    val blockType: Int,
    val label: String,
    val dispatchedAtMillis: Long,
    val confirmationDispatchedAtMillis: Long? = null,
    val confirmationAttempts: Int = 0,
    val nodeSelectionFramesSinceAction: Int = 0,
)

private data class ConfirmedNodeEntryReceipt(
    val transition: PendingNodeTransition,
    val sourceId: Long,
)

internal enum class LabyrinthPostBossStage {
    NONE,
    BEFORE_SCORE,
    SCORE_RESULT,
    CHEST_SEQUENCE,
    FINAL_ITEM_REWARD,
    WAITING_FOR_DAWN_HOME,
}

/**
 * Stage to adopt when the final RESULT page is recognized.
 *
 * The final Boss usually completes the route first, which arms BEFORE_SCORE; the RESULT page
 * then arrives on top of that. The old transition only accepted NONE → SCORE_RESULT, so a run
 * that had already armed BEFORE_SCORE never gained a close-button plan for RESULT and spent its
 * whole animation budget tapping blind fallback points instead (2026-09-17 live: 402 taps,
 * "RUN_CLEAR_RESULT：暂不自动处理"). Any pre-score stage may enter SCORE_RESULT; later stages
 * must not regress.
 */
internal fun labyrinthPostBossStageOnFinalResult(current: LabyrinthPostBossStage): LabyrinthPostBossStage =
    when (current) {
        LabyrinthPostBossStage.NONE,
        LabyrinthPostBossStage.BEFORE_SCORE,
        // The first tap on 关闭 only skips the score count-up animation and the RESULT page stays
        // visible (2026-09-17 02:08 device log: one anchor-centre tap, page unchanged, then no
        // plan). A visible RESULT page is the score stage by definition; the chest sequence has
        // not started until RESULT is gone.
        LabyrinthPostBossStage.CHEST_SEQUENCE,
        -> LabyrinthPostBossStage.SCORE_RESULT
        else -> current
    }

/**
 * The chest-result dialog (宝箱开封结果) is a centred modal with one 确认 button at the bottom
 * centre (1080p ≈ (958,958)), the same shape as the RESULT page's 关闭. The bottom-right point used
 * for the character summary's 下一步 misses it entirely.
 */
internal fun labyrinthChestResultFallbackTap(): LabyrinthFallbackTap = LabyrinthFallbackTap.BOTTOM_CENTER

/** A session popup always takes priority over the normal page classifier and route actions. */
internal data class LabyrinthSessionBlockObservation(
    val kind: SessionBlockKind,
    val returnTitleRect: EntryPixelRect?,
    val actionLabel: String = "返回标题",
) {
    val blocksNormalActions: Boolean get() = kind != SessionBlockKind.NONE
}

internal fun labyrinthSessionBlockObservation(
    result: LabyrinthEntryFrameResult,
    classifier: SessionBlockClassifier = SessionBlockClassifier(),
): LabyrinthSessionBlockObservation {
    // 「特别装备」的两个弹窗是本特性的新 chrome，却与这里「故意做宽」的蓝标题模板同壳：
    // 一旦某帧把它读成会话失效，自动化会去点「返回标题」并把整局丢掉。
    // 窄排除：只在观测确实给出弹窗 stage（≥0.70 的模板证据）时生效；stage 为 null 时——包括
    // 真实的过期/掉线弹窗——本函数逐字不变（反向断言见 LabyrinthSpecialEquipmentSessionFlowTest）。
    if (result.specialEquipment?.stage != null) {
        return LabyrinthSessionBlockObservation(SessionBlockKind.NONE, returnTitleRect = null)
    }
    // The reconnect detector uses a deliberately broad blue-title template. A normal labyrinth
    // movement dialog shares that chrome, so its more specific two-button detector must win.
    if (result.nodeMoveConfirmation != null) {
        return LabyrinthSessionBlockObservation(SessionBlockKind.NONE, returnTitleRect = null)
    }
    // A plain one-button 确认 dialog (无法获得报酬) shares the title chrome too. 2026-09-18 live:
    // it read as a session block and the run waited 255 actions for a 返回标题 that never came.
    if (labyrinthGenericConfirmDialogRect(result) != null) {
        return LabyrinthSessionBlockObservation(SessionBlockKind.NONE, returnTitleRect = null)
    }
    val dateChangeTitleScore =
        result.observation.anchorScores[EntryAnchorId.SESSION_DATE_CHANGE_TITLE]
    if (dateChangeTitleScore >= SESSION_DATE_CHANGE_TITLE_MIN_SCORE) {
        val confirmRect = result.anchorMatches[EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM]
            ?.takeIf { it.score >= SESSION_DATE_CHANGE_CONFIRM_MIN_SCORE }
            ?.rect
        return LabyrinthSessionBlockObservation(
            kind = SessionBlockKind.RELOGIN_REQUIRED,
            returnTitleRect = confirmRect,
            actionLabel = "确认",
        )
    }
    // The shop's own dialogs (purchase confirmation / purchase complete / exit confirmation)
    // reuse the blue title bar the broad reconnect template was cut from, and a transition frame
    // between them classifies UNKNOWN while the shop chrome stays on screen. 2026-09-18 live:
    // every purchase produced "检测到账号会话失效". Without a recognised 返回标题 button there is
    // nothing to act on anyway; a genuine expiry inside the shop still shows that button.
    if (
        labyrinthShopBackgroundVisible(result) &&
        result.observation.anchorScores[EntryAnchorId.SESSION_RETURN_TITLE] < SESSION_RETURN_TITLE_MIN_SCORE
    ) {
        return LabyrinthSessionBlockObservation(SessionBlockKind.NONE, returnTitleRect = null)
    }
    val kind = classifier.classify(
        scores = result.observation.anchorScores.toSessionBlockScores(),
        pageTitleScore = result.observation.stateScores[LabyrinthEntryPageState.TITLE_WAITING_TAP] ?: 0.0,
    )
    val returnTitleRect = if (
        kind == SessionBlockKind.RECONNECT_PROMPTED || kind == SessionBlockKind.RELOGIN_REQUIRED
    ) {
        result.anchorMatches[EntryAnchorId.SESSION_RETURN_TITLE]
            ?.takeIf { it.score >= SESSION_RETURN_TITLE_MIN_SCORE }
            ?.rect
    } else {
        null
    }
    return LabyrinthSessionBlockObservation(kind, returnTitleRect)
}

data class LabyrinthEntryRecognitionSessionState(
    val status: LabyrinthEntryRecognitionStatus = LabyrinthEntryRecognitionStatus.IDLE,
    val sessionId: AutomationSessionId? = null,
    val dryRun: Boolean = true,
    val paused: Boolean = false,
    val receivedFrameCount: Long = 0,
    val frameCount: Long = 0,
    val actionCount: Int = 0,
    val lastActionLabel: String? = null,
    val lastResult: LabyrinthEntryFrameResult? = null,
    val joinedCharacters: List<LabyrinthJoinedCharacter> = emptyList(),
    val observedRelics: List<LabyrinthObservedRelic> = emptyList(),
    val routeProgress: LabyrinthRouteProgress? = null,
    val combatContext: LabyrinthCombatContext? = null,
    val battleTeamRecommendation: LabyrinthBattleTeamRecommendation? = null,
    val battleTeamRecommendationUnavailableReason: String? = null,
    val battleTeamSelectionPlan: LabyrinthBattleTeamSelectionPlan? = null,
    val roleRewardChoiceDecision: LabyrinthRoleRewardChoiceDecision? = null,
    val eventChoiceDecision: LabyrinthEventChoiceDecision? = null,
    val pendingAcquiredCharacterId: String? = null,
    val message: String? = null,
    /** Diagnostic trace for the frame that produced [lastResult]; never read for decisions. */
    val frameTrace: LabyrinthFrameTrace = LabyrinthFrameTrace(),
) {
    val running: Boolean get() = status == LabyrinthEntryRecognitionStatus.RUNNING
}

sealed interface LabyrinthEntryRecognitionStartResult {
    data class Started(val sessionId: AutomationSessionId) : LabyrinthEntryRecognitionStartResult
    data class AlreadyRunning(val sessionId: AutomationSessionId) : LabyrinthEntryRecognitionStartResult
    data class Blocked(val reason: String) : LabyrinthEntryRecognitionStartResult
}

/** Entry recognition session with an explicitly selected read-only or guarded action mode. */
class LabyrinthEntryRecognitionSession(
    private val sessionManager: AutomationSessionManager,
    private val captureActive: () -> Boolean = CaptureStateRegistry::isActive,
    private val captureStop: () -> Unit = {},
    private val processorFactory: (finalBossOnly: () -> Boolean) -> (CapturedFrame) -> LabyrinthEntryFrameResult,
    private val overlayCoordinator: AutomationOverlayCoordinator = AutomationOverlayCoordinator(),
    private val actionExecutor: SessionBoundActionExecutor? = null,
    private val actionsAvailable: () -> Boolean = { actionExecutor != null },
    private val actionTargetReady: () -> Boolean = { true },
    private val gameLauncher: () -> Boolean = { true },
    private val actionPlannerFactory: () -> LabyrinthEntryActionPlanner = { LabyrinthEntryActionPlanner() },
    private var relicChoicePolicy: LabyrinthRelicChoicePolicy = LabyrinthRelicChoicePolicy(),
    private var shopPolicy: LabyrinthShopPolicy = LabyrinthShopPolicy(relicChoicePolicy),
    private var roleRewardChoicePlanner: LabyrinthRoleRewardChoicePlanner? = null,
    private val roleRewardChoiceUnavailableReason: String? = null,
    private var eventChoicePlanner: LabyrinthEventChoicePlanner? = null,
    private var battleTeamRecommendationPlanner: LabyrinthBattleTeamRecommendationPlanner? = null,
    private val battleTeamRecommendationUnavailableReason: String? = null,
    private var battleTeamSelectionPlanner: LabyrinthBattleTeamSelectionPlanner? = null,
    private val runStateStore: LabyrinthRunStateStore? = null,
    private val routeLoader: (suspend (Long?) -> LabyrinthRouteJson?)? = null,
    private val routeProgressSaver: (suspend (Long, Long, Long) -> Boolean)? = null,
    private val routeExecutionGate: (suspend (Long?) -> LabyrinthExecutionGateResult)? = null,
    private val nodeTemplateLoader: (() -> NodeTemplateSet)? = null,
    private val nodeSessionFactory: () -> LabyrinthNodeSession = {
        LabyrinthNodeSession(
            debugLogger = NodeDebugLogger { message ->
                Log.d(NODE_LOG_TAG, "node-mapping $message")
            },
        )
    },
    private val debugFramePublisher: ((CapturedFrame, LabyrinthEntryRecognitionSessionState, LabyrinthEntryFrameResult, AutomationOverlayPresentation) -> Unit)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val strategyProvider: (() -> LabyrinthStrategySnapshot)? = null,
    private val rerollRequester: (suspend (Long) -> Boolean)? = null,
    /**
     * Formal run outcome for an outer batch orchestrator (labyrinth-full-automation.md §11).
     * Fired at most once per run id, after the run has fully stopped. Absent runId = no batch.
     */
    private val runTerminalListener: (suspend (LabyrinthRunTerminalEvent) -> Unit)? = null,
) {
    private data class QueuedFrame(
        val sessionId: AutomationSessionId,
        val processor: (CapturedFrame) -> LabyrinthEntryFrameResult,
        val frame: CapturedFrame,
    )

    private val mutex = Mutex()
    private val persistenceMutex = Mutex()
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actionInFlight = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val queuedFrame = AtomicReference<QueuedFrame?>(null)
    private val frameWorkerRunning = AtomicBoolean(false)
    private val actionPlannerLock = Any()
    private val nodeActionPlanner = LabyrinthNodeActionPlanner()
    private val nodeViewportScanner = LabyrinthNodeViewportScanner()
    private val sessionBlockClassifier = SessionBlockClassifier()
    private val _state = MutableStateFlow(LabyrinthEntryRecognitionSessionState())
    val state: StateFlow<LabyrinthEntryRecognitionSessionState> = _state.asStateFlow()

    /**
     * Supplies the frame processor with route-aware search bounds without letting the vision layer
     * read mutable session internals directly. A missing viewport fit deliberately keeps the full
     * scan so the first reliable topology fit can be established instead of inventing coordinates.
     */
    /**
     * The map scan is only worth running once a route session can consume it. The first map frame
     * after entry only starts asynchronous route/template loading and returns; scanning it burns
     * the whole 16-anchor full sweep for nothing.
     */
    fun nodeScanRequested(): Boolean = nodeSession != null

    /**
     * Whether the next node-page frame's classifications would be consumed. Mirrors the two early
     * returns at the top of [handleNodeSelectionFrame]; see [labyrinthNodeScanConsumable].
     *
     * The pending-transition check deliberately counts the frame being decided as one more
     * node-page frame, so the retry guard in [labyrinthKeepsPendingNodeTransition] still lets the
     * second post-timeout frame through to be scanned and to expire the transition.
     */
    fun nodeScanConsumable(): Boolean {
        val now = clock()
        val lastAction = lastPostEntryActionAt
        val settleRemaining = if (lastAction == Long.MIN_VALUE) {
            0L
        } else {
            NODE_MAP_SETTLE_AFTER_POST_ACTION_MILLIS - (now - lastAction)
        }
        val pending = pendingNodeTransition
        val pendingHeld = pending != null && labyrinthKeepsPendingNodeTransition(
            elapsedMillis = now - (pending.confirmationDispatchedAtMillis ?: pending.dispatchedAtMillis),
            timeoutMillis = if (pending.confirmationDispatchedAtMillis == null) {
                NODE_MOVE_CONFIRMATION_APPEAR_TIMEOUT_MILLIS
            } else {
                NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS
            },
            nodeSelectionFramesSinceAction = pending.nodeSelectionFramesSinceAction + 1,
        )
        return labyrinthNodeScanConsumable(
            settleRemainingMillis = settleRemaining,
            pendingTransitionHeld = pendingHeld,
        )
    }

    /**
     * Roles this run has acquired, as canonical ids — the only roles a team page can show.
     *
     * Empty when the run has not established a roster yet, which the matcher reads as "no
     * restriction" rather than "no candidates".
     */
    fun rosterCharacterIds(): Set<String> = _state.value.joinedCharacters
        .map(LabyrinthJoinedCharacter::characterId)
        .toSet()

    fun currentNodeSearchHint(): NodeSearchHint? {
        val session = nodeSession ?: return null
        val target = session.currentRouteTarget() ?: return null
        val expectedCenterX = nodeViewportScanner.expectedScreenCenterX(target.column)?.toInt()
        return NodeSearchHint(
            targetBlockId = target.blockId,
            expectedCenterX = expectedCenterX,
            expectedTypes = session.searchTypesNearTarget(target),
            reachableColumns = (target.column - 1).coerceAtLeast(1)..(target.column + 1),
            matchedTargetBlockId = session.matchedRouteTargetId(),
        )
    }

    @Volatile
    private var activeSessionId: AutomationSessionId? = null
    @Volatile
    private var activeRunAccountId: Long? = null
    private var lease: CaptureFrameConsumerLease? = null
    private var firstFrameWatchdog: Job? = null
    private var lastProcessedAt = Long.MIN_VALUE
    private var actionPlanner: LabyrinthEntryActionPlanner? = null
    @Volatile
    private var validatedRoute: LabyrinthRouteJson? = null
    private val openingStateReset = AtomicBoolean(false)
    @Volatile
    private var lastPersistedObservationSignature: String? = null
    @Volatile
    private var lastBattleTeamRecommendationKey: String? = null
    @Volatile
    private var lastBattleTeamSelectionPlanLog: String? = null
    @Volatile
    private var lastBattleTeamExecutionKey: String? = null
    @Volatile
    private var battleTeamExecutionAttempts = 0
    @Volatile
    private var battleTeamScrollActions = 0
    private val battleRosterSearch = LabyrinthBattleRosterSearch()
    /** Attribute tabs already scanned to the end for the current set of still-missing roles. */
    private val exhaustedBattleRosterFilters = linkedSetOf<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter>()
    private var exhaustedBattleRosterFiltersKey: String? = null
    @Volatile
    private var lastBattleTeamActionAt = Long.MIN_VALUE
    /**
     * 会话级动作节流：本会话**任意**两次点击之间强制 [BATTLE_TEAM_ACTION_INTERVAL_MILLIS]。
     *
     * 「特别装备」流程与编组/开战路径各有自己的执行跟踪，但游戏只有一个输入口。两者各持一个时间戳时，
     * 弹窗「取消」刚点完 0.76s，编组路径只看到自己的旧时间戳就派发「战斗开始」，被关闭动画吃掉；
     * 所以这两个流程共用这一个时间戳：任一方派发点击都盖上它，任一方派发前都要等它过期。
     */
    @Volatile
    private var lastSessionActionAt = Long.MIN_VALUE
    /**
     * 「编组页动作已经派发、画面却还没有推进」这一件事的全部状态：START_BATTLE 与 NEXT_BOSS_TEAM
     * 共用同一处记账（不回滚在各处各打一块补丁）。
     *
     * 只有「从派发那一刻起每一帧都还是同一个编组页签名」才算「画面没动」——游戏只要收下了这一下，
     * 画面就会换页或换 Boss 页签。超过 [BATTLE_TEAM_ACTION_ACK_TIMEOUT_MILLIS] 即认定这一下没被接受，
     * 回滚本次提交并重新规划；重复失败落在 [MAX_BATTLE_TEAM_ACTION_UNCONFIRMED] 次以内。
     */
    private data class PendingBattleTeamAction(
        val kind: LabyrinthBattleTeamExecutionKind,
        val label: String,
        val confirmedTeamIds: List<String>,
        val signature: String,
        val dispatchedAt: Long,
        /**
         * 派发这一刻之前 [lastNormalBattleTeamSignature] 的值（只有 START_BATTLE 会带）。
         *
         * 「派发后画面没推进」说明这一下没被游戏收下：回收把记忆还原成这个当次捕获的原值，也就是
         * **最近一次派发之前**的值。重试链上的每一次派发都按同一规则重新捕获，因此它不等同于
         * 「最近一场真正开起来的战斗」的编组。
         */
        val previousNormalTeamSignature: String? = null,
    )

    @Volatile
    private var pendingBattleTeamAction: PendingBattleTeamAction? = null
    /** 连续多少次「编组页动作派发后画面没动」；画面前进一次或整页重来即清零。 */
    @Volatile
    private var battleTeamActionUnconfirmedAttempts = 0
    /**
     * 超限停机已经申请、但还没落定的停机文案（非空即表示申请中）。
     *
     * 停机走动作协程（[finishFromPlanner] → `launchGuardedAction`），单动作锁被占的那一帧申请会被
     * 静默丢掉；所以申请之后每帧都要再要一次，直到运行真的停住（与既有 5 分钟战斗等待超时的写法一致）。
     */
    @Volatile
    private var battleTeamActionStopReason: String? = null
    /**
     * 编组页自己的停滞计时戳。
     *
     * [handlePostEntryPage] 走到编组页就 return，通用的 120 秒停滞看门狗对这个页面永远不可达
     * （[LabyrinthPostEntryTapPlan] 里 BATTLE_TEAM_SELECTION 是显式 null），所以本页必须自己记账。
     */
    @Volatile
    private var battleTeamPageStallSince = Long.MIN_VALUE
    /**
     * 「特别装备」自动穿戴的本轮状态（纯状态机见 [labyrinthSpecialEquipmentDecision]）。
     *
     * **刻意不放进 [resetBattleTeamExecutionTracking]**：那个函数会在「页面状态变成编组页」时被调用
     * （`handlePostEntryPage` 里进入 BATTLE_TEAM_SELECTION 的那一支），而弹窗开合本身就会让页面
     * 状态抖动，放进去会让本轮标记被抖掉、流程半途重来。这里的标记只在「稳定地离开编组页」时清零。
     */
    @Volatile
    private var specialEquipmentFlowStage = LabyrinthSpecialEquipmentFlowStage.INACTIVE
    @Volatile
    private var specialEquipmentStepAttempts = 0
    @Volatile
    private var specialEquipmentStepStartedAt = Long.MIN_VALUE
    /** 本轮是否真的看到过弹窗（stage 非空）；决定「弹窗刚关」这一帧要不要让给关闭动画。 */
    @Volatile
    private var specialEquipmentDialogObserved = false
    /**
     * 「特别装备弹窗刚关闭」这一帧已经交给编组路径、但还没被让掉。
     *
     * 弹窗关闭动画期间点在「战斗开始」上会被游戏吞掉，所以关闭后的第一帧只让一帧：
     * 由 [handleBattleTeamSelectionExecution] 消费，消费的那一帧不派发任何编组/开战点击。
     */
    @Volatile
    private var specialEquipmentModalCloseSettlePending = false
    /** 连续多少帧落在非编组页（UNKNOWN 不累计）；达到 [SPECIAL_EQUIPMENT_LEAVE_STABLE_FRAMES] 才算离开。 */
    @Volatile
    private var specialEquipmentAwayFrames = 0
    /**
     * 超限逃生那次点击（点弹窗1 左下白色「取消」）已经决定、但还没派发出去时的待办。
     *
     * 决策只在「超限的那一帧」带 tap，而 stage 那时已经推进到 DONE，后续帧的 DONE 决策不再带 tap；
     * 没有这个待办字段，逃生点击会被动作间隔或帧序吞掉：表现为「只被决定、从未点击」。
     * 待办本身也有窗口（[specialEquipmentEscapeDeadline]）：窗口内始终派发不出去就放弃这次点击，
     * 由「本轮已结束但弹窗仍开着」的分支停机并保留诊断。
     */
    @Volatile
    private var specialEquipmentPendingEscape: LabyrinthSpecialEquipmentTap? = null
    /** 逃生点击派发后的等弹窗关闭上限；超期仍未关就停机并保留诊断。 */
    @Volatile
    private var specialEquipmentEscapeDeadline = Long.MIN_VALUE
    /** 超限/放弃时的诊断原文：后续帧不得用「本轮已结束，继续编组与开战」把它覆盖掉。 */
    @Volatile
    private var specialEquipmentDiagnostic: String? = null
    /**
     * 「装备确定」两态判定的连续同类证据计数（跨帧稳定）：单帧（尤其是弹窗切换的过渡帧）不下结论。
     * 由 [labyrinthSpecialEquipmentEquipEvidenceStreak] 每帧推进，`confirmedEnabled` 才交给决策。
     */
    @Volatile
    private var specialEquipmentEquipEvidence = LabyrinthSpecialEquipmentEquipEvidenceStreak()
    /**
     * 本轮是不是**放弃**收尾（超限、或入口缺失跳过）。
     *
     * 它只影响停机文案：既可能因为「超限放弃 + 弹窗没关」停机，也可能因为「本轮已正常结束 + 弹窗
     * 又被打开」停机，两者不能共用一句「已放弃本轮」。
     */
    @Volatile
    private var specialEquipmentAbandoned = false
    /**
     * 本轮武装时所在的队伍页签（非「Boss 多队编组页的页签」一律为 null）。
     *
     * Boss 多队编组页上的每个页签是一支独立的队伍，所以页签是「一轮」的身份之一：页签一变，上一个
     * 页签的结束标记不属于当前队伍，必须重新武装，否则队伍2 会沿用队伍1 的标记而永不穿戴。
     */
    @Volatile
    private var specialEquipmentRoundBossTeamIndex: Int? = null
    private val committedBattleCharacterIds = linkedSetOf<String>()
    /** Teams actually committed during the current combat attempt, grouped by team signature. */
    private val currentBattleTeamSignatures = linkedSetOf<String>()
    /**
     * 上一场**普通**关卡的编组签名（[labyrinthBattleTeamSignature]）。
     *
     * 只有普通关卡的 START_BATTLE 会把它更新为本场编组；EX / Boss 的战斗不会改变它（回收时写回的只是
     * 当次捕获的原值）。刻意不放进 [resetBattleTeamExecutionTracking] 与单场重置：它是跨战斗的记忆，
     * 本轮普通关卡若与它完全相同就跳过特别装备流程。
     */
    @Volatile
    private var lastNormalBattleTeamSignature: String? = null
    /** Exact team signatures observed in failed attempts for this node. Never submit them again. */
    private val failedBattleTeamSignatures = linkedSetOf<String>()
    @Volatile
    private var battleRetryCount = 0
    private var bossEditorTeamIndex: Int? = null
    private var pendingBossTeamAdvance: Pair<Int, List<String>>? = null
    private var bossEditorBlocked = false
    private var bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.INACTIVE
    private var preparedBossFirstTeamIds: List<String> = emptyList()
    /** Chosen safe team count for the current Boss multi-team attempt; 1/2 teams leave later tabs empty. */
    private var bossMultiTeamTargetCount: Int? = null
    private var configuredBossTeamMode: LabyrinthBossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM
    /** User-configured opening picks per guild; empty means every guild keeps its shipped plan. */
    private var configuredOpeningRosters: Map<Int, List<List<String>>> = emptyMap()
    /** Names for ids an override may reference, so opening progress messages stay readable. */
    private var roleDisplayNames: Map<String, String> = emptyMap()
    private var bossTeamMode: LabyrinthBossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM
    private var preferPureBossDamageSystem: Boolean = true
    /**
     * 启动时抓取的设置快照，只用来回答「本次战斗的关卡类型要不要自动穿戴特别装备」：
     * 运行中保存新配置不改变本轮，只影响下一次启动。
     */
    private var specialEquipmentSettings: LabyrinthStrategySettings? = null
    private var singleBossFallbackToMultiAfterThreeFailures: Boolean = false
    private var singleBossRetryCountBeforeMulti: Int = 2
    private var pendingSingleBossFallbackToMulti: Boolean = false
    /**
     * True once the single-team Boss attempts were exhausted and the editor was switched to the
     * temporary multi-team mode. Every team recommendation for this Boss then uses the relaxed
     * vanguard gate; otherwise a roster with one real tank silently plans one team again.
     */
    private var bossFallbackMultiTeamActive: Boolean = false
    private var rerollAfterThreeBattleFailures: Boolean = false
    /** Batch-assigned identity of the current run; null when started interactively. */
    @Volatile
    private var currentRunId: String? = null
    /** Set by the terminal paths before stop() so stop() can publish the right event. */
    @Volatile
    private var pendingTerminalOutcome: LabyrinthRunTerminalOutcome? = null
    @Volatile
    private var lastObservedPageState: LabyrinthEntryPageState? = null
    @Volatile
    private var pendingAcquiredCharacterId: String? = null
    /**
     * Role rewards can be selected in a batch and only materialize as CHARACTER_JOINED popups
     * after every selection has finished. Keep every selected id until its joined popup is
     * positively observed; the public single id remains only a compact diagnostic head.
     */
    private val pendingAcquiredCharacterIds = linkedSetOf<String>()
    @Volatile
    var skipRoleRewardJoinedRecognition: Boolean = false
        private set
    private var roleRewardDecisionCacheKey: String? = null
    @Volatile
    private var pendingShopPurchase: LabyrinthRelicMatch? = null
    @Volatile
    private var plannedShopPurchaseRelicId: String? = null
    @Volatile
    private var plannedShopRoleImprintLabel: String? = null
    /** Set when a purchased 选择印记 still owes us a character pick. */
    @Volatile
    private var shopChoiceImprintRolePending = false
    @Volatile
    private var shopImprintRewardConfirmedAt = Long.MIN_VALUE
    @Volatile
    private var plannedShopPurchaseStartedAt = Long.MIN_VALUE
    @Volatile
    private var plannedShopPurchaseCounted = false
    @Volatile
    private var plannedShopRefreshStartedAt = Long.MIN_VALUE
    @Volatile
    private var plannedShopRefreshConfirmedAt = Long.MIN_VALUE
    @Volatile
    private var shopRelicPurchasesThisCycle = 0
    @Volatile
    private var pendingRelicSelection: LabyrinthRelicMatch? = null
    @Volatile
    private var relicChoiceCommitted = false
    private val relicAcquisitionGate = LabyrinthRelicAcquisitionGate()
    @Volatile
    private var eventChoiceCommitted = false
    private var eventChoiceCommittedAt = Long.MIN_VALUE
    private var eventChoiceCommitAttempts = 0
    /** The choice whose tap is currently being waited on, so a refusal can be attributed to it. */
    private var eventChoiceCommittedChoiceId: String? = null
    /** Choices tapped on this event visit that left the page unchanged; see the planner's KDoc. */
    private val eventChoiceRejectedIds = linkedSetOf<String>()
    @Volatile
    private var relicFocusMark: LabyrinthRelicMark? = null
    /** Latest game-visible "当前：N" values. These override history-derived stacks when known. */
    private val relicStackLedger = LabyrinthRelicStackLedger()
    private val relicMarkStackCalibration get() = relicStackLedger.stacks
    /** A single OCR read is evidence, not truth; require repeated/same-frame corroboration. */
    @Volatile
    private var lastShopDialogState: LabyrinthShopDialogState = LabyrinthShopDialogState.NONE
    private val nodeConflictRecovery = com.landosol.toolbox.labyrinth.node.LabyrinthNodeConflictRecovery()
    @Volatile
    private var nodeSession: LabyrinthNodeSession? = null
    private val nodeInitStarted = AtomicBoolean(false)
    @Volatile
    private var nodeInitFailedReason: String? = null
    @Volatile
    private var finalBossLocalizationStartedAt = Long.MIN_VALUE
    @Volatile
    private var entryPhaseComplete = false
    @Volatile
    private var pendingNodeClickBlockId: Long = Long.MIN_VALUE
    @Volatile
    private var pendingNodeClickRect: EntryPixelRect? = null
    @Volatile
    private var pendingNodeClickStableFrames = 0
    @Volatile
    private var strongRouteTargetBindingBlockId: Long = Long.MIN_VALUE
    @Volatile
    private var strongRouteTargetBindingRect: EntryPixelRect? = null
    @Volatile
    private var strongRouteTargetBindingAtMillis: Long = Long.MIN_VALUE
    @Volatile
    private var nodeScrollAttempts = 0
    /** Completed scan -> nudge -> scan cycles for the current target; bounds an unwinnable search. */
    private var nodeSearchRearmCount = 0
    @Volatile
    private var nodeRecoveryNudgeBlockId = Long.MIN_VALUE
    @Volatile
    private var nodeRecoveryNudgeCount = 0
    @Volatile
    private var lastNodeSwipeAvoidRects: List<EntryPixelRect> = emptyList()
    @Volatile
    private var pendingNodeScrollBlockId = Long.MIN_VALUE
    @Volatile
    private var pendingNodeScrollViewportSignature: String? = null
    @Volatile
    private var pendingNodeScrollStableFrames = 0
    @Volatile
    private var pendingNodeScrollStartedAt = Long.MIN_VALUE
    @Volatile
    private var lastNodeActionAt = Long.MIN_VALUE
    @Volatile
    private var pendingNodeTransition: PendingNodeTransition? = null
    // Semantic receipt survives page-local invalidation during title/loading re-entry.
    // Keep transition + source atomic so frame processing can never observe a mismatched pair.
    @Volatile
    private var confirmedNodeEntryReceipt: ConfirmedNodeEntryReceipt? = null
    @Volatile
    private var nodeTapAttempts = 0
    @Volatile
    private var nodeMoveConfirmationStableFrames = 0
    @Volatile
    private var lastNodeMoveConfirmationRect: EntryPixelRect? = null
    @Volatile
    private var nodeEntryMismatchFrames = 0
    private var nodeEntryMismatchPage: LabyrinthEntryPageState? = null
    @Volatile
    private var orphanMoveConfirmationStableFrames = 0
    @Volatile
    private var orphanMoveConfirmationDismissAttempts = 0
    /** Consecutive frames with no movement dialog; proof a cancel worked, which refills the budget. */
    private var orphanMoveConfirmationClearFrames = 0
    @Volatile
    private var lastOrphanMoveConfirmationDismissAt = Long.MIN_VALUE
    @Volatile
    private var lastNodeScrollSourceSignature: String? = null
    @Volatile
    private var nodeErrorStreak = 0
    @Volatile
    private var routeNodeCount = 0
    @Volatile
    private var lastPostEntryState: LabyrinthEntryPageState? = null
    @Volatile
    private var postEntryStableFrames = 0
    @Volatile
    private var postEntryAttempts = 0
    @Volatile
    private var lastPostEntryActionAt = Long.MIN_VALUE
    /** Wall-clock time the last post-entry tap was reported executed; see [labyrinthPostEntryCooldownRemaining]. */
    @Volatile
    private var lastPostEntryActionLandedAt = Long.MIN_VALUE
    @Volatile
    private var postEntryUnknownSince = Long.MIN_VALUE
    /** When the single-action latch was claimed; see [beginGuardedAction]. */
    @Volatile
    private var actionInFlightSince = Long.MIN_VALUE
    /** Start of the current no-action, no-page-change, no-message-change stretch. */
    @Volatile
    private var postEntryStallSince = Long.MIN_VALUE
    /** First frame of an uninterrupted relic-detail popup; that popup is a human's to close. */
    @Volatile
    private var relicDetailHoldSince = Long.MIN_VALUE
    /** First frame after the stray-dialog cancel budget ran out. */
    @Volatile
    private var orphanMoveConfirmationExhaustedSince = Long.MIN_VALUE
    @Volatile
    private var postEntryStallMessage: String? = null
    private val battleWait = LabyrinthBattleWaitPolicy()
    @Volatile
    private var characterAcquisitionActive = false
    @Volatile
    private var characterAcquisitionClicks = 0
    @Volatile
    private var characterAcquisitionStartedAt = Long.MIN_VALUE
    @Volatile
    private var lastCharacterAcquisitionActionAt = Long.MIN_VALUE
    @Volatile
    private var waitingForManualRoleSelection = false
    @Volatile
    private var roleRewardChoiceCommitted = false
    @Volatile
    private var existingRunResumeHandoffArmed = false
    @Volatile
    private var roleRewardBatchActive = false
    @Volatile
    private var committedRoleRewardSelectionSignature: String? = null
    @Volatile
    private var roleRewardJoinedSequenceStarted = false
    @Volatile
    private var lastObservedRoleRewardSelectionSignature: String? = null
    @Volatile
    private var postBossStage = LabyrinthPostBossStage.NONE
    @Volatile
    private var postBossUnknownAttempts = 0
    @Volatile
    private var postBossStartedAt = Long.MIN_VALUE
    @Volatile
    private var combatContext: LabyrinthCombatContext? = null
    @Volatile
    private var currentExEncounterStrategy: LabyrinthExEncounterStrategy? = null
    /**
     * An EX detail probe may reliably confirm that this is an EX encounter while its monster name
     * has no entry in the local guide catalog.  That must not turn a playable challenge into a
     * terminal batch failure: after closing the automation-owned detail modal, this flag permits
     * the normal EX team-selection and challenge flow without claiming that a dedicated guide
     * exists.  It is reset for every new route node/session.
     */
    @Volatile
    private var exEncounterFallbackApproved = false
    @Volatile
    private var exSlot3ProbePending = false
    @Volatile
    private var exSlot3ProbeAttempts = 0
    @Volatile
    private var exEncounterProbeStartedAt = Long.MIN_VALUE
    /** Last non-UNKNOWN page the EX handler saw; a challenge page after any other page is a fresh visit. */
    private var exLastKnownPage: LabyrinthEntryPageState? = null
    @Volatile
    private var exIdentityProbeDescription = "EX识别目标"
    /** Official “有效效果” roles for the current encounter; populated by the later filter scan. */
    private val effectiveExCharacterIds = linkedSetOf<String>()
    private val effectiveRosterSearch = LabyrinthBattleRosterSearch()
    @Volatile
    private var effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.IDLE
    /**
     * Owned-roster fingerprint captured when the sweep reached COMPLETE.
     *
     * The sweep describes the encounter, so the EX fight and the Boss of one encounter share it,
     * but the filtered list only shows roles this account owns. A role acquired between the two
     * editors therefore invalidates the saved sweep instead of silently reusing a stale roster.
     */
    @Volatile
    private var effectiveCharacterScanRosterStamp = Long.MIN_VALUE
    @Volatile
    private var effectiveCharacterScanScrollActions = 0
    @Volatile
    private var lastEffectiveCharacterScanActionAt = Long.MIN_VALUE
    @Volatile
    private var effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
    @Volatile
    private var effectiveCharacterScanSkippedUnsafe = false
    private val eventFreeRoleSelectedCharacterIds = linkedSetOf<String>()
    @Volatile
    private var eventFreeRoleLastSelectAt = Long.MIN_VALUE
    private var eventFreeRoleObservedSelectedCount = 0
    /** When the free-role page last had nothing it could act on; bounds the wait. */
    private var eventFreeRoleBlockedSince = Long.MIN_VALUE
    @Volatile
    private var activeNodeType: Int? = null
    @Volatile
    private var activeNodeArea: Int? = null
    @Volatile
    private var eventActionAttempts = 0
    @Volatile
    private var lastSessionBlockKind = SessionBlockKind.NONE
    @Volatile
    private var sessionBlockStableFrames = 0

    /**
     * Consecutive frames that actually resolved a 返回标题 rect, not merely frames spent blocked.
     *
     * [sessionBlockStableFrames] counts every blocked frame, including the stretches where the
     * button is not recognised at all and the run sits on 等待识别“返回标题”按钮. That primes it past
     * the stability floor, so the first frame whose score happens to clear the trigger taps at once,
     * unconfirmed. See [labyrinthSessionReturnTitleStreak].
     */
    @Volatile
    private var sessionReturnTitleStableFrames = 0
    @Volatile
    private var sessionReturnTitleAttempts = 0
    @Volatile
    private var lastSessionReturnTitleActionAt = Long.MIN_VALUE

    suspend fun start(
        dryRun: Boolean = true,
        accountId: Long? = null,
        runId: String? = null,
    ): LabyrinthEntryRecognitionStartResult = mutex.withLock {
        activeSessionId?.let { return LabyrinthEntryRecognitionStartResult.AlreadyRunning(it) }
        sessionManager.current()?.let { running ->
            val reason = "已有${running.mode.name}任务运行"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        if (!dryRun && (actionExecutor == null || !actionsAvailable())) {
            val reason = "无障碍服务未连接；若系统开关显示已开启，请关闭后重新开启"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        if (!captureActive()) {
            val reason = "请先在权限中心开启屏幕捕获"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        var executionGateMessage: String? = null
        val executionRoute = if (!dryRun && nodeExecutionConfigured()) {
            val gate = routeExecutionGate
            if (gate == null) {
                val reason = "未配置路线执行联网门禁，已禁止自动点节点"
                requestCaptureStop()
                _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
                return LabyrinthEntryRecognitionStartResult.Blocked(reason)
            }
            when (val result = runCatching { gate(accountId) }.getOrElse { failure ->
                LabyrinthExecutionGateResult.Blocked(
                    "执行前联网校验失败：${failure.message ?: "未知错误"}",
                )
            }) {
                is LabyrinthExecutionGateResult.Allowed -> {
                    executionGateMessage = result.message
                    result.route
                }
                is LabyrinthExecutionGateResult.Blocked -> {
                    requestCaptureStop()
                    _state.value = _state.value.copy(
                        status = LabyrinthEntryRecognitionStatus.ERROR,
                        message = result.message,
                    )
                    return LabyrinthEntryRecognitionStartResult.Blocked(result.message)
                }
            }
        } else {
            null
        }
        val strategySnapshot = try {
            strategyProvider?.invoke()
        } catch (failure: Exception) {
            requestCaptureStop()
            val reason = "策略配置加载失败：${failure.message}"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        val session = when (val started = sessionManager.start(AutomationMode.LABYRINTH, dryRun = dryRun)) {
            is AutomationSessionStartResult.AlreadyRunning -> {
                val reason = "已有${started.session.mode.name}任务运行"
                _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
                return LabyrinthEntryRecognitionStartResult.Blocked(reason)
            }
            is AutomationSessionStartResult.Started -> started.session
        }
        rerollAfterThreeBattleFailures = false
        singleBossFallbackToMultiAfterThreeFailures = false
        singleBossRetryCountBeforeMulti = 2
        pendingSingleBossFallbackToMulti = false
        bossFallbackMultiTeamActive = false
        strategySnapshot?.let { snapshot ->
            configuredBossTeamMode = snapshot.settings.bossTeamMode
            bossTeamMode = configuredBossTeamMode
            preferPureBossDamageSystem = snapshot.settings.preferPureBossDamageSystem
            specialEquipmentSettings = snapshot.settings
            singleBossFallbackToMultiAfterThreeFailures =
                snapshot.settings.singleBossFallbackToMultiAfterThreeFailures
            singleBossRetryCountBeforeMulti = snapshot.settings.singleBossRetryCountBeforeMulti
            rerollAfterThreeBattleFailures = snapshot.settings.rerollAfterThreeBattleFailures
            relicChoicePolicy = snapshot.settings.relicPolicy()
            shopPolicy = LabyrinthShopPolicy(
                relicChoicePolicy = relicChoicePolicy,
                finalArea = snapshot.settings.refreshFromArea,
                buyRelics = snapshot.settings.buyRelics,
                refreshEnabled = snapshot.settings.refreshShop,
            )
            configuredOpeningRosters = snapshot.settings.openingRosters
            roleDisplayNames = snapshot.roleRuntime?.document?.characters
                ?.associate { it.characterId to it.displayName }
                .orEmpty()
            roleRewardChoicePlanner = snapshot.roleRuntime?.rewardChoicePlanner
            eventChoicePlanner = snapshot.roleRuntime?.eventChoicePlanner
            battleTeamRecommendationPlanner = snapshot.roleRuntime?.battleTeamRecommendationPlanner
            battleTeamSelectionPlanner = snapshot.roleRuntime?.battleTeamSelectionPlanner
        }
        val initialRunSnapshot = try {
            runStateStore?.begin(accountId, clock())
        } catch (failure: Throwable) {
            sessionManager.stop(session.id)
            requestCaptureStop()
            validatedRoute = null
            val reason = "加载本局队伍状态失败：${failure.message ?: "未知错误"}"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        val processor = runCatching {
            processorFactory { nodeSession?.directFinalBossTarget() != null }
        }.getOrElse { error ->
            sessionManager.stop(session.id)
            requestCaptureStop()
            validatedRoute = null
            val reason = "加载入口识别模板失败：${error.message ?: "未知错误"}"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        val registration = CaptureFrameBus.register(OWNER) { frame ->
            enqueueLatestFrame(session.id, processor, frame)
        }
        if (registration is CaptureFrameRegistrationResult.Busy) {
            sessionManager.stop(session.id)
            validatedRoute = null
            val reason = "截图帧正在由${registration.owner}使用"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        lease = (registration as CaptureFrameRegistrationResult.Registered).lease
        // Bind batch ownership before publishing the session id. A very fast first frame may
        // otherwise complete before the batch run id is attached.
        currentRunId = if (dryRun) null else runId
        pendingTerminalOutcome = null
        activeSessionId = session.id
        activeRunAccountId = accountId
        shopImprintRewardConfirmedAt = Long.MIN_VALUE
        confirmedNodeEntryReceipt = null
        validatedRoute = executionRoute
        lastProcessedAt = Long.MIN_VALUE
        endGuardedAction()
        stopRequested.set(false)
        openingStateReset.set(false)
        lastPersistedObservationSignature = null
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        resetBattleRetryTracking()
        lastObservedPageState = null
        pendingAcquiredCharacterId = null
        synchronized(pendingAcquiredCharacterIds) { pendingAcquiredCharacterIds.clear() }
        skipRoleRewardJoinedRecognition = false
        roleRewardBatchActive = false
        committedRoleRewardSelectionSignature = null
        roleRewardJoinedSequenceStarted = false
        lastObservedRoleRewardSelectionSignature = null
        existingRunResumeHandoffArmed = false
        pendingShopPurchase = null
        plannedShopPurchaseRelicId = null
        plannedShopRoleImprintLabel = null
        plannedShopPurchaseStartedAt = Long.MIN_VALUE
        plannedShopPurchaseCounted = false
        plannedShopRefreshStartedAt = Long.MIN_VALUE
        plannedShopRefreshConfirmedAt = Long.MIN_VALUE
        shopRelicPurchasesThisCycle = 0
        plannedShopPurchaseCounted = false
        shopRelicPurchasesThisCycle = 0
        pendingRelicSelection = null
        relicChoiceCommitted = false
        roleRewardChoiceCommitted = false
        relicFocusMark = null
        relicStackLedger.clear()
        relicStackLedger.clearPending()
        lastShopDialogState = LabyrinthShopDialogState.NONE
        resetNodeExecutionState()
        resetExEncounterTracking()
        resetEffectiveCharacterScan()
        actionPlanner = if (dryRun) null else createActionPlanner()
        relicStackLedger.seedAcquisitions(relicChoicePolicy.markStacks(initialRunSnapshot?.observedRelics.orEmpty()))
        _state.value = LabyrinthEntryRecognitionSessionState(
            status = LabyrinthEntryRecognitionStatus.RUNNING,
            sessionId = session.id,
            dryRun = dryRun,
            joinedCharacters = initialRunSnapshot?.joinedCharacters.orEmpty(),
            observedRelics = initialRunSnapshot?.observedRelics.orEmpty(),
            message = if (dryRun) {
                "入口只读识别已启动"
            } else {
                listOfNotNull(executionGateMessage, "路线自动执行已启动，等待游戏切到前台").joinToString("；")
            },
        )
        val attached = overlayCoordinator.attach(
            presentation = buildOverlayPresentation(session.id),
            sessionHandler = object : AutomationOverlaySessionHandler {
                override suspend fun setPaused(paused: Boolean): Boolean =
                    this@LabyrinthEntryRecognitionSession.setPaused(paused)

                override suspend fun stop(): Boolean =
                    this@LabyrinthEntryRecognitionSession.stop("通知栏停止")
            },
        )
        if (!attached) {
            lease?.let(CaptureFrameBus::unregister)
            lease = null
            activeSessionId = null
            currentRunId = null
            pendingTerminalOutcome = null
            validatedRoute = null
            sessionManager.stop(session.id)
            requestCaptureStop()
            val reason = "任务控制通知不可用或已有任务占用，请检查通知权限与频道"
            _state.value = _state.value.copy(
                status = LabyrinthEntryRecognitionStatus.ERROR,
                sessionId = null,
                message = reason,
            )
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        armFirstFrameWatchdog(session.id)
        if (!dryRun && !gameLauncher()) {
            firstFrameWatchdog?.cancel()
            firstFrameWatchdog = null
            lease?.let(CaptureFrameBus::unregister)
            lease = null
            activeSessionId = null
            currentRunId = null
            pendingTerminalOutcome = null
            validatedRoute = null
            actionPlanner = null
            sessionManager.stop(session.id)
            overlayCoordinator.detach(session.id)
            requestCaptureStop()
            val reason = "无法启动公主连结"
            _state.value = _state.value.copy(
                status = LabyrinthEntryRecognitionStatus.ERROR,
                sessionId = null,
                message = reason,
            )
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        LabyrinthEntryRecognitionStartResult.Started(session.id)
    }

    suspend fun startAutomation(
        accountId: Long? = null,
        runId: String? = null,
    ): LabyrinthEntryRecognitionStartResult {
        return start(
            dryRun = false,
            accountId = accountId,
            runId = runId,
        )
    }

    suspend fun setPaused(paused: Boolean): Boolean = mutex.withLock {
        val sessionId = activeSessionId ?: return false
        if (!sessionManager.setPaused(sessionId, paused)) return false
        finalBossLocalizationStartedAt = Long.MIN_VALUE
        resetPendingNodeClickStability()
        if (paused) clearQueuedFrame()
        _state.value = _state.value.copy(
            paused = paused,
            message = if (paused) "入口识别已暂停" else "入口仅识别已继续",
        )
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        true
    }

    /**
     * Stops the current labyrinth run.
     *
     * [releaseCapture] separates the Run lifetime from the Capture lifetime. A user stop or a
     * startup failure ends both. A run that hands off to the network reroll must keep the
     * MediaProjection session alive: Android 14+ allows a single createVirtualDisplay() per
     * token, so ending capture here would force a fresh screen-capture consent before the next
     * run could start, which is incompatible with "one authorization per batch".
     */
    suspend fun stop(
        reason: String = "用户停止入口识别",
        releaseCapture: Boolean = true,
    ): Boolean = mutex.withLock {
        val sessionId = activeSessionId ?: run {
            // 启动阶段失败或异常处理可能已经释放了会话，但录屏服务仍在运行。
            if (releaseCapture) requestCaptureStop()
            return false
        }
        stopRequested.set(true)
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = null
        activeSessionId = null
        activeRunAccountId = null
        validatedRoute = null
        actionPlanner = null
        endGuardedAction()
        openingStateReset.set(false)
        lastPersistedObservationSignature = null
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        lastObservedPageState = null
        pendingAcquiredCharacterId = null
        synchronized(pendingAcquiredCharacterIds) { pendingAcquiredCharacterIds.clear() }
        skipRoleRewardJoinedRecognition = false
        roleRewardBatchActive = false
        committedRoleRewardSelectionSignature = null
        roleRewardJoinedSequenceStarted = false
        lastObservedRoleRewardSelectionSignature = null
        existingRunResumeHandoffArmed = false
        pendingShopPurchase = null
        plannedShopPurchaseRelicId = null
        plannedShopRoleImprintLabel = null
        plannedShopPurchaseStartedAt = Long.MIN_VALUE
        plannedShopRefreshStartedAt = Long.MIN_VALUE
        plannedShopRefreshConfirmedAt = Long.MIN_VALUE
        lastShopDialogState = LabyrinthShopDialogState.NONE
        relicStackLedger.clearPending()
        resetNodeExecutionState()
        clearQueuedFrame()
        lease?.let(CaptureFrameBus::unregister)
        lease = null
        val stopped = sessionManager.stop(sessionId)
        overlayCoordinator.detach(sessionId)
        if (releaseCapture) {
            requestCaptureStop()
        } else {
            nodeLog("run-stopped-capture-retained reason=$reason")
        }
        _state.value = _state.value.copy(
            status = LabyrinthEntryRecognitionStatus.STOPPED,
            sessionId = null,
            paused = false,
            message = reason,
        )
        publishRunTerminal(reason, userInitiated = releaseCapture && pendingTerminalOutcome == null)
        stopped
    }

    /**
     * Turns the stop into one formal [LabyrinthRunTerminalEvent] for the batch, if this run was
     * batch-owned. CLEARED and FAILED_MAX_RETRY are set explicitly by their producing paths;
     * everything else is either a user stop (capture released, no explicit outcome) or an
     * unexplained stop the batch must not count.
     */
    private fun publishRunTerminal(reason: String, userInitiated: Boolean) {
        val runId = currentRunId ?: return
        val outcome = pendingTerminalOutcome
        // Run ownership is session-local state and must be cleared even in builds/tests that do
        // not install a batch listener. Otherwise the next interactive run inherits a stale run
        // id and may retain capture or report a terminal event to the wrong batch.
        currentRunId = null
        pendingTerminalOutcome = null
        val listener = runTerminalListener ?: return
        val event = when {
            outcome == LabyrinthRunTerminalOutcome.CLEARED -> LabyrinthRunTerminalEvent.Cleared(runId)
            outcome == LabyrinthRunTerminalOutcome.FAILED_MAX_RETRY -> LabyrinthRunTerminalEvent.FailedMaxRetry(runId)
            userInitiated -> LabyrinthRunTerminalEvent.UserStopped(runId)
            else -> LabyrinthRunTerminalEvent.FatalUnknown(runId, reason)
        }
        actionScope.launch { runCatching { listener(event) } }
    }

    /**
     * CaptureFrameBus dispatches synchronously on the capture callback thread. Heavy recognition
     * must therefore never run directly inside that callback: one slow OCR/template pass would
     * otherwise stop fresh frames from reaching the state machine and make the whole session
     * appear seconds behind the game.
     *
     * Keep at most one waiting frame while a single recognition worker is busy. Newer frames
     * replace older waiting frames (latest-frame-wins), so an occasional expensive frame can add
     * at most one recognition-duration of latency instead of creating an unbounded stale queue.
     */
    private fun enqueueLatestFrame(
        sessionId: AutomationSessionId,
        processor: (CapturedFrame) -> LabyrinthEntryFrameResult,
        frame: CapturedFrame,
    ) {
        if (activeSessionId != sessionId || _state.value.paused) {
            recycle(frame)
            return
        }
        if (lastProcessedAt != Long.MIN_VALUE && frame.timestampMillis - lastProcessedAt < FRAME_INTERVAL_MILLIS) {
            recycle(frame)
            return
        }
        lastProcessedAt = frame.timestampMillis

        val replaced = queuedFrame.getAndSet(QueuedFrame(sessionId, processor, frame))
        replaced?.let { recycle(it.frame) }
        ensureFrameWorker()
    }

    private fun ensureFrameWorker() {
        if (!frameWorkerRunning.compareAndSet(false, true)) return
        actionScope.launch {
            try {
                while (true) {
                    val next = queuedFrame.getAndSet(null) ?: break
                    onFrame(next.sessionId, next.processor, next.frame)
                }
            } finally {
                frameWorkerRunning.set(false)
                // Close the race where a frame arrives after the loop observes null but before
                // frameWorkerRunning becomes false.
                if (queuedFrame.get() != null) ensureFrameWorker()
            }
        }
    }

    private fun clearQueuedFrame() {
        queuedFrame.getAndSet(null)?.let { recycle(it.frame) }
    }

    private fun onFrame(
        sessionId: AutomationSessionId,
        processor: (CapturedFrame) -> LabyrinthEntryFrameResult,
        frame: CapturedFrame,
    ) {
        if (activeSessionId != sessionId || _state.value.paused) {
            recycle(frame)
            return
        }
        val sessionState = _state.value
        if (!sessionState.dryRun && !actionTargetReady()) {
            val message = "等待游戏切到前台，自动会话保持运行"
            if (sessionState.message != message) {
                _state.value = sessionState.copy(message = message)
                runCatching {
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }.onFailure { error ->
                    handleFrameFailure(sessionId, error)
                }
            }
            recycle(frame)
            return
        }
        val frameWidth = frame.bitmap.width
        val frameHeight = frame.bitmap.height
        val beforeProcessing = _state.value
        _state.value = beforeProcessing.copy(
            receivedFrameCount = beforeProcessing.receivedFrameCount + 1,
        )
        runCatching {
            val result = processor(frame)
            // stop()/pause may run while an expensive frame is being recognized. Never let that
            // stale result mutate a stopped/paused session or publish an old dashboard frame.
            if (activeSessionId != sessionId || _state.value.paused) return@runCatching
            processFrameResult(
                sessionId = sessionId,
                result = result,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = frame.timestampMillis,
            )
            debugFramePublisher?.invoke(
                frame,
                _state.value,
                result,
                buildOverlayPresentation(sessionId),
            )
        }.onFailure { error ->
            handleFrameFailure(sessionId, error)
        }
            .also { recycle(frame) }
    }

    private fun handleFrameFailure(sessionId: AutomationSessionId, error: Throwable) {
        if (activeSessionId != sessionId) return
        val reason = "入口识别失败：${error.message ?: "未知错误"}"
        _state.value = _state.value.copy(message = reason)
        requestStopAfterFailure(sessionId, reason)
    }

    private fun processFrameResult(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        if (activeSessionId != sessionId) return
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = null
        if (result.frameWidth != frameWidth || result.frameHeight != frameHeight) {
            nodeLog(
                "frame-size-mismatch callback=${frameWidth}x${frameHeight} " +
                    "result=${result.frameWidth}x${result.frameHeight}",
                warning = true,
            )
        }

        if (labyrinthActionLatchStuck(actionInFlightSince, timestampMillis) && actionInFlight.get()) {
            val heldFor = timestampMillis - actionInFlightSince
            endGuardedAction()
            finishFromPlanner(
                sessionId,
                "动作执行超过${heldFor / 1000}秒未返回（手势可能未落地），已释放动作锁并停止以保留诊断状态",
            )
            return
        }
        val current = _state.value
        _state.value = current.copy(
            frameCount = current.frameCount + 1,
            lastResult = result,
            message = if (result.observation.state == LabyrinthEntryPageState.UNKNOWN) {
                "未知或低置信度页面，动作已禁用"
            } else {
                "页面识别已更新"
            },
            frameTrace = LabyrinthFrameTrace(
                expectedPages = LabyrinthEntryPageState.entries.map(LabyrinthEntryPageState::name),
                nodeSearchWindowCount = result.nodeSearchWindowCount,
            ),
        )
        val pageState = result.observation.state
        if (
            handleSessionBlockFrame(
                sessionId = sessionId,
                result = result,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = timestampMillis,
                dryRun = current.dryRun,
            )
        ) {
            return
        }
        if (skipRoleRewardJoinedRecognition &&
            !labyrinthKeepsRoleRewardBatchOnPage(pageState, labyrinthIsRoleRewardPage(result))
        ) skipRoleRewardJoinedRecognition = false
        val shopJoinedReward = labyrinthShopJoinedRewardOwnsRoute(result, shopImprintRewardConfirmedAt, timestampMillis)
        if (!current.dryRun &&
            !entryPhaseComplete &&
            (shopJoinedReward || (existingRunResumeHandoffArmed && labyrinthResumedRunRewardPageOwnsRoute(result)))
        ) {
            // Resuming an existing run can restore directly into an unfinished reward batch.
            // Do not let the opening invitation planner interpret that CHARACTER_JOINED page.
            entryPhaseComplete = true
            existingRunResumeHandoffArmed = false
            roleRewardBatchActive = true
            if (pageState == LabyrinthEntryPageState.CHARACTER_JOINED) {
                roleRewardJoinedSequenceStarted = true
                characterAcquisitionActive = true
                characterAcquisitionClicks = 0
                characterAcquisitionStartedAt = timestampMillis
                lastCharacterAcquisitionActionAt = Long.MIN_VALUE
            }
            _state.value = _state.value.copy(
                message = if (pageState == LabyrinthEntryPageState.CHARACTER_JOINED) {
                    if (shopJoinedReward) "商店印记触发角色加入，已交接奖励流程"
                    else "继续挑战后恢复到角色加入奖励，已交接连续奖励链"
                } else {
                    "继续挑战后恢复到角色奖励选择，已交接连续奖励链"
                },
            )
            nodeLog("existing-run resume handed off directly to role reward page: ${pageState.name}")
        }
        val routeHasPassedOpening = labyrinthRouteHasPassedOpening(
            nodeSession?.getState(),
            hasNodeTransition = pendingNodeTransition != null || activeNodeType != null,
        )
        if (!current.dryRun && labyrinthShouldResumeOpeningSelection(
                entryPhaseComplete = entryPhaseComplete,
                result = result,
                routeActive = routeHasPassedOpening,
            )
        ) {
            entryPhaseComplete = false
            existingRunResumeHandoffArmed = false
            battleWait.reset()
            clearCharacterAcquisitionContext()
            clearRoleRewardBatchTracking()
            synchronized(actionPlannerLock) {
                actionPlanner = createActionPlanner()
            }
            nodeLog("opening-selection reclaimed entry flow after map handoff")
        }
        if (!current.dryRun) {
            // Destination recognition may confirm the just-dispatched node before its page-local
            // proposal is invalidated below.
            confirmPendingNodeTransition(sessionId, pageState)
        }
        val preservePendingNodeMoveConfirmation = !current.dryRun &&
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = pageState,
                hasPendingNodeTransition = pendingNodeTransition != null,
                hasMoveConfirmation = result.nodeMoveConfirmation != null,
            )
        val preservePendingNodeTransitionAcrossPage = !current.dryRun &&
            pendingNodeTransition?.let { pending ->
                labyrinthPreservesPendingNodeTransitionAcrossPage(
                    pageState = pageState,
                    confirmationDispatchedAtMillis = pending.confirmationDispatchedAtMillis,
                    nowMillis = timestampMillis,
                    timeoutMillis = NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS,
                    dispatchedAtMillis = pending.dispatchedAtMillis,
                )
            } == true
        val preservePendingNodeTransitionForMismatch = !current.dryRun &&
            pendingNodeTransition?.let { pending ->
                labyrinthPreservesPendingNodeTransitionForEntryMismatch(
                    mismatchFrames = nodeEntryMismatchFrames,
                    stableFrames = NODE_ENTRY_MISMATCH_STABLE_FRAMES,
                    confirmationDispatchedAtMillis = pending.confirmationDispatchedAtMillis,
                    nowMillis = timestampMillis,
                    timeoutMillis = NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS,
                    dispatchedAtMillis = pending.dispatchedAtMillis,
                )
            } == true
        val confirmedTransitionTimedOut = !current.dryRun &&
            pendingNodeTransition?.confirmationDispatchedAtMillis?.let { confirmedAt ->
                !labyrinthConfirmsNodeEntry(pageState) &&
                    pageState in setOf(
                        LabyrinthEntryPageState.UNKNOWN,
                        LabyrinthEntryPageState.NODE_MAP_VIEW,
                        LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
                        LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
                    ) &&
                    (timestampMillis - confirmedAt).coerceAtLeast(0L) >= NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS
            } == true
        if (confirmedTransitionTimedOut) {
            val pending = pendingNodeTransition
            if (pending != null) {
                nodeLog(
                    "node-entry-timeout target=${pending.label} after-confirmation page=${pageState.name} " +
                        "elapsed=${(timestampMillis - requireNotNull(pending.confirmationDispatchedAtMillis)).coerceAtLeast(0L)}ms",
                    warning = true,
                )
                pendingNodeTransition = null
                resetNodeMoveConfirmationTracking()
                resetPendingNodeClickStability()
                finishFromPlanner(
                    sessionId,
                    "已确认移动到${pending.label}，但在限定时间内未识别到对应节点页面；已停止并保留诊断状态",
                )
                return
            }
        }
        invalidatePreviousPageState(
            pageState = pageState,
            preservePendingNodeMoveConfirmation =
                preservePendingNodeMoveConfirmation ||
                    preservePendingNodeTransitionAcrossPage ||
                    preservePendingNodeTransitionForMismatch,
        )
        synchronizeBossEditor(result)
        refreshRelicStackCalibration(result)
        refreshRoleRewardChoiceDecision(sessionId, result, frameWidth, frameHeight)
        refreshEventChoiceDecision(sessionId, result)
        refreshEffectiveCharacterScan(sessionId, result, frameWidth, frameHeight, timestampMillis)
        refreshBattleTeamRecommendation(sessionId, pageState)
        refreshBattleTeamSelectionPlan(sessionId, pageState, result.battleTeamSelection)
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        persistObservation(sessionId, result, timestampMillis)
        if (!current.dryRun && entryPhaseComplete &&
            handleBattleWaitFrame(sessionId, result, timestampMillis)
        ) return
        val relicDetail = result.relicDetailObservation
        if (relicDetail != null && labyrinthRelicDetailHoldsFrame(result)) {
            // This popup is only ever opened by hand, and the run deliberately does not close it
            // so the operator can read it. That made it an unbounded wait: a popup left open (or
            // one the classifier keeps seeing) parked the route with no deadline at all. Reading
            // it is still uninterrupted for as long as anyone plausibly needs.
            if (relicDetailHoldSince == Long.MIN_VALUE) relicDetailHoldSince = timestampMillis
            val heldFor = timestampMillis - relicDetailHoldSince
            if (heldFor >= RELIC_DETAIL_HOLD_TIMEOUT_MILLIS) {
                finishFromPlanner(
                    sessionId,
                    "系列详情弹窗持续${heldFor / 1000}秒未关闭，路线无法继续；已停止并保留诊断状态",
                )
                return
            }
            _state.value = _state.value.copy(message = if (relicDetail.titleConfirmed) {
                "系列详情核对中：只更新已确认的可见系列；可手动滚动，关闭后继续路线"
            } else "正在确认系列详情弹窗，暂不执行点击")
            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
            return
        }
        relicDetailHoldSince = Long.MIN_VALUE
        if (!current.dryRun && handleShopRefreshConfirmationFrame(
                sessionId = sessionId,
                result = result,
                timestampMillis = timestampMillis,
            )
        ) {
            return
        }
        if (!current.dryRun && result.nodeMoveConfirmation != null && pendingNodeTransition == null) {
            // Read persistent chrome before considering the generic modal recovery. A shop exit
            // confirmation can briefly classify as UNKNOWN but still has the shop behind it; it
            // must never manufacture a pending route transition to the next Boss/node.
            val shopBackground = labyrinthShopBackgroundVisible(result)
            val recoverableTarget = nodeSession?.uniqueReachableRouteTarget()
            val canRecover = recoverableTarget != null &&
                labyrinthCanRecoverOrphanNodeMoveConfirmation(
                    pageState = pageState,
                    confirmationConfidence = result.nodeMoveConfirmation.confidence,
                    hasUniqueReachableRouteTarget = true,
                    shopBackgroundVisible = shopBackground,
                )
            if (canRecover && recoverableTarget != null) {
                recoverPendingNodeTransitionFromMoveConfirmation(
                    sessionId = sessionId,
                    session = requireNotNull(nodeSession),
                    blockId = recoverableTarget.blockId,
                    blockType = recoverableTarget.blockType,
                    area = recoverableTarget.area,
                    timestampMillis = timestampMillis,
                    confidence = result.nodeMoveConfirmation.confidence,
                )
            } else if (nodeSession != null && entryPhaseComplete) {
                // A modal nobody asked for and nobody can adopt (a stray tap while the route has
                // several reachable successors). Every map gesture would land on the dialog, so
                // the node search below must not run; press the dialog's cancel button instead.
                // The shop exit dialog shares this chrome; while shop controls are visible the
                // dialog is the shop handler's and must be left alone.
                val shopBackground = labyrinthShopBackgroundVisible(result)
                orphanMoveConfirmationClearFrames = 0
                if (shopBackground) {
                    orphanMoveConfirmationStableFrames = 0
                } else {
                    orphanMoveConfirmationStableFrames++
                }
                if (!shopBackground && labyrinthShouldDismissOrphanNodeMoveConfirmation(
                        pageState = pageState,
                        hasPendingNodeTransition = false,
                        canRecover = false,
                        stableFrames = orphanMoveConfirmationStableFrames,
                        dismissAttempts = orphanMoveConfirmationDismissAttempts,
                        lastDismissAt = lastOrphanMoveConfirmationDismissAt,
                        now = timestampMillis,
                        shopBackgroundVisible = false,
                    )
                ) {
                    dismissOrphanNodeMoveConfirmation(
                        sessionId = sessionId,
                        cancelRect = result.nodeMoveConfirmation.cancelButtonRect,
                        timestampMillis = timestampMillis,
                    )
                } else if (shopBackground) {
                    // Fall through: the shop / shop-exit handlers own this frame.
                } else if (orphanMoveConfirmationDismissAttempts >= MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS) {
                    // The cancel button was pressed its full budget and the dialog is still up.
                    // Asking for a human and then waiting for one indefinitely is the one thing
                    // an unattended run must not do: every map gesture lands on this dialog, so
                    // nothing else can make progress either.
                    if (orphanMoveConfirmationExhaustedSince == Long.MIN_VALUE) {
                        orphanMoveConfirmationExhaustedSince = timestampMillis
                    }
                    val heldFor = timestampMillis - orphanMoveConfirmationExhaustedSince
                    if (heldFor >= ORPHAN_MOVE_CONFIRMATION_MANUAL_GRACE_MILLIS) {
                        finishFromPlanner(
                            sessionId,
                            "移动确认弹窗取消${orphanMoveConfirmationDismissAttempts}次无效，" +
                                "等待手动关闭${heldFor / 1000}秒后仍未消失；已停止并保留诊断状态",
                        )
                        return
                    }
                    publishNodeMoveConfirmationMessage(sessionId, "移动确认弹窗多次取消无效，请手动关闭后继续")
                    return
                } else {
                    publishNodeMoveConfirmationMessage(sessionId, "检测到非自动触发的移动确认弹窗，准备点击取消")
                    return
                }
                if (!shopBackground) return
            }
        } else if (result.nodeMoveConfirmation == null) {
            orphanMoveConfirmationStableFrames = 0
            orphanMoveConfirmationExhaustedSince = Long.MIN_VALUE
            // The screen cleared, so the previous cancel worked. Give the budget back: it is there
            // to stop tapping at a dialog that will not close, not to cap how many separate stray
            // dialogs one run may recover from.
            orphanMoveConfirmationClearFrames++
            if (labyrinthOrphanMoveConfirmationBudgetRestored(orphanMoveConfirmationClearFrames)) {
                resetOrphanMoveConfirmationTracking()
            }
        }
        if (!current.dryRun && labyrinthNodeMoveConfirmationOwnsFrame(
                pageState = pageState,
                hasPendingNodeTransition = pendingNodeTransition != null,
            )
        ) {
            if (handleNodeMoveConfirmationFrame(sessionId, result, timestampMillis)) {
                return
            }
        }
        val persistedCurrentNode = labyrinthPersistedCurrentNode(validatedRoute)
        val effectiveNodeType = activeNodeType ?: persistedCurrentNode?.blockType
        val eventFreeRoleSelection = labyrinthEventFreeRoleSelectionOwnsFrame(
            pageState = pageState,
            activeNodeType = effectiveNodeType,
            roleRewardPage = postEntryIsRoleRewardPage(result),
            hasOpeningViewport = result.openingCharacterSelection != null,
            shopChoiceImprintPending = shopChoiceImprintRolePending,
            routeActive = routeHasPassedOpening,
        )
        if (eventFreeRoleSelection) {
            if (activeNodeType == null && persistedCurrentNode?.blockType == LabyrinthNodeTypes.EVENT) {
                activeNodeType = LabyrinthNodeTypes.EVENT
                activeNodeArea = persistedCurrentNode.area
            }
            if (current.dryRun) {
                if (_state.value.message != "事件自由选角页：Dry Run 不点击") {
                    _state.value = _state.value.copy(message = "事件自由选角页：Dry Run 不点击")
                }
            } else {
                handleEventFreeRoleSelection(sessionId, result, timestampMillis)
            }
            return
        }
        val nodeReplayActive = nodeExecutionConfigured() &&
            (if (current.dryRun) pageState == LabyrinthEntryPageState.NODE_SELECTION else entryPhaseComplete)
        // A one-button 确认 dialog is drawn over the map, so the page classifier still reports
        // NODE_SELECTION and the branch below would hand the frame to the node search. 2026-09-18
        // live: the 「无法获得报酬」 popup after an event sat open while the run swiped the map
        // looking for 普通战斗#40501 for 279 actions. The dialog was recognised the whole time --
        // its 确认 button scored 0.985 -- but the only code that acts on it lives in the
        // post-entry page handler, which a NODE_SELECTION frame never reaches. A modal owns the
        // frame: nothing behind it can be clicked, and no map gesture can reach the map.
        val genericConfirmOwnsFrame = !current.dryRun &&
            labyrinthGenericConfirmDialogRect(result) != null
        // 「特别装备」弹窗是编组页之上的模态：它自己的按钮与上面共享的「确认」模板互相干扰，而压暗
        // 底层页之后页面分类可能落到 UNKNOWN，那条路根本到不了编组页处理器。所以这一支**必须排在
        // genericConfirmOwnsFrame 之前**，并且只凭观测的 stage（或本轮已武装）进入，与当帧 pageState
        // 无关。流程没接管（返回 false）时不改变任何既有分支的行为。
        val specialEquipmentOwnsFrame = !current.dryRun &&
            (
                result.specialEquipment?.stage != null ||
                    specialEquipmentFlowStage != LabyrinthSpecialEquipmentFlowStage.INACTIVE
                )
        if (
            specialEquipmentOwnsFrame &&
            handleSpecialEquipmentFlowFrame(sessionId, result, timestampMillis)
        ) {
            return
        }
        when {
            genericConfirmOwnsFrame ->
                handlePostEntryPage(sessionId, result, frameWidth, frameHeight, timestampMillis)

            shopJoinedReward && !current.dryRun ->
                handlePostEntryPage(sessionId, result, frameWidth, frameHeight, timestampMillis)
            nodeReplayActive && pageState == LabyrinthEntryPageState.NODE_SELECTION ->
                handleNodeSelectionFrame(sessionId, result, frameWidth, frameHeight, timestampMillis)

            // 入口流程已交接给路线执行：点击节点后出现的事件页在这里处理，
            // 处理完回到节点页继续走路线。
            nodeReplayActive ->
                if (!current.dryRun) {
                    handlePostEntryPage(sessionId, result, frameWidth, frameHeight, timestampMillis)
                }

            !current.dryRun ->
                handleActionDecision(sessionId, result, frameWidth, frameHeight, timestampMillis)
        }
    }

    private fun armFirstFrameWatchdog(sessionId: AutomationSessionId) {
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = actionScope.launch {
            delay(FIRST_FRAME_TIMEOUT_MILLIS)
            if (activeSessionId != sessionId) return@launch
            val current = _state.value
            if (current.lastResult != null || current.frameCount > 0) return@launch
            val captureRunning = captureActive()
            val currentOwner = CaptureFrameBus.currentOwner()
            val reason = when {
                current.receivedFrameCount > 0 ->
                    "5秒未完成首帧识别：已收到${current.receivedFrameCount}个原始截图，首帧处理卡住或过慢"

                !captureRunning ->
                    buildString {
                        append("5秒未收到首帧：屏幕捕获已停止")
                        CaptureStateRegistry.lastStopReason()?.let { append("（$it）") }
                        append("，请停止Dry Run后重新开启屏幕捕获")
                    }

                currentOwner != OWNER ->
                    "5秒未收到首帧：截图消费者异常（${currentOwner ?: "无消费者"}）"

                else ->
                    "5秒未收到首帧：屏幕捕获仍运行但没有图像帧，请重新开启屏幕捕获"
            }
            _state.value = current.copy(message = reason)
            runCatching {
                overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
            }
        }
    }

    private fun requestStopAfterFailure(sessionId: AutomationSessionId, reason: String) {
        if (!stopRequested.compareAndSet(false, true)) return
        actionScope.launch {
            if (activeSessionId == sessionId) stopFromRunLogic(reason)
        }
    }

    private fun requestCaptureStop() {
        runCatching { captureStop() }
    }

    /**
     * Handles account-session interruption before normal page actions. The popup can retain a
     * recognizable HOME page behind it, so allowing the regular planner to run here would tap
     * through the modal instead of returning to the title screen.
     */
    private fun handleSessionBlockFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        dryRun: Boolean,
    ): Boolean {
        if (
            labyrinthShopTransitionSuppressesSessionBlock(
                pageState = result.observation.state,
                previousPageState = lastObservedPageState,
                activeNodeType = activeNodeType,
                lastActionAt = lastPostEntryActionAt,
                now = timestampMillis,
            )
        ) {
            resetSessionRecoveryTracking()
            return false
        }
        val block = labyrinthSessionBlockObservation(result, sessionBlockClassifier)
        if (!block.blocksNormalActions) {
            resetSessionRecoveryTracking()
            return false
        }
        battleWait.reset()
        if (lastSessionBlockKind != block.kind) {
            lastSessionBlockKind = block.kind
            sessionBlockStableFrames = 0
            sessionReturnTitleStableFrames = 0
            sessionReturnTitleAttempts = 0
            lastSessionReturnTitleActionAt = Long.MIN_VALUE
        }
        sessionBlockStableFrames++
        sessionReturnTitleStableFrames = labyrinthSessionReturnTitleStreak(
            previousStreak = sessionReturnTitleStableFrames,
            returnTitleRectPresent = block.returnTitleRect != null,
        )

        val message = when {
            dryRun -> "检测到账号会话失效；只读模式不会点击${block.actionLabel}"
            block.kind == SessionBlockKind.UNKNOWN_PROMPT -> "检测到未知会话弹窗，已暂停普通点击"
            block.returnTitleRect == null -> "检测到账号会话失效，等待识别“${block.actionLabel}”按钮"
            sessionReturnTitleStableFrames < SESSION_BLOCK_STABLE_FRAMES ->
                "检测到账号会话失效，正在确认“${block.actionLabel}”按钮"
            sessionReturnTitleAttempts >= MAX_SESSION_RETURN_TITLE_ATTEMPTS ->
                "${block.actionLabel}多次无响应，请手动点击；自动会话仍保持运行"
            else -> null
        }
        if (message != null) {
            publishSessionRecoveryMessage(sessionId, message)
            return true
        }

        val rect = block.returnTitleRect ?: return true
        if (
            rect.left < 0 || rect.top < 0 || rect.width <= 0 || rect.height <= 0 ||
            rect.left + rect.width > frameWidth || rect.top + rect.height > frameHeight
        ) {
            publishSessionRecoveryMessage(sessionId, "${block.actionLabel}按钮坐标越界，等待重新识别")
            return true
        }
        if (actionInFlight.get()) return true
        if (
            lastSessionReturnTitleActionAt != Long.MIN_VALUE &&
            timestampMillis - lastSessionReturnTitleActionAt < SESSION_RETURN_TITLE_ACTION_INTERVAL_MILLIS
        ) {
            return true
        }
        dispatchSessionReturnTitle(sessionId, rect, block.actionLabel, timestampMillis)
        return true
    }

    private fun dispatchSessionReturnTitle(
        sessionId: AutomationSessionId,
        rect: EntryPixelRect,
        actionLabel: String,
        timestampMillis: Long,
    ) {
        if (!beginGuardedAction()) return
        traceAction("会话失效：$actionLabel")
        sessionReturnTitleAttempts++
        lastSessionReturnTitleActionAt = timestampMillis
        launchGuardedAction {
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = rect.left + rect.width / 2f,
                    y = rect.top + rect.height / 2f,
                ),
            )
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    if (activeSessionId == sessionId) {
                        restartEntryNavigationAfterSessionError()
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "会话失效：$actionLabel",
                            message = "已点击$actionLabel，等待标题页后重新登录",
                        )
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        sessionReturnTitleAttempts = (sessionReturnTitleAttempts - 1).coerceAtLeast(0)
                        lastSessionReturnTitleActionAt = Long.MIN_VALUE
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("$actionLabel 点击被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    private fun clearPlannedShopPurchase() {
        plannedShopPurchaseRelicId = null
        plannedShopRoleImprintLabel = null
        plannedShopPurchaseStartedAt = Long.MIN_VALUE
        plannedShopPurchaseCounted = false
    }

    private fun clearPlannedShopRefresh() {
        plannedShopRefreshStartedAt = Long.MIN_VALUE
        plannedShopRefreshConfirmedAt = Long.MIN_VALUE
    }

    /** Restart login/entry navigation while retaining any already-loaded route progress. */
    private fun restartEntryNavigationAfterSessionError() {
        battleWait.reset()
        entryPhaseComplete = false
        resetPendingNodeClickStability()
        resetStrongRouteTargetBinding()
        pendingNodeTransition = null
        nodeTapAttempts = 0
        nodeEntryMismatchFrames = 0
        nodeEntryMismatchPage = null
        resetNodeMoveConfirmationTracking()
        resetOrphanMoveConfirmationTracking()
        nodeScrollAttempts = 0
        resetNodeRecoveryNudgeTracking()
        lastNodeSwipeAvoidRects = emptyList()
        resetNodeScrollSearchGate()
        lastNodeScrollSourceSignature = null
        nodeViewportScanner.reset()
        lastNodeActionAt = Long.MIN_VALUE
        nodeErrorStreak = 0
        lastPostEntryState = null
        postEntryStableFrames = 0
        postEntryAttempts = 0
        lastPostEntryActionAt = Long.MIN_VALUE
        lastPostEntryActionLandedAt = Long.MIN_VALUE
        postEntryUnknownSince = Long.MIN_VALUE
        actionInFlightSince = Long.MIN_VALUE
        resetPostEntryStall()
        clearCharacterAcquisitionContext()
        clearRoleRewardBatchTracking()
        existingRunResumeHandoffArmed = false
        activeNodeType = null
        activeNodeArea = null
        activeNodeArea = null
        eventActionAttempts = 0
        combatContext = null
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        _state.value = _state.value.copy(
            combatContext = null,
            battleTeamRecommendation = null,
            battleTeamRecommendationUnavailableReason = null,
            battleTeamSelectionPlan = null,
        )
        synchronized(actionPlannerLock) {
            actionPlanner = createActionPlanner()
        }
    }

    private fun createActionPlanner(): LabyrinthEntryActionPlanner = actionPlannerFactory().also { planner ->
        planner.configureOpeningRoster(
            guildId = validatedRoute?.guildId,
            openingRosters = configuredOpeningRosters,
            displayNameFor = { id -> roleDisplayNames[id] },
        )
        planner.start(clock())
    }

    private fun publishSessionRecoveryMessage(sessionId: AutomationSessionId, message: String) {
        if (activeSessionId != sessionId) return
        if (_state.value.message != message) {
            _state.value = _state.value.copy(message = message)
            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        }
    }

    private fun resetSessionRecoveryTracking() {
        lastSessionBlockKind = SessionBlockKind.NONE
        sessionBlockStableFrames = 0
        sessionReturnTitleStableFrames = 0
        sessionReturnTitleAttempts = 0
        lastSessionReturnTitleActionAt = Long.MIN_VALUE
    }

    /**
     * Reconcile history-derived mark stacks with the value printed by the game itself.
     * Multiple cards with the same mark must agree before that mark is accepted as calibration.
     */
    private fun refreshRelicStackCalibration(result: LabyrinthEntryFrameResult) {
        result.relicDetailObservation?.let { detail ->
            if (detail.titleConfirmed) detail.entries.forEach { (mark, value) ->
                relicStackLedger.observe(mark, value, LabyrinthRelicStackLedger.Source.DETAIL,
                    mark.name, detail.evidenceId)
            }
        }
        result.relicChoiceSelection?.choices.orEmpty().filter { it.recognized }
            .groupBy { LabyrinthRelicMark.fromLabel(it.attribute) }.forEach { (mark, choices) ->
            if (mark == null) return@forEach
            val conflicting = choices.mapNotNull { it.currentMarkStacks }.distinct().size > 1
            choices.forEach { choice ->
                relicStackLedger.observe(mark, choice.currentMarkStacks.takeUnless { conflicting },
                    LabyrinthRelicStackLedger.Source.CHOICE, choice.slotId, choice.currentMarkStacksEvidenceId)
            }
        }
        result.nodeRelicStackObservation?.entries.orEmpty().forEach { entry ->
            val mark = LabyrinthRelicMark.fromLabel(entry.attribute) ?: return@forEach
            relicStackLedger.observe(mark, entry.stacks, LabyrinthRelicStackLedger.Source.MAP,
                entry.slotId, entry.evidenceId)
        }
    }

    private fun advanceRelicStackCalibrationAfterAcquisition(relic: LabyrinthRelicMatch) {
        val mark = LabyrinthRelicMark.fromLabel(relic.attribute) ?: return
        val bonus = relic.attributeBonus?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return
        val fallback = relicChoicePolicy.markStacks(_state.value.observedRelics).getValue(mark)
        relicStackLedger.acquired(mark, bonus, fallback)
    }

    /** Evaluates role reward identity/choice in both dry-run and live sessions. */
    private fun refreshRoleRewardChoiceDecision(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        if (activeSessionId != sessionId) return
        if (!labyrinthIsRoleRewardPage(result)) {
            roleRewardDecisionCacheKey = null
            if (_state.value.roleRewardChoiceDecision != null) {
                _state.value = _state.value.copy(roleRewardChoiceDecision = null)
            }
            return
        }
        if (committedRoleRewardSelectionSignature != null &&
            labyrinthRoleRewardSelectionSignature(result) == committedRoleRewardSelectionSignature
        ) return
        val planner = roleRewardChoicePlanner
        val decision = if (planner == null) {
            LabyrinthRoleRewardChoiceDecision.Wait(
                roleRewardChoiceUnavailableReason ?: "角色三选一决策资料未加载",
            )
        } else {
            val defenseStacks = relicChoicePolicy.markStacks(
                _state.value.observedRelics,
                relicMarkStackCalibration,
            )
                .getValue(LabyrinthRelicMark.DEFENSE)
            val pendingSelectedIds = synchronized(pendingAcquiredCharacterIds) {
                pendingAcquiredCharacterIds.toSet()
            }
            val acquiredIds = _state.value.joinedCharacters.map(LabyrinthJoinedCharacter::characterId)
                .plus(pendingSelectedIds).toSet()
            val cacheKey = buildString {
                append("$frameWidth/$frameHeight/$defenseStacks/")
                append(acquiredIds.sorted().joinToString(","))
                result.characterMatches.forEach {
                    append("|${it.slotId}:${it.characterId}:${it.displayName}:${it.screenRect}:")
                    append(labyrinthRoleRewardIdentityIsActionSafe(it))
                }
            }
            if (cacheKey == roleRewardDecisionCacheKey &&
                _state.value.roleRewardChoiceDecision is LabyrinthRoleRewardChoiceDecision.Select
            ) return
            if (cacheKey != roleRewardDecisionCacheKey) {
                val collision = result.characterMatches.mapNotNull { it.characterId }
                    .map(::canonicalLabyrinthRoleId)
                    .filter(acquiredIds::contains)
                    .distinct()
                if (collision.isNotEmpty()) {
                    nodeLog("role-reward-roster-conflict candidateIds=${collision.joinToString()} " +
                        "action=rank-visible-candidates", warning = true)
                }
            }
            roleRewardDecisionCacheKey = cacheKey
            val decisionStarted = System.nanoTime()
            planner.decide(
                candidates = result.characterMatches,
                acquiredCharacterIds = acquiredIds,
                context = LabyrinthRoleDecisionContext(defenseMarkStacks = defenseStacks),
                frameWidth = frameWidth,
                frameHeight = frameHeight,
            ).also {
                runCatching {
                    Log.d("LabyrinthEntry", "roleRewardDecisionMillis=${(System.nanoTime() - decisionStarted) / 1_000_000}")
                }
            }
        }
        val message = when (decision) {
            is LabyrinthRoleRewardChoiceDecision.Select -> buildString {
                append("角色三选一建议：${decision.displayName}")
                if (!decision.actionSafe) append("（仅推荐，不自动点击）")
                if (decision.explanation.isNotEmpty()) {
                    append("；${decision.explanation.joinToString("；")}")
                }
                decision.safetyNote?.let { append("；$it") }
            }
            is LabyrinthRoleRewardChoiceDecision.Wait -> decision.reason
        }
        _state.value = _state.value.copy(
            roleRewardChoiceDecision = decision,
            message = message,
        )
    }

    /** Evaluates a catalog-resolved event in both Dry Run and guarded live sessions. */
    private fun refreshEventChoiceDecision(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
    ) {
        if (activeSessionId != sessionId) return
        val observation = result.eventChoiceSelection
        if (result.observation.state != LabyrinthEntryPageState.EVENT_CHOICE || observation == null) {
            if (_state.value.eventChoiceDecision != null) {
                nodeLog("event-choice-decision-cleared page=${result.observation.state} " +
                    "ocr=${result.eventOcrDiagnostics?.status} cache=${result.eventOcrDiagnostics?.cache?.status} " +
                    "cacheAgeMillis=${result.eventOcrDiagnostics?.cache?.ageMillis}")
                _state.value = _state.value.copy(eventChoiceDecision = null)
            }
            return
        }
        val planner = eventChoicePlanner
        val decision = if (planner == null) {
            LabyrinthEventChoiceDecision.Wait("事件推荐资料未加载")
        } else {
            val current = _state.value
            val stacks = relicChoicePolicy.markStacks(current.observedRelics, relicMarkStackCalibration)
            runCatching {
                planner.decide(
                    observation = observation,
                    acquiredCharacterIds = current.joinedCharacters
                        .map(LabyrinthJoinedCharacter::characterId)
                        .toSet(),
                    context = LabyrinthRoleDecisionContext(
                        defenseMarkStacks = stacks.getValue(LabyrinthRelicMark.DEFENSE),
                        targetCount = combatContext?.targetCount ?: 1,
                    ),
                    relicStacks = stacks,
                    rejectedChoiceIds = eventChoiceRejectedIds.toSet(),
                )
            }.getOrElse { failure ->
                LabyrinthEventChoiceDecision.Wait(
                    "事件${observation.event.id}推荐失败：${failure.message ?: "未知错误"}",
                )
            }
        }
        val message = when (decision) {
            is LabyrinthEventChoiceDecision.Select -> buildString {
                append("事件${decision.eventId}建议：${decision.label}")
                if (!decision.actionSafe) append("（仅推荐）")
                append("；${decision.explanation}")
                decision.safetyNote?.let { append("；$it") }
            }
            is LabyrinthEventChoiceDecision.Wait -> decision.reason
        }
        _state.value = _state.value.copy(eventChoiceDecision = decision, message = message)
    }

    private fun persistObservation(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ) {
        val store = runStateStore ?: return
        val accountId = activeRunAccountId
        if (
            result.observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
                !labyrinthIsRoleRewardPage(result) &&
                openingStateReset.compareAndSet(false, true)
        ) {
            lastPersistedObservationSignature = null
            relicFocusMark = null
            relicStackLedger.clear()
            pendingRelicSelection = null
            relicChoiceCommitted = false
            actionScope.launch {
                runCatching {
                    persistenceMutex.withLock { store.startNew(accountId, timestampMillis) }
                }
                    .onSuccess { snapshot ->
                        if (activeSessionId == sessionId) {
                            _state.value = _state.value.copy(
                                joinedCharacters = snapshot.joinedCharacters,
                                observedRelics = snapshot.observedRelics,
                            )
                            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                        }
                    }
                    .onFailure { failure ->
                        if (activeSessionId == sessionId) {
                            _state.value = _state.value.copy(message = "清理旧队伍状态失败：${failure.message ?: "未知错误"}")
                        }
                    }
            }
            return
        }
        val shopDialogState = result.shopObservation?.dialogState
        val purchasedRelic = when (shopDialogState) {
            LabyrinthShopDialogState.PURCHASE_CONFIRMATION -> {
                // Only a transaction explicitly planned as a relic is eligible for relic
                // persistence. Role-imprint icons can resemble relic templates and must never be
                // written into the relic ledger after their purchase-complete modal.
                pendingShopPurchase = plannedShopPurchaseRelicId?.let { plannedId ->
                    result.shopObservation.purchaseCandidate?.takeIf { it.relicId == plannedId }
                }
                null
            }

            LabyrinthShopDialogState.PURCHASE_COMPLETE -> {
                pendingShopPurchase.also { pendingShopPurchase = null }
            }

            LabyrinthShopDialogState.NONE -> {
                if (lastShopDialogState == LabyrinthShopDialogState.PURCHASE_CONFIRMATION) {
                    // Returning directly to the shop means the confirmation was cancelled.
                    pendingShopPurchase = null
                    clearPlannedShopPurchase()
                }
                null
            }

            LabyrinthShopDialogState.EXIT_CONFIRMATION -> {
                pendingShopPurchase = null
                null
            }
            null -> {
                pendingShopPurchase = null
                null
            }
        }
        lastShopDialogState = shopDialogState ?: LabyrinthShopDialogState.NONE
        val reconciled = labyrinthRosterReconciliationMatches(result)
        val characters = if (labyrinthRosterReplacesExisting(result, reconciled)) {
            // The three confirmed picks replace the roster; add the guild's automatic grant
            // (2026-09-17: 拉比林斯 → 菈比莉斯塔) so it is never lost to an unrecognised popup.
            reconciled + labyrinthGuildGrantedRosterMatches(
                guildId = validatedRoute?.guildId,
                frameRect = EntryPixelRect(0, 0, maxOf(1, result.frameWidth), maxOf(1, result.frameHeight)),
            )
        } else {
            reconciled
        }
        val confirmedRelic = if (relicAcquisitionGate.observe(relicChoiceCommitted, result.observation.state)) {
            pendingRelicSelection.also {
                pendingRelicSelection = null
                relicChoiceCommitted = false
                relicAcquisitionGate.reset()
            }
        } else {
            null
        }
        val relics = when {
            confirmedRelic != null -> listOf(confirmedRelic)
            purchasedRelic != null -> listOf(purchasedRelic)
            else -> emptyList()
        }
        relics.forEach(::advanceRelicStackCalibrationAfterAcquisition)
        val signature = buildString {
            append(result.observation.state.name)
            characters.forEach { character ->
                append('|')
                append(character.slotId)
                append(':')
                append(character.characterId)
                append(':')
                append(character.displayName)
            }
            relics.forEach { relic ->
                append('|')
                append(relic.slotId)
                append(':')
                append(relic.relicId)
                append(':')
                append(relic.attribute)
            }
        }
        if (signature == lastPersistedObservationSignature) return
        lastPersistedObservationSignature = signature
        actionScope.launch {
            runCatching {
                persistenceMutex.withLock {
                    store.recordPage(
                        accountId = accountId,
                        page = result.observation.state.name,
                        characters = characters,
                        nowMillis = timestampMillis,
                        relics = relics,
                        replaceCharacters = labyrinthRosterReplacesExisting(result, reconciled),
                    )
                }
            }.onSuccess { snapshot ->
                if (activeSessionId == sessionId) {
                    if (characters.isNotEmpty()) {
                        val confirmedIds = characters.mapNotNull { it.characterId }
                            .map(::canonicalLabyrinthRoleId)
                            .toSet()
                        synchronized(pendingAcquiredCharacterIds) {
                            pendingAcquiredCharacterIds.removeAll { pendingId ->
                                canonicalLabyrinthRoleId(pendingId) in confirmedIds
                            }
                            pendingAcquiredCharacterId = pendingAcquiredCharacterIds.firstOrNull()
                        }
                    }
                    _state.value = _state.value.copy(
                        joinedCharacters = snapshot.joinedCharacters,
                        observedRelics = snapshot.observedRelics,
                        pendingAcquiredCharacterId = pendingAcquiredCharacterId,
                        message = confirmedRelic?.displayName?.let { "选择遗物已入库：$it" }
                            ?: purchasedRelic?.displayName?.let { "购买遗物已入库：$it" }
                            ?: "页面识别已更新",
                    )
                    pendingAcquiredCharacterId = _state.value.pendingAcquiredCharacterId
                    refreshBattleTeamRecommendation(
                        sessionId,
                        _state.value.lastResult?.observation?.state ?: LabyrinthEntryPageState.UNKNOWN,
                    )
                    refreshBattleTeamSelectionPlan(
                        sessionId = sessionId,
                        pageState = _state.value.lastResult?.observation?.state ?: LabyrinthEntryPageState.UNKNOWN,
                        observation = _state.value.lastResult?.battleTeamSelection,
                    )
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
            }.onFailure { failure ->
                lastPersistedObservationSignature = null
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "保存本局队伍/遗物失败：${failure.message ?: "未知错误"}")
                }
            }
        }
    }

    /** A Boss tab change is a team boundary even though the page type stays the same. */
    private fun synchronizeBossEditor(result: LabyrinthEntryFrameResult) {
        if (actionInFlight.get()) return
        val observation = result.battleTeamSelection ?: return
        if (observation.recognitionState != com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState.STABLE) return
        val index = observation.bossTeamIndex ?: return
        if (combatContext?.kind == LabyrinthCombatKind.BOSS &&
            bossEditorPreparationStage !in setOf(
                LabyrinthBossEditorPreparationStage.INACTIVE,
                LabyrinthBossEditorPreparationStage.COMPLETE,
            )
        ) {
            // Preparation deliberately visits tabs out of the normal 1 -> 2 -> 3 commit flow.
            // Treat those transitions as cleanup navigation, never as a submitted team boundary.
            bossEditorTeamIndex = index
            pendingBossTeamAdvance = null
            bossEditorBlocked = false
            combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
                .copy(kind = LabyrinthCombatKind.BOSS, teamIndex = index)
            _state.value = _state.value.copy(combatContext = combatContext)
            return
        }
        if (bossEditorTeamIndex == index) return
        val pending = pendingBossTeamAdvance
        if (bossEditorTeamIndex == null) {
            bossEditorBlocked = index != 1
        } else if (pending?.first == index) {
            val advance = labyrinthBossTeamAdvanceDisposition(bossTeamMode, pending.second)
            if (advance.committedIds.isNotEmpty()) {
                synchronized(currentBattleTeamSignatures) {
                    currentBattleTeamSignatures += labyrinthBattleTeamSignature(advance.committedIds)
                }
                synchronized(committedBattleCharacterIds) {
                    committedBattleCharacterIds += advance.committedIds
                }
            }
            preparedBossFirstTeamIds = advance.preparedSingleTeamIds
        } else {
            // Untracked manual tab changes may hide other teams' members. Never silently reuse
            // the first team's recommendation against a different editor.
            bossEditorBlocked = true
        }
        pendingBossTeamAdvance = null
        bossEditorTeamIndex = index
        combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
            .copy(kind = LabyrinthCombatKind.BOSS, teamIndex = index)
        lastBattleTeamRecommendationKey = null
        resetBattleTeamExecutionTracking()
        _state.value = _state.value.copy(combatContext = combatContext)
    }

    /** Clear viewport-local proposals on page changes; route and acquisition context may survive. */
    private fun invalidatePreviousPageState(
        pageState: LabyrinthEntryPageState,
        preservePendingNodeMoveConfirmation: Boolean = false,
    ) {
        val previous = lastObservedPageState
        if (previous == pageState) return
        lastObservedPageState = pageState

        if (
            pageState == LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE &&
            plannedShopPurchaseRelicId != null &&
            !plannedShopPurchaseCounted
        ) {
            // The completed-purchase page is the visual commit point. Count it exactly once;
            // do not infer success merely because a tap was dispatched.
            shopRelicPurchasesThisCycle++
            plannedShopPurchaseCounted = true
        }

        if (pageState != LabyrinthEntryPageState.NODE_SELECTION) {
            finalBossLocalizationStartedAt = Long.MIN_VALUE
            resetPendingNodeClickStability()
            resetStrongRouteTargetBinding()
            resetNodeScrollSearchGate()
            if (!preservePendingNodeMoveConfirmation) {
                pendingNodeTransition = null
                nodeMoveConfirmationStableFrames = 0
                lastNodeMoveConfirmationRect = null
            }
            _state.value.routeProgress?.let { progress ->
                if (progress.nextNodeLabel != null || progress.nextNodeRect != null) {
                    _state.value = _state.value.copy(
                        routeProgress = progress.copy(nextNodeLabel = null, nextNodeRect = null),
                    )
                }
            }
        }
        if (pageState != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            lastBattleTeamRecommendationKey = null
            lastBattleTeamSelectionPlanLog = null
            battleTeamPageStallSince = Long.MIN_VALUE
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamRecommendationUnavailableReason = null,
                battleTeamSelectionPlan = null,
            )
        }
        if (pageState != LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION) {
            _state.value = _state.value.copy(roleRewardChoiceDecision = null)
        }
        if (pageState != LabyrinthEntryPageState.EVENT_CHOICE) {
            _state.value = _state.value.copy(eventChoiceDecision = null)
            resetEventChoiceCommit()
        }
        if (labyrinthPageUiOwner(pageState) != LabyrinthPageUiOwner.SHOP) {
            pendingShopPurchase = null
            val keepPurchaseTransition = labyrinthKeepsPlannedShopPurchaseAcrossPage(
                pageState = pageState,
                plannedRelicId = plannedShopPurchaseRelicId ?: plannedShopRoleImprintLabel,
                startedAt = plannedShopPurchaseStartedAt,
                now = clock(),
                timeoutMillis = SHOP_PURCHASE_TRANSITION_TIMEOUT_MILLIS,
            )
            if (!keepPurchaseTransition) {
                clearPlannedShopPurchase()
                lastShopDialogState = LabyrinthShopDialogState.NONE
            }
        }
    }

    /**
     * EX/Boss-only discovery pass for the game's official “有效效果” filter.
     *
     * The filter is useful encounter-specific evidence, but it is not allowed to become a blind
     * team selector. We first switch to the filter, rewind/scan the complete roster with the same
     * visual scrollbar gates used by normal auto-team search, record only safely identified cards,
     * then restore “全部”. Only after that visual round-trip is confirmed may team scoring run.
     */
    private fun refreshEffectiveCharacterScan(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        if (activeSessionId != sessionId || _state.value.dryRun) return
        if (result.observation.state != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) return
        if (combatContext?.kind !in setOf(LabyrinthCombatKind.EX, LabyrinthCombatKind.BOSS)) return
        if (combatContext?.kind == LabyrinthCombatKind.BOSS &&
            bossEditorPreparationStage != LabyrinthBossEditorPreparationStage.COMPLETE
        ) return
        val observation = result.battleTeamSelection ?: return

        // A completed sweep survives the EX -> Boss hand-off of one encounter, but only while the
        // owned roster is unchanged, because the filtered list only shows roles this account owns.
        if (effectiveCharacterScanStage == LabyrinthEffectiveCharacterScanStage.COMPLETE &&
            effectiveCharacterScanRosterStamp != currentEffectiveScanRosterStamp()
        ) {
            effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.IDLE
        }
        if (effectiveCharacterScanStage == LabyrinthEffectiveCharacterScanStage.IDLE) {
            synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.clear() }
            effectiveRosterSearch.reset()
            effectiveCharacterScanScrollActions = 0
            effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
            effectiveCharacterScanSkippedUnsafe = false
            effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.SWITCH_TO_EFFECTIVE
        }
        if (effectiveCharacterScanStage == LabyrinthEffectiveCharacterScanStage.COMPLETE) return
        if (actionInFlight.get()) return
        if (observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE) {
            _state.value = _state.value.copy(message = "有效效果扫描：等待编组页稳定")
            return
        }
        if (lastEffectiveCharacterScanActionAt != Long.MIN_VALUE &&
            timestampMillis - lastEffectiveCharacterScanActionAt < BATTLE_TEAM_ACTION_INTERVAL_MILLIS
        ) return

        when (effectiveCharacterScanStage) {
            LabyrinthEffectiveCharacterScanStage.IDLE -> Unit

            LabyrinthEffectiveCharacterScanStage.SWITCH_TO_EFFECTIVE -> {
                if (observation.currentFilter == LabyrinthBattleElementFilter.EFFECTIVE_EFFECT) {
                    effectiveRosterSearch.reset()
                    effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.SCANNING
                    _state.value = _state.value.copy(message = "有效效果扫描：已进入筛选，开始从顶部完整扫描")
                    return
                }
                val target = observation.filters.firstOrNull {
                    it.filter == LabyrinthBattleElementFilter.EFFECTIVE_EFFECT
                }
                if (target == null) {
                    _state.value = _state.value.copy(message = "有效效果扫描：尚未可靠识别“有效效果”筛选按钮")
                    return
                }
                dispatchEffectiveCharacterScanAction(
                    sessionId = sessionId,
                    label = "有效效果扫描：切换有效效果",
                    action = AutomationAction.Tap(
                        ScreenPoint(
                            target.screenRect.left + target.screenRect.width / 2f,
                            target.screenRect.top + target.screenRect.height / 2f,
                        ),
                    ),
                    timestampMillis = timestampMillis,
                )
            }

            LabyrinthEffectiveCharacterScanStage.SCANNING -> {
                if (observation.currentFilter != LabyrinthBattleElementFilter.EFFECTIVE_EFFECT) {
                    effectiveRosterSearch.reset()
                    effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.SWITCH_TO_EFFECTIVE
                    _state.value = _state.value.copy(message = "有效效果扫描：筛选状态变化，重新确认有效效果页")
                    return
                }
                if (
                    labyrinthEffectiveFilterIsEmpty(
                        observation = observation,
                        emptyNoticeScore = result.observation.anchorScores[EntryAnchorId.BATTLE_TEAM_ROSTER_EMPTY_NOTICE],
                    )
                ) {
                    // 「未搜索到该角色」: zero effective roles is a complete answer.
                    effectiveRosterSearch.reset()
                    effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.RETURN_TO_ALL
                    _state.value = _state.value.copy(
                        message = "有效效果筛选下没有角色（未搜索到该角色）；本局无对策角色，准备恢复全部筛选",
                    )
                    return
                }

                val frameEvidence = labyrinthEffectiveScanFrameEvidence(observation)
                val unsafe = frameEvidence.unsafeVisibleCharacters
                synchronized(effectiveExCharacterIds) {
                    frameEvidence.confirmedCharacterIds.forEach(effectiveExCharacterIds::add)
                }

                if (unsafe.isNotEmpty()) {
                    if (effectiveCharacterUnsafeStartedAt == Long.MIN_VALUE) {
                        effectiveCharacterUnsafeStartedAt = timestampMillis
                    }
                    if (timestampMillis - effectiveCharacterUnsafeStartedAt <
                        EFFECTIVE_SCAN_UNSAFE_SETTLE_MILLIS
                    ) {
                        _state.value = _state.value.copy(
                            message = "有效效果扫描：当前页有${unsafe.size}个角色身份未过安全线；短暂等待稳定后仍会继续滚动",
                        )
                        return
                    }
                    // An unresolved portrait must never be promoted into an effective-role id,
                    // but it also must not deadlock the whole Boss/EX preparation. Keep every
                    // independently confirmed role from this viewport and continue traversing the
                    // filtered roster. A later overlapping viewport may identify the same card
                    // reliably; otherwise the unresolved card simply contributes no bonus.
                    effectiveCharacterScanSkippedUnsafe = true
                } else {
                    effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
                }

                val searchDecision = effectiveRosterSearch.observe(
                    sessionId = sessionId,
                    observation = observation,
                    recommendedIds = EFFECTIVE_SCAN_CONTEXT_IDS,
                )
                when (searchDecision) {
                    LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE -> {
                        _state.value = _state.value.copy(message = "有效效果扫描：等待滚动回弹并稳定")
                    }
                    LabyrinthBattleRosterSearchDecision.EXHAUSTED -> {
                        effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.RETURN_TO_ALL
                        _state.value = _state.value.copy(
                            message = "有效效果扫描到底：共确认" +
                                synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.size } +
                                "名" +
                                if (effectiveCharacterScanSkippedUnsafe) "；未可靠身份已跳过；准备恢复全部筛选"
                                else "；准备恢复全部筛选",
                        )
                    }
                    LabyrinthBattleRosterSearchDecision.UNAVAILABLE -> {
                        effectiveRosterSearch.reset()
                        effectiveCharacterScanScrollActions = 0
                        _state.value = _state.value.copy(message = "有效效果扫描暂时无法确认列表反馈；保持编组页并重新从顶部建立扫描")
                        return
                    }
                    LabyrinthBattleRosterSearchDecision.TO_TOP,
                    LabyrinthBattleRosterSearchDecision.NEXT_PAGE,
                    -> {
                        if (effectiveCharacterScanScrollActions >= MAX_EFFECTIVE_SCAN_SCROLL_ACTIONS) {
                            effectiveRosterSearch.reset()
                            effectiveCharacterScanScrollActions = 0
                            _state.value = _state.value.copy(message = "有效效果扫描达到单轮滚动上限；保持编组页并重新从顶部复扫")
                            return
                        }
                        val toTop = searchDecision == LabyrinthBattleRosterSearchDecision.TO_TOP
                        // Match the normal roster-search gesture: the Boss editor has a shorter
                        // viewport, so use a smaller drag there and keep at least one full card
                        // height overlapped between consecutive captures.  Without this, the old
                        // 0.67H -> 0.43H drag could move ~259 px on a 1080p frame while the Boss
                        // viewport is only ~386 px tall, leaving less than one ~195 px card of
                        // overlap and making a skipped row possible if the list moves as far as
                        // the gesture.
                        val upper = ScreenPoint(
                            frameWidth * 0.75f,
                            frameHeight * if (observation.bossTeamIndex != null) 0.50f else 0.43f,
                        )
                        val lower = ScreenPoint(frameWidth * 0.75f, frameHeight * 0.67f)
                        dispatchEffectiveCharacterScanAction(
                            sessionId = sessionId,
                            label = if (toTop) "有效效果扫描：返回列表顶部" else "有效效果扫描：查看下一页",
                            action = AutomationAction.Swipe(
                                start = if (toTop) upper else lower,
                                end = if (toTop) lower else upper,
                                durationMillis = EFFECTIVE_SCAN_SCROLL_DURATION_MILLIS,
                            ),
                            timestampMillis = timestampMillis,
                            scrollDirection = if (toTop) {
                                LabyrinthBattleRosterScrollDirection.TO_TOP
                            } else {
                                LabyrinthBattleRosterScrollDirection.NEXT_PAGE
                            },
                            scrollOriginPosition = observation.scrollbar.position
                                .takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.5,
                        )
                    }
                }
            }

            LabyrinthEffectiveCharacterScanStage.RETURN_TO_ALL -> {
                if (observation.currentFilter == LabyrinthBattleElementFilter.ALL) {
                    effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.COMPLETE
                    effectiveCharacterScanRosterStamp = currentEffectiveScanRosterStamp()
                    effectiveRosterSearch.reset()
                    lastBattleTeamRecommendationKey = null
                    _state.value = _state.value.copy(
                        message = "有效效果扫描完成：确认" +
                            synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.size } +
                            "名对策角色；开始生成阵容",
                    )
                    return
                }
                val target = observation.filters.firstOrNull { it.filter == LabyrinthBattleElementFilter.ALL }
                if (target == null) {
                    _state.value = _state.value.copy(message = "有效效果扫描完成，等待可靠识别“全部”筛选按钮")
                    return
                }
                dispatchEffectiveCharacterScanAction(
                    sessionId = sessionId,
                    label = "有效效果扫描：恢复全部",
                    action = AutomationAction.Tap(
                        ScreenPoint(
                            target.screenRect.left + target.screenRect.width / 2f,
                            target.screenRect.top + target.screenRect.height / 2f,
                        ),
                    ),
                    timestampMillis = timestampMillis,
                )
            }

            LabyrinthEffectiveCharacterScanStage.COMPLETE -> Unit
        }
    }

    private fun dispatchEffectiveCharacterScanAction(
        sessionId: AutomationSessionId,
        label: String,
        action: AutomationAction,
        timestampMillis: Long,
        scrollDirection: LabyrinthBattleRosterScrollDirection? = null,
        scrollOriginPosition: Double? = null,
    ) {
        if (!beginGuardedAction()) return
        traceAction(label)
        lastEffectiveCharacterScanActionAt = timestampMillis
        if (scrollDirection != null) effectiveCharacterScanScrollActions++
        launchGuardedAction {
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(action, "动作执行器不可用")
            } else {
                executor.execute(sessionId, action)
            }
            when (result) {
                is AutomationActionResult.Executed -> if (activeSessionId == sessionId) {
                    if (scrollDirection != null && scrollOriginPosition != null) {
                        effectiveRosterSearch.recordExecutedScroll(scrollDirection, scrollOriginPosition)
                        // Give each newly exposed viewport its own short recognition grace period.
                        effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
                    }
                    val current = _state.value
                    _state.value = current.copy(
                        actionCount = current.actionCount + 1,
                        lastActionLabel = label,
                        message = "已执行：$label；等待视觉确认",
                    )
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
                is AutomationActionResult.Rejected -> {
                    if (scrollDirection != null) {
                        effectiveCharacterScanScrollActions = (effectiveCharacterScanScrollActions - 1).coerceAtLeast(0)
                    }
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        lastEffectiveCharacterScanActionAt = Long.MIN_VALUE
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("有效效果扫描动作被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    /** Builds the recommendation consumed by the guarded battle-team execution stage. */
    private fun refreshBattleTeamRecommendation(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
    ) {
        if (activeSessionId != sessionId) return
        if (pageState != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            if (
                lastBattleTeamRecommendationKey != null ||
                _state.value.battleTeamRecommendation != null ||
                _state.value.battleTeamRecommendationUnavailableReason != null
            ) {
                lastBattleTeamRecommendationKey = null
                _state.value = _state.value.copy(
                    battleTeamRecommendation = null,
                    battleTeamRecommendationUnavailableReason = null,
                )
            }
            return
        }

        if (combatContext?.kind == LabyrinthCombatKind.BOSS &&
            bossEditorPreparationStage != LabyrinthBossEditorPreparationStage.COMPLETE
        ) {
            lastBattleTeamRecommendationKey = null
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamRecommendationUnavailableReason = null,
                battleTeamSelectionPlan = null,
                message = "Boss编组预处理：清空队伍1/2/3并返回队伍1",
            )
            return
        }

        if (combatContext?.kind in setOf(LabyrinthCombatKind.EX, LabyrinthCombatKind.BOSS) &&
            effectiveCharacterScanStage != LabyrinthEffectiveCharacterScanStage.COMPLETE
        ) {
            lastBattleTeamRecommendationKey = null
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamRecommendationUnavailableReason = null,
                battleTeamSelectionPlan = null,
                message = "EX/Boss编组前正在扫描“有效效果”角色；完成后再生成阵容",
            )
            return
        }

        if (
            bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM &&
            preparedBossFirstTeamIds.isNotEmpty() &&
            bossEditorTeamIndex in 2..3
        ) {
            _state.value = _state.value.copy(battleTeamRecommendation = null, battleTeamSelectionPlan = null,
                battleTeamRecommendationUnavailableReason = "第一队已确认；按单队首战策略核对空置队伍并前往第三队开始战斗")
            return
        }
        val partialBossTarget = bossMultiTeamTargetCount
        if (
            bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM &&
            partialBossTarget != null && partialBossTarget < 3 &&
            (bossEditorTeamIndex ?: 1) > partialBossTarget
        ) {
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamSelectionPlan = null,
                battleTeamRecommendationUnavailableReason =
                    "Boss已确认${partialBossTarget}支安全队伍；后续队伍留空并准备开始挑战",
            )
            return
        }

        val current = _state.value
        val currentCombatContext = combatContext
        val encounterStrategy = when (currentCombatContext?.kind) {
            LabyrinthCombatKind.EX -> currentExEncounterStrategy
            LabyrinthCombatKind.BOSS -> labyrinthBossEncounterStrategy(validatedRoute)
            else -> null
        }
        val preferCohesiveDamageSystem = when (currentCombatContext?.kind) {
            LabyrinthCombatKind.EX -> true
            LabyrinthCombatKind.BOSS -> preferPureBossDamageSystem
            else -> false
        }
        val defenseStacks = relicChoicePolicy.markStacks(
            current.observedRelics,
            relicMarkStackCalibration,
        )
            .getValue(LabyrinthRelicMark.DEFENSE)
        val targetCount = encounterStrategy?.targetCount ?: currentCombatContext?.targetCount ?: 1
        val requestedBossTeamCount = if (
            currentCombatContext?.kind == LabyrinthCombatKind.BOSS &&
            bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM
        ) {
            val absoluteTarget = bossMultiTeamTargetCount ?: 3
            (absoluteTarget - currentCombatContext.teamIndex + 1).coerceIn(1, 3)
        } else {
            1
        }
        val acquiredCharacterIds = current.joinedCharacters
            .map(LabyrinthJoinedCharacter::characterId)
            .map(::canonicalLabyrinthRoleId)
            .distinct()
        val cacheKey = buildString {
            append(acquiredCharacterIds.sorted().joinToString(","))
            append("|defense=")
            append(defenseStacks)
            append("|targets=")
            append(targetCount)
            append("|team=")
            append(currentCombatContext?.teamIndex ?: 1)
            append("|bossMode=")
            append(bossTeamMode.name)
            append("|fallbackMulti=")
            append(bossFallbackMultiTeamActive)
            append("|requestedBossTeams=")
            append(requestedBossTeamCount)
            append("|bossTarget=")
            append(bossMultiTeamTargetCount ?: 0)
            append("|retry=")
            append(battleRetryCount)
            append("|encounter=")
            append(encounterStrategy?.id ?: "none")
            append("|effective=")
            append(synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.sorted().joinToString(",") })
            append("|cohesiveDamage=")
            append(preferCohesiveDamageSystem)
            append("|failedTeams=")
            append(synchronized(failedBattleTeamSignatures) { failedBattleTeamSignatures.sorted().joinToString(";") })
            append("|used=")
            append(
                synchronized(committedBattleCharacterIds) {
                    committedBattleCharacterIds.sorted().joinToString(",")
                },
            )
        }
        if (
            cacheKey == lastBattleTeamRecommendationKey &&
            (current.battleTeamRecommendation != null ||
                current.battleTeamRecommendationUnavailableReason != null)
        ) {
            val message = labyrinthBattleTeamSelectionMessage(
                combatContext = currentCombatContext,
                recommendation = current.battleTeamRecommendation,
                unavailableReason = current.battleTeamRecommendationUnavailableReason,
            )
            if (current.message != message) _state.value = current.copy(message = message)
            return
        }

        val usedCharacterIds = synchronized(committedBattleCharacterIds) {
            committedBattleCharacterIds.toSet()
        }
        val eligibleCharacterIds = acquiredCharacterIds.filterNot(usedCharacterIds::contains)
        val planner = battleTeamRecommendationPlanner
        // 「不可用」是否由 cacheKey 的输入唯一决定。规划器自己的结论（没有可用角色、没有合格一号位
        // 等）是输入的纯函数，可以缓存；真正的瞬时故障（资料未加载、计算抛错）不是，缓存住就等于把
        // 一次瞬时失败固定成终态、每帧都命中同一条原因。内存不足是唯一的例外：按既有注释，它绝不
        // 能每帧重算。
        var recommendationResultCacheable = true
        val recommendationResult = if (planner == null) {
            recommendationResultCacheable = false
            LabyrinthBattleTeamRecommendationResult.Unavailable(
                battleTeamRecommendationUnavailableReason ?: "角色决策资料未加载",
            )
        } else {
            runCatching {
                val decisionContext = LabyrinthRoleDecisionContext(
                    defenseMarkStacks = defenseStacks,
                    targetCount = targetCount,
                    optimizeBossVanguardSynergy = currentCombatContext?.kind == LabyrinthCombatKind.BOSS &&
                        currentCombatContext.teamIndex == 1,
                    preferSingleDamageSystem = preferCohesiveDamageSystem,
                    encounterStrategy = encounterStrategy,
                    effectiveCharacterIds = synchronized(effectiveExCharacterIds) {
                        effectiveExCharacterIds.toSet()
                    },
                    relaxedVanguardGate = currentCombatContext?.kind == LabyrinthCombatKind.BOSS &&
                        bossFallbackMultiTeamActive,
                )
                // Team 1 must be tank-led. Teams 2/3 fill their slot even without a qualified
                // vanguard: 2026-09-17 a Boss survived on a sliver because slot 3 was left empty.
                val allowSupplementLead = currentCombatContext?.kind == LabyrinthCombatKind.BOSS &&
                    bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM &&
                    currentCombatContext.teamIndex > 1
                if (battleRetryCount > 0) {
                    val failedSignatures = synchronized(failedBattleTeamSignatures) {
                        failedBattleTeamSignatures.toSet()
                    }
                    planner.retryRecommendation(
                        acquiredCharacterIds = eligibleCharacterIds,
                        context = decisionContext,
                        failedTeamSignatures = failedSignatures,
                        retryNumber = battleRetryCount,
                        lastFailedTeamSignature = synchronized(failedBattleTeamSignatures) {
                            failedBattleTeamSignatures.lastOrNull()
                        },
                        requestedBossTeamCount = requestedBossTeamCount,
                        allowSupplementLead = allowSupplementLead,
                    )
                } else {
                    planner.initialRecommendation(
                        acquiredCharacterIds = eligibleCharacterIds,
                        context = decisionContext,
                        requestedBossTeamCount = requestedBossTeamCount,
                        allowSupplementLead = allowSupplementLead,
                    )
                }
            }.getOrElse { failure ->
                recommendationResultCacheable = failure is OutOfMemoryError
                LabyrinthBattleTeamRecommendationResult.Unavailable(
                    if (failure is OutOfMemoryError) {
                        // Do not retry every frame or misreport this as missing character data.
                        "编组计算内存不足，已停止本次自动编组；请停止并重启辅助 App 后再试"
                    } else {
                        "生成第${currentCombatContext?.teamIndex ?: 1}队建议失败：${failure.message ?: "未知错误"}"
                    },
                )
            }
        }
        // 只缓存「由 cacheKey 唯一决定」的结果：瞬时故障不写缓存键，下一帧就会重算。
        lastBattleTeamRecommendationKey = cacheKey.takeIf { recommendationResultCacheable }
        when (recommendationResult) {
            is LabyrinthBattleTeamRecommendationResult.Ready -> {
                val recommendation = recommendationResult.recommendation
                if (
                    currentCombatContext?.kind == LabyrinthCombatKind.BOSS &&
                    bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM
                ) {
                    val teamIndex = currentCombatContext.teamIndex.coerceIn(1, 3)
                    val absoluteTarget = (teamIndex - 1 + recommendation.plannedBossTeamCount).coerceIn(teamIndex, 3)
                    if (bossMultiTeamTargetCount == null || teamIndex == 1) {
                        bossMultiTeamTargetCount = absoluteTarget
                    }
                }
                _state.value = current.copy(
                    battleTeamRecommendation = recommendation,
                    battleTeamRecommendationUnavailableReason = null,
                    message = labyrinthBattleTeamSelectionMessage(
                        combatContext = currentCombatContext,
                        recommendation = recommendation,
                        unavailableReason = null,
                    ),
                )
                runCatching { Log.i(TEAM_RECOMMENDATION_LOG_TAG, recommendation.logText()) }
            }

            is LabyrinthBattleTeamRecommendationResult.Unavailable -> {
                _state.value = current.copy(
                    battleTeamRecommendation = null,
                    battleTeamRecommendationUnavailableReason = recommendationResult.reason,
                    message = labyrinthBattleTeamSelectionMessage(
                        combatContext = currentCombatContext,
                        recommendation = null,
                        unavailableReason = recommendationResult.reason,
                    ),
                )
                runCatching {
                    Log.w(TEAM_RECOMMENDATION_LOG_TAG, "第一队建议不可用：${recommendationResult.reason}")
                }
            }
        }
    }

    private fun refreshBattleTeamSelectionPlan(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
        observation: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation?,
    ) {
        if (activeSessionId != sessionId) return
        val recommendation = _state.value.battleTeamRecommendation
        val planner = battleTeamSelectionPlanner
        if (
            pageState != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION ||
            recommendation == null ||
            observation == null ||
            planner == null
        ) {
            if (_state.value.battleTeamSelectionPlan != null || lastBattleTeamSelectionPlanLog != null) {
                lastBattleTeamSelectionPlanLog = null
                _state.value = _state.value.copy(battleTeamSelectionPlan = null)
            }
            return
        }
        val recommendationKey = recommendation.members.joinToString(",") {
            canonicalLabyrinthRoleId(it.characterId)
        }
        if (exhaustedBattleRosterFiltersKey != recommendationKey) {
            exhaustedBattleRosterFiltersKey = recommendationKey
            exhaustedBattleRosterFilters.clear()
        }
        val plan = planner.plan(
            sessionId = sessionId,
            recommendation = recommendation,
            observation = observation,
            exhaustedFilters = exhaustedBattleRosterFilters.toSet(),
            // Ordinary battles keep whatever full team the game already has instead of paying for
            // a re-selection; a failed attempt (battleRetryCount > 0) goes back to the normal
            // recommendation path, which is the existing retry behaviour.
            holdCurrentTeamRequested = combatContext?.kind == LabyrinthCombatKind.NORMAL &&
                battleRetryCount == 0,
        )
        val logText = plan.logText()
        val current = _state.value
        _state.value = current.copy(
            battleTeamSelectionPlan = plan,
            message = labyrinthBattleTeamSelectionMessage(
                combatContext = combatContext,
                recommendation = recommendation,
                unavailableReason = null,
                selectionPlan = plan,
            ),
        )
        if (lastBattleTeamSelectionPlanLog != logText) {
            lastBattleTeamSelectionPlanLog = logText
            runCatching { Log.i(TEAM_SELECTION_PLAN_LOG_TAG, logText) }
        }
    }

    private fun handleActionDecision(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        if (actionInFlight.get() || activeSessionId != sessionId) return
        val planner = actionPlanner ?: return
        val decision = synchronized(actionPlannerLock) {
            planner.decide(
                state = result.observation.state,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                nowMillis = timestampMillis,
                anchorScores = result.observation.anchorScores,
                anchorMatches = result.anchorMatches,
                openingCharacterMatches = result.openingCharacterMatches,
                openingCharacterSelection = result.openingCharacterSelection,
            )
        }
        when (decision) {
            is LabyrinthEntryActionDecision.Wait -> {
                if (activeSessionId == sessionId && _state.value.message != decision.reason) {
                    _state.value = _state.value.copy(message = decision.reason)
                }
            }
            is LabyrinthEntryActionDecision.Execute -> dispatchAction(sessionId, decision)
            is LabyrinthEntryActionDecision.Complete ->
                if (!nodeExecutionConfigured() && labyrinthCompletionWaitsWithoutRoute(decision.state)) {
                    // The run-clear settlement chain is owned by route execution. Without a
                    // configured route there is nobody to drive it, but stopping on the score
                    // page abandons a finished run mid-settlement. Stay put and say so.
                    _state.value = _state.value.copy(
                        message = "已到达通关结算页 ${decision.state.name}；未配置路线，保持在当前页不自动点击",
                    )
                } else if (nodeExecutionConfigured() && shouldHandOffToRouteExecution(decision.state)) {
                    // A live session can be started on the map or on a route-owned page. Hand it
                    // to the route executor instead of treating the entry planner as finished.
                    entryPhaseComplete = true
                    existingRunResumeHandoffArmed = false
                    _state.value = _state.value.copy(
                        message = if (decision.state == LabyrinthEntryPageState.NODE_SELECTION) {
                            "入口流程完成，开始按已保存路线执行"
                        } else {
                            "检测到中途运行页面 ${decision.state.name}，接管当前路线执行"
                        },
                    )
                } else {
                    finishFromPlanner(sessionId, decision.reason)
                }
            is LabyrinthEntryActionDecision.Stop -> finishFromPlanner(sessionId, decision.reason)
        }
    }

    private fun dispatchAction(
        sessionId: AutomationSessionId,
        decision: LabyrinthEntryActionDecision.Execute,
    ) {
        if (!beginGuardedAction()) return
        traceAction(decision.label)
        launchGuardedAction {
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(decision.action, "动作执行器不可用")
            } else {
                executor.execute(sessionId, decision.action)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    if (decision.kind == LabyrinthEntryActionKind.RESUME_DAWN_REALM) {
                        // The next page may be the node map, a choice page, or an unfinished
                        // CHARACTER_JOINED reward from before the app/session was restarted.
                        existingRunResumeHandoffArmed = true
                    }
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = decision.label,
                            message = "已执行：${decision.label}",
                        )
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }
                is AutomationActionResult.Rejected -> {
                    if (decision.kind == LabyrinthEntryActionKind.RESUME_DAWN_REALM) {
                        existingRunResumeHandoffArmed = false
                    }
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        synchronized(actionPlannerLock) {
                            actionPlanner = createActionPlanner()
                        }
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("点击被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    /**
     * Stop initiated by the run's own logic (a terminal page, a rejected tap, a planner stop).
     *
     * When a batch owns this run the MediaProjection session belongs to the batch, not to the
     * run: the next run reuses it, and Android 14+ would demand a fresh consent if it were
     * released here. 2026-09-17 live: the cleared run's stop released capture, so the following
     * client-invalidation step found no capture and the batch halted with
     * CLIENT_INVALIDATION_FAILED. Only a user-initiated [stop] releases capture during a batch.
     */
    internal suspend fun stopFromRunLogic(reason: String): Boolean =
        stop(reason, releaseCapture = currentRunId == null)

    private fun resetEventChoiceCommit() {
        eventChoiceCommitted = false
        eventChoiceCommittedAt = Long.MIN_VALUE
        eventChoiceCommitAttempts = 0
        eventChoiceCommittedChoiceId = null
        eventChoiceRejectedIds.clear()
    }

    private fun finishFromPlanner(sessionId: AutomationSessionId, reason: String) {
        if (!beginGuardedAction()) return
        launchGuardedAction {
            if (activeSessionId == sessionId) stopFromRunLogic(reason)
        }
    }

    private fun finishFromPlannerAndRequestReroll(
        sessionId: AutomationSessionId,
        reason: String,
    ) {
        if (!beginGuardedAction()) return
        val accountId = activeRunAccountId
        launchGuardedAction {
            if (activeSessionId == sessionId) {
                // The next run reuses this projection; only the run ends here.
                pendingTerminalOutcome = LabyrinthRunTerminalOutcome.FAILED_MAX_RETRY
                val stopped = stop("$reason；正在切换到刷开局", releaseCapture = false)
                if (stopped && accountId != null) {
                    val started = runCatching { rerollRequester?.invoke(accountId) == true }.getOrDefault(false)
                    if (!started) {
                        _state.value = _state.value.copy(
                            message = "$reason；自动重刷启动失败，请手动进入刷开局页面继续",
                        )
                    }
                } else if (stopped) {
                    _state.value = _state.value.copy(
                        message = "$reason；缺少当前账号，未自动启动重刷",
                    )
                }
            }
        }
    }

    private fun nodeExecutionConfigured(): Boolean =
        routeLoader != null && nodeTemplateLoader != null

    private fun shouldHandOffToRouteExecution(state: LabyrinthEntryPageState): Boolean = state in setOf(
        LabyrinthEntryPageState.NODE_SELECTION,
        LabyrinthEntryPageState.LINK_CHOICE,
        LabyrinthEntryPageState.RELIC_CHOICE,
        LabyrinthEntryPageState.EVENT_CHOICE,
        LabyrinthEntryPageState.EVENT_ANIMATION,
        LabyrinthEntryPageState.ITEM_REWARD,
        LabyrinthEntryPageState.SHOP,
        LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
        LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
        LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
        LabyrinthEntryPageState.BATTLE_CHALLENGE,
        LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
        LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
        LabyrinthEntryPageState.BATTLE_RESULT,
        LabyrinthEntryPageState.RUN_CLEAR_RESULT,
        LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
        LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
        LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
        LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
        LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
    )

    private fun currentShopDecision(result: LabyrinthEntryFrameResult): LabyrinthShopDecision? {
        val shop = result.shopObservation ?: return null
        if (shop.dialogState != LabyrinthShopDialogState.NONE) return null
        val refreshAvailable = result.anchorMatches[EntryAnchorId.SHOP_REFRESH_BUTTON]
            ?.score
            ?.let { it >= POST_ENTRY_ANCHOR_MIN_SCORE }
            ?.let { anchorReady -> anchorReady && shop.refreshButtonEnabled }
            ?: false
        return shopPolicy.decide(
            // Prefer the area of the node we actually tapped. routeProgress.currentArea can lag
            // behind while the post-entry shop page is already visible, which previously caused
            // an area-4 shop to fall through into relic recognition and stop on missing metadata.
            currentArea = activeNodeArea ?: _state.value.routeProgress?.currentArea,
            items = shop.items,
            acquired = _state.value.observedRelics,
            calibratedStacks = relicMarkStackCalibration,
            refreshAvailable = refreshAvailable,
            lockedFocus = relicFocusMark,
            relicPurchasesInCycle = shopRelicPurchasesThisCycle,
        )
    }

    /** Battle waiting gates all route taps before UNKNOWN can reach any animation fallback. */
    private fun handleBattleWaitFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        if (result.relicDetailObservation != null || result.nodeMoveConfirmation != null) {
            battleWait.reset()
            return false
        }
        val restartSafeBossContext = combatContext ?: labyrinthPersistedBossNode(validatedRoute)?.let {
            LabyrinthCombatContext(LabyrinthCombatKind.BOSS)
        }
        val bossSettlementNextRect = labyrinthBossSettlementNextButtonRect(
            pageState = result.observation.state,
            combatContext = restartSafeBossContext,
            nextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON],
            bossSummaryNextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON],
        )
        if (bossSettlementNextRect != null) {
            if (combatContext?.kind != LabyrinthCombatKind.BOSS) {
                activeNodeType = LabyrinthNodeTypes.BOSS
                combatContext = restartSafeBossContext
                _state.value = _state.value.copy(combatContext = combatContext)
            }
            // The current client renders area-3/area-5 Boss victory summaries as UNKNOWN pages.
            // Do not let the generic battle waiter swallow a real post-battle "下一步" screen
            // for up to five minutes. The post-entry handler below will use this exact rect.
            battleWait.reset()
            postEntryUnknownSince = Long.MIN_VALUE
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "已确认进入Boss战后结算，结束战斗等待并准备点击下一步",
                )
            }
            return false
        }
        val battleResultNextReady = result.observation.state == LabyrinthEntryPageState.BATTLE_RESULT &&
            (result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON]?.score ?: 0.0) >=
            POST_ENTRY_ANCHOR_MIN_SCORE
        // 「编组页动作派发后画面没动」的兜底必须先于战斗等待判定：编组页在战斗等待的 3 秒宽限内是
        // WAIT，宽限一过又变成 NONE，两条路都会把这一帧继续交下去，而卡住的运行永远等不到结果。
        if (handleUnconfirmedBattleTeamActionFrame(sessionId, result, timestampMillis)) return true
        when (battleWait.observe(
            page = result.observation.state,
            now = timestampMillis,
            battleResultNextReady = battleResultNextReady,
        )) {
            LabyrinthBattleWaitDecision.NONE -> {
                acknowledgeBattleTeamActionProgress(result)
                return false
            }
            LabyrinthBattleWaitDecision.TIMED_OUT -> {
                finishFromPlanner(sessionId, "等待战斗结算超过5分钟，已停止并保留诊断状态")
                return true
            }
            LabyrinthBattleWaitDecision.WAIT -> {
                acknowledgeBattleTeamActionProgress(result)
                clearCharacterAcquisitionContext()
                waitingForManualRoleSelection = false
                postEntryUnknownSince = Long.MIN_VALUE
                resetPostEntryStall()
                // Do not inherit reward animation evidence when battle ends on an unknown frame.
                lastPostEntryState = LabyrinthEntryPageState.BATTLE_IN_PROGRESS
                postEntryStableFrames = 0
                postEntryAttempts = 0
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "等待战斗结算（最长5分钟）；暂停动画补点",
                    )
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
                return true
            }
        }
    }

    /**
     * 画面已经离开编组页（或换了 Boss 页签）⇒ 上一次编组页动作被游戏收下了。
     *
     * 撤掉「没被收下」的记账并清零连续失败计数，让后面的动作重新从零开始。
     *
     * 「进度」的判据是**编组页签名变了**（页面类型 + Boss 队伍页签），不是「页面类型变了」：
     * Boss 切队成功后页面类型仍然是编组页，只有页签变——只看页面类型会把一次成功的切队当成「点了
     * 没反应」，pending 与连续失败计数就会带到下一队去。签名没变的编组页帧什么都不能清（那正是
     * 「派发了但画面没动」的本体），没有 pending 时也不能清（刚被判未确认的那一次要留住计数）。
     */
    private fun acknowledgeBattleTeamActionProgress(result: LabyrinthEntryFrameResult) {
        if (result.observation.state == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            val pending = pendingBattleTeamAction
            if (pending == null || battleTeamEditorSignature(result) == pending.signature) return
        }
        pendingBattleTeamAction = null
        battleTeamActionUnconfirmedAttempts = 0
    }

    /**
     * 「编组页动作派发之后画面原地不动」的统一兜底：START_BATTLE 与 NEXT_BOSS_TEAM 共用。
     *
     * 判定条件刻意最窄——从派发那一刻起**每一帧都还是同一个编组页签名**：游戏只要收下了这一下，
     * 画面必然换页（加载 / 战斗 / 结算 / 未知过渡）或换 Boss 页签。超过
     * [BATTLE_TEAM_ACTION_ACK_TIMEOUT_MILLIS] 仍原地不动 ⇒ 认定这一下没被接受（实机上被弹窗关闭动画
     * 吞掉），回滚本次提交并重置，让下一帧重建计划重试；重复失败落在
     * [MAX_BATTLE_TEAM_ACTION_UNCONFIRMED] 次以内，超限停机并保留诊断，所以既不会静默无限等待，
     * 也不会无限重发。
     */
    private fun handleUnconfirmedBattleTeamActionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        battleTeamActionStopReason?.let { reason ->
            // 停机申请还没落定：每帧再要一次（申请本身可能被单动作锁瞬时挡下）。
            finishFromPlanner(sessionId, reason)
            return true
        }
        val pending = pendingBattleTeamAction ?: return false
        if (result.observation.state != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION ||
            battleTeamEditorSignature(result) != pending.signature
        ) {
            acknowledgeBattleTeamActionProgress(result)
            return false
        }
        if (timestampMillis - pending.dispatchedAt < BATTLE_TEAM_ACTION_ACK_TIMEOUT_MILLIS) return false
        pendingBattleTeamAction = null
        battleTeamActionUnconfirmedAttempts++
        if (battleTeamActionUnconfirmedAttempts > MAX_BATTLE_TEAM_ACTION_UNCONFIRMED) {
            val reason = "队伍编组页动作连续${battleTeamActionUnconfirmedAttempts}次派发后画面仍未推进" +
                "（${pending.label}）；已停止并保留诊断状态"
            battleTeamActionStopReason = reason
            finishFromPlanner(sessionId, reason)
            return true
        }
        recoverUnconfirmedBattleTeamAction(sessionId, pending)
        return true
    }

    /**
     * 「编组页动作已派发、画面却没动」的回收。
     *
     * START_BATTLE 的提交（角色 id、队伍签名、以及普通关卡的上一场编组记忆）是在派发成功的同一处写进去的，
     * 所以回收也必须一起还回去；NEXT_BOSS_TEAM 在派发时并没有提交任何角色（提交发生在页签真的变化之后），
     * 只撤销它的待办推进。两条路径都重置战斗等待与编组执行跟踪，并丢掉推荐/计划的缓存，让下一帧重建。
     */
    private fun recoverUnconfirmedBattleTeamAction(
        sessionId: AutomationSessionId,
        pending: PendingBattleTeamAction,
    ) {
        if (
            pending.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE &&
            pending.confirmedTeamIds.isNotEmpty()
        ) {
            val released = pending.confirmedTeamIds.toSet()
            synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.removeAll(released) }
            synchronized(currentBattleTeamSignatures) {
                currentBattleTeamSignatures.remove(labyrinthBattleTeamSignature(pending.confirmedTeamIds))
            }
        }
        if (pending.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE) {
            // 这一下没被游戏收下，普通关卡的编组记忆还原成当次捕获的原值（最近一次派发之前的值）。
            lastNormalBattleTeamSignature = pending.previousNormalTeamSignature
        }
        if (pending.kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM) {
            pendingBossTeamAdvance = null
        }
        battleWait.reset()
        resetBattleTeamExecutionTracking()
        battleTeamPageStallSince = Long.MIN_VALUE
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        if (activeSessionId == sessionId) {
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamRecommendationUnavailableReason = null,
                battleTeamSelectionPlan = null,
                message = "编组页动作派发后画面未推进（第${battleTeamActionUnconfirmedAttempts}次）：" +
                    "${pending.label}；已释放本次编组提交与队伍签名，重新规划后重试",
            )
        }
        nodeLog(
            "battle-team-action-unconfirmed kind=${pending.kind} " +
                "released=${pending.confirmedTeamIds.joinToString(",")} " +
                "attempt=${battleTeamActionUnconfirmedAttempts}",
            warning = true,
        )
    }

    /**
     * Resolve the actual EX encounter before the challenge button is allowed to fire.
     *
     * Route block ids are deliberately ignored here: EX placement is randomized and event nodes
     * can also enter an EX.  A single EX is identified from challenge-page text.  A multi-monster
     * EX requires the stable five-info-button layout, then exactly slot 3 is opened and its fixed
     * detail-name row is OCR'd.  A missing local guide never borrows another encounter's guide:
     * the automation closes its own detail modal and proceeds with the generic EX battle path.
     */
    private fun continueUnknownExAfterDetail(
        sessionId: AutomationSessionId,
        close: EntryPixelRect?,
        observedName: String?,
        timestampMillis: Long,
    ) {
        val displayName = observedName?.takeIf(String::isNotBlank) ?: "OCR无可靠文本"
        if (close == null) {
            // The modal belongs to this session, but without its own close control a generic
            // coordinate tap could affect the monster list behind it. Keep waiting for the
            // structural close anchor instead of converting an unknown guide into a blind tap.
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "$exIdentityProbeDescription 未匹配本地攻略（$displayName）；等待详情关闭按钮确认",
                )
            }
            return
        }
        if (lastPostEntryActionAt != Long.MIN_VALUE &&
            timestampMillis - lastPostEntryActionAt < POST_ENTRY_ACTION_INTERVAL_MILLIS
        ) return

        // This is deliberately narrower than a global UNKNOWN fallback. It is enabled only after
        // the session itself opened an EX monster-detail modal, so it cannot dismiss a panel the
        // user opened manually. The existing EX team planner still runs, simply without a
        // encounter-specific guide.
        exEncounterFallbackApproved = true
        lastBattleTeamRecommendationKey = null
        nodeLog("ex-guide-fallback name=$displayName action=close-detail-and-continue", warning = true)
        dispatchPostEntryTap(
            sessionId = sessionId,
            kind = LabyrinthPostEntryActionKind.EX_CLOSE_DETAIL,
            label = "EX攻略未建档：关闭魔物详情并继续挑战",
            rect = close,
            timestampMillis = timestampMillis,
        )
        if (activeSessionId == sessionId) {
            _state.value = _state.value.copy(
                combatContext = combatContext,
                message = "$exIdentityProbeDescription 未匹配本地攻略（$displayName）；已关闭详情并按通用EX流程继续",
            )
        }
    }

    private fun handleExEncounterRecognitionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        val observation = result.exEncounter
        val page = result.observation.state

        val freshChallengeVisit = labyrinthExIdentityProbeRestarts(
            lastKnownPage = exLastKnownPage,
            page = page,
            encounterResolved = currentExEncounterStrategy != null,
        )
        if (page != LabyrinthEntryPageState.UNKNOWN) exLastKnownPage = page
        if (freshChallengeVisit && combatContext?.kind == LabyrinthCombatKind.EX) {
            // Coming back from a lost battle (重新挑战) or the map: give name OCR and the detail
            // probe their full budget again instead of inheriting the previous visit's clock.
            exSlot3ProbePending = false
            exSlot3ProbeAttempts = 0
            exEncounterProbeStartedAt = timestampMillis
            Log.d("LabyrinthNode", "ex-identity-probe-restarted page=$page")
        }

        // Restarting/reinstalling the app clears the in-memory activeNodeType/combatContext while
        // the game may remain on an already-entered Boss challenge page.  The route cursor is
        // persisted as soon as node entry is confirmed, so use that semantic evidence as the
        // restart-safe fallback instead of forcing the Boss title through the normal/extreme OCR
        // gate.  Live traversal still prefers activeNodeType from the actual dispatched node tap.
        val persistedBossNode = labyrinthPersistedBossNode(validatedRoute)
        val bossRouteEvidence = activeNodeType == LabyrinthNodeTypes.BOSS ||
            (activeNodeType == null && combatContext == null && persistedBossNode != null)

        // Boss uses the same BATTLE_CHALLENGE shell, but its title is "首领战格子" rather than
        // "战斗格子（普通/极难）".  The route node type is already semantic evidence that this
        // is a Boss; recover that context here before the ordinary/EX difficulty gate runs.
        // This also heals a transient/null combatContext or a restarted session without asking
        // the Boss title to match either of the normal/extreme suffix templates.
        if (
            page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
            bossRouteEvidence &&
            combatContext?.kind != LabyrinthCombatKind.BOSS
        ) {
            activeNodeType = LabyrinthNodeTypes.BOSS
            combatContext = LabyrinthCombatContext(
                kind = LabyrinthCombatKind.BOSS,
                teamIndex = 1,
                targetCount = combatContext?.targetCount ?: 1,
            )
            currentExEncounterStrategy = null
            exEncounterFallbackApproved = false
            exSlot3ProbePending = false
            exEncounterProbeStartedAt = Long.MIN_VALUE
            lastBattleTeamRecommendationKey = null
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    combatContext = combatContext,
                    message = "已确认首领节点挑战页；Boss不参与普通/极难二分类",
                )
            }
        }

        // The game can enter EX from an event even though the route node itself is EVENT.  “极难”
        // is game-visible evidence and therefore has priority over the route-derived combat kind.
        if (page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
            observation?.extremeChallenge == true &&
            combatContext?.kind != LabyrinthCombatKind.EX
        ) {
            combatContext = LabyrinthCombatContext(
                kind = LabyrinthCombatKind.EX,
                targetCount = when {
                    observation.multiMonsterLikely -> 5
                    observation.specialDualLikely -> 2
                    else -> 1
                },
            )
            currentExEncounterStrategy = null
            exEncounterFallbackApproved = false
            exSlot3ProbePending = false
            exSlot3ProbeAttempts = 0
            exEncounterProbeStartedAt = timestampMillis
            synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.clear() }
            lastBattleTeamRecommendationKey = null
            _state.value = _state.value.copy(
                combatContext = combatContext,
                message = "挑战页识别到极难，已按EX遭遇接管；正在识别具体攻略",
            )
        }

        // EVENT arena choices can open an ordinary challenge page while the route-derived
        // combatContext is still null (the clicked map node itself is EVENT).  Once the challenge
        // title has positively resolved as non-EX, restore an explicit NORMAL context so retry,
        // team recommendation and failure policy never run with an untyped battle.
        val persistedCurrentNode = labyrinthPersistedCurrentNode(validatedRoute)
        val eventRouteEvidence = activeNodeType == LabyrinthNodeTypes.EVENT ||
            (activeNodeType == null && persistedCurrentNode?.blockType == LabyrinthNodeTypes.EVENT)
        if (
            page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
            eventRouteEvidence &&
            observation?.challengeDifficultyResolved == true &&
            observation.extremeChallenge != true &&
            combatContext == null
        ) {
            if (activeNodeType == null) {
                activeNodeType = LabyrinthNodeTypes.EVENT
                activeNodeArea = persistedCurrentNode?.area
            }
            combatContext = LabyrinthCombatContext(LabyrinthCombatKind.NORMAL)
            lastBattleTeamRecommendationKey = null
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    combatContext = combatContext,
                    message = "事件进入普通战挑战页，已恢复普通战上下文",
                )
            }
        }

        // Challenge-title OCR is asynchronous. In particular, an EVENT may enter an EX while the
        // route-derived combat context is still null/non-EX. The first recognized challenge frame
        // must therefore wait for a positive "战斗格子" title read before a normal challenge is
        // allowed. Otherwise the generic challenge branch can win the race one frame before
        // "极难" arrives.
        if (page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
            combatContext?.kind != LabyrinthCombatKind.BOSS &&
            combatContext?.kind != LabyrinthCombatKind.EX &&
            observation?.challengeDifficultyResolved != true
        ) {
            if (exEncounterProbeStartedAt == Long.MIN_VALUE) exEncounterProbeStartedAt = timestampMillis
            if (timestampMillis - exEncounterProbeStartedAt >= EX_ENCOUNTER_SINGLE_NAME_TIMEOUT_MILLIS) {
                finishFromPlanner(
                    sessionId,
                    "挑战页标题在限定时间内未可靠确认普通/极难；不执行盲目挑战",
                )
            } else if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "挑战页：正在确认普通/极难，确认前禁止点击挑战",
                )
            }
            return true
        }

        // A monster-detail modal is action-owned only when this session opened slot 3.  Manually
        // opened details remain read-only, which prevents a generic UNKNOWN fallback from closing
        // a panel the user is inspecting.
        if (observation?.monsterDetailOpen == true) {
            if (!exSlot3ProbePending) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "检测到魔物详情；非程序发起的EX识别，保持人工控制",
                    )
                }
                return true
            }
            if (!observation.trusted || observation.encounterId == null) {
                if (exEncounterProbeStartedAt != Long.MIN_VALUE &&
                    timestampMillis - exEncounterProbeStartedAt >= EX_ENCOUNTER_DETAIL_TIMEOUT_MILLIS
                ) {
                    continueUnknownExAfterDetail(
                        sessionId = sessionId,
                        close = observation.closeButtonRect,
                        observedName = observation.monsterDetailText,
                        timestampMillis = timestampMillis,
                    )
                } else if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "正在识别$exIdentityProbeDescription：${observation.monsterDetailText ?: "等待两次独立OCR"}",
                    )
                }
                return true
            }
            val strategy = LabyrinthExEncounterCatalog.all.firstOrNull { it.id == observation.encounterId }
            if (strategy == null || strategy.identityName.startsWith("？？？")) {
                continueUnknownExAfterDetail(
                    sessionId = sessionId,
                    close = observation.closeButtonRect,
                    observedName = observation.encounterName ?: observation.encounterId,
                    timestampMillis = timestampMillis,
                )
                return true
            }
            currentExEncounterStrategy = strategy
            combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.EX)).copy(
                kind = LabyrinthCombatKind.EX,
                targetCount = strategy.targetCount,
            )
            lastBattleTeamRecommendationKey = null
            val close = observation.closeButtonRect
            if (close == null) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        combatContext = combatContext,
                        message = "已识别EX：${strategy.identityName}，但详情关闭按钮结构未确认",
                    )
                }
                return true
            }
            if (lastPostEntryActionAt != Long.MIN_VALUE &&
                timestampMillis - lastPostEntryActionAt < POST_ENTRY_ACTION_INTERVAL_MILLIS
            ) return true
            dispatchPostEntryTap(
                sessionId = sessionId,
                kind = LabyrinthPostEntryActionKind.EX_CLOSE_DETAIL,
                label = "EX识别完成：关闭魔物详情",
                rect = close,
                timestampMillis = timestampMillis,
            )
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    combatContext = combatContext,
                    message = "已识别EX：${strategy.identityName}；关闭详情后按攻略编组",
                )
            }
            return true
        }

        if (page != LabyrinthEntryPageState.BATTLE_CHALLENGE) return false
        val isEx = combatContext?.kind == LabyrinthCombatKind.EX || observation?.extremeChallenge == true
        if (!isEx) return false

        // Single-target EX can be identified directly from the stable challenge-page text.
        if (currentExEncounterStrategy == null && observation?.trusted == true && observation.encounterId != null) {
            LabyrinthExEncounterCatalog.all.firstOrNull { it.id == observation.encounterId }?.let { strategy ->
                currentExEncounterStrategy = strategy
                combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.EX)).copy(
                    kind = LabyrinthCombatKind.EX,
                    targetCount = strategy.targetCount,
                )
                lastBattleTeamRecommendationKey = null
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        combatContext = combatContext,
                        message = "已识别EX：${strategy.identityName}；准备按攻略进入编组",
                    )
                }
            }
        }
        // A guide-less EX was confirmed from an automation-owned details modal and deliberately
        // dismissed.  Do not reopen the modal or wait for a strategy that cannot arrive; allow
        // the existing generic EX team-selection path to take over instead.
        if (currentExEncounterStrategy != null || exEncounterFallbackApproved) return false

        if (exEncounterProbeStartedAt == Long.MIN_VALUE) exEncounterProbeStartedAt = timestampMillis
        if (exSlot3ProbePending) {
            if (timestampMillis - exEncounterProbeStartedAt >= EX_ENCOUNTER_OPEN_DETAIL_TIMEOUT_MILLIS) {
                if (exSlot3ProbeAttempts >= MAX_EX_SLOT3_PROBE_ATTEMPTS) {
                    finishFromPlanner(sessionId, "已尝试打开$exIdentityProbeDescription 详情${exSlot3ProbeAttempts}次仍未出现详情页，停止EX自动挑战")
                } else {
                    exSlot3ProbePending = false
                    exEncounterProbeStartedAt = timestampMillis
                    if (activeSessionId == sessionId) {
                        _state.value = _state.value.copy(message = "$exIdentityProbeDescription 详情未出现，准备有限重试")
                    }
                }
            }
            return true
        }

        val structuredProbe = when {
            observation?.multiMonsterLikely == true ->
                "第3只魔物" to observation.slot3InfoButtonRect
            observation?.specialDualLikely == true ->
                "特殊EX右侧本体" to observation.specialDualIdentityInfoButtonRect
            else -> null
        }
        if (structuredProbe != null) {
            val (probeDescription, rect) = structuredProbe
            exIdentityProbeDescription = probeDescription
            if (rect == null) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "已确认EX布局，但$probeDescription 信息按钮未达到安全识别线")
                }
                return true
            }
            if (exSlot3ProbeAttempts >= MAX_EX_SLOT3_PROBE_ATTEMPTS) {
                finishFromPlanner(sessionId, "$probeDescription 详情探测达到上限，未识别具体EX；不发起挑战")
                return true
            }
            if (postEntryStableFrames < POST_ENTRY_STABLE_FRAMES) return true
            exSlot3ProbeAttempts++
            // Set pending before dispatch so a very fast next capture cannot be treated as a
            // manually opened modal while the executor callback is still finishing.
            exSlot3ProbePending = true
            exEncounterProbeStartedAt = timestampMillis
            dispatchPostEntryTap(
                sessionId = sessionId,
                kind = LabyrinthPostEntryActionKind.EX_PROBE_DETAIL,
                label = "识别EX：查看$probeDescription 详情",
                rect = rect,
                timestampMillis = timestampMillis,
            )
            return true
        }

        // Give direct challenge-page OCR a short chance, then fall back to the fixed Details
        // button for a single-target EX.  Opening Details is non-destructive and lets the existing
        // detail-modal resolver read the name from a much cleaner row.  This also covers future
        // single EX names whose challenge-page text shifts slightly between client revisions.
        val singleDetailRect = labyrinthSingleExDetailProbeRect(
            pageState = page,
            isEx = isEx,
            encounterResolved = currentExEncounterStrategy != null,
            multiMonsterLikely = observation?.multiMonsterLikely == true,
            specialDualLikely = observation?.specialDualLikely == true,
            elapsedMillis = timestampMillis - exEncounterProbeStartedAt,
            stableFrames = postEntryStableFrames,
            attempts = exSlot3ProbeAttempts,
            frameWidth = result.frameWidth,
            frameHeight = result.frameHeight,
            maxAttempts = MAX_EX_SLOT3_PROBE_ATTEMPTS,
        )
        if (singleDetailRect != null) {
            exIdentityProbeDescription = "单体魔物"
            exSlot3ProbeAttempts++
            exSlot3ProbePending = true
            exEncounterProbeStartedAt = timestampMillis
            dispatchPostEntryTap(
                sessionId = sessionId,
                kind = LabyrinthPostEntryActionKind.EX_PROBE_DETAIL,
                label = "识别EX：查看单体魔物详情",
                rect = singleDetailRect,
                timestampMillis = timestampMillis,
            )
            return true
        }

        // If neither direct OCR nor the bounded detail probe can establish identity, retain the
        // old safe stop rather than challenging an unknown EX.
        if (timestampMillis - exEncounterProbeStartedAt >= EX_ENCOUNTER_SINGLE_NAME_TIMEOUT_MILLIS) {
            finishFromPlanner(
                sessionId,
                "已确认EX挑战页，但单体名称OCR/详情识别均未建立可信身份；不发起盲目挑战",
            )
        } else if (activeSessionId == sessionId) {
            _state.value = _state.value.copy(
                message = "EX挑战页：等待单体名称OCR；失败后将安全打开详情识别",
            )
        }
        return true
    }

    /**
     * EVENT-only full-roster selector.  This deliberately does not reuse the opening roster's
     * fixed guild policy or 3/3 counter.  The current viewport is ranked with the same role model
     * used by three-choice rewards, then the page's 1-person invite button is confirmed.
     */
    private fun handleEventFreeRoleSelection(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ) {
        if (activeSessionId != sessionId || actionInFlight.get()) return
        val viewport = result.openingCharacterSelection ?: run {
            noteEventFreeRoleBlocked(sessionId, timestampMillis, "事件自由选角：等待角色视口生成")
            return
        }
        if (viewport.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE) {
            // One unrecognizable portrait among a full roster used to hold the page for ever.
            noteEventFreeRoleBlocked(sessionId, timestampMillis, "事件自由选角：等待当前视口角色稳定识别")
            return
        }

        // The roster, not our own dispatch log, decides who is selected: a tap the game ignored
        // must not pin the page on the confirm branch for ever.
        val visibleIds = viewport.visibleCharacters
            .mapNotNull { it.characterId?.takeIf(String::isNotBlank) }
        val visibleSelectedIds = viewport.visibleCharacters
            .filter { it.selected && it.trusted }
            .mapNotNull { it.characterId?.takeIf(String::isNotBlank) }
        val selectedIds = labyrinthEventFreeRoleReconcileSelection(
            rememberedIds = eventFreeRoleSelectedCharacterIds,
            visibleSelectedIds = visibleSelectedIds,
            visibleIds = visibleIds,
        )
        eventFreeRoleSelectedCharacterIds.clear()
        eventFreeRoleSelectedCharacterIds += selectedIds
        if (selectedIds.size > eventFreeRoleObservedSelectedCount) {
            // Observed progress is the only thing allowed to restart the give-up clock.
            eventFreeRoleObservedSelectedCount = selectedIds.size
            resetEventFreeRoleProgress()
        }

        val scores = result.observation.anchorScores
        val inviteState = labyrinthInviteButtonState(
            enabledScore = maxOf(
                scores[EntryAnchorId.INVITE_ENABLED],
                scores[EntryAnchorId.INVITE_ENABLED_STANDARD],
            ),
            disabledScore = maxOf(
                scores[EntryAnchorId.INVITE_DISABLED],
                scores[EntryAnchorId.INVITE_DISABLED_STANDARD],
            ),
        )
        val inviteRect = anchorRect(result, EntryAnchorId.INVITE_ENABLED)
            ?: anchorRect(result, EntryAnchorId.INVITE_ENABLED_STANDARD)
        if (
            selectedIds.isNotEmpty() &&
            inviteRect != null &&
            inviteState != LabyrinthInviteButtonState.DISABLED
        ) {
            if (labyrinthPostEntryCooldownRemaining(
                    frameTimestampMillis = timestampMillis,
                    dispatchedAtMillis = lastPostEntryActionAt,
                    landedAtMillis = lastPostEntryActionLandedAt,
                    intervalMillis = POST_ENTRY_ACTION_INTERVAL_MILLIS,
                ) > 0L
            ) return
            if (eventFreeRoleBlockedSince == Long.MIN_VALUE) eventFreeRoleBlockedSince = timestampMillis
            if (
                labyrinthEventFreeRoleBlockedAction(timestampMillis - eventFreeRoleBlockedSince) ==
                LabyrinthEventFreeRoleBlockedAction.STOP
            ) {
                finishFromPlanner(
                    sessionId,
                    "事件自由选角无法继续：已选择 ${selectedIds.size} 名角色但“去邀请”始终未生效",
                )
                return
            }
            dispatchPostEntryTap(
                sessionId = sessionId,
                kind = LabyrinthPostEntryActionKind.EVENT_FREE_ROLE_CONFIRM,
                label = "确认事件自由选角（${selectedIds.size}名）",
                rect = inviteRect,
                timestampMillis = timestampMillis,
                eventFreeRoleCandidateId = selectedIds.first(),
                eventFreeRoleConfirm = true,
                eventFreeRoleConfirmIds = selectedIds,
            )
            return
        }
        if (selectedIds.isNotEmpty()) {
            val needsAnother = labyrinthEventFreeRoleNeedsAnotherPick(
                selectedCount = selectedIds.size,
                inviteEnabled = inviteState == LabyrinthInviteButtonState.ENABLED,
                inviteDisabled = inviteState == LabyrinthInviteButtonState.DISABLED,
                millisSinceLastSelect = timestampMillis - eventFreeRoleLastSelectAt,
            )
            if (!needsAnother) {
                // Even "the button is not ready yet" is a wait, so it runs on the give-up clock.
                noteEventFreeRoleBlocked(
                    sessionId,
                    timestampMillis,
                    "事件自由选角：已选择 ${selectedIds.size} 名角色，等待“去邀请”按钮就绪",
                )
                return
            }
            // 去邀请 stays disabled after the pick settled: this event wants more than one.
        }

        val planner = roleRewardChoicePlanner ?: run {
            _state.value = _state.value.copy(
                message = roleRewardChoiceUnavailableReason ?: "事件自由选角：角色评分资料未加载",
            )
            return
        }
        val defenseStacks = relicChoicePolicy.markStacks(
            _state.value.observedRelics,
            relicMarkStackCalibration,
        ).getValue(LabyrinthRelicMark.DEFENSE)
        val pendingSelectedIds = synchronized(pendingAcquiredCharacterIds) {
            pendingAcquiredCharacterIds.toSet()
        }
        val acquiredIds = _state.value.joinedCharacters
            .map(LabyrinthJoinedCharacter::characterId)
            .plus(pendingSelectedIds)
            .plus(selectedIds)
            .toSet()
        val decision = planner.decideFreeVisibleRole(
            candidates = viewport.visibleCharacters,
            acquiredCharacterIds = acquiredIds,
            context = LabyrinthRoleDecisionContext(defenseMarkStacks = defenseStacks),
        )
        when (decision) {
            is LabyrinthRoleRewardChoiceDecision.Wait -> {
                // Nothing usable *here*: with a 32-role party and a roster several screens long,
                // the first viewport can legitimately hold only already-owned characters.
                noteEventFreeRoleBlocked(sessionId, timestampMillis, decision.reason)
            }

            is LabyrinthRoleRewardChoiceDecision.Select -> {
                if (!decision.actionSafe) {
                    noteEventFreeRoleBlocked(
                        sessionId,
                        timestampMillis,
                        decision.safetyNote ?: "事件自由选角：推荐尚未达到自动点击安全条件",
                    )
                    return
                }
                // Session recovery clears the action timestamp to Long.MIN_VALUE. Subtracting
                // that sentinel overflows and would hold every stable picker frame indefinitely.
                if (labyrinthPostEntryCooldownRemaining(
                        frameTimestampMillis = timestampMillis,
                        dispatchedAtMillis = lastPostEntryActionAt,
                        landedAtMillis = lastPostEntryActionLandedAt,
                        intervalMillis = POST_ENTRY_ACTION_INTERVAL_MILLIS,
                    ) > 0L
                ) return
                _state.value = _state.value.copy(
                    message = "事件自由选角建议（第${selectedIds.size + 1}名）：${decision.displayName}；${decision.explanation.take(2).joinToString("；")}",
                )
                dispatchPostEntryTap(
                    sessionId = sessionId,
                    kind = LabyrinthPostEntryActionKind.EVENT_FREE_ROLE_SELECT,
                    label = "事件自由选角：${decision.displayName}",
                    rect = decision.buttonRect,
                    timestampMillis = timestampMillis,
                    eventFreeRoleCandidateId = decision.characterId,
                )
            }
        }
    }

    private fun resetEventFreeRoleProgress() {
        eventFreeRoleBlockedSince = Long.MIN_VALUE
    }

    /**
     * Claim the single-action latch, remembering when it was claimed.
     *
     * Only one gesture may be in flight at a time, and every frame handler bails out while the
     * latch is held. That makes the latch the one piece of state that can freeze the entire
     * session on its own: if it is ever claimed and not released, no page handler runs again, no
     * message is published, and the run sits on the generic per-frame status for ever with a
     * frozen action count -- exactly the 408 s freeze in 2026-09-22 bundle 173753.
     *
     * Release therefore belongs in a `finally` ([launchGuardedAction]) rather than at the end of
     * a long coroutine body, and a latch held implausibly long is treated as a fault by
     * [labyrinthActionLatchStuck] instead of being waited on.
     */
    private fun beginGuardedAction(): Boolean {
        if (!actionInFlight.compareAndSet(false, true)) return false
        actionInFlightSince = clock()
        return true
    }

    private fun endGuardedAction() {
        actionInFlightSince = Long.MIN_VALUE
        actionInFlight.set(false)
    }

    /** Runs a dispatched action, releasing the latch however the body ends. */
    private fun launchGuardedAction(block: suspend () -> Unit) {
        actionScope.launch {
            try {
                block()
            } finally {
                endGuardedAction()
            }
        }
    }

    private fun resetPostEntryStall() {
        postEntryStallSince = Long.MIN_VALUE
        postEntryStallMessage = null
    }

    /**
     * The free-role page has nothing it can act on right now.
     *
     * Every branch that reports this used to just publish a message and return, which is an
     * unbounded wait on a page with no other timeout: an unreadable portrait, a viewport holding
     * nothing but already-owned characters, or a recommendation that never clears the safety bar
     * would each park the run there silently. A short wait is still right (the list animates, OCR
     * catches up); after that, look further down the roster; and once the roster has been walked
     * without a usable candidate, stop with the reason instead of holding a dead session open.
     */
    private fun noteEventFreeRoleBlocked(
        sessionId: AutomationSessionId,
        timestampMillis: Long,
        reason: String,
    ) {
        if (eventFreeRoleBlockedSince == Long.MIN_VALUE) eventFreeRoleBlockedSince = timestampMillis
        when (labyrinthEventFreeRoleBlockedAction(timestampMillis - eventFreeRoleBlockedSince)) {
            LabyrinthEventFreeRoleBlockedAction.WAIT ->
                if (_state.value.message != reason) _state.value = _state.value.copy(message = reason)

            LabyrinthEventFreeRoleBlockedAction.STOP ->
                finishFromPlanner(sessionId, "事件自由选角无法继续：$reason")
        }
    }

    /** Dispatch recognized route pages and bounded reward/settlement animation recovery. */
    private fun handlePostEntryPage(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        val pageState = result.observation.state
        val previousPostEntryState = lastPostEntryState
        val roleRewardPage = postEntryIsRoleRewardPage(result)
        val roleRewardSelectionSignature = if (roleRewardPage) {
            labyrinthRoleRewardSelectionSignature(result)
        } else {
            null
        }
        if (lastPostEntryState != pageState) {
            lastPostEntryState = pageState
            postEntryStableFrames = 0
            postEntryAttempts = 0
            resetPostEntryStall()
            resetEventChoiceCommit()
            if (pageState == LabyrinthEntryPageState.RELIC_CHOICE) {
                pendingRelicSelection = null
                relicChoiceCommitted = false
            }
            if (pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
                resetBattleTeamExecutionTracking()
            }
        }
        if (roleRewardPage &&
            roleRewardSelectionSignature != null &&
            roleRewardSelectionSignature != lastObservedRoleRewardSelectionSignature
        ) {
            // A new choice round can reuse the exact same page enum and geometry. Treat the
            // candidate-set change as a page transition for stability/attempt accounting.
            lastObservedRoleRewardSelectionSignature = roleRewardSelectionSignature
            postEntryStableFrames = 0
            postEntryAttempts = 0
        }
        postEntryStableFrames++

        if (handleExEncounterRecognitionFrame(sessionId, result, timestampMillis)) return

        if (roleRewardPage) {
            if (!roleRewardBatchActive) roleRewardJoinedSequenceStarted = false
            roleRewardBatchActive = true
            // Choice rounds come first. Any generic reward fallback inherited from the Boss/
            // event result must stop here so an inter-choice UNKNOWN transition is never tapped.
            if (!roleRewardJoinedSequenceStarted && characterAcquisitionActive) {
                clearCharacterAcquisitionContext()
            }
            // The page enum can remain INITIAL_CHARACTER_SELECTION across several reward rounds.
            // Only the candidate-set signature tells us whether this exact round was already
            // clicked. A new candidate set immediately re-arms automatic selection.
            roleRewardChoiceCommitted = roleRewardSelectionSignature != null &&
                roleRewardSelectionSignature == committedRoleRewardSelectionSignature
        }

        // The role-card selection is human input, but the following portrait/召唤 animation is
        // not. Some event/link/reward paths skip a separately recognizable role-selection page
        // and expose only UNKNOWN frames, so arm the bounded safe-area fallback from the last
        // known reward page instead of waiting forever for a role-selection transition.
        if (!characterAcquisitionActive &&
            !(roleRewardBatchActive && !roleRewardJoinedSequenceStarted) &&
            shouldArmCharacterAcquisitionFallback(
                previousPage = previousPostEntryState,
                currentPage = pageState,
                activeNodeType = activeNodeType,
            )
        ) {
            characterAcquisitionActive = true
            waitingForManualRoleSelection = false
            characterAcquisitionClicks = 0
            characterAcquisitionStartedAt = timestampMillis
            lastCharacterAcquisitionActionAt = Long.MIN_VALUE
            postEntryAttempts = 0
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "已进入角色/奖励动画兜底，连续点击安全区域推进")
            }
        }

        // A multi-role settlement is one batch: several choice pages can be followed by several
        // CHARACTER_JOINED popups. Do not clear the acquisition driver between those pages.
        val keepsRoleRewardBatch = labyrinthKeepsRoleRewardBatchOnPage(pageState, roleRewardPage)
        val keepsAcquisitionPage = pageState in setOf(
            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.EVENT_ANIMATION,
            LabyrinthEntryPageState.CHARACTER_JOINED,
            LabyrinthEntryPageState.ITEM_REWARD,
        ) || (roleRewardBatchActive && roleRewardPage)
        if (characterAcquisitionActive && !keepsAcquisitionPage) {
            clearCharacterAcquisitionContext()
        }
        if (roleRewardBatchActive && !keepsRoleRewardBatch) {
            clearRoleRewardBatchTracking()
        }

        if (pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION &&
            previousPostEntryState == LabyrinthEntryPageState.BATTLE_RESULT &&
            result.battleTeamSelection?.bossTeamIndex == null &&
            combatContext?.kind == LabyrinthCombatKind.BOSS
        ) {
            combatContext = combatContext?.copy(
                teamIndex = (combatContext?.teamIndex ?: 1) + 1,
            )
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    combatContext = combatContext,
                    message = "Boss第${combatContext?.teamIndex}队编组：正在生成安全编组计划",
                )
            }
        }
        if (pageState == LabyrinthEntryPageState.UNKNOWN) {
            if (postEntryUnknownSince == Long.MIN_VALUE) postEntryUnknownSince = timestampMillis
            if (!characterAcquisitionActive && postBossStage == LabyrinthPostBossStage.NONE &&
                timestampMillis - postEntryUnknownSince > UNKNOWN_POST_ENTRY_TIMEOUT_MILLIS
            ) {
                finishFromPlanner(sessionId, "路线阶段未知页面超过限定时间，已停止并保留诊断状态")
                return
            }
        } else {
            postEntryUnknownSince = Long.MIN_VALUE
        }
        if (pageState == LabyrinthEntryPageState.RUN_CLEAR_RESULT) {
            val next = labyrinthPostBossStageOnFinalResult(postBossStage)
            if (next != postBossStage) {
                nodeLog("post-boss-stage ${postBossStage.name} -> ${next.name} on RUN_CLEAR_RESULT")
                postBossStage = next
                postBossStartedAt = timestampMillis
                postBossUnknownAttempts = 0
                // postEntryAttempts is deliberately kept: a CHEST_SEQUENCE -> SCORE_RESULT return
                // means 关闭 was already tapped once and the per-page budget bounds the re-taps.
            }
        }
        if (postBossStage == LabyrinthPostBossStage.CHEST_SEQUENCE &&
            pageState == LabyrinthEntryPageState.ITEM_REWARD
        ) {
            postBossStage = LabyrinthPostBossStage.FINAL_ITEM_REWARD
        }
        if (postBossStage == LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME &&
            pageState == LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE
        ) {
            if (postEntryStableFrames < POST_ENTRY_STABLE_FRAMES) return
            markRunCleared(sessionId)
            return
        }
        if (postBossStage == LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME &&
            postBossStartedAt != Long.MIN_VALUE &&
            timestampMillis - postBossStartedAt > FINAL_HOME_RETURN_TIMEOUT_MILLIS
        ) {
            finishFromPlanner(sessionId, "最终道具已关闭，但未在限定时间内返回黎明界主页")
            return
        }

        // Later role rewards are human card selections. The common "选择" template is visible
        // before the user chooses anything, so it must never be used as an automatic tap target.
        // We only arm the automatic animation driver after the page itself changes, which proves
        // that the user has completed the selection.
        if (roleRewardPage) {
            waitingForManualRoleSelection = true
            if (postEntryStableFrames == POST_ENTRY_STABLE_FRAMES && activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "角色奖励批次：继续完成当前三选一；全部选完后统一处理角色加入弹窗")
            }
        } else if (waitingForManualRoleSelection &&
            pageState != LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION
        ) {
            waitingForManualRoleSelection = false
            if (roleRewardBatchActive && activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "角色奖励批次选择过渡中；不盲点，等待下一次三选一或首个角色加入弹窗",
                )
            }
        }

        if (pageState == LabyrinthEntryPageState.CHARACTER_JOINED && roleRewardBatchActive) {
            roleRewardJoinedSequenceStarted = true
            waitingForManualRoleSelection = false
            if (!characterAcquisitionActive) {
                characterAcquisitionActive = true
                characterAcquisitionClicks = 0
                characterAcquisitionStartedAt = timestampMillis
                lastCharacterAcquisitionActionAt = Long.MIN_VALUE
                postEntryAttempts = 0
            }
        }

        if (postEntryStableFrames < POST_ENTRY_STABLE_FRAMES && !roleRewardPage &&
            !(pageState == LabyrinthEntryPageState.CHARACTER_JOINED && skipRoleRewardJoinedRecognition)
        ) {
            traceReject("stable-frames:$postEntryStableFrames/$POST_ENTRY_STABLE_FRAMES")
            return
        }
        if (pageState == LabyrinthEntryPageState.BATTLE_FAILED) {
            val context = combatContext
            if (context == null) {
                finishFromPlanner(sessionId, "已识别战斗失败页，但缺少本次战斗上下文；未自动点击结束或重新挑战")
                return
            }
            // The two-button layout's lower-right button reads "下一步": it advances to the
            // "遗物效果结果" popup instead of retrying, so the three stop conditions below — which
            // exist for a run that keeps losing the same fight — must not apply to it. They would
            // otherwise reroll or end the run that was just revived. The three-button page and the
            // defensive path for a missing observation keep their exact previous behaviour.
            val advanceOnly = result.battleFailure?.layout == LabyrinthBattleFailureLayout.TWO_BUTTON
            if (!advanceOnly) {
                if (!pendingSingleBossFallbackToMulti) {
                    pendingSingleBossFallbackToMulti = labyrinthShouldSwitchSingleBossToMulti(
                        enabled = singleBossFallbackToMultiAfterThreeFailures,
                        kind = context.kind,
                        mode = bossTeamMode,
                        battleRetryCount = battleRetryCount,
                        retryCountBeforeMulti = singleBossRetryCountBeforeMulti,
                    )
                }
                val singleBossFallbackOwnsFailurePolicy =
                    context.kind == LabyrinthCombatKind.BOSS &&
                        bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM &&
                        singleBossFallbackToMultiAfterThreeFailures
                if (
                    !_state.value.dryRun &&
                    !pendingSingleBossFallbackToMulti &&
                    !singleBossFallbackOwnsFailurePolicy &&
                    labyrinthShouldRerollAfterBattleFailure(
                        rerollAfterThreeFailures = rerollAfterThreeBattleFailures,
                        battleRetryCount = battleRetryCount,
                    )
                ) {
                    finishFromPlannerAndRequestReroll(
                        sessionId,
                        "${labyrinthBattleKindLabel(context.kind)}连续3次挑战失败，不结算当前战斗",
                    )
                    return
                }
                val retryLimit = labyrinthEffectiveBattleRetryLimit(
                    kind = context.kind,
                    rerollAfterThreeFailures = rerollAfterThreeBattleFailures,
                    singleBossFallbackRetryCount = singleBossRetryCountBeforeMulti.takeIf {
                        singleBossFallbackToMultiAfterThreeFailures &&
                            bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM
                    },
                )
                if (
                    !_state.value.dryRun &&
                    !pendingSingleBossFallbackToMulti &&
                    labyrinthBatchOwnedRunEndsAtRetryLimit(
                        batchOwnsRun = currentRunId != null,
                        battleRetryCount = battleRetryCount,
                        retryLimit = retryLimit,
                    )
                ) {
                    // The batch rerolls and invalidates the failure page itself; the run just ends
                    // with FAILED_MAX_RETRY and leaves capture alive for the next run.
                    finishFromPlannerAndRequestReroll(
                        sessionId,
                        "${labyrinthBattleKindLabel(context.kind)}已达到自动重试上限：" +
                            "已重试${battleRetryCount}次；本局按失败计入批次",
                    )
                    return
                }
                if (!pendingSingleBossFallbackToMulti && battleRetryCount >= retryLimit) {
                    finishFromPlanner(
                        sessionId,
                        "${labyrinthBattleKindLabel(context.kind)}已达到自动重试上限：" +
                            "已重试${battleRetryCount}次；保留在失败页，不自动点击结束",
                    )
                    return
                }
                if (result.battleFailure == null) {
                    finishFromPlanner(sessionId, "战斗失败页按钮结构未达到安全线；不执行固定坐标重试")
                    return
                }
            }
        }
        if (pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            handleBattleTeamSelectionExecution(
                sessionId = sessionId,
                result = result,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = timestampMillis,
            )
            return
        }
        val postEntryActionInterval = if (characterAcquisitionActive) {
            CHARACTER_ACQUISITION_ACTION_INTERVAL_MILLIS
        } else {
            POST_ENTRY_ACTION_INTERVAL_MILLIS
        }
        val cooldownRemaining = labyrinthPostEntryCooldownRemaining(
            frameTimestampMillis = timestampMillis,
            dispatchedAtMillis = lastPostEntryActionAt,
            landedAtMillis = lastPostEntryActionLandedAt,
            intervalMillis = postEntryActionInterval,
        )
        if (cooldownRemaining > 0L) {
            traceReject("cooldown:${postEntryActionInterval - cooldownRemaining}/$postEntryActionInterval")
            return
        }

        val finalAnimationPage = pageState == LabyrinthEntryPageState.UNKNOWN ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT
        val finalAnimationActive = postBossStage == LabyrinthPostBossStage.BEFORE_SCORE ||
            postBossStage == LabyrinthPostBossStage.CHEST_SEQUENCE
        val maxAttempts = when {
            characterAcquisitionActive -> MAX_CHARACTER_ACQUISITION_CLICKS
            finalAnimationActive && finalAnimationPage -> MAX_POST_BOSS_UNKNOWN_ATTEMPTS
            activeNodeType == LabyrinthNodeTypes.EVENT -> MAX_EVENT_ACTIONS
            else -> MAX_POST_ENTRY_ATTEMPTS
        }
        if (characterAcquisitionActive &&
            characterAcquisitionStartedAt != Long.MIN_VALUE &&
            timestampMillis - characterAcquisitionStartedAt > CHARACTER_ACQUISITION_TIMEOUT_MILLIS
        ) {
            finishFromPlanner(sessionId, "角色获得流程超过限定时间，已停止并保留诊断状态")
            return
        }
        if (postEntryAttempts >= maxAttempts) {
            val reason = if (characterAcquisitionActive) {
                "角色获得动画推进达到上限，已停止并保留诊断状态"
            } else if (finalAnimationActive && finalAnimationPage) {
                "最终结算动画推进达到上限，未找到下一结算页面"
            } else {
                "${pageState.name} 页面重复点击达到上限，已暂停等待人工检查"
            }
            finishFromPlanner(sessionId, reason)
            return
        }

        var roleRewardWaitReason: String? = null
        val roleRewardPlan = if (
            pageState == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
            postEntryIsRoleRewardPage(result) &&
            !roleRewardChoiceCommitted
        ) {
            when (val decision = _state.value.roleRewardChoiceDecision) {
                is LabyrinthRoleRewardChoiceDecision.Select -> {
                    if (decision.actionSafe) {
                        LabyrinthPostEntryTapPlan(
                            LabyrinthPostEntryActionKind.SELECT_ROLE_REWARD,
                            "选择角色：${decision.displayName}；${decision.explanation.joinToString("；")}",
                            decision.buttonRect,
                        )
                    } else {
                        roleRewardWaitReason = decision.safetyNote ?: "当前角色推荐仅供参考，不满足自动点击安全条件"
                        null
                    }
                }
                is LabyrinthRoleRewardChoiceDecision.Wait -> {
                    roleRewardWaitReason = decision.reason
                    null
                }
                null -> {
                    roleRewardWaitReason = "角色三选一决策尚未生成"
                    null
                }
            }
        } else {
            null
        }
        val manualInputMessage = if (
            pageState == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
            postEntryIsRoleRewardPage(result) &&
            roleRewardChoicePlanner == null
        ) {
            listOfNotNull(
                "角色三选一暂由人工完成",
                roleRewardChoiceUnavailableReason,
            ).joinToString("；")
        } else if (pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            labyrinthBattleTeamSelectionMessage(
                combatContext = combatContext,
                recommendation = _state.value.battleTeamRecommendation,
                unavailableReason = _state.value.battleTeamRecommendationUnavailableReason,
                selectionPlan = _state.value.battleTeamSelectionPlan,
            )
        } else {
            labyrinthManualInputMessage(pageState)
        }
        var shopWaitReason: String? = null
        // Page ownership is the hard action boundary. Workflow flags may refine the action
        // *inside* the current page, but they can never make one page execute another page's
        // proposal. This is intentionally page-first rather than flow-first.
        val genericConfirmRect = labyrinthGenericConfirmDialogRect(result)
        val plan: LabyrinthPostEntryTapPlan? = if (genericConfirmRect != null) {
            // A modal 确认 owns the frame whatever the map behind it classifies as.
            LabyrinthPostEntryTapPlan(
                LabyrinthPostEntryActionKind.CONFIRM_GENERIC_DIALOG,
                "确认提示弹窗",
                genericConfirmRect,
            )
        } else when (pageState) {
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> when {
                roleRewardPlan != null -> roleRewardPlan
                roleRewardWaitReason != null || manualInputMessage != null -> null
                else -> null
            }

            LabyrinthEntryPageState.CHARACTER_JOINED ->
                LabyrinthPostEntryTapPlan(
                    LabyrinthPostEntryActionKind.CLOSE_CHARACTER_JOINED,
                    "关闭角色加入结果",
                    anchorRect(result, EntryAnchorId.JOINED_CLOSE)
                        ?: anchorRect(result, EntryAnchorId.JOINED_CLOSE_STANDARD)
                        ?: LabyrinthFallbackTap.MODAL_CLOSE.rect(frameWidth, frameHeight),
                )

            LabyrinthEntryPageState.ITEM_REWARD -> when {
                postBossStage == LabyrinthPostBossStage.FINAL_ITEM_REWARD ->
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.CLOSE_FINAL_ITEM,
                        "关闭最终获得道具",
                        anchorRect(result, EntryAnchorId.ITEM_REWARD_CLOSE)
                            ?: LabyrinthFallbackTap.MODAL_CLOSE.rect(frameWidth, frameHeight),
                    )
                else ->
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.CLOSE_ITEM_REWARD,
                        "关闭获得道具界面",
                        // 遗物效果结果 is a short centred dialog whose 关闭 sits ~190 reference
                        // pixels above the full-height one, so the generic MODAL_CLOSE fallback
                        // would miss it entirely. Its own anchor is tried first and only ever
                        // matches while that dialog is the one on screen.
                        anchorRect(result, EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE)
                            ?: anchorRect(result, EntryAnchorId.ITEM_REWARD_CLOSE)
                            ?: LabyrinthFallbackTap.MODAL_CLOSE.rect(frameWidth, frameHeight),
                    )
            }

            LabyrinthEntryPageState.UNKNOWN -> when {
                labyrinthHasBattleControls(result) -> null
                labyrinthBossSettlementNextButtonRect(
                    pageState = pageState,
                    combatContext = combatContext,
                    nextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON],
                    bossSummaryNextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON],
                ) != null ->
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.BOSS_SETTLEMENT_NEXT,
                        "Boss结算：下一步",
                        requireNotNull(
                            labyrinthBossSettlementNextButtonRect(
                                pageState = pageState,
                                combatContext = combatContext,
                                nextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON],
                                bossSummaryNextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON],
                            ),
                        ),
                    )
                finalAnimationActive && finalAnimationPage ->
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.ADVANCE_FINAL_SETTLEMENT,
                        "推进最终结算动画",
                        finalSettlementFallbackRect(
                            result = result,
                            pageState = pageState,
                            frameWidth = frameWidth,
                            frameHeight = frameHeight,
                        ),
                    )
                else -> null
            }

            LabyrinthEntryPageState.EVENT_ANIMATION -> when {
                labyrinthHasBattleControls(result) -> null
                characterAcquisitionActive ->
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.ADVANCE_CHARACTER_ACQUISITION,
                        "推进角色获得动画",
                        LabyrinthFallbackTap.CENTER.rect(frameWidth, frameHeight),
                    )
                else -> LabyrinthPostEntryTapPlan(
                    LabyrinthPostEntryActionKind.ADVANCE_EVENT_ANIMATION,
                    "推进事件动画",
                    anchorRect(result, EntryAnchorId.EVENT_ANIMATION_SKIP)
                        ?: LabyrinthFallbackTap.CENTER.rect(frameWidth, frameHeight),
                )
            }

            LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
            LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
            LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
            -> if (finalAnimationActive && finalAnimationPage) {
                LabyrinthPostEntryTapPlan(
                    LabyrinthPostEntryActionKind.ADVANCE_FINAL_SETTLEMENT,
                    "推进最终结算动画",
                    finalSettlementFallbackRect(
                        result = result,
                        pageState = pageState,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                    ),
                )
            } else {
                null
            }

            LabyrinthEntryPageState.RUN_CLEAR_RESULT ->
                if (postBossStage == LabyrinthPostBossStage.SCORE_RESULT) {
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.CLOSE_FINAL_SCORE,
                        "关闭最终分数结果",
                        anchorRect(result, EntryAnchorId.RUN_RESULT_CLOSE_BUTTON)
                            ?: LabyrinthFallbackTap.BOTTOM_CENTER.rect(frameWidth, frameHeight),
                    )
                } else {
                    null
                }

            LabyrinthEntryPageState.LINK_CHOICE ->
                (result.linkChoiceSelection?.preferredChoice
                    ?: result.linkChoiceSelection?.choices?.firstOrNull())?.let { choice ->
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.SELECT_LINK_IMPRINT,
                        "选择连结印记：${choice.element.label}",
                        choice.selectionButtonRect,
                    )
                }

            LabyrinthEntryPageState.RELIC_CHOICE -> {
                if (relicChoiceCommitted) {
                    null
                } else {
                    relicChoicePolicy.choose(
                        acquired = _state.value.observedRelics,
                        candidates = result.relicChoiceSelection?.choices.orEmpty(),
                        lockedFocus = relicFocusMark,
                        calibratedStacks = relicMarkStackCalibration,
                        currentArea = _state.value.routeProgress?.currentArea,
                    )?.let { decision ->
                        if (decision.phase != LabyrinthRelicChoicePhase.BUILD_BASELINE) {
                            relicFocusMark = decision.focusMark
                        }
                        pendingRelicSelection = decision.choice
                        LabyrinthPostEntryTapPlan(
                            LabyrinthPostEntryActionKind.SELECT_RELIC,
                            "选择遗物：${decision.choice.displayName}；${decision.reason}",
                            decision.choice.selectionButtonRect,
                        )
                    }
                }
            }

            LabyrinthEntryPageState.EVENT_CHOICE -> if (
                eventChoiceCommitted &&
                !labyrinthEventChoiceCommitExpired(eventChoiceCommittedAt, timestampMillis)
            ) {
                null
            } else if (
                eventChoiceCommitted &&
                eventChoiceCommitAttempts >= maxOf(
                    MAX_EVENT_CHOICE_COMMIT_ATTEMPTS,
                    (result.eventChoiceSelection?.event?.choices?.size ?: 0) + 1,
                )
            ) {
                finishFromPlanner(
                    sessionId,
                    "事件选项已点击${eventChoiceCommitAttempts}次仍停留在事件页；停止以免反复空点",
                )
                null
            } else {
                // A committed tap that the game ignored used to latch this page forever: the flag
                // only cleared on a page change, and the page never changed. The run then sat on
                // "事件推荐已生成，等待按钮稳定" — a message that describes waiting for a button,
                // while the truth was that the tap had already been sent and lost (2026-09-20
                // bundle 133934: 323 s on one EVENT_CHOICE page, actionCount frozen at 271).
                if (eventChoiceCommitted) {
                    nodeLog("event-choice-retry elapsedMillis=${timestampMillis - eventChoiceCommittedAt} " +
                        "choiceId=$eventChoiceCommittedChoiceId attempts=$eventChoiceCommitAttempts " +
                        "event=${result.eventChoiceSelection?.event?.id} " +
                        "ocr=${result.eventOcrDiagnostics?.status} cache=${result.eventOcrDiagnostics?.cache?.status}")
                    eventChoiceCommitted = false
                    eventChoiceCommitAttempts++
                    // The tap landed and the page did not move on. That option is gated -- the
                    // usual reason is a price the current alpha-coin balance cannot meet, which
                    // no pixel on the button reveals. Retire it and let the next-best option run.
                    eventChoiceCommittedChoiceId?.let { rejected ->
                        eventChoiceRejectedIds += rejected
                        nodeLog("event-choice-refused id=$rejected rejected=$eventChoiceRejectedIds", warning = true)
                    }
                    eventChoiceCommittedChoiceId = null
                }
                when (val decision = _state.value.eventChoiceDecision) {
                    is LabyrinthEventChoiceDecision.Select -> if (decision.actionSafe) {
                        LabyrinthPostEntryTapPlan(
                            LabyrinthPostEntryActionKind.SELECT_EVENT,
                            "选择事件：${decision.choiceId} ${decision.label}；${decision.explanation}",
                            decision.buttonRect,
                        )
                    } else {
                        null
                    }
                    is LabyrinthEventChoiceDecision.Wait -> null
                    null -> labyrinthSingleChoiceEventButtonRect(result)?.let { rect ->
                        nodeLog("event-choice-fallback source=SINGLE_CHOICE_ANCHORS rect=$rect " +
                            "layoutCount=${result.eventOcrDiagnostics?.completeLayoutCount} " +
                            "ocr=${result.eventOcrDiagnostics?.status} " +
                            "cache=${result.eventOcrDiagnostics?.cache?.status} rejected=$eventChoiceRejectedIds")
                        LabyrinthPostEntryTapPlan(
                            LabyrinthPostEntryActionKind.SELECT_EVENT,
                            "选择事件：唯一选项（单选事件结构确认）",
                            rect,
                        )
                    }
                }
            }

            LabyrinthEntryPageState.SHOP -> when (val decision = currentShopDecision(result)) {
                is LabyrinthShopDecision.BuyRelic -> {
                    val relic = decision.relicDecision.choice
                    if (decision.relicDecision.phase != LabyrinthRelicChoicePhase.BUILD_BASELINE) {
                        relicFocusMark = decision.relicDecision.focusMark
                    }
                    plannedShopRoleImprintLabel = null
                    plannedShopPurchaseRelicId = relic.relicId
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.SHOP_BUY_RELIC,
                        "购买遗物：${relic.displayName ?: relic.relicId}；${decision.relicDecision.reason}",
                        decision.item.buyButtonRect,
                    )
                }

                is LabyrinthShopDecision.BuyRoleImprint -> {
                    plannedShopPurchaseRelicId = null
                    plannedShopRoleImprintLabel = decision.label
                    // A 选择印记 opens the roster picker once the purchase completes; a 随机印记
                    // does not. Remember which was bought so the picker is answered by the
                    // free-role selector rather than the opening-roster handler.
                    shopChoiceImprintRolePending = decision.opensRolePicker
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.SHOP_BUY_IMPRINT,
                        "购买印记：${decision.label}（${if (decision.opensRolePicker) "选择" else "随机"}）；${decision.reason}",
                        decision.item.buyButtonRect,
                    )
                }

                is LabyrinthShopDecision.Refresh -> {
                    clearPlannedShopPurchase()
                    val rect = anchorRect(result, EntryAnchorId.SHOP_REFRESH_BUTTON)
                    if (rect != null) {
                        LabyrinthPostEntryTapPlan(LabyrinthPostEntryActionKind.SHOP_REFRESH, "最终区域刷新商店", rect)
                    } else {
                        shopWaitReason = "策略允许刷新，但刷新按钮没有达到安全识别线"
                        null
                    }
                }

                is LabyrinthShopDecision.Close -> {
                    clearPlannedShopPurchase()
                    val rect = anchorRect(result, EntryAnchorId.SHOP_CLOSE)
                    if (rect != null) {
                        LabyrinthPostEntryTapPlan(LabyrinthPostEntryActionKind.SHOP_CLOSE, "关闭商店：${decision.reason}", rect)
                    } else {
                        shopWaitReason = "商店已无计划购买项，但关闭按钮未稳定识别"
                        null
                    }
                }

                is LabyrinthShopDecision.Wait -> {
                    shopWaitReason = decision.reason
                    null
                }

                null -> {
                    shopWaitReason = "商店商品识别尚未生成"
                    null
                }
            }

            LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION -> {
                val plannedId = plannedShopPurchaseRelicId
                val plannedImprint = plannedShopRoleImprintLabel
                val candidate = result.shopObservation?.purchaseCandidate
                when {
                    plannedId == null && plannedImprint == null -> {
                        shopWaitReason = "未检测到程序发起的购买，本次确认弹窗保持人工控制"
                        null
                    }
                    plannedShopPurchaseStartedAt == Long.MIN_VALUE ||
                        timestampMillis - plannedShopPurchaseStartedAt > SHOP_PURCHASE_TRANSITION_TIMEOUT_MILLIS -> {
                        clearPlannedShopPurchase()
                        shopWaitReason = "程序发起的购买确认已超时，本次弹窗保持人工控制"
                        null
                    }
                    plannedImprint != null -> {
                        // The originating shop-card title was already OCR-classified as a role
                        // imprint. The confirmation modal is transaction-bound and short-lived;
                        // require the normal confirmation button, but do not run relic icon
                        // identity against an item that is explicitly not a relic.
                        val confirmRect = anchorRect(result, EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON)
                        if (confirmRect != null) {
                            LabyrinthPostEntryTapPlan(
                                LabyrinthPostEntryActionKind.SHOP_CONFIRM_IMPRINT,
                                "确认购买印记：$plannedImprint",
                                confirmRect,
                            )
                        } else {
                            shopWaitReason = "印记购买确认已建立，但确认按钮未达到安全识别线"
                            null
                        }
                    }
                    candidate?.relicId == null -> {
                        shopWaitReason = "购买确认弹窗中的遗物尚未可靠识别，拒绝自动确认"
                        null
                    }
                    candidate.relicId != plannedId -> {
                        shopWaitReason = "购买确认遗物${candidate.relicId}与计划${plannedId}不一致，拒绝自动确认"
                        null
                    }
                    else -> {
                        val confirmRect = anchorRect(result, EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON)
                        if (confirmRect != null) {
                            LabyrinthPostEntryTapPlan(
                                LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC,
                                "确认购买遗物：${candidate.displayName ?: candidate.relicId}",
                                confirmRect,
                            )
                        } else {
                            // Never fall back to the old hard-coded point: on the current modal
                            // it lands on the currency-change row above the confirm button.
                            shopWaitReason = "购买确认遗物已核对，但确认按钮未达到安全识别线"
                            null
                        }
                    }
                }
            }

            LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE ->
                if (plannedShopPurchaseRelicId != null || plannedShopRoleImprintLabel != null) {
                    // Purchase-complete is a single-button standard modal; dismissing it returns
                    // to SHOP, where all visible slots are recognized again. This is what makes
                    // the game-side "后面的商品补位" naturally enter the next decision cycle.
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE,
                        "关闭购买完成",
                        LabyrinthFallbackTap.SHOP_PURCHASE_COMPLETE_CLOSE.rect(frameWidth, frameHeight),
                    )
                } else {
                    shopWaitReason = "人工购买完成弹窗保持人工控制"
                    null
                }

            LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION ->
                LabyrinthPostEntryTapPlan(
                    LabyrinthPostEntryActionKind.SHOP_CONFIRM_EXIT,
                    "确认退出商店",
                    anchorRect(result, EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON)
                        ?: LabyrinthFallbackTap.SHOP_EXIT_CONFIRM.rect(frameWidth, frameHeight),
                )

            LabyrinthEntryPageState.BATTLE_TEAM_SELECTION -> null

            LabyrinthEntryPageState.BATTLE_FAILED ->
                result.battleFailure?.let { failure ->
                    // Both layouts tap the same lower-right button; only the kind and its
                    // accounting differ.
                    labyrinthBattleFailureTapPlan(failure, pendingSingleBossFallbackToMulti)
                }

            LabyrinthEntryPageState.BATTLE_CHALLENGE ->
                if (
                    labyrinthBattleChallengeCanAutoStart(
                        combatContext = combatContext,
                        challengeDifficultyResolved = result.exEncounter?.challengeDifficultyResolved == true,
                        extremeChallenge = result.exEncounter?.extremeChallenge == true,
                        exEncounterResolved = currentExEncounterStrategy != null,
                        exGuideFallbackApproved = exEncounterFallbackApproved,
                    )
                ) {
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.BATTLE_START_CHALLENGE,
                        "发起挑战",
                        anchorRect(result, EntryAnchorId.BATTLE_CHALLENGE_BUTTON)
                            ?: LabyrinthFallbackTap.CHALLENGE_START.rect(frameWidth, frameHeight),
                    )
                } else {
                    null
                }

            LabyrinthEntryPageState.BATTLE_RESULT ->
                LabyrinthPostEntryTapPlan(
                    LabyrinthPostEntryActionKind.BATTLE_RESULT_NEXT,
                    "战斗结算：下一步",
                    anchorRect(result, EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON)
                        ?: LabyrinthFallbackTap.BOTTOM_RIGHT.rect(frameWidth, frameHeight),
                )

            LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
            LabyrinthEntryPageState.NODE_SELECTION,
            -> null

            else -> null
        }
        if (plan == null) {
            traceReject("no-plan:${pageState.name}")
            if (activeSessionId == sessionId && postEntryStableFrames >= POST_ENTRY_STABLE_FRAMES) {
                val message = when (pageState) {
                    LabyrinthEntryPageState.BATTLE_IN_PROGRESS ->
                        "战斗中，等待结算"
                    LabyrinthEntryPageState.BATTLE_FAILED ->
                        "战斗失败页已识别，等待安全重试条件"
                    LabyrinthEntryPageState.BATTLE_CHALLENGE ->
                        when {
                            combatContext?.kind == LabyrinthCombatKind.EX || result.exEncounter?.extremeChallenge == true ->
                                "EX挑战页：等待具体遭遇攻略识别完成，禁止直接进入编组"
                            result.exEncounter?.challengeDifficultyResolved != true ->
                                "挑战页：等待确认普通/极难，禁止盲目挑战"
                            else -> "挑战页已确认，等待安全挑战条件"
                        }
                    LabyrinthEntryPageState.SHOP ->
                        shopWaitReason ?: "商店策略等待商品识别稳定"
                    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
                    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
                    -> shopWaitReason ?: "商店购买弹窗等待安全条件"
                    LabyrinthEntryPageState.EVENT_CHOICE ->
                        when (val decision = _state.value.eventChoiceDecision) {
                            is LabyrinthEventChoiceDecision.Select ->
                                decision.safetyNote ?: "事件推荐已生成，等待按钮稳定"
                            is LabyrinthEventChoiceDecision.Wait -> decision.reason
                            null -> "事件选项已识别，等待OCR语义决策"
                        }
                    LabyrinthEntryPageState.UNKNOWN ->
                        when {
                            roleRewardBatchActive && !roleRewardJoinedSequenceStarted ->
                                "角色奖励批次选择过渡中；等待下一次三选一，不执行盲点"
                            finalAnimationActive -> "最终结算动画中，程序会在有限次数内推进"
                            else -> "未知页面，自动点击已暂停；等待页面识别恢复"
                        }
                    LabyrinthEntryPageState.RUN_CLEAR_RESULT,
                    LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
                    LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
                    LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
                    LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
                    LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
                    -> "${pageState.name}：暂不自动处理（结算阶段 ${postBossStage.name}，已尝试 $postEntryAttempts 次）"
                    else -> roleRewardWaitReason
                        ?: manualInputMessage
                        ?: "${pageState.name}：暂不自动处理，等待画面推进"
                }
                _state.value = _state.value.copy(message = message)
                // A different message means some sub-state advanced, so the stretch restarts.
                if (postEntryStallMessage != message || postEntryStallSince == Long.MIN_VALUE) {
                    postEntryStallMessage = message
                    postEntryStallSince = timestampMillis
                } else if (
                    labyrinthPageStallExpired(
                        stalledMillis = timestampMillis - postEntryStallSince,
                        battlePage = pageState == LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
                    )
                ) {
                    finishFromPlanner(
                        sessionId,
                        "${pageState.name} 页面连续${(timestampMillis - postEntryStallSince) / 1000}秒无进展：$message；已停止并保留诊断状态",
                    )
                }
            }
            return
        }
        val (kind, label, rect) = plan
        if (rect.left + rect.width > frameWidth || rect.top + rect.height > frameHeight) {
            traceReject("target-outside-frame:$label")
            return
        }
        val roleRewardTap = kind == LabyrinthPostEntryActionKind.SELECT_ROLE_REWARD
        val relicPurchaseTap = kind == LabyrinthPostEntryActionKind.SHOP_BUY_RELIC ||
            kind == LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC
        val imprintPurchaseTap = kind == LabyrinthPostEntryActionKind.SHOP_BUY_IMPRINT ||
            kind == LabyrinthPostEntryActionKind.SHOP_CONFIRM_IMPRINT
        dispatchPostEntryTap(
            sessionId = sessionId,
            kind = kind,
            label = label,
            rect = rect,
            timestampMillis = timestampMillis,
            roleRewardSelectionSignature = if (roleRewardTap) roleRewardSelectionSignature else null,
            roleRewardSelectedCharacterId = if (roleRewardTap) {
                (_state.value.roleRewardChoiceDecision as? LabyrinthRoleRewardChoiceDecision.Select)?.characterId
            } else {
                null
            },
            shopPurchaseRelicId = if (relicPurchaseTap) plannedShopPurchaseRelicId else null,
            shopPurchaseRoleImprintLabel = if (imprintPurchaseTap) plannedShopRoleImprintLabel else null,
        )
    }

    /**
     * 编组页帧的入口：先看一眼特别装备流程是否在等着让帧，再交给编组/开战规划，最后给本页记账。
     *
     * 这个页面在 [handlePostEntryPage] 里直接 return，通用的 120 秒停滞看门狗对它永远不可达
     * （见 [LabyrinthPostEntryTapPlan] 里 BATTLE_TEAM_SELECTION 的显式 null），所以「同一句消息、
     * 一帧都没有派发动作」的停滞必须在这里自己收尾，否则就是静默无限等待。
     */
    private fun handleBattleTeamSelectionExecution(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        val messageBefore = _state.value.message
        planBattleTeamSelectionFrame(sessionId, result, frameWidth, frameHeight, timestampMillis)
        val progressed = _state.value.frameTrace.actionLabel != null ||
            _state.value.message != messageBefore
        if (!progressed && battleTeamPageStallSince != Long.MIN_VALUE &&
            timestampMillis - battleTeamPageStallSince >= POST_ENTRY_STALL_TIMEOUT_MILLIS
        ) {
            finishFromPlanner(
                sessionId,
                "队伍编组页连续${(timestampMillis - battleTeamPageStallSince) / 1000}秒没有派发任何编组动作：" +
                    "${_state.value.message}；已停止并保留诊断状态",
            )
            return
        }
        if (progressed || battleTeamPageStallSince == Long.MIN_VALUE) {
            battleTeamPageStallSince = timestampMillis
        }
    }

    private fun planBattleTeamSelectionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        if (actionInFlight.get() || activeSessionId != sessionId) return
        val observation = result.battleTeamSelection ?: return

        var preparationStep: LabyrinthBattleTeamExecutionStep? = null
        if (observation.bossTeamIndex != null && combatContext?.kind == LabyrinthCombatKind.BOSS) {
            if (bossEditorPreparationStage == LabyrinthBossEditorPreparationStage.INACTIVE) {
                // Also recovers a session that entered an already-open Boss editor directly.
                bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.TEAM_1
                bossEditorBlocked = false
                pendingBossTeamAdvance = null
            }
            if (bossEditorPreparationStage != LabyrinthBossEditorPreparationStage.COMPLETE) {
                val preparation = labyrinthBossEditorPreparationDecision(
                    observation = observation,
                    stage = bossEditorPreparationStage,
                )
                bossEditorPreparationStage = preparation.stage
                if (preparation.stage == LabyrinthBossEditorPreparationStage.COMPLETE) {
                    bossEditorTeamIndex = 1
                    bossEditorBlocked = false
                    pendingBossTeamAdvance = null
                    preparedBossFirstTeamIds = emptyList()
                    bossMultiTeamTargetCount = null
                    synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
                    synchronized(currentBattleTeamSignatures) { currentBattleTeamSignatures.clear() }
                    combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
                        .copy(kind = LabyrinthCombatKind.BOSS, teamIndex = 1)
                    lastBattleTeamRecommendationKey = null
                    lastBattleTeamSelectionPlanLog = null
                    resetBattleTeamExecutionTracking()
                    _state.value = _state.value.copy(
                        combatContext = combatContext,
                        battleTeamRecommendation = null,
                        battleTeamSelectionPlan = null,
                        battleTeamRecommendationUnavailableReason = null,
                        message = "Boss编组预处理完成：三队已清空并返回队伍1；开始扫描并生成新阵容",
                    )
                    return
                }
                preparationStep = preparation.step
                if (preparationStep == null) {
                    _state.value = _state.value.copy(message = "Boss编组预处理：等待队伍页与当前成员稳定识别")
                    return
                }
            }
        }

        if (preparationStep == null && observation.bossTeamIndex != null && bossEditorBlocked) {
            // A missed/ambiguous tab transition must never reuse another team's recommendation,
            // but stopping here permanently makes the automation unrecoverable. Re-enter the
            // existing Boss editor preparation flow instead: it clears all three tabs and returns
            // to team 1 before any new recommendation is allowed to execute.
            bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.TEAM_1
            bossEditorTeamIndex = observation.bossTeamIndex
            bossEditorBlocked = false
            pendingBossTeamAdvance = null
            preparedBossFirstTeamIds = emptyList()
            bossMultiTeamTargetCount = null
            synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
            synchronized(currentBattleTeamSignatures) { currentBattleTeamSignatures.clear() }
            combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
                .copy(kind = LabyrinthCombatKind.BOSS, teamIndex = observation.bossTeamIndex)
            lastBattleTeamRecommendationKey = null
            lastBattleTeamSelectionPlanLog = null
            resetBattleTeamExecutionTracking()
            _state.value = _state.value.copy(
                combatContext = combatContext,
                battleTeamRecommendation = null,
                battleTeamSelectionPlan = null,
                battleTeamRecommendationUnavailableReason = null,
                message = "Boss切队记录不完整；已自动进入重同步，将清空三队并返回队伍1重新编组",
            )
            return
        }
        // 「特别装备」闸门：Boss 预处理完成之后、产出任何编组执行步（含三条路径各自的 START_BATTLE）
        // 之前。本轮没结束时这一帧由特别装备流程接管并 return，于是下面三个调用点都无法产出动作；
        // 本轮结束（成功 / 未变更 / 超限放弃）或不适用时返回 false，原有编组/开战路径逐字继续。
        if (handleSpecialEquipmentFlowFrame(sessionId, result, timestampMillis)) return
        if (specialEquipmentModalCloseSettlePending) {
            // 特别装备弹窗刚关闭：这一帧只让给关闭动画，不派发任何编组/开战点击。紧跟在闸门之后、
            // 在产出任何执行步之前消费，且只让一帧。
            specialEquipmentModalCloseSettlePending = false
            return
        }
        val step = (preparationStep ?: if (
            bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM &&
            bossMultiTeamTargetCount != null &&
            bossMultiTeamTargetCount!! < 3 &&
            (observation.bossTeamIndex ?: 1) > bossMultiTeamTargetCount!!
        ) {
            labyrinthBossPartialMultiTeamExecutionStep(
                observation = observation,
                completedTeamCount = bossMultiTeamTargetCount!!,
                startButtonRect = anchorRect(result, EntryAnchorId.BATTLE_TEAM_START_BUTTON),
            ).also { next ->
                if (next == null) {
                    _state.value = _state.value.copy(message = when {
                        observation.recognitionState != com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState.STABLE ->
                            "Boss空队切换后等待画面稳定"
                        observation.selectedCharacters.isNotEmpty() ->
                            "Boss计划仅使用${bossMultiTeamTargetCount}队，但第${observation.bossTeamIndex}队并非空队；停止自动点击"
                        else -> "Boss已完成${bossMultiTeamTargetCount}队安全编组；等待空队页与开始按钮"
                    })
                }
            }
        } else if (
            bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM &&
            preparedBossFirstTeamIds.isNotEmpty()
        ) {
            labyrinthBossSingleTeamExecutionStep(
                observation,
                preparedBossFirstTeamIds,
                anchorRect(result, EntryAnchorId.BATTLE_TEAM_START_BUTTON),
            ).also { next ->
                if (next == null) {
                    _state.value = _state.value.copy(message = when {
                        observation.recognitionState != com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState.STABLE ->
                            "Boss切队后等待画面稳定"
                        observation.selectedCharacters.isNotEmpty() ->
                            "Boss单队首战阻塞：第${observation.bossTeamIndex}队并非空队，请核对后从队伍1重新启动"
                        else -> "Boss第一队已确认；等待可靠识别当前队伍页及战斗开始按钮"
                    })
                }
            }
        } else {
            val recommendation = _state.value.battleTeamRecommendation ?: return
            val plan = _state.value.battleTeamSelectionPlan ?: return
            if (!plan.isStillValid(sessionId, recommendation, observation)) return
            val search = battleRosterSearch.observe(sessionId, observation, plan.recommendedIds)
            var rosterRecoveryStep: LabyrinthBattleTeamExecutionStep? = null
            if (plan.scrollRequired) {
                when (search) {
                    LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE -> {
                        _state.value = _state.value.copy(message = "角色列表滑动后等待越界回弹并重新稳定")
                        return
                    }
                    LabyrinthBattleRosterSearchDecision.EXHAUSTED -> {
                        val missing = plan.notCurrentlyVisibleIds.joinToString("、") {
                            plan.recommendedNames[it] ?: plan.currentlySelectedNames[it] ?: it
                        }
                        battleRosterSearch.reset()
                        battleTeamScrollActions = 0
                        // Walk the missing roles' attribute tabs first (usually one or two rows,
                        // so no multi-row ambiguity), then 全部 as the last resort. Each tab is
                        // scanned to the end at most once per recommendation; the set is keyed to
                        // the recommendation in refreshBattleTeamSelectionPlan.
                        exhaustedBattleRosterFilters += observation.currentFilter
                        val recoveryStep = labyrinthBattleRosterFilterRecoveryStep(
                            plan = plan,
                            observation = observation,
                            exhaustedFilters = exhaustedBattleRosterFilters,
                        )
                        if (recoveryStep != null) {
                            rosterRecoveryStep = recoveryStep
                        } else {
                            // Every candidate tab, 全部 included, was scanned end to end and the
                            // roles are simply not in this editor. Clearing the exhausted set here
                            // used to restart the identical sweep on the next frame (8 full
                            // 火→光→全部 cycles, 66 swipes in the 2026-09-15 20:58 bundle). Keep
                            // the set, drop the unfindable roles from the run roster so the next
                            // recommendation is built from characters that actually exist, and
                            // let the plan be rebuilt from that.
                            val scanned = exhaustedBattleRosterFilters.joinToString("、") { it.label }
                            val unfindable = plan.notCurrentlyVisibleIds.toSet()
                            resetBattleTeamExecutionTracking()
                            excludeUnfindableBattleCharacters(sessionId, unfindable, missing, scanned)
                            return
                        }
                    }
                    LabyrinthBattleRosterSearchDecision.UNAVAILABLE -> {
                        resetBattleTeamExecutionTracking()
                        _state.value = _state.value.copy(message = "角色列表反馈暂不可确认；保持编组页并重新建立扫描")
                        return
                    }
                    else -> Unit
                }
            }
            rosterRecoveryStep ?: labyrinthBattleTeamExecutionStep(
                plan = plan,
                sessionId = sessionId,
                recommendation = recommendation,
                observation = observation,
                startButtonRect = anchorRect(result, EntryAnchorId.BATTLE_TEAM_START_BUTTON),
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                scrollDirection = if (search == LabyrinthBattleRosterSearchDecision.TO_TOP)
                    LabyrinthBattleRosterScrollDirection.TO_TOP else LabyrinthBattleRosterScrollDirection.NEXT_PAGE,
            )
        }) ?: return

        if (step.key != lastBattleTeamExecutionKey) {
            lastBattleTeamExecutionKey = step.key
            battleTeamExecutionAttempts = 0
        }
        val isCharacterToggle = step.kind == LabyrinthBattleTeamExecutionKind.SELECT_CHARACTER ||
            step.kind == LabyrinthBattleTeamExecutionKind.DESELECT_CHARACTER ||
            step.kind == LabyrinthBattleTeamExecutionKind.RESET_BOSS_DESELECT_CHARACTER
        val maximumAttempts = when {
            isCharacterToggle -> 1
            step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS -> MAX_BATTLE_TEAM_SCROLL_ACTIONS
            else -> MAX_BATTLE_TEAM_STEP_ATTEMPTS
        }
        if (battleTeamExecutionAttempts >= maximumAttempts) {
            // Never blindly repeat a character toggle. If visual feedback is still absent after
            // the grace period, discard the executable plan and let the next frame rebuild it
            // from the current roster state instead of terminating automation.
            if (
                isCharacterToggle &&
                timestampMillis - lastBattleTeamActionAt < BATTLE_TEAM_CHARACTER_FEEDBACK_TIMEOUT_MILLIS
            ) {
                return
            }
            resetBattleTeamExecutionTracking()
            lastBattleTeamSelectionPlanLog = null
            _state.value = _state.value.copy(
                battleTeamSelectionPlan = null,
                message = "自动编组动作反馈未确认：${step.label}；保持编组页并重新识别后规划",
            )
            return
        }
        if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS &&
            battleTeamScrollActions >= MAX_BATTLE_TEAM_SCROLL_ACTIONS
        ) {
            resetBattleTeamExecutionTracking()
            _state.value = _state.value.copy(message = "自动编组达到单轮滚动上限；保持编组页并重新从顶部扫描")
            return
        }
        if (lastBattleTeamActionAt != Long.MIN_VALUE &&
            timestampMillis - lastBattleTeamActionAt < BATTLE_TEAM_ACTION_INTERVAL_MILLIS
        ) {
            return
        }
        // 会话级节流：本路径自己的时间戳只在编组执行步之间有效，特别装备流程的点击不写它。
        // 两条流程之间必须看 [lastSessionActionAt]，否则「特别装备：取消」之后 0.76s 就会派发开战。
        if (!sessionActionIntervalElapsed(timestampMillis)) {
            traceReject("session-interval:${timestampMillis - lastSessionActionAt}")
            return
        }
        if (
            step.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE ||
            step.kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM
        ) {
            // 这两种动作必须把编组页推走：派发即记账，页面原地不动超过动作确认上限就按「没被收下」
            // 回滚（见 handleBattleWaitFrame）。
            // 先取旧值：下面会把本场编组写进记忆，而「这一下没被收下」时要还回去的是**写入之前**的
            // 上一场编组。顺序反了的话回滚只是把新值写成它自己。
            val previousNormalTeamSignature = lastNormalBattleTeamSignature
            if (step.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE &&
                combatContext?.kind == LabyrinthCombatKind.NORMAL &&
                step.confirmedTeamIds.isNotEmpty()
            ) {
                // 普通关卡在这里记下本场编组：下一场若与它完全相同就跳过特别装备流程。EX / Boss 不写。
                lastNormalBattleTeamSignature = labyrinthBattleTeamSignature(step.confirmedTeamIds)
            }
            pendingBattleTeamAction = PendingBattleTeamAction(
                kind = step.kind,
                label = step.label,
                confirmedTeamIds = step.confirmedTeamIds,
                signature = battleTeamEditorSignature(result),
                dispatchedAt = timestampMillis,
                previousNormalTeamSignature = previousNormalTeamSignature.takeIf {
                    step.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE
                },
            )
        }
        // 无论哪种动作，派发前都把会话级节流戳盖上（动作被拒时的回退由各自的 Rejected 分支负责）。
        lastSessionActionAt = timestampMillis
        dispatchBattleTeamAction(sessionId, step, timestampMillis)
    }

    /**
     * 「编组页动作是否被画面收下」的签名：页面类型 + Boss 队伍页签。
     *
     * Boss 切换队伍时页面类型仍是编组页，只有页签变，所以签名必须带上页签；否则一次成功的「下一队」
     * 会被当成「点了没反应」。
     */
    private fun battleTeamEditorSignature(result: LabyrinthEntryFrameResult): String =
        result.observation.state.name + ":" + (result.battleTeamSelection?.bossTeamIndex ?: -1)

    /**
     * 本次战斗的关卡类型是否需要自动穿戴特别装备。
     *
     * 判定只读启动时抓取的设置快照（[specialEquipmentSettings]），并且直接调用设置层自己的
     * [LabyrinthStrategySettings.autoEquipsSpecialEquipment]——「自动穿戴 + 空集合」在那一层就已经
     * 是「不执行」，这里不再另写一套规则。`combatContext` 为空＝不知道类型 ⇒ 不执行。
     */
    private fun specialEquipmentAppliesToCurrentBattle(): Boolean {
        val kind = combatContext?.kind ?: return false
        return specialEquipmentSettings?.autoEquipsSpecialEquipment(kind) == true
    }

    /** 清掉本轮标记（离开编组页、页签更换、或本轮不适用时调用）。 */
    private fun resetSpecialEquipmentRound() {
        specialEquipmentFlowStage = LabyrinthSpecialEquipmentFlowStage.INACTIVE
        specialEquipmentStepAttempts = 0
        specialEquipmentStepStartedAt = Long.MIN_VALUE
        specialEquipmentAwayFrames = 0
        specialEquipmentPendingEscape = null
        specialEquipmentEscapeDeadline = Long.MIN_VALUE
        specialEquipmentDiagnostic = null
        specialEquipmentAbandoned = false
        specialEquipmentDialogObserved = false
        specialEquipmentModalCloseSettlePending = false
        specialEquipmentEquipEvidence = LabyrinthSpecialEquipmentEquipEvidenceStreak()
        specialEquipmentRoundBossTeamIndex = null
    }

    /** 当前页签的队伍成员：每个槽位一个 `characterId`，未识别的槽位是 null。 */
    private fun currentTeamCharacterIds(result: LabyrinthEntryFrameResult): List<String?> =
        result.battleTeamSelection?.selectedCharacters.orEmpty().map { match -> match.characterId }

    /**
     * 这一帧是不是「Boss 多队模式编组页的一个队伍页签」。
     *
     * 只有这种页面上页签才代表一支独立的队伍：单队模式整段只跑一轮，普通/EX 的队伍页没有页签身份，
     * 两者都不受「页签更换即重新武装」的影响。
     */
    private fun onMultiTeamBossTab(result: LabyrinthEntryFrameResult): Boolean =
        combatContext?.kind == LabyrinthCombatKind.BOSS &&
            bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM &&
            result.battleTeamSelection?.bossTeamIndex != null

    /**
     * 本场普通关卡是否与上一场普通战斗的编组完全相同。
     *
     * 判据见 [labyrinthSpecialEquipmentTeamIsUnchanged]：数量相同、逐槽都识别出角色、角色集合相同。
     * EX / Boss 永不跳过；本次战斗类型不在勾选集合内时流程本来就不介入。
     */
    private fun specialEquipmentTeamIsUnchanged(result: LabyrinthEntryFrameResult): Boolean {
        if (combatContext?.kind != LabyrinthCombatKind.NORMAL) return false
        return labyrinthSpecialEquipmentTeamIsUnchanged(
            characterIds = currentTeamCharacterIds(result),
            previousSignature = lastNormalBattleTeamSignature,
        )
    }

    /** 只更新消息，避免每帧重建整个 state；消息本身也是停滞看门狗眼里的「进度」。 */
    private fun publishSpecialEquipmentMessage(message: String) {
        if (_state.value.message == message) return
        _state.value = _state.value.copy(message = message)
    }

    private fun specialEquipmentTapLabel(tap: LabyrinthSpecialEquipmentTap): String = when (tap) {
        LabyrinthSpecialEquipmentTap.ENTRY -> "特别装备：点击入口「特别装备」"
        LabyrinthSpecialEquipmentTap.AUTO_BUTTON -> "特别装备：点击「自动装备」"
        LabyrinthSpecialEquipmentTap.AUTO_CONFIRM -> "特别装备：点击弹窗2「确认」"
        LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM -> "特别装备：点击「装备确定」"
        LabyrinthSpecialEquipmentTap.CANCEL -> "特别装备：点击「取消」"
    }

    /** 决策里的一次点击按按钮称呼（不含动作前缀，用来拼「等待后补发…」这类诊断消息）。 */
    private fun specialEquipmentTapName(tap: LabyrinthSpecialEquipmentTap): String = when (tap) {
        LabyrinthSpecialEquipmentTap.ENTRY -> "入口「特别装备」"
        LabyrinthSpecialEquipmentTap.AUTO_BUTTON -> "「自动装备」"
        LabyrinthSpecialEquipmentTap.AUTO_CONFIRM -> "弹窗2「确认」"
        LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM -> "「装备确定」"
        LabyrinthSpecialEquipmentTap.CANCEL -> "「取消」"
    }

    /**
     * 「特别装备」本轮的执行钩子。返回 true 表示这一帧由本流程接管（已派发点击或正在等待），调用处
     * 必须立刻 return，**不得**再产出任何编组执行步——三条开始战斗路径都由这一点统一切断。
     *
     * 返回 false 的三种情况都保持既有行为不变：
     * 1. 本轮不适用（设置为「关闭」/ 本次战斗类型未勾选 / 不知道类型）；
     * 2. 已经稳定地离开编组页（顺带清掉本轮标记，下次进入编组页是新的一轮）；
     * 3. 本轮已经结束，而且编组页没有被弹窗挡住（放行开战）。
     */
    private fun handleSpecialEquipmentFlowFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        if (activeSessionId != sessionId) return false
        val observation = result.specialEquipment
        val pageState = result.observation.state
        val dialogOpen = observation?.stage != null
        val onTeamPage = pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION

        // 弹窗1/弹窗2 与 UNKNOWN 都不算离开编组页；只有**连续**若干帧落在别的页面才算「稳定
        // 地离开」。单帧即清标记是错的：弹窗关合动画、Boss 切队、结算过渡都会插进来一两帧误分类，
        // 那样会重新武装并在同一轮里再跑一遍。
        //
        // 这一支一律不吃帧（返回 false），UNKNOWN 也不例外。
        // 它位于下面决策调用的前面，吃帧就等于把这一帧从两个上限体系里摘出去——本流程自己的 8s/3 次
        // 上限根本走不到，而 2403-2408 的早返回又让 handlePostEntryPage（120s 停滞看门狗唯一推进的
        // 地方）看不到它 ⇒ 又变回无界等待。交还帧就够：**标记保留**本身已经满足
        // （UNKNOWN 不算离开、单帧不算离开），下一次看到真实页面再决定是否继续本轮。
        if (!onTeamPage && !dialogOpen) {
            specialEquipmentAwayFrames =
                if (pageState == LabyrinthEntryPageState.UNKNOWN) 0 else specialEquipmentAwayFrames + 1
            if (
                specialEquipmentAwayFrames >= SPECIAL_EQUIPMENT_LEAVE_STABLE_FRAMES &&
                specialEquipmentFlowStage != LabyrinthSpecialEquipmentFlowStage.INACTIVE
            ) {
                resetSpecialEquipmentRound()
            }
            return false
        }
        specialEquipmentAwayFrames = 0

        // 设置关闭、或本次战斗类型未勾选时完全不介入——零点击、不新增等待、不新增中间态。
        if (!specialEquipmentAppliesToCurrentBattle()) {
            if (specialEquipmentFlowStage != LabyrinthSpecialEquipmentFlowStage.INACTIVE) {
                resetSpecialEquipmentRound()
            }
            return false
        }

        // Boss 多队编组页的队伍标签切换**不是**同一轮：每个页签是一支独立的队伍，页签一变上一轮就
        // 作废——让当前页签走完自己的一整轮（入口 → 自动装备 → 弹窗2 确认 → 装备确定/取消），上一个
        // 页签的结束标记不再替它挡住穿戴。单队模式整段只跑一轮，页签不参与身份，行为不变。
        //
        // 只在上一轮**已经结束**（DONE）时才按页签重开：本轮还在走的时候被一帧误分类的页签打断，
        // 就会回到「重新武装并在同一轮里再跑一遍」的老问题；在走的轮次本来也有 8s/3 次上限兜底。
        val bossMultiTeamTab = onMultiTeamBossTab(result)
        val bossTeamIndex = result.battleTeamSelection?.bossTeamIndex
        if (
            bossMultiTeamTab &&
            specialEquipmentFlowStage == LabyrinthSpecialEquipmentFlowStage.DONE &&
            specialEquipmentRoundBossTeamIndex != null &&
            bossTeamIndex != specialEquipmentRoundBossTeamIndex
        ) {
            resetSpecialEquipmentRound()
        }

        // 武装：只在编组页且队伍页已稳定识别时武装一次。
        if (specialEquipmentFlowStage == LabyrinthSpecialEquipmentFlowStage.INACTIVE) {
            if (!onTeamPage) {
                // 没有在编组页武装过就不启动（例如用户手动打开了这个弹窗）：只留一条诊断消息。
                publishSpecialEquipmentMessage("特别装备：识别到弹窗，但本轮尚未在编组页武装，不自动点击")
                return false
            }
            if (
                result.battleTeamSelection?.recognitionState !=
                LabyrinthBattleTeamRecognitionState.STABLE
            ) {
                return false
            }
// **推荐阵容就位之前不武装、更不得点入口**：成员槽还空着、总战力 0、画面中央是
// 「请选择角色。」时点入口，游戏会弹「请选择角色。」，特别装备弹窗不会出现。
//
// 判据是编组计划的 teamReady（推荐阵容已全部就位），用法与 buildOverlayPresentation 一致，
// 刻意**不**要求 5 人齐全：「保持当前队伍」的场景在 teamReady 变成 true 之后照常进入。
// 未就绪就返回 false 把这一帧交还既有编组路径——它才负责把角色选上、把计划推到 ready。
//
// 入口 ROI 在实机帧上命中正确：圆形按钮位置与标定一致，还带点击反馈圈。
//
            if (_state.value.battleTeamSelectionPlan?.teamReady != true) return false
            // Boss 多队模式下没有成员的页签没有可穿戴的对象：不武装、零点击，把帧交还给既有
            // 「其余留空，开始挑战」路径。
            if (bossMultiTeamTab && labyrinthSpecialEquipmentTeamIsEmpty(currentTeamCharacterIds(result))) {
                return false
            }
            // 普通关卡与上一场普通战斗的编组完全相同 ⇒ 装备没变，跳过本轮：不武装、不点入口，
            // 把帧交还给既有编组/开战路径，只留一条可诊断消息。
            if (specialEquipmentTeamIsUnchanged(result)) {
                publishSpecialEquipmentMessage("特别装备：本场编组与上一场普通战斗完全相同，跳过本次穿戴")
                return false
            }
            specialEquipmentFlowStage = LabyrinthSpecialEquipmentFlowStage.AWAIT_ENTRY
            specialEquipmentStepAttempts = 0
            specialEquipmentStepStartedAt = timestampMillis
            specialEquipmentRoundBossTeamIndex = bossTeamIndex.takeIf { bossMultiTeamTab }
        }

// 两态判定必须先**跨帧稳定**：这一帧的原始证据只用来推进连续
        // 计数：只有连续 SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_FRAMES 帧同类证据才把结论交给决策，
        // 首帧/过渡帧拿到的是 null ⇒ 决策只等一帧，既不点「装备确定」也不点「取消」。
        val equipEvidence = labyrinthSpecialEquipmentEquipEvidenceStreak(
            previous = specialEquipmentEquipEvidence,
            observation = observation,
        )
        specialEquipmentEquipEvidence = equipEvidence

        val decision = labyrinthSpecialEquipmentDecision(
            stage = specialEquipmentFlowStage,
            observation = observation,
            stepAttempts = specialEquipmentStepAttempts,
            stepElapsedMillis = if (specialEquipmentStepStartedAt == Long.MIN_VALUE) {
                0L
            } else {
                (timestampMillis - specialEquipmentStepStartedAt).coerceAtLeast(0L)
            },
            equipConfirmEnabled = equipEvidence.confirmedEnabled,
        )
        // 只在本轮真正（武装后）看到过弹窗的帧上记账：用户手动打开的弹窗不引入任何新等待。
        if (dialogOpen) specialEquipmentDialogObserved = true

        // 结束但**带点击**的决策只有一种：超限逃生（点弹窗1 左下白色「取消」退回编组页）。
        // 结束判定必须先落下（stage=DONE），否则下一帧的 DONE 决策不会再带上这次点击；点击本身记成
        // 待办，等动作间隔到期补发——这是「记成待办再补发」的那一支，与下面「派发成功再提交」互补。
        if (decision.finished) {
            if (decision.abandoned) {
                // 超限/放弃的**诊断原文**：后续帧不得用「本轮已结束，继续编组与开战」把它覆盖，
                // 它同时决定停机文案该说「已放弃」还是「已正常结束」。
                if (specialEquipmentDiagnostic == null) specialEquipmentDiagnostic = decision.message
                specialEquipmentAbandoned = true
            }
            if (decision.tap != null) {
                specialEquipmentPendingEscape = decision.tap
                // 待办本身也有窗口：窗口内始终派发不出去（例如单动作锁一直被占）就放弃这次点击，
                // 交给下面「本轮已结束但弹窗仍开着」的分支停机并保留诊断，不引入无界等待。
                specialEquipmentEscapeDeadline =
                    timestampMillis + SPECIAL_EQUIPMENT_ESCAPE_TIMEOUT_MILLIS
            }
        }
        if (
            specialEquipmentPendingEscape != null &&
            specialEquipmentEscapeDeadline != Long.MIN_VALUE &&
            timestampMillis >= specialEquipmentEscapeDeadline
        ) {
            specialEquipmentPendingEscape = null
        }

        val tap = decision.tap ?: specialEquipmentPendingEscape
        val rect = tap?.let { current -> observation?.let { labyrinthSpecialEquipmentTapRect(current, it) } }
        val tapReady = rect != null && sessionActionIntervalElapsed(timestampMillis)
        // **「决定要点击」不等于「点击已经派发」**：会话动作间隔（[BATTLE_TEAM_ACTION_INTERVAL_MILLIS]）
        // 与单动作锁都可能把这一下挡在派发之前。此时若把 stage 推进到下一状态，就等于宣布「已经点过
        // 了」——下一帧的决策会从下一状态继续等一个永远不会出现的画面，入口与「装备确定」都是这样丢的。
        // 所以 stage 只在「本帧没有待派发的点击」或「待派发的点击确实派发成功」之后才提交；被挡下的
        // 这一帧保持原 stage、步进次数与步进起点不变，间隔到期后同一决策会重新算出来补发**一次**。
        // 补发同样受 [MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS] 与 [SPECIAL_EQUIPMENT_STEP_TIMEOUT_MILLIS]
        // 约束（次数只在真的派发时增加，超时按原来的步进起点计时），因此不会变成新的无界等待。
        if (tap == null || decision.finished) {
            commitSpecialEquipmentStage(decision, timestampMillis)
        }
        // 1) 有待派发的点击，但这一帧派不出去（动作间隔未到 / 矩形不可信）⇒ 只等，stage 不动
        //    （结束决策的 DONE 与逃生待办已经落下了）。间隔到期后同一决策会再算出来补发。
        if (tap != null && !tapReady) {
            publishSpecialEquipmentMessage(
                when {
                    // 矩形可信 ⇒ 唯一的拦路者就是会话动作间隔。
                    rect != null -> "特别装备：动作间隔未到，等待后补发点击${specialEquipmentTapName(tap)}"
                    // 结束决策的待办在等矩形：诊断原文优先，别用「本轮已结束，继续编组与开战」盖掉超限原因。
                    decision.finished -> specialEquipmentDiagnostic ?: decision.message
                    else -> decision.message
                },
            )
            return true
        }

        // 2) 没有待派发的点击。
        if (tap == null) {
            if (!decision.finished) {
                // 3) 本轮还没结束：没有可信矩形就只等（不点候选位置）；
                //    次数与时间上限都在决策函数里兜底。
                publishSpecialEquipmentMessage(decision.message)
                return true
            }
            if (!dialogOpen) {
                // 编组页干净：把这一帧交还既有编组/开战路径（放行开战）。
                // 交还帧时把逃生窗口一并收掉，否则 pendingEscape/escapeDeadline 会跨轮残留，
                // 8s 窗口内若再出现弹窗就会多打一次「取消」。
                specialEquipmentPendingEscape = null
                specialEquipmentEscapeDeadline = Long.MIN_VALUE
                publishSpecialEquipmentMessage(decision.message)
                if (specialEquipmentDialogObserved) {
                    // 本轮刚关掉过弹窗：把「关闭后的第一帧」让给关闭动画——这一刻点在「战斗开始」上
                    // 会被动画吃掉。只让一帧，由 handleBattleTeamSelectionExecution 消费。
                    specialEquipmentDialogObserved = false
                    specialEquipmentModalCloseSettlePending = true
                }
                return false
            }
            // 本轮已结束、弹窗却仍开着，而且没有可以派发的逃生点击（待办已清或矩形不可信）。再吃帧就
            // 变成「安静地永久停在编组页」：既逃不出弹窗也不停机、也没有「卡在哪一步」的诊断。
            // 停机时必须保留超限诊断原文，不得用「本轮已结束，继续编组与开战」把它盖掉。
            val diagnostic = specialEquipmentDiagnostic ?: decision.message
            publishSpecialEquipmentMessage(diagnostic)
            if (
                specialEquipmentEscapeDeadline != Long.MIN_VALUE &&
                timestampMillis < specialEquipmentEscapeDeadline
            ) {
                // 逃生点击已经派发出去，给它一个等弹窗关闭的窗口；窗口内不重复点击、也不停机。
                return true
            }
            finishFromPlanner(
                sessionId,
                if (specialEquipmentAbandoned) {
                    "特别装备：已放弃本轮且弹窗仍未关闭（无可用逃生矩形）；已停止并保留诊断状态；$diagnostic"
                } else {
                    // 本轮是**正常结束**（没有放弃诊断），不得把它误报成「超限放弃」。
                    "特别装备：本轮已结束但弹窗仍开着且没有可用逃生矩形；已停止并保留诊断状态；$diagnostic"
                },
            )
            return true
        }

        // 4) 有待派发的点击，而且这一帧满足派发条件 ⇒ **真的派发出去之后再提交 stage**。
        publishSpecialEquipmentMessage(
            if (decision.finished) specialEquipmentDiagnostic ?: decision.message else decision.message,
        )
        if (
            !dispatchSpecialEquipmentAction(
                sessionId = sessionId,
                rect = requireNotNull(rect),
                label = specialEquipmentTapLabel(tap),
                timestampMillis = timestampMillis,
            )
        ) {
            // 单动作锁被占：这一下没有落到画面上，与「动作间隔未到」同等处理——stage 不前进（结束决策
            // 除外，它的 DONE 与逃生待办在前面的结束判定里已经落下），待办保留，下一次间隔到期后补发。
            return true
        }
        if (!decision.finished) {
            commitSpecialEquipmentStage(decision, timestampMillis)
        }
        if (decision.tap != null) {
            specialEquipmentStepAttempts++
        }
        if (decision.finished) {
            // 逃生点击**只点一次**——派发成功后立刻清待办，否则下一个动作间隔（≥1.2s）到来时残留的
            // pendingEscape 会再点一次「取消」，与本段的语义和注释都不符。
            specialEquipmentPendingEscape = null
            specialEquipmentEscapeDeadline =
                timestampMillis + SPECIAL_EQUIPMENT_ESCAPE_TIMEOUT_MILLIS
        }
        return true
    }

    /**
     * 提交决策给出的 stage（stage 变化时同时把步进次数与步进起点重置到本帧）。
     *
     * 只允许在「本帧没有待派发的点击」或「待派发的点击确实派发成功」之后调用：stage 的推进本身就
     * 声明了「这一步的动作已经派发过」，在点击被会话节流或单动作锁挡下时提交它，会让下一帧的决策
     * 从一个没人执行过的状态继续等下去。
     */
    private fun commitSpecialEquipmentStage(
        decision: LabyrinthSpecialEquipmentDecision,
        timestampMillis: Long,
    ) {
        if (decision.stage != specialEquipmentFlowStage) {
            specialEquipmentStepAttempts = 0
            specialEquipmentStepStartedAt = timestampMillis
        }
        specialEquipmentFlowStage = decision.stage
    }

    /**
     * 会话级动作间隔：与编组执行步共用同一个节流时间戳，两个流程之间不得插队（见 [lastSessionActionAt]）。
     */
    private fun sessionActionIntervalElapsed(timestampMillis: Long): Boolean =
        lastSessionActionAt == Long.MIN_VALUE ||
            timestampMillis - lastSessionActionAt >= BATTLE_TEAM_ACTION_INTERVAL_MILLIS

    /**
     * 派发特别装备流程的一次点击。与 [dispatchBattleTeamAction] 同构（同一把单动作锁、同样的前台与
     * 拒绝处置），但**用自己的时间戳与计数**，不触碰编组执行步的 key/attempts 语义。
     *
     * 动作坐标一律取「决策选中的按钮身份」在本帧观测里的矩形中心（[labyrinthSpecialEquipmentTapRect]），
     * 因此不存在写死坐标，也不存在把「确认」派发到「装备确定」上的路径。
     */
    private fun dispatchSpecialEquipmentAction(
        sessionId: AutomationSessionId,
        rect: EntryPixelRect,
        label: String,
        timestampMillis: Long,
    ): Boolean {
        if (!beginGuardedAction()) return false
        traceAction(label)
        lastSessionActionAt = timestampMillis
        launchGuardedAction {
            val executor = actionExecutor
            val action = AutomationAction.Tap(
                ScreenPoint(rect.left + rect.width / 2f, rect.top + rect.height / 2f),
            )
            val actionResult = if (executor == null) {
                AutomationActionResult.Rejected(action, "动作执行器不可用")
            } else {
                executor.execute(sessionId, action)
            }
            when (actionResult) {
                is AutomationActionResult.Executed -> if (activeSessionId == sessionId) {
                    val current = _state.value
                    _state.value = current.copy(
                        actionCount = current.actionCount + 1,
                        lastActionLabel = label,
                        message = "已执行：$label；等待画面确认",
                    )
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
                is AutomationActionResult.Rejected -> {
                    if (actionResult.reason == GAME_NOT_FOREGROUND_REASON) {
                        // 这一下没有落到游戏上，不算一次真实点击：丢掉节流戳，回前台后可以立刻重试
                        // （与编组执行步被拒时的处置一致）。
                        lastSessionActionAt = Long.MIN_VALUE
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("特别装备动作被拒绝：${actionResult.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
        return true
    }

    private fun dispatchBattleTeamAction(
        sessionId: AutomationSessionId,
        step: LabyrinthBattleTeamExecutionStep,
        timestampMillis: Long,
    ) {
        if (!beginGuardedAction()) return
        traceAction(step.label)
        battleTeamExecutionAttempts++
        if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS) {
            battleTeamScrollActions++
        }
        lastBattleTeamActionAt = timestampMillis
        launchGuardedAction {
            val executor = actionExecutor
            val actionResult = if (executor == null) {
                AutomationActionResult.Rejected(step.action, "动作执行器不可用")
            } else {
                executor.execute(sessionId, step.action)
            }
            when (actionResult) {
                is AutomationActionResult.Executed -> if (activeSessionId == sessionId) {
                    if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS) {
                        val direction = step.scrollDirection
                        val originPosition = step.scrollOriginPosition
                        if (direction != null && originPosition != null) {
                            battleRosterSearch.recordExecutedScroll(direction, originPosition)
                        }
                    }
                    if (step.kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM) {
                        pendingBossTeamAdvance = ((bossEditorTeamIndex ?: 1) + 1) to step.confirmedTeamIds
                    }
                    if (step.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE) {
                        battleWait.onStartExecuted(clock())
                        if (step.confirmedTeamIds.isNotEmpty()) {
                            synchronized(currentBattleTeamSignatures) {
                                currentBattleTeamSignatures += labyrinthBattleTeamSignature(step.confirmedTeamIds)
                            }
                        }
                        synchronized(committedBattleCharacterIds) {
                            committedBattleCharacterIds += step.confirmedTeamIds
                        }
                    }
                    val current = _state.value
                    _state.value = current.copy(
                        actionCount = current.actionCount + 1,
                        lastActionLabel = step.label,
                        message = "已执行：${step.label}；等待画面确认",
                    )
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
                is AutomationActionResult.Rejected -> {
                    if (actionResult.reason == GAME_NOT_FOREGROUND_REASON) {
                        battleTeamExecutionAttempts = (battleTeamExecutionAttempts - 1).coerceAtLeast(0)
                        if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS) {
                            battleTeamScrollActions = (battleTeamScrollActions - 1).coerceAtLeast(0)
                        }
                        lastBattleTeamActionAt = Long.MIN_VALUE
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("自动编组动作被拒绝：${actionResult.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    /** Record why a plannable action was withheld this frame. Diagnostic only. */
    private fun traceReject(reason: String) {
        val current = _state.value
        if (current.frameTrace.actionRejectReason == reason) return
        _state.value = current.copy(frameTrace = current.frameTrace.copy(actionRejectReason = reason))
    }

    /** Record the label of the action dispatched on this frame. Diagnostic only. */
    private fun traceAction(label: String) {
        val current = _state.value
        _state.value = current.copy(frameTrace = current.frameTrace.copy(actionLabel = label))
    }

    /**
     * The run roster can contain characters this editor cannot show: a stale roster restored
     * from a previous run, or a recognition mistake at join time. Once every tab has been scanned
     * they are provably absent, so remove them from the roster the recommendation is built from.
     * Nothing is persisted: the store still holds the full list, this only shapes planning.
     */
    private fun excludeUnfindableBattleCharacters(
        sessionId: AutomationSessionId,
        unfindable: Set<String>,
        missingLabel: String,
        scannedLabel: String,
    ) {
        if (activeSessionId != sessionId) return
        val current = _state.value
        val remaining = labyrinthRosterAfterUnfindableExclusion(current.joinedCharacters, unfindable)
        if (remaining == null) {
            _state.value = current.copy(
                battleTeamSelectionPlan = null,
                message = "已在${scannedLabel}筛选均扫到底仍未识别推荐角色：$missingLabel；未能从角色库排除，保持编组页",
            )
            return
        }
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        _state.value = current.copy(
            joinedCharacters = remaining,
            battleTeamRecommendation = null,
            battleTeamSelectionPlan = null,
            battleTeamRecommendationUnavailableReason = null,
            message = "已在${scannedLabel}筛选均扫到底仍未识别推荐角色：$missingLabel；已从本局可用角色中排除，重新规划编组",
        )
        nodeLog(
            "battle-roster-exclude unfindable=${unfindable.joinToString(",")} " +
                "scanned=$scannedLabel remaining=${remaining.size}",
            warning = true,
        )
    }

    private fun resetBattleTeamExecutionTracking() {
        battleRosterSearch.reset()
        lastBattleTeamExecutionKey = null
        battleTeamExecutionAttempts = 0
        battleTeamScrollActions = 0
        lastBattleTeamActionAt = Long.MIN_VALUE
    }

    private fun resetExEncounterTracking() {
        currentExEncounterStrategy = null
        exEncounterFallbackApproved = false
        exSlot3ProbePending = false
        exSlot3ProbeAttempts = 0
        exEncounterProbeStartedAt = Long.MIN_VALUE
        exLastKnownPage = null
        exIdentityProbeDescription = "EX识别目标"
        // The official "有效效果" sweep describes the encounter rather than the node, so it is
        // cleared by [resetEffectiveCharacterScan] instead of here.
    }

    /**
     * Drops the official "有效效果" roster sweep, forcing a fresh filtered-list scan.
     *
     * The sweep belongs to the encounter, so an EX fight and the Boss of that same encounter share
     * one. [prepareNodeTransitionContext] keeps it across exactly that hand-off and clears it for
     * every other node entry; [refreshEffectiveCharacterScan] also drops it when the owned roster
     * changed in between. Boss contexts that are recovered without a node transition (settlement
     * or challenge-page fallbacks) never reach the hand-off branch and therefore always re-sweep,
     * which is the safe direction.
     */
    private fun resetEffectiveCharacterScan() {
        synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.clear() }
        effectiveRosterSearch.reset()
        effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.IDLE
        effectiveCharacterScanRosterStamp = Long.MIN_VALUE
        effectiveCharacterScanScrollActions = 0
        effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
        effectiveCharacterScanSkippedUnsafe = false
        lastEffectiveCharacterScanActionAt = Long.MIN_VALUE
        lastBattleTeamRecommendationKey = null
    }

    /** Owned-roster fingerprint used to decide whether a saved sweep is still valid. */
    private fun currentEffectiveScanRosterStamp(): Long = labyrinthEffectiveScanRosterStamp(
        _state.value.joinedCharacters.map(LabyrinthJoinedCharacter::characterId),
    )

    private fun resetBattleRetryTracking() {
        battleRetryCount = 0
        synchronized(currentBattleTeamSignatures) { currentBattleTeamSignatures.clear() }
        synchronized(failedBattleTeamSignatures) { failedBattleTeamSignatures.clear() }
        // 提交与签名是一体的：只清签名会把两者留在不一致的状态（提交还在、签名没了），下一次失败重试
        // 就再也对不上这一队的失败记录。
        synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
        pendingBattleTeamAction = null
        battleTeamActionUnconfirmedAttempts = 0
        battleTeamActionStopReason = null
    }

    private fun postEntryIsRoleRewardPage(result: LabyrinthEntryFrameResult): Boolean {
        return labyrinthIsRoleRewardPage(result, POST_ENTRY_SELECTION_MIN_SCORE)
    }

    private fun finalSettlementFallbackRect(
        result: LabyrinthEntryFrameResult,
        pageState: LabyrinthEntryPageState,
        frameWidth: Int,
        frameHeight: Int,
    ): EntryPixelRect {
        val center = { LabyrinthFallbackTap.CENTER.rect(frameWidth, frameHeight) }
        val bottomCenter = { LabyrinthFallbackTap.BOTTOM_CENTER.rect(frameWidth, frameHeight) }
        val bottomRight = { LabyrinthFallbackTap.BOTTOM_RIGHT.rect(frameWidth, frameHeight) }
        return when (pageState) {
            LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY ->
                anchorRect(result, EntryAnchorId.RUN_CLEAR_NEXT_BUTTON)
                    ?: bottomRight()

            LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION -> bottomCenter()
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT ->
                anchorRect(result, EntryAnchorId.RUN_CLEAR_CHEST_CONFIRM_BUTTON)
                    ?: labyrinthChestResultFallbackTap().rect(frameWidth, frameHeight)
            LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
            -> center()

            LabyrinthEntryPageState.UNKNOWN -> when (postBossStage) {
                LabyrinthPostBossStage.CHEST_SEQUENCE ->
                    if (postBossUnknownAttempts >= 3) bottomRight() else center()
                LabyrinthPostBossStage.BEFORE_SCORE ->
                    when (postBossUnknownAttempts % 4) {
                        1, 3 -> bottomRight()
                        else -> center()
                    }
                else -> center()
            }

            else -> center()
        }
    }

    private fun dispatchPostEntryTap(
        sessionId: AutomationSessionId,
        kind: LabyrinthPostEntryActionKind,
        label: String,
        rect: EntryPixelRect,
        timestampMillis: Long,
        roleRewardSelectionSignature: String? = null,
        roleRewardSelectedCharacterId: String? = null,
        shopPurchaseRelicId: String? = null,
        shopPurchaseRoleImprintLabel: String? = null,
        eventFreeRoleCandidateId: String? = null,
        eventFreeRoleConfirm: Boolean = false,
        eventFreeRoleConfirmIds: List<String> = emptyList(),
    ) {
        if (!beginGuardedAction()) return
        val selectedReward = (_state.value.roleRewardChoiceDecision as? LabyrinthRoleRewardChoiceDecision.Select)
            ?.takeIf { it.characterId == roleRewardSelectedCharacterId && it.actionSafe }
        val selectedRewardAccountId = activeRunAccountId
        val selectedRewardMatch = _state.value.lastResult?.characterMatches
            ?.firstOrNull { it.characterId == selectedReward?.characterId && it.trusted }
        if (shopPurchaseRelicId != null || shopPurchaseRoleImprintLabel != null) {
            // This is semantic transaction context, not a page-local coordinate. It must survive
            // the brief UNKNOWN transition that can appear between the buy tap and confirmation.
            plannedShopPurchaseRelicId = shopPurchaseRelicId
            plannedShopRoleImprintLabel = shopPurchaseRoleImprintLabel
            plannedShopPurchaseStartedAt = timestampMillis
        }
        traceAction(label)
        resetPostEntryStall()
        postEntryAttempts++
        lastPostEntryActionAt = timestampMillis
        if (kind == LabyrinthPostEntryActionKind.SELECT_EVENT || kind == LabyrinthPostEntryActionKind.ADVANCE_EVENT_ANIMATION) {
            eventActionAttempts++
        }
        if (kind == LabyrinthPostEntryActionKind.ADVANCE_CHARACTER_ACQUISITION) {
            characterAcquisitionClicks++
            lastCharacterAcquisitionActionAt = timestampMillis
        }
        if (kind == LabyrinthPostEntryActionKind.ADVANCE_FINAL_SETTLEMENT) postBossUnknownAttempts++
        launchGuardedAction {
            val executor = actionExecutor
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = rect.left + rect.width / 2f,
                    y = rect.top + rect.height / 2f,
                ),
            )
            val actionResult = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (actionResult) {
                is AutomationActionResult.Executed -> {
                    lastPostEntryActionLandedAt = clock()
                    if (kind == LabyrinthPostEntryActionKind.EVENT_FREE_ROLE_SELECT && eventFreeRoleCandidateId != null) {
                        eventFreeRoleSelectedCharacterIds += eventFreeRoleCandidateId
                        eventFreeRoleLastSelectAt = clock()
                    }
                    if (eventFreeRoleConfirm) {
                        // Whatever opened this picker has now been answered.
                        clearShopChoiceImprintRolePending()
                        val confirmed = eventFreeRoleConfirmIds.ifEmpty { listOfNotNull(eventFreeRoleCandidateId) }
                        if (confirmed.isNotEmpty()) {
                            synchronized(pendingAcquiredCharacterIds) {
                                pendingAcquiredCharacterIds += confirmed
                                pendingAcquiredCharacterId = pendingAcquiredCharacterIds.firstOrNull()
                            }
                        }
                    }
                    if (kind == LabyrinthPostEntryActionKind.SELECT_EVENT) {
                        eventChoiceCommitted = true
                        eventChoiceCommittedAt = clock()
                        eventChoiceCommittedChoiceId =
                            (_state.value.eventChoiceDecision as? LabyrinthEventChoiceDecision.Select)?.choiceId
                    }
                    if (kind == LabyrinthPostEntryActionKind.SELECT_RELIC) {
                        relicChoiceCommitted = true
                    }
                    if (kind == LabyrinthPostEntryActionKind.SELECT_ROLE_REWARD) {
                        roleRewardChoiceCommitted = true
                        committedRoleRewardSelectionSignature = roleRewardSelectionSignature
                        roleRewardBatchActive = true
                        roleRewardSelectedCharacterId?.let { selectedId ->
                                synchronized(pendingAcquiredCharacterIds) {
                                    pendingAcquiredCharacterIds += selectedId
                                    pendingAcquiredCharacterId = pendingAcquiredCharacterIds.firstOrNull()
                                }
                        }
                        selectedReward?.let { selected ->
                            val match = selectedRewardMatch ?: LabyrinthCharacterMatch(
                                slotId = "role_reward_selected", characterId = selected.characterId,
                                displayName = selected.displayName, confidence = ROLE_REWARD_STRONG_ICON_CONFIDENCE,
                                screenRect = rect, trusted = true,
                            )
                            val persisted = runCatching {
                                persistenceMutex.withLock {
                                    runStateStore?.recordPage(
                                        accountId = selectedRewardAccountId,
                                        page = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION.name,
                                        characters = listOf(match), nowMillis = clock(),
                                    )
                                }
                            }.onFailure {
                                val failure = it
                                runCatching {
                                    Log.e("LabyrinthEntry", "Selected role persistence failed; retain joined recognition", failure)
                                }
                            }
                            if (activeSessionId == sessionId) {
                                _state.value = _state.value.copy(
                                    joinedCharacters = persisted.getOrNull()?.joinedCharacters ?: LabyrinthRosterMerger.merge(
                                        _state.value.joinedCharacters, listOf(match), clock(),
                                    ),
                                )
                                skipRoleRewardJoinedRecognition = persisted.isSuccess
                                synchronized(pendingAcquiredCharacterIds) {
                                    pendingAcquiredCharacterIds.remove(selected.characterId)
                                    pendingAcquiredCharacterId = pendingAcquiredCharacterIds.firstOrNull()
                                }
                            }
                        }
                    }
                    if (kind == LabyrinthPostEntryActionKind.SHOP_CONFIRM_IMPRINT) {
                        shopImprintRewardConfirmedAt = clock()
                    }
                    when (kind) {
                        LabyrinthPostEntryActionKind.EX_CLOSE_DETAIL -> {
                            exSlot3ProbePending = false
                            exEncounterProbeStartedAt = Long.MIN_VALUE
                        }
                        LabyrinthPostEntryActionKind.BATTLE_FAILURE_NEXT -> {
                            // "下一步" advances the relic-revive chain, it does not retry: spending
                            // the retry budget or clearing the failed-team books here would book the
                            // run wrongly. Only cancel the battle wait and reset the frame budget,
                            // so the "遗物效果结果" popup that follows is handled as a new page.
                            battleWait.reset()
                            postEntryStableFrames = 0
                            postEntryAttempts = 0
                        }
                        LabyrinthPostEntryActionKind.BATTLE_RETRY,
                        LabyrinthPostEntryActionKind.BATTLE_RETRY_SWITCH_MULTI,
                        -> {
                            synchronized(failedBattleTeamSignatures) {
                                failedBattleTeamSignatures += currentBattleTeamSignatures
                            }
                            synchronized(currentBattleTeamSignatures) {
                                currentBattleTeamSignatures.clear()
                            }
                            synchronized(committedBattleCharacterIds) {
                                committedBattleCharacterIds.clear()
                            }
                            if (pendingSingleBossFallbackToMulti) {
                                bossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM
                                bossFallbackMultiTeamActive = true
                                pendingSingleBossFallbackToMulti = false
                                battleRetryCount = 0
                                synchronized(failedBattleTeamSignatures) { failedBattleTeamSignatures.clear() }
                            } else {
                                battleRetryCount++
                            }
                            battleWait.reset()
                            bossEditorTeamIndex = null
                            pendingBossTeamAdvance = null
                            bossEditorBlocked = false
                            bossEditorPreparationStage = if (combatContext?.kind == LabyrinthCombatKind.BOSS) {
                                LabyrinthBossEditorPreparationStage.TEAM_1
                            } else {
                                LabyrinthBossEditorPreparationStage.INACTIVE
                            }
                            preparedBossFirstTeamIds = emptyList()
                            bossMultiTeamTargetCount = null
                            combatContext = combatContext?.copy(teamIndex = 1)
                            lastBattleTeamRecommendationKey = null
                            lastBattleTeamSelectionPlanLog = null
                            resetBattleTeamExecutionTracking()
                            postEntryStableFrames = 0
                            postEntryAttempts = 0
                        }
                        LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE -> clearPlannedShopPurchase()
                        LabyrinthPostEntryActionKind.SHOP_REFRESH -> {
                            // The refresh button opens a second confirmation dialog.  Keep the
                            // completed-cycle count until that dialog is confirmed and fresh shop
                            // stock is visible again.
                            plannedShopRefreshStartedAt = clock()
                            plannedShopRefreshConfirmedAt = Long.MIN_VALUE
                            postEntryStableFrames = 0
                            postEntryAttempts = 0
                            clearPlannedShopPurchase()
                        }
                        LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH -> {
                            plannedShopRefreshConfirmedAt = clock()
                            postEntryStableFrames = 0
                            postEntryAttempts = 0
                        }
                        LabyrinthPostEntryActionKind.CLOSE_CHARACTER_JOINED -> if (!roleRewardBatchActive) {
                            clearCharacterAcquisitionContext()
                        } else {
                            // More joined popups may follow immediately with the same page state.
                            // Keep the batch/fallback alive and refresh only its progress timeout.
                            characterAcquisitionStartedAt = timestampMillis
                        }
                        LabyrinthPostEntryActionKind.CLOSE_ITEM_REWARD -> if (characterAcquisitionActive && !roleRewardBatchActive) {
                            clearCharacterAcquisitionContext()
                        }
                        LabyrinthPostEntryActionKind.CLOSE_FINAL_SCORE -> {
                            postBossStage = LabyrinthPostBossStage.CHEST_SEQUENCE
                            postBossUnknownAttempts = 0
                        }
                        LabyrinthPostEntryActionKind.CLOSE_FINAL_ITEM -> {
                            postBossStage = LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME
                            postBossStartedAt = timestampMillis
                        }
                        else -> Unit
                    }
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = label,
                            pendingAcquiredCharacterId = pendingAcquiredCharacterId,
                            message = "已执行：$label",
                        )
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (shopPurchaseRelicId != null) clearPlannedShopPurchase()
                    if (kind == LabyrinthPostEntryActionKind.EX_PROBE_DETAIL) {
                        exSlot3ProbePending = false
                        exSlot3ProbeAttempts = (exSlot3ProbeAttempts - 1).coerceAtLeast(0)
                        exEncounterProbeStartedAt = Long.MIN_VALUE
                    }
                    if (actionResult.reason == GAME_NOT_FOREGROUND_REASON) {
                        postEntryAttempts = (postEntryAttempts - 1).coerceAtLeast(0)
                        if (kind == LabyrinthPostEntryActionKind.ADVANCE_CHARACTER_ACQUISITION) {
                            characterAcquisitionClicks = (characterAcquisitionClicks - 1).coerceAtLeast(0)
                        }
                        if (kind == LabyrinthPostEntryActionKind.ADVANCE_FINAL_SETTLEMENT) {
                            postBossUnknownAttempts = (postBossUnknownAttempts - 1).coerceAtLeast(0)
                        }
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("点击被拒绝：${actionResult.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    /** Only the final item popup being closed and the idle maze home being visible completes a run. */
    private fun markRunCleared(sessionId: AutomationSessionId) {
        if (activeSessionId != sessionId) return
        postBossStage = LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME
        val current = _state.value
        val progress = current.routeProgress ?: LabyrinthRouteProgress(
            currentArea = 0,
            visitedCount = 0,
            routeNodeCount = routeNodeCount,
        )
        _state.value = current.copy(routeProgress = progress.copy(complete = true))
        pendingTerminalOutcome = LabyrinthRunTerminalOutcome.CLEARED
        finishFromPlanner(sessionId, "已完成最终结算并返回黎明界主页")
    }

    private fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect? =
        result.anchorMatches[anchorId]
            ?.takeIf { it.score >= POST_ENTRY_ANCHOR_MIN_SCORE }
            ?.rect

    /** 把 1920x1080 参考坐标按当前帧尺寸换算成一个小点击框。 */
    /** The picker has been answered, or the run left the shop without one appearing. */
    private fun clearShopChoiceImprintRolePending() {
        shopChoiceImprintRolePending = false
    }

    private fun resetNodeExecutionState() {
        battleWait.reset()
        eventFreeRoleSelectedCharacterIds.clear()
        eventFreeRoleLastSelectAt = Long.MIN_VALUE
        eventFreeRoleObservedSelectedCount = 0
        resetEventFreeRoleProgress()
        nodeSession = null
        nodeInitStarted.set(false)
        nodeInitFailedReason = null
        finalBossLocalizationStartedAt = Long.MIN_VALUE
        entryPhaseComplete = false
        resetPendingNodeClickStability()
        resetStrongRouteTargetBinding()
        pendingNodeTransition = null
        nodeEntryMismatchFrames = 0
        nodeEntryMismatchPage = null
        confirmedNodeEntryReceipt = null
        nodeTapAttempts = 0
        resetNodeMoveConfirmationTracking()
        nodeScrollAttempts = 0
        resetNodeRecoveryNudgeTracking()
        resetNodeScrollSearchGate()
        lastNodeScrollSourceSignature = null
        nodeViewportScanner.reset()
        lastNodeActionAt = Long.MIN_VALUE
        nodeErrorStreak = 0
        routeNodeCount = 0
        lastPostEntryState = null
        postEntryStableFrames = 0
        postEntryAttempts = 0
        lastPostEntryActionAt = Long.MIN_VALUE
        lastPostEntryActionLandedAt = Long.MIN_VALUE
        postEntryUnknownSince = Long.MIN_VALUE
        actionInFlightSince = Long.MIN_VALUE
        resetPostEntryStall()
        pendingRelicSelection = null
        relicChoiceCommitted = false
        relicFocusMark = null
        relicStackLedger.clear()
        clearCharacterAcquisitionContext()
        clearRoleRewardBatchTracking()
        existingRunResumeHandoffArmed = false
        postBossStage = LabyrinthPostBossStage.NONE
        postBossUnknownAttempts = 0
        postBossStartedAt = Long.MIN_VALUE
        combatContext = null
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        resetBattleTeamExecutionTracking()
        // 会话/账号边界：特别装备轮次与「上一场普通战斗的编组记忆」都属于**这一次运行**的状态。
        // 应用复用同一个会话对象跑下一个账号，不能把上一轮的 stage（可能正停在 DONE，于是新账号的
        // 第一场直接跳过穿戴）、旧轮次的超时起点、或上一场的普通编组记忆带过去。
        // 这条重置只在 start()/stop() 走这里，普通战斗之间不会触发，所以「编组未变则跳过」照常生效。
        resetSpecialEquipmentRound()
        lastNormalBattleTeamSignature = null
        pendingBattleTeamAction = null
        battleTeamActionUnconfirmedAttempts = 0
        battleTeamActionStopReason = null
        battleTeamPageStallSince = Long.MIN_VALUE
        synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
        resetBattleRetryTracking()
        bossEditorTeamIndex = null
        pendingBossTeamAdvance = null
        bossEditorBlocked = false
        bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.INACTIVE
        preparedBossFirstTeamIds = emptyList()
        bossMultiTeamTargetCount = null
        activeNodeType = null
        eventActionAttempts = 0
        _state.value = _state.value.copy(
            combatContext = null,
            battleTeamRecommendation = null,
            battleTeamRecommendationUnavailableReason = null,
            battleTeamSelectionPlan = null,
        )
        resetSessionRecoveryTracking()
    }

    private fun clearCharacterAcquisitionContext() {
        characterAcquisitionActive = false
        characterAcquisitionClicks = 0
        characterAcquisitionStartedAt = Long.MIN_VALUE
        lastCharacterAcquisitionActionAt = Long.MIN_VALUE
    }

    private fun clearRoleRewardBatchTracking() {
        skipRoleRewardJoinedRecognition = false
        roleRewardDecisionCacheKey = null
        roleRewardBatchActive = false
        roleRewardChoiceCommitted = false
        committedRoleRewardSelectionSignature = null
        roleRewardJoinedSequenceStarted = false
        lastObservedRoleRewardSelectionSignature = null
        waitingForManualRoleSelection = false
        synchronized(pendingAcquiredCharacterIds) {
            pendingAcquiredCharacterIds.clear()
            pendingAcquiredCharacterId = null
        }
    }

    private fun handleNodeSelectionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        val visibleNodeRects = result.nodeClassifications.mapNotNull { it.screenRect }
        if (visibleNodeRects.isNotEmpty()) {
            lastNodeSwipeAvoidRects = visibleNodeRects
        }
        val session = nodeSession
        if (session == null) {
            maybeStartNodeInit(sessionId)
            return
        }
        // A reward animation may end on a map frame without a separately recognized close
        // page. Once the node page is back, the bounded portrait fallback must not leak into
        // the next node or keep tapping the map.
        if (characterAcquisitionActive) {
            clearCharacterAcquisitionContext()
        }
        // 关闭角色加入/奖励等弹窗后，地图会经历短暂的过渡动画。此时节点模板可能与
        // 弹窗残影同时命中，立即点击或滚图会把脏帧当成稳定地图。
        if (lastPostEntryActionAt != Long.MIN_VALUE &&
            timestampMillis - lastPostEntryActionAt < NODE_MAP_SETTLE_AFTER_POST_ACTION_MILLIS
        ) {
            val message = "弹窗关闭后等待节点地图稳定"
            if (_state.value.message != message) {
                _state.value = _state.value.copy(message = message)
                overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
            }
            return
        }
        val nodeState = session.getState()
        // Two identities on purpose. The classification-bucketed signature is stable across the
        // map's glow animation and gates "hold the camera N frames before scrolling"; the pixel
        // hash keys the viewport tracker and stays available when no node can be classified.
        val viewportSignature = labyrinthNodeViewportSignature(result.nodeClassifications)
        val trackerSignature = result.nodeViewportSignature.ifBlank { viewportSignature }
        nodeLog(
            "node-frame size=${frameWidth}x${frameHeight} " +
                "area=${nodeState.currentArea} current=${nodeState.currentNodeId} " +
                "visited=${nodeState.visitedNodes.size} " +
                "classes=${describeNodeClassifications(result.nodeClassifications)}",
        )
        pendingNodeTransition?.let { pending ->
            val observedPending = pending.copy(
                nodeSelectionFramesSinceAction = pending.nodeSelectionFramesSinceAction + 1,
            )
            pendingNodeTransition = observedPending
            val waitStartedAt = pending.confirmationDispatchedAtMillis ?: pending.dispatchedAtMillis
            val timeout = if (pending.confirmationDispatchedAtMillis == null) {
                NODE_MOVE_CONFIRMATION_APPEAR_TIMEOUT_MILLIS
            } else {
                NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS
            }
            val elapsedMillis = timestampMillis - waitStartedAt
            if (
                labyrinthKeepsPendingNodeTransition(
                    elapsedMillis = elapsedMillis,
                    timeoutMillis = timeout,
                    nodeSelectionFramesSinceAction = observedPending.nodeSelectionFramesSinceAction,
                )
            ) {
                val message = if (pending.confirmationDispatchedAtMillis == null) {
                    "已点击${pending.label}，等待移动确认弹窗"
                } else {
                    "已确认移动到${pending.label}，等待游戏进入节点"
                }
                if (_state.value.message != message) {
                    _state.value = _state.value.copy(message = message)
                }
                return
            }
            pendingNodeTransition = null
            resetNodeMoveConfirmationTracking()
            resetPendingNodeClickStability()
            if (nodeTapAttempts >= MAX_NODE_TAP_ATTEMPTS) {
                nodeTapAttempts = 0
                requestNodeMapNudge(
                    sessionId = sessionId,
                    label = pending.label,
                    targetBlockId = pending.blockId,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    timestampMillis = timestampMillis,
                    viewportSignature = viewportSignature,
                    avoidRects = lastNodeSwipeAvoidRects,
                    reason = "node-tap-not-confirmed",
                )
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "节点多次点击未被确认；已切换为轻微移动地图后重新定位并重试：${pending.label}")
                }
                return
            }
            nodeLog(
                "node-transition-timeout target=${pending.label} " +
                    "elapsed=${elapsedMillis}ms " +
                    "nodeFrames=${observedPending.nodeSelectionFramesSinceAction} " +
                    "confirmationSent=${pending.confirmationDispatchedAtMillis != null} " +
                    "confirmationAttempts=${pending.confirmationAttempts} " +
                    "tapAttempts=$nodeTapAttempts/$MAX_NODE_TAP_ATTEMPTS " +
                    "page=${result.observation.state.name}",
                warning = true,
            )
            _state.value = _state.value.copy(message = "游戏未进入${pending.label}，准备重试同一节点")
            return
        }
        // The processor skipped this frame's map scan because, when it looked, one of the two
        // gates above was still closed (see nodeScanConsumable). Both gates only open with time,
        // so this frame is normally caught above; the exception is a pending transition cleared
        // by an action coroutine in between. An unscanned frame carries no node evidence and
        // must not be read as "no nodes visible".
        if (result.nodeSearchMode == NODE_SEARCH_MODE_DEFERRED) return
        // Bind any current-frame ordinary-node evidence. The final-Boss-only fast path supplies
        // none: observing that empty mapping also invalidates old camera coordinates after a pan.
        val rawNodeAction = session.processClassifications(result.nodeClassifications)
        val conflictTargetId = (rawNodeAction as? NodeAction.TypeConflict)?.blockId
        val visuallyPlannedAction = nodeConflictRecovery.resolve(
            session = sessionId.toString(),
            action = rawNodeAction,
            visible = result.nodeClassifications,
            uniqueReachableRouteTarget = conflictTargetId != null &&
                session.uniqueReachableRouteTarget()?.blockId == conflictTargetId,
        )
        if (rawNodeAction is NodeAction.TypeConflict && visuallyPlannedAction is NodeAction.ClickNode) {
            nodeLog("node-conflict-recovery route=${visuallyPlannedAction.blockId} generic-route-bound stable=3+ rect=${visuallyPlannedAction.screenRect}")
        }
        session.getLastMatchResult()?.let { match ->
            val snapshot = nodeViewportScanner.observe(
                area = nodeState.currentArea,
                viewportSignature = trackerSignature,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                matchResult = match,
                viewportPixels = result.nodeViewportPixels,
            )
            val target = match.nextNode
            val current = _state.value
            _state.value = current.copy(
                frameTrace = current.frameTrace.copy(
                    nodeTargetBlockId = target?.blockId,
                    nodeTargetColumn = target?.column,
                    nodeTrackerReliable = snapshot?.geometryReliable,
                    nodeTrackerWorldLeft = snapshot?.viewportWorldLeft,
                    nodeTargetExpectedX = target?.let { nodeViewportScanner.expectedScreenCenterX(it.column) },
                ),
            )
        }

        val directFinalBoss = session.directFinalBossTarget()
        val action: NodeAction
        if (directFinalBoss != null) {
            val predictedX = nodeViewportScanner.expectedScreenCenterX(directFinalBoss.column)
            val rect = finalBossPlatformClickRect(
                matches = result.finalBossPlatforms,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                predictedX = predictedX,
            )
            nodeLog(
                "node-direct-final-boss target=Boss#${directFinalBoss.blockId} " +
                    "area=${directFinalBoss.area} column=${directFinalBoss.column} " +
                    "predictedX=${predictedX?.let { "%.1f".format(it) } ?: "n/a"} " +
                    "platforms=${result.finalBossPlatforms} candidates=${result.finalBossPlatformCandidates} rect=$rect",
            )
            if (rect == null) {
                resetPendingNodeClickStability()
                publishRouteProgress(sessionId, session, nextLabel = "Boss#${directFinalBoss.blockId}", nextRect = null)
                if (finalBossLocalizationStartedAt == Long.MIN_VALUE) finalBossLocalizationStartedAt = timestampMillis
                if (!_state.value.dryRun &&
                    timestampMillis - finalBossLocalizationStartedAt >= NODE_TARGET_VISIBLE_MAX_WAIT_MILLIS
                ) {
                    requestNodeMapNudge(
                        sessionId = sessionId,
                        label = "Boss#${directFinalBoss.blockId}",
                        targetBlockId = directFinalBoss.blockId,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        timestampMillis = timestampMillis,
                        viewportSignature = viewportSignature,
                        avoidRects = lastNodeSwipeAvoidRects,
                        reason = "final-boss-platform-not-localized",
                    )
                    finalBossLocalizationStartedAt = timestampMillis
                    return
                }
                _state.value = _state.value.copy(message = "最终Boss底座暂未可靠定位；保持节点页，稍后将轻微左右移动地图重新识别")
                return
            }
            finalBossLocalizationStartedAt = Long.MIN_VALUE
            action = NodeAction.ClickNode(
                blockId = directFinalBoss.blockId,
                blockType = directFinalBoss.blockType,
                screenRect = rect,
            )
        } else {
            finalBossLocalizationStartedAt = Long.MIN_VALUE
            action = visuallyPlannedAction
        }
        if (action !is NodeAction.ClickNode) {
            // "Stable frames" must be consecutive. A conflict/wait frame, or a target crop that
            // disappears, invalidates any click evidence accumulated before it.
            resetPendingNodeClickStability()
        }
        when (action) {
            is NodeAction.ClickNode -> {
                nodeLog(
                    "node-decision target=${LabyrinthNodeTypes.labelOf(action.blockType)}#${action.blockId} " +
                        "rect=${action.screenRect}",
                )
                nodeErrorStreak = 0
                onNodeClickPlanned(
                    sessionId = sessionId,
                    session = session,
                    action = action,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    timestampMillis = timestampMillis,
                    viewportSignature = viewportSignature,
                )
            }

            is NodeAction.TypeConflict -> {
                val expected = LabyrinthNodeTypes.labelOf(action.expectedBlockType)
                val detected = LabyrinthNodeTypes.labelOf(action.detectedBlockType)
                val label = "$expected#${action.blockId}"
                nodeLog(
                    "node-decision TYPE_CONFLICT target=$label detected=$detected " +
                        "confidence=${"%.3f".format(action.confidence)} " +
                        "topology=${action.topologyBindingKind ?: "none"}/" +
                        "${action.topologyConfidence?.let { "%.3f".format(it) } ?: "n/a"} " +
                        "rect=${action.screenRect}",
                    warning = true,
                )
                // Every node type can be recovered when route-bound visual evidence is stable.
                // If the conflict remains ambiguous, actively perturb the background instead of
                // watching the same bad crop forever.
                nodeErrorStreak++
                publishRouteProgress(sessionId, session, nextLabel = label, nextRect = action.screenRect)
                val message = "节点类型待复核：视觉$detected / 路线$label，正在结合路线与当前画面自恢复"
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = message)
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
                if (!_state.value.dryRun && nodeErrorStreak >= NODE_CONFLICT_NUDGE_STREAK) {
                    requestNodeMapNudge(
                        sessionId = sessionId,
                        label = label,
                        targetBlockId = action.blockId,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        timestampMillis = timestampMillis,
                        viewportSignature = viewportSignature,
                        avoidRects = lastNodeSwipeAvoidRects,
                        reason = "persistent-type-conflict",
                    )
                }
            }

            NodeAction.WaitForLoad -> {
                nodeLog("node-decision wait-for-load")
                val waitTarget = session.getLastMatchResult()?.nextNode
                val waitLabel = waitTarget?.let { "${LabyrinthNodeTypes.labelOf(it.blockType)}#${it.blockId}" }
                publishRouteProgress(sessionId, session, nextLabel = waitLabel, nextRect = null)
                nodeErrorStreak++
                if (!_state.value.dryRun && waitTarget != null && nodeErrorStreak >= NODE_WAIT_NUDGE_STREAK) {
                    requestNodeMapNudge(
                        sessionId = sessionId,
                        label = waitLabel ?: "节点#${waitTarget.blockId}",
                        targetBlockId = waitTarget.blockId,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        timestampMillis = timestampMillis,
                        viewportSignature = viewportSignature,
                        avoidRects = lastNodeSwipeAvoidRects,
                        reason = "route-target-visible-but-not-active",
                    )
                } else if (activeSessionId == sessionId && waitTarget != null) {
                    _state.value = _state.value.copy(message = "已定位路线目标但节点尚未稳定激活，继续识别；持续无变化时将轻微移动地图")
                }
            }

            NodeAction.Complete -> {
                publishRouteProgress(sessionId, session, nextLabel = null, nextRect = null, complete = false)
                if (_state.value.dryRun) {
                    _state.value = _state.value.copy(message = "路线节点回放完成")
                } else {
                    // The node graph can finish as soon as the final Boss node is accepted. The
                    // run is not complete yet: Congratulations, score, chest and final item
                    // pages still have to be consumed and the game must return to its idle home.
                    if (postBossStage == LabyrinthPostBossStage.NONE) {
                        postBossStage = LabyrinthPostBossStage.BEFORE_SCORE
                        postBossStartedAt = timestampMillis
                        postBossUnknownAttempts = 0
                    }
                    _state.value = _state.value.copy(message = "路线节点已完成，等待最终结算链")
                }
            }

            is NodeAction.Error -> {
                nodeLog("node-decision error=${action.message}", warning = true)
                nodeErrorStreak++
                if (!_state.value.dryRun && nodeErrorStreak >= MAX_NODE_ERROR_STREAK) {
                    finishFromPlanner(sessionId, "路线执行连续失败：${action.message}")
                } else if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "路线匹配失败：${action.message}")
                }
            }
        }
    }

    private fun maybeStartNodeInit(sessionId: AutomationSessionId) {
        if (!nodeInitStarted.compareAndSet(false, true)) return
        val loadRoute = routeLoader
        val loadTemplates = nodeTemplateLoader
        if (loadRoute == null || loadTemplates == null) {
            nodeInitFailedReason = "节点执行未配置"
            finishFromPlanner(sessionId, nodeInitFailedReason!!)
            return
        }
        val accountId = activeRunAccountId
        actionScope.launch {
            val failure: String? = run {
                val route = validatedRoute ?: runCatching { loadRoute(accountId) }.getOrElse { error ->
                    return@run "读取已保存路线失败：${error.message ?: "未知错误"}"
                } ?: return@run "未找到已保存路线，请先完成刷开局"
                validatedRoute = route
                if (route.allNodes.isEmpty()) {
                    return@run "已保存路线缺少完整地图数据，请重新刷取一次"
                }
                val templates = runCatching(loadTemplates).getOrElse { error ->
                    return@run "加载节点模板失败：${error.message ?: "未知错误"}"
                }
                val session = nodeSessionFactory()
                val routeNodes = route.toLabyrinthRoute().nodes
                val initializationFailure = runCatching {
                    session.initialize(
                        allNodes = route.toFullMap(),
                        route = routeNodes,
                        nodeTemplates = templates,
                        currentNodeId = route.currentBlockId,
                    )
                }.exceptionOrNull()
                if (initializationFailure != null) {
                    return@run "初始化节点会话失败：${initializationFailure.message ?: "未知错误"}"
                }
                routeNodeCount = routeNodes.size
                nodeSession = session
                null
            }
            if (activeSessionId != sessionId) return@launch
            if (failure != null) {
                nodeInitFailedReason = failure
                finishFromPlanner(sessionId, failure)
                return@launch
            } else {
                nodeSession?.let { initialized ->
                    val state = initialized.getState()
                    nodeLog(
                        "route-init area=${state.currentArea} current=${state.currentNodeId} " +
                            "visited=${state.visitedNodes.size} routeNodes=$routeNodeCount",
                    )
                }
                _state.value = _state.value.copy(
                    message = "路线已加载，开始节点${if (_state.value.dryRun) "回放" else "执行"}",
                )
                nodeSession?.let { publishRouteProgress(sessionId, it, nextLabel = null, nextRect = null) }
            }
            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        }
    }

    private fun onNodeClickPlanned(
        sessionId: AutomationSessionId,
        session: LabyrinthNodeSession,
        action: NodeAction.ClickNode,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        viewportSignature: String,
    ) {
        val label = "${LabyrinthNodeTypes.labelOf(action.blockType)}#${action.blockId}"
        publishRouteProgress(sessionId, session, nextLabel = label, nextRect = action.screenRect)
        if (_state.value.dryRun) return

        val rect = action.screenRect
        val targetMapping = session.getLastMatchResult()?.visibleNodes
            ?.filter { it.blockId == action.blockId && it.screenRect == rect }
            ?.maxByOrNull { it.topologyConfidence ?: 0.0 }
        val strongOrdered = targetMapping?.let(::labyrinthNodeMappingHasStrongOrderedTopology) == true
        if (strongRouteTargetBindingBlockId != action.blockId) {
            resetStrongRouteTargetBinding()
        }
        if (
            labyrinthNodeClickContradictsRecentStrongBinding(
                rememberedRect = strongRouteTargetBindingRect,
                rememberedAtMillis = strongRouteTargetBindingAtMillis,
                nowMillis = timestampMillis,
                memoryMillis = NODE_STRONG_BINDING_MEMORY_MILLIS,
                candidateRect = rect,
                candidateHasStrongOrderedTopology = strongOrdered,
            )
        ) {
            resetPendingNodeClickStability()
            val source = when {
                targetMapping?.routeSemanticRepair == true -> "semantic-repair"
                else -> targetMapping?.topologyBindingKind?.name ?: "none"
            }
            nodeLog(
                "node-click-held target=$label reason=weak-rebinding-contradicts-strong-column " +
                    "strongRect=$strongRouteTargetBindingRect candidateRect=$rect source=$source " +
                    "age=${timestampMillis - strongRouteTargetBindingAtMillis}ms",
                warning = true,
            )
            traceReject("node-weak-rebinding")
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "$label 刚以完整列定位，当前帧仅剩单个候选且位置不同；不采信，等待完整列重新出现",
                )
            }
            return
        }
        if (strongOrdered && rect != null) {
            strongRouteTargetBindingBlockId = action.blockId
            strongRouteTargetBindingRect = rect
            strongRouteTargetBindingAtMillis = timestampMillis
        }
        // 同一目标和同一物理位置需连续多帧稳定才允许点击。模板窗口明显跳动时重新计数，
        // 避免把前一帧的正确底座与后一帧落在底栏附近的噪声框累计在一起。
        if (
            pendingNodeClickBlockId == action.blockId &&
            nodeClickRectsAreStable(pendingNodeClickRect, rect)
        ) {
            pendingNodeClickStableFrames++
        } else {
            pendingNodeClickBlockId = action.blockId
            pendingNodeClickRect = rect
            pendingNodeClickStableFrames = 1
        }
        if (pendingNodeClickStableFrames < NODE_CLICK_STABLE_FRAMES) {
            traceReject("node-stable-frames:$pendingNodeClickStableFrames/$NODE_CLICK_STABLE_FRAMES")
            return
        }
        if (lastNodeActionAt != Long.MIN_VALUE &&
            timestampMillis - lastNodeActionAt < NODE_CLICK_INTERVAL_MILLIS
        ) {
            traceReject("node-cooldown:${timestampMillis - lastNodeActionAt}/$NODE_CLICK_INTERVAL_MILLIS")
            return
        }
        val invalidRectReason = when {
            rect == null -> "null"
            rect.left < 0 || rect.top < 0 -> "negative"
            rect.left + rect.width > frameWidth || rect.top + rect.height > frameHeight ->
                "out-of-bounds"
            else -> null
        }
        if (invalidRectReason != null) {
            nodeLog(
                "node-target-invalid target=$label reason=$invalidRectReason rect=$rect " +
                    "frame=${frameWidth}x${frameHeight} viewport=$viewportSignature",
                warning = true,
            )

            if (
                pendingNodeScrollBlockId == action.blockId &&
                pendingNodeScrollViewportSignature == viewportSignature
            ) {
                pendingNodeScrollStableFrames++
            } else {
                pendingNodeScrollBlockId = action.blockId
                pendingNodeScrollViewportSignature = viewportSignature
                pendingNodeScrollStableFrames = 1
                pendingNodeScrollStartedAt = timestampMillis
            }

            val routeTarget = session.getLastMatchResult()?.nextNode
            if (routeTarget != null && nodeViewportScanner.targetLikelyVisible(routeTarget.column)) {
                val elapsed = if (pendingNodeScrollStartedAt == Long.MIN_VALUE) 0L
                    else timestampMillis - pendingNodeScrollStartedAt
                if (
                    pendingNodeScrollStableFrames >= NODE_TARGET_VISIBLE_MAX_WAIT_FRAMES &&
                    elapsed >= NODE_TARGET_VISIBLE_MAX_WAIT_MILLIS
                ) {
                    requestNodeMapNudge(
                        sessionId = sessionId,
                        label = label,
                        targetBlockId = action.blockId,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        timestampMillis = timestampMillis,
                        viewportSignature = viewportSignature,
                        avoidRects = lastNodeSwipeAvoidRects,
                        reason = "target-geometry-visible-but-classification-missing",
                    )
                } else if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "${label}按地图几何应在当前画面，先保持镜头并复识别；持续失败将左右轻微移动制造新背景（" +
                            "$pendingNodeScrollStableFrames/$NODE_TARGET_VISIBLE_MAX_WAIT_FRAMES）",
                    )
                }
                return
            }

            val elapsed = if (pendingNodeScrollStartedAt == Long.MIN_VALUE) 0L
                else timestampMillis - pendingNodeScrollStartedAt
            if (
                pendingNodeScrollStableFrames < NODE_SCROLL_CURRENT_VIEW_STABLE_FRAMES ||
                elapsed < NODE_SCROLL_CURRENT_VIEW_MIN_WAIT_MILLIS
            ) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "暂未找到可点击的$label，优先保持当前地图并继续识别（" +
                            "$pendingNodeScrollStableFrames/$NODE_SCROLL_CURRENT_VIEW_STABLE_FRAMES）",
                    )
                }
                return
            }
            requestNodeMapScroll(
                sessionId,
                label,
                frameWidth,
                frameHeight,
                timestampMillis,
                viewportSignature,
                lastNodeSwipeAvoidRects,
            )
            return
        }
        resetNodeScrollSearchGate()
        val clickRect = rect ?: return
        if (!beginGuardedAction()) return
        traceAction("点击节点 $label")
        lastNodeActionAt = timestampMillis
        launchGuardedAction {
            val executor = actionExecutor
            // Each rejected attempt steps further down the node's own body: repeating the exact
            // pixel the game already refused can only be refused again (2026-09-20 bundle 163307:
            // three taps at 988,350, all rejected, 29 s).
            val (clickX, clickY) = nodeActionPlanner.getBaseClickPosition(
                rect = clickRect,
                retry = nodeTapAttempts,
            )
            nodeLog(
                "node-tap target=$label rect=$clickRect tap=$clickX,$clickY " +
                    "frame=${frameWidth}x${frameHeight} " +
                    "display=${CaptureStateRegistry.gestureDisplayId()} " +
                    "attempt=${nodeTapAttempts + 1}/$MAX_NODE_TAP_ATTEMPTS " +
                    "viewport=$viewportSignature",
            )
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = clickX.toFloat(),
                    y = clickY.toFloat(),
                ),
            )
            val result = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    nodeTapAttempts++
                    val targetArea = session.getLastMatchResult()
                        ?.nextNode
                        ?.takeIf { it.blockId == action.blockId }
                        ?.area
                        ?: _state.value.routeProgress?.currentArea
                    prepareNodeTransitionContext(action.blockType, targetArea)
                    pendingNodeTransition = PendingNodeTransition(
                        blockId = action.blockId,
                        blockType = action.blockType,
                        label = label,
                        // Dense-map recognition can consume most of the timeout before the tap.
                        dispatchedAtMillis = clock(),
                    )
                    resetPendingNodeClickStability()
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "点击节点 $label",
                            combatContext = combatContext,
                            message = "已点击$label，等待移动确认弹窗",
                        )
                        publishRouteProgress(sessionId, session, nextLabel = label, nextRect = rect)
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("节点点击被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    private fun recoverPendingNodeTransitionFromMoveConfirmation(
        sessionId: AutomationSessionId,
        session: LabyrinthNodeSession,
        blockId: Long,
        blockType: Int,
        area: Int,
        timestampMillis: Long,
        confidence: Double,
    ) {
        val label = "${LabyrinthNodeTypes.labelOf(blockType)}#$blockId"
        prepareNodeTransitionContext(blockType, area)
        pendingNodeTransition = PendingNodeTransition(
            blockId = blockId,
            blockType = blockType,
            label = label,
            dispatchedAtMillis = timestampMillis,
        )
        nodeTapAttempts = 0
        resetPendingNodeClickStability()
        resetNodeScrollSearchGate()
        nodeScrollAttempts = 0
        resetNodeRecoveryNudgeTracking()
        lastNodeScrollSourceSignature = null
        nodeLog(
            "node-move-confirm-recover target=$label confidence=${"%.3f".format(confidence)} " +
                "reason=unique-reachable-route-target",
        )
        if (activeSessionId == sessionId) {
            _state.value = _state.value.copy(
                combatContext = combatContext,
                message = "检测到移动确认弹窗，已按唯一可达路线恢复：$label",
            )
            publishRouteProgress(sessionId, session, nextLabel = label, nextRect = null)
            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        }
    }

    private fun prepareNodeTransitionContext(blockType: Int, area: Int?) {
        resetNodeMoveConfirmationTracking()
        nodeEntryMismatchFrames = 0
        nodeEntryMismatchPage = null
        eventFreeRoleSelectedCharacterIds.clear()
        eventFreeRoleLastSelectAt = Long.MIN_VALUE
        eventFreeRoleObservedSelectedCount = 0
        resetEventFreeRoleProgress()
        val previousNodeType = activeNodeType
        activeNodeType = blockType
        activeNodeArea = area
        eventActionAttempts = 0
        if (blockType == LabyrinthNodeTypes.SHOP) {
            // Reset only when entering a new shop node; purchase-animation page churn must not
            // erase this counter, but a recovered movement dialog represents a real new node.
            shopRelicPurchasesThisCycle = 0
            clearPlannedShopRefresh()
        }
        if (blockType == LabyrinthNodeTypes.BOSS) {
            bossTeamMode = configuredBossTeamMode
            pendingSingleBossFallbackToMulti = false
            bossFallbackMultiTeamActive = false
        }
        synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
        clearShopChoiceImprintRolePending()
        resetBattleRetryTracking()
        resetExEncounterTracking()
        // Walking out of an EX fight straight into the Boss of the same encounter used to wipe the
        // "有效效果" sweep that had just completed, so the Boss editor re-swept the whole filtered
        // roster for an identical answer. Keep that sweep across this one hand-off; every other
        // node entry (and any owned-roster change in between) still forces a fresh scan.
        if (!labyrinthKeepsEffectiveScanAcrossNodes(previousNodeType, blockType)) {
            resetEffectiveCharacterScan()
        }
        bossEditorTeamIndex = null
        pendingBossTeamAdvance = null
        bossEditorBlocked = false
        bossEditorPreparationStage = if (blockType == LabyrinthNodeTypes.BOSS) {
            LabyrinthBossEditorPreparationStage.TEAM_1
        } else {
            LabyrinthBossEditorPreparationStage.INACTIVE
        }
        preparedBossFirstTeamIds = emptyList()
        bossMultiTeamTargetCount = null
        combatContext = combatContextFor(blockType)
    }

    private fun handleShopRefreshConfirmationFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        val startedAt = plannedShopRefreshStartedAt
        if (startedAt == Long.MIN_VALUE) return false

        val elapsed = (timestampMillis - startedAt).coerceAtLeast(0L)
        if (elapsed > SHOP_REFRESH_TRANSITION_TIMEOUT_MILLIS) {
            clearPlannedShopRefresh()
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "商店刷新确认超时；未重置本轮遗物计数，等待重新识别商店",
                )
                overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
            }
            return false
        }

        val confirmation = result.nodeMoveConfirmation
        if (
            labyrinthShopRefreshConfirmationOwnsFrame(
                activeNodeType = activeNodeType,
                refreshStartedAt = startedAt,
                refreshConfirmedAt = plannedShopRefreshConfirmedAt,
                now = timestampMillis,
                hasGenericConfirmation = confirmation != null,
                timeoutMillis = SHOP_REFRESH_TRANSITION_TIMEOUT_MILLIS,
            ) && confirmation != null
        ) {
            if (
                lastPostEntryActionAt == Long.MIN_VALUE ||
                timestampMillis - lastPostEntryActionAt >= POST_ENTRY_ACTION_INTERVAL_MILLIS
            ) {
                dispatchPostEntryTap(
                    sessionId = sessionId,
                    kind = LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH,
                    label = "确认刷新商店",
                    rect = confirmation.confirmButtonRect,
                    timestampMillis = timestampMillis,
                )
            } else if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "已识别更新确认，等待安全点击间隔")
                overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
            }
            return true
        }

        if (plannedShopRefreshConfirmedAt != Long.MIN_VALUE) {
            if (
                confirmation == null &&
                labyrinthShopRefreshCanCommit(
                    pageState = result.observation.state,
                    refreshStartedAt = startedAt,
                    refreshConfirmedAt = plannedShopRefreshConfirmedAt,
                    now = timestampMillis,
                    settleMillis = SHOP_REFRESH_SETTLE_MILLIS,
                    timeoutMillis = SHOP_REFRESH_TRANSITION_TIMEOUT_MILLIS,
                )
            ) {
                shopRelicPurchasesThisCycle = 0
                clearPlannedShopRefresh()
                postEntryStableFrames = 0
                postEntryAttempts = 0
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "商店已刷新，等待新商品列表稳定")
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
                return true
            }
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "已确认刷新商店，等待新商品列表返回")
                overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
            }
            return true
        }

        if (activeSessionId == sessionId) {
            _state.value = _state.value.copy(message = "已点击用300更新，等待更新确认弹窗")
            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        }
        return true
    }

    private fun handleNodeMoveConfirmationFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        val confirmation = result.nodeMoveConfirmation ?: run {
            resetNodeMoveConfirmationTracking()
            return false
        }
        val pending = pendingNodeTransition ?: run {
            resetNodeMoveConfirmationTracking()
            publishNodeMoveConfirmationMessage(
                sessionId,
                "检测到移动确认弹窗，但没有待确认的自动节点，请手动处理",
            )
            return true
        }
        if (timestampMillis < pending.dispatchedAtMillis) {
            resetNodeMoveConfirmationTracking()
            publishNodeMoveConfirmationMessage(sessionId, "等待节点点击后的新画面")
            return true
        }
        val rect = confirmation.confirmButtonRect
        if (lastNodeMoveConfirmationRect == rect) {
            nodeMoveConfirmationStableFrames++
        } else {
            lastNodeMoveConfirmationRect = rect
            nodeMoveConfirmationStableFrames = 1
        }
        if (nodeMoveConfirmationStableFrames < NODE_MOVE_CONFIRMATION_STABLE_FRAMES) {
            publishNodeMoveConfirmationMessage(sessionId, "已识别移动确认弹窗，正在确认稳定性")
            return true
        }
        val lastConfirmationAt = pending.confirmationDispatchedAtMillis
        if (
            lastConfirmationAt != null &&
            timestampMillis - lastConfirmationAt < NODE_MOVE_CONFIRMATION_RETRY_INTERVAL_MILLIS
        ) {
            publishNodeMoveConfirmationMessage(sessionId, "已点击移动确认，等待游戏响应")
            return true
        }
        if (pending.confirmationAttempts >= MAX_NODE_MOVE_CONFIRMATION_ATTEMPTS) {
            finishFromPlanner(sessionId, "移动确认按钮多次无响应：${pending.label}")
            return true
        }
        if (!beginGuardedAction()) return true
        dispatchNodeMoveConfirmation(sessionId, pending, rect)
        return true
    }

    private fun dispatchNodeMoveConfirmation(
        sessionId: AutomationSessionId,
        pending: PendingNodeTransition,
        rect: EntryPixelRect,
    ) {
        traceAction("确认移动 ${pending.label}")
        launchGuardedAction {
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = rect.left + rect.width / 2f,
                    y = rect.top + rect.height / 2f,
                ),
            )
            nodeLog(
                "node-move-confirm-tap target=${pending.label} rect=$rect " +
                    "tap=${tap.point.x},${tap.point.y} " +
                    "attempt=${pending.confirmationAttempts + 1}/$MAX_NODE_MOVE_CONFIRMATION_ATTEMPTS " +
                    "display=${CaptureStateRegistry.gestureDisplayId()}",
            )
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    val currentPending = pendingNodeTransition
                    if (currentPending?.blockId == pending.blockId) {
                        pendingNodeTransition = currentPending.copy(
                            confirmationDispatchedAtMillis = clock(),
                            confirmationAttempts = currentPending.confirmationAttempts + 1,
                            nodeSelectionFramesSinceAction = 0,
                        )
                        val confirmed = pendingNodeTransition
                        val sourceId = nodeSession?.getState()?.currentNodeId
                        if (confirmed != null && sourceId != null) {
                            confirmedNodeEntryReceipt = ConfirmedNodeEntryReceipt(confirmed, sourceId)
                        }
                    }
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "确认移动 ${pending.label}",
                            message = "已确认移动到${pending.label}，等待游戏进入节点",
                        )
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("移动确认点击被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    private fun dismissOrphanNodeMoveConfirmation(
        sessionId: AutomationSessionId,
        cancelRect: EntryPixelRect,
        timestampMillis: Long,
    ) {
        if (!beginGuardedAction()) return
        val attempt = orphanMoveConfirmationDismissAttempts + 1
        lastOrphanMoveConfirmationDismissAt = timestampMillis
        traceAction("取消误触的移动确认")
        nodeLog(
            "node-move-confirm-dismiss rect=$cancelRect attempt=$attempt/$MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS",
            warning = true,
        )
        launchGuardedAction {
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = cancelRect.left + cancelRect.width / 2f,
                    y = cancelRect.top + cancelRect.height / 2f,
                ),
            )
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    orphanMoveConfirmationDismissAttempts = attempt
                    orphanMoveConfirmationStableFrames = 0
                    // The map behind the dialog is untouched, but the recovery counters that
                    // accumulated while it was open describe a blocked screen, not the map.
                    resetNodeScrollSearchGate()
                    resetNodeRecoveryNudgeTracking()
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "取消误触的移动确认",
                            message = "已取消非自动触发的移动确认弹窗，继续寻找路线节点",
                        )
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }
                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("取消移动确认被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    private fun publishNodeMoveConfirmationMessage(sessionId: AutomationSessionId, message: String) {
        if (activeSessionId != sessionId || _state.value.message == message) return
        _state.value = _state.value.copy(message = message)
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
    }

    private fun resetNodeMoveConfirmationTracking() {
        nodeMoveConfirmationStableFrames = 0
        lastNodeMoveConfirmationRect = null
    }

    private fun resetOrphanMoveConfirmationTracking() {
        orphanMoveConfirmationStableFrames = 0
        orphanMoveConfirmationDismissAttempts = 0
        orphanMoveConfirmationClearFrames = 0
        lastOrphanMoveConfirmationDismissAt = Long.MIN_VALUE
    }

    private fun publishWaitingForGameForeground(sessionId: AutomationSessionId) {
        if (activeSessionId != sessionId) return
        _state.value = _state.value.copy(message = "游戏不在前台，保持会话并等待返回")
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
    }

    private fun confirmPendingNodeTransition(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
    ) {
        if (!labyrinthConfirmsNodeEntry(pageState)) return
        val session = nodeSession ?: return
        val recoveredReceipt = confirmedNodeEntryReceipt?.takeIf { receipt ->
            labyrinthCanRecoverConfirmedNodeEntry(
                receipt.transition.blockType,
                pageState,
                receipt.sourceId,
                session.getState().currentNodeId,
                session.uniqueReachableRouteTarget()?.blockId == receipt.transition.blockId,
                receipt.transition.confirmationDispatchedAtMillis,
                clock(),
            )
        }
        val pending = pendingNodeTransition ?: recoveredReceipt?.transition ?: return
        if (pendingNodeTransition == null) {
            nodeLog(
                "node-entry-recovered target=${pending.label} page=${pageState.name} " +
                    "source=${recoveredReceipt?.sourceId}",
            )
        }
        if (!labyrinthNodeEntryMatchesExpectedType(pending.blockType, pageState)) {
            // Link and relic pages share their title and remaining-count anchors and differ by a
            // few glyphs in the instruction line; the first frame after entry can classify as the
            // wrong one of the pair (2026-09-15 23:55 bundle: link#20602 read as RELIC_CHOICE for
            // one frame, then LINK_CHOICE). Stopping on that frame left the route cursor behind
            // and the restarted session hunted the already-cleared node. Require agreement
            // across frames before treating a mismatch as real.
            nodeEntryMismatchFrames = if (nodeEntryMismatchPage == pageState) {
                nodeEntryMismatchFrames + 1
            } else {
                1
            }
            nodeEntryMismatchPage = pageState
            nodeLog(
                "node-entry-mismatch target=${pending.label} expected=" +
                    "${LabyrinthNodeTypes.labelOf(pending.blockType)} actual=${pageState.name} " +
                    "frames=$nodeEntryMismatchFrames/$NODE_ENTRY_MISMATCH_STABLE_FRAMES",
                warning = true,
            )
            if (nodeEntryMismatchFrames < NODE_ENTRY_MISMATCH_STABLE_FRAMES) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "节点${pending.label}进入页面识别为${pageState.name}，等待后续帧确认",
                    )
                }
                return
            }
            val observedType = labyrinthObservedDestinationType(pageState, pending.blockType)
            if (observedType == null) {
                // A generic reward or transition may belong to the previous node. Keep
                // observing instead of either advancing an unproven cursor or ending the run.
                _state.value = _state.value.copy(
                    message = "节点${pending.label}与${pageState.name}不符；等待可确认的实际页面",
                )
                return
            }
            nodeLog(
                "node-entry-reconciled target=${pending.label} expected=" +
                    "${LabyrinthNodeTypes.labelOf(pending.blockType)} actual=${pageState.name} " +
                    "observedType=${LabyrinthNodeTypes.labelOf(observedType)}",
                warning = true,
            )
            // The accepted tap proves progression, while the visible page owns the subsequent
            // shop/event/battle handler. Do not keep using the predicted type as runtime state.
            prepareNodeTransitionContext(observedType, activeNodeArea)
            _state.value = _state.value.copy(
                combatContext = combatContext,
                message = "路线预期${pending.label}，实际进入${pageState.name}；已记录差异并按实际页面继续",
            )
        }
        nodeEntryMismatchFrames = 0
        nodeEntryMismatchPage = null
        nodeLog(
            "node-entry-confirmed target=${pending.label} page=${pageState.name} " +
                "confirmationAttempts=${pending.confirmationAttempts} tapAttempts=$nodeTapAttempts",
        )
        session.afterClick(pending.blockId)
        confirmedNodeEntryReceipt = null
        persistNodeRouteProgress(sessionId, session.getState())
        if (!_state.value.dryRun && session.getState().isComplete &&
            activeNodeType in setOf(LabyrinthNodeTypes.BOSS, LabyrinthNodeTypes.EX_BATTLE) &&
            postBossStage == LabyrinthPostBossStage.NONE
        ) {
            // The final node is normally a Boss/EX page, so completion is observed here when
            // the destination page is first recognized—not later when the node matcher is
            // polled again. Keep the live run open for the complete settlement chain.
            postBossStage = LabyrinthPostBossStage.BEFORE_SCORE
            postBossStartedAt = clock()
            postBossUnknownAttempts = 0
        }
        pendingNodeTransition = null
        resetPendingNodeClickStability()
        nodeTapAttempts = 0
        resetNodeMoveConfirmationTracking()
        nodeScrollAttempts = 0
        resetNodeRecoveryNudgeTracking()
        resetNodeScrollSearchGate()
        lastNodeScrollSourceSignature = null
        if (activeSessionId == sessionId) {
            val combatMessage = combatContext?.let { context ->
                when (context.kind) {
                    LabyrinthCombatKind.NORMAL -> "普通战"
                    LabyrinthCombatKind.EX -> "EX战"
                    LabyrinthCombatKind.BOSS -> "Boss第${context.teamIndex}队"
                }
            }
            _state.value = _state.value.copy(
                combatContext = combatContext,
                message = "已确认进入节点：${pending.label}" +
                    (combatMessage?.let { "（$it）" } ?: ""),
            )
            publishRouteProgress(sessionId, session, nextLabel = null, nextRect = null)
            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        }
    }

    /**
     * Keep the saved route cursor aligned with the last destination page that the game actually
     * accepted. The Room store repeats the enterId and monotonic-order checks in its transaction,
     * so delayed writes cannot move a newer session backwards.
     */
    private fun persistNodeRouteProgress(
        sessionId: AutomationSessionId,
        nodeState: NodeSessionState,
    ) {
        if (_state.value.dryRun) return
        val currentBlockId = nodeState.currentNodeId ?: return
        val accountId = activeRunAccountId ?: return
        val route = validatedRoute ?: return
        val saver = routeProgressSaver ?: return
        val advancedRoute = route.withAdvancedCurrentBlock(currentBlockId)
        if (advancedRoute == null) {
            nodeLog(
                "route-progress-rejected-memory enterId=${route.enterId} " +
                    "saved=${route.currentBlockId} requested=$currentBlockId area=${nodeState.currentArea}",
                warning = true,
            )
            return
        }
        validatedRoute = advancedRoute
        actionScope.launch {
            val result = runCatching {
                persistenceMutex.withLock {
                    saver(accountId, advancedRoute.enterId, currentBlockId)
                }
            }
            if (activeSessionId != sessionId) return@launch
            result.fold(
                onSuccess = { persisted ->
                    nodeLog(
                        if (persisted) {
                            "route-progress-persisted account=$accountId enterId=${advancedRoute.enterId} " +
                                "current=$currentBlockId area=${nodeState.currentArea}"
                        } else {
                            "route-progress-persist-rejected account=$accountId " +
                                "enterId=${advancedRoute.enterId} current=$currentBlockId"
                        },
                        warning = !persisted,
                    )
                },
                onFailure = { error ->
                    nodeLog(
                        "route-progress-persist-failed account=$accountId " +
                            "enterId=${advancedRoute.enterId} current=$currentBlockId " +
                            "error=${error.message ?: error::class.java.simpleName}",
                        warning = true,
                    )
                },
            )
        }
    }

    private fun combatContextFor(blockType: Int): LabyrinthCombatContext? = when (blockType) {
        LabyrinthNodeTypes.NORMAL_BATTLE -> LabyrinthCombatContext(LabyrinthCombatKind.NORMAL)
        LabyrinthNodeTypes.EX_BATTLE -> LabyrinthCombatContext(LabyrinthCombatKind.EX)
        LabyrinthNodeTypes.BOSS -> LabyrinthCombatContext(LabyrinthCombatKind.BOSS)
        else -> null
    }

    private fun resetPendingNodeClickStability() {
        pendingNodeClickBlockId = Long.MIN_VALUE
        pendingNodeClickRect = null
        pendingNodeClickStableFrames = 0
    }

    /** Any map gesture or page change may move the camera; strong-column memory is then stale. */
    private fun resetStrongRouteTargetBinding() {
        strongRouteTargetBindingBlockId = Long.MIN_VALUE
        strongRouteTargetBindingRect = null
        strongRouteTargetBindingAtMillis = Long.MIN_VALUE
    }

    private fun resetNodeScrollSearchGate() {
        pendingNodeScrollBlockId = Long.MIN_VALUE
        pendingNodeScrollViewportSignature = null
        pendingNodeScrollStableFrames = 0
        pendingNodeScrollStartedAt = Long.MIN_VALUE
    }

    private fun resetNodeRecoveryNudgeTracking() {
        nodeRecoveryNudgeBlockId = Long.MIN_VALUE
        nodeRecoveryNudgeCount = 0
        nodeConflictRecovery.reset()
    }

    private fun nodeClickRectsAreStable(
        previous: EntryPixelRect?,
        current: EntryPixelRect?,
    ): Boolean {
        return labyrinthNodeClickRectsAreStable(previous, current)
    }

    private fun requestNodeMapNudge(
        sessionId: AutomationSessionId,
        label: String,
        targetBlockId: Long,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        viewportSignature: String,
        avoidRects: List<EntryPixelRect>,
        reason: String,
    ) {
        if (nodeRecoveryNudgeBlockId != targetBlockId) {
            nodeRecoveryNudgeBlockId = targetBlockId
            nodeRecoveryNudgeCount = 0
            nodeSearchRearmCount = 0
            nodeConflictRecovery.reset()
        }
        if (labyrinthNodeRecoveryNudgeExhausted(nodeRecoveryNudgeCount)) {
            // Oscillating in place has provably stopped helping. Give the segmented scan a fresh
            // budget so the camera actually travels; bound the alternation so a genuinely
            // unclassifiable node still reaches a diagnosable stop instead of looping forever.
            nodeRecoveryNudgeCount = 0
            nodeSearchRearmCount++
            nodeLog(
                "node-nudge-exhausted target=$label rearm=$nodeSearchRearmCount/$MAX_NODE_SEARCH_REARMS " +
                    "reason=$reason viewport=$viewportSignature",
                warning = true,
            )
            if (nodeSearchRearmCount > MAX_NODE_SEARCH_REARMS) {
                finishFromPlanner(
                    sessionId,
                    "${label}在完整扫描与局部微调各${MAX_NODE_SEARCH_REARMS}轮后仍无法识别；已停止并保留诊断状态",
                )
                return
            }
            nodeScrollAttempts = 0
            nodeViewportScanner.reset()
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "${label}局部微调无效，重新开始整段地图扫描（第${nodeSearchRearmCount}轮）",
                )
            }
            return
        }
        if (lastNodeActionAt != Long.MIN_VALUE &&
            timestampMillis - lastNodeActionAt < NODE_RECOVERY_NUDGE_INTERVAL_MILLIS
        ) return
        val direction = labyrinthNodeRecoveryNudgeDirection(nodeRecoveryNudgeCount)
        val swipe = labyrinthMapNudge(frameWidth, frameHeight, direction, avoidRects) ?: return
        if (!beginGuardedAction()) return
        val attempt = nodeRecoveryNudgeCount + 1
        lastNodeActionAt = timestampMillis
        resetNodeScrollSearchGate()
        resetPendingNodeClickStability()
        resetStrongRouteTargetBinding()
        nodeConflictRecovery.reset()
        nodeLog(
            "node-nudge-dispatch target=$label attempt=$attempt direction=${direction.name} " +
                "reason=$reason viewport=$viewportSignature swipe=" +
                "${swipe.start.x.toInt()},${swipe.start.y.toInt()}->${swipe.end.x.toInt()},${swipe.end.y.toInt()}",
            warning = true,
        )
        launchGuardedAction {
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(swipe, "动作执行器不可用")
            } else {
                executor.execute(sessionId, swipe)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    nodeRecoveryNudgeCount = attempt
                    nodeErrorStreak = 0
                    if (activeSessionId == sessionId) {
                        val directionLabel = when (direction) {
                            LabyrinthMapScanDirection.FORWARD -> "向右轻移"
                            LabyrinthMapScanDirection.BACKWARD -> "向左轻移"
                        }
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "微调地图重新识别 $label",
                            message = "已${directionLabel}地图制造新背景证据，重新识别$label（第${attempt}次微调）",
                        )
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }
                is AutomationActionResult.Rejected -> {
                    lastNodeActionAt = Long.MIN_VALUE
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        publishWaitingForGameForeground(sessionId)
                    } else if (activeSessionId == sessionId) {
                        _state.value = _state.value.copy(message = "地图微调未执行：${result.reason}；保持节点页并等待重试")
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    private fun requestNodeMapScroll(
        sessionId: AutomationSessionId,
        label: String,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        viewportSignature: String,
        avoidRects: List<EntryPixelRect>,
    ) {
        nodeLog(
            "node-scroll-request target=$label attempt=${nodeScrollAttempts + 1}/$MAX_NODE_SCROLL_ATTEMPTS " +
                "frame=${frameWidth}x${frameHeight} viewport=$viewportSignature " +
                nodeViewportScanner.diagnostics(nodeSession?.getLastMatchResult()?.nextNode?.blockId),
            warning = true,
        )
        val routeTarget = nodeSession?.getLastMatchResult()?.nextNode
        if (routeTarget == null) {
            logNodeScrollTerminalDiagnostics(
                label = label,
                reason = "MISSING_ROUTE_TARGET",
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                viewportSignature = viewportSignature,
            )
            finishFromPlanner(sessionId, "路线缺少待定位节点，无法继续扫描：$label")
            return
        }
        if (nodeScrollAttempts >= MAX_NODE_SCROLL_ATTEMPTS) {
            logNodeScrollTerminalDiagnostics(
                label = label,
                reason = "MAX_ATTEMPTS_SWITCH_TO_LOCAL_RECOVERY",
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                viewportSignature = viewportSignature,
            )
            requestNodeMapNudge(
                sessionId = sessionId,
                label = label,
                targetBlockId = routeTarget.blockId,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = timestampMillis,
                viewportSignature = viewportSignature,
                avoidRects = avoidRects,
                reason = "segmented-scan-budget-exhausted",
            )
            return
        }
        val hasVisualViewport = viewportSignature.isNotBlank()
        if (hasVisualViewport && nodeViewportScanner.awaitingSwipeOutcome()) {
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "已滚动地图，等待视口稳定并判断是否到达边界；暂不连续滑动")
            }
            return
        }
        val scanPlan = if (hasVisualViewport) {
            nodeViewportScanner.plan(
                targetBlockId = routeTarget.blockId,
                targetLogicalColumn = routeTarget.column,
            )
        } else {
            labyrinthBlindMapScanPlan(
                currentNodeId = nodeSession?.getState()?.currentNodeId ?: 0L,
                targetLogicalColumn = routeTarget.column,
                completedAttempts = nodeScrollAttempts,
                reverseAfterAttempts = MAX_NODE_SCROLL_ATTEMPTS / 2,
            )
        }
        if (scanPlan == null) {
            logNodeScrollTerminalDiagnostics(
                label = label,
                reason = "${nodeViewportScanner.exhaustionReason()}:switch-to-local-recovery",
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                viewportSignature = viewportSignature,
            )
            requestNodeMapNudge(
                sessionId = sessionId,
                label = label,
                targetBlockId = routeTarget.blockId,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = timestampMillis,
                viewportSignature = viewportSignature,
                avoidRects = avoidRects,
                reason = nodeViewportScanner.exhaustionReason(),
            )
            return
        }
        if (lastNodeActionAt != Long.MIN_VALUE &&
            timestampMillis - lastNodeActionAt < NODE_CLICK_INTERVAL_MILLIS
        ) {
            return
        }
        val swipe = labyrinthMapSwipe(frameWidth, frameHeight, scanPlan.direction, avoidRects) ?: run {
            finishFromPlanner(sessionId, "地图尺寸无效，无法滚动到下一节点：$label")
            return
        }
        if (!beginGuardedAction()) return
        nodeScrollAttempts++
        resetNodeScrollSearchGate()
        resetStrongRouteTargetBinding()
        lastNodeScrollSourceSignature = viewportSignature
        lastNodeActionAt = timestampMillis
        nodeLog(
            "node-scroll-dispatch target=$label direction=${scanPlan.direction.name} " +
                "reason=${scanPlan.reason} viewport=$viewportSignature " +
                "swipe=${swipe.start.x.toInt()},${swipe.start.y.toInt()}->" +
                "${swipe.end.x.toInt()},${swipe.end.y.toInt()} avoidRects=${avoidRects.size}",
        )
        launchGuardedAction {
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(swipe, "动作执行器不可用")
            } else {
                executor.execute(sessionId, swipe)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    if (hasVisualViewport) {
                        nodeViewportScanner.recordSwipe(scanPlan.direction, nodeViewportScanner.currentSignature())
                    }
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        val directionLabel = when (scanPlan.direction) {
                            LabyrinthMapScanDirection.FORWARD -> "向右"
                            LabyrinthMapScanDirection.BACKWARD -> "向左"
                        }
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "滚动地图寻找 $label",
                            message = "已${directionLabel}搜索地图，等待画面稳定后重新识别下一节点（" +
                                "$nodeScrollAttempts/$MAX_NODE_SCROLL_ATTEMPTS）",
                        )
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        nodeScrollAttempts = (nodeScrollAttempts - 1).coerceAtLeast(0)
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("地图滚动被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    private fun logNodeScrollTerminalDiagnostics(
        label: String,
        reason: String,
        frameWidth: Int,
        frameHeight: Int,
        viewportSignature: String,
    ) {
        val session = nodeSession
        val rawCandidates = session?.getLastClassifications().orEmpty().joinToString(";") { candidate ->
            "${candidate.column}/${candidate.row}:${LabyrinthNodeTypes.labelOf(candidate.blockType)}" +
                "@${"%.2f".format(candidate.confidence)}:" +
                "click=${candidate.isClickable}:" +
                "cyan=${"%.2f".format(candidate.cyanGlowScore)}:" +
                "cyanRows=${"%.2f".format(candidate.cyanGlowRowCoverage)}:" +
                "purple=${"%.2f".format(candidate.purpleGlowScore)}:" +
                "purpleRows=${"%.2f".format(candidate.purpleGlowRowCoverage)}:" +
                "purpleSide=${"%.2f".format(candidate.purpleGlowSideScore)}:" +
                "purpleLower=${"%.2f".format(candidate.purpleGlowLowerScore)}:" +
                "color=${"%.2f".format(candidate.colorRoiScore)}:" +
                "rect=${candidate.screenRect}:template=${candidate.templateId}"
        }.ifBlank { "none" }
        val match = session?.getLastMatchResult()
        val mappings = match?.visibleNodes.orEmpty().joinToString(";") { mapping ->
            "${mapping.blockId}:${LabyrinthNodeTypes.labelOf(mapping.blockType)}:" +
                "rect=${mapping.screenRect}"
        }.ifBlank { "none" }
        val conflicts = match?.conflicts.orEmpty().joinToString(";") { conflict ->
            "${conflict.reason}:${conflict.blockId}:" +
                "expected=${LabyrinthNodeTypes.labelOf(conflict.expectedBlockType)}:" +
                "detected=${LabyrinthNodeTypes.labelOf(conflict.detectedBlockType)}:" +
                "rect=${conflict.screenRect}"
        }.ifBlank { "none" }
        val topology = match?.topologyMappings.orEmpty().joinToString(";") { mapping ->
            "${mapping.blockId}:${LabyrinthNodeTypes.labelOf(mapping.expectedBlockType)}:" +
                "visual=${mapping.visualColumn}/${mapping.visualRow}:" +
                "detected=${LabyrinthNodeTypes.labelOf(mapping.detectedBlockType)}:" +
                "topo=${"%.2f".format(mapping.topologyConfidence)}:" +
                "rect=${mapping.screenRect}"
        }.ifBlank { "none" }
        val topologyColumns = match?.topologyObservedColumns.orEmpty().joinToString(";") { column ->
            "v${column.visualColumn}@${column.centerX}[${column.nodeCount}]->" +
                "c${column.mappedLogicalColumn ?: "?"}"
        }.ifBlank { "none" }
        val routeTarget = match?.nextNode?.let { node ->
            "${node.blockId}:${LabyrinthNodeTypes.labelOf(node.blockType)}"
        } ?: label
        nodeLog(
            "node-scroll-terminal reason=$reason target=$routeTarget " +
                "frame=${frameWidth}x${frameHeight} viewport=$viewportSignature " +
                "columnOffset=${match?.logicalColumnOffset ?: "unknown"} " +
                "topologyScore=${match?.topologyAlignmentScore ?: 0.0} " +
                "scan=${nodeViewportScanner.diagnostics(match?.nextNode?.blockId)} " +
                "topologyColumns=[$topologyColumns] topology=[$topology] " +
                "raw=[$rawCandidates] mapped=[$mappings] conflicts=[$conflicts]",
            warning = true,
        )
    }

    private fun publishRouteProgress(
        sessionId: AutomationSessionId,
        session: LabyrinthNodeSession,
        nextLabel: String?,
        nextRect: EntryPixelRect?,
        complete: Boolean = false,
    ) {
        if (activeSessionId != sessionId) return
        val nodeState = session.getState()
        _state.value = _state.value.copy(
            routeProgress = LabyrinthRouteProgress(
                currentArea = nodeState.currentArea,
                visitedCount = nodeState.visitedNodes.size,
                routeNodeCount = routeNodeCount,
                nextNodeLabel = nextLabel,
                nextNodeRect = nextRect,
                complete = complete || nodeState.isComplete,
            ),
        )
    }

    private fun buildOverlayPresentation(sessionId: AutomationSessionId): AutomationOverlayPresentation {
        val current = _state.value
        val result = current.lastResult
        val pageOwner = result?.observation?.state
            ?.let(::labyrinthPageUiOwner)
            ?: LabyrinthPageUiOwner.NONE
        val detail = buildList {
            if (pageOwner == LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION) {
                if (current.paused) {
                    current.battleTeamSelectionPlan?.overlayLines()?.let(::addAll)
                    current.battleTeamRecommendation?.overlayLines()?.let(::addAll)
                } else {
                    current.battleTeamSelectionPlan?.compactOverlayLines()?.let(::addAll)
                    current.battleTeamRecommendation?.compactOverlayLine()?.let(::add)
                }
                current.battleTeamRecommendationUnavailableReason?.let {
                    add("第一队建议不可用：$it")
                }
            }
            add("原始帧：${current.receivedFrameCount}")
            add("识别帧：${current.frameCount}")
            if (pageOwner == LabyrinthPageUiOwner.NODE_SELECTION) {
                nodeRelicStackSummary(result)?.let { add("地图遗物：$it") }
            }
            if (!current.dryRun) {
                add("动作：${current.actionCount}")
                add("输入显示器：${CaptureStateRegistry.gestureDisplayId()}")
            }
            if (result == null) {
                add("页面：等待首个游戏画面")
                add("捕获：${if (captureActive()) "运行中" else "已停止"}")
                add("帧消费者：${CaptureFrameBus.currentOwner() ?: "无"}")
                if (!captureActive()) {
                    CaptureStateRegistry.lastStopReason()?.let { add("捕获停止原因：$it") }
                }
                current.message?.takeIf(String::isNotBlank)?.let { add("状态：$it") }
            } else {
                add("页面：${result.observation.state.name}")
                add("置信度：${"%.3f".format(result.observation.confidence)}")
                add("耗时：${result.elapsedMillis} ms")
                if (result.observation.state == LabyrinthEntryPageState.UNKNOWN) {
                    val scores = result.observation.anchorScores
                    val nodeHeader = maxOf(
                        scores[EntryAnchorId.NODE_HEADER],
                        scores[EntryAnchorId.NODE_HEADER_STANDARD],
                    )
                    val nodeLeft = maxOf(
                        scores[EntryAnchorId.NODE_CHARACTERS],
                        scores[EntryAnchorId.NODE_CHARACTERS_STANDARD],
                        scores[EntryAnchorId.NODE_RELICS],
                        scores[EntryAnchorId.NODE_RELICS_STANDARD],
                    )
                    val nodeRight = maxOf(
                        scores[EntryAnchorId.NODE_RETREAT],
                        scores[EntryAnchorId.NODE_RETREAT_STANDARD],
                        scores[EntryAnchorId.NODE_RETURN],
                        scores[EntryAnchorId.NODE_RETURN_STANDARD],
                    )
                    val nodeSelectionScore =
                        result.observation.stateScores[LabyrinthEntryPageState.NODE_SELECTION] ?: 0.0
                    val nodeMapViewScore =
                        result.observation.stateScores[LabyrinthEntryPageState.NODE_MAP_VIEW] ?: 0.0
                    add(
                        "节点诊断：选=${"%.2f".format(nodeSelectionScore)} · " +
                            "看=${"%.2f".format(nodeMapViewScore)} · " +
                            "头=${"%.2f".format(nodeHeader)} · 左=${"%.2f".format(nodeLeft)} · " +
                            "右=${"%.2f".format(nodeRight)}",
                    )
                }
                result.matchedFeatures.take(3).takeIf(List<String>::isNotEmpty)?.let {
                    add("特征：${it.joinToString()}")
                }
                result.nodeClassifications
                    .groupingBy { it.blockType }
                    .eachCount()
                    .entries
                    .sortedBy { it.key }
                    .takeIf { it.isNotEmpty() }
                    ?.let { nodes ->
                        add(
                            "节点：" + nodes.joinToString("、") { (type, count) ->
                                "${LabyrinthNodeTypes.labelOf(type)}×$count"
                            },
                        )
                    }
                result.characterMatches
                    .takeIf(List<*>::isNotEmpty)
                    ?.let { characters ->
                        add(
                            "角色：" + characters.joinToString("、") { character ->
                                character.displayName ?: "未收录"
                            },
                        )
                }
                if (pageOwner == LabyrinthPageUiOwner.CHARACTER_SELECTION) {
                    when (val decision = current.roleRewardChoiceDecision) {
                        is LabyrinthRoleRewardChoiceDecision.Select -> {
                            add(
                                "角色三选一建议：${decision.characterId} ${decision.displayName}" +
                                    if (decision.actionSafe) "" else "（仅推荐）",
                            )
                            decision.safetyNote?.let { add("推荐安全状态：$it") }
                        }
                        is LabyrinthRoleRewardChoiceDecision.Wait ->
                            add("角色三选一等待：${decision.reason}")
                        null -> Unit
                    }
                }
                result.battleTeamSelection?.let { team ->
                    if (team.recognitionState == LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY) {
                        add("编组：${team.currentFilter.label} · 角色列表变化，等待稳定")
                    } else {
                        val recognized = team.recognizedCharacterCount
                        val total = team.visibleCharacters.size + team.selectedCharacters.size
                        add(
                            "编组：${team.currentFilter.label} · 角色 $recognized/$total · " +
                                if (team.scrollbar.canScroll) "可滚动" else "滚动条未测得",
                        )
                        team.selectedCharacters
                            .mapNotNull { it.displayName }
                            .takeIf(List<String>::isNotEmpty)
                            ?.let { add("当前队伍：${it.joinToString("、")}") }
                    }
                }
                result.linkChoiceSelection?.let { link ->
                    add(
                        "连结印记：" + link.choices.joinToString("、") { choice ->
                            val rank = choice.priorityRank.takeIf { it != Int.MAX_VALUE }?.let { "#$it" } ?: "#?"
                            "$rank${choice.element.label}(${"%.2f".format(choice.confidence)})"
                        },
                    )
                    add("属性优先级：${link.priority.label}")
                    link.preferredChoice?.let { preferred ->
                        add(
                            "当前建议：${preferred.slotId.removePrefix("link_choice_")}号卡 · " +
                                "${preferred.element.label}（优先${preferred.priorityRank}）",
                        )
                    }
                }
                result.eventChoiceSelection?.let { event ->
                    add(
                        "事件OCR：${event.event.id} · ${"%.2f".format(event.confidence)}" +
                            " / 差${"%.2f".format(event.rivalMargin)} · 稳定${event.stableFrames}" +
                            if (event.trusted) " · 可信" else " · 待确认",
                    )
                    when (val decision = current.eventChoiceDecision) {
                        is LabyrinthEventChoiceDecision.Select -> add(
                            "事件建议：${decision.choiceId} ${decision.label} · ${"%.2f".format(decision.utility)}" +
                                if (decision.actionSafe) " · 可执行" else " · 仅推荐",
                        )
                        is LabyrinthEventChoiceDecision.Wait -> add("事件等待：${decision.reason}")
                        null -> Unit
                    }
                }
                result.relicChoiceSelection?.let { relics ->
                    add(
                        "迷宫遗物：" + relics.choices.joinToString("、") { relic ->
                            val identity = relic.displayName ?: relic.suspectedDisplayName ?: "未确认"
                            val attribute = relic.attribute ?: relic.suspectedAttribute ?: "未知属性"
                            val bonus = relic.attributeBonus
                                ?.takeIf(String::isNotBlank)
                                ?.let { "+$it" }
                                .orEmpty()
                            val prefix = if (relic.recognized) "" else "疑似"
                            val currentStacks = relic.currentMarkStacks?.let { "/当前$it" }.orEmpty()
                            "$prefix$identity[$attribute$bonus$currentStacks](${"%.2f".format(relic.confidence)}/差${"%.2f".format(relic.rivalMargin)})"
                        },
                    )
                    relicChoicePolicy.choose(
                        acquired = current.observedRelics,
                        candidates = relics.choices,
                        calibratedStacks = relicMarkStackCalibration,
                        currentArea = current.routeProgress?.currentArea,
                        lockedFocus = relicFocusMark,
                    )?.let { decision ->
                        add("当前建议：${decision.choice.displayName ?: "未收录"} · ${decision.reason}")
                    }
                }
                result.nodeRelicStackObservation
                    ?.entries
                    ?.takeIf(List<*>::isNotEmpty)
                    ?.let { entries ->
                        add(
                            "地图遗物层数：" + entries.joinToString("、") { entry ->
                                val mark = entry.attribute ?: "未知"
                                val stacks = entry.stacks?.toString() ?: "?"
                                "$mark$stacks"
                            },
                        )
                    }
                result.shopObservation?.let { shop ->
                    val dialog = shop.dialogState.takeIf { it != LabyrinthShopDialogState.NONE }
                        ?.let { " · 弹窗=${it.name}" }
                        .orEmpty()
                    add(
                            "商店：购买位${shop.items.size} · 可购买${shop.availableItemCount} · " +
                                "按钮识别${shop.recognizedButtonCount}${dialog}",
                    )
                    if (shop.dialogState == LabyrinthShopDialogState.NONE) {
                        add(
                            "商店商品：" + shop.items.joinToString("、") { item ->
                                if (item.kind == LabyrinthShopItemKind.ROLE_IMPRINT) {
                                    "${item.roleImprintLabel ?: "职能印记"}(OCR确认)"
                                } else {
                                    val relic = item.relicMatch
                                    val identity = relic?.displayName ?: relic?.suspectedDisplayName ?: "非遗物/未确认"
                                    val prefix = if (relic?.recognized == true) "" else "疑似"
                                    "$prefix$identity(${"%.2f".format(relic?.confidence ?: 0.0)})"
                                }
                            },
                        )
                        currentShopDecision(result)?.let { decision ->
                            add(
                                "商店策略：" + when (decision) {
                                    is LabyrinthShopDecision.BuyRelic ->
                                        "购买${decision.relicDecision.choice.displayName ?: decision.relicDecision.choice.relicId} · " +
                                            decision.relicDecision.reason
                                    is LabyrinthShopDecision.BuyRoleImprint ->
                                        "购买${decision.label} · ${decision.reason}"
                                    is LabyrinthShopDecision.Refresh -> "刷新 · ${decision.reason}"
                                    is LabyrinthShopDecision.Close -> "关闭 · ${decision.reason}"
                                    is LabyrinthShopDecision.Wait -> "等待 · ${decision.reason}"
                                },
                            )
                        }
                    }
                    shop.purchaseCandidate?.let { candidate ->
                        add(
                            "待购买遗物：${candidate.displayName ?: "未收录"} · " +
                                "${candidate.attribute ?: "未知属性"} · " +
                                "${"%.2f".format(candidate.confidence)}",
                        )
                    }
                }
                current.joinedCharacters
                    .takeIf(List<LabyrinthJoinedCharacter>::isNotEmpty)
                    ?.let { characters ->
                        add("已加入：" + characters.joinToString("、") { it.displayName })
                    }
                if (relicMarkStackCalibration.isNotEmpty()) {
                    add("系列记录：" + relicMarkStackCalibration.entries.joinToString("、") { "${it.key.label}${it.value}" })
                }
                if (relicStackLedger.needsAudit) {
                    add("地图数字与记录冲突，保留已确认值；可打开系列效果一览核对，不阻塞路线")
                }
                current.observedRelics
                    .takeIf(List<LabyrinthObservedRelic>::isNotEmpty)
                    ?.let { relics ->
                        add(
                            "已观察遗物：" + relics.joinToString("、") {
                                "${it.displayName}[${it.attribute}]×${it.observationCount}"
                            },
                        )
                    }
                current.pendingAcquiredCharacterId?.let { add("待确认获得角色：$it") }
                current.routeProgress?.let { progress ->
                    add(
                        "路线：区域${progress.currentArea} · 已走${progress.visitedCount}/${progress.routeNodeCount}" +
                            if (progress.complete) " · 已完成" else "",
                    )
                    if (pageOwner == LabyrinthPageUiOwner.NODE_SELECTION) {
                        progress.nextNodeLabel?.let { add("下一节点：$it") }
                    }
                }
            }
        }.joinToString("\n")
        return AutomationOverlayPresentation(
            sessionId = sessionId,
            title = if (current.dryRun) "黎明界入口识别" else "黎明界入口流程",
            status = if (current.paused) {
                "已暂停"
            } else if (
                pageOwner == LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION &&
                current.battleTeamSelectionPlan?.teamReady == true
            ) {
                "第一队已一致 · 准备开始战斗"
            } else if (
                pageOwner == LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION &&
                current.battleTeamSelectionPlan != null
            ) {
                val plan = current.battleTeamSelectionPlan
                "自动编组 · 保持${plan.alreadyCorrectIds.size}/取消${plan.needDeselectIds.size}/选择${plan.needSelectIds.size}"
            } else if (
                pageOwner == LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION &&
                current.battleTeamRecommendation != null
            ) {
                val recommendation = current.battleTeamRecommendation
                "推荐第一队 · ${recommendation.damageTypeLabel} · ${"%.2f".format(recommendation.score)}"
            } else if (
                pageOwner == LabyrinthPageUiOwner.EVENT_CHOICE &&
                current.eventChoiceDecision is LabyrinthEventChoiceDecision.Select
            ) {
                val decision = current.eventChoiceDecision
                if (decision.actionSafe) {
                    "事件${decision.eventId}建议 · ${decision.label}"
                } else {
                    "事件${decision.eventId}推荐 · ${decision.label} · 仅建议"
                }
            } else if (
                pageOwner == LabyrinthPageUiOwner.CHARACTER_SELECTION &&
                current.roleRewardChoiceDecision is LabyrinthRoleRewardChoiceDecision.Select
            ) {
                val decision = current.roleRewardChoiceDecision
                if (decision.actionSafe) {
                    "角色三选一建议 · ${decision.displayName}"
                } else {
                    "角色三选一推荐 · ${decision.displayName} · 仅建议"
                }
            } else if (pageOwner == LabyrinthPageUiOwner.NODE_SELECTION) {
                val stacks = nodeRelicStackSummary(result)
                when {
                    stacks != null && current.dryRun -> "只读运行中 · 遗物 $stacks"
                    stacks != null -> "点击运行中 · 遗物 $stacks"
                    current.dryRun -> "只读运行中 · 遗物识别中"
                    else -> "点击运行中 · 遗物识别中"
                }
            } else if (current.dryRun) {
                "只读运行中"
            } else {
                "点击运行中"
            },
            detail = detail,
            dryRun = current.dryRun,
            paused = current.paused,
            boxes = buildPageOwnedOverlayBoxes(pageOwner, current, result),
            renderBoxesOnDevice = false,
        )
    }

    private fun nodeRelicStackSummary(result: LabyrinthEntryFrameResult?): String? =
        result?.nodeRelicStackObservation
            ?.entries
            ?.takeIf(List<*>::isNotEmpty)
            ?.joinToString(" ") { entry ->
                val mark = entry.attribute ?: "?"
                val stacks = entry.stacks?.toString() ?: "?"
                "$mark$stacks"
            }

    private fun buildPageOwnedOverlayBoxes(
        owner: LabyrinthPageUiOwner,
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> = when (owner) {
        LabyrinthPageUiOwner.NODE_SELECTION ->
            buildNextNodeOverlayBoxes(current, result) + buildPausedNodeDiagnosticsBoxes(current, result)
        LabyrinthPageUiOwner.CHARACTER_SELECTION,
        LabyrinthPageUiOwner.CHARACTER_JOINED,
        -> buildCharacterPageOverlayBoxes(current, result)
        LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION -> buildBattleTeamOverlayBoxes(current, result)
        LabyrinthPageUiOwner.LINK_CHOICE -> buildLinkChoiceOverlayBoxes(result)
        LabyrinthPageUiOwner.RELIC_CHOICE -> buildRelicChoiceOverlayBoxes(result)
        LabyrinthPageUiOwner.SHOP -> buildShopOverlayBoxes(result)
        LabyrinthPageUiOwner.EVENT_CHOICE -> buildEventChoiceOverlayBoxes(current, result)
        else -> emptyList()
    }

    private fun buildEventChoiceOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val event = result?.eventChoiceSelection ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: return emptyList()
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: return emptyList()
        val selectedChoiceId = (current.eventChoiceDecision as? LabyrinthEventChoiceDecision.Select)?.choiceId
        return event.choices.mapNotNull { visual ->
            val rect = visual.buttonRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) return@mapNotNull null
            val selected = visual.choice.id == selectedChoiceId
            AutomationOverlayBox(
                left = left,
                top = top,
                width = width,
                height = height,
                label = buildString {
                    append(if (selected) "推荐 " else "事件 ")
                    append(visual.choice.id)
                    append(' ')
                    append(visual.choice.name)
                    append(" / 蓝${"%.2f".format(visual.buttonConfidence)}")
                    append(" / 亮蓝${"%.2f".format(visual.enabledConfidence)}")
                },
                recognized = event.trusted && visual.enabledConfidence >= 0.35,
                selected = selected,
                labelAbove = true,
            )
        }
    }

    private fun buildCharacterPageOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        result ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        val chosenId = (current.roleRewardChoiceDecision as? LabyrinthRoleRewardChoiceDecision.Select)
            ?.characterId
            ?.let(::canonicalLabyrinthRoleId)
        val roleMatches = result.characterMatches.map { match ->
            CharacterOverlaySource(
                characterId = match.characterId,
                suspectedId = match.suspectedCharacterId,
                displayName = match.displayName ?: match.suspectedDisplayName,
                confidence = match.confidence,
                rivalMargin = match.rivalMargin,
                // The reward page has a stricter irreversible-action gate than the shared icon
                // matcher.  Reflect that in the overlay too: a weak shared "trusted" match must
                // still be shown as tentative until the icon is strong or name OCR confirms it.
                trusted = labyrinthRoleRewardIdentityIsActionSafe(match),
                selected = match.characterId?.let(::canonicalLabyrinthRoleId) == chosenId,
                rect = match.screenRect,
                nameEvidenceText = match.nameEvidenceText,
                nameEvidenceScore = match.nameEvidenceScore,
                nameEvidenceMargin = match.nameEvidenceMargin,
                nameAssisted = match.nameAssisted,
            )
        }
        val openingMatches = result.openingCharacterMatches.map { match ->
            CharacterOverlaySource(
                characterId = match.characterId,
                suspectedId = match.suspectedCharacterId,
                displayName = match.displayName ?: match.suspectedDisplayName,
                confidence = match.confidence,
                rivalMargin = match.rivalMargin,
                trusted = match.trusted,
                selected = match.selected,
                rect = match.screenRect,
            )
        }
        return (roleMatches + openingMatches).mapNotNull { source ->
            val rect = source.rect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) return@mapNotNull null
            val identity = source.displayName ?: source.suspectedId ?: "未识别"
            val nameEvidence = source.nameEvidenceText
                ?.replace("\n", "")
                ?.replace("\r", "")
                ?.take(8)
                ?.takeIf(String::isNotBlank)
                ?.let { text ->
                    " / 字${"%.2f".format(source.nameEvidenceScore)}" +
                        " 差${"%.2f".format(source.nameEvidenceMargin)}[$text]"
                }
                .orEmpty()
            AutomationOverlayBox(
                left = left,
                top = top,
                width = width,
                height = height,
                label = if (source.trusted) {
                    buildString {
                        append(identity)
                        if (source.nameAssisted) append(" ✓字")
                        append(" ${"%.2f".format(source.confidence)} / 差${"%.2f".format(source.rivalMargin)}")
                        append(nameEvidence)
                    }
                } else {
                    "疑似$identity，不点击 ${"%.2f".format(source.confidence)} / 差${"%.2f".format(source.rivalMargin)}$nameEvidence"
                },
                recognized = source.trusted,
                selected = source.selected,
                labelAbove = true,
            )
        }
    }

    private data class CharacterOverlaySource(
        val characterId: String?,
        val suspectedId: String?,
        val displayName: String?,
        val confidence: Double,
        val rivalMargin: Double,
        val trusted: Boolean,
        val selected: Boolean,
        val rect: EntryPixelRect,
        val nameEvidenceText: String? = null,
        val nameEvidenceScore: Double = 0.0,
        val nameAssisted: Boolean = false,
        val nameEvidenceMargin: Double = 0.0,
    )

    /**
     * Show enlarged node diagnostics only after the user pauses the session. The annotation layer
     * is visible to MediaProjection, so drawing these boxes while recognition is running would
     * make the boxes themselves possible node candidates.
     */
    private fun buildPausedNodeDiagnosticsBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        if (!labyrinthShowsPausedNodeDiagnostics(current.paused)) return emptyList()
        if (result?.observation?.state != LabyrinthEntryPageState.NODE_SELECTION) return emptyList()

        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        val matchResult = nodeSession?.getLastMatchResult()
        val boxes = mutableListOf<AutomationOverlayBox>()
        result.nodeClassifications.forEach { classification ->
            val sourceRect = classification.screenRect ?: return@forEach
            val rect = expandNodeDiagnosticsRect(sourceRect, frameWidth, frameHeight) ?: return@forEach
            val mapping = matchResult?.visibleNodes?.firstOrNull { it.screenRect == sourceRect }
            val conflict = matchResult?.conflicts?.firstOrNull { it.screenRect == sourceRect }
            val topology = matchResult?.topologyMappings?.firstOrNull { it.screenRect == sourceRect }
            val mappingLabel = when {
                conflict != null -> " → TYPE_CONFLICT ${conflict.blockId} " +
                    "路线=${LabyrinthNodeTypes.labelOf(conflict.expectedBlockType)}"
                topology != null -> " → 拓扑 ${topology.blockId} " +
                    "${LabyrinthNodeTypes.labelOf(topology.expectedBlockType)} " +
                    "T=${"%.2f".format(topology.topologyConfidence)}"
                mapping != null -> " → ${mapping.blockId} ${LabyrinthNodeTypes.labelOf(mapping.blockType)}"
                else -> " → 未映射"
            }
            toNodeDiagnosticsOverlayBox(
                rect = rect,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                label = "候选 ${classification.column}/${classification.row} 视觉=" +
                    "${LabyrinthNodeTypes.labelOf(classification.blockType)} " +
                    "类别${"%.2f".format(classification.typeConfidence)} " +
                    "结构${"%.2f".format(classification.confidence)}" +
                    (if (classification.isClickable) " 可点" else " 不可点") +
                    mappingLabel,
                recognized = classification.isClickable,
                selected = conflict != null,
            )?.let(boxes::add)
        }

        matchResult?.conflicts
            ?.filter { conflict ->
                conflict.screenRect != null &&
                    result.nodeClassifications.none { it.screenRect == conflict.screenRect }
            }
            ?.forEach { conflict ->
                val sourceRect = conflict.screenRect ?: return@forEach
                val rect = expandNodeDiagnosticsRect(sourceRect, frameWidth, frameHeight) ?: return@forEach
                toNodeDiagnosticsOverlayBox(
                    rect = rect,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    label = "TYPE_CONFLICT ${conflict.blockId} " +
                        "视觉=${LabyrinthNodeTypes.labelOf(conflict.detectedBlockType)} " +
                        "路线=${LabyrinthNodeTypes.labelOf(conflict.expectedBlockType)}",
                    recognized = true,
                    selected = true,
                )?.let(boxes::add)
            }

        val targetRect = current.routeProgress?.nextNodeRect
        val targetLabel = current.routeProgress?.nextNodeLabel
        if (targetRect != null && targetLabel != null) {
            expandNodeDiagnosticsRect(targetRect, frameWidth, frameHeight)?.let { rect ->
                toNodeDiagnosticsOverlayBox(
                    rect = rect,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    label = "目标 $targetLabel",
                    recognized = true,
                    selected = true,
                )?.let(boxes::add)
            }

            val (clickX, clickY) = nodeActionPlanner.getBaseClickPosition(targetRect)
            val markerSize = minOf(DEBUG_CLICK_MARKER_SIZE_PX, frameWidth, frameHeight)
            if (markerSize > 0) {
                val marker = EntryPixelRect(
                    left = (clickX - markerSize / 2).coerceIn(0, frameWidth - markerSize),
                    top = (clickY - markerSize / 2).coerceIn(0, frameHeight - markerSize),
                    width = markerSize,
                    height = markerSize,
                )
                toNodeDiagnosticsOverlayBox(
                    rect = marker,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    label = "点击点 $clickX,$clickY",
                    recognized = true,
                    selected = true,
                )?.let(boxes::add)
            }
        }
        return boxes
    }

    private fun expandNodeDiagnosticsRect(
        rect: EntryPixelRect,
        frameWidth: Int,
        frameHeight: Int,
    ): EntryPixelRect? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val left = (rect.left - DEBUG_NODE_BOX_MARGIN_X_PX).coerceAtLeast(0)
        val top = (rect.top - DEBUG_NODE_BOX_MARGIN_Y_PX).coerceAtLeast(0)
        val right = (rect.left + rect.width + DEBUG_NODE_BOX_MARGIN_X_PX).coerceAtMost(frameWidth)
        val bottom = (rect.top + rect.height + DEBUG_NODE_BOX_MARGIN_Y_PX).coerceAtMost(frameHeight)
        if (right <= left || bottom <= top) return null
        return EntryPixelRect(left, top, right - left, bottom - top)
    }

    private fun toNodeDiagnosticsOverlayBox(
        rect: EntryPixelRect,
        frameWidth: Int,
        frameHeight: Int,
        label: String,
        recognized: Boolean,
        selected: Boolean,
    ): AutomationOverlayBox? {
        val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
        val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
        val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
        val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
        if (width <= 0f || height <= 0f) return null
        return AutomationOverlayBox(
            left = left,
            top = top,
            width = width,
            height = height,
            label = label,
            recognized = recognized,
            selected = selected,
        )
    }

    private fun buildNextNodeOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        // MediaProjection includes this overlay in later frames. During execution a cyan target
        // rectangle would therefore become new node-recognition input and reinforce a bad match.
        if (!labyrinthShowsNextNodeOverlayBox(current.dryRun)) return emptyList()
        val progress = current.routeProgress ?: return emptyList()
        val rect = progress.nextNodeRect ?: return emptyList()
        val label = progress.nextNodeLabel ?: return emptyList()
        val frameWidth = result?.frameWidth?.takeIf { it > 0 } ?: 1920
        val frameHeight = result?.frameHeight?.takeIf { it > 0 } ?: 1080
        val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
        val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
        val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
        val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
        if (width <= 0f || height <= 0f) return emptyList()
        return listOf(
            AutomationOverlayBox(
                left = left,
                top = top,
                width = width,
                height = height,
                label = if (current.dryRun) "将点击：$label" else "下一节点：$label",
                recognized = true,
                selected = true,
            ),
        )
    }

    private fun buildBattleTeamOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val team = result?.battleTeamSelection ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        val plan = current.battleTeamSelectionPlan
        return (team.visibleCharacters + team.selectedCharacters).mapNotNull { character ->
            val rect = character.screenRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) {
                null
            } else {
                val selectTarget = plan?.visibleSelectTargets?.firstOrNull { it.slotId == character.slotId }
                val deselectTarget = plan?.visibleDeselectTargets?.firstOrNull { it.slotId == character.slotId }
                val unsafeTarget = plan?.unsafeVisibleMatches?.firstOrNull { it.slotId == character.slotId }
                AutomationOverlayBox(
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    label = when {
                        selectTarget != null -> "将选择：${selectTarget.displayName}"
                        deselectTarget != null -> "将取消：${deselectTarget.displayName}"
                        unsafeTarget != null -> "低置信度，不点击：${unsafeTarget.displayName}"
                        else -> character.displayName ?: buildString {
                            append("未确认")
                            character.suspectedDisplayName?.let { append("（疑似$it）") }
                            append(" ${"%.2f".format(character.confidence)} / 差${"%.2f".format(character.rivalMargin)}")
                        }
                    },
                    recognized = character.characterId != null,
                    selected = character.selected,
                    labelAbove = true,
                )
            }
        }
    }

    private fun buildLinkChoiceOverlayBoxes(
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val link = result?.linkChoiceSelection ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        return link.choices.mapNotNull { choice ->
            // Show the same lower selection button that the action executor will tap. The card
            // and badge remain available in the recognition result for diagnostics.
            val rect = choice.selectionButtonRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) {
                null
            } else {
                val rank = choice.priorityRank.takeIf { it != Int.MAX_VALUE }?.let { "优先$it" } ?: "未识别"
                AutomationOverlayBox(
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    label = "${choice.element.label} · $rank · ${"%.2f".format(choice.confidence)}",
                    recognized = choice.recognized,
                    selected = false,
                )
            }
        }
    }

    private fun buildRelicChoiceOverlayBoxes(
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val relics = result?.relicChoiceSelection ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        return relics.choices.mapNotNull { relic ->
            val rect = relic.screenRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) {
                null
            } else {
                val identity = relic.displayName ?: relic.suspectedDisplayName ?: "未确认"
                val attribute = relic.attribute ?: relic.suspectedAttribute ?: "未知属性"
                val currentStacks = relic.currentMarkStacks?.let { " · 当前$it" }.orEmpty()
                AutomationOverlayBox(
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    label = if (relic.recognized) {
                        "$identity · $attribute$currentStacks · ${"%.2f".format(relic.confidence)} / 差${"%.2f".format(relic.rivalMargin)}"
                    } else {
                        "疑似$identity · $attribute$currentStacks · ${"%.2f".format(relic.confidence)} / 差${"%.2f".format(relic.rivalMargin)}"
                    },
                    recognized = relic.recognized,
                    selected = false,
                )
            }
        }
    }

    private fun buildShopOverlayBoxes(
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val shop = result?.shopObservation ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        return shop.items.mapNotNull { item ->
            val rect = item.screenRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) {
                null
            } else {
                val status = when (item.status) {
                    LabyrinthShopItemStatus.AVAILABLE -> "可购买"
                    LabyrinthShopItemStatus.DISABLED -> "不可购买/余额不足"
                    LabyrinthShopItemStatus.UNKNOWN -> "按钮未确认"
                }
                val relic = item.relicMatch?.takeIf(LabyrinthRelicMatch::recognized)
                val relicLabel = relic?.displayName?.let { " · $it" }.orEmpty()
                AutomationOverlayBox(
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    label = "${item.slotId.removePrefix("shop_item_")}号商品$relicLabel · $status · " +
                        "模板${"%.2f".format(item.buyButtonScore)} / 可用态${"%.2f".format(item.buyButtonEnabledEvidence)}",
                    recognized = item.purchasable,
                    selected = false,
                )
            }
        }
    }

    private fun recycle(frame: CapturedFrame) {
        if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
    }

    private fun describeNodeClassifications(classifications: List<NodeClassification>): String =
        classifications.joinToString(separator = ";") { classification ->
            val rect = classification.screenRect
            "${classification.column}/${classification.row}:" +
                "${LabyrinthNodeTypes.labelOf(classification.blockType)}" +
                "@${"%.2f".format(classification.confidence)}" +
                ":click=${classification.isClickable}" +
                ":glow=cyan${"%.2f".format(classification.cyanGlowScore)}," +
                "rows${"%.2f".format(classification.cyanGlowRowCoverage)}," +
                "purple${"%.2f".format(classification.purpleGlowScore)}," +
                "purpleRows${"%.2f".format(classification.purpleGlowRowCoverage)}," +
                "purpleSide${"%.2f".format(classification.purpleGlowSideScore)}," +
                "purpleLower${"%.2f".format(classification.purpleGlowLowerScore)}" +
                ":color=${"%.2f".format(classification.colorRoiScore)}" +
                ":rect=${rect?.left},${rect?.top},${rect?.width},${rect?.height}" +
                ":template=${classification.templateId}"
        }.ifBlank { "none" }

    private fun nodeLog(message: String, warning: Boolean = false) {
        runCatching {
            if (warning) Log.w(NODE_LOG_TAG, message) else Log.d(NODE_LOG_TAG, message)
        }
    }

    private companion object {
        const val NODE_LOG_TAG = "LabyrinthNode"
        const val TEAM_RECOMMENDATION_LOG_TAG = "LabyrinthTeamPlan"
        const val TEAM_SELECTION_PLAN_LOG_TAG = "LabyrinthTeamSelection"
        const val OWNER = "labyrinth-entry-recognition"
        const val FRAME_INTERVAL_MILLIS = 500L
        const val FIRST_FRAME_TIMEOUT_MILLIS = 5_000L
        const val NODE_CLICK_STABLE_FRAMES = 3
        /** How long a strong FULL_COLUMN target rect outranks weaker re-bindings without a gesture. */
        const val NODE_STRONG_BINDING_MEMORY_MILLIS = 8_000L
        const val NODE_CLICK_INTERVAL_MILLIS = 2_500L
        const val NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS = 6_000L
        const val NODE_MOVE_CONFIRMATION_APPEAR_TIMEOUT_MILLIS = 6_000L
        const val NODE_MOVE_CONFIRMATION_RETRY_INTERVAL_MILLIS = 2_000L
        const val NODE_MOVE_CONFIRMATION_STABLE_FRAMES = 2
        /** Consecutive destination frames that must disagree with the clicked type before stopping. */
        const val NODE_ENTRY_MISMATCH_STABLE_FRAMES = 3
        const val MAX_NODE_MOVE_CONFIRMATION_ATTEMPTS = 3
        const val MAX_NODE_TAP_ATTEMPTS = 3
        const val MAX_NODE_SCROLL_ATTEMPTS = 6
        const val NODE_CONFLICT_NUDGE_STREAK = 4
        const val NODE_WAIT_NUDGE_STREAK = 8
        const val NODE_RECOVERY_NUDGE_INTERVAL_MILLIS = 1_500L
        /** Full scan+nudge rounds before the node is declared unrecognisable. */
        const val MAX_NODE_SEARCH_REARMS = 3
        const val NODE_SCROLL_CURRENT_VIEW_STABLE_FRAMES = 5
        const val NODE_SCROLL_CURRENT_VIEW_MIN_WAIT_MILLIS = 1_500L
        const val NODE_TARGET_VISIBLE_MAX_WAIT_FRAMES = 12
        const val NODE_TARGET_VISIBLE_MAX_WAIT_MILLIS = 6_000L
        const val MAX_NODE_ERROR_STREAK = 20
        const val POST_ENTRY_STABLE_FRAMES = 2
        const val POST_ENTRY_ACTION_INTERVAL_MILLIS = 1_500L

        const val MAX_EVENT_CHOICE_COMMIT_ATTEMPTS = 3
        const val SHOP_PURCHASE_TRANSITION_TIMEOUT_MILLIS = 8_000L
        const val SHOP_REFRESH_TRANSITION_TIMEOUT_MILLIS = 8_000L
        const val SHOP_REFRESH_SETTLE_MILLIS = 500L
        const val CHARACTER_ACQUISITION_ACTION_INTERVAL_MILLIS = 650L
        const val NODE_MAP_SETTLE_AFTER_POST_ACTION_MILLIS = 2_000L
        const val MAX_POST_ENTRY_ATTEMPTS = 6
        const val BATTLE_TEAM_ACTION_INTERVAL_MILLIS = 1_200L
        const val BATTLE_TEAM_CHARACTER_FEEDBACK_TIMEOUT_MILLIS = 4_000L
        /**
         * 「编组页动作派发后，画面还允许原地不动多久」。
         *
         * 取值 4 秒的依据：必须**大于** [LabyrinthBattleWaitPolicy] 自己的 3 秒「注入后可能还是旧编组页」
         * 宽限，否则一次慢一点的正常切换会被误判成「没被收下」；实机帧率约 600ms 一帧，4 秒保证了宽限
         * 过后至少还有一帧可判；而真正被吞掉的那一下在实机上是十几秒原地不动，4 秒离它很远。
         * 总的可重试次数由 [MAX_BATTLE_TEAM_ACTION_UNCONFIRMED] 兜底。
         */
        const val BATTLE_TEAM_ACTION_ACK_TIMEOUT_MILLIS = 4_000L
        /** 连续多少次「编组页动作派发后画面没动」就停机（每次之间都重新规划并重发，不是静默等待）。 */
        const val MAX_BATTLE_TEAM_ACTION_UNCONFIRMED = 3
        const val MAX_BATTLE_TEAM_STEP_ATTEMPTS = 3
        const val MAX_BATTLE_TEAM_SCROLL_ACTIONS = 48
        const val MAX_EFFECTIVE_SCAN_SCROLL_ACTIONS = 48
        const val EFFECTIVE_SCAN_SCROLL_DURATION_MILLIS = 360L
        const val EFFECTIVE_SCAN_UNSAFE_SETTLE_MILLIS = 1_500L
        val EFFECTIVE_SCAN_CONTEXT_IDS = listOf("__effective-effect-scan__")
        const val EX_ENCOUNTER_OPEN_DETAIL_TIMEOUT_MILLIS = 5_000L
        const val EX_ENCOUNTER_DETAIL_TIMEOUT_MILLIS = 10_000L
        const val EX_ENCOUNTER_SINGLE_NAME_TIMEOUT_MILLIS = 10_000L
        const val MAX_EX_SLOT3_PROBE_ATTEMPTS = 2
        const val MAX_EVENT_ACTIONS = 16
        const val MAX_CHARACTER_ACQUISITION_CLICKS = 40
        const val CHARACTER_ACQUISITION_TIMEOUT_MILLIS = 30_000L
        const val MAX_POST_BOSS_UNKNOWN_ATTEMPTS = 40
        const val FINAL_HOME_RETURN_TIMEOUT_MILLIS = 20_000L
        const val UNKNOWN_POST_ENTRY_TIMEOUT_MILLIS = 30_000L
        const val POST_ENTRY_SELECTION_MIN_SCORE = 0.45
        const val POST_ENTRY_ANCHOR_MIN_SCORE = 0.45
        const val SESSION_BLOCK_STABLE_FRAMES = 2
        const val SESSION_RETURN_TITLE_ACTION_INTERVAL_MILLIS = 1_500L
        const val EVENT_FREE_ROLE_INVITE_DISABLED_MIN_SCORE = 0.72
        const val MAX_SESSION_RETURN_TITLE_ATTEMPTS = 6
        const val DEBUG_NODE_BOX_MARGIN_X_PX = 36
        const val DEBUG_NODE_BOX_MARGIN_Y_PX = 48
        const val DEBUG_CLICK_MARKER_SIZE_PX = 56
    }
}

private const val SESSION_RETURN_TITLE_MIN_SCORE = 0.45
private const val SESSION_DATE_CHANGE_TITLE_MIN_SCORE = 0.72
private const val SESSION_DATE_CHANGE_CONFIRM_MIN_SCORE = 0.55
private const val NODE_VIEWPORT_SIGNATURE_BUCKET_PX = 32

/**
 * 连续多少帧落在非编组页才算「稳定地离开编组页」。
 *
 * 取 2 与 `POST_ENTRY_STABLE_FRAMES` / `SESSION_BLOCK_STABLE_FRAMES` 同量级：弹窗关合动画、Boss 切队、
 * 战斗结算过渡都会插进来一两帧误分类，而 UNKNOWN 根本不累计。
 */
internal const val SPECIAL_EQUIPMENT_LEAVE_STABLE_FRAMES = 2

/** 超限逃生点击（点弹窗1「取消」）之后等弹窗关闭的上限；超期仍未关就停机并保留诊断。 */
internal const val SPECIAL_EQUIPMENT_ESCAPE_TIMEOUT_MILLIS = 8_000L
