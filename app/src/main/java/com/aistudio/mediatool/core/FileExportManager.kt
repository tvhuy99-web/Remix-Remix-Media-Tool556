package com.aistudio.mediatool.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object FileExportManager {
    data class PendingDefaultOutput(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
    )

    suspend fun copyToUri(context: Context, source: File, destination: Uri) = withContext(Dispatchers.IO) {
        val destinationId = DiagnosticRedactor.stableId(destination.toString())
        try {
            require(source.isFile && source.length() > 0L) { "Tệp kết quả không tồn tại hoặc đang rỗng" }
            context.contentResolver.openOutputStream(destination, "w")?.buffered()?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
                output.flush()
            } ?: error("Không thể mở vị trí lưu đã chọn")
            DiagnosticLogger.info(
                component = "FileExportManager",
                event = "copy_to_uri_success",
                fields = mapOf(
                    "destination_id" to destinationId,
                    "extension" to source.extension,
                    "bytes" to source.length(),
                ),
            )
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = "FileExportManager",
                event = "copy_to_uri_failed",
                message = error.message,
                fields = mapOf(
                    "destination_id" to destinationId,
                    "extension" to source.extension,
                    "bytes" to source.length(),
                ),
                error = error,
            )
            throw error
        }
    }

    /**
     * Android 10+ always has a usable app-controlled default: the public Download collection.
     * A user-selected SAF tree overrides it. Android 7-9 still need an explicitly granted tree.
     */
    fun hasDefaultSaveLocation(context: Context): Boolean =
        SettingsManager.getDefaultSaveTreeUri(context) != null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Creates a normal child document. If the user has not selected a custom folder, Android 10+
     * writes to the system Download directory through MediaStore.
     */
    fun createDefaultSaveDocument(
        context: Context,
        displayName: String,
        mimeType: String,
    ): Uri {
        val customTree = SettingsManager.getDefaultSaveTreeUri(context)?.let(Uri::parse)
        if (customTree == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return createSystemDownloadDocument(context, displayName, mimeType, pending = false)
            }
            error("Android 9 trở xuống cần chọn thư mục lưu trong Cài đặt")
        }

        val safeName = DocumentUtils.sanitizeFileName(displayName).ifBlank { "result" }
        try {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(customTree)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(customTree, treeDocumentId)
            return DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                mimeType,
                safeName,
            ) ?: error("Không thể tạo tệp trong thư mục lưu mặc định")
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = "FileExportManager",
                event = "default_save_create_failed",
                message = error.message,
                fields = mapOf(
                    "tree_id" to DiagnosticRedactor.stableId(customTree.toString()),
                    "extension" to safeName.substringAfterLast('.', ""),
                ),
                error = error,
            )
            throw IllegalStateException(
                "Không thể dùng thư mục lưu mặc định. Hãy chọn lại thư mục trong Cài đặt.",
                error,
            )
        }
    }

    fun createPendingDefaultOutput(
        context: Context,
        baseName: String,
        extension: String,
    ): PendingDefaultOutput {
        val displayName = resultDisplayName(baseName, extension)
        val mimeType = mimeTypeForName(displayName)
        val uri = if (
            SettingsManager.getDefaultSaveTreeUri(context) == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            createSystemDownloadDocument(context, displayName, mimeType, pending = true)
        } else {
            createDefaultSaveDocument(context, displayName, mimeType)
        }
        try {
            PendingExportStore.register(context, uri)
        } catch (error: Throwable) {
            PendingExportStore.discard(context, uri)
            throw error
        }
        return PendingDefaultOutput(uri = uri, displayName = displayName, mimeType = mimeType)
    }

    fun commitPendingDefaultOutput(context: Context, uri: Uri) {
        finalizeSystemDownloadDocument(context, uri)
        PendingExportStore.commit(context, uri)
    }

    fun discardPendingDefaultOutput(context: Context, uri: Uri): Boolean =
        PendingExportStore.discard(context, uri)

    suspend fun saveToDefaultLocation(context: Context, source: File): Uri = withContext(Dispatchers.IO) {
        val useSystemDownloads =
            SettingsManager.getDefaultSaveTreeUri(context) == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val destination = if (useSystemDownloads) {
            createSystemDownloadDocument(context, source.name, mimeTypeFor(source), pending = true)
        } else {
            createDefaultSaveDocument(context, source.name, mimeTypeFor(source))
        }
        try {
            copyToUri(context, source, destination)
            if (useSystemDownloads) finalizeSystemDownloadDocument(context, destination)
            DiagnosticLogger.info(
                component = "FileExportManager",
                event = "default_save_success",
                fields = mapOf(
                    "destination_id" to DiagnosticRedactor.stableId(destination.toString()),
                    "extension" to source.extension,
                    "bytes" to source.length(),
                    "system_downloads" to useSystemDownloads,
                ),
            )
            destination
        } catch (error: Throwable) {
            runCatching { context.contentResolver.delete(destination, null, null) }
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createSystemDownloadDocument(
        context: Context,
        displayName: String,
        mimeType: String,
        pending: Boolean,
    ): Uri {
        val safeName = DocumentUtils.sanitizeFileName(displayName).ifBlank { "result" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Không thể tạo tệp trong thư mục Download")
    }

    private fun finalizeSystemDownloadDocument(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || uri.authority != MediaStore.AUTHORITY) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        val updated = context.contentResolver.update(uri, values, null, null)
        require(updated > 0) { "Không thể hoàn tất tệp trong thư mục Download" }
    }

    fun contentLength(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.statSize >= 0L) return descriptor.statSize
            }
        }
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    fun shareFile(context: Context, source: File, chooserTitle: String = "Chia sẻ kết quả") {
        try {
            require(source.isFile && source.length() > 0L) { "Tệp kết quả không tồn tại hoặc đang rỗng" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
            shareUri(context, uri, mimeTypeFor(source), chooserTitle)
            DiagnosticLogger.info(
                component = "FileExportManager",
                event = "share_chooser_opened",
                fields = mapOf("extension" to source.extension, "bytes" to source.length()),
            )
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = "FileExportManager",
                event = "share_failed",
                message = error.message,
                fields = mapOf("extension" to source.extension, "bytes" to source.length()),
                error = error,
            )
            throw error
        }
    }

    fun shareUri(
        context: Context,
        uri: Uri,
        mimeType: String,
        chooserTitle: String = "Chia sẻ kết quả",
    ) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }

    fun resultFile(context: Context, baseName: String, extension: String): File {
        val directory = File(context.cacheDir, "results").apply { mkdirs() }
        var candidate = File(directory, resultDisplayName(baseName, extension))
        var counter = 1
        while (candidate.exists()) {
            val ext = candidate.extension
            val stem = candidate.nameWithoutExtension
            candidate = File(directory, "${stem}_${counter++}.$ext")
        }
        return candidate
    }

    fun resultDisplayName(baseName: String, extension: String): String {
        val safeBase = DocumentUtils.sanitizeFileName(baseName).replace(' ', '_').ifBlank { "result" }
        val safeExt = extension.trimStart('.').lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
            ?: "bin"
        return "${safeBase}_${System.currentTimeMillis()}.$safeExt"
    }

    suspend fun zipFiles(context: Context, files: List<File>, baseName: String): File = withContext(Dispatchers.IO) {
        val validFiles = files.filter { it.isFile && it.length() > 0L }
        require(validFiles.isNotEmpty()) { "Không có tệp hợp lệ để đóng gói" }
        val target = resultFile(context, baseName, "zip")
        try {
            val usedNames = mutableSetOf<String>()
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                validFiles.forEach { file ->
                    val entryName = uniqueZipName(file.name, usedNames)
                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            require(target.length() > 0L) { "ZIP đầu ra bị rỗng" }
            target
        } catch (error: Throwable) {
            target.delete()
            DiagnosticLogger.error(
                component = "FileExportManager",
                event = "zip_failed",
                message = error.message,
                fields = mapOf("input_count" to validFiles.size),
                error = error,
            )
            throw error
        }
    }

    private fun uniqueZipName(original: String, used: MutableSet<String>): String {
        val safe = DocumentUtils.sanitizeFileName(original).ifBlank { "file" }
        if (used.add(safe)) return safe
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "${base}_$index$ext"
            if (used.add(candidate)) return candidate
            index++
        }
    }

    fun mimeTypeFor(file: File): String = mimeTypeForName(file.name)

    fun mimeTypeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "m4a", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "txt", "log" -> "text/plain"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
