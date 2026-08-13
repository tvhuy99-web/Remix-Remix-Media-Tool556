package com.aistudio.mediatool.feature.studio.ui

import android.content.Context
import com.aistudio.mediatool.feature.studio.data.StudioGeneratedLayerResult
import com.aistudio.mediatool.feature.studio.data.StudioPitchSourceProject
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.integration.StudioAutoTuneConfig
import com.aistudio.mediatool.feature.studio.integration.StudioAutoTuneProcessor
import com.aistudio.mediatool.feature.studio.integration.StudioHarmonyConfig
import com.aistudio.mediatool.feature.studio.integration.StudioHarmonyProcessor
import com.aistudio.mediatool.feature.studio.integration.StudioPitchPreviewResult
import com.aistudio.mediatool.feature.studio.render.StudioExportFormat
import com.aistudio.mediatool.feature.studio.render.StudioRenderEngine
import java.io.File

internal class StudioPitchToolSession(context: Context) {
    private val appContext = context.applicationContext
    private val repository = StudioProjectRepository(appContext)
    private val renderer = StudioRenderEngine(appContext)
    private val autoTune = StudioAutoTuneProcessor(appContext)
    private val harmony = StudioHarmonyProcessor(appContext)

    fun load(projectId: String): StudioProject? = repository.load(projectId)

    fun previewFile(projectId: String, assetId: String): File? = repository.assetFile(projectId, assetId)

    suspend fun renderSourcePreview(projectId: String, trackId: String): File {
        val project = requireNotNull(repository.load(projectId)) { "Không tìm thấy dự án Studio" }
        return renderer.renderDryMix(
            StudioPitchSourceProject.create(project, trackId),
            StudioExportFormat.WAV,
        )
    }

    suspend fun preview(
        projectId: String,
        trackId: String,
        mode: StudioPitchToolMode,
        autoTuneConfig: StudioAutoTuneConfig,
        harmonyConfig: StudioHarmonyConfig,
        onProgress: (Float, String) -> Unit,
    ): StudioPitchPreviewResult = when (mode) {
        StudioPitchToolMode.AUTO_TUNE -> autoTune.preview(
            projectId = projectId,
            trackId = trackId,
            config = autoTuneConfig,
            onProgress = onProgress,
        )
        StudioPitchToolMode.HARMONY -> harmony.preview(
            projectId = projectId,
            trackId = trackId,
            config = harmonyConfig,
            onProgress = onProgress,
        )
    }

    suspend fun apply(
        projectId: String,
        mode: StudioPitchToolMode,
        preview: StudioPitchPreviewResult,
        harmonyConfig: StudioHarmonyConfig,
    ): StudioGeneratedLayerResult = when (mode) {
        StudioPitchToolMode.AUTO_TUNE -> autoTune.apply(projectId, preview)
        StudioPitchToolMode.HARMONY -> harmony.apply(projectId, preview, harmonyConfig)
    }

    suspend fun restoreAutoTune(
        projectId: String,
        sourceTrackId: String,
        generatedTrackId: String,
        sourceWasMuted: Boolean,
    ): StudioProject = autoTune.restore(projectId, sourceTrackId, generatedTrackId, sourceWasMuted)
}
