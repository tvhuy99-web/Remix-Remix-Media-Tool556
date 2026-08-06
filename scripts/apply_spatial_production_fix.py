#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, found {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_values(path: Path, replacements: dict[str, str]) -> None:
    for old, new in replacements.items():
        replace_once(path, old, new)


def patch_build_metadata() -> None:
    path = ROOT / "app/build.gradle.kts"
    replace_once(
        path,
        'val hasCiKeystore = ciKeystoreFile.isFile\n',
        'val hasCiKeystore = ciKeystoreFile.isFile\n'
        'val buildGitSha = (System.getenv("GITHUB_SHA") ?: "local").take(12)\n'
        'val buildGitBranch = System.getenv("GITHUB_HEAD_REF")\n'
        '    ?.takeIf(String::isNotBlank)\n'
        '    ?: System.getenv("GITHUB_REF_NAME")?.takeIf(String::isNotBlank)\n'
        '    ?: "local"\n',
    )
    replace_once(
        path,
        '        versionName = "1.3.3"\n',
        '        versionName = "1.3.3"\n'
        '        buildConfigField("String", "GIT_COMMIT_SHA", "\\\"$buildGitSha\\\"")\n'
        '        buildConfigField("String", "GIT_BRANCH", "\\\"$buildGitBranch\\\"")\n',
    )


def patch_config() -> None:
    path = ROOT / "app/src/main/java/com/aistudio/mediatool/core/spatial/SpatialAudioConfig.kt"
    replace_once(
        path,
        'package com.aistudio.mediatool.core.spatial\n\nimport kotlin.math.PI\n',
        'package com.aistudio.mediatool.core.spatial\n\n'
        'import com.aistudio.mediatool.BuildConfig\n'
        'import kotlin.math.PI\n',
    )
    replace_once(
        path,
        '''            SpatialTrajectory.FRONT_BACK -> copy(
                trajectory = next,
                motionMode = SpatialMotionMode.LOOP,
                startAzimuthDeg = 0f,
                endAzimuthDeg = 180f,
                startElevationDeg = 0f,
                endElevationDeg = 45f,
                startDistanceM = fixedDistance,
                endDistanceM = fixedDistance,
            )''',
        '''            SpatialTrajectory.FRONT_BACK -> copy(
                trajectory = next,
                motionMode = SpatialMotionMode.LOOP,
                startAzimuthDeg = 0f,
                endAzimuthDeg = 180f,
                startElevationDeg = 0f,
                endElevationDeg = 0f,
                startDistanceM = fixedDistance,
                endDistanceM = fixedDistance,
            )''',
    )
    replace_once(
        path,
        '''            SpatialTrajectory.FRONT_BACK -> {
                val theta = 2.0 * PI * phase
                val sweep = (0.5 - 0.5 * cos(theta)).toFloat()
                val arch = sin(theta).let { it * it }.toFloat()
                fromAngles(
                    azimuthDeg = lerp(value.startAzimuthDeg, value.endAzimuthDeg, sweep),
                    elevationDeg = lerp(value.startElevationDeg, value.endElevationDeg, arch),
                    distanceM = distance,
                )
            }''',
        '''            SpatialTrajectory.FRONT_BACK -> {
                val theta = 2.0 * PI * phase
                val sweep = (0.5 - 0.5 * cos(theta)).toFloat()
                fromAngles(
                    azimuthDeg = lerp(value.startAzimuthDeg, value.endAzimuthDeg, sweep),
                    elevationDeg = lerp(value.startElevationDeg, value.endElevationDeg, sweep),
                    distanceM = distance,
                )
            }''',
    )
    replace_values(
        path,
        {
            '            "room_model_version" to 2,':
                '            "room_model_version" to 3,\n'
                '            "spatial_renderer_version" to 4,\n'
                '            "build_git_sha" to BuildConfig.GIT_COMMIT_SHA,\n'
                '            "build_git_branch" to BuildConfig.GIT_BRANCH,',
            '        private const val REFLECTION_CURVE_EXPONENT = 1.25f':
                '        private const val REFLECTION_CURVE_EXPONENT = 1.15f',
        },
    )


def patch_presets() -> None:
    path = ROOT / "app/src/main/java/com/aistudio/mediatool/core/spatial/SpatialRoomPreset.kt"
    replace_values(
        path,
        {
            '        maxReflectionWet = 0.24f,': '        maxReflectionWet = 0.27f,',
            '        maxReflectionWet = 0.34f,': '        maxReflectionWet = 0.38f,',
            '        maxReflectionWet = 0.43f,': '        maxReflectionWet = 0.48f,',
            '        maxReflectionWet = 0.42f,': '        maxReflectionWet = 0.50f,',
            '        val reverbEq = reflectedEnergy.map { (it / energyPeak).coerceIn(0.05f, 1f) }':
                '        val normalizedEq = reflectedEnergy.map { (it / energyPeak).coerceIn(0.05f, 1f) }\n'
                '        val reverbEq = SpatialAcousticBands(\n'
                '            low = normalizedEq.low,\n'
                '            mid = normalizedEq.mid,\n'
                '            high = (normalizedEq.high * 0.78f).coerceAtLeast(0.05f),\n'
                '        )',
        },
    )


def patch_controls() -> None:
    path = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/components/SpatialAudioControls.kt"
    replace_once(
        path,
        '''            val reflection = value.friendlyReflectionPosition()
            AccessibleSliderColumn(
                label = "Phản xạ phòng • ${(reflection * 100f).roundToInt()}%",
                value = reflection,
                onValueChange = { onConfigChange(value.withFriendlyReflection(it)) },
                valueRange = 0f..1f,
            )''',
        '''            val reflection = value.friendlyReflectionPosition()
            val reflectionWetPercent = (value.reverbWet * 100f).roundToInt()
            AccessibleSliderColumn(
                label = "Phản xạ phòng • ${(reflection * 100f).roundToInt()}% " +
                    "• mức trộn $reflectionWetPercent%",
                value = reflection,
                onValueChange = { onConfigChange(value.withFriendlyReflection(it)) },
                valueRange = 0f..1f,
            )''',
    )


def patch_native() -> None:
    path = ROOT / "app/src/main/cpp/room_aware_spatial_jni.cpp"
    replace_values(
        path,
        {
            'constexpr float kReflectionHeadroom = 0.59566214f;':
                'constexpr float kReflectionHeadroom = 0.70794578f;',
            'constexpr float kReflectionHeadroomDb = -4.5f;':
                'constexpr float kReflectionHeadroomDb = -3.0f;',
            'constexpr float kRearDirectLowpassHz = 9200.0f;':
                'constexpr float kRearDirectLowpassHz = 6800.0f;',
            'constexpr float kRearNotchDepth = 0.62f;':
                'constexpr float kRearNotchDepth = 0.68f;',
            'constexpr float kRearDirectAttenuation = 0.24f;':
                'constexpr float kRearDirectAttenuation = 0.32f;',
            'constexpr float kRearWetBoost = 0.55f;':
                'constexpr float kRearWetBoost = 0.65f;',
            'constexpr float kReflectionLowpassHz = 9000.0f;':
                'constexpr float kReflectionLowpassHz = 7200.0f;',
            'constexpr float kEarlyReflectionLowpassHz = 7200.0f;':
                'constexpr float kEarlyReflectionLowpassHz = 6800.0f;',
            'constexpr float kReflectionGateFloorDbfs = -62.0f;':
                'constexpr float kReflectionGateFloorDbfs = -56.0f;',
            'constexpr float kReflectionGateOpenDbfs = -42.0f;':
                'constexpr float kReflectionGateOpenDbfs = -38.0f;',
            'constexpr int kPayloadVersion = 1;':
                'constexpr float kFallbackDiffuseAzimuthDeg = 58.0f;\n'
                'constexpr float kFallbackPrimaryMix = 0.65f;\n'
                'constexpr float kFallbackSecondaryMix = 0.35f;\n'
                'constexpr float kFallbackHighEqCeiling = 0.72f;\n'
                'constexpr int kPayloadVersion = 2;',
            '    IPLBinauralEffect fallbackWetBinaural = nullptr;':
                '    IPLBinauralEffect fallbackWetBinaural = nullptr;\n'
                '    IPLBinauralEffect fallbackWetBinauralRight = nullptr;',
            '    float reflectionSendLowpassState = 0.0f;':
                '    float reflectionSendLowpassState = 0.0f;\n'
                '    float reflectionGateState = 0.0f;',
            '        if (fallbackWetBinaural) iplBinauralEffectRelease(&fallbackWetBinaural);':
                '        if (fallbackWetBinauralRight) iplBinauralEffectRelease(&fallbackWetBinauralRight);\n'
                '        if (fallbackWetBinaural) iplBinauralEffectRelease(&fallbackWetBinaural);',
            '''        case 6: {
            const float sweep = 0.5f - 0.5f * std::cos(theta);
            const float sine = std::sin(theta);
            return Pose{directionFromAngles(lerp(startAzimuthDeg, endAzimuthDeg, sweep),
                                            lerp(startElevationDeg, endElevationDeg, sine * sine)), distance};
        }''':
                '''        case 6: {
            const float sweep = 0.5f - 0.5f * std::cos(theta);
            return Pose{directionFromAngles(lerp(startAzimuthDeg, endAzimuthDeg, sweep),
                                            lerp(startElevationDeg, endElevationDeg, sweep)), distance};
        }''',
        },
    )
    replace_once(
        path,
        '''    if (!steamOk(iplBinauralEffectCreate(resources->context,
                                          const_cast<IPLAudioSettings*>(&audioSettings),
                                          &wetSettings, &resources->fallbackWetBinaural),
                 "Tạo fallback wet binaural", error)) return false;''',
        '''    if (!steamOk(iplBinauralEffectCreate(resources->context,
                                          const_cast<IPLAudioSettings*>(&audioSettings),
                                          &wetSettings, &resources->fallbackWetBinaural),
                 "Tạo fallback wet binaural trái", error) ||
        !steamOk(iplBinauralEffectCreate(resources->context,
                                          const_cast<IPLAudioSettings*>(&audioSettings),
                                          &wetSettings, &resources->fallbackWetBinauralRight),
                 "Tạo fallback wet binaural phải", error)) return false;''',
    )
    replace_once(
        path,
        '''    const float gate = smoothstep(static_cast<float>(
        (dbfs(objectRms) - kReflectionGateFloorDbfs) /
        (kReflectionGateOpenDbfs - kReflectionGateFloorDbfs)));
    const float sendAlpha = onePoleAlpha(kReflectionLowpassHz, sampleRate);''',
        '''    const float targetGate = smoothstep(static_cast<float>(
        (dbfs(objectRms) - kReflectionGateFloorDbfs) /
        (kReflectionGateOpenDbfs - kReflectionGateFloorDbfs)));
    const float blockSeconds = static_cast<float>(std::max(1, framesRead)) /
        static_cast<float>(std::max(1, sampleRate));
    const float attack = 1.0f - std::exp(-blockSeconds / 0.015f);
    const float release = 1.0f - std::exp(-blockSeconds / 0.180f);
    const float gateAlpha = targetGate > resources->reflectionGateState ? attack : release;
    resources->reflectionGateState += gateAlpha * (targetGate - resources->reflectionGateState);
    const float gate = std::max(0.0f, std::min(1.0f, resources->reflectionGateState));
    const float sendAlpha = onePoleAlpha(kReflectionLowpassHz, sampleRate);''',
    )
    replace_once(
        path,
        'void renderReflectionBlock(Resources* resources, const RoomSpec& room, const Pose& pose,\n',
        '''void mixFallbackDiffuse(Resources* resources, int frameSize) {
    for (int i = 0; i < frameSize; ++i) {
        const float leftFromLeft = resources->reflectionStereo.data[0][i];
        const float rightFromLeft = resources->reflectionStereo.data[1][i];
        const float leftFromRight = resources->earlyReflectionStereo.data[0][i];
        const float rightFromRight = resources->earlyReflectionStereo.data[1][i];
        resources->reflectionStereo.data[0][i] =
            kFallbackPrimaryMix * leftFromLeft + kFallbackSecondaryMix * leftFromRight;
        resources->reflectionStereo.data[1][i] =
            kFallbackSecondaryMix * rightFromLeft + kFallbackPrimaryMix * rightFromRight;
    }
}

void applyFallbackDiffuse(Resources* resources, IPLAudioBuffer* monoInput,
                          int interpolation, int frameSize) {
    clearBuffer(resources->reflectionStereo, frameSize);
    clearBuffer(resources->earlyReflectionStereo, frameSize);
    IPLBinauralEffectParams leftParams{};
    leftParams.direction = directionFromAngles(-kFallbackDiffuseAzimuthDeg, 0.0f);
    leftParams.interpolation = interpolation == 0
        ? IPL_HRTFINTERPOLATION_BILINEAR : IPL_HRTFINTERPOLATION_NEAREST;
    leftParams.spatialBlend = 0.82f;
    leftParams.hrtf = resources->hrtf;
    IPLBinauralEffectParams rightParams = leftParams;
    rightParams.direction = directionFromAngles(kFallbackDiffuseAzimuthDeg, 0.0f);
    iplBinauralEffectApply(resources->fallbackWetBinaural, &leftParams,
                           monoInput, &resources->reflectionStereo);
    iplBinauralEffectApply(resources->fallbackWetBinauralRight, &rightParams,
                           monoInput, &resources->earlyReflectionStereo);
    mixFallbackDiffuse(resources, frameSize);
}

void renderReflectionBlock(Resources* resources, const RoomSpec& room, const Pose& pose,
''',
    )
    replace_once(
        path,
        '''    } else if (resources->reflectionMode == ReflectionMode::ParametricFallback) {
        IPLReflectionEffectParams params{};
        params.type = IPL_REFLECTIONEFFECTTYPE_PARAMETRIC;
        params.reverbTimes[0] = rt60Low;
        params.reverbTimes[1] = rt60Mid;
        params.reverbTimes[2] = rt60High;
        params.eq[0] = eqLow;
        params.eq[1] = eqMid;
        params.eq[2] = eqHigh;
        params.numChannels = 1;
        params.irSize = static_cast<int>(std::ceil(std::max({rt60Low, rt60Mid, rt60High}) * 48000.0f));
        iplReflectionEffectApply(resources->reflectionEffect, &params,
                                 &resources->reflectionInput, &resources->reflectionField, nullptr);
        IPLBinauralEffectParams wetParams{};
        wetParams.direction = pose.direction;
        wetParams.interpolation = interpolation == 0
            ? IPL_HRTFINTERPOLATION_BILINEAR : IPL_HRTFINTERPOLATION_NEAREST;
        wetParams.spatialBlend = 0.65f;
        wetParams.hrtf = resources->hrtf;
        iplBinauralEffectApply(resources->fallbackWetBinaural, &wetParams,
                               &resources->reflectionField, &resources->reflectionStereo);
    }
    filterReflectionStereo(resources, frameSize, sampleRate);''',
        '''    } else if (resources->reflectionMode == ReflectionMode::ParametricFallback) {
        IPLReflectionEffectParams params{};
        params.type = IPL_REFLECTIONEFFECTTYPE_PARAMETRIC;
        params.reverbTimes[0] = rt60Low;
        params.reverbTimes[1] = rt60Mid;
        params.reverbTimes[2] = rt60High;
        params.eq[0] = eqLow;
        params.eq[1] = eqMid;
        params.eq[2] = std::min(eqHigh, kFallbackHighEqCeiling);
        params.numChannels = 1;
        params.irSize = static_cast<int>(std::ceil(
            std::max({rt60Low, rt60Mid, rt60High}) * static_cast<float>(sampleRate)));
        iplReflectionEffectApply(resources->reflectionEffect, &params,
                                 &resources->reflectionInput, &resources->reflectionField, nullptr);
        applyFallbackDiffuse(resources, &resources->reflectionField, interpolation, frameSize);
    }
    (void)pose;
    filterReflectionStereo(resources, frameSize, sampleRate);''',
    )
    replace_once(
        path,
        '''    } else if (resources->reflectionMode == ReflectionMode::ParametricFallback) {
        bool hasMono = false;
        if (iplReflectionEffectGetTailSize(resources->reflectionEffect) > 0) {
            iplReflectionEffectGetTail(resources->reflectionEffect, &resources->reflectionField, nullptr);
            hasMono = true;
        }
        if (hasMono) {
            IPLBinauralEffectParams wetParams{};
            wetParams.direction = pose.direction;
            wetParams.interpolation = interpolation == 0
                ? IPL_HRTFINTERPOLATION_BILINEAR : IPL_HRTFINTERPOLATION_NEAREST;
            wetParams.spatialBlend = 0.65f;
            wetParams.hrtf = resources->hrtf;
            iplBinauralEffectApply(resources->fallbackWetBinaural, &wetParams,
                                   &resources->reflectionField, &resources->reflectionStereo);
            filterReflectionStereo(resources, frameSize, sampleRate);
            return true;
        }
        if (iplBinauralEffectGetTailSize(resources->fallbackWetBinaural) > 0) {
            iplBinauralEffectGetTail(resources->fallbackWetBinaural,
                                     &resources->reflectionStereo);
            filterReflectionStereo(resources, frameSize, sampleRate);
            return true;
        }
    }''',
        '''    } else if (resources->reflectionMode == ReflectionMode::ParametricFallback) {
        if (iplReflectionEffectGetTailSize(resources->reflectionEffect) > 0) {
            iplReflectionEffectGetTail(resources->reflectionEffect, &resources->reflectionField, nullptr);
            applyFallbackDiffuse(resources, &resources->reflectionField, interpolation, frameSize);
            filterReflectionStereo(resources, frameSize, sampleRate);
            return true;
        }
        clearBuffer(resources->reflectionStereo, frameSize);
        clearBuffer(resources->earlyReflectionStereo, frameSize);
        const bool hasLeftTail = iplBinauralEffectGetTailSize(resources->fallbackWetBinaural) > 0;
        const bool hasRightTail =
            iplBinauralEffectGetTailSize(resources->fallbackWetBinauralRight) > 0;
        if (hasLeftTail) {
            iplBinauralEffectGetTail(resources->fallbackWetBinaural,
                                     &resources->reflectionStereo);
        }
        if (hasRightTail) {
            iplBinauralEffectGetTail(resources->fallbackWetBinauralRight,
                                     &resources->earlyReflectionStereo);
        }
        if (hasLeftTail || hasRightTail) {
            mixFallbackDiffuse(resources, frameSize);
            filterReflectionStereo(resources, frameSize, sampleRate);
            return true;
        }
    }
    (void)pose;''',
    )
    replace_once(
        path,
        '''        const float maxMakeup = (trajectory == 8 || std::max(startDistance, endDistance) > 4.0f)
            ? 3.0f : 6.0f;''',
        '        const float maxMakeup = 6.0f;',
    )
    replace_once(
        path,
        '''         << ",\\\"reflection_gate_floor_dbfs\\\":" << kReflectionGateFloorDbfs
         << ",\\\"true_effect_mix\\\":true}"''',
        '''         << ",\\\"reflection_gate_floor_dbfs\\\":" << kReflectionGateFloorDbfs
         << ",\\\"reflection_gate_open_dbfs\\\":" << kReflectionGateOpenDbfs
         << ",\\\"fallback_diffuse_azimuth_deg\\\":" << kFallbackDiffuseAzimuthDeg
         << ",\\\"fallback_high_eq_ceiling\\\":" << kFallbackHighEqCeiling
         << ",\\\"effective_reflection_wet_gain\\\":"
         << (std::sqrt(reverbWet) * kReflectionHeadroom)
         << ",\\\"native_renderer_version\\\":4"
         << ",\\\"true_effect_mix\\\":true}"''',
    )


def patch_tests() -> None:
    path = ROOT / "app/src/test/java/com/aistudio/mediatool/core/spatial/SpatialRoomPresenceV3Test.kt"
    replace_values(
        path,
        {
            '            SpatialRoomPreset.STUDIO to 0.24f,':
                '            SpatialRoomPreset.STUDIO to 0.27f,',
            '            SpatialRoomPreset.LISTENING_ROOM to 0.34f,':
                '            SpatialRoomPreset.LISTENING_ROOM to 0.38f,',
            '            SpatialRoomPreset.THEATER to 0.43f,':
                '            SpatialRoomPreset.THEATER to 0.48f,',
            '            SpatialRoomPreset.WAREHOUSE to 0.42f,':
                '            SpatialRoomPreset.WAREHOUSE to 0.50f,',
            '            assertTrue(value.reverbWet < 0.5f)':
                '            assertTrue(value.reverbWet <= 0.5f)',
        },
    )
    replace_once(
        path,
        '''    @Test
    fun midpointNoLongerFeelsAlmostDry() {''',
        '''    @Test
    fun frontBackPresetStaysOnTheHorizontalPlane() {
        val config = SpatialAudioConfig()
            .withFriendlyTrajectory(SpatialTrajectory.FRONT_BACK)
        assertEquals(0f, config.startElevationDeg, 1e-6f)
        assertEquals(0f, config.endElevationDeg, 1e-6f)

        val rear = SpatialTrajectoryMath.pose(config, config.cycleSeconds * 0.5f)
        assertTrue(kotlin.math.abs(rear.y) < 1e-4f)
        assertTrue(rear.z > 0.99f)
    }

    @Test
    fun midpointNoLongerFeelsAlmostDry() {''',
    )
    source_test = ROOT / "app/src/test/java/com/aistudio/mediatool/core/spatial/SpatialProductionTuningSourceTest.kt"
    source_test.write_text(
        '''package com.aistudio.mediatool.core.spatial

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialProductionTuningSourceTest {
    private val source by lazy {
        File("src/main/cpp/room_aware_spatial_jni.cpp").readText()
    }

    @Test
    fun fallbackUsesDiffusePairInsteadOfFollowingTheMovingSource() {
        assertTrue(source.contains("applyFallbackDiffuse"))
        assertTrue(source.contains("fallbackWetBinauralRight"))
        assertTrue(source.contains("kFallbackDiffuseAzimuthDeg = 58.0f"))
        assertTrue(!source.contains("wetParams.direction = pose.direction"))
    }

    @Test
    fun wetBusIsDarkenedAndGateIsSmoothed() {
        assertTrue(source.contains("kReflectionLowpassHz = 7200.0f"))
        assertTrue(source.contains("kFallbackHighEqCeiling = 0.72f"))
        assertTrue(source.contains("reflectionGateState"))
        assertTrue(source.contains("blockSeconds / 0.180f"))
    }

    @Test
    fun rearCueAndRoomPresenceHaveProductionHeadroom() {
        assertTrue(source.contains("kRearDirectLowpassHz = 6800.0f"))
        assertTrue(source.contains("kRearDirectAttenuation = 0.32f"))
        assertTrue(source.contains("kRearWetBoost = 0.65f"))
        assertTrue(source.contains("kReflectionHeadroomDb = -3.0f"))
    }
}
''',
        encoding="utf-8",
    )


def patch_docs() -> None:
    path = ROOT / "docs/SPATIAL_AUDIO_ROADMAP.md"
    text = path.read_text(encoding="utf-8")
    note = '''

## Production tuning v4

- Phản xạ phòng dùng đường cong điều khiển 1.15 và trần wet theo từng preset.
- Wet bus được lọc 7.2 kHz, giảm high-band và dùng gate attack/release để loại tiếng xì, pumping.
- Parametric fallback dùng cặp HRTF khuếch tán cố định thay vì bám theo hướng nguồn.
- Quỹ đạo trước/sau chạy ngang ở elevation 0°, với rear notch, low-pass, attenuation và wet boost rõ hơn.
- Diagnostics ghi commit, branch, renderer version và gain phản xạ hiệu dụng.
'''
    if "## Production tuning v4" not in text:
        path.write_text(text.rstrip() + note + "\n", encoding="utf-8")


def main() -> None:
    patch_build_metadata()
    patch_config()
    patch_presets()
    patch_controls()
    patch_native()
    patch_tests()
    patch_docs()

    # One-shot workflow: remove the temporary patch machinery from the final branch.
    (ROOT / ".github/workflows/apply-spatial-production-fix.yml").unlink(missing_ok=True)
    Path(__file__).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
