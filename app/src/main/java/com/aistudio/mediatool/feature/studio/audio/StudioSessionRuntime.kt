package com.aistudio.mediatool.feature.studio.audio

import android.content.Context
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.feature.studio.data.PendingStudioTake
import com.aistudio.mediatool.feature.studio.data.StudioEditEngine
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.data.StudioTrackEditor
import com.aistudio.mediatool.feature.studio.data.StudioWaveform
import com.aistudio.mediatool.feature.studio.data.StudioWaveformStore
import com.aistudio.mediatool.feature.studio.data.selectActiveTake
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTakeStatus
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import com.aistudio.mediatool.feature.studio.domain.latencyCompensatedPlacement
import com.aistudio.mediatool.feature.studio.render.StudioExportFormat
import com.aistudio.mediatool.feature.studio.render.StudioRenderEngine
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StudioSessionStatus {
    CLOSED,
    LOADING,
    READY,
    PLAYING,
    RECORDING,
    CALIBRATING,
    RENDERING,
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
    val audioDevices: StudioAudioDeviceSnapshot = StudioAudioDeviceSnapshot(),
    val selectedInputDeviceId: Int? = null,
    val selectedOutputDeviceId: Int? = null,
    val inputMode: StudioInputMode = StudioInputMode.AUTO,
    val latencyProfile: StudioLatencyProfile? = null,
    val exportResultPath: String? = null,
    val exportResultLabel: String? = null,
) {
    val isPrepared: Boolean
        get() = status == StudioSessionStatus.READY ||
            status == StudioSessionStatus.PLAYING ||
            status == StudioSessionStatus.RECORDING

    val hasPunchRange: Boolean
        get() = punchStartFrame != null && punchEndFrame != null && punchEndFrame > punchStartFrame

    val isBusy: Boolean
        get() = status == StudioSessionStatus.CALIBRATING || status == StudioSessionStatus.RENDERING
}

private data class PendingPunch(
    val startFrame: Long,
    val endFrame: Long,
    val recordStartFrame: Long,
    val trackId: String,
)

/** Process-scoped Studio session, edit history, routing, latency and realtime transport owner. */
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
    private var latencyStore: StudioLatencyStore? = null
    private var renderEngine: StudioRenderEngine? = null
    private var deviceManager: StudioAudioDeviceManager? = null
    private var audioFocusManager: StudioAudioFocusManager? = null
    private var engine: StudioNativeAudio? = null
    private var openJob: Job? = null
    private var pollJob: Job? = null
    private var operationJob: Job? = null
    private var pendingTake: PendingStudioTake? = null
    private var pendingPunch: PendingPunch? = null
    private var uiVisible = true
    private var outputSuspendedForBackground = false
    private val undoStack = ArrayDeque<StudioProject>()
    private val redoStack = ArrayDeque<StudioProject>()

    fun open(context: Context, projectId: String) {
        val applicationContext = context.applicationContext
        if (_state.value.projectId == projectId && _state.value.isPrepared && engine != null) return
        openJob?.cancel()
        if (_state.value.projectId != projectId) {
            operationJob?.cancel()
            operationJob = null
        }
        openJob = scope.launch { openInternal(applicationContext, projectId) }
    }

    fun setUiVisible(visible: Boolean) {
        scope.launch {
            uiVisible = visible
            val native = engine ?: return@launch
            val current = _state.value
            if (!visible) {
                if (current.status == StudioSessionStatus.RECORDING) return@launch
                if (current.status == StudioSessionStatus.CALIBRATING || current.status == StudioSessionStatus.RENDERING) {
                    operationJob?.cancel()
                    operationJob = null
                }
                native.setPlaying(false)
                audioFocusManager?.abandon()
                if (!outputSuspendedForBackground) {
                    native.stop()
                    outputSuspendedForBackground = true
                }
                _state.value = current.copy(
                    status = if (current.status == StudioSessionStatus.PLAYING) StudioSessionStatus.READY else current.status,
                    message = if (current.status == StudioSessionStatus.PLAYING) "Playback đã dừng khi Studio đi nền" else current.message,
                )
            } else if (outputSuspendedForBackground && current.project != null && current.status != StudioSessionStatus.CLOSED) {
                if (!resumeOutputIfNeeded(native)) {
                    _state.value = _state.value.copy(errorMessage = "Studio phải mở lại audio route sau khi trở về foreground")
                }
            }
        }
    }

    fun play() {
        scope.launch {
            val native = engine ?: return@launch
            val current = _state.value
            if (!current.isPrepared || current.status == StudioSessionStatus.RECORDING) return@launch
            if (!uiVisible) {
                _state.value = current.copy(errorMessage = "Mở Studio ở foreground để phát audio")
                return@launch
            }
            if (!resumeOutputIfNeeded(native)) return@launch
            if (audioFocusManager?.requestPlayback() != true) {
                _state.value = _state.value.copy(errorMessage = "Ứng dụng khác đang giữ audio focus")
                return@launch
            }
            native.setPlaying(true)
            _state.value = _state.value.copy(status = StudioSessionStatus.PLAYING, errorMessage = null)
        }
    }

    fun pause() {
        scope.launch {
            val native = engine ?: return@launch
            val current = _state.value
            if (current.status == StudioSessionStatus.RECORDING || current.isBusy) return@launch
            native.setPlaying(false)
            audioFocusManager?.abandon()
            _state.value = current.copy(status = StudioSessionStatus.READY)
        }
    }

    fun seek(projectFrame: Long) {
        scope.launch {
            val current = _state.value
            if (current.status == StudioSessionStatus.RECORDING || current.isBusy) return@launch
            val target = projectFrame.coerceIn(0L, max(current.durationFrames, 0L))
            engine?.seek(target)
            _state.value = current.copy(transportFrame = target)
        }
    }

    fun setInputMode(mode: StudioInputMode) {
        scope.launch {
            if (_state.value.status == StudioSessionStatus.RECORDING || _state.value.isBusy) return@launch
            _state.value = _state.value.copy(inputMode = mode, latencyProfile = profileForCurrentRoute(mode))
        }
    }

    fun selectInputDevice(deviceId: Int?) {
        scope.launch {
            if (_state.value.status == StudioSessionStatus.RECORDING || _state.value.isBusy) return@launch
            val validId = deviceId?.takeIf { id -> _state.value.audioDevices.inputs.any { it.id == id } }
            _state.value = _state.value.copy(
                selectedInputDeviceId = validId,
                latencyProfile = profileForCurrentRoute(_state.value.inputMode, inputIdOverride = validId),
                errorMessage = null,
            )
        }
    }

    fun selectOutputDevice(deviceId: Int?) {
        scope.launch {
            val current = _state.value
            if (current.status == StudioSessionStatus.RECORDING || current.isBusy) return@launch
            val validId = deviceId?.takeIf { id -> current.audioDevices.outputs.any { it.id == id } }
            switchOutputRouteInternal(validId)
        }
    }

    fun calibrateLatency(mode: StudioInputMode = _state.value.inputMode) {
        if (_state.value.status == StudioSessionStatus.RECORDING || _state.value.isBusy || !uiVisible) return
        operationJob?.cancel()
        operationJob = scope.launch { calibrateLatencyInternal(mode) }
    }

    fun adjustLatencyManual(deltaMilliseconds: Double) {
        scope.launch {
            val current = _state.value
            if (current.status == StudioSessionStatus.RECORDING || current.isBusy) return@launch
            val store = latencyStore ?: return@launch
            val diagnostics = current.diagnostics ?: return@launch
            val route = latencyRoute(
                inputDeviceId = current.selectedInputDeviceId ?: diagnostics.inputDeviceId,
                outputDeviceId = current.selectedOutputDeviceId ?: diagnostics.outputDeviceId.takeIf { it >= 0 },
                mode = current.inputMode,
                sampleRate = diagnostics.sampleRate,
            )
            val profile = store.adjustManual(route, deltaMilliseconds)
            _state.value = current.copy(
                latencyProfile = profile,
                message = "Độ trễ đã chỉnh: ${formatLatency(profile.milliseconds)} ms",
                errorMessage = null,
            )
        }
    }

    fun resetLatencyProfile() {
        scope.launch {
            val current = _state.value
            if (current.status == StudioSessionStatus.RECORDING || current.isBusy) return@launch
            val store = latencyStore ?: return@launch
            val diagnostics = current.diagnostics ?: return@launch
            val route = latencyRoute(
                inputDeviceId = current.selectedInputDeviceId ?: diagnostics.inputDeviceId,
                outputDeviceId = current.selectedOutputDeviceId ?: diagnostics.outputDeviceId.takeIf { it >= 0 },
                mode = current.inputMode,
                sampleRate = diagnostics.sampleRate,
            )
            store.reset(route)
            _state.value = current.copy(latencyProfile = null, message = "Đã xóa profile latency của route này")
        }
    }

    fun startRecording(
        mode: StudioInputMode = _state.value.inputMode,
        preferredInputDeviceId: Int? = null,
    ) {
        scope.launch {
            startRecordingInternal(
                mode,
                preferredInputDeviceId ?: _state.value.selectedInputDeviceId,
                punch = null,
            )
        }
    }

    fun startPunchRecording(
        mode: StudioInputMode = _state.value.inputMode,
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
            val editableVoiceTrack = project.tracks.firstOrNull { track ->
                track.type != StudioTrackType.BEAT &&
                    (track.takes.isNotEmpty() || track.clips.isNotEmpty())
            }
            if (editableVoiceTrack == null) {
                _state.value = current.copy(errorMessage = "Cần ít nhất một lớp giọng trước khi thu sửa đoạn")
                return@launch
            }
            val preRoll = DEFAULT_PUNCH_PREROLL_SECONDS * project.timelineSampleRate.toLong()
            val punch = PendingPunch(
                startFrame = start,
                endFrame = end,
                recordStartFrame = (start - preRoll).coerceAtLeast(0L),
                trackId = editableVoiceTrack.id,
            )
            startRecordingInternal(
                mode,
                preferredInputDeviceId ?: current.selectedInputDeviceId,
                punch,
            )
        }
    }

    fun stopRecording() {
        scope.launch { stopRecordingInternal() }
    }

    fun setPunchStartAtPlayhead() {
        scope.launch {
            if (_state.value.status == StudioSessionStatus.RECORDING || _state.value.isBusy) return@launch
            val frame = _state.value.transportFrame
            val end = _state.value.punchEndFrame?.takeIf { it > frame }
            _state.value = _state.value.copy(punchStartFrame = frame, punchEndFrame = end)
        }
    }

    fun setPunchEndAtPlayhead() {
        scope.launch {
            if (_state.value.status == StudioSessionStatus.RECORDING || _state.value.isBusy) return@launch
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
            if (_state.value.status == StudioSessionStatus.RECORDING || _state.value.isBusy) return@launch
            _state.value = _state.value.copy(punchStartFrame = null, punchEndFrame = null)
        }
    }

    fun selectClip(clipId: String?) {
        scope.launch {
            if (_state.value.status != StudioSessionStatus.RECORDING && !_state.value.isBusy) {
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

    fun moveSelectedToPlayhead() {
        scope.launch {
            val current = _state.value
            val clipId = current.selectedClipId ?: return@launch
            val project = current.project ?: return@launch
            val clip = project.tracks
                .asSequence()
                .flatMap { it.clips.asSequence() }
                .firstOrNull { it.id == clipId } ?: return@launch
            val deltaFrames = current.transportFrame - clip.timelineStartFrame
            applyEdit("move_to_playhead") { StudioEditEngine.move(it, clipId, deltaFrames) }
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
            if (_state.value.status == StudioSessionStatus.RECORDING || _state.value.isBusy || undoStack.isEmpty()) return@launch
            val repo = repository ?: return@launch
            val current = _state.value.project ?: return@launch
            val previous = undoStack.removeLast()
            pushBounded(redoStack, current)
            val saved = repo.save(previous)
            syncPlaybackPlan(saved)
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
            if (_state.value.status == StudioSessionStatus.RECORDING || _state.value.isBusy || redoStack.isEmpty()) return@launch
            val repo = repository ?: return@launch
            val current = _state.value.project ?: return@launch
            val next = redoStack.removeLast()
            pushBounded(undoStack, current)
            val saved = repo.save(next)
            syncPlaybackPlan(saved)
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
            if (_state.value.status == StudioSessionStatus.RECORDING || _state.value.isBusy) return@launch
            runCatching { repo.selectActiveTake(projectId, trackId, takeId) }
                .onSuccess { project ->
                    syncPlaybackPlan(project)
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

    fun setTrackVolume(trackId: String, volumeDb: Float) {
        updateMixer { project ->
            project.copy(tracks = project.tracks.map { track ->
                if (track.id == trackId) track.copy(volumeDb = volumeDb.coerceIn(-60f, 18f)) else track
            })
        }
    }

    fun setTrackPan(trackId: String, pan: Float) {
        updateMixer { project ->
            project.copy(tracks = project.tracks.map { track ->
                if (track.id == trackId) track.copy(pan = pan.coerceIn(-1f, 1f)) else track
            })
        }
    }

    fun toggleTrackMute(trackId: String) {
        updateMixer { project ->
            project.copy(tracks = project.tracks.map { track ->
                if (track.id == trackId) track.copy(muted = !track.muted) else track
            })
        }
    }

    fun toggleTrackSolo(trackId: String) {
        updateMixer { project ->
            project.copy(tracks = project.tracks.map { track ->
                if (track.id == trackId) track.copy(solo = !track.solo) else track
            })
        }
    }

    fun renameTrack(trackId: String, name: String) {
        scope.launch { applyTrackEdit("rename_track") { StudioTrackEditor.rename(it, trackId, name) } }
    }

    fun setTrackRole(trackId: String, type: StudioTrackType) {
        scope.launch { applyTrackEdit("track_role") { StudioTrackEditor.setRole(it, trackId, type) } }
    }

    fun duplicateTrack(trackId: String) {
        scope.launch { applyTrackEdit("duplicate_track") { StudioTrackEditor.duplicate(it, trackId) } }
    }

    fun deleteTrack(trackId: String) {
        scope.launch { applyTrackEdit("delete_track") { StudioTrackEditor.delete(it, trackId) } }
    }

    fun moveTrack(trackId: String, direction: Int) {
        scope.launch { applyTrackEdit("move_track") { StudioTrackEditor.move(it, trackId, direction) } }
    }

    fun setMasterGain(gainDb: Float) {
        updateMixer { project -> project.copy(masterMix = project.masterMix.copy(gainDb = gainDb.coerceIn(-24f, 12f))) }
    }

    fun setMasterLimiter(enabled: Boolean) {
        updateMixer { project -> project.copy(masterMix = project.masterMix.copy(limiterEnabled = enabled)) }
    }

    fun exportMix(format: StudioExportFormat) {
        if (_state.value.status == StudioSessionStatus.RECORDING || _state.value.isBusy) return
        operationJob?.cancel()
        operationJob = scope.launch { exportInternal(format = format, stems = false) }
    }

    fun exportStems() {
        if (_state.value.status == StudioSessionStatus.RECORDING || _state.value.isBusy) return
        operationJob?.cancel()
        operationJob = scope.launch { exportInternal(format = StudioExportFormat.WAV, stems = true) }
    }

    fun clearExportResult() {
        scope.launch { _state.value = _state.value.copy(exportResultPath = null, exportResultLabel = null) }
    }

    fun clearError() {
        scope.launch { _state.value = _state.value.copy(errorMessage = null) }
    }

    fun closeProject() {
        openJob?.cancel()
        openJob = null
        operationJob?.cancel()
        operationJob = null
        scope.launch {
            if (_state.value.status == StudioSessionStatus.RECORDING) stopRecordingInternal()
            closeEngineInternal()
            deviceManager?.close()
            deviceManager = null
            audioFocusManager?.abandon()
            audioFocusManager = null
            latencyStore = null
            renderEngine = null
            repository = null
            waveformStore = null
            undoStack.clear()
            redoStack.clear()
            _state.value = StudioSessionState()
        }
    }

    private suspend fun openInternal(context: Context, projectId: String) {
        if (_state.value.status == StudioSessionStatus.RECORDING) stopRecordingInternal()
        closeEngineInternal()
        deviceManager?.close()
        audioFocusManager?.abandon()
        undoStack.clear()
        redoStack.clear()
        pendingPunch = null
        appContext = context
        repository = StudioProjectRepository(context)
        waveformStore = StudioWaveformStore(context)
        latencyStore = StudioLatencyStore(context)
        renderEngine = StudioRenderEngine(context)
        audioFocusManager = StudioAudioFocusManager(context) {
            scope.launch { handleAudioFocusLost() }
        }
        deviceManager = StudioAudioDeviceManager(context) { snapshot ->
            scope.launch { handleDeviceSnapshot(snapshot) }
        }
        val repo = requireNotNull(repository)
        val waves = requireNotNull(waveformStore)
        val devices = deviceManager?.snapshot() ?: StudioAudioDeviceSnapshot()
        _state.value = StudioSessionState(
            projectId = projectId,
            status = StudioSessionStatus.LOADING,
            message = "Đang mở dự án và kiểm tra bản thu dở...",
            audioDevices = devices,
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
                    native.setPlaybackPlan(StudioPlaybackPlanner.build(project, repo), project.timelineSampleRate),
                    "Không thể nạp Studio playback plan",
                )
                requireSuccess(native.start(), "Không thể khởi động Studio Audio Core")
                if (!uiVisible) {
                    requireSuccess(native.stop(), "Không thể dừng Studio audio khi ứng dụng đang ở nền")
                    outputSuspendedForBackground = true
                }
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
                audioDevices = devices,
                latencyProfile = diagnostics?.let { profileForDiagnostics(StudioInputMode.AUTO, it) },
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
                    "output_device_id" to diagnostics?.outputDeviceId,
                    "audio_api" to diagnostics?.audioApiLabel,
                ),
            )
        } catch (cancelled: CancellationException) {
            closeEngineInternal()
            throw cancelled
        } catch (error: Throwable) {
            closeEngineInternal()
            _state.value = _state.value.copy(
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
        if (!uiVisible) {
            _state.value = current.copy(errorMessage = "Mở Studio ở foreground để bắt đầu thu")
            return
        }
        if (audioFocusManager?.requestRecording() != true) {
            _state.value = current.copy(errorMessage = "Không thể giành audio focus để thu âm")
            return
        }
        if (!resumeOutputIfNeeded(native)) {
            audioFocusManager?.abandon()
            return
        }

        val wasPlaying = current.status == StudioSessionStatus.PLAYING
        native.setPlaying(false)
        val recordStart = punch?.recordStartFrame ?: current.transportFrame
        native.seek(recordStart)
        native.setPunchMuteWindow(punch?.startFrame, punch?.endFrame)
        _state.value = current.copy(
            status = StudioSessionStatus.READY,
            inputMode = mode,
            transportFrame = recordStart,
            message = if (punch != null) "Đang chuẩn bị Punch..." else "Đang chuẩn bị microphone...",
            errorMessage = null,
        )

        fun failRecordingSetup(message: String) {
            native.setPunchMuteWindow(null, null)
            audioFocusManager?.abandon()
            val resumePlayback = wasPlaying && punch == null && uiVisible && audioFocusManager?.requestPlayback() == true
            native.setPlaying(resumePlayback)
            _state.value = current.copy(
                status = if (resumePlayback) StudioSessionStatus.PLAYING else StudioSessionStatus.READY,
                inputMode = mode,
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
        val actualInput = inputDiagnostics.inputDeviceId ?: preferredInputDeviceId
        val actualOutput = inputDiagnostics.outputDeviceId.takeIf { it >= 0 } ?: current.selectedOutputDeviceId
        val route = latencyRoute(actualInput, actualOutput, mode, inputDiagnostics.sampleRate)
        val profile = latencyStore?.find(route)
        val compensationFrames = profile?.compensationFrames(project.timelineSampleRate) ?: 0L
        val pending = runCatching {
            repo.beginTake(
                projectId = project.id,
                recordedTimelineFrame = recordStart,
                inputSampleRate = inputSampleRate,
                inputDeviceId = actualInput,
                latencyCompensationFrames = compensationFrames,
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
                    inputMode = mode,
                    latencyProfile = profile,
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
                        "output_device_id" to actualOutput,
                        "latency_compensation_frames" to compensationFrames,
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

    private fun stopRecordingInternal(forceRecovered: Boolean = false) {
        val native = engine ?: return
        val repo = repository ?: return
        val context = appContext
        val pending = pendingTake
        val punch = pendingPunch
        if (_state.value.status != StudioSessionStatus.RECORDING && pending == null) return

        native.setPlaying(false)
        val stopResult = native.stopRecording()
        audioFocusManager?.abandon()
        val recoveredByNativeStop = forceRecovered || stopResult !is StudioAudioOperationResult.Success
        native.setPunchMuteWindow(null, null)
        var project = _state.value.project
        var selectedClipId = _state.value.selectedClipId
        var errorMessage: String? = null
        if (pending != null) {
            runCatching {
                val currentTrack = project?.tracks?.firstOrNull { it.id == pending.trackId }
                val keepArrangement = punch != null || currentTrack?.clips?.isNotEmpty() == true
                var finalized = repo.finalizeTake(
                    pending = pending,
                    status = if (recoveredByNativeStop) StudioTakeStatus.RECOVERED else StudioTakeStatus.COMPLETE,
                    activateTake = !keepArrangement,
                )
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
                .onFailure { error -> errorMessage = error.message ?: "Không thể hoàn tất Studio take" }
        }
        pendingTake = null
        pendingPunch = null
        context?.let { StudioSessionService.stop(it) }

        val refreshedProject = project ?: _state.value.project
        if (refreshedProject != null && errorMessage == null) {
            runCatching { syncPlaybackPlan(refreshedProject) }
                .onFailure { error -> errorMessage = error.message ?: "Không thể cập nhật Studio playback plan" }
        }
        val refreshedWaves = if (refreshedProject != null) loadWaveforms(refreshedProject, null) else _state.value.waveforms
        if (recoveredByNativeStop && errorMessage == null) {
            errorMessage = when (stopResult) {
                StudioAudioOperationResult.Released -> "Audio engine đã đóng; phần audio hợp lệ được lưu dưới dạng recovered take"
                is StudioAudioOperationResult.Error -> "Audio writer/route bị gián đoạn; phần audio hợp lệ được lưu dưới dạng recovered take"
                StudioAudioOperationResult.Success -> "Audio route bị gián đoạn; phần audio hợp lệ được lưu dưới dạng recovered take"
            }
        }
        if (!uiVisible && !outputSuspendedForBackground) {
            native.stop()
            outputSuspendedForBackground = true
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
                "take_status" to if (recoveredByNativeStop) StudioTakeStatus.RECOVERED.name else StudioTakeStatus.COMPLETE.name,
                "latency_compensation_frames" to pending?.latencyCompensationFrames,
                "finalized" to (project != null),
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
                val disconnected = diagnostics.disconnectCount > (current.diagnostics?.disconnectCount ?: 0L)
                if (current.status == StudioSessionStatus.RECORDING && disconnected) {
                    stopRecordingInternal(forceRecovered = true)
                    if (uiVisible) reopenPreferredOutputAfterDisconnect()
                    continue
                }
                if (current.status == StudioSessionStatus.RECORDING && punch != null &&
                    diagnostics.transportFrame >= punch.endFrame
                ) {
                    stopRecordingInternal()
                    continue
                }
                if (current.status == StudioSessionStatus.RECORDING &&
                    (!diagnostics.isRecording || diagnostics.writerErrorCode != 0)
                ) {
                    stopRecordingInternal(forceRecovered = true)
                    continue
                }
                if (disconnected && !current.isBusy && uiVisible) {
                    reopenPreferredOutputAfterDisconnect()
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

    private fun reopenPreferredOutputAfterDisconnect() {
        val current = _state.value
        if (current.status == StudioSessionStatus.RECORDING || current.isBusy || !uiVisible) return
        val preferred = current.selectedOutputDeviceId?.takeIf { id -> current.audioDevices.outputs.any { it.id == id } }
        switchOutputRouteInternal(preferred)
    }

    private fun applyEdit(
        label: String,
        transform: (StudioProject) -> StudioEditEngine.EditResult,
    ) {
        val current = _state.value
        if (current.status == StudioSessionStatus.RECORDING || current.isBusy) return
        val repo = repository ?: return
        val project = current.project ?: return
        runCatching {
            val edit = transform(project)
            if (edit.project == project) return@runCatching edit
            pushBounded(undoStack, project)
            redoStack.clear()
            val saved = repo.save(edit.project)
            syncPlaybackPlan(saved)
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

    private fun applyTrackEdit(
        label: String,
        transform: (StudioProject) -> StudioProject,
    ) {
        val current = _state.value
        if (current.status == StudioSessionStatus.RECORDING || current.isBusy) return
        val repo = repository ?: return
        val project = current.project ?: return
        runCatching {
            val changed = transform(project)
            if (changed == project) return@runCatching project
            pushBounded(undoStack, project)
            redoStack.clear()
            val saved = repo.save(changed)
            syncPlaybackPlan(saved)
            saved
        }.onSuccess { saved ->
            _state.value = _state.value.copy(
                project = saved,
                selectedClipId = current.selectedClipId?.takeIf { id -> saved.hasClip(id) },
                durationFrames = projectDurationFrames(saved),
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                errorMessage = null,
            )
            DiagnosticLogger.info(
                component = TAG,
                event = "studio_track_edit",
                sessionId = saved.id,
                fields = mapOf("command" to label),
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(
                errorMessage = error.message ?: "Không thể thay đổi lớp âm thanh",
            )
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

    private fun updateMixer(transform: (StudioProject) -> StudioProject) {
        scope.launch {
            val current = _state.value
            if (current.status == StudioSessionStatus.CALIBRATING || current.status == StudioSessionStatus.RENDERING) return@launch
            val project = current.project ?: return@launch
            val repo = repository ?: return@launch
            runCatching {
                val changed = transform(project)
                if (changed == project) return@runCatching project
                val saved = repo.save(changed)
                syncPlaybackPlan(saved)
                saved
            }.onSuccess { saved ->
                _state.value = _state.value.copy(project = saved, errorMessage = null)
            }.onFailure { error ->
                _state.value = _state.value.copy(errorMessage = error.message ?: "Không thể cập nhật Mixer")
            }
        }
    }

    private suspend fun calibrateLatencyInternal(mode: StudioInputMode) {
        val current = _state.value
        val project = current.project ?: return
        val native = engine ?: return
        val context = appContext ?: return
        val repo = repository ?: return
        val waves = waveformStore ?: return
        if (current.status == StudioSessionStatus.RECORDING || current.isBusy || !uiVisible) return
        if (audioFocusManager?.requestRecording() != true) {
            _state.value = current.copy(errorMessage = "Không thể giành audio focus để hiệu chỉnh latency")
            return
        }

        val frame = current.transportFrame
        val wasPlaying = current.status == StudioSessionStatus.PLAYING
        native.setPlaying(false)
        native.closeStream()
        _state.value = current.copy(
            status = StudioSessionStatus.CALIBRATING,
            inputMode = mode,
            message = "Đang phát tín hiệu hiệu chỉnh và đo round-trip latency...",
            errorMessage = null,
        )

        var measurement: StudioLatencyMeasurement? = null
        var measurementError: Throwable? = null
        try {
            val calibrationResult = coroutineScope {
                val calibrationTask = async(Dispatchers.IO) {
                    StudioLatencyNative.measure(
                        preferredInputDeviceId = current.selectedInputDeviceId,
                        preferredOutputDeviceId = current.selectedOutputDeviceId,
                        inputMode = mode,
                    )
                }
                try {
                    calibrationTask.await()
                } catch (cancelled: CancellationException) {
                    StudioLatencyNative.cancel()
                    withContext(NonCancellable) { calibrationTask.join() }
                    throw cancelled
                }
            }
            calibrationResult.onSuccess { measurement = it }
                .onFailure { measurementError = it }

            val prepared = StudioBeatPreparer(context, repo, waves).prepare(project.id)
            val restoredProject = prepared.project
            requireSuccess(native.openOutput(current.selectedOutputDeviceId), "Không thể mở lại output sau hiệu chỉnh")
            requireSuccess(
                native.loadBeat(prepared.pcmFile, restoredProject.timelineSampleRate, 2),
                "Không thể nạp lại beat sau hiệu chỉnh",
            )
            requireSuccess(
                native.setPlaybackPlan(StudioPlaybackPlanner.build(restoredProject, repo), restoredProject.timelineSampleRate),
                "Không thể nạp lại playback plan sau hiệu chỉnh",
            )
            requireSuccess(native.start(), "Không thể khởi động lại Studio sau hiệu chỉnh")
            outputSuspendedForBackground = false
            native.seek(frame)
            val resumePlayback = wasPlaying && uiVisible && audioFocusManager?.requestPlayback() == true
            native.setPlaying(resumePlayback)
            if (!resumePlayback) audioFocusManager?.abandon()

            val diagnostics = native.diagnostics()
            val measured = measurement
            val profile = if (measured != null) {
                val route = latencyRoute(
                    inputDeviceId = measured.inputDeviceId ?: current.selectedInputDeviceId,
                    outputDeviceId = measured.outputDeviceId ?: current.selectedOutputDeviceId,
                    mode = mode,
                    sampleRate = measured.sampleRate,
                )
                latencyStore?.saveAutomatic(route, measured.latencyFrames, measured.confidence)
            } else {
                null
            }
            if (!uiVisible) {
                native.stop()
                outputSuspendedForBackground = true
            }
            _state.value = _state.value.copy(
                project = restoredProject,
                status = if (resumePlayback) StudioSessionStatus.PLAYING else StudioSessionStatus.READY,
                inputMode = mode,
                transportFrame = frame,
                durationFrames = projectDurationFrames(restoredProject),
                diagnostics = diagnostics,
                latencyProfile = profile ?: diagnostics?.let { profileForDiagnostics(mode, it) },
                message = measured?.let {
                    "Latency ${formatLatency(it.milliseconds)} ms • độ tin cậy ${(it.confidence * 100f).toInt()}%"
                },
                errorMessage = measurementError?.message,
            )
            DiagnosticLogger.info(
                component = TAG,
                event = "studio_latency_calibrated",
                sessionId = project.id,
                fields = mapOf(
                    "latency_frames" to measured?.latencyFrames,
                    "latency_ms" to measured?.milliseconds,
                    "confidence" to measured?.confidence,
                    "input_device_id" to measured?.inputDeviceId,
                    "output_device_id" to measured?.outputDeviceId,
                    "sample_rate" to measured?.sampleRate,
                ),
            )
        } catch (cancelled: CancellationException) {
            StudioLatencyNative.cancel()
            restoreAfterCalibrationInterruption(
                context = context,
                repo = repo,
                waves = waves,
                native = native,
                projectId = project.id,
                frame = frame,
                outputDeviceId = current.selectedOutputDeviceId,
            )
            audioFocusManager?.abandon()
            throw cancelled
        } catch (error: Throwable) {
            restoreAfterCalibrationInterruption(
                context = context,
                repo = repo,
                waves = waves,
                native = native,
                projectId = project.id,
                frame = frame,
                outputDeviceId = current.selectedOutputDeviceId,
            )
            audioFocusManager?.abandon()
            _state.value = _state.value.copy(
                message = null,
                errorMessage = error.message ?: "Không thể khôi phục Studio sau hiệu chỉnh latency",
            )
        }
    }

    private suspend fun restoreAfterCalibrationInterruption(
        context: Context,
        repo: StudioProjectRepository,
        waves: StudioWaveformStore,
        native: StudioNativeAudio,
        projectId: String,
        frame: Long,
        outputDeviceId: Int?,
    ) = withContext(NonCancellable) {
        runCatching {
            val prepared = StudioBeatPreparer(context, repo, waves).prepare(projectId)
            val restoredProject = prepared.project
            requireSuccess(native.openOutput(outputDeviceId), "Không thể mở lại output Studio")
            requireSuccess(
                native.loadBeat(prepared.pcmFile, restoredProject.timelineSampleRate, 2),
                "Không thể nạp lại beat Studio",
            )
            requireSuccess(
                native.setPlaybackPlan(StudioPlaybackPlanner.build(restoredProject, repo), restoredProject.timelineSampleRate),
                "Không thể nạp lại bản phối Studio",
            )
            requireSuccess(native.start(), "Không thể khởi động lại Studio")
            outputSuspendedForBackground = false
            native.seek(frame)
            native.setPlaying(false)
            if (!uiVisible) {
                native.stop()
                outputSuspendedForBackground = true
            }
            _state.value = _state.value.copy(
                project = restoredProject,
                status = StudioSessionStatus.READY,
                transportFrame = frame,
                durationFrames = projectDurationFrames(restoredProject),
                diagnostics = native.diagnostics(),
                message = null,
            )
        }.onFailure { restoreError ->
            _state.value = _state.value.copy(
                status = StudioSessionStatus.ERROR,
                message = null,
                errorMessage = restoreError.message ?: "Không thể khôi phục Studio sau khi dừng căn tiếng",
            )
        }
        Unit
    }

    private fun switchOutputRouteInternal(deviceId: Int?) {
        val native = engine ?: return
        val current = _state.value
        val project = current.project ?: return
        val repo = repository ?: return
        val frame = current.transportFrame
        val wasPlaying = current.status == StudioSessionStatus.PLAYING
        native.setPlaying(false)

        runCatching {
            requireSuccess(native.openOutput(deviceId), "Không thể mở output route đã chọn")
            requireSuccess(
                native.setPlaybackPlan(StudioPlaybackPlanner.build(project, repo), project.timelineSampleRate),
                "Không thể đồng bộ Mixer sau khi đổi output",
            )
            requireSuccess(native.start(), "Không thể khởi động output route đã chọn")
            outputSuspendedForBackground = false
            native.seek(frame)
            native.setPlaying(wasPlaying)
            native.diagnostics()
        }.onSuccess { diagnostics ->
            _state.value = current.copy(
                selectedOutputDeviceId = deviceId,
                diagnostics = diagnostics,
                status = if (wasPlaying) StudioSessionStatus.PLAYING else StudioSessionStatus.READY,
                latencyProfile = diagnostics?.let { profileForDiagnostics(current.inputMode, it) },
                message = diagnostics?.outputDeviceId?.let { actual ->
                    if (deviceId != null && actual != deviceId) "Android đã route output sang device $actual thay vì device $deviceId" else null
                },
                errorMessage = null,
            )
        }.onFailure { requestedRouteError ->
            runCatching {
                requireSuccess(native.openOutput(), "Không thể fallback output mặc định")
                requireSuccess(
                    native.setPlaybackPlan(StudioPlaybackPlanner.build(project, repo), project.timelineSampleRate),
                    "Không thể restore playback plan",
                )
                requireSuccess(native.start(), "Không thể khởi động output mặc định")
                outputSuspendedForBackground = false
                native.seek(frame)
                native.setPlaying(wasPlaying)
                native.diagnostics()
            }.onSuccess { diagnostics ->
                _state.value = current.copy(
                    selectedOutputDeviceId = null,
                    status = if (wasPlaying) StudioSessionStatus.PLAYING else StudioSessionStatus.READY,
                    diagnostics = diagnostics,
                    latencyProfile = diagnostics?.let { profileForDiagnostics(current.inputMode, it) },
                    message = "Route đã chọn không còn dùng được; Studio đã chuyển về output mặc định.",
                    errorMessage = requestedRouteError.message,
                )
            }.onFailure { fallbackError ->
                _state.value = current.copy(
                    selectedOutputDeviceId = null,
                    status = StudioSessionStatus.ERROR,
                    diagnostics = native.diagnostics(),
                    message = null,
                    errorMessage = "Không thể mở route audio đã chọn và cũng không thể fallback output mặc định: ${fallbackError.message ?: requestedRouteError.message ?: "không xác định"}",
                )
            }
        }
    }

    private suspend fun exportInternal(format: StudioExportFormat, stems: Boolean) {
        val current = _state.value
        val project = current.project ?: return
        val renderer = renderEngine ?: return
        if (current.status == StudioSessionStatus.RECORDING || current.isBusy) return
        val wasPlaying = current.status == StudioSessionStatus.PLAYING
        engine?.setPlaying(false)
        audioFocusManager?.abandon()
        _state.value = current.copy(
            status = StudioSessionStatus.RENDERING,
            message = if (stems) "Đang render stems..." else "Đang render ${format.name}...",
            errorMessage = null,
            exportResultPath = null,
            exportResultLabel = null,
        )
        runCatching {
            if (stems) renderer.renderStems(project) else renderer.renderMix(project, format)
        }.onSuccess { file ->
            if (_state.value.projectId == project.id) {
                _state.value = _state.value.copy(
                    status = StudioSessionStatus.READY,
                    message = "Đã xuất ${file.name}",
                    exportResultPath = file.absolutePath,
                    exportResultLabel = if (stems) "Stems ZIP" else "Studio ${format.name}",
                    errorMessage = null,
                )
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            if (_state.value.projectId == project.id) {
                _state.value = _state.value.copy(
                    status = StudioSessionStatus.READY,
                    message = null,
                    errorMessage = error.message ?: "Không thể xuất Studio audio",
                )
            }
        }
        if (wasPlaying && _state.value.projectId == project.id) {
            // Export intentionally returns paused so the user can inspect/share the result without playback restarting unexpectedly.
            engine?.setPlaying(false)
        }
    }

    private fun syncPlaybackPlan(project: StudioProject) {
        val native = engine ?: return
        val repo = repository ?: return
        requireSuccess(
            native.setPlaybackPlan(StudioPlaybackPlanner.build(project, repo), project.timelineSampleRate),
            "Không thể cập nhật Studio playback plan",
        )
    }

    private fun handleDeviceSnapshot(snapshot: StudioAudioDeviceSnapshot) {
        val current = _state.value
        var selectedInput = current.selectedInputDeviceId
        var selectedOutput = current.selectedOutputDeviceId
        var message = current.message
        val selectedOutputRemoved = selectedOutput != null && snapshot.outputs.none { it.id == selectedOutput }
        val actualOutputId = current.diagnostics?.outputDeviceId?.takeIf { it >= 0 }
        val actualOutputRemoved = actualOutputId != null && snapshot.outputs.none { it.id == actualOutputId }
        if (selectedInput != null && snapshot.inputs.none { it.id == selectedInput }) {
            selectedInput = null
            message = "Microphone đã chọn vừa bị ngắt kết nối; Studio sẽ dùng route mặc định ở lần thu tiếp theo."
        }
        if (selectedOutputRemoved) {
            selectedOutput = null
            message = "Output đã chọn vừa bị ngắt kết nối; Studio đang chuyển về route mặc định."
        }
        _state.value = current.copy(
            audioDevices = snapshot,
            selectedInputDeviceId = selectedInput,
            selectedOutputDeviceId = selectedOutput,
            latencyProfile = profileForCurrentRoute(
                current.inputMode,
                inputIdOverride = selectedInput,
                outputIdOverride = selectedOutput,
            ),
            message = message,
        )
        if ((selectedOutputRemoved || actualOutputRemoved) && current.status != StudioSessionStatus.RECORDING && !current.isBusy && uiVisible) {
            switchOutputRouteInternal(null)
        }
    }

    private fun handleAudioFocusLost() {
        val current = _state.value
        when (current.status) {
            StudioSessionStatus.RECORDING -> {
                stopRecordingInternal()
                _state.value = _state.value.copy(message = "Bản thu đã dừng vì audio focus bị gián đoạn")
            }
            StudioSessionStatus.PLAYING -> {
                engine?.setPlaying(false)
                audioFocusManager?.abandon()
                _state.value = current.copy(
                    status = StudioSessionStatus.READY,
                    message = "Playback đã tạm dừng vì ứng dụng khác cần audio",
                )
            }
            StudioSessionStatus.CALIBRATING -> {
                operationJob?.cancel()
                operationJob = null
                audioFocusManager?.abandon()
            }
            else -> audioFocusManager?.abandon()
        }
    }

    private fun profileForCurrentRoute(
        mode: StudioInputMode,
        inputIdOverride: Int? = _state.value.selectedInputDeviceId,
        outputIdOverride: Int? = _state.value.selectedOutputDeviceId,
    ): StudioLatencyProfile? {
        val diagnostics = _state.value.diagnostics ?: return null
        return profileForDiagnostics(mode, diagnostics, inputIdOverride, outputIdOverride)
    }

    private fun profileForDiagnostics(
        mode: StudioInputMode,
        diagnostics: StudioAudioDiagnostics,
        inputIdOverride: Int? = _state.value.selectedInputDeviceId,
        outputIdOverride: Int? = diagnostics.outputDeviceId.takeIf { it >= 0 },
    ): StudioLatencyProfile? {
        val route = latencyRoute(
            inputDeviceId = inputIdOverride ?: diagnostics.inputDeviceId,
            outputDeviceId = outputIdOverride ?: diagnostics.outputDeviceId.takeIf { it >= 0 },
            mode = mode,
            sampleRate = diagnostics.sampleRate,
        )
        return latencyStore?.find(route)
    }

    private fun latencyRoute(
        inputDeviceId: Int?,
        outputDeviceId: Int?,
        mode: StudioInputMode,
        sampleRate: Int,
    ): StudioLatencyRoute {
        val devices = _state.value.audioDevices
        val inputFingerprint = devices.inputs.firstOrNull { it.id == inputDeviceId }?.fingerprint
            ?: if (inputDeviceId == null) "default-input" else "input-device-$inputDeviceId"
        val outputFingerprint = devices.outputs.firstOrNull { it.id == outputDeviceId }?.fingerprint
            ?: if (outputDeviceId == null) "default-output" else "output-device-$outputDeviceId"
        return StudioLatencyRoute(
            inputFingerprint = inputFingerprint,
            outputFingerprint = outputFingerprint,
            inputMode = mode,
            sampleRate = sampleRate.coerceAtLeast(1),
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
        audioFocusManager?.abandon()
        engine?.close()
        engine = null
        pendingTake = null
        pendingPunch = null
        outputSuspendedForBackground = false
    }

    private fun resumeOutputIfNeeded(native: StudioNativeAudio): Boolean {
        if (!outputSuspendedForBackground) return true
        return when (native.start()) {
            StudioAudioOperationResult.Success -> {
                outputSuspendedForBackground = false
                _state.value = _state.value.copy(
                    status = if (_state.value.status == StudioSessionStatus.ERROR) StudioSessionStatus.ERROR else StudioSessionStatus.READY,
                    diagnostics = native.diagnostics(),
                )
                true
            }
            else -> {
                val current = _state.value
                val preferred = current.selectedOutputDeviceId?.takeIf { id -> current.audioDevices.outputs.any { it.id == id } }
                switchOutputRouteInternal(preferred)
                !outputSuspendedForBackground && _state.value.status != StudioSessionStatus.ERROR
            }
        }
    }

    internal fun projectDurationFrames(project: StudioProject): Long {
        var duration = project.beatAsset()?.let { asset ->
            sourceFramesToTimeline(
                asset.durationFrames ?: 0L,
                asset.sampleRate ?: project.timelineSampleRate,
                project.timelineSampleRate,
            )
        } ?: 0L
        for (track in project.tracks) {
            if (track.type == StudioTrackType.BEAT) continue
            if (track.clips.isNotEmpty()) {
                for (clip in track.clips) {
                    duration = max(duration, StudioEditEngine.timelineEnd(project, clip))
                }
                continue
            }
            val activeTake = track.activeTakeId
                ?.let { id -> track.takes.firstOrNull { it.id == id } }
                ?: track.takes.lastOrNull()
                ?: continue
            val placement = activeTake.latencyCompensatedPlacement(project.timelineSampleRate)
            val sourceLength = (placement.sourceEndFrame - placement.sourceStartFrame).coerceAtLeast(0L)
            val length = sourceFramesToTimeline(sourceLength, activeTake.inputSampleRate, project.timelineSampleRate)
            duration = max(duration, placement.timelineStartFrame + length)
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

    private fun formatLatency(milliseconds: Double): String = String.format(java.util.Locale.US, "%.1f", milliseconds)

    private fun StudioProject.hasClip(clipId: String): Boolean = tracks.any { track -> track.clips.any { it.id == clipId } }
}
