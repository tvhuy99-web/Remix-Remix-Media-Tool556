package com.aistudio.mediatool.core.diagnostics

import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest

/**
 * Che dữ liệu có thể nhận diện tệp/người dùng trước khi ghi ra nhật ký.
 *
 * Nhật ký chỉ cần đủ dữ kiện kỹ thuật để tái hiện lỗi. URI, đường dẫn, URL,
 * metadata media và thông tin xác thực không bao giờ cần thiết cho mục đích đó.
 */
object DiagnosticRedactor {
    private const val DEFAULT_MAX_CHARS = 12_000

    private val localUri = Regex("""(?i)\b(?:content|file)://[^\s\"'<>]+""")
    private val webUrl = Regex("""(?i)\bhttps?://[^\s\"'<>]+""")
    private val email = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")
    private val secret = Regex(
        """(?i)\b(authorization|access[_-]?token|refresh[_-]?token|token|api[_-]?key|signature|sig)\s*[:=]\s*(?:bearer\s+)?[^\s,;]+""",
    )
    private val bearerSecret = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+""")
    private val mediaFileName = Regex(
        """(?i)\b[^\s\"'<>/\\]+\.(mp3|m4a|aac|wav|flac|ogg|opus|mp4|mkv|webm|mov|avi|jpg|jpeg|png|srt|vtt|ass)\b""",
    )
    private val absolutePath = Regex(
        """(?<![A-Za-z0-9._-])/(?:[^/\s\"'<>:]+/)*[^/\s\"'<>:,;\])}]+""",
    )
    private val mediaMetadataLine = Regex(
        """(?i)^\s*(title|artist|album|album_artist|composer|performer|comment|description|lyrics|copyright|creation_time|date)\s*:""",
    )

    fun sanitize(value: String?, maxChars: Int = DEFAULT_MAX_CHARS): String? {
        if (value == null) return null
        val boundedLimit = maxChars.coerceIn(128, 64_000)
        var result = value
        result = localUri.replace(result, "<media-uri>")
        result = webUrl.replace(result, "<url>")
        result = secret.replace(result) { match -> "${match.groupValues[1]}=<redacted>" }
        result = bearerSecret.replace(result, "bearer <redacted>")
        result = email.replace(result, "<email>")
        result = absolutePath.replace(result, "<path>")
        result = mediaFileName.replace(result, "<media-file>")
        result = result.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "?")
        return if (result.length <= boundedLimit) {
            result
        } else {
            result.take(boundedLimit) + "…<truncated:${result.length - boundedLimit}>"
        }
    }

    fun sanitizeFfmpegLogs(value: String?, maxChars: Int = DEFAULT_MAX_CHARS): String? {
        if (value == null) return null
        val withoutMetadata = value.lineSequence()
            .map { line -> if (mediaMetadataLine.containsMatchIn(line)) "<media-metadata omitted>" else line }
            .joinToString("\n")
        val withoutInputNames = Regex("""(?i)\b(from|opening)\s+['\"][^'\"]+['\"]""")
            .replace(withoutMetadata) { match -> "${match.groupValues[1]} '<media-source>'" }
        return sanitize(withoutInputNames.takeLast(maxChars * 2), maxChars)
    }

    fun stackTrace(error: Throwable, maxChars: Int = 20_000): String {
        val raw = StringWriter().also { writer ->
            PrintWriter(writer).use { printer -> error.printStackTrace(printer) }
        }.toString()
        return sanitize(raw, maxChars).orEmpty()
    }

    /** Mã tương quan một chiều; cùng một nguồn cho cùng mã nhưng không lộ URI. */
    fun stableId(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
