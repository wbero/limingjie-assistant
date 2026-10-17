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

/**
 * Whether a committed selection tap on a single-pick choice page has waited long enough to be
 * considered lost.
 *
 * This is [labyrinthEventChoiceCommitExpired] generalised to the remaining page that holds a
 * "committed" latch, so a tap the game never accepted cannot pin the run forever. Same reasoning:
 * a real selection leaves the page within a second or two, so a page that is still the same choice
 * after this long means the tap never landed. Retrying is safe because these pages accept a single
 * selection per round — if the tap did land and the page merely lagged, the page has changed by the
 * time the retry is planned.
 *
 * Contrast the 2026-10-12 bundles: a RELIC_CHOICE page sat motionless for 120 s with actionCount
 * frozen and was only ended by the page-stall watchdog, because the relic latch cleared on a page
 * change alone and the page never changed.
 */
internal fun labyrinthSelectionCommitExpired(
    committedAtMillis: Long,
    nowMillis: Long,
    retryAfterMillis: Long = SELECTION_COMMIT_RETRY_MILLIS,
): Boolean = committedAtMillis != Long.MIN_VALUE &&
    (nowMillis - committedAtMillis).coerceAtLeast(0L) >= retryAfterMillis

internal const val SELECTION_COMMIT_RETRY_MILLIS = 6_000L
