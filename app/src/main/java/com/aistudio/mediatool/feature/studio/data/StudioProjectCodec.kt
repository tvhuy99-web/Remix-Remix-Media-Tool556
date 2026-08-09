package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.STUDIO_PROJECT_SCHEMA_VERSION
import com.aistudio.mediatool.feature.studio.domain.STUDIO_TIMELINE_SAMPLE_RATE
import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTakeStatus
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import org.json.JSONArray
import org.json.JSONObject

object StudioProjectCodec {
    fun encode(project: StudioProject): String = JSONObject().apply {
        put("schemaVersion", project.schemaVersion)
        put("id", project.id)
        put("name", project.name)
        put("createdAt", project.createdAt)
        put("updatedAt", project.updatedAt)
        put("timelineSampleRate", project.timelineSampleRate)
        putNullable("beatAssetId", project.beatAssetId)
        put("assets", JSONArray().apply { project.assets.forEach { put(assetToJson(it)) } })
        put("tracks", JSONArray().apply { project.tracks.forEach { put(trackToJson(it)) } })
    }.toString(2)

    fun decode(raw: String): StudioProject {
        val json = JSONObject(raw)
        val schemaVersion = json.optInt("schemaVersion", STUDIO_PROJECT_SCHEMA_VERSION)
        require(schemaVersion in 1..STUDIO_PROJECT_SCHEMA_VERSION) {
            "Phiên bản dự án Studio chưa được hỗ trợ: $schemaVersion"
        }
        val id = json.getString("id").trim()
        require(id.isNotEmpty()) { "Dự án Studio thiếu id" }
        val name = json.optString("name").trim().ifEmpty { "Dự án Studio" }
        val sampleRate = json.optInt("timelineSampleRate", STUDIO_TIMELINE_SAMPLE_RATE)
            .takeIf { it > 0 } ?: STUDIO_TIMELINE_SAMPLE_RATE
        return StudioProject(
            schemaVersion = schemaVersion,
            id = id,
            name = name,
            createdAt = json.optLong("createdAt", 0L),
            updatedAt = json.optLong("updatedAt", 0L),
            timelineSampleRate = sampleRate,
            beatAssetId = json.nullableString("beatAssetId"),
            assets = json.optJSONArray("assets").mapObjects(::assetFromJson),
            tracks = json.optJSONArray("tracks").mapObjects(::trackFromJson),
        )
    }

    private fun assetToJson(asset: StudioAsset) = JSONObject().apply {
        put("id", asset.id)
        put("kind", asset.kind.name)
        put("relativePath", asset.relativePath)
        put("displayName", asset.displayName)
        putNullable("mimeType", asset.mimeType)
        put("bytes", asset.bytes)
        putNullable("sourceAssetId", asset.sourceAssetId)
        putNullable("sampleRate", asset.sampleRate)
        putNullable("channelCount", asset.channelCount)
        putNullable("durationFrames", asset.durationFrames)
    }

    private fun assetFromJson(json: JSONObject) = StudioAsset(
        id = json.getString("id"),
        kind = enumOrDefault(json.optString("kind"), StudioAssetKind.DERIVED),
        relativePath = json.getString("relativePath"),
        displayName = json.optString("displayName").ifBlank { "Audio" },
        mimeType = json.nullableString("mimeType"),
        bytes = json.optLong("bytes", 0L),
        sourceAssetId = json.nullableString("sourceAssetId"),
        sampleRate = json.nullableInt("sampleRate"),
        channelCount = json.nullableInt("channelCount"),
        durationFrames = json.nullableLong("durationFrames"),
    )

    private fun trackToJson(track: StudioTrack) = JSONObject().apply {
        put("id", track.id)
        put("type", track.type.name)
        put("name", track.name)
        putNullable("primaryAssetId", track.primaryAssetId)
        putNullable("activeTakeId", track.activeTakeId)
        put("volumeDb", track.volumeDb.toDouble())
        put("pan", track.pan.toDouble())
        put("muted", track.muted)
        put("solo", track.solo)
        put("locked", track.locked)
        put("takes", JSONArray().apply { track.takes.forEach { put(takeToJson(it)) } })
        put("clips", JSONArray().apply { track.clips.forEach { put(clipToJson(it)) } })
    }

    private fun trackFromJson(json: JSONObject) = StudioTrack(
        id = json.getString("id"),
        type = enumOrDefault(json.optString("type"), StudioTrackType.OTHER),
        name = json.optString("name").ifBlank { "Track" },
        primaryAssetId = json.nullableString("primaryAssetId"),
        activeTakeId = json.nullableString("activeTakeId"),
        volumeDb = json.optDouble("volumeDb", 0.0).toFloat(),
        pan = json.optDouble("pan", 0.0).toFloat().coerceIn(-1f, 1f),
        muted = json.optBoolean("muted", false),
        solo = json.optBoolean("solo", false),
        locked = json.optBoolean("locked", false),
        takes = json.optJSONArray("takes").mapObjects(::takeFromJson),
        clips = json.optJSONArray("clips").mapObjects(::clipFromJson),
    )

    private fun takeToJson(take: StudioTake) = JSONObject().apply {
        put("id", take.id)
        put("assetId", take.assetId)
        put("recordedTimelineFrame", take.recordedTimelineFrame)
        put("recordedFrames", take.recordedFrames)
        putNullable("inputDeviceId", take.inputDeviceId)
        put("inputSampleRate", take.inputSampleRate)
        put("latencyCompensationFrames", take.latencyCompensationFrames)
        put("status", take.status.name)
    }

    private fun takeFromJson(json: JSONObject) = StudioTake(
        id = json.getString("id"),
        assetId = json.getString("assetId"),
        recordedTimelineFrame = json.optLong("recordedTimelineFrame", 0L),
        recordedFrames = json.optLong("recordedFrames", 0L),
        inputDeviceId = json.nullableInt("inputDeviceId"),
        inputSampleRate = json.optInt("inputSampleRate", STUDIO_TIMELINE_SAMPLE_RATE),
        latencyCompensationFrames = json.optLong("latencyCompensationFrames", 0L),
        status = enumOrDefault(json.optString("status"), StudioTakeStatus.FAILED),
    )

    private fun clipToJson(clip: StudioClip) = JSONObject().apply {
        put("id", clip.id)
        put("sourceAssetId", clip.sourceAssetId)
        putNullable("sourceTakeId", clip.sourceTakeId)
        put("timelineStartFrame", clip.timelineStartFrame)
        put("sourceStartFrame", clip.sourceStartFrame)
        put("sourceEndFrame", clip.sourceEndFrame)
        put("gainDb", clip.gainDb.toDouble())
        put("fadeInFrames", clip.fadeInFrames)
        put("fadeOutFrames", clip.fadeOutFrames)
    }

    private fun clipFromJson(json: JSONObject) = StudioClip(
        id = json.getString("id"),
        sourceAssetId = json.getString("sourceAssetId"),
        sourceTakeId = json.nullableString("sourceTakeId"),
        timelineStartFrame = json.optLong("timelineStartFrame", 0L),
        sourceStartFrame = json.optLong("sourceStartFrame", 0L),
        sourceEndFrame = json.optLong("sourceEndFrame", 0L),
        gainDb = json.optDouble("gainDb", 0.0).toFloat(),
        fadeInFrames = json.optLong("fadeInFrames", 0L),
        fadeOutFrames = json.optLong("fadeOutFrames", 0L),
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.nullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optJSONObject(index)?.let { add(transform(it)) }
            }
        }
    }
}
