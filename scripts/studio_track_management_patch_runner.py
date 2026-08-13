#!/usr/bin/env python3
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> bool:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if new in text:
        return False
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one patch anchor in {path}, found {count}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")
    return True


def patch_runtime() -> bool:
    path = "app/src/main/java/com/aistudio/mediatool/feature/studio/audio/StudioSessionRuntime.kt"
    changed = False
    changed |= replace_once(
        path,
        "import com.aistudio.mediatool.feature.studio.data.StudioEditEngine\n"
        "import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository\n",
        "import com.aistudio.mediatool.feature.studio.data.StudioEditEngine\n"
        "import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository\n"
        "import com.aistudio.mediatool.feature.studio.data.StudioTrackEditor\n",
    )

    changed |= replace_once(
        path,
        '''            val vocalTrack = project.tracks.firstOrNull { it.type == StudioTrackType.VOCAL }\n            if (vocalTrack == null || vocalTrack.takes.isEmpty()) {\n                _state.value = current.copy(errorMessage = "Cần ít nhất một vocal take trước khi Punch")\n                return@launch\n            }\n''',
        '''            val editableVoiceTrack = project.tracks.firstOrNull { track ->\n                track.type != StudioTrackType.BEAT &&\n                    (track.takes.isNotEmpty() || track.clips.isNotEmpty())\n            }\n            if (editableVoiceTrack == null) {\n                _state.value = current.copy(errorMessage = "Cần ít nhất một lớp giọng trước khi thu sửa đoạn")\n                return@launch\n            }\n''',
    )
    changed |= replace_once(
        path,
        '''                trackId = vocalTrack.id,\n''',
        '''                trackId = editableVoiceTrack.id,\n''',
    )

    changed |= replace_once(
        path,
        '''    fun setMasterGain(gainDb: Float) {\n''',
        '''    fun renameTrack(trackId: String, name: String) {\n        scope.launch { applyTrackEdit("rename_track") { StudioTrackEditor.rename(it, trackId, name) } }\n    }\n\n    fun setTrackRole(trackId: String, type: StudioTrackType) {\n        scope.launch { applyTrackEdit("track_role") { StudioTrackEditor.setRole(it, trackId, type) } }\n    }\n\n    fun duplicateTrack(trackId: String) {\n        scope.launch { applyTrackEdit("duplicate_track") { StudioTrackEditor.duplicate(it, trackId) } }\n    }\n\n    fun deleteTrack(trackId: String) {\n        scope.launch { applyTrackEdit("delete_track") { StudioTrackEditor.delete(it, trackId) } }\n    }\n\n    fun moveTrack(trackId: String, direction: Int) {\n        scope.launch { applyTrackEdit("move_track") { StudioTrackEditor.move(it, trackId, direction) } }\n    }\n\n    fun setMasterGain(gainDb: Float) {\n''',
    )

    changed |= replace_once(
        path,
        '''    private fun adjustSelectedFade(millisecondsDelta: Long, isFadeIn: Boolean) {\n''',
        '''    private fun applyTrackEdit(\n        label: String,\n        transform: (StudioProject) -> StudioProject,\n    ) {\n        val current = _state.value\n        if (current.status == StudioSessionStatus.RECORDING || current.isBusy) return\n        val repo = repository ?: return\n        val project = current.project ?: return\n        runCatching {\n            val changed = transform(project)\n            if (changed == project) return@runCatching project\n            pushBounded(undoStack, project)\n            redoStack.clear()\n            val saved = repo.save(changed)\n            syncPlaybackPlan(saved)\n            saved\n        }.onSuccess { saved ->\n            _state.value = _state.value.copy(\n                project = saved,\n                selectedClipId = current.selectedClipId?.takeIf { id -> saved.hasClip(id) },\n                durationFrames = projectDurationFrames(saved),\n                canUndo = undoStack.isNotEmpty(),\n                canRedo = redoStack.isNotEmpty(),\n                errorMessage = null,\n            )\n            DiagnosticLogger.info(\n                component = TAG,\n                event = "studio_track_edit",\n                sessionId = saved.id,\n                fields = mapOf("command" to label),\n            )\n        }.onFailure { error ->\n            _state.value = _state.value.copy(\n                errorMessage = error.message ?: "Không thể thay đổi lớp âm thanh",\n            )\n        }\n    }\n\n    private fun adjustSelectedFade(millisecondsDelta: Long, isFadeIn: Boolean) {\n''',
    )
    return changed


def patch_workspace() -> bool:
    path = "app/src/main/java/com/aistudio/mediatool/feature/studio/ui/StudioWorkspaceScreen.kt"
    changed = False
    changed |= replace_once(
        path,
        "                StudioMixerCard(project = loaded, enabled = mixerEnabled)\n",
        "                StudioMixerCard(\n"
        "                    project = loaded,\n"
        "                    enabled = mixerEnabled,\n"
        "                    trackEditingEnabled = editEnabled,\n"
        "                )\n",
    )
    changed |= replace_once(
        path,
        '''    val hasVocal = project.tracks.any {\n        it.type == StudioTrackType.VOCAL && it.takes.isNotEmpty()\n    }\n''',
        '''    val hasVocal = project.tracks.any { track ->\n        track.type != StudioTrackType.BEAT &&\n            (track.takes.isNotEmpty() || track.clips.isNotEmpty())\n    }\n''',
    )
    return changed


def patch_advanced_controls() -> bool:
    path = "app/src/main/java/com/aistudio/mediatool/feature/studio/ui/StudioAdvancedControls.kt"
    changed = False
    changed |= replace_once(
        path,
        "import androidx.compose.material3.Button\n",
        "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.Button\n",
    )
    changed |= replace_once(
        path,
        "import androidx.compose.material3.OutlinedButton\n",
        "import androidx.compose.material3.OutlinedButton\nimport androidx.compose.material3.OutlinedTextField\n",
    )
    changed |= replace_once(
        path,
        '''fun StudioMixerCard(\n    project: StudioProject,\n    enabled: Boolean,\n) {\n''',
        '''fun StudioMixerCard(\n    project: StudioProject,\n    enabled: Boolean,\n    trackEditingEnabled: Boolean = enabled,\n) {\n''',
    )

    state_anchor = '''                    var pan by remember(track.id, track.pan) {\n                        mutableFloatStateOf(track.pan.coerceIn(-1f, 1f))\n                    }\n                    val name = friendlyTrackName(track)\n\n                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {\n                        Text(name, fontWeight = FontWeight.SemiBold)\n'''
    state_replacement = '''                    var pan by remember(track.id, track.pan) {\n                        mutableFloatStateOf(track.pan.coerceIn(-1f, 1f))\n                    }\n                    var showManagement by rememberSaveable(track.id) { mutableStateOf(false) }\n                    var editName by rememberSaveable(track.id, track.name) { mutableStateOf(track.name) }\n                    var confirmDelete by rememberSaveable(track.id) { mutableStateOf(false) }\n                    val name = friendlyTrackName(track)\n\n                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {\n                        Text(name, fontWeight = FontWeight.SemiBold)\n\n                        if (track.type != StudioTrackType.BEAT) {\n                            OutlinedButton(\n                                onClick = { showManagement = !showManagement },\n                                enabled = trackEditingEnabled,\n                                modifier = Modifier.fillMaxWidth(),\n                            ) {\n                                Text(if (showManagement) "Ẩn quản lý lớp" else "Quản lý lớp")\n                            }\n\n                            if (showManagement) {\n                                Text("Vai trò", style = MaterialTheme.typography.labelLarge)\n                                Row(\n                                    modifier = Modifier\n                                        .fillMaxWidth()\n                                        .horizontalScroll(rememberScrollState()),\n                                    horizontalArrangement = Arrangement.spacedBy(8.dp),\n                                ) {\n                                    TrackRoleChip("Chính", StudioTrackType.VOCAL, track, trackEditingEnabled)\n                                    TrackRoleChip("Bè", StudioTrackType.BACKING_VOCAL, track, trackEditingEnabled)\n                                    TrackRoleChip("Phụ", StudioTrackType.ADLIB, track, trackEditingEnabled)\n                                    TrackRoleChip("Song ca / khác", StudioTrackType.OTHER, track, trackEditingEnabled)\n                                }\n\n                                OutlinedTextField(\n                                    value = editName,\n                                    onValueChange = { editName = it.take(48) },\n                                    label = { Text("Tên lớp") },\n                                    singleLine = true,\n                                    enabled = trackEditingEnabled,\n                                    modifier = Modifier.fillMaxWidth(),\n                                )\n                                Button(\n                                    onClick = { StudioSessionRuntime.renameTrack(track.id, editName) },\n                                    enabled = trackEditingEnabled && editName.isNotBlank() && editName.trim() != track.name,\n                                    modifier = Modifier.fillMaxWidth(),\n                                ) { Text("Lưu tên lớp") }\n\n                                Row(\n                                    modifier = Modifier.fillMaxWidth(),\n                                    horizontalArrangement = Arrangement.spacedBy(8.dp),\n                                ) {\n                                    OutlinedButton(\n                                        onClick = { StudioSessionRuntime.moveTrack(track.id, -1) },\n                                        enabled = trackEditingEnabled,\n                                        modifier = Modifier.weight(1f),\n                                    ) { Text("Đưa lên") }\n                                    OutlinedButton(\n                                        onClick = { StudioSessionRuntime.moveTrack(track.id, 1) },\n                                        enabled = trackEditingEnabled,\n                                        modifier = Modifier.weight(1f),\n                                    ) { Text("Đưa xuống") }\n                                }\n                                Row(\n                                    modifier = Modifier.fillMaxWidth(),\n                                    horizontalArrangement = Arrangement.spacedBy(8.dp),\n                                ) {\n                                    OutlinedButton(\n                                        onClick = { StudioSessionRuntime.duplicateTrack(track.id) },\n                                        enabled = trackEditingEnabled,\n                                        modifier = Modifier.weight(1f),\n                                    ) { Text("Nhân bản") }\n                                    OutlinedButton(\n                                        onClick = { confirmDelete = true },\n                                        enabled = trackEditingEnabled,\n                                        modifier = Modifier.weight(1f),\n                                    ) { Text("Xóa lớp") }\n                                }\n                            }\n\n                            if (confirmDelete) {\n                                AlertDialog(\n                                    onDismissRequest = { confirmDelete = false },\n                                    title = { Text("Xóa lớp âm thanh?") },\n                                    text = { Text("File thu gốc vẫn được giữ trong dự án và thao tác này có thể Hoàn tác.") },\n                                    confirmButton = {\n                                        Button(onClick = {\n                                            confirmDelete = false\n                                            StudioSessionRuntime.deleteTrack(track.id)\n                                        }) { Text("Xóa lớp") }\n                                    },\n                                    dismissButton = {\n                                        OutlinedButton(onClick = { confirmDelete = false }) { Text("Giữ lại") }\n                                    },\n                                )\n                            }\n                        }\n'''
    changed |= replace_once(path, state_anchor, state_replacement)

    changed |= replace_once(
        path,
        '''private fun friendlyTrackName(track: StudioTrack): String = when (track.type) {\n    StudioTrackType.BEAT -> "Nhạc nền"\n    StudioTrackType.VOCAL -> "Giọng chính"\n    StudioTrackType.BACKING_VOCAL -> "Giọng bè"\n    StudioTrackType.ADLIB -> "Giọng phụ"\n    StudioTrackType.INSTRUMENT -> "Nhạc cụ"\n    StudioTrackType.OTHER -> track.name.ifBlank { "Âm thanh khác" }\n}\n''',
        '''@Composable\nprivate fun TrackRoleChip(\n    label: String,\n    type: StudioTrackType,\n    track: StudioTrack,\n    enabled: Boolean,\n) {\n    FilterChip(\n        selected = track.type == type,\n        onClick = { StudioSessionRuntime.setTrackRole(track.id, type) },\n        enabled = enabled,\n        label = { Text(label) },\n    )\n}\n\nprivate fun friendlyTrackName(track: StudioTrack): String = when {\n    track.type == StudioTrackType.BEAT -> "Nhạc nền"\n    track.name.isNotBlank() && !track.name.equals("Vocal", ignoreCase = true) -> track.name\n    track.type == StudioTrackType.VOCAL -> "Giọng chính"\n    track.type == StudioTrackType.BACKING_VOCAL -> "Giọng bè"\n    track.type == StudioTrackType.ADLIB -> "Giọng phụ"\n    track.type == StudioTrackType.INSTRUMENT -> "Nhạc cụ"\n    else -> "Âm thanh khác"\n}\n''',
    )
    return changed


def patch_timeline() -> bool:
    path = "app/src/main/java/com/aistudio/mediatool/feature/studio/ui/StudioTimeline.kt"
    return replace_once(
        path,
        '''private fun friendlyTrackName(track: StudioTrack): String = when (track.type) {\n    StudioTrackType.BEAT -> "Nhạc nền"\n    StudioTrackType.VOCAL -> "Giọng chính"\n    StudioTrackType.BACKING_VOCAL -> "Giọng bè"\n    StudioTrackType.ADLIB -> "Giọng phụ"\n    StudioTrackType.INSTRUMENT -> "Nhạc cụ"\n    StudioTrackType.OTHER -> track.name.ifBlank { "Âm thanh khác" }\n}\n''',
        '''private fun friendlyTrackName(track: StudioTrack): String = when {\n    track.type == StudioTrackType.BEAT -> "Nhạc nền"\n    track.name.isNotBlank() && !track.name.equals("Vocal", ignoreCase = true) -> track.name\n    track.type == StudioTrackType.VOCAL -> "Giọng chính"\n    track.type == StudioTrackType.BACKING_VOCAL -> "Giọng bè"\n    track.type == StudioTrackType.ADLIB -> "Giọng phụ"\n    track.type == StudioTrackType.INSTRUMENT -> "Nhạc cụ"\n    else -> "Âm thanh khác"\n}\n''',
    )


def patch_track_editor() -> bool:
    path = "app/src/main/java/com/aistudio/mediatool/feature/studio/data/StudioTrackEditor.kt"
    return replace_once(
        path,
        '''            clip.copy(id = UUID.randomUUID().toString())\n''',
        '''            clip.copy(\n                id = UUID.randomUUID().toString(),\n                sourceTakeId = null,\n            )\n''',
    )


def patch_track_editor_test() -> bool:
    path = "app/src/test/java/com/aistudio/mediatool/feature/studio/data/StudioTrackEditorTest.kt"
    changed = False
    changed |= replace_once(
        path,
        "import org.junit.Assert.assertNotEquals\n",
        "import org.junit.Assert.assertNotEquals\nimport org.junit.Assert.assertNull\n",
    )
    changed |= replace_once(
        path,
        '''        assertEquals(take.id, copy.clips.single().sourceTakeId)\n''',
        '''        assertNull(copy.clips.single().sourceTakeId)\n''',
    )
    return changed


def main() -> None:
    editor_changed = patch_track_editor()
    test_changed = patch_track_editor_test()
    changed = (
        patch_runtime()
        | patch_workspace()
        | patch_advanced_controls()
        | patch_timeline()
        | editor_changed
        | test_changed
    )
    if editor_changed:
        subprocess.run(
            ["git", "add", "app/src/main/java/com/aistudio/mediatool/feature/studio/data/StudioTrackEditor.kt"],
            check=True,
        )
    if test_changed:
        subprocess.run(
            ["git", "add", "app/src/test/java/com/aistudio/mediatool/feature/studio/data/StudioTrackEditorTest.kt"],
            check=True,
        )
    print("Studio track-management patch applied" if changed else "Studio track-management patch already applied")


if __name__ == "__main__":
    main()
