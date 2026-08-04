package com.aistudio.mediatool.core.ml

/** Validates AI PCM before it is handed to the configured output limiter/encoder. */
internal object VoiceCleanupPcmOutput {
    fun validatedSample(sample: Float): Float {
        require(sample.isFinite()) { "Đầu ra MossFormer2 chứa giá trị không hữu hạn" }
        return sample
    }
}
