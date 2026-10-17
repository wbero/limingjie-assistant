package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/** Read-only support for the manually opened series panel. Never swipes, opens or closes it. */
class AndroidLabyrinthRelicDetailResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val ocr = AndroidRelicRoiOcr(submitTextRead)

    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        // 「特别装备设定 / 自动特别装备设定」也是白色面板加蓝色标题带，只凭面板外观（四个白角）
        // 就会被当成系列面板把帧抢走；这类帧已由特别装备识别器给出 stage，直接交还：不 hold、不 OCR。
        if (labyrinthSpecialEquipmentOwnsFrame(result)) {
            ocr.clear()
            return result
        }
        if (result.observation.state !in setOf(LabyrinthEntryPageState.UNKNOWN,
                LabyrinthEntryPageState.NODE_SELECTION, LabyrinthEntryPageState.NODE_MAP_VIEW) ||
            !looksLikeSeriesPanel(bitmap)) {
            ocr.clear()
            return result
        }
        val titleRect = map(bitmap, EntryReferenceRect(490, 50, 940, 70)) ?: return result
        val title = ocr.read(bitmap, "series-title", titleRect)
        // While title is being checked, hold UNKNOWN-page fallback taps on this modal.
        if (title?.text == null) return holdModal(result, LabyrinthRelicDetailObservation(false))
        if (!LabyrinthRelicDetailText.isSeriesTitle(title.text)) return result
        val listRect = map(bitmap, EntryReferenceRect(532, 150, 120, 713)) ?: return result
        val read = ocr.read(bitmap, "series-visible", listRect, scale = 2)
        return holdModal(result, LabyrinthRelicDetailObservation(
            titleConfirmed = true,
            entries = read?.text?.let(LabyrinthRelicDetailText::parseVisibleSeries).orEmpty(),
            evidenceId = read?.id,
        ))
    }

    private fun holdModal(result: LabyrinthEntryFrameResult, detail: LabyrinthRelicDetailObservation) = result.copy(
        observation = result.observation.copy(state = LabyrinthEntryPageState.UNKNOWN,
            reason = "系列详情弹窗禁止点击背景地图"),
        nodeClassifications = emptyList(),
        relicDetailObservation = detail,
    )

    private fun map(bitmap: Bitmap, rect: EntryReferenceRect) =
        ReferenceFitMapper.map(bitmap.width, bitmap.height, EntryReferenceSize(1920, 1080), rect)

    private fun looksLikeSeriesPanel(bitmap: Bitmap): Boolean {
        // Validate narrow white panel sides and white footer; do not OCR a loading animation.
        return listOf(EntryReferenceRect(500, 160, 8, 8), EntryReferenceRect(1400, 160, 8, 8),
            EntryReferenceRect(520, 880, 8, 8), EntryReferenceRect(1395, 880, 8, 8)).all { reference ->
            val rect = map(bitmap, reference) ?: return@all false
            val color = bitmap.getPixel(rect.left, rect.top)
            val channels = listOf(color ushr 16 and 255, color ushr 8 and 255, color and 255)
            channels.min() >= 205 && channels.max() - channels.min() < 35
        }
    }
}
