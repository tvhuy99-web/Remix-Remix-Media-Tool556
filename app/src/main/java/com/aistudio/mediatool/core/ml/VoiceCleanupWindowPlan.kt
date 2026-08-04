package com.aistudio.mediatool.core.ml

/** Resolves the actual model context after the decoded sample count is known. */
internal data class VoiceCleanupWindowPlan(
    val mode: VoiceCleanupWindowMode,
    val segmentSamples: Int,
    val strideSamples: Int,
    val edgeDiscardSamples: Int,
    val fullContext: Boolean,
    val minimumAvailableRamBytes: Long,
) {
    val frames: Int
        get() = MossFormer2Dsp.frameCount(segmentSamples)

    internal fun diagnosticFields(): Map<String, Any?> = mapOf(
        "actual_segment_samples" to segmentSamples,
        "actual_stride_samples" to strideSamples,
        "actual_edge_discard_samples" to edgeDiscardSamples,
        "actual_feature_frames" to frames,
        "full_context" to fullContext,
        "required_ram_bytes" to minimumAvailableRamBytes,
    )

    companion object {
        fun resolve(totalSamples: Long, mode: VoiceCleanupWindowMode): VoiceCleanupWindowPlan {
            require(totalSamples >= 0L)
            if (totalSamples <= mode.onePassLimitSamples.toLong()) {
                val requested = maxOf(totalSamples, MossFormer2Dsp.REFERENCE_SEGMENT_SAMPLES.toLong())
                val aligned = MossFormer2Dsp.alignSegmentSamples(requested)
                return VoiceCleanupWindowPlan(
                    mode = mode,
                    segmentSamples = aligned,
                    strideSamples = aligned,
                    edgeDiscardSamples = 0,
                    fullContext = true,
                    minimumAvailableRamBytes = mode.minimumAvailableRamBytes,
                )
            }
            return fixed(mode)
        }

        fun fixed(mode: VoiceCleanupWindowMode): VoiceCleanupWindowPlan = VoiceCleanupWindowPlan(
            mode = mode,
            segmentSamples = mode.segmentSamples,
            strideSamples = mode.strideSamples,
            edgeDiscardSamples = mode.edgeDiscardSamples,
            fullContext = false,
            minimumAvailableRamBytes = mode.minimumAvailableRamBytes,
        )
    }
}
