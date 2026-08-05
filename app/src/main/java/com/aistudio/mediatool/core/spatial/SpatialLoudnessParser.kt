package com.aistudio.mediatool.core.spatial

internal data class SpatialLoudnessReading(
    val integratedLufs: Double?,
    val truePeakDbtp: Double?,
    val loudnessRangeLu: Double?,
    val thresholdLufs: Double?,
) {
    fun diagnosticFields(prefix: String): Map<String, Any?> = mapOf(
        "${prefix}_integrated_lufs" to integratedLufs,
        "${prefix}_true_peak_dbtp" to truePeakDbtp,
        "${prefix}_loudness_range_lu" to loudnessRangeLu,
        "${prefix}_loudness_threshold_lufs" to thresholdLufs,
    )
}

internal fun SpatialLoudnessReading?.diagnosticFields(prefix: String): Map<String, Any?> =
    this?.diagnosticFields(prefix) ?: mapOf(
        "${prefix}_integrated_lufs" to null,
        "${prefix}_true_peak_dbtp" to null,
        "${prefix}_loudness_range_lu" to null,
        "${prefix}_loudness_threshold_lufs" to null,
    )

/** Extracts the last complete loudnorm JSON object without Android JSON runtime dependencies. */
internal object SpatialLoudnessParser {
    private val numericValue = Regex(
        """\"([a-zA-Z0-9_]+)\"\s*:\s*\"?([-+]?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][-+]?\d+)?)\"?""",
    )

    fun parse(logs: String): SpatialLoudnessReading? {
        var result: SpatialLoudnessReading? = null
        for (block in jsonObjects(logs)) {
            val values = numericValue.findAll(block).associate { match ->
                match.groupValues[1] to match.groupValues[2]
            }
            if (!values.containsKey("input_i") || !values.containsKey("input_tp")) continue
            result = SpatialLoudnessReading(
                integratedLufs = number(values, "input_i"),
                truePeakDbtp = number(values, "input_tp"),
                loudnessRangeLu = number(values, "input_lra"),
                thresholdLufs = number(values, "input_thresh"),
            )
        }
        return result
    }

    private fun number(values: Map<String, String>, name: String): Double? =
        values[name]?.toDoubleOrNull()?.takeIf(Double::isFinite)

    private fun jsonObjects(text: String): List<String> {
        val objects = mutableListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        text.forEachIndexed { index, character ->
            if (start < 0) {
                if (character == '{') {
                    start = index
                    depth = 1
                    inString = false
                    escaped = false
                }
                return@forEachIndexed
            }
            if (inString) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
                return@forEachIndexed
            }
            when (character) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        objects += text.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return objects
    }
}
