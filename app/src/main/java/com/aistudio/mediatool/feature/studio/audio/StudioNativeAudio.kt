package com.aistudio.mediatool.feature.studio.audio

import java.io.Closeable

/**
 * Thin owner for the native Oboe engine. Step 2 only establishes the output
 * clock and diagnostics. Beat rendering and microphone capture are added on top
 * of this engine in the Studio Recording step.
 */
class StudioNativeAudio : Closeable {
    private var nativeHandle: Long = nativeCreate()

    val isReleased: Boolean get() = nativeHandle == 0L

    fun openOutput(preferredDeviceId: Int? = null): StudioAudioOperationResult {
        val handle = nativeHandle
        if (handle == 0L) return StudioAudioOperationResult.Released
        return resultOf(nativeOpenOutput(handle, preferredDeviceId ?: -1))
    }

    fun start(): StudioAudioOperationResult {
        val handle = nativeHandle
        if (handle == 0L) return StudioAudioOperationResult.Released
        return resultOf(nativeStart(handle))
    }

    fun stop(): StudioAudioOperationResult {
        val handle = nativeHandle
        if (handle == 0L) return StudioAudioOperationResult.Released
        return resultOf(nativeStop(handle))
    }

    fun closeStream() {
        val handle = nativeHandle
        if (handle != 0L) nativeClose(handle)
    }

    fun diagnostics(): StudioAudioDiagnostics? {
        val handle = nativeHandle
        if (handle == 0L) return null
        val values = nativeDiagnostics(handle)
        if (values.size < DIAGNOSTIC_FIELD_COUNT || values[0] <= 0L) return null
        return StudioAudioDiagnostics(
            sampleRate = values[0].toInt(),
            channelCount = values[1].toInt(),
            framesPerBurst = values[2].toInt(),
            bufferSizeFrames = values[3].toInt(),
            bufferCapacityFrames = values[4].toInt(),
            deviceId = values[5].toInt(),
            audioApi = values[6].toInt(),
            sharingMode = values[7].toInt(),
            performanceMode = values[8].toInt(),
            callbackFrames = values[9],
            disconnectCount = values[10],
        )
    }

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        nativeRelease(handle)
    }

    private fun resultOf(code: Int): StudioAudioOperationResult =
        if (code == 0) StudioAudioOperationResult.Success else StudioAudioOperationResult.Error(code)

    private external fun nativeCreate(): Long
    private external fun nativeOpenOutput(handle: Long, preferredDeviceId: Int): Int
    private external fun nativeStart(handle: Long): Int
    private external fun nativeStop(handle: Long): Int
    private external fun nativeClose(handle: Long)
    private external fun nativeDiagnostics(handle: Long): LongArray
    private external fun nativeRelease(handle: Long)

    companion object {
        private const val DIAGNOSTIC_FIELD_COUNT = 11

        init {
            System.loadLibrary("mediatool_studio")
        }
    }
}

sealed interface StudioAudioOperationResult {
    data object Success : StudioAudioOperationResult
    data object Released : StudioAudioOperationResult
    data class Error(val nativeCode: Int) : StudioAudioOperationResult
}

data class StudioAudioDiagnostics(
    val sampleRate: Int,
    val channelCount: Int,
    val framesPerBurst: Int,
    val bufferSizeFrames: Int,
    val bufferCapacityFrames: Int,
    val deviceId: Int,
    val audioApi: Int,
    val sharingMode: Int,
    val performanceMode: Int,
    val callbackFrames: Long,
    val disconnectCount: Long,
) {
    val approximateBufferMs: Double
        get() = if (sampleRate > 0) bufferSizeFrames * 1000.0 / sampleRate else 0.0

    val audioApiLabel: String
        get() = when (audioApi) {
            1 -> "OpenSL ES"
            2 -> "AAudio"
            else -> "Mặc định"
        }

    val sharingModeLabel: String
        get() = when (sharingMode) {
            0 -> "Exclusive"
            1 -> "Shared"
            else -> "Không xác định"
        }

    val performanceModeLabel: String
        get() = when (performanceMode) {
            10 -> "None"
            11 -> "Power saving"
            12 -> "Low latency"
            else -> "Không xác định"
        }
}
