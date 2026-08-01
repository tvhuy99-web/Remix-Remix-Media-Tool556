package com.aistudio.mediatool.core.ml

data class ParsedContentRange(val start: Long, val endInclusive: Long, val total: Long)

object ContentRange {
    private val pattern = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)

    fun parse(value: String?): ParsedContentRange? {
        val match = value?.trim()?.let(pattern::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull() ?: return null
        if (start < 0 || end < start || total <= end) return null
        return ParsedContentRange(start, end, total)
    }
}
