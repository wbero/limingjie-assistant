package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Worker threads for the two scoring passes.
 *
 * Deliberately not the CPU count. The game shares the same virtual machine, and the formation
 * search in this project already measured 4 threads at 0.58x wall clock while 8 threads made the
 * whole run slower, so 4 is the measured sweet spot for this kind of per-frame number crunching.
 */
private const val ICON_SCORING_THREADS = 4

/** Below this many candidates the thread hand-off costs more than the scoring it saves. */
private const val ICON_SCORING_MIN_CANDIDATES = 128

/** No shard should carry fewer than this, otherwise the per-shard fixed cost dominates. */
private const val ICON_SCORING_MIN_PER_SHARD = 64

/** One process-wide pool: a run calls [LabyrinthCharacterIconMatcher.match] tens of thousands of times. */
private val ICON_SCORING_EXECUTOR: ExecutorService =
    Executors.newFixedThreadPool(ICON_SCORING_THREADS) { runnable ->
        Thread(runnable, "labyrinth-icon-score").apply { isDaemon = true }
    }

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
     * Sample values hoisted out of the scoring loops because they cannot change.
     *
     * Both passes walk a fixed sample grid and derive, for every cell, three colour channels, a
     * luminance and a local gradient (four more luminance lookups) from one pixel. Two of the three
     * inputs to that work are constant:
     *
     *  - the pack-icon side depends only on the template and the grid, and a template never changes,
     *    so it is cached in [templateSamples] for the lifetime of the matcher;
     *  - the observed side depends only on the comparison rectangle and the mask, and the pack ships
     *    icons of a single size, so every candidate inside one [match] reads the very same pixels of
     *    the same frame. That side is sampled once at the top of [match] and passed down as locals,
     *    so no field ever retains the whole-frame [PixelImage] after [match] returns.
     *
     * Deriving each value once instead of once per candidate removes about 1.2 million luminance
     * conversions per [match] on the real pack. Every sampled number is the same pure function of
     * the same pixels and the correlation inputs are still filled in the original order, so the
     * scores stay bit-identical.
     */
    private class GridSamples(gridSize: Int) {
        val color = Array(3) { DoubleArray(gridSize) }
        val luminance = DoubleArray(gridSize)
        val gradient = DoubleArray(gridSize)
    }

    /** Coarse grid: colour only, so it never pays for the luminance and gradient it never reads. */
    private class CoarseSamples {
        val color = Array(3) { DoubleArray(COARSE_SAMPLE_COUNT) }
    }

    /** The cells one mask keeps, in the row-major order the scoring loops used to visit them. */
    private class GridMask(val indices: IntArray, val count: Int)

    /**
     * Pack-icon samples, one entry per template, built lazily and kept for the lifetime of the
     * matcher because a template never changes.
     *
     * Memory cost: 795 templates × 5 DoubleArray(240) × 8 B ≈ 7.3 MiB of array payload for the
     * scoring grid, plus 795 × 3 DoubleArray(108) × 8 B ≈ 2.1 MiB for the coarse grid, plus the
     * per-entry objects and the [IdentityHashMap] overhead. That is a deliberate trade-off for
     * skipping roughly 1.2 million luminance conversions, and the per-candidate coordinate maths of
     * the coarse pass, on every [match]; it is bounded by the icon pack size and does not grow with
     * the number of frames processed.
     *
     * Both maps are written only from [templateSamplesOf] / [coarseSamplesOf], which [match] calls
     * on its own thread before it hands the scoring passes to the pool. The scoring passes read the
     * resolved grids, never these maps, so no worker thread ever mutates them.
     */
    private val templateSamples =
        IdentityHashMap<LabyrinthBattleCharacterTemplate, GridSamples>()

    private val coarseTemplateSamples =
        IdentityHashMap<LabyrinthBattleCharacterTemplate, CoarseSamples>()

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
        // Sample the frame side once for this call. Every candidate shares the same comparison
        // rectangle (the pack ships icons of a single size), so these grids are shared across all
        // candidates. They stay locals, so the whole-frame PixelImage is released as soon as match()
        // returns instead of being retained in a field. The mask is not applied here: the grid is
        // stored complete so it can serve any mask, and the GridMask below decides what is read.
        val observedTarget = candidateTemplates.firstOrNull()
            ?.let { comparisonRect(frame, iconRect, it.image) }
        val coarseObserved = observedTarget?.let {
            sampleGrid(frame, it, COARSE_SAMPLE_WIDTH, COARSE_SAMPLE_HEIGHT)
        }
        val scoreObserved = observedTarget?.let {
            sampleGrid(frame, it, SCORE_SAMPLE_WIDTH, SCORE_SAMPLE_HEIGHT, withLuminanceAndGradient = true)
        }
        // Which grid cells the mask keeps. A pure function of the mask and the grid size, so it is
        // derived once per call rather than once per candidate.
        val coarseMask = maskOf(mask, COARSE_SAMPLE_WIDTH, COARSE_SAMPLE_HEIGHT)
        val scoreMask = maskOf(mask, SCORE_SAMPLE_WIDTH, SCORE_SAMPLE_HEIGHT)
        // Resolve the pack side on this thread, so the workers below read plain arrays and never
        // touch the single-writer sample caches.
        val coarsePack = candidateTemplates.map { coarseSamplesOf(it) }
        val coarseScores = scoreAll(candidateTemplates.size) { index ->
            coarseIconScore(coarsePack[index], coarseObserved, coarseMask)
        }
        val coarse = candidateTemplates.indices
            .map { index -> candidateTemplates[index] to coarseScores[index] }
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
        val finalists = considered
            .filterIndexed { index, (_, score) -> index < MIN_ICON_CANDIDATES || score >= coarseCutoff }
            .take(MAX_ICON_CANDIDATES)
        val finalPack = finalists.map { (template, _) -> templateSamplesOf(template) }
        val finalScores = scoreAll(finalists.size) { index ->
            iconScore(finalPack[index], scoreObserved, scoreMask)
        }
        val ranked = finalists.indices
            .map { index -> finalists[index].first to finalScores[index] }
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

    /**
     * Coarse-pass score for one pack icon.
     *
     * Both sides are already resolved when this runs, so the body only reads: no scratch buffer, no
     * cache lookup and no writes to anything another worker could see.
     */
    private fun coarseIconScore(
        pack: CoarseSamples,
        observed: GridSamples?,
        mask: GridMask,
    ): Double {
        // observed is null only when the comparison rectangle does not exist for this frame and
        // rectangle (the pack ships icons of a single size, so the rectangle is shared by every
        // candidate and was sampled once up in [match]).
        if (observed == null) return 0.0
        val count = mask.count
        if (count < 8) return 0.0
        return (0..2).map { channel ->
            correlation(observed.color[channel], pack.color[channel], mask, count)
        }.average().coerceAtLeast(0.0)
    }

    /** Scoring-pass score for one pack icon; read-only for the same reason as [coarseIconScore]. */
    private fun iconScore(
        pack: GridSamples,
        observed: GridSamples?,
        mask: GridMask,
    ): Double {
        // observed is null only when the comparison rectangle does not exist for this frame and
        // rectangle (the pack ships icons of a single size, so the rectangle is shared by every
        // candidate and was sampled once up in [match]).
        if (observed == null) return 0.0
        val count = mask.count
        if (count < 20) return 0.0
        val colorScore = (0..2).map { channel ->
            correlation(observed.color[channel], pack.color[channel], mask, count)
        }.average().coerceAtLeast(0.0)
        val luminanceScore = correlation(observed.luminance, pack.luminance, mask, count).coerceAtLeast(0.0)
        val gradientScore = correlation(observed.gradient, pack.gradient, mask, count).coerceAtLeast(0.0)
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

    /** The pack-icon side of the coarse grid: colour only, constant for the life of the template. */
    private fun coarseSamplesOf(template: LabyrinthBattleCharacterTemplate): CoarseSamples {
        coarseTemplateSamples[template]?.let { return it }
        val image = template.image
        val samples = CoarseSamples()
        var grid = 0
        repeat(COARSE_SAMPLE_HEIGHT) { sampleY ->
            val yRatio = sampleY.toDouble() / (COARSE_SAMPLE_HEIGHT - 1)
            for (sampleX in 0 until COARSE_SAMPLE_WIDTH) {
                val xRatio = sampleX.toDouble() / (COARSE_SAMPLE_WIDTH - 1)
                val imageX = (xRatio * (image.width - 1)).roundToInt()
                val imageY = ICON_CROP_TOP + (yRatio * (ICON_CROP_HEIGHT - 1)).roundToInt()
                val pixel = image[imageX, imageY]
                for (channel in 0..2) {
                    samples.color[channel][grid] = channel(pixel, channel).toDouble()
                }
                grid++
            }
        }
        coarseTemplateSamples[template] = samples
        return samples
    }

    /**
     * Which grid cells [mask] keeps, in the row-major order the scoring loops visit them.
     *
     * Rebuilt per call on purpose. The mask carries the clip ratios of the card that produced it,
     * so caching by mask would grow with the number of distinct clip positions a run sees, while a
     * rebuild is only a few hundred comparisons.
     */
    private fun maskOf(
        mask: LabyrinthCharacterIconMask,
        sampleWidth: Int,
        sampleHeight: Int,
    ): GridMask {
        val indices = IntArray(sampleWidth * sampleHeight)
        var count = 0
        repeat(sampleHeight) { sampleY ->
            val yRatio = sampleY.toDouble() / (sampleHeight - 1)
            for (sampleX in 0 until sampleWidth) {
                val xRatio = sampleX.toDouble() / (sampleWidth - 1)
                if (isMasked(xRatio, yRatio, mask)) continue
                indices[count] = sampleY * sampleWidth + sampleX
                count++
            }
        }
        return GridMask(indices, count)
    }

    /**
     * Samples one observed-side grid from the frame, in the order the scoring loops visit it.
     *
     * The mask is deliberately not applied here: the grid is stored complete so the same samples
     * can serve any mask, and [GridMask] then decides which cells a correlation actually reads.
     */
    private fun sampleGrid(
        frame: PixelImage,
        target: EntryPixelRect,
        sampleWidth: Int,
        sampleHeight: Int,
        withLuminanceAndGradient: Boolean = false,
    ): GridSamples {
        val samples = GridSamples(sampleWidth * sampleHeight)
        var grid = 0
        repeat(sampleHeight) { sampleY ->
            val yRatio = sampleY.toDouble() / (sampleHeight - 1)
            for (sampleX in 0 until sampleWidth) {
                val xRatio = sampleX.toDouble() / (sampleWidth - 1)
                val frameX = target.left + (xRatio * (target.width - 1)).roundToInt()
                val frameY = target.top + (yRatio * (target.height - 1)).roundToInt()
                val pixel = frame[frameX, frameY]
                for (channel in 0..2) {
                    samples.color[channel][grid] = channel(pixel, channel).toDouble()
                }
                if (withLuminanceAndGradient) {
                    samples.luminance[grid] = luminance(pixel).toDouble()
                    samples.gradient[grid] = localGradient(frame, frameX, frameY, target)
                }
                grid++
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
     * Pearson correlation over the [count] cells [mask] keeps.
     *
     * The two means are accumulated with an explicit loop instead of `take(count).average()`. Both
     * forms compute `sum / count` with the sum accumulated from index 0 in the same order, so the
     * result is bit-identical -- but `take()` copied every element into a boxed `Double` (growing an
     * `ArrayList` on the way) purely to add numbers up. On the real 795-icon pack that is about
     * 1.75 million throwaway objects per [match], and it is the single largest cost this class has.
     *
     * Reading through [GridMask.indices] instead of a compacted copy visits exactly the same values
     * in exactly the same order, so both sums -- and the result -- are unchanged. That is what lets
     * the pack grid stay in one cache per template instead of being copied per mask.
     */
    private fun correlation(left: DoubleArray, right: DoubleArray, mask: GridMask, count: Int): Double {
        if (count <= 1) return 0.0
        val indices = mask.indices
        var leftSum = 0.0
        var rightSum = 0.0
        var index = 0
        while (index < count) {
            val at = indices[index]
            leftSum += left[at]
            rightSum += right[at]
            index++
        }
        val leftMean = leftSum / count
        val rightMean = rightSum / count
        var numerator = 0.0
        var leftSquare = 0.0
        var rightSquare = 0.0
        index = 0
        while (index < count) {
            val at = indices[index]
            val leftDelta = left[at] - leftMean
            val rightDelta = right[at] - rightMean
            numerator += leftDelta * rightDelta
            leftSquare += leftDelta * leftDelta
            rightSquare += rightDelta * rightDelta
            index++
        }
        val denominator = sqrt(leftSquare * rightSquare)
        return if (denominator <= 1e-9) 0.0 else numerator / denominator
    }

    /**
     * Scores [size] candidates with [score], on the scoring pool when there is enough work.
     *
     * Determinism does not depend on how the work is split: every candidate is scored entirely by
     * one shard, from values it only reads, and the shards are collected in submission order, so
     * the returned array is identical for any shard count. That is also why the shard count may
     * vary with the candidate count.
     */
    private fun scoreAll(size: Int, score: (Int) -> Double): DoubleArray {
        val scores = DoubleArray(size)
        val shards = if (size < ICON_SCORING_MIN_CANDIDATES) {
            1
        } else {
            minOf(ICON_SCORING_THREADS, size / ICON_SCORING_MIN_PER_SHARD).coerceAtLeast(1)
        }
        if (shards <= 1) {
            for (index in 0 until size) scores[index] = score(index)
            return scores
        }
        val perShard = (size + shards - 1) / shards
        val tasks = (0 until shards).map { shard ->
            val from = shard * perShard
            val to = minOf(size, from + perShard)
            Callable<DoubleArray> {
                val part = DoubleArray(to - from)
                for (index in from until to) part[index - from] = score(index)
                part
            }
        }
        var at = 0
        for (future in ICON_SCORING_EXECUTOR.invokeAll(tasks)) {
            val part = future.get()
            System.arraycopy(part, 0, scores, at, part.size)
            at += part.size
        }
        return scores
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
