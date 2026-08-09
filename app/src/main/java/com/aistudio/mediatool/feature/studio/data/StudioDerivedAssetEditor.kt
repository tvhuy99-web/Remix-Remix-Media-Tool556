package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import com.aistudio.mediatool.feature.studio.domain.latencyCompensatedPlacement

/** Pure project transforms for non-destructive derived assets. Source files and Takes are never mutated. */
object StudioDerivedAssetEditor {
    fun apply(project: StudioProject, sourceAssetId: String, derivedAssetId: String): StudioProject {
        require(sourceAssetId != derivedAssetId) { "Derived asset phải khác source" }
        val source = requireNotNull(project.asset(sourceAssetId)) { "Không tìm thấy source asset" }
        val derived = requireNotNull(project.asset(derivedAssetId)) { "Không tìm thấy derived asset" }
        require(derived.kind == StudioAssetKind.DERIVED) { "Asset được chọn không phải bản xử lý" }
        require(isDescendantOf(project, derived, sourceAssetId)) { "Derived asset không thuộc source đã chọn" }
        return replace(project, source, derived, allowMaterializeTake = true)
    }

    fun restoreSource(project: StudioProject, derivedAssetId: String): StudioProject {
        val derived = requireNotNull(project.asset(derivedAssetId)) { "Không tìm thấy derived asset" }
        require(derived.kind == StudioAssetKind.DERIVED) { "Asset được chọn không phải bản xử lý" }
        val sourceId = requireNotNull(derived.sourceAssetId) { "Derived asset không còn thông tin source" }
        val source = requireNotNull(project.asset(sourceId)) { "Source asset không còn trong project" }
        return replace(project, derived, source, allowMaterializeTake = false)
    }

    private fun replace(
        project: StudioProject,
        current: StudioAsset,
        replacement: StudioAsset,
        allowMaterializeTake: Boolean,
    ): StudioProject {
        if (project.beatAssetId == current.id) {
            val tracks = project.tracks.map { track ->
                if (track.type == StudioTrackType.BEAT && track.primaryAssetId == current.id) {
                    track.copy(primaryAssetId = replacement.id)
                } else track
            }
            return project.copy(beatAssetId = replacement.id, tracks = tracks)
        }

        var changed = false
        val tracks = project.tracks.map { track ->
            var primaryAssetId = track.primaryAssetId
            if (primaryAssetId == current.id) {
                primaryAssetId = replacement.id
                changed = true
            }
            val replacedClips = track.clips.map { clip ->
                if (clip.sourceAssetId == current.id) {
                    changed = true
                    remapClip(project, clip, current, replacement)
                } else clip
            }
            if (replacedClips != track.clips || primaryAssetId != track.primaryAssetId) {
                return@map track.copy(primaryAssetId = primaryAssetId, clips = replacedClips)
            }

            if (allowMaterializeTake && track.clips.isEmpty()) {
                val take = track.activeTakeId
                    ?.let { id -> track.takes.firstOrNull { it.id == id } }
                    ?: track.takes.lastOrNull()
                if (take?.assetId == current.id) {
                    val placement = take.latencyCompensatedPlacement(project.timelineSampleRate)
                    val currentRate = current.sampleRate ?: take.inputSampleRate
                    val replacementRate = replacement.sampleRate ?: currentRate
                    val sourceStart = scaleFrames(placement.sourceStartFrame, currentRate, replacementRate)
                    val sourceEnd = scaleFrames(placement.sourceEndFrame, currentRate, replacementRate)
                        .coerceAtMost(replacement.durationFrames ?: Long.MAX_VALUE)
                    require(sourceEnd > sourceStart) { "Derived asset quá ngắn để thay Active Take" }
                    changed = true
                    return@map track.copy(
                        primaryAssetId = replacement.id,
                        clips = listOf(
                            StudioClip(
                                id = "derived-${replacement.id.take(8)}-${take.id.take(8)}",
                                sourceAssetId = replacement.id,
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
        require(changed) { "Asset chưa được dùng trong arrangement hiện tại" }
        return project.copy(tracks = tracks)
    }

    private fun remapClip(
        project: StudioProject,
        clip: StudioClip,
        current: StudioAsset,
        replacement: StudioAsset,
    ): StudioClip {
        val currentRate = current.sampleRate ?: project.timelineSampleRate
        val replacementRate = replacement.sampleRate ?: currentRate
        val mappedStart = scaleFrames(clip.sourceStartFrame, currentRate, replacementRate)
        val mappedEnd = scaleFrames(clip.sourceEndFrame, currentRate, replacementRate)
            .coerceAtMost(replacement.durationFrames ?: Long.MAX_VALUE)
        require(mappedEnd > mappedStart) { "Replacement asset không đủ dữ liệu cho clip" }
        return clip.copy(
            sourceAssetId = replacement.id,
            sourceStartFrame = mappedStart,
            sourceEndFrame = mappedEnd,
            fadeInFrames = scaleFrames(clip.fadeInFrames, currentRate, replacementRate),
            fadeOutFrames = scaleFrames(clip.fadeOutFrames, currentRate, replacementRate),
        )
    }

    private fun isDescendantOf(project: StudioProject, asset: StudioAsset, sourceId: String): Boolean {
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
