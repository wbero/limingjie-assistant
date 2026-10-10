package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import java.util.IdentityHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Shared safety floor for every page that identifies a character from the full icon pack. */
const val LABYRINTH_CHARACTER_ICON_SAFE_CONFIDENCE = 0.35

/** Shared separation required between the best character and the best different character. */
const val LABYRINTH_CHARACTER_ICON_SAFE_RIVAL_MARGIN = 0.045

data class LabyrinthCharacterIconMask(
    val ignoreRightEdge: Boolean = false,
    val ignoreTopLeft: Boolean = false,
    val ignoreTopRight: Boolean = false,
    val ignoreBottomLeft: Boolean = false,
    val ignoreBottomBand: Boolean = false,
    val minYRatio: Double = 0.0,
    val maxYRatio: Double = 1.0,
)

data class LabyrinthCharacterIconCandidate(
    val characterId: String,
    val displayName: String,
    val iconVariant: String,
    val confidence: Double,
    val aliases: List<String> = emptyList(),
)

data class LabyrinthCharacterIconMatch(
    val characterId: String?,
    val displayName: String?,
    val iconVariant: String?,
    val confidence: Double,
    val rivalConfidence: Double,
    val rivalMargin: Double,
    val trusted: Boolean,
    val suspectedCharacterId: String?,
    val suspectedDisplayName: String?,
    val candidates: List<LabyrinthCharacterIconCandidate> = emptyList(),
)

/**
 * Matches the stable centre of an observed square character icon against the complete icon pack.
 *
 * All role-related pages provide only the full icon rectangle. Page-specific card frames, labels,
 * buttons and list geometry stay outside this class. Different star variants of one character do
 * not compete with each other when the rival margin is calculated.
 */
class LabyrinthCharacterIconMatcher(
    private val templates: List<LabyrinthBattleCharacterTemplate> = emptyList(),
    private val minimumConfidence: Double = LABYRINTH_CHARACTER_ICON_SAFE_CONFIDENCE,
    private val minimumRivalMargin: Double = LABYRINTH_CHARACTER_ICON_SAFE_RIVAL_MARGIN,
) {
    init {
        require(minimumConfidence in 0.0..1.0)
        require(minimumRivalMargin in 0.0..1.0)
    }

    /**
     * Scratch buffers for the expected (pack-icon) side of the two scoring passes.
     *
     * Both passes fill their sample arrays and consume them inside a single call -- [correlation]
     * reads the first `count` entries and nothing keeps the arrays afterwards -- so one set of
     * buffers per pass is reused instead of allocating them per candidate template. On the real
     * pack that is roughly 9 900 arrays (a few megabytes) of garbage per [match].
     *
     * This relies on the recognition pipeline handling one frame at a time, the same assumption the
     * shared [GradientTemplateMatcher] offset caches already make. If a later step parallelises this
     * class, these have to become per-thread or be allocated per call again.
     */
    private val coarseExpectedScratch = Array(3) { DoubleArray(COARSE_SAMPLE_COUNT) }
    private val scoreColorExpectedScratch = Array(3) { DoubleArray(SCORE_SAMPLE_COUNT) }
    private val scoreLuminanceExpectedScratch = DoubleArray(SCORE_SAMPLE_COUNT)
    private val scoreGradientExpectedScratch = DoubleArray(SCORE_SAMPLE_COUNT)

    /**
     * Sample values hoisted out of the scoring loops because they cannot change.
     *
     * Both passes walk a fixed sample grid and derive, for every cell, three colour channels, a
     * luminance and a local gradient (four more luminance lookups) from one pixel. Two of the three
     * inputs to that work are constant:
     *
     *  - the pack-icon side depends only on the template and the grid, and a template never changes;
     *  - the observed side depends only on the comparison rectangle and the mask, and the pack ships
     *    icons of a single size, so every candidate inside one [match] reads the very same pixels of
     *    the same frame.
     *
     * Deriving each value once instead of once per candidate removes about 1.2 million luminance
     * conversions per [match] on the real pack. Every cached number is the same pure function of the
     * same pixels and the correlation inputs are still filled in the original order, so the scores
     * stay bit-identical.
     *
     * Same single-frame-at-a-time assumption as the buffers above.
     */
    private class GridSamples(gridSize: Int) {
        val color = Array(3) { DoubleArray(gridSize) }
        val luminance = DoubleArray(gridSize)
        val gradient = DoubleArray(gridSize)
    }

    /** Pack-icon samples, one entry per template, built once because a template never changes. */
    private val templateSamples =
        IdentityHashMap<LabyrinthBattleCharacterTemplate, GridSamples>()

    /**
     * Observed-side samples of the most recent call. The pack ships icons of one size, so every
     * candidate inside one [match] reads the same pixels of the same frame; the frame is compared by
     * reference, so samples can never be served for a different frame.
     */
    private var coarseObservedFrame: PixelImage? = null
    private var coarseObservedTarget: EntryPixelRect? = null
    private var coarseObservedMask: LabyrinthCharacterIconMask? = null
    private var coarseObservedSamples: GridSamples? = null

    /** Same for the scoring pass, which additionally carries luminance and gradient. */
    private var scoreObservedFrame: PixelImage? = null
    private var scoreObservedTarget: EntryPixelRect? = null
    private var scoreObservedMask: LabyrinthCharacterIconMask? = null
    private var scoreObservedSamples: GridSamples? = null

    /**
     * @param rosterCharacterIds the characters that can actually appear here, or null for any.
     *
     * A labyrinth run's team pages can only show roles that joined that run: roughly 30 of the
     * ~800 icons in the pack. The other 770 are not merely unlikely, they are impossible, and
     * letting them compete costs real identifications — an impossible rival that scores close to
     * the true role eats the margin gate and the slot comes back unidentified, which blocks the
     * whole team plan (2026-09-20 bundle 204642: 成员2 unresolved for 162 consecutive frames while
     * 祈梨（怪盗） was sitting in the run's own joined list).
     *
     * The roster narrows which candidates may *win* and may *veto*; it never lowers the absolute
     * confidence bar, and [ROSTER_OUTSIDER_OVERRIDE_MARGIN] still hands the frame back to the full
     * pack when the best icon overall is clearly outside the roster — that means the roster itself
     * is incomplete, and a confident wrong answer would be worse than an unidentified slot.
     */
    fun match(
        frame: PixelImage,
        iconRect: EntryPixelRect,
        mask: LabyrinthCharacterIconMask = LabyrinthCharacterIconMask(),
        requiredAttribute: LabyrinthCharacterAttribute? = null,
        rosterCharacterIds: Set<String>? = null,
    ): LabyrinthCharacterIconMatch {
        val candidateTemplates = if (requiredAttribute == null) {
            templates
        } else {
            // Attribute is auxiliary evidence from the visible card badge. Keep templates whose
            // attribute metadata is still unknown so incomplete data never makes a real role
            // impossible to recognize, while known mismatched attributes stop competing.
            templates.filter { template ->
                template.attribute == null || template.attribute == requiredAttribute
            }
        }
        val coarse = candidateTemplates
            .map { template -> template to coarseIconScore(frame, iconRect, template, mask) }
            .sortedByDescending { (_, score) -> score }
        // The coarse pass is cheap and still sees the whole pack, so it can answer whether the
        // roster is trustworthy before the expensive pass commits to it.
        val roster = rosterCharacterIds?.takeIf(Set<String>::isNotEmpty)
        val insideRoster = if (roster == null) coarse else coarse.filter { (template, _) ->
            template.characterId in roster
        }
        val rosterUsable = roster != null && insideRoster.isNotEmpty() &&
            (coarse.firstOrNull()?.second ?: 0.0) - (insideRoster.firstOrNull()?.second ?: 0.0) <
            ROSTER_OUTSIDER_OVERRIDE_MARGIN
        val considered = if (rosterUsable) insideRoster else coarse
        val coarseCutoff = (considered.firstOrNull()?.second ?: 0.0) * COARSE_KEEP_RATIO
        val ranked = considered
            .filterIndexed { index, (_, score) -> index < MIN_ICON_CANDIDATES || score >= coarseCutoff }
            .take(MAX_ICON_CANDIDATES)
            .map { (template, _) -> template to iconScore(frame, iconRect, template, mask) }
            .sortedByDescending { (_, score) -> score }
        // Keep only the strongest icon variant for each logical character. A character can have
        // multiple star/rarity icon variants and those variants must never compete with each other
        // when we calculate the rival margin or expose the short list to the name verifier.
        val characterCandidates = ranked
            .groupBy { (template, _) -> template.characterId }
            .mapNotNull { (_, variants) ->
                variants.maxByOrNull { (_, score) -> score }?.let { (template, score) ->
                    LabyrinthCharacterIconCandidate(
                        characterId = template.characterId,
                        displayName = template.displayName,
                        iconVariant = template.iconVariant,
                        confidence = score,
                        aliases = template.aliases,
                    )
                }
            }
            .sortedByDescending(LabyrinthCharacterIconCandidate::confidence)
        val best = characterCandidates.firstOrNull()
        val rivalScore = characterCandidates.getOrNull(1)?.confidence ?: 0.0
        val bestScore = best?.confidence ?: 0.0
        val rivalMargin = bestScore - rivalScore
        val trusted = best != null &&
            bestScore >= minimumConfidence &&
            rivalMargin >= minimumRivalMargin
        val trustedTemplate = best?.takeIf { trusted }
        return LabyrinthCharacterIconMatch(
            characterId = trustedTemplate?.characterId,
            displayName = trustedTemplate?.displayName,
            iconVariant = trustedTemplate?.iconVariant,
            confidence = bestScore,
            rivalConfidence = rivalScore,
            rivalMargin = rivalMargin,
            trusted = trusted,
            suspectedCharacterId = best?.characterId,
            suspectedDisplayName = best?.displayName,
            candidates = characterCandidates.take(MAX_EXPOSED_CHARACTER_CANDIDATES),
        )
    }

    /**
     * Only failed real-card matches pay for a small bounded geometry calibration.
     *
     * A detected card border can be off by a few pixels after scaling/scroll settling.  Trying
     * only symmetric inset/expand crops misses pure X/Y translations, which is especially visible
     * on the long effective-effect roster where one otherwise valid card can block the complete
     * scan.  Every alternative still has to pass the same global confidence and rival-margin
     * floors in [reconcileGeometryMatches]; this is geometry recovery, not a relaxed identity
     * threshold.
     */
    internal fun refineMemberGeometry(
        frame: PixelImage,
        iconRect: EntryPixelRect,
        initial: LabyrinthCharacterIconMatch,
        mask: LabyrinthCharacterIconMask = LabyrinthCharacterIconMask(
            ignoreRightEdge = true,
            ignoreTopLeft = true,
        ),
        requiredAttribute: LabyrinthCharacterAttribute? = null,
        force: Boolean = false,
        rosterCharacterIds: Set<String>? = null,
    ): LabyrinthCharacterIconMatch {
        if (initial.trusted && !force) return initial
        val radius = (iconRect.width / 90.0).roundToInt().coerceIn(1, 3)
        val deltas = listOf(-radius, -1, 1, radius).distinct()
        val candidateRects = buildList {
            // Border thickness / size calibration.
            deltas.forEach { inset ->
                add(
                    iconRect.copy(
                        left = iconRect.left + inset,
                        top = iconRect.top + inset,
                        width = iconRect.width - inset * 2,
                        height = iconRect.height - inset * 2,
                    ),
                )
            }
            // Pure translation calibration.  These four directions cover the common measured
            // border error without turning recovery into an unbounded local image search.
            deltas.forEach { delta ->
                add(iconRect.copy(left = iconRect.left + delta))
                add(iconRect.copy(top = iconRect.top + delta))
            }
        }.distinct()
        val alternatives = candidateRects.map { rect ->
            match(
                frame = frame,
                iconRect = rect,
                mask = mask,
                requiredAttribute = requiredAttribute,
                rosterCharacterIds = rosterCharacterIds,
            )
        }
        return reconcileGeometryMatches(initial, alternatives, force = force)
    }

    internal fun reconcileGeometryMatches(
        initial: LabyrinthCharacterIconMatch,
        alternatives: List<LabyrinthCharacterIconMatch>,
        force: Boolean = false,
    ): LabyrinthCharacterIconMatch {
        if (initial.trusted && !force) return initial
        // A rival from ANY crop must compete with the winner. Do not accept the first crop that
        // happens to clear the threshold, or compare star variants as different characters.
        val candidates = (listOf(initial) + alternatives).flatMap { it.candidates }
            .groupBy { it.characterId }.values.map { variants -> variants.maxBy { it.confidence } }
            .sortedByDescending { it.confidence }
        val best = candidates.firstOrNull() ?: return initial
        val rival = candidates.getOrNull(1)?.confidence ?: 0.0
        if (best.confidence < minimumConfidence || best.confidence - rival < minimumRivalMargin) return initial
        return LabyrinthCharacterIconMatch(
            characterId = best.characterId, displayName = best.displayName, iconVariant = best.iconVariant,
            confidence = best.confidence, rivalConfidence = rival, rivalMargin = best.confidence - rival,
            trusted = true, suspectedCharacterId = best.characterId, suspectedDisplayName = best.displayName,
            candidates = candidates.take(MAX_EXPOSED_CHARACTER_CANDIDATES),
        )
    }

    private fun coarseIconScore(
        frame: PixelImage,
        iconRect: EntryPixelRect,
        template: LabyrinthBattleCharacterTemplate,
        mask: LabyrinthCharacterIconMask,
    ): Double {
        val target = comparisonRect(frame, iconRect, template.image) ?: return 0.0
        val sampleWidth = COARSE_SAMPLE_WIDTH
        val sampleHeight = COARSE_SAMPLE_HEIGHT
        // The observed grid is shared by every candidate in this match(), so it is computed once
        // here and only the pack side is filled per candidate below.
        val observed = coarseObservedSamplesOf(frame, target, mask).color
        val expected = coarseExpectedScratch
        var count = 0
        repeat(sampleHeight) { sampleY ->
            val yRatio = sampleY.toDouble() / (sampleHeight - 1)
            for (sampleX in 0 until sampleWidth) {
                val xRatio = sampleX.toDouble() / (sampleWidth - 1)
                if (isMasked(xRatio, yRatio, mask)) continue
                val imageX = (xRatio * (template.image.width - 1)).roundToInt()
                val imageY = ICON_CROP_TOP + (yRatio * (ICON_CROP_HEIGHT - 1)).roundToInt()
                val expectedColor = template.image[imageX, imageY]
                for (channel in 0..2) {
                    expected[channel][count] = channel(expectedColor, channel).toDouble()
                }
                count++
            }
        }
        if (count < 8) return 0.0
        return (0..2).map { channel ->
            correlation(observed[channel], expected[channel], count)
        }.average().coerceAtLeast(0.0)
    }

    private fun iconScore(
        frame: PixelImage,
        iconRect: EntryPixelRect,
        template: LabyrinthBattleCharacterTemplate,
        mask: LabyrinthCharacterIconMask,
    ): Double {
        val target = comparisonRect(frame, iconRect, template.image) ?: return 0.0
        // Observed side of the fine grid: same rectangle and mask for every candidate in this
        // match(), and it additionally carries luminance and gradient.
        val observed = scoreObservedSamplesOf(frame, target, mask)
        // Pack side: a template never changes, so its grid is derived once per template and reused
        // for every candidate from then on, in this match() and in later ones.
        val expected = templateSamplesOf(template)
        val colorExpected = scoreColorExpectedScratch
        val luminanceExpected = scoreLuminanceExpectedScratch
        val gradientExpected = scoreGradientExpectedScratch
        var count = 0
        repeat(SCORE_SAMPLE_HEIGHT) { sampleY ->
            val yRatio = sampleY.toDouble() / (SCORE_SAMPLE_HEIGHT - 1)
            for (sampleX in 0 until SCORE_SAMPLE_WIDTH) {
                val xRatio = sampleX.toDouble() / (SCORE_SAMPLE_WIDTH - 1)
                if (isMasked(xRatio, yRatio, mask)) continue
                val grid = sampleY * SCORE_SAMPLE_WIDTH + sampleX
                colorExpected[0][count] = expected.color[0][grid]
                colorExpected[1][count] = expected.color[1][grid]
                colorExpected[2][count] = expected.color[2][grid]
                luminanceExpected[count] = expected.luminance[grid]
                gradientExpected[count] = expected.gradient[grid]
                count++
            }
        }
        if (count < 20) return 0.0
        val colorScore = (0..2).map { channel ->
            correlation(observed.color[channel], colorExpected[channel], count)
        }.average().coerceAtLeast(0.0)
        val luminanceScore = correlation(observed.luminance, luminanceExpected, count).coerceAtLeast(0.0)
        val gradientScore = correlation(observed.gradient, gradientExpected, count).coerceAtLeast(0.0)
        return (colorScore * 0.55 + luminanceScore * 0.25 + gradientScore * 0.20).coerceIn(0.0, 1.0)
    }

    /** The pack-icon side of the scoring grid: constant for the life of the template. */
    private fun templateSamplesOf(template: LabyrinthBattleCharacterTemplate): GridSamples {
        templateSamples[template]?.let { return it }
        val image = template.image
        val bounds = EntryPixelRect(0, ICON_CROP_TOP, image.width, ICON_CROP_HEIGHT)
        val samples = GridSamples(SCORE_SAMPLE_COUNT)
        var grid = 0
        repeat(SCORE_SAMPLE_HEIGHT) { sampleY ->
            val yRatio = sampleY.toDouble() / (SCORE_SAMPLE_HEIGHT - 1)
            for (sampleX in 0 until SCORE_SAMPLE_WIDTH) {
                val xRatio = sampleX.toDouble() / (SCORE_SAMPLE_WIDTH - 1)
                val imageX = (xRatio * (image.width - 1)).roundToInt()
                val imageY = ICON_CROP_TOP + (yRatio * (ICON_CROP_HEIGHT - 1)).roundToInt()
                val pixel = image[imageX, imageY]
                for (channel in 0..2) {
                    samples.color[channel][grid] = channel(pixel, channel).toDouble()
                }
                samples.luminance[grid] = luminance(pixel).toDouble()
                samples.gradient[grid] = localGradient(image, imageX, imageY, bounds)
                grid++
            }
        }
        templateSamples[template] = samples
        return samples
    }

    /** The observed side of the coarse grid for one frame, rectangle and mask. */
    private fun coarseObservedSamplesOf(
        frame: PixelImage,
        target: EntryPixelRect,
        mask: LabyrinthCharacterIconMask,
    ): GridSamples {
        val cached = coarseObservedSamples
        if (cached != null &&
            coarseObservedFrame === frame &&
            coarseObservedTarget == target &&
            coarseObservedMask == mask
        ) {
            return cached
        }
        val samples =
            fillObservedSamples(frame, target, mask, COARSE_SAMPLE_WIDTH, COARSE_SAMPLE_HEIGHT)
        coarseObservedFrame = frame
        coarseObservedTarget = target
        coarseObservedMask = mask
        coarseObservedSamples = samples
        return samples
    }

    /** The observed side of the scoring grid for one frame, rectangle and mask. */
    private fun scoreObservedSamplesOf(
        frame: PixelImage,
        target: EntryPixelRect,
        mask: LabyrinthCharacterIconMask,
    ): GridSamples {
        val cached = scoreObservedSamples
        if (cached != null &&
            scoreObservedFrame === frame &&
            scoreObservedTarget == target &&
            scoreObservedMask == mask
        ) {
            return cached
        }
        val samples = fillObservedSamples(
            frame = frame,
            target = target,
            mask = mask,
            sampleWidth = SCORE_SAMPLE_WIDTH,
            sampleHeight = SCORE_SAMPLE_HEIGHT,
            withLuminanceAndGradient = true,
        )
        scoreObservedFrame = frame
        scoreObservedTarget = target
        scoreObservedMask = mask
        scoreObservedSamples = samples
        return samples
    }

    /** Fills one observed-side grid from the frame, in the same order the loops used to. */
    private fun fillObservedSamples(
        frame: PixelImage,
        target: EntryPixelRect,
        mask: LabyrinthCharacterIconMask,
        sampleWidth: Int,
        sampleHeight: Int,
        withLuminanceAndGradient: Boolean = false,
    ): GridSamples {
        val samples = GridSamples(sampleWidth * sampleHeight)
        var count = 0
        repeat(sampleHeight) { sampleY ->
            val yRatio = sampleY.toDouble() / (sampleHeight - 1)
            for (sampleX in 0 until sampleWidth) {
                val xRatio = sampleX.toDouble() / (sampleWidth - 1)
                if (isMasked(xRatio, yRatio, mask)) continue
                val frameX = target.left + (xRatio * (target.width - 1)).roundToInt()
                val frameY = target.top + (yRatio * (target.height - 1)).roundToInt()
                val pixel = frame[frameX, frameY]
                for (channel in 0..2) {
                    samples.color[channel][count] = channel(pixel, channel).toDouble()
                }
                if (withLuminanceAndGradient) {
                    samples.luminance[count] = luminance(pixel).toDouble()
                    samples.gradient[count] = localGradient(frame, frameX, frameY, target)
                }
                count++
            }
        }
        return samples
    }

    private fun comparisonRect(
        frame: PixelImage,
        iconRect: EntryPixelRect,
        template: PixelImage,
    ): EntryPixelRect? {
        if (template.width < 3 || template.height < ICON_CROP_TOP + ICON_CROP_HEIGHT) return null
        val top = iconRect.top +
            (iconRect.height * ICON_CROP_TOP.toDouble() / template.height).roundToInt()
        val height =
            (iconRect.height * ICON_CROP_HEIGHT.toDouble() / template.height).roundToInt()
                .coerceAtLeast(16)
        val target = EntryPixelRect(iconRect.left, top, iconRect.width, height)
        return target.takeIf {
            it.left >= 0 && it.top >= 0 &&
                it.left + it.width <= frame.width && it.top + it.height <= frame.height
        }
    }

    private fun isMasked(xRatio: Double, yRatio: Double, mask: LabyrinthCharacterIconMask): Boolean =
        yRatio < mask.minYRatio ||
            yRatio > mask.maxYRatio ||
            (mask.ignoreRightEdge && xRatio > RIGHT_EDGE_MAX_X_RATIO) ||
            (mask.ignoreTopLeft &&
                xRatio <= TOP_LEFT_OVERLAY_IGNORE_X_RATIO &&
                yRatio <= TOP_LEFT_OVERLAY_IGNORE_Y_RATIO) ||
            (mask.ignoreTopRight &&
                xRatio >= TOP_RIGHT_OVERLAY_IGNORE_X_RATIO &&
                yRatio <= TOP_RIGHT_OVERLAY_IGNORE_Y_RATIO) ||
            (mask.ignoreBottomLeft &&
                xRatio <= BOTTOM_LEFT_OVERLAY_IGNORE_X_RATIO &&
                yRatio >= BOTTOM_LEFT_OVERLAY_IGNORE_Y_RATIO) ||
            (mask.ignoreBottomBand && yRatio >= BOTTOM_BAND_OVERLAY_IGNORE_Y_RATIO)

    private fun localGradient(image: PixelImage, x: Int, y: Int, bounds: EntryPixelRect): Double {
        val right = (x + 1).coerceAtMost(bounds.left + bounds.width - 1)
        val bottom = (y + 1).coerceAtMost(bounds.top + bounds.height - 1)
        val previousX = (x - 1).coerceAtLeast(bounds.left)
        val previousY = (y - 1).coerceAtLeast(bounds.top)
        return kotlin.math.abs(luminance(image[right, y]) - luminance(image[previousX, y])).toDouble() +
            kotlin.math.abs(luminance(image[x, bottom]) - luminance(image[x, previousY])).toDouble()
    }

    /**
     * Pearson correlation over the first [count] entries.
     *
     * The two means are accumulated with an explicit loop instead of `take(count).average()`. Both
     * forms compute `sum / count` with the sum accumulated from index 0 in the same order, so the
     * result is bit-identical -- but `take()` copied every element into a boxed `Double` (growing an
     * `ArrayList` on the way) purely to add numbers up. On the real 795-icon pack that is about
     * 1.75 million throwaway objects per [match], and it is the single largest cost this class has.
     */
    private fun correlation(left: DoubleArray, right: DoubleArray, count: Int): Double {
        if (count <= 1) return 0.0
        var leftSum = 0.0
        var rightSum = 0.0
        var index = 0
        while (index < count) {
            leftSum += left[index]
            rightSum += right[index]
            index++
        }
        val leftMean = leftSum / count
        val rightMean = rightSum / count
        var numerator = 0.0
        var leftSquare = 0.0
        var rightSquare = 0.0
        index = 0
        while (index < count) {
            val leftDelta = left[index] - leftMean
            val rightDelta = right[index] - rightMean
            numerator += leftDelta * rightDelta
            leftSquare += leftDelta * leftDelta
            rightSquare += rightDelta * rightDelta
            index++
        }
        val denominator = sqrt(leftSquare * rightSquare)
        return if (denominator <= 1e-9) 0.0 else numerator / denominator
    }

    private fun channel(color: Int, channel: Int): Int = when (channel) {
        0 -> color ushr 16 and 0xff
        1 -> color ushr 8 and 0xff
        else -> color and 0xff
    }

    private fun luminance(color: Int): Int =
        (channel(color, 0) * 299 + channel(color, 1) * 587 + channel(color, 2) * 114 + 500) / 1000

    private companion object {
        const val ICON_CROP_TOP = 20
        const val ICON_CROP_HEIGHT = 80

        /**
         * Sampling grids. The counts are named so the shared scratch buffers and the loops that
         * fill them cannot drift apart.
         */
        const val COARSE_SAMPLE_WIDTH = 12
        const val COARSE_SAMPLE_HEIGHT = 9
        const val COARSE_SAMPLE_COUNT = COARSE_SAMPLE_WIDTH * COARSE_SAMPLE_HEIGHT
        const val SCORE_SAMPLE_WIDTH = 20
        const val SCORE_SAMPLE_HEIGHT = 12
        const val SCORE_SAMPLE_COUNT = SCORE_SAMPLE_WIDTH * SCORE_SAMPLE_HEIGHT

        const val MAX_ICON_CANDIDATES = 512
        const val MIN_ICON_CANDIDATES = 64
        const val MAX_EXPOSED_CHARACTER_CANDIDATES = 5
        const val COARSE_KEEP_RATIO = 0.5
        /**
         * How far the best icon in the whole pack may beat the best roster icon before the roster
         * is treated as incomplete and ignored for this slot.
         *
         * Coarse scores for the true role sit well above its rivals, so a gap this large means the
         * face on screen is not in the roster at all — a run whose joined list missed a character,
         * or a page that is not a labyrinth team page. Trusting the roster there would convert an
         * honest "unidentified" into a confident wrong role, which the planner would act on.
         */
        const val ROSTER_OUTSIDER_OVERRIDE_MARGIN = 0.08
        const val RIGHT_EDGE_MAX_X_RATIO = 0.82
        const val TOP_LEFT_OVERLAY_IGNORE_X_RATIO = 0.46
        const val TOP_LEFT_OVERLAY_IGNORE_Y_RATIO = 0.38
        const val TOP_RIGHT_OVERLAY_IGNORE_X_RATIO = 0.58
        const val TOP_RIGHT_OVERLAY_IGNORE_Y_RATIO = 0.42
        const val BOTTOM_LEFT_OVERLAY_IGNORE_X_RATIO = 0.32
        const val BOTTOM_LEFT_OVERLAY_IGNORE_Y_RATIO = 0.72
        const val BOTTOM_BAND_OVERLAY_IGNORE_Y_RATIO = 0.74
    }
}
