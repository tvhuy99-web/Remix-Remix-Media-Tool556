#!/usr/bin/env python3
from pathlib import Path

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


def patch_repository() -> bool:
    path = "app/src/main/java/com/aistudio/mediatool/feature/studio/data/StudioProjectRepository.kt"
    changed = False

    changed |= replace_once(
        path,
        "import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind\n"
        "import com.aistudio.mediatool.feature.studio.domain.StudioProSettings\n",
        "import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind\n"
        "import com.aistudio.mediatool.feature.studio.domain.StudioClip\n"
        "import com.aistudio.mediatool.feature.studio.domain.StudioProSettings\n",
    )
    changed |= replace_once(
        path,
        "import com.aistudio.mediatool.feature.studio.domain.StudioTrackType\n"
        "import java.io.File\n",
        "import com.aistudio.mediatool.feature.studio.domain.StudioTrackType\n"
        "import com.aistudio.mediatool.feature.studio.domain.latencyCompensatedPlacement\n"
        "import java.io.File\n",
    )

    old_begin = '''    fun beginTake(\n        projectId: String,\n        recordedTimelineFrame: Long,\n        inputSampleRate: Int,\n        inputDeviceId: Int?,\n        latencyCompensationFrames: Long = 0L,\n    ): PendingStudioTake {\n        var project = requireNotNull(load(projectId)) { "Không tìm thấy dự án Studio" }\n        var vocalTrack = project.tracks.firstOrNull { it.type == StudioTrackType.VOCAL }\n        if (vocalTrack == null) {\n            vocalTrack = StudioTrack(\n                id = UUID.randomUUID().toString(),\n                type = StudioTrackType.VOCAL,\n                name = "Vocal",\n            )\n            project = save(project.copy(tracks = project.tracks + vocalTrack))\n        }\n        return takeStore.begin(\n            projectId = project.id,\n            trackId = vocalTrack.id,\n            recordedTimelineFrame = recordedTimelineFrame,\n            inputSampleRate = inputSampleRate,\n            inputDeviceId = inputDeviceId,\n            latencyCompensationFrames = latencyCompensationFrames,\n            channelCount = 1,\n        )\n    }\n'''
    new_begin = '''    fun beginTake(\n        projectId: String,\n        recordedTimelineFrame: Long,\n        inputSampleRate: Int,\n        inputDeviceId: Int?,\n        latencyCompensationFrames: Long = 0L,\n    ): PendingStudioTake {\n        var project = requireNotNull(load(projectId)) { "Không tìm thấy dự án Studio" }\n        val selection = project.selectRecordingTrack(StudioRecordingTargetRequests.consume())\n        if (selection.project != project) {\n            project = save(selection.project)\n        }\n        return takeStore.begin(\n            projectId = project.id,\n            trackId = selection.track.id,\n            recordedTimelineFrame = recordedTimelineFrame,\n            inputSampleRate = inputSampleRate,\n            inputDeviceId = inputDeviceId,\n            latencyCompensationFrames = latencyCompensationFrames,\n            channelCount = 1,\n        )\n    }\n'''
    changed |= replace_once(path, old_begin, new_begin)

    changed |= replace_once(
        path,
        '            displayName = "Vocal Take $takeNumber",\n',
        '            displayName = "${baseTrack.name.ifBlank { "Vocal" }} · Bản $takeNumber",\n',
    )

    old_update = '''        val updatedTrack = baseTrack.copy(\n            primaryAssetId = if (activateTake) asset.id else baseTrack.primaryAssetId,\n            activeTakeId = if (activateTake) take.id else baseTrack.activeTakeId,\n            takes = baseTrack.takes + take,\n        )\n'''
    new_update = '''        val initialLayerClip = if (\n            baseTrack.type == StudioTrackType.OTHER &&\n            baseTrack.isAutoRecordingLayer() &&\n            baseTrack.takes.isEmpty() &&\n            baseTrack.clips.isEmpty()\n        ) {\n            val placement = take.latencyCompensatedPlacement(project.timelineSampleRate)\n            StudioClip(\n                id = UUID.randomUUID().toString(),\n                sourceAssetId = asset.id,\n                sourceTakeId = take.id,\n                timelineStartFrame = placement.timelineStartFrame,\n                sourceStartFrame = placement.sourceStartFrame,\n                sourceEndFrame = placement.sourceEndFrame,\n            )\n        } else {\n            null\n        }\n        val updatedTrack = baseTrack.copy(\n            primaryAssetId = if (activateTake) asset.id else baseTrack.primaryAssetId,\n            activeTakeId = if (activateTake) take.id else baseTrack.activeTakeId,\n            takes = baseTrack.takes + take,\n            clips = initialLayerClip?.let(::listOf) ?: baseTrack.clips,\n        )\n'''
    changed |= replace_once(path, old_update, new_update)

    changed |= replace_once(
        path,
        '''                if (partial == null || !partial.isFile || partial.length() <= StudioWavFile.HEADER_BYTES) {\n                    takeStore.cancel(pending)\n                }\n''',
        '''                if (partial == null || !partial.isFile || partial.length() <= StudioWavFile.HEADER_BYTES) {\n                    cancelTake(pending)\n                    project = load(projectId) ?: project\n                }\n''',
    )

    changed |= replace_once(
        path,
        '    fun cancelTake(pending: PendingStudioTake) = takeStore.cancel(pending)\n',
        '''    fun cancelTake(pending: PendingStudioTake) {\n        takeStore.cancel(pending)\n        val project = load(pending.projectId) ?: return\n        val track = project.tracks.firstOrNull { it.id == pending.trackId } ?: return\n        if (\n            track.isAutoRecordingLayer() &&\n            track.primaryAssetId == null &&\n            track.takes.isEmpty() &&\n            track.clips.isEmpty()\n        ) {\n            save(project.copy(tracks = project.tracks.filterNot { it.id == track.id }))\n        }\n    }\n''',
    )
    return changed


def patch_workspace() -> bool:
    path = "app/src/main/java/com/aistudio/mediatool/feature/studio/ui/StudioWorkspaceScreen.kt"
    changed = False
    changed |= replace_once(
        path,
        "import com.aistudio.mediatool.feature.studio.audio.StudioSessionStatus\n"
        "import com.aistudio.mediatool.feature.studio.domain.StudioClip\n",
        "import com.aistudio.mediatool.feature.studio.audio.StudioSessionStatus\n"
        "import com.aistudio.mediatool.feature.studio.data.StudioRecordingTargetRequests\n"
        "import com.aistudio.mediatool.feature.studio.domain.StudioClip\n",
    )

    old = '''    fun beginRecordingAfterPermission() {\n        when (requestedRecordingKind) {\n            StudioRecordingKind.FULL_TAKE -> StudioSessionRuntime.startRecording(mode = inputMode)\n            StudioRecordingKind.PUNCH -> StudioSessionRuntime.startPunchRecording(mode = inputMode)\n        }\n    }\n'''
    new = '''    fun beginRecordingAfterPermission() {\n        when (requestedRecordingKind) {\n            StudioRecordingKind.FULL_TAKE -> {\n                StudioRecordingTargetRequests.requestNewLayer()\n                StudioSessionRuntime.startRecording(mode = inputMode)\n            }\n            StudioRecordingKind.PUNCH -> {\n                val selectedTrackId = session.project?.tracks\n                    ?.firstOrNull { track ->\n                        session.selectedClipId != null &&\n                            track.clips.any { it.id == session.selectedClipId }\n                    }\n                    ?.id\n                    ?: session.project?.tracks?.firstOrNull { it.type == StudioTrackType.VOCAL }?.id\n                StudioRecordingTargetRequests.requestExistingTrack(selectedTrackId)\n                StudioSessionRuntime.startPunchRecording(mode = inputMode)\n            }\n        }\n    }\n'''
    changed |= replace_once(path, old, new)
    return changed


def main() -> None:
    changed = patch_repository() | patch_workspace()
    print("Studio multivocal patch applied" if changed else "Studio multivocal patch already applied")


if __name__ == "__main__":
    main()
