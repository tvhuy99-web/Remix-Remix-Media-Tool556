package com.aistudio.mediatool.core.spatial

/**
 * Stable values shared with native switch statements. Never derive these IDs from enum ordinal:
 * reordering UI entries must not silently change the rendered trajectory or interpolation mode.
 */
internal val SpatialTrajectory.nativeId: Int
    get() = when (this) {
        SpatialTrajectory.HORIZONTAL_CIRCLE -> 0
        SpatialTrajectory.VERTICAL_CIRCLE -> 1
        SpatialTrajectory.FIGURE_EIGHT -> 2
        SpatialTrajectory.LINEAR -> 3
        SpatialTrajectory.STATIC -> 4
        SpatialTrajectory.PENDULUM -> 5
        SpatialTrajectory.FRONT_BACK -> 6
        SpatialTrajectory.SPIRAL -> 7
        SpatialTrajectory.NEAR_FAR -> 8
        SpatialTrajectory.FREE_DRIFT -> 9
    }

internal val SpatialInterpolation.nativeId: Int
    get() = when (this) {
        SpatialInterpolation.BILINEAR -> 0
        SpatialInterpolation.NEAREST -> 1
    }

internal val SpatialMotionMode.nativeId: Int
    get() = when (this) {
        SpatialMotionMode.LOOP -> 0
        SpatialMotionMode.ONCE -> 1
    }
