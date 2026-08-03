package com.aistudio.mediatool.core.media

object MediaCommandSanitizer {
    data class Result(
        val command: String,
        val adjustments: Set<String> = emptySet(),
    )

    private val audioFilterArgument = Regex("-af\\s+\"([^\"]*)\"")
    private val timelineOption = Regex(":enable='[^']*'")
    private val denoiseNoiseFloor = Regex("^afftdn=nf=-([0-9]+(?:\\.[0-9]+)?)(.*)$")
    private val asetrate = Regex("^asetrate=([0-9]+(?:\\.[0-9]+)?)$")
    private val atempo = Regex("^atempo=([0-9]+(?:\\.[0-9]+)?)$")

    fun sanitize(command: String): Result {
        val match = audioFilterArgument.find(command) ?: return Result(command)
        val filters = splitFilterChain(match.groupValues[1]).toMutableList()
        val adjustments = linkedSetOf<String>()

        disableUnsupportedTimelines(filters, adjustments)
        convertDenoiseParameter(filters, adjustments)
        bypassZeroWetReverb(filters, adjustments)
        disableVideoSilenceRemoval(command, filters, adjustments)
        stabilizeSpeedAndPitch(command, filters, adjustments)
        moveLoudnessToFinalStage(filters, adjustments)

        val replacement = if (filters.isEmpty()) {
            ""
        } else {
            "-af \"${filters.joinToString(",\")}\""
        }
        val sanitized = command.replaceRange(match.range, replacement)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return Result(sanitized, adjustments)
    }

    private fun disableUnsupportedTimelines(
        filters: MutableList<String>,
        adjustments: MutableSet<String>,
    ) {
        filters.indices.forEach { index ->
            val filter = filters[index]
            if (
                filter.startsWith("pan=") ||
                filter.startsWith("apulsator=") ||
                filter.startsWith("acompressor=")
            ) {
                val cleaned = filter.replace(timelineOption, "")
                if (cleaned != filter) {
                    filters[index] = cleaned
                    adjustments += "unsupported_timeline_disabled"
                }
            }
        }
    }

    private fun convertDenoiseParameter(
        filters: MutableList<String>,
        adjustments: MutableSet<String>,
    ) {
        val gateNoiseFloorIndices = filters.indices
            .filter { filters[it].startsWith("agate=") }
            .mapNotNull { gateIndex ->
                (gateIndex - 1).takeIf { it >= 0 && filters[it].startsWith("afftdn=nf=") }
            }
            .toSet()

        filters.indices.forEach { index ->
            if (index in gateNoiseFloorIndices) return@forEach
            val match = denoiseNoiseFloor.matchEntire(filters[index]) ?: return@forEach
            val requested = match.groupValues[1].toFloatOrNull() ?: return@forEach
            val suffix = match.groupValues[2]
            val safe = MediaEffectRules.denoiseReductionDb(requested)
            filters[index] = "afftdn=nr=${format(safe, 2)}$suffix"
            adjustments += "denoise_parameter_corrected"
        }
    }

    private fun bypassZeroWetReverb(
        filters: MutableList<String>,
        adjustments: MutableSet<String>,
    ) {
        val iterator = filters.listIterator()
        while (iterator.hasNext()) {
            val filter = iterator.next()
            if (!filter.startsWith("aecho=")) continue
            val parts = filter.removePrefix("aecho=").split(':')
            if (parts.size < 4) continue
            val decays = parts[3].split('|').mapNotNull(String::toDoubleOrNull)
            if (decays.isNotEmpty() && decays.all { it <= 0.0 }) {
                iterator.remove()
                adjustments += "zero_wet_reverb_bypassed"
            }
        }
    }

    private fun disableVideoSilenceRemoval(
        command: String,
        filters: MutableList<String>,
        adjustments: MutableSet<String>,
    ) {
        if (!command.contains("-c:v copy")) return
        if (filters.removeAll { it.startsWith("silenceremove=") }) {
            adjustments += "video_silence_removal_disabled"
        }
    }

    private fun stabilizeSpeedAndPitch(
        command: String,
        filters: MutableList<String>,
        adjustments: MutableSet<String>,
    ) {
        var index = 0
        while (index < filters.size) {
            val rateMatch = asetrate.matchEntire(filters[index])
            if (rateMatch == null) {
                index++
                continue
            }

            if (index == 0 || filters[index - 1] != "aresample=44100") {
                filters.add(index, "aresample=44100")
                adjustments += "source_sample_rate_normalized"
                index++
            }

            val shiftedRate = rateMatch.groupValues[1].toDoubleOrNull() ?: 44_100.0
            var cursor = index + 1
            if (cursor < filters.size && filters[cursor] == "aresample=44100") cursor++
            val tempoStart = cursor
            while (cursor < filters.size && atempo.matches(filters[cursor])) cursor++

            if (command.contains("-c:v copy")) {
                repeat(cursor - tempoStart) { filters.removeAt(tempoStart) }
                val pitch = (shiftedRate / 44_100.0).coerceIn(0.5, 2.0)
                val replacement = MediaEffectRules.atempoFilters((1.0 / pitch).toFloat())
                filters.addAll(tempoStart, replacement)
                adjustments += "hidden_video_speed_neutralized"
                cursor = tempoStart + replacement.size
            }
            index = cursor
        }
    }

    private fun moveLoudnessToFinalStage(
        filters: MutableList<String>,
        adjustments: MutableSet<String>,
    ) {
        val loudness = mutableListOf<String>()
        var index = 0
        while (index < filters.size) {
            if (!filters[index].startsWith("loudnorm=")) {
                index++
                continue
            }
            loudness += filters.removeAt(index)
            if (index < filters.size && filters[index] == "aresample=48000") {
                loudness += filters.removeAt(index)
            }
        }
        if (loudness.isEmpty()) return

        val limiterIndex = filters.indexOfFirst { it.startsWith("alimiter=") }
            .let { if (it < 0) filters.size else it }
        filters.addAll(limiterIndex, loudness)
        adjustments += "loudness_moved_to_final_stage"
    }

    internal fun splitFilterChain(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var insideSingleQuotes = false
        value.forEach { char ->
            when {
                char == '\'' -> {
                    insideSingleQuotes = !insideSingleQuotes
                    current.append(char)
                }
                char == ',' && !insideSingleQuotes -> {
                    current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
        return result
    }

    private fun format(value: Float, decimals: Int): String =
        java.lang.String.format(java.util.Locale.US, "%.${decimals}f", value)
}
