#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/aistudio/mediatool/core/audio/SynchronizedStemMixer.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match, found {count}: {old[:160]!r}")
    text = text.replace(old, new, 1)


replace_once(
'''            val session = FFmpegKit.executeAsync(command) { completed ->
                activeSessionId.compareAndSet(completed.sessionId, -1L)
                if (isCurrent(currentGeneration) && !ReturnCode.isSuccess(completed.returnCode)) {
''',
'''            val session = FFmpegKit.executeAsync(command) { completed ->
                val wasActive = activeSessionId.compareAndSet(completed.sessionId, -1L)
                if (wasActive && isCurrent(currentGeneration) && !ReturnCode.isSuccess(completed.returnCode)) {
''',
)

replace_once(
'''            audioTrack.play()
            _isPlaying.value = true
            readAndMixPipe(
''',
'''            readAndMixPipe(
''',
)

replace_once(
'''        val mixedFloats = FloatArray(BLOCK_FRAMES * SynchronizedStemMixerMath.OUTPUT_CHANNELS)
        var bufferedBytes = 0
        var writtenFrames = 0L
''',
'''        val mixedFloats = FloatArray(BLOCK_FRAMES * SynchronizedStemMixerMath.OUTPUT_CHANNELS)
        val outputBytes = ByteBuffer.allocateDirect(
            BLOCK_FRAMES * SynchronizedStemMixerMath.OUTPUT_CHANNELS * Float.SIZE_BYTES,
        ).order(ByteOrder.nativeOrder())
        var bufferedBytes = 0
        var writtenFrames = 0L
        var playbackStarted = false
''',
)

replace_once(
'''                writeAll(audioTrack, mixedFloats, sampleCount)
                writtenFrames += frames
''',
'''                writeAll(
                    audioTrack = audioTrack,
                    samples = mixedFloats,
                    sampleCount = sampleCount,
                    buffer = outputBytes,
                    currentGeneration = currentGeneration,
                )
                if (!playbackStarted) {
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
''',
)

replace_once(
'''    private fun writeAll(audioTrack: AudioTrack, samples: FloatArray, sampleCount: Int) {
        var offset = 0
        while (offset < sampleCount) {
            val written = audioTrack.write(
                samples,
                offset,
                sampleCount - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            require(written > 0) { "AudioTrack không nhận dữ liệu mixer: $written" }
            offset += written
        }
    }
''',
'''    private suspend fun writeAll(
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
''',
)

replace_once(
'''        private const val PIPE_BUFFER_BYTES = 256 * 1_024
        private val PAN_CHANNEL_ORDER = listOf("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR")
''',
'''        private const val PIPE_BUFFER_BYTES = 256 * 1_024
        private const val MAX_CONSECUTIVE_ZERO_WRITES = 20
        private const val ZERO_WRITE_RETRY_MS = 10L
        private val PAN_CHANNEL_ORDER = listOf("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR")
''',
)

path.write_text(text, encoding="utf-8")
print("Applied device-tested AudioTrack write hardening")
