package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StudioWorkingTrackState(
    val projectId: String? = null,
    val trackId: String? = null,
    val selectedClipTrackId: String? = null,
    val defaultFallbackTrackId: String? = null,
    val validTrackIds: Set<String> = emptySet(),
)

/**
 * Lightweight UI/session bridge for the voice layer currently being worked on.
 *
 * It is deliberately separate from the realtime audio engine. Timeline clip selection can update it,
 * Mixer can select it explicitly, and the one-shot punch target can consume the same decision.
 */
object StudioWorkingTrackSelection {
    private val _state = MutableStateFlow(StudioWorkingTrackState())
    val state: StateFlow<StudioWorkingTrackState> = _state.asStateFlow()

    fun sync(project: StudioProject?, selectedClipId: String?) {
        if (project == null) {
            _state.value = StudioWorkingTrackState()
            return
        }
        val editable = project.tracks.filter { it.type != StudioTrackType.BEAT }
        val validIds = editable.mapTo(linkedSetOf()) { it.id }
        val clipTrackId = selectedClipId?.let { clipId ->
            editable.firstOrNull { track -> track.clips.any { it.id == clipId } }?.id
        }
        val current = _state.value
        val currentTrackId = current.trackId?.takeIf {
            current.projectId == project.id && it in validIds
        }
        val fallback = editable.firstOrNull {
            it.type == StudioTrackType.VOCAL && (it.takes.isNotEmpty() || it.clips.isNotEmpty())
        }?.id ?: editable.firstOrNull { it.takes.isNotEmpty() || it.clips.isNotEmpty() }?.id
            ?: editable.firstOrNull()?.id
        _state.value = StudioWorkingTrackState(
            projectId = project.id,
            trackId = clipTrackId ?: currentTrackId ?: fallback,
            selectedClipTrackId = clipTrackId,
            defaultFallbackTrackId = fallback,
            validTrackIds = validIds,
        )
    }

    fun select(project: StudioProject, trackId: String) {
        val editable = project.tracks.filter { it.type != StudioTrackType.BEAT }
        require(editable.any { it.id == trackId }) { "Không tìm thấy lớp giọng đang thao tác" }
        val fallback = editable.firstOrNull {
            it.type == StudioTrackType.VOCAL && (it.takes.isNotEmpty() || it.clips.isNotEmpty())
        }?.id ?: editable.firstOrNull { it.takes.isNotEmpty() || it.clips.isNotEmpty() }?.id
            ?: editable.firstOrNull()?.id
        _state.value = StudioWorkingTrackState(
            projectId = project.id,
            trackId = trackId,
            selectedClipTrackId = null,
            defaultFallbackTrackId = fallback,
            validTrackIds = editable.mapTo(linkedSetOf()) { it.id },
        )
    }

    /**
     * The Workspace currently passes either the selected clip's track or its legacy main-vocal fallback.
     * A real selected-clip track wins; otherwise the explicit working-track choice wins over that fallback.
     */
    fun preferredPunchTrackId(workspaceTrackId: String?): String? {
        val current = _state.value
        current.selectedClipTrackId?.takeIf { it in current.validTrackIds }?.let { return it }
        if (
            workspaceTrackId != null &&
            workspaceTrackId in current.validTrackIds &&
            workspaceTrackId != current.defaultFallbackTrackId
        ) {
            return workspaceTrackId
        }
        return current.trackId?.takeIf { it in current.validTrackIds } ?: workspaceTrackId
    }

    internal fun clear() {
        _state.value = StudioWorkingTrackState()
    }
}
