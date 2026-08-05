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
constexpr float kTargetPeak = 0.89125094f; // -1.0 dBFS, chừa headroom cho codec mất dữ liệu.
constexpr float kPeakCeilingDbfs = -1.0f;
constexpr const char* kTag = "MediaToolSpatial";

struct Pose {
    IPLVector3 direction;
    float distance;
};

struct StereoStats {
    long long frames = 0;
    double sumLeftSquares = 0.0;
    double sumRightSquares = 0.0;
    double sumCross = 0.0;
    double sumDifferenceSquares = 0.0;
    float peakLeft = 0.0f;
    float peakRight = 0.0f;

    void add(float left, float right) {
        ++frames;
        const double l = static_cast<double>(left);
        const double r = static_cast<double>(right);
        sumLeftSquares += l * l;
        sumRightSquares += r * r;
        sumCross += l * r;
        const double difference = l - r;
        sumDifferenceSquares += difference * difference;
        peakLeft = std::max(peakLeft, std::fabs(left));
        peakRight = std::max(peakRight, std::fabs(right));
    }

    double rmsLeft() const {
        return frames > 0 ? std::sqrt(sumLeftSquares / static_cast<double>(frames)) : 0.0;
    }

    double rmsRight() const {
        return frames > 0 ? std::sqrt(sumRightSquares / static_cast<double>(frames)) : 0.0;
    }

    double rmsCombined() const {
        return frames > 0
            ? std::sqrt((sumLeftSquares + sumRightSquares) / (2.0 * static_cast<double>(frames)))
            : 0.0;
    }

    double differenceRms() const {
        return frames > 0 ? std::sqrt(sumDifferenceSquares / static_cast<double>(frames)) : 0.0;
    }

    double correlation() const {
        const double denominator = std::sqrt(sumLeftSquares * sumRightSquares);
        if (denominator <= 1e-20) return 0.0;
        return std::max(-1.0, std::min(1.0, sumCross / denominator));
    }

    double balanceDb() const {
        const double left = std::max(rmsLeft(), 1e-12);
        const double right = std::max(rmsRight(), 1e-12);
        return 20.0 * std::log10(left / right);
    }

    float peak() const { return std::max(peakLeft, peakRight); }
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

float dbToLinear(float db) {
    return std::pow(10.0f, db / 20.0f);
}

double dbfs(double linear) {
    if (!std::isfinite(linear) || linear <= 1e-12) return -160.0;
    return std::max(-160.0, 20.0 * std::log10(linear));
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
    if (motionMode == 0) phase = positiveModulo(phase, 1.0f);
    else phase = std::max(0.0f, std::min(1.0f, phase));

    const float eased = smoothstep(phase);
    const float distance = lerp(startDistance, endDistance, eased);
    const float theta = 2.0f * kPi * phase;

    switch (trajectory) {
        case 1: { // VERTICAL_CIRCLE
            const float yaw = startAzimuthDeg * kPi / 180.0f;
            return Pose{
                normalize(
                    std::sin(yaw) * std::cos(theta),
                    std::sin(theta),
                    -std::cos(yaw) * std::cos(theta)
                ),
                distance,
            };
        }
        case 2: { // FIGURE_EIGHT
            const float azimuth = lerp(
                startAzimuthDeg,
                endAzimuthDeg,
                0.5f + 0.5f * std::sin(theta)
            );
            const float elevation = lerp(
                startElevationDeg,
                endElevationDeg,
                0.5f + 0.5f * std::sin(2.0f * theta)
            );
            return Pose{directionFromAngles(azimuth, elevation), distance};
        }
        case 3: // LINEAR
            return Pose{
                directionFromAngles(
                    lerp(startAzimuthDeg, endAzimuthDeg, eased),
                    lerp(startElevationDeg, endElevationDeg, eased)
                ),
                distance,
            };
        case 4: // STATIC
            return Pose{
                directionFromAngles(startAzimuthDeg, startElevationDeg),
                startDistance,
            };
        case 5: { // PENDULUM
            const float swing = 0.5f - 0.5f * std::cos(theta);
            const float lift = 0.5f - 0.5f * std::cos(2.0f * theta);
            return Pose{
                directionFromAngles(
                    lerp(startAzimuthDeg, endAzimuthDeg, swing),
                    lerp(startElevationDeg, endElevationDeg, lift)
                ),
                distance,
            };
        }
        case 6: { // FRONT_BACK
            const float sweep = 0.5f - 0.5f * std::cos(theta);
            const float sine = std::sin(theta);
            const float arch = sine * sine;
            return Pose{
                directionFromAngles(
                    lerp(startAzimuthDeg, endAzimuthDeg, sweep),
                    lerp(startElevationDeg, endElevationDeg, arch)
                ),
                distance,
            };
        }
        case 7: { // SPIRAL
            const float elevationWave = 0.5f + 0.5f * std::sin(theta);
            return Pose{
                directionFromAngles(
                    lerp(startAzimuthDeg, endAzimuthDeg, phase),
                    lerp(startElevationDeg, endElevationDeg, elevationWave)
                ),
                distance,
            };
        }
        case 8: { // NEAR_FAR
            const float breathe = 0.5f - 0.5f * std::cos(theta);
            return Pose{
                directionFromAngles(
                    lerp(startAzimuthDeg, endAzimuthDeg, phase),
                    lerp(startElevationDeg, endElevationDeg, smoothstep(breathe))
                ),
                lerp(startDistance, endDistance, breathe),
            };
        }
        case 9: { // FREE_DRIFT
            const float azimuthCenter = 0.5f * (startAzimuthDeg + endAzimuthDeg);
            const float azimuthRadius = 0.5f * (endAzimuthDeg - startAzimuthDeg);
            const float elevationCenter = 0.5f * (startElevationDeg + endElevationDeg);
            const float elevationRadius = 0.5f * (endElevationDeg - startElevationDeg);
            const float azimuthNoise =
                0.68f * std::sin(theta) +
                0.22f * std::sin(3.0f * theta + 0.7f) +
                0.10f * std::sin(5.0f * theta + 1.4f);
            const float elevationNoise =
                0.72f * std::sin(2.0f * theta + 1.1f) +
                0.28f * std::sin(4.0f * theta + 0.3f);
            const float distanceWave = std::max(
                0.0f,
                std::min(
                    1.0f,
                    0.5f +
                        0.32f * std::sin(theta + 0.4f) +
                        0.18f * std::sin(3.0f * theta + 1.2f)
                )
            );
            return Pose{
                directionFromAngles(
                    azimuthCenter + azimuthRadius * azimuthNoise,
                    elevationCenter + elevationRadius * elevationNoise
                ),
                lerp(startDistance, endDistance, distanceWave),
            };
        }
        case 0: // HORIZONTAL_CIRCLE
        default:
            return Pose{
                directionFromAngles(
                    lerp(startAzimuthDeg, endAzimuthDeg, phase),
                    lerp(startElevationDeg, endElevationDeg, eased)
                ),
                distance,
            };
    }
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
    const int trajectory = std::max(0, std::min(9, static_cast<int>(trajectoryValue)));
    const int interpolation = std::max(0, std::min(1, static_cast<int>(interpolationValue)));
    const int motionMode = std::max(0, std::min(1, static_cast<int>(motionModeValue)));
    const float startAzimuth = clampFinite(startAzimuthDegValue, -720.0f, 720.0f, -90.0f);
    const float endAzimuth = clampFinite(endAzimuthDegValue, -720.0f, 720.0f, 270.0f);
    const float startElevation = clampFinite(startElevationDegValue, -90.0f, 90.0f, 0.0f);
    const float endElevation = clampFinite(endElevationDegValue, -90.0f, 90.0f, 0.0f);
    const float startDistance = clampFinite(startDistanceValue, 0.2f, 100.0f, 1.2f);
    const float endDistance = clampFinite(endDistanceValue, 0.2f, 100.0f, 1.2f);
    const float cycleSeconds = clampFinite(cycleSecondsValue, 0.5f, 120.0f, 8.0f);
    const float spatialBlend = clampFinite(spatialBlendValue, 0.0f, 1.0f, 0.85f);
    const float distanceMin = clampFinite(distanceMinValue, 0.1f, 20.0f, 1.2f);
    const float distanceRolloff = clampFinite(distanceRolloffValue, 0.1f, 4.0f, 0.65f);
    const float airAbsorption = clampFinite(airAbsorptionValue, 0.0f, 2.0f, 0.35f);
    const float directivityWeight = clampFinite(directivityWeightValue, 0.0f, 1.0f, 0.0f);
    const float directivityPower = clampFinite(directivityPowerValue, 1.0f, 8.0f, 1.0f);
    const float sourceYaw = clampFinite(sourceYawDegValue, -180.0f, 180.0f, 0.0f);
    const float reverbWet = clampFinite(reverbWetValue, 0.0f, 1.0f, 0.12f);
    const float rt60Low = clampFinite(reverbRt60LowValue, 0.1f, 10.0f, 0.7f);
    const float rt60Mid = clampFinite(reverbRt60MidValue, 0.1f, 10.0f, 0.6f);
    const float rt60High = clampFinite(reverbRt60HighValue, 0.1f, 10.0f, 0.45f);
    const float eqLow = clampFinite(reverbEqLowValue, 0.0f, 1.0f, 1.0f);
    const float eqMid = clampFinite(reverbEqMidValue, 0.0f, 1.0f, 1.0f);
    const float eqHigh = clampFinite(reverbEqHighValue, 0.0f, 1.0f, 1.0f);
    const float manualOutputGainDb = clampFinite(outputGainDbValue, -24.0f, 6.0f, 0.0f);
    const float effectStart = std::max(0.0f, static_cast<float>(effectStartSecondsValue));
    const float effectEnd = effectEndSecondsValue < 0.0f
        ? -1.0f
        : std::max(effectStart, static_cast<float>(effectEndSecondsValue));

    std::ifstream input(inputPath, std::ios::binary);
    if (!input) return errorJson(env, "Không mở được PCM stereo đầu vào");
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
    IPLAudioBuffer reverbInputBuffer{};
    IPLAudioBuffer reverbBuffer{};
    IPLAudioBuffer reverbStereo{};
    std::string steamError;

    IPLContextSettings contextSettings{};
    contextSettings.version = STEAMAUDIO_VERSION;
    contextSettings.logCallback = steamLog;
    contextSettings.simdLevel = IPL_SIMDLEVEL_NEON;
    if (!steamOk(iplContextCreate(&contextSettings, &context), "Tạo context", &steamError)) {
        firstPass.close();
        std::remove(tempPath.c_str());
        return errorJson(env, steamError);
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
        firstPass.close();
        std::remove(tempPath.c_str());
        return errorJson(env, steamError);
    }

    IPLDirectEffectSettings directSettings{};
    directSettings.numChannels = 2;
    IPLBinauralEffectSettings binauralSettings{};
    binauralSettings.hrtf = hrtf;
    if (!steamOk(iplDirectEffectCreate(context, &audioSettings, &directSettings, &directEffect), "Tạo direct stereo", &steamError) ||
        !steamOk(iplBinauralEffectCreate(context, &audioSettings, &binauralSettings, &directBinaural), "Tạo binaural stereo", &steamError)) {
        cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural, {});
        firstPass.close();
        std::remove(tempPath.c_str());
        return errorJson(env, steamError);
    }

    if (reverbWet > 0.0f) {
        IPLReflectionEffectSettings reflectionSettings{};
        reflectionSettings.type = IPL_REFLECTIONEFFECTTYPE_PARAMETRIC;
        reflectionSettings.irSize = static_cast<int>(std::ceil(std::max({rt60Low, rt60Mid, rt60High}) * sampleRate));
        reflectionSettings.numChannels = 1;
        if (!steamOk(iplReflectionEffectCreate(context, &audioSettings, &reflectionSettings, &reflectionEffect), "Tạo reverb", &steamError) ||
            !steamOk(iplBinauralEffectCreate(context, &audioSettings, &binauralSettings, &wetBinaural), "Tạo wet binaural", &steamError)) {
            cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural, {});
            firstPass.close();
            std::remove(tempPath.c_str());
            return errorJson(env, steamError);
        }
    }

    const auto allocate = [&](int channels, IPLAudioBuffer* buffer, const char* phase) -> bool {
        return steamOk(iplAudioBufferAllocate(context, channels, frameSize, buffer), phase, &steamError);
    };
    if (!allocate(2, &inputBuffer, "Cấp input stereo") ||
        !allocate(2, &directBuffer, "Cấp direct stereo") ||
        !allocate(2, &directStereo, "Cấp binaural output") ||
        (reverbWet > 0.0f && (!allocate(1, &reverbInputBuffer, "Cấp reverb mono input") ||
                             !allocate(1, &reverbBuffer, "Cấp reverb buffer") ||
                             !allocate(2, &reverbStereo, "Cấp wet stereo buffer")))) {
        cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural,
                {&inputBuffer, &directBuffer, &directStereo, &reverbInputBuffer, &reverbBuffer, &reverbStereo});
        firstPass.close();
        std::remove(tempPath.c_str());
        return errorJson(env, steamError);
    }

    std::vector<float> inputInterleaved(static_cast<size_t>(frameSize) * 2u, 0.0f);
    std::vector<float> outputInterleaved(static_cast<size_t>(frameSize) * 2u, 0.0f);
    long long frames = 0;
    long long blocks = 0;
    long long nonFinite = 0;
    long long clippedBefore = 0;
    float peakBefore = 0.0f;
    StereoStats inputStats;
    StereoStats outputMainStats;
    const auto started = std::chrono::steady_clock::now();

    while (input.good()) {
        std::fill(inputInterleaved.begin(), inputInterleaved.end(), 0.0f);
        for (int channel = 0; channel < 2; ++channel) {
            std::fill(inputBuffer.data[channel], inputBuffer.data[channel] + frameSize, 0.0f);
        }
        input.read(
            reinterpret_cast<char*>(inputInterleaved.data()),
            static_cast<std::streamsize>(inputInterleaved.size() * sizeof(float))
        );
        const std::streamsize bytesRead = input.gcount();
        const int floatsRead = static_cast<int>(bytesRead / static_cast<std::streamsize>(sizeof(float)));
        const int framesRead = floatsRead / 2;
        if (framesRead <= 0) break;

        for (int i = 0; i < framesRead; ++i) {
            float left = inputInterleaved[static_cast<size_t>(i) * 2u];
            float right = inputInterleaved[static_cast<size_t>(i) * 2u + 1u];
            if (!std::isfinite(left)) {
                left = 0.0f;
                ++nonFinite;
            }
            if (!std::isfinite(right)) {
                right = 0.0f;
                ++nonFinite;
            }
            inputBuffer.data[0][i] = left;
            inputBuffer.data[1][i] = right;
            inputStats.add(left, right);
        }

        const float absoluteSeconds = (static_cast<float>(frames) + 0.5f * framesRead) / sampleRate;
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
            for (int i = 0; i < frameSize; ++i) {
                reverbInputBuffer.data[0][i] = 0.5f * (directBuffer.data[0][i] + directBuffer.data[1][i]);
            }
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
            iplReflectionEffectApply(reflectionEffect, &reflectionParams, &reverbInputBuffer, &reverbBuffer, nullptr);
            IPLBinauralEffectParams wetParams = binauralParams;
            wetParams.spatialBlend = std::max(0.35f, spatialBlend * 0.75f);
            iplBinauralEffectApply(wetBinaural, &wetParams, &reverbBuffer, &reverbStereo);
        }

        const float dryGain = reverbWet > 0.0f ? std::sqrt(1.0f - reverbWet) : 1.0f;
        const float wetGain = reverbWet > 0.0f ? std::sqrt(reverbWet) : 0.0f;
        for (int i = 0; i < framesRead; ++i) {
            float samples[2]{};
            for (int channel = 0; channel < 2; ++channel) {
                float spatial = dryGain * directStereo.data[channel][i];
                if (reverbWet > 0.0f) spatial += wetGain * reverbStereo.data[channel][i];
                const float original = inputBuffer.data[channel][i];
                float safe = (1.0f - window) * original + window * spatial;
                if (!std::isfinite(safe)) {
                    safe = 0.0f;
                    ++nonFinite;
                }
                const float magnitude = std::fabs(safe);
                peakBefore = std::max(peakBefore, magnitude);
                if (magnitude > 1.0f) ++clippedBefore;
                samples[channel] = safe;
                outputInterleaved[static_cast<size_t>(i) * 2u + static_cast<size_t>(channel)] = safe;
            }
            outputMainStats.add(samples[0], samples[1]);
        }
        firstPass.write(
            reinterpret_cast<const char*>(outputInterleaved.data()),
            static_cast<std::streamsize>(framesRead * 2 * sizeof(float))
        );
        if (!firstPass) {
            cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural,
                    {&inputBuffer, &directBuffer, &directStereo, &reverbInputBuffer, &reverbBuffer, &reverbStereo});
            firstPass.close();
            std::remove(tempPath.c_str());
            return errorJson(env, "Ghi PCM spatial tạm thất bại");
        }
        frames += framesRead;
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
            for (int channel = 0; channel < 2; ++channel) {
                std::fill(directBuffer.data[channel], directBuffer.data[channel] + frameSize, 0.0f);
                std::fill(directStereo.data[channel], directStereo.data[channel] + frameSize, 0.0f);
            }
            if (reverbWet > 0.0f) {
                std::fill(reverbInputBuffer.data[0], reverbInputBuffer.data[0] + frameSize, 0.0f);
                std::fill(reverbBuffer.data[0], reverbBuffer.data[0] + frameSize, 0.0f);
                for (int channel = 0; channel < 2; ++channel) {
                    std::fill(reverbStereo.data[channel], reverbStereo.data[channel] + frameSize, 0.0f);
                }
            }

            bool hasDirectFiltered = false;
            bool hasDirectStereo = false;
            bool hasReverbMono = false;
            bool hasWetStereo = false;

            if (iplDirectEffectGetTailSize(directEffect) > 0) {
                iplDirectEffectGetTail(directEffect, &directBuffer);
                hasDirectFiltered = true;
            }

            if (hasDirectFiltered) {
                iplBinauralEffectApply(directBinaural, &tailBinauralParams, &directBuffer, &directStereo);
                hasDirectStereo = true;
            } else if (iplBinauralEffectGetTailSize(directBinaural) > 0) {
                iplBinauralEffectGetTail(directBinaural, &directStereo);
                hasDirectStereo = true;
            }

            if (reverbWet > 0.0f) {
                if (hasDirectFiltered) {
                    for (int i = 0; i < frameSize; ++i) {
                        reverbInputBuffer.data[0][i] = 0.5f * (directBuffer.data[0][i] + directBuffer.data[1][i]);
                    }
                    iplReflectionEffectApply(
                        reflectionEffect,
                        &tailReflectionParams,
                        &reverbInputBuffer,
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
                    iplBinauralEffectApply(wetBinaural, &wetTailParams, &reverbBuffer, &reverbStereo);
                    hasWetStereo = true;
                } else if (iplBinauralEffectGetTailSize(wetBinaural) > 0) {
                    iplBinauralEffectGetTail(wetBinaural, &reverbStereo);
                    hasWetStereo = true;
                }
            }

            if (!hasDirectFiltered && !hasDirectStereo && !hasReverbMono && !hasWetStereo) break;

            for (int i = 0; i < frameSize; ++i) {
                for (int channel = 0; channel < 2; ++channel) {
                    float sample = 0.0f;
                    if (hasDirectStereo) sample += tailDryGain * directStereo.data[channel][i];
                    if (hasWetStereo) sample += tailWetGain * reverbStereo.data[channel][i];
                    if (!std::isfinite(sample)) {
                        sample = 0.0f;
                        ++nonFinite;
                    }
                    const float magnitude = std::fabs(sample);
                    peakBefore = std::max(peakBefore, magnitude);
                    if (magnitude > 1.0f) ++clippedBefore;
                    outputInterleaved[static_cast<size_t>(i) * 2u + static_cast<size_t>(channel)] = sample;
                }
            }
            firstPass.write(
                reinterpret_cast<const char*>(outputInterleaved.data()),
                static_cast<std::streamsize>(frameSize * 2 * sizeof(float))
            );
            if (!firstPass) {
                cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural,
                        {&inputBuffer, &directBuffer, &directStereo, &reverbInputBuffer, &reverbBuffer, &reverbStereo});
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
    cleanup(context, &hrtf, &directEffect, &directBinaural, &reflectionEffect, &wetBinaural,
            {&inputBuffer, &directBuffer, &directStereo, &reverbInputBuffer, &reverbBuffer, &reverbStereo});

    const double inputRmsDbfs = dbfs(inputStats.rmsCombined());
    const double outputMainRmsBeforeDbfs = dbfs(outputMainStats.rmsCombined());
    float automaticMakeupGainDb = 0.0f;
    if (inputRmsDbfs > -159.0 && outputMainRmsBeforeDbfs > -159.0) {
        automaticMakeupGainDb = clampFinite(
            static_cast<float>(inputRmsDbfs - outputMainRmsBeforeDbfs),
            -6.0f,
            12.0f,
            0.0f
        );
    }
    const float requestedGain = dbToLinear(automaticMakeupGainDb + manualOutputGainDb);
    const float peakLimiterGain = peakBefore > 0.0f && peakBefore * requestedGain > kTargetPeak
        ? kTargetPeak / (peakBefore * requestedGain)
        : 1.0f;
    const float totalGain = requestedGain * peakLimiterGain;
    const float peakLimiterGainDb = 20.0f * std::log10(std::max(peakLimiterGain, 1e-12f));
    const float appliedGainDb = 20.0f * std::log10(std::max(totalGain, 1e-12f));

    std::ifstream secondInput(tempPath, std::ios::binary);
    std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
    if (!secondInput || !output) {
        std::remove(tempPath.c_str());
        return errorJson(env, "Không mở được lượt loudness/peak gain");
    }

    StereoStats outputStats;
    std::vector<float> gainBuffer(static_cast<size_t>(frameSize) * 2u, 0.0f);
    long long outputSamples = 0;
    while (secondInput.good()) {
        secondInput.read(
            reinterpret_cast<char*>(gainBuffer.data()),
            static_cast<std::streamsize>(gainBuffer.size() * sizeof(float))
        );
        const std::streamsize bytesRead = secondInput.gcount();
        const size_t count = static_cast<size_t>(bytesRead / static_cast<std::streamsize>(sizeof(float)));
        if (count == 0u) break;
        for (size_t i = 0; i < count; ++i) {
            float sample = gainBuffer[i] * totalGain;
            if (!std::isfinite(sample)) sample = 0.0f;
            gainBuffer[i] = sample;
        }
        for (size_t i = 0; i + 1u < count; i += 2u) {
            outputStats.add(gainBuffer[i], gainBuffer[i + 1u]);
        }
        output.write(
            reinterpret_cast<const char*>(gainBuffer.data()),
            static_cast<std::streamsize>(count * sizeof(float))
        );
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
    const double outputMainRmsAfterDbfs = outputMainRmsBeforeDbfs + appliedGainDb;
    const double estimatedLoudnessDeltaDb = outputMainRmsAfterDbfs - inputRmsDbfs;
    const bool inputDualMono = inputStats.differenceRms() <= 1e-7 ||
        (inputStats.correlation() > 0.99999 && std::fabs(inputStats.balanceDb()) < 0.05);

    std::ostringstream json;
    json.setf(std::ios::fixed);
    json.precision(8);
    json << "{\"ok\":true"
         << ",\"frames\":" << frames
         << ",\"blocks\":" << blocks
         << ",\"tail_frames\":" << tailFrames
         << ",\"render_ms\":" << renderMs
         << ",\"input_channels\":2"
         << ",\"output_channels\":2"
         << ",\"stereo_mode\":\"preserve_or_upmix\""
         << ",\"input_peak\":" << inputStats.peak()
         << ",\"input_peak_left\":" << inputStats.peakLeft
         << ",\"input_peak_right\":" << inputStats.peakRight
         << ",\"input_rms_dbfs\":" << inputRmsDbfs
         << ",\"input_rms_left_dbfs\":" << dbfs(inputStats.rmsLeft())
         << ",\"input_rms_right_dbfs\":" << dbfs(inputStats.rmsRight())
         << ",\"input_correlation\":" << inputStats.correlation()
         << ",\"input_balance_db\":" << inputStats.balanceDb()
         << ",\"input_difference_rms_dbfs\":" << dbfs(inputStats.differenceRms())
         << ",\"input_dual_mono\":" << (inputDualMono ? "true" : "false")
         << ",\"peak_before_gain\":" << peakBefore
         << ",\"peak_after_gain\":" << outputStats.peak()
         << ",\"peak_after_gain_left\":" << outputStats.peakLeft
         << ",\"peak_after_gain_right\":" << outputStats.peakRight
         << ",\"output_main_rms_before_gain_dbfs\":" << outputMainRmsBeforeDbfs
         << ",\"output_main_rms_after_gain_dbfs\":" << outputMainRmsAfterDbfs
         << ",\"output_total_rms_dbfs\":" << dbfs(outputStats.rmsCombined())
         << ",\"output_rms_left_dbfs\":" << dbfs(outputStats.rmsLeft())
         << ",\"output_rms_right_dbfs\":" << dbfs(outputStats.rmsRight())
         << ",\"output_correlation\":" << outputStats.correlation()
         << ",\"output_balance_db\":" << outputStats.balanceDb()
         << ",\"automatic_makeup_gain_db\":" << automaticMakeupGainDb
         << ",\"manual_output_gain_db\":" << manualOutputGainDb
         << ",\"peak_limiter_gain_db\":" << peakLimiterGainDb
         << ",\"applied_gain_db\":" << appliedGainDb
         << ",\"estimated_loudness_delta_db\":" << estimatedLoudnessDeltaDb
         << ",\"peak_ceiling_dbfs\":" << kPeakCeilingDbfs
         << ",\"nonfinite_samples\":" << nonFinite
         << ",\"clipped_samples_before_gain\":" << clippedBefore
         << ",\"hrtf_type\":\"" << (sofaPath.empty() ? "built_in" : "custom_sofa") << "\""
         << ",\"steam_audio_version\":\"4.8.1\"}"
         ;
    return env->NewStringUTF(json.str().c_str());
}
