package com.aistudio.mediatool.core.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavHeaderTest {
    @Test
    fun createsValidStereoPcmHeader() {
        val header = WavHeader.create(176_400, 44_100, 2, 16)
        assertEquals(44, header.size)
        assertArrayEquals("RIFF".toByteArray(), header.copyOfRange(0, 4))
        assertArrayEquals("WAVE".toByteArray(), header.copyOfRange(8, 12))
        assertArrayEquals("data".toByteArray(), header.copyOfRange(36, 40))
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(176_436, buffer.getInt(4))
        assertEquals(44_100, buffer.getInt(24))
        assertEquals(176_400, buffer.getInt(28))
        assertEquals(176_400, buffer.getInt(40))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsClassicWavOverflow() {
        WavHeader.create(WavHeader.MAX_PCM_BYTES + 1, 44_100, 2, 16)
    }
}
