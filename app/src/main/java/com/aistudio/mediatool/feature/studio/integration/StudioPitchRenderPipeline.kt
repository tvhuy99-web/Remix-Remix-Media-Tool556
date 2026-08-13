package com.aistudio.mediatool.feature.studio.integration

import android.content.Context
import com.aistudio.mediatool.feature.studio.audio.StudioPitchAnalysisInput
import com.aistudio.mediatool.feature.studio.audio.StudioPitchAudioIO
import com.aistudio.mediatool.feature.studio.audio.StudioPitchPcmProcessor
import com.aistudio.mediatool.feature.studio.audio.StudioPitchPlan
import com.aistudio.mediatool.feature.studio.data.StudioGeneratedAssetStore
import com.aistudio.mediatool.feature.studio.data.StudioPitchSourceProject
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.render.StudioExportFormat
import com.aistudio.mediatool.feature.studio.render.StudioRenderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StudioPitchRenderPipeline(context: Context) {
    private val appContext = context.applicationContext
    private val repository = StudioProjectRepository(appContext)
    private val renderer = StudioRenderEngine(appContext)
    private val generatedStore = StudioGeneratedAssetStore(appContext)

    suspend fun render(
        projectId: String,
        trackId: String,
        processorId: String,
        processorLabel: String,
        suffix: String,
        configJson: String,
        buildPlan: (StudioPitchAnalysisInput) -> StudioPitchPlan,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): StudioPitchPreviewResult = withContext(Dispatchers.IO) {
        val project = requireNotNull(repository.load(projectId)) { "Không tìm thấy dự án Studio" }
        val sourceTrack = requireNotNull(project.tracks.firstOrNull { it.id == trackId }) { "Không tìm thấy lớp giọng" }
        onProgress(0.03f, "Đang dựng lớp giọng để phân tích")
        val sourceFile = renderer.renderDryMix(
            StudioPitchSourceProject.create(project, trackId),
            StudioExportFormat.WAV,
        )
        try {
            onProgress(0.18f, "Đang nhận diện cao độ giọng")
            val analysis = StudioPitchAudioIO.readAnalysisMono(sourceFile)
            val plan = buildPlan(analysis)
            require(plan.voicedCoverage >= 0.02f) { "Chưa nhận diện đủ phần có giọng để xử lý cao độ" }
            val (relativePath, target) = repository.derivedOutputFile(projectId, processorId)
            onProgress(0.32f, "Đang tạo bản giọng mới")
            StudioPitchPcmProcessor.process(sourceFile, target, plan) { value ->
                onProgress(0.32f + value.coerceIn(0f, 1f) * 0.58f, "Đang xử lý cao độ")
            }
            val displayBase = sourceTrack.name.ifBlank { "Giọng" }
            val (saved, asset) = generatedStore.commit(
                projectId = projectId,
                relativePath = relativePath,
                displayName = "$displayBase • $suffix",
                processorId = processorId,
                processorLabel = processorLabel,
                processorConfig = configJson,
            )
            onProgress(1f, "Đã tạo bản nghe thử")
            StudioPitchPreviewResult(
                project = saved,
                asset = asset,
                sourceTrackId = sourceTrack.id,
                sourceWasMuted = sourceTrack.muted,
                voicedCoverage = plan.voicedCoverage,
                confidence = plan.averageConfidence,
            )
        } finally {
            sourceFile.delete()
        }
    }
}
