from pathlib import Path

def load(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")

def save(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")

def replace_once(path: str, old: str, new: str) -> None:
    text = load(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one occurrence, found {count}: {old[:120]!r}")
    save(path, text.replace(old, new, 1))

room_path = "app/src/main/java/com/aistudio/mediatool/core/spatial/SpatialRoomPreset.kt"
room = load(room_path)
for old, new in {
    "maxReflectionWet = 0.08f,": "maxReflectionWet = 0.12f,",
    "maxReflectionWet = 0.15f,": "maxReflectionWet = 0.24f,",
    "maxReflectionWet = 0.22f,": "maxReflectionWet = 0.34f,",
    "maxReflectionWet = 0.28f,": "maxReflectionWet = 0.43f,",
    "maxReflectionWet = 0.26f,": "maxReflectionWet = 0.42f,",
    "maxReflectionWet = 0.02f,": "maxReflectionWet = 0.03f,",
}.items():
    if room.count(old) != 1:
        raise RuntimeError(f"{room_path}: expected one occurrence of {old!r}")
    room = room.replace(old, new, 1)
save(room_path, room)

config_path = "app/src/main/java/com/aistudio/mediatool/core/spatial/SpatialAudioConfig.kt"
replace_once(config_path, "private const val REFLECTION_CURVE_EXPONENT = 1.6f",
             "private const val REFLECTION_CURVE_EXPONENT = 1.25f")
replace_once(config_path, '"room_model_version" to 1,', '"room_model_version" to 2,')

bridge_path = "app/src/main/java/com/aistudio/mediatool/core/spatial/SteamAudioBridge.kt"
replace_once(
    bridge_path,
    "                reverbWet = value.reverbWet,\n"
    "                reverbRt60Low = value.reverbRt60Low,",
    "                reverbWet = value.reverbWet,\n"
    "                roomPresence = value.friendlyReflectionPosition(),\n"
    "                reverbRt60Low = value.reverbRt60Low,",
)
replace_once(
    bridge_path,
    "        reverbWet: Float,\n"
    "        reverbRt60Low: Float,",
    "        reverbWet: Float,\n"
    "        roomPresence: Float,\n"
    "        reverbRt60Low: Float,",
)
replace_once(
    bridge_path,
    "            reflectionHeadroomDb = json.float(\"reflection_headroom_db\", -4.5f),\n"
    "            effectiveSpatialBlend = json.float(\"effective_spatial_blend\", value.spatialBlend),",
    "            reflectionHeadroomDb = json.float(\"reflection_headroom_db\", -4.5f),\n"
    "            roomPresence = json.float(\"room_presence\", value.friendlyReflectionPosition()),\n"
    "            earlyReflectionGain = json.float(\"early_reflection_gain\"),\n"
    "            earlyReflectionLowpassHz = json.float(\"early_reflection_lowpass_hz\", 7_200f),\n"
    "            frontPresenceGain = json.float(\"front_presence_gain\", 0.32f),\n"
    "            rearNotchDepth = json.float(\"rear_notch_depth\", 0.62f),\n"
    "            rearNotchLowHz = json.float(\"rear_notch_low_hz\", 4_200f),\n"
    "            rearNotchHighHz = json.float(\"rear_notch_high_hz\", 9_500f),\n"
    "            rearWetBoost = json.float(\"rear_wet_boost\", 0.55f),\n"
    "            effectiveSpatialBlend = json.float(\"effective_spatial_blend\", value.spatialBlend),",
)
replace_once(
    bridge_path,
    "    val reflectionHeadroomDb: Float,\n"
    "    val effectiveSpatialBlend: Float,",
    "    val reflectionHeadroomDb: Float,\n"
    "    val roomPresence: Float,\n"
    "    val earlyReflectionGain: Float,\n"
    "    val earlyReflectionLowpassHz: Float,\n"
    "    val frontPresenceGain: Float,\n"
    "    val rearNotchDepth: Float,\n"
    "    val rearNotchLowHz: Float,\n"
    "    val rearNotchHighHz: Float,\n"
    "    val rearWetBoost: Float,\n"
    "    val effectiveSpatialBlend: Float,",
)
replace_once(
    bridge_path,
    "        \"reflection_headroom_db\" to reflectionHeadroomDb,\n"
    "        \"effective_spatial_blend\" to effectiveSpatialBlend,",
    "        \"reflection_headroom_db\" to reflectionHeadroomDb,\n"
    "        \"room_presence\" to roomPresence,\n"
    "        \"early_reflection_gain\" to earlyReflectionGain,\n"
    "        \"early_reflection_lowpass_hz\" to earlyReflectionLowpassHz,\n"
    "        \"front_presence_gain\" to frontPresenceGain,\n"
    "        \"rear_notch_depth\" to rearNotchDepth,\n"
    "        \"rear_notch_low_hz\" to rearNotchLowHz,\n"
    "        \"rear_notch_high_hz\" to rearNotchHighHz,\n"
    "        \"rear_wet_boost\" to rearWetBoost,\n"
    "        \"effective_spatial_blend\" to effectiveSpatialBlend,",
)
replace_once(
    bridge_path,
    '            directRearLowpassHz = json.float("direct_rear_lowpass_hz", 6_800f),',
    '            directRearLowpassHz = json.float("direct_rear_lowpass_hz", 9_200f),',
)

native_path = "app/src/main/cpp/room_aware_spatial_jni.cpp"
replace_once(
    native_path,
    "constexpr float kFrontDirectLowpassHz = 18000.0f;\n"
    "constexpr float kRearDirectLowpassHz = 6800.0f;\n"
    "constexpr float kReflectionLowpassHz = 9000.0f;\n"
    "constexpr float kReflectionGateFloorDbfs = -62.0f;\n"
    "constexpr float kReflectionGateOpenDbfs = -42.0f;",
    "constexpr float kFrontDirectLowpassHz = 19000.0f;\n"
    "constexpr float kRearDirectLowpassHz = 9200.0f;\n"
    "constexpr float kFrontPresenceHz = 2800.0f;\n"
    "constexpr float kFrontPresenceGain = 0.32f;\n"
    "constexpr float kRearNotchLowHz = 4200.0f;\n"
    "constexpr float kRearNotchHighHz = 9500.0f;\n"
    "constexpr float kRearNotchDepth = 0.62f;\n"
    "constexpr float kRearDirectAttenuation = 0.24f;\n"
    "constexpr float kRearWetBoost = 0.55f;\n"
    "constexpr float kReflectionLowpassHz = 9000.0f;\n"
    "constexpr float kEarlyReflectionLowpassHz = 7200.0f;\n"
    "constexpr float kEarlyReflectionMaxGain = 0.42f;\n"
    "constexpr float kEarlyReflectionMinDelaySeconds = 0.008f;\n"
    "constexpr float kEarlyReflectionMaxDelaySeconds = 0.060f;\n"
    "constexpr float kSpeedOfSoundMps = 343.0f;\n"
    "constexpr float kReflectionGateFloorDbfs = -62.0f;\n"
    "constexpr float kReflectionGateOpenDbfs = -42.0f;",
)
replace_once(
    native_path,
    "    IPLAudioBuffer reflectionStereo{};\n"
    "    bool sourceAdded = false;",
    "    IPLAudioBuffer reflectionStereo{};\n"
    "    IPLAudioBuffer earlyReflectionStereo{};\n"
    "    bool sourceAdded = false;",
)
replace_once(
    native_path,
    "    float directLowpassState[2] = {0.0f, 0.0f};\n"
    "    float reflectionSendLowpassState = 0.0f;\n"
    "    float reflectionWetLowpassState[2] = {0.0f, 0.0f};",
    "    float directLowpassState[2] = {0.0f, 0.0f};\n"
    "    float frontPresenceLowpassState[2] = {0.0f, 0.0f};\n"
    "    float rearNotchLowpassState[2] = {0.0f, 0.0f};\n"
    "    float rearNotchHighpassState[2] = {0.0f, 0.0f};\n"
    "    float reflectionSendLowpassState = 0.0f;\n"
    "    float reflectionWetLowpassState[2] = {0.0f, 0.0f};\n"
    "    float earlyReflectionLowpassState[2] = {0.0f, 0.0f};\n"
    "    std::vector<float> earlyReflectionDelay;\n"
    "    size_t earlyReflectionWriteIndex = 0u;",
)
replace_once(
    native_path,
    "            freeBuffer(reflectionStereo);\n"
    "        }",
    "            freeBuffer(reflectionStereo);\n"
    "            freeBuffer(earlyReflectionStereo);\n"
    "        }",
)

helpers = r'''
float shapeFrontRearSample(Resources* resources, int channel, float sample,
                           float frontAmount, float rearAmount, int sampleRate) {
    const float presenceLow = lowpassSample(
        sample, onePoleAlpha(kFrontPresenceHz, sampleRate),
        &resources->frontPresenceLowpassState[channel]);
    const float frontHigh = sample - presenceLow;
    const float notchLow = lowpassSample(
        sample, onePoleAlpha(kRearNotchLowHz, sampleRate),
        &resources->rearNotchLowpassState[channel]);
    const float notchHigh = lowpassSample(
        sample, onePoleAlpha(kRearNotchHighHz, sampleRate),
        &resources->rearNotchHighpassState[channel]);
    const float rearBand = notchHigh - notchLow;
    float shaped = sample + kFrontPresenceGain * frontAmount * frontHigh -
        kRearNotchDepth * rearAmount * rearBand;
    shaped = lowpassSample(
        shaped,
        onePoleAlpha(lerp(kFrontDirectLowpassHz, kRearDirectLowpassHz, rearAmount), sampleRate),
        &resources->directLowpassState[channel]);
    return shaped * (1.0f + 0.08f * frontAmount) *
        (1.0f - kRearDirectAttenuation * rearAmount);
}

float roomMidReflectivity(const RoomSpec& room) {
    const float absorption = (room.walls.mid + room.floor.mid + room.ceiling.mid) / 3.0f;
    return std::max(0.10f, std::min(0.98f, 1.0f - absorption));
}

int earlyDelaySamples(float seconds, int sampleRate, size_t bufferSize) {
    if (bufferSize < 2u) return 1;
    const int requested = static_cast<int>(std::lround(seconds * sampleRate));
    return std::max(1, std::min(static_cast<int>(bufferSize) - 1, requested));
}

float readEarlyDelay(const Resources* resources, int delaySamples) {
    const size_t size = resources->earlyReflectionDelay.size();
    if (size < 2u) return 0.0f;
    const size_t delay = static_cast<size_t>(std::max(1, delaySamples));
    const size_t index = (resources->earlyReflectionWriteIndex + size - delay % size) % size;
    return resources->earlyReflectionDelay[index];
}

float earlyReflectionGain(const RoomSpec& room, float roomPresence) {
    if (!room.enabled || roomPresence <= 1e-6f) return 0.0f;
    const float reflectivityScale = lerp(0.58f, 1.0f, roomMidReflectivity(room));
    return kEarlyReflectionMaxGain * smoothstep(roomPresence) * reflectivityScale;
}

bool renderEarlyReflectionBlock(Resources* resources, const RoomSpec& room, const Pose& pose,
                                int framesRead, int frameSize, int sampleRate,
                                float roomPresence) {
    clearBuffer(resources->earlyReflectionStereo, frameSize);
    const float gain = earlyReflectionGain(room, roomPresence);
    if (gain <= 1e-6f || resources->earlyReflectionDelay.size() < 2u) return false;

    const float sideBase = std::max(kEarlyReflectionMinDelaySeconds,
        std::min(kEarlyReflectionMaxDelaySeconds, 0.78f * room.width / kSpeedOfSoundMps));
    const float depthBase = std::max(kEarlyReflectionMinDelaySeconds,
        std::min(kEarlyReflectionMaxDelaySeconds, 0.92f * room.depth / kSpeedOfSoundMps));
    const float ceilingBase = std::max(kEarlyReflectionMinDelaySeconds,
        std::min(0.035f, 1.15f * room.height / kSpeedOfSoundMps));
    const int leftDelay = earlyDelaySamples(
        sideBase * (1.0f + 0.16f * pose.direction.x), sampleRate,
        resources->earlyReflectionDelay.size());
    const int rightDelay = earlyDelaySamples(
        sideBase * (1.0f - 0.16f * pose.direction.x), sampleRate,
        resources->earlyReflectionDelay.size());
    const int depthDelay = earlyDelaySamples(
        depthBase * (1.0f - 0.10f * pose.direction.z), sampleRate,
        resources->earlyReflectionDelay.size());
    const int ceilingDelay = earlyDelaySamples(
        ceilingBase * (1.0f - 0.08f * pose.direction.y), sampleRate,
        resources->earlyReflectionDelay.size());
    const float alpha = onePoleAlpha(kEarlyReflectionLowpassHz, sampleRate);
    bool active = false;

    for (int i = 0; i < framesRead; ++i) {
        resources->earlyReflectionDelay[resources->earlyReflectionWriteIndex] =
            resources->objectMono.data[0][i];
        const float leftWall = readEarlyDelay(resources, leftDelay);
        const float rightWall = readEarlyDelay(resources, rightDelay);
        const float depthWall = readEarlyDelay(resources, depthDelay);
        const float ceiling = readEarlyDelay(resources, ceilingDelay);
        const float rawLeft = gain * (
            0.52f * leftWall + 0.12f * rightWall +
            0.20f * depthWall + 0.10f * ceiling);
        const float rawRight = gain * (
            0.12f * leftWall + 0.52f * rightWall +
            0.20f * depthWall + 0.10f * ceiling);
        const float left = lowpassSample(
            rawLeft, alpha, &resources->earlyReflectionLowpassState[0]);
        const float right = lowpassSample(
            rawRight, alpha, &resources->earlyReflectionLowpassState[1]);
        resources->earlyReflectionStereo.data[0][i] = left;
        resources->earlyReflectionStereo.data[1][i] = right;
        active = active || std::fabs(left) > 1e-7f || std::fabs(right) > 1e-7f;
        resources->earlyReflectionWriteIndex =
            (resources->earlyReflectionWriteIndex + 1u) % resources->earlyReflectionDelay.size();
    }
    return active;
}
'''
replace_once(
    native_path,
    "void filterReflectionStereo(Resources* resources, int frameSize, int sampleRate) {\n"
    "    const float alpha = onePoleAlpha(kReflectionLowpassHz, sampleRate);\n"
    "    for (int channel = 0; channel < 2; ++channel) {\n"
    "        for (int i = 0; i < frameSize; ++i) {\n"
    "            resources->reflectionStereo.data[channel][i] = lowpassSample(\n"
    "                resources->reflectionStereo.data[channel][i], alpha,\n"
    "                &resources->reflectionWetLowpassState[channel]);\n"
    "        }\n"
    "    }\n"
    "}\n",
    "void filterReflectionStereo(Resources* resources, int frameSize, int sampleRate) {\n"
    "    const float alpha = onePoleAlpha(kReflectionLowpassHz, sampleRate);\n"
    "    for (int channel = 0; channel < 2; ++channel) {\n"
    "        for (int i = 0; i < frameSize; ++i) {\n"
    "            resources->reflectionStereo.data[channel][i] = lowpassSample(\n"
    "                resources->reflectionStereo.data[channel][i], alpha,\n"
    "                &resources->reflectionWetLowpassState[channel]);\n"
    "        }\n"
    "    }\n"
    "}\n\n" + helpers + "\n",
)
replace_once(
    native_path,
    "    jfloat sourceYawDegValue,\n"
    "    jfloat reverbWetValue,\n"
    "    jfloat reverbRt60LowValue,",
    "    jfloat sourceYawDegValue,\n"
    "    jfloat reverbWetValue,\n"
    "    jfloat roomPresenceValue,\n"
    "    jfloat reverbRt60LowValue,",
)
replace_once(
    native_path,
    "    const float reverbWet = clampFinite(reverbWetValue, 0.0f, 0.49f, 0.12f);\n"
    "    const float rt60Low = clampFinite(reverbRt60LowValue, 0.1f, 10.0f, 0.7f);",
    "    const float reverbWet = clampFinite(reverbWetValue, 0.0f, 0.60f, 0.12f);\n"
    "    const float roomPresence = clampFinite(roomPresenceValue, 0.0f, 1.0f, 0.5f);\n"
    "    const float rt60Low = clampFinite(reverbRt60LowValue, 0.1f, 10.0f, 0.7f);",
)
replace_once(
    native_path,
    "        !allocateBuffer(&resources, 1, frameSize, &resources.directBuffer, \"Cấp direct mono\", &steamError) ||\n"
    "        !allocateBuffer(&resources, 2, frameSize, &resources.directStereo, \"Cấp binaural output\", &steamError)) {",
    "        !allocateBuffer(&resources, 1, frameSize, &resources.directBuffer, \"Cấp direct mono\", &steamError) ||\n"
    "        !allocateBuffer(&resources, 2, frameSize, &resources.directStereo, \"Cấp binaural output\", &steamError) ||\n"
    "        !allocateBuffer(&resources, 2, frameSize, &resources.earlyReflectionStereo,\n"
    "                        \"Cấp early reflection stereo\", &steamError)) {",
)
replace_once(
    native_path,
    "    if (reverbWet > 0.0f) {\n"
    "        bool initialized = false;",
    "    resources.earlyReflectionDelay.assign(\n"
    "        static_cast<size_t>(std::ceil(kEarlyReflectionMaxDelaySeconds * sampleRate)) + 2u,\n"
    "        0.0f);\n\n"
    "    if (reverbWet > 0.0f) {\n"
    "        bool initialized = false;",
)
replace_once(
    native_path,
    "        const float rearAmount = std::max(0.0f, std::min(1.0f, pose.direction.z));\n"
    "        const float rearDirectScale = 1.0f - 0.18f * rearAmount;\n"
    "        const float rearWetScale = 1.0f + 0.25f * rearAmount;\n"
    "        const float directAlpha = onePoleAlpha(\n"
    "            lerp(kFrontDirectLowpassHz, kRearDirectLowpassHz, rearAmount), sampleRate);",
    "        const float frontAmount = std::max(0.0f, std::min(1.0f, -pose.direction.z));\n"
    "        const float rearAmount = std::max(0.0f, std::min(1.0f, pose.direction.z));\n"
    "        const float rearWetScale = 1.0f + kRearWetBoost * rearAmount;\n"
    "        const float earlyRearScale = 1.0f + 0.22f * rearAmount;",
)
replace_once(
    native_path,
    "        if (reverbWet > 0.0f) {\n"
    "            updateHybridReflection(&resources, room, pose, frames, sampleRate);\n"
    "            renderReflectionBlock(&resources, room, pose, interpolation, framesRead, frameSize,\n"
    "                                  sampleRate, rt60Low, rt60Mid, rt60High, eqLow, eqMid, eqHigh);\n"
    "        }\n"
    "        const float dryGain = reverbWet > 0.0f ? std::sqrt(1.0f - reverbWet) : 1.0f;",
    "        if (reverbWet > 0.0f) {\n"
    "            updateHybridReflection(&resources, room, pose, frames, sampleRate);\n"
    "            renderReflectionBlock(&resources, room, pose, interpolation, framesRead, frameSize,\n"
    "                                  sampleRate, rt60Low, rt60Mid, rt60High, eqLow, eqMid, eqHigh);\n"
    "        }\n"
    "        renderEarlyReflectionBlock(\n"
    "            &resources, room, pose, framesRead, frameSize, sampleRate, roomPresence);\n"
    "        const float dryGain = reverbWet > 0.0f ? std::sqrt(1.0f - reverbWet) : 1.0f;",
)
replace_once(
    native_path,
    "            const float directLeft = rearDirectScale * leftCue * lowpassSample(\n"
    "                resources.directStereo.data[0][i], directAlpha,\n"
    "                &resources.directLowpassState[0]);\n"
    "            const float directRight = rearDirectScale * rightCue * lowpassSample(\n"
    "                resources.directStereo.data[1][i], directAlpha,\n"
    "                &resources.directLowpassState[1]);",
    "            const float directLeft = leftCue * shapeFrontRearSample(\n"
    "                &resources, 0, resources.directStereo.data[0][i],\n"
    "                frontAmount, rearAmount, sampleRate);\n"
    "            const float directRight = rightCue * shapeFrontRearSample(\n"
    "                &resources, 1, resources.directStereo.data[1][i],\n"
    "                frontAmount, rearAmount, sampleRate);",
)
replace_once(
    native_path,
    "            const float processedLeft = dryGain * directLeft + wetGain * wetLeft +\n"
    "                ambienceBedGain * sourceSide;\n"
    "            const float processedRight = dryGain * directRight + wetGain * wetRight -\n"
    "                ambienceBedGain * sourceSide;",
    "            const float processedLeft = dryGain * directLeft + wetGain * wetLeft +\n"
    "                earlyRearScale * resources.earlyReflectionStereo.data[0][i] +\n"
    "                ambienceBedGain * sourceSide;\n"
    "            const float processedRight = dryGain * directRight + wetGain * wetRight +\n"
    "                earlyRearScale * resources.earlyReflectionStereo.data[1][i] -\n"
    "                ambienceBedGain * sourceSide;",
)
replace_once(
    native_path,
    "        const float tailRear = std::max(0.0f, std::min(1.0f, tailPose.direction.z));\n"
    "        const float tailDirectScale = 1.0f - 0.18f * tailRear;\n"
    "        const float tailWetScale = 1.0f + 0.25f * tailRear;\n"
    "        const float tailDirectAlpha = onePoleAlpha(\n"
    "            lerp(kFrontDirectLowpassHz, kRearDirectLowpassHz, tailRear), sampleRate);",
    "        const float tailFront = std::max(0.0f, std::min(1.0f, -tailPose.direction.z));\n"
    "        const float tailRear = std::max(0.0f, std::min(1.0f, tailPose.direction.z));\n"
    "        const float tailWetScale = 1.0f + kRearWetBoost * tailRear;\n"
    "        const float tailEarlyScale = 1.0f + 0.22f * tailRear;",
)
replace_once(
    native_path,
    "        for (int block = 0; block < maximumTailBlocks; ++block) {\n"
    "            clearBuffer(resources.directBuffer, frameSize);\n"
    "            clearBuffer(resources.directStereo, frameSize);",
    "        for (int block = 0; block < maximumTailBlocks; ++block) {\n"
    "            clearBuffer(resources.objectMono, frameSize);\n"
    "            clearBuffer(resources.directBuffer, frameSize);\n"
    "            clearBuffer(resources.directStereo, frameSize);",
)
replace_once(
    native_path,
    "            const bool hasReflection = reverbWet > 0.0f && renderReflectionTail(\n"
    "                &resources, room, tailPose, interpolation, frameSize, sampleRate,\n"
    "                rt60Low, rt60Mid, rt60High, eqLow, eqMid, eqHigh);\n"
    "            if (!hasDirect && !hasReflection) break;",
    "            const bool hasReflection = reverbWet > 0.0f && renderReflectionTail(\n"
    "                &resources, room, tailPose, interpolation, frameSize, sampleRate,\n"
    "                rt60Low, rt60Mid, rt60High, eqLow, eqMid, eqHigh);\n"
    "            const bool hasEarlyReflection = renderEarlyReflectionBlock(\n"
    "                &resources, room, tailPose, frameSize, frameSize, sampleRate, roomPresence);\n"
    "            if (!hasDirect && !hasReflection && !hasEarlyReflection) break;",
)
replace_once(
    native_path,
    "                if (hasDirect) {\n"
    "                    left += dryGain * tailDirectScale * tailLeftCue * lowpassSample(\n"
    "                        resources.directStereo.data[0][i], tailDirectAlpha,\n"
    "                        &resources.directLowpassState[0]);\n"
    "                    right += dryGain * tailDirectScale * tailRightCue * lowpassSample(\n"
    "                        resources.directStereo.data[1][i], tailDirectAlpha,\n"
    "                        &resources.directLowpassState[1]);\n"
    "                }",
    "                if (hasDirect) {\n"
    "                    left += dryGain * tailLeftCue * shapeFrontRearSample(\n"
    "                        &resources, 0, resources.directStereo.data[0][i],\n"
    "                        tailFront, tailRear, sampleRate);\n"
    "                    right += dryGain * tailRightCue * shapeFrontRearSample(\n"
    "                        &resources, 1, resources.directStereo.data[1][i],\n"
    "                        tailFront, tailRear, sampleRate);\n"
    "                }",
)
replace_once(
    native_path,
    "                if (hasReflection) {\n"
    "                    left += wetGain * tailWetScale * resources.reflectionStereo.data[0][i];\n"
    "                    right += wetGain * tailWetScale * resources.reflectionStereo.data[1][i];\n"
    "                }\n"
    "                left *= effectiveSpatialBlend;",
    "                if (hasReflection) {\n"
    "                    left += wetGain * tailWetScale * resources.reflectionStereo.data[0][i];\n"
    "                    right += wetGain * tailWetScale * resources.reflectionStereo.data[1][i];\n"
    "                }\n"
    "                if (hasEarlyReflection) {\n"
    "                    left += tailEarlyScale * resources.earlyReflectionStereo.data[0][i];\n"
    "                    right += tailEarlyScale * resources.earlyReflectionStereo.data[1][i];\n"
    "                }\n"
    "                left *= effectiveSpatialBlend;",
)
replace_once(
    native_path,
    "         << \",\\\"reflection_headroom_db\\\":\" << kReflectionHeadroomDb\n"
    "         << \",\\\"effective_spatial_blend\\\":\" << effectiveSpatialBlend",
    "         << \",\\\"reflection_headroom_db\\\":\" << kReflectionHeadroomDb\n"
    "         << \",\\\"room_presence\\\":\" << roomPresence\n"
    "         << \",\\\"early_reflection_gain\\\":\" << earlyReflectionGain(room, roomPresence)\n"
    "         << \",\\\"early_reflection_lowpass_hz\\\":\" << kEarlyReflectionLowpassHz\n"
    "         << \",\\\"front_presence_gain\\\":\" << kFrontPresenceGain\n"
    "         << \",\\\"rear_notch_depth\\\":\" << kRearNotchDepth\n"
    "         << \",\\\"rear_notch_low_hz\\\":\" << kRearNotchLowHz\n"
    "         << \",\\\"rear_notch_high_hz\\\":\" << kRearNotchHighHz\n"
    "         << \",\\\"rear_wet_boost\\\":\" << kRearWetBoost\n"
    "         << \",\\\"effective_spatial_blend\\\":\" << effectiveSpatialBlend",
)

test_path = Path("app/src/test/java/com/aistudio/mediatool/core/spatial/SpatialRoomPresenceV3Test.kt")
test_path.write_text(
    '''package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialRoomPresenceV3Test {
    @Test
    fun maximumRoomPresenceIsAudibleButBounded() {
        val minimums = mapOf(
            SpatialRoomPreset.DRY to 0.12f,
            SpatialRoomPreset.STUDIO to 0.24f,
            SpatialRoomPreset.LISTENING_ROOM to 0.34f,
            SpatialRoomPreset.THEATER to 0.43f,
            SpatialRoomPreset.WAREHOUSE to 0.42f,
            SpatialRoomPreset.OUTDOOR to 0.03f,
        )
        minimums.forEach { (preset, expected) ->
            val value = SpatialAudioConfig()
                .withRoomPreset(preset)
                .withFriendlyReflection(1f)
            assertEquals(expected, value.reverbWet, 1e-6f)
            assertTrue(value.reverbWet < 0.5f)
        }
    }

    @Test
    fun midpointNoLongerFeelsAlmostDry() {
        val value = SpatialAudioConfig()
            .withRoomPreset(SpatialRoomPreset.LISTENING_ROOM)
            .withFriendlyReflection(0.5f)
        val ratio = value.reverbWet / SpatialRoomPreset.LISTENING_ROOM.acoustics.maxReflectionWet
        assertTrue(ratio > 0.40f)
        assertTrue(ratio < 0.50f)
        assertEquals(0.5f, value.friendlyReflectionPosition(), 1e-4f)
    }
}
''',
    encoding="utf-8",
)

print("Spatial room presence and front/back v3 patch applied")
