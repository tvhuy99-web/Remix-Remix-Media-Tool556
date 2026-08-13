package com.aistudio.mediatool.feature.studio.data

import android.content.Context
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import java.io.File
import java.io.FileOutputStream

class StudioProjectStore(context: Context) {
    private val rootDir = File(context.filesDir, "studio/projects")

    fun listProjects(): List<StudioProject> {
        if (!rootDir.isDirectory) return emptyList()
        return rootDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { directory -> runCatching { loadFromDirectory(directory) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    fun load(projectId: String): StudioProject? {
        val directory = projectDirectory(projectId)
        if (!directory.isDirectory) return null
        return runCatching { loadFromDirectory(directory) }.getOrNull()
    }

    fun save(project: StudioProject) {
        requireValidProjectId(project.id)
        val directory = projectDirectory(project.id).apply { mkdirs() }
        val target = File(directory, PROJECT_FILE)
        val temporary = File(directory, "$PROJECT_FILE.tmp")
        val backup = File(directory, "$PROJECT_FILE.bak")
        val bytes = StudioProjectCodec.encode(project).toByteArray(Charsets.UTF_8)

        temporary.delete()
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }

        if (target.exists() && isValidSnapshot(target, project.id)) {
            backup.delete()
            if (!target.renameTo(backup)) {
                target.copyTo(backup, overwrite = true)
                target.delete()
            }
        }

        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        check(isValidSnapshot(target, project.id)) { "Không thể lưu dự án Studio hợp lệ" }
        // Keep the previous valid project snapshot. loadFromDirectory() can then
        // recover from a later torn/corrupted primary file. If the primary was
        // already corrupt, do not overwrite a known-good backup with it.
    }

    fun delete(projectId: String): Boolean {
        val directory = projectDirectory(projectId)
        return !directory.exists() || directory.deleteRecursively()
    }

    fun projectDirectory(projectId: String): File {
        requireValidProjectId(projectId)
        return File(rootDir, projectId)
    }

    fun resolveAssetFile(projectId: String, relativePath: String): File {
        val projectDirectory = projectDirectory(projectId).canonicalFile
        val target = File(projectDirectory, relativePath).canonicalFile
        require(target.path.startsWith(projectDirectory.path + File.separator)) {
            "Đường dẫn asset nằm ngoài dự án Studio"
        }
        return target
    }

    private fun loadFromDirectory(directory: File): StudioProject {
        val primary = File(directory, PROJECT_FILE)
        val backup = File(directory, "$PROJECT_FILE.bak")
        val project = runCatching { StudioProjectCodec.decode(primary.readText(Charsets.UTF_8)) }
            .recoverCatching { StudioProjectCodec.decode(backup.readText(Charsets.UTF_8)) }
            .getOrThrow()
        require(project.id == directory.name) { "Id dự án Studio không khớp thư mục" }
        return project
    }

    private fun isValidSnapshot(file: File, expectedProjectId: String): Boolean =
        runCatching {
            file.isFile && file.length() > 0L &&
                StudioProjectCodec.decode(file.readText(Charsets.UTF_8)).id == expectedProjectId
        }.getOrDefault(false)

    private fun requireValidProjectId(projectId: String) {
        require(PROJECT_ID.matches(projectId)) { "Id dự án Studio không hợp lệ" }
    }

    companion object {
        private const val PROJECT_FILE = "project.json"
        private val PROJECT_ID = Regex("[A-Za-z0-9_-]{8,80}")
    }
}
