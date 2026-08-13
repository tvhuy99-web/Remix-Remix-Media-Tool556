package com.aistudio.mediatool.feature.studio.data

import android.content.Context
import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.min

data class StudioWaveform(
    val sampleRate: Int,
    val channelCount: Int,
    val framesPerPoint: Int,
    val totalFrames: Long,
    val minima: ShortArray,
    val maxima: ShortArray,
) {
    val pointCount: Int get() = min(minima.size, maxima.size)
}

class StudioWaveformStore(context: Context) {
    private val projectStore = StudioProjectStore(context.applicationContext)

    fun load(projectId: String, assetId: String): StudioWaveform? {
        val file = cacheFile(projectId, assetId)
        if (!file.isFile || file.length() <= HEADER_BYTES) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                require(input.readInt() == MAGIC) { "Waveform magic không hợp lệ" }
                require(input.readInt() == VERSION) { "Waveform version chưa được hỗ trợ" }
                val sampleRate = input.readInt()
                val channels = input.readInt()
                val framesPerPoint = input.readInt()
                val totalFrames = input.readLong()
                val pointCount = input.readInt()
                require(sampleRate > 0 && channels > 0 && framesPerPoint > 0)
                require(totalFrames >= 0L && pointCount in 0..MAX_POINTS)
                val minima = ShortArray(pointCount)
                val maxima = ShortArray(pointCount)
                repeat(pointCount) { index ->
                    minima[index] = input.readShort()
                    maxima[index] = input.readShort()
                }
                StudioWaveform(sampleRate, channels, framesPerPoint, totalFrames, minima, maxima)
            }
        }.getOrNull()
    }

    fun generateFromRawPcm16(
        projectId: String,
        assetId: String,
        pcmFile: File,
        sampleRate: Int,
        channelCount: Int,
        framesPerPoint: Int = DEFAULT_FRAMES_PER_POINT,
    ): StudioWaveform {
        require(pcmFile.isFile && pcmFile.length() > 0L) { "PCM beat cache không hợp lệ" }
        val frameBytes = channelCount * 2L
        require(sampleRate > 0 && channelCount in 1..8 && framesPerPoint > 0)
        val totalFrames = pcmFile.length() / frameBytes
        return analyzePcm16(
            projectId = projectId,
            assetId = assetId,
            source = pcmFile,
            dataOffset = 0L,
            totalFrames = totalFrames,
            sampleRate = sampleRate,
            channelCount = channelCount,
            framesPerPoint = framesPerPoint,
        )
    }

    fun ensureForCanonicalWav(
        projectId: String,
        asset: StudioAsset,
        wavFile: File,
        framesPerPoint: Int = DEFAULT_FRAMES_PER_POINT,
    ): StudioWaveform? {
        load(projectId, asset.id)?.let { return it }
        val info = StudioWavFile.inspectCanonicalPcm16(wavFile) ?: return null
        return analyzePcm16(
            projectId = projectId,
            assetId = asset.id,
            source = wavFile,
            dataOffset = StudioWavFile.HEADER_BYTES.toLong(),
            totalFrames = info.dataFrames,
            sampleRate = info.sampleRate,
            channelCount = info.channelCount,
            framesPerPoint = framesPerPoint,
        )
    }

    private fun analyzePcm16(
        projectId: String,
        assetId: String,
        source: File,
        dataOffset: Long,
        totalFrames: Long,
        sampleRate: Int,
        channelCount: Int,
        framesPerPoint: Int,
    ): StudioWaveform {
        val pointCountLong = if (totalFrames == 0L) 0L else (totalFrames + framesPerPoint - 1L) / framesPerPoint
        require(pointCountLong <= MAX_POINTS) { "Audio quá dài để tạo waveform cache an toàn" }
        val pointCount = pointCountLong.toInt()
        val minima = ShortArray(pointCount)
        val maxima = ShortArray(pointCount)
        if (pointCount > 0) {
            RandomAccessFile(source, "r").use { input ->
                input.seek(dataOffset)
                val frameBytes = channelCount * 2
                val chunkFrames = 8_192
                val buffer = ByteArray(chunkFrames * frameBytes)
                var remaining = totalFrames
                var pointIndex = 0
                var framesInPoint = 0
                var currentMin = Short.MAX_VALUE.toInt()
                var currentMax = Short.MIN_VALUE.toInt()

                while (remaining > 0L) {
                    val framesToRead = min(chunkFrames.toLong(), remaining).toInt()
                    val bytesToRead = framesToRead * frameBytes
                    input.readFully(buffer, 0, bytesToRead)
                    var cursor = 0
                    repeat(framesToRead) {
                        repeat(channelCount) {
                            val low = buffer[cursor].toInt() and 0xff
                            val high = buffer[cursor + 1].toInt()
                            val sample = ((high shl 8) or low).toShort().toInt()
                            if (sample < currentMin) currentMin = sample
                            if (sample > currentMax) currentMax = sample
                            cursor += 2
                        }
                        framesInPoint++
                        if (framesInPoint == framesPerPoint && pointIndex < pointCount) {
                            minima[pointIndex] = currentMin.toShort()
                            maxima[pointIndex] = currentMax.toShort()
                            pointIndex++
                            framesInPoint = 0
                            currentMin = Short.MAX_VALUE.toInt()
                            currentMax = Short.MIN_VALUE.toInt()
                        }
                    }
                    remaining -= framesToRead
                }
                if (framesInPoint > 0 && pointIndex < pointCount) {
                    minima[pointIndex] = currentMin.toShort()
                    maxima[pointIndex] = currentMax.toShort()
                }
            }
        }

        val waveform = StudioWaveform(
            sampleRate = sampleRate,
            channelCount = channelCount,
            framesPerPoint = framesPerPoint,
            totalFrames = totalFrames,
            minima = minima,
            maxima = maxima,
        )
        writeCache(projectId, assetId, waveform)
        return waveform
    }

    private fun writeCache(projectId: String, assetId: String, waveform: StudioWaveform) {
        val target = cacheFile(projectId, assetId)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.delete()
        FileOutputStream(temporary).use { raw ->
            val output = DataOutputStream(BufferedOutputStream(raw))
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(waveform.sampleRate)
            output.writeInt(waveform.channelCount)
            output.writeInt(waveform.framesPerPoint)
            output.writeLong(waveform.totalFrames)
            output.writeInt(waveform.pointCount)
            repeat(waveform.pointCount) { index ->
                output.writeShort(waveform.minima[index].toInt())
                output.writeShort(waveform.maxima[index].toInt())
            }
            output.flush()
            raw.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private fun cacheFile(projectId: String, assetId: String): File =
        projectStore.resolveAssetFile(projectId, "waveform/$assetId.stwf")

    companion object {
        private const val MAGIC = 0x53545746 // STWF
        private const val VERSION = 1
        private const val HEADER_BYTES = 28L
        private const val DEFAULT_FRAMES_PER_POINT = 256
        private const val MAX_POINTS = 4_000_000
    }
}
