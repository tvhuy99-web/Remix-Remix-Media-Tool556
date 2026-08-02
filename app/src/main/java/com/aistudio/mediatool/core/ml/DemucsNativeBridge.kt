package com.aistudio.mediatool.core.ml

import androidx.annotation.Keep

/** JNI mỏng cho engine demucs.cpp. Toàn bộ inference chạy trên một worker IO. */
class DemucsNativeBridge {
    @Keep
    class ProgressCallback(
        private val callback: (Float, String) -> Unit,
    ) {
        @Keep
        fun onProgress(progress: Float, message: String) {
            callback(progress.coerceIn(0f, 1f), message)
        }
    }

    /** Trả null khi thành công, hoặc chuỗi lỗi ổn định khi thất bại. */
    external fun separate(
        modelPath: String,
        inputRawPath: String,
        vocalsRawPath: String,
        musicRawPath: String,
        drumsRawPath: String,
        bassRawPath: String,
        otherRawPath: String,
        writeFourStems: Boolean,
        threadCount: Int,
        callback: ProgressCallback,
    ): String?

    external fun cancel()

    companion object {
        init {
            System.loadLibrary("mediatool_demucs")
        }
    }
}
