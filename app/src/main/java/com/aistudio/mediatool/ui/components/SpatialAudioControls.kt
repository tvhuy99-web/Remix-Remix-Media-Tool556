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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.core.spatial.SpatialAudioConfig
import com.aistudio.mediatool.core.spatial.SpatialTrajectory
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

    Text(
        "Giữ stereo gốc, hoặc tự chuyển nguồn mono thành stereo trước khi dựng HRTF.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Tùy chỉnh Spatial Audio",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            EnumDropdown(
                label = "Kiểu chuyển động",
                valueLabel = value.trajectory.label,
                entries = listOf(
                    SpatialTrajectory.HORIZONTAL_CIRCLE,
                    SpatialTrajectory.FIGURE_EIGHT,
                    SpatialTrajectory.VERTICAL_CIRCLE,
                    SpatialTrajectory.LINEAR,
                ),
                entryLabel = { it.label },
                onSelect = { onConfigChange(value.withFriendlyTrajectory(it)) },
            )

            val speed = value.friendlySpeedPosition()
            AccessibleSliderColumn(
                label = "Tốc độ: ${levelLabel(speed, "Chậm", "Vừa", "Nhanh")}",
                value = speed,
                onValueChange = { onConfigChange(value.withFriendlySpeed(it)) },
                valueRange = 0f..1f,
            )

            val distance = value.friendlyDistancePosition()
            AccessibleSliderColumn(
                label = "Khoảng cách: ${levelLabel(distance, "Gần", "Vừa", "Xa")}",
                value = distance,
                onValueChange = { onConfigChange(value.withFriendlyDistance(it)) },
                valueRange = 0f..1f,
            )

            AccessibleSliderColumn(
                label = "Cường độ 3D: ${levelLabel(value.spatialBlend, "Nhẹ", "Cân bằng", "Rõ")}",
                value = value.spatialBlend,
                onValueChange = { onConfigChange(value.copy(spatialBlend = it).normalized()) },
                valueRange = 0f..1f,
            )

            AccessibleSliderColumn(
                label = "Độ vang: ${(value.reverbWet * 100f).roundToInt()}%",
                value = value.reverbWet,
                onValueChange = { onConfigChange(value.copy(reverbWet = it).normalized()) },
                valueRange = 0f..1f,
            )

            Text(
                "Âm lượng được cân tự động theo đầu vào và bảo vệ bằng một peak ceiling chung cho hai tai. Độ vang 0% là đường khô.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun levelLabel(value: Float, low: String, middle: String, high: String): String = when {
    value < 0.34f -> low
    value < 0.67f -> middle
    else -> high
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
