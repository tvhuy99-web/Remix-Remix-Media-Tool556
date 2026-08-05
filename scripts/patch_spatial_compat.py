from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
config_path = root / "app/src/main/java/com/aistudio/mediatool/core/spatial/SpatialAudioConfig.kt"
ui_path = root / "app/src/main/java/com/aistudio/mediatool/ui/components/SpatialAudioControls.kt"

config = config_path.read_text(encoding="utf-8")
config = config.replace(
    "        }.normalized().fitToRoom()\n    }\n\n    fun withRoomPreset",
    "        }.normalized()\n    }\n\n    fun withRoomPreset",
    1,
)
config = config.replace(
    "        ).normalized().fitToRoom()\n    }\n\n    fun fitToRoom",
    "        ).normalized()\n    }\n\n    fun fitToRoom",
    1,
)

distance_block = '''    fun friendlyDistanceUpperBound(): Float =
        SpatialRoomTrajectoryPolicy.maximumDistance(normalized())
            .coerceIn(FRIENDLY_DISTANCE_MIN_M, FRIENDLY_DISTANCE_MAX_M)

    fun roomAwareFriendlyDistancePosition(): Float {
        val upperBound = friendlyDistanceUpperBound()
        if (upperBound <= FRIENDLY_DISTANCE_MIN_M + 1e-4f) return 0f
        val distance = max(startDistanceM, endDistanceM)
            .coerceIn(FRIENDLY_DISTANCE_MIN_M, upperBound)
        return (
            ln((distance / FRIENDLY_DISTANCE_MIN_M).toDouble()) /
                ln((upperBound / FRIENDLY_DISTANCE_MIN_M).toDouble())
            ).toFloat().coerceIn(0f, 1f)
    }

    fun withRoomAwareFriendlyDistance(position: Float): SpatialAudioConfig {
        val upperBound = friendlyDistanceUpperBound()
        val distance = if (upperBound <= FRIENDLY_DISTANCE_MIN_M + 1e-4f) {
            FRIENDLY_DISTANCE_MIN_M
        } else {
            (
                FRIENDLY_DISTANCE_MIN_M *
                    kotlin.math.exp(
                        ln((upperBound / FRIENDLY_DISTANCE_MIN_M).toDouble()) *
                            position.coerceIn(0f, 1f),
                    ).toFloat()
                ).coerceIn(FRIENDLY_DISTANCE_MIN_M, upperBound)
        }
        return when (trajectory) {
            SpatialTrajectory.NEAR_FAR -> copy(
                startDistanceM = max(FRIENDLY_DISTANCE_MIN_M, distance * NEAR_FAR_RATIO),
                endDistanceM = distance,
            )
            SpatialTrajectory.FREE_DRIFT -> copy(
                startDistanceM = max(FRIENDLY_DISTANCE_MIN_M, distance * FREE_DRIFT_NEAR_RATIO),
                endDistanceM = distance,
            )
            else -> copy(startDistanceM = distance, endDistanceM = distance)
        }.normalized().fitToRoom()
    }

    fun friendlyDistancePosition(): Float {
        val distance = max(startDistanceM, endDistanceM)
            .coerceIn(FRIENDLY_DISTANCE_MIN_M, FRIENDLY_DISTANCE_MAX_M)
        return (
            ln((distance / FRIENDLY_DISTANCE_MIN_M).toDouble()) /
                ln((FRIENDLY_DISTANCE_MAX_M / FRIENDLY_DISTANCE_MIN_M).toDouble())
            ).toFloat().coerceIn(0f, 1f)
    }

    fun withFriendlyDistance(position: Float): SpatialAudioConfig {
        val distance = (
            FRIENDLY_DISTANCE_MIN_M *
                kotlin.math.exp(
                    ln((FRIENDLY_DISTANCE_MAX_M / FRIENDLY_DISTANCE_MIN_M).toDouble()) *
                        position.coerceIn(0f, 1f),
                ).toFloat()
            ).coerceIn(FRIENDLY_DISTANCE_MIN_M, FRIENDLY_DISTANCE_MAX_M)

        return when (trajectory) {
            SpatialTrajectory.NEAR_FAR -> copy(
                startDistanceM = max(FRIENDLY_DISTANCE_MIN_M, distance * NEAR_FAR_RATIO),
                endDistanceM = distance,
            )
            SpatialTrajectory.FREE_DRIFT -> copy(
                startDistanceM = max(FRIENDLY_DISTANCE_MIN_M, distance * FREE_DRIFT_NEAR_RATIO),
                endDistanceM = distance,
            )
            else -> copy(startDistanceM = distance, endDistanceM = distance)
        }.normalized()
    }

'''
config, count = re.subn(
    r"    fun friendlyDistanceUpperBound\(\): Float =[\s\S]*?(?=    fun friendlyReflectionPosition\(\): Float)",
    distance_block,
    config,
    count=1,
)
if count != 1:
    raise RuntimeError("Could not install backward-compatible room distance controls")
config_path.write_text(config, encoding="utf-8")

ui = ui_path.read_text(encoding="utf-8")
ui = ui.replace(
    "onSelect = { onConfigChange(value.withFriendlyTrajectory(it)) },",
    "onSelect = { onConfigChange(value.withFriendlyTrajectory(it).fitToRoom()) },",
)
ui = ui.replace(
    "onSelect = { onConfigChange(value.withRoomPreset(it)) },",
    "onSelect = { onConfigChange(value.withRoomPreset(it).fitToRoom()) },",
)
ui = ui.replace(
    "val distance = value.friendlyDistancePosition()",
    "val distance = value.roomAwareFriendlyDistancePosition()",
)
ui = ui.replace(
    "onValueChange = { onConfigChange(value.withFriendlyDistance(it)) },",
    "onValueChange = { onConfigChange(value.withRoomAwareFriendlyDistance(it)) },",
)
ui_path.write_text(ui, encoding="utf-8")
