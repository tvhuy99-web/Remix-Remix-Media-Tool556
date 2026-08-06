from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str, flags: int = 0) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one regex match, found {count}")
    return updated


# 1. Give the friendly control a genuinely useful range without raising the noisy late tail alone.
room_path = "app/src/main/java/com/aistudio/mediatool/core/spatial/SpatialRoomPreset.kt"
room = read(room_path)
for old, new, name in [
    ("maxReflectionWet = 0.08f", "maxReflectionWet = 0.12f", "dry wet ceiling"),
    ("maxReflectionWet = 0.15f", "maxReflectionWet = 0.28f", "studio wet ceiling"),
    ("maxReflectionWet = 0.22f", "maxReflectionWet = 0.38f", "listening wet ceiling"),
    ("maxReflectionWet = 0.28f", "maxReflectionWet = 0.46f", "theater wet ceiling"),
    ("maxReflectionWet = 0.26f", "maxReflectionWet = 0.44f", "warehouse wet ceiling"),
    ("maxReflectionWet = 0.02f", "maxReflectionWet = 0.03f", "outdoor wet ceiling"),
]:
    room = replace_once(room, old, new, name)
write(room_path, room)

config_path = "app/src/main/java/com/aistudio/mediatool/core/spatial/SpatialAudioConfig.kt"
config = read(config_path)
config = replace_once(
    config,
    """            SpatialTrajectory.FRONT_BACK -> copy(
                trajectory = next,
                motionMode = SpatialMotionMode.LOOP,
                startAzimuthDeg = 0f,
                endAzimuthDeg = 180f,
                startElevationDeg = 0f,
                endElevationDeg = 45f,""",
    """            SpatialTrajectory.FRONT_BACK -> copy(
                trajectory = next,
                motionMode = SpatialMotionMode.LOOP,
                startAzimuthDeg = 0f,
                endAzimuthDeg = 180f,
                startElevationDeg = 0f,
                endElevationDeg = 0f,""",
    "front/back horizontal trajectory",
)
config = replace_once(
    config,
    "private const val REFLECTION_CURVE_EXPONENT = 1.6f",
    "private const val REFLECTION_CURVE_EXPONENT = 1.25f",
    "reflection control curve",
)
write(config_path, config)

# 2. Increase ray stability moderately. Clean early reflections provide presence, so late noise does not need huge gain.
spec_path = "app/src/main/java/com/aistudio/mediatool/core/spatial/RoomReflectionNativeSpec.kt"
spec = read(spec_path)
if "rays = 8_192" in spec:
    spec = spec.replace("rays = 8_192", "rays = 12_288", 1)
elif "rays = 8192" in spec:
    spec = spec.replace("rays = 8192", "rays = 12288", 1)
else:
    raise RuntimeError("balanced ray count marker not found")
write(spec_path, spec)

spec_test_path = "app/src/test/java/com/aistudio/mediatool/core/spatial/RoomReflectionNativeSpecTest.kt"
spec_test = read(spec_test_path)
spec_test = spec_test.replace("8_192", "12_288").replace("8192", "12288")
write(spec_test_path, spec_test)

# 3. Native renderer: deterministic early-reflection cluster plus a darker, lower late tail.
native_path = "app/src/main/cpp/room_aware_spatial_jni.cpp"
native = read(native_path)
for old, new, label in [
    ("constexpr float kReflectionHeadroom = 0.59566214f;", "constexpr float kReflectionHeadroom = 0.38f;", "late reflection gain"),
    ("constexpr float kReflectionHeadroomDb = -4.5f;", "constexpr float kReflectionHeadroomDb = -8.404328f;", "late reflection gain db"),
    ("constexpr float kRearDirectLowpassHz = 6800.0f;", "constexpr float kRearDirectLowpassHz = 4200.0f;", "rear lowpass"),
    ("constexpr float kReflectionLowpassHz = 9000.0f;", "constexpr float kReflectionLowpassHz = 7200.0f;", "late reflection lowpass"),
    ("constexpr float kReflectionGateFloorDbfs = -62.0f;", "constexpr float kReflectionGateFloorDbfs = -56.0f;", "late reflection gate floor"),
    ("constexpr float kReflectionGateOpenDbfs = -42.0f;", "constexpr float kReflectionGateOpenDbfs = -36.0f;", "late reflection gate open"),
]:
    native = replace_once(native, old, new, label)

native = replace_once(
    native,
    "constexpr float kReflectionGateOpenDbfs = -36.0f;\n",
    """constexpr float kReflectionGateOpenDbfs = -36.0f;
constexpr float kRoomPresenceReferenceWet = 0.46f;
constexpr float kEarlyReflectionMaxGain = 0.34f;
constexpr float kEarlyReflectionLowpassHz = 10500.0f;
constexpr float kRearDirectScaleMin = 0.68f;
constexpr float kRearWetScaleMax = 1.55f;
""",
    "room presence constants",
)

native = replace_once(
    native,
    """    float reflectionSendLowpassState = 0.0f;
    float reflectionWetLowpassState[2] = {0.0f, 0.0f};
""",
    """    float reflectionSendLowpassState = 0.0f;
    float reflectionWetLowpassState[2] = {0.0f, 0.0f};
    std::vector<float> earlyReflectionHistory;
    size_t earlyReflectionCursor = 0;
    float earlyReflectionLowpassState = 0.0f;
""",
    "early reflection state",
)

helper = r'''
float roomFirstReflectionMs(int roomId) {
    switch (roomId) {
        case 0: return 14.0f;
        case 1: return 18.0f;
        case 2: return 22.0f;
        case 3: return 35.0f;
        case 4: return 45.0f;
        case 5:
        default: return 0.0f;
    }
}

float delayedHistorySample(const std::vector<float>& history, size_t cursor, int delaySamples) {
    if (history.empty()) return 0.0f;
    const size_t delay = static_cast<size_t>(std::max(1, delaySamples)) % history.size();
    return history[(cursor + history.size() - delay) % history.size()];
}

void renderEarlyReflectionSample(Resources* resources, const RoomSpec& room, float input,
                                 int sampleRate, float* left, float* right) {
    *left = 0.0f;
    *right = 0.0f;
    if (!room.enabled || room.roomId == 5 || resources->earlyReflectionHistory.empty()) return;

    const float alpha = onePoleAlpha(kEarlyReflectionLowpassHz, sampleRate);
    const float filtered = lowpassSample(input, alpha, &resources->earlyReflectionLowpassState);
    auto& history = resources->earlyReflectionHistory;
    const size_t cursor = resources->earlyReflectionCursor;
    history[cursor] = filtered;

    const float firstMs = roomFirstReflectionMs(room.roomId);
    const float scattering = std::max(0.0f, std::min(1.0f,
        (room.walls.scattering + room.floor.scattering + room.ceiling.scattering) / 3.0f));
    const int tap1 = std::max(1, static_cast<int>(std::lround(firstMs * 0.001f * sampleRate)));
    const int tap2 = std::max(tap1 + 1, static_cast<int>(std::lround(
        (firstMs + 3.0f + 6.0f * scattering) * 0.001f * sampleRate)));
    const int tap3 = std::max(tap2 + 1, static_cast<int>(std::lround(
        (firstMs + 9.0f + 11.0f * scattering) * 0.001f * sampleRate)));
    const float a = delayedHistorySample(history, cursor, tap1);
    const float b = delayedHistorySample(history, cursor, tap2);
    const float c = delayedHistorySample(history, cursor, tap3);

    // Fixed, energy-bounded asymmetry supplies clean room width without Monte-Carlo hiss.
    *left = 0.62f * a + 0.28f * b - 0.14f * c;
    *right = 0.54f * a - 0.16f * b + 0.34f * c;
    resources->earlyReflectionCursor = (cursor + 1u) % history.size();
}

'''
native = replace_once(native, "IPLVector3 normalize(float x, float y, float z) {", helper + "IPLVector3 normalize(float x, float y, float z) {", "early reflection helper insertion")

native = replace_once(
    native,
    """    const float ambienceBedGain = lerp(
        kObjectAmbienceBedMax, kObjectAmbienceBedMin, effectiveSpatialBlend);
    const auto started = std::chrono::steady_clock::now();
""",
    """    const float ambienceBedGain = lerp(
        kObjectAmbienceBedMax, kObjectAmbienceBedMin, effectiveSpatialBlend);
    const float roomPresence = reverbWet > 0.0f
        ? std::max(0.0f, std::min(1.0f, reverbWet / kRoomPresenceReferenceWet)) : 0.0f;
    resources.earlyReflectionHistory.assign(
        static_cast<size_t>(std::max(frameSize * 2, sampleRate / 4)), 0.0f);
    const auto started = std::chrono::steady_clock::now();
""",
    "room presence initialization",
)

# Stronger and earlier rear classification, still driven by the HRTF direction.
native = native.replace(
    "const float rearAmount = std::max(0.0f, std::min(1.0f, pose.direction.z));",
    "const float rearAmount = std::pow(std::max(0.0f, std::min(1.0f, pose.direction.z)), 0.65f);",
)
native = native.replace(
    "const float tailRear = std::max(0.0f, std::min(1.0f, tailPose.direction.z));",
    "const float tailRear = std::pow(std::max(0.0f, std::min(1.0f, tailPose.direction.z)), 0.65f);",
)
if native.count("const float rearDirectScale = 1.0f - 0.18f * rearAmount;") != 1:
    raise RuntimeError("rear direct scale marker not found")
native = native.replace(
    "const float rearDirectScale = 1.0f - 0.18f * rearAmount;",
    "const float rearDirectScale = 1.0f - (1.0f - kRearDirectScaleMin) * rearAmount;",
    1,
)
native = replace_once(
    native,
    "const float rearWetScale = 1.0f + 0.25f * rearAmount;",
    "const float rearWetScale = 1.0f + (kRearWetScaleMax - 1.0f) * rearAmount;",
    "rear wet scale",
)
native = replace_once(
    native,
    "const float tailDirectScale = 1.0f - 0.18f * tailRear;",
    "const float tailDirectScale = 1.0f - (1.0f - kRearDirectScaleMin) * tailRear;",
    "tail rear direct scale",
)
native = replace_once(
    native,
    "const float tailWetScale = 1.0f + 0.25f * tailRear;",
    "const float tailWetScale = 1.0f + (kRearWetScaleMax - 1.0f) * tailRear;",
    "tail rear wet scale",
)

mix_old = """        const float dryGain = reverbWet > 0.0f ? std::sqrt(1.0f - reverbWet) : 1.0f;
        const float wetGain = reverbWet > 0.0f ? std::sqrt(reverbWet) * kReflectionHeadroom : 0.0f;
        for (int i = 0; i < framesRead; ++i) {
"""
mix_new = """        const float dryGain = reverbWet > 0.0f
            ? std::sqrt(std::max(0.0f, 1.0f - 0.35f * roomPresence)) : 1.0f;
        const float wetGain = reverbWet > 0.0f
            ? std::sqrt(roomPresence) * kReflectionHeadroom : 0.0f;
        const float earlyWetGain = reverbWet > 0.0f
            ? std::sqrt(roomPresence) * kEarlyReflectionMaxGain : 0.0f;
        for (int i = 0; i < framesRead; ++i) {
"""
native = replace_once(native, mix_old, mix_new, "main room mix gains")

native = replace_once(
    native,
    """            const float sourceSide = 0.5f * (originalLeft - originalRight);
            const float directLeft = rearDirectScale * leftCue * lowpassSample(
""",
    """            const float sourceSide = 0.5f * (originalLeft - originalRight);
            float earlyLeft = 0.0f;
            float earlyRight = 0.0f;
            renderEarlyReflectionSample(&resources, room,
                window * resources.objectMono.data[0][i], sampleRate, &earlyLeft, &earlyRight);
            const float directLeft = rearDirectScale * leftCue * lowpassSample(
""",
    "early reflection render call",
)

native = replace_once(
    native,
    """            const float processedLeft = dryGain * directLeft + wetGain * wetLeft +
                ambienceBedGain * sourceSide;
            const float processedRight = dryGain * directRight + wetGain * wetRight -
                ambienceBedGain * sourceSide;
""",
    """            const float processedLeft = dryGain * directLeft + wetGain * wetLeft +
                earlyWetGain * rearWetScale * earlyLeft + ambienceBedGain * sourceSide;
            const float processedRight = dryGain * directRight + wetGain * wetRight +
                earlyWetGain * rearWetScale * earlyRight - ambienceBedGain * sourceSide;
""",
    "early reflection main mix",
)

# Tail uses only the denoised late engine; deterministic early taps have already decayed within milliseconds.
tail_old = """        const float dryGain = reverbWet > 0.0f ? std::sqrt(1.0f - reverbWet) : 1.0f;
        const float wetGain = reverbWet > 0.0f ? std::sqrt(reverbWet) * kReflectionHeadroom : 0.0f;
"""
tail_new = """        const float dryGain = reverbWet > 0.0f
            ? std::sqrt(std::max(0.0f, 1.0f - 0.35f * roomPresence)) : 1.0f;
        const float wetGain = reverbWet > 0.0f
            ? std::sqrt(roomPresence) * kReflectionHeadroom : 0.0f;
"""
native = replace_once(native, tail_old, tail_new, "tail room mix gains")

native = replace_once(
    native,
    """         << ",\\\"reflection_gate_floor_dbfs\\\":" << kReflectionGateFloorDbfs
         << ",\\\"true_effect_mix\\\":true}"
""",
    """         << ",\\\"reflection_gate_floor_dbfs\\\":" << kReflectionGateFloorDbfs
         << ",\\\"early_reflection_max_gain\\\":" << kEarlyReflectionMaxGain
         << ",\\\"early_reflection_first_ms\\\":" << roomFirstReflectionMs(room.roomId)
         << ",\\\"room_presence_control\\\":" << roomPresence
         << ",\\\"rear_direct_scale_min\\\":" << kRearDirectScaleMin
         << ",\\\"rear_wet_scale_max\\\":" << kRearWetScaleMax
         << ",\\\"true_effect_mix\\\":true}"
""",
    "native diagnostics fields",
)
write(native_path, native)

# 4. Surface the new tuning values in exported diagnostics.
bridge_path = "app/src/main/java/com/aistudio/mediatool/core/spatial/SteamAudioBridge.kt"
bridge = read(bridge_path)
for old, new, label in [
    ('reflectionHeadroomDb = json.float("reflection_headroom_db", -4.5f),', 'reflectionHeadroomDb = json.float("reflection_headroom_db", -8.404328f),', "bridge late headroom fallback"),
    ('directRearLowpassHz = json.float("direct_rear_lowpass_hz", 6_800f),', 'directRearLowpassHz = json.float("direct_rear_lowpass_hz", 4_200f),', "bridge rear lowpass fallback"),
    ('reflectionLowpassHz = json.float("reflection_lowpass_hz", 9_000f),', 'reflectionLowpassHz = json.float("reflection_lowpass_hz", 7_200f),', "bridge reflection lowpass fallback"),
    ('reflectionGateFloorDbfs = json.float("reflection_gate_floor_dbfs", -62f),', 'reflectionGateFloorDbfs = json.float("reflection_gate_floor_dbfs", -56f),', "bridge gate fallback"),
]:
    bridge = replace_once(bridge, old, new, label)
bridge = replace_once(
    bridge,
    """            reflectionGateFloorDbfs = json.float("reflection_gate_floor_dbfs", -56f),
            trueEffectMix = json.optBoolean("true_effect_mix", false),
""",
    """            reflectionGateFloorDbfs = json.float("reflection_gate_floor_dbfs", -56f),
            earlyReflectionMaxGain = json.float("early_reflection_max_gain", 0.34f),
            earlyReflectionFirstMs = json.float("early_reflection_first_ms"),
            roomPresenceControl = json.float("room_presence_control"),
            rearDirectScaleMin = json.float("rear_direct_scale_min", 0.68f),
            rearWetScaleMax = json.float("rear_wet_scale_max", 1.55f),
            trueEffectMix = json.optBoolean("true_effect_mix", false),
""",
    "bridge new metrics parse",
)
bridge = replace_once(
    bridge,
    """    val reflectionGateFloorDbfs: Float,
    val trueEffectMix: Boolean,
""",
    """    val reflectionGateFloorDbfs: Float,
    val earlyReflectionMaxGain: Float,
    val earlyReflectionFirstMs: Float,
    val roomPresenceControl: Float,
    val rearDirectScaleMin: Float,
    val rearWetScaleMax: Float,
    val trueEffectMix: Boolean,
""",
    "metrics new fields",
)
bridge = replace_once(
    bridge,
    """        "reflection_gate_floor_dbfs" to reflectionGateFloorDbfs,
        "true_effect_mix" to trueEffectMix,
""",
    """        "reflection_gate_floor_dbfs" to reflectionGateFloorDbfs,
        "early_reflection_max_gain" to earlyReflectionMaxGain,
        "early_reflection_first_ms" to earlyReflectionFirstMs,
        "room_presence_control" to roomPresenceControl,
        "rear_direct_scale_min" to rearDirectScaleMin,
        "rear_wet_scale_max" to rearWetScaleMax,
        "true_effect_mix" to trueEffectMix,
""",
    "metrics diagnostic map",
)
write(bridge_path, bridge)

# 5. Regression tests for the user-facing behavior.
test_path = ROOT / "app/src/test/java/com/aistudio/mediatool/core/spatial/SpatialRoomPresenceTuningTest.kt"
test_path.write_text('''package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialRoomPresenceTuningTest {
    @Test
    fun `full reflection control reaches a clearly audible room ceiling`() {
        val listening = SpatialAudioConfig(roomPreset = SpatialRoomPreset.LISTENING_ROOM)
            .withFriendlyReflection(1f)
        val theater = SpatialAudioConfig(roomPreset = SpatialRoomPreset.THEATER)
            .withFriendlyReflection(1f)

        assertEquals(SpatialRoomPreset.LISTENING_ROOM.acoustics.maxReflectionWet, listening.reverbWet, 1e-6f)
        assertEquals(SpatialRoomPreset.THEATER.acoustics.maxReflectionWet, theater.reverbWet, 1e-6f)
        assertTrue(listening.reverbWet >= 0.38f)
        assertTrue(theater.reverbWet >= 0.46f)
    }

    @Test
    fun `middle reflection positions are no longer excessively compressed`() {
        val value = SpatialAudioConfig(roomPreset = SpatialRoomPreset.LISTENING_ROOM)
            .withFriendlyReflection(0.5f)
        assertTrue(value.reverbWet > 0.15f)
    }

    @Test
    fun `front back friendly path stays on the horizontal plane`() {
        val value = SpatialAudioConfig().withFriendlyTrajectory(SpatialTrajectory.FRONT_BACK)
        assertEquals(0f, value.startElevationDeg, 1e-6f)
        assertEquals(0f, value.endElevationDeg, 1e-6f)
        assertEquals(0f, value.startAzimuthDeg, 1e-6f)
        assertEquals(180f, value.endAzimuthDeg, 1e-6f)
    }
}
''', encoding="utf-8")

print("Room presence and front/back v3 patch applied")
