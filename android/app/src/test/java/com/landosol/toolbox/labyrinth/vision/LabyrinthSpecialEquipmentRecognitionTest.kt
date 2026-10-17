package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 「特别装备」两个弹窗与编组页入口的识别层。
 *
 * 合成帧的做法：把仓库里的模板按标定坐标（1920x1080 参考系）贴回画布。模板本身就是源截图上
 * 该控件的 1:1 裁图，所以贴回之后画布上的像素与源截图逐像素一致，锚点分数应当是 1.0 量级；
 * 这条路径同时钉住「模板内容 + ROI 位置 + 参考系映射」三者一致——只贴模板不查 ROI、或只查
 * ROI 不贴模板，都会漏掉其中一半。
 *
 * 几何数值、参考系与逐帧像素证据与 `android/docs/special-equipment.md` 一致
 *（逐帧像素标定记录是本机过程产物，已由 .git/info/exclude 排除，不随提交入库）。
 */
class LabyrinthSpecialEquipmentRecognitionTest {
    private val projectRoot = generateSequence(File(".").canonicalFile, File::getParentFile).take(6)
        .firstOrNull { File(it, "android/app/build.gradle.kts").isFile }
    private val assetRoot = projectRoot?.let {
        File(it, "android/app/src/main/assets/resource-packs/cn-bilibili/vision")
    }
    private val fixtureRoot = projectRoot?.let { File(it, "android/app/src/test/resources/labyrinth") }

    // ---- 弹窗1「特别装备设定」的标定几何 ----
    private val mainDialogAnchor = EntryReferenceRect(46, 42, 50, 58)
    private val mainDialogTitle = EntryReferenceRect(835, 59, 264, 54)
    private val mainAutoButton = EntryReferenceRect(995, 922, 387, 71)
    private val mainEquipConfirm = EntryReferenceRect(1428, 917, 396, 81)
    private val equipConfirmCore = EntryReferenceRect(1487, 933, 276, 48)

    // ---- 弹窗2「自动特别装备设定」的标定几何 ----
    private val autoDialogAnchor = EntryReferenceRect(760, 56, 400, 58)
    private val autoDialogConfirm = EntryReferenceRect(982, 917, 396, 91)
    private val autoDialogCancel = EntryReferenceRect(548, 934, 387, 67)

    // ---- 编组页入口 ----
    private val entryButton = EntryReferenceRect(1180, 858, 97, 97)

    @Test
    fun `main dialog is recognised and both click targets come from the calibrated rects`() {
        assumeTrue(assetRoot?.isDirectory == true)
        val templates = requireNotNull(loadShippedTemplates())
        val processor = LabyrinthEntryFrameProcessor(templates = templates)
        val frame = mainDialogFrame(templates, EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED)

        val result = processor.process(frame)
        val scores = result.observation.anchorScores
        // 四条锚点各自 ≥0.80：模板能在 1920x1080 画布上命中（不是「模板对了但 ROI 挪了」）。
        for (id in listOf(
            EntryAnchorId.SPECIAL_EQUIPMENT_DIALOG_ANCHOR,
            EntryAnchorId.SPECIAL_EQUIPMENT_DIALOG_TITLE,
            EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_BUTTON,
            EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED,
        )) {
            assertTrue("$id = ${scores[id]}", scores[id] >= 0.80)
        }

        val observation = requireNotNull(result.specialEquipment) { "no special equipment observation" }
        assertEquals(LabyrinthSpecialEquipmentStage.MAIN_DIALOG, observation.stage)
        assertTrue("confidence ${observation.confidence}", observation.confidence >= 0.80)

        // 点击矩形必须是标定矩形本身，中心落在标定中心附近（取消 (294,957)、装备确定 (1625,957)）。
        assertEquals(pixelRect(1920, 1080, mainAutoButton), observation.autoButtonRect)
        assertEquals(pixelRect(1920, 1080, mainEquipConfirm), observation.confirmButtonRect)
        assertEquals(pixelRect(1920, 1080, EntryReferenceRect(101, 922, 387, 71)), observation.cancelButtonRect)
        assertCenterIn(observation.autoButtonRect, 1188, 957)
        assertCenterIn(observation.confirmButtonRect, 1626, 957)
        assertCenterIn(observation.cancelButtonRect, 294, 957)

        // 弹窗1 的蓝钮给的是「不可点击」的证据（暗态模板贴上来的那一帧）。
        assertEquals(false, observation.equipConfirmEnabled)
        assertTrue(
            "luminance ${observation.equipConfirmLuminance}",
            requireNotNull(observation.equipConfirmLuminance) in 90.0..101.0,
        )
    }

    @Test
    fun `装备确定 two states are separated by the core luminance and never inverted`() {
        assumeTrue(assetRoot?.isDirectory == true)
        val templates = requireNotNull(loadShippedTemplates())
        val processor = LabyrinthEntryFrameProcessor(templates = templates)

        val disabledResult = processor.process(
            mainDialogFrame(templates, EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED),
        )
        val enabledResult = processor.process(
            mainDialogFrame(templates, EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_ENABLED),
        )
        val disabled = requireNotNull(disabledResult.specialEquipment)
        val enabled = requireNotNull(enabledResult.specialEquipment)

        assertEquals(LabyrinthSpecialEquipmentStage.MAIN_DIALOG, disabled.stage)
        assertEquals(LabyrinthSpecialEquipmentStage.MAIN_DIALOG, enabled.stage)
        assertEquals(false, disabled.equipConfirmEnabled)
        assertEquals(true, enabled.equipConfirmEnabled)

        // 两类实测值（93.9/152.7 是整数系数 + 全像素探测；实现用 Rec.709 + 每 3px
        // 抽样，实测 95.46/155.18，见标定记录 §6 的口径差异）：钉到标定值 ±0.6，阈值 120 两侧
        // 各留 ≥24，两类不得互相越界。
        val disabledLuminance = requireNotNull(disabled.equipConfirmLuminance)
        val enabledLuminance = requireNotNull(enabled.equipConfirmLuminance)
        assertEquals(95.5, disabledLuminance, 0.6)
        assertEquals(155.2, enabledLuminance, 0.6)
        val disabledBlueMinusRed = requireNotNull(disabled.equipConfirmBlueMinusRed)
        val enabledBlueMinusRed = requireNotNull(enabled.equipConfirmBlueMinusRed)
        assertEquals(89.4, disabledBlueMinusRed, 0.6)
        assertEquals(112.3, enabledBlueMinusRed, 0.6)
        assertTrue(disabledLuminance < EQUIP_CONFIRM_LUMINANCE_THRESHOLD)
        assertTrue(enabledLuminance > EQUIP_CONFIRM_LUMINANCE_THRESHOLD)
        assertTrue(disabledBlueMinusRed < EQUIP_CONFIRM_BLUE_MINUS_RED_THRESHOLD)
        assertTrue(enabledBlueMinusRed > EQUIP_CONFIRM_BLUE_MINUS_RED_THRESHOLD)

        // 两态模板的分数**分不开**两态（0.9945 对 1.0，差 0.006）：形状相同、只是被压暗，所以
        // 判态只能来自内芯亮度。这条挡住「用模板相关度判可点击性」的写法。
        val disabledScores = disabledResult.observation.anchorScores
        assertTrue(
            "enabled template on a disabled frame ${disabledScores[EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_ENABLED]}",
            disabledScores[EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_ENABLED] >= 0.99,
        )
        assertTrue(
            "disabled template on a disabled frame ${disabledScores[EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED]}",
            disabledScores[EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED] >= 0.99,
        )
    }

    @Test
    fun `auto dialog wins over the main dialog it dims and hides the lower buttons`() {
        assumeTrue(assetRoot?.isDirectory == true)
        val templates = requireNotNull(loadShippedTemplates())
        val processor = LabyrinthEntryFrameProcessor(templates = templates)
        // 弹窗2 打开时弹窗1 与编组页都还在屏幕上（只是被压暗），识别顺序必须先弹窗2。
        val frame = mainDialogFrame(templates, EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED)
        paste(frame, templates[EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_DIALOG_ANCHOR], autoDialogAnchor)
        paste(frame, templates[EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_DIALOG_CONFIRM_BUTTON], autoDialogConfirm)

        val result = processor.process(frame)
        val observation = requireNotNull(result.specialEquipment)
        val scores = result.observation.anchorScores
        assertTrue("auto anchor ${scores[EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_DIALOG_ANCHOR]}",
            scores[EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_DIALOG_ANCHOR] >= 0.80)
        assertTrue("auto confirm ${scores[EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_DIALOG_CONFIRM_BUTTON]}",
            scores[EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_DIALOG_CONFIRM_BUTTON] >= 0.80)

        assertEquals(LabyrinthSpecialEquipmentStage.AUTO_DIALOG, observation.stage)
        // 被覆盖的下层按钮不得给出可点矩形，也不得给出弹窗1 的判态。
        assertNull(observation.autoButtonRect)
        assertEquals(pixelRect(1920, 1080, autoDialogConfirm), observation.confirmButtonRect)
        assertEquals(pixelRect(1920, 1080, autoDialogCancel), observation.cancelButtonRect)
        assertCenterIn(observation.confirmButtonRect, 1180, 962)
        assertCenterIn(observation.cancelButtonRect, 741, 967)
    }

    @Test
    fun `equip confirm evidence returns null instead of guessing`() {
        // 直接测判据本身：两个信号分居阈值两侧时必须给 null（上层按不可点击处理）。
        val cases = listOf(
            Triple(0xFFFFFFFF.toInt(), "两信号矛盾：亮到 255 但 B-R=0", null),
            Triple(rgb(150, 150, 150), "同上：灰 150 亮度够、B-R=0", null),
            Triple(rgb(60, 140, 215), "蓝且亮：可点击", true),
            Triple(rgb(40, 90, 125), "蓝但暗：不可点击", false),
        )
        val frame = emptyCanvas()
        val core = pixelRect(1920, 1080, equipConfirmCore)
        for ((color, label, expected) in cases) {
            fill(frame, core, color)
            val evidence = requireNotNull(labyrinthEquipConfirmButtonEvidence(frame, core)) { label }
            assertEquals(label, expected, evidence.enabled)
        }
        // 采样区小到取不到样时也必须给 null，而不是默认「可点击」。
        val tiny = EntryPixelRect(0, 0, 2, 2)
        assertNull(labyrinthEquipConfirmButtonEvidence(frame, tiny))
    }

    @Test
    fun `entry button is reported when found and its absence carries the diagnosable reason`() {
        assumeTrue(assetRoot?.isDirectory == true)
        val templates = requireNotNull(loadShippedTemplates())
        val processor = LabyrinthEntryFrameProcessor(templates = templates)

        // (a) 找到入口：stage 为 null + 矩形可用 ⇒ 这正是流程第一步该点的东西。
        val withEntry = emptyCanvas()
        paste(withEntry, templates[EntryAnchorId.BATTLE_TEAM_SPECIAL_EQUIPMENT_BUTTON], entryButton)
        val found = processor.process(withEntry)
        assertTrue(
            "entry score ${found.observation.anchorScores[EntryAnchorId.BATTLE_TEAM_SPECIAL_EQUIPMENT_BUTTON]}",
            found.observation.anchorScores[EntryAnchorId.BATTLE_TEAM_SPECIAL_EQUIPMENT_BUTTON] >= 0.80,
        )
        val foundObservation = requireNotNull(found.specialEquipment)
        assertNull(foundObservation.stage)
        assertEquals(pixelRect(1920, 1080, entryButton), foundObservation.entryButtonRect)
        assertNull(foundObservation.entryButtonMissReason)
        assertCenterIn(foundObservation.entryButtonRect, 1228, 906)

        // (b) 在编组页但入口没找到：观测非空、没有矩形、带原因码（上层零点击 + 照常开战）。
        val recognizer = LabyrinthSpecialEquipmentRecognizer()
        val missing = recognizer.recognize(
            frame = emptyCanvas(),
            anchorScores = LabyrinthAnchorScores(mapOf(EntryAnchorId.BATTLE_TEAM_SPECIAL_EQUIPMENT_BUTTON to 0.31)),
            onBattleTeamSelectionPage = true,
        )
        val missingObservation = requireNotNull(missing) { "a team page must still produce an observation" }
        assertNull(missingObservation.stage)
        assertNull(missingObservation.entryButtonRect)
        assertEquals(
            LabyrinthSpecialEquipmentRecognizer.ENTRY_BUTTON_MISS_REASON,
            missingObservation.entryButtonMissReason,
        )
        // 低分候选不得被当成矩形交出去（0.31 < 0.70）。
        assertEquals(0.31, missingObservation.entryButtonScore ?: -1.0, 1e-9)

        // (c) 不在编组页且没有弹窗：整体为 null ⇒ 上层知道「与特别装备无关」。
        assertNull(recognizer.recognize(emptyCanvas(), LabyrinthAnchorScores(emptyMap())))
        // (d) 分辨率低于判据下限的帧一律 null（竖屏/异常尺寸不猜几何）。
        assertNull(
            recognizer.recognize(
                frame = emptyCanvas(640, 360),
                anchorScores = LabyrinthAnchorScores(emptyMap()),
                onBattleTeamSelectionPage = true,
            ),
        )
    }

    @Test
    fun `a real team page finds the entry button at the calibrated rect and is never a dialog`() {
        assumeTrue(fixtureRoot?.isDirectory == true)
        val processor = LabyrinthEntryFrameProcessor(templates = requireNotNull(loadShippedTemplates()))
        val result = processor.process(readImage(File(fixtureRoot, "battle-team-roster-20260914.jpg")))
        assertEquals(
            result.observation.stateScores.toString(),
            LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
            result.observation.state,
        )
        val observation = requireNotNull(result.specialEquipment)
        // 编组页永远不得被读成两个弹窗之一（否则整局会在编组页上点弹窗按钮）。
        assertNull(observation.stage)
        // 真机 1920x1080 编组页帧（模板取自基准图 team-homare-eriko-20261002.png，本帧是另一张）：
        // 实测 0.871 ≥ 0.70，矩形就是标定矩形——「换图后旧坐标失效」的那点余量在这里被实测钉住。
        val score = result.observation.anchorScores[EntryAnchorId.BATTLE_TEAM_SPECIAL_EQUIPMENT_BUTTON]
        assertTrue("entry score on the live team page = $score", score >= 0.80)
        assertEquals(pixelRect(1920, 1080, entryButton), observation.entryButtonRect)
        assertNull(observation.entryButtonMissReason)
        assertCenterIn(observation.entryButtonRect, 1228, 906)
    }

    @Test
    fun `the series panel is never read as one of the two special equipment dialogs`() {
        assumeTrue(fixtureRoot?.isDirectory == true)
        val processor = LabyrinthEntryFrameProcessor(templates = requireNotNull(loadShippedTemplates()))
        val result = processor.process(readImage(File(fixtureRoot, "series-effect-panel-20261012.jpg")))

        // 「系列效果一览」面板与两个弹窗共用同一套外观：白色面板（四个白角探针全白）、蓝色标题带、
        // 白色页脚。所以面板外观不能判身份，只有标题文字与蓝钮能分开；这张真机帧必须给出「不是弹窗」。
        val observation = result.specialEquipment
        assertNull("panel read as ${observation?.stage}", observation?.stage)
        // 必须给不出任何弹窗按钮矩形：一旦给出，上层会按弹窗几何点击，而面板左缘之外就是地图。
        assertNull(observation?.autoButtonRect)
        assertNull(observation?.confirmButtonRect)
        assertNull(observation?.cancelButtonRect)
        assertNull(observation?.equipConfirmEnabled)
        // 标题文字是这一结论的依据：面板的标题带虽然同样是蓝底白字，但文字是「系列效果一览」。
        val titleScore = result.observation.anchorScores[EntryAnchorId.SPECIAL_EQUIPMENT_DIALOG_TITLE]
        assertTrue("panel title score = $titleScore", titleScore < 0.70)
    }

    @Test
    fun `the two state judgement also holds when the frame is not 1920x1080`() {
        assumeTrue(assetRoot?.isDirectory == true)
        val templates = requireNotNull(loadShippedTemplates())
        val processor = LabyrinthEntryFrameProcessor(templates = templates)

        // 1920x1080 与 2400x1080：FIT 映射的 scale 都是 1.0（2400 宽只是左右各多 240 px 黑边），
        // 所以 ROI 与模板仍是 1:1，判态必须逐项成立。
        for (width in listOf(1920, 2400)) {
            for ((id, expected) in listOf(
                EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED to false,
                EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_ENABLED to true,
            )) {
                val result = processor.process(mainDialogFrame(templates, id, width, 1080))
                val observation = requireNotNull(result.specialEquipment) { "${width}x1080 $id" }
                assertEquals("${width}x1080 $id", LabyrinthSpecialEquipmentStage.MAIN_DIALOG, observation.stage)
                assertEquals("${width}x1080 $id", expected, observation.equipConfirmEnabled)
                assertTrue(
                    "${width}x1080 $id anchor " +
                        result.observation.anchorScores[EntryAnchorId.SPECIAL_EQUIPMENT_DIALOG_ANCHOR],
                    result.observation.anchorScores[EntryAnchorId.SPECIAL_EQUIPMENT_DIALOG_ANCHOR] >= 0.80,
                )
                assertTrue("${width}x1080 $id conf ${observation.confidence}", observation.confidence >= 0.80)
            }
        }

        // 1600x720（FIT scale = 2/3，本机用最近邻重采样模拟）：实测识别层**不给 stage**
        // ——角部/自动装备锚点 0.779/0.754 过线，但两态蓝钮模板掉到 0.667（<0.70），于是
        // min(...) 不达阈值。这是安全方向：没有 stage ⇒ 上层不做任何动作，绝不会在未标定尺寸上
        // 点错按钮。源图两态在 1600x720 下得到的是同一个保守结论，因此这里同时断言「模板分数仍
        // 低于阈值」——若将来缩放后的匹配质量提高而让弹窗被认出来，这条会失败；那时的正确做法是
        // 重新标定、把新证据与结论写进 android/docs/special-equipment.md（几何章节），
        // 而不是放宽它。
        for (id in listOf(
            EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED,
            EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_ENABLED,
        )) {
            val result = processor.process(mainDialogFrame(templates, id, 1600, 720))
            val scores = result.observation.anchorScores
            assertTrue(
                "1600x720 $id enabled-template ${scores[EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_ENABLED]}",
                scores[EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_ENABLED] < 0.70,
            )
            assertTrue(
                "1600x720 $id disabled-template ${scores[EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED]}",
                scores[EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED] < 0.70,
            )
            assertNull("1600x720 $id stage", result.specialEquipment?.stage)
            assertNull("1600x720 $id enabled", result.specialEquipment?.equipConfirmEnabled)
            assertNull("1600x720 $id confirm rect", result.specialEquipment?.confirmButtonRect)
        }
    }

    // ---- helpers ----

    /** 弹窗1 的一帧：角部锚点 + 标题文字 + 正文中部「自动装备」+ 指定态别的蓝「装备确定」。 */
    private fun mainDialogFrame(
        templates: LabyrinthEntryTemplateSet,
        confirmTemplateId: String,
        width: Int = 1920,
        height: Int = 1080,
    ): PixelImage {
        val frame = emptyCanvas(width, height)
        paste(frame, templates[EntryAnchorId.SPECIAL_EQUIPMENT_DIALOG_ANCHOR], mainDialogAnchor)
        paste(frame, templates[EntryAnchorId.SPECIAL_EQUIPMENT_DIALOG_TITLE], mainDialogTitle)
        paste(frame, templates[EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_BUTTON], mainAutoButton)
        paste(frame, templates[confirmTemplateId], mainEquipConfirm)
        return frame
    }

    private fun emptyCanvas(width: Int = 1920, height: Int = 1080): PixelImage =
        PixelImage(width, height, IntArray(width * height) { OPAQUE_BLACK })

    /**
     * 把模板按参考系矩形贴到帧上（按 [ReferenceFitMapper] 映射，必要时最近邻重采样）。
     *
     * 1920x1080 下映射是恒等，贴上去的像素与模板逐像素相同——这正是断言能钉到 1.0 量级分数的
     * 前提；其它尺寸下重采样会带来损耗，所以那些用例只断言「判态不得被颠倒」。
     */
    private fun paste(frame: PixelImage, template: PixelImage, referenceRect: EntryReferenceRect) {
        val rect = requireNotNull(
            ReferenceFitMapper.map(
                frameWidth = frame.width,
                frameHeight = frame.height,
                referenceSize = LabyrinthSpecialEquipmentRecognizer.STANDARD_REFERENCE,
                referenceRect = referenceRect,
            ),
        ) { "reference rect $referenceRect does not fit ${frame.width}x${frame.height}" }
        for (y in 0 until rect.height) {
            val sourceY = (y * template.height) / rect.height
            for (x in 0 until rect.width) {
                val sourceX = (x * template.width) / rect.width
                frame.pixels[(rect.top + y) * frame.width + rect.left + x] = template[sourceX, sourceY]
            }
        }
    }

    private fun pixelRect(width: Int, height: Int, referenceRect: EntryReferenceRect): EntryPixelRect =
        requireNotNull(
            ReferenceFitMapper.map(
                frameWidth = width,
                frameHeight = height,
                referenceSize = LabyrinthSpecialEquipmentRecognizer.STANDARD_REFERENCE,
                referenceRect = referenceRect,
            ),
        )

    private fun assertCenterIn(rect: EntryPixelRect?, expectedX: Int, expectedY: Int) {
        val actual = requireNotNull(rect)
        val centreX = actual.left + actual.width / 2
        val centreY = actual.top + actual.height / 2
        assertTrue("centre ($centreX,$centreY) != ($expectedX,$expectedY)", centreX == expectedX && centreY == expectedY)
    }

    private fun fill(frame: PixelImage, rect: EntryPixelRect, color: Int) {
        for (y in 0 until rect.height) {
            for (x in 0 until rect.width) {
                frame.pixels[(rect.top + y) * frame.width + rect.left + x] = color
            }
        }
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        OPAQUE_BLACK or (red shl 16) or (green shl 8) or blue

    private fun loadShippedTemplates(): LabyrinthEntryTemplateSet? {
        val root = assetRoot ?: return null
        val all = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS +
            AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
        val prefix = "resource-packs/cn-bilibili/vision/"
        return LabyrinthEntryTemplateSet(
            all.mapNotNull { (id, path) ->
                val file = File(root, path.removePrefix(prefix))
                if (file.isFile) id to readImage(file) else null
            }.toMap(),
        )
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

    private companion object {
        const val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}
