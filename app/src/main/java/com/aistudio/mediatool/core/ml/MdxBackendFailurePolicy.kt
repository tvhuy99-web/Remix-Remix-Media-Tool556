package com.aistudio.mediatool.core.ml

import java.util.concurrent.CancellationException

internal class MdxModelContractException(message: String) : IllegalStateException(message)

/** Classifies failures that may be retried on another LiteRT backend. */
internal object MdxBackendFailurePolicy {
    fun isRecoverable(error: Throwable): Boolean = when (error) {
        is MdxModelContractException,
        is CancellationException,
        is InterruptedException,
        is SecurityException,
        is VirtualMachineError -> false

        is UnsatisfiedLinkError -> true
        is LinkageError -> false
        is Exception -> true
        else -> false
    }

    fun shouldFallback(error: Throwable, hasNextBackend: Boolean): Boolean =
        hasNextBackend && isRecoverable(error)
}
