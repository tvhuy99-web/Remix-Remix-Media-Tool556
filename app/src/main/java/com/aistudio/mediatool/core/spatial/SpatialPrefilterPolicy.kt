package com.aistudio.mediatool.core.spatial

internal data class SpatialPrefilterDecision(
    val allowed: List<String>,
    val suppressed: List<String>,
) {
    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "pre_filter_count" to allowed.size,
        "suppressed_pre_filter_count" to suppressed.size,
        "suppressed_pre_filter_types" to suppressed
            .map(SpatialPrefilterPolicy::typeOf)
            .distinct()
            .sorted()
            .joinToString(","),
    )
}

/** Prevents legacy position/room filters from contradicting the Steam Audio scene. */
internal object SpatialPrefilterPolicy {
    fun apply(filters: List<String>): SpatialPrefilterDecision {
        val allowed = mutableListOf<String>()
        val suppressed = mutableListOf<String>()
        filters.forEach { raw ->
            val filter = raw.trim()
            if (filter.isEmpty()) return@forEach
            if (isConflicting(filter)) suppressed += filter else allowed += filter
        }
        return SpatialPrefilterDecision(allowed = allowed, suppressed = suppressed)
    }

    internal fun typeOf(filter: String): String = when {
        filter.startsWith("alimiter=", ignoreCase = true) -> "limiter"
        filter.startsWith("apulsator=", ignoreCase = true) -> "auto_pan"
        filter.startsWith("aecho=", ignoreCase = true) -> "echo_or_legacy_reverb"
        filter.startsWith("pan=mono", ignoreCase = true) -> "mono_downmix"
        filter.startsWith("pan=stereo", ignoreCase = true) -> "manual_pan"
        else -> filter.substringBefore('=').lowercase()
    }

    private fun isConflicting(filter: String): Boolean =
        filter.startsWith("alimiter=", ignoreCase = true) ||
            filter.startsWith("apulsator=", ignoreCase = true) ||
            filter.startsWith("aecho=", ignoreCase = true) ||
            filter.startsWith("pan=mono", ignoreCase = true) ||
            filter.startsWith("pan=stereo", ignoreCase = true)
}
