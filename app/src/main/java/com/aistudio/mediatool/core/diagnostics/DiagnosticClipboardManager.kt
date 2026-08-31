package com.aistudio.mediatool.core.diagnostics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A bounded plaintext snapshot suitable for Android's clipboard. */
data class DiagnosticClipboardSnapshot(
    val text: String,
    val truncated: Boolean,
    val sourceFiles: Int,
)

object DiagnosticClipboardManager {
    private const val DEFAULT_MAX_CHARS = 500_000

    suspend fun create(
        context: Context,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): DiagnosticClipboardSnapshot = withContext(Dispatchers.IO) {
        require(maxChars >= 10_000) { "Giới hạn nhật ký quá nhỏ" }
        val snapshotDir = File(context.cacheDir, "diagnostic_clipboard_${System.currentTimeMillis()}")
        try {
            val files = DiagnosticLogger.snapshotLogs(snapshotDir)
            var remaining = maxChars
            val newestFirstChunks = mutableListOf<String>()
            var truncated = false

            for (file in files.asReversed()) {
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val text = file.readText()
                if (text.length <= remaining) {
                    newestFirstChunks += text
                    remaining -= text.length
                } else {
                    newestFirstChunks += text.takeLast(remaining)
                    remaining = 0
                    truncated = true
                    break
                }
            }

            val body = newestFirstChunks.asReversed().joinToString(separator = "")
            val prefix = if (truncated) {
                "[Nhật ký đã được rút gọn để phù hợp bộ nhớ tạm; giữ phần mới nhất.]\n"
            } else {
                ""
            }
            DiagnosticClipboardSnapshot(
                text = prefix + body,
                truncated = truncated,
                sourceFiles = files.size,
            )
        } finally {
            snapshotDir.deleteRecursively()
        }
    }
}
