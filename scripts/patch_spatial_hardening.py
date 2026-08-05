from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def load(path):
    return (ROOT / path).read_text(encoding='utf-8')


def save(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')


def once(path, old, new):
    text = load(path)
    if old not in text:
        if new in text:
            return
        raise RuntimeError(f'missing pattern in {path}: {old[:100]!r}')
    if text.count(old) != 1:
        raise RuntimeError(f'non-unique pattern in {path}: {old[:100]!r}')
    save(path, text.replace(old, new, 1))


def alln(path, old, new, count):
    text = load(path)
    found = text.count(old)
    if found == 0 and new in text:
        return
    if found != count:
        raise RuntimeError(f'expected {count}, found {found} in {path}: {old[:100]!r}')
    save(path, text.replace(old, new))


def regex_once(path, pattern, replacement):
    text = load(path)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S | re.M)
    if count != 1:
        if replacement.strip() in text:
            return
        raise RuntimeError(f'missing regex in {path}: {pattern[:100]!r}')
    save(path, updated)

S = 'app/src/main/java/com/aistudio/mediatool/core/spatial'

p = f'{S}/SpatialAudioConfig.kt'
once(p,
'''        }.normalized()
    }

    fun withRoomPreset(next: SpatialRoomPreset): SpatialAudioConfig {''',
'''        }.normalized().fitToRoom()
    }

    fun withRoomPreset(next: SpatialRoomPreset): SpatialAudioConfig {''')
once(p,
'''        ).normalized()
    }

    fun friendlySpeedPosition(): Float =''',
'''        ).normalized().fitToRoom()
    }

    fun fitToRoom(): SpatialAudioConfig = SpatialRoomTrajectoryPolicy.fit(normalized()).config

    fun friendlySpeedPosition(): Float =''')
once(p,
'''    fun friendlyDistancePosition(): Float {
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
''',
'''    fun friendlyDistanceUpperBound(): Float =
        SpatialRoomTrajectoryPolicy.maximumDistance(normalized())
            .coerceIn(FRIENDLY_DISTANCE_MIN_M, FRIENDLY_DISTANCE_MAX_M)

    fun friendlyDistancePosition(): Float {
        val upperBound = friendlyDistanceUpperBound()
        if (upperBound <= FRIENDLY_DISTANCE_MIN_M + 1e-4f) return 0f
        val distance = max(startDistanceM, endDistanceM)
            .coerceIn(FRIENDLY_DISTANCE_MIN_M, upperBound)
        return (
            ln((distance / FRIENDLY_DISTANCE_MIN_M).toDouble()) /
                ln((upperBound / FRIENDLY_DISTANCE_MIN_M).toDouble())
            ).toFloat().coerceIn(0f, 1f)
    }

    fun withFriendlyDistance(position: Float): SpatialAudioConfig {
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
''')
once(p,
'''            else -> copy(startDistanceM = distance, endDistanceM = distance)
        }.normalized()
    }

    fun friendlyReflectionPosition(): Float {''',
'''            else -> copy(startDistanceM = distance, endDistanceM = distance)
        }.normalized().fitToRoom()
    }

    fun friendlyReflectionPosition(): Float {''')

p = 'app/src/main/java/com/aistudio/mediatool/ui/components/SpatialAudioControls.kt'
once(p, 'import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.getValue',
        'import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.LaunchedEffect\nimport androidx.compose.runtime.getValue')
once(p, 'import com.aistudio.mediatool.core.spatial.SpatialRoomPreset\nimport com.aistudio.mediatool.core.spatial.SpatialTrajectory',
        'import com.aistudio.mediatool.core.spatial.SpatialRoomPreset\nimport com.aistudio.mediatool.core.spatial.SpatialRoomTrajectoryPolicy\nimport com.aistudio.mediatool.core.spatial.SpatialTrajectory')
once(p,
'''    val value = config.normalized()
    AccessibleCheckboxRow(''',
'''    val roomFit = SpatialRoomTrajectoryPolicy.fit(config.normalized())
    val value = roomFit.config
    LaunchedEffect(roomFit.adjusted, value) {
        if (roomFit.adjusted) onConfigChange(value)
    }
    AccessibleCheckboxRow(''')
once(p,
'''            val distance = value.friendlyDistancePosition()
            AccessibleSliderColumn(
                label = "Độ xa ước tính • ${formatDistance(max(value.startDistanceM, value.endDistanceM))}",''',
'''            val distance = value.friendlyDistancePosition()
            val distanceUpperBound = value.friendlyDistanceUpperBound()
            AccessibleSliderColumn(
                label = "Độ xa ước tính • ${formatDistance(max(value.startDistanceM, value.endDistanceM))} " +
                    "• tối đa ${formatDistance(distanceUpperBound)} trong không gian này",''')

p = f'{S}/SpatialStereoPostProcessor.kt'
once(p, 'private const val MAX_SIDE_PRESERVATION = 0.40f',
        'private const val MAX_SIDE_PRESERVATION = 0.45f\n    private const val MIN_SIDE_PRESERVATION = 0.22f')
once(p,
'''        val preservation = if (inputDualMono) 0f else {
            MAX_SIDE_PRESERVATION * spatialBlend.coerceIn(0f, 1f) * distanceScale
        }''',
'''        val preservation = sidePreservation(
            distanceM = max(startDistanceM, endDistanceM),
            spatialBlend = spatialBlend,
            inputDualMono = inputDualMono,
        )''')
once(p,
'''    internal fun distanceWidthScale(distanceM: Float): Float {
        val distance = distanceM.coerceAtLeast(0.8f)
        return (1f / (1f + 0.08f * (distance - 1f))).coerceIn(0.35f, 1f)
    }

    private data class ScanResult''',
'''    internal fun distanceWidthScale(distanceM: Float): Float {
        val distance = distanceM.coerceAtLeast(0.8f)
        return (1f / (1f + 0.08f * (distance - 1f))).coerceIn(0.35f, 1f)
    }

    internal fun sidePreservation(
        distanceM: Float,
        spatialBlend: Float,
        inputDualMono: Boolean,
    ): Float {
        if (inputDualMono) return 0f
        val width = (MAX_SIDE_PRESERVATION * distanceWidthScale(distanceM))
            .coerceAtLeast(MIN_SIDE_PRESERVATION)
        return width * spatialBlend.coerceIn(0f, 1f)
    }

    private data class ScanResult''')

p = 'app/src/main/cpp/room_aware_spatial_jni.cpp'
once(p,
'''constexpr float kPeakCeilingDbfs = -1.0f;
constexpr int kPayloadVersion = 1;''',
'''constexpr float kPeakCeilingDbfs = -1.0f;
constexpr float kReflectionHeadroom = 0.70794578f;
constexpr float kReflectionHeadroomDb = -3.0f;
constexpr int kPayloadVersion = 1;''')
alln(p, 'const float wetGain = reverbWet > 0.0f ? std::sqrt(reverbWet) : 0.0f;',
        'const float wetGain = reverbWet > 0.0f ? std::sqrt(reverbWet) * kReflectionHeadroom : 0.0f;', 2)
once(p,
'''         << ",\"reflection_duration_seconds\":" << room.duration
         << ",\"true_effect_mix\":true}"''',
'''         << ",\"reflection_duration_seconds\":" << room.duration
         << ",\"reflection_headroom_db\":" << kReflectionHeadroomDb
         << ",\"true_effect_mix\":true}"''')

p = f'{S}/SteamAudioBridge.kt'
once(p,
'''            reflectionDurationSeconds = json.float("reflection_duration_seconds", room.durationSeconds),
            trueEffectMix = json.optBoolean("true_effect_mix", false),''',
'''            reflectionDurationSeconds = json.float("reflection_duration_seconds", room.durationSeconds),
            reflectionHeadroomDb = json.float("reflection_headroom_db", -3f),
            trueEffectMix = json.optBoolean("true_effect_mix", false),''')
once(p, '    val reflectionDurationSeconds: Float,\n    val trueEffectMix: Boolean,',
        '    val reflectionDurationSeconds: Float,\n    val reflectionHeadroomDb: Float,\n    val trueEffectMix: Boolean,')
once(p, '        "reflection_duration_seconds" to reflectionDurationSeconds,\n        "true_effect_mix" to trueEffectMix,',
        '        "reflection_duration_seconds" to reflectionDurationSeconds,\n        "reflection_headroom_db" to reflectionHeadroomDb,\n        "true_effect_mix" to trueEffectMix,')

p = f'{S}/SpatialLoudnessLogCapture.kt'
once(p, '        session.logsAsString.orEmpty()',
'''        session.logsAsString.orEmpty().also { logs ->
            check(logs.contains("\\\"input_i\\\"")) {
                "FFmpeg loudness analysis returned no loudnorm JSON"
            }
        }''')

p = f'{S}/SpatialAudioEngine.kt'
once(p, '        val value = config.normalized()',
'''        val normalizedConfig = config.normalized()
        val roomFit = SpatialRoomTrajectoryPolicy.fit(normalizedConfig)
        val value = roomFit.config''')
once(p,
'''        val expectedDurationMs = if (preview) sourceDurationMs.coerceAtMost(10_000L) else sourceDurationMs
        val safeFilters = preFilters.filterNot { it.startsWith("alimiter=") }
        val sourceCopyStartedAt''',
'''        val expectedDurationMs = if (preview) sourceDurationMs.coerceAtMost(10_000L) else sourceDurationMs
        val tailPolicy = SpatialTailPolicy.resolve(isVideoMode = isVideoMode, preview = preview)
        val prefilterDecision = SpatialPrefilterPolicy.apply(preFilters)
        val safeFilters = prefilterDecision.allowed
        if (prefilterDecision.suppressed.isNotEmpty()) {
            DiagnosticLogger.warn(
                component = TAG,
                event = "spatial_prefilters_suppressed",
                sessionId = taskId,
                fields = prefilterDecision.diagnosticFields(),
            )
        }
        if (roomFit.adjusted) {
            DiagnosticLogger.info(
                component = TAG,
                event = "spatial_room_trajectory_fitted",
                sessionId = taskId,
                fields = roomFit.diagnosticFields(),
            )
        }
        val sourceCopyStartedAt''')
once(p,
'''            fields = value.diagnosticFields() + sourceInfo.diagnosticFields("source") + runtimeBefore + mapOf(''',
'''            fields = value.diagnosticFields() + roomFit.diagnosticFields() +
                prefilterDecision.diagnosticFields() + tailPolicy.diagnosticFields() +
                sourceInfo.diagnosticFields("source") + runtimeBefore + mapOf(''')
once(p, '                "pre_filter_count" to safeFilters.size,',
        '                "allowed_pre_filter_count" to safeFilters.size,')
once(p, '                command = "-hide_banner -f f32le',
        '                command = "-hide_banner -nostats -f f32le')
once(p, '                command = "-hide_banner -i \\"${output.absolutePath}\\"',
        '                command = "-hide_banner -nostats -i \\"${output.absolutePath}\\"')
once(p,
'''                fields = metrics.diagnosticFields() + nativeBefore + nativeAfter + mapOf(''',
'''                fields = metrics.diagnosticFields() + roomFit.diagnosticFields() +
                    nativeBefore + nativeAfter + mapOf(''')
once(p,
'''                fields = metrics.diagnosticFields() + inputLoudness.diagnosticFields("input") +''',
'''                fields = metrics.diagnosticFields() + roomFit.diagnosticFields() +
                    tailPolicy.diagnosticFields() + inputLoudness.diagnosticFields("input") +''')
regex_once(p,
    r'    private suspend fun analyzeLoudness\([\s\S]*?\n    private fun probeAudio',
'''    private suspend fun analyzeLoudness(
        command: String,
        phase: String,
        taskId: String,
    ): SpatialLoudnessReading? = try {
        val logs = SpatialLoudnessLogCapture.execute(command)
        SpatialLoudnessParser.parse(logs).also { reading ->
            if (reading == null) {
                DiagnosticLogger.warn(
                    component = TAG,
                    event = "spatial_loudness_parse_failed",
                    sessionId = taskId,
                    fields = mapOf(
                        "phase" to phase,
                        "log_chars" to logs.length,
                        "contains_input_i" to logs.contains("\\\"input_i\\\""),
                    ),
                )
            }
        }
    } catch (error: Exception) {
        DiagnosticLogger.warn(
            component = TAG,
            event = "spatial_loudness_analysis_failed",
            sessionId = taskId,
            message = error.message,
            fields = mapOf("phase" to phase, "failure_type" to error.javaClass.name),
            error = error,
        )
        null
    }

    private fun probeAudio''')
alln(p, 'LoudnessMetrics?', 'SpatialLoudnessReading?', 2)
regex_once(p, r'\n    private data class LoudnessMetrics\([\s\S]*?\n    companion object \{', '\n    companion object {')
once(p,
'''            append("-vn ")
            append(audioEncodingArgs(extension))''',
'''            if (preview) append("-t 10 ")
            append("-vn ")
            append(audioEncodingArgs(extension))''')
once(p,
'''        private const val DECODE_STARTUP_TIMEOUT_MS = 20_000L
        private val LOUDNORM_JSON = Regex("""\{\s*\"input_i\"[\s\S]*?\}""")''',
'''        private const val DECODE_STARTUP_TIMEOUT_MS = 20_000L''')

print('existing spatial files patched')
