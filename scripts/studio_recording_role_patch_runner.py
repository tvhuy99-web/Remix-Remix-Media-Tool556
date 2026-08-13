from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one anchor, found {count}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1))


recording_target = "app/src/main/java/com/aistudio/mediatool/feature/studio/data/StudioRecordingTarget.kt"
replace_once(
    recording_target,
    """sealed interface StudioRecordingTargetRequest {\n    data object NewLayer : StudioRecordingTargetRequest\n    data class ExistingTrack(val trackId: String) : StudioRecordingTargetRequest\n}\n""",
    """sealed interface StudioRecordingTargetRequest {\n    /** Legacy fresh-layer request kept for callers that do not choose a role yet. */\n    data object NewLayer : StudioRecordingTargetRequest\n    data class NewLayerForRole(val type: StudioTrackType) : StudioRecordingTargetRequest\n    data class ExistingTrack(val trackId: String) : StudioRecordingTargetRequest\n}\n""",
)
replace_once(
    recording_target,
    """    fun requestNewLayer() {\n        pending.set(StudioRecordingTargetRequest.NewLayer)\n    }\n\n    fun requestExistingTrack(trackId: String?) {\n""",
    """    fun requestNewLayer() {\n        pending.set(StudioRecordingTargetRequest.NewLayer)\n    }\n\n    fun requestNewLayer(type: StudioTrackType) {\n        require(type in RECORDABLE_VOICE_TYPES) { \"Loại lớp này không dùng để thu giọng\" }\n        pending.set(StudioRecordingTargetRequest.NewLayerForRole(type))\n    }\n\n    fun requestExistingTrack(trackId: String?) {\n""",
)
replace_once(
    recording_target,
    """    if (request == StudioRecordingTargetRequest.NewLayer) {\n        val layerNumber = tracks.count(StudioTrack::isRecordingVoiceLayer) + 1\n""",
    """    if (request is StudioRecordingTargetRequest.NewLayerForRole) {\n        val ordinal = tracks.count { it.type == request.type } + 1\n        val track = StudioTrack(\n            id = UUID.randomUUID().toString(),\n            type = request.type,\n            name = recordingLayerName(request.type, ordinal),\n        )\n        return StudioRecordingTrackSelection(\n            project = copy(tracks = tracks + track),\n            track = track,\n            createdNewTrack = true,\n        )\n    }\n\n    if (request == StudioRecordingTargetRequest.NewLayer) {\n        val layerNumber = tracks.count(StudioTrack::isRecordingVoiceLayer) + 1\n""",
)
replace_once(
    recording_target,
    """internal fun StudioTrack.isAutoRecordingLayer(): Boolean =\n    name == \"Giọng chính\" || (type == StudioTrackType.OTHER && name.matches(Regex(\"Giọng \\\\d+\")))\n\nprivate fun StudioTrack.isRecordingVoiceLayer(): Boolean = when (type) {\n""",
    """private val RECORDABLE_VOICE_TYPES = setOf(\n    StudioTrackType.VOCAL,\n    StudioTrackType.BACKING_VOCAL,\n    StudioTrackType.ADLIB,\n    StudioTrackType.OTHER,\n)\n\ninternal fun recordingLayerName(type: StudioTrackType, ordinal: Int): String {\n    require(type in RECORDABLE_VOICE_TYPES) { \"Loại lớp này không dùng để thu giọng\" }\n    require(ordinal >= 1) { \"Số thứ tự lớp phải từ 1\" }\n    val base = when (type) {\n        StudioTrackType.VOCAL -> \"Giọng chính\"\n        StudioTrackType.BACKING_VOCAL -> \"Giọng bè\"\n        StudioTrackType.ADLIB -> \"Giọng phụ\"\n        StudioTrackType.OTHER -> \"Song ca / khác\"\n        StudioTrackType.BEAT,\n        StudioTrackType.INSTRUMENT,\n        -> error(\"Loại lớp này không dùng để thu giọng\")\n    }\n    return if (ordinal == 1) base else \"$base $ordinal\"\n}\n\ninternal fun StudioTrack.isAutoRecordingLayer(): Boolean =\n    type in RECORDABLE_VOICE_TYPES && (\n        name.matches(Regex(\"Giọng chính(?: \\\\d+)?\")) ||\n            name.matches(Regex(\"Giọng bè(?: \\\\d+)?\")) ||\n            name.matches(Regex(\"Giọng phụ(?: \\\\d+)?\")) ||\n            name.matches(Regex(\"Song ca / khác(?: \\\\d+)?\")) ||\n            name.matches(Regex(\"Giọng \\\\d+\"))\n        )\n\nprivate fun StudioTrack.isRecordingVoiceLayer(): Boolean = when (type) {\n""",
)
replace_once(
    recording_target,
    """    StudioTrackType.OTHER -> name.startsWith(\"Giọng \")\n""",
    """    StudioTrackType.OTHER -> name.startsWith(\"Giọng \") || name.startsWith(\"Song ca / khác\")\n""",
)

working_track = Path("app/src/main/java/com/aistudio/mediatool/feature/studio/data/StudioWorkingTrack.kt")
if working_track.exists():
    raise SystemExit(f"{working_track}: already exists")
working_track.write_text('''package com.aistudio.mediatool.feature.studio.data\n\nimport com.aistudio.mediatool.feature.studio.domain.StudioProject\nimport com.aistudio.mediatool.feature.studio.domain.StudioTrack\nimport com.aistudio.mediatool.feature.studio.domain.StudioTrackType\n\n/** Shared working-track resolution for Timeline, Mixer and punch recording. */\ninternal fun StudioProject.resolveWorkingTrackId(\n    selectedTrackId: String?,\n    selectedClipId: String?,\n    requireRecordedContent: Boolean = false,\n): String? {\n    fun StudioTrack.usable(): Boolean =\n        type != StudioTrackType.BEAT &&\n            (!requireRecordedContent || takes.isNotEmpty() || clips.isNotEmpty())\n\n    selectedClipId?.let { clipId ->\n        tracks.firstOrNull { track ->\n            track.usable() && track.clips.any { it.id == clipId }\n        }?.let { return it.id }\n    }\n\n    selectedTrackId?.let { trackId ->\n        tracks.firstOrNull { it.id == trackId && it.usable() }?.let { return it.id }\n    }\n\n    return tracks.firstOrNull { it.usable() && (it.takes.isNotEmpty() || it.clips.isNotEmpty()) }?.id\n        ?: tracks.firstOrNull { it.usable() }?.id\n}\n\ninternal fun StudioProject.trackIdForClip(clipId: String?): String? {\n    if (clipId == null) return null\n    return tracks.firstOrNull { track ->\n        track.type != StudioTrackType.BEAT && track.clips.any { it.id == clipId }\n    }?.id\n}\n''')

runtime = "app/src/main/java/com/aistudio/mediatool/feature/studio/audio/StudioSessionRuntime.kt"
replace_once(
    runtime,
    """import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository\nimport com.aistudio.mediatool.feature.studio.data.StudioTrackEditor\n""",
    """import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository\nimport com.aistudio.mediatool.feature.studio.data.StudioRecordingTargetRequests\nimport com.aistudio.mediatool.feature.studio.data.StudioTrackEditor\n""",
)
replace_once(
    runtime,
    """import com.aistudio.mediatool.feature.studio.data.selectActiveTake\n""",
    """import com.aistudio.mediatool.feature.studio.data.resolveWorkingTrackId\nimport com.aistudio.mediatool.feature.studio.data.selectActiveTake\nimport com.aistudio.mediatool.feature.studio.data.trackIdForClip\n""",
)
replace_once(
    runtime,
    """    val selectedClipId: String? = null,\n    val canUndo: Boolean = false,\n""",
    """    val selectedClipId: String? = null,\n    val selectedTrackId: String? = null,\n    val canUndo: Boolean = false,\n""",
)
replace_once(
    runtime,
    """            val editableVoiceTrack = project.tracks.firstOrNull { track ->\n                track.type != StudioTrackType.BEAT &&\n                    (track.takes.isNotEmpty() || track.clips.isNotEmpty())\n            }\n            if (editableVoiceTrack == null) {\n""",
    """            val editableTrackId = project.resolveWorkingTrackId(\n                selectedTrackId = current.selectedTrackId,\n                selectedClipId = current.selectedClipId,\n                requireRecordedContent = true,\n            )\n            val editableVoiceTrack = editableTrackId?.let { id -> project.tracks.firstOrNull { it.id == id } }\n            if (editableVoiceTrack == null) {\n""",
)
replace_once(
    runtime,
    """            val punch = PendingPunch(\n                startFrame = start,\n                endFrame = end,\n                recordStartFrame = (start - preRoll).coerceAtLeast(0L),\n                trackId = editableVoiceTrack.id,\n            )\n            startRecordingInternal(\n""",
    """            val punch = PendingPunch(\n                startFrame = start,\n                endFrame = end,\n                recordStartFrame = (start - preRoll).coerceAtLeast(0L),\n                trackId = editableVoiceTrack.id,\n            )\n            StudioRecordingTargetRequests.requestExistingTrack(editableVoiceTrack.id)\n            startRecordingInternal(\n""",
)
replace_once(
    runtime,
    """    fun selectClip(clipId: String?) {\n        scope.launch {\n            if (_state.value.status != StudioSessionStatus.RECORDING && !_state.value.isBusy) {\n                _state.value = _state.value.copy(selectedClipId = clipId)\n            }\n        }\n    }\n\n    fun beginEditing(trackId: String) {\n""",
    """    fun selectClip(clipId: String?) {\n        scope.launch {\n            val current = _state.value\n            if (current.status != StudioSessionStatus.RECORDING && !current.isBusy) {\n                val trackId = current.project?.trackIdForClip(clipId)\n                _state.value = current.copy(\n                    selectedClipId = clipId,\n                    selectedTrackId = trackId ?: current.selectedTrackId,\n                )\n            }\n        }\n    }\n\n    fun selectTrack(trackId: String?) {\n        scope.launch {\n            val current = _state.value\n            if (current.status == StudioSessionStatus.RECORDING || current.isBusy) return@launch\n            val project = current.project ?: return@launch\n            val validTrackId = trackId?.takeIf { id ->\n                project.tracks.any { it.id == id && it.type != StudioTrackType.BEAT }\n            }\n            val keepClip = current.selectedClipId?.takeIf { clipId ->\n                validTrackId != null && project.trackIdForClip(clipId) == validTrackId\n            }\n            _state.value = current.copy(\n                selectedTrackId = validTrackId,\n                selectedClipId = keepClip,\n            )\n        }\n    }\n\n    fun beginEditing(trackId: String) {\n""",
)
replace_once(
    runtime,
    """                    _state.value = _state.value.copy(\n                        project = project,\n                        durationFrames = projectDurationFrames(project),\n                        errorMessage = null,\n                    )\n""",
    """                    _state.value = _state.value.copy(\n                        project = project,\n                        selectedTrackId = trackId,\n                        durationFrames = projectDurationFrames(project),\n                        errorMessage = null,\n                    )\n""",
)
replace_once(
    runtime,
    """                project = saved,\n                selectedClipId = _state.value.selectedClipId?.takeIf { id -> saved.hasClip(id) },\n                durationFrames = projectDurationFrames(saved),\n""",
    """                project = saved,\n                selectedClipId = _state.value.selectedClipId?.takeIf { id -> saved.hasClip(id) },\n                selectedTrackId = saved.resolveWorkingTrackId(\n                    _state.value.selectedTrackId,\n                    _state.value.selectedClipId?.takeIf { id -> saved.hasClip(id) },\n                ),\n                durationFrames = projectDurationFrames(saved),\n""",
)
replace_once(
    runtime,
    """                project = saved,\n                selectedClipId = _state.value.selectedClipId?.takeIf { id -> saved.hasClip(id) },\n                durationFrames = projectDurationFrames(saved),\n""",
    """                project = saved,\n                selectedClipId = _state.value.selectedClipId?.takeIf { id -> saved.hasClip(id) },\n                selectedTrackId = saved.resolveWorkingTrackId(\n                    _state.value.selectedTrackId,\n                    _state.value.selectedClipId?.takeIf { id -> saved.hasClip(id) },\n                ),\n                durationFrames = projectDurationFrames(saved),\n""",
)
replace_once(
    runtime,
    """                recoveredTakeCount = recoveredTakeCount,\n                audioDevices = devices,\n""",
    """                recoveredTakeCount = recoveredTakeCount,\n                selectedTrackId = project.resolveWorkingTrackId(null, null),\n                audioDevices = devices,\n""",
)
replace_once(
    runtime,
    """        fun failRecordingSetup(message: String) {\n            native.setPunchMuteWindow(null, null)\n""",
    """        fun failRecordingSetup(message: String) {\n            StudioRecordingTargetRequests.clear()\n            native.setPunchMuteWindow(null, null)\n""",
)
replace_once(
    runtime,
    """                    status = StudioSessionStatus.RECORDING,\n                    recordingKind = if (punch != null) StudioRecordingKind.PUNCH else StudioRecordingKind.FULL_TAKE,\n                    inputMode = mode,\n""",
    """                    status = StudioSessionStatus.RECORDING,\n                    recordingKind = if (punch != null) StudioRecordingKind.PUNCH else StudioRecordingKind.FULL_TAKE,\n                    selectedTrackId = pending.trackId,\n                    inputMode = mode,\n""",
)
replace_once(
    runtime,
    """        var project = _state.value.project\n        var selectedClipId = _state.value.selectedClipId\n        var errorMessage: String? = null\n""",
    """        var project = _state.value.project\n        var selectedClipId = _state.value.selectedClipId\n        var selectedTrackId = _state.value.selectedTrackId\n        var errorMessage: String? = null\n""",
)
replace_once(
    runtime,
    """                if (punch != null) {\n                    val edit = StudioEditEngine.replacePunchRange(\n""",
    """                selectedTrackId = pending.trackId\n                if (punch != null) {\n                    val edit = StudioEditEngine.replacePunchRange(\n""",
)
replace_once(
    runtime,
    """                    finalized = repo.save(edit.project)\n                    selectedClipId = edit.selectedClipId\n                }\n                finalized\n""",
    """                    finalized = repo.save(edit.project)\n                    selectedClipId = edit.selectedClipId\n                } else {\n                    selectedClipId = null\n                }\n                finalized\n""",
)
replace_once(
    runtime,
    """            selectedClipId = selectedClipId,\n            durationFrames = refreshedProject?.let(::projectDurationFrames) ?: _state.value.durationFrames,\n""",
    """            selectedClipId = selectedClipId,\n            selectedTrackId = refreshedProject?.resolveWorkingTrackId(selectedTrackId, selectedClipId),\n            durationFrames = refreshedProject?.let(::projectDurationFrames) ?: _state.value.durationFrames,\n""",
)
replace_once(
    runtime,
    """                project = edit.project,\n                selectedClipId = edit.selectedClipId,\n                durationFrames = projectDurationFrames(edit.project),\n""",
    """                project = edit.project,\n                selectedClipId = edit.selectedClipId,\n                selectedTrackId = edit.project.resolveWorkingTrackId(\n                    current.selectedTrackId,\n                    edit.selectedClipId,\n                ),\n                durationFrames = projectDurationFrames(edit.project),\n""",
)
replace_once(
    runtime,
    """                project = saved,\n                selectedClipId = current.selectedClipId?.takeIf { id -> saved.hasClip(id) },\n                durationFrames = projectDurationFrames(saved),\n""",
    """                project = saved,\n                selectedClipId = current.selectedClipId?.takeIf { id -> saved.hasClip(id) },\n                selectedTrackId = saved.resolveWorkingTrackId(\n                    current.selectedTrackId,\n                    current.selectedClipId?.takeIf { id -> saved.hasClip(id) },\n                ),\n                durationFrames = projectDurationFrames(saved),\n""",
)

workspace = "app/src/main/java/com/aistudio/mediatool/feature/studio/ui/StudioWorkspaceScreen.kt"
replace_once(
    workspace,
    """    var pixelsPerSecond by rememberSaveable { mutableFloatStateOf(58f) }\n    var requestedRecordingKind by remember { mutableStateOf(StudioRecordingKind.FULL_TAKE) }\n""",
    """    var pixelsPerSecond by rememberSaveable { mutableFloatStateOf(58f) }\n    var nextRecordingRole by rememberSaveable { mutableStateOf(StudioTrackType.VOCAL) }\n    var requestedRecordingKind by remember { mutableStateOf(StudioRecordingKind.FULL_TAKE) }\n""",
)
replace_once(
    workspace,
    """            StudioRecordingKind.FULL_TAKE -> {\n                StudioRecordingTargetRequests.requestNewLayer()\n                StudioSessionRuntime.startRecording(mode = inputMode)\n            }\n            StudioRecordingKind.PUNCH -> {\n                val selectedTrackId = session.project?.tracks\n                    ?.firstOrNull { track ->\n                        session.selectedClipId != null &&\n                            track.clips.any { it.id == session.selectedClipId }\n                    }\n                    ?.id\n                    ?: session.project?.tracks?.firstOrNull { it.type == StudioTrackType.VOCAL }?.id\n                StudioRecordingTargetRequests.requestExistingTrack(selectedTrackId)\n                StudioSessionRuntime.startPunchRecording(mode = inputMode)\n            }\n""",
    """            StudioRecordingKind.FULL_TAKE -> {\n                StudioRecordingTargetRequests.requestNewLayer(nextRecordingRole)\n                StudioSessionRuntime.startRecording(mode = inputMode)\n            }\n            StudioRecordingKind.PUNCH -> {\n                StudioSessionRuntime.startPunchRecording(mode = inputMode)\n            }\n""",
)
replace_once(
    workspace,
    """                StudioRoutingLatencyCard(\n                    session = session,\n""",
    """                NextRecordingRolePanel(\n                    selectedRole = nextRecordingRole,\n                    enabled = routingEnabled,\n                    onSelected = { nextRecordingRole = it },\n                )\n\n                StudioRoutingLatencyCard(\n                    session = session,\n""",
)
replace_once(
    workspace,
    """                EditPanel(\n                    project = loaded,\n                    selectedClipId = session.selectedClipId,\n""",
    """                EditPanel(\n                    project = loaded,\n                    selectedTrackId = session.selectedTrackId,\n                    selectedClipId = session.selectedClipId,\n""",
)
replace_once(
    workspace,
    """                StudioMixerCard(\n                    project = loaded,\n                    enabled = mixerEnabled,\n                    trackEditingEnabled = editEnabled,\n                )\n""",
    """                StudioMixerCard(\n                    project = loaded,\n                    enabled = mixerEnabled,\n                    trackEditingEnabled = editEnabled,\n                    selectedTrackId = session.selectedTrackId,\n                )\n""",
)
replace_once(
    workspace,
    """}\n\n@Composable\nprivate fun TimelinePanel(\n""",
    """}\n\n@Composable\nprivate fun NextRecordingRolePanel(\n    selectedRole: StudioTrackType,\n    enabled: Boolean,\n    onSelected: (StudioTrackType) -> Unit,\n) {\n    Card(modifier = Modifier.fillMaxWidth()) {\n        Column(\n            modifier = Modifier.padding(16.dp),\n            verticalArrangement = Arrangement.spacedBy(10.dp),\n        ) {\n            Text(\n                \"Lớp sắp thu\",\n                fontWeight = FontWeight.Bold,\n                modifier = Modifier.semantics { heading() },\n            )\n            Text(\n                \"Chọn vai trò trước khi bấm Thu giọng. Bản thu mới sẽ vào một lớp độc lập đúng vai trò này.\",\n                style = MaterialTheme.typography.bodySmall,\n                color = MaterialTheme.colorScheme.onSurfaceVariant,\n            )\n            Row(\n                modifier = Modifier\n                    .fillMaxWidth()\n                    .horizontalScroll(rememberScrollState()),\n                horizontalArrangement = Arrangement.spacedBy(8.dp),\n            ) {\n                RECORDING_ROLE_TYPES.forEach { type ->\n                    val label = recordingRoleLabel(type)\n                    FilterChip(\n                        selected = selectedRole == type,\n                        onClick = { onSelected(type) },\n                        enabled = enabled,\n                        label = { Text(label) },\n                        modifier = Modifier.semantics {\n                            contentDescription = \"Vai trò lớp sắp thu: $label\"\n                            stateDescription = if (selectedRole == type) \"đang chọn\" else \"chưa chọn\"\n                        },\n                    )\n                }\n            }\n            Text(\n                \"Sẽ thu vào: ${recordingRoleLabel(selectedRole)}\",\n                style = MaterialTheme.typography.labelLarge,\n                modifier = Modifier.semantics {\n                    liveRegion = LiveRegionMode.Polite\n                    contentDescription = \"Lớp sắp thu là ${recordingRoleLabel(selectedRole)}\"\n                },\n            )\n        }\n    }\n}\n\n@Composable\nprivate fun TimelinePanel(\n""",
)
replace_once(
    workspace,
    """        Text(\n            \"Không cần kéo trên sóng âm. Chọn bước rồi dùng Lùi hoặc Tiến để đặt vị trí nghe thật chính xác.\",\n            style = MaterialTheme.typography.bodySmall,\n            color = MaterialTheme.colorScheme.onSurfaceVariant,\n        )\n\n        Text(\"Bước di chuyển vị trí nghe\", style = MaterialTheme.typography.labelLarge)\n""",
    """        Text(\n            \"Không cần kéo trên sóng âm. Chọn lớp, chọn bước rồi dùng Lùi hoặc Tiến để đặt vị trí nghe thật chính xác.\",\n            style = MaterialTheme.typography.bodySmall,\n            color = MaterialTheme.colorScheme.onSurfaceVariant,\n        )\n\n        val voiceTracks = project.tracks.filter { it.type != StudioTrackType.BEAT }\n        if (voiceTracks.isNotEmpty()) {\n            Text(\"Lớp đang thao tác\", style = MaterialTheme.typography.labelLarge)\n            Row(\n                modifier = Modifier\n                    .fillMaxWidth()\n                    .horizontalScroll(rememberScrollState()),\n                horizontalArrangement = Arrangement.spacedBy(8.dp),\n            ) {\n                voiceTracks.forEach { track ->\n                    val label = editTrackName(track)\n                    FilterChip(\n                        selected = session.selectedTrackId == track.id,\n                        onClick = { StudioSessionRuntime.selectTrack(track.id) },\n                        enabled = enabled,\n                        label = { Text(label) },\n                        modifier = Modifier.semantics {\n                            contentDescription = \"Lớp âm thanh $label\"\n                            stateDescription = if (session.selectedTrackId == track.id) \"đang thao tác\" else \"chưa chọn\"\n                        },\n                    )\n                }\n            }\n            val selectedName = voiceTracks.firstOrNull { it.id == session.selectedTrackId }?.let(::editTrackName)\n            if (selectedName != null) {\n                Text(\n                    \"Đang thao tác: $selectedName\",\n                    style = MaterialTheme.typography.labelMedium,\n                    modifier = Modifier.semantics {\n                        liveRegion = LiveRegionMode.Polite\n                        contentDescription = \"Lớp đang thao tác là $selectedName\"\n                    },\n                )\n            }\n        }\n\n        Text(\"Bước di chuyển vị trí nghe\", style = MaterialTheme.typography.labelLarge)\n""",
)
replace_once(
    workspace,
    """private fun EditPanel(\n    project: StudioProject,\n    selectedClipId: String?,\n    canUndo: Boolean,\n    canRedo: Boolean,\n    enabled: Boolean,\n) {\n    val vocalTrack = project.tracks.firstOrNull { it.type == StudioTrackType.VOCAL }\n    val clipChoices = project.tracks\n        .filter { it.type != StudioTrackType.BEAT }\n        .flatMap { track ->\n            track.clips.mapIndexed { index, clip -> EditableClipChoice(track, index, clip) }\n        }\n""",
    """private fun EditPanel(\n    project: StudioProject,\n    selectedTrackId: String?,\n    selectedClipId: String?,\n    canUndo: Boolean,\n    canRedo: Boolean,\n    enabled: Boolean,\n) {\n    val selectedTrack = project.tracks.firstOrNull {\n        it.id == selectedTrackId && it.type != StudioTrackType.BEAT\n    }\n    val editableTracks = selectedTrack?.let(::listOf)\n        ?: project.tracks.filter { it.type != StudioTrackType.BEAT }\n    val materializableTrack = selectedTrack?.takeIf { it.takes.isNotEmpty() && it.clips.isEmpty() }\n        ?: project.tracks.firstOrNull {\n            it.type != StudioTrackType.BEAT && it.takes.isNotEmpty() && it.clips.isEmpty()\n        }\n    val clipChoices = editableTracks.flatMap { track ->\n        track.clips.mapIndexed { index, clip -> EditableClipChoice(track, index, clip) }\n    }\n""",
)
replace_once(
    workspace,
    """            if (vocalTrack != null && vocalTrack.takes.isNotEmpty() && vocalTrack.clips.isEmpty()) {\n                Button(\n                    onClick = { StudioSessionRuntime.beginEditing(vocalTrack.id) },\n""",
    """            if (materializableTrack != null) {\n                Button(\n                    onClick = { StudioSessionRuntime.beginEditing(materializableTrack.id) },\n""",
)
replace_once(
    workspace,
    """            if (clipChoices.isNotEmpty()) {\n                Text(\"Chọn đoạn\", style = MaterialTheme.typography.labelLarge)\n""",
    """            if (clipChoices.isNotEmpty()) {\n                val clipListLabel = selectedTrack?.let { \"Chọn đoạn trong ${editTrackName(it)}\" } ?: \"Chọn đoạn\"\n                Text(clipListLabel, style = MaterialTheme.typography.labelLarge)\n""",
)
replace_once(
    workspace,
    """private fun inputModeLabel(mode: StudioInputMode): String = when (mode) {\n""",
    """private val RECORDING_ROLE_TYPES = listOf(\n    StudioTrackType.VOCAL,\n    StudioTrackType.BACKING_VOCAL,\n    StudioTrackType.ADLIB,\n    StudioTrackType.OTHER,\n)\n\nprivate fun recordingRoleLabel(type: StudioTrackType): String = when (type) {\n    StudioTrackType.VOCAL -> \"Giọng chính\"\n    StudioTrackType.BACKING_VOCAL -> \"Giọng bè\"\n    StudioTrackType.ADLIB -> \"Giọng phụ\"\n    StudioTrackType.OTHER -> \"Song ca / khác\"\n    StudioTrackType.BEAT -> \"Nhạc nền\"\n    StudioTrackType.INSTRUMENT -> \"Nhạc cụ\"\n}\n\nprivate fun inputModeLabel(mode: StudioInputMode): String = when (mode) {\n""",
)

advanced = "app/src/main/java/com/aistudio/mediatool/feature/studio/ui/StudioAdvancedControls.kt"
replace_once(
    advanced,
    """import androidx.compose.ui.semantics.contentDescription\nimport androidx.compose.ui.semantics.heading\n""",
    """import androidx.compose.ui.semantics.LiveRegionMode\nimport androidx.compose.ui.semantics.contentDescription\nimport androidx.compose.ui.semantics.heading\nimport androidx.compose.ui.semantics.liveRegion\n""",
)
replace_once(
    advanced,
    """fun StudioMixerCard(\n    project: StudioProject,\n    enabled: Boolean,\n    trackEditingEnabled: Boolean = enabled,\n) {\n""",
    """fun StudioMixerCard(\n    project: StudioProject,\n    enabled: Boolean,\n    trackEditingEnabled: Boolean = enabled,\n    selectedTrackId: String? = null,\n) {\n""",
)
replace_once(
    advanced,
    """            Text(\n                \"Cân âm\",\n                fontWeight = FontWeight.Bold,\n                modifier = Modifier.semantics { heading() },\n            )\n\n            project.tracks.forEach { track ->\n""",
    """            Text(\n                \"Cân âm\",\n                fontWeight = FontWeight.Bold,\n                modifier = Modifier.semantics { heading() },\n            )\n            project.tracks.firstOrNull { it.id == selectedTrackId && it.type != StudioTrackType.BEAT }?.let { selected ->\n                Text(\n                    \"Đang thao tác: ${friendlyTrackName(selected)}\",\n                    style = MaterialTheme.typography.labelMedium,\n                    modifier = Modifier.semantics {\n                        liveRegion = LiveRegionMode.Polite\n                        contentDescription = \"Lớp đang thao tác là ${friendlyTrackName(selected)}\"\n                    },\n                )\n            }\n\n            project.tracks.forEach { track ->\n""",
)
replace_once(
    advanced,
    """                        if (track.type != StudioTrackType.BEAT) {\n                            OutlinedButton(\n""",
    """                        if (track.type != StudioTrackType.BEAT) {\n                            FilterChip(\n                                selected = selectedTrackId == track.id,\n                                onClick = { StudioSessionRuntime.selectTrack(track.id) },\n                                enabled = trackEditingEnabled,\n                                label = { Text(if (selectedTrackId == track.id) \"Lớp đang thao tác\" else \"Chọn lớp này\") },\n                                modifier = Modifier\n                                    .fillMaxWidth()\n                                    .semantics {\n                                        contentDescription = \"Chọn $name làm lớp đang thao tác\"\n                                        stateDescription = if (selectedTrackId == track.id) \"đang chọn\" else \"chưa chọn\"\n                                    },\n                            )\n                            OutlinedButton(\n""",
)

recording_test = "app/src/test/java/com/aistudio/mediatool/feature/studio/data/StudioRecordingTargetTest.kt"
replace_once(
    recording_test,
    """    @Test\n    fun punchRequestKeepsTheRequestedExistingTrack() {\n""",
    """    @Test\n    fun explicitRoleCreatesBackingVocalWithAccessibleName() {\n        val project = projectWithTracks(beatTrack())\n\n        val first = project.selectRecordingTrack(\n            StudioRecordingTargetRequest.NewLayerForRole(StudioTrackType.BACKING_VOCAL),\n        )\n        val second = first.project.selectRecordingTrack(\n            StudioRecordingTargetRequest.NewLayerForRole(StudioTrackType.BACKING_VOCAL),\n        )\n\n        assertEquals(StudioTrackType.BACKING_VOCAL, first.track.type)\n        assertEquals(\"Giọng bè\", first.track.name)\n        assertEquals(\"Giọng bè 2\", second.track.name)\n        assertTrue(first.track.isAutoRecordingLayer())\n        assertTrue(second.track.isAutoRecordingLayer())\n    }\n\n    @Test\n    fun explicitRoleRequestIsOneShotAndKeepsTheRole() {\n        StudioRecordingTargetRequests.requestNewLayer(StudioTrackType.ADLIB)\n\n        assertEquals(\n            StudioRecordingTargetRequest.NewLayerForRole(StudioTrackType.ADLIB),\n            StudioRecordingTargetRequests.consume(),\n        )\n        assertNull(StudioRecordingTargetRequests.consume())\n    }\n\n    @Test\n    fun punchRequestKeepsTheRequestedExistingTrack() {\n""",
)

working_test = Path("app/src/test/java/com/aistudio/mediatool/feature/studio/data/StudioWorkingTrackTest.kt")
if working_test.exists():
    raise SystemExit(f"{working_test}: already exists")
working_test.write_text('''package com.aistudio.mediatool.feature.studio.data\n\nimport com.aistudio.mediatool.feature.studio.domain.StudioClip\nimport com.aistudio.mediatool.feature.studio.domain.StudioProject\nimport com.aistudio.mediatool.feature.studio.domain.StudioTake\nimport com.aistudio.mediatool.feature.studio.domain.StudioTrack\nimport com.aistudio.mediatool.feature.studio.domain.StudioTrackType\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertNull\nimport org.junit.Test\n\nclass StudioWorkingTrackTest {\n    @Test\n    fun selectedClipWinsSoTimelineMixerAndPunchAgree() {\n        val project = project()\n\n        assertEquals(\n            \"backing\",\n            project.resolveWorkingTrackId(\n                selectedTrackId = \"main\",\n                selectedClipId = \"backing-clip\",\n                requireRecordedContent = true,\n            ),\n        )\n    }\n\n    @Test\n    fun selectedTrackWorksWithoutAClip() {\n        val project = project()\n\n        assertEquals(\n            \"backing\",\n            project.resolveWorkingTrackId(\"backing\", null),\n        )\n    }\n\n    @Test\n    fun punchSkipsEmptySelectedLayerAndFallsBackToRecordedLayer() {\n        val project = project().copy(\n            tracks = project().tracks + StudioTrack(\n                id = \"empty\",\n                type = StudioTrackType.ADLIB,\n                name = \"Giọng phụ\",\n            ),\n        )\n\n        assertEquals(\n            \"main\",\n            project.resolveWorkingTrackId(\"empty\", null, requireRecordedContent = true),\n        )\n    }\n\n    @Test\n    fun beatCanNeverBecomeWorkingVoiceTrack() {\n        val project = StudioProject(\n            id = \"p\",\n            name = \"P\",\n            createdAt = 0L,\n            updatedAt = 0L,\n            tracks = listOf(StudioTrack(\"beat\", StudioTrackType.BEAT, \"Beat\")),\n        )\n\n        assertNull(project.resolveWorkingTrackId(\"beat\", null))\n    }\n\n    private fun project(): StudioProject {\n        val mainClip = StudioClip(\n            id = \"main-clip\",\n            sourceAssetId = \"a1\",\n            timelineStartFrame = 0L,\n            sourceEndFrame = 48_000L,\n        )\n        val backingClip = StudioClip(\n            id = \"backing-clip\",\n            sourceAssetId = \"a2\",\n            timelineStartFrame = 48_000L,\n            sourceEndFrame = 48_000L,\n        )\n        return StudioProject(\n            id = \"p\",\n            name = \"P\",\n            createdAt = 0L,\n            updatedAt = 0L,\n            tracks = listOf(\n                StudioTrack(id = \"beat\", type = StudioTrackType.BEAT, name = \"Beat\"),\n                StudioTrack(\n                    id = \"main\",\n                    type = StudioTrackType.VOCAL,\n                    name = \"Giọng chính\",\n                    clips = listOf(mainClip),\n                ),\n                StudioTrack(\n                    id = \"backing\",\n                    type = StudioTrackType.BACKING_VOCAL,\n                    name = \"Giọng bè\",\n                    clips = listOf(backingClip),\n                ),\n            ),\n        )\n    }\n}\n''')

print("Studio recording-role and shared selected-track patch applied")
