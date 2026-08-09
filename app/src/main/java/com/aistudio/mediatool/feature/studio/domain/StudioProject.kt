package com.aistudio.mediatool.feature.studio.domain

const val STUDIO_PROJECT_SCHEMA_VERSION = 1
const val STUDIO_TIMELINE_SAMPLE_RATE = 48_000

enum class StudioAssetKind {
    BEAT,
    TAKE,
    DERIVED,
}

enum class StudioTrackType {
    BEAT,
    VOCAL,
    BACKING_VOCAL,
    ADLIB,
    INSTRUMENT,
    OTHER,
}

enum class StudioTakeStatus {
    RECORDING,
    COMPLETE,
    RECOVERED,
    FAILED,
}

data class StudioAsset(
    val id: String,
    val kind: StudioAssetKind,
    val relativePath: String,
    val displayName: String,
    val mimeType: String? = null,
    val bytes: Long = 0L,
    val sourceAssetId: String? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val durationFrames: Long? = null,
)

data class StudioTake(
    val id: String,
    val assetId: String,
    val recordedTimelineFrame: Long,
    val recordedFrames: Long,
    val inputDeviceId: Int? = null,
    val inputSampleRate: Int,
    val latencyCompensationFrames: Long = 0L,
    val status: StudioTakeStatus,
)

data class StudioClip(
    val id: String,
    val sourceAssetId: String,
    val sourceTakeId: String? = null,
    val timelineStartFrame: Long,
    val sourceStartFrame: Long,
    val sourceEndFrame: Long,
    val gainDb: Float = 0f,
    val fadeInFrames: Long = 0L,
    val fadeOutFrames: Long = 0L,
)

data class StudioTrack(
    val id: String,
    val type: StudioTrackType,
    val name: String,
    val primaryAssetId: String? = null,
    val activeTakeId: String? = null,
    val volumeDb: Float = 0f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val locked: Boolean = false,
    val takes: List<StudioTake> = emptyList(),
    val clips: List<StudioClip> = emptyList(),
)

data class StudioProject(
    val schemaVersion: Int = STUDIO_PROJECT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val timelineSampleRate: Int = STUDIO_TIMELINE_SAMPLE_RATE,
    val beatAssetId: String? = null,
    val assets: List<StudioAsset> = emptyList(),
    val tracks: List<StudioTrack> = emptyList(),
) {
    fun asset(assetId: String?): StudioAsset? =
        assetId?.let { id -> assets.firstOrNull { it.id == id } }

    fun beatAsset(): StudioAsset? = asset(beatAssetId)

    fun take(takeId: String?): StudioTake? = takeId?.let { id ->
        tracks.asSequence().flatMap { it.takes.asSequence() }.firstOrNull { it.id == id }
    }
}
