package com.aistudio.mediatool.core.ml

data class VoiceCleanupModelDescriptor(
    val id: String,
    val displayName: String,
    val modelSpec: ModelSpec,
    val sampleRate: Int,
    val windowLength: Int,
    val hopLength: Int,
    val licenseName: String,
    val projectUrl: String,
) {
    val downloadSizeMiB: Long
        get() = (modelSpec.expectedBytes + 1024L * 1024L - 1L) / (1024L * 1024L)
}

object VoiceCleanupModelRegistry {
    const val DPDFNET8_48KHZ_HR_ID = "dpdfnet8-48khz-hr-litert-v1"
    const val REVISION = "dd6818d00f50c836fed43a6243ebe49116de5964"

    val dpdfnet8_48khz_hr = VoiceCleanupModelDescriptor(
        id = DPDFNET8_48KHZ_HR_ID,
        displayName = "DPDFNet-8 48 kHz",
        modelSpec = ModelSpec(
            url = "https://huggingface.co/Ceva-IP/DPDFNet/resolve/$REVISION/dpdfnet8_48khz_hr.tflite?download=true",
            fileName = "dpdfnet8_48khz_hr-dd6818d.tflite",
            familyPrefix = "dpdfnet8_48khz_hr-",
            expectedBytes = 19_639_068L,
            sha256 = "3a28291a00b359592eaf6e853f49344eb6aac23dc992739de28da0f9face44c3",
        ),
        sampleRate = 48_000,
        windowLength = 960,
        hopLength = 480,
        licenseName = "Apache-2.0",
        projectUrl = "https://github.com/ceva-ip/DPDFNet",
    )
}
