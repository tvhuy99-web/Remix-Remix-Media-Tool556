package com.aistudio.mediatool.core.media

data class TimelineSegment(
    val startMs: Long,
    val endMs: Long?,
)

data class TimelineParseResult(
    val segments: List<TimelineSegment>?,
    val error: String?,
) {
    val isValid: Boolean get() = segments != null && error == null
}

object TimelineSegments {
    fun parse(startsText: String, endsText: String): TimelineParseResult {
        val starts = parseTokens(startsText)
            ?: return TimelineParseResult(null, "Mốc bắt đầu phải là số nguyên mili-giây không âm")
        val ends = parseTokens(endsText)
            ?: return TimelineParseResult(null, "Mốc kết thúc phải là số nguyên mili-giây không âm")

        val normalizedStarts = starts.ifEmpty { listOf(0L) }
        if (ends.isNotEmpty() && ends.size != normalizedStarts.size) {
            return TimelineParseResult(
                null,
                "Số mốc bắt đầu (${normalizedStarts.size}) và kết thúc (${ends.size}) không bằng nhau",
            )
        }

        val segments = normalizedStarts.mapIndexed { index, start ->
            val end = ends.getOrNull(index)?.takeIf { it > 0L }
            if (end != null && end <= start) {
                return TimelineParseResult(
                    null,
                    "Đoạn ${index + 1}: mốc kết thúc ($end) phải lớn hơn mốc bắt đầu ($start)",
                )
            }
            TimelineSegment(start, end)
        }
        return TimelineParseResult(segments, null)
    }

    private fun parseTokens(text: String): List<Long>? {
        if (text.isBlank()) return emptyList()
        val tokens = text.split(',').map(String::trim)
        if (tokens.any(String::isEmpty)) return null
        return tokens.map { token ->
            token.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        }
    }
}
