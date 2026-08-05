package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialPrefilterPolicyTest {
    @Test
    fun suppressesPositionRoomMonoAndLimiterFilters() {
        val value = SpatialPrefilterPolicy.apply(
            listOf(
                "pan=stereo|c0=0.2*c0|c1=0.8*c1",
                "apulsator=mode=sine:hz=0.25:width=1",
                "aecho=0.8:0.9:300:0.5",
                "pan=mono|c0=0.5*c0+0.5*c1",
                "alimiter=limit=0.9886:level=0:latency=1",
            ),
        )
        assertTrue(value.allowed.isEmpty())
        assertEquals(5, value.suppressed.size)
        assertEquals(
            "auto_pan,echo_or_legacy_reverb,limiter,manual_pan,mono_downmix",
            value.diagnosticFields()["suppressed_pre_filter_types"],
        )
    }

    @Test
    fun preservesCleanupDynamicsEqAndTimingFilters() {
        val filters = listOf(
            "loudnorm=I=-16:LRA=11:TP=-1",
            "aresample=48000",
            "afftdn=nf=-25",
            "agate=threshold=0.03:ratio=4",
            "acompressor=threshold=0.3:ratio=4",
            "equalizer=f=910:width_type=q:width=1:g=3",
            "asetrate=48000",
            "atempo=1.1",
            "aformat=sample_fmts=fltp:channel_layouts=stereo",
        )
        val value = SpatialPrefilterPolicy.apply(filters)
        assertEquals(filters, value.allowed)
        assertTrue(value.suppressed.isEmpty())
    }

    @Test
    fun ignoresBlankEntriesWithoutReportingConflicts() {
        val value = SpatialPrefilterPolicy.apply(listOf("", "   ", "afftdn=nf=-30"))
        assertEquals(listOf("afftdn=nf=-30"), value.allowed)
        assertTrue(value.suppressed.isEmpty())
    }
}
