package com.aistudio.mediatool.feature.studio.data

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.min

/** Canonical PCM16 WAV helpers used by Studio take recovery, derived processors and waveform analysis. */
object StudioWavFile {
    const val HEADER_BYTES = 44

    data class Info(
        val sampleRate: Int,
        val channelCount: Int,
        val dataFrames: Long,
        val dataBytes: Long,
    )

    fun writeFromRawPcm16(raw: File, target: File, sampleRate: Int, channelCount: Int): Info? {
        if (!raw.isFile || raw.length() <= 0L || sampleRate <= 0 || channelCount !in 1..8) return null
        val frameBytes = channelCount * 2L
        val alignedBytes = raw.length() - (raw.length() % frameBytes)
        if (alignedBytes <= 0L || alignedBytes > UInt.MAX_VALUE.toLong()) return null
        target.parentFile?.mkdirs()
        target.delete()
        FileOutputStream(target).use { output ->
            output.write(canonicalHeader(sampleRate, channelCount, alignedBytes.toInt()))
            raw.inputStream().buffered().use { input ->
                val buffer = ByteArray(256 * 1024)
                var remaining = alignedBytes
                while (remaining > 0L) {
                    val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                require(remaining == 0L) { "PCM source kết thúc sớm" }
            }
            output.flush()
            output.fd.sync()
        }
        return inspectCanonicalPcm16(target)
    }

    fun repairCanonicalPcm16(file: File, sampleRate: Int, channelCount: Int): Info? {
        if (!file.isFile || sampleRate <= 0 || channelCount !in 1..8 || file.length() <= HEADER_BYTES) return null
        val frameBytes = channelCount * 2L
        val available = (file.length() - HEADER_BYTES).coerceAtLeast(0L)
        val alignedBytes = available - (available % frameBytes)
        if (alignedBytes <= 0L || alignedBytes > UInt.MAX_VALUE.toLong()) return null
        val finalLength = HEADER_BYTES + alignedBytes

        RandomAccessFile(file, "rw").use { wav ->
            if (wav.length() != finalLength) wav.setLength(finalLength)
            wav.seek(0L)
            wav.write(canonicalHeader(sampleRate, channelCount, alignedBytes.toInt()))
            wav.fd.sync()
        }
        return Info(
            sampleRate = sampleRate,
            channelCount = channelCount,
            dataFrames = alignedBytes / frameBytes,
            dataBytes = alignedBytes,
        )
    }

    fun inspectCanonicalPcm16(file: File): Info? {
        if (!file.isFile || file.length() < HEADER_BYTES) return null
        val header = ByteArray(HEADER_BYTES)
        RandomAccessFile(file, "r").use { wav ->
            wav.readFully(header)
        }
        if (ascii(header, 0, 4) != "RIFF" || ascii(header, 8, 4) != "WAVE") return null
        if (ascii(header, 12, 4) != "fmt " || ascii(header, 36, 4) != "data") return null
        val format = leShort(header, 20)
        val channels = leShort(header, 22)
        val sampleRate = leInt(header, 24)
        val bitsPerSample = leShort(header, 34)
        if (format != 1 || channels !in 1..8 || sampleRate <= 0 || bitsPerSample != 16) return null
        val frameBytes = channels * 2L
        val declaredDataBytes = leUInt(header, 40)
        val physicallyAvailable = (file.length() - HEADER_BYTES).coerceAtLeast(0L)
        val usable = min(declaredDataBytes, physicallyAvailable) -
            (min(declaredDataBytes, physicallyAvailable) % frameBytes)
        if (usable <= 0L) return null
        return Info(
            sampleRate = sampleRate,
            channelCount = channels,
            dataFrames = usable / frameBytes,
            dataBytes = usable,
        )
    }

    private fun canonicalHeader(sampleRate: Int, channels: Int, dataBytes: Int): ByteArray =
        ByteArray(HEADER_BYTES).also { header ->
            putAscii(header, 0, "RIFF")
            putLeInt(header, 4, 36 + dataBytes)
            putAscii(header, 8, "WAVE")
            putAscii(header, 12, "fmt ")
            putLeInt(header, 16, 16)
            putLeShort(header, 20, 1)
            putLeShort(header, 22, channels)
            putLeInt(header, 24, sampleRate)
            val byteRate = sampleRate * channels * 2
            putLeInt(header, 28, byteRate)
            putLeShort(header, 32, channels * 2)
            putLeShort(header, 34, 16)
            putAscii(header, 36, "data")
            putLeInt(header, 40, dataBytes)
        }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun putAscii(target: ByteArray, offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(target, offset)
    }

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun leUInt(bytes: ByteArray, offset: Int): Long = leInt(bytes, offset).toLong() and 0xffffffffL

    private fun putLeShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
