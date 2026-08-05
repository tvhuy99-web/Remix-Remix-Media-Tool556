#include <jni.h>
#include <android/log.h>
#include <phonon.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kTargetPeak = 0.988553f; // xấp xỉ -0.1 dBFS
constexpr const char* kTag = "MediaToolSpatial";

struct Pose {
    IPLVector3 direction;
    float distance;
};

void steamLog(IPLLogLevel level, const char* message) {
    int priority = ANDROID_LOG_INFO;
    if (level == IPL_LOGLEVEL_WARNING) priority = ANDROID_LOG_WARN;
    if (level == IPL_LOGLEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    if (level == IPL_LOGLEVEL_DEBUG) priority = ANDROID_LOG_DEBUG;
    __android_log_print(priority, kTag, "%s", message ? message : "");
}

std::string fromJString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* raw = env->GetStringUTFChars(value, nullptr);
    if (!raw) return {};
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

std::string jsonEscape(const std::string& value) {
    std::ostringstream out;
    for (char ch : value) {
        switch (ch) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (static_cast<unsigned char>(ch) < 0x20) out << ' ';
                else out << ch;
        }
    }
    return out.str();
}

jstring errorJson(JNIEnv* env, const std::string& message) {
    const std::string json = "{\"ok\":false,\"error\":\"" + jsonEscape(message) + "\"}";
    return env->NewStringUTF(json.c_str());
}

float clampFinite(float value, float low, float high, float fallback) {
    if (!std::isfinite(value)) return fallback;
    return std::max(low, std::min(high, value));
}

float lerp(float start, float end, float progress) {
    const float p = std::max(0.0f, std::min(1.0f, progress));
    return start + (end - start) * p;
}

float smoothstep(float value) {
    const float x = std::max(0.0f, std::min(1.0f, value));
    return x * x * (3.0f - 2.0f * x);
}

float positiveModulo(float value, float divisor) {
    const float mod = std::fmod(value, divisor);
    return mod < 0.0f ? mod + divisor : mod;
}

IPLVector3 normalize(float x, float y, float z) {
    const float length = std::sqrt(x * x + y * y + z * z);
    if (!std::isfinite(length) || length < 1e-6f) return IPLVector3{0.0f, 0.0f, -1.0f};
    return IPLVector3{x / length, y / length, z / length};
}

IPLVector3 directionFromAngles(float azimuthDeg, float elevationDeg) {
    const float azimuth = azimuthDeg * kPi / 180.0f;
    const float elevation = elevationDeg * kPi / 180.0f;
    const float horizontal = std::cos(elevation);
    return normalize(
        horizontal * std::sin(azimuth),
        std::sin(elevation),
        -horizontal * std::cos(azimuth)
    );
}

Pose calculatePose(
    int trajectory,
    int motionMode,
    float seconds,
    float cycleSeconds,
    float startAzimuthDeg,
    float endAzimuthDeg,
    float startElevationDeg,
    float endElevationDeg,
    float startDistance,
    float endDistance
) {
    float phase = seconds / std::max(0.5f, cycleSeconds);
    if (motionMode == 0) phase = positiveModulo(phase, 1.0f); // LOOP
    else phase = std::max(0.0f, std::min(1.0f, phase)); // ONCE
    const float eased = smoothstep(phase);
    const float distance = lerp(startDistance, endDistance, eased);

    if (trajectory == 1) { // VERTICAL_CIRCLE
        const float theta = 2.0f * kPi * phase;
        const float yaw = startAzimuthDeg * kPi / 180.0f;
        return Pose{
            normalize(std::sin(yaw) * std::cos(theta), std::sin(theta), -std::cos(yaw) * std::cos(theta)),
            distance,
        };
    }
    if (trajectory == 2) { // FIGURE_EIGHT
        const float theta = 2.0f * kPi * phase;
        const float azimuth = lerp(startAzimuthDeg, endAzimuthDeg, 0.5f + 0.5f * std::sin(theta));
        const float elevation = lerp(startElevationDeg, endElevationDeg, 0.5f + 0.5f * std::sin(2.0f * theta));
        return Pose{directionFromAngles(azimuth, elevation), distance};
    }
    if (trajectory == 3) { // LINEAR
        return Pose{
            directionFromAngles(
                lerp(startAzimuthDeg, endAzimuthDeg, eased),
                lerp(startElevationDeg, endElevationDeg, eased)
            ),
            distance,
        };
    }
    if (trajectory == 4) { // STATIC
        return Pose{directionFromAngles(startAzimuthDeg, startElevationDeg), startDistance};
    }
    // HORIZONTAL_CIRCLE
    return Pose{
        directionFromAngles(
            lerp(startAzimuthDeg, endAzimuthDeg, phase),
            lerp(startElevationDeg, endElevationDeg, eased)
        ),
        distance,
    };
}

float activeMix(float absoluteSeconds, float startSeconds, float endSeconds) {
    constexpr float fadeSeconds = 0.02f;
    if (absoluteSeconds < startSeconds - fadeSeconds) return 0.0f;
    float mix = smoothstep((absoluteSeconds - startSeconds + fadeSeconds) / (2.0f * fadeSeconds));
    if (endSeconds >= 0.0f) {
        if (absoluteSeconds > endSeconds + fadeSeconds) return 0.0f;
        mix *= 1.0f - smoothstep((absoluteSeconds - endSeconds + fadeSeconds) / (2.0f * fadeSeconds));
    }
    return std::max(0.0f, std::min(1.0f, mix));
}

float distanceAttenuation(float distance, float minimumDistance, float rolloff) {
    if (distance <= minimumDistance) return 1.0f;
    return std::pow(minimumDistance / distance, rolloff);
}

float directivityGain(const IPLVector3& sourceToListener, float yawDeg, float weight, float power) {
    const float yaw = yawDeg * kPi / 180.0f;
    const IPLVector3 forward{std::sin(yaw), 0.0f, -std::cos(yaw)};
    const float cosine = forward.x * sourceToListener.x +
                         forward.y * sourceToListener.y +
                         forward.z * sourceToListener.z;
    const float pattern = std::fabs((1.0f - weight) + weight * cosine);
    return std::pow(std::max(0.0f, std::min(1.0f, pattern)), power);
}

bool steamOk(IPLerror error, const char* phase, std::string* message) {
    if (error == IPL_STATUS_SUCCESS) return true;
    std::ostringstream out;
    out << phase << " thất bại, mã Steam Audio " << static_cast<int>(error);
    *message = out.str();
    return false;
}

void cleanup(
    IPLContext context,
    IPLHRTF* hrtf,
    IPLDirectEffect* directEffect,
    IPLBinauralEffect* directBinaural,
    IPLReflectionEffect* reflectionEffect,
    IPLBinauralEffect* wetBinaural,
    std::vector<IPLAudioBuffer*> buffers
) {
    for (IPLAudioBuffer* buffer : buffers) {
        if (context && buffer && buffer->data) iplAudioBufferFree(context, buffer);
    }
    if (wetBinaural && *wetBinaural) iplBinauralEffectRelease(wetBinaural);
    if (reflectionEffect && *reflectionEffect) iplReflectionEffectRelease(reflectionEffect);
    if (directBinaural && *directBinaural) iplBinauralEffectRelease(directBinaural);
    if (directEffect && *directEffect) iplDirectEffectRelease(directEffect);
    if (hrtf && *hrtf) iplHRTFRelease(hrtf);
    if (context) iplContextRelease(&context);
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_aistudio_mediatool_core_spatial_SteamAudioBridge_nativeRender(
    JNIEnv* env,
    jobject,
    jstring inputPathValue,
    jstring outputPathValue,
    jstring sofaPathValue,
    jint sampleRateValue,
    jint frameSizeValue,
    jint trajectoryValue,
    jint interpolationValue,
    jint motionModeValue,
    jfloat startAzimuthDegValue,
    jfloat endAzimuthDegValue,
    jfloat startElevationDegValue,
    jfloat endElevationDegValue,
    jfloat startDistanceValue,
    jfloat endDistanceValue,
    jfloat cycleSecondsValue,
    jfloat spatialBlendValue,
    jfloat distanceMinValue,
    jfloat distanceRolloffValue,
    jfloat airAbsorptionValue,
    jfloat directivityWeightValue,
    jfloat directivityPowerValue,
    jfloat sourceYawDegValue,
    jfloat reverbWetValue,
    jfloat reverbRt60LowValue,
    jfloat reverbRt60MidValue,
    jfloat reverbRt60HighValue,
    jfloat reverbEqLowValue,
    jfloat reverbEqMidValue,
    jfloat reverbEqHighValue,
    jfloat outputGainDbValue,
    jfloat effectStartSecondsValue,
    jfloat effectEndSecondsValue
) {
    const std::string inputPath = fromJString(env, inputPathValue);
    const std::string outputPath = fromJString(env, outputPathValue);
    const std::string sofaPath = fromJString(env, sofaPathValue);
    if (inputPath.empty() || outputPath.empty()) return errorJson(env, "Thiếu đường dẫn PCM");

    const int sampleRate = std::max(8000, static_cast<int>(sampleRateValue));
    const int frameSize = std::max(256, static_cast<int>(frameSizeValue));
    const int trajectory = std::max(0, std::min(4, static_cast<int>(trajectoryValue)));
    const int interpolation = std::max(0, std::min(1, static_cast<int>(interpolationValue)));
    const int motionMode = std::max(0, std::min(1, static_cast<int>(motionModeValue)));
    const float startAzimuth = clampFinite(startAzimuthDegValue, -720.0f, 720.0f, -90.0f);
    const float endAzimuth = clampFinite(endAzimuthDegValue, -720.0f, 720.0f, 270.0f);
    const float startElevation = clampFinite(startElevationDegValue, -90.0f, 90.0f, 0.0f);
    const float endElevation = clampFinite(endElevationDegValue, -90.0f, 90.0f, 0.0f);
    const float startDistance = clampFinite(startDistanceValue, 0.2f, 100.0f, 1.5f);
    const float endDistance = clampFinite(endDistanceValue, 0.2f, 100.0f, 1.5f);
    const float cycleSeconds = clampFinite(cycleSecondsValue, 0.5f, 120.0f, 8.0f);
    const float spatialBlend = clampFinite(spatialBlendValue, 0.0f, 1.0f, 1.0f);
    const float distanceMin = clampFinite(distanceMinValue, 0.1f, 20.0f, 1.0f);
    const float distanceRolloff = clampFinite(distanceRolloffValue, 0.1f, 4.0f, 1.0f);
    const float airAbsorption = clampFinite(airAbsorptionValue, 0.0f, 2.0f, 1.0f);
    const float directivityWeight = clampFinite(directivityWeightValue, 0.0f, 1.0f, 0.0f);
    const float directivityPower = clampFinite(directivityPowerValue, 1.0f, 8.0f, 1.0f);
    const float sourceYaw = clampFinite(sourceYawDegValue, -180.0f, 180.0f, 0.0f);
    const float reverbWet = clampFinite(reverbWetValue, 0.0f, 1.0f, 0.0f);
    const float rt60Low = clampFinite(reverbRt60LowValue, 0.1f, 10.0f, 0.8f);
    const float rt60Mid = clampFinite(reverbRt60MidValue, 0.1f, 10.0f, 0.7f);
    const float rt60High = clampFinite(reverbRt60HighValue, 0.1f, 10.0f, 0.5f);
    const float eqLow = clampFinite(reverbEqLowValue, 0.0f, 1.0f, 1.0f);
    const float eqMid = clampFinite(reverbEqMidValue, 0.0f, 1.0f, 1.0f);
    const float eqHigh = clampFinite(reverbEqHighValue, 0.0f, 1.0f, 1.0f);
    const float outputGain = std::pow(10.0f, clampFinite(outputGainDbValue, -24.0f, 6.0f, 0.0f) / 20.0f);
    const float effectStart = std::max(0.0f, static_cast<float>(effectStartSecondsValue));
    const float effectEnd = effectEndSecondsValue < 0.0f ? -1.0f : std::max(effectStart, static_cast<float>(effectEndSecondsValue));

    std::ifstream input(inputPath, std::ios::binary);
    if (!input) return errorJson(env, "Không mở được PCM đầu vào");
    const std::string tempPath = outputPath + ".rendering";
    std::ofstream firstPass(tempPath, std::ios::binary | std::ios::trunc);
    if (!firstPass) return errorJson(env, "Không tạo được PCM tạm");

    IPLContext context = nullptr;
    IPLHRTF hrtf = nullptr;
    IPLDirectEffect directEffect = nullptr;
    IPLBinauralEffect directBinaural = nullptr;
    IPLReflectionEffect reflectionEffect = nullptr;
    IPLBinauralEffect wetBinaural = nullptr;
    IPLAudioBuffer inputBuffer{};
    IPLAudioBuffer directBuffer{};
    IPLAudioBuffer directStereo{};
    IPLAudioBuffer reverbBuffer{};
    IPLAudioBuffer reverbStereo{};
    std::string steamError;

    IPLContextSettings contextSettings{};
    contextSettings.version = STEAMAUDIO_VERSION;
    contextSettings.logCallback = steamLog;
    contextSettings.simdLevel = IPL_SIMDLEVEL_NEON;
    if (!steamOk(iplContextCreate(&contextSettings, &context), "Tạo context", &steamError)) {
        firstPass.close(); std::remove(tempPath.c_str()); return errorJson(env, steamError);
    }

    IPLAudioSettings audioSettings{};
    audioSettings.samplingRate = sampleRate;
    audioSettings.frameSize = frameSize;

    IPLHRTFSettings hrtfSettings{};
    hrtfSettings.type = sofaPath.empty() ? IPL_HRTFTYPE_DEFAULT : IPL_HRTFTYPE_SOFA;
    hrtfSettings.sofaFileName = sofaPath.empty() ? nullptr : sofaPath.c_str();
    hrtfSettings.sofaData = nullptr;
    hrtfSettings.sofaDataSize = 0;
    hrtfSettings.volume = 1.0f;
    hrtfSettings.normType = IPL_HRTFNORMTYPE_RMS;
    if (!steamOk(iplHRTFCreate(context, &audioSettings, &hrtfSettings, &hrtf), "Nạp HRTF", &steamError)) {
        cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural, {});
        firstPass.close(); std::remove(tempPath.c_str()); return errorJson(env, steamError);
    }

    IPLDirectEffectSettings directSettings{};
    directSettings.numChannels = 1;
    IPLBinauralEffectSettings binauralSettings{};
    binauralSettings.hrtf = hrtf;
    if (!steamOk(iplDirectEffectCreate(context, &audioSettings, &directSettings, &directEffect), "Tạo direct effect", &steamError) ||
        !steamOk(iplBinauralEffectCreate(context, &audioSettings, &binauralSettings, &directBinaural), "Tạo binaural effect", &steamError)) {
        cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural, {});
        firstPass.close(); std::remove(tempPath.c_str()); return errorJson(env, steamError);
    }

    if (reverbWet > 0.0f) {
        IPLReflectionEffectSettings reflectionSettings{};
        reflectionSettings.type = IPL_REFLECTIONEFFECTTYPE_PARAMETRIC;
        reflectionSettings.irSize = static_cast<int>(std::ceil(std::max({rt60Low, rt60Mid, rt60High}) * sampleRate));
        reflectionSettings.numChannels = 1;
        if (!steamOk(iplReflectionEffectCreate(context, &audioSettings, &reflectionSettings, &reflectionEffect), "Tạo reverb", &steamError) ||
            !steamOk(iplBinauralEffectCreate(context, &audioSettings, &binauralSettings, &wetBinaural), "Tạo wet binaural", &steamError)) {
            cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural, {});
            firstPass.close(); std::remove(tempPath.c_str()); return errorJson(env, steamError);
        }
    }

    const auto allocate = [&](int channels, IPLAudioBuffer* buffer, const char* phase) -> bool {
        return steamOk(iplAudioBufferAllocate(context, channels, frameSize, buffer), phase, &steamError);
    };
    if (!allocate(1, &inputBuffer, "Cấp input buffer") ||
        !allocate(1, &directBuffer, "Cấp direct buffer") ||
        !allocate(2, &directStereo, "Cấp binaural buffer") ||
        (reverbWet > 0.0f && (!allocate(1, &reverbBuffer, "Cấp reverb buffer") ||
                             !allocate(2, &reverbStereo, "Cấp wet stereo buffer")))) {
        cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural,
                {&inputBuffer, &directBuffer, &directStereo, &reverbBuffer, &reverbStereo});
        firstPass.close(); std::remove(tempPath.c_str()); return errorJson(env, steamError);
    }

    std::vector<float> interleaved(static_cast<size_t>(frameSize) * 2u, 0.0f);
    long long frames = 0;
    long long blocks = 0;
    long long nonFinite = 0;
    long long clippedBefore = 0;
    double sumSquaresBefore = 0.0;
    float peakBefore = 0.0f;
    const auto started = std::chrono::steady_clock::now();

    while (input.good()) {
        std::fill(inputBuffer.data[0], inputBuffer.data[0] + frameSize, 0.0f);
        input.read(reinterpret_cast<char*>(inputBuffer.data[0]), static_cast<std::streamsize>(frameSize * sizeof(float)));
        const std::streamsize bytesRead = input.gcount();
        const int samplesRead = static_cast<int>(bytesRead / static_cast<std::streamsize>(sizeof(float)));
        if (samplesRead <= 0) break;

        for (int i = 0; i < samplesRead; ++i) {
            if (!std::isfinite(inputBuffer.data[0][i])) {
                inputBuffer.data[0][i] = 0.0f;
                ++nonFinite;
            }
        }

        const float absoluteSeconds = (static_cast<float>(frames) + 0.5f * samplesRead) / sampleRate;
        const float localSeconds = std::max(0.0f, absoluteSeconds - effectStart);
        const float window = activeMix(absoluteSeconds, effectStart, effectEnd);
        const Pose pose = calculatePose(
            trajectory, motionMode, localSeconds, cycleSeconds,
            startAzimuth, endAzimuth, startElevation, endElevation,
            startDistance, endDistance
        );

        IPLDirectEffectParams directParams{};
        directParams.flags = static_cast<IPLDirectEffectFlags>(
            IPL_DIRECTEFFECTFLAGS_APPLYDISTANCEATTENUATION |
            IPL_DIRECTEFFECTFLAGS_APPLYAIRABSORPTION |
            IPL_DIRECTEFFECTFLAGS_APPLYDIRECTIVITY
        );
        directParams.transmissionType = IPL_TRANSMISSIONTYPE_FREQINDEPENDENT;
        directParams.distanceAttenuation = distanceAttenuation(pose.distance, distanceMin, distanceRolloff);
        directParams.airAbsorption[0] = std::exp(-0.0002f * pose.distance * airAbsorption);
        directParams.airAbsorption[1] = std::exp(-0.0020f * pose.distance * airAbsorption);
        directParams.airAbsorption[2] = std::exp(-0.0100f * pose.distance * airAbsorption);
        const IPLVector3 sourceToListener{-pose.direction.x, -pose.direction.y, -pose.direction.z};
        directParams.directivity = directivityGain(sourceToListener, sourceYaw, directivityWeight, directivityPower);
        directParams.occlusion = 1.0f;
        directParams.transmission[0] = 1.0f;
        directParams.transmission[1] = 1.0f;
        directParams.transmission[2] = 1.0f;
        iplDirectEffectApply(directEffect, &directParams, &inputBuffer, &directBuffer);

        IPLBinauralEffectParams binauralParams{};
        binauralParams.direction = pose.direction;
        binauralParams.interpolation = interpolation == 0
            ? IPL_HRTFINTERPOLATION_BILINEAR
            : IPL_HRTFINTERPOLATION_NEAREST;
        binauralParams.spatialBlend = spatialBlend;
        binauralParams.hrtf = hrtf;
        binauralParams.peakDelays = nullptr;
        iplBinauralEffectApply(directBinaural, &binauralParams, &directBuffer, &directStereo);

        if (reverbWet > 0.0f) {
            IPLReflectionEffectParams reflectionParams{};
            reflectionParams.type = IPL_REFLECTIONEFFECTTYPE_PARAMETRIC;
            reflectionParams.reverbTimes[0] = rt60Low;
            reflectionParams.reverbTimes[1] = rt60Mid;
            reflectionParams.reverbTimes[2] = rt60High;
            reflectionParams.eq[0] = eqLow;
            reflectionParams.eq[1] = eqMid;
            reflectionParams.eq[2] = eqHigh;
            reflectionParams.delay = 0;
            reflectionParams.numChannels = 1;
            reflectionParams.irSize = static_cast<int>(std::ceil(std::max({rt60Low, rt60Mid, rt60High}) * sampleRate));
            reflectionParams.ir = nullptr;
            reflectionParams.tanDevice = nullptr;
            reflectionParams.tanSlot = 0;
            iplReflectionEffectApply(reflectionEffect, &reflectionParams, &directBuffer, &reverbBuffer, nullptr);
            IPLBinauralEffectParams wetParams = binauralParams;
            wetParams.spatialBlend = std::max(0.35f, spatialBlend * 0.75f);
            iplBinauralEffectApply(wetBinaural, &wetParams, &reverbBuffer, &reverbStereo);
        }

        const float dryGain = reverbWet > 0.0f ? std::sqrt(1.0f - reverbWet) : 1.0f;
        const float wetGain = reverbWet > 0.0f ? std::sqrt(reverbWet) : 0.0f;
        for (int i = 0; i < samplesRead; ++i) {
            const float original = inputBuffer.data[0][i];
            for (int channel = 0; channel < 2; ++channel) {
                float spatial = dryGain * directStereo.data[channel][i];
                if (reverbWet > 0.0f) spatial += wetGain * reverbStereo.data[channel][i];
                const float sample = ((1.0f - window) * original + window * spatial) * outputGain;
                float safe = sample;
                if (!std::isfinite(safe)) {
                    safe = 0.0f;
                    ++nonFinite;
                }
                const float magnitude = std::fabs(safe);
                peakBefore = std::max(peakBefore, magnitude);
                if (magnitude > 1.0f) ++clippedBefore;
                sumSquaresBefore += static_cast<double>(safe) * safe;
                interleaved[static_cast<size_t>(i) * 2u + static_cast<size_t>(channel)] = safe;
            }
        }
        firstPass.write(
            reinterpret_cast<const char*>(interleaved.data()),
            static_cast<std::streamsize>(samplesRead * 2 * sizeof(float))
        );
        if (!firstPass) {
            cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural,
                    {&inputBuffer, &directBuffer, &directStereo, &reverbBuffer, &reverbStereo});
            firstPass.close(); std::remove(tempPath.c_str()); return errorJson(env, "Ghi PCM spatial tạm thất bại");
        }
        frames += samplesRead;
        ++blocks;
    }
    firstPass.close();
    input.close();

    cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural,
            {&inputBuffer, &directBuffer, &directStereo, &reverbBuffer, &reverbStereo});

    const float sharedGain = peakBefore > kTargetPeak && peakBefore > 0.0f ? kTargetPeak / peakBefore : 1.0f;
    const float appliedGainDb = 20.0f * std::log10(std::max(sharedGain, 1e-12f));
    std::ifstream secondInput(tempPath, std::ios::binary);
    std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
    if (!secondInput || !output) {
        std::remove(tempPath.c_str());
        return errorJson(env, "Không mở được lượt shared-gain");
    }
    float peakAfter = 0.0f;
    double sumSquaresAfter = 0.0;
    long long outputSamples = 0;
    std::vector<float> gainBuffer(static_cast<size_t>(frameSize) * 2u, 0.0f);
    while (secondInput.good()) {
        secondInput.read(
            reinterpret_cast<char*>(gainBuffer.data()),
            static_cast<std::streamsize>(gainBuffer.size() * sizeof(float))
        );
        const std::streamsize bytesRead = secondInput.gcount();
        const size_t count = static_cast<size_t>(bytesRead / static_cast<std::streamsize>(sizeof(float)));
        if (count == 0u) break;
        for (size_t i = 0; i < count; ++i) {
            float sample = gainBuffer[i] * sharedGain;
            if (!std::isfinite(sample)) sample = 0.0f;
            gainBuffer[i] = sample;
            peakAfter = std::max(peakAfter, std::fabs(sample));
            sumSquaresAfter += static_cast<double>(sample) * sample;
        }
        output.write(reinterpret_cast<const char*>(gainBuffer.data()), static_cast<std::streamsize>(count * sizeof(float)));
        outputSamples += static_cast<long long>(count);
    }
    secondInput.close();
    output.close();
    std::remove(tempPath.c_str());
    if (frames <= 0 || outputSamples <= 0) {
        std::remove(outputPath.c_str());
        return errorJson(env, "PCM đầu vào không có mẫu âm thanh");
    }

    const auto finished = std::chrono::steady_clock::now();
    const long long renderMs = std::chrono::duration_cast<std::chrono::milliseconds>(finished - started).count();
    const double rms = std::sqrt(sumSquaresAfter / std::max(1LL, outputSamples));
    const double rmsDbfs = rms > 0.0 ? 20.0 * std::log10(rms) : -160.0;
    std::ostringstream json;
    json.setf(std::ios::fixed);
    json.precision(8);
    json << "{\"ok\":true"
         << ",\"frames\":" << frames
         << ",\"blocks\":" << blocks
         << ",\"render_ms\":" << renderMs
         << ",\"peak_before_gain\":" << peakBefore
         << ",\"peak_after_gain\":" << peakAfter
         << ",\"rms_dbfs\":" << rmsDbfs
         << ",\"applied_gain_db\":" << appliedGainDb
         << ",\"nonfinite_samples\":" << nonFinite
         << ",\"clipped_samples_before_gain\":" << clippedBefore
         << ",\"hrtf_type\":\"" << (sofaPath.empty() ? "built_in" : "custom_sofa") << "\""
         << ",\"steam_audio_version\":\"4.8.1\"}"
         ;
    return env->NewStringUTF(json.str().c_str());
}
