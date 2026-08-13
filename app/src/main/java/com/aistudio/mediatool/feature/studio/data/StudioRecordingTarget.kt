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
    /** Legacy fresh-layer request kept for callers that construct the request directly. */
    data object NewLayer : StudioRecordingTargetRequest
    data class NewLayerForRole(val type: StudioTrackType) : StudioRecordingTargetRequest
    data class ExistingTrack(val trackId: String) : StudioRecordingTargetRequest
}

object StudioRecordingTargetRequests {
    private val pending = AtomicReference<StudioRecordingTargetRequest?>(null)
    private val nextNewLayerRole = AtomicReference(StudioTrackType.VOCAL)

    /** The normal REC button uses the role chosen in the Studio controls. */
    fun requestNewLayer() {
        pending.set(StudioRecordingTargetRequest.NewLayerForRole(nextNewLayerRole.get()))
    }

    fun requestNewLayer(type: StudioTrackType) {
        setNextNewLayerRole(type)
        pending.set(StudioRecordingTargetRequest.NewLayerForRole(type))
    }

    fun setNextNewLayerRole(type: StudioTrackType) {
        require(type in RECORDABLE_VOICE_TYPES) { "Loại lớp này không dùng để thu giọng" }
        nextNewLayerRole.set(type)
    }

    fun nextNewLayerRole(): StudioTrackType = nextNewLayerRole.get()

    fun requestExistingTrack(trackId: String?) {
        val preferredTrackId = StudioWorkingTrackSelection.preferredPunchTrackId(trackId)
        pending.set(preferredTrackId?.let { StudioRecordingTargetRequest.ExistingTrack(it) })
    }

    internal fun consume(): StudioRecordingTargetRequest? = pending.getAndSet(null)

    internal fun clear() {
        pending.set(null)
        nextNewLayerRole.set(StudioTrackType.VOCAL)
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

    if (request is StudioRecordingTargetRequest.NewLayerForRole) {
        val ordinal = tracks.count { it.type == request.type } + 1
        val track = StudioTrack(
            id = UUID.randomUUID().toString(),
            type = request.type,
            name = recordingLayerName(request.type, ordinal),
        )
        return StudioRecordingTrackSelection(
            project = copy(tracks = tracks + track),
            track = track,
            createdNewTrack = true,
        )
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

private val RECORDABLE_VOICE_TYPES = setOf(
    StudioTrackType.VOCAL,
    StudioTrackType.BACKING_VOCAL,
    StudioTrackType.ADLIB,
    StudioTrackType.OTHER,
)

internal fun recordingLayerName(type: StudioTrackType, ordinal: Int): String {
    require(type in RECORDABLE_VOICE_TYPES) { "Loại lớp này không dùng để thu giọng" }
    require(ordinal >= 1) { "Số thứ tự lớp phải từ 1" }
    val base = when (type) {
        StudioTrackType.VOCAL -> "Giọng chính"
        StudioTrackType.BACKING_VOCAL -> "Giọng bè"
        StudioTrackType.ADLIB -> "Giọng phụ"
        StudioTrackType.OTHER -> "Song ca / khác"
        StudioTrackType.BEAT,
        StudioTrackType.INSTRUMENT,
        -> error("Loại lớp này không dùng để thu giọng")
    }
    return if (ordinal == 1) base else "$base $ordinal"
}

internal fun StudioTrack.isAutoRecordingLayer(): Boolean =
    type in RECORDABLE_VOICE_TYPES && (
        name.matches(Regex("Giọng chính(?: \\d+)?")) ||
            name.matches(Regex("Giọng bè(?: \\d+)?")) ||
            name.matches(Regex("Giọng phụ(?: \\d+)?")) ||
            name.matches(Regex("Song ca / khác(?: \\d+)?")) ||
            name.matches(Regex("Giọng \\d+"))
        )

private fun StudioTrack.isRecordingVoiceLayer(): Boolean = when (type) {
    StudioTrackType.VOCAL,
    StudioTrackType.BACKING_VOCAL,
    StudioTrackType.ADLIB,
    -> true
    StudioTrackType.OTHER -> name.startsWith("Giọng ") || name.startsWith("Song ca / khác")
    StudioTrackType.BEAT,
    StudioTrackType.INSTRUMENT,
    -> false
}
