package com.aistudio.mediatool.core.spatial

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Cấu hình lõi của renderer binaural. Giao diện thông thường chỉ ánh xạ các
 * điều khiển thân thiện vào cấu hình này; tham số phòng được suy ra từ một mô
 * hình hình học và vật liệu ổn định để chuẩn bị cho reflection simulator.
 */
data class SpatialAudioConfig(
    val trajectory: SpatialTrajectory = SpatialTrajectory.HORIZONTAL_CIRCLE,
    val interpolation: SpatialInterpolation = SpatialInterpolation.BILINEAR,
    val motionMode: SpatialMotionMode = SpatialMotionMode.LOOP,
    val roomPreset: SpatialRoomPreset = SpatialRoomPreset.LISTENING_ROOM,
    val startAzimuthDeg: Float = -90f,
    val endAzimuthDeg: Float = 270f,
    val startElevationDeg: Float = 0f,
    val endElevationDeg: Float = 0f,
    val startDistanceM: Float = 1.2f,
    val endDistanceM: Float = 1.2f,
    val cycleSeconds: Float = 8f,
    val spatialBlend: Float = 0.85f,
    val distanceMinM: Float = 1.2f,
    val distanceRolloff: Float = 0.65f,
    val airAbsorption: Float = 0.35f,
    val directivityWeight: Float = 0f,
    val directivityPower: Float = 1f,
    val sourceYawDeg: Float = 0f,
    val reverbWet: Float = 0.12f,
    val reverbRt60Low: Float = 0.7f,
    val reverbRt60Mid: Float = 0.6f,
    val reverbRt60High: Float = 0.45f,
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
            startDistanceM = finite(startDistanceM, 1.2f).coerceIn(0.2f, 100f),
            endDistanceM = finite(endDistanceM, 1.2f).coerceIn(0.2f, 100f),
            cycleSeconds = finite(cycleSeconds, 8f).coerceIn(0.5f, 120f),
            spatialBlend = finite(spatialBlend, 0.85f).coerceIn(0f, 1f),
            distanceMinM = finite(distanceMinM, 1.2f).coerceIn(0.1f, 20f),
            distanceRolloff = finite(distanceRolloff, 0.65f).coerceIn(0.1f, 4f),
            airAbsorption = finite(airAbsorption, 0.35f).coerceIn(0f, 2f),
            directivityWeight = finite(directivityWeight, 0f).coerceIn(0f, 1f),
            directivityPower = finite(directivityPower, 1f).coerceIn(1f, 8f),
            sourceYawDeg = finite(sourceYawDeg, 0f).coerceIn(-180f, 180f),
            reverbWet = finite(reverbWet, 0.12f).coerceIn(0f, 1f),
            reverbRt60Low = finite(reverbRt60Low, 0.7f).coerceIn(0.1f, 10f),
            reverbRt60Mid = finite(reverbRt60Mid, 0.6f).coerceIn(0.1f, 10f),
            reverbRt60High = finite(reverbRt60High, 0.45f).coerceIn(0.1f, 10f),
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

    fun withFriendlyTrajectory(next: SpatialTrajectory): SpatialAudioConfig {
        val fixedDistance = max(
            FRIENDLY_DISTANCE_MIN_M,
            max(startDistanceM, endDistanceM),
        )
        return when (next) {
            SpatialTrajectory.HORIZONTAL_CIRCLE -> copy(
                trajectory = next,
                motionMode = SpatialMotionMode.LOOP,
                startAzimuthDeg = -90f,
                endAzimuthDeg = 270f,
                startElevationDeg = 0f,
                endElevationDeg = 0f,
                startDistanceM = fixedDistance,
                endDistanceM = fixedDistance,
            )
            SpatialTrajectory.VERTICAL_CIRCLE -> copy(
                trajectory = next,
                motionMode = SpatialMotionMode.LOOP,
                startAzimuthDeg = 0f,
                endAzimuthDeg = 0f,
                startElevationDeg = 0f,
                endElevationDeg = 0f,
                startDistanceM = fixedDistance,
                endDistanceM = fixedDistance,
            )
            SpatialTrajectory.FIGURE_EIGHT -> copy(
                trajectory = next,
                motionMode = SpatialMotionMode.LOOP,
                startAzimuthDeg = -110f,
                endAzimuthDeg = 110f,
                startElevationDeg = -30f,
                endElevationDeg = 30f,
                startDistanceM = fixedDistance,
                endDistanceM = fixedDistance,
            )
            SpatialTrajectory.LINEAR -> copy(
                trajectory = next,
                motionMode = SpatialMotionMode.ONCE,
                startAzimuthDeg = -100f,
                endAzimuthDeg = 100f,
                startElevationDeg = 0f,
                endElevationDeg = 0f,
                startDistanceM = fixedDistance,
                endDistanceM = fixedDistance,
            )
            SpatialTrajectory.STATIC -> copy(
                trajectory = next,
                motionMode = SpatialMotionMode.ONCE,
                startAzimuthDeg = 0f,
                endAzimuthDeg = 0f,
                startElevationDeg = 0f,
                endElevationDeg = 0f,
                startDistanceM = fixedDistance,
                endDistanceM = fixedDistance,
            )
            SpatialTrajectory.PENDULUM -> copy(
                trajectory = next,
                motionMode = SpatialMotionMode.LOOP,
                startAzimuthDeg = -75f,
                endAzimuthDeg = 75f,
                startElevationDeg = -5f,
                endElevationDeg = 10f,
                startDistanceM = fixedDistance,
                endDistanceM = fixedDistance,
            )
            SpatialTrajectory.FRONT_BACK -> copy(
                trajectory = next,
                motionMode = SpatialMotionMode.LOOP,
                startAzimuthDeg = 0f,
                endAzimuthDeg = 180f,
                startElevationDeg = 0f,
                endElevationDeg = 45f,
                startDistanceM = fixedDistance,
                endDistanceM = fixedDistance,
            )
            SpatialTrajectory.SPIRAL -> copy(
                trajectory = next,
                motionMode = SpatialMotionMode.LOOP,
                startAzimuthDeg = -90f,
                endAzimuthDeg = 270f,
                startElevationDeg = -30f,
                endElevationDeg = 30f,
                startDistanceM = fixedDistance,
                endDistanceM = fixedDistance,
            )
            SpatialTrajectory.NEAR_FAR -> {
                val farDistance = max(2.5f, fixedDistance)
                copy(
                    trajectory = next,
                    motionMode = SpatialMotionMode.LOOP,
                    startAzimuthDeg = -90f,
                    endAzimuthDeg = 270f,
                    startElevationDeg = 0f,
                    endElevationDeg = 0f,
                    startDistanceM = max(FRIENDLY_DISTANCE_MIN_M, farDistance * NEAR_FAR_RATIO),
                    endDistanceM = farDistance,
                )
            }
            SpatialTrajectory.FREE_DRIFT -> {
                val farDistance = max(2.2f, fixedDistance)
                copy(
                    trajectory = next,
                    motionMode = SpatialMotionMode.LOOP,
                    startAzimuthDeg = -110f,
                    endAzimuthDeg = 110f,
                    startElevationDeg = -25f,
                    endElevationDeg = 25f,
                    startDistanceM = max(FRIENDLY_DISTANCE_MIN_M, farDistance * FREE_DRIFT_NEAR_RATIO),
                    endDistanceM = farDistance,
                )
            }
        }.normalized()
    }

    fun withRoomPreset(next: SpatialRoomPreset): SpatialAudioConfig {
        val reflectionPosition = friendlyReflectionPosition()
        val room = next.acoustics
        return copy(
            roomPreset = next,
            distanceRolloff = room.distanceRolloff,
            airAbsorption = room.airAbsorption,
            reverbWet = room.maxReflectionWet * reflectionCurve(reflectionPosition),
            reverbRt60Low = room.rt60Seconds.low,
            reverbRt60Mid = room.rt60Seconds.mid,
            reverbRt60High = room.rt60Seconds.high,
            reverbEqLow = room.reverbEq.low,
            reverbEqMid = room.reverbEq.mid,
            reverbEqHigh = room.reverbEq.high,
        ).normalized()
    }

    fun fitToRoom(): SpatialAudioConfig = SpatialRoomTrajectoryPolicy.fit(normalized()).config

    fun friendlySpeedPosition(): Float =
        ((FRIENDLY_SPEED_MAX_SECONDS - cycleSeconds) /
            (FRIENDLY_SPEED_MAX_SECONDS - FRIENDLY_SPEED_MIN_SECONDS)).coerceIn(0f, 1f)

    fun withFriendlySpeed(position: Float): SpatialAudioConfig = copy(
        cycleSeconds = FRIENDLY_SPEED_MAX_SECONDS -
            (FRIENDLY_SPEED_MAX_SECONDS - FRIENDLY_SPEED_MIN_SECONDS) * position.coerceIn(0f, 1f),
    ).normalized()

    fun friendlyDistanceUpperBound(): Float =
        SpatialRoomTrajectoryPolicy.maximumDistance(normalized())
            .coerceIn(FRIENDLY_DISTANCE_MIN_M, FRIENDLY_DISTANCE_MAX_M)

    fun roomAwareFriendlyDistancePosition(): Float {
        val upperBound = friendlyDistanceUpperBound()
        if (upperBound <= FRIENDLY_DISTANCE_MIN_M + 1e-4f) return 0f
        val distance = max(startDistanceM, endDistanceM)
            .coerceIn(FRIENDLY_DISTANCE_MIN_M, upperBound)
        return (
            ln((distance / FRIENDLY_DISTANCE_MIN_M).toDouble()) /
                ln((upperBound / FRIENDLY_DISTANCE_MIN_M).toDouble())
            ).toFloat().coerceIn(0f, 1f)
    }

    fun withRoomAwareFriendlyDistance(position: Float): SpatialAudioConfig {
        val upperBound = friendlyDistanceUpperBound()
        val distance = if (upperBound <= FRIENDLY_DISTANCE_MIN_M + 1e-4f) {
            FRIENDLY_DISTANCE_MIN_M
        } else {
            (
                FRIENDLY_DISTANCE_MIN_M *
                    kotlin.math.exp(
                        ln((upperBound / FRIENDLY_DISTANCE_MIN_M).toDouble()) *
                            position.coerceIn(0f, 1f),
                    ).toFloat()
                ).coerceIn(FRIENDLY_DISTANCE_MIN_M, upperBound)
        }
        return when (trajectory) {
            SpatialTrajectory.NEAR_FAR -> copy(
                startDistanceM = max(FRIENDLY_DISTANCE_MIN_M, distance * NEAR_FAR_RATIO),
                endDistanceM = distance,
            )
            SpatialTrajectory.FREE_DRIFT -> copy(
                startDistanceM = max(FRIENDLY_DISTANCE_MIN_M, distance * FREE_DRIFT_NEAR_RATIO),
                endDistanceM = distance,
            )
            else -> copy(startDistanceM = distance, endDistanceM = distance)
        }.normalized().fitToRoom()
    }

    fun friendlyDistancePosition(): Float {
        val distance = max(startDistanceM, endDistanceM)
            .coerceIn(FRIENDLY_DISTANCE_MIN_M, FRIENDLY_DISTANCE_MAX_M)
        return (
            ln((distance / FRIENDLY_DISTANCE_MIN_M).toDouble()) /
                ln((FRIENDLY_DISTANCE_MAX_M / FRIENDLY_DISTANCE_MIN_M).toDouble())
            ).toFloat().coerceIn(0f, 1f)
    }

    fun withFriendlyDistance(position: Float): SpatialAudioConfig {
        val distance = (
            FRIENDLY_DISTANCE_MIN_M *
                kotlin.math.exp(
                    ln((FRIENDLY_DISTANCE_MAX_M / FRIENDLY_DISTANCE_MIN_M).toDouble()) *
                        position.coerceIn(0f, 1f),
                ).toFloat()
            ).coerceIn(FRIENDLY_DISTANCE_MIN_M, FRIENDLY_DISTANCE_MAX_M)

        return when (trajectory) {
            SpatialTrajectory.NEAR_FAR -> copy(
                startDistanceM = max(FRIENDLY_DISTANCE_MIN_M, distance * NEAR_FAR_RATIO),
                endDistanceM = distance,
            )
            SpatialTrajectory.FREE_DRIFT -> copy(
                startDistanceM = max(FRIENDLY_DISTANCE_MIN_M, distance * FREE_DRIFT_NEAR_RATIO),
                endDistanceM = distance,
            )
            else -> copy(startDistanceM = distance, endDistanceM = distance)
        }.normalized()
    }

    fun friendlyReflectionPosition(): Float {
        val maxWet = roomPreset.acoustics.maxReflectionWet
        if (maxWet <= 1e-6f) return 0f
        val curved = (reverbWet / maxWet).coerceIn(0f, 1f)
        return curved.pow(1f / REFLECTION_CURVE_EXPONENT).coerceIn(0f, 1f)
    }

    fun withFriendlyReflection(position: Float): SpatialAudioConfig = copy(
        reverbWet = roomPreset.acoustics.maxReflectionWet * reflectionCurve(position),
    ).normalized()

    fun diagnosticFields(): Map<String, Any?> = normalized().let { value ->
        val room = value.roomPreset.acoustics
        mapOf(
            "trajectory" to value.trajectory.name,
            "interpolation" to value.interpolation.name,
            "motion_mode" to value.motionMode.name,
            "room_preset" to value.roomPreset.name,
            "room_native_id" to value.roomPreset.nativeId,
            "room_width_m" to room.dimensions?.widthM,
            "room_depth_m" to room.dimensions?.depthM,
            "room_height_m" to room.dimensions?.heightM,
            "room_volume_m3" to room.dimensions?.volumeM3,
            "room_scattering" to room.averageScattering,
            "room_first_reflection_ms" to room.firstReflectionMs,
            "room_outdoor" to room.outdoor,
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
            "reflection_position" to value.friendlyReflectionPosition(),
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
            "decode_channels" to 2,
            "stereo_render_mode" to "preserve_or_upmix",
            "automatic_loudness_preservation" to true,
            "room_model_version" to 2,
        )
    }

    companion object {
        const val FRIENDLY_SPEED_MIN_SECONDS = 3f
        const val FRIENDLY_SPEED_MAX_SECONDS = 30f
        const val FRIENDLY_DISTANCE_MIN_M = 0.8f
        const val FRIENDLY_DISTANCE_MAX_M = 20f
        private const val REFLECTION_CURVE_EXPONENT = 1.25f
        private const val NEAR_FAR_RATIO = 0.45f
        private const val FREE_DRIFT_NEAR_RATIO = 0.6f

        private fun reflectionCurve(position: Float): Float =
            position.coerceIn(0f, 1f).pow(REFLECTION_CURVE_EXPONENT)
    }
}

enum class SpatialTrajectory(val label: String) {
    HORIZONTAL_CIRCLE("Vòng quanh đầu"),
    VERTICAL_CIRCLE("Trên và dưới"),
    FIGURE_EIGHT("Hình số 8"),
    LINEAR("Trái sang phải"),
    STATIC("Đứng yên"),
    PENDULUM("Con lắc"),
    FRONT_BACK("Trước ra sau"),
    SPIRAL("Xoắn ốc quanh đầu"),
    NEAR_FAR("Gần và xa"),
    FREE_DRIFT("Trôi tự do"),
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

object SpatialTrajectoryMath {
    fun pose(config: SpatialAudioConfig, seconds: Float): SpatialPose {
        val value = config.normalized()
        val phase = when (value.motionMode) {
            SpatialMotionMode.LOOP -> positiveModulo(seconds / value.cycleSeconds, 1f)
            SpatialMotionMode.ONCE -> (seconds / value.cycleSeconds).coerceIn(0f, 1f)
        }
        val eased = smoothstep(phase)
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
            SpatialTrajectory.PENDULUM -> {
                val theta = 2.0 * PI * phase
                val swing = (0.5 - 0.5 * cos(theta)).toFloat()
                val lift = (0.5 - 0.5 * cos(2.0 * theta)).toFloat()
                fromAngles(
                    azimuthDeg = lerp(value.startAzimuthDeg, value.endAzimuthDeg, swing),
                    elevationDeg = lerp(value.startElevationDeg, value.endElevationDeg, lift),
                    distanceM = distance,
                )
            }
            SpatialTrajectory.FRONT_BACK -> {
                val theta = 2.0 * PI * phase
                val sweep = (0.5 - 0.5 * cos(theta)).toFloat()
                val arch = sin(theta).let { it * it }.toFloat()
                fromAngles(
                    azimuthDeg = lerp(value.startAzimuthDeg, value.endAzimuthDeg, sweep),
                    elevationDeg = lerp(value.startElevationDeg, value.endElevationDeg, arch),
                    distanceM = distance,
                )
            }
            SpatialTrajectory.SPIRAL -> {
                val theta = 2.0 * PI * phase
                val elevationWave = (0.5 + 0.5 * sin(theta)).toFloat()
                fromAngles(
                    azimuthDeg = lerp(value.startAzimuthDeg, value.endAzimuthDeg, phase),
                    elevationDeg = lerp(value.startElevationDeg, value.endElevationDeg, elevationWave),
                    distanceM = distance,
                )
            }
            SpatialTrajectory.NEAR_FAR -> {
                val theta = 2.0 * PI * phase
                val breathe = (0.5 - 0.5 * cos(theta)).toFloat()
                fromAngles(
                    azimuthDeg = lerp(value.startAzimuthDeg, value.endAzimuthDeg, phase),
                    elevationDeg = lerp(value.startElevationDeg, value.endElevationDeg, smoothstep(breathe)),
                    distanceM = lerp(value.startDistanceM, value.endDistanceM, breathe),
                )
            }
            SpatialTrajectory.FREE_DRIFT -> {
                val theta = 2.0 * PI * phase
                val azimuthCenter = 0.5f * (value.startAzimuthDeg + value.endAzimuthDeg)
                val azimuthRadius = 0.5f * (value.endAzimuthDeg - value.startAzimuthDeg)
                val elevationCenter = 0.5f * (value.startElevationDeg + value.endElevationDeg)
                val elevationRadius = 0.5f * (value.endElevationDeg - value.startElevationDeg)
                val azimuthNoise = (
                    0.68 * sin(theta) +
                        0.22 * sin(3.0 * theta + 0.7) +
                        0.10 * sin(5.0 * theta + 1.4)
                    ).toFloat()
                val elevationNoise = (
                    0.72 * sin(2.0 * theta + 1.1) +
                        0.28 * sin(4.0 * theta + 0.3)
                    ).toFloat()
                val distanceWave = (
                    0.5 +
                        0.32 * sin(theta + 0.4) +
                        0.18 * sin(3.0 * theta + 1.2)
                    ).toFloat().coerceIn(0f, 1f)
                fromAngles(
                    azimuthDeg = azimuthCenter + azimuthRadius * azimuthNoise,
                    elevationDeg = elevationCenter + elevationRadius * elevationNoise,
                    distanceM = lerp(value.startDistanceM, value.endDistanceM, distanceWave),
                )
            }
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

    private fun smoothstep(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)

    private fun positiveModulo(value: Float, divisor: Float): Float =
        ((value % divisor) + divisor) % divisor
}
