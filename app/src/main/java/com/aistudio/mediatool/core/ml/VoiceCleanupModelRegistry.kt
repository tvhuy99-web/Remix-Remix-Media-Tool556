package com.aistudio.mediatool.core.ml

data class VoiceCleanupModelDescriptor(
    val id: String,
    val displayName: String,
    val modelSpec: ModelSpec,
    val sampleRate: Int,
    val segmentSamples: Int,
    val strideSamples: Int,
    val licenseName: String,
    val projectUrl: String,
) {
    val downloadSizeMiB: Long
        get() = (modelSpec.expectedBytes + 1024L * 1024L - 1L) / (1024L * 1024L)
}

object VoiceCleanupModelRegistry {
    const val MOSSFORMER2_ID = "mossformer2-se-48k-onnx-v1"
    const val REVISION = "0d91401f480ab971bb26daa108771c5fc9c8cfeb"

    val mossFormer2 = VoiceCleanupModelDescriptor(
        id = MOSSFORMER2_ID,
        displayName = "MossFormer2 SE 48 kHz",
        modelSpec = ModelSpec(
            url = "https://huggingface.co/TigreGotico/audiosronnx-mossformer2/resolve/$REVISION/mossformer2_48k.onnx?download=true",
            fileName = "mossformer2-se-48k-$REVISION.onnx",
            familyPrefix = "mossformer2-se-48k-",
            expectedBytes = 229_126_935L,
            sha256 = "0904ff3b74bdc089854612096edbe5a2fcfada489241972ba69e0c3ccb24304a",
        ),
        sampleRate = MossFormer2Dsp.SAMPLE_RATE,
        segmentSamples = VoiceCleanupWindowMode.COMPATIBILITY_4S.segmentSamples,
        strideSamples = VoiceCleanupWindowMode.COMPATIBILITY_4S.strideSamples,
        licenseName = "Apache-2.0",
        projectUrl = "https://github.com/modelscope/ClearerVoice-Studio",
    )
}
