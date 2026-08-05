package com.aistudio.mediatool.core.spatial

/**
 * Versioned bridge payload for the native room-reflection renderer. Keeping a fixed array layout
 * avoids an ever-growing JNI signature while tests protect the Kotlin/C++ contract.
 */
data class RoomReflectionNativeSpec(
    val enabled: Boolean,
    val roomId: Int,
    val rays: Int,
    val bounces: Int,
    val order: Int,
    val threads: Int,
    val durationSeconds: Float,
    val hybridTransitionSeconds: Float,
    val hybridOverlapPercent: Float,
    val updateSeconds: Float,
    val dimensions: SpatialRoomDimensions?,
    val walls: SpatialRoomMaterial?,
    val floor: SpatialRoomMaterial?,
    val ceiling: SpatialRoomMaterial?,
) {
    fun integerPayload(): IntArray = intArrayOf(
        PAYLOAD_VERSION,
        if (enabled) 1 else 0,
        roomId,
        rays,
        bounces,
        order,
        threads,
    )

    fun floatPayload(): FloatArray {
        val size = dimensions
        val wall = walls
        val floorMaterial = floor
        val ceilingMaterial = ceiling
        return floatArrayOf(
            PAYLOAD_VERSION.toFloat(),
            size?.widthM ?: 0f,
            size?.depthM ?: 0f,
            size?.heightM ?: 0f,
            wall?.absorption?.low ?: 0f,
            wall?.absorption?.mid ?: 0f,
            wall?.absorption?.high ?: 0f,
            wall?.scattering ?: 0f,
            floorMaterial?.absorption?.low ?: 0f,
            floorMaterial?.absorption?.mid ?: 0f,
            floorMaterial?.absorption?.high ?: 0f,
            floorMaterial?.scattering ?: 0f,
            ceilingMaterial?.absorption?.low ?: 0f,
            ceilingMaterial?.absorption?.mid ?: 0f,
            ceilingMaterial?.absorption?.high ?: 0f,
            ceilingMaterial?.scattering ?: 0f,
            durationSeconds,
            hybridTransitionSeconds,
            hybridOverlapPercent,
            updateSeconds,
        )
    }

    companion object {
        const val PAYLOAD_VERSION = 1
        const val INTEGER_PAYLOAD_SIZE = 7
        const val FLOAT_PAYLOAD_SIZE = 20

        fun balanced(preset: SpatialRoomPreset): RoomReflectionNativeSpec =
            RoomReflectionNativeSpec(
                enabled = preset.dimensions != null,
                roomId = preset.nativeId,
                rays = 4_096,
                bounces = 16,
                order = 1,
                threads = 2,
                durationSeconds = 2.0f,
                hybridTransitionSeconds = 0.12f,
                hybridOverlapPercent = 0.25f,
                updateSeconds = 0.25f,
                dimensions = preset.dimensions,
                walls = preset.walls,
                floor = preset.floor,
                ceiling = preset.ceiling,
            )
    }
}
