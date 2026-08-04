package com.aistudio.mediatool.core.ml

import java.util.Arrays

/** Large host-side buffers reused by one single-threaded MossFormer2 DSP instance. */
internal class MossFormer2Workspace(
    segmentSamples: Int,
    frames: Int,
) {
    init {
        require(segmentSamples >= MossFormer2Dsp.FFT_SIZE)
        require(frames == MossFormer2Dsp.frameCount(segmentSamples))
    }

    val featureBase = FloatArray(frames * MossFormer2Dsp.MEL_BINS)
    val featureDelta = FloatArray(featureBase.size)
    val featureDeltaDelta = FloatArray(featureBase.size)
    val features = FloatArray(frames * MossFormer2Dsp.FEATURES)
    val output = FloatArray(segmentSamples)
    val envelope = FloatArray(segmentSamples)

    fun clearSynthesis() {
        Arrays.fill(output, 0f)
        Arrays.fill(envelope, 0f)
    }
}
