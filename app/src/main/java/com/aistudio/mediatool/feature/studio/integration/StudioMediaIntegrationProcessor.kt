package com.aistudio.mediatool.feature.studio.integration

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.aistudio.mediatool.core.media.MediaEngine
import com.aistudio.mediatool.core.ml.ModelDownloader
import com.aistudio.mediatool.core.ml.VoiceCleanupConfig
import com.aistudio.mediatool.core.ml.VoiceCleanupLoudnessMode
import com.aistudio.mediatool.core.ml.VoiceCleanupModelRegistry
import com.aistudio.mediatool.core.ml.VoiceCleanupProcessor
import com.aistudio.mediatool.core.ml.VoiceCleanupState
import com.aistudio.mediatool.core.ml.VoiceCleanupWindowMode
import com.aistudio.mediatool.core.spatial.SpatialAudioConfig
import com.aistudio.mediatool.core.spatial.SpatialAudioEngine
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.data.StudioWavFile
import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.render.StudioProFilterBuilder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class StudioMediaProcessorKind(
    val id: String,
    val label: String,
    val suffix: String,
) {
    VOICE_CLEANUP("voice_cleanup", "AI Voice Cleanup", "Clean"),
    VOCAL_POLISH("vocal_polish", "Vocal Polish", "Polish"),
    SPATIAL_8D("spatial_8d", "Spatial / 8D", "8D"),
    PRO_VOCAL_CHAIN("pro_vocal_chain", "Pro Vocal Chain", "Pro"),
}

data class StudioMediaIntegrationResult(
    val project: StudioProject,
    val asset: StudioAsset,
)

/** Reuses MediaTool processors while preserving immutable Studio source assets. */
class StudioMediaIntegrationProcessor(context: Context) {
    private val appContext = context.applicationContext
    private val repository = StudioProjectRepository(appContext)
    private val mediaEngine = MediaEngine(appContext)
    @Volatile private var voiceCleanupModelReadyCache: Boolean? = null

    fun isVoiceCleanupModelReady(forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh) voiceCleanupModelReadyCache?.let { return it }
        val descriptor = VoiceCleanupModelRegistry.mossFormer2
        return ModelDownloader(appContext).isModelDownloaded(descriptor.modelSpec).also {
            voiceCleanupModelReadyCache = it
        }
    }

    suspend fun process(
        projectId: String,
        sourceAssetId: String,
        kind: StudioMediaProcessorKind,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
    ): StudioMediaIntegrationResult = withContext(Dispatchers.IO) {
        val project = requireNotNull(repository.load(projectId)) { "Không tìm thấy dự án Studio" }
        val sourceAsset = requireNotNull(project.asset(sourceAssetId)) { "Không tìm thấy audio source" }
        val sourceFile = requireNotNull(repository.assetFile(project.id, sourceAsset.id)) { "Audio source không còn tồn tại" }
        val sourceRate = sourceAsset.sampleRate ?: project.timelineSampleRate
        val sourceChannels = sourceAsset.channelCount ?: 1
        val targetChannels = if (kind == StudioMediaProcessorKind.SPATIAL_8D) 2 else sourceChannels.coerceIn(1, 2)
        val (relativePath, target) = repository.derivedOutputFile(project.id, kind.id)
        val workDir = File(appContext.cacheDir, "studio_processor_${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        try {
            onProgress(0.02f, "Đang chuẩn bị ${kind.label}")
            when (kind) {
                StudioMediaProcessorKind.VOICE_CLEANUP -> renderVoiceCleanup(
                    sourceFile, File(workDir, "processed.s16"), target, sourceRate, targetChannels, onProgress,
                )
                StudioMediaProcessorKind.VOCAL_POLISH -> renderFilterChain(
                    sourceFile, File(workDir, "processed.s16"), target, sourceRate, targetChannels,
                    StudioProFilterBuilder.build(StudioProFilterBuilder.polishPreset()), onProgress,
                )
                StudioMediaProcessorKind.SPATIAL_8D -> renderSpatial(
                    sourceFile, sourceAsset, File(workDir, "processed.s16"), File(workDir, "spatial.wav"),
                    target, sourceRate, onProgress,
                )
                StudioMediaProcessorKind.PRO_VOCAL_CHAIN -> renderFilterChain(
                    sourceFile, File(workDir, "processed.s16"), target, sourceRate, targetChannels,
                    StudioProFilterBuilder.build(project.proSettings.vocalFx), onProgress,
                )
            }
            val info = requireNotNull(StudioWavFile.inspectCanonicalPcm16(target)) {
                "Processor không tạo WAV Studio hợp lệ"
            }
            require(info.dataFrames > 0L) { "Bản xử lý bị rỗng" }
            onProgress(0.96f, "Đang ghi derived asset")
            val displayBase = sourceAsset.displayName.substringBeforeLast('.').ifBlank { "Audio" }
            val (saved, derived) = repository.commitDerivedAsset(
                projectId = project.id,
                sourceAssetId = sourceAsset.id,
                relativePath = relativePath,
                displayName = "$displayBase • ${kind.suffix}",
                processorId = kind.id,
                processorLabel = kind.label,
                processorConfig = processorConfig(project, kind),
            )
            onProgress(1f, "Đã tạo ${derived.displayName}")
            StudioMediaIntegrationResult(saved, derived)
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun renderVoiceCleanup(
        sourceFile: File,
        rawTarget: File,
        finalTarget: File,
        sampleRate: Int,
        channels: Int,
        onProgress: suspend (Float, String) -> Unit,
    ) {
        val descriptor = VoiceCleanupModelRegistry.mossFormer2
        val downloader = ModelDownloader(appContext)
        val modelFile = downloader.modelFile(descriptor.modelSpec)
        require(downloader.isModelFileValid(modelFile, descriptor.modelSpec)) {
            voiceCleanupModelReadyCache = false
            "Chưa có model Voice Cleanup. Hãy tải model trong công cụ Làm sạch giọng trước."
        }
        voiceCleanupModelReadyCache = true
        val sourceUri: Uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.provider", sourceFile)
        val config = VoiceCleanupConfig(
            windowMode = VoiceCleanupWindowMode.BALANCED_10S,
            cleanupStrengthPercent = 68,
            loudnessMode = VoiceCleanupLoudnessMode.MATCH_SOURCE,
            targetLufs = -16f,
            outputGainDb = 0f,
            limiterEnabled = true,
            limiterCeilingDb = -1f,
        )
        val processor = VoiceCleanupProcessor(
            context = appContext,
            modelFile = modelFile,
            taskId = "studio-${UUID.randomUUID()}",
            config = config,
        )
        var success: VoiceCleanupState.Success? = null
        processor.cleanup(sourceUri).collect { state ->
            when (state) {
                is VoiceCleanupState.Progress -> onProgress(0.05f + state.value.coerceIn(0f, 1f) * 0.72f, state.phase)
                is VoiceCleanupState.Success -> success = state
            }
        }
        val terminal = requireNotNull(success) { "Voice Cleanup không trả kết quả" }
        try {
            canonicalize(terminal.outputFile, rawTarget, finalTarget, sampleRate, channels, null, "studio_voice_cleanup_canonicalize")
        } finally {
            terminal.outputFile.delete()
        }
    }

    private suspend fun renderFilterChain(
        sourceFile: File,
        rawTarget: File,
        finalTarget: File,
        sampleRate: Int,
        channels: Int,
        filter: String,
        onProgress: suspend (Float, String) -> Unit,
    ) {
        onProgress(0.12f, "Đang render chuỗi hiệu ứng")
        canonicalize(sourceFile, rawTarget, finalTarget, sampleRate, channels, filter, "studio_media_integration_filter")
        onProgress(0.90f, "Đã render hiệu ứng")
    }

    private suspend fun renderSpatial(
        sourceFile: File,
        sourceAsset: StudioAsset,
        rawTarget: File,
        encodedTarget: File,
        finalTarget: File,
        sampleRate: Int,
        onProgress: suspend (Float, String) -> Unit,
    ) {
        val rate = (sourceAsset.sampleRate ?: sampleRate).coerceAtLeast(1)
        val durationMs = ((sourceAsset.durationFrames ?: 0L) * 1_000L / rate).coerceAtLeast(1L)
        val spatial = SpatialAudioEngine(appContext, mediaEngine)
        val config = SpatialAudioConfig(cycleSeconds = 8f, spatialBlend = 0.88f, reverbWet = 0.07f, outputGainDb = -1f)
        var success = false
        var failure: String? = null
        spatial.process(
            inputSaf = sourceFile.absolutePath,
            output = encodedTarget,
            sourceDurationMs = durationMs,
            config = config,
            preFilters = emptyList(),
            isVideoMode = false,
            modeIndex = 0,
            extension = "wav",
            preview = false,
        ).collect { state ->
            when (state) {
                is SpatialAudioEngine.State.Progress -> onProgress(
                    0.05f + state.percent.coerceIn(0f, 100f) / 100f * 0.78f,
                    state.message,
                )
                is SpatialAudioEngine.State.Success -> success = true
                is SpatialAudioEngine.State.Error -> failure = state.message
            }
        }
        failure?.let(::error)
        require(success && encodedTarget.isFile && encodedTarget.length() > 0L) { "Spatial Audio không tạo kết quả" }
        canonicalize(encodedTarget, rawTarget, finalTarget, sampleRate, 2, null, "studio_spatial_canonicalize")
    }

    private suspend fun canonicalize(
        source: File,
        rawTarget: File,
        finalTarget: File,
        sampleRate: Int,
        channels: Int,
        filter: String?,
        phase: String,
    ) {
        rawTarget.delete()
        val command = buildString {
            append("-y -hide_banner -loglevel error -i ").append(quote(source.absolutePath)).append(" -map 0:a:0 -vn ")
            if (!filter.isNullOrBlank() && filter != "anull") append("-af ").append(quote(filter)).append(' ')
            append("-ac ").append(channels.coerceIn(1, 2)).append(' ')
            append("-ar ").append(sampleRate.coerceAtLeast(8_000)).append(' ')
            append("-f s16le ").append(quote(rawTarget.absolutePath))
        }
        val terminal = mediaEngine.executeFFmpegCommand(command, diagnosticPhase = phase).first {
            it is MediaEngine.ExecutionState.Success || it is MediaEngine.ExecutionState.Error
        }
        if (terminal is MediaEngine.ExecutionState.Error) {
            error(terminal.failStackTrace ?: terminal.logs ?: "Không thể render Studio processor")
        }
        require(rawTarget.isFile && rawTarget.length() > 0L) { "Processor không tạo PCM" }
        requireNotNull(
            StudioWavFile.writeFromRawPcm16(rawTarget, finalTarget, sampleRate, channels.coerceIn(1, 2)),
        ) { "Không thể đóng gói derived WAV" }
    }

    private fun processorConfig(project: StudioProject, kind: StudioMediaProcessorKind): String = JSONObject().apply {
        put("processor", kind.id)
        put("projectSchema", project.schemaVersion)
        when (kind) {
            StudioMediaProcessorKind.VOICE_CLEANUP -> {
                put("strength", 68)
                put("model", VoiceCleanupModelRegistry.MOSSFORMER2_ID)
            }
            StudioMediaProcessorKind.VOCAL_POLISH -> put("preset", "balanced_polish_v1")
            StudioMediaProcessorKind.SPATIAL_8D -> {
                put("cycleSeconds", 8.0)
                put("spatialBlend", 0.88)
            }
            StudioMediaProcessorKind.PRO_VOCAL_CHAIN -> put("vocalFx", project.proSettings.vocalFx.toString())
        }
    }.toString()

    private fun quote(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
