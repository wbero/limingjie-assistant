package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.clanbattle.recognition.PixelImage
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
 * Reads the user's own 2026-10-04 17:20 debug bundle (`limingjie-debug-20261004-172026/` in the
 * workspace root, git-ignored) and replays its archived frames through the shipped pipeline.
 *
 * This is the only evidence in the project that comes from the report's own device: the panel
 * archive holds 960x540 copies of frames the app captured at 1920x1080 (the bundle's history
 * records `frameWidth=1920 frameHeight=1080 frameAspectRatio=1.7778` for every record).
 *
 * Which archived frame belongs to which recorded frame is read from the bundle's own
 * `state/history.ndjson`: the `archivedFrame` field names the panel copy of the frame the record
 * describes. Nothing here names a frame file literally — the archive only exists on the machine
 * that produced it, so a literal name would fail on every other checkout.
 *
 * Diagnostic only: the first test asserts nothing about the run, it prints what the recogniser
 * sees so the page verdicts can be compared with `state/history.ndjson`. The bundle is not part of
 * the repository (it carries real game frames), so on any other checkout these tests are skipped
 * by [assumeTrue]. See `android/docs/relic-revive-battle-failure-audit.md`, section
 * 「6.4 归档帧重放（可复现）」.
 */
class LabyrinthReportBundleReplayTest {
    private val projectRoot = locateProjectRoot()
    private val bundle = File(projectRoot, "limingjie-debug-20261004-172026")
    private val frames = File(bundle, "frames")
    private val history = File(bundle, "state/history.ndjson")
    private val assetRoot = File(projectRoot, "android/app/src/main/assets")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Test
    fun `report bundle frames are classified and the failure variants are located`() {
        assumeTrue("report bundle not present: ${bundle.absolutePath}", frames.isDirectory)
        val frameFiles = frames.listFiles().orEmpty().filter { it.isFile && it.extension.equals("jpg", true) }
        assumeTrue("bundle has no archived frames", frameFiles.isNotEmpty())
        val processor = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates())

        // The recorded advance away from the two-button failure page, the chain that follows it,
        // and the archive tail. Every index comes from the history's own `archivedFrame` column.
        val records = recordedFrames()
        val advanceIndex = records.indexOfFirst { it.trace?.actionLabel?.contains("遗物复活") == true }
        val wanted = buildList {
            if (advanceIndex >= 0) {
                addAll((advanceIndex..(advanceIndex + CHAIN_FRAMES_AFTER_ADVANCE)).filter { it in records.indices })
            }
            addAll(records.indices.toList().takeLast(TAIL_FRAMES))
        }.distinct().sorted()

        var replayed = 0
        for (index in wanted) {
            val record = records[index]
            val file = archivedFrameFile(record)
            if (file == null) {
                // A record may name no frame, or name one the archive has already rotated out.
                // Both are normal for a rolling export and must not fail the test.
                println("[report-bundle] fv=${record.frameVersion} archivedFrame=${record.archivedFrame} absent")
                continue
            }
            replayed++
            val frame = readImage(file)
            val result = processor.process(frame)
            val block = labyrinthSessionBlockObservation(result)
            println(
                "[report-bundle] ${file.name} ${frame.width}x${frame.height} " +
                    "page=${result.observation.state} conf=${"%.3f".format(result.observation.confidence)} " +
                    "layout=${result.battleFailure?.layout ?: "-"} " +
                    "retry=${result.battleFailure?.retryButtonRect ?: "-"} " +
                    "end=${result.battleFailure?.endButtonRect ?: "-"} " +
                    "genericConfirm=${labyrinthGenericConfirmDialogRect(result) ?: "-"} " +
                    "sessionBlock=${block.kind}",
            )
            result.observation.stateScores.entries
                .filter { it.value > 0.10 }
                .sortedByDescending { it.value }
                .forEach { println("      score ${it.key}=${"%.3f".format(it.value)}") }
            listOf(
                EntryAnchorId.SESSION_RETURN_TITLE,
                EntryAnchorId.SESSION_ERROR_TITLE,
                EntryAnchorId.RELIC_EFFECT_RESULT_TITLE,
                EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE,
                EntryAnchorId.ITEM_REWARD_CLOSE,
                EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON,
            // No elvis here: LabyrinthAnchorScores.get already returns 0.0 for a missing id.
            ).filter { result.observation.anchorScores[it] > 0.05 }
                .forEach { println("      anchor $it=${"%.3f".format(result.observation.anchorScores[it])}") }
        }
        println("[report-bundle] replayed $replayed of ${wanted.size} recorded frames")
        assertTrue("the history resolved no archived frame although ${frameFiles.size} exist", replayed > 0)
    }

    /**
     * The decisive end-to-end chain, read out of the bundle's own history rather than from frames
     * picked by hand: the two-button failure page → "下一步" → "遗物效果结果" → "关闭" → back to the map.
     *
     * The history is the authority for *which* frame is which: `frameVersion` names the app's frame
     * counter and `archivedFrame` names the panel copy of that same frame, so this test follows the
     * recorded sequence instead of guessing indices. Skipped when the bundle is absent.
     */
    @Test
    fun `recorded relic revive chain ends with the relic effect result closed by the generic dialog`() {
        assumeTrue("report bundle not present: ${bundle.absolutePath}", frames.isDirectory)
        assumeTrue("bundle has no history", history.isFile)
        val processor = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates())
        val records = recordedFrames()

        // The tap that advances the two-button page, and the frame the app closed as a generic
        // confirm dialog — the latter is the relic-effect-result popup, and its recorded action
        // label is what names it, not its position in the sequence.
        val advance = requireRecorded("no recorded 遗物复活 advance in this bundle") {
            records.firstOrNull { it.trace?.actionLabel?.contains("遗物复活") == true }
        }
        val popup = requireRecorded("no recorded generic-confirm popup after the advance") {
            records.firstOrNull {
                it.frameVersion >= advance.frameVersion &&
                    it.trace?.actionLabel?.contains("确认提示弹窗") == true
            }
        }

        for (record in listOf(advance, popup)) {
            val file = archivedFrameFile(record) ?: continue
            val result = processor.process(readImage(file))
            println(
                "[relic-chain] fv=${record.frameVersion} ${file.name} recorded=${record.page} " +
                    "action=${record.trace?.actionLabel} replayed=${result.observation.state} " +
                    "genericConfirm=${labyrinthGenericConfirmDialogRect(result) ?: "-"} " +
                    "sessionBlock=${labyrinthSessionBlockObservation(result).kind}",
            )
        }

        // 1. The two-button page is owned and offers the advance, without being counted as a retry.
        val advanceName = requireRecorded("the recorded advance names no archived frame") { advance.archivedFrame }
        val advanceFile = File(frames, advanceName)
        assumeTrue("archived frame missing on disk: $advanceName", advanceFile.isFile)
        val advanceFrame = readImage(advanceFile)
        val advanceResult = processor.process(advanceFrame)
        assertEquals(
            LabyrinthEntryPageState.BATTLE_FAILED,
            advanceResult.observation.state,
        )
        assertEquals(
            LabyrinthBattleFailureLayout.TWO_BUTTON,
            requireNotNull(advanceResult.battleFailure).layout,
        )
        assertEquals(SessionBlockKind.NONE, labyrinthSessionBlockObservation(advanceResult).kind)

        // 2. The frame the app closed as "确认提示弹窗" really is the relic-effect-result popup and
        //    hands back its own "关闭" rect. This is the chain the user confirmed on device: pressing
        //    "下一步" on a loss makes the client show "遗物效果结果", and the existing generic-confirm
        //    branch closes it with no session change.
        //
        //    Deliberately *not* asserted here: the replayed page state. This is the dashboard's
        //    960x540 panel copy, and the popup's page score is min(title, close) against anchors
        //    tuned for the 1920x1080 capture; on the scaled copy the title reads ~0.76, below the
        //    0.80 strong-modal line and the ambiguity margin, so the page falls back to UNKNOWN.
        //    An earlier version of this test asserted ITEM_REWARD here and failed for exactly that
        //    reason. The live app judged the full-resolution frame ITEM_REWARD 1.000 (history
        //    record #172) and the full-resolution popup is pinned by
        //    `LabyrinthEntryFrameProcessorTest.relic effect result popup is recognized...`.
        val popupName = requireRecorded("the recorded popup names no archived frame") { popup.archivedFrame }
        val popupFile = File(frames, popupName)
        assumeTrue("archived frame missing on disk: $popupName", popupFile.isFile)
        val popupResult = processor.process(readImage(popupFile))
        val title = popupResult.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_RESULT_TITLE]
        val close = popupResult.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE]
        val closeMatch = popupResult.anchorMatches[EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE]
        val generic = labyrinthGenericConfirmDialogRect(popupResult)
        println(
            "[relic-chain] popup replayed=${popupResult.observation.state} " +
                "itemRewardScore=${popupResult.observation.stateScores[LabyrinthEntryPageState.ITEM_REWARD]} " +
                "title=$title close=$close genericConfirm=$generic " +
                "sessionBlock=${labyrinthSessionBlockObservation(popupResult).kind}",
        )

        // The popup's own identity survives the panel downscale: both anchors still land on the
        // dialog. This is what proves the recorded frame *is* "遗物效果结果" and not some other modal.
        assertTrue("popup title did not match the relic effect result: $title", title >= 0.60)
        assertTrue("popup close did not match: $close", close >= 0.60)
        // The "关闭" rect the modal-close plan would use is the popup's own button. The rect is what
        // the run taps, so it is the part that must hold; whether the *generic-confirm front
        // branch* claims the frame is a separate, resolution-sensitive question (see below).
        assertNotNull(
            "the popup's own 关闭 anchor must resolve to a rect",
            closeMatch,
        )

        // Recorded behaviour on the device: the app judged this frame ITEM_REWARD 1.000 and tapped
        // "确认提示弹窗". Nothing here re-derives that (the 960x540 panel copy scores the page
        // min(title, close) = 0.757, below the 0.80 strong-modal line, so it reads UNKNOWN and the
        // generic-confirm front branch does not fire on it). The full-resolution popup and the
        // branch itself are pinned by the existing fixture test instead:
        // `LabyrinthEntryFrameProcessorTest.relic effect result popup is recognized and offers its
        // own close button` (title 1.0, close 1.0, genericConfirm = the popup's own rect).
        //
        // Not relaxed to make this pass: the detector's 0.80 / 0.85 lines are the 2026-09-23
        // defence that stopped "遗物效果结果" being read as an expired session, and a scaled copy is
        // not a reason to move them.
        assertEquals("确认提示弹窗", popup.trace?.actionLabel)
        // `page` is the recorded name, not an enum.
        assertEquals("ITEM_REWARD", popup.page)
    }

    /**
     * A record that names no frame, or names one the archive no longer holds, must resolve to
     * "nothing to replay" instead of throwing: a rolling export drops the name while the frame is
     * stale, and an archive can be pruned, neither of which is under this test's control.
     */
    @Test
    fun `a record without an archived frame is skipped instead of failing`() {
        assumeTrue("report bundle not present: ${bundle.absolutePath}", frames.isDirectory)
        assertNull(
            "a record with no archivedFrame must resolve to nothing",
            archivedFrameFile(RecordedFrame(frameVersion = 1L, page = "BATTLE_FAILED")),
        )
        assertNull(
            "a named frame that is absent on disk must resolve to nothing",
            archivedFrameFile(
                RecordedFrame(
                    frameVersion = 2L,
                    page = "BATTLE_FAILED",
                    archivedFrame = "no-such-archived-frame.jpg",
                ),
            ),
        )
        val existing = requireRecorded("bundle has no archived frame to resolve") {
            frames.listFiles().orEmpty().firstOrNull { it.isFile && it.extension.equals("jpg", true) }
        }
        assertEquals(
            existing.name,
            archivedFrameFile(
                RecordedFrame(frameVersion = 3L, page = "UNKNOWN", archivedFrame = existing.name),
            )?.name,
        )
    }

    /** The scalar subset of a history record this test follows. */
    @kotlinx.serialization.Serializable
    private data class RecordedFrame(
        val frameVersion: Long = -1L,
        val page: String = "",
        val archivedFrame: String? = null,
        val trace: RecordedTrace? = null,
    )

    @kotlinx.serialization.Serializable
    private data class RecordedTrace(val actionLabel: String? = null)

    /** Every history record that could be parsed; unreadable lines are ignored. */
    private fun recordedFrames(): List<RecordedFrame> {
        if (!history.isFile) return emptyList()
        return history.readLines().filter(String::isNotBlank).mapNotNull { line ->
            runCatching { json.decodeFromString<RecordedFrame>(line) }.getOrNull()
        }
    }

    /** The archived frame a record points at, or null when it names none or the file is gone. */
    private fun archivedFrameFile(record: RecordedFrame): File? =
        record.archivedFrame?.let { File(frames, it) }?.takeIf(File::isFile)

    /** Skips the test when [value] is null, otherwise returns it non-null. */
    private fun <T : Any> requireRecorded(message: String, value: () -> T?): T {
        val resolved = value()
        assumeTrue(message, resolved != null)
        return resolved ?: error("unreachable: assumeTrue raised for a null value")
    }

    private fun loadShippedTemplates(): LabyrinthEntryTemplateSet {
        val root = File(assetRoot, AndroidLabyrinthEntryTemplateLoader.ROOT)
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

    private fun locateProjectRoot(): File {
        val candidates = generateSequence(File(".").canonicalFile, File::getParentFile).take(6)
        return candidates
            .firstOrNull { File(it, "android/app/build.gradle.kts").isFile }
            ?: error("Cannot locate project root from ${File(".").canonicalPath}")
    }

    private companion object {
        /** Frames after the recorded advance that belong to the "遗物效果结果" chain. */
        const val CHAIN_FRAMES_AFTER_ADVANCE = 4

        /** Records at the end of the archive, where the run was still stalled. */
        const val TAIL_FRAMES = 6
    }
}
