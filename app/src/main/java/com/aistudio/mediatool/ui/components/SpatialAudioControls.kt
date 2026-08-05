package com.aistudio.mediatool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.core.spatial.SpatialAudioConfig
import com.aistudio.mediatool.core.spatial.SpatialRoomPreset
import com.aistudio.mediatool.core.spatial.SpatialRoomTrajectoryPolicy
import com.aistudio.mediatool.core.spatial.SpatialTrajectory
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun SpatialAudioControls(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    config: SpatialAudioConfig,
    onConfigChange: (SpatialAudioConfig) -> Unit,
    onPickSofa: () -> Unit,
    onClearSofa: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roomFit = SpatialRoomTrajectoryPolicy.fit(config.normalized())
    val value = roomFit.config
    LaunchedEffect(roomFit.adjusted, value) {
        if (roomFit.adjusted) onConfigChange(value)
    }
    AccessibleCheckboxRow(
        checked = enabled,
        onCheckedChange = onEnabledChange,
        text = "Spatial Audio 3D",
    )
    if (!enabled) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EnumDropdown(
                label = "Chuyển động",
                valueLabel = value.trajectory.label,
                entries = listOf(
                    SpatialTrajectory.HORIZONTAL_CIRCLE,
                    SpatialTrajectory.LINEAR,
                    SpatialTrajectory.PENDULUM,
                    SpatialTrajectory.FRONT_BACK,
                    SpatialTrajectory.FIGURE_EIGHT,
                    SpatialTrajectory.VERTICAL_CIRCLE,
                    SpatialTrajectory.SPIRAL,
                    SpatialTrajectory.NEAR_FAR,
                    SpatialTrajectory.FREE_DRIFT,
                    SpatialTrajectory.STATIC,
                ),
                entryLabel = { it.label },
                onSelect = { onConfigChange(value.withFriendlyTrajectory(it).fitToRoom()) },
            )

            EnumDropdown(
                label = "Không gian",
                valueLabel = value.roomPreset.label,
                entries = SpatialRoomPreset.entries.toList(),
                entryLabel = { it.label },
                onSelect = { onConfigChange(value.withRoomPreset(it).fitToRoom()) },
            )

            if (value.trajectory != SpatialTrajectory.STATIC) {
                val speed = value.friendlySpeedPosition()
                val speedLabel = if (value.trajectory == SpatialTrajectory.LINEAR) {
                    "Thời gian quét • ${formatSeconds(value.cycleSeconds)}"
                } else {
                    "Chu kỳ • ${formatSeconds(value.cycleSeconds)}"
                }
                AccessibleSliderColumn(
                    label = speedLabel,
                    value = speed,
                    onValueChange = { onConfigChange(value.withFriendlySpeed(it)) },
                    valueRange = 0f..1f,
                )
            }

            val distance = value.roomAwareFriendlyDistancePosition()
            val distanceUpperBound = value.friendlyDistanceUpperBound()
            AccessibleSliderColumn(
                label = "Độ xa ước tính • ${formatDistance(max(value.startDistanceM, value.endDistanceM))} " +
                    "• tối đa ${formatDistance(distanceUpperBound)} trong không gian này",
                value = distance,
                onValueChange = { onConfigChange(value.withRoomAwareFriendlyDistance(it)) },
                valueRange = 0f..1f,
            )

            AccessibleSliderColumn(
                label = "Cường độ chuyển động • ${(value.spatialBlend * 100f).roundToInt()}%",
                value = value.spatialBlend,
                onValueChange = { onConfigChange(value.copy(spatialBlend = it).normalized()) },
                valueRange = 0f..1f,
            )

            val reflection = value.friendlyReflectionPosition()
            AccessibleSliderColumn(
                label = "Phản xạ phòng • ${(reflection * 100f).roundToInt()}%",
                value = reflection,
                onValueChange = { onConfigChange(value.withFriendlyReflection(it)) },
                valueRange = 0f..1f,
            )
        }
    }
}

private fun formatSeconds(seconds: Float): String {
    val tenths = (seconds * 10f).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10} giây" else "${tenths / 10f} giây"
}

private fun formatDistance(distanceM: Float): String {
    val tenths = (distanceM * 10f).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10} m" else "${tenths / 10f} m"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    valueLabel: String,
    entries: List<T>,
    entryLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = valueLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            label = { Text(label) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entryLabel(entry)) },
                    onClick = {
                        onSelect(entry)
                        expanded = false
                    },
                )
            }
        }
    }
}
