package com.aistudio.mediatool.feature.studio.ui

import com.aistudio.mediatool.feature.studio.integration.StudioPitchPreviewResult
import kotlin.math.roundToInt

internal class StudioPitchToolRunner(
    private val projectId: String,
    private val session: StudioPitchToolSession,
) {
    suspend fun preview(
        model: StudioPitchUiModel,
        onProgress: (String) -> Unit,
    ): StudioPitchUiModel {
        val trackId = requireNotNull(model.selectedTrackId) { "Chưa chọn lớp giọng" }
        val result = session.preview(
            projectId = projectId,
            trackId = trackId,
            mode = model.mode,
            autoTuneConfig = model.autoTuneConfig(),
            harmonyConfig = model.harmonyConfig(),
        ) { progress, message ->
            onProgress("$message · ${(progress * 100).roundToInt()}%")
        }
        return model.copy(
            project = result.project,
            preview = result,
            processing = false,
            status = previewSummary(result),
        )
    }

    suspend fun apply(model: StudioPitchUiModel): StudioPitchUiModel {
        val preview = requireNotNull(model.preview) { "Chưa có bản nghe thử" }
        val result = session.apply(
            projectId = projectId,
            mode = model.mode,
            preview = preview,
            harmonyConfig = model.harmonyConfig(),
        )
        return if (model.mode == StudioPitchToolMode.AUTO_TUNE) {
            model.copy(
                project = result.project,
                preview = null,
                processing = false,
                appliedAutoTune = StudioAppliedPitchVersion(
                    preview.sourceTrackId,
                    result.generatedTrackId,
                    preview.sourceWasMuted,
                ),
                status = "Đã dùng bản Auto-Tune. Giọng gốc vẫn được giữ và đang tắt tiếng.",
            )
        } else {
            model.copy(
                project = result.project,
                preview = null,
                processing = false,
                status = "Đã thêm một lớp Giọng bè độc lập vào bài.",
            )
        }
    }

    suspend fun restore(model: StudioPitchUiModel): StudioPitchUiModel {
        val applied = requireNotNull(model.appliedAutoTune) { "Không có phiên bản Auto-Tune để khôi phục" }
        val restored = session.restoreAutoTune(
            projectId,
            applied.sourceTrackId,
            applied.generatedTrackId,
            applied.sourceWasMuted,
        )
        return model.copy(
            project = restored,
            processing = false,
            appliedAutoTune = null,
            status = "Đã khôi phục lớp giọng gốc và bỏ phiên bản Auto-Tune.",
        )
    }

    private fun previewSummary(result: StudioPitchPreviewResult): String =
        "Bản nghe thử sẵn sàng. Nhận diện giọng ${(result.voicedCoverage * 100).roundToInt()}%, " +
            "độ tin cậy ${(result.confidence * 100).roundToInt()}%. Chưa áp dụng vào bài."
}
