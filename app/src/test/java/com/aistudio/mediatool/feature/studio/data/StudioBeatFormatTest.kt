package com.aistudio.mediatool.feature.studio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StudioBeatFormatTest {
    @Test
    fun supportedExtensionsAreAcceptedCaseInsensitively() {
        assertEquals(StudioBeatFormat.MP3, StudioBeatFormatDetector.detect("beat.MP3", null))
        assertEquals(StudioBeatFormat.WAV, StudioBeatFormatDetector.detect("beat.wav", "application/octet-stream"))
        assertEquals(StudioBeatFormat.M4A, StudioBeatFormatDetector.detect("beat.m4a", null))
        assertEquals(StudioBeatFormat.FLAC, StudioBeatFormatDetector.detect("beat.FLAC", null))
    }

    @Test
    fun knownMimeCanRecoverWhenProviderOmitsExtension() {
        assertEquals(StudioBeatFormat.MP3, StudioBeatFormatDetector.detect("beat", "audio/mpeg"))
        assertEquals(StudioBeatFormat.WAV, StudioBeatFormatDetector.detect("beat", "audio/x-wav"))
        assertEquals(StudioBeatFormat.M4A, StudioBeatFormatDetector.detect("beat", "audio/mp4"))
        assertEquals(StudioBeatFormat.FLAC, StudioBeatFormatDetector.detect("beat", "audio/flac"))
    }

    @Test
    fun explicitUnsupportedExtensionIsRejectedEvenWithGenericAudioMime() {
        assertNull(StudioBeatFormatDetector.detect("beat.ogg", "audio/ogg"))
        assertNull(StudioBeatFormatDetector.detect("beat.opus", "audio/opus"))
        assertNull(StudioBeatFormatDetector.detect("beat.aac", "audio/aac"))
    }

    @Test
    fun unsupportedFormatHasFriendlyActionableMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            StudioBeatFormatDetector.requireSupported("beat.ogg", "audio/ogg")
        }
        assertEquals(
            "Định dạng nhạc nền chưa được hỗ trợ. Hãy chọn MP3, WAV, M4A hoặc FLAC.",
            error.message,
        )
    }
}
