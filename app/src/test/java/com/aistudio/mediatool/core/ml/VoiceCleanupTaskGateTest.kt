package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCleanupTaskGateTest {
    @Test
    fun duplicateStartIsRejectedUntilCurrentTaskFinishes() {
        val gate = VoiceCleanupTaskGate()
        val token = requireNotNull(gate.tryStart())

        assertNull(gate.tryStart())
        assertTrue(gate.beginStop(token))
        assertNull(gate.tryStart())
        assertTrue(gate.finish(token))
        assertNotNull(gate.tryStart())
    }

    @Test
    fun staleTokenCannotStopOrFinishNewTask() {
        val gate = VoiceCleanupTaskGate()
        val first = requireNotNull(gate.tryStart())
        assertTrue(gate.finish(first))
        val second = requireNotNull(gate.tryStart())

        assertFalse(gate.beginStop(first))
        assertFalse(gate.finish(first))
        assertTrue(gate.isRunning(second))
    }
}
