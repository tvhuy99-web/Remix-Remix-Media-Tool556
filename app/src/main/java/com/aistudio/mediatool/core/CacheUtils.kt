package com.aistudio.mediatool.core

import android.content.Context
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CacheUtils {
    private const val DEFAULT_MAX_AGE_MS = 48L * 60L * 60L * 1000L

    data class CleanupResult(val scanned: Int, val deleted: Int, val failures: Int)

    /** Chỉ dọn file tạm cũ và luôn giữ output của task chưa được người dùng xử lý. */
    suspend fun clearOldCache(
        context: Context,
        excludeFiles: List<File> = emptyList(),
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): CleanupResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val persisted = TaskStateStore.outputFiles(context)
        val excludedPaths = (excludeFiles + persisted)
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
            .toSet()

        var scanned = 0
        var deleted = 0
        var failures = 0
        context.cacheDir.walkBottomUp().forEach { file ->
            scanned++
            runCatching {
                if (file == context.cacheDir) return@runCatching
                if (file.isFile) {
                    val canonical = file.canonicalPath
                    val isExcluded = canonical in excludedPaths
                    val isOld = now - file.lastModified() >= maxAgeMs
                    val isActivePart = file.extension == "part"
                    if (!isExcluded && !isActivePart && isOld && file.delete()) deleted++
                } else if (file.isDirectory && file.listFiles().isNullOrEmpty()) {
                    if (file.delete()) deleted++
                }
            }.onFailure { error ->
                failures++
                DiagnosticLogger.warn(
                    component = "CacheUtils",
                    event = "cache_entry_cleanup_failed",
                    fields = mapOf(
                        "entry_id" to DiagnosticRedactor.stableId(file.absolutePath),
                        "directory" to file.isDirectory,
                    ),
                    error = error,
                )
            }
        }
        CleanupResult(scanned = scanned, deleted = deleted, failures = failures)
    }
}
