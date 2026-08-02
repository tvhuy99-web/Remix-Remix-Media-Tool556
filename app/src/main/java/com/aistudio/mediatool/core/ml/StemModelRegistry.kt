package com.aistudio.mediatool.core.ml

/** Catalog model được xác minh cho engine native demucs.cpp. */
object StemModelRegistry {
    const val DEMUCS_FT_VOCALS_ID = "demucs-ht-v4-ft-vocals-native-f16-v1"
    const val DEMUCS_FT_VOCALS_4_STEM_ID = "demucs-ht-v4-ft-vocals-native-f16-4stem-v1"

    // Giữ ID cũ để cài đặt đã lưu tự fallback, không còn model tương ứng trong catalog.
    const val MEL_BAND_ROFORMER_ID = "melband-roformer-kj-vocals-v1"
    const val DEMUCS_2_STEM_LITE_ID = "demucs-ht-2stems-lite-v1"
    const val DEMUCS_4_STEM_ID = "demucs-ht-4stems-legacy-v1"

    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB

    private val nativeFtVocalsSpec = ModelSpec(
        url = "https://huggingface.co/datasets/Retrobear/demucs.cpp/resolve/5f5daffffcf06ad7b27a7285da327e18ea62068a/ggml-model-htdemucs_ft_vocals-4s-f16.bin?download=true",
        fileName = "ggml-model-htdemucs-ft-vocals-5f5daff-f16.bin",
        familyPrefix = "ggml-model-htdemucs-ft-vocals-",
        expectedBytes = 83_994_361L,
        sha256 = "19186500a45a551a034d96e9500415ebe73c8bd570bf55337ddc8cc8f53a9120",
    )

    private fun descriptor(id: String, mode: StemMode, displayName: String) = StemModelDescriptor(
        id = id,
        displayName = displayName,
        description = if (mode == StemMode.TWO_STEM) {
            "Demucs v4 fine-tuned cho giọng hát, chạy bằng engine C++ native."
        } else {
            "Xuất lời, trống, bass và nhạc cụ khác bằng Demucs v4 native."
        },
        mode = mode,
        modelSpec = nativeFtVocalsSpec,
        sampleRate = 44_100,
        channels = 2,
        chunking = ChunkingSpec(
            frames = 343_980,
            overlapFrames = 85_995,
            edgeFadeFrames = 85_995,
            overlapProfile = OverlapProfile.COMPLEMENTARY_SINE,
        ),
        normalization = AudioNormalization.GLOBAL_MONO_MEAN_STD,
        tensor = TensorContract(
            inputName = "native_pcm",
            outputName = "native_sources",
            inputLayout = TensorAudioLayout.BATCH_CHANNEL_FRAME,
            outputLayout = TensorSourceLayout.BATCH_SOURCE_CHANNEL_FRAME,
            sourceCount = 4,
        ),
        sources = StemSourceMap(
            vocals = SourceMix(listOf(3)),
            music = SourceMix(listOf(0, 1, 2)),
            drums = if (mode == StemMode.FOUR_STEM) SourceMix(listOf(0)) else null,
            bass = if (mode == StemMode.FOUR_STEM) SourceMix(listOf(1)) else null,
            other = if (mode == StemMode.FOUR_STEM) SourceMix(listOf(2)) else null,
        ),
        allowedAccelerators = setOf(OnnxAcceleration.CPU),
        deviceRequirements = DeviceRequirements(
            minimumTotalRamBytes = 4L * GIB,
            minimumAvailableRamBytes = 1L * GIB,
            userFacingSummary = "Khuyến nghị còn ít nhất 1 GB RAM trống.",
        ),
        licenseName = "MIT",
        projectUrl = "https://github.com/sevagh/demucs.cpp",
    )

    val demucsFtVocalsTwoStem = descriptor(
        DEMUCS_FT_VOCALS_ID,
        StemMode.TWO_STEM,
        "Demucs v4 Vocals chất lượng cao",
    )
    val demucsFtVocalsFourStem = descriptor(
        DEMUCS_FT_VOCALS_4_STEM_ID,
        StemMode.FOUR_STEM,
        "Demucs v4 Native (4 stem)",
    )

    // Alias nguồn để các màn hình cũ biên dịch trong giai đoạn migration.
    val demucsTwoStemLite: StemModelDescriptor = demucsFtVocalsTwoStem
    val demucsFourStem: StemModelDescriptor = demucsFtVocalsFourStem

    val all: List<StemModelDescriptor> = listOf(
        demucsFtVocalsTwoStem,
        demucsFtVocalsFourStem,
    )

    fun modelsFor(mode: StemMode): List<StemModelDescriptor> = all.filter { it.mode == mode }

    fun find(id: String?): StemModelDescriptor? = all.firstOrNull { it.id == id }

    fun findByFileName(fileName: String): StemModelDescriptor? =
        all.firstOrNull { it.modelSpec.fileName == fileName }

    fun resolve(mode: StemMode, preferredId: String?): StemModelDescriptor =
        find(preferredId)?.takeIf { it.mode == mode }
            ?: modelsFor(mode).firstOrNull()
            ?: demucsFtVocalsTwoStem
}
