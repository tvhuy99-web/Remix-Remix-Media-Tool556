package com.aistudio.mediatool.core

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale
import java.util.UUID

object DocumentUtils {
    fun persistReadPermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )
            if (cursor?.moveToFirst() == true) {
                cursor.getString(0).orEmpty().ifBlank { fallbackName(uri) }
            } else {
                fallbackName(uri)
            }
        } catch (_: Exception) {
            fallbackName(uri)
        } finally {
            cursor?.close()
        }
    }

    fun copyToImportCache(context: Context, uri: Uri, prefix: String = "input"): File {
        val displayName = displayName(context, uri)
        val ext = displayName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        val safePrefix = sanitizeFileName(prefix).ifBlank { "input" }
        val target = File(
            File(context.cacheDir, "imports").apply { mkdirs() },
            buildString {
                append(safePrefix)
                append('_')
                append(UUID.randomUUID())
                if (ext != null) append('.').append(ext)
            },
        )
        try {
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: error("Không thể đọc tệp đã chọn")
            require(target.isFile && target.length() > 0L) { "Tệp đã chọn không chứa dữ liệu" }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.', '_')
        .take(96)

    private fun fallbackName(uri: Uri): String = uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: "document"
}
