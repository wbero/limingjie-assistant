package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.labyrinthBossSettlementNextButtonRect
import com.landosol.toolbox.labyrinth.labyrinthGenericConfirmDialogRect
import com.landosol.toolbox.labyrinth.labyrinthSessionBlockObservation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regression suite for the two-button battle-failure page: it must stay a clickable failure page
 * when its lower-left "结束" button is absent (the "星辉吐息" relic-revive variant: "伤害报告"
 * plus a blue "下一步" in the lower-right button and nothing else). See
 * `android/docs/relic-revive-battle-failure-audit.md`, section 「3. 实现级设计」.
 *
 * Fixtures
 * --------
 *  * control: `battle/battle_failure_20260910.jpg` (1920x1080, three buttons) — must not change;
 *  * synthetic two-button: the control frame with **only** the "结束" slot repainted from its own
 *    background (x=1390 column, between the pale button and the blue button). One variable, and it
 *    is the variable under test;
 *  * [deviceCapture]: the user's own 1920x1080 screenshot, the committed baseline;
 *  * [nonSixteenByNineCapture]: a 2848x1320 third-party screenshot that must not be committed and
 *    only exercises the non-16:9 mapping. Both captures are optional; the cases that need one are
 *    skipped with `assumeTrue`.
 */
class LabyrinthBattleFailureTwoButtonLayoutTest {
    private val projectRoot = generateSequence(File(".").canonicalFile, File::getParentFile).take(6)
        .firstOrNull { File(it, "android/app/build.gradle.kts").isFile }
    private val resourceRoot = projectRoot?.let { File(it, "android/app/src/test/resources") }
    private val assetRoot = projectRoot?.let { File(it, "android/app/src/main/assets") }

    private val control: PixelImage
        get() = readImage(File(requireNotNull(resourceRoot), "battle/battle_failure_20260910.jpg"))

    // ------------------------------------------------------------------ (a) two-button page

    @Test
    fun `two button layout is a clickable failure variant with a live lower right rect`() {
        val frame = twoButtonFrame()
        val detection = requireNotNull(LabyrinthBattleFailureDetector().detect(frame)) {
            "the two-button failure variant is not detected; the run cannot press 下一步 and stalls"
        }

        // The variant is exposed, and the "结束" rect is absent rather than pointing at background.
        assertEquals(LabyrinthBattleFailureLayout.TWO_BUTTON, detection.layout)
        assertNull(detection.endButtonRect)

        // The click target is the lower-right blue button at the reference position, and its centre
        // lands inside the measured button bounds (so the tap is a real tap, not a background click).
        assertEquals(EntryPixelRect(1405, 935, 430, 110), detection.retryButtonRect)
        val buttonBounds = requireNotNull(buttonBounds(frame, blue = true)) { "no blue button in the lower-right band" }
        val centreX = detection.retryButtonRect.left + detection.retryButtonRect.width / 2
        val centreY = detection.retryButtonRect.top + detection.retryButtonRect.height / 2
        assertTrue(
            "click centre ($centreX,$centreY) outside the button $buttonBounds",
            centreX in buttonBounds.left..buttonBounds.right && centreY in buttonBounds.top..buttonBounds.bottom,
        )

        // The frame keeps a page identity with exactly the detector's confidence.
        val result = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates()).process(frame)
        assertEquals(LabyrinthEntryPageState.BATTLE_FAILED, result.observation.state)
        assertEquals(detection.confidence, result.observation.confidence, 1e-12)

        // No misjudgement: not the one-button "确认" dialog, not an expired account session.
        assertNull("two-button failure page read as the generic confirm dialog", labyrinthGenericConfirmDialogRect(result))
        val block = labyrinthSessionBlockObservation(result)
        assertEquals(SessionBlockKind.NONE, block.kind)
        assertNull(block.returnTitleRect)
    }

    // ---------------------------------------------------------------- (b) three-button control

    @Test
    fun `three button control keeps its verdict and both rects`() {
        val frame = control
        val detection = requireNotNull(LabyrinthBattleFailureDetector().detect(frame))

        assertEquals(LabyrinthBattleFailureLayout.THREE_BUTTON, detection.layout)
        assertEquals(EntryPixelRect(945, 935, 430, 110), detection.endButtonRect)
        assertEquals(EntryPixelRect(1405, 935, 430, 110), detection.retryButtonRect)
        // Frozen score of the 1920x1080 control (measured independently before this suite existed,
        // and it is the same expression as before the two-button layout was added).
        assertEquals(0.7080125258222605, detection.confidence, 1e-9)

        val result = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates()).process(frame)
        assertEquals(LabyrinthEntryPageState.BATTLE_FAILED, result.observation.state)
        assertEquals(detection.confidence, result.observation.confidence, 1e-12)
    }

    // ------------------------------------------------------------------- (c) no frame stealing

    @Test
    fun `two button layout is not claimed by boss settlement or battle result`() {
        val result = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates()).process(twoButtonFrame())

        assertTrue(
            "two-button failure page read as ${result.observation.state}",
            result.observation.state == LabyrinthEntryPageState.BATTLE_FAILED,
        )
        assertTrue(
            "BATTLE_RESULT (${result.observation.stateScores[LabyrinthEntryPageState.BATTLE_RESULT]}) " +
                "outranks BATTLE_FAILED (${result.observation.stateScores[LabyrinthEntryPageState.BATTLE_FAILED]})",
            (result.observation.stateScores[LabyrinthEntryPageState.BATTLE_RESULT] ?: 0.0) <
                (result.observation.stateScores[LabyrinthEntryPageState.BATTLE_FAILED] ?: 0.0),
        )
        // The Boss settlement fallback only ever runs from an UNKNOWN page.
        assertNull(
            labyrinthBossSettlementNextButtonRect(
                pageState = LabyrinthEntryPageState.BATTLE_FAILED,
                combatContext = com.landosol.toolbox.labyrinth.LabyrinthCombatContext(
                    com.landosol.toolbox.labyrinth.LabyrinthCombatKind.BOSS,
                ),
                nextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON],
            ),
        )
    }

    // --------------------------------------------- the committed 1920x1080 device capture

    /**
     * The user's own capture is the primary baseline. At 1920x1080 the reference mapper is the
     * identity, so every number here is a plain reference-space number — including the click rect
     * `(1405,935,430,110)`, which is byte-identical to the three-button control's.
     */
    @Test
    fun `the committed device capture is the primary two button baseline`() {
        val fixture = deviceCapture()
        assumeTrue("no 1920x1080 device capture in this checkout", fixture != null)
        val frame = readImage(requireNotNull(fixture))
        assertEquals(1920, frame.width)
        assertEquals(1080, frame.height)
        emit("device capture ${fixture.name} ${frame.width}x${frame.height}")

        val gates = measureGates(frame)
        emit("device capture gates $gates")
        // Four gates as recorded in the audit document, with a 0.02 tolerance: JPEG decoding and
        // the sample grid make the third decimal machine-dependent, and nothing here turns on it.
        assertEquals(0.000, gates.endLight, 0.02)
        assertEquals(0.934, gates.retryBlue, 0.02)
        assertEquals(0.770, gates.reportLegacyLight, 0.02)
        assertEquals(0.349, gates.titleBlue, 0.02)
        assertTrue("report gate", gates.reportMatched)

        val detection = requireNotNull(LabyrinthBattleFailureDetector().detect(frame)) {
            "the committed two-button baseline is not detected"
        }
        assertEquals(LabyrinthBattleFailureLayout.TWO_BUTTON, detection.layout)
        assertNull("the two-button page must not claim an 结束 rect", detection.endButtonRect)
        assertEquals(EntryPixelRect(1405, 935, 430, 110), detection.retryButtonRect)
        assertEquals(0.6841272788175443, detection.confidence, 1e-6)

        // The click centre lands inside the measured button bounds, so the tap is a real tap.
        val buttonBounds = requireNotNull(buttonBounds(frame, blue = true)) { "no blue button in the lower-right band" }
        val centreX = detection.retryButtonRect.left + detection.retryButtonRect.width / 2
        val centreY = detection.retryButtonRect.top + detection.retryButtonRect.height / 2
        assertTrue(
            "click centre ($centreX,$centreY) outside the button $buttonBounds",
            centreX in buttonBounds.left..buttonBounds.right && centreY in buttonBounds.top..buttonBounds.bottom,
        )

        val result = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates()).process(frame)
        assertEquals(LabyrinthEntryPageState.BATTLE_FAILED, result.observation.state)
        assertEquals(detection.confidence, result.observation.confidence, 1e-12)
        assertNull("read as the generic confirm dialog", labyrinthGenericConfirmDialogRect(result))
        val block = labyrinthSessionBlockObservation(result)
        assertEquals(SessionBlockKind.NONE, block.kind)
        assertNull(block.returnTitleRect)
        // BATTLE_FAILED is not a classifier output: the raw ranking on this very frame is UNKNOWN,
        // so the page identity can only come from the detector's copy in the frame processor.
        val raw = LabyrinthEntryPageClassifier().classify(result.observation.anchorScores)
        assertEquals(LabyrinthEntryPageState.UNKNOWN, raw.state)
        assertNull(result.battleEndConfirmation)
        emit(
            "device capture ranking: rawClassifier=${raw.state} " +
                "nextButton=${result.observation.anchorScores[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON]} " +
                "battleResult=${result.observation.stateScores[LabyrinthEntryPageState.BATTLE_RESULT]}",
        )
    }

    // ------------------------------------------- the local-only non-16:9 third-party capture

    @Test
    fun `the local non 16 by 9 capture maps onto the same click target`() {
        val fixture = nonSixteenByNineCapture()
        assumeTrue("no local 2848x1320 capture in this checkout", fixture != null)
        val frame = readImage(requireNotNull(fixture))
        assertEquals(2848, frame.width)
        assertEquals(1320, frame.height)

        val detection = requireNotNull(LabyrinthBattleFailureDetector().detect(frame))
        assertEquals(LabyrinthBattleFailureLayout.TWO_BUTTON, detection.layout)
        assertNull(detection.endButtonRect)
        // The rect is the FIT image of the shared reference rect — derived, not hard-coded.
        assertEquals(
            ReferenceFitMapper.map(
                frameWidth = frame.width,
                frameHeight = frame.height,
                referenceSize = EntryReferenceSize(1920, 1080),
                referenceRect = EntryReferenceRect(1405, 935, 430, 110),
            ),
            detection.retryButtonRect,
        )
        assertEquals(EntryPixelRect(1968, 1143, 525, 134), detection.retryButtonRect)
        val buttonBounds = requireNotNull(buttonBounds(frame, blue = true))
        val centreX = detection.retryButtonRect.left + detection.retryButtonRect.width / 2
        val centreY = detection.retryButtonRect.top + detection.retryButtonRect.height / 2
        assertTrue("click centre ($centreX,$centreY) outside the button $buttonBounds", centreX in buttonBounds.left..buttonBounds.right && centreY in buttonBounds.top..buttonBounds.bottom)
    }

    // ------------------------------------------------- the dim guard (why the relaxation is safe)

    /**
     * A frame whose pale controls are dimmed — a modal overlay, a fade — must never be relabelled
     * as the two-button variant, even when its blue controls still pass the hue test. The detector's
     * pale-report requirement (the top-right "伤害报告" control must itself be lit) is what rejects
     * it; the "结束确认" overlay measured on 2026-09-17 dims the button to `(33,65,123)` and the
     * pale controls below the 205 line at the same time.
     */
    @Test
    fun `dimmed pale controls are not read as the two button variant`() {
        // The "current" failure layout: blue report control inside the panel, blue button, blue
        // heading — only the "结束" button is dimmed instead of absent.
        val pixels = IntArray(1920 * 1080) { rgb(45, 50, 80) }
        fill(pixels, EntryPixelRect(740, 40, 450, 130), rgb(70, 145, 220))
        fill(pixels, EntryPixelRect(1380, 250, 380, 55), rgb(90, 165, 240))
        fill(pixels, EntryPixelRect(945, 935, 430, 110), rgb(163, 163, 168))
        fill(pixels, EntryPixelRect(1405, 935, 430, 110), rgb(65, 150, 240))

        assertNull(
            "a dimmed pale control was relabelled as the two-button variant",
            LabyrinthBattleFailureDetector().detect(PixelImage(1920, 1080, pixels)),
        )

        // Same frame with the "结束" button actually gone (the backdrop colour): still rejected
        // here, because this layout's report gate is the blue one and the variant requires the pale
        // one.
        fill(pixels, EntryPixelRect(945, 935, 430, 110), rgb(45, 50, 80))
        assertNull(LabyrinthBattleFailureDetector().detect(PixelImage(1920, 1080, pixels)))

        // The real dimmed artifact (EX failure page under the "结束确认" dialog) stays rejected too.
        val dimmed = File(requireNotNull(resourceRoot), "battle/ex_end_confirm_choice_20260917.png")
        if (dimmed.isFile) {
            assertNull(LabyrinthBattleFailureDetector().detect(readImage(dimmed)))
        }
    }

    // ------------------------------------- no other committed fixture changes its verdict

    @Test
    fun `relaxing the end gate changes no committed fixture`() {
        val root = requireNotNull(resourceRoot)
        // Both two-button captures are excluded from the four-gate comparison on purpose: they are
        // exactly the frames the relaxation is allowed to change. Whether they exist is decided per
        // checkout, never assumed.
        val excluded = twoButtonCaptures().map(File::getCanonicalPath).toSet()
        val fixtures = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            .filter { it.canonicalPath !in excluded }
            .sortedBy(File::getPath)
            .toList()
        assertTrue("no fixtures under $root", fixtures.size >= 20)

        var matched = 0
        for (fixture in fixtures) {
            val frame = runCatching { readImage(fixture) }.getOrNull() ?: continue
            if (frame.width <= frame.height) continue

            // The rule exactly as it was before the two-button layout existed: all four gates.
            val endLight = coverage(frame, 1010, 965, 260, 55, ::isPale)
            val retryBlue = coverage(frame, 1480, 965, 260, 55, ::isBlue)
            val reportMatched = coverage(frame, 1600, 55, 220, 45, ::isPale) >= 0.48 ||
                coverage(frame, 1380, 250, 380, 55, ::isBlue) >= 0.55
            val titleBlue = coverage(frame, 740, 40, 450, 130, ::isBlue)
            val fourGateRule = endLight >= 0.60 && retryBlue >= 0.60 && reportMatched && titleBlue >= 0.18

            val detected = LabyrinthBattleFailureDetector().detect(frame) != null
            assertEquals(
                "committed fixture ${fixture.name} changed verdict: four-gate=$fourGateRule detected=$detected " +
                    "(end=$endLight retry=$retryBlue report=$reportMatched title=$titleBlue)",
                fourGateRule,
                detected,
            )
            if (detected) {
                matched++
                // Nothing in the corpus was relabelled as the relic-revive variant.
                assertTrue("committed fixture ${fixture.name} was relabelled TWO_BUTTON", endLight >= 0.60)
                emit(
                    "unchanged failure fixture ${fixture.name} ${frame.width}x${frame.height} " +
                        "end=$endLight retry=$retryBlue",
                )
            }
        }
        // The known failure pages: the 1920x1080 one is stored twice under different names.
        assertTrue("expected the known failure fixtures, saw $matched", matched in 2..4)
        // Only the two-button captures are allowed to be excluded, and every one that exists is
        // covered by its own case above.
        assertEquals(listOfNotNull(deviceCapture(), nonSixteenByNineCapture()).size, excluded.size)
    }

    private companion object {
        /** Committed baseline: the user's own 1920x1080 capture. */
        const val DEVICE_CAPTURE_PATH = "labyrinth/battle-failure-two-button-20261004.jpg"

        /** Local-only third-party 2848x1320 screenshot; guarded by assumeTrue everywhere. */
        const val LOCAL_ONLY_CAPTURE_PATH = "labyrinth/battle-failure-relic-revive-20261004.jpg"
    }

    private fun coverage(
        frame: PixelImage,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        match: (Int, Int, Int) -> Boolean,
    ): Double {
        val rect = ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = EntryReferenceSize(1920, 1080),
            referenceRect = EntryReferenceRect(x, y, width, height),
        ) ?: return 0.0
        var matches = 0
        var samples = 0
        var row = rect.top
        while (row < rect.top + rect.height) {
            var column = rect.left
            while (column < rect.left + rect.width) {
                val color = frame[column, row]
                if (match(color shr 16 and 0xff, color shr 8 and 0xff, color and 0xff)) matches++
                samples++
                column += 4
            }
            row += 4
        }
        return if (samples == 0) 0.0 else matches.toDouble() / samples
    }

    private fun isPale(r: Int, g: Int, b: Int): Boolean = r >= 205 && g >= 205 && b >= 205

    private fun isBlue(r: Int, g: Int, b: Int): Boolean = b >= 150 && b - r >= 35 && b - g >= 10

    /** The detector's five sample windows, re-measured independently of the detector. */
    private data class Gates(
        val endLight: Double,
        val retryBlue: Double,
        val reportLegacyLight: Double,
        val reportCurrentBlue: Double,
        val titleBlue: Double,
    ) {
        val reportConfidence: Double get() = maxOf(reportLegacyLight, reportCurrentBlue)
        val reportMatched: Boolean
            get() = reportLegacyLight >= 0.48 || reportCurrentBlue >= 0.55

        override fun toString(): String =
            "end=%.3f retry=%.3f reportLegacy=%.3f reportCurrent=%.3f reportOk=%s title=%.3f".format(
                endLight, retryBlue, reportLegacyLight, reportCurrentBlue, reportMatched, titleBlue,
            )
    }

    private fun measureGates(frame: PixelImage): Gates = Gates(
        endLight = coverage(frame, 1010, 965, 260, 55, ::isPale),
        retryBlue = coverage(frame, 1480, 965, 260, 55, ::isBlue),
        reportLegacyLight = coverage(frame, 1600, 55, 220, 45, ::isPale),
        reportCurrentBlue = coverage(frame, 1380, 250, 380, 55, ::isBlue),
        titleBlue = coverage(frame, 740, 40, 450, 130, ::isBlue),
    )

    // ---------------- the chain the tap starts: "下一步" → "遗物效果结果" → "关闭" → back to the map

    @Test
    fun `the frame after the two button tap is owned by the relic effect result chain`() {
        // 1) the two-button page hands over a clickable lower-right rect for "下一步".
        val failure = requireNotNull(LabyrinthBattleFailureDetector().detect(twoButtonFrame()))
        assertEquals(LabyrinthBattleFailureLayout.TWO_BUTTON, failure.layout)

        // 2) the frame that tap produces — "遗物效果结果" — is owned and closed by anchors that
        //    already exist: the generic-confirm front branch returns the popup's own "关闭" rect.
        val processor = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates())
        val popup = processor.process(
            readImage(File(requireNotNull(resourceRoot), "labyrinth/relic-effect-result-20260922.png")),
        )
        val close = requireNotNull(labyrinthGenericConfirmDialogRect(popup)) {
            "the 遗物效果结果 popup is no longer closeable by an existing branch"
        }
        assertEquals(requireNotNull(popup.anchorMatches[EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE]).rect, close)
        assertEquals(SessionBlockKind.NONE, labyrinthSessionBlockObservation(popup).kind)
        // The "关闭" sits in the dialog's own button row, nowhere near the failure page's button slot.
        assertTrue("close rect $close", close.left in 700..800 && close.top in 650..730)

        // 3) the map the run returns to is still recognised as a map page.
        val map = processor.process(
            readImage(File(requireNotNull(resourceRoot), "labyrinth/event-no-reward-over-map-20260918.jpg")),
        )
        assertTrue(
            "map frame classified as ${map.observation.state}",
            map.observation.state in setOf(
                LabyrinthEntryPageState.NODE_SELECTION,
                LabyrinthEntryPageState.NODE_MAP_VIEW,
            ),
        )
    }

    // ------------------------------------------------------------------------ helpers

    /** Pale coverage of one reference-space window, measured the way the detector measures it. */
    private fun paleCoverage(frame: PixelImage, x: Int, y: Int, width: Int, height: Int): Double =
        coverage(frame, x, y, width, height, ::isPale)

    private fun fill(pixels: IntArray, rect: EntryPixelRect, color: Int) {
        repeat(rect.height) { row ->
            val offset = (rect.top + row) * 1920 + rect.left
            pixels.fill(color, offset, offset + rect.width)
        }
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue

    private fun emit(line: String) = println("[two-button-layout] $line")

    /** The 1920x1080 control with only the "结束" slot repainted from the page's own background. */
    private fun twoButtonFrame(): PixelImage {
        val frame = control
        val pixels = frame.pixels.copyOf()
        for (y in 935 until 1045) {
            val background = frame[1390, y]
            for (x in 945 until 1375) pixels[y * 1920 + x] = background
        }
        return PixelImage(1920, 1080, pixels)
    }

    /**
     * The user's own 1920x1080 on-device capture: the committed baseline every assertion about the
     * two-button layout is measured on. It is the reference canvas itself (scale 1.0, offset 0).
     */
    private fun deviceCapture(): File? = sequenceOf(
        System.getProperty("labyrinth.battleFailureTwoButton")?.let(::File),
        resourceRoot?.let { File(it, DEVICE_CAPTURE_PATH) },
    ).filterNotNull().firstOrNull(File::isFile)

    /**
     * The 2848x1320 third-party screenshot that must never be committed. It is deliberately a
     * *separate* accessor: it exists only to exercise the non-16:9 mapping path, and every use of
     * it is skipped when the file is absent.
     */
    private fun nonSixteenByNineCapture(): File? = sequenceOf(
        System.getProperty("labyrinth.battleFailureNonSixteenByNine")?.let(::File),
        resourceRoot?.let { File(it, LOCAL_ONLY_CAPTURE_PATH) },
    ).filterNotNull().firstOrNull(File::isFile)

    private fun twoButtonCaptures(): List<File> =
        listOfNotNull(deviceCapture(), nonSixteenByNineCapture()).distinctBy(File::getCanonicalPath)

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        override fun toString(): String = "[$left,$top..$right,$bottom]"
    }

    /** Measured bounding box of the pale ("结束") or blue ("重新挑战"/"下一步") control in the lower band. */
    private fun buttonBounds(frame: PixelImage, blue: Boolean): Box? {
        val left = (frame.width * 0.70).toInt()
        val top = (frame.height * 0.82).toInt()
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in top until frame.height) {
            for (x in left until frame.width) {
                val color = frame[x, y]
                val r = color shr 16 and 0xff
                val g = color shr 8 and 0xff
                val b = color and 0xff
                val hit = if (blue) b >= 150 && b - r >= 35 && b - g >= 10 else r >= 205 && g >= 205 && b >= 205
                if (!hit) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) null else Box(minX, minY, maxX, maxY)
    }

    private fun readImage(file: File): PixelImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(imageIo.getMethod("read", File::class.java).invoke(null, file)) {
            "Cannot read $file"
        }
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            IntArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private fun loadShippedTemplates(): LabyrinthEntryTemplateSet {
        val root = File(requireNotNull(assetRoot), AndroidLabyrinthEntryTemplateLoader.ROOT)
        val paths = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS +
            AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
        val prefix = "${AndroidLabyrinthEntryTemplateLoader.ROOT}/"
        return LabyrinthEntryTemplateSet(
            paths.mapNotNull { (id, path) ->
                val file = File(root, path.removePrefix(prefix))
                if (file.isFile) id to readImage(file) else null
            }.toMap(),
        )
    }
}
