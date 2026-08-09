package com.aistudio.mediatool.feature.studio.audio

import android.content.Context
import android.media.AudioManager
import java.io.Closeable
import java.io.File

enum class StudioInputMode(val nativeValue: Int) {
    AUTO(0),
    STUDIO_RAW(1),
    LIVE_LOW_LATENCY(2),
}

data class StudioPlaybackClip(
    val file: File,
    val timelineStartFrame: Long,
    val sourceStartFrame: Long,
    val sourceEndFrame: Long,
    val gainDb: Float = 0f,
    val fadeInFrames: Long = 0L,
    val fadeOutFrames: Long = 0L,
)

/** Thread-safe Kotlin owner for the realtime Oboe Studio engine. */
class StudioNativeAudio(context: Context) : Closeable {
    private val nativeLock = Any()
    private var nativeHandle: Long = nativeCreate()

    init {
        val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
        val sampleRate = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?: 0
        val framesPerBurst = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?: 0
        nativeConfigureDefaults(sampleRate, framesPerBurst)
    }

    val isReleased: Boolean
        get() = synchronized(nativeLock) { nativeHandle == 0L }

    fun openOutput(preferredDeviceId: Int? = null): StudioAudioOperationResult = synchronized(nativeLock) {
        withHandle { resultOf(nativeOpenOutput(it, preferredDeviceId ?: -1)) }
    }

    fun start(): StudioAudioOperationResult = synchronized(nativeLock) {
        withHandle { resultOf(nativeStart(it)) }
    }

    fun stop(): StudioAudioOperationResult = synchronized(nativeLock) {
        withHandle { resultOf(nativeStop(it)) }
    }

    fun loadBeat(file: File, sampleRate: Int, channelCount: Int): StudioAudioOperationResult = synchronized(nativeLock) {
        if (!file.isFile || file.length() <= 0L) return@synchronized StudioAudioOperationResult.Error(-10_001)
        withHandle { resultOf(nativeLoadBeat(it, file.absolutePath, sampleRate, channelCount)) }
    }

    fun setArrangement(
        clips: List<StudioPlaybackClip>,
        projectSampleRate: Int,
    ): StudioAudioOperationResult = synchronized(nativeLock) {
        if (projectSampleRate <= 0 || clips.any { !it.file.isFile || it.sourceEndFrame <= it.sourceStartFrame }) {
            return@synchronized StudioAudioOperationResult.Error(-10_006)
        }
        withHandle { handle ->
            resultOf(
                nativeSetArrangement(
                    handle = handle,
                    paths = clips.map { it.file.absolutePath }.toTypedArray(),
                    timelineStarts = LongArray(clips.size) { clips[it].timelineStartFrame },
                    sourceStarts = LongArray(clips.size) { clips[it].sourceStartFrame },
                    sourceEnds = LongArray(clips.size) { clips[it].sourceEndFrame },
                    gainsDb = FloatArray(clips.size) { clips[it].gainDb },
                    fadeIns = LongArray(clips.size) { clips[it].fadeInFrames },
                    fadeOuts = LongArray(clips.size) { clips[it].fadeOutFrames },
                    projectSampleRate = projectSampleRate,
                ),
            )
        }
    }

    fun setPunchMuteWindow(startFrame: Long?, endFrame: Long?) = synchronized(nativeLock) {
        val handle = nativeHandle
        if (handle == 0L) return@synchronized
        val valid = startFrame != null && endFrame != null && startFrame >= 0L && endFrame > startFrame
        nativeSetPunchMuteWindow(
            handle,
            if (valid) startFrame!! else -1L,
            if (valid) endFrame!! else -1L,
        )
    }

    fun setPlaying(playing: Boolean) = synchronized(nativeLock) {
        val handle = nativeHandle
        if (handle != 0L) nativeSetPlaying(handle, playing)
    }

    fun seek(projectFrame: Long) = synchronized(nativeLock) {
        val handle = nativeHandle
        if (handle != 0L) nativeSeek(handle, projectFrame.coerceAtLeast(0L))
    }

    fun prepareInput(
        preferredDeviceId: Int? = null,
        mode: StudioInputMode = StudioInputMode.AUTO,
    ): StudioAudioOperationResult = synchronized(nativeLock) {
        withHandle { resultOf(nativePrepareInput(it, preferredDeviceId ?: -1, mode.nativeValue)) }
    }

    fun startRecording(target: File): StudioAudioOperationResult = synchronized(nativeLock) {
        target.parentFile?.mkdirs()
        target.delete()
        withHandle { resultOf(nativeStartRecording(it, target.absolutePath)) }
    }

    fun stopRecording(): StudioAudioOperationResult = synchronized(nativeLock) {
        withHandle { resultOf(nativeStopRecording(it)) }
    }

    fun closeStream() = synchronized(nativeLock) {
        val handle = nativeHandle
        if (handle != 0L) nativeClose(handle)
    }

    fun diagnostics(): StudioAudioDiagnostics? = synchronized(nativeLock) {
        val handle = nativeHandle
        if (handle == 0L) return@synchronized null
        val values = nativeDiagnostics(handle)
        if (values.size < DIAGNOSTIC_FIELD_COUNT || values[0] <= 0L) return@synchronized null
        StudioAudioDiagnostics(
            sampleRate = values[0].toInt(),
            channelCount = values[1].toInt(),
            framesPerBurst = values[2].toInt(),
            bufferSizeFrames = values[3].toInt(),
            bufferCapacityFrames = values[4].toInt(),
            outputDeviceId = values[5].toInt(),
            audioApi = values[6].toInt(),
            sharingMode = values[7].toInt(),
            performanceMode = values[8].toInt(),
            callbackFrames = values[9],
            disconnectCount = values[10],
            transportFrame = values[11],
            beatDurationFrames = values[12],
            inputSampleRate = values[13].toInt().takeIf { it > 0 },
            inputDeviceId = values[14].toInt().takeIf { it >= 0 },
            recordedFrames = values[15],
            ringOverrunFrames = values[16],
            isPlaying = values[17] != 0L,
            isRecording = values[18] != 0L,
            writerErrorCode = values[19].toInt(),
            arrangementClipCount = values[20].toInt(),
            arrangementDurationFrames = values[21],
        )
    }

    override fun close() = synchronized(nativeLock) {
        val handle = nativeHandle
        if (handle == 0L) return@synchronized
        nativeHandle = 0L
        nativeRelease(handle)
    }

    private inline fun withHandle(block: (Long) -> StudioAudioOperationResult): StudioAudioOperationResult {
        val handle = nativeHandle
        return if (handle == 0L) StudioAudioOperationResult.Released else block(handle)
    }

    private fun resultOf(code: Int): StudioAudioOperationResult =
        if (code == 0) StudioAudioOperationResult.Success else StudioAudioOperationResult.Error(code)

    private external fun nativeConfigureDefaults(sampleRate: Int, framesPerBurst: Int)
    private external fun nativeCreate(): Long
    private external fun nativeOpenOutput(handle: Long, preferredDeviceId: Int): Int
    private external fun nativeStart(handle: Long): Int
    private external fun nativeStop(handle: Long): Int
    private external fun nativeLoadBeat(handle: Long, path: String, sampleRate: Int, channelCount: Int): Int
    private external fun nativeSetArrangement(
        handle: Long,
        paths: Array<String>,
        timelineStarts: LongArray,
        sourceStarts: LongArray,
        sourceEnds: LongArray,
        gainsDb: FloatArray,
        fadeIns: LongArray,
        fadeOuts: LongArray,
        projectSampleRate: Int,
    ): Int
    private external fun nativeSetPunchMuteWindow(handle: Long, startFrame: Long, endFrame: Long)
    private external fun nativeSetPlaying(handle: Long, playing: Boolean)
    private external fun nativeSeek(handle: Long, projectFrame: Long)
    private external fun nativePrepareInput(handle: Long, preferredDeviceId: Int, inputMode: Int): Int
    private external fun nativeStartRecording(handle: Long, path: String): Int
    private external fun nativeStopRecording(handle: Long): Int
    private external fun nativeClose(handle: Long)
    private external fun nativeDiagnostics(handle: Long): LongArray
    private external fun nativeRelease(handle: Long)

    companion object {
        private const val DIAGNOSTIC_FIELD_COUNT = 22

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
    val outputDeviceId: Int,
    val audioApi: Int,
    val sharingMode: Int,
    val performanceMode: Int,
    val callbackFrames: Long,
    val disconnectCount: Long,
    val transportFrame: Long,
    val beatDurationFrames: Long,
    val inputSampleRate: Int?,
    val inputDeviceId: Int?,
    val recordedFrames: Long,
    val ringOverrunFrames: Long,
    val isPlaying: Boolean,
    val isRecording: Boolean,
    val writerErrorCode: Int,
    val arrangementClipCount: Int,
    val arrangementDurationFrames: Long,
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
