package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.labyrinthBossSettlementNextButtonRect
import com.landosol.toolbox.labyrinth.labyrinthGenericConfirmDialogRect
import com.landosol.toolbox.labyrinth.labyrinthSessionBlockObservation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Layout audit for the relic-revive battle-failure page (the "星辉吐息" relic turning a defeat
 * into a win). The written audit is `android/docs/relic-revive-battle-failure-audit.md`; every
 * claim in it is backed by one of the tests below.
 *
 * Why this test exists
 * --------------------
 * `LabyrinthEntryPageState.BATTLE_FAILED` has exactly one producer in the whole app:
 * `LabyrinthEntryFrameProcessor.processPrepared` copies it over the classifier's verdict when
 * `LabyrinthBattleFailureDetector.detect(frame) != null`. The classifier itself never returns it.
 * The detector used to require four gates at once — pale "结束" (lower left), blue "重新挑战"
 * (lower right), "伤害报告" and the "战斗失败" heading — so a failure page whose bottom-left
 * "结束" button is absent (the user's 2026-10-04 report: only "下一步" in the lower-right button)
 * came out `UNKNOWN`, actions were disabled and the stall watchdog stopped the run.
 *
 * This suite therefore pins, with pixel evidence:
 *  * the 1920x1080 three-button verdict and both click rects (must stay bit-for-bit identical
 *    after the detector is relaxed);
 *  * that the two-button capture fails exactly one gate, the "结束" one;
 *  * that the lower-right button sits at the same reference position in both layouts;
 *  * that relaxing the "结束" gate admits no other existing fixture;
 *  * that the relic-effect-result popup is already closed by the generic-confirm branch, so no
 *    session change is needed.
 *
 * Assertions written before the detector change used to be marked as "pre-change" and written as
 * an `if (production == null) … else …` pair; the detector change landed on 2026-10-04 and the
 * pairs are now straight `requireNotNull` acceptance assertions. The two-button regression contract
 * itself lives in `LabyrinthBattleFailureTwoButtonLayoutTest`.
 *
 * Fixtures
 * --------
 * Two captures exist and are read through two separate accessors:
 *  * [deviceCapture]: the user's own 1920x1080 screenshot
 *    (`labyrinth/battle-failure-two-button-20261004.jpg`), the committed baseline;
 *  * [nonSixteenByNineCapture]: a 2848x1320 third-party screenshot
 *    (`labyrinth/battle-failure-relic-revive-20261004.jpg`) that must **not** be committed and
 *    only drives the non-16:9 mapping cases.
 * Either may be absent; the cases that need one are skipped with `assumeTrue`. The synthetic
 * two-button control (`the reference frame with the "结束" slot erased`) exercises the same code
 * path on a frame that is always present.
 */
class LabyrinthBattleFailureLayoutAuditTest {
    private val projectRoot = generateSequence(File(".").canonicalFile, File::getParentFile).take(6)
        .firstOrNull { File(it, "android/app/build.gradle.kts").isFile }
    private val resourceRoot = projectRoot?.let { File(it, "android/app/src/test/resources") }
    private val assetRoot = projectRoot?.let { File(it, "android/app/src/main/assets") }

    // ---------------------------------------------------------------- fixtures

    private val referenceFrame: PixelImage
        get() = readImage(File(requireNotNull(resourceRoot), "battle/battle_failure_20260910.jpg"))

    /**
     * The user's own 1920x1080 on-device capture: the committed baseline. At the reference size the
     * mapper is the identity, so everything measured here is a plain reference-space number.
     */
    private fun deviceCapture(): File? = sequenceOf(
        System.getProperty("labyrinth.battleFailureTwoButton")?.let(::File),
        resourceRoot?.let { File(it, DEVICE_CAPTURE_PATH) },
    ).filterNotNull().firstOrNull(File::isFile)

    /**
     * The 2848x1320 third-party screenshot that must never be committed: a *separate* accessor used
     * only by the non-16:9 geometry cases, each guarded by `assumeTrue` so a clean checkout that
     * does not have the file still runs green.
     */
    private fun nonSixteenByNineCapture(): File? = sequenceOf(
        System.getProperty("labyrinth.battleFailureNonSixteenByNine")?.let(::File),
        resourceRoot?.let { File(it, LOCAL_ONLY_CAPTURE_PATH) },
    ).filterNotNull().firstOrNull(File::isFile)

    private fun twoButtonCaptures(): List<File> =
        listOfNotNull(deviceCapture(), nonSixteenByNineCapture()).distinctBy(File::getCanonicalPath)

    // ------------------------------------------------- 1. frozen 1080p verdict

    @Test
    fun `1920x1080 three button frame keeps its verdict and both click rects`() {
        val frame = referenceFrame
        assertEquals(1920, frame.width)
        assertEquals(1080, frame.height)
        val result = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates()).process(frame)
        val failure = requireNotNull(result.battleFailure) { "reference failure fixture no longer detected" }

        // Frozen contract (also pinned by LabyrinthBattleFailureDetectorTest): at the 1920x1080
        // reference the mapper is the identity, so these are the raw reference constants.
        assertEquals(EntryPixelRect(945, 935, 430, 110), failure.endButtonRect)
        assertEquals(EntryPixelRect(1405, 935, 430, 110), failure.retryButtonRect)
        assertEquals(LabyrinthBattleFailureLayout.THREE_BUTTON, failure.layout)
        assertEquals(LabyrinthEntryPageState.BATTLE_FAILED, result.observation.state)
        assertEquals(failure.confidence, result.observation.confidence, 1e-12)
        emit("reference verdict=${result.observation.state} confidence=${failure.confidence}")
        emit("reference end=${failure.endButtonRect} retry=${failure.retryButtonRect}")
    }

    @Test
    fun `the classifier alone can never produce BATTLE_FAILED`() {
        // Mechanises deliverable 1: the failure page's identity is not a classifier output at all,
        // so the detector returning null removes the state from the frame entirely.
        val result = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates()).process(referenceFrame)
        val raw = LabyrinthEntryPageClassifier().classify(result.observation.anchorScores)

        assertFalse(
            "classifier stateScores now contain BATTLE_FAILED; the audit's premise changed",
            raw.stateScores.containsKey(LabyrinthEntryPageState.BATTLE_FAILED),
        )
        assertTrue(
            "raw classifier verdict $raw.state is BATTLE_FAILED",
            raw.state != LabyrinthEntryPageState.BATTLE_FAILED,
        )
        emit("raw-classifier verdict=${raw.state} confidence=${raw.confidence} (no BATTLE_FAILED key)")
        emit("processor verdict=${result.observation.state} via battle.failure.visual")
    }

    // ------------------------------------------- 2. the four gates, re-measured

    @Test
    fun `the reference frame passes all four gates and reconstructs the detector confidence`() {
        val frame = referenceFrame
        val gates = measureGates(frame)
        emit("reference gates $gates")

        assertTrue("end ${gates.endLight}", gates.endLight >= DETECTOR_MIN_END_LIGHT)
        assertTrue("retry ${gates.retryBlue}", gates.retryBlue >= DETECTOR_MIN_RETRY_BLUE)
        assertTrue("report ${gates.reportConfidence}", gates.reportMatched)
        assertTrue("title ${gates.titleBlue}", gates.titleBlue >= DETECTOR_MIN_TITLE_BLUE)

        // Differential pin: the re-measured gates must rebuild the production confidence exactly.
        // Any ROI or threshold the detector uses is therefore covered by this test.
        val reconstructed = (
            gates.endLight + gates.retryBlue + gates.reportConfidence + gates.titleBlue
            ) / 4.0
        val production = requireNotNull(LabyrinthBattleFailureDetector().detect(frame))
        assertEquals(reconstructed, production.confidence, 1e-12)
    }

    // --------------------------------------- 3. the two-button capture's fate

    @Test
    fun `two button capture fails the end gate and nothing else`() {
        val fixture = deviceCapture()
        assumeTrue("no 1920x1080 device capture in this checkout", fixture != null)
        val frame = readImage(requireNotNull(fixture))
        emit("two-button capture ${fixture.name} ${frame.width}x${frame.height}")
        assertEquals(1920, frame.width)
        assertEquals(1080, frame.height)

        // The decisive measurement, true both before and after the detector change: exactly one of
        // the four gates fails, and it is the "结束" gate. Every other gate already passes here.
        val gates = measureGates(frame)
        emit("two-button gates $gates")
        val failed = buildList {
            if (gates.endLight < DETECTOR_MIN_END_LIGHT) add("end")
            if (gates.retryBlue < DETECTOR_MIN_RETRY_BLUE) add("retry")
            if (!gates.reportMatched) add("report")
            if (gates.titleBlue < DETECTOR_MIN_TITLE_BLUE) add("title")
        }
        assertEquals("failed gates on the two-button capture", listOf("end"), failed)

        val production = requireNotNull(LabyrinthBattleFailureDetector().detect(frame)) {
            "the two-button failure page must keep its page identity"
        }
        assertEquals(LabyrinthBattleFailureLayout.TWO_BUTTON, production.layout)
        assertNull(production.endButtonRect)
        // The click target is the FIT image of RETRY_BUTTON_RECT, which contains the measured button bounds.
        assertEquals(mapRect(frame, 1405, 935, 430, 110), production.retryButtonRect)

        // The whole frame keeps its identity, a live click rect and no session block.
        val result = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates()).process(frame)
        assertEquals(LabyrinthEntryPageState.BATTLE_FAILED, result.observation.state)
        assertEquals(production.confidence, result.observation.confidence, 1e-12)
        assertEquals(SessionBlockKind.NONE, labyrinthSessionBlockObservation(result).kind)
        emit("two-button processor verdict=${result.observation.state} confidence=${result.observation.confidence}")
        // E4 evidence: neither the classifier's BATTLE_RESULT candidate (fed by this very
        // "下一步" artwork) nor the battle-end confirmation dialog competes for the frame.
        emit(
            "two-button ranking: rawClassifier=${LabyrinthEntryPageClassifier().classify(result.observation.anchorScores).state} " +
                "nextButton=${result.observation.anchorScores[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON]} " +
                "battleResult=${result.observation.stateScores[LabyrinthEntryPageState.BATTLE_RESULT]} " +
                "endConfirm=${result.battleEndConfirmation != null}",
        )
    }

    @Test
    fun `erasing only the end slot reproduces the two-button condition on a committable frame`() {
        // Synthetic control: same frame, one variable. Everything except the "结束" slot is
        // byte-identical to the 1920x1080 fixture, so any verdict change is attributable to it.
        val reference = referenceFrame
        val synthetic = eraseEndSlot(reference)
        val before = measureGates(reference)
        val after = measureGates(synthetic)
        emit("synthetic two-button gates $after")

        assertTrue(before.retryBlue == after.retryBlue)
        assertTrue(before.reportConfidence == after.reportConfidence)
        assertTrue(before.titleBlue == after.titleBlue)
        assertEquals(0.0, after.endLight, 0.0)

        // The production detector now accepts the two-button variant on a committable frame, with
        // the byte-identical click target of the three-button frame.
        val production = requireNotNull(LabyrinthBattleFailureDetector().detect(synthetic)) {
            "erasing only the 结束 slot must yield the two-button variant"
        }
        assertEquals(LabyrinthBattleFailureLayout.TWO_BUTTON, production.layout)
        assertNull(production.endButtonRect)
        assertEquals(mapRect(synthetic, 1405, 935, 430, 110), production.retryButtonRect)
        // The audit's rule and the shipped detector agree, including the pale-report guard that
        // keeps a merely dimmed frame out of the two-button variant.
        val layout = requireNotNull(proposedLayout(synthetic))
        assertEquals(LabyrinthBattleFailureLayoutProbe.TWO_BUTTON, layout.layout)
        assertNull(layout.endRect)
        assertEquals(requireNotNull(proposedLayout(reference)).retryRect, layout.retryRect)
        assertEquals(layout.confidence, production.confidence, 1e-12)
        emit("synthetic two-button layout=${layout.layout} confidence=${layout.confidence} retry=${layout.retryRect}")
    }

    // ------------------------------- 4. same place: lower-right button in both layouts

    /**
     * The non-16:9 geometry cases are deliberately limited to the local-only 2848x1320 capture via
     * [nonSixteenByNineCapture]; the committed 1920x1080 baseline is the identity case and is
     * pinned by [the device capture sits exactly on the reference canvas] instead.
     */
    @Test
    fun `the lower right button sits at the same reference position in both layouts`() {
        val fixture = nonSixteenByNineCapture()
        assumeTrue("no local 2848x1320 capture in this checkout", fixture != null)
        val capture = readImage(requireNotNull(fixture))
        assertEquals("this case is about the non-16:9 frame", 2848, capture.width)
        assertEquals(1320, capture.height)
        val reference = referenceFrame

        val referenceButton = requireNotNull(
            boxOf(reference, window(1380, 900, 560, 170)) { r, g, b -> isBlue(r, g, b) },
        )
        val captureButton = requireNotNull(
            boxOf(capture, window(1900, 1100, 700, 210)) { r, g, b -> isBlue(r, g, b) },
        )
        emit("reference blue button $referenceButton")
        emit("capture blue button $captureButton normalised ${normalise(capture, captureButton)}")

        val normalised = normalise(capture, captureButton)
        assertBoxesAgree(referenceButton, normalised, tolerance = 5, label = "lower-right blue button")

        // The production sampling ROI for "重新挑战" lands inside the button in *both* frames: that
        // is why retryBlue is 0.823 at 1080p and 0.912 in the 2848x1320 capture (0.912 > 0.823
        // because the larger button leaves less of its dark label inside the sampled window).
        val mappedRetrySample = requireNotNull(mapRect(capture, 1480, 965, 260, 55))
        assertTrue(
            "mapped retry sample $mappedRetrySample outside the measured button bounds $captureButton",
            mappedRetrySample.left >= captureButton.left && mappedRetrySample.top >= captureButton.top &&
                mappedRetrySample.left + mappedRetrySample.width <= captureButton.right + 1 &&
                mappedRetrySample.top + mappedRetrySample.height <= captureButton.bottom + 1,
        )
        emit("capture retry sample $mappedRetrySample retryBlue=${measureGates(capture).retryBlue}")
    }

    /**
     * The committed 1920x1080 baseline *is* the reference canvas: the mapper degenerates to the
     * identity (scale 1.0, offset 0,0) and all three landmarks land on the reference frame's own
     * boxes. That is what makes every reference-space number in this file directly applicable to
     * the user's captures.
     */
    @Test
    fun `the device capture sits exactly on the reference canvas`() {
        val fixture = deviceCapture()
        assumeTrue("no 1920x1080 device capture in this checkout", fixture != null)
        val capture = readImage(requireNotNull(fixture))
        val reference = referenceFrame

        val scale = minOf(capture.width / 1920.0, capture.height / 1080.0)
        val offsetX = (capture.width - 1920 * scale) / 2.0
        val offsetY = (capture.height - 1080 * scale) / 2.0
        assertEquals(1.0, scale, 0.0)
        assertEquals(0.0, offsetX, 0.0)
        assertEquals(0.0, offsetY, 0.0)
        emit("device capture ${capture.width}x${capture.height} scale=$scale offset=($offsetX,$offsetY)")

        data class Landmark(val name: String, val refWindow: EntryPixelRect, val blue: Boolean)
        val landmarks = listOf(
            Landmark("lower-right button", window(1380, 900, 560, 170), true),
            Landmark("damage report button", window(1500, 20, 400, 120), false),
            Landmark("failure heading", window(700, 20, 560, 190), true),
        )
        for (landmark in landmarks) {
            val predicate: (Int, Int, Int) -> Boolean = if (landmark.blue) ::isBlue else ::isPale
            val refBox = requireNotNull(boxOf(reference, landmark.refWindow, predicate)) { landmark.name }
            val capBox = requireNotNull(boxOf(capture, landmark.refWindow, predicate)) { landmark.name }
            // Same window, same canvas: a genuine on-device 16:9 capture differs only by JPEG noise.
            assertBoxesAgree(refBox, capBox, tolerance = 2, label = "${landmark.name} (identity)")
            emit("landmark ${landmark.name}: reference=$refBox capture=$capBox")
        }

        // No crop and no rescale: the button/report width ratio is the reference's own ratio.
        val refButton = requireNotNull(boxOf(reference, landmarks[0].refWindow, ::isBlue))
        val refReport = requireNotNull(boxOf(reference, landmarks[1].refWindow, ::isPale))
        val capButton = requireNotNull(boxOf(capture, landmarks[0].refWindow, ::isBlue))
        val capReport = requireNotNull(boxOf(capture, landmarks[1].refWindow, ::isPale))
        val refRatio = refButton.width.toDouble() / refReport.width
        val capRatio = capButton.width.toDouble() / capReport.width
        emit("device capture button/report width ratio reference=$refRatio capture=$capRatio")
        assertTrue(
            "device capture is anisotropic: $capRatio vs $refRatio",
            kotlin.math.abs(capRatio - refRatio) <= 0.02,
        )
    }

    @Test
    fun `the local capture is not displaced by an external crop or an anisotropic rescale`() {
        val fixture = nonSixteenByNineCapture()
        assumeTrue("no local 2848x1320 capture in this checkout", fixture != null)
        val reference = referenceFrame
        val capture = readImage(requireNotNull(fixture))
        assertEquals(2848, capture.width)
        assertEquals(1320, capture.height)

        // Three independent landmarks; the geometry has no free parameters: ReferenceFitMapper
        // assumes a 16:9 canvas scaled to the frame height and centred horizontally.
        val scale = minOf(capture.width / 1920.0, capture.height / 1080.0)
        val predictedOffsetX = (capture.width - 1920 * scale) / 2.0
        emit("capture scale=$scale predictedOffsetX=$predictedOffsetX")

        data class Landmark(val name: String, val refWindow: EntryPixelRect, val capWindow: EntryPixelRect, val blue: Boolean)
        val landmarks = listOf(
            Landmark("lower-right button", window(1380, 900, 560, 170), window(1900, 1100, 700, 210), true),
            Landmark("damage report button", window(1500, 20, 400, 120), window(2100, 10, 500, 160), false),
            Landmark("failure heading", window(700, 20, 560, 190), window(1100, 10, 700, 260), true),
        )
        for (landmark in landmarks) {
            val predicate: (Int, Int, Int) -> Boolean = if (landmark.blue) ::isBlue else ::isPale
            val refBox = requireNotNull(boxOf(reference, landmark.refWindow, predicate)) { landmark.name }
            val capBox = requireNotNull(boxOf(capture, landmark.capWindow, predicate)) { landmark.name }
            val normalised = normalise(capture, capBox)
            assertBoxesAgree(refBox, normalised, tolerance = 5, label = landmark.name)

            // Zero-parameter check on horizontal anchoring: the implied offset must be the centred
            // one, not 0 (left-anchored) and not width - 1920*scale (right-anchored).
            val impliedOffsetX = (capBox.left - refBox.left * scale)
            assertTrue(
                "${landmark.name} implied offsetX=$impliedOffsetX is not centred ($predictedOffsetX)",
                kotlin.math.abs(impliedOffsetX - predictedOffsetX) <= 8.0,
            )
            emit("landmark ${landmark.name}: ref=$refBox capture=$capBox normalised=$normalised impliedOffsetX=$impliedOffsetX")
        }

        // Isotropic: the two buttons' width ratio equals the one in the 16:9 reference, so the
        // capture is not a non-uniform (squashed or stretched) render of the same page.
        val refButton = requireNotNull(boxOf(reference, landmarks[0].refWindow, ::isBlue))
        val refReport = requireNotNull(boxOf(reference, landmarks[1].refWindow, ::isPale))
        val capButton = requireNotNull(boxOf(capture, landmarks[0].capWindow, ::isBlue))
        val capReport = requireNotNull(boxOf(capture, landmarks[1].capWindow, ::isPale))
        val refRatio = refButton.width.toDouble() / refReport.width
        val capRatio = capButton.width.toDouble() / capReport.width
        emit("button/report width ratio reference=$refRatio capture=$capRatio (delta=${kotlin.math.abs(capRatio - refRatio) / refRatio})")
        assertTrue(
            "anisotropic capture: $capRatio vs $refRatio",
            kotlin.math.abs(capRatio - refRatio) / refRatio <= 0.015,
        )
    }

    // ------------------------------------------- 5. the layout discriminator

    @Test
    fun `the empty lower left slot is the minimal two-button discriminator`() {
        val reference = referenceFrame
        val referenceGates = measureGates(reference)
        val buttonColumns = requireNotNull(columnRun(reference, 900, 1450, 930, 1025, 0.25, ::isPale))
        val buttonRows = requireNotNull(rowRun(reference, 960, 1350, 900, 1060, 0.25, ::isPale))
        emit(
            "1080p pale \"结束\" button columns=${buttonColumns.first}..${buttonColumns.last} " +
                "rows=${buttonRows.first}..${buttonRows.last}, endLight=${referenceGates.endLight}",
        )

        // Full pale button at the 1920x1080 reference: the detector's "结束" sample window
        // (1010,965,260,55) sits fully inside it (coverage 0.895). The pale run is the only
        // control in that band — the neighbouring pale blobs (x 812..943) are the character art
        // and stay outside the sample window.
        assertTrue("button columns $buttonColumns", buttonColumns.first in 950..970 && buttonColumns.last in 1340..1365)
        assertTrue("button rows $buttonRows", buttonRows.first in 938..956 && buttonRows.last in 1012..1032)
        assertTrue(referenceGates.endLight >= 0.60)

        // Both captures are checked: the committed 1080p baseline where the slot is plain
        // reference space, and the local non-16:9 frame where it goes through the mapping.
        for (fixture in twoButtonCaptures()) {
            val capture = readImage(fixture)
            val gates = measureGates(capture)
            val mapped = requireNotNull(mapRect(capture, 1010, 965, 260, 55))
            val slot = window(mapped.left - 60, mapped.top - 60, mapped.width + 120, mapped.height + 120)
            emit("${fixture.name} end slot $slot pale=${boxOf(capture, slot, ::isPale)} blue=${boxOf(capture, slot, ::isBlue)}")

            // Absent, not moved and not recoloured: the slot holds neither a pale nor a blue
            // control, and the discriminator's decision sits far on the empty side of 0.60.
            assertTrue("${fixture.name} endLight ${gates.endLight}", gates.endLight < 0.10)
            assertNull(boxOf(capture, slot, ::isBlue))
            // The button that has to be clicked is unaffected by the discriminator.
            assertTrue("${fixture.name} retryBlue ${gates.retryBlue}", gates.retryBlue >= 0.60)
        }
    }

    // ---------------------------- 6. slack of the relaxed gate set on the corpus

    @Test
    fun `relaxing the end gate admits no existing fixture`() {
        val root = requireNotNull(resourceRoot)
        val fixtures = root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            .sortedBy(File::getPath).toList()
        assertTrue("no fixtures under $root", fixtures.size >= 20)

        var currentMatches = 0
        var proposedMatches = 0
        var productionMatches = 0
        val twoButtonFrames = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for (fixture in fixtures) {
            val frame = runCatching { readImage(fixture) }.getOrNull() ?: continue
            if (frame.width <= frame.height) continue
            val gates = measureGates(frame)
            val current = gates.endLight >= DETECTOR_MIN_END_LIGHT &&
                gates.retryBlue >= DETECTOR_MIN_RETRY_BLUE && gates.reportMatched &&
                gates.titleBlue >= DETECTOR_MIN_TITLE_BLUE
            val production = LabyrinthBattleFailureDetector().detect(frame) != null
            val proposed = proposedLayout(frame)

            // Differential pin against the shipped detector: the two rules differ only on frames
            // whose "结束" slot is missing, so the detector must equal one of them on every fixture
            // — the four-gate rule before the relaxation and the relaxed rule after it.
            assertTrue(
                "detector verdict on ${fixture.name} matches neither the current nor the proposed rule",
                production == current || production == (proposed != null),
            )

            if (current) currentMatches++
            if (production) productionMatches++
            if (proposed != null) {
                proposedMatches++
                // The relaxed rule keeps frames that lost the "结束" button only when the remaining
                // three gates all pass; anything else must stay unmatched.
                assertTrue(gates.retryBlue >= DETECTOR_MIN_RETRY_BLUE)
                assertTrue(gates.reportMatched)
                assertTrue(gates.titleBlue >= DETECTOR_MIN_TITLE_BLUE)
                if (proposed.layout == LabyrinthBattleFailureLayoutProbe.TWO_BUTTON) {
                    twoButtonFrames += "${fixture.name} (${frame.width}x${frame.height})"
                }
            }
            if (current || proposed != null) {
                lines += "  ${fixture.name} ${frame.width}x${frame.height} end=${gates.endLight} " +
                    "retry=${gates.retryBlue} report=${gates.reportConfidence} title=${gates.titleBlue} " +
                    "layout=${proposed?.layout ?: "-"}"
            }
        }
        emit("corpus ${fixtures.size} fixtures: current=$currentMatches proposed=$proposedMatches production=$productionMatches")
        emit("corpus matches:\n" + lines.joinToString("\n"))
        emit("corpus two-button frames: $twoButtonFrames")

        // Every existing (three-button) match survives the relaxation untouched.
        assertTrue("corpus lost a three-button match", currentMatches >= 2)
        assertTrue(proposedMatches >= currentMatches)
        // The relaxation adds exactly the captures that lost the "结束" button — one per capture
        // present in this checkout (0, 1 or 2), never a hard-coded count.
        assertEquals(twoButtonCaptures().size, twoButtonFrames.size)
        assertEquals(currentMatches + twoButtonCaptures().size, proposedMatches)
    }

    // ---------------------------------------------- 7. relic effect result chain

    @Test
    fun `relic effect result popup is closed by the existing generic confirm branch`() {
        val frame = readImage(File(requireNotNull(resourceRoot), "labyrinth/relic-effect-result-20260922.png"))
        val result = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates()).process(frame)

        val close = requireNotNull(result.anchorMatches[EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE])
        val title = result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_RESULT_TITLE]
        val generic = labyrinthGenericConfirmDialogRect(result)
        emit("relic-effect-result state=${result.observation.state} title=$title close=${close.score} genericConfirm=$generic")

        // Both existing owners resolve: the page-level "遗物效果结果" anchors and (when its front
        // branch fires) the generic confirm dialog. Whichever wins, the frame has a "关闭" to tap.
        assertEquals(LabyrinthEntryPageState.ITEM_REWARD, result.observation.state)
        assertTrue("title $title", title >= 0.80)
        assertTrue("close ${close.score}", close.score >= 0.85)
        // Verified on this fixture: the generic-confirm front branch does fire and hands back
        // exactly the popup's own "关闭" rect, so the existing chain closes it with no change.
        assertEquals(close.rect, requireNotNull(generic) { "generic confirm branch stopped matching" })

        // Not a session expiry: the popup proves its own identity before the expiry guards run.
        assertEquals(SessionBlockKind.NONE, labyrinthSessionBlockObservation(result).kind)
        // Unrelated pages must not be claimed by the same branch.
        assertNull(
            labyrinthGenericConfirmDialogRect(
                LabyrinthEntryFrameProcessor(templates = loadShippedTemplates()).process(referenceFrame),
            ),
        )
    }

    // --------------------------------------------- 8. boss settlement precedence

    @Test
    fun `boss settlement next button cannot claim a failure page`() {
        val next = EntryAnchorMatch(score = 0.99, rect = EntryPixelRect(1987, 1156, 525, 134))

        // Control: the Boss settlement path is real, and only reachable from UNKNOWN.
        assertNotNull(
            labyrinthBossSettlementNextButtonRect(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                combatContext = com.landosol.toolbox.labyrinth.LabyrinthCombatContext(
                    com.landosol.toolbox.labyrinth.LabyrinthCombatKind.BOSS,
                ),
                nextButtonMatch = next,
            ),
        )
        assertNull(
            labyrinthBossSettlementNextButtonRect(
                pageState = LabyrinthEntryPageState.BATTLE_FAILED,
                combatContext = com.landosol.toolbox.labyrinth.LabyrinthCombatContext(
                    com.landosol.toolbox.labyrinth.LabyrinthCombatKind.BOSS,
                ),
                nextButtonMatch = next,
            ),
        )
        // Independent of the page state: the processor's failure copy (frame processor line ~508)
        // overwrites whatever the classifier ranked, so the failure verdict also wins the race
        // against the BATTLE_RESULT_NEXT_BUTTON candidate that this same "下一步" artwork produces.
        val reference = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates()).process(referenceFrame)
        assertTrue(
            "BATTLE_RESULT_NEXT_BUTTON no longer scores on the failure page; re-check E4's ranking claim",
            reference.observation.anchorScores[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON] > 0.0,
        )
        emit(
            "failure page ranking: state=${reference.observation.state} " +
                "nextButton=${reference.observation.anchorScores[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON]} " +
                "battleResult=${reference.observation.stateScores[LabyrinthEntryPageState.BATTLE_RESULT]}",
        )
    }

    // ------------------------------------------------------------------ helpers

    private data class Gates(
        val endLight: Double,
        val retryBlue: Double,
        val reportLegacyLight: Double,
        val reportCurrentBlue: Double,
        val titleBlue: Double,
    ) {
        val reportConfidence: Double get() = maxOf(reportLegacyLight, reportCurrentBlue)
        val reportMatched: Boolean
            get() = reportLegacyLight >= DETECTOR_MIN_REPORT_LIGHT || reportCurrentBlue >= DETECTOR_MIN_REPORT_BLUE

        override fun toString(): String =
            "end=%.3f retry=%.3f reportLegacy=%.3f reportCurrent=%.3f reportOk=%s title=%.3f".format(
                endLight, retryBlue, reportLegacyLight, reportCurrentBlue, reportMatched, titleBlue,
            )
    }

    /** The detector's five sample windows, re-measured independently of the detector. */
    private fun measureGates(frame: PixelImage): Gates = Gates(
        endLight = sample(frame, 1010, 965, 260, 55) { r, g, b -> isPale(r, g, b) },
        retryBlue = sample(frame, 1480, 965, 260, 55) { r, g, b -> isBlue(r, g, b) },
        reportLegacyLight = sample(frame, 1600, 55, 220, 45) { r, g, b -> isPale(r, g, b) },
        reportCurrentBlue = sample(frame, 1380, 250, 380, 55) { r, g, b -> isBlue(r, g, b) },
        titleBlue = sample(frame, 740, 40, 450, 130) { r, g, b -> isBlue(r, g, b) },
    )

    private fun sample(
        frame: PixelImage,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        match: (Int, Int, Int) -> Boolean,
    ): Double {
        val rect = mapRect(frame, x, y, width, height) ?: return 0.0
        var matches = 0
        var samples = 0
        var row = rect.top
        while (row < rect.top + rect.height) {
            var column = rect.left
            while (column < rect.left + rect.width) {
                val color = frame[column, row]
                if (match(color shr 16 and 0xff, color shr 8 and 0xff, color and 0xff)) matches++
                samples++
                column += SAMPLE_STEP
            }
            row += SAMPLE_STEP
        }
        return if (samples == 0) 0.0 else matches.toDouble() / samples
    }

    private fun mapRect(frame: PixelImage, x: Int, y: Int, width: Int, height: Int): EntryPixelRect? =
        ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = REFERENCE,
            referenceRect = EntryReferenceRect(x, y, width, height),
        )

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
        override fun toString(): String = "[$left,$top..$right,$bottom] ${width}x$height"
    }

    private fun window(left: Int, top: Int, width: Int, height: Int) = EntryPixelRect(left, top, width, height)

    private fun boxOf(
        frame: PixelImage,
        window: EntryPixelRect,
        match: (Int, Int, Int) -> Boolean,
    ): Box? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in window.top until minOf(frame.height, window.top + window.height)) {
            for (x in window.left until minOf(frame.width, window.left + window.width)) {
                val color = frame[x, y]
                if (!match(color shr 16 and 0xff, color shr 8 and 0xff, color and 0xff)) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return if (right < 0) null else Box(left, top, right, bottom)
    }

    /** Maps a measured pixel box back onto the 1920x1080 reference canvas. */
    private fun normalise(frame: PixelImage, box: Box): Box {
        val scale = minOf(frame.width / 1920.0, frame.height / 1080.0)
        val offsetX = (frame.width - 1920 * scale) / 2.0
        val offsetY = (frame.height - 1080 * scale) / 2.0
        return Box(
            left = Math.round((box.left - offsetX) / scale).toInt(),
            top = Math.round((box.top - offsetY) / scale).toInt(),
            right = Math.round((box.right - offsetX) / scale).toInt(),
            bottom = Math.round((box.bottom - offsetY) / scale).toInt(),
        )
    }

    private fun assertBoxesAgree(expected: Box, actual: Box, tolerance: Int, label: String) {
        for ((edge, delta) in listOf(
            "left" to actual.left - expected.left,
            "top" to actual.top - expected.top,
            "right" to actual.right - expected.right,
            "bottom" to actual.bottom - expected.bottom,
        )) {
            assertTrue("$label $edge off by $delta (expected $expected, got $actual)", kotlin.math.abs(delta) <= tolerance)
        }
    }

    /** The 1920x1080 reference with only the "结束" slot repainted from its own background. */
    private fun eraseEndSlot(frame: PixelImage): PixelImage {
        require(frame.width == 1920 && frame.height == 1080)
        val pixels = frame.pixels.copyOf()
        for (y in 935 until 1045) {
            // Column 1390 sits between the pale "结束" button (ends x=1352) and the blue
            // "重新挑战" button (starts x=1421): the page's own backdrop, never pale or blue.
            val background = frame[1390, y]
            for (x in 945 until 1375) pixels[y * 1920 + x] = background
        }
        return PixelImage(1920, 1080, pixels)
    }

    /** Longest contiguous column run in [x0,x1) that is at least [minFraction] the given colour. */
    private fun columnRun(
        frame: PixelImage,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        minFraction: Double,
        match: (Int, Int, Int) -> Boolean,
    ): IntRange? {
        val rows = y1 - y0
        var best: IntRange? = null
        var start = -1
        for (x in x0 until minOf(frame.width, x1)) {
            var hits = 0
            for (y in y0 until y1) {
                val color = frame[x, y]
                if (match(color shr 16 and 0xff, color shr 8 and 0xff, color and 0xff)) hits++
            }
            val on = hits >= rows * minFraction
            if (on && start < 0) start = x
            if (!on && start >= 0) {
                if (best == null || x - start > best.last - best.first + 1) best = start until x
                start = -1
            }
        }
        if (start >= 0 && (best == null || minOf(frame.width, x1) - start > best.last - best.first + 1)) {
            best = start until minOf(frame.width, x1)
        }
        return best
    }

    /** Longest contiguous row run in [y0,y1) that is at least [minFraction] the given colour. */
    private fun rowRun(
        frame: PixelImage,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        minFraction: Double,
        match: (Int, Int, Int) -> Boolean,
    ): IntRange? {
        val columns = x1 - x0
        var best: IntRange? = null
        var start = -1
        for (y in y0 until minOf(frame.height, y1)) {
            var hits = 0
            for (x in x0 until x1) {
                val color = frame[x, y]
                if (match(color shr 16 and 0xff, color shr 8 and 0xff, color and 0xff)) hits++
            }
            val on = hits >= columns * minFraction
            if (on && start < 0) start = y
            if (!on && start >= 0) {
                if (best == null || y - start > best.last - best.first + 1) best = start until y
                start = -1
            }
        }
        if (start >= 0 && (best == null || minOf(frame.height, y1) - start > best.last - best.first + 1)) {
            best = start until minOf(frame.height, y1)
        }
        return best
    }

    private fun isBlue(red: Int, green: Int, blue: Int): Boolean =
        blue >= 150 && blue - red >= 35 && blue - green >= 10

    private fun isPale(red: Int, green: Int, blue: Int): Boolean =
        red >= 205 && green >= 205 && blue >= 205

    private fun emit(line: String) = println("[battle-failure-audit] $line")

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

    private companion object {
        /** Committed baseline: the user's own 1920x1080 capture. */
        const val DEVICE_CAPTURE_PATH = "labyrinth/battle-failure-two-button-20261004.jpg"

        /** Local-only third-party 2848x1320 screenshot; guarded by assumeTrue everywhere. */
        const val LOCAL_ONLY_CAPTURE_PATH = "labyrinth/battle-failure-relic-revive-20261004.jpg"

        val REFERENCE = EntryReferenceSize(1920, 1080)
        const val SAMPLE_STEP = 4

        // Detector constants, restated so a change in the audit's assumptions fails loudly.
        const val DETECTOR_MIN_END_LIGHT = 0.60
        const val DETECTOR_MIN_RETRY_BLUE = 0.60
        const val DETECTOR_MIN_REPORT_LIGHT = 0.48
        const val DETECTOR_MIN_REPORT_BLUE = 0.55
        const val DETECTOR_MIN_TITLE_BLUE = 0.18
    }
}

/**
 * Executable form of the shipped detector's rule: the "战斗失败" heading, "伤害报告" and the
 * lower-right blue button are required, while the lower-left pale button only selects the layout
 * variant. Kept here — not in production — so the audit stays checkable against the shipped
 * detector; the corpus test pins the two to each other on every fixture.
 */
private object LabyrinthBattleFailureLayoutProbe {
    const val THREE_BUTTON = "THREE_BUTTON"
    const val TWO_BUTTON = "TWO_BUTTON"

    data class Match(val layout: String, val confidence: Double, val retryRect: EntryPixelRect, val endRect: EntryPixelRect?)

    fun detect(frame: PixelImage): Match? {
        if (frame.width <= frame.height) return null
        val reference = EntryReferenceSize(1920, 1080)
        fun rect(x: Int, y: Int, w: Int, h: Int): EntryPixelRect? = ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = reference,
            referenceRect = EntryReferenceRect(x, y, w, h),
        )

        fun coverage(window: EntryPixelRect?, match: (Int, Int, Int) -> Boolean): Double {
            if (window == null) return 0.0
            var matches = 0
            var samples = 0
            var y = window.top
            while (y < window.top + window.height) {
                var x = window.left
                while (x < window.left + window.width) {
                    val color = frame[x, y]
                    if (match(color shr 16 and 0xff, color shr 8 and 0xff, color and 0xff)) matches++
                    samples++
                    x += 4
                }
                y += 4
            }
            return if (samples == 0) 0.0 else matches.toDouble() / samples
        }

        fun isBlue(r: Int, g: Int, b: Int) = b >= 150 && b - r >= 35 && b - g >= 10
        fun isPale(r: Int, g: Int, b: Int) = r >= 205 && g >= 205 && b >= 205

        val endLight = coverage(rect(1010, 965, 260, 55), ::isPale)
        val retryBlue = coverage(rect(1480, 965, 260, 55), ::isBlue)
        val legacyReportLight = coverage(rect(1600, 55, 220, 45), ::isPale)
        val report = maxOf(legacyReportLight, coverage(rect(1380, 250, 380, 55), ::isBlue))
        val titleBlue = coverage(rect(740, 40, 450, 130), ::isBlue)
        if (retryBlue < 0.60 || report < 0.48 || titleBlue < 0.18) return null

        val retry = rect(1405, 935, 430, 110) ?: return null
        if (endLight >= 0.60) {
            return Match(
                layout = THREE_BUTTON,
                confidence = (endLight + retryBlue + report + titleBlue) / 4.0,
                retryRect = retry,
                endRect = rect(945, 935, 430, 110),
            )
        }
        // The two-button variant is the ordinary normal/EX failure chrome, so the pale top-right
        // "伤害报告" button must itself be lit; a mid-fade frame therefore cannot claim it.
        if (legacyReportLight < 0.48) return null
        return Match(
            layout = TWO_BUTTON,
            confidence = (retryBlue + report + titleBlue) / 3.0,
            retryRect = retry,
            endRect = null,
        )
    }
}

private fun proposedLayout(frame: PixelImage): LabyrinthBattleFailureLayoutProbe.Match? =
    LabyrinthBattleFailureLayoutProbe.detect(frame)
