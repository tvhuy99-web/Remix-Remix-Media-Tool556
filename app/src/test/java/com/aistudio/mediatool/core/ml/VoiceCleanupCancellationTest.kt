package com.aistudio.mediatool.core.ml

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCleanupCancellationTest {
    @Test
    fun terminatedRunBecomesCancellationWhenRequested() {
        val runtimeError = IllegalStateException("terminated")
        val translated = VoiceCleanupCancellation.translate(true, runtimeError)

        assertTrue(translated is CancellationException)
        assertSame(runtimeError, translated.cause)
    }

    @Test
    fun unrelatedRuntimeFailureIsPreserved() {
        val runtimeError = IllegalStateException("bad graph")
        assertSame(runtimeError, VoiceCleanupCancellation.translate(false, runtimeError))
    }

    @Test
    fun fatalRuntimeFailureIsNeverMaskedByCancellation() {
        val fatal = OutOfMemoryError("heap exhausted")
        assertSame(fatal, VoiceCleanupCancellation.translate(true, fatal))
    }
}
