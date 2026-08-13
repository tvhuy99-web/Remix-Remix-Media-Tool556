package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.data.StudioWavFile
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

object StudioPitchPcmProcessor {
    fun process(
        source: File,
        target: File,
        plan: StudioPitchPlan,
        onProgress: (Float) -> Unit = {},
    ) {
        val info = requireNotNull(StudioWavFile.inspectCanonicalPcm16(source)) {
            "Nguồn xử lý cao độ không phải WAV PCM16 Studio"
        }
        val raw = File(target.parentFile ?: source.parentFile, "${target.nameWithoutExtension}.pitch.s16")
        raw.delete()
        val chunkFrames = info.sampleRate.toLong() * 8L
        val contextFrames = info.sampleRate.toLong() / 12L
        try {
            RandomAccessFile(source, "r").use { input ->
                FileOutputStream(raw).buffered(256 * 1024).use { output ->
                    var coreStart = 0L
                    while (coreStart < info.dataFrames) {
                        val coreEnd = minOf(info.dataFrames, coreStart + chunkFrames)
                        val readStart = (coreStart - contextFrames).coerceAtLeast(0L)
                        val readEnd = (coreEnd + contextFrames).coerceAtMost(info.dataFrames)
                        val mono = StudioPcm16Io.readMonoRange(input, info.channelCount, readStart, readEnd)
                        val shifted = StudioPsola.process(mono, info.sampleRate, readStart, plan)
                        val first = (coreStart - readStart).toInt()
                        val last = first + (coreEnd - coreStart).toInt()
                        StudioPcm16Io.writeMono(output, shifted, first, last)
                        coreStart = coreEnd
                        onProgress((coreStart.toDouble() / info.dataFrames.toDouble()).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
            requireNotNull(StudioWavFile.writeFromRawPcm16(raw, target, info.sampleRate, 1)) {
                "Không thể đóng gói bản xử lý cao độ"
            }
        } finally {
            raw.delete()
        }
    }
}
