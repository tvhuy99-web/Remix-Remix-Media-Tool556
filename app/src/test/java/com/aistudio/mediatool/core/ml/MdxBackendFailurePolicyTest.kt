package com.aistudio.mediatool.core.ml

import java.util.concurrent.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdxBackendFailurePolicyTest {
    @Test
    fun outOfMemoryNeverFallsBack() {
        val error = OutOfMemoryError("test")

        assertFalse(MdxBackendFailurePolicy.isRecoverable(error))
        assertFalse(MdxBackendFailurePolicy.shouldFallback(error, hasNextBackend = true))
    }

    @Test
    fun ordinaryBackendFailureMayFallBackWhenAnotherBackendExists() {
        val error = RuntimeException("delegate compilation failed")

        assertTrue(MdxBackendFailurePolicy.isRecoverable(error))
        assertTrue(MdxBackendFailurePolicy.shouldFallback(error, hasNextBackend = true))
        assertFalse(MdxBackendFailurePolicy.shouldFallback(error, hasNextBackend = false))
    }

    @Test
    fun missingOptionalNativeGpuImplementationMayFallBack() {
        val error = UnsatisfiedLinkError("gpu delegate unavailable")

        assertTrue(MdxBackendFailurePolicy.isRecoverable(error))
        assertTrue(MdxBackendFailurePolicy.shouldFallback(error, hasNextBackend = true))
    }

    @Test
    fun modelContractFailureNeverFallsBack() {
        val error = MdxModelContractException("unexpected tensor count")

        assertFalse(MdxBackendFailurePolicy.isRecoverable(error))
        assertFalse(MdxBackendFailurePolicy.shouldFallback(error, hasNextBackend = true))
    }

    @Test
    fun cancellationAndInterruptionNeverFallBack() {
        val cancellation = CancellationException("cancelled")
        val interruption = InterruptedException("interrupted")

        assertFalse(MdxBackendFailurePolicy.isRecoverable(cancellation))
        assertFalse(MdxBackendFailurePolicy.shouldFallback(cancellation, hasNextBackend = true))
        assertFalse(MdxBackendFailurePolicy.isRecoverable(interruption))
        assertFalse(MdxBackendFailurePolicy.shouldFallback(interruption, hasNextBackend = true))
    }

    @Test
    fun packageLinkageFailureNeverFallsBack() {
        val error = object : LinkageError("incompatible package") {}

        assertFalse(MdxBackendFailurePolicy.isRecoverable(error))
        assertFalse(MdxBackendFailurePolicy.shouldFallback(error, hasNextBackend = true))
    }
}
