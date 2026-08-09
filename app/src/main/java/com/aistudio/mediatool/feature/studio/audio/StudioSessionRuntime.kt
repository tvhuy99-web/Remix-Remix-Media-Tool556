package com.aistudio.mediatool.feature.studio.audio

import android.content.Context
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.feature.studio.data.PendingStudioTake
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.data.StudioWaveform
import com.aistudio.mediatool.feature.studio.data.StudioWaveformStore
import com.aistudio.mediatool.feature.studio.data.selectActiveTake
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.max

enum class StudioSessionStatus {
    CLOSED,
    LOADING,
    READY,
    PLAYING,
    RECORDING,
    ERROR,
}

data class StudioSessionState(
    val projectId: String? = null,
    val project: StudioProject? = null,
    val status: StudioSessionStatus = StudioSessionStatus.CLOSED,
    val message: String? = null,
    val errorMessage: String? = null,
    val transportFrame: Long = 0L,
    val durationFrames: Long = 0L,
    val waveforms: Map<String, StudioWaveform> = emptyMap(),
    val diagnostics: StudioAudioDiagnostics? = null,
    val recoveredTakeCount: Int = 0,
) {
    val isPrepared: Boolean
        get() = status == StudioSessionStatus.READY ||
            status == StudioSessionStatus.PLAYING ||
            status == StudioSessionStatus.RECORDING
}

/**
 * Process-scoped owner of the Studio engine. A foreground service keeps the
 * process important while recording; if the process is nevertheless killed,
 * the take journal is recovered on the next open.
 */
object StudioSessionRuntime {
    private const val TAG = "StudioSession"
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MediaTool-StudioSession").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(StudioSessionState())
    val state: StateFlow<StudioSessionState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var repository: StudioProjectRepository? = null
    private var waveformStore: StudioWaveformStore? = null
    private var engine: StudioNativeAudio? = null
    private var pollJob: Job? = null
    private var pendingTake: PendingStudioTake? = null

    fun open(context: Context, projectId: String) {
        val applicationContext = context.applicationContext
        scope.launch {
            if (_state.value.projectId == projectId && _state.value.isPrepared && engine != null) return@launch
            openInternal(applicationContext, projectId)
        }
    }

    fun play() {
        scope.launch {
            val native = engine ?: return@launch
            val current = _state.value
            if (!current.isPrepared || current.status == StudioSessionStatus.RECORDING) return@launch
            native.setPlaying(true)
            _state.value = current.copy(status = StudioSessionStatus.PLAYING, errorMessage = null)
        }
    }

    fun pause() {
        scope.launch {
            val native = engine ?: return@launch
            val current = _state.value
            if (current.status == StudioSessionStatus.RECORDING) return@launch
            native.setPlaying(false)
            _state.value = current.copy(status = StudioSessionStatus.READY)
        }
    }

    fun seek(projectFrame: Long) {
        scope.launch {
            if (_state.value.status == StudioSessionStatus.RECORDING) return@launch
            val target = projectFrame.coerceIn(0L, max(_state.value.durationFrames, 0L))
            engine?.seek(target)
            _state.value = _state.value.copy(transportFrame = target)
        }
    }

    fun startRecording(
        mode: StudioInputMode = StudioInputMode.AUTO,
        preferredInputDeviceId: Int? = null,
    ) {
        scope.launch { startRecordingInternal(mode, preferredInputDeviceId) }
    }

    fun stopRecording() {
        scope.launch { stopRecordingInternal() }
    }

    fun selectTake(trackId: String, takeId: String) {
        scope.launch {
            val repo = repository ?: return@launch
            val projectId = _state.value.projectId ?: return@launch
            if (_state.value.status == StudioSessionStatus.RECORDING) return@launch
            runCatching { repo.selectActiveTake(projectId, trackId, takeId) }
                .onSuccess { project ->
                    _state.value = _state.value.copy(
                        project = project,
                        durationFrames = projectDurationFrames(project),
                        errorMessage = null,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(errorMessage = error.message ?: "Không thể chọn take")
                }
        }
    }

    fun clearError() {
        scope.launch { _state.value = _state.value.copy(errorMessage = null) }
    }

    fun closeProject() {
        scope.launch {
            if (_state.value.status == StudioSessionStatus.RECORDING) stopRecordingInternal()
            closeEngineInternal()
            _state.value = StudioSessionState()
        }
    }

    private suspend fun openInternal(context: Context, projectId: String) {
        if (_state.value.status == StudioSessionStatus.RECORDING) stopRecordingInternal()
        closeEngineInternal()
        appContext = context
        repository = StudioProjectRepository(context)
        waveformStore = StudioWaveformStore(context)
        val repo = requireNotNull(repository)
        val waves = requireNotNull(waveformStore)
        _state.value = StudioSessionState(
            projectId = projectId,
            status = StudioSessionStatus.LOADING,
            message = "Đang mở dự án và kiểm tra bản thu dở...",
        )

        val initialTakeCount = repo.load(projectId)?.tracks?.sumOf { it.takes.size } ?: 0
        runCatching {
            var project = requireNotNull(repo.recoverInterruptedTakes(projectId)) {
                "Không tìm thấy dự án Studio"
            }
            val recoveredTakeCount = (project.tracks.sumOf { it.takes.size } - initialTakeCount).coerceAtLeast(0)
            _state.value = _state.value.copy(
                project = project,
                recoveredTakeCount = recoveredTakeCount,
                message = "Đang chuẩn bị beat cho realtime engine...",
            )

            val prepared = StudioBeatPreparer(context, repo, waves).prepare(projectId)
            project = prepared.project
            val waveformMap = loadWaveforms(project, prepared.waveform)
            val native = StudioNativeAudio(context)
            requireSuccess(native.openOutput(), "Không thể mở output Studio")
            requireSuccess(
                native.loadBeat(
                    file = prepared.pcmFile,
                    sampleRate = project.timelineSampleRate,
                    channelCount = 2,
                ),
                "Không thể nạp beat vào Studio Audio Core",
            )
            requireSuccess(native.start(), "Không thể khởi động Studio Audio Core")
            engine = native
            val diagnostics = native.diagnostics()
            _state.value = StudioSessionState(
                projectId = project.id,
                project = project,
                status = StudioSessionStatus.READY,
                message = if (recoveredTakeCount > 0) "Đã khôi phục $recoveredTakeCount bản thu" else null,
                transportFrame = diagnostics?.transportFrame ?: 0L,
                durationFrames = projectDurationFrames(project),
                waveforms = waveformMap,
                diagnostics = diagnostics,
                recoveredTakeCount = recoveredTakeCount,
            )
            startPolling(native)
            DiagnosticLogger.info(
                component = TAG,
                event = "studio_session_ready",
                sessionId = project.id,
                fields = mapOf(
                    "beat_frames" to prepared.frameCount,
                    "recovered_takes" to recoveredTakeCount,
                    "output_sample_rate" to diagnostics?.sampleRate,
                    "audio_api" to diagnostics?.audioApiLabel,
                ),
            )
        }.onFailure { error ->
            closeEngineInternal()
            _state.value = StudioSessionState(
                projectId = projectId,
                status = StudioSessionStatus.ERROR,
                errorMessage = error.message ?: "Không thể mở Studio",
            )
            DiagnosticLogger.error(
                component = TAG,
                event = "studio_session_open_failed",
                sessionId = projectId,
                message = error.message,
                error = error,
            )
        }
    }

    private suspend fun startRecordingInternal(
        mode: StudioInputMode,
        preferredInputDeviceId: Int?,
    ) {
        val native = engine ?: return
        val repo = repository ?: return
        val context = appContext ?: return
        val current = _state.value
        val project = current.project ?: return
        if (!current.isPrepared || current.status == StudioSessionStatus.RECORDING) return

        val inputResult = native.prepareInput(preferredInputDeviceId, mode)
        if (inputResult !is StudioAudioOperationResult.Success) {
            _state.value = current.copy(errorMessage = operationError("Không thể mở microphone Studio", inputResult))
            return
        }
        val inputDiagnostics = native.diagnostics()
        val inputSampleRate = inputDiagnostics?.inputSampleRate ?: inputDiagnostics?.sampleRate
        if (inputSampleRate == null || inputSampleRate <= 0) {
            native.stopRecording()
            _state.value = current.copy(errorMessage = "Studio không xác định được sample rate microphone")
            return
        }
        val pending = runCatching {
            repo.beginTake(
                projectId = project.id,
                recordedTimelineFrame = inputDiagnostics.transportFrame,
                inputSampleRate = inputSampleRate,
                inputDeviceId = inputDiagnostics.inputDeviceId,
            )
        }.getOrElse { error ->
            native.stopRecording()
            _state.value = current.copy(errorMessage = error.message ?: "Không thể chuẩn bị Studio take")
            return
        }
        val target = repo.pendingTakeFile(pending)
        val serviceStarted = runCatching {
            StudioSessionService.start(context, project.name)
        }.isSuccess
        if (!serviceStarted) {
            native.stopRecording()
            repo.cancelTake(pending)
            _state.value = current.copy(errorMessage = "Không thể khởi động dịch vụ thu âm Studio")
            return
        }

        when (val result = native.startRecording(target)) {
            StudioAudioOperationResult.Success -> {
                pendingTake = pending
                native.setPlaying(true)
                _state.value = current.copy(
                    status = StudioSessionStatus.RECORDING,
                    errorMessage = null,
                    diagnostics = native.diagnostics(),
                )
                DiagnosticLogger.info(
                    component = TAG,
                    event = "studio_take_started",
                    sessionId = project.id,
                    fields = mapOf(
                        "take_id" to pending.takeId,
                        "timeline_frame" to pending.recordedTimelineFrame,
                        "input_sample_rate" to inputSampleRate,
                        "input_device_id" to pending.inputDeviceId,
                        "input_mode" to mode.name,
                    ),
                )
            }
            else -> {
                native.stopRecording()
                repo.cancelTake(pending)
                StudioSessionService.stop(context)
                _state.value = current.copy(errorMessage = operationError("Không thể bắt đầu Studio take", result))
            }
        }
    }

    private suspend fun stopRecordingInternal() {
        val native = engine ?: return
        val repo = repository ?: return
        val context = appContext
        val pending = pendingTake
        if (_state.value.status != StudioSessionStatus.RECORDING && pending == null) return

        native.setPlaying(false)
        val stopResult = native.stopRecording()
        var project = _state.value.project
        var errorMessage: String? = null
        if (pending != null) {
            runCatching { repo.finalizeTake(pending) }
                .onSuccess { finalized -> project = finalized }
                .onFailure { error ->
                    // Keep the journal on disk. Opening the project again retries recovery.
                    errorMessage = error.message ?: "Không thể hoàn tất Studio take"
                }
        }
        pendingTake = null
        context?.let(StudioSessionService::stop)

        val refreshedProject = project ?: _state.value.project
        val refreshedWaves = if (refreshedProject != null) {
            loadWaveforms(refreshedProject, null)
        } else {
            _state.value.waveforms
        }
        if (stopResult is StudioAudioOperationResult.Error && errorMessage == null) {
            errorMessage = "Audio writer báo lỗi ${stopResult.nativeCode}; bản thu đã được giữ để recovery kiểm tra"
        }
        _state.value = _state.value.copy(
            project = refreshedProject,
            status = StudioSessionStatus.READY,
            durationFrames = refreshedProject?.let(::projectDurationFrames) ?: _state.value.durationFrames,
            waveforms = refreshedWaves,
            diagnostics = native.diagnostics(),
            errorMessage = errorMessage,
        )
        DiagnosticLogger.info(
            component = TAG,
            event = "studio_take_stopped",
            sessionId = refreshedProject?.id,
            fields = mapOf(
                "take_id" to pending?.takeId,
                "native_stop" to stopResult.javaClass.simpleName,
                "finalized" to (errorMessage == null),
            ),
        )
    }

    private fun startPolling(native: StudioNativeAudio) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (engine === native) {
                delay(40L)
                val diagnostics = native.diagnostics() ?: continue
                val current = _state.value
                if (current.status == StudioSessionStatus.RECORDING &&
                    (!diagnostics.isRecording || diagnostics.writerErrorCode != 0)
                ) {
                    stopRecordingInternal()
                    continue
                }
                val status = when {
                    current.status == StudioSessionStatus.PLAYING && !diagnostics.isPlaying -> StudioSessionStatus.READY
                    else -> current.status
                }
                _state.value = current.copy(
                    status = status,
                    transportFrame = diagnostics.transportFrame,
                    durationFrames = max(current.durationFrames, diagnostics.transportFrame),
                    diagnostics = diagnostics,
                )
            }
        }
    }

    private fun loadWaveforms(
        project: StudioProject,
        preparedBeat: StudioWaveform?,
    ): Map<String, StudioWaveform> {
        val repo = repository ?: return emptyMap()
        val waves = waveformStore ?: return emptyMap()
        val result = LinkedHashMap<String, StudioWaveform>()
        project.beatAssetId?.let { beatId ->
            (preparedBeat ?: waves.load(project.id, beatId))?.let { result[beatId] = it }
        }
        project.assets
            .filter { it.kind == StudioAssetKind.TAKE || it.kind == StudioAssetKind.DERIVED }
            .forEach { asset ->
                val waveform = waves.load(project.id, asset.id) ?: repo.assetFile(project.id, asset.id)
                    ?.let { waves.ensureForCanonicalWav(project.id, asset, it) }
                if (waveform != null) result[asset.id] = waveform
            }
        return result
    }

    private suspend fun closeEngineInternal() {
        pollJob?.let { job ->
            if (job != kotlinx.coroutines.currentCoroutineContext()[Job]) {
                job.cancelAndJoin()
            } else {
                job.cancel()
            }
        }
        pollJob = null
        engine?.close()
        engine = null
        pendingTake = null
    }

    private fun projectDurationFrames(project: StudioProject): Long {
        var duration = project.beatAsset()?.let { asset ->
            sourceFramesToTimeline(asset.durationFrames ?: 0L, asset.sampleRate ?: project.timelineSampleRate, project.timelineSampleRate)
        } ?: 0L
        project.tracks.forEach { track ->
            track.takes.forEach { take ->
                val start = (take.recordedTimelineFrame - take.latencyCompensationFrames).coerceAtLeast(0L)
                val length = sourceFramesToTimeline(take.recordedFrames, take.inputSampleRate, project.timelineSampleRate)
                duration = max(duration, start + length)
            }
            track.clips.forEach { clip ->
                val asset = project.asset(clip.sourceAssetId) ?: return@forEach
                val rate = asset.sampleRate ?: project.timelineSampleRate
                val length = sourceFramesToTimeline(
                    (clip.sourceEndFrame - clip.sourceStartFrame).coerceAtLeast(0L),
                    rate,
                    project.timelineSampleRate,
                )
                duration = max(duration, clip.timelineStartFrame + length)
            }
        }
        return duration.coerceAtLeast(project.timelineSampleRate.toLong())
    }

    private fun sourceFramesToTimeline(sourceFrames: Long, sourceRate: Int, timelineRate: Int): Long {
        if (sourceFrames <= 0L || sourceRate <= 0 || timelineRate <= 0) return 0L
        return ((sourceFrames.toDouble() * timelineRate.toDouble()) / sourceRate.toDouble()).toLong()
    }

    private fun requireSuccess(result: StudioAudioOperationResult, prefix: String) {
        if (result !is StudioAudioOperationResult.Success) error(operationError(prefix, result))
    }

    private fun operationError(prefix: String, result: StudioAudioOperationResult): String = when (result) {
        StudioAudioOperationResult.Success -> prefix
        StudioAudioOperationResult.Released -> "$prefix: engine đã được giải phóng"
        is StudioAudioOperationResult.Error -> "$prefix (mã ${result.nativeCode})"
    }
}
