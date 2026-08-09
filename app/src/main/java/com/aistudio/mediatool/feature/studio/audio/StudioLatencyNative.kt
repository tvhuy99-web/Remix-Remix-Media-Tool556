package com.aistudio.mediatool.feature.studio.audio

data class StudioLatencyMeasurement(
    val latencyFrames: Long,
    val sampleRate: Int,
    val outputDeviceId: Int?,
    val inputDeviceId: Int?,
    val confidence: Float,
) {
    val milliseconds: Double
        get() = if (sampleRate > 0) latencyFrames * 1_000.0 / sampleRate else 0.0
}

object StudioLatencyNative {
    fun measure(
        preferredInputDeviceId: Int?,
        preferredOutputDeviceId: Int?,
        inputMode: StudioInputMode,
    ): Result<StudioLatencyMeasurement> = runCatching {
        val values = nativeMeasure(
            preferredInputDeviceId ?: -1,
            preferredOutputDeviceId ?: -1,
            inputMode.nativeValue,
        )
        require(values.size >= 6) { "Latency calibrator không trả đủ dữ liệu" }
        val status = values[0].toInt()
        check(status == 0) { calibrationError(status) }
        val sampleRate = values[2].toInt()
        val frames = values[1]
        require(sampleRate > 0 && frames > 0L) { "Kết quả latency không hợp lệ" }
        StudioLatencyMeasurement(
            latencyFrames = frames,
            sampleRate = sampleRate,
            outputDeviceId = values[3].toInt().takeIf { it >= 0 },
            inputDeviceId = values[4].toInt().takeIf { it >= 0 },
            confidence = (values[5].toFloat() / 1_000f).coerceIn(0f, 1f),
        )
    }

    private fun calibrationError(code: Int): String = when (code) {
        -20_001 -> "Không mở được output route để hiệu chỉnh"
        -20_002 -> "Không mở được microphone route để hiệu chỉnh"
        -20_003 -> "Không thể khởi động luồng hiệu chỉnh"
        -20_004 -> "Hiệu chỉnh quá thời gian hoặc thiết bị bị ngắt kết nối"
        -20_005 -> "Microphone không nhận đủ tín hiệu click. Hãy đưa loa/tai nghe gần microphone hơn và thử lại."
        -20_006 -> "Input và output không chạy cùng sample rate cho phép auto calibration"
        else -> "Hiệu chỉnh latency thất bại (mã $code)"
    }

    private external fun nativeMeasure(
        preferredInputDeviceId: Int,
        preferredOutputDeviceId: Int,
        inputMode: Int,
    ): LongArray

    init {
        System.loadLibrary("mediatool_studio")
    }
}
