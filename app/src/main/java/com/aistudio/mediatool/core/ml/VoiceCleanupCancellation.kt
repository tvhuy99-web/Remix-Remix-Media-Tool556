package com.aistudio.mediatool.core.ml

import kotlinx.coroutines.CancellationException

internal object VoiceCleanupCancellation {
    fun translate(cancelRequested: Boolean, error: Throwable): Throwable {
        if (!cancelRequested || error is CancellationException || error is Error) return error
        return CancellationException("Đã hủy suy luận MossFormer2").apply {
            initCause(error)
        }
    }
}
