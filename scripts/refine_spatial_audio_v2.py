from pathlib import Path

FILES = {
    "app/src/main/java/com/aistudio/mediatool/core/spatial/SpatialAudioConfig.kt": r'''package com.aistudio.mediatool.core.spatial

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cấu hình lõi của renderer binaural. Giao diện thông thường chỉ ánh xạ năm
 * điều khiển thân thiện vào cấu hình này; các tham số kỹ thuật vẫn được giữ cố
 * định và ghi diagnostics để có thể tinh chỉnh bằng dữ liệu thực tế.
 */
data class SpatialAudioConfig(
    val trajectory: SpatialTrajectory = SpatialTrajectory.HORIZONTAL_CIRCLE,
    val interpolation: SpatialInterpolation = SpatialInterpolation.BILINEAR,
    val motionMode: SpatialMotionMode = SpatialMotionMode.LOOP,
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

    fun withFriendlyTrajectory(next: SpatialTrajectory): SpatialAudioConfig = when (next) {
        SpatialTrajectory.HORIZONTAL_CIRCLE -> copy(
            trajectory = next,
            motionMode = SpatialMotionMode.LOOP,
            startAzimuthDeg = -90f,
            endAzimuthDeg = 270f,
            startElevationDeg = 0f,
            endElevationDeg = 0f,
        )
        SpatialTrajectory.VERTICAL_CIRCLE -> copy(
            trajectory = next,
            motionMode = SpatialMotionMode.LOOP,
            startAzimuthDeg = 0f,
            endAzimuthDeg = 0f,
            startElevationDeg = 0f,
            endElevationDeg = 0f,
        )
        SpatialTrajectory.FIGURE_EIGHT -> copy(
            trajectory = next,
            motionMode = SpatialMotionMode.LOOP,
            startAzimuthDeg = -110f,
            endAzimuthDeg = 110f,
            startElevationDeg = -30f,
            endElevationDeg = 30f,
        )
        SpatialTrajectory.LINEAR -> copy(
            trajectory = next,
            motionMode = SpatialMotionMode.ONCE,
            startAzimuthDeg = -100f,
            endAzimuthDeg = 100f,
            startElevationDeg = 0f,
            endElevationDeg = 0f,
        )
        SpatialTrajectory.STATIC -> copy(trajectory = next, motionMode = SpatialMotionMode.ONCE)
    }.normalized()

    fun friendlySpeedPosition(): Float = ((18f - cycleSeconds) / 15f).coerceIn(0f, 1f)

    fun withFriendlySpeed(position: Float): SpatialAudioConfig = copy(
        cycleSeconds = 18f - 15f * position.coerceIn(0f, 1f),
    ).normalized()

    fun friendlyDistancePosition(): Float = ((startDistanceM - 0.8f) / 3.2f).coerceIn(0f, 1f)

    fun withFriendlyDistance(position: Float): SpatialAudioConfig {
        val distance = 0.8f + 3.2f * position.coerceIn(0f, 1f)
        return copy(startDistanceM = distance, endDistanceM = distance).normalized()
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
            "decode_channels" to 2,
            "stereo_render_mode" to "preserve_or_upmix",
            "automatic_loudness_preservation" to true,
        )
    }
}

enum class SpatialTrajectory(val label: String) {
    HORIZONTAL_CIRCLE("Vòng quanh đầu"),
    VERTICAL_CIRCLE("Trên và dưới"),
    FIGURE_EIGHT("Hình số 8"),
    LINEAR("Đi từ trái sang phải"),
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
''',
    "app/src/main/java/com/aistudio/mediatool/core/spatial/SpatialAudioPreferences.kt": r'''package com.aistudio.mediatool.core.spatial

import android.content.Context
import org.json.JSONObject

/** Chỉ lưu năm lựa chọn thân thiện; tham số kỹ thuật dùng bộ mặc định đã kiểm chứng. */
object SpatialAudioPreferences {
    private const val PREFS = "spatial_audio_preferences"
    private const val KEY_CONFIG = "config_v2_simple"
    private const val LEGACY_KEY = "config_v1"

    fun load(context: Context): SpatialAudioConfig {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = preferences.getString(KEY_CONFIG, null)
        if (raw == null) {
            preferences.edit().remove(LEGACY_KEY).apply()
            return SpatialAudioConfig()
        }
        return runCatching {
            val json = JSONObject(raw)
            val trajectory = enumValueOrDefault(
                json.optString("trajectory"),
                SpatialTrajectory.HORIZONTAL_CIRCLE,
            )
            SpatialAudioConfig()
                .withFriendlyTrajectory(trajectory)
                .withFriendlySpeed(json.optDouble("speed", SpatialAudioConfig().friendlySpeedPosition().toDouble()).toFloat())
                .withFriendlyDistance(json.optDouble("distance", SpatialAudioConfig().friendlyDistancePosition().toDouble()).toFloat())
                .copy(
                    spatialBlend = json.optDouble("intensity", 0.85).toFloat(),
                    reverbWet = json.optDouble("reverb", 0.12).toFloat(),
                )
                .normalized()
        }.getOrDefault(SpatialAudioConfig())
    }

    fun save(context: Context, config: SpatialAudioConfig) {
        val value = config.normalized()
        val json = JSONObject()
            .put("trajectory", value.trajectory.name)
            .put("speed", value.friendlySpeedPosition())
            .put("distance", value.friendlyDistancePosition())
            .put("intensity", value.spatialBlend)
            .put("reverb", value.reverbWet)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG, json.toString())
            .remove(LEGACY_KEY)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
''',
    "app/src/main/java/com/aistudio/mediatool/ui/components/SpatialAudioControls.kt": r'''package com.aistudio.mediatool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.core.spatial.SpatialAudioConfig
import com.aistudio.mediatool.core.spatial.SpatialTrajectory
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun SpatialAudioControls(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    config: SpatialAudioConfig,
    onConfigChange: (SpatialAudioConfig) -> Unit,
    onPickSofa: () -> Unit,
    onClearSofa: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val value = config.normalized()
    AccessibleCheckboxRow(
        checked = enabled,
        onCheckedChange = onEnabledChange,
        text = "Spatial Audio 3D",
    )
    if (!enabled) return

    Text(
        "Giữ stereo gốc, hoặc tự chuyển nguồn mono thành stereo trước khi dựng HRTF.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Tùy chỉnh Spatial Audio",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            EnumDropdown(
                label = "Kiểu chuyển động",
                valueLabel = value.trajectory.label,
                entries = listOf(
                    SpatialTrajectory.HORIZONTAL_CIRCLE,
                    SpatialTrajectory.FIGURE_EIGHT,
                    SpatialTrajectory.VERTICAL_CIRCLE,
                    SpatialTrajectory.LINEAR,
                ),
                entryLabel = { it.label },
                onSelect = { onConfigChange(value.withFriendlyTrajectory(it)) },
            )

            val speed = value.friendlySpeedPosition()
            AccessibleSliderColumn(
                label = "Tốc độ: ${levelLabel(speed, "Chậm", "Vừa", "Nhanh")}",
                value = speed,
                onValueChange = { onConfigChange(value.withFriendlySpeed(it)) },
                valueRange = 0f..1f,
            )

            val distance = value.friendlyDistancePosition()
            AccessibleSliderColumn(
                label = "Khoảng cách: ${levelLabel(distance, "Gần", "Vừa", "Xa")}",
                value = distance,
                onValueChange = { onConfigChange(value.withFriendlyDistance(it)) },
                valueRange = 0f..1f,
            )

            AccessibleSliderColumn(
                label = "Cường độ 3D: ${levelLabel(value.spatialBlend, "Nhẹ", "Cân bằng", "Rõ")}",
                value = value.spatialBlend,
                onValueChange = { onConfigChange(value.copy(spatialBlend = it).normalized()) },
                valueRange = 0f..1f,
            )

            AccessibleSliderColumn(
                label = "Độ vang: ${(value.reverbWet * 100f).roundToInt()}%",
                value = value.reverbWet,
                onValueChange = { onConfigChange(value.copy(reverbWet = it).normalized()) },
                valueRange = 0f..1f,
            )

            Text(
                "Âm lượng được cân tự động theo đầu vào và bảo vệ bằng một peak ceiling chung cho hai tai. Độ vang 0% là đường khô.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun levelLabel(value: Float, low: String, middle: String, high: String): String = when {
    value < 0.34f -> low
    value < 0.67f -> middle
    else -> high
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    valueLabel: String,
    entries: List<T>,
    entryLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = valueLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            label = { Text(label) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entryLabel(entry)) },
                    onClick = {
                        onSelect(entry)
                        expanded = false
                    },
                )
            }
        }
    }
}
''',
    "app/src/main/java/com/aistudio/mediatool/core/spatial/SteamAudioBridge.kt": r'''package com.aistudio.mediatool.core.spatial

import org.json.JSONObject
import java.io.File

object SteamAudioBridge {
    private val loadResult: Result<Unit> by lazy {
        runCatching {
            System.loadLibrary("phonon")
            System.loadLibrary("mediatool_spatial")
        }
    }

    fun render(input: File, output: File, config: SpatialAudioConfig): SpatialRenderMetrics {
        loadResult.getOrThrow()
        require(input.isFile && input.length() >= 8L) { "PCM stereo đầu vào spatial không hợp lệ" }
        output.parentFile?.mkdirs()
        output.delete()
        val value = config.normalized()
        val response = nativeRender(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            sofaPath = value.customSofaPath.orEmpty(),
            sampleRate = 48_000,
            frameSize = value.frameSize,
            trajectory = value.trajectory.ordinal,
            interpolation = value.interpolation.ordinal,
            motionMode = value.motionMode.ordinal,
            startAzimuthDeg = value.startAzimuthDeg,
            endAzimuthDeg = value.endAzimuthDeg,
            startElevationDeg = value.startElevationDeg,
            endElevationDeg = value.endElevationDeg,
            startDistanceM = value.startDistanceM,
            endDistanceM = value.endDistanceM,
            cycleSeconds = value.cycleSeconds,
            spatialBlend = value.spatialBlend,
            distanceMinM = value.distanceMinM,
            distanceRolloff = value.distanceRolloff,
            airAbsorption = value.airAbsorption,
            directivityWeight = value.directivityWeight,
            directivityPower = value.directivityPower,
            sourceYawDeg = value.sourceYawDeg,
            reverbWet = value.reverbWet,
            reverbRt60Low = value.reverbRt60Low,
            reverbRt60Mid = value.reverbRt60Mid,
            reverbRt60High = value.reverbRt60High,
            reverbEqLow = value.reverbEqLow,
            reverbEqMid = value.reverbEqMid,
            reverbEqHigh = value.reverbEqHigh,
            outputGainDb = value.outputGainDb,
            effectStartSeconds = value.effectStartSeconds,
            effectEndSeconds = value.effectEndSeconds,
        )
        val json = JSONObject(response)
        if (!json.optBoolean("ok", false)) {
            error(json.optString("error", "Steam Audio không thể render"))
        }
        require(output.isFile && output.length() > 0L) { "Steam Audio không tạo PCM đầu ra" }
        return SpatialRenderMetrics(
            frames = json.optLong("frames"),
            blocks = json.optLong("blocks"),
            tailFrames = json.optLong("tail_frames"),
            renderMs = json.optLong("render_ms"),
            inputChannels = json.optInt("input_channels", 2),
            outputChannels = json.optInt("output_channels", 2),
            stereoMode = json.optString("stereo_mode", "preserve_or_upmix"),
            inputPeak = json.float("input_peak"),
            inputPeakLeft = json.float("input_peak_left"),
            inputPeakRight = json.float("input_peak_right"),
            inputRmsDbfs = json.float("input_rms_dbfs", -160f),
            inputRmsLeftDbfs = json.float("input_rms_left_dbfs", -160f),
            inputRmsRightDbfs = json.float("input_rms_right_dbfs", -160f),
            inputCorrelation = json.float("input_correlation"),
            inputBalanceDb = json.float("input_balance_db"),
            inputDifferenceRmsDbfs = json.float("input_difference_rms_dbfs", -160f),
            inputDualMono = json.optBoolean("input_dual_mono", false),
            peakBeforeGain = json.float("peak_before_gain"),
            peakAfterGain = json.float("peak_after_gain"),
            peakAfterGainLeft = json.float("peak_after_gain_left"),
            peakAfterGainRight = json.float("peak_after_gain_right"),
            outputMainRmsBeforeGainDbfs = json.float("output_main_rms_before_gain_dbfs", -160f),
            outputMainRmsAfterGainDbfs = json.float("output_main_rms_after_gain_dbfs", -160f),
            outputTotalRmsDbfs = json.float("output_total_rms_dbfs", -160f),
            outputRmsLeftDbfs = json.float("output_rms_left_dbfs", -160f),
            outputRmsRightDbfs = json.float("output_rms_right_dbfs", -160f),
            outputCorrelation = json.float("output_correlation"),
            outputBalanceDb = json.float("output_balance_db"),
            automaticMakeupGainDb = json.float("automatic_makeup_gain_db"),
            manualOutputGainDb = json.float("manual_output_gain_db"),
            peakLimiterGainDb = json.float("peak_limiter_gain_db"),
            appliedGainDb = json.float("applied_gain_db"),
            estimatedLoudnessDeltaDb = json.float("estimated_loudness_delta_db"),
            peakCeilingDbfs = json.float("peak_ceiling_dbfs", -1f),
            nonFiniteSamples = json.optLong("nonfinite_samples"),
            clippedSamplesBeforeGain = json.optLong("clipped_samples_before_gain"),
            hrtfType = json.optString("hrtf_type", "unknown"),
            steamAudioVersion = json.optString("steam_audio_version", "unknown"),
        )
    }

    private fun JSONObject.float(name: String, fallback: Float = 0f): Float =
        optDouble(name, fallback.toDouble()).toFloat().takeIf(Float::isFinite) ?: fallback

    private external fun nativeRender(
        inputPath: String,
        outputPath: String,
        sofaPath: String,
        sampleRate: Int,
        frameSize: Int,
        trajectory: Int,
        interpolation: Int,
        motionMode: Int,
        startAzimuthDeg: Float,
        endAzimuthDeg: Float,
        startElevationDeg: Float,
        endElevationDeg: Float,
        startDistanceM: Float,
        endDistanceM: Float,
        cycleSeconds: Float,
        spatialBlend: Float,
        distanceMinM: Float,
        distanceRolloff: Float,
        airAbsorption: Float,
        directivityWeight: Float,
        directivityPower: Float,
        sourceYawDeg: Float,
        reverbWet: Float,
        reverbRt60Low: Float,
        reverbRt60Mid: Float,
        reverbRt60High: Float,
        reverbEqLow: Float,
        reverbEqMid: Float,
        reverbEqHigh: Float,
        outputGainDb: Float,
        effectStartSeconds: Float,
        effectEndSeconds: Float,
    ): String
}

data class SpatialRenderMetrics(
    val frames: Long,
    val blocks: Long,
    val tailFrames: Long,
    val renderMs: Long,
    val inputChannels: Int,
    val outputChannels: Int,
    val stereoMode: String,
    val inputPeak: Float,
    val inputPeakLeft: Float,
    val inputPeakRight: Float,
    val inputRmsDbfs: Float,
    val inputRmsLeftDbfs: Float,
    val inputRmsRightDbfs: Float,
    val inputCorrelation: Float,
    val inputBalanceDb: Float,
    val inputDifferenceRmsDbfs: Float,
    val inputDualMono: Boolean,
    val peakBeforeGain: Float,
    val peakAfterGain: Float,
    val peakAfterGainLeft: Float,
    val peakAfterGainRight: Float,
    val outputMainRmsBeforeGainDbfs: Float,
    val outputMainRmsAfterGainDbfs: Float,
    val outputTotalRmsDbfs: Float,
    val outputRmsLeftDbfs: Float,
    val outputRmsRightDbfs: Float,
    val outputCorrelation: Float,
    val outputBalanceDb: Float,
    val automaticMakeupGainDb: Float,
    val manualOutputGainDb: Float,
    val peakLimiterGainDb: Float,
    val appliedGainDb: Float,
    val estimatedLoudnessDeltaDb: Float,
    val peakCeilingDbfs: Float,
    val nonFiniteSamples: Long,
    val clippedSamplesBeforeGain: Long,
    val hrtfType: String,
    val steamAudioVersion: String,
) {
    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "frames" to frames,
        "blocks" to blocks,
        "tail_frames" to tailFrames,
        "render_ms" to renderMs,
        "input_channels" to inputChannels,
        "output_channels" to outputChannels,
        "stereo_mode" to stereoMode,
        "input_peak" to inputPeak,
        "input_peak_left" to inputPeakLeft,
        "input_peak_right" to inputPeakRight,
        "input_rms_dbfs" to inputRmsDbfs,
        "input_rms_left_dbfs" to inputRmsLeftDbfs,
        "input_rms_right_dbfs" to inputRmsRightDbfs,
        "input_correlation" to inputCorrelation,
        "input_balance_db" to inputBalanceDb,
        "input_difference_rms_dbfs" to inputDifferenceRmsDbfs,
        "input_dual_mono" to inputDualMono,
        "peak_before_gain" to peakBeforeGain,
        "peak_after_gain" to peakAfterGain,
        "peak_after_gain_left" to peakAfterGainLeft,
        "peak_after_gain_right" to peakAfterGainRight,
        "output_main_rms_before_gain_dbfs" to outputMainRmsBeforeGainDbfs,
        "output_main_rms_after_gain_dbfs" to outputMainRmsAfterGainDbfs,
        "output_total_rms_dbfs" to outputTotalRmsDbfs,
        "output_rms_left_dbfs" to outputRmsLeftDbfs,
        "output_rms_right_dbfs" to outputRmsRightDbfs,
        "output_correlation" to outputCorrelation,
        "output_balance_db" to outputBalanceDb,
        "automatic_makeup_gain_db" to automaticMakeupGainDb,
        "manual_output_gain_db" to manualOutputGainDb,
        "peak_limiter_gain_db" to peakLimiterGainDb,
        "applied_gain_db" to appliedGainDb,
        "estimated_loudness_delta_db" to estimatedLoudnessDeltaDb,
        "peak_ceiling_dbfs" to peakCeilingDbfs,
        "nonfinite_samples" to nonFiniteSamples,
        "clipped_samples_before_gain" to clippedSamplesBeforeGain,
        "hrtf_type" to hrtfType,
        "steam_audio_version" to steamAudioVersion,
    )
}
''',
    "app/src/test/java/com/aistudio/mediatool/core/spatial/SpatialAudioConfigTest.kt": r'''package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialAudioConfigTest {
    @Test
    fun defaultsAreTunedForNormalStereoMusic() {
        val value = SpatialAudioConfig()
        assertEquals(1.2f, value.startDistanceM, 0f)
        assertEquals(1.2f, value.endDistanceM, 0f)
        assertEquals(0.85f, value.spatialBlend, 0f)
        assertEquals(0.12f, value.reverbWet, 0f)
        assertEquals(0.65f, value.distanceRolloff, 0f)
        assertEquals(0.35f, value.airAbsorption, 0f)
    }

    @Test
    fun normalizesEveryUserControlledParameter() {
        val value = SpatialAudioConfig(
            startAzimuthDeg = Float.NaN,
            endAzimuthDeg = 5_000f,
            startElevationDeg = -500f,
            endElevationDeg = 500f,
            startDistanceM = 0f,
            endDistanceM = 1_000f,
            cycleSeconds = 0f,
            spatialBlend = 3f,
            distanceMinM = -1f,
            distanceRolloff = 99f,
            airAbsorption = Float.POSITIVE_INFINITY,
            directivityWeight = -1f,
            directivityPower = 20f,
            sourceYawDeg = 500f,
            reverbWet = -2f,
            reverbRt60Low = 0f,
            reverbRt60Mid = 20f,
            reverbRt60High = Float.NaN,
            outputGainDb = 30f,
            effectStartSeconds = -3f,
            effectEndSeconds = -2f,
            frameSize = 3_000,
        ).normalized()

        assertEquals(-90f, value.startAzimuthDeg, 0f)
        assertEquals(720f, value.endAzimuthDeg, 0f)
        assertEquals(-90f, value.startElevationDeg, 0f)
        assertEquals(90f, value.endElevationDeg, 0f)
        assertEquals(0.2f, value.startDistanceM, 0f)
        assertEquals(100f, value.endDistanceM, 0f)
        assertEquals(0.5f, value.cycleSeconds, 0f)
        assertEquals(1f, value.spatialBlend, 0f)
        assertEquals(0.1f, value.distanceMinM, 0f)
        assertEquals(4f, value.distanceRolloff, 0f)
        assertEquals(0.35f, value.airAbsorption, 0f)
        assertEquals(0f, value.directivityWeight, 0f)
        assertEquals(8f, value.directivityPower, 0f)
        assertEquals(180f, value.sourceYawDeg, 0f)
        assertEquals(0f, value.reverbWet, 0f)
        assertEquals(0.1f, value.reverbRt60Low, 0f)
        assertEquals(10f, value.reverbRt60Mid, 0f)
        assertEquals(0.45f, value.reverbRt60High, 0f)
        assertEquals(6f, value.outputGainDb, 0f)
        assertEquals(0f, value.effectStartSeconds, 0f)
        assertEquals(-1f, value.effectEndSeconds, 0f)
        assertEquals(2_048, value.frameSize)
    }

    @Test
    fun friendlyControlsStayInsideMusicalRanges() {
        val slowNear = SpatialAudioConfig().withFriendlySpeed(0f).withFriendlyDistance(0f)
        val fastFar = SpatialAudioConfig().withFriendlySpeed(1f).withFriendlyDistance(1f)
        assertEquals(18f, slowNear.cycleSeconds, 0.001f)
        assertEquals(0.8f, slowNear.startDistanceM, 0.001f)
        assertEquals(3f, fastFar.cycleSeconds, 0.001f)
        assertEquals(4f, fastFar.startDistanceM, 0.001f)
        assertEquals(fastFar.startDistanceM, fastFar.endDistanceM, 0f)
    }

    @Test
    fun friendlyTrajectoryAppliesSafePreset() {
        val figureEight = SpatialAudioConfig().withFriendlyTrajectory(SpatialTrajectory.FIGURE_EIGHT)
        assertEquals(-110f, figureEight.startAzimuthDeg, 0f)
        assertEquals(110f, figureEight.endAzimuthDeg, 0f)
        assertEquals(-30f, figureEight.startElevationDeg, 0f)
        assertEquals(30f, figureEight.endElevationDeg, 0f)

        val linear = SpatialAudioConfig().withFriendlyTrajectory(SpatialTrajectory.LINEAR)
        assertEquals(SpatialMotionMode.ONCE, linear.motionMode)
    }

    @Test
    fun horizontalCircleVisitsFourCardinalDirections() {
        val config = SpatialAudioConfig(
            trajectory = SpatialTrajectory.HORIZONTAL_CIRCLE,
            startAzimuthDeg = 0f,
            endAzimuthDeg = 360f,
            cycleSeconds = 8f,
        )
        assertPose(SpatialTrajectoryMath.pose(config, 0f), 0f, 0f, -1f)
        assertPose(SpatialTrajectoryMath.pose(config, 2f), 1f, 0f, 0f)
        assertPose(SpatialTrajectoryMath.pose(config, 4f), 0f, 0f, 1f)
        assertPose(SpatialTrajectoryMath.pose(config, 6f), -1f, 0f, 0f)
    }

    @Test
    fun verticalCirclePassesAboveAndBelowListener() {
        val config = SpatialAudioConfig(
            trajectory = SpatialTrajectory.VERTICAL_CIRCLE,
            startAzimuthDeg = 0f,
            cycleSeconds = 8f,
        )
        assertPose(SpatialTrajectoryMath.pose(config, 2f), 0f, 1f, 0f)
        assertPose(SpatialTrajectoryMath.pose(config, 6f), 0f, -1f, 0f)
    }

    @Test
    fun oneShotLinearTrajectoryStopsAtEndPose() {
        val config = SpatialAudioConfig(
            trajectory = SpatialTrajectory.LINEAR,
            motionMode = SpatialMotionMode.ONCE,
            startAzimuthDeg = -90f,
            endAzimuthDeg = 90f,
            startElevationDeg = -30f,
            endElevationDeg = 45f,
            startDistanceM = 12f,
            endDistanceM = 0.5f,
            cycleSeconds = 5f,
        )
        val atEnd = SpatialTrajectoryMath.pose(config, 5f)
        val longAfterEnd = SpatialTrajectoryMath.pose(config, 50f)
        assertEquals(atEnd.x, longAfterEnd.x, 1e-5f)
        assertEquals(atEnd.y, longAfterEnd.y, 1e-5f)
        assertEquals(atEnd.z, longAfterEnd.z, 1e-5f)
        assertEquals(0.5f, longAfterEnd.distanceM, 1e-5f)
    }

    @Test
    fun everyPoseIsUnitLengthAndDistanceInterpolates() {
        SpatialTrajectory.entries.forEach { trajectory ->
            val config = SpatialAudioConfig(
                trajectory = trajectory,
                startDistanceM = 1f,
                endDistanceM = 15f,
                cycleSeconds = 9f,
            )
            repeat(101) { index ->
                val pose = SpatialTrajectoryMath.pose(config, index * 0.09f)
                val length = kotlin.math.sqrt(pose.x * pose.x + pose.y * pose.y + pose.z * pose.z)
                assertEquals(1f, length, 1e-4f)
                assertTrue(pose.distanceM in 1f..15f)
            }
        }
    }

    private fun assertPose(actual: SpatialPose, x: Float, y: Float, z: Float) {
        assertEquals(x, actual.x, 1e-4f)
        assertEquals(y, actual.y, 1e-4f)
        assertEquals(z, actual.z, 1e-4f)
    }
}
''',
}

for path, content in FILES.items():
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")

# The two large processing files are written from companion source fragments.
Path("scripts/refine_spatial_audio_v2_stage2.py").write_text("# populated by the next commit\n", encoding="utf-8")
