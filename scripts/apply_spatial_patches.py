#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise RuntimeError(f"{label}: cần đúng 1 vị trí, tìm thấy {count}")
    return text.replace(old, new, 1)


# Compose imports left by the first bootstrap revision.
controls = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/components/SpatialAudioControls.kt"
text = controls.read_text(encoding="utf-8")
text = text.replace("import androidx.compose.foundation.layout.weight\n", "")
text = text.replace("import androidx.compose.material3.ExposedDropdownMenu\n", "")
controls.write_text(text, encoding="utf-8")

# Remove the obsolete 8D enum contract. Auto Pan remains independent and is
# still sanitized by filter name in MediaCommandSanitizer.
rules = ROOT / "app/src/main/java/com/aistudio/mediatool/core/media/MediaEffectRules.kt"
text = rules.read_text(encoding="utf-8")
text = text.replace("    SPATIAL_8D,\n", "")
text = text.replace("        MediaAudioEffect.SPATIAL_8D,\n", "")
rules.write_text(text, encoding="utf-8")

rules_test = ROOT / "app/src/test/java/com/aistudio/mediatool/core/media/MediaEffectRulesTest.kt"
text = rules_test.read_text(encoding="utf-8")
text = text.replace("        assertFalse(MediaEffectRules.supportsTimeline(MediaAudioEffect.SPATIAL_8D))\n", "")
rules_test.write_text(text, encoding="utf-8")

# Flush the internal Steam Audio tails so HRTF convolution, air-absorption
# filtering and optional parametric reverb are not cut at end-of-file.
native = ROOT / "app/src/main/cpp/spatial_audio_jni.cpp"
text = native.read_text(encoding="utf-8")
text = text.replace("    double sumSquaresBefore = 0.0;\n", "")
text = text.replace("                sumSquaresBefore += static_cast<double>(safe) * safe;\n", "")
text = replace_once(
    text,
    """        frames += samplesRead;
        ++blocks;
    }
    firstPass.close();
    input.close();
""",
    """        frames += samplesRead;
        ++blocks;
    }

    long long tailFrames = 0;
    const float sourceDurationSeconds = static_cast<float>(frames) / static_cast<float>(sampleRate);
    const float blockDurationSeconds = static_cast<float>(frameSize) / static_cast<float>(sampleRate);
    const bool effectReachesFileEnd = effectEnd < 0.0f ||
        effectEnd >= sourceDurationSeconds - blockDurationSeconds;

    if (effectReachesFileEnd && frames > 0) {
        const float tailLocalSeconds = std::max(0.0f, sourceDurationSeconds - effectStart);
        const Pose tailPose = calculatePose(
            trajectory, motionMode, tailLocalSeconds, cycleSeconds,
            startAzimuth, endAzimuth, startElevation, endElevation,
            startDistance, endDistance
        );

        IPLBinauralEffectParams tailBinauralParams{};
        tailBinauralParams.direction = tailPose.direction;
        tailBinauralParams.interpolation = interpolation == 0
            ? IPL_HRTFINTERPOLATION_BILINEAR
            : IPL_HRTFINTERPOLATION_NEAREST;
        tailBinauralParams.spatialBlend = spatialBlend;
        tailBinauralParams.hrtf = hrtf;
        tailBinauralParams.peakDelays = nullptr;

        IPLReflectionEffectParams tailReflectionParams{};
        if (reverbWet > 0.0f) {
            tailReflectionParams.type = IPL_REFLECTIONEFFECTTYPE_PARAMETRIC;
            tailReflectionParams.reverbTimes[0] = rt60Low;
            tailReflectionParams.reverbTimes[1] = rt60Mid;
            tailReflectionParams.reverbTimes[2] = rt60High;
            tailReflectionParams.eq[0] = eqLow;
            tailReflectionParams.eq[1] = eqMid;
            tailReflectionParams.eq[2] = eqHigh;
            tailReflectionParams.delay = 0;
            tailReflectionParams.numChannels = 1;
            tailReflectionParams.irSize = static_cast<int>(
                std::ceil(std::max({rt60Low, rt60Mid, rt60High}) * sampleRate)
            );
            tailReflectionParams.ir = nullptr;
            tailReflectionParams.tanDevice = nullptr;
            tailReflectionParams.tanSlot = 0;
        }

        const float tailDryGain = reverbWet > 0.0f ? std::sqrt(1.0f - reverbWet) : 1.0f;
        const float tailWetGain = reverbWet > 0.0f ? std::sqrt(reverbWet) : 0.0f;
        const float maximumTailSeconds = reverbWet > 0.0f
            ? std::max({rt60Low, rt60Mid, rt60High}) + 1.0f
            : 1.0f;
        const int maximumTailBlocks = std::max(
            16,
            static_cast<int>(std::ceil(maximumTailSeconds * sampleRate / frameSize)) + 8
        );

        for (int tailBlock = 0; tailBlock < maximumTailBlocks; ++tailBlock) {
            std::fill(directBuffer.data[0], directBuffer.data[0] + frameSize, 0.0f);
            for (int channel = 0; channel < 2; ++channel) {
                std::fill(directStereo.data[channel], directStereo.data[channel] + frameSize, 0.0f);
            }
            if (reverbWet > 0.0f) {
                std::fill(reverbBuffer.data[0], reverbBuffer.data[0] + frameSize, 0.0f);
                for (int channel = 0; channel < 2; ++channel) {
                    std::fill(reverbStereo.data[channel], reverbStereo.data[channel] + frameSize, 0.0f);
                }
            }

            bool hasDirectMono = false;
            bool hasDirectStereo = false;
            bool hasReverbMono = false;
            bool hasWetStereo = false;

            if (iplDirectEffectGetTailSize(directEffect) > 0) {
                iplDirectEffectGetTail(directEffect, &directBuffer);
                hasDirectMono = true;
            }

            if (hasDirectMono) {
                iplBinauralEffectApply(
                    directBinaural,
                    &tailBinauralParams,
                    &directBuffer,
                    &directStereo
                );
                hasDirectStereo = true;
            } else if (iplBinauralEffectGetTailSize(directBinaural) > 0) {
                iplBinauralEffectGetTail(directBinaural, &directStereo);
                hasDirectStereo = true;
            }

            if (reverbWet > 0.0f) {
                if (hasDirectMono) {
                    iplReflectionEffectApply(
                        reflectionEffect,
                        &tailReflectionParams,
                        &directBuffer,
                        &reverbBuffer,
                        nullptr
                    );
                    hasReverbMono = true;
                } else if (iplReflectionEffectGetTailSize(reflectionEffect) > 0) {
                    iplReflectionEffectGetTail(reflectionEffect, &reverbBuffer, nullptr);
                    hasReverbMono = true;
                }

                if (hasReverbMono) {
                    IPLBinauralEffectParams wetTailParams = tailBinauralParams;
                    wetTailParams.spatialBlend = std::max(0.35f, spatialBlend * 0.75f);
                    iplBinauralEffectApply(
                        wetBinaural,
                        &wetTailParams,
                        &reverbBuffer,
                        &reverbStereo
                    );
                    hasWetStereo = true;
                } else if (iplBinauralEffectGetTailSize(wetBinaural) > 0) {
                    iplBinauralEffectGetTail(wetBinaural, &reverbStereo);
                    hasWetStereo = true;
                }
            }

            if (!hasDirectMono && !hasDirectStereo && !hasReverbMono && !hasWetStereo) break;

            for (int i = 0; i < frameSize; ++i) {
                for (int channel = 0; channel < 2; ++channel) {
                    float sample = 0.0f;
                    if (hasDirectStereo) sample += tailDryGain * directStereo.data[channel][i];
                    if (hasWetStereo) sample += tailWetGain * reverbStereo.data[channel][i];
                    sample *= outputGain;
                    if (!std::isfinite(sample)) {
                        sample = 0.0f;
                        ++nonFinite;
                    }
                    const float magnitude = std::fabs(sample);
                    peakBefore = std::max(peakBefore, magnitude);
                    if (magnitude > 1.0f) ++clippedBefore;
                    interleaved[static_cast<size_t>(i) * 2u + static_cast<size_t>(channel)] = sample;
                }
            }
            firstPass.write(
                reinterpret_cast<const char*>(interleaved.data()),
                static_cast<std::streamsize>(frameSize * 2 * sizeof(float))
            );
            if (!firstPass) {
                cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural,
                        {&inputBuffer, &directBuffer, &directStereo, &reverbBuffer, &reverbStereo});
                firstPass.close();
                std::remove(tempPath.c_str());
                return errorJson(env, "Ghi tail Spatial Audio thất bại");
            }
            tailFrames += frameSize;
            ++blocks;
        }
    }

    firstPass.close();
    input.close();
""",
    "Chèn tail flush Steam Audio",
)
text = replace_once(
    text,
    '         << ",\\\"blocks\\\":" << blocks\n',
    '         << ",\\\"blocks\\\":" << blocks\n         << ",\\\"tail_frames\\\":" << tailFrames\n',
    "Ghi tail_frames vào JSON",
)
native.write_text(text, encoding="utf-8")

# Surface tail metrics through Kotlin diagnostics.
bridge = ROOT / "app/src/main/java/com/aistudio/mediatool/core/spatial/SteamAudioBridge.kt"
text = bridge.read_text(encoding="utf-8")
text = replace_once(
    text,
    '            blocks = json.optLong("blocks"),\n',
    '            blocks = json.optLong("blocks"),\n            tailFrames = json.optLong("tail_frames"),\n',
    "Đọc tail_frames",
)
text = replace_once(
    text,
    '    val blocks: Long,\n    val renderMs: Long,\n',
    '    val blocks: Long,\n    val tailFrames: Long,\n    val renderMs: Long,\n',
    "Thêm tailFrames vào metrics",
)
text = replace_once(
    text,
    '        "blocks" to blocks,\n        "render_ms" to renderMs,\n',
    '        "blocks" to blocks,\n        "tail_frames" to tailFrames,\n        "render_ms" to renderMs,\n',
    "Ghi tailFrames diagnostics",
)
bridge.write_text(text, encoding="utf-8")

# Document the tail guarantee.
doc = ROOT / "docs/SPATIAL_AUDIO_ENGINE.md"
text = doc.read_text(encoding="utf-8")
anchor = "- số frame và block;\n"
if "tail frame" not in text.lower():
    text = text.replace(anchor, anchor + "- số tail frame được xả sau khi nguồn kết thúc;\n", 1)
    text = text.replace(
        "Wet bằng 0 không tạo hoặc chạy reflection effect.\n",
        "Wet bằng 0 không tạo hoặc chạy reflection effect. Renderer vẫn xả tail ngắn của direct/HRTF khi hiệu ứng kéo tới cuối tệp; khi Wet lớn hơn 0, reflection và wet-binaural tail cũng được xả đầy đủ.\n",
        1,
    )
doc.write_text(text, encoding="utf-8")

print("Đã hoàn thiện tail DSP và loại bỏ contract 8D cũ")
