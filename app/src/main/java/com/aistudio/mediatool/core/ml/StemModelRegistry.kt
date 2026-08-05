package com.aistudio.mediatool.core.ml

object StemModelRegistry {
    const val UVR_MDX_VOC_FT_LITERT_ID = "uvr-mdx-voc-ft-litert-fp16-v1"
    const val MDX23C_VOCAL_PERSONAL_ID = "mdx23c-vocals-core-personal-v1"
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
        supportsPolarityDenoise = true,
    )

    val uvrMdxVocFtLiteRt = StemModelDescriptor(
        id = UVR_MDX_VOC_FT_LITERT_ID,
        displayName = "UVR MDX-Net Voc FT",
        description = "2 stem",
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
        chunking = ChunkingSpec(
            frames = uvrMdxVocFtContract.chunkFrames,
            overlapFrames = uvrMdxVocFtContract.overlapFrames,
            edgeFadeFrames = uvrMdxVocFtContract.trimFrames,
            overlapProfile = OverlapProfile.REFERENCE_LINEAR_WINDOW,
        ),
        normalization = AudioNormalization.NONE,
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
            userFacingSummary = "Cần 1,5 GB RAM trống.",
        ),
        licenseName = "MIT",
        projectUrl = "https://huggingface.co/gyoom-sa/UVR-MDX-LiteRT",
        backend = StemInferenceBackend.MDX_LITERT,
        mdx = uvrMdxVocFtContract,
    )

    val mdx23cVocalPersonal = StemModelDescriptor(
        id = MDX23C_VOCAL_PERSONAL_ID,
        displayName = "MDX23C Vocal",
        description = "2 stem • thử nghiệm",
        mode = StemMode.TWO_STEM,
        modelSpec = ModelSpec(
            url = "https://github.com/tvhuy99-web/Remix-Remix-Media-Tool556/releases/download/mdx23c-vocal-personal-v1/mdx23c-vocals-core.onnx",
            fileName = "mdx23c-vocals-core-8925ece1.onnx",
            familyPrefix = "mdx23c-vocals-core-",
            expectedBytes = 448_152_790L,
            sha256 = "8925ece1f0da006d342856f93e75ba2dea9058d44c286c4cd6a98a41c67367bb",
        ),
        sampleRate = 44_100,
        channels = 2,
        chunking = ChunkingSpec(
            frames = Mdx23cVocalPrototypeContract.spectrogram.chunkFrames,
            overlapFrames = Mdx23cVocalPrototypeContract.spectrogram.overlapFrames,
            edgeFadeFrames = Mdx23cVocalPrototypeContract.spectrogram.windowFadeFrames,
            overlapProfile = OverlapProfile.REFERENCE_LINEAR_WINDOW,
            reflectBoundaryFrames = Mdx23cVocalPrototypeContract.spectrogram.reflectBoundaryFrames,
        ),
        normalization = AudioNormalization.NONE,
        tensor = Mdx23cVocalPrototypeContract.tensor,
        sources = StemSourceMap(
            vocals = SourceMix(listOf(0)),
            music = SourceMix(listOf(1)),
        ),
        allowedAccelerators = setOf(
            OnnxAcceleration.CPU,
            OnnxAcceleration.XNNPACK,
        ),
        deviceRequirements = DeviceRequirements(
            minimumTotalRamBytes = 8L * GIB,
            minimumAvailableRamBytes = 3L * GIB,
            userFacingSummary = "Cần thiết bị 8 GB RAM và khoảng 3 GB RAM trống.",
        ),
        licenseName = "Dùng cá nhân/phi thương mại",
        projectUrl = "https://github.com/ZFTurbo/Music-Source-Separation-Training/releases/tag/v1.0.0",
        backend = StemInferenceBackend.MDX_ONNX,
        mdx = Mdx23cVocalPrototypeContract.spectrogram,
    )

    val demucsTwoStemLite = StemModelDescriptor(
        id = DEMUCS_2_STEM_LITE_ID,
        displayName = "Demucs",
        description = "2 stem",
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
        allowedAccelerators = setOf(OnnxAcceleration.CPU),
        deviceRequirements = DeviceRequirements(
            minimumTotalRamBytes = 8L * GIB,
            minimumAvailableRamBytes = 5L * GIB / 2L,
            userFacingSummary = "Cần thiết bị 8 GB RAM và khoảng 2,5 GB RAM trống.",
        ),
        licenseName = "Apache-2.0",
        projectUrl = "https://huggingface.co/jackjiangxinfa/demucs-onnx",
    )

    val demucsFourStem = StemModelDescriptor(
        id = DEMUCS_4_STEM_ID,
        displayName = "Demucs",
        description = "4 stem",
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
        allowedAccelerators = setOf(OnnxAcceleration.CPU),
        deviceRequirements = DeviceRequirements(
            minimumTotalRamBytes = 8L * GIB,
            minimumAvailableRamBytes = 5L * GIB / 2L,
            userFacingSummary = "Cần thiết bị 8 GB RAM và khoảng 2,5 GB RAM trống.",
        ),
        licenseName = "Apache-2.0",
        projectUrl = "https://huggingface.co/jackjiangxinfa/demucs-onnx",
    )

    val all: List<StemModelDescriptor> = listOf(
        uvrMdxVocFtLiteRt,
        mdx23cVocalPersonal,
        demucsTwoStemLite,
        demucsFourStem,
    )

    fun modelsFor(mode: StemMode): List<StemModelDescriptor> = all.filter { it.mode == mode }

    fun find(id: String?): StemModelDescriptor? = all.firstOrNull { it.id == id }

    fun findByFileName(fileName: String): StemModelDescriptor? =
        all.firstOrNull { it.modelSpec.fileName == fileName }

    fun resolve(mode: StemMode, preferredId: String?): StemModelDescriptor =
        find(preferredId)?.takeIf { it.mode == mode }
            ?: modelsFor(mode).firstOrNull()
            ?: error("Không có model ${mode.stemCount} stem")
}
