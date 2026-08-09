package com.aistudio.mediatool.feature.studio.data

import android.content.Context
import android.net.Uri
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.feature.studio.domain.STUDIO_TIMELINE_SAMPLE_RATE
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import java.io.File
import java.util.UUID

class StudioProjectRepository(context: Context) {
    private val appContext = context.applicationContext
    private val projectStore = StudioProjectStore(appContext)
    private val assetStore = StudioAssetStore(appContext, projectStore)

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
        val updated = project.copy(updatedAt = System.currentTimeMillis())
        projectStore.save(updated)
        return updated
    }

    fun delete(projectId: String): Boolean = projectStore.delete(projectId)

    fun assetFile(projectId: String, assetId: String): File? {
        val project = load(projectId) ?: return null
        val asset = project.assets.firstOrNull { it.id == assetId } ?: return null
        return assetStore.fileFor(projectId, asset).takeIf { it.isFile && it.length() > 0L }
    }
}
