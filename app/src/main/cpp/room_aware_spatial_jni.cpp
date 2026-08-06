#include <jni.h>
#include <android/log.h>
#include <phonon.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kTargetPeak = 0.89125094f;
constexpr float kPeakCeilingDbfs = -1.0f;
constexpr float kReflectionHeadroom = 0.59566214f;
constexpr float kReflectionHeadroomDb = -4.5f;
constexpr float kObjectLateralCueDb = 4.5f;
constexpr float kObjectAmbienceBedMin = 0.06f;
constexpr float kObjectAmbienceBedMax = 0.14f;
constexpr float kFrontDirectLowpassHz = 18000.0f;
constexpr float kRearDirectLowpassHz = 6800.0f;
constexpr float kReflectionLowpassHz = 9000.0f;
constexpr float kReflectionGateFloorDbfs = -62.0f;
constexpr float kReflectionGateOpenDbfs = -42.0f;
constexpr int kPayloadVersion = 1;
constexpr const char* kTag = "MediaToolRoomSpatial";

enum class ReflectionMode { None, Hybrid, ParametricFallback };

struct Pose {
    IPLVector3 direction;
    float distance;
};

struct MaterialSpec {
    float low = 0.0f;
    float mid = 0.0f;
    float high = 0.0f;
    float scattering = 0.0f;
};

struct RoomSpec {
    bool enabled = false;
    int roomId = 0;
    int rays = 4096;
    int bounces = 16;
    int order = 1;
    int threads = 2;
    float width = 0.0f;
    float depth = 0.0f;
    float height = 0.0f;
    MaterialSpec walls;
    MaterialSpec floor;
    MaterialSpec ceiling;
    float duration = 2.0f;
    float transition = 0.12f;
    float overlap = 0.25f;
    float updateSeconds = 0.25f;
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

struct Resources {
    IPLContext context = nullptr;
    IPLHRTF hrtf = nullptr;
    IPLDirectEffect directEffect = nullptr;
    IPLBinauralEffect directBinaural = nullptr;
    IPLScene scene = nullptr;
    IPLStaticMesh staticMesh = nullptr;
    IPLSimulator simulator = nullptr;
    IPLSource source = nullptr;
    IPLReflectionEffect reflectionEffect = nullptr;
    IPLAmbisonicsDecodeEffect ambisonicsDecode = nullptr;
    IPLBinauralEffect fallbackWetBinaural = nullptr;
    IPLAudioBuffer inputBuffer{};
    IPLAudioBuffer objectMono{};
    IPLAudioBuffer directBuffer{};
    IPLAudioBuffer directStereo{};
    IPLAudioBuffer reflectionInput{};
    IPLAudioBuffer reflectionField{};
    IPLAudioBuffer reflectionStereo{};
    bool sourceAdded = false;
    ReflectionMode reflectionMode = ReflectionMode::None;
    IPLReflectionEffectParams reflectionParams{};
    bool reflectionReady = false;
    long long nextReflectionUpdateFrame = 0;
    long long reflectionUpdates = 0;
    long long reflectionSourceClamps = 0;
    long long reflectionSimulationMs = 0;
    float directLowpassState[2] = {0.0f, 0.0f};
    float reflectionSendLowpassState = 0.0f;
    float reflectionWetLowpassState[2] = {0.0f, 0.0f};

    ~Resources() {
        if (context) {
            freeBuffer(inputBuffer);
            freeBuffer(objectMono);
            freeBuffer(directBuffer);
            freeBuffer(directStereo);
            freeBuffer(reflectionInput);
            freeBuffer(reflectionField);
            freeBuffer(reflectionStereo);
        }
        if (fallbackWetBinaural) iplBinauralEffectRelease(&fallbackWetBinaural);
        if (ambisonicsDecode) iplAmbisonicsDecodeEffectRelease(&ambisonicsDecode);
        if (reflectionEffect) iplReflectionEffectRelease(&reflectionEffect);
        if (source) {
            if (sourceAdded && simulator) iplSourceRemove(source, simulator);
            iplSourceRelease(&source);
        }
        if (simulator) iplSimulatorRelease(&simulator);
        if (staticMesh) iplStaticMeshRelease(&staticMesh);
        if (scene) iplSceneRelease(&scene);
        if (directBinaural) iplBinauralEffectRelease(&directBinaural);
        if (directEffect) iplDirectEffectRelease(&directEffect);
        if (hrtf) iplHRTFRelease(&hrtf);
        if (context) iplContextRelease(&context);
    }

    void freeBuffer(IPLAudioBuffer& buffer) {
        if (buffer.data) iplAudioBufferFree(context, &buffer);
    }
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

float onePoleAlpha(float cutoffHz, int sampleRate) {
    const float safeCutoff = std::max(20.0f, std::min(cutoffHz, 0.45f * sampleRate));
    return 1.0f - std::exp(-2.0f * kPi * safeCutoff / static_cast<float>(sampleRate));
}

float lowpassSample(float sample, float alpha, float* state) {
    *state += alpha * (sample - *state);
    return *state;
}

float effectiveSpatialMix(float blend) {
    const float value = std::max(0.0f, std::min(1.0f, blend));
    return 1.0f - (1.0f - value) * (1.0f - value);
}

void filterReflectionStereo(Resources* resources, int frameSize, int sampleRate) {
    const float alpha = onePoleAlpha(kReflectionLowpassHz, sampleRate);
    for (int channel = 0; channel < 2; ++channel) {
        for (int i = 0; i < frameSize; ++i) {
            resources->reflectionStereo.data[channel][i] = lowpassSample(
                resources->reflectionStereo.data[channel][i], alpha,
                &resources->reflectionWetLowpassState[channel]);
        }
    }
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
        case 1: {
            const float yaw = startAzimuthDeg * kPi / 180.0f;
            return Pose{normalize(std::sin(yaw) * std::cos(theta), std::sin(theta),
                                  -std::cos(yaw) * std::cos(theta)), distance};
        }
        case 2:
            return Pose{directionFromAngles(
                lerp(startAzimuthDeg, endAzimuthDeg, 0.5f + 0.5f * std::sin(theta)),
                lerp(startElevationDeg, endElevationDeg, 0.5f + 0.5f * std::sin(2.0f * theta))
            ), distance};
        case 3:
            return Pose{directionFromAngles(lerp(startAzimuthDeg, endAzimuthDeg, eased),
                                            lerp(startElevationDeg, endElevationDeg, eased)), distance};
        case 4:
            return Pose{directionFromAngles(startAzimuthDeg, startElevationDeg), startDistance};
        case 5: {
            const float swing = 0.5f - 0.5f * std::cos(theta);
            const float lift = 0.5f - 0.5f * std::cos(2.0f * theta);
            return Pose{directionFromAngles(lerp(startAzimuthDeg, endAzimuthDeg, swing),
                                            lerp(startElevationDeg, endElevationDeg, lift)), distance};
        }
        case 6: {
            const float sweep = 0.5f - 0.5f * std::cos(theta);
            const float sine = std::sin(theta);
            return Pose{directionFromAngles(lerp(startAzimuthDeg, endAzimuthDeg, sweep),
                                            lerp(startElevationDeg, endElevationDeg, sine * sine)), distance};
        }
        case 7:
            return Pose{directionFromAngles(lerp(startAzimuthDeg, endAzimuthDeg, phase),
                                            lerp(startElevationDeg, endElevationDeg,
                                                 0.5f + 0.5f * std::sin(theta))), distance};
        case 8: {
            const float breathe = 0.5f - 0.5f * std::cos(theta);
            return Pose{directionFromAngles(lerp(startAzimuthDeg, endAzimuthDeg, phase),
                                            lerp(startElevationDeg, endElevationDeg,
                                                 smoothstep(breathe))),
                        lerp(startDistance, endDistance, breathe)};
        }
        case 9: {
            const float azimuthCenter = 0.5f * (startAzimuthDeg + endAzimuthDeg);
            const float azimuthRadius = 0.5f * (endAzimuthDeg - startAzimuthDeg);
            const float elevationCenter = 0.5f * (startElevationDeg + endElevationDeg);
            const float elevationRadius = 0.5f * (endElevationDeg - startElevationDeg);
            const float azimuthNoise = 0.68f * std::sin(theta) +
                0.22f * std::sin(3.0f * theta + 0.7f) + 0.10f * std::sin(5.0f * theta + 1.4f);
            const float elevationNoise = 0.72f * std::sin(2.0f * theta + 1.1f) +
                0.28f * std::sin(4.0f * theta + 0.3f);
            const float distanceWave = std::max(0.0f, std::min(1.0f,
                0.5f + 0.32f * std::sin(theta + 0.4f) + 0.18f * std::sin(3.0f * theta + 1.2f)));
            return Pose{directionFromAngles(azimuthCenter + azimuthRadius * azimuthNoise,
                                            elevationCenter + elevationRadius * elevationNoise),
                        lerp(startDistance, endDistance, distanceWave)};
        }
        case 0:
        default:
            return Pose{directionFromAngles(lerp(startAzimuthDeg, endAzimuthDeg, phase),
                                            lerp(startElevationDeg, endElevationDeg, eased)), distance};
    }
}

float activeMix(float absoluteSeconds, float startSeconds, float endSeconds) {
    constexpr float fadeSeconds = 0.02f;
    if (absoluteSeconds < startSeconds - fadeSeconds) return 0.0f;
    float mix = smoothstep((absoluteSeconds - startSeconds + fadeSeconds) / (2.0f * fadeSeconds));
    if (endSeconds >= 0.0f) {
        if (absoluteSeconds > endSeconds + fadeSeconds) return 0.0f;
        mix *= 1.0f - smoothstep((absoluteSeconds - endSeconds + fadeSeconds) /
                                 (2.0f * fadeSeconds));
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
    const float cosine = forward.x * sourceToListener.x + forward.y * sourceToListener.y +
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

IPLCoordinateSpace3 coordinates(IPLVector3 origin) {
    IPLCoordinateSpace3 result{};
    result.origin = origin;
    result.right = IPLVector3{1.0f, 0.0f, 0.0f};
    result.up = IPLVector3{0.0f, 1.0f, 0.0f};
    result.ahead = IPLVector3{0.0f, 0.0f, -1.0f};
    return result;
}

bool readRoomSpec(JNIEnv* env, jintArray integerPayload, jfloatArray floatPayload,
                  RoomSpec* spec, std::string* error) {
    if (!integerPayload || !floatPayload || env->GetArrayLength(integerPayload) < 7 ||
        env->GetArrayLength(floatPayload) < 20) {
        *error = "Payload mô hình phòng không hợp lệ";
        return false;
    }
    std::array<jint, 7> ints{};
    std::array<jfloat, 20> floats{};
    env->GetIntArrayRegion(integerPayload, 0, static_cast<jsize>(ints.size()), ints.data());
    env->GetFloatArrayRegion(floatPayload, 0, static_cast<jsize>(floats.size()), floats.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        *error = "Không đọc được payload mô hình phòng";
        return false;
    }
    if (ints[0] != kPayloadVersion || static_cast<int>(std::lround(floats[0])) != kPayloadVersion) {
        *error = "Phiên bản payload mô hình phòng không tương thích";
        return false;
    }
    spec->enabled = ints[1] != 0;
    spec->roomId = std::max(0, std::min(32, static_cast<int>(ints[2])));
    spec->rays = std::max(256, std::min(32768, static_cast<int>(ints[3])));
    spec->bounces = std::max(1, std::min(64, static_cast<int>(ints[4])));
    spec->order = std::max(0, std::min(2, static_cast<int>(ints[5])));
    spec->threads = std::max(1, std::min(4, static_cast<int>(ints[6])));
    spec->width = clampFinite(floats[1], 0.5f, 100.0f, 5.0f);
    spec->depth = clampFinite(floats[2], 0.5f, 100.0f, 4.0f);
    spec->height = clampFinite(floats[3], 0.5f, 30.0f, 2.8f);
    auto material = [&](int offset) {
        return MaterialSpec{
            clampFinite(floats[offset], 0.0f, 1.0f, 0.2f),
            clampFinite(floats[offset + 1], 0.0f, 1.0f, 0.3f),
            clampFinite(floats[offset + 2], 0.0f, 1.0f, 0.4f),
            clampFinite(floats[offset + 3], 0.0f, 1.0f, 0.5f),
        };
    };
    spec->walls = material(4);
    spec->floor = material(8);
    spec->ceiling = material(12);
    spec->duration = clampFinite(floats[16], 0.25f, 4.0f, 2.0f);
    spec->transition = clampFinite(floats[17], 0.02f, std::min(0.5f, spec->duration), 0.12f);
    spec->overlap = clampFinite(floats[18], 0.0f, 1.0f, 0.25f);
    spec->updateSeconds = clampFinite(floats[19], 0.05f, 2.0f, 0.25f);
    return true;
}

IPLMaterial toMaterial(const MaterialSpec& source) {
    IPLMaterial material{};
    material.absorption[0] = source.low;
    material.absorption[1] = source.mid;
    material.absorption[2] = source.high;
    material.scattering = source.scattering;
    material.transmission[0] = 0.0f;
    material.transmission[1] = 0.0f;
    material.transmission[2] = 0.0f;
    return material;
}

bool createRoomScene(Resources* resources, const RoomSpec& room, std::string* error) {
    IPLSceneSettings sceneSettings{};
    sceneSettings.type = IPL_SCENETYPE_DEFAULT;
    if (!steamOk(iplSceneCreate(resources->context, &sceneSettings, &resources->scene),
                 "Tạo scene phòng", error)) return false;

    const float hx = room.width * 0.5f;
    const float hy = room.height * 0.5f;
    const float hz = room.depth * 0.5f;
    IPLVector3 vertices[8] = {
        {-hx, -hy, -hz}, {hx, -hy, -hz}, {hx, -hy, hz}, {-hx, -hy, hz},
        {-hx, hy, -hz}, {hx, hy, -hz}, {hx, hy, hz}, {-hx, hy, hz},
    };
    IPLTriangle triangles[12] = {
        {0, 3, 2}, {0, 2, 1},
        {4, 5, 6}, {4, 6, 7},
        {0, 1, 5}, {0, 5, 4},
        {3, 7, 6}, {3, 6, 2},
        {0, 4, 7}, {0, 7, 3},
        {1, 2, 6}, {1, 6, 5},
    };
    IPLMaterial materials[3] = {
        toMaterial(room.walls), toMaterial(room.floor), toMaterial(room.ceiling),
    };
    IPLint32 materialIndices[12] = {1, 1, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0};
    IPLStaticMeshSettings meshSettings{};
    meshSettings.numVertices = 8;
    meshSettings.numTriangles = 12;
    meshSettings.numMaterials = 3;
    meshSettings.vertices = vertices;
    meshSettings.triangles = triangles;
    meshSettings.materialIndices = materialIndices;
    meshSettings.materials = materials;
    if (!steamOk(iplStaticMeshCreate(resources->scene, &meshSettings, &resources->staticMesh),
                 "Tạo mesh phòng", error)) return false;
    iplStaticMeshAdd(resources->staticMesh, resources->scene);
    iplSceneCommit(resources->scene);
    return true;
}

bool allocateBuffer(Resources* resources, int channels, int frameSize,
                    IPLAudioBuffer* buffer, const char* phase, std::string* error) {
    return steamOk(iplAudioBufferAllocate(resources->context, channels, frameSize, buffer),
                   phase, error);
}

bool initializeHybridReflections(Resources* resources, const RoomSpec& room,
                                 const IPLAudioSettings& audioSettings, std::string* error) {
    if (!createRoomScene(resources, room, error)) return false;
    IPLSimulationSettings simulationSettings{};
    simulationSettings.flags = IPL_SIMULATIONFLAGS_REFLECTIONS;
    simulationSettings.sceneType = IPL_SCENETYPE_DEFAULT;
    simulationSettings.reflectionType = IPL_REFLECTIONEFFECTTYPE_HYBRID;
    simulationSettings.maxNumRays = room.rays;
    simulationSettings.numDiffuseSamples = 64;
    simulationSettings.maxDuration = room.duration;
    simulationSettings.maxOrder = room.order;
    simulationSettings.maxNumSources = 1;
    simulationSettings.numThreads = room.threads;
    simulationSettings.samplingRate = audioSettings.samplingRate;
    simulationSettings.frameSize = audioSettings.frameSize;
    if (!steamOk(iplSimulatorCreate(resources->context, &simulationSettings, &resources->simulator),
                 "Tạo reflection simulator", error)) return false;
    iplSimulatorSetScene(resources->simulator, resources->scene);
    iplSimulatorCommit(resources->simulator);

    IPLSourceSettings sourceSettings{};
    sourceSettings.flags = IPL_SIMULATIONFLAGS_REFLECTIONS;
    if (!steamOk(iplSourceCreate(resources->simulator, &sourceSettings, &resources->source),
                 "Tạo reflection source", error)) return false;
    iplSourceAdd(resources->source, resources->simulator);
    resources->sourceAdded = true;
    iplSimulatorCommit(resources->simulator);

    const int channels = (room.order + 1) * (room.order + 1);
    const int irSize = static_cast<int>(std::ceil(room.duration * audioSettings.samplingRate));
    IPLReflectionEffectSettings reflectionSettings{};
    reflectionSettings.type = IPL_REFLECTIONEFFECTTYPE_HYBRID;
    reflectionSettings.irSize = irSize;
    reflectionSettings.numChannels = channels;
    if (!steamOk(iplReflectionEffectCreate(resources->context, const_cast<IPLAudioSettings*>(&audioSettings),
                                            &reflectionSettings, &resources->reflectionEffect),
                 "Tạo Hybrid Reflection Effect", error)) return false;

    IPLAmbisonicsDecodeEffectSettings decodeSettings{};
    decodeSettings.hrtf = resources->hrtf;
    decodeSettings.maxOrder = room.order;
    if (!steamOk(iplAmbisonicsDecodeEffectCreate(resources->context,
                                                  const_cast<IPLAudioSettings*>(&audioSettings),
                                                  &decodeSettings, &resources->ambisonicsDecode),
                 "Tạo Ambisonics binaural decoder", error)) return false;

    if (!allocateBuffer(resources, 1, audioSettings.frameSize, &resources->reflectionInput,
                        "Cấp reflection mono input", error) ||
        !allocateBuffer(resources, channels, audioSettings.frameSize, &resources->reflectionField,
                        "Cấp Ambisonics reflection field", error) ||
        !allocateBuffer(resources, 2, audioSettings.frameSize, &resources->reflectionStereo,
                        "Cấp reflection stereo", error)) return false;
    resources->reflectionMode = ReflectionMode::Hybrid;
    return true;
}

bool initializeParametricFallback(Resources* resources, const IPLAudioSettings& audioSettings,
                                  float rt60Low, float rt60Mid, float rt60High,
                                  std::string* error) {
    IPLReflectionEffectSettings reflectionSettings{};
    reflectionSettings.type = IPL_REFLECTIONEFFECTTYPE_PARAMETRIC;
    reflectionSettings.irSize = static_cast<int>(std::ceil(
        std::max({rt60Low, rt60Mid, rt60High}) * audioSettings.samplingRate));
    reflectionSettings.numChannels = 1;
    if (!steamOk(iplReflectionEffectCreate(resources->context, const_cast<IPLAudioSettings*>(&audioSettings),
                                            &reflectionSettings, &resources->reflectionEffect),
                 "Tạo parametric fallback", error)) return false;
    IPLBinauralEffectSettings wetSettings{};
    wetSettings.hrtf = resources->hrtf;
    if (!steamOk(iplBinauralEffectCreate(resources->context,
                                          const_cast<IPLAudioSettings*>(&audioSettings),
                                          &wetSettings, &resources->fallbackWetBinaural),
                 "Tạo fallback wet binaural", error)) return false;
    if (!allocateBuffer(resources, 1, audioSettings.frameSize, &resources->reflectionInput,
                        "Cấp fallback mono input", error) ||
        !allocateBuffer(resources, 1, audioSettings.frameSize, &resources->reflectionField,
                        "Cấp fallback reverb field", error) ||
        !allocateBuffer(resources, 2, audioSettings.frameSize, &resources->reflectionStereo,
                        "Cấp fallback wet stereo", error)) return false;
    resources->reflectionMode = ReflectionMode::ParametricFallback;
    return true;
}

IPLVector3 clampedRoomPosition(const RoomSpec& room, const Pose& pose, bool* clamped) {
    const float margin = 0.25f;
    const float maxX = std::max(0.05f, room.width * 0.5f - margin);
    const float maxY = std::max(0.05f, room.height * 0.5f - margin);
    const float maxZ = std::max(0.05f, room.depth * 0.5f - margin);
    const IPLVector3 raw{pose.direction.x * pose.distance,
                         pose.direction.y * pose.distance,
                         pose.direction.z * pose.distance};
    const IPLVector3 result{
        std::max(-maxX, std::min(maxX, raw.x)),
        std::max(-maxY, std::min(maxY, raw.y)),
        std::max(-maxZ, std::min(maxZ, raw.z)),
    };
    *clamped = std::fabs(result.x - raw.x) > 1e-4f ||
        std::fabs(result.y - raw.y) > 1e-4f || std::fabs(result.z - raw.z) > 1e-4f;
    return result;
}

void updateHybridReflection(Resources* resources, const RoomSpec& room, const Pose& pose,
                            long long absoluteFrame, int sampleRate) {
    if (resources->reflectionMode != ReflectionMode::Hybrid ||
        absoluteFrame < resources->nextReflectionUpdateFrame) return;
    bool clamped = false;
    const IPLVector3 sourceOrigin = clampedRoomPosition(room, pose, &clamped);
    if (clamped) ++resources->reflectionSourceClamps;

    IPLSimulationInputs inputs{};
    inputs.flags = IPL_SIMULATIONFLAGS_REFLECTIONS;
    inputs.source = coordinates(sourceOrigin);
    inputs.reverbScale[0] = 1.0f;
    inputs.reverbScale[1] = 1.0f;
    inputs.reverbScale[2] = 1.0f;
    inputs.hybridReverbTransitionTime = room.transition;
    inputs.hybridReverbOverlapPercent = room.overlap;
    iplSourceSetInputs(resources->source, IPL_SIMULATIONFLAGS_REFLECTIONS, &inputs);

    IPLSimulationSharedInputs shared{};
    shared.listener = coordinates(IPLVector3{0.0f, 0.0f, 0.0f});
    shared.numRays = room.rays;
    shared.numBounces = room.bounces;
    shared.duration = room.duration;
    shared.order = room.order;
    shared.irradianceMinDistance = 0.5f;
    iplSimulatorSetSharedInputs(resources->simulator, IPL_SIMULATIONFLAGS_REFLECTIONS, &shared);

    const auto started = std::chrono::steady_clock::now();
    iplSimulatorRunReflections(resources->simulator);
    const auto finished = std::chrono::steady_clock::now();
    resources->reflectionSimulationMs += std::chrono::duration_cast<std::chrono::milliseconds>(
        finished - started).count();

    IPLSimulationOutputs outputs{};
    iplSourceGetOutputs(resources->source, IPL_SIMULATIONFLAGS_REFLECTIONS, &outputs);
    resources->reflectionParams = outputs.reflections;
    resources->reflectionParams.type = IPL_REFLECTIONEFFECTTYPE_HYBRID;
    resources->reflectionParams.numChannels = (room.order + 1) * (room.order + 1);
    resources->reflectionParams.irSize = static_cast<int>(std::ceil(room.duration * sampleRate));
    resources->reflectionReady = resources->reflectionParams.ir != nullptr;
    ++resources->reflectionUpdates;
    resources->nextReflectionUpdateFrame = absoluteFrame +
        std::max<long long>(1, static_cast<long long>(std::llround(room.updateSeconds * sampleRate)));
}

void clearBuffer(IPLAudioBuffer& buffer, int frameSize) {
    if (!buffer.data) return;
    for (int channel = 0; channel < buffer.numChannels; ++channel) {
        std::fill(buffer.data[channel], buffer.data[channel] + frameSize, 0.0f);
    }
}

void renderReflectionBlock(Resources* resources, const RoomSpec& room, const Pose& pose,
                           int interpolation, int framesRead, int frameSize, int sampleRate,
                           float rt60Low, float rt60Mid, float rt60High,
                           float eqLow, float eqMid, float eqHigh) {
    clearBuffer(resources->reflectionInput, frameSize);
    clearBuffer(resources->reflectionField, frameSize);
    clearBuffer(resources->reflectionStereo, frameSize);
    double objectEnergy = 0.0;
    for (int i = 0; i < framesRead; ++i) {
        const float sample = resources->objectMono.data[0][i];
        objectEnergy += static_cast<double>(sample) * static_cast<double>(sample);
    }
    const double objectRms = framesRead > 0
        ? std::sqrt(objectEnergy / static_cast<double>(framesRead)) : 0.0;
    const float gate = smoothstep(static_cast<float>(
        (dbfs(objectRms) - kReflectionGateFloorDbfs) /
        (kReflectionGateOpenDbfs - kReflectionGateFloorDbfs)));
    const float sendAlpha = onePoleAlpha(kReflectionLowpassHz, sampleRate);
    for (int i = 0; i < framesRead; ++i) {
        resources->reflectionInput.data[0][i] = gate * lowpassSample(
            resources->objectMono.data[0][i], sendAlpha,
            &resources->reflectionSendLowpassState);
    }

    if (resources->reflectionMode == ReflectionMode::Hybrid && resources->reflectionReady) {
        iplReflectionEffectApply(resources->reflectionEffect, &resources->reflectionParams,
                                 &resources->reflectionInput, &resources->reflectionField, nullptr);
        IPLAmbisonicsDecodeEffectParams decodeParams{};
        decodeParams.order = room.order;
        decodeParams.hrtf = resources->hrtf;
        decodeParams.orientation = coordinates(IPLVector3{0.0f, 0.0f, 0.0f});
        decodeParams.binaural = IPL_TRUE;
        iplAmbisonicsDecodeEffectApply(resources->ambisonicsDecode, &decodeParams,
                                       &resources->reflectionField, &resources->reflectionStereo);
    } else if (resources->reflectionMode == ReflectionMode::ParametricFallback) {
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
    filterReflectionStereo(resources, frameSize, sampleRate);
}

bool renderReflectionTail(Resources* resources, const RoomSpec& room, const Pose& pose,
                          int interpolation, int frameSize, int sampleRate, float rt60Low,
                          float rt60Mid, float rt60High, float eqLow, float eqMid, float eqHigh) {
    clearBuffer(resources->reflectionField, frameSize);
    clearBuffer(resources->reflectionStereo, frameSize);
    if (resources->reflectionMode == ReflectionMode::Hybrid) {
        if (iplReflectionEffectGetTailSize(resources->reflectionEffect) > 0) {
            iplReflectionEffectGetTail(resources->reflectionEffect, &resources->reflectionField, nullptr);
            IPLAmbisonicsDecodeEffectParams decodeParams{};
            decodeParams.order = room.order;
            decodeParams.hrtf = resources->hrtf;
            decodeParams.orientation = coordinates(IPLVector3{0.0f, 0.0f, 0.0f});
            decodeParams.binaural = IPL_TRUE;
            iplAmbisonicsDecodeEffectApply(resources->ambisonicsDecode, &decodeParams,
                                           &resources->reflectionField, &resources->reflectionStereo);
            filterReflectionStereo(resources, frameSize, sampleRate);
            return true;
        }
        if (iplAmbisonicsDecodeEffectGetTailSize(resources->ambisonicsDecode) > 0) {
            iplAmbisonicsDecodeEffectGetTail(resources->ambisonicsDecode,
                                             &resources->reflectionStereo);
            filterReflectionStereo(resources, frameSize, sampleRate);
            return true;
        }
    } else if (resources->reflectionMode == ReflectionMode::ParametricFallback) {
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
    }
    (void)rt60Low;
    (void)rt60Mid;
    (void)rt60High;
    (void)eqLow;
    (void)eqMid;
    (void)eqHigh;
    return false;
}

const char* reflectionModeName(ReflectionMode mode) {
    switch (mode) {
        case ReflectionMode::Hybrid: return "ray_traced_hybrid";
        case ReflectionMode::ParametricFallback: return "parametric_fallback";
        case ReflectionMode::None:
        default: return "none";
    }
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_aistudio_mediatool_core_spatial_SteamAudioBridge_nativeRenderRoomAware(
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
    jintArray reflectionIntegerPayload,
    jfloatArray reflectionFloatPayload,
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

    RoomSpec room;
    std::string steamError;
    if (!readRoomSpec(env, reflectionIntegerPayload, reflectionFloatPayload, &room, &steamError)) {
        return errorJson(env, steamError);
    }

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
    const float reverbWet = clampFinite(reverbWetValue, 0.0f, 0.49f, 0.12f);
    const float rt60Low = clampFinite(reverbRt60LowValue, 0.1f, 10.0f, 0.7f);
    const float rt60Mid = clampFinite(reverbRt60MidValue, 0.1f, 10.0f, 0.6f);
    const float rt60High = clampFinite(reverbRt60HighValue, 0.1f, 10.0f, 0.45f);
    const float eqLow = clampFinite(reverbEqLowValue, 0.0f, 1.0f, 1.0f);
    const float eqMid = clampFinite(reverbEqMidValue, 0.0f, 1.0f, 1.0f);
    const float eqHigh = clampFinite(reverbEqHighValue, 0.0f, 1.0f, 1.0f);
    const float manualOutputGainDb = clampFinite(outputGainDbValue, -24.0f, 6.0f, 0.0f);
    const float effectStart = std::max(0.0f, static_cast<float>(effectStartSecondsValue));
    const float effectEnd = effectEndSecondsValue < 0.0f
        ? -1.0f : std::max(effectStart, static_cast<float>(effectEndSecondsValue));

    std::ifstream input(inputPath, std::ios::binary);
    if (!input) return errorJson(env, "Không mở được PCM stereo đầu vào");
    const std::string tempPath = outputPath + ".room_rendering";
    std::ofstream firstPass(tempPath, std::ios::binary | std::ios::trunc);
    if (!firstPass) return errorJson(env, "Không tạo được PCM tạm");

    Resources resources;
    IPLContextSettings contextSettings{};
    contextSettings.version = STEAMAUDIO_VERSION;
    contextSettings.logCallback = steamLog;
    contextSettings.simdLevel = IPL_SIMDLEVEL_NEON;
    if (!steamOk(iplContextCreate(&contextSettings, &resources.context), "Tạo context", &steamError)) {
        std::remove(tempPath.c_str());
        return errorJson(env, steamError);
    }

    IPLAudioSettings audioSettings{};
    audioSettings.samplingRate = sampleRate;
    audioSettings.frameSize = frameSize;
    IPLHRTFSettings hrtfSettings{};
    hrtfSettings.type = sofaPath.empty() ? IPL_HRTFTYPE_DEFAULT : IPL_HRTFTYPE_SOFA;
    hrtfSettings.sofaFileName = sofaPath.empty() ? nullptr : sofaPath.c_str();
    hrtfSettings.volume = 1.0f;
    hrtfSettings.normType = IPL_HRTFNORMTYPE_RMS;
    if (!steamOk(iplHRTFCreate(resources.context, &audioSettings, &hrtfSettings, &resources.hrtf),
                 "Nạp HRTF", &steamError)) {
        std::remove(tempPath.c_str());
        return errorJson(env, steamError);
    }

    IPLDirectEffectSettings directSettings{};
    directSettings.numChannels = 1;
    IPLBinauralEffectSettings binauralSettings{};
    binauralSettings.hrtf = resources.hrtf;
    if (!steamOk(iplDirectEffectCreate(resources.context, &audioSettings, &directSettings,
                                        &resources.directEffect), "Tạo direct stereo", &steamError) ||
        !steamOk(iplBinauralEffectCreate(resources.context, &audioSettings, &binauralSettings,
                                          &resources.directBinaural), "Tạo binaural stereo", &steamError) ||
        !allocateBuffer(&resources, 2, frameSize, &resources.inputBuffer, "Cấp input stereo", &steamError) ||
        !allocateBuffer(&resources, 1, frameSize, &resources.objectMono, "Cấp moving mono object", &steamError) ||
        !allocateBuffer(&resources, 1, frameSize, &resources.directBuffer, "Cấp direct mono", &steamError) ||
        !allocateBuffer(&resources, 2, frameSize, &resources.directStereo, "Cấp binaural output", &steamError)) {
        std::remove(tempPath.c_str());
        return errorJson(env, steamError);
    }

    if (reverbWet > 0.0f) {
        bool initialized = false;
        if (room.enabled) initialized = initializeHybridReflections(&resources, room, audioSettings, &steamError);
        if (!initialized) {
            if (resources.reflectionEffect || resources.scene || resources.simulator || resources.source) {
                std::remove(tempPath.c_str());
                return errorJson(env, steamError.empty() ? "Khởi tạo reflection scene thất bại" : steamError);
            }
            if (!initializeParametricFallback(&resources, audioSettings, rt60Low, rt60Mid, rt60High,
                                              &steamError)) {
                std::remove(tempPath.c_str());
                return errorJson(env, steamError);
            }
        }
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
    const float effectiveSpatialBlend = effectiveSpatialMix(spatialBlend);
    const float ambienceBedGain = lerp(
        kObjectAmbienceBedMax, kObjectAmbienceBedMin, effectiveSpatialBlend);
    const auto started = std::chrono::steady_clock::now();

    while (input.good()) {
        std::fill(inputInterleaved.begin(), inputInterleaved.end(), 0.0f);
        clearBuffer(resources.inputBuffer, frameSize);
        clearBuffer(resources.objectMono, frameSize);
        input.read(reinterpret_cast<char*>(inputInterleaved.data()),
                   static_cast<std::streamsize>(inputInterleaved.size() * sizeof(float)));
        const int floatsRead = static_cast<int>(input.gcount() / static_cast<std::streamsize>(sizeof(float)));
        const int framesRead = floatsRead / 2;
        if (framesRead <= 0) break;
        for (int i = 0; i < framesRead; ++i) {
            float left = inputInterleaved[static_cast<size_t>(i) * 2u];
            float right = inputInterleaved[static_cast<size_t>(i) * 2u + 1u];
            if (!std::isfinite(left)) { left = 0.0f; ++nonFinite; }
            if (!std::isfinite(right)) { right = 0.0f; ++nonFinite; }
            resources.inputBuffer.data[0][i] = left;
            resources.inputBuffer.data[1][i] = right;
            resources.objectMono.data[0][i] = 0.5f * (left + right);
            inputStats.add(left, right);
        }

        const float absoluteSeconds = (static_cast<float>(frames) + 0.5f * framesRead) / sampleRate;
        const float localSeconds = std::max(0.0f, absoluteSeconds - effectStart);
        const float window = activeMix(absoluteSeconds, effectStart, effectEnd);
        const Pose pose = calculatePose(trajectory, motionMode, localSeconds, cycleSeconds,
                                        startAzimuth, endAzimuth, startElevation, endElevation,
                                        startDistance, endDistance);
        const float lateral = std::max(-1.0f, std::min(1.0f, pose.direction.x));
        float leftCue = dbToLinear(-kObjectLateralCueDb * lateral);
        float rightCue = dbToLinear(kObjectLateralCueDb * lateral);
        const float cueNormalization = std::sqrt(
            2.0f / std::max(1e-6f, leftCue * leftCue + rightCue * rightCue));
        leftCue *= cueNormalization;
        rightCue *= cueNormalization;
        const float rearAmount = std::max(0.0f, std::min(1.0f, pose.direction.z));
        const float rearDirectScale = 1.0f - 0.18f * rearAmount;
        const float rearWetScale = 1.0f + 0.25f * rearAmount;
        const float directAlpha = onePoleAlpha(
            lerp(kFrontDirectLowpassHz, kRearDirectLowpassHz, rearAmount), sampleRate);
        clearBuffer(resources.directBuffer, frameSize);
        clearBuffer(resources.directStereo, frameSize);
        IPLDirectEffectParams directParams{};
        directParams.flags = static_cast<IPLDirectEffectFlags>(
            IPL_DIRECTEFFECTFLAGS_APPLYDISTANCEATTENUATION |
            IPL_DIRECTEFFECTFLAGS_APPLYAIRABSORPTION |
            IPL_DIRECTEFFECTFLAGS_APPLYDIRECTIVITY);
        directParams.transmissionType = IPL_TRANSMISSIONTYPE_FREQINDEPENDENT;
        directParams.distanceAttenuation = distanceAttenuation(pose.distance, distanceMin, distanceRolloff);
        directParams.airAbsorption[0] = std::exp(-0.0002f * pose.distance * airAbsorption);
        directParams.airAbsorption[1] = std::exp(-0.0020f * pose.distance * airAbsorption);
        directParams.airAbsorption[2] = std::exp(-0.0100f * pose.distance * airAbsorption);
        const IPLVector3 sourceToListener{-pose.direction.x, -pose.direction.y, -pose.direction.z};
        directParams.directivity = directivityGain(sourceToListener, sourceYaw,
                                                   directivityWeight, directivityPower);
        directParams.occlusion = 1.0f;
        directParams.transmission[0] = 1.0f;
        directParams.transmission[1] = 1.0f;
        directParams.transmission[2] = 1.0f;
        iplDirectEffectApply(resources.directEffect, &directParams,
                             &resources.objectMono, &resources.directBuffer);
        IPLBinauralEffectParams binauralParams{};
        binauralParams.direction = pose.direction;
        binauralParams.interpolation = interpolation == 0
            ? IPL_HRTFINTERPOLATION_BILINEAR : IPL_HRTFINTERPOLATION_NEAREST;
        binauralParams.spatialBlend = 1.0f;
        binauralParams.hrtf = resources.hrtf;
        iplBinauralEffectApply(resources.directBinaural, &binauralParams,
                               &resources.directBuffer, &resources.directStereo);

        if (reverbWet > 0.0f) {
            updateHybridReflection(&resources, room, pose, frames, sampleRate);
            renderReflectionBlock(&resources, room, pose, interpolation, framesRead, frameSize,
                                  sampleRate, rt60Low, rt60Mid, rt60High, eqLow, eqMid, eqHigh);
        }
        const float dryGain = reverbWet > 0.0f ? std::sqrt(1.0f - reverbWet) : 1.0f;
        const float wetGain = reverbWet > 0.0f ? std::sqrt(reverbWet) * kReflectionHeadroom : 0.0f;
        for (int i = 0; i < framesRead; ++i) {
            const float originalLeft = resources.inputBuffer.data[0][i];
            const float originalRight = resources.inputBuffer.data[1][i];
            const float sourceSide = 0.5f * (originalLeft - originalRight);
            const float directLeft = rearDirectScale * leftCue * lowpassSample(
                resources.directStereo.data[0][i], directAlpha,
                &resources.directLowpassState[0]);
            const float directRight = rearDirectScale * rightCue * lowpassSample(
                resources.directStereo.data[1][i], directAlpha,
                &resources.directLowpassState[1]);
            const float wetLeft = reverbWet > 0.0f
                ? rearWetScale * resources.reflectionStereo.data[0][i] : 0.0f;
            const float wetRight = reverbWet > 0.0f
                ? rearWetScale * resources.reflectionStereo.data[1][i] : 0.0f;
            const float processedLeft = dryGain * directLeft + wetGain * wetLeft +
                ambienceBedGain * sourceSide;
            const float processedRight = dryGain * directRight + wetGain * wetRight -
                ambienceBedGain * sourceSide;
            const float mixedLeft = (1.0f - effectiveSpatialBlend) * originalLeft +
                effectiveSpatialBlend * processedLeft;
            const float mixedRight = (1.0f - effectiveSpatialBlend) * originalRight +
                effectiveSpatialBlend * processedRight;
            float left = (1.0f - window) * originalLeft + window * mixedLeft;
            float right = (1.0f - window) * originalRight + window * mixedRight;
            if (!std::isfinite(left)) { left = 0.0f; ++nonFinite; }
            if (!std::isfinite(right)) { right = 0.0f; ++nonFinite; }
            peakBefore = std::max(peakBefore, std::max(std::fabs(left), std::fabs(right)));
            if (std::fabs(left) > 1.0f) ++clippedBefore;
            if (std::fabs(right) > 1.0f) ++clippedBefore;
            outputInterleaved[static_cast<size_t>(i) * 2u] = left;
            outputInterleaved[static_cast<size_t>(i) * 2u + 1u] = right;
            outputMainStats.add(left, right);
        }
        firstPass.write(reinterpret_cast<const char*>(outputInterleaved.data()),
                        static_cast<std::streamsize>(framesRead * 2 * sizeof(float)));
        if (!firstPass.good()) {
            std::remove(tempPath.c_str());
            return errorJson(env, "Ghi PCM room-aware tạm thất bại");
        }
        frames += framesRead;
        ++blocks;
    }

    long long tailFrames = 0;
    const float sourceDuration = static_cast<float>(frames) / sampleRate;
    const bool effectReachesFileEnd = effectEnd < 0.0f ||
        effectEnd >= sourceDuration - static_cast<float>(frameSize) / sampleRate;
    if (effectReachesFileEnd && frames > 0 && effectiveSpatialBlend > 0.0f) {
        const Pose tailPose = calculatePose(trajectory, motionMode,
            std::max(0.0f, sourceDuration - effectStart), cycleSeconds,
            startAzimuth, endAzimuth, startElevation, endElevation,
            startDistance, endDistance);
        const float tailLateral = std::max(-1.0f, std::min(1.0f, tailPose.direction.x));
        float tailLeftCue = dbToLinear(-kObjectLateralCueDb * tailLateral);
        float tailRightCue = dbToLinear(kObjectLateralCueDb * tailLateral);
        const float tailCueNormalization = std::sqrt(
            2.0f / std::max(1e-6f, tailLeftCue * tailLeftCue + tailRightCue * tailRightCue));
        tailLeftCue *= tailCueNormalization;
        tailRightCue *= tailCueNormalization;
        const float tailRear = std::max(0.0f, std::min(1.0f, tailPose.direction.z));
        const float tailDirectScale = 1.0f - 0.18f * tailRear;
        const float tailWetScale = 1.0f + 0.25f * tailRear;
        const float tailDirectAlpha = onePoleAlpha(
            lerp(kFrontDirectLowpassHz, kRearDirectLowpassHz, tailRear), sampleRate);
        IPLBinauralEffectParams tailBinaural{};
        tailBinaural.direction = tailPose.direction;
        tailBinaural.interpolation = interpolation == 0
            ? IPL_HRTFINTERPOLATION_BILINEAR : IPL_HRTFINTERPOLATION_NEAREST;
        tailBinaural.spatialBlend = 1.0f;
        tailBinaural.hrtf = resources.hrtf;
        const float dryGain = reverbWet > 0.0f ? std::sqrt(1.0f - reverbWet) : 1.0f;
        const float wetGain = reverbWet > 0.0f ? std::sqrt(reverbWet) * kReflectionHeadroom : 0.0f;
        const int maximumTailBlocks = std::max(32, static_cast<int>(
            std::ceil((std::max({rt60Low, rt60Mid, rt60High, room.duration}) + 1.0f) *
                      sampleRate / frameSize)) + 16);
        for (int block = 0; block < maximumTailBlocks; ++block) {
            clearBuffer(resources.directBuffer, frameSize);
            clearBuffer(resources.directStereo, frameSize);
            bool hasDirect = false;
            if (iplDirectEffectGetTailSize(resources.directEffect) > 0) {
                iplDirectEffectGetTail(resources.directEffect, &resources.directBuffer);
                iplBinauralEffectApply(resources.directBinaural, &tailBinaural,
                                       &resources.directBuffer, &resources.directStereo);
                hasDirect = true;
            } else if (iplBinauralEffectGetTailSize(resources.directBinaural) > 0) {
                iplBinauralEffectGetTail(resources.directBinaural, &resources.directStereo);
                hasDirect = true;
            }
            const bool hasReflection = reverbWet > 0.0f && renderReflectionTail(
                &resources, room, tailPose, interpolation, frameSize, sampleRate,
                rt60Low, rt60Mid, rt60High, eqLow, eqMid, eqHigh);
            if (!hasDirect && !hasReflection) break;
            for (int i = 0; i < frameSize; ++i) {
                float left = 0.0f;
                float right = 0.0f;
                if (hasDirect) {
                    left += dryGain * tailDirectScale * tailLeftCue * lowpassSample(
                        resources.directStereo.data[0][i], tailDirectAlpha,
                        &resources.directLowpassState[0]);
                    right += dryGain * tailDirectScale * tailRightCue * lowpassSample(
                        resources.directStereo.data[1][i], tailDirectAlpha,
                        &resources.directLowpassState[1]);
                }
                if (hasReflection) {
                    left += wetGain * tailWetScale * resources.reflectionStereo.data[0][i];
                    right += wetGain * tailWetScale * resources.reflectionStereo.data[1][i];
                }
                left *= effectiveSpatialBlend;
                right *= effectiveSpatialBlend;
                if (!std::isfinite(left)) { left = 0.0f; ++nonFinite; }
                if (!std::isfinite(right)) { right = 0.0f; ++nonFinite; }
                peakBefore = std::max(peakBefore, std::max(std::fabs(left), std::fabs(right)));
                if (std::fabs(left) > 1.0f) ++clippedBefore;
                if (std::fabs(right) > 1.0f) ++clippedBefore;
                outputInterleaved[static_cast<size_t>(i) * 2u] = left;
                outputInterleaved[static_cast<size_t>(i) * 2u + 1u] = right;
            }
            firstPass.write(reinterpret_cast<const char*>(outputInterleaved.data()),
                            static_cast<std::streamsize>(frameSize * 2 * sizeof(float)));
            if (!firstPass.good()) {
                std::remove(tempPath.c_str());
                return errorJson(env, "Ghi đuôi room-aware thất bại");
            }
            tailFrames += frameSize;
        }
    }
    firstPass.flush();
    if (!firstPass.good()) {
        std::remove(tempPath.c_str());
        return errorJson(env, "Flush PCM room-aware tạm thất bại");
    }
    firstPass.close();

    if (frames <= 0) {
        std::remove(tempPath.c_str());
        return errorJson(env, "PCM đầu vào không có mẫu âm thanh");
    }
    const double inputRmsDbfs = dbfs(inputStats.rmsCombined());
    const double outputMainRmsDbfs = dbfs(outputMainStats.rmsCombined());
    const bool exactBypass = effectiveSpatialBlend <= 1e-6f &&
        std::fabs(manualOutputGainDb) <= 1e-6f;
    float automaticMakeupGainDb = 0.0f;
    if (!exactBypass && inputStats.rmsCombined() > 1e-9 && outputMainStats.rmsCombined() > 1e-9) {
        const float maxMakeup = (trajectory == 8 || std::max(startDistance, endDistance) > 4.0f)
            ? 3.0f : 6.0f;
        automaticMakeupGainDb = clampFinite(
            static_cast<float>(inputRmsDbfs - outputMainRmsDbfs), -6.0f, maxMakeup, 0.0f);
    }
    const float requestedGain = exactBypass
        ? 1.0f : dbToLinear(automaticMakeupGainDb + manualOutputGainDb);
    const float limiterGain = (!exactBypass && peakBefore > 0.0f &&
                               peakBefore * requestedGain > kTargetPeak)
        ? kTargetPeak / (peakBefore * requestedGain) : 1.0f;
    const float totalGain = requestedGain * limiterGain;
    const float limiterGainDb = 20.0f * std::log10(std::max(limiterGain, 1e-12f));
    const float appliedGainDb = 20.0f * std::log10(std::max(totalGain, 1e-12f));

    std::ifstream secondInput(tempPath, std::ios::binary);
    std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
    if (!secondInput || !output) {
        std::remove(tempPath.c_str());
        std::remove(outputPath.c_str());
        return errorJson(env, "Không mở được lượt loudness/peak gain");
    }
    StereoStats outputStats;
    std::vector<float> gainBuffer(static_cast<size_t>(frameSize) * 2u, 0.0f);
    long long outputSamples = 0;
    while (secondInput.good()) {
        secondInput.read(reinterpret_cast<char*>(gainBuffer.data()),
                         static_cast<std::streamsize>(gainBuffer.size() * sizeof(float)));
        const size_t count = static_cast<size_t>(secondInput.gcount() /
                                                  static_cast<std::streamsize>(sizeof(float)));
        if (count == 0u) break;
        for (size_t i = 0; i < count; ++i) {
            float sample = gainBuffer[i] * totalGain;
            if (!std::isfinite(sample)) sample = 0.0f;
            gainBuffer[i] = sample;
        }
        for (size_t i = 0; i + 1u < count; i += 2u) outputStats.add(gainBuffer[i], gainBuffer[i + 1u]);
        output.write(reinterpret_cast<const char*>(gainBuffer.data()),
                     static_cast<std::streamsize>(count * sizeof(float)));
        if (!output.good()) {
            secondInput.close();
            output.close();
            std::remove(tempPath.c_str());
            std::remove(outputPath.c_str());
            return errorJson(env, "Ghi PCM room-aware đầu ra thất bại");
        }
        outputSamples += static_cast<long long>(count);
    }
    output.flush();
    const bool outputOk = output.good();
    secondInput.close();
    output.close();
    std::remove(tempPath.c_str());
    if (!outputOk || outputSamples <= 0) {
        std::remove(outputPath.c_str());
        return errorJson(env, "Hoàn tất PCM room-aware đầu ra thất bại");
    }

    const auto finished = std::chrono::steady_clock::now();
    const long long renderMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        finished - started).count();
    const double outputMainAfterDbfs = outputMainRmsDbfs + appliedGainDb;
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
         << ",\"input_channels\":2,\"output_channels\":2"
         << ",\"stereo_mode\":\"moving_mono_object_with_side_bed\""
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
         << ",\"output_main_rms_before_gain_dbfs\":" << outputMainRmsDbfs
         << ",\"output_main_rms_after_gain_dbfs\":" << outputMainAfterDbfs
         << ",\"output_total_rms_dbfs\":" << dbfs(outputStats.rmsCombined())
         << ",\"output_rms_left_dbfs\":" << dbfs(outputStats.rmsLeft())
         << ",\"output_rms_right_dbfs\":" << dbfs(outputStats.rmsRight())
         << ",\"output_correlation\":" << outputStats.correlation()
         << ",\"output_balance_db\":" << outputStats.balanceDb()
         << ",\"automatic_makeup_gain_db\":" << automaticMakeupGainDb
         << ",\"manual_output_gain_db\":" << manualOutputGainDb
         << ",\"peak_limiter_gain_db\":" << limiterGainDb
         << ",\"applied_gain_db\":" << appliedGainDb
         << ",\"estimated_loudness_delta_db\":" << (outputMainAfterDbfs - inputRmsDbfs)
         << ",\"peak_ceiling_dbfs\":" << kPeakCeilingDbfs
         << ",\"nonfinite_samples\":" << nonFinite
         << ",\"clipped_samples_before_gain\":" << clippedBefore
         << ",\"hrtf_type\":\"" << (sofaPath.empty() ? "built_in" : "custom_sofa") << "\""
         << ",\"steam_audio_version\":\"4.8.1\""
         << ",\"reflection_mode\":\"" << reflectionModeName(resources.reflectionMode) << "\""
         << ",\"room_id\":" << room.roomId
         << ",\"reflection_updates\":" << resources.reflectionUpdates
         << ",\"reflection_simulation_ms\":" << resources.reflectionSimulationMs
         << ",\"reflection_source_clamps\":" << resources.reflectionSourceClamps
         << ",\"reflection_rays\":" << room.rays
         << ",\"reflection_bounces\":" << room.bounces
         << ",\"reflection_order\":" << room.order
         << ",\"reflection_duration_seconds\":" << room.duration
         << ",\"reflection_headroom_db\":" << kReflectionHeadroomDb
         << ",\"effective_spatial_blend\":" << effectiveSpatialBlend
         << ",\"object_ambience_bed_gain\":" << ambienceBedGain
         << ",\"object_lateral_cue_db\":" << kObjectLateralCueDb
         << ",\"direct_rear_lowpass_hz\":" << kRearDirectLowpassHz
         << ",\"reflection_lowpass_hz\":" << kReflectionLowpassHz
         << ",\"reflection_gate_floor_dbfs\":" << kReflectionGateFloorDbfs
         << ",\"true_effect_mix\":true}"
         ;
    return env->NewStringUTF(json.str().c_str());
}
