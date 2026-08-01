package com.aistudio.mediatool.core.subtitle

data class SubtitleCue(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

object SubtitleParser {
    fun parse(content: String): List<SubtitleCue> {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').lines()
        val result = mutableListOf<SubtitleCue>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.isBlank() || line.startsWith("WEBVTT") || line.startsWith("NOTE")) {
                index++
                continue
            }
            val timeLineIndex = when {
                line.contains("-->") -> index
                index + 1 < lines.size && lines[index + 1].contains("-->") -> index + 1
                else -> {
                    index++
                    continue
                }
            }
            val parts = lines[timeLineIndex].split("-->", limit = 2)
            val start = parseTimeMs(parts.getOrNull(0).orEmpty().trim())
            val end = parseTimeMs(parts.getOrNull(1).orEmpty().trim().substringBefore(' '))
            index = timeLineIndex + 1
            val textLines = mutableListOf<String>()
            while (index < lines.size && lines[index].isNotBlank()) {
                textLines += lines[index].trim()
                index++
            }
            if (start != null && end != null && end > start) {
                val text = cleanText(textLines.joinToString(" "))
                if (text.isNotBlank()) result += SubtitleCue(result.size + 1, start, end, text)
            }
        }
        return result.sortedBy { it.startMs }
    }

    fun parseTimeMs(value: String): Long? = runCatching {
        val clean = value.trim().replace(',', '.').substringBefore(' ')
        val parts = clean.split(':')
        require(parts.size in 2..3)
        val secondsParts = parts.last().split('.', limit = 2)
        val hours = if (parts.size == 3) parts[0].toLong() else 0L
        val minutes = parts[parts.size - 2].toLong()
        val seconds = secondsParts[0].toLong()
        require(minutes in 0..59 && seconds in 0..59 && hours >= 0)
        val millis = secondsParts.getOrNull(1)
            ?.filter(Char::isDigit)
            ?.padEnd(3, '0')
            ?.take(3)
            ?.toLongOrNull()
            ?: 0L
        Math.addExact(
            Math.multiplyExact(Math.addExact(Math.multiplyExact(hours, 60L), minutes), 60_000L),
            Math.addExact(Math.multiplyExact(seconds, 1_000L), millis),
        )
    }.getOrNull()

    private fun cleanText(value: String): String = value
        .replace(Regex("<[^>]*>"), "")
        .replace(Regex("\\{[^}]*}"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
}
