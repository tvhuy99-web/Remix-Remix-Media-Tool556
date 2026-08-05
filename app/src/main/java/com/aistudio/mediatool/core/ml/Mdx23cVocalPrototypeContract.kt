package com.aistudio.mediatool.core.ml

/**
 * Verified tensor and overlap contract for the personal-use MSST MDX23C vocal artifact.
 *
 * The exported ONNX graph contains only the learned complex-spectrogram core. Android keeps the
 * periodic-Hann STFT/iSTFT, reflect padding, 75% overlap-add and residual instrumental path in host
 * code so the signal-processing contract remains inspectable and testable.
 */
internal object Mdx23cVocalPrototypeContract {
    const val INPUT_NAME = "spectrogram"
    const val OUTPUT_NAME = "vocals_spectrogram"

    val spectrogram = MdxSpectrogramContract(
        nFft = 8_192,
        hopLength = 1_024,
        frequencyBins = 4_096,
        timeFrames = 256,
        overlapRatio = 0.75f,
        compensation = 1f,
        contributionTrimFrames = 0,
        strideFramesOverride = 65_280,
        windowFadeFramesOverride = 26_112,
        reflectBoundaryFrames = 195_840,
        supportsPolarityDenoise = false,
    )

    val tensor = TensorContract(
        inputName = INPUT_NAME,
        outputName = OUTPUT_NAME,
        inputLayout = TensorAudioLayout.BATCH_CHANNEL_FRAME,
        outputLayout = TensorSourceLayout.BATCH_SOURCE_CHANNEL_FRAME,
        sourceCount = 2,
    )
}
