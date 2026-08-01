package com.aistudio.mediatool.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun removesUrisPathsIdentityAndSecrets() {
        val input = "content://media/external/audio/42 /storage/emulated/0/Music/song.mp3 " +
            "https://example.test/file?id=42 user@example.com Authorization: Bearer very-secret"
        val sanitized = DiagnosticRedactor.sanitize(input).orEmpty()

        assertFalse(sanitized.contains("content://"))
        assertFalse(sanitized.contains("/storage/"))
        assertFalse(sanitized.contains("example.test"))
        assertFalse(sanitized.contains("user@example.com"))
        assertFalse(sanitized.contains("very-secret"))
        assertTrue(sanitized.contains("<media-uri>"))
        assertTrue(sanitized.contains("<redacted>"))
    }

    @Test
    fun ffmpegLogsOmitMetadataAndInputName() {
        val raw = """
            Input #0, mp3, from 'private-song.mp3':
              title           : My private title
              artist          : A private artist
            Error opening /storage/emulated/0/Music/private-song.mp3
        """.trimIndent()
        val sanitized = DiagnosticRedactor.sanitizeFfmpegLogs(raw).orEmpty()

        assertFalse(sanitized.contains("private-song"))
        assertFalse(sanitized.contains("My private title"))
        assertFalse(sanitized.contains("A private artist"))
        assertTrue(sanitized.contains("<media-metadata omitted>"))
        assertTrue(sanitized.contains("<media-source>"))
    }

    @Test
    fun stableIdsAreDeterministicWithoutExposingSource() {
        val first = DiagnosticRedactor.stableId("content://private/one")
        val repeated = DiagnosticRedactor.stableId("content://private/one")
        val second = DiagnosticRedactor.stableId("content://private/two")

        assertEquals(first, repeated)
        assertFalse(first == second)
        assertEquals(16, first.length)
        assertFalse(first.contains("private"))
    }
}
