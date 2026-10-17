package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.labyrinth.LabyrinthRelicMark

/** Only visible, individually confirmed series; absent/clipped rows never imply zero. */
data class LabyrinthRelicDetailObservation(
    val titleConfirmed: Boolean,
    val entries: Map<LabyrinthRelicMark, Int> = emptyMap(),
    val evidenceId: Long? = null,
)

/**
 * 这一帧是否属于「特别装备」的两个弹窗。
 *
 * 两个弹窗都是白色面板加蓝色标题带，与「系列效果一览」面板共用同一套外观（角部金饰、标题带底色、
 * 白色页脚），所以面板外观不能判身份；只有特别装备识别器给出的 stage 能说明「这一帧是那两个弹窗」。
 */
internal fun labyrinthSpecialEquipmentOwnsFrame(result: LabyrinthEntryFrameResult): Boolean =
    result.specialEquipment?.stage != null

/**
 * 这一帧是否该由「系列详情」分支接管（只读、持帧、不派发点击）。
 *
 * 系列详情观测必须成立，且这帧不是「特别装备」的弹窗：只要特别装备识别器认出了自己的 stage，
 * 帧就必须留给特别装备流程，否则本轮会一直等不到下一次判断——面板外观相似导致的抢帧会让
 * 整个特别装备穿戴停在原地。
 */
internal fun labyrinthRelicDetailHoldsFrame(result: LabyrinthEntryFrameResult): Boolean =
    result.relicDetailObservation != null && !labyrinthSpecialEquipmentOwnsFrame(result)

internal object LabyrinthRelicDetailText {
    fun isSeriesTitle(text: String): Boolean = text.filterNot(Char::isWhitespace).contains("系列效果一览")

    fun parseVisibleSeries(text: String): Map<LabyrinthRelicMark, Int> {
        val headings = Regex("守备|加速|强化|会心|暴击|弱体|削弱|崩弱").findAll(text).toList()
        val entries = headings.mapIndexedNotNull { index, heading ->
            val mark = LabyrinthRelicMark.fromLabel(heading.value) ?: return@mapIndexedNotNull null
            val segment = text.substring(heading.range.last + 1, headings.getOrNull(index + 1)?.range?.first ?: text.length)
            // The ROI contains only series headings/icons, not descriptions. Reject conflicting
            // digits, bonus expressions and clipped rows instead of scavenging any number.
            if (segment.contains('+') || segment.contains('%') || segment.contains('-')) return@mapIndexedNotNull null
            val value = Regex("[0-9]+").findAll(segment).map { it.value.toIntOrNull() }.toList().singleOrNull()
                ?.takeIf { it in 0..30 } ?: return@mapIndexedNotNull null
            mark to value
        }
        return entries.groupBy({ it.first }, { it.second }).mapNotNull { (mark, values) ->
            values.distinct().singleOrNull()?.let { mark to it }
        }.toMap()
    }
}
