package com.aistudio.mediatool.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class SynchronizedStemTrack(
    val id: String,
    val label: String,
    val file: File,
)

/** Pure block mixer for one FFmpeg-produced 7.1 frame stream. */
internal object SynchronizedStemMixerMath {
    const val SOURCE_CHANNELS = 8
    const val OUTPUT_CHANNELS = 2

    fun mixInterleaved(
        source: FloatArray,
        frames: Int,
        trackCount: Int,
        gains: FloatArray,
        destination: FloatArray,
    ): Int {
        require(trackCount in 1..4)
        require(gains.size >= trackCount)
        require(source.size >= frames * SOURCE_CHANNELS)
        require(destination.size >= frames * OUTPUT_CHANNELS)

        for (frame in 0 until frames) {
            val sourceBase = frame * SOURCE_CHANNELS
            var left = 0f
            var right = 0f
            for (track in 0 until trackCount) {
                val gain = gains[track].takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                val channelBase = sourceBase + track * 2
                val sourceLeft = source[channelBase].takeIf { it.isFinite() } ?: 0f
                val sourceRight = source[channelBase + 1].takeIf { it.isFinite() } ?: 0f
                left += sourceLeft * gain
                right += sourceRight * gain
            }
            destination[frame * OUTPUT_CHANNELS] = if (left.isFinite()) left else 0f
            destination[frame * OUTPUT_CHANNELS + 1] = if (right.isFinite()) right else 0f
        }
        return frames * OUTPUT_CHANNELS
    }
}

/**
 * Sample-aligned preview engine.
 *
 * One FFmpeg session decodes every stem from the same sample offset. Each stereo stem is placed in
 * a dedicated channel pair of one 7.1 float stream. Kotlin then applies live gains and writes one
 * stereo stream to one AudioTrack, so play, pause and seek share a single hardware clock.
 */
internal class SynchronizedStemMixerController(
    context: Context,
    val tracks: List<SynchronizedStemTrack>,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val generation = AtomicLong(0L)
    private val playbackRequested = AtomicBoolean(false)
    private val gains = AtomicReference(FloatArray(tracks.size) { 1f })
    private val activeSessionId = AtomicLong(-1L)
    private val activePipe = AtomicReference<String?>(null)
    private val activeAudioTrack = AtomicReference<AudioTrack?>(null)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private val _durationMs = MutableStateFlow(1L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var worker: Job? = null
    private var released = false

    init {
        require(tracks.size in 1..4) { "Mixer hỗ trợ tối đa bốn stem" }
        require(tracks.map(SynchronizedStemTrack::id).distinct().size == tracks.size)
        require(tracks.all { it.file.isFile && it.file.length() > 0L })
        scope.launch {
            _durationMs.value = readDurationMs(tracks.first().file).coerceAtLeast(1L)
        }
    }

    fun updateGains(values: FloatArray) {
        require(values.size == tracks.size)
        gains.set(FloatArray(values.size) { index -> values[index].coerceIn(0f, 1f) })
    }

    fun play(requestedPositionMs: Long) {
        synchronized(lock) {
            if (released) return
            playbackRequested.set(true)
            val currentTrack = activeAudioTrack.get()
            if (worker?.isActive == true && currentTrack != null) {
                runCatching(currentTrack::play)
                _isPlaying.value = true
                return
            }
        }
        restart(requestedPositionMs, playImmediately = true)
    }

    fun pause() {
        synchronized(lock) {
            if (released) return
            playbackRequested.set(false)
            runCatching { activeAudioTrack.get()?.pause() }
            _isPlaying.value = false
        }
    }

    fun seekTo(requestedPositionMs: Long, resume: Boolean) {
        restart(requestedPositionMs, playImmediately = resume)
    }

    fun clearError() {
        _error.value = null
    }

    override fun close() {
        synchronized(lock) {
            if (released) return
            released = true
            stopActiveLocked()
        }
        scope.cancel()
    }

    private fun restart(requestedPositionMs: Long, playImmediately: Boolean) {
        val clampedMs = requestedPositionMs.coerceIn(0L, _durationMs.value.coerceAtLeast(1L))
        val currentGeneration: Long
        synchronized(lock) {
            if (released) return
            stopActiveLocked()
            playbackRequested.set(playImmediately)
            _positionMs.value = clampedMs
            _error.value = null
            currentGeneration = generation.incrementAndGet()
            if (!playImmediately) return
            worker = scope.launch { stream(currentGeneration, clampedMs) }
        }
    }

    private fun stopActiveLocked() {
        generation.incrementAndGet()
        playbackRequested.set(false)
        worker?.cancel()
        worker = null
        val sessionId = activeSessionId.getAndSet(-1L)
        if (sessionId >= 0L) FFmpegKit.cancel(sessionId)
        activePipe.getAndSet(null)?.let { pipe -> runCatching { FFmpegKitConfig.closeFFmpegPipe(pipe) } }
        activeAudioTrack.getAndSet(null)?.let(::releaseAudioTrack)
        _isPlaying.value = false
    }

    private suspend fun stream(currentGeneration: Long, startMs: Long) {
        val startFrame = startMs * SAMPLE_RATE / 1_000L
        var pipe: String? = null
        var audioTrack: AudioTrack? = null
        var sessionId = -1L
        try {
            pipe = FFmpegKitConfig.registerNewFFmpegPipe(appContext)
                ?: error("Không tạo được pipe đồng bộ cho mixer")
            if (!isCurrent(currentGeneration)) return
            activePipe.set(pipe)

            audioTrack = createAudioTrack()
            if (!isCurrent(currentGeneration)) return
            activeAudioTrack.set(audioTrack)

            val command = buildFfmpegCommand(pipe, startFrame)
            val session = FFmpegKit.executeAsync(command) { completed ->
                val wasActive = activeSessionId.compareAndSet(completed.sessionId, -1L)
                if (wasActive && isCurrent(currentGeneration) && !ReturnCode.isSuccess(completed.returnCode)) {
                    _error.value = "Không thể giải mã đồng bộ các stem để nghe thử"
                    DiagnosticLogger.error(
                        component = TAG,
                        event = "preview_ffmpeg_failed",
                        fields = mapOf(
                            "return_code" to completed.returnCode.toString(),
                            "track_count" to tracks.size,
                        ),
                    )
                }
            }
            sessionId = session.sessionId
            activeSessionId.set(sessionId)
            if (!isCurrent(currentGeneration)) {
                FFmpegKit.cancel(sessionId)
                return
            }

            readAndMixPipe(
                pipe = pipe,
                audioTrack = audioTrack,
                currentGeneration = currentGeneration,
                startFrame = startFrame,
            )
        } catch (_: CancellationException) {
            // Seek, dispose and restart use cancellation as the normal control path.
        } catch (error: Throwable) {
            if (isCurrent(currentGeneration)) {
                _error.value = error.message ?: "Không thể phát mixer đồng bộ"
                DiagnosticLogger.error(
                    component = TAG,
                    event = "preview_pipeline_failed",
                    message = error.message,
                    fields = mapOf("track_count" to tracks.size),
                    error = error,
                )
            }
        } finally {
            if (sessionId >= 0L && activeSessionId.compareAndSet(sessionId, -1L)) {
                FFmpegKit.cancel(sessionId)
            }
            if (pipe != null) {
                activePipe.compareAndSet(pipe, null)
                runCatching { FFmpegKitConfig.closeFFmpegPipe(pipe) }
            }
            if (audioTrack != null) {
                activeAudioTrack.compareAndSet(audioTrack, null)
                releaseAudioTrack(audioTrack)
            }
            if (isCurrent(currentGeneration)) {
                playbackRequested.set(false)
                _isPlaying.value = false
                synchronized(lock) {
                    worker = null
                }
            }
        }
    }

    private suspend fun readAndMixPipe(
        pipe: String,
        audioTrack: AudioTrack,
        currentGeneration: Long,
        startFrame: Long,
    ) {
        val bytesPerSourceFrame = SynchronizedStemMixerMath.SOURCE_CHANNELS * Float.SIZE_BYTES
        val byteBuffer = ByteArray(BLOCK_FRAMES * bytesPerSourceFrame)
        val sourceFloats = FloatArray(BLOCK_FRAMES * SynchronizedStemMixerMath.SOURCE_CHANNELS)
        val mixedFloats = FloatArray(BLOCK_FRAMES * SynchronizedStemMixerMath.OUTPUT_CHANNELS)
        val outputBytes = ByteBuffer.allocateDirect(
            BLOCK_FRAMES * SynchronizedStemMixerMath.OUTPUT_CHANNELS * Float.SIZE_BYTES,
        ).order(ByteOrder.nativeOrder())
        var bufferedBytes = 0
        var writtenFrames = 0L
        var playbackStarted = false

        FileInputStream(pipe).buffered(PIPE_BUFFER_BYTES).use { input ->
            while (scope.isActive && isCurrent(currentGeneration)) {
                val read = input.read(byteBuffer, bufferedBytes, byteBuffer.size - bufferedBytes)
                if (read < 0) break
                if (read == 0) continue
                bufferedBytes += read
                val completeBytes = bufferedBytes - bufferedBytes % bytesPerSourceFrame
                if (completeBytes == 0) continue
                val frames = completeBytes / bytesPerSourceFrame
                val floats = ByteBuffer.wrap(byteBuffer, 0, completeBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                floats.get(sourceFloats, 0, frames * SynchronizedStemMixerMath.SOURCE_CHANNELS)
                val sampleCount = SynchronizedStemMixerMath.mixInterleaved(
                    source = sourceFloats,
                    frames = frames,
                    trackCount = tracks.size,
                    gains = gains.get(),
                    destination = mixedFloats,
                )
                writeAll(
                    audioTrack = audioTrack,
                    samples = mixedFloats,
                    sampleCount = sampleCount,
                    buffer = outputBytes,
                    currentGeneration = currentGeneration,
                )
                if (!playbackStarted && playbackRequested.get()) {
                    audioTrack.play()
                    playbackStarted = true
                    _isPlaying.value = true
                    DiagnosticLogger.info(
                        component = TAG,
                        event = "preview_audio_started",
                        fields = mapOf(
                            "track_count" to tracks.size,
                            "buffer_bytes" to outputBytes.capacity(),
                            "encoding" to "PCM_FLOAT",
                        ),
                    )
                }
                writtenFrames += frames
                updatePosition(audioTrack, startFrame, writtenFrames)

                val leftoverBytes = bufferedBytes - completeBytes
                if (leftoverBytes > 0) {
                    System.arraycopy(byteBuffer, completeBytes, byteBuffer, 0, leftoverBytes)
                }
                bufferedBytes = leftoverBytes
            }
        }

        while (scope.isActive && isCurrent(currentGeneration)) {
            val playedFrames = audioTrack.playbackHeadPosition.toLong() and 0xffff_ffffL
            updatePosition(audioTrack, startFrame, writtenFrames)
            if (playedFrames >= writtenFrames) break
            delay(20L)
        }
        if (isCurrent(currentGeneration) && _positionMs.value >= _durationMs.value - 100L) {
            _positionMs.value = _durationMs.value
        }
    }

    private suspend fun writeAll(
        audioTrack: AudioTrack,
        samples: FloatArray,
        sampleCount: Int,
        buffer: ByteBuffer,
        currentGeneration: Long,
    ) {
        require(sampleCount in 1..samples.size)
        require(sampleCount * Float.SIZE_BYTES <= buffer.capacity())
        buffer.clear()
        buffer.asFloatBuffer().put(samples, 0, sampleCount)
        buffer.limit(sampleCount * Float.SIZE_BYTES)
        buffer.position(0)

        var consecutiveZeroWrites = 0
        while (buffer.hasRemaining()) {
            if (!scope.isActive || !isCurrent(currentGeneration)) throw CancellationException()
            val writtenBytes = audioTrack.write(
                buffer,
                buffer.remaining(),
                AudioTrack.WRITE_BLOCKING,
            )
            when {
                writtenBytes > 0 -> consecutiveZeroWrites = 0
                writtenBytes == 0 -> {
                    if (!playbackRequested.get()) {
                        consecutiveZeroWrites = 0
                        delay(PAUSED_WRITE_RETRY_MS)
                        continue
                    }
                    consecutiveZeroWrites += 1
                    if (consecutiveZeroWrites == 1) {
                        DiagnosticLogger.warn(
                            component = TAG,
                            event = "preview_audio_write_stalled",
                            fields = mapOf(
                                "track_count" to tracks.size,
                                "play_state" to audioTrack.playState,
                                "remaining_bytes" to buffer.remaining(),
                            ),
                        )
                    }
                    require(consecutiveZeroWrites <= MAX_CONSECUTIVE_ZERO_WRITES) {
                        "AudioTrack không nhận dữ liệu mixer sau $consecutiveZeroWrites lần thử"
                    }
                    if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        runCatching(audioTrack::play)
                    }
                    delay(ZERO_WRITE_RETRY_MS)
                }
                else -> error("AudioTrack từ chối dữ liệu mixer: $writtenBytes")
            }
        }
    }

    private fun updatePosition(audioTrack: AudioTrack, startFrame: Long, writtenFrames: Long) {
        val playedFrames = audioTrack.playbackHeadPosition.toLong() and 0xffff_ffffL
        val effectiveFrames = startFrame + minOf(playedFrames, writtenFrames)
        _positionMs.value = (effectiveFrames * 1_000L / SAMPLE_RATE)
            .coerceIn(0L, _durationMs.value)
    }

    private fun buildFfmpegCommand(pipe: String, startFrame: Long): String {
        val inputs = tracks.joinToString(" ") { track ->
            "-i \"${escapeFfmpegPath(track.file.absolutePath)}\""
        }
        val filters = tracks.mapIndexed { index, _ ->
            "[$index:a]" +
                "aresample=$SAMPLE_RATE," +
                "aformat=sample_fmts=flt:channel_layouts=stereo," +
                "atrim=start_sample=$startFrame," +
                "asetpts=N/SR/TB," +
                buildPanFilter(index) +
                "[stem$index]"
        }.toMutableList()
        val stemInputs = tracks.indices.joinToString("") { index -> "[stem$index]" }
        filters += "$stemInputs" +
            "amix=inputs=${tracks.size}:duration=shortest:normalize=0[out]"
        val filterGraph = filters.joinToString(";")
        return "-y -nostdin -hide_banner -loglevel error $inputs " +
            "-filter_complex \"$filterGraph\" -map \"[out]\" " +
            "-f f32le -acodec pcm_f32le -ar $SAMPLE_RATE -ac 8 \"${escapeFfmpegPath(pipe)}\""
    }

    private fun buildPanFilter(trackIndex: Int): String {
        val pair = PAN_CHANNEL_PAIRS[trackIndex]
        val assignments = PAN_CHANNEL_ORDER.joinToString("|") { channel ->
            when (channel) {
                pair.first -> "$channel=c0"
                pair.second -> "$channel=c1"
                else -> "$channel=0*c0"
            }
        }
        return "pan=7.1|$assignments"
    }

    private fun createAudioTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        require(minimum > 0) { "Thiết bị không hỗ trợ PCM float stereo" }
        val bufferBytes = max(
            minimum,
            BLOCK_FRAMES * SynchronizedStemMixerMath.OUTPUT_CHANNELS * Float.SIZE_BYTES * 4,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
            .also { track -> require(track.state == AudioTrack.STATE_INITIALIZED) }
    }

    private fun releaseAudioTrack(track: AudioTrack) {
        runCatching { if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop() }
        runCatching(track::flush)
        runCatching(track::release)
    }

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1L
        } finally {
            retriever.release()
        }
    }

    private fun isCurrent(candidate: Long): Boolean =
        !released && generation.get() == candidate

    private fun escapeFfmpegPath(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val TAG = "SynchronizedStemMixer"
        private const val SAMPLE_RATE = 44_100
        private const val BLOCK_FRAMES = 1_024
        private const val PIPE_BUFFER_BYTES = 256 * 1_024
        private const val MAX_CONSECUTIVE_ZERO_WRITES = 20
        private const val ZERO_WRITE_RETRY_MS = 10L
        private const val PAUSED_WRITE_RETRY_MS = 20L
        private val PAN_CHANNEL_ORDER = listOf("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR")
        private val PAN_CHANNEL_PAIRS = listOf(
            "FL" to "FR",
            "FC" to "LFE",
            "BL" to "BR",
            "SL" to "SR",
        )
    }
}
