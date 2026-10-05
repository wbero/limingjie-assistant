package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage

/**
 * Which button row the failure page renders. Both variants share the blue "战斗失败" heading, the
 * "伤害报告" control, the "阿尔法强化" panel and the lower-right blue button; they differ by the
 * pale "结束" button on the lower left and by the label of that blue button ("重新挑战" against
 * "下一步").
 *
 * Pixel evidence and the design rationale live in
 * `android/docs/relic-revive-battle-failure-audit.md`.
 */
enum class LabyrinthBattleFailureLayout {
    /** "伤害报告" + pale "结束" (lower left) + blue "重新挑战" (lower right). */
    THREE_BUTTON,

    /**
     * "伤害报告" + blue "下一步" (lower right) with no "结束" button at all. This is the
     * relic-revive variant: pressing the button reveals "遗物效果结果", which is closed and the
     * run continues back on the map.
     */
    TWO_BUTTON,
}

data class LabyrinthBattleFailureObservation(
    val confidence: Double,
    /** The "结束" button; null exactly when [layout] is [LabyrinthBattleFailureLayout.TWO_BUTTON]. */
    val endButtonRect: EntryPixelRect?,
    /** Lower-right blue button: "重新挑战" in THREE_BUTTON, "下一步" in TWO_BUTTON. Always clickable. */
    val retryButtonRect: EntryPixelRect,
    /**
     * Defaults to [LabyrinthBattleFailureLayout.THREE_BUTTON] so callers written before the layout
     * field existed keep compiling; [init] still requires an "结束" rect for that layout.
     */
    val layout: LabyrinthBattleFailureLayout = LabyrinthBattleFailureLayout.THREE_BUTTON,
) {
    init {
        require((layout == LabyrinthBattleFailureLayout.THREE_BUTTON) == (endButtonRect != null)) {
            "a three-button failure page carries an \"结束\" rect; the two-button variant has none"
        }
    }
}

/**
 * Detects the current-client battle-failure screen without depending on changing character art.
 *
 * The required structure is the large blue "战斗失败" heading, the "伤害报告" control and the blue
 * lower-right button ("重新挑战" or "下一步"). Those three gates carry the page's identity, and
 * they are also what keeps a generic popup and the Boss victory summary — which shows a
 * lower-right "下一步" against a 0.714 blue report hit but only a 0.054 heading — from being read
 * as a failed battle.
 *
 * The pale lower-left "结束" button deliberately no longer gates the page: the relic-revive variant
 * does not render it at all, and demanding it dropped the whole frame to UNKNOWN, which disabled
 * every action and let the stall watchdog stop the run (2026-10-04 report). It now only selects
 * [LabyrinthBattleFailureLayout]. A frame that lost the "结束" button while the ordinary top-right
 * "伤害报告" control is still lit is that variant; a frame whose pale controls are merely dimmed
 * (a modal overlay or a fade) is rejected instead of being relabelled.
 */
class LabyrinthBattleFailureDetector(
    private val minEndLightCoverage: Double = 0.60,
    private val minRetryBlueCoverage: Double = 0.60,
    private val minReportLightCoverage: Double = 0.48,
    private val minReportBlueCoverage: Double = 0.55,
    private val minTitleBlueCoverage: Double = 0.18,
) {
    fun detect(frame: PixelImage): LabyrinthBattleFailureObservation? {
        if (frame.width <= frame.height) return null
        val endSample = map(frame, END_BUTTON_SAMPLE) ?: return null
        val retrySample = map(frame, RETRY_BUTTON_SAMPLE) ?: return null
        val legacyReportSample = map(frame, LEGACY_DAMAGE_REPORT_SAMPLE) ?: return null
        val currentReportSample = map(frame, CURRENT_DAMAGE_REPORT_SAMPLE) ?: return null
        val titleSample = map(frame, FAILURE_TITLE_SAMPLE) ?: return null
        val endButton = map(frame, END_BUTTON_RECT) ?: return null
        val retryButton = map(frame, RETRY_BUTTON_RECT) ?: return null

        val endLight = coverage(frame, endSample, ::isLight)
        val retryBlue = coverage(frame, retrySample, ::isBlue)
        val legacyReportLight = coverage(frame, legacyReportSample, ::isLight)
        val currentReportBlue = coverage(frame, currentReportSample, ::isBlue)
        val reportConfidence = maxOf(legacyReportLight, currentReportBlue)
        val reportMatched =
            legacyReportLight >= minReportLightCoverage || currentReportBlue >= minReportBlueCoverage
        val titleBlue = coverage(frame, titleSample, ::isBlue)
        if (
            retryBlue < minRetryBlueCoverage ||
            !reportMatched ||
            titleBlue < minTitleBlueCoverage
        ) return null

        // The "结束" button is on screen: the ordinary three-button page. The formula and both
        // rects are frozen here — changing them breaks LabyrinthBattleFailureDetectorTest and the
        // 1920x1080 control case of LabyrinthBattleFailureTwoButtonLayoutTest.
        if (endLight >= minEndLightCoverage) {
            return LabyrinthBattleFailureObservation(
                confidence = (endLight + retryBlue + reportConfidence + titleBlue) / 4.0,
                endButtonRect = endButton,
                retryButtonRect = retryButton,
                layout = LabyrinthBattleFailureLayout.THREE_BUTTON,
            )
        }

        // The "结束" button is absent: the relic-revive variant. Accepted only on the ordinary
        // normal/EX failure chrome, i.e. while the pale top-right "伤害报告" control is itself
        // fully lit. A modal overlay dims every pale control by roughly half, and during a fade
        // there is a band where the blue button still passes the hue test while the pale ones
        // already fail; both would otherwise be relabelled as this layout.
        if (legacyReportLight < minReportLightCoverage) return null
        return LabyrinthBattleFailureObservation(
            confidence = (retryBlue + reportConfidence + titleBlue) / 3.0,
            endButtonRect = null,
            retryButtonRect = retryButton,
            layout = LabyrinthBattleFailureLayout.TWO_BUTTON,
        )
    }

    private fun map(frame: PixelImage, rect: EntryReferenceRect): EntryPixelRect? =
        ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = rect,
        )

    private fun coverage(
        frame: PixelImage,
        rect: EntryPixelRect,
        predicate: (red: Int, green: Int, blue: Int) -> Boolean,
    ): Double {
        var matches = 0
        var samples = 0
        var y = rect.top
        while (y < rect.top + rect.height) {
            var x = rect.left
            while (x < rect.left + rect.width) {
                val color = frame[x, y]
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                if (predicate(red, green, blue)) matches++
                samples++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return if (samples == 0) 0.0 else matches.toDouble() / samples
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val FAILURE_TITLE_SAMPLE = EntryReferenceRect(740, 40, 450, 130)
        // Older captures placed the report control near the top-right. Current Boss failure
        // screens render the blue report button inside the white result panel lower down.
        // Accept either stable layout so client/UI revisions do not silently disable retry.
        val LEGACY_DAMAGE_REPORT_SAMPLE = EntryReferenceRect(1600, 55, 220, 45)
        val CURRENT_DAMAGE_REPORT_SAMPLE = EntryReferenceRect(1380, 250, 380, 55)
        val END_BUTTON_SAMPLE = EntryReferenceRect(1010, 965, 260, 55)
        val RETRY_BUTTON_SAMPLE = EntryReferenceRect(1480, 965, 260, 55)
        val END_BUTTON_RECT = EntryReferenceRect(945, 935, 430, 110)
        val RETRY_BUTTON_RECT = EntryReferenceRect(1405, 935, 430, 110)
        const val SAMPLE_STEP = 4

        fun isBlue(red: Int, green: Int, blue: Int): Boolean =
            blue >= 150 && blue - red >= 35 && blue - green >= 10

        fun isLight(red: Int, green: Int, blue: Int): Boolean =
            red >= 205 && green >= 205 && blue >= 205
    }
}
