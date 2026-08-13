package com.aistudio.mediatool.feature.studio.integration

import android.content.Context
import com.aistudio.mediatool.feature.studio.audio.StudioHarmonyPlanner
import com.aistudio.mediatool.feature.studio.data.StudioGeneratedLayerResult
import com.aistudio.mediatool.feature.studio.data.StudioGeneratedVocalEditor
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class StudioHarmonyProcessor(context: Context) {
    private val repository = StudioProjectRepository(context.applicationContext)
    private val pipeline = StudioPitchRenderPipeline(context.applicationContext)

    suspend fun preview(
        projectId: String,
        trackId: String,
        config: StudioHarmonyConfig,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): StudioPitchPreviewResult {
        val project = withContext(Dispatchers.IO) {
            requireNotNull(repository.load(projectId)) { "Không tìm thấy dự án Studio" }
        }
        val key = project.proSettings.musicalKey
        val root = requireNotNull(key.root) { "Hãy chọn tông bài trước khi tạo bè" }
        val scale = requireNotNull(key.scale) { "Hãy chọn Trưởng hoặc Thứ trước khi tạo bè" }
        val safe = config.copy(
            volumeDb = config.volumeDb.coerceIn(-24f, 6f),
            pan = config.pan.coerceIn(-1f, 1f),
        )
        val json = JSONObject()
            .put("processor", "studio_harmony_v1")
            .put("root", root.name)
            .put("scale", scale.name)
            .put("preset", safe.preset.name)
            .put("volumeDb", safe.volumeDb)
            .put("pan", safe.pan)
            .toString()
        return pipeline.render(
            projectId = projectId,
            trackId = trackId,
            processorId = "studio_harmony_v1",
            processorLabel = "Studio Harmony",
            suffix = safe.preset.label,
            configJson = json,
            buildPlan = { input ->
                StudioHarmonyPlanner.build(
                    mono = input.mono,
                    analysisRate = input.analysisRate,
                    sourceRate = input.sourceRate,
                    sourceFrames = input.sourceFrames,
                    root = root,
                    scale = scale,
                    preset = safe.preset,
                )
            },
            onProgress = onProgress,
        )
    }

    suspend fun apply(
        projectId: String,
        preview: StudioPitchPreviewResult,
        config: StudioHarmonyConfig,
    ): StudioGeneratedLayerResult = withContext(Dispatchers.IO) {
        val project = requireNotNull(repository.load(projectId)) { "Không tìm thấy dự án Studio" }
        val result = StudioGeneratedVocalEditor.addHarmonyLayer(
            project = project,
            sourceTrackId = preview.sourceTrackId,
            assetId = preview.asset.id,
            preset = config.preset,
            volumeDb = config.volumeDb,
            pan = config.pan,
        )
        result.copy(project = repository.save(result.project))
    }
}
