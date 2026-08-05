package com.aistudio.mediatool.core.spatial

import java.io.File
import kotlin.math.ceil

internal data class SpatialDiskBudget(
    val decodedBytes: Long,
    val estimatedRenderedBytes: Long,
    val additionalRequiredBytes: Long,
    val usableBytes: Long,
) {
    val hasCapacity: Boolean get() = usableBytes >= additionalRequiredBytes

    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "disk_decoded_bytes" to decodedBytes,
        "disk_estimated_rendered_bytes" to estimatedRenderedBytes,
        "disk_additional_required_bytes" to additionalRequiredBytes,
        "disk_usable_bytes" to usableBytes,
        "disk_guard_passed" to hasCapacity,
    )
}

internal object SpatialDiskBudgetEstimator {
    private const val SAMPLE_RATE = 48_000L
    private const val CHANNELS = 2L
    private const val FLOAT_BYTES = 4L
    private const val FIXED_MARGIN_BYTES = 256L * 1024L * 1024L
    private const val MAX_ESTIMATED_TAIL_SECONDS = 8.0

    fun estimate(decodedPcm: File, output: File, config: SpatialAudioConfig): SpatialDiskBudget {
        val decodedBytes = decodedPcm.length().coerceAtLeast(0L)
        val roomIrSeconds = RoomReflectionNativeSpec.balanced(config.roomPreset).durationSeconds.toDouble()
        val reverbSeconds = maxOf(
            config.reverbRt60Low.toDouble(),
            config.reverbRt60Mid.toDouble(),
            config.reverbRt60High.toDouble(),
            roomIrSeconds,
        ).coerceIn(0.0, MAX_ESTIMATED_TAIL_SECONDS)
        val tailBytes = ceil((reverbSeconds + 1.0) * bytesPerSecond()).toLong()
        val renderedBytes = saturatedAdd(decodedBytes, tailBytes)

        // Native pass 1 writes a temporary rendered PCM. Pass 2 writes the final rendered PCM
        // while the temporary file still exists, so peak additional usage is roughly 2x rendered.
        val additionalRequired = saturatedAdd(
            saturatedMultiply(renderedBytes, 2L),
            FIXED_MARGIN_BYTES,
        )
        val storageRoot = output.parentFile ?: decodedPcm.parentFile ?: File(".")
        return SpatialDiskBudget(
            decodedBytes = decodedBytes,
            estimatedRenderedBytes = renderedBytes,
            additionalRequiredBytes = additionalRequired,
            usableBytes = storageRoot.usableSpace.coerceAtLeast(0L),
        )
    }

    fun requireCapacity(decodedPcm: File, output: File, config: SpatialAudioConfig): SpatialDiskBudget {
        val budget = estimate(decodedPcm, output, config)
        require(budget.hasCapacity) {
            "Không đủ dung lượng tạm cho Spatial Audio. Cần khoảng " +
                "${formatBytes(budget.additionalRequiredBytes)}, còn " +
                "${formatBytes(budget.usableBytes)}. Hãy giải phóng bộ nhớ hoặc xử lý đoạn ngắn hơn."
        }
        return budget
    }

    internal fun estimateForTest(
        decodedBytes: Long,
        usableBytes: Long,
        tailSeconds: Double,
    ): SpatialDiskBudget {
        val safeTail = tailSeconds.coerceIn(0.0, MAX_ESTIMATED_TAIL_SECONDS)
        val tailBytes = ceil((safeTail + 1.0) * bytesPerSecond()).toLong()
        val rendered = saturatedAdd(decodedBytes.coerceAtLeast(0L), tailBytes)
        return SpatialDiskBudget(
            decodedBytes = decodedBytes.coerceAtLeast(0L),
            estimatedRenderedBytes = rendered,
            additionalRequiredBytes = saturatedAdd(
                saturatedMultiply(rendered, 2L),
                FIXED_MARGIN_BYTES,
            ),
            usableBytes = usableBytes.coerceAtLeast(0L),
        )
    }

    private fun bytesPerSecond(): Long = SAMPLE_RATE * CHANNELS * FLOAT_BYTES

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun saturatedMultiply(value: Long, factor: Long): Long =
        if (value > Long.MAX_VALUE / factor) Long.MAX_VALUE else value * factor

    private fun formatBytes(bytes: Long): String {
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) {
            String.format(java.util.Locale.US, "%.2f GB", gib)
        } else {
            val mib = bytes.toDouble() / (1024.0 * 1024.0)
            String.format(java.util.Locale.US, "%.0f MB", mib)
        }
    }
}
