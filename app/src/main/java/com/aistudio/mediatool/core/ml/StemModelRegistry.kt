package com.aistudio.mediatool.core.ml

/**
 * Catalog duy nhất cho UI, downloader, preflight và inference.
 *
 * Waveform ONNX và MDX LiteRT dùng cùng descriptor/download path nhưng được định tuyến sang engine riêng
 * bởi [StemEngineRouter]. Model URL luôn ghim vào revision và mọi byte đều được kiểm tra SHA-256.
 */
object StemModelRegistry {
    const val UVR_MDX_VOC_FT_LITERT_ID = "uvr-mdx-voc-ft-litert-fp16-v1"
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

    private val uvrMdxVocFtContract = MdxSpectrogramContract(
        nFft = 6_144,
        hopLength = 1_024,
        frequencyBins = 3_072,
        timeFrames = 256,
        overlapRatio = 0.10f,
        compensation = 1.0f,
    )

    val uvrMdxVocFtLiteRt = StemModelDescriptor(
        id = UVR_MDX_VOC_FT_LITERT_ID,
        displayName = "UVR MDX-Net Voc FT — LiteRT (thử nghiệm)",
        description = "Model chuyên vocals; ưu tiên LiteRT GPU, tự chuyển CPU 4 luồng nếu GPU không dùng được.",
        mode = StemMode.TWO_STEM,
        modelSpec = ModelSpec(
            url = "https://huggingface.co/gyoom-sa/UVR-MDX-LiteRT/resolve/2ea6124ae9a9e83e2c2bc432eb84533d76310a66/UVR-MDX-NET-Voc_FT.fp16acc.tflite?download=true",
            fileName = "UVR-MDX-NET-Voc_FT-2ea6124-fp16acc.tflite",
            familyPrefix = "UVR-MDX-NET-Voc_FT-",
            expectedBytes = 66_848_828L,
            sha256 = "5ef47e3b3bafa14357532c0a3f6c5f18444d94b6efe3fd62b3d13f80051f1e58",
        ),
        sampleRate = 44_100,
        channels = 2,
        // MDX engine uses mdx.strideFrames/window; this mirrors its native static tensor size for preflight/UI.
        chunking = ChunkingSpec(
            frames = uvrMdxVocFtContract.chunkFrames,
            overlapFrames = uvrMdxVocFtContract.overlapFrames,
            edgeFadeFrames = uvrMdxVocFtContract.trimFrames,
            overlapProfile = OverlapProfile.REFERENCE_LINEAR_WINDOW,
        ),
        normalization = AudioNormalization.NONE,
        // Placeholder logical contract for the common descriptor. Physical LiteRT I/O is defined by mdx.
        tensor = TensorContract(
            inputName = "serving_default_args_0:0",
            outputName = "PartitionedCall:0",
            inputLayout = TensorAudioLayout.BATCH_CHANNEL_FRAME,
            outputLayout = TensorSourceLayout.BATCH_SOURCE_CHANNEL_FRAME,
            sourceCount = 2,
        ),
        sources = StemSourceMap(
            vocals = SourceMix(listOf(0)),
            music = SourceMix(listOf(1)),
        ),
        allowedAccelerators = setOf(OnnxAcceleration.CPU),
        deviceRequirements = DeviceRequirements(
            minimumTotalRamBytes = 4L * GIB,
            minimumAvailableRamBytes = 3L * GIB / 2L,
            userFacingSummary = "Khuyến nghị RAM 6 GB và còn ít nhất 1,5 GB trống.",
        ),
        licenseName = "MIT (UVR MDX-Net và bản chuyển LiteRT)",
        projectUrl = "https://huggingface.co/gyoom-sa/UVR-MDX-LiteRT",
        backend = StemInferenceBackend.MDX_LITERT,
        mdx = uvrMdxVocFtContract,
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
        description = "Baseline ổn định 1.3.3; xuất lời và nhạc nền bằng ONNX CPU/XNNPACK.",
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
        // Experimental branch defaults new two-stem installs to UVR; persisted model IDs remain respected.
        uvrMdxVocFtLiteRt,
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
