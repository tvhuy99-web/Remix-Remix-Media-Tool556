package com.example.core.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

object WavRecorder {
    private var audioRecord: AudioRecord? = null
    var isRecording = false
        private set
    private var recordingThread: Thread? = null
    var isPaused = false
        private set

    @RequiresApi(Build.VERSION_CODES.Q)
    fun startRecording(mediaProjection: MediaProjection, outputFile: File) {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        isPaused = false
        
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
            
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(audioFormat)
            .setChannelMask(channelConfig)
            .build()
            
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = if (minBufferSize != AudioRecord.ERROR_BAD_VALUE) minBufferSize * 4 else 44100 * 2
        
        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .build()
            
        isRecording = true
        audioRecord?.startRecording()
        
        recordingThread = Thread {
            writeAudioDataToFile(outputFile.absolutePath, sampleRate)
        }
        recordingThread?.start()
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun pauseRecording() {
        isPaused = true
    }

    fun resumeRecording() {
        isPaused = false
    }

    private fun writeAudioDataToFile(path: String, sampleRate: Int) {
        val data = ByteArray(4096)
        var os: FileOutputStream? = null
        var totalAudioLen: Long = 0
        try {
            os = FileOutputStream(path)
            os.write(ByteArray(44)) // Dummy header
            
            while (isRecording) {
                if (isPaused) {
                    Thread.sleep(100)
                    continue
                }
                val read = audioRecord?.read(data, 0, data.size) ?: 0
                if (read > 0) {
                    os.write(data, 0, read)
                    totalAudioLen += read
                }
            }
            os.close()
            
            writeWavHeader(path, totalAudioLen, totalAudioLen + 36, sampleRate.toLong(), 1)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeWavHeader(path: String, totalAudioLen: Long, totalDataLen: Long, longSampleRate: Long, channels: Int) {
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte()
        header[5] = ((totalDataLen shr 8) and 0xffL).toByte()
        header[6] = ((totalDataLen shr 16) and 0xffL).toByte()
        header[7] = ((totalDataLen shr 24) and 0xffL).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xffL).toByte()
        header[25] = ((longSampleRate shr 8) and 0xffL).toByte()
        header[26] = ((longSampleRate shr 16) and 0xffL).toByte()
        header[27] = ((longSampleRate shr 24) and 0xffL).toByte()
        
        val byteR = longSampleRate * channels * 2
        header[28] = (byteR and 0xffL).toByte()
        header[29] = ((byteR shr 8) and 0xffL).toByte()
        header[30] = ((byteR shr 16) and 0xffL).toByte()
        header[31] = ((byteR shr 24) and 0xffL).toByte()
        
        header[32] = (channels * 2).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xffL).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xffL).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xffL).toByte()

        try {
            val randomAccessFile = RandomAccessFile(path, "rw")
            randomAccessFile.seek(0)
            randomAccessFile.write(header)
            randomAccessFile.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
