package com.aistudio.mediatool.core.ml

data class OnnxThreadingConfig(
    val ortIntraOpThreads: Int,
    val xnnpackThreads: Int?,
)

object OnnxThreadingPolicy {
    private const val XNNPACK_INDEX = 2

    fun resolve(hardwareAccelerationIndex: Int, requestedThreads: Int): OnnxThreadingConfig {
        val safeThreads = requestedThreads.coerceIn(1, 8)
        return if (hardwareAccelerationIndex == XNNPACK_INDEX) {
            // XNNPACK sở hữu thread pool riêng; ORT không nên tạo thêm pool cạnh tranh.
            OnnxThreadingConfig(ortIntraOpThreads = 1, xnnpackThreads = safeThreads)
        } else {
            OnnxThreadingConfig(ortIntraOpThreads = safeThreads, xnnpackThreads = null)
        }
    }
}
