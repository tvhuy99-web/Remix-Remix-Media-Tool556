package com.aistudio.mediatool.feature.studio.data

import android.content.Context
import android.net.Uri
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.feature.studio.domain.STUDIO_PROJECT_SCHEMA_VERSION
import com.aistudio.mediatool.feature.studio.domain.STUDIO_TIMELINE_SAMPLE_RATE
import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProSettings
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTakeStatus
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import com.aistudio.mediatool.feature.studio.domain.latencyCompensatedPlacement
import java.io.File
import java.util.UUID

class StudioProjectRepository(context: Context) {
    private val appContext = context.applicationContext
    private val projectStore = StudioProjectStore(appContext)
    private val assetStore = StudioAssetStore(appContext, projectStore)
    private val takeStore = StudioTakeStore(projectStore)

    fun listProjects(): List<StudioProject> = projectStore.listProjects()

    fun load(projectId: String): StudioProject? = projectStore.load(projectId)

    fun createFromBeat(beatUri: Uri, requestedName: String? = null): StudioProject {
        val projectId = UUID.randomUUID().toString()
        val projectDir = projectStore.projectDirectory(projectId).apply { mkdirs() }
        return try {
            val beat = assetStore.importBeat(projectId, beatUri)
            val now = System.currentTimeMillis()
            val defaultName = DocumentUtils.displayName(appContext, beatUri)
                .substringBeforeLast('.')
                .trim()
                .ifBlank { "Dự án Studio" }
            val projectName = requestedName?.trim().takeUnless { it.isNullOrEmpty() } ?: defaultName
            val beatTrack = StudioTrack(
                id = UUID.randomUUID().toString(),
                type = StudioTrackType.BEAT,
                name = "Beat",
                primaryAssetId = beat.id,
                locked = true,
            )
            StudioProject(
                id = projectId,
                name = projectName.take(96),
                createdAt = now,
                updatedAt = now,
                timelineSampleRate = STUDIO_TIMELINE_SAMPLE_RATE,
                beatAssetId = beat.id,
                assets = listOf(beat),
                tracks = listOf(beatTrack),
            ).also(projectStore::save)
        } catch (error: Throwable) {
            projectDir.deleteRecursively()
            throw error
        }
    }

    fun save(project: StudioProject): StudioProject {
        val updated = project.copy(
            schemaVersion = STUDIO_PROJECT_SCHEMA_VERSION,
            updatedAt = System.currentTimeMillis(),
        )
        projectStore.save(updated)
        return updated
    }

    fun updateProSettings(projectId: String, settings: StudioProSettings): StudioProject {
        val project = requireNotNull(load(projectId)) { "Không tìm thấy dự án Studio" }
        val merged = StudioProSettingsMerge.preserveMusicalMetadata(
            existing = project.proSettings,
            editedByLegacyProUi = settings,
        )
        return save(project.copy(proSettings = merged))
    }

    fun derivedOutputFile(projectId: String, processorId: String): Pair<String, File> {
        val safeProcessor = DocumentUtils.sanitizeFileName(processorId)
            .replace(' ', '_')
            .ifBlank { "processor" }
            .take(40)
        val relativePath = "derived/${safeProcessor}_${UUID.randomUUID().toString().take(8)}.wav"
        val file = resolveProjectFile(projectId, relativePath)
        file.parentFile?.mkdirs()
        file.delete()
        return relativePath to file
    }

    fun commitDerivedAsset(
        projectId: String,
        sourceAssetId: String,
        relativePath: String,
        displayName: String,
        processorId: String,
        processorLabel: String,
        processorConfig: String? = null,
    ): Pair<StudioProject, StudioAsset> {
        val project = requireNotNull(load(projectId)) { "Không tìm thấy dự án Studio" }
        requireNotNull(project.asset(sourceAssetId)) { "Không tìm thấy source asset" }
        val file = resolveProjectFile(projectId, relativePath)
        val info = requireNotNull(StudioWavFile.inspectCanonicalPcm16(file)) {
            "Derived audio phải là WAV PCM16 canonical"
        }
        val asset = StudioAsset(
            id = UUID.randomUUID().toString(),
            kind = StudioAssetKind.DERIVED,
            relativePath = relativePath,
            displayName = displayName.take(120),
            mimeType = "audio/wav",
            bytes = file.length(),
            sourceAssetId = sourceAssetId,
            processorId = processorId,
            processorLabel = processorLabel,
            processorConfig = processorConfig,
            sampleRate = info.sampleRate,
            channelCount = info.channelCount,
            durationFrames = info.dataFrames,
        )
        val saved = save(project.copy(assets = project.assets.filterNot { it.id == asset.id } + asset))
        return saved to asset
    }

    fun applyDerivedAsset(projectId: String, sourceAssetId: String, derivedAssetId: String): StudioProject {
        val project = requireNotNull(load(projectId)) { "Không tìm thấy dự án Studio" }
        return save(StudioDerivedAssetEditor.apply(project, sourceAssetId, derivedAssetId))
    }

    fun updatePreparedBeat(
        projectId: String,
        sampleRate: Int,
        channelCount: Int,
        durationFrames: Long,
    ): StudioProject {
        val project = requireNotNull(load(projectId)) { "Không tìm thấy dự án Studio" }
        val beatId = requireNotNull(project.beatAssetId) { "Dự án Studio chưa có beat" }
        val updatedAssets = project.assets.map { asset ->
            if (asset.id == beatId) {
                asset.copy(
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    durationFrames = durationFrames,
                )
            } else {
                asset
            }
        }
        return if (updatedAssets == project.assets) project else save(project.copy(assets = updatedAssets))
    }

    fun beginTake(
        projectId: String,
        recordedTimelineFrame: Long,
        inputSampleRate: Int,
        inputDeviceId: Int?,
        latencyCompensationFrames: Long = 0L,
    ): PendingStudioTake {
        var project = requireNotNull(load(projectId)) { "Không tìm thấy dự án Studio" }
        val selection = project.selectRecordingTrack(StudioRecordingTargetRequests.consume())
        if (selection.project != project) {
            project = save(selection.project)
        }
        return takeStore.begin(
            projectId = project.id,
            trackId = selection.track.id,
            recordedTimelineFrame = recordedTimelineFrame,
            inputSampleRate = inputSampleRate,
            inputDeviceId = inputDeviceId,
            latencyCompensationFrames = latencyCompensationFrames,
            channelCount = 1,
        )
    }

    fun pendingTakeFile(pending: PendingStudioTake): File = takeStore.partialFile(pending)

    fun finalizeTake(
        pending: PendingStudioTake,
        status: StudioTakeStatus = StudioTakeStatus.COMPLETE,
        activateTake: Boolean = true,
    ): StudioProject {
        val finalized = requireNotNull(takeStore.finalizeAudioFile(pending)) {
            "Bản thu Studio không chứa dữ liệu hợp lệ"
        }
        var project = requireNotNull(load(pending.projectId)) { "Không tìm thấy dự án Studio" }

        val alreadyCommitted = project.tracks.any { track -> track.takes.any { it.id == pending.takeId } }
        if (alreadyCommitted) {
            takeStore.commit(pending)
            return project
        }

        val trackIndex = project.tracks.indexOfFirst { it.id == pending.trackId }
        val baseTrack = if (trackIndex >= 0) {
            project.tracks[trackIndex]
        } else {
            StudioTrack(
                id = pending.trackId,
                type = StudioTrackType.VOCAL,
                name = "Vocal",
            )
        }
        val takeNumber = baseTrack.takes.size + 1
        val asset = StudioAsset(
            id = pending.assetId,
            kind = StudioAssetKind.TAKE,
            relativePath = finalized.relativePath,
            displayName = "${baseTrack.name.ifBlank { "Vocal" }} · Bản $takeNumber",
            mimeType = "audio/wav",
            bytes = finalized.file.length(),
            sampleRate = finalized.info.sampleRate,
            channelCount = finalized.info.channelCount,
            durationFrames = finalized.info.dataFrames,
        )
        val take = StudioTake(
            id = pending.takeId,
            assetId = asset.id,
            recordedTimelineFrame = pending.recordedTimelineFrame,
            recordedFrames = finalized.info.dataFrames,
            inputDeviceId = pending.inputDeviceId,
            inputSampleRate = finalized.info.sampleRate,
            latencyCompensationFrames = pending.latencyCompensationFrames,
            status = status,
        )
        val initialLayerClip = if (
            baseTrack.type == StudioTrackType.OTHER &&
            baseTrack.isAutoRecordingLayer() &&
            baseTrack.takes.isEmpty() &&
            baseTrack.clips.isEmpty()
        ) {
            val placement = take.latencyCompensatedPlacement(project.timelineSampleRate)
            StudioClip(
                id = UUID.randomUUID().toString(),
                sourceAssetId = asset.id,
                sourceTakeId = take.id,
                timelineStartFrame = placement.timelineStartFrame,
                sourceStartFrame = placement.sourceStartFrame,
                sourceEndFrame = placement.sourceEndFrame,
            )
        } else {
            null
        }
        val updatedTrack = baseTrack.copy(
            primaryAssetId = if (activateTake) asset.id else baseTrack.primaryAssetId,
            activeTakeId = if (activateTake) take.id else baseTrack.activeTakeId,
            takes = baseTrack.takes + take,
            clips = initialLayerClip?.let(::listOf) ?: baseTrack.clips,
        )
        val updatedTracks = if (trackIndex >= 0) {
            project.tracks.toMutableList().apply { this[trackIndex] = updatedTrack }
        } else {
            project.tracks + updatedTrack
        }
        project = save(
            project.copy(
                assets = project.assets.filterNot { it.id == asset.id } + asset,
                tracks = updatedTracks,
            ),
        )
        takeStore.commit(pending)
        return project
    }

    fun recoverInterruptedTakes(projectId: String): StudioProject? {
        var project = load(projectId) ?: return null
        takeStore.loadPending(projectId).forEach { pending ->
            val recovered = runCatching {
                finalizeTake(pending, status = StudioTakeStatus.RECOVERED)
            }.getOrElse {
                val partial = runCatching { takeStore.partialFile(pending) }.getOrNull()
                if (partial == null || !partial.isFile || partial.length() <= StudioWavFile.HEADER_BYTES) {
                    cancelTake(pending)
                    project = load(projectId) ?: project
                }
                null
            }
            if (recovered != null) project = recovered
        }
        return project
    }

    fun cancelTake(pending: PendingStudioTake) {
        takeStore.cancel(pending)
        val project = load(pending.projectId) ?: return
        val track = project.tracks.firstOrNull { it.id == pending.trackId } ?: return
        if (
            track.isAutoRecordingLayer() &&
            track.primaryAssetId == null &&
            track.takes.isEmpty() &&
            track.clips.isEmpty()
        ) {
            save(project.copy(tracks = project.tracks.filterNot { it.id == track.id }))
        }
    }

    fun delete(projectId: String): Boolean = projectStore.delete(projectId)

    fun projectDirectory(projectId: String): File = projectStore.projectDirectory(projectId)

    fun resolveProjectFile(projectId: String, relativePath: String): File =
        projectStore.resolveAssetFile(projectId, relativePath)

    fun assetFile(projectId: String, assetId: String): File? {
        val project = load(projectId) ?: return null
        val asset = project.assets.firstOrNull { it.id == assetId } ?: return null
        return assetStore.fileFor(projectId, asset).takeIf { it.isFile && it.length() > 0L }
    }
}
