package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage

/**
 * 哪个「特别装备」弹窗正开着。
 *
 * 判定顺序固定为「先弹窗2、再弹窗1」：弹窗2 打开时，弹窗1 自己的「取消」「装备确定」以及底层
 * 编组页仍在屏幕上但被压暗（蓝钮内芯平均亮度 93.9 → 44.4，8 帧逐帧一致），
 * 所以含弹窗2 的帧不得当作「弹窗1 + 装备确定」解读，也不得对下层矩形派发点击。
 */
enum class LabyrinthSpecialEquipmentStage {
    /** 「特别装备设定」：底行三按钮 左白「取消」/ 中白「自动装备」/ 右蓝「装备确定」。 */
    MAIN_DIALOG,

    /** 「自动特别装备设定」：两按钮 左白「取消」/ 右蓝「确认」；层在弹窗1 之上。 */
    AUTO_DIALOG,
}

/**
 * 一帧上的「特别装备」观测。只描述看到什么，不含任何动作。
 *
 * [stage] 为 null 表示这一帧不是特别装备弹窗（上层据此知道「不是特别装备界面」）；此时若
 * [onBattleTeamSelectionPage] 成立，观测仍会给出编组页入口按钮的矩形或找不到的原因。
 */
data class LabyrinthSpecialEquipmentObservation(
    val stage: LabyrinthSpecialEquipmentStage?,
    /** 识别置信度：产生本 stage 的那几项锚点分数的最小值；没有 stage 时为 0.0。 */
    val confidence: Double,
    /** 弹窗1 正文中部的白色「自动装备」按钮；弹窗2 打开时为 null（被覆盖的下层不得点击）。 */
    val autoButtonRect: EntryPixelRect? = null,
    /** 当前 stage 的蓝色确认按钮：弹窗1「装备确定」/ 弹窗2「确认」。 */
    val confirmButtonRect: EntryPixelRect? = null,
    /** 当前 stage 的白色「取消」按钮：弹窗1 左下 [101,922..487,992]，弹窗2 左中 [548,934..934,1000]。 */
    val cancelButtonRect: EntryPixelRect? = null,
    /**
     * 弹窗1「装备确定」是否可点击；无证据（没有 stage、或按钮没被识别、或两个信号互相矛盾）时为
     * null，上层必须按**不可点击**处理。
     */
    val equipConfirmEnabled: Boolean? = null,
    /** 判据的原始证据：蓝钮内芯 [1487,933..1763,981) 的 0..255 平均亮度。 */
    val equipConfirmLuminance: Double? = null,
    /** 判据的辅助证据：同一 ROI 的平均 B-R。 */
    val equipConfirmBlueMinusRed: Double? = null,
    /**
     * 编组页最底行白色圆形「特别装备」入口按钮；找不到可信矩形时为 null，
     * 上层不得点击任何候选位置（宁可这一次不穿特别装备，也不要点错位置）。
     */
    val entryButtonRect: EntryPixelRect? = null,
    val entryButtonScore: Double? = null,
    /** [entryButtonRect] 为 null 时的可诊断原因（英文代码串，上层可原文转述给用户）。 */
    val entryButtonMissReason: String? = null,
)

/**
 * 「特别装备」两弹窗与编组页入口的识别层。
 *
 * 只读已经算好的锚点分数（[LabyrinthAnchorScores]）并对两个固定 ROI 取样，不产生动作；点击矩形
 * 由上层从本类的观测里取。几何数值、参考系与两态实测值见
 * `android/docs/special-equipment.md`（弹窗1、弹窗2、层级与变暗、两态判据、模板裁剪、
 * 编组页入口），本文件与它逐字一致。
 * 逐帧像素标定记录是本机过程产物：只在本机保留、已由 `.git/info/exclude` 排除、不随
 * PR 提交，所以本类注释里的实测数值一律以上面那份文档为准。
 *
 * 阈值取 0.70：模板与 ROI 同尺寸、同一 1920x1080 参考系，实机同弹窗各帧实测 ≥0.97、其它页面
 * 同 ROI 分数 ≤0.36（两者是本机逐帧探测的实测值；仓库内可复跑的同批
 * 断言在 `LabyrinthSpecialEquipmentRecognitionTest`：合成帧锚点 ≥0.80、真机编组页入口帧 0.871、
 * 低分候选不交付矩形）。留出的是「换图后旧坐标失效」的余量，不是精度余量。
 */
class LabyrinthSpecialEquipmentRecognizer(
    private val mainDialogMinimumScore: Double = MAIN_DIALOG_MIN_SCORE,
    private val autoDialogMinimumScore: Double = AUTO_DIALOG_MIN_SCORE,
    private val entryButtonMinimumScore: Double = ENTRY_BUTTON_MIN_SCORE,
) {
    /**
     * @param onBattleTeamSelectionPage 本帧是否被页面分类器判为编组页（
     *   [LabyrinthEntryPageState.BATTLE_TEAM_SELECTION]）。为 true 时即使入口按钮没被识别到，
     *   也会返回一个 stage=null 的观测，好让上层拿到「找不到入口按钮」这一结论而不是「没有观测」。
     */
    fun recognize(
        frame: PixelImage,
        anchorScores: LabyrinthAnchorScores,
        onBattleTeamSelectionPage: Boolean = false,
    ): LabyrinthSpecialEquipmentObservation? {
        if (frame.width < MINIMUM_FRAME_WIDTH || frame.height < MINIMUM_FRAME_HEIGHT) return null

        // 先判弹窗2：它压暗弹窗1，含它的帧不得再给出弹窗1 的按钮矩形。
        val autoDialogScore = minOf(
            anchorScores[EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_DIALOG_ANCHOR],
            anchorScores[EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_DIALOG_CONFIRM_BUTTON],
        )
        if (autoDialogScore >= autoDialogMinimumScore) {
            return LabyrinthSpecialEquipmentObservation(
                stage = LabyrinthSpecialEquipmentStage.AUTO_DIALOG,
                confidence = autoDialogScore,
                confirmButtonRect = map(frame, AUTO_DIALOG_CONFIRM_BUTTON),
                cancelButtonRect = map(frame, AUTO_DIALOG_CANCEL_BUTTON),
            )
        }

        // 再判弹窗1：角部锚点 + 标题文字 + 正文中部「自动装备」+ 蓝钮两态模板之一，四项同时成立才算。
        // 标题文字是必需项：面板外观（角部金饰、蓝色标题带、白色页脚）与「系列效果一览」面板共用，
        // 只有标题能证明这是「特别装备设定」而不是那个面板。
        val equipConfirmScore = maxOf(
            anchorScores[EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_ENABLED],
            anchorScores[EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED],
        )
        val mainDialogScore = minOf(
            anchorScores[EntryAnchorId.SPECIAL_EQUIPMENT_DIALOG_ANCHOR],
            anchorScores[EntryAnchorId.SPECIAL_EQUIPMENT_DIALOG_TITLE],
            anchorScores[EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_BUTTON],
            equipConfirmScore,
        )
        if (mainDialogScore >= mainDialogMinimumScore) {
            val evidence = map(frame, EQUIP_CONFIRM_CORE)
                ?.let { core -> labyrinthEquipConfirmButtonEvidence(frame, core) }
            return LabyrinthSpecialEquipmentObservation(
                stage = LabyrinthSpecialEquipmentStage.MAIN_DIALOG,
                confidence = mainDialogScore,
                autoButtonRect = map(frame, MAIN_AUTO_BUTTON),
                confirmButtonRect = map(frame, MAIN_EQUIP_CONFIRM_BUTTON),
                cancelButtonRect = map(frame, MAIN_CANCEL_BUTTON),
                equipConfirmEnabled = evidence?.enabled,
                equipConfirmLuminance = evidence?.luminance,
                equipConfirmBlueMinusRed = evidence?.blueMinusRed,
            )
        }

        return entryObservation(frame, anchorScores, onBattleTeamSelectionPage)
    }

    private fun entryObservation(
        frame: PixelImage,
        anchorScores: LabyrinthAnchorScores,
        onBattleTeamSelectionPage: Boolean,
    ): LabyrinthSpecialEquipmentObservation? {
        val score = anchorScores[EntryAnchorId.BATTLE_TEAM_SPECIAL_EQUIPMENT_BUTTON]
        val rect = map(frame, ENTRY_BUTTON)
        val matched = rect != null && score >= entryButtonMinimumScore
        if (!matched && !onBattleTeamSelectionPage) return null
        return LabyrinthSpecialEquipmentObservation(
            stage = null,
            confidence = if (matched) score else 0.0,
            // 矩形缺失或分数不足时只给结论，不给候选位置。
            entryButtonRect = rect?.takeIf { matched },
            entryButtonScore = score,
            entryButtonMissReason = if (matched) null else ENTRY_BUTTON_MISS_REASON,
        )
    }

    private fun map(frame: PixelImage, rect: EntryReferenceRect): EntryPixelRect? =
        ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = rect,
        )

    companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)

        /** 竖屏/异常尺寸的帧不判：两个弹窗的几何只在 1920x1080 参考系下验证过。 */
        const val MINIMUM_FRAME_WIDTH = 1280
        const val MINIMUM_FRAME_HEIGHT = 720

        const val MAIN_DIALOG_MIN_SCORE = 0.70
        const val AUTO_DIALOG_MIN_SCORE = 0.70
        const val ENTRY_BUTTON_MIN_SCORE = 0.70

        /** 找不到可信的编组页入口按钮时给上层的原因码（可诊断消息）。 */
        const val ENTRY_BUTTON_MISS_REASON = "special-equipment-entry-button-not-found"

        // ---- 弹窗1「特别装备设定」 ----
        val MAIN_CANCEL_BUTTON = EntryReferenceRect(101, 922, 387, 71)
        val MAIN_AUTO_BUTTON = EntryReferenceRect(995, 922, 387, 71)
        val MAIN_EQUIP_CONFIRM_BUTTON = EntryReferenceRect(1428, 917, 396, 81)

        /**
         * 蓝钮内芯采样区 `[1487,933..1763,981)`：只取蓝色填充，避开金/白边框，
         * 这样「不可点击（变暗）」与「可点击」的差别才是按钮底色本身的差别。
         */
        val EQUIP_CONFIRM_CORE = EntryReferenceRect(1487, 933, 276, 48)

        // ---- 弹窗2「自动特别装备设定」 ----
        val AUTO_DIALOG_CANCEL_BUTTON = EntryReferenceRect(548, 934, 387, 67)
        val AUTO_DIALOG_CONFIRM_BUTTON = EntryReferenceRect(982, 917, 396, 91)

        // ---- 编组页入口（基准图 team-homare-eriko-20261002.png 实测） ----
        val ENTRY_BUTTON = EntryReferenceRect(1180, 858, 97, 97)
    }
}

/**
 * 亮度判据的原始证据。几何、两态实测值与阈值见**已入库**的
 * `android/docs/special-equipment.md`（本机逐帧标定记录不入库）。
 *
 * [enabled] 为 null 表示两个信号互相矛盾：按不可点击处理，上层不得点击「装备确定」。
 */
internal data class LabyrinthSpecialEquipmentButtonEvidence(
    val luminance: Double,
    val blueMinusRed: Double,
    val enabled: Boolean?,
)

/**
 * 「装备确定」两态判定的像素证据。
 *
 * 蓝钮在不可点击时只是被压暗，形状与文字完全不变，所以模板相关度仍然很高、不足以判态；
 * 唯一的差别是蓝色填充本身的亮度（与商铺买按钮同一手法，见
 * `LabyrinthShopRecognition.buyButtonEnabledEvidence`）。
 *
 * 实机 8 帧逐帧一致（内芯 `[1487,933..1763,981)`，0..255 平均亮度；数值与 ROI 见
 * `android/docs/special-equipment.md`）：
 * 不可用（202610071/731/732）lum=93.9 / B-R=89.8；可用（2026100733/734/735）lum=152.7 /
 * B-R=112.9。本实现用 Rec.709 浮点系数、每 3 px 采样复测同一 ROI：不可用 95.5 / 可用 155.2
 * （同口径 B-R 89.4 / 112.3）；与 93.9/152.7 差 1.6–2.5，来源是以下逐帧
 * 探测的口径：整数系数 `(2126R+7152G+722B)/10000` 且逐像素（step=1）。两者同在阈值 120 的同一
 * 侧、结论一致，这里不对齐数字、也不改判据。阈值 120 距两类实测值 ≥24（断言见
 * `LabyrinthSpecialEquipmentRecognitionTest`）。
 *
 * 两个信号分居阈值两侧（例如亮度说可用、B-R 说不可用）时返回 enabled=null：判据互相矛盾，
 * 「不确定」必须走保守分支（不点「装备确定」）。
 */
internal fun labyrinthEquipConfirmButtonEvidence(
    frame: PixelImage,
    core: EntryPixelRect,
): LabyrinthSpecialEquipmentButtonEvidence? {
    if (core.width < 3 || core.height < 3) return null
    var luminanceSum = 0.0
    var blueMinusRedSum = 0.0
    var samples = 0
    var y = core.top
    while (y < core.top + core.height) {
        var x = core.left
        while (x < core.left + core.width) {
            val color = frame[x, y]
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            luminanceSum += red * 0.2126 + green * 0.7152 + blue * 0.0722
            blueMinusRedSum += (blue - red).toDouble()
            samples++
            x += EQUIP_CONFIRM_SAMPLE_STEP
        }
        y += EQUIP_CONFIRM_SAMPLE_STEP
    }
    if (samples == 0) return null
    val luminance = luminanceSum / samples
    val blueMinusRed = blueMinusRedSum / samples
    val enabled = when {
        luminance >= EQUIP_CONFIRM_LUMINANCE_THRESHOLD &&
            blueMinusRed >= EQUIP_CONFIRM_BLUE_MINUS_RED_THRESHOLD -> true
        luminance < EQUIP_CONFIRM_LUMINANCE_THRESHOLD &&
            blueMinusRed < EQUIP_CONFIRM_BLUE_MINUS_RED_THRESHOLD -> false
        else -> null
    }
    return LabyrinthSpecialEquipmentButtonEvidence(luminance, blueMinusRed, enabled)
}

/** 2 倍于两类实测间距（58.8）的余量仍在两侧之外。 */
internal const val EQUIP_CONFIRM_LUMINANCE_THRESHOLD = 120.0

/** 辅助信号 B-R 的阈值：两侧实测 89.8 与 112.9 的中点，只做交叉校验，从不单独下结论。 */
internal const val EQUIP_CONFIRM_BLUE_MINUS_RED_THRESHOLD = 101.0

internal const val EQUIP_CONFIRM_SAMPLE_STEP = 3
