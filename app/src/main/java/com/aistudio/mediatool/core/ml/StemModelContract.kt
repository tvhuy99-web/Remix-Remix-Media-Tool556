package com.aistudio.mediatool.core.ml

import kotlin.math.roundToInt

data class ModelSpec(
    val url: String,
    val fileName: String,
    val familyPrefix: String,
    val expectedBytes: Long,
    val sha256: String,
)

enum class StemMode(val settingsIndex: Int, val stemCount: Int) {
    TWO_STEM(settingsIndex = 0, stemCount = 2),
    FOUR_STEM(settingsIndex = 1, stemCount = 4),
    ;

    companion object {
        fun fromSettingsIndex(index: Int): StemMode =
            entries.firstOrNull { it.settingsIndex == index } ?: TWO_STEM
    }
}

enum class StemInferenceBackend {
    /** Existing waveform models executed by ONNX Runtime. */
    WAVEFORM_ONNX,

    /** MDX-Net learned spectrogram core executed by LiteRT; STFT/iSTFT stay in host code. */
    MDX_LITERT,
}

enum class TensorAudioLayout {
    BATCH_CHANNEL_FRAME,
    BATCH_FRAME_CHANNEL,
}

enum class TensorSourceLayout {
    BATCH_SOURCE_CHANNEL_FRAME,
    BATCH_SOURCE_FRAME_CHANNEL,
}

enum class AudioNormalization {
    NONE,
    GLOBAL_MONO_MEAN_STD,
}

enum class OnnxAcceleration(val settingsIndex: Int) {
    CPU(0),
    NNAPI(1),
    XNNPACK(2),
    ;

    companion object {
        fun fromSettingsIndex(index: Int): OnnxAcceleration =
            entries.firstOrNull { it.settingsIndex == index } ?: CPU
    }
}

enum class OverlapProfile {
    /** Equal-power complementary crossfade used by the legacy Demucs export. */
    COMPLEMENTARY_SINE,

    /** Window/counter normalization used by the reference Mel-Band RoFormer inference. */
    REFERENCE_LINEAR_WINDOW,
}

data class ChunkingSpec(
    val frames: Int,
    val overlapFrames: Int,
    val edgeFadeFrames: Int,
    val overlapProfile: OverlapProfile,
    val reflectBoundaryFrames: Int = 0,
) {
    init {
        require(frames > 0)
        require(overlapFrames in 1 until frames)
        require(edgeFadeFrames in 1..frames / 2)
        require(reflectBoundaryFrames in 0 until frames)
    }

    val stepFrames: Int = frames - overlapFrames
}

data class TensorContract(
    val inputName: String,
    val outputName: String,
    val inputLayout: TensorAudioLayout,
    val outputLayout: TensorSourceLayout,
    val sourceCount: Int,
)

/**
 * Static host-DSP contract for an MDX-Net LiteRT graph.
 *
 * The graph sees only the learned complex-as-channels spectrogram core. Audio framing, periodic Hann
 * STFT, inverse STFT and overlap-add are implemented by [MdxAudioSeparator].
 */
data class MdxSpectrogramContract(
    val nFft: Int,
    val hopLength: Int,
    val frequencyBins: Int,
    val timeFrames: Int,
    val overlapRatio: Float,
    val compensation: Float = 1f,
) {
    /** Native waveform samples represented by one static MDX tensor. */
    val chunkFrames: Int = Math.multiplyExact(hopLength, timeFrames - 1)

    /** center=True STFT trim on each side. */
    val trimFrames: Int = nFft / 2

    /** Central samples contributed by one model invocation after dropping both center trims. */
    val generatedFrames: Int = chunkFrames - 2 * trimFrames

    /** Fixed overlap stride used by the reference pipeline. */
    val strideFrames: Int = (generatedFrames * (1f - overlapRatio))
        .roundToInt()
        .coerceIn(1, generatedFrames)

    val overlapFrames: Int = generatedFrames - strideFrames

    /** [1, 4, frequencyBins, timeFrames], float32 NCHW. */
    val tensorElements: Int = Math.multiplyExact(4, Math.multiplyExact(frequencyBins, timeFrames))

    init {
        require(nFft >= 4 && nFft % 2 == 0)
        require(hopLength in 1 until nFft)
        require(frequencyBins == nFft / 2) {
            "MDX contract drops exactly the Nyquist bin: frequencyBins must equal nFft/2"
        }
        require(timeFrames >= 2)
        require(overlapRatio >= 0f && overlapRatio < 0.5f)
        require(compensation.isFinite() && compensation > 0f)
        require(generatedFrames > 0)
    }
}

data class SourceMix(val sourceIndices: List<Int>) {
    init {
        require(sourceIndices.isNotEmpty())
        require(sourceIndices.distinct().size == sourceIndices.size)
        require(sourceIndices.all { it >= 0 })
    }
}

data class StemSourceMap(
    val vocals: SourceMix,
    val music: SourceMix,
    val drums: SourceMix? = null,
    val bass: SourceMix? = null,
    val other: SourceMix? = null,
) {
    fun allSourceIndices(): List<Int> = listOfNotNull(vocals, music, drums, bass, other)
        .flatMap(SourceMix::sourceIndices)
}

data class DeviceRequirements(
    val minimumTotalRamBytes: Long,
    val minimumAvailableRamBytes: Long,
    val userFacingSummary: String,
)

data class StemModelDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val mode: StemMode,
    val modelSpec: ModelSpec,
    val sampleRate: Int,
    val channels: Int,
    val chunking: ChunkingSpec,
    val normalization: AudioNormalization,
    val tensor: TensorContract,
    val sources: StemSourceMap,
    val allowedAccelerators: Set<OnnxAcceleration>,
    val deviceRequirements: DeviceRequirements,
    val licenseName: String,
    val projectUrl: String,
    val backend: StemInferenceBackend = StemInferenceBackend.WAVEFORM_ONNX,
    val mdx: MdxSpectrogramContract? = null,
) {
    init {
        require(id.isNotBlank())
        require(sampleRate > 0)
        require(channels == 2) { "Backend stem hiện hỗ trợ stereo" }
        require(tensor.sourceCount >= 2)
        require(sources.allSourceIndices().all { it < tensor.sourceCount })
        require(mode.stemCount == if (sources.drums == null) 2 else 4)

        when (backend) {
            StemInferenceBackend.WAVEFORM_ONNX -> {
                require(mdx == null) { "Model ONNX waveform không được khai báo contract MDX" }
                require(OnnxAcceleration.CPU in allowedAccelerators) { "Mọi model ONNX phải có fallback CPU" }
            }

            StemInferenceBackend.MDX_LITERT -> {
                require(mode == StemMode.TWO_STEM) { "Pipeline MDX LiteRT hiện chỉ xuất vocals/instrumental" }
                requireNotNull(mdx) { "Model MDX LiteRT phải có contract STFT" }
                require(mdx.chunkFrames == chunking.frames) {
                    "ChunkingSpec.frames phải khớp chunkFrames của MDX"
                }
                require(tensor.sourceCount == 2) { "Descriptor MDX biểu diễn hai đầu ra vocals/instrumental" }
            }
        }
    }

    val downloadSizeMiB: Long
        get() = (modelSpec.expectedBytes + 1024L * 1024L - 1L) / (1024L * 1024L)
}
