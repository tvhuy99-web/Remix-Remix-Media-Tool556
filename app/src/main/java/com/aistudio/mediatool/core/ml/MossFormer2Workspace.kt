package com.aistudio.mediatool.core.ml

import java.util.Arrays

/** Large host-side buffers reused by one single-threaded MossFormer2 DSP instance. */
internal class MossFormer2Workspace {
    val featureBase = FloatArray(MossFormer2Dsp.FRAMES * MossFormer2Dsp.MEL_BINS)
    val featureDelta = FloatArray(featureBase.size)
    val featureDeltaDelta = FloatArray(featureBase.size)
    val features = FloatArray(MossFormer2Dsp.FRAMES * MossFormer2Dsp.FEATURES)
    val output = FloatArray(MossFormer2Dsp.SEGMENT_SAMPLES)
    val envelope = FloatArray(MossFormer2Dsp.SEGMENT_SAMPLES)

    fun clearSynthesis() {
        Arrays.fill(output, 0f)
        Arrays.fill(envelope, 0f)
    }
}
