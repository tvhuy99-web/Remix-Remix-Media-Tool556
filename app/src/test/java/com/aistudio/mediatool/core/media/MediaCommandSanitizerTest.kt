package com.aistudio.mediatool.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCommandSanitizerTest {
    @Test
    fun normalizesSourceBeforeSpeedPitchChain() {
        val result = MediaCommandSanitizer.sanitize(
            "-y -i input.wav -af \"asetrate=44100,aresample=44100,atempo=2.0\" -c:a aac out.m4a",
        )

        assertTrue(result.command.contains("-af \"aresample=44100,asetrate=44100,aresample=44100,atempo=2.0\""))
        assertTrue("source_sample_rate_normalized" in result.adjustments)
    }

    @Test
    fun neutralizesHiddenVideoSpeedWhileKeepingPitchCompensation() {
        val result = MediaCommandSanitizer.sanitize(
            "-y -i input.mp4 -af \"asetrate=88200,aresample=44100\" -c:v copy -c:a aac out.mp4",
        )

        assertTrue(result.command.contains("aresample=44100,asetrate=88200,aresample=44100,atempo=0.50000"))
        assertTrue("hidden_video_speed_neutralized" in result.adjustments)
    }

    @Test
    fun removesTimelineOnlyFromUnsupportedFilters() {
        val result = MediaCommandSanitizer.sanitize(
            "-af \"afftdn=nf=-25:enable='between(t,0,1)',pan=stereo|c0=c0|c1=c1:enable='between(t,0,1)',apulsator=hz=0.2:enable='between(t,0,1)',acompressor=ratio=4:enable='between(t,0,1)',equalizer=f=910:g=4:enable='between(t,0,1)'\" out.wav",
        )

        assertTrue(result.command.contains("afftdn=nr=25.00:enable='between(t,0,1)'"))
        assertTrue(result.command.contains("equalizer=f=910:g=4:enable='between(t,0,1)'"))
        assertFalse(result.command.contains("pan=stereo|c0=c0|c1=c1:enable="))
        assertFalse(result.command.contains("apulsator=hz=0.2:enable="))
        assertFalse(result.command.contains("acompressor=ratio=4:enable="))
    }

    @Test
    fun preservesNoiseGateFloorWhileCorrectingStandaloneDenoise() {
        val result = MediaCommandSanitizer.sanitize(
            "-af \"afftdn=nf=-25,afftdn=nf=-40,agate=threshold=0.03\" out.wav",
        )

        assertTrue(result.command.contains("afftdn=nr=25.00,afftdn=nf=-40,agate="))
    }

    @Test
    fun bypassesZeroWetReverb() {
        val result = MediaCommandSanitizer.sanitize(
            "-af \"equalizer=f=910:g=4,aecho=0.8:0.8:50|75:0.0|0.0,alimiter=limit=0.98\" out.wav",
        )

        assertFalse(result.command.contains("aecho="))
        assertTrue(result.command.contains("equalizer=f=910:g=4,alimiter="))
    }

    @Test
    fun disablesSilenceRemovalWhenVideoIsStreamCopied() {
        val result = MediaCommandSanitizer.sanitize(
            "-af \"silenceremove=start_periods=1,equalizer=f=910:g=4\" -c:v copy -c:a aac out.mp4",
        )

        assertFalse(result.command.contains("silenceremove="))
        assertTrue("video_silence_removal_disabled" in result.adjustments)
    }

    @Test
    fun movesLoudnessAfterEffectsAndBeforeLimiter() {
        val result = MediaCommandSanitizer.sanitize(
            "-af \"loudnorm=I=-16:LRA=11:TP=-1,aresample=48000,equalizer=f=910:g=4,acompressor=ratio=4,alimiter=limit=0.98\" out.wav",
        )

        assertEquals(
            "-af \"equalizer=f=910:g=4,acompressor=ratio=4,loudnorm=I=-16:LRA=11:TP=-1,aresample=48000,alimiter=limit=0.98\" out.wav",
            result.command,
        )
    }

    @Test
    fun keepsCommasInsideTimelineExpressionTogether() {
        val filters = MediaCommandSanitizer.splitFilterChain(
            "afftdn=nf=-25:enable='between(t,0,1)',equalizer=f=910:g=4",
        )

        assertEquals(2, filters.size)
    }
}
