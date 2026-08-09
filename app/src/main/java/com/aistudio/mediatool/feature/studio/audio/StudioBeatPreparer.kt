package com.aistudio.mediatool.feature.studio.audio

import android.content.Context
import com.aistudio.mediatool.core.media.MediaEngine
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.data.StudioWaveform
import com.aistudio.mediatool.feature.studio.data.StudioWaveformStore
import com.aistudio.mediatool.feature.studio.domain.STUDIO_TIMELINE_SAMPLE_RATE
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

data class PreparedStudioBeat(
    val project: StudioProject,
    val pcmFile: File,
    val waveform: StudioWaveform,
    val frameCount: Long,
)

/**
 * Materializes the immutable imported beat into deterministic PCM16 stereo.
 * The realtime native callback only reads this cache; decoding never happens on
 * the audio callback thread.
 */
class StudioBeatPreparer(
    context: Context,
    private val repository: StudioProjectRepository,
    private val waveformStore: StudioWaveformStore,
) {
    private val mediaEngine = MediaEngine(context.applicationContext)

    suspend fun prepare(projectId: String): PreparedStudioBeat = withContext(Dispatchers.IO) {
        var project = requireNotNull(repository.load(projectId)) { "Không tìm thấy dự án Studio" }
        val beat = requireNotNull(project.beatAsset()) { "Dự án Studio chưa có nhạc beat" }
        val source = requireNotNull(repository.assetFile(project.id, beat.id)) {
            "Không tìm thấy file beat của dự án Studio"
        }
        val target = repository.resolveProjectFile(
            project.id,
            "cache/beat_${beat.id}_48000_stereo_s16le.pcm",
        )
        target.parentFile?.mkdirs()

        if (!target.isFile || target.length() < FRAME_BYTES) {
            target.delete()
            val command = buildString {
                append("-y -hide_banner -loglevel error -i ")
                append(quote(source.absolutePath))
                append(" -map 0:a:0 -vn -ac 2 -ar ")
                append(STUDIO_TIMELINE_SAMPLE_RATE)
                append(" -f s16le ")
                append(quote(target.absolutePath))
            }
            var failure: String? = null
            mediaEngine.executeFFmpegCommand(
                command = command,
                diagnosticPhase = "studio_prepare_beat",
                startupTimeoutMs = 30_000L,
            ).collect { state ->
                if (state is MediaEngine.ExecutionState.Error) {
                    failure = state.failStackTrace ?: state.logs ?: "Không thể giải mã nhạc beat"
                }
            }
            if (failure != null || !target.isFile || target.length() < FRAME_BYTES) {
                target.delete()
                error(failure ?: "Không tạo được realtime PCM cache cho beat")
            }
        }

        val alignedBytes = target.length() - (target.length() % FRAME_BYTES)
        require(alignedBytes >= FRAME_BYTES) { "PCM beat cache không chứa frame hợp lệ" }
        if (alignedBytes != target.length()) {
            RandomAccessFile(target, "rw").use { file ->
                file.setLength(alignedBytes)
                file.fd.sync()
            }
        }
        val frameCount = alignedBytes / FRAME_BYTES
        project = repository.updatePreparedBeat(
            projectId = project.id,
            sampleRate = STUDIO_TIMELINE_SAMPLE_RATE,
            channelCount = CHANNEL_COUNT,
            durationFrames = frameCount,
        )
        val waveform = waveformStore.load(project.id, beat.id)
            ?: waveformStore.generateFromRawPcm16(
                projectId = project.id,
                assetId = beat.id,
                pcmFile = target,
                sampleRate = STUDIO_TIMELINE_SAMPLE_RATE,
                channelCount = CHANNEL_COUNT,
            )
        PreparedStudioBeat(project, target, waveform, frameCount)
    }

    private fun quote(path: String): String = "\"${path.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    companion object {
        private const val CHANNEL_COUNT = 2
        private const val FRAME_BYTES = CHANNEL_COUNT * 2L
    }
}
