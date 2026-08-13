package com.aistudio.mediatool.feature.studio.ui

import android.content.Context
import com.aistudio.mediatool.feature.studio.data.StudioGeneratedLayerResult
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.integration.StudioAutoTuneConfig
import com.aistudio.mediatool.feature.studio.integration.StudioAutoTuneProcessor
import com.aistudio.mediatool.feature.studio.integration.StudioHarmonyConfig
import com.aistudio.mediatool.feature.studio.integration.StudioHarmonyProcessor
import com.aistudio.mediatool.feature.studio.integration.StudioPitchPreviewResult
import java.io.File

internal class StudioPitchToolSession(context: Context) {
    private val repository = StudioProjectRepository(context.applicationContext)
    private val autoTune = StudioAutoTuneProcessor(context.applicationContext)
    private val harmony = StudioHarmonyProcessor(context.applicationContext)

    fun load(projectId: String): StudioProject? = repository.load(projectId)

    fun previewFile(projectId: String, assetId: String): File? = repository.assetFile(projectId, assetId)

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
