package com.aistudio.mediatool.core.ml

/** User-selectable execution path for the MDX23C ONNX core. */
enum class Mdx23cExecutionMode(
    val settingsIndex: Int,
    val displayName: String,
    val acceleration: OnnxAcceleration,
) {
    CPU(0, "CPU ổn định", OnnxAcceleration.CPU),
    XNNPACK(1, "XNNPACK thử nghiệm", OnnxAcceleration.XNNPACK),
    ;

    companion object {
        fun fromSettingsIndex(index: Int): Mdx23cExecutionMode =
            entries.firstOrNull { it.settingsIndex == index } ?: XNNPACK
    }
}

/**
 * Runtime overlap modes. The static ONNX tensor is unchanged; only the host-side stride changes.
 * Strides are exact quarters of the 261,120-frame MDX23C chunk.
 */
enum class Mdx23cOverlapMode(
    val settingsIndex: Int,
    val displayName: String,
    val overlapPercent: Int,
    val strideFrames: Int,
    val explanation: String,
) {
    FAST(
        settingsIndex = 0,
        displayName = "Nhanh • overlap 25%",
        overlapPercent = 25,
        strideFrames = 195_840,
        explanation = "Ít lượt inference nhất; cần nghe kỹ các điểm nối.",
    ),
    BALANCED(
        settingsIndex = 1,
        displayName = "Cân bằng • overlap 50%",
        overlapPercent = 50,
        strideFrames = 130_560,
        explanation = "Mặc định thử nghiệm; giảm gần một nửa số chunk so với 75%.",
    ),
    HIGH_QUALITY(
        settingsIndex = 2,
        displayName = "Chất lượng cao • overlap 75%",
        overlapPercent = 75,
        strideFrames = 65_280,
        explanation = "Giữ cấu hình tham chiếu hiện tại nhưng rất chậm.",
    ),
    ;

    fun requireCompatible(contract: MdxSpectrogramContract): Mdx23cOverlapMode = apply {
        require(strideFrames in 1..contract.generatedFrames)
        require(contract.generatedFrames - strideFrames >= contract.windowFadeFrames) {
            "Overlap MDX23C phải đủ dài cho cửa sổ fade"
        }
    }

    fun overlapFrames(contract: MdxSpectrogramContract): Int =
        contract.generatedFrames - strideFrames

    companion object {
        fun fromSettingsIndex(index: Int): Mdx23cOverlapMode =
            entries.firstOrNull { it.settingsIndex == index } ?: BALANCED
    }
}
