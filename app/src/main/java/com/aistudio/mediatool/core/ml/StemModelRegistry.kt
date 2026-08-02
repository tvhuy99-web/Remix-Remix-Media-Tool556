package com.aistudio.mediatool.core.ml

/**
 * Catalog duy nhất cho UI, downloader, preflight và inference. Thêm một model waveform ONNX
 * mới không cần thêm nhánh theo tên model trong các lớp đó; chỉ cần descriptor đã kiểm chứng.
 */
object StemModelRegistry {
    const val HTDEMUCS_FT_VOCALS_QNN_ID = "htdemucs-ft-vocals-fp16-qnn-v1"
    const val MEL_BAND_ROFORMER_ID = "melband-roformer-kj-vocals-v1"
    const val DEMUCS_2_STEM_LITE_ID = "demucs-ht-2stems-lite-v1"
    const val DEMUCS_4_STEM_ID = "demucs-ht-4stems-legacy-v1"

    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB

    private val demucsModelSpec = ModelSpec(
        url = "https://huggingface.co/jackjiangxinfa/demucs-onnx/resolve/49fcb820b3fa39937e955dda5cef1ad35dec1f7c/model.onnx?download=true",
        fileName = "demucs-4stems-49fcb820b3fa39937e955dda5cef1ad35dec1f7c.onnx",
        familyPrefix = "demucs-4stems-",
        expectedBytes = 304_330_587L,
        sha256 = "0cf9f378b3a736efacafe09b8c07aafbb3109568c274ffb7b963b540aa1978d2",
    )

    val htDemucsFtVocalsQnn = StemModelDescriptor(
        id = HTDEMUCS_FT_VOCALS_QNN_ID,
        displayName = "HT-Demucs v4 FT Vocals (QNN GPU)",
        description = "Model vocals fine-tuned, ưu tiên GPU Snapdragon; instrumental được lấy từ mix trừ vocals.",
        mode = StemMode.TWO_STEM,
        modelSpec = ModelSpec(
            url = "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/2ef0d757d3e226d0da85fb8c71514f464fcabdd0/htdemucs_ft_vocals_fp16weights.onnx?download=true",
            fileName = "htdemucs-ft-vocals-fp16-2ef0d757.onnx",
            familyPrefix = "htdemucs-ft-vocals-fp16-",
            expectedBytes = 165_612_636L,
            sha256 = "0cbe651f535415c9d26a7bb614f7d322dd5a080fa0298f2e50f478030a994dce",
        ),
        sampleRate = 44_100,
        channels = 2,
        chunking = ChunkingSpec(
            frames = 343_980,
            overlapFrames = 85_995,
            edgeFadeFrames = 85_995,
            overlapProfile = OverlapProfile.REFERENCE_LINEAR_WINDOW,
        ),
        // Export ONNX tham chiếu nhận PCM float32 [-1, 1] trực tiếp.
        normalization = AudioNormalization.NONE,
        tensor = TensorContract(
            inputName = "mix",
            outputName = "stems",
            inputLayout = TensorAudioLayout.BATCH_CHANNEL_FRAME,
            outputLayout = TensorSourceLayout.BATCH_SOURCE_CHANNEL_FRAME,
            sourceCount = 4,
        ),
        sources = StemSourceMap(
            vocals = SourceMix(listOf(3)),
            music = SourceMix(listOf(0, 1, 2)),
        ),
        musicFromMixMinusVocals = true,
        allowedAccelerators = setOf(
            OnnxAcceleration.CPU,
            OnnxAcceleration.XNNPACK,
            OnnxAcceleration.QNN_GPU,
        ),
        deviceRequirements = DeviceRequirements(
            minimumTotalRamBytes = 6L * GIB,
            minimumAvailableRamBytes = 2L * GIB,
            userFacingSummary = "Khuyến nghị Snapdragon và còn ít nhất 2 GB RAM trống.",
        ),
        licenseName = "MIT",
        projectUrl = "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx",
    )

    val melBandRoFormerTwoStem = StemModelDescriptor(
        id = MEL_BAND_ROFORMER_ID,
        displayName = "Mel-Band RoFormer (2 stem)",
        description = "Tách lời và nhạc nền.",
        mode = StemMode.TWO_STEM,
        modelSpec = ModelSpec(
            url = "https://huggingface.co/smank/mel-band-roformer-vocals-onnx/resolve/60cb6b4b97e41b42f7ff16c2e386f47a8cc7e50a/melband_roformer_vocals.onnx?download=true",
            fileName = "melband-roformer-kj-vocals-60cb6b4b97e41b42f7ff16c2e386f47a8cc7e50a.onnx",
            familyPrefix = "melband-roformer-kj-vocals-",
            expectedBytes = 953_292_899L,
            sha256 = "64a4f3bee48fbe7d971b23875adc924ed004c3533f49672592641dddc0f6f561",
        ),
        sampleRate = 44_100,
        channels = 2,
        chunking = ChunkingSpec(
            frames = 352_800,
            overlapFrames = 176_400,
            edgeFadeFrames = 35_280,
            overlapProfile = OverlapProfile.REFERENCE_LINEAR_WINDOW,
            reflectBoundaryFrames = 176_400,
        ),
        normalization = AudioNormalization.NONE,
        tensor = TensorContract(
            inputName = "mix",
            outputName = "sources",
            inputLayout = TensorAudioLayout.BATCH_CHANNEL_FRAME,
            outputLayout = TensorSourceLayout.BATCH_SOURCE_CHANNEL_FRAME,
            sourceCount = 2,
        ),
        sources = StemSourceMap(
            vocals = SourceMix(listOf(0)),
            music = SourceMix(listOf(1)),
        ),
        // Graph lớn có nhiều op ngoài tập NNAPI phổ biến. Chỉ mở NNAPI sau
        // khi có benchmark/compatibility matrix trên thiết bị thật.
        allowedAccelerators = setOf(OnnxAcceleration.CPU, OnnxAcceleration.XNNPACK),
        deviceRequirements = DeviceRequirements(
            minimumTotalRamBytes = 6L * GIB,
            minimumAvailableRamBytes = 5L * GIB / 2L,
            userFacingSummary = "RAM 8 GB.",
        ),
        licenseName = "MIT",
        projectUrl = "https://huggingface.co/smank/mel-band-roformer-vocals-onnx",
    )

    val demucsTwoStemLite = StemModelDescriptor(
        id = DEMUCS_2_STEM_LITE_ID,
        displayName = "Demucs nhẹ (2 stem)",
        description = "Ít tốn RAM hơn Mel-Band; xuất lời và nhạc nền.",
        mode = StemMode.TWO_STEM,
        modelSpec = demucsModelSpec,
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
            inputName = "input",
            outputName = "output",
            inputLayout = TensorAudioLayout.BATCH_CHANNEL_FRAME,
            outputLayout = TensorSourceLayout.BATCH_SOURCE_CHANNEL_FRAME,
            sourceCount = 4,
        ),
        sources = StemSourceMap(
            vocals = SourceMix(listOf(3)),
            music = SourceMix(listOf(0, 1, 2)),
        ),
        allowedAccelerators = OnnxAcceleration.entries.toSet(),
        deviceRequirements = DeviceRequirements(
            minimumTotalRamBytes = 3L * GIB,
            minimumAvailableRamBytes = 1L * GIB,
            userFacingSummary = "Khuyến nghị còn ít nhất 1 GB RAM trống.",
        ),
        licenseName = "Apache-2.0 (theo metadata nguồn)",
        projectUrl = "https://huggingface.co/jackjiangxinfa/demucs-onnx",
    )

    val demucsFourStem = StemModelDescriptor(
        id = DEMUCS_4_STEM_ID,
        displayName = "Demucs ONNX — 4 stem (cũ)",
        description = "Giữ tương thích với chế độ lời, trống, bass và nhạc cụ khác.",
        mode = StemMode.FOUR_STEM,
        modelSpec = demucsModelSpec,
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
            inputName = "input",
            outputName = "output",
            inputLayout = TensorAudioLayout.BATCH_CHANNEL_FRAME,
            outputLayout = TensorSourceLayout.BATCH_SOURCE_CHANNEL_FRAME,
            sourceCount = 4,
        ),
        sources = StemSourceMap(
            vocals = SourceMix(listOf(3)),
            music = SourceMix(listOf(0, 1, 2)),
            drums = SourceMix(listOf(0)),
            bass = SourceMix(listOf(1)),
            other = SourceMix(listOf(2)),
        ),
        allowedAccelerators = OnnxAcceleration.entries.toSet(),
        deviceRequirements = DeviceRequirements(
            minimumTotalRamBytes = 3L * GIB,
            minimumAvailableRamBytes = 1L * GIB,
            userFacingSummary = "Khuyến nghị còn ít nhất 1 GB RAM trống.",
        ),
        licenseName = "Apache-2.0 (theo metadata nguồn)",
        projectUrl = "https://huggingface.co/jackjiangxinfa/demucs-onnx",
    )

    val all: List<StemModelDescriptor> = listOf(
        htDemucsFtVocalsQnn,
        melBandRoFormerTwoStem,
        demucsFourStem,
        demucsTwoStemLite,
    )

    fun modelsFor(mode: StemMode): List<StemModelDescriptor> = all.filter { it.mode == mode }

    fun find(id: String?): StemModelDescriptor? = all.firstOrNull { it.id == id }

    fun findByFileName(fileName: String): StemModelDescriptor? =
        all.firstOrNull { it.modelSpec.fileName == fileName }

    fun resolve(mode: StemMode, preferredId: String?): StemModelDescriptor =
        find(preferredId)?.takeIf { it.mode == mode }
            ?: modelsFor(mode).firstOrNull()
            ?: error("Chưa có model cho chế độ ${mode.stemCount} stem")
}
