package com.aistudio.mediatool.core.ml

import java.io.File

sealed class VoiceCleanupState {
    data class Progress(
        val value: Float,
        val phase: String,
    ) : VoiceCleanupState()

    data class Success(val outputFile: File) : VoiceCleanupState()
}
