package com.aistudio.mediatool.feature.studio.render

import android.content.Context
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.FileExportManager
import com.aistudio.mediatool.core.media.MediaEngine
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import com.aistudio.mediatool.feature.studio.domain.latencyCompensatedPlacement
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class StudioExportFormat(val extension: String) {
    WAV("wav"),
    M4A("m4a"),
}

class StudioRenderEngine(context: Context) {
    private val appContext = context.applicationContext
    private val mediaEngine = MediaEngine(appContext)
    private val repository = StudioProjectRepository(appContext)

    suspend fun renderMix(project: StudioProject, format: StudioExportFormat): File = withContext(Dispatchers.IO) {
        val activeTracks = finalMixTracks(project)
        require(activeTracks.isNotEmpty()) { "Dự án chưa có track nào để xuất" }
        val workDir = File(appContext.cacheDir, "studio_final_${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        try {
            val trackFiles = mutableListOf<File>()
            activeTracks.forEachIndexed { index, track ->
                val sources = buildTrackSources(project, track)
                if (sources.isEmpty()) return@forEachIndexed
                val target = File(workDir, "track_${index.toString().padStart(3, '0')}.wav")
                execute(
                    buildCommand(project, sources, target, StudioExportFormat.WAV, applyMaster = false),
                    "studio_export_track_stage",
                )
                require(target.isFile && target.length() > 0L) { "Không thể dựng lớp ${track.name}" }
                trackFiles += target
            }
            require(trackFiles.isNotEmpty()) { "Dự án chưa có lớp âm thanh hợp lệ để xuất" }
            val target = FileExportManager.resultFile(appContext, "${project.name}_Studio_Mix", format.extension)
            execute(
                buildFinalStageCommand(project, trackFiles, target, format, StudioMasteringOptions()),
                "studio_export_final_master",
            )
            require(target.isFile && target.length() > 0L) { "File mix xuất ra bị rỗng" }
            target
        } finally {
            workDir.deleteRecursively()
        }
    }

    suspend fun renderDryMix(project: StudioProject, format: StudioExportFormat): File = withContext(Dispatchers.IO) {
        val target = FileExportManager.resultFile(appContext, "${project.name}_Studio_Mix", format.extension)
        val sources = buildFinalMixSources(project)
        require(sources.isNotEmpty()) { "Dự án chưa có track nào để xuất" }
        execute(buildCommand(project, sources, target, format, applyMaster = true), "studio_export_dry_mix")
        require(target.isFile && target.length() > 0L) { "File mix xuất ra bị rỗng" }
        target
    }

    suspend fun renderStems(project: StudioProject): File = withContext(Dispatchers.IO) {
        val rendered = mutableListOf<File>()
        val temporaryFiles = mutableListOf<File>()
        try {
            project.tracks
                .filter { !it.muted }
                .forEachIndexed { index, track ->
                    val sources = buildTrackSources(project, track)
                    if (sources.isEmpty()) return@forEachIndexed
                    val safeName = DocumentUtils.sanitizeFileName(track.name).ifBlank { "Track_${index + 1}" }
                    val target = FileExportManager.resultFile(appContext, "${project.name}_${safeName}", "wav")
                    temporaryFiles += target
                    execute(
                        buildCommand(project, sources, target, StudioExportFormat.WAV, applyMaster = false),
                        "studio_export_stem",
                    )
                    if (target.isFile && target.length() > 0L) rendered += target
                }
            require(rendered.isNotEmpty()) { "Không có stem nào để xuất" }
            FileExportManager.zipFiles(appContext, rendered, "${project.name}_Studio_Stems")
        } finally {
            temporaryFiles.forEach { file -> runCatching { file.delete() } }
        }
    }

    private suspend fun execute(command: String, phase: String) {
        when (val terminal = mediaEngine.executeFFmpegCommand(command, diagnosticPhase = phase).first {
            it is MediaEngine.ExecutionState.Success || it is MediaEngine.ExecutionState.Error
        }) {
            is MediaEngine.ExecutionState.Success -> Unit
            is MediaEngine.ExecutionState.Error -> error(
                terminal.failStackTrace ?: terminal.logs ?: "Không thể render Studio audio",
            )
            else -> error("Studio render kết thúc ở trạng thái không hợp lệ")
        }
    }

    private fun finalMixTracks(project: StudioProject): List<StudioTrack> {
        val hasSolo = project.tracks.any { it.solo }
        return project.tracks.filter { track -> !track.muted && (!hasSolo || track.solo) }
    }

    private fun buildFinalMixSources(project: StudioProject): List<RenderSource> =
        finalMixTracks(project).flatMap { buildTrackSources(project, it) }

    private fun buildTrackSources(project: StudioProject, track: StudioTrack): List<RenderSource> {
        if (track.type == StudioTrackType.BEAT) {
            val asset = project.beatAsset() ?: return emptyList()
            val file = repository.assetFile(project.id, asset.id) ?: return emptyList()
            return listOf(
                RenderSource(
                    file = file,
                    sourceSampleRate = asset.sampleRate ?: project.timelineSampleRate,
                    sourceStartFrame = null,
                    sourceEndFrame = null,
                    timelineStartFrame = 0L,
                    gainDb = track.volumeDb,
                    pan = track.pan,
                    fadeInFrames = 0L,
                    fadeOutFrames = 0L,
                ),
            )
        }

        val clips = if (track.clips.isNotEmpty()) {
            track.clips
        } else {
            activeTake(track)?.let { listOf(fullTakeClip(project, it)) }.orEmpty()
        }
        return clips.map { clip ->
            val asset = requireNotNull(project.asset(clip.sourceAssetId)) { "Clip ${clip.id} thiếu asset" }
            val file = requireNotNull(repository.assetFile(project.id, asset.id)) { "Thiếu file audio cho ${asset.displayName}" }
            RenderSource(
                file = file,
                sourceSampleRate = asset.sampleRate ?: project.timelineSampleRate,
                sourceStartFrame = clip.sourceStartFrame,
                sourceEndFrame = clip.sourceEndFrame,
                timelineStartFrame = clip.timelineStartFrame,
                gainDb = (track.volumeDb + clip.gainDb).coerceIn(-60f, 18f),
                pan = track.pan.coerceIn(-1f, 1f),
                fadeInFrames = clip.fadeInFrames,
                fadeOutFrames = clip.fadeOutFrames,
            )
        }
    }

    private fun buildFinalStageCommand(
        project: StudioProject,
        trackFiles: List<File>,
        target: File,
        format: StudioExportFormat,
        mastering: StudioMasteringOptions,
    ): String {
        val inputArgs = trackFiles.joinToString(" ") { "-i ${quote(it.absolutePath)}" }
        val labels = trackFiles.indices.joinToString("") { "[$it:a]" }
        val filters = mutableListOf(
            "${labels}amix=inputs=${trackFiles.size}:normalize=0:dropout_transition=0",
            "volume=${formatDb(project.masterMix.gainDb)}dB",
        )
        filters += StudioMasteringFilter.chain(mastering)
        if (project.masterMix.limiterEnabled) {
            filters += "alimiter=limit=0.98:attack=5:release=50:level=0:latency=1"
        }
        target.parentFile?.mkdirs()
        target.delete()
        val codec = codec(format)
        return "-y $inputArgs -filter_complex ${quote(filters.joinToString(",") + "[outa]")} -map [outa] -ar ${project.timelineSampleRate} $codec ${quote(target.absolutePath)}"
    }

    private fun buildCommand(
        project: StudioProject,
        sources: List<RenderSource>,
        target: File,
        format: StudioExportFormat,
        applyMaster: Boolean,
    ): String {
        val inputArgs = sources.joinToString(" ") { "-i ${quote(it.file.absolutePath)}" }
        val filters = mutableListOf<String>()
        val labels = mutableListOf<String>()
        sources.forEachIndexed { index, source ->
            val label = "s$index"
            labels += "[$label]"
            val chain = mutableListOf<String>()
            if (source.sourceStartFrame != null && source.sourceEndFrame != null) {
                chain += "atrim=start_sample=${source.sourceStartFrame}:end_sample=${source.sourceEndFrame}"
                chain += "asetpts=PTS-STARTPTS"
            }
            chain += "aresample=${project.timelineSampleRate}"
            chain += "aformat=channel_layouts=stereo"
            chain += "volume=${formatDb(source.gainDb)}dB"
            val left = if (source.pan > 0f) 1f - source.pan else 1f
            val right = if (source.pan < 0f) 1f + source.pan else 1f
            chain += "pan=stereo|c0=${formatNumber(left)}*c0|c1=${formatNumber(right)}*c1"
            val sourceLength = if (source.sourceStartFrame != null && source.sourceEndFrame != null) {
                (source.sourceEndFrame - source.sourceStartFrame).coerceAtLeast(0L)
            } else null
            if (source.fadeInFrames > 0L) {
                chain += "afade=t=in:st=0:d=${seconds(source.fadeInFrames, source.sourceSampleRate)}"
            }
            if (source.fadeOutFrames > 0L && sourceLength != null) {
                val fadeStart = (sourceLength - source.fadeOutFrames).coerceAtLeast(0L)
                chain += "afade=t=out:st=${seconds(fadeStart, source.sourceSampleRate)}:d=${seconds(source.fadeOutFrames, source.sourceSampleRate)}"
            }
            if (source.timelineStartFrame > 0L) {
                chain += "adelay=${source.timelineStartFrame}S:all=1"
            }
            filters += "[$index:a]${chain.joinToString(",")}[$label]"
        }

        val mixed = labels.joinToString("") + "amix=inputs=${labels.size}:normalize=0:dropout_transition=0"
        val master = if (applyMaster) {
            buildString {
                append(",volume=${formatDb(project.masterMix.gainDb)}dB")
                if (project.masterMix.limiterEnabled) {
                    append(",alimiter=limit=0.98:attack=5:release=50:level=0:latency=1")
                }
            }
        } else {
            ""
        }
        filters += "$mixed$master[outa]"

        target.parentFile?.mkdirs()
        target.delete()
        return "-y $inputArgs -filter_complex ${quote(filters.joinToString(";"))} -map [outa] -ar ${project.timelineSampleRate} ${codec(format)} ${quote(target.absolutePath)}"
    }

    private fun codec(format: StudioExportFormat): String = when (format) {
        StudioExportFormat.WAV -> "-c:a pcm_s16le"
        StudioExportFormat.M4A -> "-c:a aac -b:a 256k"
    }

    private fun activeTake(track: StudioTrack): StudioTake? =
        track.activeTakeId?.let { id -> track.takes.firstOrNull { it.id == id } }
            ?: track.takes.lastOrNull()

    private fun fullTakeClip(project: StudioProject, take: StudioTake): StudioClip {
        val placement = take.latencyCompensatedPlacement(project.timelineSampleRate)
        return StudioClip(
            id = "render-${take.id}",
            sourceAssetId = take.assetId,
            sourceTakeId = take.id,
            timelineStartFrame = placement.timelineStartFrame,
            sourceStartFrame = placement.sourceStartFrame,
            sourceEndFrame = placement.sourceEndFrame,
        )
    }

    private fun seconds(frames: Long, rate: Int): String =
        String.format(Locale.US, "%.9f", frames.toDouble() / rate.coerceAtLeast(1).toDouble())

    private fun formatDb(value: Float): String = String.format(Locale.US, "%.3f", value.coerceIn(-60f, 18f))
    private fun formatNumber(value: Float): String = String.format(Locale.US, "%.6f", value)

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private data class RenderSource(
        val file: File,
        val sourceSampleRate: Int,
        val sourceStartFrame: Long?,
        val sourceEndFrame: Long?,
        val timelineStartFrame: Long,
        val gainDb: Float,
        val pan: Float,
        val fadeInFrames: Long,
        val fadeOutFrames: Long,
    )
}
