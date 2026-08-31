package com.aistudio.mediatool.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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

    fun hasDefaultSaveLocation(context: Context): Boolean =
        SettingsManager.getDefaultSaveTreeUri(context) != null

    /**
     * Creates a child document in the persisted default tree. This removes the repeated
     * CreateDocument picker. Existing processors may still need to copy their private-cache
     * result into this URI; processors that can write SAF directly can use [createDefaultSaveDocument]
     * before processing and avoid that second copy entirely.
     */
    fun createDefaultSaveDocument(
        context: Context,
        displayName: String,
        mimeType: String,
    ): Uri {
        val treeUri = SettingsManager.getDefaultSaveTreeUri(context)
            ?.let(Uri::parse)
            ?: error("Chưa chọn thư mục lưu mặc định trong Cài đặt")
        val safeName = DocumentUtils.sanitizeFileName(displayName).ifBlank { "result" }
        try {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
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
                    "tree_id" to DiagnosticRedactor.stableId(treeUri.toString()),
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

    suspend fun saveToDefaultLocation(context: Context, source: File): Uri = withContext(Dispatchers.IO) {
        val destination = createDefaultSaveDocument(context, source.name, mimeTypeFor(source))
        try {
            copyToUri(context, source, destination)
            DiagnosticLogger.info(
                component = "FileExportManager",
                event = "default_save_success",
                fields = mapOf(
                    "destination_id" to DiagnosticRedactor.stableId(destination.toString()),
                    "extension" to source.extension,
                    "bytes" to source.length(),
                ),
            )
            destination
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
            throw error
        }
    }

    fun shareFile(context: Context, source: File, chooserTitle: String = "Chia sẻ kết quả") {
        try {
            require(source.isFile && source.length() > 0L) { "Tệp kết quả không tồn tại hoặc đang rỗng" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeFor(source)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
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

    fun resultFile(context: Context, baseName: String, extension: String): File {
        val safeBase = DocumentUtils.sanitizeFileName(baseName).replace(' ', '_').ifBlank { "result" }
        val safeExt = extension.trimStart('.').lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
            ?: "bin"
        val directory = File(context.cacheDir, "results").apply { mkdirs() }
        var candidate = File(directory, "${safeBase}_${System.currentTimeMillis()}.$safeExt")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "${safeBase}_${System.currentTimeMillis()}_${counter++}.$safeExt")
        }
        return candidate
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

    fun mimeTypeFor(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
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
