package com.aistudio.mediatool.feature.studio.integration

import android.content.Context
import com.aistudio.mediatool.feature.studio.audio.StudioAutoTunePlanner
import com.aistudio.mediatool.feature.studio.data.StudioGeneratedLayerResult
import com.aistudio.mediatool.feature.studio.data.StudioGeneratedVocalEditor
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class StudioAutoTuneProcessor(context: Context) {
    private val repository = StudioProjectRepository(context.applicationContext)
    private val pipeline = StudioPitchRenderPipeline(context.applicationContext)

    suspend fun preview(
        projectId: String,
        trackId: String,
        config: StudioAutoTuneConfig,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): StudioPitchPreviewResult {
        val project = withContext(Dispatchers.IO) {
            requireNotNull(repository.load(projectId)) { "Không tìm thấy dự án Studio" }
        }
        val key = project.proSettings.musicalKey
        val root = requireNotNull(key.root) { "Hãy chọn tông bài trước khi dùng Auto-Tune" }
        val scale = requireNotNull(key.scale) { "Hãy chọn Trưởng hoặc Thứ trước khi dùng Auto-Tune" }
        val safe = config.copy(
            strength = config.strength.coerceIn(0f, 1f),
            maxCorrectionCents = config.maxCorrectionCents.coerceIn(25f, 600f),
        )
        val json = JSONObject()
            .put("processor", "studio_autotune_v1")
            .put("root", root.name)
            .put("scale", scale.name)
            .put("strength", safe.strength)
            .put("maxCorrectionCents", safe.maxCorrectionCents)
            .toString()
        return pipeline.render(
            projectId = projectId,
            trackId = trackId,
            processorId = "studio_autotune_v1",
            processorLabel = "Studio Auto-Tune",
            suffix = "Auto-Tune",
            configJson = json,
            buildPlan = { input ->
                StudioAutoTunePlanner.build(
                    mono = input.mono,
                    analysisRate = input.analysisRate,
                    sourceRate = input.sourceRate,
                    sourceFrames = input.sourceFrames,
                    root = root,
                    scale = scale,
                    strength = safe.strength,
                    maxCents = safe.maxCorrectionCents,
                )
            },
            onProgress = onProgress,
        )
    }

    suspend fun apply(projectId: String, preview: StudioPitchPreviewResult): StudioGeneratedLayerResult =
        withContext(Dispatchers.IO) {
            val project = requireNotNull(repository.load(projectId)) { "Không tìm thấy dự án Studio" }
            val result = StudioGeneratedVocalEditor.addAutoTuneVersion(project, preview.sourceTrackId, preview.asset.id)
            result.copy(project = repository.save(result.project))
        }

    suspend fun restore(
        projectId: String,
        sourceTrackId: String,
        generatedTrackId: String,
        sourceWasMuted: Boolean,
    ): StudioProject = withContext(Dispatchers.IO) {
        val project = requireNotNull(repository.load(projectId)) { "Không tìm thấy dự án Studio" }
        repository.save(
            StudioGeneratedVocalEditor.restoreAutoTune(project, sourceTrackId, generatedTrackId, sourceWasMuted),
        )
    }
}
