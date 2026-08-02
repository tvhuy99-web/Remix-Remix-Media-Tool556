package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnnxThreadingPolicyTest {
    @Test
    fun xnnpackOwnsRequestedThreadsAndOrtStaysSingleThreaded() {
        assertEquals(OnnxThreadingConfig(1, 4), OnnxThreadingPolicy.resolve(2, 4))
    }

    @Test
    fun qnnGpuKeepsOrtSingleThreaded() {
        assertEquals(OnnxThreadingConfig(1, null), OnnxThreadingPolicy.resolve(3, 8))
    }

    @Test
    fun cpuAndNnapiKeepOrtThreadSetting() {
        val cpu = OnnxThreadingPolicy.resolve(0, 4)
        assertEquals(4, cpu.ortIntraOpThreads)
        assertNull(cpu.xnnpackThreads)

        val nnapi = OnnxThreadingPolicy.resolve(1, 2)
        assertEquals(2, nnapi.ortIntraOpThreads)
        assertNull(nnapi.xnnpackThreads)
    }
}
