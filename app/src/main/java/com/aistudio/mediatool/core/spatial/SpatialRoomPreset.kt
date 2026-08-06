package com.aistudio.mediatool.core.spatial

import kotlin.math.max

private const val SABINE_CONSTANT = 0.161f
private const val MIN_RT60_SECONDS = 0.10f
private const val MAX_RT60_SECONDS = 6.0f

/** Three-band acoustic values centered around Steam Audio's low, mid and high bands. */
data class SpatialAcousticBands(
    val low: Float,
    val mid: Float,
    val high: Float,
) {
    fun map(transform: (Float) -> Float): SpatialAcousticBands = SpatialAcousticBands(
        low = transform(low),
        mid = transform(mid),
        high = transform(high),
    )

    fun maxValue(): Float = max(low, max(mid, high))
}

data class SpatialRoomDimensions(
    val widthM: Float,
    val depthM: Float,
    val heightM: Float,
) {
    val volumeM3: Float get() = widthM * depthM * heightM
    val wallAreaM2: Float get() = 2f * (widthM * heightM + depthM * heightM)
    val floorAreaM2: Float get() = widthM * depthM
    val ceilingAreaM2: Float get() = floorAreaM2
}

data class SpatialRoomMaterial(
    val absorption: SpatialAcousticBands,
    val scattering: Float,
)

data class SpatialRoomAcoustics(
    val dimensions: SpatialRoomDimensions?,
    val rt60Seconds: SpatialAcousticBands,
    val reverbEq: SpatialAcousticBands,
    val averageScattering: Float,
    val maxReflectionWet: Float,
    val distanceRolloff: Float,
    val airAbsorption: Float,
    val firstReflectionMs: Float,
    val outdoor: Boolean,
)

private fun roomMaterial(
    low: Float,
    mid: Float,
    high: Float,
    scattering: Float,
) = SpatialRoomMaterial(
    absorption = SpatialAcousticBands(low, mid, high),
    scattering = scattering,
)

private fun weightedAbsorption(
    dimensions: SpatialRoomDimensions,
    walls: SpatialRoomMaterial,
    floor: SpatialRoomMaterial,
    ceiling: SpatialRoomMaterial,
    band: (SpatialAcousticBands) -> Float,
): Float {
    val totalArea = dimensions.wallAreaM2 + dimensions.floorAreaM2 + dimensions.ceilingAreaM2
    return (
        dimensions.wallAreaM2 * band(walls.absorption) +
            dimensions.floorAreaM2 * band(floor.absorption) +
            dimensions.ceilingAreaM2 * band(ceiling.absorption)
        ) / totalArea
}

/**
 * Room presets are described by geometry and materials, not independent reverb knobs. Public
 * geometry values form the stable bridge contract used by the native Steam Audio scene builder.
 */
enum class SpatialRoomPreset(
    val label: String,
    val description: String,
    val nativeId: Int,
    val dimensions: SpatialRoomDimensions?,
    val walls: SpatialRoomMaterial?,
    val floor: SpatialRoomMaterial?,
    val ceiling: SpatialRoomMaterial?,
    private val maxReflectionWet: Float,
    private val distanceRolloff: Float,
    private val airAbsorption: Float,
    private val firstReflectionMs: Float,
) {
    DRY(
        label = "Không gian khô",
        description = "Hấp thụ mạnh, định vị rõ, gần như không có đuôi vang.",
        nativeId = 0,
        dimensions = SpatialRoomDimensions(5f, 4f, 2.8f),
        walls = roomMaterial(0.45f, 0.75f, 0.80f, 0.75f),
        floor = roomMaterial(0.15f, 0.55f, 0.70f, 0.60f),
        ceiling = roomMaterial(0.50f, 0.80f, 0.90f, 0.80f),
        maxReflectionWet = 0.12f,
        distanceRolloff = 0.72f,
        airAbsorption = 0.38f,
        firstReflectionMs = 14f,
    ),
    STUDIO(
        label = "Studio",
        description = "Phòng kiểm âm cân bằng, phản xạ sớm gọn và đuôi ngắn.",
        nativeId = 1,
        dimensions = SpatialRoomDimensions(6f, 5f, 3f),
        walls = roomMaterial(0.25f, 0.55f, 0.65f, 0.65f),
        floor = roomMaterial(0.12f, 0.35f, 0.50f, 0.45f),
        ceiling = roomMaterial(0.35f, 0.70f, 0.80f, 0.70f),
        maxReflectionWet = 0.27f,
        distanceRolloff = 0.68f,
        airAbsorption = 0.36f,
        firstReflectionMs = 18f,
    ),
    LISTENING_ROOM(
        label = "Phòng nghe nhạc",
        description = "Không gian tự nhiên cho nhạc stereo, có chiều sâu nhưng vẫn rõ lời.",
        nativeId = 2,
        dimensions = SpatialRoomDimensions(7f, 5.5f, 3.2f),
        walls = roomMaterial(0.18f, 0.35f, 0.45f, 0.55f),
        floor = roomMaterial(0.10f, 0.28f, 0.40f, 0.42f),
        ceiling = roomMaterial(0.25f, 0.45f, 0.55f, 0.58f),
        maxReflectionWet = 0.38f,
        distanceRolloff = 0.65f,
        airAbsorption = 0.35f,
        firstReflectionMs = 22f,
    ),
    THEATER(
        label = "Nhà hát",
        description = "Không gian lớn, phản xạ khuếch tán và đuôi vang dài hơn.",
        nativeId = 3,
        dimensions = SpatialRoomDimensions(18f, 13f, 8f),
        walls = roomMaterial(0.12f, 0.25f, 0.40f, 0.62f),
        floor = roomMaterial(0.08f, 0.22f, 0.35f, 0.50f),
        ceiling = roomMaterial(0.18f, 0.30f, 0.45f, 0.68f),
        maxReflectionWet = 0.48f,
        distanceRolloff = 0.60f,
        airAbsorption = 0.33f,
        firstReflectionMs = 35f,
    ),
    WAREHOUSE(
        label = "Nhà kho",
        description = "Bề mặt cứng, phản xạ sáng và đuôi rất dài.",
        nativeId = 4,
        dimensions = SpatialRoomDimensions(25f, 18f, 10f),
        walls = roomMaterial(0.02f, 0.03f, 0.04f, 0.30f),
        floor = roomMaterial(0.02f, 0.03f, 0.04f, 0.22f),
        ceiling = roomMaterial(0.05f, 0.08f, 0.10f, 0.35f),
        maxReflectionWet = 0.50f,
        distanceRolloff = 0.55f,
        airAbsorption = 0.30f,
        firstReflectionMs = 45f,
    ),
    OUTDOOR(
        label = "Ngoài trời",
        description = "Không có phòng bao quanh, chỉ giữ một lượng phản xạ môi trường rất nhỏ.",
        nativeId = 5,
        dimensions = null,
        walls = null,
        floor = null,
        ceiling = null,
        maxReflectionWet = 0.03f,
        distanceRolloff = 0.50f,
        airAbsorption = 0.45f,
        firstReflectionMs = 0f,
    );

    val acoustics: SpatialRoomAcoustics by lazy {
        if (dimensions == null || walls == null || floor == null || ceiling == null) {
            return@lazy SpatialRoomAcoustics(
                dimensions = null,
                rt60Seconds = SpatialAcousticBands(0.10f, 0.10f, 0.10f),
                reverbEq = SpatialAcousticBands(0.35f, 0.25f, 0.15f),
                averageScattering = 0.10f,
                maxReflectionWet = maxReflectionWet,
                distanceRolloff = distanceRolloff,
                airAbsorption = airAbsorption,
                firstReflectionMs = firstReflectionMs,
                outdoor = true,
            )
        }

        val equivalentAbsorption = SpatialAcousticBands(
            low = dimensions.wallAreaM2 * walls.absorption.low +
                dimensions.floorAreaM2 * floor.absorption.low +
                dimensions.ceilingAreaM2 * ceiling.absorption.low,
            mid = dimensions.wallAreaM2 * walls.absorption.mid +
                dimensions.floorAreaM2 * floor.absorption.mid +
                dimensions.ceilingAreaM2 * ceiling.absorption.mid,
            high = dimensions.wallAreaM2 * walls.absorption.high +
                dimensions.floorAreaM2 * floor.absorption.high +
                dimensions.ceilingAreaM2 * ceiling.absorption.high,
        )
        val rt60 = equivalentAbsorption.map { absorptionArea ->
            (SABINE_CONSTANT * dimensions.volumeM3 / absorptionArea.coerceAtLeast(0.01f))
                .coerceIn(MIN_RT60_SECONDS, MAX_RT60_SECONDS)
        }
        val reflectedEnergy = SpatialAcousticBands(
            low = 1f - weightedAbsorption(dimensions, walls, floor, ceiling) { it.low },
            mid = 1f - weightedAbsorption(dimensions, walls, floor, ceiling) { it.mid },
            high = 1f - weightedAbsorption(dimensions, walls, floor, ceiling) { it.high },
        )
        val energyPeak = reflectedEnergy.maxValue().coerceAtLeast(0.01f)
        val normalizedEq = reflectedEnergy.map { (it / energyPeak).coerceIn(0.05f, 1f) }
        val reverbEq = SpatialAcousticBands(
            low = normalizedEq.low,
            mid = normalizedEq.mid,
            high = (normalizedEq.high * 0.78f).coerceAtLeast(0.05f),
        )
        val totalArea = dimensions.wallAreaM2 + dimensions.floorAreaM2 + dimensions.ceilingAreaM2
        val scattering = (
            dimensions.wallAreaM2 * walls.scattering +
                dimensions.floorAreaM2 * floor.scattering +
                dimensions.ceilingAreaM2 * ceiling.scattering
            ) / totalArea

        SpatialRoomAcoustics(
            dimensions = dimensions,
            rt60Seconds = rt60,
            reverbEq = reverbEq,
            averageScattering = scattering.coerceIn(0f, 1f),
            maxReflectionWet = maxReflectionWet,
            distanceRolloff = distanceRolloff,
            airAbsorption = airAbsorption,
            firstReflectionMs = firstReflectionMs,
            outdoor = false,
        )
    }
}
