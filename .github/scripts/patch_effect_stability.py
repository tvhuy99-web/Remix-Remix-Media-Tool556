from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCREEN = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/screens/OtherScreen.kt"
RULES = ROOT / "app/src/main/java/com/aistudio/mediatool/core/media/MediaEffectRules.kt"
TESTS = ROOT / "app/src/test/java/com/aistudio/mediatool/core/media/MediaEffectRulesTest.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


text = SCREEN.read_text(encoding="utf-8")

text = replace_once(
    text,
    "import com.aistudio.mediatool.core.media.MediaEffectPolicy\n",
    "import com.aistudio.mediatool.core.media.MediaEffectPolicy\n"
    "import com.aistudio.mediatool.core.media.MediaAudioEffect\n"
    "import com.aistudio.mediatool.core.media.MediaEffectRules\n",
    "effect rule imports",
)

old_helpers = '''                fun enableExpression(start: String, end: String): String {
                    if (!enableTimeMocks) return ""
                    val parsed = TimelineSegments.parse(start, end)
                    val segments = parsed.segments
                        ?: throw IllegalArgumentException(parsed.error ?: "Mốc thời gian hiệu ứng không hợp lệ")
                    if (segments.size == 1 && segments[0].startMs == 0L && segments[0].endMs == null) return ""
                    val conditions = segments.map { segment ->
                        val startSeconds = segment.startMs / 1000.0
                        val endSeconds = segment.endMs?.div(1000.0)
                        if (endSeconds != null) {
                            "between(t,$startSeconds,$endSeconds)"
                        } else {
                            "gte(t,$startSeconds)"
                        }
                    }
                    return if (conditions.isEmpty()) "" else ":enable='${conditions.joinToString("+")}'"
                }

                fun amplitudeFromDb(db: Float): Double =
                    10.0.pow(db.toDouble() / 20.0).coerceIn(0.000001, 1.0)

                fun atempoFilters(value: Float): List<String> {
                    var remaining = value.toDouble().coerceIn(0.25, 4.0)
                    val filters = mutableListOf<String>()
                    while (remaining > 2.0) {
                        filters += "atempo=2.0"
                        remaining /= 2.0
                    }
                    while (remaining < 0.5) {
                        filters += "atempo=0.5"
                        remaining /= 0.5
                    }
                    if (kotlin.math.abs(remaining - 1.0) > 0.0001) {
                        filters += "atempo=${"%.5f".format(java.util.Locale.US, remaining)}"
                    }
                    return filters
                }
'''
new_helpers = '''                fun enableExpression(effect: MediaAudioEffect, start: String, end: String): String {
                    if (!enableTimeMocks || !MediaEffectRules.supportsTimeline(effect)) return ""
                    val parsed = TimelineSegments.parse(start, end)
                    val segments = parsed.segments
                        ?: throw IllegalArgumentException(parsed.error ?: "Mốc thời gian hiệu ứng không hợp lệ")
                    if (segments.size == 1 && segments[0].startMs == 0L && segments[0].endMs == null) return ""
                    val conditions = segments.map { segment ->
                        val startSeconds = segment.startMs / 1000.0
                        val endSeconds = segment.endMs?.div(1000.0)
                        if (endSeconds != null) {
                            "between(t,$startSeconds,$endSeconds)"
                        } else {
                            "gte(t,$startSeconds)"
                        }
                    }
                    return if (conditions.isEmpty()) "" else ":enable='${conditions.joinToString("+")}'"
                }

                fun amplitudeFromDb(db: Float): Double =
                    10.0.pow(db.toDouble() / 20.0).coerceIn(0.000001, 1.0)
'''
text = replace_once(text, old_helpers, new_helpers, "timeline and tempo helpers")

old_normalization = '''                if (enableNorm) {
                    val truePeakDb = AudioMath.truePeakDbFromPercent(targetPeakPercent)
                    val formattedPeak = "%.3f".format(java.util.Locale.US, truePeakDb)
                    // loudnorm là chuẩn hóa loudness EBU R128 thật; chế độ động
                    // một lượt phù hợp khi còn ghép cùng chuỗi hiệu ứng khác.
                    audioFilters += "loudnorm=I=-16:LRA=11:TP=$formattedPeak"
                    // loudnorm động nội suy ở 192 kHz; trả về 48 kHz để encoder
                    // AAC/MP3 trên thiết bị Android luôn nhận sample rate phổ biến.
                    audioFilters += "aresample=48000"
                }
'''
text = replace_once(text, old_normalization, "", "remove early loudness normalization")

text = replace_once(
    text,
    '''                if (enableDenoise) {
                    audioFilters += "afftdn=nf=-${denoiseLevel.toInt()}${enableExpression(denoiseStartMs, denoiseEndMs)}"
                }
''',
    '''                if (enableDenoise) {
                    audioFilters += MediaEffectRules.denoiseFilter(
                        reductionDb = denoiseLevel,
                        timelineExpression = enableExpression(MediaAudioEffect.DENOISE, denoiseStartMs, denoiseEndMs),
                    )
                }
''',
    "denoise filter",
)

text = replace_once(
    text,
    '''                if (enableSilenceRemove) {
                    audioFilters += "silenceremove=start_periods=1:start_duration=0.1:start_threshold=-${silenceThreshold.toInt()}dB:stop_periods=-1:stop_duration=0.5:stop_threshold=-${silenceThreshold.toInt()}dB"
                }
''',
    '''                if (enableSilenceRemove && MediaEffectRules.supportsSilenceRemoval(isVideoMode, modeIndex)) {
                    audioFilters += "silenceremove=start_periods=1:start_duration=0.1:start_threshold=-${silenceThreshold.toInt()}dB:stop_periods=-1:stop_duration=0.5:stop_threshold=-${silenceThreshold.toInt()}dB"
                }
''',
    "silence removal policy",
)

text = replace_once(
    text,
    'audioFilters += "agate=threshold=$gateThreshold:ratio=$gateRatio:range=0.01:attack=${preset.second}:release=${preset.third}${enableExpression(ngStartMs, ngEndMs)}"',
    'audioFilters += "agate=threshold=$gateThreshold:ratio=$gateRatio:range=0.01:attack=${preset.second}:release=${preset.third}${enableExpression(MediaAudioEffect.NOISE_GATE, ngStartMs, ngEndMs)}"',
    "noise gate timeline",
)
text = replace_once(
    text,
    'audioFilters += "pan=stereo|c0=${gain.left}*c0|c1=${gain.right}*c1${enableExpression(panStartMs, panEndMs)}"',
    'audioFilters += "pan=stereo|c0=${gain.left}*c0|c1=${gain.right}*c1${enableExpression(MediaAudioEffect.PAN, panStartMs, panEndMs)}"',
    "pan timeline",
)
text = replace_once(
    text,
    'audioFilters += "apulsator=mode=sine:hz=${1f / eightDCycle}:width=1${enableExpression(eightDStartMs, eightDEndMs)}"',
    'audioFilters += "apulsator=mode=sine:hz=${1f / eightDCycle}:width=1${enableExpression(MediaAudioEffect.SPATIAL_8D, eightDStartMs, eightDEndMs)}"',
    "8d timeline",
)
text = replace_once(
    text,
    '''                if (enableReverb) {
                    val delays = "${reverbRoomSize * 100f}|${reverbRoomSize * 150f}"
                    val absorption = (1f - reverbDamping).coerceIn(0.05f, 1f)
                    val firstDecay = (reverbWet * absorption).coerceIn(0f, 0.9f)
                    val secondDecay = (firstDecay * 0.55f).coerceIn(0f, 0.9f)
                    audioFilters += "aecho=0.8:0.8:$delays:${firstDecay}|${secondDecay}"
                }
''',
    '''                if (enableReverb) {
                    MediaEffectRules.reverbFilter(
                        roomSize = reverbRoomSize,
                        damping = reverbDamping,
                        wet = reverbWet,
                    )?.let(audioFilters::add)
                }
''',
    "reverb bypass",
)
text = replace_once(
    text,
    'audioFilters += "acompressor=threshold=$threshold:ratio=$ratio:attack=$compAttackMs:release=$compReleaseMs:makeup=$makeup${enableExpression(compStartMs, compEndMs)}"',
    'audioFilters += "acompressor=threshold=$threshold:ratio=$ratio:attack=$compAttackMs:release=$compReleaseMs:makeup=$makeup${enableExpression(MediaAudioEffect.COMPRESSOR, compStartMs, compEndMs)}"',
    "compressor timeline",
)
text = replace_once(
    text,
    'audioFilters += "equalizer=f=$frequency:width_type=q:width=1:g=${eqBands[index]}${enableExpression(eqStartMs, eqEndMs)}"',
    'audioFilters += "equalizer=f=$frequency:width_type=q:width=1:g=${eqBands[index]}${enableExpression(MediaAudioEffect.EQUALIZER, eqStartMs, eqEndMs)}"',
    "equalizer timeline",
)
text = replace_once(
    text,
    '''                if (enableSpeedPitch) {
                    val sampleRate = (44_100f * pitchFactor).roundToInt().coerceAtLeast(8_000)
                    audioFilters += "asetrate=$sampleRate"
                    audioFilters += "aresample=44100"
                    audioFilters += atempoFilters(speedFactor / pitchFactor)
                }
''',
    '''                if (enableSpeedPitch) {
                    audioFilters += MediaEffectRules.speedPitchFilters(
                        speed = speedFactor,
                        pitch = pitchFactor,
                        isVideoMode = isVideoMode,
                    )
                }
''',
    "speed and pitch filter",
)

old_channel_tail = '''                if (channelModeIndex == 1) {
                    audioFilters += "pan=mono|c0=0.5*c0+0.5*c1"
                } else if (channelModeIndex == 2) {
                    audioFilters += "aformat=sample_fmts=fltp:channel_layouts=stereo"
                }
                }
'''
new_channel_tail = '''                if (channelModeIndex == 1) {
                    audioFilters += "pan=mono|c0=0.5*c0+0.5*c1"
                } else if (channelModeIndex == 2) {
                    audioFilters += "aformat=sample_fmts=fltp:channel_layouts=stereo"
                }
                MediaEffectRules.appendFinalLoudnessFilters(
                    filters = audioFilters,
                    enabled = enableNorm,
                    targetPeakPercent = targetPeakPercent,
                )
                }
'''
text = replace_once(text, old_channel_tail, new_channel_tail, "final loudness placement")

old_time_block = '''                @Composable
                fun TimeBlock(startMs: String, onStartChange: (String) -> Unit, endMs: String, onEndChange: (String) -> Unit, effectName: String) {
                    if (enableTimeMocks) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(value = startMs, onValueChange = onStartChange, modifier = Modifier.weight(1f), label = { Text("Từ $effectName (ms, vd: 0, 50000)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                            OutlinedTextField(value = endMs, onValueChange = onEndChange, modifier = Modifier.weight(1f), label = { Text("Đến $effectName (ms, vd: 10000, 60000)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
'''
new_time_block = '''                @Composable
                fun TimeBlock(
                    effect: MediaAudioEffect,
                    startMs: String,
                    onStartChange: (String) -> Unit,
                    endMs: String,
                    onEndChange: (String) -> Unit,
                    effectName: String,
                ) {
                    if (!enableTimeMocks) return
                    if (!MediaEffectRules.supportsTimeline(effect)) {
                        Text(
                            "$effectName không hỗ trợ giới hạn thời gian và sẽ áp dụng cho toàn bộ tệp.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        return
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(value = startMs, onValueChange = onStartChange, modifier = Modifier.weight(1f), label = { Text("Từ $effectName (ms, vd: 0, 50000)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                        OutlinedTextField(value = endMs, onValueChange = onEndChange, modifier = Modifier.weight(1f), label = { Text("Đến $effectName (ms, vd: 10000, 60000)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
'''
text = replace_once(text, old_time_block, new_time_block, "time block UI")

call_replacements = {
    'TimeBlock(eightDStartMs, { eightDStartMs = it }, eightDEndMs, { eightDEndMs = it }, "Nhạc 8D")': 'TimeBlock(MediaAudioEffect.SPATIAL_8D, eightDStartMs, { eightDStartMs = it }, eightDEndMs, { eightDEndMs = it }, "Nhạc 8D")',
    'TimeBlock(denoiseStartMs, { denoiseStartMs = it }, denoiseEndMs, { denoiseEndMs = it }, "Lọc nhiễu")': 'TimeBlock(MediaAudioEffect.DENOISE, denoiseStartMs, { denoiseStartMs = it }, denoiseEndMs, { denoiseEndMs = it }, "Lọc nhiễu")',
    'TimeBlock(ngStartMs, { ngStartMs = it }, ngEndMs, { ngEndMs = it }, "Noise Gate")': 'TimeBlock(MediaAudioEffect.NOISE_GATE, ngStartMs, { ngStartMs = it }, ngEndMs, { ngEndMs = it }, "Noise Gate")',
    'TimeBlock(panStartMs, { panStartMs = it }, panEndMs, { panEndMs = it }, "Pan trái phải")': 'TimeBlock(MediaAudioEffect.PAN, panStartMs, { panStartMs = it }, panEndMs, { panEndMs = it }, "Pan trái phải")',
    'TimeBlock(compStartMs, { compStartMs = it }, compEndMs, { compEndMs = it }, "Nén âm lượng Compressor")': 'TimeBlock(MediaAudioEffect.COMPRESSOR, compStartMs, { compStartMs = it }, compEndMs, { compEndMs = it }, "Nén âm lượng Compressor")',
    'TimeBlock(eqStartMs, { eqStartMs = it }, eqEndMs, { eqEndMs = it }, "Equalizer")': 'TimeBlock(MediaAudioEffect.EQUALIZER, eqStartMs, { eqStartMs = it }, eqEndMs, { eqEndMs = it }, "Equalizer")',
}
for old, new in call_replacements.items():
    text = replace_once(text, old, new, f"time block call {old[:20]}")

text = replace_once(
    text,
    '"Chuẩn hóa áp dụng cho toàn bộ tệp để đo loudness ổn định."',
    '"Chuẩn hóa được áp dụng gần cuối chuỗi, sau EQ và Compressor, để giữ loudness đầu ra ổn định."',
    "normalization explanation",
)
text = replace_once(
    text,
    'label = "Mức độ giảm nhiễu: ${denoiseLevel.roundToInt()} dB",',
    'label = "Mức giảm nhiễu: ${denoiseLevel.roundToInt()} dB",',
    "denoise label",
)
text = replace_once(
    text,
    'valueRange = 10f..80f',
    'valueRange = 1f..80f',
    "denoise range",
)
text = replace_once(
    text,
    'label = "Mức Reverb (Wet): ${(reverbWet * 100).roundToInt()}%",',
    'label = "Mức Reverb (Wet): ${(reverbWet * 100).roundToInt()}%${if (reverbWet <= 0f) " (Tắt)" else ""}",',
    "reverb wet label",
)

old_silence_card = '''                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableSilenceRemove, onCheckedChange = { enableSilenceRemove = it }, text = "Cắt khoảng lặng")
                        if (enableSilenceRemove) {
                            AccessibleSliderColumn(
                                label = "Ngưỡng phát hiện: -${silenceThreshold.roundToInt()} dB",
                                value = silenceThreshold,
                                onValueChange = { silenceThreshold = it },
                                valueRange = 20f..60f
                            )
                        }
                    }
                }
'''
new_silence_card = '''                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (MediaEffectRules.supportsSilenceRemoval(isVideoMode, modeIndex)) {
                            AccessibleCheckboxRow(checked = enableSilenceRemove, onCheckedChange = { enableSilenceRemove = it }, text = "Cắt khoảng lặng")
                            if (enableSilenceRemove) {
                                AccessibleSliderColumn(
                                    label = "Ngưỡng phát hiện: -${silenceThreshold.roundToInt()} dB",
                                    value = silenceThreshold,
                                    onValueChange = { silenceThreshold = it },
                                    valueRange = 20f..60f
                                )
                            }
                        } else {
                            Text("Cắt khoảng lặng không khả dụng khi giữ nguyên hình video vì sẽ làm lệch đồng bộ hình và tiếng.")
                        }
                    }
                }
'''
text = replace_once(text, old_silence_card, new_silence_card, "silence removal UI")

SCREEN.write_text(text, encoding="utf-8")

RULES.parent.mkdir(parents=True, exist_ok=True)
RULES.write_text('''package com.aistudio.mediatool.core.media

import java.util.Locale
import kotlin.math.roundToInt

enum class MediaAudioEffect {
    DENOISE,
    NOISE_GATE,
    PAN,
    SPATIAL_8D,
    COMPRESSOR,
    EQUALIZER,
}

object MediaEffectRules {
    private const val BASE_SAMPLE_RATE = 44_100

    fun supportsTimeline(effect: MediaAudioEffect): Boolean = when (effect) {
        MediaAudioEffect.DENOISE,
        MediaAudioEffect.NOISE_GATE,
        MediaAudioEffect.EQUALIZER,
        -> true

        MediaAudioEffect.PAN,
        MediaAudioEffect.SPATIAL_8D,
        MediaAudioEffect.COMPRESSOR,
        -> false
    }

    fun supportsSilenceRemoval(isVideoMode: Boolean, modeIndex: Int): Boolean =
        !(isVideoMode && modeIndex == 0)

    fun denoiseReductionDb(value: Float): Float = value.coerceIn(0.01f, 97f)

    fun denoiseFilter(reductionDb: Float, timelineExpression: String = ""): String =
        "afftdn=nr=${format(denoiseReductionDb(reductionDb).toDouble(), 2)}$timelineExpression"

    fun reverbFilter(roomSize: Float, damping: Float, wet: Float): String? {
        if (!wet.isFinite() || wet <= 0.0001f) return null
        val safeRoom = roomSize.coerceIn(0.1f, 1f)
        val safeDamping = damping.coerceIn(0f, 1f)
        val safeWet = wet.coerceIn(0.0001f, 0.8f)
        val firstDelay = safeRoom * 100f
        val secondDelay = safeRoom * 150f
        val absorption = (1f - safeDamping).coerceIn(0.05f, 1f)
        val firstDecay = (safeWet * absorption).coerceIn(0.001f, 0.9f)
        val secondDecay = (firstDecay * 0.55f).coerceIn(0.001f, 0.9f)
        return "aecho=0.8:0.8:${format(firstDelay.toDouble(), 2)}|${format(secondDelay.toDouble(), 2)}:" +
            "${format(firstDecay.toDouble(), 4)}|${format(secondDecay.toDouble(), 4)}"
    }

    fun speedPitchFilters(speed: Float, pitch: Float, isVideoMode: Boolean): List<String> {
        val safePitch = pitch.takeIf(Float::isFinite)?.coerceIn(0.5f, 2f) ?: 1f
        val requestedSpeed = speed.takeIf(Float::isFinite)?.coerceIn(0.5f, 2f) ?: 1f
        val safeSpeed = if (isVideoMode) 1f else requestedSpeed
        val shiftedSampleRate = (BASE_SAMPLE_RATE * safePitch).roundToInt().coerceAtLeast(8_000)
        return buildList {
            // Chuẩn hóa nguồn trước, tránh dùng 44,1 kHz như thể đó là sample rate gốc.
            add("aresample=$BASE_SAMPLE_RATE")
            add("asetrate=$shiftedSampleRate")
            add("aresample=$BASE_SAMPLE_RATE")
            addAll(atempoFilters(safeSpeed / safePitch))
        }
    }

    fun appendFinalLoudnessFilters(
        filters: MutableList<String>,
        enabled: Boolean,
        targetPeakPercent: Float,
    ) {
        if (!enabled) return
        val truePeakDb = AudioMath.truePeakDbFromPercent(targetPeakPercent)
        filters += "loudnorm=I=-16:LRA=11:TP=${format(truePeakDb, 3)}"
        // loudnorm động nội suy ở 192 kHz; trả về sample rate phổ biến cho encoder Android.
        filters += "aresample=48000"
    }

    internal fun atempoFilters(value: Float): List<String> {
        var remaining = value.takeIf(Float::isFinite)?.toDouble()?.coerceIn(0.25, 4.0) ?: 1.0
        val filters = mutableListOf<String>()
        while (remaining > 2.0) {
            filters += "atempo=2.0"
            remaining /= 2.0
        }
        while (remaining < 0.5) {
            filters += "atempo=0.5"
            remaining /= 0.5
        }
        if (kotlin.math.abs(remaining - 1.0) > 0.0001) {
            filters += "atempo=${format(remaining, 5)}"
        }
        return filters
    }

    private fun format(value: Double, decimals: Int): String =
        String.format(Locale.US, ".${decimals}f", value)
}
''', encoding="utf-8")

TESTS.parent.mkdir(parents=True, exist_ok=True)
TESTS.write_text('''package com.aistudio.mediatool.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaEffectRulesTest {
    @Test
    fun unsupportedFiltersNeverReceiveTimelineExpressions() {
        assertTrue(MediaEffectRules.supportsTimeline(MediaAudioEffect.DENOISE))
        assertTrue(MediaEffectRules.supportsTimeline(MediaAudioEffect.NOISE_GATE))
        assertTrue(MediaEffectRules.supportsTimeline(MediaAudioEffect.EQUALIZER))
        assertFalse(MediaEffectRules.supportsTimeline(MediaAudioEffect.PAN))
        assertFalse(MediaEffectRules.supportsTimeline(MediaAudioEffect.SPATIAL_8D))
        assertFalse(MediaEffectRules.supportsTimeline(MediaAudioEffect.COMPRESSOR))
    }

    @Test
    fun speedPitchNormalizesInputSampleRateBeforePitchShift() {
        val filters = MediaEffectRules.speedPitchFilters(speed = 1f, pitch = 1f, isVideoMode = false)
        assertEquals(listOf("aresample=44100", "asetrate=44100", "aresample=44100"), filters)
    }

    @Test
    fun videoModeIgnoresHiddenSpeedSettingButPreservesPitchCompensation() {
        val filters = MediaEffectRules.speedPitchFilters(speed = 2f, pitch = 2f, isVideoMode = true)
        assertEquals("aresample=44100", filters[0])
        assertEquals("asetrate=88200", filters[1])
        assertEquals("aresample=44100", filters[2])
        assertEquals("atempo=0.50000", filters[3])
    }

    @Test
    fun denoiseUsesNoiseReductionParameterAndClampsToFfmpegRange() {
        assertEquals("afftdn=nr=0.01", MediaEffectRules.denoiseFilter(-5f))
        assertEquals("afftdn=nr=97.00:enable='between(t,0,1)'", MediaEffectRules.denoiseFilter(120f, ":enable='between(t,0,1)'"))
    }

    @Test
    fun zeroWetReverbIsABypass() {
        assertNull(MediaEffectRules.reverbFilter(roomSize = 0.5f, damping = 0.5f, wet = 0f))
        assertTrue(MediaEffectRules.reverbFilter(roomSize = 0.5f, damping = 1f, wet = 0.3f)!!.contains("0.0010"))
    }

    @Test
    fun silenceRemovalIsDisabledOnlyForVideoKeepPictureMode() {
        assertFalse(MediaEffectRules.supportsSilenceRemoval(isVideoMode = true, modeIndex = 0))
        assertTrue(MediaEffectRules.supportsSilenceRemoval(isVideoMode = true, modeIndex = 1))
        assertTrue(MediaEffectRules.supportsSilenceRemoval(isVideoMode = false, modeIndex = 0))
    }

    @Test
    fun loudnessNormalizationIsAppendedAfterExistingEffects() {
        val filters = mutableListOf("equalizer=f=910:g=4", "acompressor=ratio=4")
        MediaEffectRules.appendFinalLoudnessFilters(filters, enabled = true, targetPeakPercent = 95f)
        assertEquals("equalizer=f=910:g=4", filters[0])
        assertEquals("acompressor=ratio=4", filters[1])
        assertTrue(filters[2].startsWith("loudnorm=I=-16:LRA=11:TP="))
        assertEquals("aresample=48000", filters[3])
    }
}
''', encoding="utf-8")

print("Effect stability patch applied")
