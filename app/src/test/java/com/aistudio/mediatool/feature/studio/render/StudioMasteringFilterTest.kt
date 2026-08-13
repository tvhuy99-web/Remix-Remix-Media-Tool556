package com.aistudio.mediatool.feature.studio.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioMasteringFilterTest {
    @Test
    fun defaultChainAppliesTargetAndPeakProtection() {
        val chain = StudioMasteringFilter.chain(StudioMasteringOptions())

        assertEquals(2, chain.size)
        assertTrue(chain.first().contains("I=-14.0"))
        assertTrue(chain.first().contains("TP=-1.0"))
        assertEquals("aresample=48000", chain.last())
    }

    @Test
    fun disabledMasteringReturnsNoFilters() {
        assertTrue(
            StudioMasteringFilter.chain(StudioMasteringOptions(enabled = false)).isEmpty(),
        )
    }

    @Test
    fun unsafeTargetsAreClamped() {
        val safe = StudioMasteringOptions(
            integratedTarget = -40f,
            peakTarget = 2f,
        ).sanitized()

        assertEquals(-23f, safe.integratedTarget)
        assertEquals(-0.1f, safe.peakTarget)
    }
}
