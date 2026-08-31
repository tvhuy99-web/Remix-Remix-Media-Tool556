package com.aistudio.mediatool.core.media

import java.util.Locale

object TrimVideoCommandBuilder {
    data class Result(
        val command: String,
        val expectedDurationSec: Double,
    )

    fun build(
        inputPath: String,
        outputPath: String,
        segments: List<TimelineSegment>,
        sourceDurationSec: Double,
        sourceHasAudio: Boolean,
        requestedFadeSec: Double,
    ): Result {
        require(segments.isNotEmpty()) { "Cần ít nhất một đoạn video" }
        require(sourceDurationSec.isFinite() && sourceDurationSec > 0.0) {
            "Không đọc được thời lượng video nguồn"
        }

        val expectedDurationSec = segments.sumOf { segment ->
            val startSec = segment.startMs / 1_000.0
            val endSec = segment.endMs?.div(1_000.0) ?: sourceDurationSec
            (endSec - startSec).coerceAtLeast(0.0)
        }
        require(expectedDurationSec > 0.0) { "Tổng thời lượng các đoạn cần cắt bằng 0" }

        val filters = mutableListOf<String>()
        segments.forEachIndexed { index, segment ->
            val start = formatSeconds(segment.startMs / 1_000.0)
            val end = segment.endMs?.let { ":end=${formatSeconds(it / 1_000.0)}" }.orEmpty()
            filters += "[0:v]trim=start=$start$end,setpts=PTS-STARTPTS[v$index]"
            if (sourceHasAudio) {
                filters += "[0:a]atrim=start=$start$end,asetpts=PTS-STARTPTS[a$index]"
            }
        }

        var videoLabel: String
        var audioLabel: String? = null
        if (segments.size == 1) {
            videoLabel = "v0"
            if (sourceHasAudio) audioLabel = "a0"
        } else if (sourceHasAudio) {
            val concatInputs = segments.indices.joinToString(separator = "") { "[v$it][a$it]" }
            filters += "$concatInputs concat=n=${segments.size}:v=1:a=1[vcat][acat]"
            videoLabel = "vcat"
            audioLabel = "acat"
        } else {
            val concatInputs = segments.indices.joinToString(separator = "") { "[v$it]" }
            filters += "$concatInputs concat=n=${segments.size}:v=1:a=0[vcat]"
            videoLabel = "vcat"
        }

        if (audioLabel != null) {
            val fadeSec = AudioMath.clampedFadeDuration(requestedFadeSec, expectedDurationSec)
            if (fadeSec > 0.0) {
                val fadeOutStart = (expectedDurationSec - fadeSec).coerceAtLeast(0.0)
                filters += "[$audioLabel]afade=t=in:st=0:d=${formatSeconds(fadeSec)}," +
                    "afade=t=out:st=${formatSeconds(fadeOutStart)}:d=${formatSeconds(fadeSec)}[aout]"
                audioLabel = "aout"
            }
        }

        val filterComplex = filters.joinToString(";")
        val command = buildString {
            append("-y -i \"")
            append(escapeQuoted(inputPath))
            append("\" -filter_complex \"")
            append(filterComplex)
            append("\" -map \"[")
            append(videoLabel)
            append("]\"")
            audioLabel?.let {
                append(" -map \"[")
                append(it)
                append("]\"")
            }
            append(" -c:v mpeg4 -q:v 2 -pix_fmt yuv420p")
            if (audioLabel != null) append(" -c:a aac -b:a 192k -shortest")
            append(" -max_muxing_queue_size 1024 -movflags +faststart \"")
            append(escapeQuoted(outputPath))
            append("\"")
        }

        return Result(command = command, expectedDurationSec = expectedDurationSec)
    }

    private fun formatSeconds(value: Double): String =
        String.format(Locale.US, "%.3f", value.coerceAtLeast(0.0))

    private fun escapeQuoted(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
