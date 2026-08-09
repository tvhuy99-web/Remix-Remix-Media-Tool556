package com.aistudio.mediatool.feature.studio.audio

import android.content.Context
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.feature.studio.data.PendingStudioTake
import com.aistudio.mediatool.feature.studio.data.StudioEditEngine
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.data.StudioWaveform
import com.aistudio.mediatool.feature.studio.data.StudioWaveformStore
import com.aistudio.mediatool.feature.studio.data.selectActiveTake
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToLong

enum class StudioSessionStatus {
    CLOSED,
    LOADING,
    READY,
    PLAYING,
    RECORDING,
    ERROR,
}

enum class StudioRecordingKind {
    FULL_TAKE,
    PUNCH,
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
    val selectedClipId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val punchStartFrame: Long? = null,
    val punchEndFrame: Long? = null,
    val recordingKind: StudioRecordingKind? = null,
) {
    val isPrepared: Boolean
        get() = status == StudioSessionStatus.READY ||
            status == StudioSessionStatus.PLAYING ||
            status == StudioSessionStatus.RECORDING

    val hasPunchRange: Boolean
        get() = punchStartFrame != null && punchEndFrame != null && punchEndFrame > punchStartFrame
}

private data class PendingPunch(
    val startFrame: Long,
    val endFrame: Long,
    val recordStartFrame: Long,
    val trackId: String,
)

/** Process-scoped Studio session, edit history and realtime transport owner. */
object StudioSessionRuntime {
    private const val TAG = "StudioSession"
    private const val MAX_EDIT_HISTORY = 50
    private const val DEFAULT_PUNCH_PREROLL_SECONDS = 3L

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
    private var pendingPunch: PendingPunch? = null
    private val undoStack = ArrayDeque<StudioProject>()
    private val redoStack = ArrayDeque<StudioProject>()

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
        scope.launch { startRecordingInternal(mode, preferredInputDeviceId, punch = null) }
    }

    fun startPunchRecording(
        mode: StudioInputMode = StudioInputMode.AUTO,
        preferredInputDeviceId: Int? = null,
    ) {
        scope.launch {
            val current = _state.value
            val project = current.project ?: return@launch
            val start = current.punchStartFrame
            val end = current.punchEndFrame
            if (start == null || end == null || end <= start) {
                _state.value = current.copy(errorMessage = "Hãy đặt điểm Punch In và Punch Out trước")
                return@launch
            }
            val vocalTrack = project.tracks.firstOrNull { it.type == StudioTrackType.VOCAL }
            if (vocalTrack == null || vocalTrack.takes.isEmpty()) {
                _state.value = current.copy(errorMessage = "Cần ít nhất một vocal take trước khi Punch")
                return@launch
            }
            val preRoll = DEFAULT_PUNCH_PREROLL_SECONDS * project.timelineSampleRate.toLong()
            val punch = PendingPunch(
                startFrame = start,
                endFrame = end,
                recordStartFrame = (start - preRoll).coerceAtLeast(0L),
                trackId = vocalTrack.id,
            )
            startRecordingInternal(mode, preferredInputDeviceId, punch)
        }
    }

    fun stopRecording() {
        scope.launch { stopRecordingInternal() }
    }

    fun setPunchStartAtPlayhead() {
        scope.launch {
            if (_state.value.status == StudioSessionStatus.RECORDING) return@launch
            val frame = _state.value.transportFrame
            val end = _state.value.punchEndFrame?.takeIf { it > frame }
            _state.value = _state.value.copy(punchStartFrame = frame, punchEndFrame = end)
        }
    }

    fun setPunchEndAtPlayhead() {
        scope.launch {
            if (_state.value.status == StudioSessionStatus.RECORDING) return@launch
            val frame = _state.value.transportFrame
            val start = _state.value.punchStartFrame
            if (start == null || frame <= start) {
                _state.value = _state.value.copy(errorMessage = "Punch Out phải nằm sau Punch In")
            } else {
                _state.value = _state.value.copy(punchEndFrame = frame, errorMessage = null)
            }
        }
    }

    fun clearPunchRange() {
        scope.launch {
            if (_state.value.status == StudioSessionStatus.RECORDING) return@launch
            _state.value = _state.value.copy(punchStartFrame = null, punchEndFrame = null)
        }
    }

    fun selectClip(clipId: String?) {
        scope.launch {
            if (_state.value.status != StudioSessionStatus.RECORDING) {
                _state.value = _state.value.copy(selectedClipId = clipId)
            }
        }
    }

    fun beginEditing(trackId: String) {
        scope.launch {
            applyEdit("materialize_arrangement") { project -> StudioEditEngine.materializeTrack(project, trackId) }
        }
    }

    fun splitSelectedAtPlayhead() {
        scope.launch {
            val clipId = _state.value.selectedClipId ?: return@launch
            val frame = _state.value.transportFrame
            applyEdit("split") { project -> StudioEditEngine.split(project, clipId, frame) }
        }
    }

    fun trimSelectedStartToPlayhead() {
        scope.launch {
            val clipId = _state.value.selectedClipId ?: return@launch
            val frame = _state.value.transportFrame
            applyEdit("trim_start") { project -> StudioEditEngine.trimStart(project, clipId, frame) }
        }
    }

    fun trimSelectedEndToPlayhead() {
        scope.launch {
            val clipId = _state.value.selectedClipId ?: return@launch
            val frame = _state.value.transportFrame
            applyEdit("trim_end") { project -> StudioEditEngine.trimEnd(project, clipId, frame) }
        }
    }

    fun moveSelectedByMillis(milliseconds: Long) {
        scope.launch {
            val current = _state.value
            val clipId = current.selectedClipId ?: return@launch
            val project = current.project ?: return@launch
            val deltaFrames = milliseconds * project.timelineSampleRate.toLong() / 1_000L
            applyEdit("move") { StudioEditEngine.move(it, clipId, deltaFrames) }
        }
    }

    fun deleteSelectedClip() {
        scope.launch {
            val clipId = _state.value.selectedClipId ?: return@launch
            applyEdit("delete") { StudioEditEngine.delete(it, clipId) }
        }
    }

    fun adjustSelectedGain(deltaDb: Float) {
        scope.launch {
            val current = _state.value
            val clipId = current.selectedClipId ?: return@launch
            val clip = current.project?.tracks?.asSequence()?.flatMap { it.clips.asSequence() }?.firstOrNull { it.id == clipId }
                ?: return@launch
            applyEdit("gain") { StudioEditEngine.setGain(it, clipId, clip.gainDb + deltaDb) }
        }
    }

    fun adjustSelectedFadeIn(millisecondsDelta: Long) {
        scope.launch { adjustSelectedFade(millisecondsDelta, isFadeIn = true) }
    }

    fun adjustSelectedFadeOut(millisecondsDelta: Long) {
        scope.launch { adjustSelectedFade(millisecondsDelta, isFadeIn = false) }
    }

    fun undo() {
        scope.launch {
            if (_state.value.status == StudioSessionStatus.RECORDING || undoStack.isEmpty()) return@launch
            val repo = repository ?: return@launch
            val current = _state.value.project ?: return@launch
            val previous = undoStack.removeLast()
            pushBounded(redoStack, current)
            val saved = repo.save(previous)
            syncArrangement(saved)
            _state.value = _state.value.copy(
                project = saved,
                selectedClipId = _state.value.selectedClipId?.takeIf { id -> saved.hasClip(id) },
                durationFrames = projectDurationFrames(saved),
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                errorMessage = null,
            )
        }
    }

    fun redo() {
        scope.launch {
            if (_state.value.status == StudioSessionStatus.RECORDING || redoStack.isEmpty()) return@launch
            val repo = repository ?: return@launch
            val current = _state.value.project ?: return@launch
            val next = redoStack.removeLast()
            pushBounded(undoStack, current)
            val saved = repo.save(next)
            syncArrangement(saved)
            _state.value = _state.value.copy(
                project = saved,
                selectedClipId = _state.value.selectedClipId?.takeIf { id -> saved.hasClip(id) },
                durationFrames = projectDurationFrames(saved),
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                errorMessage = null,
            )
        }
    }

    fun selectTake(trackId: String, takeId: String) {
        scope.launch {
            val repo = repository ?: return@launch
            val projectId = _state.value.projectId ?: return@launch
            if (_state.value.status == StudioSessionStatus.RECORDING) return@launch
            runCatching { repo.selectActiveTake(projectId, trackId, takeId) }
                .onSuccess { project ->
                    syncArrangement(project)
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
            undoStack.clear()
            redoStack.clear()
            _state.value = StudioSessionState()
        }
    }

    private suspend fun openInternal(context: Context, projectId: String) {
        if (_state.value.status == StudioSessionStatus.RECORDING) stopRecordingInternal()
        closeEngineInternal()
        undoStack.clear()
        redoStack.clear()
        pendingPunch = null
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
        try {
            var project = requireNotNull(repo.recoverInterruptedTakes(projectId)) {
                "Không tìm thấy dự án Studio"
            }
            val recoveredTakeCount = (project.tracks.sumOf { it.takes.size } - initialTakeCount).coerceAtLeast(0)
            _state.value = _state.value.copy(
                project = project,
                recoveredTakeCount = recoveredTakeCount,
                message = "Đang chuẩn bị beat và arrangement...",
            )

            val prepared = StudioBeatPreparer(context, repo, waves).prepare(projectId)
            project = prepared.project
            val waveformMap = loadWaveforms(project, prepared.waveform)
            val native = StudioNativeAudio(context)
            try {
                requireSuccess(native.openOutput(), "Không thể mở output Studio")
                requireSuccess(
                    native.loadBeat(
                        file = prepared.pcmFile,
                        sampleRate = project.timelineSampleRate,
                        channelCount = 2,
                    ),
                    "Không thể nạp beat vào Studio Audio Core",
                )
                requireSuccess(
                    native.setArrangement(StudioPlaybackPlanner.build(project, repo), project.timelineSampleRate),
                    "Không thể nạp vocal arrangement",
                )
                requireSuccess(native.start(), "Không thể khởi động Studio Audio Core")
            } catch (error: Throwable) {
                native.close()
                throw error
            }
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
                    "monitor_clips" to diagnostics?.arrangementClipCount,
                    "output_sample_rate" to diagnostics?.sampleRate,
                    "audio_api" to diagnostics?.audioApiLabel,
                ),
            )
        } catch (error: Throwable) {
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

    private fun startRecordingInternal(
        mode: StudioInputMode,
        preferredInputDeviceId: Int?,
        punch: PendingPunch?,
    ) {
        val native = engine ?: return
        val repo = repository ?: return
        val context = appContext ?: return
        val current = _state.value
        val project = current.project ?: return
        if (!current.isPrepared || current.status == StudioSessionStatus.RECORDING) return

        val wasPlaying = current.status == StudioSessionStatus.PLAYING
        native.setPlaying(false)
        val recordStart = punch?.recordStartFrame ?: current.transportFrame
        native.seek(recordStart)
        native.setPunchMuteWindow(punch?.startFrame, punch?.endFrame)
        _state.value = current.copy(
            status = StudioSessionStatus.READY,
            transportFrame = recordStart,
            message = if (punch != null) "Đang chuẩn bị Punch..." else "Đang chuẩn bị microphone...",
            errorMessage = null,
        )

        fun failRecordingSetup(message: String) {
            native.setPunchMuteWindow(null, null)
            if (wasPlaying && punch == null) native.setPlaying(true)
            _state.value = current.copy(
                status = if (wasPlaying && punch == null) StudioSessionStatus.PLAYING else StudioSessionStatus.READY,
                message = null,
                errorMessage = message,
                diagnostics = native.diagnostics(),
            )
        }

        val inputResult = native.prepareInput(preferredInputDeviceId, mode)
        if (inputResult !is StudioAudioOperationResult.Success) {
            failRecordingSetup(operationError("Không thể mở microphone Studio", inputResult))
            return
        }
        val inputDiagnostics = native.diagnostics()
        if (inputDiagnostics == null) {
            native.stopRecording()
            failRecordingSetup("Studio không đọc được cấu hình microphone")
            return
        }
        val inputSampleRate = inputDiagnostics.inputSampleRate ?: inputDiagnostics.sampleRate
        if (inputSampleRate <= 0) {
            native.stopRecording()
            failRecordingSetup("Studio không xác định được sample rate microphone")
            return
        }
        val pending = runCatching {
            repo.beginTake(
                projectId = project.id,
                recordedTimelineFrame = recordStart,
                inputSampleRate = inputSampleRate,
                inputDeviceId = inputDiagnostics.inputDeviceId,
            )
        }.getOrElse { error ->
            native.stopRecording()
            failRecordingSetup(error.message ?: "Không thể chuẩn bị Studio take")
            return
        }
        val target = repo.pendingTakeFile(pending)
        val serviceStarted = runCatching { StudioSessionService.start(context, project.name) }.isSuccess
        if (!serviceStarted) {
            native.stopRecording()
            repo.cancelTake(pending)
            failRecordingSetup("Không thể khởi động dịch vụ thu âm Studio")
            return
        }

        when (val result = native.startRecording(target)) {
            StudioAudioOperationResult.Success -> {
                pendingTake = pending
                pendingPunch = punch?.copy(trackId = pending.trackId)
                _state.value = _state.value.copy(
                    status = StudioSessionStatus.RECORDING,
                    recordingKind = if (punch != null) StudioRecordingKind.PUNCH else StudioRecordingKind.FULL_TAKE,
                    message = null,
                    errorMessage = null,
                    transportFrame = recordStart,
                    diagnostics = native.diagnostics(),
                )
                DiagnosticLogger.info(
                    component = TAG,
                    event = if (punch != null) "studio_punch_started" else "studio_take_started",
                    sessionId = project.id,
                    fields = mapOf(
                        "take_id" to pending.takeId,
                        "timeline_frame" to recordStart,
                        "punch_start" to punch?.startFrame,
                        "punch_end" to punch?.endFrame,
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
                failRecordingSetup(operationError("Không thể bắt đầu Studio take", result))
            }
        }
    }

    private fun stopRecordingInternal() {
        val native = engine ?: return
        val repo = repository ?: return
        val context = appContext
        val pending = pendingTake
        val punch = pendingPunch
        if (_state.value.status != StudioSessionStatus.RECORDING && pending == null) return

        native.setPlaying(false)
        val stopResult = native.stopRecording()
        native.setPunchMuteWindow(null, null)
        var project = _state.value.project
        var selectedClipId = _state.value.selectedClipId
        var errorMessage: String? = null
        if (pending != null) {
            runCatching {
                val currentTrack = project?.tracks?.firstOrNull { it.id == pending.trackId }
                val keepArrangement = punch != null || currentTrack?.clips?.isNotEmpty() == true
                var finalized = repo.finalizeTake(pending, activateTake = !keepArrangement)
                if (punch != null) {
                    val edit = StudioEditEngine.replacePunchRange(
                        project = finalized,
                        trackId = punch.trackId,
                        newTakeId = pending.takeId,
                        punchStart = punch.startFrame,
                        punchEnd = punch.endFrame,
                        recordedTakeStart = punch.recordStartFrame,
                    )
                    pushBounded(undoStack, finalized)
                    redoStack.clear()
                    finalized = repo.save(edit.project)
                    selectedClipId = edit.selectedClipId
                }
                finalized
            }.onSuccess { finalized -> project = finalized }
                .onFailure { error ->
                    errorMessage = error.message ?: "Không thể hoàn tất Studio take"
                }
        }
        pendingTake = null
        pendingPunch = null
        context?.let { StudioSessionService.stop(it) }

        val refreshedProject = project ?: _state.value.project
        if (refreshedProject != null && errorMessage == null) {
            runCatching { syncArrangement(refreshedProject) }
                .onFailure { error -> errorMessage = error.message ?: "Không thể cập nhật monitor arrangement" }
        }
        val refreshedWaves = if (refreshedProject != null) loadWaveforms(refreshedProject, null) else _state.value.waveforms
        if (stopResult is StudioAudioOperationResult.Error && errorMessage == null) {
            errorMessage = "Audio writer báo lỗi ${stopResult.nativeCode}; bản thu đã được giữ để recovery kiểm tra"
        }
        _state.value = _state.value.copy(
            project = refreshedProject,
            status = StudioSessionStatus.READY,
            recordingKind = null,
            message = null,
            selectedClipId = selectedClipId,
            durationFrames = refreshedProject?.let(::projectDurationFrames) ?: _state.value.durationFrames,
            waveforms = refreshedWaves,
            diagnostics = native.diagnostics(),
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            errorMessage = errorMessage,
        )
        DiagnosticLogger.info(
            component = TAG,
            event = if (punch != null) "studio_punch_stopped" else "studio_take_stopped",
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
                val punch = pendingPunch
                if (current.status == StudioSessionStatus.RECORDING && punch != null &&
                    diagnostics.transportFrame >= punch.endFrame
                ) {
                    stopRecordingInternal()
                    continue
                }
                if (current.status == StudioSessionStatus.RECORDING &&
                    (!diagnostics.isRecording || diagnostics.writerErrorCode != 0)
                ) {
                    stopRecordingInternal()
                    continue
                }
                val status = if (
                    current.status == StudioSessionStatus.PLAYING && !diagnostics.isPlaying
                ) StudioSessionStatus.READY else current.status
                _state.value = current.copy(
                    status = status,
                    transportFrame = diagnostics.transportFrame,
                    durationFrames = max(current.durationFrames, max(diagnostics.transportFrame, diagnostics.arrangementDurationFrames)),
                    diagnostics = diagnostics,
                )
            }
        }
    }

    private fun applyEdit(
        label: String,
        transform: (StudioProject) -> StudioEditEngine.EditResult,
    ) {
        val current = _state.value
        if (current.status == StudioSessionStatus.RECORDING) return
        val repo = repository ?: return
        val project = current.project ?: return
        runCatching {
            val edit = transform(project)
            if (edit.project == project) return@runCatching edit
            pushBounded(undoStack, project)
            redoStack.clear()
            val saved = repo.save(edit.project)
            syncArrangement(saved)
            edit.copy(project = saved)
        }.onSuccess { edit ->
            _state.value = _state.value.copy(
                project = edit.project,
                selectedClipId = edit.selectedClipId,
                durationFrames = projectDurationFrames(edit.project),
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                errorMessage = null,
            )
            DiagnosticLogger.info(
                component = TAG,
                event = "studio_edit",
                sessionId = edit.project.id,
                fields = mapOf("command" to label, "selected_clip" to edit.selectedClipId),
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(errorMessage = error.message ?: "Không thể chỉnh sửa clip")
        }
    }

    private fun adjustSelectedFade(millisecondsDelta: Long, isFadeIn: Boolean) {
        val current = _state.value
        val clipId = current.selectedClipId ?: return
        val project = current.project ?: return
        val clip = project.tracks.asSequence().flatMap { it.clips.asSequence() }.firstOrNull { it.id == clipId } ?: return
        val asset = project.asset(clip.sourceAssetId) ?: return
        val sourceRate = asset.sampleRate ?: project.timelineSampleRate
        val deltaFrames = millisecondsDelta * sourceRate.toLong() / 1_000L
        val fadeIn = if (isFadeIn) (clip.fadeInFrames + deltaFrames).coerceAtLeast(0L) else clip.fadeInFrames
        val fadeOut = if (isFadeIn) clip.fadeOutFrames else (clip.fadeOutFrames + deltaFrames).coerceAtLeast(0L)
        applyEdit(if (isFadeIn) "fade_in" else "fade_out") {
            StudioEditEngine.setFades(it, clipId, fadeIn, fadeOut)
        }
    }

    private fun syncArrangement(project: StudioProject) {
        val native = engine ?: return
        val repo = repository ?: return
        requireSuccess(
            native.setArrangement(StudioPlaybackPlanner.build(project, repo), project.timelineSampleRate),
            "Không thể cập nhật vocal arrangement",
        )
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

    private fun closeEngineInternal() {
        pollJob?.cancel()
        pollJob = null
        engine?.close()
        engine = null
        pendingTake = null
        pendingPunch = null
    }

    private fun projectDurationFrames(project: StudioProject): Long {
        var duration = project.beatAsset()?.let { asset ->
            sourceFramesToTimeline(
                asset.durationFrames ?: 0L,
                asset.sampleRate ?: project.timelineSampleRate,
                project.timelineSampleRate,
            )
        } ?: 0L
        for (track in project.tracks) {
            for (take in track.takes) {
                val start = (take.recordedTimelineFrame - take.latencyCompensationFrames).coerceAtLeast(0L)
                val length = sourceFramesToTimeline(take.recordedFrames, take.inputSampleRate, project.timelineSampleRate)
                duration = max(duration, start + length)
            }
            for (clip in track.clips) {
                duration = max(duration, StudioEditEngine.timelineEnd(project, clip))
            }
        }
        return duration.coerceAtLeast(project.timelineSampleRate.toLong())
    }

    private fun sourceFramesToTimeline(sourceFrames: Long, sourceRate: Int, timelineRate: Int): Long {
        if (sourceFrames <= 0L || sourceRate <= 0 || timelineRate <= 0) return 0L
        return (sourceFrames.toDouble() * timelineRate.toDouble() / sourceRate.toDouble()).roundToLong()
    }

    private fun requireSuccess(result: StudioAudioOperationResult, prefix: String) {
        if (result !is StudioAudioOperationResult.Success) error(operationError(prefix, result))
    }

    private fun operationError(prefix: String, result: StudioAudioOperationResult): String = when (result) {
        StudioAudioOperationResult.Success -> prefix
        StudioAudioOperationResult.Released -> "$prefix: engine đã được giải phóng"
        is StudioAudioOperationResult.Error -> "$prefix (mã ${result.nativeCode})"
    }

    private fun pushBounded(stack: ArrayDeque<StudioProject>, project: StudioProject) {
        stack.addLast(project)
        while (stack.size > MAX_EDIT_HISTORY) stack.removeFirst()
    }

    private fun StudioProject.hasClip(clipId: String): Boolean = tracks.any { track -> track.clips.any { it.id == clipId } }
}
