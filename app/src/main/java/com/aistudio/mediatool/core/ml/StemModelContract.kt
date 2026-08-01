package com.aistudio.mediatool.core.ml

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
) {
    init {
        require(id.isNotBlank())
        require(sampleRate > 0)
        require(channels == 2) { "Backend waveform hiện hỗ trợ stereo" }
        require(tensor.sourceCount >= 2)
        require(sources.allSourceIndices().all { it < tensor.sourceCount })
        require(mode.stemCount == if (sources.drums == null) 2 else 4)
        require(OnnxAcceleration.CPU in allowedAccelerators) { "Mọi model phải có fallback CPU" }
    }

    val downloadSizeMiB: Long
        get() = (modelSpec.expectedBytes + 1024L * 1024L - 1L) / (1024L * 1024L)
}
