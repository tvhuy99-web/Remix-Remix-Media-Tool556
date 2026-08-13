package com.aistudio.mediatool.feature.studio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class StudioWavFileTest {
    @Test
    fun repairsInterruptedMonoPcm16Take() {
        val file = File.createTempFile("studio_take", ".partial.wav")
        try {
            file.outputStream().use { output ->
                output.write(ByteArray(StudioWavFile.HEADER_BYTES))
                repeat(4_800) { frame ->
                    val sample = (frame % 32_000).toShort().toInt()
                    output.write(sample and 0xff)
                    output.write((sample ushr 8) and 0xff)
                }
            }

            val repaired = StudioWavFile.repairCanonicalPcm16(file, sampleRate = 48_000, channelCount = 1)
            assertNotNull(repaired)
            assertEquals(4_800L, repaired!!.dataFrames)
            assertEquals(48_000, repaired.sampleRate)

            val inspected = StudioWavFile.inspectCanonicalPcm16(file)
            assertNotNull(inspected)
            assertEquals(repaired, inspected)
        } finally {
            file.delete()
        }
    }

    @Test
    fun dropsIncompleteTrailingSampleFrame() {
        val file = File.createTempFile("studio_take_stereo", ".partial.wav")
        try {
            file.outputStream().use { output ->
                output.write(ByteArray(StudioWavFile.HEADER_BYTES))
                output.write(ByteArray(4 * 100 + 3))
            }

            val repaired = StudioWavFile.repairCanonicalPcm16(file, sampleRate = 44_100, channelCount = 2)
            assertNotNull(repaired)
            assertEquals(100L, repaired!!.dataFrames)
            assertEquals((StudioWavFile.HEADER_BYTES + 400).toLong(), file.length())
        } finally {
            file.delete()
        }
    }
}
