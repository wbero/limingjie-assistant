package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-17 user screenshots (1178x663) of the EX battle-failure page and its two 结束确认
 * dialogs. The retreat chain 结束 → 撤退（无报酬） → 确认 is the only failure-page action
 * that reaches the server, so every stage must be recognised and the buttons must land.
 */
class LabyrinthBattleEndConfirmationDetectorTest {
    private val detector = LabyrinthBattleEndConfirmationDetector()

    @Test
    fun `ex failure page without a dialog is a failure page and not an end confirmation`() {
        val frame = fixture("ex_battle_failure_20260917.png")
        assertNull(detector.detect(frame))
        val failure = requireNotNull(LabyrinthBattleFailureDetector().detect(frame))
        // 结束 centre on the 1178x663 screenshot: (705, 605) → 1080p (1149, 985).
        val end = requireNotNull(failure.endButtonRect) { "this fixture is the three-button layout" }
        val cx = end.left + end.width / 2
        val cy = end.top + end.height / 2
        assertTrue("end centre ($cx,$cy)", cx in 640..770 && cy in 580..625)
    }

    @Test
    fun `choice dialog resolves to the 撤退 button`() {
        val frame = fixture("ex_end_confirm_choice_20260917.png")
        val result = requireNotNull(detector.detect(frame))
        assertEquals(LabyrinthBattleEndConfirmationStage.CHOICE, result.stage)
        assertTrue(result.confidence >= 0.70)
        // 撤退（无报酬） centre ≈ (590, 455) on the screenshot.
        val cx = result.advanceButtonRect.left + result.advanceButtonRect.width / 2
        val cy = result.advanceButtonRect.top + result.advanceButtonRect.height / 2
        assertTrue("retreat centre ($cx,$cy)", cx in 545..635 && cy in 435..475)
    }

    @Test
    fun `retreat confirmation dialog resolves to the 确认 button`() {
        val frame = fixture("ex_end_confirm_retreat_20260917.png")
        val result = requireNotNull(detector.detect(frame))
        assertEquals(LabyrinthBattleEndConfirmationStage.RETREAT_CONFIRM, result.stage)
        assertTrue(result.confidence >= 0.70)
        // 确认 centre ≈ (725, 455) on the screenshot.
        val cx = result.advanceButtonRect.left + result.advanceButtonRect.width / 2
        val cy = result.advanceButtonRect.top + result.advanceButtonRect.height / 2
        assertTrue("confirm centre ($cx,$cy)", cx in 680..770 && cy in 435..475)
    }

    @Test
    fun `boss failure fixture has no dialog`() {
        assertNull(detector.detect(fixture("battle_failure_20260910.jpg")))
    }

    private fun fixture(name: String): PixelImage {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val file = listOf(
            File(root, "src/test/resources/battle/$name"),
            File(root, "app/src/test/resources/battle/$name"),
            File(root, "android/app/src/test/resources/battle/$name"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate $name from $root")
        return readImage(file)
    }

    private fun readImage(file: File): PixelImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(imageIo.getMethod("read", File::class.java).invoke(null, file))
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }
}
