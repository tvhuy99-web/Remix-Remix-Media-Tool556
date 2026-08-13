package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot recording intent passed from the Studio UI to the repository.
 *
 * The realtime runtime deliberately stays unaware of project-layer creation. A normal record
 * requests a fresh vocal layer, while punch recording requests an existing track. The repository
 * consumes the request exactly once when it creates the pending take.
 */
sealed interface StudioRecordingTargetRequest {
    data object NewLayer : StudioRecordingTargetRequest
    data class ExistingTrack(val trackId: String) : StudioRecordingTargetRequest
}

object StudioRecordingTargetRequests {
    private val pending = AtomicReference<StudioRecordingTargetRequest?>(null)

    fun requestNewLayer() {
        pending.set(StudioRecordingTargetRequest.NewLayer)
    }

    fun requestExistingTrack(trackId: String?) {
        pending.set(trackId?.let(StudioRecordingTargetRequest::ExistingTrack))
    }

    internal fun consume(): StudioRecordingTargetRequest? = pending.getAndSet(null)

    internal fun clear() {
        pending.set(null)
    }
}

internal data class StudioRecordingTrackSelection(
    val project: StudioProject,
    val track: StudioTrack,
    val createdNewTrack: Boolean,
)

internal fun StudioProject.selectRecordingTrack(
    request: StudioRecordingTargetRequest?,
): StudioRecordingTrackSelection {
    if (request is StudioRecordingTargetRequest.ExistingTrack) {
        val existing = requireNotNull(tracks.firstOrNull { it.id == request.trackId }) {
            "Không tìm thấy lớp giọng cần thu sửa"
        }
        require(existing.type != StudioTrackType.BEAT) { "Không thể thu đè lên nhạc nền" }
        return StudioRecordingTrackSelection(this, existing, createdNewTrack = false)
    }

    if (request == StudioRecordingTargetRequest.NewLayer) {
        val layerNumber = tracks.count(StudioTrack::isRecordingVoiceLayer) + 1
        val track = StudioTrack(
            id = UUID.randomUUID().toString(),
            type = if (layerNumber == 1) StudioTrackType.VOCAL else StudioTrackType.OTHER,
            name = if (layerNumber == 1) "Giọng chính" else "Giọng $layerNumber",
        )
        return StudioRecordingTrackSelection(
            project = copy(tracks = tracks + track),
            track = track,
            createdNewTrack = true,
        )
    }

    // Backward-compatible fallback for callers that do not participate in the one-shot policy.
    val existing = tracks.firstOrNull { it.type == StudioTrackType.VOCAL }
    if (existing != null) {
        return StudioRecordingTrackSelection(this, existing, createdNewTrack = false)
    }
    val track = StudioTrack(
        id = UUID.randomUUID().toString(),
        type = StudioTrackType.VOCAL,
        name = "Giọng chính",
    )
    return StudioRecordingTrackSelection(
        project = copy(tracks = tracks + track),
        track = track,
        createdNewTrack = true,
    )
}

internal fun StudioTrack.isAutoRecordingLayer(): Boolean =
    name == "Giọng chính" || (type == StudioTrackType.OTHER && name.matches(Regex("Giọng \\d+")))

private fun StudioTrack.isRecordingVoiceLayer(): Boolean = when (type) {
    StudioTrackType.VOCAL,
    StudioTrackType.BACKING_VOCAL,
    StudioTrackType.ADLIB,
    -> true
    StudioTrackType.OTHER -> name.startsWith("Giọng ")
    StudioTrackType.BEAT,
    StudioTrackType.INSTRUMENT,
    -> false
}
