package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.data.StudioWavFile
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.roundToInt

object StudioPcm16Io {
    fun readMonoRange(
        file: RandomAccessFile,
        channels: Int,
        startFrame: Long,
        endFrame: Long,
    ): FloatArray {
        val frameBytes = channels * 2
        val frames = (endFrame - startFrame).coerceAtLeast(0L).toInt()
        val bytes = ByteArray(frames * frameBytes)
        file.seek(StudioWavFile.HEADER_BYTES.toLong() + startFrame * frameBytes)
        file.readFully(bytes)
        return FloatArray(frames) { frame ->
            val base = frame * frameBytes
            var sum = 0
            for (channel in 0 until channels) {
                val offset = base + channel * 2
                sum += pcm16(bytes[offset], bytes[offset + 1])
            }
            (sum.toFloat() / channels.toFloat() / 32768f).coerceIn(-1f, 1f)
        }
    }

    fun writeMono(output: OutputStream, samples: FloatArray, from: Int, until: Int) {
        val first = from.coerceIn(0, samples.size)
        val last = until.coerceIn(first, samples.size)
        val bytes = ByteArray((last - first) * 2)
        var offset = 0
        for (index in first until last) {
            val value = (samples[index].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort().toInt()
            bytes[offset++] = value.toByte()
            bytes[offset++] = (value shr 8).toByte()
        }
        output.write(bytes)
    }

    private fun pcm16(low: Byte, high: Byte): Int =
        (((high.toInt() and 0xff) shl 8) or (low.toInt() and 0xff)).toShort().toInt()
}
