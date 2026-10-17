package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Shared safety floor for both recognition and read-only selection planning. */
const val LABYRINTH_BATTLE_CHARACTER_SAFE_CONFIDENCE = LABYRINTH_CHARACTER_ICON_SAFE_CONFIDENCE

enum class LabyrinthBattleElementFilter(val label: String) {
    ALL("全部"),
    FIRE("火"),
    WATER("水"),
    WIND("风"),
    LIGHT("光"),
    DARK("暗"),
    EFFECTIVE_EFFECT("有效效果"),
    UNKNOWN("未知"),
}

data class LabyrinthBattleFilterObservation(
    val filter: LabyrinthBattleElementFilter,
    val screenRect: EntryPixelRect,
    val selectionScore: Double,
    val selected: Boolean,
)

data class LabyrinthBattleScrollbarObservation(
    val trackRect: EntryPixelRect,
    val thumbRect: EntryPixelRect?,
    val visible: Boolean,
    val canScroll: Boolean,
    val position: Double,
    /** A complete card row followed by substantial empty list space; not a rewind signal. */
    val contentEndVisible: Boolean = false,
)

enum class LabyrinthBattleTeamRecognitionState {
    STABLE,
    WAITING_FOR_STABILITY,
}

data class LabyrinthBattleCharacterTemplate(
    val characterId: String,
    val displayName: String,
    val iconVariant: String,
    val image: PixelImage,
    val attribute: LabyrinthCharacterAttribute? = null,
    val aliases: List<String> = emptyList(),
)

data class LabyrinthBattleCharacterMatch(
    val slotId: String,
    val characterId: String?,
    val displayName: String?,
    val iconVariant: String?,
    val confidence: Double,
    val screenRect: EntryPixelRect,
    val selected: Boolean,
    val trusted: Boolean = characterId != null,
    val rivalMargin: Double = if (trusted) 1.0 else 0.0,
    val suspectedCharacterId: String? = characterId,
    val suspectedDisplayName: String? = displayName,
    val detectedAttribute: LabyrinthCharacterAttribute? = null,
)

data class LabyrinthBattleTeamObservation(
    val currentFilter: LabyrinthBattleElementFilter,
    val filters: List<LabyrinthBattleFilterObservation>,
    val visibleCharacters: List<LabyrinthBattleCharacterMatch>,
    val selectedCharacters: List<LabyrinthBattleCharacterMatch>,
    val scrollbar: LabyrinthBattleScrollbarObservation,
    val recognitionState: LabyrinthBattleTeamRecognitionState = LabyrinthBattleTeamRecognitionState.STABLE,
    val viewportRevision: Long = 0L,
    val bossTeamIndex: Int? = null,
    val bossTeamTabs: List<EntryPixelRect> = emptyList(),
) {
    val recognizedCharacterCount: Int
        get() = (visibleCharacters + selectedCharacters).count { it.characterId != null }
}

/**
 * Coarse visual fingerprint for the currently visible roster cards.
 *
 * Sample only the portrait interior, away from level text, element badges and the selected
 * checkmark. Colors are heavily quantized so tiny animation/compression changes do not churn the
 * viewport revision, while a different set of character portraits still changes the fingerprint.
 */
internal fun labyrinthBattleRosterVisualFingerprint(
    frame: PixelImage,
    rects: List<EntryPixelRect>,
): Long {
    var hash = -3750763034362895579L // FNV-1a 64 offset basis as signed Long.
    fun mix(value: Long) {
        hash = (hash xor value) * 1099511628211L
    }
    mix(rects.size.toLong())
    rects.forEachIndexed { index, rect ->
        if (rect.width <= 0 || rect.height <= 0) {
            mix(index.toLong())
            return@forEachIndexed
        }
        mix(index.toLong())
        // Keep clear of the top-right selected checkmark (roughly x>=59%, y<=31%), the level
        // text at the top edge and the element badge at the bottom-right.
        val xs = intArrayOf(25, 40, 52)
        val ys = intArrayOf(35, 50, 65)
        for (yr in ys) for (xr in xs) {
            val x = (rect.left + rect.width * xr / 100).coerceIn(0, frame.width - 1)
            val y = (rect.top + rect.height * yr / 100).coerceIn(0, frame.height - 1)
            val color = frame[x, y]
            val r = color ushr 16 and 0xff
            val g = color ushr 8 and 0xff
            val b = color and 0xff
            // Five-bit channels are coarse enough to ignore tiny JPEG/animation noise.
            mix(((r ushr 3) shl 10 or (g ushr 3) shl 5 or (b ushr 3)).toLong())
        }
    }
    return hash
}

/**
 * Observes the battle-before-team-selection page. The page has a fixed 1920x1080 layout, while
 * the character artwork and the selected check marks are dynamic. Stable controls are measured
 * independently from the artwork so a missing icon never turns the whole page into UNKNOWN.
 */
class LabyrinthBattleTeamRecognizer(
    templates: List<LabyrinthBattleCharacterTemplate> = emptyList(),
    // The upper row has selection badges and the game compresses seasonal card art differently
    // from the lower selected-member row. Keep the score gate above the diagnostic noise floor,
    // while letting a clear margin decide whether a candidate is trustworthy.
    // 深色/高光立绘（如 1297 涅妃＝涅菈）与图标的相关性只有 0.39 左右，但与次高候选
    // 仍有 0.15 的分差，所以绝对门槛取 0.35，由分差承担主要的可信判定。
    private val minCharacterConfidence: Double = LABYRINTH_BATTLE_CHARACTER_SAFE_CONFIDENCE,
    private val minCharacterMargin: Double = 0.045,
    private val requiredStableFrames: Int = 2,
    private val iconMatcher: LabyrinthCharacterIconMatcher = LabyrinthCharacterIconMatcher(
        templates = templates,
        minimumConfidence = minCharacterConfidence,
        minimumRivalMargin = minCharacterMargin,
    ),
) {
    init {
        require(minCharacterConfidence in 0.0..1.0)
        require(minCharacterMargin in 0.0..1.0)
        require(requiredStableFrames >= 1)
    }

    /** Set per [recognize] call; read by [observeSlot] rather than threaded through each layer. */
    private var roster: Set<String>? = null
    private var stableSignature: Long? = null
    private var pendingSignature: Long? = null
    private var pendingStableFrames = 0
    private var stableObservation: LabyrinthBattleTeamObservation? = null
    private var viewportRevision = 0L

    private var openingStableSignature: Long? = null
    private var openingPendingSignature: Long? = null
    private var openingPendingStableFrames = 0
    private var openingStableObservation: LabyrinthBattleTeamObservation? = null
    private var openingViewportRevision = 0L
    private val gridDetector = LabyrinthCharacterGridDetector()
    private val currentMemberStripDetector = LabyrinthCurrentMemberStripDetector()

    /**
     * Card-grid detection is the most expensive step of this page and it repeats within a single
     * interaction even though the cards have not moved. These fields hold the last result together
     * with the evidence needed to reuse it: the layout profile it was measured for, the
     * scrollbar-derived row hints it was given, and a hash of the sampled card pixels.
     */
    private var cachedGridProfile: RosterLayoutProfile? = null
    private var cachedGridHintsKey: Int = 0
    private var cachedGridSlots: List<LabyrinthDetectedGridSlot> = emptyList()
    private var cachedGridFingerprint: Long = 0L
    private var cachedGridReuseFrames: Int = 0

    /**
     * Selecting a card dims the whole card, which shifts the sampled portrait pixels, so a naive
     * fingerprint would drop the grid cache on every add/remove inside one team edit. The card
     * frame does not move, and identity reuse is still gated by [viewportSignature], so a short run
     * of fingerprint mismatches is tolerated before paying for a full grid detection.
     */
    private var cachedGridFingerprintMisses: Int = 0

    /**
     * Second visible row anchor, reused across frames.
     *
     * [findSecondaryRowTop] walks hundreds of candidate rows and samples the frame around each of
     * them to answer one question that only changes when the roster scrolls. The call site already
     * knows the scrollbar thumb position, so the measurement is reused while that position stays in
     * the same quantisation bucket (the same bucket the viewport fingerprint uses) and for a
     * bounded number of frames, after which it is measured again.
     *
     * The result only feeds `fallbackVisibleSlots`; a measured card grid always wins, so a stale
     * anchor cannot override a projection that actually found cards.
     */
    private var cachedSecondaryRowTopProfile: RosterLayoutProfile? = null
    private var cachedSecondaryRowTopBucket = 0
    private var cachedSecondaryRowTopValue: Int? = null
    private var cachedSecondaryRowTopFrames = 0

    /** Clears the viewport cache when the entry page leaves the battle team selector. */
    @Synchronized
    fun reset() {
        stableSignature = null
        pendingSignature = null
        pendingStableFrames = 0
        stableObservation = null
        viewportRevision = 0L
        openingStableSignature = null
        openingPendingSignature = null
        openingPendingStableFrames = 0
        openingStableObservation = null
        openingViewportRevision = 0L
        cachedGridProfile = null
        cachedGridSlots = emptyList()
        cachedGridFingerprint = 0L
        cachedGridReuseFrames = 0
        cachedGridFingerprintMisses = 0
        cachedSecondaryRowTopProfile = null
        cachedSecondaryRowTopBucket = 0
        cachedSecondaryRowTopValue = null
        cachedSecondaryRowTopFrames = 0
    }

    /**
     * Opening selection shares the same card-recognition machinery as battle team selection, but
     * not the same viewport geometry. The opening page has no selected-team strip at the bottom,
     * so its list can expose a third row at the bottom stop. Keep a separate layout profile and
     * cache so opening coordinates never inherit the battle editor's two-row clipping rules.
     */
    @Synchronized
    fun recognizeOpening(frame: PixelImage): LabyrinthBattleTeamObservation {
        val layout = measureLayout(frame, OPENING_LAYOUT_PROFILE)
        val signature = viewportSignature(frame, layout)
        val cached = openingStableObservation
        if (cached == null) {
            openingViewportRevision = 1L
            return recognizeOpeningStable(frame, layout, signature)
        }
        if (signature == openingStableSignature) {
            openingPendingSignature = null
            openingPendingStableFrames = 0
            val refreshed = cached.copy(
                currentFilter = layout.currentFilter,
                filters = layout.filters,
                visibleCharacters = cached.visibleCharacters.map { match ->
                    match.copy(
                        selected = isOpeningCardSelected(
                            frame,
                            layout.visibleSlots.firstOrNull { it.id == match.slotId }
                                ?.let { mapSlotRect(frame, it) } ?: match.screenRect,
                        ),
                    )
                },
                scrollbar = layout.scrollbar,
                recognitionState = LabyrinthBattleTeamRecognitionState.STABLE,
                viewportRevision = openingViewportRevision,
            )
            openingStableObservation = refreshed
            return refreshed
        }

        if (openingPendingSignature == signature) {
            openingPendingStableFrames++
        } else {
            openingPendingSignature = signature
            openingPendingStableFrames = 1
            openingViewportRevision++
        }
        if (openingPendingStableFrames < requiredStableFrames) {
            return waitingForOpeningStability(layout)
        }
        return recognizeOpeningStable(frame, layout, signature)
    }

    fun recognizeOpeningCharacters(frame: PixelImage): List<LabyrinthBattleCharacterMatch> =
        recognizeOpening(frame).visibleCharacters

    /** Boss editor has one gold selected tab and two white tabs, above the lowered filter bar. */
    internal fun detectBossTeamIndex(frame: PixelImage): Int? {
        val colors = BOSS_TEAM_TABS.map { reference ->
            val rect = ReferenceFitMapper.map(frame.width, frame.height, STANDARD_REFERENCE,
                reference.copy(x = reference.x + 20, y = reference.y + 8, width = 155, height = 42))
                ?: return null
            var gold = 0
            var white = 0
            var samples = 0
            for (y in rect.top until rect.top + rect.height step 3) for (x in rect.left until rect.left + rect.width step 3) {
                val c = frame[x, y]
                val r = c ushr 16 and 255; val g = c ushr 8 and 255; val b = c and 255
                if (r >= 190 && g >= 140 && b < 150 && r - b >= 60) gold++
                if (minOf(r, g, b) >= 190 && maxOf(r, g, b) - minOf(r, g, b) < 45) white++
                samples++
            }
            gold.toDouble() / samples to white.toDouble() / samples
        }
        val selected = colors.indices.filter { colors[it].first >= 0.30 }.singleOrNull() ?: return null
        if (colors.indices.any { it != selected && colors[it].second < 0.40 }) return null
        return selected + 1
    }

    @Synchronized
    /**
     * @param rosterCharacterIds roles that joined this run, or empty when the run is not known.
     *
     * A labyrinth team page can only show roles the run has already acquired, so the other ~770
     * icons in the pack are impossible here rather than merely unlikely. Passing the run's own
     * joined list stops an impossible look-alike from eating the rival margin and leaving a slot
     * unidentified, which blocks the entire team plan.
     */
    fun recognize(
        frame: PixelImage,
        rosterCharacterIds: Set<String> = emptySet(),
    ): LabyrinthBattleTeamObservation {
        roster = rosterCharacterIds.takeIf(Set<String>::isNotEmpty)
        val bossIndex = detectBossTeamIndex(frame)
        val profile = if (bossIndex != null) BOSS_LAYOUT_PROFILE else BATTLE_LAYOUT_PROFILE
        val layout = measureLayout(frame, profile).copy(bossTeamIndex = bossIndex,
            bossTeamTabs = if (bossIndex == null) emptyList() else BOSS_TEAM_TABS.mapNotNull {
                ReferenceFitMapper.map(frame.width, frame.height, STANDARD_REFERENCE, it)
            })
        val signature = viewportSignature(frame, layout)
        val cached = stableObservation
        if (cached == null) {
            viewportRevision = 1L
            return recognizeStable(frame, layout, signature)
        }
        if (signature == stableSignature) {
            pendingSignature = null
            pendingStableFrames = 0
            val refreshed = cached.copy(
                currentFilter = layout.currentFilter,
                filters = layout.filters,
                // A battle selection only dims the card and adds a checkmark; it does not move
                // the card, so the structural viewport signature deliberately stays unchanged.
                // Refresh that dynamic state instead of replaying a stale cached selection.
                visibleCharacters = cached.visibleCharacters.map { match ->
                    match.copy(selected = isBattleCardSelected(frame, match.screenRect))
                },
                // The bottom current-member strip is the authoritative team state. Unlike the
                // roster geometry it changes after every selection, so it must be re-read even
                // while the available-card viewport itself remains unchanged.
                selectedCharacters = layout.selectedSlots.mapNotNull { slot ->
                    observeSlot(
                        frame = frame,
                        slot = slot,
                        selected = true,
                        topRow = false,
                        openingCard = false,
                        viewport = layout.viewport,
                    )
                },
                scrollbar = layout.scrollbar,
                recognitionState = LabyrinthBattleTeamRecognitionState.STABLE,
                viewportRevision = viewportRevision,
            )
            stableObservation = refreshed
            return refreshed
        }

        if (pendingSignature == signature) {
            pendingStableFrames++
        } else {
            pendingSignature = signature
            pendingStableFrames = 1
            viewportRevision++
        }
        if (pendingStableFrames < requiredStableFrames) {
            return waitingForStability(layout)
        }
        return recognizeStable(frame, layout, signature)
    }

    private fun recognizeStable(
        frame: PixelImage,
        layout: BattleTeamLayout,
        signature: Long,
    ): LabyrinthBattleTeamObservation {
        viewportRevision = viewportRevision.coerceAtLeast(1L)
        val visibleCharacters = layout.visibleSlots.mapNotNull { slot ->
            observeSlot(
                frame = frame,
                slot = slot,
                selected = false,
                topRow = true,
                openingCard = false,
                viewport = layout.viewport,
            )
        }
        val selectedCharacters = layout.selectedSlots.mapNotNull { slot ->
            observeSlot(
                frame = frame,
                slot = slot,
                selected = true,
                topRow = false,
                openingCard = false,
                viewport = layout.viewport,
            )
        }
        val observation = LabyrinthBattleTeamObservation(
            currentFilter = layout.currentFilter,
            filters = layout.filters,
            visibleCharacters = visibleCharacters,
            selectedCharacters = selectedCharacters,
            scrollbar = layout.scrollbar,
            recognitionState = LabyrinthBattleTeamRecognitionState.STABLE,
            viewportRevision = viewportRevision,
            bossTeamIndex = layout.bossTeamIndex,
            bossTeamTabs = layout.bossTeamTabs,
        )
        stableSignature = signature
        pendingSignature = null
        pendingStableFrames = 0
        stableObservation = observation
        return observation
    }

    private fun recognizeOpeningStable(
        frame: PixelImage,
        layout: BattleTeamLayout,
        signature: Long,
    ): LabyrinthBattleTeamObservation {
        openingViewportRevision = openingViewportRevision.coerceAtLeast(1L)
        val reusableMatches = openingStableObservation
            ?.takeIf { previous -> sameOpeningViewport(previous, layout) }
            ?.visibleCharacters
            ?.associateBy(LabyrinthBattleCharacterMatch::slotId)
            .orEmpty()
        val visibleCharacters = layout.visibleSlots.mapNotNull { slot ->
            val observed = observeSlot(
                frame = frame,
                slot = slot,
                selected = false,
                topRow = true,
                openingCard = true,
                viewport = OPENING_LAYOUT_PROFILE.viewport,
            )
            val previous = reusableMatches[slot.id]
            if (observed?.selected == true && previous?.trusted == true) {
                observed.copy(
                    characterId = previous.characterId,
                    displayName = previous.displayName,
                    iconVariant = previous.iconVariant,
                    confidence = previous.confidence,
                    trusted = true,
                    rivalMargin = previous.rivalMargin,
                    suspectedCharacterId = previous.suspectedCharacterId,
                    suspectedDisplayName = previous.suspectedDisplayName,
                    detectedAttribute = previous.detectedAttribute ?: observed.detectedAttribute,
                )
            } else {
                observed
            }
        }
        val observation = LabyrinthBattleTeamObservation(
            currentFilter = layout.currentFilter,
            filters = layout.filters,
            visibleCharacters = visibleCharacters,
            selectedCharacters = emptyList(),
            scrollbar = layout.scrollbar,
            recognitionState = LabyrinthBattleTeamRecognitionState.STABLE,
            viewportRevision = openingViewportRevision,
        )
        openingStableSignature = signature
        openingPendingSignature = null
        openingPendingStableFrames = 0
        openingStableObservation = observation
        return observation
    }

    private fun sameOpeningViewport(
        previous: LabyrinthBattleTeamObservation,
        current: BattleTeamLayout,
    ): Boolean = previous.currentFilter == current.currentFilter &&
        abs(previous.scrollbar.position - current.scrollbar.position) <= OPENING_CACHE_SCROLL_TOLERANCE &&
        previous.visibleCharacters.map(LabyrinthBattleCharacterMatch::slotId).toSet() ==
        current.visibleSlots.map(Slot::id).toSet()

    private fun waitingForOpeningStability(layout: BattleTeamLayout): LabyrinthBattleTeamObservation =
        LabyrinthBattleTeamObservation(
            currentFilter = layout.currentFilter,
            filters = layout.filters,
            visibleCharacters = emptyList(),
            selectedCharacters = emptyList(),
            scrollbar = layout.scrollbar,
            recognitionState = LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY,
            viewportRevision = openingViewportRevision,
        )

    private fun waitingForStability(layout: BattleTeamLayout): LabyrinthBattleTeamObservation =
        LabyrinthBattleTeamObservation(
            currentFilter = layout.currentFilter,
            filters = layout.filters,
            // Do not expose stale coordinates while a programmatic or manual scroll is settling.
            visibleCharacters = emptyList(),
            selectedCharacters = emptyList(),
            scrollbar = layout.scrollbar,
            recognitionState = LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY,
            viewportRevision = viewportRevision,
            bossTeamIndex = layout.bossTeamIndex,
            bossTeamTabs = layout.bossTeamTabs,
        )

    private fun measureLayout(
        frame: PixelImage,
        profile: RosterLayoutProfile,
    ): BattleTeamLayout {
        val filters = profile.filters.mapNotNull { definition ->
            val rect = ReferenceFitMapper.map(
                frameWidth = frame.width,
                frameHeight = frame.height,
                referenceSize = STANDARD_REFERENCE,
                referenceRect = definition.referenceRect,
            ) ?: return@mapNotNull null
            val selectionScore = blueCoverage(frame, rect)
            LabyrinthBattleFilterObservation(
                filter = definition.filter,
                screenRect = rect,
                selectionScore = selectionScore,
                selected = selectionScore >= FILTER_SELECTED_MIN_SCORE,
            )
        }
        val selectedFilter = filters
            .filter(LabyrinthBattleFilterObservation::selected)
            .maxByOrNull(LabyrinthBattleFilterObservation::selectionScore)
            ?.filter
            ?: LabyrinthBattleElementFilter.UNKNOWN

        val primarySlots = PRIMARY_SLOT_LAYOUTS
            .map { slots ->
                slots.map { slot ->
                    slot.copy(
                        referenceRect = requireNotNull(slot.referenceRect).copy(height = profile.slotHeight),
                    )
                }
            }
            .filter { slots ->
                val rowTop = slots.firstOrNull()?.referenceRect?.y ?: DEFAULT_PRIMARY_ROW_TOP
                rowTop < SEARCH_LAYOUT_PRIMARY_TOP_MIN ||
                    horizontalEdgeScore(frame, rowTop) >= SEARCH_LAYOUT_PRIMARY_EDGE_MIN_SCORE
            }
            .maxByOrNull { slots ->
            slots.sumOf { slot ->
                val rect = ReferenceFitMapper.map(
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    referenceSize = STANDARD_REFERENCE,
                    referenceRect = requireNotNull(slot.referenceRect),
                )
                if (rect == null) 0.0 else cardPresence(frame, rect)
            }
            }.orEmpty()

        val scrollbar = observeScrollbar(frame, profile.scrollbarTrack)

        // Build coarse row hints for the grid detector. At the opening default and bottom stops,
        // card labels are stronger than the old one-dimensional edge scan, so the scrollbar picks
        // a safe hint layout. The detector below still snaps every output box to the frame; these
        // hints become the full fallback only when the card grid cannot be measured.
        val primaryTop = primarySlots.firstOrNull()?.referenceRect?.y ?: DEFAULT_PRIMARY_ROW_TOP
        val secondaryTop = findSecondaryRowTopCached(frame, profile, scrollbar)
            ?.plus(profile.secondaryRowTopAdjustment)
        val fixedLayout = profile.fixedLayouts.firstOrNull { layout ->
            scrollbar.canScroll && scrollbar.position >= layout.minimumScrollbarPosition
        }
        val fallbackVisibleSlots = when {
            fixedLayout != null ->
                fixedLayout.rowTops.flatMapIndexed { rowIndex, rowTop ->
                    availableSlots(
                        rowTop = rowTop,
                        rowIndex = rowIndex,
                        slotHeight = profile.slotHeight,
                        allowTopClip = fixedLayout.allowTopClipOnFirstRow && rowIndex == 0,
                        allowBottomClip = fixedLayout.allowBottomClipOnLastRow &&
                            rowIndex == fixedLayout.rowTops.lastIndex,
                    )
                }

            secondaryTop == null -> primarySlots

            else -> {
                // In the compact two-row editor the first row can be clipped by the viewport,
                // so its virtual top is recovered from the second row. At the search-layout top
                // the first row is fully visible and has its own lower anchor.
                val rowTops = if (primaryTop >= SEARCH_LAYOUT_PRIMARY_TOP_MIN) {
                    listOf(primaryTop, secondaryTop)
                } else {
                    listOf(secondaryTop - AVAILABLE_ROW_PITCH, secondaryTop)
                }
                rowTops.flatMapIndexed { rowIndex, rowTop ->
                    availableSlots(rowTop, rowIndex, profile.slotHeight)
                }
            }
        }
        val gridRowTopHints = fallbackVisibleSlots.mapNotNull { slot ->
            val reference = slot.referenceRect ?: return@mapNotNull null
            ReferenceFitMapper.map(
                frameWidth = frame.width,
                frameHeight = frame.height,
                referenceSize = STANDARD_REFERENCE,
                referenceRect = reference,
            )?.top
        }.distinct()
        val measuredGridSlots = reusableGridSlots(frame, profile, gridRowTopHints)
            ?: gridDetector.detect(
                frame = frame,
                viewportReference = profile.viewport,
                referenceCardSize = profile.slotHeight,
                referenceColumnPitch = profile.columnPitch,
                referenceRowPitch = profile.rowPitch,
                maxColumns = profile.maxColumns,
                rowTopHints = gridRowTopHints,
            ).also { detected ->
                cachedGridProfile = profile
                cachedGridHintsKey = gridRowTopHints.hashCode()
                cachedGridSlots = detected
                cachedGridFingerprint =
                    labyrinthBattleRosterVisualFingerprint(frame, detected.map { it.fullRect })
                cachedGridReuseFrames = 0
            }
        // A clipped leading band can end on an artwork edge instead of the card border.
        // Recover its virtual top from the next measured row, not the header or a click constant.
        val detectedGridSlots = measuredGridSlots.map { detected ->
            val nextRow = measuredGridSlots.firstOrNull {
                it.rowIndex == detected.rowIndex + 1 && !it.clippedAtTop && !it.clippedAtBottom
            }
            val pitch = (detected.fullRect.height.toDouble() * profile.rowPitch / profile.slotHeight).toInt()
            if (profile == BOSS_LAYOUT_PROFILE && detected.clippedAtTop && nextRow != null &&
                nextRow.fullRect.top - detected.fullRect.top > pitch * 1.2
            ) {
                detected.copy(fullRect = detected.fullRect.copy(top = nextRow.fullRect.top - pitch))
            } else detected
        }
        val detectedRowCount = detectedGridSlots
            .map(LabyrinthDetectedGridSlot::rowIndex)
            .distinct()
            .size
        val expectedCardRect = ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = EntryReferenceRect(0, 0, profile.slotHeight, profile.slotHeight),
        )
        val detectedVisibleSlots = detectedGridSlots.map { detected ->
            val slotId = "available_row_${detected.rowIndex + 1}_${detected.columnIndex + 1}"
            val fallbackSlot = fallbackVisibleSlots.getOrNull(
                detected.rowIndex * profile.maxColumns + detected.columnIndex,
            )
            val fallbackRecognitionRect = fallbackSlot?.let { slot -> mapSlotRect(frame, slot) }
            // Sparse terminal rows can make the projection detector merge the last real card with
            // the empty space below it (or even with a lone card in the next row).  Keep the
            // measured top/left, because those follow scrolling, but never let an obviously
            // over-tall/over-wide rectangle become either the portrait crop or the click target.
            // The calibrated slot size is geometry evidence only; identity still comes from the
            // normal portrait matcher and retains the same confidence/margin gates.
            val interactionRect = normalizeRosterCardRect(
                detectedRect = detected.fullRect,
                fallbackRect = fallbackRecognitionRect,
                expectedWidth = expectedCardRect?.width,
                expectedHeight = expectedCardRect?.height,
            )
            Slot(
                id = slotId,
                screenRect = interactionRect,
                recognitionRect = rosterRecognitionRect(
                    detectedRowCount = detectedRowCount,
                    detectedRect = interactionRect,
                    fallbackRect = fallbackRecognitionRect,
                    preferDetected = profile == OPENING_LAYOUT_PROFILE &&
                        !detected.clippedAtTop && !detected.clippedAtBottom,
                ),
                allowTopClip = detected.clippedAtTop,
                allowBottomClip = detected.clippedAtBottom,
            )
        }
        // The member strip is a fixed row. Do not let the scrolling-grid projection
        // derive its pitch/top from portrait contents or the power labels above each face.
        val memberHints = profile.fallbackSelectedSlots.mapNotNull { slot -> mapSlotRect(frame, slot) }
        val selectedSlots = currentMemberStripDetector.refine(frame, memberHints)
            .mapIndexed { index, rect ->
                Slot(id = "current_member_${index + 1}", screenRect = rect, recognitionRect = rect)
            }
        return BattleTeamLayout(
            currentFilter = selectedFilter,
            filters = filters,
            visibleSlots = detectedVisibleSlots.ifEmpty { fallbackVisibleSlots },
            selectedSlots = selectedSlots,
            scrollbar = scrollbar.copy(contentEndVisible = rosterEndVisible(frame, profile.viewport, detectedGridSlots)),
            viewport = profile.viewport,
        )
    }

    /**
     * Fully visible opening cards use their measured border in both single- and multi-row lists.
     * Guild roster sizes/sorting change the row geometry; nearby legacy coordinates are not
     * identity evidence. Only clipped opening cards retain the calibrated full-card fallback.
     * The battle editor keeps its existing calibration policy.
     */
    internal fun rosterRecognitionRect(
        detectedRowCount: Int,
        detectedRect: EntryPixelRect,
        fallbackRect: EntryPixelRect?,
        preferDetected: Boolean = false,
    ): EntryPixelRect = if (preferDetected || detectedRowCount == 1 || fallbackRect == null ||
        abs(detectedRect.left - fallbackRect.left) > detectedRect.width / 4 ||
        abs(detectedRect.top - fallbackRect.top) > detectedRect.height / 4
    ) {
        detectedRect
    } else {
        fallbackRect
    }

    /**
     * Preserve the detector's scroll-aware origin while clipping only impossible oversized card
     * bounds back to the calibrated card dimensions.  Small rectangles are intentionally left
     * untouched because they can represent a legitimately clipped card at the viewport edge.
     */
    internal fun normalizeRosterCardRect(
        detectedRect: EntryPixelRect,
        fallbackRect: EntryPixelRect?,
        expectedWidth: Int? = null,
        expectedHeight: Int? = null,
    ): EntryPixelRect {
        val targetWidth = fallbackRect?.width ?: expectedWidth ?: return detectedRect
        val targetHeight = fallbackRect?.height ?: expectedHeight ?: return detectedRect
        val maxWidth = (targetWidth * ROSTER_OVERSIZE_RATIO).toInt()
        val maxHeight = (targetHeight * ROSTER_OVERSIZE_RATIO).toInt()
        val width = if (detectedRect.width > maxWidth) targetWidth else detectedRect.width
        val height = if (detectedRect.height > maxHeight) targetHeight else detectedRect.height
        return if (width == detectedRect.width && height == detectedRect.height) {
            detectedRect
        } else {
            EntryPixelRect(detectedRect.left, detectedRect.top, width, height)
        }
    }

    private fun mapSlotRect(frame: PixelImage, slot: Slot): EntryPixelRect? {
        slot.screenRect?.let { return it }
        val reference = slot.referenceRect ?: return null
        return ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = reference,
        )
    }

    /**
     * Viewport fingerprint used to decide whether cached role identities may be reused.
     *
     * Geometry alone is insufficient: after a roster swipe the card grid can settle back onto the
     * exact same row coordinates, and scrollbar quantization can also remain in the same bucket.
     * In that case the old implementation replayed the previous page's role identities forever.
     * Mix a cheap, coarse portrait fingerprint from the visible cards so a genuinely different
     * roster page invalidates the cache even when the layout is geometrically identical.
     */
    private fun viewportSignature(frame: PixelImage, layout: BattleTeamLayout): Long {
        var signature = SIGNATURE_OFFSET_BASIS
        fun mix(value: Long) {
            signature = (signature xor value) * SIGNATURE_PRIME
        }

        mix(layout.currentFilter.ordinal.toLong())
        mix((layout.bossTeamIndex ?: 0).toLong())
        layout.scrollbar.thumbRect?.let { thumb ->
            mix((thumb.top / SIGNATURE_POSITION_QUANTIZATION).toLong())
            mix((thumb.height / SIGNATURE_POSITION_QUANTIZATION).toLong())
        } ?: mix(-1L)
        mix((layout.scrollbar.position * SIGNATURE_POSITION_BUCKETS).roundToInt().toLong())
        // Current-member slots animate and move whenever a role is added or removed. Including
        // them made an otherwise stationary roster look like a new viewport every frame and
        // repeatedly reset the guarded action key.
        layout.visibleSlots.forEach { slot ->
            slot.screenRect?.let { rect ->
                mix((rect.left / SIGNATURE_POSITION_QUANTIZATION).toLong())
                mix((rect.top / SIGNATURE_POSITION_QUANTIZATION).toLong())
                mix((rect.width / SIGNATURE_POSITION_QUANTIZATION).toLong())
                mix((rect.height / SIGNATURE_POSITION_QUANTIZATION).toLong())
            }
        }
        mix(
            labyrinthBattleRosterVisualFingerprint(
                frame,
                layout.visibleSlots.mapNotNull(Slot::screenRect),
            ),
        )
        return signature
    }

    /**
     * Reuse the previous card-grid detection while the page still looks the same.
     *
     * The fingerprint samples fixed positions inside the previously detected cards, so any scroll,
     * filter change or re-layout moves the sampled pixels and the cache is dropped. The row hints
     * derived from the scrollbar are compared as well, and [MAX_GRID_REUSE_FRAMES] forces a real
     * detection periodically so a long unchanged sequence cannot drift for ever.
     *
     * The fingerprint is sensitive to brightness, and a battle selection dims the whole card, so a
     * single toggle used to invalidate this cache even though the card frame had not moved. A short
     * run of mismatches ([MAX_GRID_FINGERPRINT_MISSES]) is therefore tolerated: this cache only
     * yields card geometry, and role identities are still re-checked against the freshly sampled
     * fingerprint through [viewportSignature], which drops the cached observation instead.
     */
    private fun reusableGridSlots(
        frame: PixelImage,
        profile: RosterLayoutProfile,
        rowTopHints: List<Int>,
    ): List<LabyrinthDetectedGridSlot>? {
        val cached = cachedGridSlots
        if (cached.isEmpty()) return null
        if (cachedGridProfile !== profile) return null
        if (cachedGridReuseFrames >= MAX_GRID_REUSE_FRAMES) return null
        if (cachedGridHintsKey != rowTopHints.hashCode()) return null
        val fingerprint = labyrinthBattleRosterVisualFingerprint(frame, cached.map { it.fullRect })
        if (fingerprint != cachedGridFingerprint) {
            if (++cachedGridFingerprintMisses >= MAX_GRID_FINGERPRINT_MISSES) return null
        } else {
            cachedGridFingerprintMisses = 0
        }
        cachedGridReuseFrames++
        return cached
    }

    /**
     * [findSecondaryRowTop] behind a scroll-position keyed cache.
     *
     * The anchor only moves when the roster list scrolls, and scrolling is already observable as the
     * scrollbar thumb position, so a measurement is reused while that position stays in the same
     * quantisation bucket and for at most [MAX_SECONDARY_ROW_TOP_REUSE_FRAMES] frames. The frame
     * budget bounds how long a scroll that stays inside one bucket can go unnoticed.
     */
    private fun findSecondaryRowTopCached(
        frame: PixelImage,
        profile: RosterLayoutProfile,
        scrollbar: LabyrinthBattleScrollbarObservation,
    ): Int? {
        val bucket = (scrollbar.position * SIGNATURE_POSITION_BUCKETS).roundToInt()
        if (cachedSecondaryRowTopProfile === profile &&
            cachedSecondaryRowTopBucket == bucket &&
            cachedSecondaryRowTopFrames < MAX_SECONDARY_ROW_TOP_REUSE_FRAMES
        ) {
            cachedSecondaryRowTopFrames++
            return cachedSecondaryRowTopValue
        }
        val measured = findSecondaryRowTop(frame)
        cachedSecondaryRowTopProfile = profile
        cachedSecondaryRowTopBucket = bucket
        cachedSecondaryRowTopValue = measured
        cachedSecondaryRowTopFrames = 0
        return measured
    }

    /** Finds a second visible card row from its shared horizontal card-frame edge. */
    private fun findSecondaryRowTop(frame: PixelImage): Int? {
        val candidates = (SECONDARY_ROW_SCAN_START..SECONDARY_ROW_SCAN_END).map { referenceY ->
            val edge = horizontalEdgeScore(frame, referenceY)
            val below = cardBandCoverage(frame, referenceY + SECONDARY_EDGE_LOOKAHEAD, SECONDARY_BAND_HEIGHT)
            val depth = cardBandCoverage(frame, referenceY + SECONDARY_EDGE_LOOKAHEAD, SECONDARY_DEPTH_HEIGHT)
            SecondaryRowCandidate(
                referenceY = referenceY,
                score = edge * below * depth,
                edge = edge,
                below = below,
                depth = depth,
            )
        }
        val best = candidates.maxByOrNull(SecondaryRowCandidate::score) ?: return null
        return best.referenceY.takeIf {
            best.edge >= SECONDARY_EDGE_MIN_SCORE &&
                best.below >= SECONDARY_BAND_MIN_SCORE &&
                best.depth >= SECONDARY_DEPTH_MIN_SCORE
        }
    }

    private fun availableSlots(
        rowTop: Int,
        rowIndex: Int,
        slotHeight: Int,
        allowTopClip: Boolean = false,
        allowBottomClip: Boolean = false,
    ): List<Slot> =
        AVAILABLE_SLOT_X.mapIndexed { columnIndex, x ->
            Slot(
                id = "available_row_${rowIndex + 1}_${columnIndex + 1}",
                referenceRect = EntryReferenceRect(x, rowTop, AVAILABLE_SLOT_SIZE, slotHeight),
                allowTopClip = allowTopClip,
                allowBottomClip = allowBottomClip,
            )
        }

    private fun horizontalEdgeScore(frame: PixelImage, referenceY: Int): Double {
        var total = 0.0
        var samples = 0
        AVAILABLE_SLOT_X.forEach { x ->
            val rect = ReferenceFitMapper.map(
                frameWidth = frame.width,
                frameHeight = frame.height,
                referenceSize = STANDARD_REFERENCE,
                referenceRect = EntryReferenceRect(x, referenceY, AVAILABLE_SLOT_SIZE, 8),
            ) ?: return@forEach
            val y = rect.top.coerceIn(0, frame.height - 2)
            var sampleX = rect.left + (rect.width * 0.06).roundToInt()
            val endX = rect.left + (rect.width * 0.94).roundToInt()
            while (sampleX < endX && sampleX < frame.width) {
                val current = frame[sampleX, y]
                val next = frame[sampleX, y + 1]
                total += (0..2).sumOf { channel ->
                    abs(channel(current, channel) - channel(next, channel)).toDouble()
                }
                samples++
                sampleX += 8
            }
        }
        return if (samples == 0) 0.0 else total / samples
    }

    private fun cardBandCoverage(frame: PixelImage, referenceTop: Int, referenceHeight: Int): Double {
        var colourful = 0
        var samples = 0
        AVAILABLE_SLOT_X.forEach { x ->
            val rect = ReferenceFitMapper.map(
                frameWidth = frame.width,
                frameHeight = frame.height,
                referenceSize = STANDARD_REFERENCE,
                referenceRect = EntryReferenceRect(x, referenceTop, AVAILABLE_SLOT_SIZE, referenceHeight),
            ) ?: return@forEach
            var y = rect.top
            while (y < rect.top + rect.height && y < frame.height) {
                var sampleX = rect.left + 8
                while (sampleX < rect.left + rect.width - 8 && sampleX < frame.width) {
                    val color = frame[sampleX, y]
                    val red = channel(color, 0)
                    val green = channel(color, 1)
                    val blue = channel(color, 2)
                    if (
                        maxOf(red, green, blue) - minOf(red, green, blue) >= 28 &&
                        (red + green + blue) / 3 < 248
                    ) colourful++
                    samples++
                    sampleX += 6
                }
                y += 3
            }
        }
        return if (samples == 0) 0.0 else colourful.toDouble() / samples
    }

    private fun observeSlot(
        frame: PixelImage,
        slot: Slot,
        selected: Boolean,
        topRow: Boolean,
        openingCard: Boolean,
        viewport: EntryReferenceRect,
    ): LabyrinthBattleCharacterMatch? {
        val screenRect = mapSlotRect(frame, slot) ?: return null
        val recognitionRect = slot.recognitionRect ?: screenRect
        val visibleScreenRect = if (selected) {
            screenRect
        } else {
            clipCardToViewport(frame, screenRect, viewport) ?: return null
        }
        val visibleRecognitionRect = if (selected) {
            recognitionRect
        } else {
            clipCardToViewport(frame, recognitionRect, viewport) ?: return null
        }
        if (!selected) {
            val clippedAtTop = visibleScreenRect.top > screenRect.top
            val clippedAtBottom = visibleScreenRect.top + visibleScreenRect.height <
                screenRect.top + screenRect.height
            val allowedClip = (!clippedAtTop || slot.allowTopClip) &&
                (!clippedAtBottom || slot.allowBottomClip)
            if (
                visibleScreenRect != screenRect &&
                (!allowedClip ||
                    visibleScreenRect.height < screenRect.height * OPENING_CARD_MIN_VISIBLE_RATIO)
            ) {
                return null
            }
        }
        val allowsViewportClip = slot.allowTopClip || slot.allowBottomClip
        val presenceRect = if (allowsViewportClip) visibleScreenRect else screenRect
        if (cardPresence(frame, presenceRect) < CARD_PRESENCE_MIN_SCORE) return null
        val iconRect = EntryPixelRect(
            left = recognitionRect.left + ICON_CARD_INSET_LEFT,
            top = recognitionRect.top + ICON_CARD_INSET_TOP,
            width = (recognitionRect.width - ICON_CARD_HORIZONTAL_INSET).coerceAtLeast(16),
            height = (recognitionRect.width - ICON_CARD_HORIZONTAL_INSET).coerceAtLeast(16),
        )
        var requiredAttribute = if (!selected && visibleRecognitionRect == recognitionRect) {
            recognizeCardAttribute(frame, recognitionRect)
        } else {
            null
        }
        val visibleTopRatio =
            (visibleRecognitionRect.top - recognitionRect.top).toDouble() / recognitionRect.height.toDouble()
        val visibleHeightRatio = visibleRecognitionRect.height.toDouble() / recognitionRect.height.toDouble()
        val openingSelected = openingCard && isOpeningCardSelected(frame, screenRect)
        val characterMask = LabyrinthCharacterIconMask(
            // Current members have the same level label and right-edge UI as roster cards.
            // Mask that chrome on both strips instead of penalizing only selected portraits.
            ignoreRightEdge = topRow || selected,
            ignoreTopLeft = topRow || selected,
            // A selected opening card is dimmed and receives a large white 1/2/3 badge in
            // the upper-right. The badge extends well past the generic right-edge mask.
            ignoreTopRight = openingSelected,
            // Available/opening cards carry a purple select marker and rank/star chrome in
            // the lower-left corner. Matching that UI against clean character-icon templates
            // suppresses the real artwork signal, especially for seasonal variants of the
            // same base character.
            ignoreBottomLeft = openingCard,
            // Selection also replaces the rank/star strip with a plus button and progress
            // dots across the complete lower edge. None of those pixels identify the role.
            ignoreBottomBand = openingSelected,
            // Opening-selector cards use the same square artwork geometry as battle-team
            // cards. At the default stop the trailing row crosses the lower viewport edge,
            // while at the true bottom the leading row crosses the upper edge. Mask pixels
            // hidden by either viewport edge instead of comparing panel chrome as artwork.
            minYRatio = if (
                openingCard &&
                slot.allowTopClip &&
                visibleRecognitionRect.top > recognitionRect.top
            ) {
                visibleTopRatio.coerceIn(0.0, 1.0)
            } else {
                0.0
            },
            maxYRatio = if (
                openingCard &&
                slot.allowBottomClip &&
                visibleRecognitionRect.top + visibleRecognitionRect.height <
                recognitionRect.top + recognitionRect.height
            ) {
                visibleHeightRatio.coerceIn(0.0, 1.0)
            } else {
                1.0
            },
        )
        // Opening/event/shop pickers offer roles that have not joined this run yet. A battle
        // roster can survive reset() in this shared recognizer, but must never restrict these
        // acquisition pages or promote an already-owned look-alike over an unknown portrait.
        val candidateRoster = if (openingCard) null else roster
        var character = iconMatcher.match(
            frame = frame,
            iconRect = iconRect,
            mask = characterMask,
            // The right-most badge is the stable elemental attribute. It is not used as identity
            // by itself; it only removes known candidates of a different attribute. If the badge
            // is unclear this returns null and the matcher falls back to the full icon pack.
            requiredAttribute = requiredAttribute,
            rosterCharacterIds = candidateRoster,
        )
        if (!character.trusted && screenRect != recognitionRect) {
            val detectedIconRect = EntryPixelRect(
                left = screenRect.left + ICON_CARD_INSET_LEFT,
                top = screenRect.top + ICON_CARD_INSET_TOP,
                width = (screenRect.width - ICON_CARD_HORIZONTAL_INSET).coerceAtLeast(16),
                height = (screenRect.width - ICON_CARD_HORIZONTAL_INSET).coerceAtLeast(16),
            )
            val detectedCharacter = iconMatcher.match(
                frame = frame,
                iconRect = detectedIconRect,
                mask = LabyrinthCharacterIconMask(
                    ignoreRightEdge = topRow || selected,
                    ignoreTopLeft = topRow || selected,
                    ignoreTopRight = openingSelected,
                    ignoreBottomLeft = openingCard,
                    ignoreBottomBand = openingSelected,
                    minYRatio = if (openingCard && slot.allowTopClip) {
                        ((visibleScreenRect.top - screenRect.top).toDouble() / screenRect.height).coerceIn(0.0, 1.0)
                    } else 0.0,
                    maxYRatio = if (openingCard && slot.allowBottomClip) {
                        (visibleScreenRect.height.toDouble() / screenRect.height).coerceIn(0.0, 1.0)
                    } else 1.0,
                ),
                requiredAttribute = if (!selected && visibleScreenRect == screenRect) {
                    recognizeCardAttribute(frame, screenRect)
                } else null,
                rosterCharacterIds = candidateRoster,
            )
            // A second attempt still has to pass the same confidence and different-role margin.
            // Never promote a merely "less bad" candidate into an actionable identity.
            if (detectedCharacter.trusted) {
                character = detectedCharacter
                requiredAttribute = if (!selected && visibleScreenRect == screenRect) {
                    recognizeCardAttribute(frame, screenRect)
                } else null
            }
        }
        // Previously only an already-selected member paid for geometry refinement. That leaves a
        // permanently ambiguous *available* card able to deadlock the effective-effect scan,
        // because that scan intentionally refuses to omit an unknown effective role. Refine every
        // real battle-editor card that failed the primary match, while opening-selector cards keep
        // their dedicated clipped-card geometry path above.
        if (!openingCard && !character.trusted) {
            character = iconMatcher.refineMemberGeometry(
                frame = frame,
                iconRect = iconRect,
                initial = character,
                mask = characterMask,
                requiredAttribute = requiredAttribute,
                rosterCharacterIds = candidateRoster,
            )
        }
        return LabyrinthBattleCharacterMatch(
            slotId = slot.id,
            characterId = character.characterId,
            displayName = character.displayName,
            iconVariant = character.iconVariant,
            confidence = character.confidence,
            // Debug overlay/click geometry must describe only the pixels the game actually exposes.
            // This matters for either clipped edge of the opening selector's terminal layouts.
            screenRect = if (allowsViewportClip) visibleScreenRect else screenRect,
            selected = selected || openingSelected ||
                (!openingCard && isBattleCardSelected(frame, visibleScreenRect)),
            trusted = character.trusted,
            rivalMargin = character.rivalMargin,
            suspectedCharacterId = character.suspectedCharacterId,
            suspectedDisplayName = character.suspectedDisplayName,
            detectedAttribute = requiredAttribute,
        )
    }

    private fun recognizeCardAttribute(
        frame: PixelImage,
        cardRect: EntryPixelRect,
    ): LabyrinthCharacterAttribute? {
        val left = cardRect.left + (cardRect.width * ATTRIBUTE_BADGE_LEFT_RATIO).toInt()
        val top = cardRect.top + (cardRect.height * ATTRIBUTE_BADGE_TOP_RATIO).toInt()
        val width = (cardRect.width * ATTRIBUTE_BADGE_WIDTH_RATIO).toInt().coerceAtLeast(8)
        val height = (cardRect.height * ATTRIBUTE_BADGE_HEIGHT_RATIO).toInt().coerceAtLeast(8)
        if (left < 0 || top < 0 || left + width > frame.width || top + height > frame.height) return null

        var vectorX = 0.0
        var vectorY = 0.0
        var accepted = 0
        var total = 0
        val step = maxOf(1, min(width, height) / 16)
        var y = top
        while (y < top + height) {
            var x = left
            while (x < left + width) {
                total++
                val hsv = cardRgbToHsv(frame[x, y])
                if (hsv.saturation >= ATTRIBUTE_MIN_SATURATION && hsv.value >= ATTRIBUTE_MIN_VALUE) {
                    val angle = hsv.hue * TWO_PI
                    vectorX += cos(angle) * hsv.saturation
                    vectorY += sin(angle) * hsv.saturation
                    accepted++
                }
                x += step
            }
            y += step
        }
        if (total == 0 || accepted.toDouble() / total < ATTRIBUTE_MIN_SIGNAL) return null
        var hue = atan2(vectorY, vectorX) / TWO_PI
        if (hue < 0.0) hue += 1.0
        val ranked = ATTRIBUTE_HUES
            .map { (attribute, expectedHue) -> attribute to attributeHueScore(hue, expectedHue) }
            .sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: 0.0
        return best.first.takeIf {
            best.second >= ATTRIBUTE_MIN_CONFIDENCE && best.second - second >= ATTRIBUTE_MIN_MARGIN
        }
    }

    private fun attributeHueScore(first: Double, second: Double): Double {
        val distance = abs(first - second).let { min(it, 1.0 - it) }
        return (1.0 - distance / 0.5).coerceIn(0.0, 1.0)
    }

    private fun cardRgbToHsv(color: Int): CardHsv {
        val red = (color ushr 16 and 0xff) / 255.0
        val green = (color ushr 8 and 0xff) / 255.0
        val blue = (color and 0xff) / 255.0
        val maximum = maxOf(red, green, blue)
        val minimum = minOf(red, green, blue)
        val delta = maximum - minimum
        val hue = when {
            delta <= 1e-9 -> 0.0
            maximum == red -> ((green - blue) / delta / 6.0).let { if (it < 0) it + 1.0 else it }
            maximum == green -> ((blue - red) / delta + 2.0) / 6.0
            else -> ((red - green) / delta + 4.0) / 6.0
        }
        return CardHsv(
            hue = hue,
            saturation = if (maximum <= 1e-9) 0.0 else delta / maximum,
            value = maximum,
        )
    }

    private data class CardHsv(val hue: Double, val saturation: Double, val value: Double)

    /** Clips a virtual card row to the portion actually exposed by the roster panel. */
    private fun clipCardToViewport(
        frame: PixelImage,
        cardRect: EntryPixelRect,
        viewportReference: EntryReferenceRect,
    ): EntryPixelRect? {
        val viewport = ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = viewportReference,
        ) ?: return null
        val left = maxOf(cardRect.left, viewport.left)
        val top = maxOf(cardRect.top, viewport.top)
        val right = minOf(cardRect.left + cardRect.width, viewport.left + viewport.width)
        val bottom = minOf(cardRect.top + cardRect.height, viewport.top + viewport.height)
        if (right <= left || bottom <= top) return null
        return EntryPixelRect(left, top, right - left, bottom - top)
    }

    private fun rosterEndVisible(
        frame: PixelImage,
        viewportReference: EntryReferenceRect,
        slots: List<LabyrinthDetectedGridSlot>,
    ): Boolean {
        if (slots.isEmpty() || slots.any { it.clippedAtBottom }) return false
        val viewport = ReferenceFitMapper.map(frame.width, frame.height, STANDARD_REFERENCE, viewportReference)
            ?: return false
        val size = slots.maxOf { it.fullRect.height }
        val bottom = slots.maxOf { it.fullRect.top + it.fullRect.height }
        val end = viewport.top + viewport.height - size / 8
        if (end - bottom < size * 0.65) return false
        var blank = 0
        var samples = 0
        for (y in bottom + size / 8 until end step 4) {
            for (x in viewport.left + size / 8 until viewport.left + viewport.width - size / 3 step 4) {
                val color = frame[x, y]
                val low = minOf(channel(color, 0), channel(color, 1), channel(color, 2))
                val high = maxOf(channel(color, 0), channel(color, 1), channel(color, 2))
                if (low >= 238 && high - low <= 20) blank++
                samples++
            }
        }
        return samples > 0 && blank.toDouble() / samples >= 0.98
    }

    private fun observeScrollbar(
        frame: PixelImage,
        trackReference: EntryReferenceRect,
    ): LabyrinthBattleScrollbarObservation {
        val nominalTrack = ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = trackReference,
        ) ?: EntryPixelRect(0, 0, 3, 3)
        fun thumbRun(track: EntryPixelRect): Pair<Int, Int>? {
            val blueRows = BooleanArray(track.height) { row ->
                var blue = 0
                var samples = 0
                var x = track.left
                while (x < track.left + track.width) {
                    if (isScrollbarThumbBlue(frame[x, track.top + row])) blue++
                    samples++
                    x += 2
                }
                samples > 0 && blue.toDouble() / samples >= SCROLLBAR_ROW_BLUE_MIN
            }
            return longestRun(blueRows)?.takeIf { (top, bottom) ->
                bottom - top >= maxOf(12, track.height / 12)
            }
        }
        // Window captures can shift the narrow scrollbar beyond the calibrated x coordinate.
        // Search nearby vertical strips, rejecting short horizontal dividers as thumb evidence.
        val radius = nominalTrack.width * 2
        val stripWidth = maxOf(3, nominalTrack.width / 4)
        val candidates = (maxOf(0, nominalTrack.left - radius)..
            minOf(frame.width - stripWidth, nominalTrack.left + radius)).mapNotNull { left ->
            val candidate = nominalTrack.copy(left = left, width = stripWidth)
            thumbRun(candidate)?.let { candidate to it }
        }
        val best = candidates.maxWithOrNull(compareBy<Pair<EntryPixelRect, Pair<Int, Int>>> {
            it.second.second - it.second.first
        }.thenBy { -abs(it.first.left - nominalTrack.left) })
        val track = best?.first ?: nominalTrack
        val run = best?.second
        val thumb = run?.let { (top, bottomExclusive) ->
            EntryPixelRect(track.left, track.top + top, track.width, bottomExclusive - top)
        }
        val canScroll = thumb != null && thumb.height < track.height * SCROLLBAR_FULL_HEIGHT_RATIO
        val position = if (thumb == null || !canScroll) {
            0.0
        } else {
            val available = (track.height - thumb.height).coerceAtLeast(1)
            ((thumb.top - track.top).toDouble() / available).coerceIn(0.0, 1.0)
        }
        return LabyrinthBattleScrollbarObservation(
            trackRect = track,
            thumbRect = thumb,
            visible = thumb != null,
            canScroll = canScroll,
            position = position,
        )
    }

    private fun cardPresence(frame: PixelImage, rect: EntryPixelRect): Double {
        var colourful = 0
        var samples = 0
        var y = rect.top + 8
        while (y < rect.top + rect.height - 8) {
            var x = rect.left + 8
            while (x < rect.left + rect.width - 8) {
                val color = frame[x, y]
                val red = channel(color, 0)
                val green = channel(color, 1)
                val blue = channel(color, 2)
                if (maxOf(red, green, blue) - minOf(red, green, blue) >= 28 &&
                    (red + green + blue) / 3 < 248
                ) colourful++
                samples++
                x += 6
            }
            y += 6
        }
        return if (samples == 0) 0.0 else colourful.toDouble() / samples
    }

    /**
     * Detects the opening selector's white ordinal badge. The selected card is dimmed, leaving a
     * high-contrast neutral circle with a small blue digit at a stable location. Requiring both
     * luminance and neutral-white contrast keeps yellow favourite stars and pale character art
     * from becoming false selected evidence.
     */
    internal fun openingSelectionMarkerConfidence(frame: PixelImage, rect: EntryPixelRect): Double {
        val scale = rect.width.toDouble()
        if (scale < 16.0 || rect.height < scale * OPENING_MARKER_REQUIRED_HEIGHT_RATIO) return 0.0
        val centerX = rect.left + scale * OPENING_MARKER_CENTER_X_RATIO
        val centerY = rect.top + scale * OPENING_MARKER_CENTER_Y_RATIO
        val innerRadius = scale * OPENING_MARKER_INNER_RADIUS_RATIO
        val outerInnerRadius = scale * OPENING_MARKER_OUTER_INNER_RADIUS_RATIO
        val outerRadius = scale * OPENING_MARKER_OUTER_RADIUS_RATIO
        var innerLuminance = 0.0
        var outerLuminance = 0.0
        var innerNeutralWhite = 0
        var outerNeutralWhite = 0
        var innerBlue = 0
        var innerSamples = 0
        var outerSamples = 0
        val left = maxOf(rect.left, (centerX - outerRadius).toInt(), 0)
        val right = minOf(rect.left + rect.width, (centerX + outerRadius).toInt() + 1, frame.width)
        val top = maxOf(rect.top, (centerY - outerRadius).toInt(), 0)
        val bottom = minOf(rect.top + rect.height, (centerY + outerRadius).toInt() + 1, frame.height)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val distance = kotlin.math.sqrt(
                    (x - centerX) * (x - centerX) + (y - centerY) * (y - centerY),
                )
                val color = frame[x, y]
                val red = channel(color, 0)
                val green = channel(color, 1)
                val blue = channel(color, 2)
                val pixelLuminance = (red * 299 + green * 587 + blue * 114) / 1000.0
                val neutralWhite = minOf(red, green, blue) >= OPENING_MARKER_WHITE_MIN_CHANNEL &&
                    maxOf(red, green, blue) - minOf(red, green, blue) <=
                    OPENING_MARKER_WHITE_MAX_CHANNEL_SPREAD
                if (distance <= innerRadius) {
                    innerLuminance += pixelLuminance
                    if (neutralWhite) innerNeutralWhite++
                    if (
                        blue >= OPENING_MARKER_BLUE_MIN_CHANNEL &&
                        blue - red >= OPENING_MARKER_BLUE_RED_MARGIN &&
                        blue - green >= OPENING_MARKER_BLUE_GREEN_MARGIN
                    ) {
                        innerBlue++
                    }
                    innerSamples++
                } else if (distance in outerInnerRadius..outerRadius) {
                    outerLuminance += pixelLuminance
                    if (neutralWhite) outerNeutralWhite++
                    outerSamples++
                }
                x += OPENING_MARKER_SAMPLE_STEP
            }
            y += OPENING_MARKER_SAMPLE_STEP
        }
        if (innerSamples < OPENING_MARKER_MIN_SAMPLES || outerSamples < OPENING_MARKER_MIN_SAMPLES) return 0.0
        val luminanceContrast =
            innerLuminance / innerSamples - outerLuminance / outerSamples
        val neutralWhiteContrast =
            innerNeutralWhite.toDouble() / innerSamples - outerNeutralWhite.toDouble() / outerSamples
        val blueCoverage = innerBlue.toDouble() / innerSamples
        val luminanceScore = normalizedEvidence(
            luminanceContrast,
            OPENING_MARKER_LUMINANCE_CONTRAST_MIN,
            OPENING_MARKER_LUMINANCE_CONTRAST_FULL,
        )
        val neutralWhiteScore = normalizedEvidence(
            neutralWhiteContrast,
            OPENING_MARKER_WHITE_CONTRAST_MIN,
            OPENING_MARKER_WHITE_CONTRAST_FULL,
        )
        val blueScore = normalizedEvidence(
            blueCoverage,
            OPENING_MARKER_BLUE_COVERAGE_MIN,
            OPENING_MARKER_BLUE_COVERAGE_FULL,
        )
        return (luminanceScore * 0.45 + neutralWhiteScore * 0.45 + blueScore * 0.10)
            .coerceIn(0.0, 1.0)
    }

    internal fun isOpeningCardSelected(frame: PixelImage, rect: EntryPixelRect): Boolean =
        openingSelectionMarkerConfidence(frame, rect) >= OPENING_SELECTED_MARKER_MIN_SCORE

    internal fun isBattleCardSelected(frame: PixelImage, rect: EntryPixelRect): Boolean =
        battleSelectedCheckmarkCoverage(frame, rect) >= SELECTED_CHECKMARK_MIN_SCORE

    private fun normalizedEvidence(value: Double, minimum: Double, full: Double): Double =
        ((value - minimum) / (full - minimum)).coerceIn(0.0, 1.0)

    private fun battleSelectedCheckmarkCoverage(frame: PixelImage, rect: EntryPixelRect): Double {
        val left = rect.left + (rect.width * 0.64).roundToInt()
        val top = rect.top + (rect.height * 0.02).roundToInt()
        val width = (rect.width * 0.40).roundToInt().coerceAtLeast(1)
        val height = (rect.height * 0.46).roundToInt().coerceAtLeast(1)
        var yellow = 0
        var neutralWhite = 0
        var samples = 0
        var y = top
        while (y < top + height && y < frame.height) {
            var x = left
            while (x < left + width && x < frame.width) {
                val color = frame[x, y]
                val red = channel(color, 0)
                val green = channel(color, 1)
                val blue = channel(color, 2)
                if (red > 175 && green > 125 && blue < 165 && red - blue > 55) yellow++
                if (red > 215 && green > 215 && blue > 215 && maxOf(red, green, blue) - minOf(red, green, blue) < 28) {
                    neutralWhite++
                }
                samples++
                x += 3
            }
            y += 3
        }
        if (samples == 0) return 0.0
        val yellowCoverage = yellow.toDouble() / samples
        val whiteCoverage = neutralWhite.toDouble() / samples
        // The real marker has a golden outline and a large white interior. Requiring both avoids
        // treating orange/blonde portrait artwork as a selected checkmark.
        return minOf(
            normalizedEvidence(yellowCoverage, CHECKMARK_YELLOW_MIN, CHECKMARK_YELLOW_FULL),
            normalizedEvidence(whiteCoverage, CHECKMARK_WHITE_MIN, CHECKMARK_WHITE_FULL),
        )
    }

    private fun blueCoverage(frame: PixelImage, rect: EntryPixelRect): Double {
        var blue = 0
        var samples = 0
        var y = rect.top
        while (y < rect.top + rect.height && y < frame.height) {
            var x = rect.left
            while (x < rect.left + rect.width && x < frame.width) {
                if (isBlue(frame[x, y])) blue++
                samples++
                x += 4
            }
            y += 4
        }
        return if (samples == 0) 0.0 else blue.toDouble() / samples
    }

    private fun isBlue(color: Int): Boolean {
        val red = channel(color, 0)
        val green = channel(color, 1)
        val blue = channel(color, 2)
        return blue >= 120 && blue - red >= 24 && green - red >= 8
    }

    /**
     * The scrollbar thumb is a saturated sky blue (roughly r 85-136, g 133-201, b 240-255), but the
     * empty track behind it is a grey-blue (roughly 148,154,170) that sits within one or two units
     * of the generic [isBlue] rule. Live captures crossed that rule on the whole track, which turned
     * the entire track into a full-height "thumb" and made a scrollable roster look like a single
     * page. Require a clearly saturated blue for thumb rows; the filter-tab coverage keeps the
     * looser rule.
     */
    private fun isScrollbarThumbBlue(color: Int): Boolean {
        val red = channel(color, 0)
        val green = channel(color, 1)
        val blue = channel(color, 2)
        return blue >= SCROLLBAR_THUMB_MIN_BLUE &&
            blue - red >= SCROLLBAR_THUMB_MIN_BLUE_MINUS_RED &&
            green - red >= SCROLLBAR_THUMB_MIN_GREEN_MINUS_RED
    }

    private fun longestRun(values: BooleanArray): Pair<Int, Int>? {
        var bestStart = -1
        var bestEnd = -1
        var start = -1
        values.forEachIndexed { index, value ->
            if (value && start < 0) start = index
            if ((!value || index == values.lastIndex) && start >= 0) {
                val end = if (value && index == values.lastIndex) index + 1 else index
                if (end - start > bestEnd - bestStart) {
                    bestStart = start
                    bestEnd = end
                }
                start = -1
            }
        }
        return if (bestStart >= 0) bestStart to bestEnd else null
    }

    private fun channel(color: Int, channel: Int): Int = when (channel) {
        0 -> color ushr 16 and 0xff
        1 -> color ushr 8 and 0xff
        else -> color and 0xff
    }

    private data class Slot(
        val id: String,
        val referenceRect: EntryReferenceRect? = null,
        val screenRect: EntryPixelRect? = null,
        val recognitionRect: EntryPixelRect? = null,
        val allowTopClip: Boolean = false,
        val allowBottomClip: Boolean = false,
    ) {
        init {
            require(referenceRect != null || screenRect != null)
        }
    }

    private data class BattleTeamLayout(
        val currentFilter: LabyrinthBattleElementFilter,
        val filters: List<LabyrinthBattleFilterObservation>,
        val visibleSlots: List<Slot>,
        val selectedSlots: List<Slot>,
        val scrollbar: LabyrinthBattleScrollbarObservation,
        val viewport: EntryReferenceRect = CHARACTER_LIST_VIEWPORT,
        val bossTeamIndex: Int? = null,
        val bossTeamTabs: List<EntryPixelRect> = emptyList(),
    )

    private data class RosterLayoutProfile(
        val viewport: EntryReferenceRect,
        val scrollbarTrack: EntryReferenceRect,
        val fixedLayouts: List<FixedRosterLayout>,
        val slotHeight: Int = AVAILABLE_SLOT_SIZE,
        val columnPitch: Int = AVAILABLE_COLUMN_PITCH,
        val rowPitch: Int = AVAILABLE_ROW_PITCH,
        val maxColumns: Int = AVAILABLE_SLOT_X.size,
        val secondaryRowTopAdjustment: Int = 0,
        val selectedViewport: EntryReferenceRect? = null,
        val fallbackSelectedSlots: List<Slot> = emptyList(),
        val filters: List<FilterDefinition> = FILTERS,
    )

    private data class FixedRosterLayout(
        val minimumScrollbarPosition: Double,
        val rowTops: IntArray,
        val allowTopClipOnFirstRow: Boolean = false,
        val allowBottomClipOnLastRow: Boolean = false,
    )

    private data class SecondaryRowCandidate(
        val referenceY: Int,
        val score: Double,
        val edge: Double,
        val below: Double,
        val depth: Double,
    )

    private data class FilterDefinition(
        val filter: LabyrinthBattleElementFilter,
        val referenceRect: EntryReferenceRect,
    )

    private companion object {
        const val ROSTER_OVERSIZE_RATIO = 1.25
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val FILTERS = listOf(
            FilterDefinition(LabyrinthBattleElementFilter.ALL, EntryReferenceRect(70, 140, 150, 80)),
            FilterDefinition(LabyrinthBattleElementFilter.FIRE, EntryReferenceRect(220, 140, 146, 80)),
            FilterDefinition(LabyrinthBattleElementFilter.WATER, EntryReferenceRect(366, 140, 146, 80)),
            FilterDefinition(LabyrinthBattleElementFilter.WIND, EntryReferenceRect(512, 140, 146, 80)),
            FilterDefinition(LabyrinthBattleElementFilter.LIGHT, EntryReferenceRect(658, 140, 146, 80)),
            FilterDefinition(LabyrinthBattleElementFilter.DARK, EntryReferenceRect(804, 140, 146, 80)),
            FilterDefinition(LabyrinthBattleElementFilter.EFFECTIVE_EFFECT, EntryReferenceRect(950, 140, 160, 80)),
        )
        val PRIMARY_SLOT_LAYOUTS = listOf(
            listOf(
                Slot("available_1", EntryReferenceRect(122, 243, 195, 195)),
                Slot("available_2", EntryReferenceRect(334, 243, 195, 195)),
                Slot("available_3", EntryReferenceRect(545, 243, 195, 195)),
                Slot("available_4", EntryReferenceRect(757, 243, 195, 195)),
                Slot("available_5", EntryReferenceRect(969, 243, 195, 195)),
                Slot("available_6", EntryReferenceRect(1181, 243, 195, 195)),
                Slot("available_7", EntryReferenceRect(1393, 243, 195, 195)),
                Slot("available_8", EntryReferenceRect(1605, 243, 195, 195)),
            ),
            listOf(
                Slot("available_1", EntryReferenceRect(122, 356, 195, 195)),
                Slot("available_2", EntryReferenceRect(334, 356, 195, 195)),
                Slot("available_3", EntryReferenceRect(545, 356, 195, 195)),
                Slot("available_4", EntryReferenceRect(757, 356, 195, 195)),
                Slot("available_5", EntryReferenceRect(969, 356, 195, 195)),
                Slot("available_6", EntryReferenceRect(1181, 356, 195, 195)),
                Slot("available_7", EntryReferenceRect(1393, 356, 195, 195)),
                Slot("available_8", EntryReferenceRect(1605, 356, 195, 195)),
            ),
            listOf(
                Slot("available_1", EntryReferenceRect(122, 486, 195, 195)),
                Slot("available_2", EntryReferenceRect(334, 486, 195, 195)),
                Slot("available_3", EntryReferenceRect(545, 486, 195, 195)),
                Slot("available_4", EntryReferenceRect(757, 486, 195, 195)),
                Slot("available_5", EntryReferenceRect(969, 486, 195, 195)),
                Slot("available_6", EntryReferenceRect(1181, 486, 195, 195)),
                Slot("available_7", EntryReferenceRect(1393, 486, 195, 195)),
                Slot("available_8", EntryReferenceRect(1605, 486, 195, 195)),
            ),
        )
        val AVAILABLE_SLOT_X = intArrayOf(122, 334, 545, 757, 969, 1181, 1393, 1605)
        const val AVAILABLE_SLOT_SIZE = 195
        const val AVAILABLE_COLUMN_PITCH = 212
        val CHARACTER_LIST_VIEWPORT = EntryReferenceRect(60, 230, 1800, 516)
        val CURRENT_MEMBER_LIST_VIEWPORT = EntryReferenceRect(70, 795, 1110, 220)
        // The opening roster occupies y=269..875. At the final scrollbar stop its first virtual
        // row starts above this viewport, while at the preceding stop its third row crosses the
        // bottom edge. Do not extend into the filters or Map/Character/Invite controls.
        val OPENING_CHARACTER_LIST_VIEWPORT = EntryReferenceRect(60, 269, 1800, 606)
        const val DEFAULT_PRIMARY_ROW_TOP = 243
        // The two-row team editor uses a taller pitch than the single-row search layout. This
        // pitch also remains stable when the first row is partially clipped during scrolling.
        const val AVAILABLE_ROW_PITCH = 218
        const val OPENING_ROW_PITCH = 212
        const val CURRENT_MEMBER_COLUMN_PITCH = 218
        val BOTTOM_VISIBLE_ROW_TOPS = intArrayOf(315, 533)
        // MuMu/CN opening selector default entry stop: the search row is collapsed, the first two
        // rows are full-size, and a third trailing row crosses the roster panel's lower edge.
        // Blank columns are discarded later by cardPresence().
        val OPENING_INITIAL_VISIBLE_ROW_TOPS = intArrayOf(282, 494, 706)
        // A downward swipe to the true bottom moves the same rows upward: the first row is clipped
        // by the panel header and the last row becomes fully visible.
        val OPENING_FINAL_VISIBLE_ROW_TOPS = intArrayOf(243, 454, 665)
        const val BOTTOM_SCROLLBAR_POSITION_MIN = 0.95
        const val OPENING_INITIAL_SCROLLBAR_POSITION_MIN = 0.65
        const val SEARCH_LAYOUT_PRIMARY_TOP_MIN = 430
        const val SEARCH_LAYOUT_PRIMARY_EDGE_MIN_SCORE = 60.0
        const val SECONDARY_ROW_SCAN_START = 350
        const val SECONDARY_ROW_SCAN_END = 820
        const val SECONDARY_EDGE_LOOKAHEAD = 4
        const val SECONDARY_BAND_HEIGHT = 20
        const val SECONDARY_DEPTH_HEIGHT = 100
        val CURRENT_MEMBER_SLOTS = listOf(
            Slot("current_member_1", EntryReferenceRect(95, 811, 195, 195)),
            Slot("current_member_2", EntryReferenceRect(314, 811, 195, 195)),
            Slot("current_member_3", EntryReferenceRect(532, 811, 195, 195)),
            Slot("current_member_4", EntryReferenceRect(750, 811, 195, 195)),
            Slot("current_member_5", EntryReferenceRect(969, 811, 195, 195)),
        )
        val SCROLLBAR_TRACK = EntryReferenceRect(1825, 235, 28, 500)
        // The opening selector uses a taller roster panel than battle team selection. Keep the
        // complete y=290..852 travel range so the default entry stop (~0.73) remains distinct from
        // the true bottom stop (1.0).
        val OPENING_SCROLLBAR_TRACK = EntryReferenceRect(1825, 290, 28, 562)
        val BATTLE_LAYOUT_PROFILE = RosterLayoutProfile(
            viewport = CHARACTER_LIST_VIEWPORT,
            scrollbarTrack = SCROLLBAR_TRACK,
            fixedLayouts = listOf(
                FixedRosterLayout(
                    minimumScrollbarPosition = BOTTOM_SCROLLBAR_POSITION_MIN,
                    rowTops = BOTTOM_VISIBLE_ROW_TOPS,
                ),
            ),
            selectedViewport = CURRENT_MEMBER_LIST_VIEWPORT,
            fallbackSelectedSlots = CURRENT_MEMBER_SLOTS,
        )
        val BOSS_TEAM_TABS = listOf(EntryReferenceRect(98, 150, 195, 65),
            EntryReferenceRect(334, 150, 195, 65), EntryReferenceRect(571, 150, 195, 65))
        val BOSS_LAYOUT_PROFILE = RosterLayoutProfile(
            viewport = EntryReferenceRect(60, 360, 1800, 386),
            scrollbarTrack = EntryReferenceRect(1825, 377, 28, 349),
            fixedLayouts = listOf(FixedRosterLayout(BOTTOM_SCROLLBAR_POSITION_MIN,
                intArrayOf(315, 533), allowTopClipOnFirstRow = true)),
            selectedViewport = CURRENT_MEMBER_LIST_VIEWPORT,
            fallbackSelectedSlots = CURRENT_MEMBER_SLOTS,
            filters = FILTERS.map { it.copy(referenceRect = it.referenceRect.copy(y = 280, height = 66)) },
        )
        val OPENING_LAYOUT_PROFILE = RosterLayoutProfile(
            viewport = OPENING_CHARACTER_LIST_VIEWPORT,
            scrollbarTrack = OPENING_SCROLLBAR_TRACK,
            fixedLayouts = listOf(
                FixedRosterLayout(
                    minimumScrollbarPosition = BOTTOM_SCROLLBAR_POSITION_MIN,
                    rowTops = OPENING_FINAL_VISIBLE_ROW_TOPS,
                    allowTopClipOnFirstRow = true,
                ),
                FixedRosterLayout(
                    minimumScrollbarPosition = OPENING_INITIAL_SCROLLBAR_POSITION_MIN,
                    rowTops = OPENING_INITIAL_VISIBLE_ROW_TOPS,
                    allowBottomClipOnLastRow = true,
                ),
            ),
            rowPitch = OPENING_ROW_PITCH,
            // Opening cards have a name/rank strip above the portrait. The generic battle edge
            // scan locks onto that strip about 24 px before the actual square card frame.
            secondaryRowTopAdjustment = 24,
        )
        const val FILTER_SELECTED_MIN_SCORE = 0.35
        const val CARD_PRESENCE_MIN_SCORE = 0.055
        const val OPENING_CARD_MIN_VISIBLE_RATIO = 0.70
        const val OPENING_SELECTED_MARKER_MIN_SCORE = 0.72
        const val OPENING_CACHE_SCROLL_TOLERANCE = 0.02
        const val OPENING_MARKER_REQUIRED_HEIGHT_RATIO = 0.42
        const val OPENING_MARKER_CENTER_X_RATIO = 0.83
        const val OPENING_MARKER_CENTER_Y_RATIO = 0.20
        const val OPENING_MARKER_INNER_RADIUS_RATIO = 0.16
        const val OPENING_MARKER_OUTER_INNER_RADIUS_RATIO = 0.205
        const val OPENING_MARKER_OUTER_RADIUS_RATIO = 0.26
        const val OPENING_MARKER_SAMPLE_STEP = 2
        const val OPENING_MARKER_MIN_SAMPLES = 24
        const val OPENING_MARKER_WHITE_MIN_CHANNEL = 185
        const val OPENING_MARKER_WHITE_MAX_CHANNEL_SPREAD = 55
        const val OPENING_MARKER_BLUE_MIN_CHANNEL = 105
        const val OPENING_MARKER_BLUE_RED_MARGIN = 18
        const val OPENING_MARKER_BLUE_GREEN_MARGIN = 6
        const val OPENING_MARKER_LUMINANCE_CONTRAST_MIN = 45.0
        const val OPENING_MARKER_LUMINANCE_CONTRAST_FULL = 100.0
        const val OPENING_MARKER_WHITE_CONTRAST_MIN = 0.30
        const val OPENING_MARKER_WHITE_CONTRAST_FULL = 0.70
        const val OPENING_MARKER_BLUE_COVERAGE_MIN = 0.005
        const val OPENING_MARKER_BLUE_COVERAGE_FULL = 0.04
        const val SELECTED_CHECKMARK_MIN_SCORE = 0.45
        const val CHECKMARK_YELLOW_MIN = 0.018
        const val CHECKMARK_YELLOW_FULL = 0.070
        const val CHECKMARK_WHITE_MIN = 0.025
        const val CHECKMARK_WHITE_FULL = 0.120
        const val SCROLLBAR_ROW_BLUE_MIN = 0.25
        const val SCROLLBAR_FULL_HEIGHT_RATIO = 0.92
        const val SCROLLBAR_THUMB_MIN_BLUE = 140
        const val SCROLLBAR_THUMB_MIN_BLUE_MINUS_RED = 45
        const val SCROLLBAR_THUMB_MIN_GREEN_MINUS_RED = 15
        const val ICON_CARD_INSET_LEFT = 6
        const val ICON_CARD_INSET_TOP = 6
        const val ICON_CARD_HORIZONTAL_INSET = 14
        const val ATTRIBUTE_BADGE_LEFT_RATIO = 0.78
        const val ATTRIBUTE_BADGE_TOP_RATIO = 0.76
        const val ATTRIBUTE_BADGE_WIDTH_RATIO = 0.19
        const val ATTRIBUTE_BADGE_HEIGHT_RATIO = 0.21
        const val ATTRIBUTE_MIN_SATURATION = 0.30
        const val ATTRIBUTE_MIN_VALUE = 0.20
        const val ATTRIBUTE_MIN_SIGNAL = 0.10
        const val ATTRIBUTE_MIN_CONFIDENCE = 0.72
        const val ATTRIBUTE_MIN_MARGIN = 0.10
        const val TWO_PI = Math.PI * 2.0
        val ATTRIBUTE_HUES = mapOf(
            LabyrinthCharacterAttribute.FIRE to 0.021,
            LabyrinthCharacterAttribute.DARK to 0.729,
            LabyrinthCharacterAttribute.LIGHT to 0.104,
            LabyrinthCharacterAttribute.WIND to 0.271,
            LabyrinthCharacterAttribute.WATER to 0.604,
        )
        const val SECONDARY_EDGE_MIN_SCORE = 140.0
        const val SECONDARY_BAND_MIN_SCORE = 0.30
        const val SECONDARY_DEPTH_MIN_SCORE = 0.50
        const val SIGNATURE_OFFSET_BASIS = -3750763034362895579L
        const val SIGNATURE_PRIME = 1099511628211L
        const val SIGNATURE_POSITION_QUANTIZATION = 4
        const val SIGNATURE_POSITION_BUCKETS = 32

        /**
         * How many frames a measured second-row anchor may be reused inside one scroll-position
         * bucket. Scrolling out of the bucket invalidates the cache immediately; this only bounds
         * how long a scroll that stays inside one bucket can go unnoticed.
         */
        const val MAX_SECONDARY_ROW_TOP_REUSE_FRAMES = 12

        /** Frames a detected card grid may be reused before it is measured again. */
        const val MAX_GRID_REUSE_FRAMES = 8

        /**
         * Consecutive fingerprint mismatches tolerated before the grid cache is dropped.
         *
         * Selecting a card dims it, which shifts the sampled portrait pixels without moving the
         * card frame; one such toggle must not force a full grid detection.
         */
        const val MAX_GRID_FINGERPRINT_MISSES = 2
    }
}

private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
