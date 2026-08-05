package com.aistudio.mediatool.core.spatial

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class SpatialRoomFitResult(
    val config: SpatialAudioConfig,
    val adjusted: Boolean,
    val scale: Float,
    val requestedMaxDistanceM: Float,
    val appliedMaxDistanceM: Float,
    val maximumDistanceM: Float,
    val marginM: Float,
    val sampleCount: Int,
) {
    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "room_trajectory_adjusted" to adjusted,
        "room_trajectory_scale" to scale,
        "room_requested_max_distance_m" to requestedMaxDistanceM,
        "room_applied_max_distance_m" to appliedMaxDistanceM,
        "room_trajectory_max_distance_m" to maximumDistanceM,
        "room_trajectory_margin_m" to marginM,
        "room_trajectory_samples" to sampleCount,
    )
}

/**
 * Fits the complete trajectory inside the current room before native rendering. A single uniform
 * scale is applied to both endpoint distances, preserving the intended trajectory shape instead of
 * clipping individual coordinates against room walls.
 */
internal object SpatialRoomTrajectoryPolicy {
    private const val ROOM_MARGIN_M = 0.25f
    private const val SAMPLE_COUNT = 256
    private const val SAFETY_SCALE = 0.995f
    private const val EPSILON = 1e-5f

    fun fit(config: SpatialAudioConfig): SpatialRoomFitResult {
        val value = config.normalized()
        val requestedMax = max(value.startDistanceM, value.endDistanceM)
        val maximum = maximumDistance(value)
        if (value.roomPreset.acoustics.outdoor || value.reverbWet <= EPSILON || requestedMax <= maximum) {
            return SpatialRoomFitResult(
                config = value,
                adjusted = false,
                scale = 1f,
                requestedMaxDistanceM = requestedMax,
                appliedMaxDistanceM = requestedMax,
                maximumDistanceM = maximum,
                marginM = ROOM_MARGIN_M,
                sampleCount = SAMPLE_COUNT,
            )
        }

        val scale = (maximum / requestedMax * SAFETY_SCALE).coerceIn(0.01f, 1f)
        val adjusted = value.copy(
            startDistanceM = (value.startDistanceM * scale).coerceAtLeast(0.2f),
            endDistanceM = (value.endDistanceM * scale).coerceAtLeast(0.2f),
        ).normalized()
        return SpatialRoomFitResult(
            config = adjusted,
            adjusted = true,
            scale = scale,
            requestedMaxDistanceM = requestedMax,
            appliedMaxDistanceM = max(adjusted.startDistanceM, adjusted.endDistanceM),
            maximumDistanceM = maximum,
            marginM = ROOM_MARGIN_M,
            sampleCount = SAMPLE_COUNT,
        )
    }

    fun maximumDistance(config: SpatialAudioConfig): Float {
        val value = config.normalized()
        val dimensions = value.roomPreset.acoustics.dimensions
            ?: return SpatialAudioConfig.FRIENDLY_DISTANCE_MAX_M
        if (value.reverbWet <= EPSILON) return SpatialAudioConfig.FRIENDLY_DISTANCE_MAX_M

        val unitDistance = value.copy(startDistanceM = 1f, endDistanceM = 1f)
        val maxX = max(0.05f, dimensions.widthM * 0.5f - ROOM_MARGIN_M)
        val maxY = max(0.05f, dimensions.heightM * 0.5f - ROOM_MARGIN_M)
        val maxZ = max(0.05f, dimensions.depthM * 0.5f - ROOM_MARGIN_M)
        var limit = Float.POSITIVE_INFINITY
        for (index in 0 until SAMPLE_COUNT) {
            val seconds = unitDistance.cycleSeconds * index.toFloat() / SAMPLE_COUNT.toFloat()
            val pose = SpatialTrajectoryMath.pose(unitDistance, seconds)
            val poseLimit = min(
                axisLimit(pose.x, maxX),
                min(axisLimit(pose.y, maxY), axisLimit(pose.z, maxZ)),
            )
            limit = min(limit, poseLimit)
        }
        return if (limit.isFinite()) {
            (limit * SAFETY_SCALE).coerceIn(0.2f, SpatialAudioConfig.FRIENDLY_DISTANCE_MAX_M)
        } else {
            SpatialAudioConfig.FRIENDLY_DISTANCE_MAX_M
        }
    }

    internal fun isPoseInside(config: SpatialAudioConfig, pose: SpatialPose): Boolean {
        val dimensions = config.roomPreset.acoustics.dimensions ?: return true
        val x = pose.x * pose.distanceM
        val y = pose.y * pose.distanceM
        val z = pose.z * pose.distanceM
        return abs(x) <= dimensions.widthM * 0.5f - ROOM_MARGIN_M + 1e-3f &&
            abs(y) <= dimensions.heightM * 0.5f - ROOM_MARGIN_M + 1e-3f &&
            abs(z) <= dimensions.depthM * 0.5f - ROOM_MARGIN_M + 1e-3f
    }

    private fun axisLimit(component: Float, extent: Float): Float =
        if (abs(component) <= EPSILON) Float.POSITIVE_INFINITY else extent / abs(component)
}
