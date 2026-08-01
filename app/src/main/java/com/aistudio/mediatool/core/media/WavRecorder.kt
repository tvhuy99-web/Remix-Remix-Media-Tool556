package com.aistudio.mediatool.core.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

object WavRecorder {
    private const val SAMPLE_RATE = 44_100
    private const val CHANNEL_COUNT = 2
    private const val BITS_PER_SAMPLE = 16
    private const val STOP_TIMEOUT_MS = 12_000L

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var outputFile: File? = null

    @Volatile
    var lastError: Throwable? = null
        private set

    val isRecording: Boolean get() = running.get()
    val isPaused: Boolean get() = paused.get()

    @RequiresApi(Build.VERSION_CODES.Q)
    @Synchronized
    @SuppressLint("MissingPermission")
    fun startRecording(mediaProjection: MediaProjection, target: File) {
        check(!running.get()) { "Một phiên ghi âm hệ thống đang chạy" }
        check(audioRecord == null && recordingThread?.isAlive != true) {
            "Phiên ghi âm trước chưa giải phóng xong"
        }
        target.parentFile?.mkdirs()
        target.delete()
        lastError = null
        paused.set(false)

        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, encoding)
        require(minBuffer > 0) { "Thiết bị không cung cấp bộ đệm AudioRecord hợp lệ" }
        val bufferSize = (minBuffer * 4).coerceAtLeast(SAMPLE_RATE * CHANNEL_COUNT * 2 / 2)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(encoding)
            .setChannelMask(channelMask)
            .build()

        val recorder = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Không thể khởi tạo AudioRecord" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord không chuyển sang trạng thái ghi"
            }

            outputFile = target
            audioRecord = recorder
            running.set(true)
            recordingThread = Thread({ writeAudioData(target, bufferSize) }, "MediaTool-WavRecorder").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            running.set(false)
            paused.set(false)
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            audioRecord = null
            recordingThread = null
            outputFile = null
            target.delete()
            throw error
        }
    }

    @Synchronized
    fun stopRecording(): Boolean {
        val target = outputFile
        running.set(false)
        paused.set(false)
        val recorder = audioRecord
        runCatching { recorder?.stop() }
        val thread = recordingThread
        if (thread !== Thread.currentThread()) runCatching { thread?.join(STOP_TIMEOUT_MS) }
        if (thread?.isAlive == true) {
            lastError = IllegalStateException("Không thể hoàn tất WAV trong thời gian cho phép")
            thread.interrupt()
            if (thread !== Thread.currentThread()) runCatching { thread.join(1_000) }
        }
        runCatching { recorder?.release() }
        audioRecord = null
        recordingThread = thread?.takeIf { it.isAlive }
        outputFile = null
        return lastError == null && target?.isFile == true && target.length() > WavHeader.HEADER_SIZE.toLong()
    }

    fun pauseRecording() {
        if (running.get()) paused.set(true)
    }

    fun resumeRecording() {
        if (running.get()) paused.set(false)
    }

    private fun writeAudioData(target: File, bufferSize: Int) {
        val data = ByteArray(bufferSize.coerceAtMost(256 * 1024))
        var totalAudioLength = 0L
        try {
            BufferedOutputStream(FileOutputStream(target)).use { output ->
                output.write(ByteArray(WavHeader.HEADER_SIZE))
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val read = audioRecord?.read(data, 0, data.size, AudioRecord.READ_BLOCKING) ?: break
                    when {
                        read > 0 && !paused.get() -> {
                            if (totalAudioLength + read > WavHeader.MAX_PCM_BYTES) {
                                error("Bản ghi vượt giới hạn WAV 4 GB. Hãy dừng và bắt đầu tệp mới.")
                            }
                            output.write(data, 0, read)
                            totalAudioLength += read
                        }
                        read == AudioRecord.ERROR_DEAD_OBJECT -> error("AudioRecord đã mất kết nối")
                        read < 0 && running.get() -> error("AudioRecord.read thất bại: $read")
                    }
                }
                output.flush()
            }
        } catch (error: Throwable) {
            if (lastError == null) lastError = error
        } finally {
            runCatching { finalizeWav(target, totalAudioLength) }
                .onFailure { if (lastError == null) lastError = it }
            running.set(false)
        }
    }

    private fun finalizeWav(target: File, audioLength: Long) {
        require(audioLength > 0L) { "Bản ghi không chứa dữ liệu âm thanh" }
        val header = WavHeader.create(audioLength, SAMPLE_RATE, CHANNEL_COUNT, BITS_PER_SAMPLE)
        RandomAccessFile(target, "rw").use { file ->
            file.seek(0)
            file.write(header)
            file.fd.sync()
        }
    }
}
