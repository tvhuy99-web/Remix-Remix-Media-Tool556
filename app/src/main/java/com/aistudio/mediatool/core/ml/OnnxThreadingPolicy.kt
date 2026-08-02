package com.aistudio.mediatool.core.ml

data class OnnxThreadingConfig(
    val ortIntraOpThreads: Int,
    val xnnpackThreads: Int?,
)

object OnnxThreadingPolicy {
    private const val XNNPACK_INDEX = 2
    private const val QNN_GPU_INDEX = 3

    fun resolve(hardwareAccelerationIndex: Int, requestedThreads: Int): OnnxThreadingConfig {
        val safeThreads = requestedThreads.coerceIn(1, 8)
        return when (hardwareAccelerationIndex) {
            XNNPACK_INDEX -> {
                // XNNPACK sở hữu thread pool riêng; ORT không nên tạo thêm pool cạnh tranh.
                OnnxThreadingConfig(ortIntraOpThreads = 1, xnnpackThreads = safeThreads)
            }
            QNN_GPU_INDEX -> {
                // QNN GPU thực thi graph trên Adreno. CPU chỉ điều phối I/O và không cần pool ORT lớn.
                OnnxThreadingConfig(ortIntraOpThreads = 1, xnnpackThreads = null)
            }
            else -> OnnxThreadingConfig(ortIntraOpThreads = safeThreads, xnnpackThreads = null)
        }
    }
}
