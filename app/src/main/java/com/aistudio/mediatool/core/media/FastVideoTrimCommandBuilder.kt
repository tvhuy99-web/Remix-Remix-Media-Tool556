package com.aistudio.mediatool.core.media

import java.util.Locale

/** Builds the fastest safe FFmpeg command for trimming one video segment without re-encoding. */
object FastVideoTrimCommandBuilder {
    data class Result(
        val command: String,
        val expectedDurationSec: Double,
    )

    fun build(
        inputPath: String,
        outputPath: String,
        segment: TimelineSegment,
        sourceDurationSec: Double,
    ): Result {
        require(sourceDurationSec.isFinite() && sourceDurationSec > 0.0) {
            "Không đọc được thời lượng video nguồn"
        }

        val startSec = segment.startMs / 1_000.0
        val endSec = segment.endMs?.div(1_000.0) ?: sourceDurationSec
        require(startSec >= 0.0 && startSec < sourceDurationSec) {
            "Mốc bắt đầu video không hợp lệ"
        }
        require(endSec > startSec && endSec <= sourceDurationSec + 0.05) {
            "Mốc kết thúc video không hợp lệ"
        }

        val durationSec = endSec - startSec
        val command = buildString {
            append("-y -ss ")
            append(formatSeconds(startSec))
            append(" -i \"")
            append(escapeQuoted(inputPath))
            append("\" -t ")
            append(formatSeconds(durationSec))
            append(" -map 0:v:0 -map 0:a:0? -map_metadata 0")
            append(" -c copy -avoid_negative_ts make_zero -movflags +faststart \"")
            append(escapeQuoted(outputPath))
            append("\"")
        }

        return Result(command = command, expectedDurationSec = durationSec)
    }

    private fun formatSeconds(value: Double): String =
        String.format(Locale.US, "%.3f", value.coerceAtLeast(0.0))

    private fun escapeQuoted(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
