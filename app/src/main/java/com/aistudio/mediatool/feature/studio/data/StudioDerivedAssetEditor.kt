package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import com.aistudio.mediatool.feature.studio.domain.latencyCompensatedPlacement

/** Pure project transform that swaps a processed DERIVED asset into the arrangement without mutating source Takes. */
object StudioDerivedAssetEditor {
    fun apply(project: StudioProject, sourceAssetId: String, derivedAssetId: String): StudioProject {
        require(sourceAssetId != derivedAssetId) { "Derived asset phải khác source" }
        val source = requireNotNull(project.asset(sourceAssetId)) { "Không tìm thấy source asset" }
        val derived = requireNotNull(project.asset(derivedAssetId)) { "Không tìm thấy derived asset" }
        require(derived.kind == StudioAssetKind.DERIVED) { "Asset được chọn không phải bản xử lý" }
        require(isDescendantOf(project, derived, sourceAssetId)) { "Derived asset không thuộc source đã chọn" }

        if (project.beatAssetId == sourceAssetId) {
            val tracks = project.tracks.map { track ->
                if (track.type == StudioTrackType.BEAT && track.primaryAssetId == sourceAssetId) {
                    track.copy(primaryAssetId = derivedAssetId)
                } else track
            }
            return project.copy(beatAssetId = derivedAssetId, tracks = tracks)
        }

        var changed = false
        val tracks = project.tracks.map { track ->
            val replacedClips = track.clips.map { clip ->
                if (clip.sourceAssetId == sourceAssetId) {
                    changed = true
                    remapClip(project, clip, sourceAssetId, derivedAssetId)
                } else clip
            }
            if (replacedClips != track.clips) return@map track.copy(clips = replacedClips)

            if (track.clips.isEmpty()) {
                val take = track.activeTakeId
                    ?.let { id -> track.takes.firstOrNull { it.id == id } }
                    ?: track.takes.lastOrNull()
                if (take?.assetId == sourceAssetId) {
                    val placement = take.latencyCompensatedPlacement(project.timelineSampleRate)
                    val sourceRate = source.sampleRate ?: take.inputSampleRate
                    val derivedRate = derived.sampleRate ?: sourceRate
                    val sourceStart = scaleFrames(placement.sourceStartFrame, sourceRate, derivedRate)
                    val sourceEnd = scaleFrames(placement.sourceEndFrame, sourceRate, derivedRate)
                        .coerceAtMost(derived.durationFrames ?: Long.MAX_VALUE)
                    require(sourceEnd > sourceStart) { "Derived asset quá ngắn để thay Active Take" }
                    changed = true
                    return@map track.copy(
                        primaryAssetId = derivedAssetId,
                        clips = listOf(
                            StudioClip(
                                id = "derived-${derivedAssetId.take(8)}-${take.id.take(8)}",
                                sourceAssetId = derivedAssetId,
                                sourceTakeId = take.id,
                                timelineStartFrame = placement.timelineStartFrame,
                                sourceStartFrame = sourceStart,
                                sourceEndFrame = sourceEnd,
                            ),
                        ),
                    )
                }
            }
            track
        }
        require(changed) { "Source asset chưa được dùng trong arrangement hiện tại" }
        return project.copy(tracks = tracks)
    }

    private fun remapClip(
        project: StudioProject,
        clip: StudioClip,
        sourceAssetId: String,
        derivedAssetId: String,
    ): StudioClip {
        val source = requireNotNull(project.asset(sourceAssetId))
        val derived = requireNotNull(project.asset(derivedAssetId))
        val sourceRate = source.sampleRate ?: project.timelineSampleRate
        val derivedRate = derived.sampleRate ?: sourceRate
        val mappedStart = scaleFrames(clip.sourceStartFrame, sourceRate, derivedRate)
        val mappedEnd = scaleFrames(clip.sourceEndFrame, sourceRate, derivedRate)
            .coerceAtMost(derived.durationFrames ?: Long.MAX_VALUE)
        require(mappedEnd > mappedStart) { "Derived asset không đủ dữ liệu cho clip" }
        return clip.copy(
            sourceAssetId = derivedAssetId,
            sourceStartFrame = mappedStart,
            sourceEndFrame = mappedEnd,
            fadeInFrames = scaleFrames(clip.fadeInFrames, sourceRate, derivedRate),
            fadeOutFrames = scaleFrames(clip.fadeOutFrames, sourceRate, derivedRate),
        )
    }

    private fun isDescendantOf(project: StudioProject, asset: com.aistudio.mediatool.feature.studio.domain.StudioAsset, sourceId: String): Boolean {
        var current = asset.sourceAssetId
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            if (current == sourceId) return true
            current = project.asset(current)?.sourceAssetId
        }
        return false
    }

    private fun scaleFrames(frames: Long, fromRate: Int, toRate: Int): Long {
        if (frames <= 0L || fromRate <= 0 || toRate <= 0 || fromRate == toRate) return frames.coerceAtLeast(0L)
        return kotlin.math.round(frames.toDouble() * toRate.toDouble() / fromRate.toDouble()).toLong().coerceAtLeast(0L)
    }
}
