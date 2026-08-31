package com.aistudio.mediatool.core.media

import java.util.Locale

object TrimAudioCommandBuilder {
    data class Result(
        val command: String,
        val expectedDurationSec: Double,
    )

    fun build(
        inputPath: String,
        outputPath: String,
        segments: List<TimelineSegment>,
        sourceDurationSec: Double,
        audioEncodingArgs: String,
        requestedFadeSec: Double,
        outputFormat: String,
    ): Result {
        require(segments.isNotEmpty()) { "Cần ít nhất một đoạn âm thanh" }
        val expectedDurationSec = segments.sumOf { segment ->
            val startSec = segment.startMs / 1_000.0
            val endSec = segment.endMs?.div(1_000.0)
                ?: sourceDurationSec.takeIf { it > startSec }
                ?: error("Không xác định được điểm kết thúc của đoạn âm thanh")
            (endSec - startSec).coerceAtLeast(0.0)
        }
        require(expectedDurationSec > 0.0) { "Tổng thời lượng các đoạn cần cắt bằng 0" }

        val multiple = segments.size > 1
        val filters = mutableListOf<String>()
        if (multiple) {
            filters += "[0:a]asplit=${segments.size}${segments.indices.joinToString("") { "[asrc$it]" }}"
        }
        segments.forEachIndexed { index, segment ->
            val input = if (multiple) "asrc$index" else "0:a"
            val start = formatSeconds(segment.startMs / 1_000.0)
            val end = segment.endMs?.let { ":end=${formatSeconds(it / 1_000.0)}" }.orEmpty()
            filters += "[$input]atrim=start=$start$end,asetpts=PTS-STARTPTS[a$index]"
        }

        var outputLabel = if (multiple) "acat" else "a0"
        if (multiple) {
            filters += segments.indices.joinToString("") { "[a$it]" } +
                "concat=n=${segments.size}:v=0:a=1[acat]"
        }

        val fadeSec = AudioMath.clampedFadeDuration(requestedFadeSec, expectedDurationSec)
        if (fadeSec > 0.0) {
            val fadeOutStart = (expectedDurationSec - fadeSec).coerceAtLeast(0.0)
            filters += "[$outputLabel]afade=t=in:st=0:d=${formatSeconds(fadeSec)}," +
                "afade=t=out:st=${formatSeconds(fadeOutStart)}:d=${formatSeconds(fadeSec)}[aout]"
            outputLabel = "aout"
        }

        val command = buildString {
            append("-y -i \"")
            append(escapeQuoted(inputPath))
            append("\" -filter_complex \"")
            append(filters.joinToString(";"))
            append("\" -map \"[")
            append(outputLabel)
            append("]\" -vn ")
            append(audioEncodingArgs.trim())
            append(" -f ")
            append(outputFormat)
            append(" \"")
            append(escapeQuoted(outputPath))
            append("\"")
        }
        return Result(command, expectedDurationSec)
    }

    private fun formatSeconds(value: Double): String =
        String.format(Locale.US, "%.3f", value.coerceAtLeast(0.0))

    private fun escapeQuoted(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
