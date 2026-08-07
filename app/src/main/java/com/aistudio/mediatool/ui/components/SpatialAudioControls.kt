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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.core.spatial.SpatialAudioConfig
import com.aistudio.mediatool.core.spatial.SpatialStereoMode
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
    val value = config.normalized()
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
                label = "Chế độ stereo",
                valueLabel = value.stereoMode.label,
                entries = listOf(
                    SpatialStereoMode.MID_SIDE,
                    SpatialStereoMode.SHARED_POSITION,
                    SpatialStereoMode.DUAL_OBJECT,
                ),
                entryLabel = { it.label },
                onSelect = { onConfigChange(value.copy(stereoMode = it).normalized()) },
            )

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
                onSelect = { onConfigChange(value.withFriendlyTrajectory(it)) },
            )

            val cycle = value.friendlyCyclePosition()
            AccessibleSliderColumn(
                label = "Chu kỳ • ${formatSeconds(value.cycleSeconds)}",
                value = cycle,
                onValueChange = { onConfigChange(value.withFriendlyCycle(it)) },
                valueRange = 0f..1f,
            )

            val distance = value.friendlyDistancePosition()
            AccessibleSliderColumn(
                label = "Khoảng cách • ${formatDistance(max(value.startDistanceM, value.endDistanceM))}",
                value = distance,
                onValueChange = { onConfigChange(value.withFriendlyDistance(it)) },
                valueRange = 0f..1f,
            )

            AccessibleSliderColumn(
                label = "Cường độ 3D • ${(value.spatialBlend * 100f).roundToInt()}%",
                value = value.spatialBlend,
                onValueChange = { onConfigChange(value.copy(spatialBlend = it).normalized()) },
                valueRange = 0f..1f,
            )

            AccessibleSliderColumn(
                label = "Độ vang • ${(value.reverbWet * 100f).roundToInt()}%",
                value = value.reverbWet,
                onValueChange = { onConfigChange(value.copy(reverbWet = it).normalized()) },
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
