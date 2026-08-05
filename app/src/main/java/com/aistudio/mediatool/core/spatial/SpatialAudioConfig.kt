package com.aistudio.mediatool.core.spatial

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cấu hình đầy đủ cho renderer binaural. Các giá trị được chuẩn hóa trước khi
 * truyền sang native để không đưa NaN, vô cực hoặc tham số ngoài miền vào DSP.
 */
data class SpatialAudioConfig(
    val trajectory: SpatialTrajectory = SpatialTrajectory.HORIZONTAL_CIRCLE,
    val interpolation: SpatialInterpolation = SpatialInterpolation.BILINEAR,
    val motionMode: SpatialMotionMode = SpatialMotionMode.LOOP,
    val startAzimuthDeg: Float = -90f,
    val endAzimuthDeg: Float = 270f,
    val startElevationDeg: Float = 0f,
    val endElevationDeg: Float = 0f,
    val startDistanceM: Float = 1.5f,
    val endDistanceM: Float = 1.5f,
    val cycleSeconds: Float = 8f,
    val spatialBlend: Float = 1f,
    val distanceMinM: Float = 1f,
    val distanceRolloff: Float = 1f,
    val airAbsorption: Float = 1f,
    val directivityWeight: Float = 0f,
    val directivityPower: Float = 1f,
    val sourceYawDeg: Float = 0f,
    val reverbWet: Float = 0f,
    val reverbRt60Low: Float = 0.8f,
    val reverbRt60Mid: Float = 0.7f,
    val reverbRt60High: Float = 0.5f,
    val reverbEqLow: Float = 1f,
    val reverbEqMid: Float = 1f,
    val reverbEqHigh: Float = 1f,
    val outputGainDb: Float = 0f,
    val effectStartSeconds: Float = 0f,
    val effectEndSeconds: Float = -1f,
    val customSofaPath: String? = null,
    val frameSize: Int = 1024,
) {
    fun normalized(): SpatialAudioConfig {
        fun finite(value: Float, fallback: Float): Float = if (value.isFinite()) value else fallback
        val safeStart = finite(effectStartSeconds, 0f).coerceAtLeast(0f)
        val rawEnd = finite(effectEndSeconds, -1f)
        val safeEnd = if (rawEnd < 0f) -1f else rawEnd.coerceAtLeast(safeStart)
        return copy(
            startAzimuthDeg = finite(startAzimuthDeg, -90f).coerceIn(-720f, 720f),
            endAzimuthDeg = finite(endAzimuthDeg, 270f).coerceIn(-720f, 720f),
            startElevationDeg = finite(startElevationDeg, 0f).coerceIn(-90f, 90f),
            endElevationDeg = finite(endElevationDeg, 0f).coerceIn(-90f, 90f),
            startDistanceM = finite(startDistanceM, 1.5f).coerceIn(0.2f, 100f),
            endDistanceM = finite(endDistanceM, 1.5f).coerceIn(0.2f, 100f),
            cycleSeconds = finite(cycleSeconds, 8f).coerceIn(0.5f, 120f),
            spatialBlend = finite(spatialBlend, 1f).coerceIn(0f, 1f),
            distanceMinM = finite(distanceMinM, 1f).coerceIn(0.1f, 20f),
            distanceRolloff = finite(distanceRolloff, 1f).coerceIn(0.1f, 4f),
            airAbsorption = finite(airAbsorption, 1f).coerceIn(0f, 2f),
            directivityWeight = finite(directivityWeight, 0f).coerceIn(0f, 1f),
            directivityPower = finite(directivityPower, 1f).coerceIn(1f, 8f),
            sourceYawDeg = finite(sourceYawDeg, 0f).coerceIn(-180f, 180f),
            reverbWet = finite(reverbWet, 0f).coerceIn(0f, 1f),
            reverbRt60Low = finite(reverbRt60Low, 0.8f).coerceIn(0.1f, 10f),
            reverbRt60Mid = finite(reverbRt60Mid, 0.7f).coerceIn(0.1f, 10f),
            reverbRt60High = finite(reverbRt60High, 0.5f).coerceIn(0.1f, 10f),
            reverbEqLow = finite(reverbEqLow, 1f).coerceIn(0f, 1f),
            reverbEqMid = finite(reverbEqMid, 1f).coerceIn(0f, 1f),
            reverbEqHigh = finite(reverbEqHigh, 1f).coerceIn(0f, 1f),
            outputGainDb = finite(outputGainDb, 0f).coerceIn(-24f, 6f),
            effectStartSeconds = safeStart,
            effectEndSeconds = safeEnd,
            customSofaPath = customSofaPath?.trim()?.takeIf(String::isNotEmpty),
            frameSize = frameSize.coerceIn(256, 4096).let { value ->
                Integer.highestOneBit(value).coerceAtLeast(256)
            },
        )
    }

    fun diagnosticFields(): Map<String, Any?> = normalized().let { value ->
        mapOf(
            "trajectory" to value.trajectory.name,
            "interpolation" to value.interpolation.name,
            "motion_mode" to value.motionMode.name,
            "start_azimuth_deg" to value.startAzimuthDeg,
            "end_azimuth_deg" to value.endAzimuthDeg,
            "start_elevation_deg" to value.startElevationDeg,
            "end_elevation_deg" to value.endElevationDeg,
            "start_distance_m" to value.startDistanceM,
            "end_distance_m" to value.endDistanceM,
            "cycle_seconds" to value.cycleSeconds,
            "spatial_blend" to value.spatialBlend,
            "distance_min_m" to value.distanceMinM,
            "distance_rolloff" to value.distanceRolloff,
            "air_absorption" to value.airAbsorption,
            "directivity_weight" to value.directivityWeight,
            "directivity_power" to value.directivityPower,
            "source_yaw_deg" to value.sourceYawDeg,
            "reverb_wet" to value.reverbWet,
            "reverb_rt60_low" to value.reverbRt60Low,
            "reverb_rt60_mid" to value.reverbRt60Mid,
            "reverb_rt60_high" to value.reverbRt60High,
            "reverb_eq_low" to value.reverbEqLow,
            "reverb_eq_mid" to value.reverbEqMid,
            "reverb_eq_high" to value.reverbEqHigh,
            "output_gain_db" to value.outputGainDb,
            "effect_start_seconds" to value.effectStartSeconds,
            "effect_end_seconds" to value.effectEndSeconds,
            "hrtf_type" to if (value.customSofaPath == null) "built_in" else "custom_sofa",
            "frame_size" to value.frameSize,
        )
    }
}

enum class SpatialTrajectory(val label: String) {
    HORIZONTAL_CIRCLE("Vòng ngang 360°"),
    VERTICAL_CIRCLE("Vòng dọc qua trên đầu"),
    FIGURE_EIGHT("Quỹ đạo hình số 8"),
    LINEAR("Điểm đầu → điểm cuối"),
    STATIC("Đứng yên tại một vị trí"),
}

enum class SpatialInterpolation(val label: String) {
    BILINEAR("Bilinear • mượt nhất"),
    NEAREST("Điểm gần nhất • nhẹ CPU"),
}

enum class SpatialMotionMode(val label: String) {
    LOOP("Lặp theo chu kỳ"),
    ONCE("Chạy một lần rồi dừng"),
}

data class SpatialPose(
    val x: Float,
    val y: Float,
    val z: Float,
    val distanceM: Float,
)

/** Oracle Kotlin dùng cho unit test và hiển thị; công thức được khóa giống native. */
object SpatialTrajectoryMath {
    fun pose(config: SpatialAudioConfig, seconds: Float): SpatialPose {
        val value = config.normalized()
        val phase = when (value.motionMode) {
            SpatialMotionMode.LOOP -> positiveModulo(seconds / value.cycleSeconds, 1f)
            SpatialMotionMode.ONCE -> (seconds / value.cycleSeconds).coerceIn(0f, 1f)
        }
        val eased = phase * phase * (3f - 2f * phase)
        val distance = lerp(value.startDistanceM, value.endDistanceM, eased)
        return when (value.trajectory) {
            SpatialTrajectory.HORIZONTAL_CIRCLE -> fromAngles(
                azimuthDeg = lerp(value.startAzimuthDeg, value.endAzimuthDeg, phase),
                elevationDeg = lerp(value.startElevationDeg, value.endElevationDeg, eased),
                distanceM = distance,
            )
            SpatialTrajectory.VERTICAL_CIRCLE -> {
                val theta = 2.0 * PI * phase
                val yaw = Math.toRadians(value.startAzimuthDeg.toDouble())
                normalize(
                    x = (sin(yaw) * cos(theta)).toFloat(),
                    y = sin(theta).toFloat(),
                    z = (-cos(yaw) * cos(theta)).toFloat(),
                    distanceM = distance,
                )
            }
            SpatialTrajectory.FIGURE_EIGHT -> {
                val theta = 2.0 * PI * phase
                fromAngles(
                    azimuthDeg = lerp(
                        value.startAzimuthDeg,
                        value.endAzimuthDeg,
                        (0.5 + 0.5 * sin(theta)).toFloat(),
                    ),
                    elevationDeg = lerp(
                        value.startElevationDeg,
                        value.endElevationDeg,
                        (0.5 + 0.5 * sin(2.0 * theta)).toFloat(),
                    ),
                    distanceM = distance,
                )
            }
            SpatialTrajectory.LINEAR -> fromAngles(
                azimuthDeg = lerp(value.startAzimuthDeg, value.endAzimuthDeg, eased),
                elevationDeg = lerp(value.startElevationDeg, value.endElevationDeg, eased),
                distanceM = distance,
            )
            SpatialTrajectory.STATIC -> fromAngles(
                azimuthDeg = value.startAzimuthDeg,
                elevationDeg = value.startElevationDeg,
                distanceM = value.startDistanceM,
            )
        }
    }

    private fun fromAngles(azimuthDeg: Float, elevationDeg: Float, distanceM: Float): SpatialPose {
        val azimuth = Math.toRadians(azimuthDeg.toDouble())
        val elevation = Math.toRadians(elevationDeg.toDouble())
        val horizontal = cos(elevation)
        return normalize(
            x = (horizontal * sin(azimuth)).toFloat(),
            y = sin(elevation).toFloat(),
            z = (-horizontal * cos(azimuth)).toFloat(),
            distanceM = distanceM,
        )
    }

    private fun normalize(x: Float, y: Float, z: Float, distanceM: Float): SpatialPose {
        val length = kotlin.math.sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
        return SpatialPose(x / length, y / length, z / length, distanceM)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)

    private fun positiveModulo(value: Float, divisor: Float): Float =
        ((value % divisor) + divisor) % divisor
}
