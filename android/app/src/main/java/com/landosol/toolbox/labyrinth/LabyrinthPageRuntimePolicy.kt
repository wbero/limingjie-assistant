package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceSize
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthSpecialEquipmentObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthSpecialEquipmentStage
import com.landosol.toolbox.labyrinth.vision.ReferenceFitMapper
import com.landosol.toolbox.labyrinth.vision.labyrinthRoleRewardOwnsInitialSelection
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeSessionState

/** Exactly one page owns page-local actions, plans and overlay boxes for each recognized frame. */
internal enum class LabyrinthPageUiOwner {
    NONE,
    NODE_SELECTION,
    NODE_MAP_VIEW,
    CHARACTER_SELECTION,
    CHARACTER_JOINED,
    BATTLE_TEAM_SELECTION,
    LINK_CHOICE,
    RELIC_CHOICE,
    EVENT_CHOICE,
    SHOP,
    BATTLE,
    RUN_RESULT,
}

/**
 * A movement modal may already be open even though the session lost/never created its pending
 * tap record (for example a manual tap while automation is searching).  Recovery must not turn
 * the generic two-button detector into a broad auto-confirm rule, so require both a very strong
 * modal observation and a route graph with exactly one reachable saved-route successor.
 */
internal fun labyrinthCanRecoverOrphanNodeMoveConfirmation(
    pageState: LabyrinthEntryPageState,
    confirmationConfidence: Double,
    hasUniqueReachableRouteTarget: Boolean,
    shopBackgroundVisible: Boolean = false,
): Boolean {
    // The shop-exit confirmation uses the same blue-title/white-body/two-button chrome as a map
    // move confirmation. During the first transition frame it can classify as UNKNOWN, so page
    // state alone is not enough: visible shop chrome must keep ownership with the shop handler.
    if (shopBackgroundVisible) return false
    if (!hasUniqueReachableRouteTarget || confirmationConfidence < 0.85) return false
    // The shop exit dialog has the same blue-title/white-body/two-button chrome, and while it
    // fades in the page can classify UNKNOWN even though the shop is still on screen.
    // 2026-09-18 live: that frame was adopted as a movement confirmation for the route's only
    // remaining successor (Boss#30701) although no node had ever been tapped; the run then
    // waited for a Boss entry page, saw the shop, and stopped. Dismissal already refuses to act
    // while shop chrome is visible; adoption must refuse too.
    if (shopBackgroundVisible) return false
    return pageState in setOf(
        LabyrinthEntryPageState.NODE_SELECTION,
        LabyrinthEntryPageState.NODE_MAP_VIEW,
        LabyrinthEntryPageState.UNKNOWN,
    )
}

/**
 * An orphan movement modal that cannot be recovered (several reachable successors, or weak
 * evidence) still blocks every map interaction: swipes and node taps land on the dialog and the
 * search loop would otherwise nudge the map forever. The only safe automatic action is to press
 * the dialog's own cancel button, which returns to the untouched map. Dismissal stays bounded so
 * a misdetected dialog cannot turn into an endless tap loop.
 */
internal fun labyrinthShouldDismissOrphanNodeMoveConfirmation(
    pageState: LabyrinthEntryPageState,
    hasPendingNodeTransition: Boolean,
    canRecover: Boolean,
    stableFrames: Int,
    dismissAttempts: Int,
    lastDismissAt: Long,
    now: Long,
    shopBackgroundVisible: Boolean = false,
): Boolean {
    if (hasPendingNodeTransition || canRecover) return false
    // The shop exit dialog has the same blue-title/white-body/two-button chrome and hides the
    // shop title, so the page can classify as UNKNOWN. The shop chrome around it is still
    // visible; that dialog belongs to the shop handler, never to map recovery.
    if (shopBackgroundVisible) return false
    if (pageState !in setOf(
            LabyrinthEntryPageState.NODE_SELECTION,
            LabyrinthEntryPageState.NODE_MAP_VIEW,
            LabyrinthEntryPageState.UNKNOWN,
        )
    ) return false
    if (stableFrames < ORPHAN_MOVE_CONFIRMATION_DISMISS_STABLE_FRAMES) return false
    if (dismissAttempts >= MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS) return false
    return lastDismissAt == Long.MIN_VALUE || now - lastDismissAt >= ORPHAN_MOVE_CONFIRMATION_DISMISS_INTERVAL_MILLIS
}

internal const val ORPHAN_MOVE_CONFIRMATION_DISMISS_STABLE_FRAMES = 2
internal const val MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS = 3
internal const val ORPHAN_MOVE_CONFIRMATION_DISMISS_INTERVAL_MILLIS = 2_000L

/**
 * Whether the dismissal budget may be handed back after the dialog went away.
 *
 * [MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS] exists to stop pressing 取消 at a dialog that
 * refuses to close. It was also, accidentally, a per-run cap: the counter only ever reset when a
 * session error restarted entry navigation, so after three stray dialogs had been cancelled
 * successfully the fourth was never tapped at all and the run stopped with
 * "移动确认弹窗多次取消无效，请手动关闭后继续" without a single cancel tap (2026-09-19 report).
 *
 * A cleared screen is proof the previous cancel worked, so the budget is restored. Proof means
 * several consecutive dialog-free frames: one frame can be a fade-out, and a dialog that flickers
 * must not refill the budget forever.
 */
internal fun labyrinthOrphanMoveConfirmationBudgetRestored(
    clearFrames: Int,
    requiredClearFrames: Int = ORPHAN_MOVE_CONFIRMATION_CLEAR_FRAMES,
): Boolean = clearFrames >= requiredClearFrames

internal const val ORPHAN_MOVE_CONFIRMATION_CLEAR_FRAMES = 3

/**
 * A one-choice event has no strategic ambiguity.  Two independent current-frame anchors are
 * required before exposing its sole button so this cannot turn an unrelated UNKNOWN/map frame
 * into a blind click.
 */
internal fun labyrinthSingleChoiceEventButtonRect(
    result: LabyrinthEntryFrameResult,
    minimumScore: Double = 0.72,
): EntryPixelRect? {
    if (result.observation.state != LabyrinthEntryPageState.EVENT_CHOICE) return null
    val scores = result.observation.anchorScores
    if (scores[EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE] < minimumScore) return null
    val match = result.anchorMatches[EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON] ?: return null
    return match.rect.takeIf { match.score >= minimumScore }
}

/**
 * Direct single-target EX name OCR is intentionally given a short chance first.  If it still
 * cannot identify the encounter, opening the fixed monster Details button is a non-destructive
 * way to move identity OCR onto the much more stable detail-modal name row.  Multi-monster and
 * special-dual EX use their own structurally detected info buttons and never enter this path.
 */
/**
 * The EX identity probe (name OCR, then the bounded 详情 fallback) is budgeted per *visit* to the
 * challenge page, not per run. 2026-09-17 live (bundle 152039): the EX challenge page was first
 * seen at 15:13:58, the battle was lost, and 重新挑战 brought the page back at 15:16:05; the
 * probe clock still held the first visit, so the 10 s budget was already spent and the run
 * stopped 0.4 s later with "未建立可信身份" before OCR could read 好朋友X even once.
 * UNKNOWN frames (animations, the 详情 modal) never count as leaving the page.
 */
internal fun labyrinthExIdentityProbeRestarts(
    lastKnownPage: LabyrinthEntryPageState?,
    page: LabyrinthEntryPageState,
    encounterResolved: Boolean,
): Boolean =
    page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
        !encounterResolved &&
        lastKnownPage != null &&
        lastKnownPage != LabyrinthEntryPageState.BATTLE_CHALLENGE

internal fun labyrinthSingleExDetailProbeRect(
    pageState: LabyrinthEntryPageState,
    isEx: Boolean,
    encounterResolved: Boolean,
    multiMonsterLikely: Boolean,
    specialDualLikely: Boolean,
    elapsedMillis: Long,
    stableFrames: Int,
    attempts: Int,
    frameWidth: Int,
    frameHeight: Int,
    minimumWaitMillis: Long = 3_000L,
    minimumStableFrames: Int = 2,
    maxAttempts: Int = 2,
): EntryPixelRect? {
    if (pageState != LabyrinthEntryPageState.BATTLE_CHALLENGE || !isEx || encounterResolved) return null
    if (multiMonsterLikely || specialDualLikely) return null
    if (elapsedMillis < minimumWaitMillis || stableFrames < minimumStableFrames || attempts >= maxAttempts) return null
    if (frameWidth <= 0 || frameHeight <= 0) return null
    return ReferenceFitMapper.map(
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        referenceSize = EntryReferenceSize(1920, 1080),
        referenceRect = EntryReferenceRect(1395, 590, 155, 88),
    )
}

/**
 * Final safety gate for the challenge button.
 *
 * Challenge-title OCR is asynchronous. Without this gate an EVENT -> EX transition can expose a
 * BATTLE_CHALLENGE frame before "极难" has returned from OCR, allowing the generic challenge
 * branch to tap first. Boss nodes are already semantically identified by the route. Every other
 * challenge must either be positively classified as a non-EX title, or have a concrete EX guide
 * resolved. An automation-owned EX details probe may also explicitly approve a guide-less
 * fallback after it has closed the modal; this remains distinct from a generic unknown page.
 */
internal fun labyrinthBattleChallengeCanAutoStart(
    combatContext: LabyrinthCombatContext?,
    challengeDifficultyResolved: Boolean,
    extremeChallenge: Boolean,
    exEncounterResolved: Boolean,
    exGuideFallbackApproved: Boolean = false,
): Boolean = when {
    combatContext?.kind == LabyrinthCombatKind.BOSS -> true
    combatContext?.kind == LabyrinthCombatKind.EX || extremeChallenge ->
        exEncounterResolved || exGuideFallbackApproved
    !challengeDifficultyResolved -> false
    else -> true
}

internal fun labyrinthPageUiOwner(page: LabyrinthEntryPageState): LabyrinthPageUiOwner = when (page) {
    LabyrinthEntryPageState.NODE_SELECTION -> LabyrinthPageUiOwner.NODE_SELECTION
    LabyrinthEntryPageState.NODE_MAP_VIEW -> LabyrinthPageUiOwner.NODE_MAP_VIEW
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> LabyrinthPageUiOwner.CHARACTER_SELECTION
    LabyrinthEntryPageState.CHARACTER_JOINED -> LabyrinthPageUiOwner.CHARACTER_JOINED
    LabyrinthEntryPageState.BATTLE_TEAM_SELECTION -> LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION
    LabyrinthEntryPageState.LINK_CHOICE -> LabyrinthPageUiOwner.LINK_CHOICE
    LabyrinthEntryPageState.RELIC_CHOICE -> LabyrinthPageUiOwner.RELIC_CHOICE
    LabyrinthEntryPageState.EVENT_CHOICE,
    LabyrinthEntryPageState.EVENT_ANIMATION,
    -> LabyrinthPageUiOwner.EVENT_CHOICE
    LabyrinthEntryPageState.SHOP,
    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
    LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
    -> LabyrinthPageUiOwner.SHOP
    LabyrinthEntryPageState.BATTLE_CHALLENGE,
    LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
    LabyrinthEntryPageState.BATTLE_FAILED,
    LabyrinthEntryPageState.BATTLE_RESULT,
    -> LabyrinthPageUiOwner.BATTLE
    LabyrinthEntryPageState.RUN_CLEAR_RESULT,
    LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
    LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
    LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
    -> LabyrinthPageUiOwner.RUN_RESULT
    else -> LabyrinthPageUiOwner.NONE
}

/**
 * The movement-confirmation detector intentionally looks for a generic two-button modal and can
 * also fire on unrelated dialogs such as SHOP_EXIT_CONFIRMATION. Page ownership must therefore
 * gate it before it is allowed to pre-empt the page-local action handler.
 *
 * A pending node transition may temporarily obscure the map enough for the page classifier to
 * return UNKNOWN, so keep that one bounded exception. Without a pending transition the generic
 * colour/shape detector has no semantic ownership at all: ordinary node-map controls can satisfy
 * its blue/light coverage test and would otherwise surface a false "manual confirmation" block.
 */
internal fun labyrinthNodeMoveConfirmationOwnsFrame(
    pageState: LabyrinthEntryPageState,
    hasPendingNodeTransition: Boolean,
): Boolean {
    if (!hasPendingNodeTransition) return false
    return when (pageState) {
    LabyrinthEntryPageState.NODE_SELECTION,
    LabyrinthEntryPageState.NODE_MAP_VIEW,
    LabyrinthEntryPageState.UNKNOWN,
    -> true
    else -> false
    }
}

/**
 * Page-local state invalidation normally clears a pending map-node transition as soon as the
 * classifier leaves NODE_SELECTION.  A real movement-confirmation modal can itself make the page
 * classify as NODE_MAP_VIEW or UNKNOWN, though, so preserve the pending semantic transition for
 * that one frame family.  The generic two-button detector is not sufficient on its own: ownership
 * still requires an already-dispatched programmatic node transition and a map-compatible page.
 */
internal fun labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
    pageState: LabyrinthEntryPageState,
    hasPendingNodeTransition: Boolean,
    hasMoveConfirmation: Boolean,
): Boolean = hasMoveConfirmation && labyrinthNodeMoveConfirmationOwnsFrame(
    pageState = pageState,
    hasPendingNodeTransition = hasPendingNodeTransition,
)

/**
 * A programmatic shop purchase may render one or two UNKNOWN transition frames before the
 * confirmation modal becomes recognizable. Keep only the semantic relic-id transaction across
 * that bounded transition; page-local coordinates still remain invalidated normally.
 */
internal fun labyrinthKeepsPlannedShopPurchaseAcrossPage(
    pageState: LabyrinthEntryPageState,
    plannedRelicId: String?,
    startedAt: Long,
    now: Long,
    timeoutMillis: Long,
): Boolean {
    if (pageState != LabyrinthEntryPageState.UNKNOWN) return false
    if (plannedRelicId.isNullOrBlank() || startedAt == Long.MIN_VALUE) return false
    return (now - startedAt).coerceAtLeast(0L) <= timeoutMillis
}

internal fun labyrinthShopRefreshConfirmationOwnsFrame(
    activeNodeType: Int?,
    refreshStartedAt: Long,
    refreshConfirmedAt: Long,
    now: Long,
    hasGenericConfirmation: Boolean,
    timeoutMillis: Long,
): Boolean {
    if (activeNodeType != LabyrinthNodeTypes.SHOP) return false
    if (!hasGenericConfirmation || refreshStartedAt == Long.MIN_VALUE) return false
    if (refreshConfirmedAt != Long.MIN_VALUE) return false
    return (now - refreshStartedAt).coerceAtLeast(0L) <= timeoutMillis
}

internal fun labyrinthShopRefreshCanCommit(
    pageState: LabyrinthEntryPageState,
    refreshStartedAt: Long,
    refreshConfirmedAt: Long,
    now: Long,
    settleMillis: Long,
    timeoutMillis: Long,
): Boolean {
    if (pageState != LabyrinthEntryPageState.SHOP) return false
    if (refreshStartedAt == Long.MIN_VALUE || refreshConfirmedAt == Long.MIN_VALUE) return false
    val sinceStart = (now - refreshStartedAt).coerceAtLeast(0L)
    val sinceConfirm = (now - refreshConfirmedAt).coerceAtLeast(0L)
    return sinceStart <= timeoutMillis && sinceConfirm >= settleMillis
}

/**
 * Purchase/refresh animation frames can resemble the broad reconnect-title template while the
 * shop itself is temporarily classified UNKNOWN. Suppress only that short, semantically owned
 * transition; a genuine session error remains actionable again as soon as the guard expires.
 */
internal fun labyrinthShopTransitionSuppressesSessionBlock(
    pageState: LabyrinthEntryPageState,
    previousPageState: LabyrinthEntryPageState?,
    activeNodeType: Int?,
    lastActionAt: Long,
    now: Long,
    guardMillis: Long = 3_000L,
): Boolean {
    if (pageState != LabyrinthEntryPageState.UNKNOWN) return false
    if (activeNodeType != LabyrinthNodeTypes.SHOP) return false
    if (labyrinthPageUiOwner(previousPageState ?: LabyrinthEntryPageState.UNKNOWN) != LabyrinthPageUiOwner.SHOP) {
        return false
    }
    if (lastActionAt == Long.MIN_VALUE) return false
    return (now - lastActionAt).coerceAtLeast(0L) <= guardMillis
}

/**
 * Intermediate Boss victories (for example area 3 -> area 4) use a special WIN/result chain
 * that is not the final run-clear flow. On the current client those pages can be classified as
 * UNKNOWN while still exposing the same fixed bottom-right "下一步" button used by the ordinary
 * battle result page.
 *
 * This is deliberately narrower than a generic UNKNOWN fallback: only an active Boss combat
 * context may use it, and only a current-frame button match above the Boss-specific safety line
 * is accepted. Normal/EX battles and unrelated UNKNOWN pages keep their existing behavior.
 */
internal fun labyrinthBossSettlementNextButtonRect(
    pageState: LabyrinthEntryPageState,
    combatContext: LabyrinthCombatContext?,
    nextButtonMatch: com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch?,
    bossSummaryNextButtonMatch: com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch? = null,
    minimumScore: Double = 0.68,
): com.landosol.toolbox.labyrinth.vision.EntryPixelRect? {
    if (pageState != LabyrinthEntryPageState.UNKNOWN) return null
    if (combatContext?.kind != LabyrinthCombatKind.BOSS) return null
    val match = listOfNotNull(nextButtonMatch, bossSummaryNextButtonMatch)
        .maxByOrNull(com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch::score)
        ?: return null
    return match.rect.takeIf { match.score >= minimumScore }
}

internal fun labyrinthIsRoleRewardPage(
    result: LabyrinthEntryFrameResult,
    minimumAnchorScore: Double = ROLE_PAGE_ANCHOR_MIN_SCORE,
): Boolean = result.observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
    labyrinthRoleRewardOwnsInitialSelection(
        anchorScores = result.observation.anchorScores,
        minimumScore = minimumAnchorScore,
    )

/**
 * A role-reward batch can present several selection pages back-to-back without changing the
 * page enum. Use the candidate identities, not the page state, to tell one selection round from
 * the next. Scores are intentionally excluded so recognition jitter cannot create a fake round.
 */
internal fun labyrinthRoleRewardSelectionSignature(
    result: LabyrinthEntryFrameResult,
): String? {
    if (!labyrinthIsRoleRewardPage(result)) return null
    if (result.characterMatches.isEmpty()) return null
    return result.characterMatches
        .sortedBy { it.slotId }
        .joinToString("|") { match ->
            val identity = match.characterId ?: match.suspectedCharacterId ?: "?"
            "${match.slotId}:$identity"
        }
}

/**
 * Boss/event settlements may first show several role-choice pages and only afterwards show all
 * corresponding CHARACTER_JOINED popups. Keep that whole sequence as one reward batch. An
 * explicit non-reward page is the terminal boundary; UNKNOWN is allowed only inside the already
 * armed batch so transient portrait/transition frames can still be advanced safely.
 */
internal fun labyrinthKeepsRoleRewardBatchOnPage(
    pageState: LabyrinthEntryPageState,
    isRoleRewardPage: Boolean,
): Boolean = when (pageState) {
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> isRoleRewardPage
    LabyrinthEntryPageState.UNKNOWN,
    LabyrinthEntryPageState.EVENT_ANIMATION,
    LabyrinthEntryPageState.CHARACTER_JOINED,
    LabyrinthEntryPageState.ITEM_REWARD,
    -> true
    else -> false
}

/**
 * After resuming an already-active Dawn Realm run, the game may reopen directly on a pending
 * role-reward page instead of the node map. Those pages belong to route execution rather than
 * the opening invitation flow. Keep this deliberately narrow so a fresh run still retains the
 * original CHARACTER_JOINED opening-safety check.
 */
/** Two or more fixed shop chrome anchors still visible: a dialog is open on top of the shop. */
internal fun labyrinthShopBackgroundVisible(result: LabyrinthEntryFrameResult): Boolean =
    listOf(EntryAnchorId.SHOP_TITLE, EntryAnchorId.SHOP_INSTRUCTION,
        EntryAnchorId.SHOP_REFRESH_BUTTON, EntryAnchorId.SHOP_CLOSE)
        .count { result.observation.anchorScores[it] >= 0.65 } >= 2

internal fun labyrinthShopJoinedRewardOwnsRoute(
    result: LabyrinthEntryFrameResult,
    purchaseConfirmedAt: Long,
    now: Long,
): Boolean {
    if (result.observation.state != LabyrinthEntryPageState.CHARACTER_JOINED) return false
    val recentPurchase = purchaseConfirmedAt != Long.MIN_VALUE && now - purchaseConfirmedAt in 0..120_000
    return recentPurchase || labyrinthShopBackgroundVisible(result)
}

internal fun labyrinthResumedRunRewardPageOwnsRoute(
    result: LabyrinthEntryFrameResult,
): Boolean = when (result.observation.state) {
    LabyrinthEntryPageState.CHARACTER_JOINED -> true
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> labyrinthIsRoleRewardPage(result)
    else -> false
}

/** Creating a route session on the first map glimpse does not prove opening selection is done. */
internal fun labyrinthRouteHasPassedOpening(
    state: NodeSessionState?,
    // A dispatched node move is already beyond opening, even before its destination confirms
    // the persisted cursor. This also protects a picker opened by the very first event node.
    hasNodeTransition: Boolean = false,
): Boolean = hasNodeTransition ||
    (state != null && (state.currentArea > 1 || state.visitedNodes.size > 1))

/** A real opening roster must reclaim the entry flow even after a premature map handoff. */
internal fun labyrinthShouldResumeOpeningSelection(
    entryPhaseComplete: Boolean,
    result: LabyrinthEntryFrameResult,
    /**
     * Whether the route has progressed beyond the first area's start. The opening selector, a shop 选择印记 pick and an
     * event free pick are the *same* page with the same header, so the header alone cannot tell
     * them apart -- and only the first of the three belongs to the entry planner.
     *
     * 2026-09-22 bundle 173753: a mid-route imprint picker matched this predicate, the run reset
     * itself to the entry phase, and the entry planner then stared at a picker it has no rule for
     * while nothing else could own the frame. 599 frames / 408 s, actionCount frozen at 379.
     */
    routeActive: Boolean = false,
): Boolean = entryPhaseComplete &&
    !routeActive &&
    result.observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
    !labyrinthIsRoleRewardPage(result) &&
    maxOf(
        result.observation.anchorScores[EntryAnchorId.SELECTION_HEADER],
        result.observation.anchorScores[EntryAnchorId.SELECTION_HEADER_STANDARD],
    ) >= 0.85

/**
 * Returns only positive, trusted ownership evidence. Absence from a later viewport is never
 * represented here and therefore can never delete a previously known role.
 */
internal fun labyrinthRosterReconciliationMatches(
    result: LabyrinthEntryFrameResult,
): List<LabyrinthCharacterMatch> = when (result.observation.state) {
    LabyrinthEntryPageState.CHARACTER_JOINED -> result.characterMatches
        .filter { it.trusted && it.characterId != null && it.displayName != null }

    // The battle editor is an observation of the already-owned roster, not an acquisition
    // event. Appending its transient icon guesses permanently polluted the saved roster whenever
    // a seasonal variant briefly matched the wrong candidate.
    LabyrinthEntryPageState.BATTLE_TEAM_SELECTION -> emptyList()

    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> {
        if (labyrinthIsRoleRewardPage(result) || !openingRosterIsConfirmed(result)) {
            emptyList()
        } else {
            result.openingCharacterMatches
                .filter {
                    it.selected && it.trusted && it.characterId != null && it.displayName != null
                }
                .map(::toRosterMatch)
                .distinctBy { match -> canonicalLabyrinthRoleId(requireNotNull(match.characterId)) }
                .takeIf { it.size == REQUIRED_OPENING_ROSTER_SIZE }
                .orEmpty()
        }
    }

    else -> emptyList()
}
    .distinctBy { match -> canonicalLabyrinthRoleId(requireNotNull(match.characterId)) }

/**
 * Roster evidence for the guild's opening grant. The confirmed three picks replace the roster;
 * the granted character rides along so the run never plans without it. Only the guild is
 * needed: the grant is unconditional and its 角色加入 popup may not be recognised.
 */
internal fun labyrinthGuildGrantedRosterMatches(
    guildId: Int?,
    frameRect: EntryPixelRect,
): List<LabyrinthCharacterMatch> = LabyrinthOpeningRosterCatalog.grantedCharactersFor(guildId).map { granted ->
    LabyrinthCharacterMatch(
        slotId = "guild_grant_${granted.characterId}",
        characterId = granted.characterId,
        displayName = granted.displayName,
        confidence = 1.0,
        screenRect = frameRect,
        trusted = true,
    )
}

/**
 * A plain one-button 确认 dialog over the map: blue title, white body, one pale 确认 button
 * bottom-centre and nothing else (2026-09-17: 「无法获得报酬」 after an event). It has no page
 * state of its own, so it reads as UNKNOWN and used to stall the run. The 确认 button is the
 * same artwork as the date-change popup's, so its template is reused; every other dialog family
 * that shares the chrome is excluded explicitly: the date-change popup (its title), the session
 * expiry popup (错误提示 title / 返回标题 button), movement and battle-end dialogs (two or three
 * buttons, structurally detected).
 */
internal fun labyrinthGenericConfirmDialogRect(
    result: LabyrinthEntryFrameResult,
    minimumButtonScore: Double = 0.85,
): EntryPixelRect? {
    // 「特别装备」的两个弹窗有自己的处理分支，而且弹窗2 的蓝色「确认」与这里共享的按钮模板互相
    // 干扰：压暗的编组页可能被判成「单按钮确认弹窗」，把点击派发到弹窗2「确认」的位置上，从而
    // 绕过「先弹窗2 再判弹窗1」那条判态顺序。窄排除：只在观测确实给出弹窗 stage 时
    // 生效；stage 为 null（真实的会话失效弹窗、事件后的单按钮确认弹窗）时本函数逐字不变。
    if (result.specialEquipment?.stage != null) return null
    // 「遗物效果结果」 proves its own identity, so it is resolved before any of the guards below
    // and regardless of which page won the classifier.
    //
    // Those guards exist only to separate dialogs whose *sole* evidence is the shared 确认 button
    // artwork. This popup instead scores 0.81 on that button and 0.62 on 错误提示's blue bar, so
    // without an identity of its own it was read as an expired account session and the route
    // stopped (upstream report 2026-09-23, v1.0.9). The same frame put SESSION_RETURN_TITLE's ROI
    // directly over this 关闭 at 0.434 against a 0.45 trigger: one frame of jitter and the run
    // taps it and keeps going as though it were logged out, discarding the whole run.
    val relicEffectResultClose = result.anchorMatches[EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE]
    if (
        result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_RESULT_TITLE] >= 0.80 &&
        relicEffectResultClose != null &&
        relicEffectResultClose.score >= minimumButtonScore
    ) {
        return relicEffectResultClose.rect
    }
    // The dialog sits over the map; the map behind it can still win the page classifier
    // (2026-09-18 live: NODE_SELECTION with the dialog open). Pages with their own dialogs
    // (shop, battle, roster) are excluded; the map family and UNKNOWN are eligible.
    if (result.observation.state !in GENERIC_CONFIRM_DIALOG_PAGES) return null
    if (result.nodeMoveConfirmation != null || result.battleEndConfirmation != null) return null
    val scores = result.observation.anchorScores
    if (scores[EntryAnchorId.SESSION_DATE_CHANGE_TITLE] >= 0.60) return null
    // The blue title bar is shared with the 错误提示 popup and scores 0.6+ here, so it cannot
    // discriminate. The button does: 返回标题 is blue, this 确认 is pale.
    if (scores[EntryAnchorId.SESSION_RETURN_TITLE] >= 0.45) return null
    val button = result.anchorMatches[EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM] ?: return null
    return button.rect.takeIf { button.score >= minimumButtonScore }
}

/**
 * Consecutive frames that actually resolved a 返回标题 rect.
 *
 * The session used to gate the tap on how long it had been blocked, which counts the stretches where
 * no button is recognised at all and the run just reports 等待识别“返回标题”按钮. That primes the
 * counter past the floor, so the first frame whose score happens to clear the trigger taps at once,
 * unconfirmed. Measured 2026-09-23 on the labyrinth 商店 page: its white panel and bottom-right 关闭
 * made session.return.title spike to 0.646 (and 0.524) on single frames, above the 0.45 trigger,
 * while the page was already classified as an expiry block. A genuine expiry popup renders its
 * button continuously, so demanding consecutive button-bearing frames delays real recovery by nothing.
 */
internal fun labyrinthSessionReturnTitleStreak(
    previousStreak: Int,
    returnTitleRectPresent: Boolean,
): Int = if (returnTitleRectPresent) previousStreak + 1 else 0

private val GENERIC_CONFIRM_DIALOG_PAGES = setOf(
    LabyrinthEntryPageState.UNKNOWN,
    LabyrinthEntryPageState.NODE_SELECTION,
    LabyrinthEntryPageState.NODE_MAP_VIEW,
    LabyrinthEntryPageState.EVENT_CHOICE,
    LabyrinthEntryPageState.EVENT_ANIMATION,
)

/**
 * Event free-role pick: some events ask for one recruit, others for two (全列表 or 职阶列表,
 * 2026-09-17). The count is not announced anywhere the recogniser reads, but 去邀请 only lights
 * up once the required number is selected. So: confirm as soon as it is enabled; while it is
 * still visibly disabled after a pick has settled, pick another, bounded by the shell's three
 * slots.
 */
/**
 * Which visual state the 「去邀请」 button is in.
 *
 * The enabled and disabled templates share one ROI and differ only in button colour, so both
 * correlate highly on either state and neither score alone is a verdict. 2026-09-23: on a page
 * with nothing selected the enabled template still scored 0.925 while disabled scored 0.987, so a
 * plain threshold read the grey button as blue and the run confirmed an empty invitation for ever.
 * Only the margin between the two templates separates them reliably.
 */
internal enum class LabyrinthInviteButtonState { ENABLED, DISABLED, UNKNOWN }

internal fun labyrinthInviteButtonState(
    enabledScore: Double,
    disabledScore: Double,
    minimumScore: Double = LABYRINTH_INVITE_BUTTON_MIN_SCORE,
    margin: Double = LABYRINTH_INVITE_BUTTON_MARGIN,
): LabyrinthInviteButtonState = when {
    maxOf(enabledScore, disabledScore) < minimumScore -> LabyrinthInviteButtonState.UNKNOWN
    disabledScore - enabledScore >= margin -> LabyrinthInviteButtonState.DISABLED
    enabledScore - disabledScore >= margin -> LabyrinthInviteButtonState.ENABLED
    else -> LabyrinthInviteButtonState.UNKNOWN
}

internal const val LABYRINTH_INVITE_BUTTON_MIN_SCORE = 0.72
internal const val LABYRINTH_INVITE_BUTTON_MARGIN = 0.02

/**
 * Reconcile the remembered free-role picks against what the roster actually shows.
 *
 * Picks used to be recorded the moment a tap was dispatched and never revisited. A tap the game
 * ignored therefore left a phantom selection that pushed every later frame down the confirm
 * branch, and the run pressed a dead 「去邀请」 until the user killed it. The roster is the
 * authority: a remembered card that is on screen and unselected is dropped, a card that scrolled
 * out of view is kept, and anything the roster shows as selected is adopted.
 */
internal fun labyrinthEventFreeRoleReconcileSelection(
    rememberedIds: Collection<String>,
    visibleSelectedIds: Collection<String>,
    visibleIds: Collection<String>,
): List<String> {
    val visible = visibleIds.toSet()
    val selected = visibleSelectedIds.toSet()
    val kept = rememberedIds.filter { it in selected || it !in visible }
    return (kept + visibleSelectedIds).distinct()
}

internal fun labyrinthEventFreeRoleNeedsAnotherPick(
    selectedCount: Int,
    inviteEnabled: Boolean,
    inviteDisabled: Boolean,
    millisSinceLastSelect: Long,
    maxPicks: Int = 3,
    settleMillis: Long = 2_500L,
): Boolean = !inviteEnabled && inviteDisabled &&
    selectedCount in 1 until maxPicks &&
    millisSinceLastSelect >= settleMillis

/**
 * The 有效效果 filter can list nobody: the roster area shows 「未搜索到该角色。」 and no cards.
 * 2026-09-18: the scan waited forever for cards/scroll feedback on that page. An empty filtered
 * roster is a complete answer (zero effective roles), so the scan must finish, not wait. Both
 * the notice template and the absence of cards are required so a slow-loading list is not
 * mistaken for an empty one.
 */
internal fun labyrinthEffectiveFilterIsEmpty(
    observation: LabyrinthBattleTeamObservation,
    emptyNoticeScore: Double,
    minimumNoticeScore: Double = 0.80,
): Boolean =
    observation.currentFilter == LabyrinthBattleElementFilter.EFFECTIVE_EFFECT &&
        observation.recognitionState == LabyrinthBattleTeamRecognitionState.STABLE &&
        observation.visibleCharacters.isEmpty() &&
        emptyNoticeScore >= minimumNoticeScore

internal fun labyrinthRosterReplacesExisting(
    result: LabyrinthEntryFrameResult,
    matches: List<LabyrinthCharacterMatch>,
): Boolean = result.observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
    matches.size == REQUIRED_OPENING_ROSTER_SIZE

private fun openingRosterIsConfirmed(result: LabyrinthEntryFrameResult): Boolean {
    val scores = result.observation.anchorScores
    val selectionComplete = maxOf(
        scores[EntryAnchorId.SELECTION_COUNT_COMPLETE],
        scores[EntryAnchorId.SELECTION_COUNT_COMPLETE_STANDARD],
    )
    val inviteEnabled = maxOf(
        scores[EntryAnchorId.INVITE_ENABLED],
        scores[EntryAnchorId.INVITE_ENABLED_STANDARD],
    )
    return minOf(selectionComplete, inviteEnabled) >= ROLE_PAGE_ANCHOR_MIN_SCORE
}

private fun toRosterMatch(match: LabyrinthBattleCharacterMatch) = LabyrinthCharacterMatch(
    slotId = match.slotId,
    characterId = match.characterId,
    displayName = match.displayName,
    confidence = match.confidence,
    screenRect = match.screenRect,
    iconVariant = match.iconVariant,
    trusted = match.trusted,
    rivalMargin = match.rivalMargin,
)

private const val ROLE_PAGE_ANCHOR_MIN_SCORE = 0.45
private const val REQUIRED_OPENING_ROSTER_SIZE = 3

/**
 * Whether a committed event-choice tap has waited long enough to be considered lost.
 *
 * A real selection leaves the event page within a second or two, so a page that is still the same
 * event after this long means the tap never landed. Retrying is safe because the event page only
 * accepts one selection: if the tap did land and the page simply lagged, the page has changed by
 * the time the retry is planned.
 */
internal fun labyrinthEventChoiceCommitExpired(
    committedAtMillis: Long,
    nowMillis: Long,
    retryAfterMillis: Long = EVENT_CHOICE_COMMIT_RETRY_MILLIS,
): Boolean = committedAtMillis != Long.MIN_VALUE &&
    (nowMillis - committedAtMillis).coerceAtLeast(0L) >= retryAfterMillis

internal const val EVENT_CHOICE_COMMIT_RETRY_MILLIS = 6_000L

// ---------------------------------------------------------------------------------------------
// 「特别装备」自动穿戴：编组页执行状态机（纯决策，无 IO）
// ---------------------------------------------------------------------------------------------

/**
 * 本轮「特别装备」流程走到哪一步。
 *
 * 视觉层只回答「现在屏幕上是什么」（弹窗1 / 弹窗2 / 都不是），**无法区分弹窗1 是第几次到达**，
 * 所以「第几次」必须由这里的子状态记：[AWAIT_MAIN_DIALOG] 是「点过入口、等弹窗1 第一次出现」，
 * [AWAIT_SECOND_MAIN_DIALOG] 是「点过弹窗2 的确认、等弹窗1 第二次出现」。这两步之后要做的判断
 * 完全不同（前者要继续点「自动装备」，后者要按亮度判据决定点「装备确定」还是「取消」），
 * 任何靠 `stage` 值本身去猜第几次的写法都会做错。
 */
internal enum class LabyrinthSpecialEquipmentFlowStage {
    /** 本轮不执行：设置为「关闭」、本次战斗类型未勾选、尚未在编组页武装，或已经离开编组页。 */
    INACTIVE,

    /** 已武装：等编组页上的白色圆形「特别装备」入口按钮。 */
    AWAIT_ENTRY,

    /** 已点入口：等弹窗1「特别装备设定」出现。 */
    AWAIT_MAIN_DIALOG,

    /** 已点弹窗1 正文中部的白色「自动装备」：等弹窗2「自动特别装备设定」出现。 */
    AWAIT_AUTO_DIALOG,

    /** 已点弹窗2 的蓝色「确认」：等回到弹窗1（第二次到达），然后判「装备确定」的可点击性。 */
    AWAIT_SECOND_MAIN_DIALOG,

    /** 3.2：已点「装备确定」，等回到编组页。 */
    AWAIT_ACK_EQUIP,

    /** 3.1：已点左下白色「取消」（含判定为不可点击与状态未知两种情况），等回到编组页。 */
    AWAIT_ACK_CANCEL,

    /** 本轮结束（已穿戴 / 未变更 / 超限放弃）：放行开始战斗；离开编组页后回到 [INACTIVE]。 */
    DONE,
}

/** 本轮要派发的那一次点击落在哪个按钮上。 */
internal enum class LabyrinthSpecialEquipmentTap {
    ENTRY,
    AUTO_BUTTON,
    AUTO_CONFIRM,
    EQUIP_CONFIRM,
    CANCEL,
}

/**
 * 纯决策结果：下一步走到哪个 stage、这一帧要不要点、点哪个按钮、给用户的诊断消息，以及本轮
 * 是否已经结束（结束后才允许派发 START_BATTLE）。
 *
 * [tap] 只是一个**按钮身份**，不是坐标：坐标由 [labyrinthSpecialEquipmentTapRect] 从当前观测里取，
 * 那一层才是「不得点错位置」的结构性保证。
 *
 * [abandoned] 说明这次结束是**放弃**（超限、或入口缺失跳过）而不是走完 3.1/3.2：上层据此选择停机
 * 文案，并把 [message] 当作必须保留的诊断原文，不让后续帧的「本轮已结束」覆盖它。
 */
internal data class LabyrinthSpecialEquipmentDecision(
    val stage: LabyrinthSpecialEquipmentFlowStage,
    val tap: LabyrinthSpecialEquipmentTap?,
    val message: String,
    val finished: Boolean,
    val abandoned: Boolean = false,
)

internal val LabyrinthSpecialEquipmentFlowStage.label: String
    get() = when (this) {
        LabyrinthSpecialEquipmentFlowStage.INACTIVE -> "未执行"
        LabyrinthSpecialEquipmentFlowStage.AWAIT_ENTRY -> "点入口「特别装备」"
        LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG -> "等弹窗1「特别装备设定」"
        LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG -> "点「自动装备」后等弹窗2"
        LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG -> "点「确认」后等回弹窗1"
        LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP -> "点了「装备确定」后等回编组页"
        LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_CANCEL -> "点了「取消」后等回编组页"
        LabyrinthSpecialEquipmentFlowStage.DONE -> "本轮已结束"
    }

/** 每一步的派发次数上限（沿用 `MAX_BATTLE_TEAM_STEP_ATTEMPTS = 3` 的量级）。 */
internal const val MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS = 3

/** 每一步的等待上限：次数与时间两个上限都必须在，任何一步都不得无期限等待。 */
internal const val SPECIAL_EQUIPMENT_STEP_TIMEOUT_MILLIS = 8_000L

/**
 * 「装备确定」的两态判定要求**连续这么多帧同类证据**。
 *
 * 单帧判态会判错：「弹窗2 刚关、弹窗1 正在显现」的过渡帧上，内芯两个信号都可能落在
 * 不可点击一侧（实测内芯 lum=112.4 / B−R=81.8，而真正可用时是 lum=162.5 / B−R=110.4，
 * 可用基准 162.4/110.4、不可用基准 100.0/88.5），于是会误点「取消」；下一帧就已经是
 * 可用状态。过渡帧与不可用帧在 B−R 上只差 2.1（81.8 对 88.5）——**调阈值解决不了**，
 * 只能要求跨帧稳定。
 */
internal const val SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_FRAMES = 2

/** 一帧上「装备确定」可点击性的原始证据分类（`null` 观测＝证据不足或两信号矛盾）。 */
internal enum class LabyrinthSpecialEquipmentEquipEvidence {
    ENABLED,
    DISABLED,
    UNKNOWN,
}

/**
 * 连续同类证据的追踪状态。会话每帧调用一次 [labyrinthSpecialEquipmentEquipEvidenceStreak] 更新它。
 *
 * [confirmedEnabled] 是**唯一**可以拿去判态的结论：连续 [SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_FRAMES]
 * 帧同类证据之后才给 `true`/`false`，否则为 `null`（＝这一帧不下结论，见 [labyrinthSpecialEquipmentDecision]
 * 的 `equipConfirmEnabled` 参数）。「两信号矛盾」连续两帧按**不可点击**处理（保守分支）。
 */
internal data class LabyrinthSpecialEquipmentEquipEvidenceStreak(
    val evidence: LabyrinthSpecialEquipmentEquipEvidence = LabyrinthSpecialEquipmentEquipEvidence.UNKNOWN,
    val frames: Int = 0,
    val confirmedEnabled: Boolean? = null,
)

/**
 * 用本帧观测推进「连续同类证据」计数。纯函数。
 *
 * 只看弹窗1（[LabyrinthSpecialEquipmentStage.MAIN_DIALOG]）：弹窗2 打开或无弹窗时证据清零——要求
 * 点「确认」回到弹窗1 之后**重新判定**，所以回到弹窗1 的第一帧一定是 `frames = 1`、`confirmedEnabled = null`。
 */
internal fun labyrinthSpecialEquipmentEquipEvidenceStreak(
    previous: LabyrinthSpecialEquipmentEquipEvidenceStreak =
        LabyrinthSpecialEquipmentEquipEvidenceStreak(),
    observation: LabyrinthSpecialEquipmentObservation?,
    requiredFrames: Int = SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_FRAMES,
): LabyrinthSpecialEquipmentEquipEvidenceStreak {
    if (observation?.stage != LabyrinthSpecialEquipmentStage.MAIN_DIALOG) {
        return LabyrinthSpecialEquipmentEquipEvidenceStreak()
    }
    val evidence = when (observation.equipConfirmEnabled) {
        true -> LabyrinthSpecialEquipmentEquipEvidence.ENABLED
        false -> LabyrinthSpecialEquipmentEquipEvidence.DISABLED
        null -> LabyrinthSpecialEquipmentEquipEvidence.UNKNOWN
    }
    val frames = if (evidence == previous.evidence) previous.frames + 1 else 1
    return LabyrinthSpecialEquipmentEquipEvidenceStreak(
        evidence = evidence,
        frames = frames,
        // 只有连续同类证据达标才下结论；矛盾的连续两帧按不可点击。
        confirmedEnabled = if (frames >= requiredFrames) {
            evidence == LabyrinthSpecialEquipmentEquipEvidence.ENABLED
        } else {
            null
        },
    )
}

/**
 * 闸门：本轮特别装备流程**没有**结束时，不得派发 START_BATTLE。
 *
 * 只有 [LabyrinthSpecialEquipmentFlowStage.INACTIVE]（本轮不执行）与
 * [LabyrinthSpecialEquipmentFlowStage.DONE]（已穿戴 / 未变更 / 超限放弃）放行——要求
 * 「宁可这一次没穿上，也不能卡死在编组页」。
 */
internal fun labyrinthSpecialEquipmentBlocksBattleStart(
    stage: LabyrinthSpecialEquipmentFlowStage,
): Boolean = stage != LabyrinthSpecialEquipmentFlowStage.INACTIVE &&
    stage != LabyrinthSpecialEquipmentFlowStage.DONE

/**
 * 这一队是否一个可用成员都没有：整个队伍没有识别出任何 `characterId`（未识别的槽位是 null）。
 *
 * Boss 多队模式下留空的页签就是这种队伍——没有可穿戴的对象，所以本轮不武装，把帧交还给既有
 * 「其余留空，开始挑战」路径。
 */
internal fun labyrinthSpecialEquipmentTeamIsEmpty(characterIds: List<String?>): Boolean =
    characterIds.none { characterId -> !characterId.isNullOrBlank() }

/**
 * 本场编组是否与上一场**普通**战斗完全相同：完全相同 ⇒ 特别装备没变，跳过本轮。
 *
 * 三个条件同时成立才算「完全相同」：
 * 1. [previousSignature] 非空（首场没有可比对象）；
 * 2. 数量相同，且逐槽都识别出了角色——任一槽未识别就无法证明是同一支队伍，宁可再穿一次；
 * 3. 角色集合相同（`characterId` 经 [labyrinthBattleTeamSignature] 规范化后逐字相同）。
 *
 * EX / Boss 与「本次战斗类型未勾选」由调用方在进入本函数之前排除。
 */
internal fun labyrinthSpecialEquipmentTeamIsUnchanged(
    characterIds: List<String?>,
    previousSignature: String?,
): Boolean {
    val previous = previousSignature?.takeIf { signature -> signature.isNotBlank() } ?: return false
    if (characterIds.isEmpty()) return false
    if (characterIds.any { characterId -> characterId.isNullOrBlank() }) return false
    val ids = characterIds.filterNotNull()
    if (ids.size != previous.split(",").count { id -> id.isNotBlank() }) return false
    return labyrinthBattleTeamSignature(ids) == previous
}

/**
 * 「特别装备」本轮的下一步决策。纯函数：只读观测与计数，不碰会话状态、不派发动作。
 *
 * @param stepAttempts 当前这一步已经派发过几次点击。
 * @param stepElapsedMillis 当前这一步已经等了多久（毫秒）。
 * @param equipConfirmEnabled **已经过跨帧稳定**的「装备确定」可点击性，取自
 *   [labyrinthSpecialEquipmentEquipEvidenceStreak] 的 `confirmedEnabled`：连续
 *   [SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_FRAMES] 帧同类证据才给 `true`/`false`，否则 `null`。
 *   `null` ＝「这一帧不下结论」：既**不点**「装备确定」，也**不点**「取消」（过渡帧上的
 *   两态证据不可信），只等下一帧；判定推迟不会破坏 3.1 分支——稳定不可用仍然会走
 *   「取消」这条路，只是晚一帧。这里刻意**不给默认值**：调用方必须显式传入稳定后的结论，避免有人
 *   拿单帧原始证据直接判态。
 *
 * 终止条件只有三个，且都在这一个函数里：
 * 1. 走完 3.2（点「装备确定」并回到编组页）→ [LabyrinthSpecialEquipmentFlowStage.DONE]；
 * 2. 走完 3.1（点「取消」并回到编组页）→ DONE，消息说明「未变更」是预期结果；
 * 3. 入口找不到、或某一步次数/时间超限 → DONE 并给出卡在哪一步、当前 stage 与观测情况。
 */
internal fun labyrinthSpecialEquipmentDecision(
    stage: LabyrinthSpecialEquipmentFlowStage,
    observation: LabyrinthSpecialEquipmentObservation?,
    stepAttempts: Int,
    stepElapsedMillis: Long,
    equipConfirmEnabled: Boolean?,
    maxStepAttempts: Int = MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS,
    stepTimeoutMillis: Long = SPECIAL_EQUIPMENT_STEP_TIMEOUT_MILLIS,
): LabyrinthSpecialEquipmentDecision {
    if (stage == LabyrinthSpecialEquipmentFlowStage.INACTIVE) {
        return LabyrinthSpecialEquipmentDecision(stage, null, "特别装备：本轮不执行", finished = false)
    }
    if (stage == LabyrinthSpecialEquipmentFlowStage.DONE) {
        return LabyrinthSpecialEquipmentDecision(
            stage = stage,
            tap = null,
            message = "特别装备：本轮已结束，继续编组与开战",
            finished = true,
        )
    }
    val dialog = observation?.stage
    val observed = dialog?.let { "当前弹窗=${it.name}" } ?: "当前无弹窗"
    if (stepAttempts >= maxStepAttempts || stepElapsedMillis >= stepTimeoutMillis) {
        // 只要弹窗1 还开着且「取消」矩形可信，就点它退回编组页；否则只给可诊断消息
        // ，并且本轮照样结束、照样放行开战。
        val escape = if (
            dialog == LabyrinthSpecialEquipmentStage.MAIN_DIALOG &&
            observation.cancelButtonRect != null
        ) {
            LabyrinthSpecialEquipmentTap.CANCEL
        } else {
            null
        }
        val tail = if (escape != null) {
            "；点击左下白色「取消」退回编组页后照常开始战斗"
        } else {
            "；放弃本轮并照常开始战斗"
        }
        return LabyrinthSpecialEquipmentDecision(
            stage = LabyrinthSpecialEquipmentFlowStage.DONE,
            tap = escape,
            message = "特别装备：${stage.label}超限（尝试 $stepAttempts/$maxStepAttempts，" +
                "已等待 ${stepElapsedMillis}ms/$stepTimeoutMillis ms；$observed）$tail",
            finished = true,
            abandoned = true,
        )
    }
    return when (stage) {
        LabyrinthSpecialEquipmentFlowStage.AWAIT_ENTRY -> when (dialog) {
            // 入口点击已经生效，弹窗1 直接出现了。
            LabyrinthSpecialEquipmentStage.MAIN_DIALOG -> autoButtonDecision(observation)
            LabyrinthSpecialEquipmentStage.AUTO_DIALOG -> autoConfirmDecision(observation)
            null -> if (observation?.entryButtonRect != null) {
                LabyrinthSpecialEquipmentDecision(
                    stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG,
                    tap = LabyrinthSpecialEquipmentTap.ENTRY,
                    message = "特别装备：点击编组页白色圆形入口「特别装备」",
                    finished = false,
                )
            } else {
                // 入口不可信就跳过本轮，**零点击**，并照常允许开始战斗（不能卡死整局）。
                LabyrinthSpecialEquipmentDecision(
                    stage = LabyrinthSpecialEquipmentFlowStage.DONE,
                    tap = null,
                    message = "特别装备：本次编组未找到可信的入口按钮" +
                        "（原因码 ${observation?.entryButtonMissReason ?: "未提供"}）；" +
                        "跳过本次穿戴并照常开始战斗",
                    finished = true,
                    abandoned = true,
                )
            }
        }

        LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG -> when (dialog) {
            LabyrinthSpecialEquipmentStage.MAIN_DIALOG -> autoButtonDecision(observation)
            LabyrinthSpecialEquipmentStage.AUTO_DIALOG -> autoConfirmDecision(observation)
            null -> waitFor(stage, "特别装备：等待「特别装备设定」弹窗出现（$observed）")
        }

        LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG -> when (dialog) {
            LabyrinthSpecialEquipmentStage.AUTO_DIALOG -> autoConfirmDecision(observation)
            // 还停在弹窗1：重新点「自动装备」（有次数上限兜底）。
            LabyrinthSpecialEquipmentStage.MAIN_DIALOG -> autoButtonDecision(observation)
            null -> waitFor(stage, "特别装备：等待「自动特别装备设定」弹窗出现")
        }

        LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG -> when (dialog) {
            LabyrinthSpecialEquipmentStage.MAIN_DIALOG -> equipDecision(observation, equipConfirmEnabled)
            LabyrinthSpecialEquipmentStage.AUTO_DIALOG -> autoConfirmDecision(observation)
            null -> LabyrinthSpecialEquipmentDecision(
                stage = LabyrinthSpecialEquipmentFlowStage.DONE,
                tap = null,
                message = "特别装备：没有回到弹窗1 就离开了弹窗；本轮结束并继续开始战斗",
                finished = true,
            )
        }

        LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP -> when (dialog) {
            // 「装备确定」点过之后**只等结果，不重复派发**——不回退、不重试、不额外点击。
            // 弹窗迟迟不关由本步的次数/时间上限收尾，然后走超限逃生（那一支只允许点「取消」）。
            LabyrinthSpecialEquipmentStage.MAIN_DIALOG ->
                waitFor(stage, "特别装备：已点击「装备确定」，等待返回编组页（不重复派发）")
            LabyrinthSpecialEquipmentStage.AUTO_DIALOG ->
                waitFor(stage, "特别装备：等待回到编组页（弹窗2 仍开着）")
            null -> LabyrinthSpecialEquipmentDecision(
                stage = LabyrinthSpecialEquipmentFlowStage.DONE,
                tap = null,
                message = "特别装备：已点击「装备确定」并回到编组页，继续开始战斗",
                finished = true,
            )
        }

        LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_CANCEL -> when (dialog) {
            // 3.1 已经决定「不点装备确定」，这一支**只允许**重复点「取消」。
            LabyrinthSpecialEquipmentStage.MAIN_DIALOG -> if (observation.cancelButtonRect != null) {
                LabyrinthSpecialEquipmentDecision(
                    stage = stage,
                    tap = LabyrinthSpecialEquipmentTap.CANCEL,
                    message = "特别装备：弹窗1 仍在，重新点击左下白色「取消」",
                    finished = false,
                )
            } else {
                waitFor(stage, "特别装备：等待返回编组页（未识别到「取消」矩形，不点击任何候选位置）")
            }
            LabyrinthSpecialEquipmentStage.AUTO_DIALOG ->
                waitFor(stage, "特别装备：等待回到编组页（弹窗2 仍开着）")
            null -> LabyrinthSpecialEquipmentDecision(
                stage = LabyrinthSpecialEquipmentFlowStage.DONE,
                tap = null,
                message = "特别装备：本次未变更（按 3.1 点了左下白色「取消」；" +
                    "游戏提示「特别装备未变更。」是预期结果，不是失败）；继续开始战斗",
                finished = true,
            )
        }

        LabyrinthSpecialEquipmentFlowStage.INACTIVE,
        LabyrinthSpecialEquipmentFlowStage.DONE,
        -> error("unreachable: $stage 已在上方提前返回")
    }
}

private fun waitFor(
    stage: LabyrinthSpecialEquipmentFlowStage,
    message: String,
): LabyrinthSpecialEquipmentDecision =
    LabyrinthSpecialEquipmentDecision(stage = stage, tap = null, message = message, finished = false)

/** 弹窗1：点正文中部的白色「自动装备」；矩形不可信时只等，不点候选位置。 */
private fun autoButtonDecision(
    observation: LabyrinthSpecialEquipmentObservation?,
): LabyrinthSpecialEquipmentDecision = if (observation?.autoButtonRect != null) {
    LabyrinthSpecialEquipmentDecision(
        stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG,
        tap = LabyrinthSpecialEquipmentTap.AUTO_BUTTON,
        message = "特别装备：点击弹窗1 正文中部的白色「自动装备」",
        finished = false,
    )
} else {
    waitFor(
        LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG,
        "特别装备：等待识别弹窗1 的「自动装备」按钮（不点击候选位置）",
    )
}

/** 弹窗2：点蓝色「确认」；矩形不可信时只等。 */
private fun autoConfirmDecision(
    observation: LabyrinthSpecialEquipmentObservation?,
): LabyrinthSpecialEquipmentDecision = if (observation?.confirmButtonRect != null) {
    LabyrinthSpecialEquipmentDecision(
        stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
        tap = LabyrinthSpecialEquipmentTap.AUTO_CONFIRM,
        message = "特别装备：点击弹窗2 的蓝色「确认」",
        finished = false,
    )
} else {
    waitFor(
        LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG,
        "特别装备：等待识别弹窗2 的「确认」矩形（不点击候选位置）",
    )
}

/**
 * 回到弹窗1 之后才叫的这一步：按「装备确定」**跨帧稳定后**的可点击性分流。
 *
 * **的判定只有这一处**：`confirmedEnabled != true` 时下一个动作要么是「取消」、要么什么都不做，
 * 不存在任何把动作指向「装备确定」的路径。
 *
 * [confirmedEnabled] 的三态语义（见 [labyrinthSpecialEquipmentEquipEvidenceStreak]）：
 * - `true`：连续两帧可用 → 点蓝色「装备确定」；
 * - `false`：连续两帧不可用（含连续两帧两信号矛盾保守分支）→ 走 3.1，点左下白色「取消」；
 * - `null`：**证据还没跨帧稳定**（弹窗刚打开、或刚从弹窗2 回来）→ 这一帧只等，既不点确定也不点取消。
 *   单帧判态会在这种过渡帧上误点「取消」。
 */
private fun equipDecision(
    observation: LabyrinthSpecialEquipmentObservation?,
    confirmedEnabled: Boolean?,
): LabyrinthSpecialEquipmentDecision = when (confirmedEnabled) {
    true -> {
        if (observation?.confirmButtonRect != null) {
            LabyrinthSpecialEquipmentDecision(
                stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP,
                tap = LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM,
                message = "特别装备：「装备确定」连续两帧判定为可点击，点击蓝色「装备确定」确认穿戴",
                finished = false,
            )
        } else {
            waitFor(
                LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
                "特别装备：等待识别「装备确定」矩形（不点击候选位置）",
            )
        }
    }

    false -> {
        if (observation?.cancelButtonRect != null) {
            LabyrinthSpecialEquipmentDecision(
                stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_CANCEL,
                tap = LabyrinthSpecialEquipmentTap.CANCEL,
                message = "特别装备：「装备确定」连续两帧判定为不可点击；点击左下白色「取消」返回编组页" +
                    "（预期提示「特别装备未变更。」）",
                finished = false,
            )
        } else {
            waitFor(
                LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
                "特别装备：「装备确定」连续两帧判定为不可点击 且未识别到「取消」矩形；" +
                    "等待下一帧（绝不点击「装备确定」）",
            )
        }
    }

    null -> waitFor(
        LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
        "特别装备：「装备确定」的两态证据尚未跨帧稳定（首帧或过渡帧一律先等一帧，按不可点击保守处理）",
    )
}

/**
 * 决策里的按钮身份 → 本帧真正可点的矩形。
 *
 * **这是的结构性保证（第二道锁）**：即便某个决策错误地返回了 [LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM]，
 * 只要 [`equipConfirmEnabled`] 不是 `true`（含 `null`），这里就返回 `null` ⇒ 上层不会构造任何点击；
 * 同理，[LabyrinthSpecialEquipmentTap.AUTO_CONFIRM] 只在弹窗2 的观测上成立，弹窗1 的
 * `confirmButtonRect`（＝「装备确定」）不会被它当成「确认」用掉。
 */
internal fun labyrinthSpecialEquipmentTapRect(
    tap: LabyrinthSpecialEquipmentTap,
    observation: LabyrinthSpecialEquipmentObservation,
): EntryPixelRect? = when (tap) {
    LabyrinthSpecialEquipmentTap.ENTRY -> observation.entryButtonRect
    LabyrinthSpecialEquipmentTap.AUTO_BUTTON -> observation.autoButtonRect
    LabyrinthSpecialEquipmentTap.AUTO_CONFIRM ->
        observation.confirmButtonRect?.takeIf { observation.stage == LabyrinthSpecialEquipmentStage.AUTO_DIALOG }
    LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM -> observation.confirmButtonRect?.takeIf {
        observation.stage == LabyrinthSpecialEquipmentStage.MAIN_DIALOG &&
            observation.equipConfirmEnabled == true
    }
    LabyrinthSpecialEquipmentTap.CANCEL -> observation.cancelButtonRect
}
