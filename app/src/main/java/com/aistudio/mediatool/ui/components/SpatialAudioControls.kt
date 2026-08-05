package com.aistudio.mediatool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.core.spatial.SpatialAudioConfig
import com.aistudio.mediatool.core.spatial.SpatialInterpolation
import com.aistudio.mediatool.core.spatial.SpatialMotionMode
import com.aistudio.mediatool.core.spatial.SpatialTrajectory
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
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
        text = "Spatial Audio binaural 3D",
    )
    if (!enabled) return

    Text(
        "Đầu ra được render cho tai nghe bằng HRTF. Engine cũ Auto Pan + Echo đã bị loại khỏi nhánh này.",
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Quỹ đạo chuyển động")
            EnumDropdown(
                label = "Kiểu quỹ đạo",
                valueLabel = value.trajectory.label,
                entries = SpatialTrajectory.entries,
                entryLabel = { it.label },
                onSelect = { onConfigChange(value.copy(trajectory = it)) },
            )
            EnumDropdown(
                label = "Cách chạy",
                valueLabel = value.motionMode.label,
                entries = SpatialMotionMode.entries,
                entryLabel = { it.label },
                onSelect = { onConfigChange(value.copy(motionMode = it)) },
            )
            AccessibleSliderColumn(
                label = "Chu kỳ: ${format(value.cycleSeconds, 1)} giây",
                value = value.cycleSeconds,
                onValueChange = { onConfigChange(value.copy(cycleSeconds = it)) },
                valueRange = 0.5f..120f,
            )
            AccessibleSliderColumn(
                label = "Góc ngang bắt đầu: ${value.startAzimuthDeg.roundToInt()}°",
                value = value.startAzimuthDeg,
                onValueChange = { onConfigChange(value.copy(startAzimuthDeg = it)) },
                valueRange = -720f..720f,
            )
            AccessibleSliderColumn(
                label = "Góc ngang kết thúc: ${value.endAzimuthDeg.roundToInt()}°",
                value = value.endAzimuthDeg,
                onValueChange = { onConfigChange(value.copy(endAzimuthDeg = it)) },
                valueRange = -720f..720f,
            )
            AccessibleSliderColumn(
                label = "Độ cao bắt đầu: ${value.startElevationDeg.roundToInt()}°",
                value = value.startElevationDeg,
                onValueChange = { onConfigChange(value.copy(startElevationDeg = it)) },
                valueRange = -90f..90f,
            )
            AccessibleSliderColumn(
                label = "Độ cao kết thúc: ${value.endElevationDeg.roundToInt()}°",
                value = value.endElevationDeg,
                onValueChange = { onConfigChange(value.copy(endElevationDeg = it)) },
                valueRange = -90f..90f,
            )
            AccessibleSliderColumn(
                label = "Khoảng cách bắt đầu: ${format(value.startDistanceM, 1)} m",
                value = value.startDistanceM,
                onValueChange = { onConfigChange(value.copy(startDistanceM = it)) },
                valueRange = 0.2f..100f,
            )
            AccessibleSliderColumn(
                label = "Khoảng cách kết thúc: ${format(value.endDistanceM, 1)} m",
                value = value.endDistanceM,
                onValueChange = { onConfigChange(value.copy(endDistanceM = it)) },
                valueRange = 0.2f..100f,
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("HRTF và định vị")
            EnumDropdown(
                label = "Nội suy HRTF",
                valueLabel = value.interpolation.label,
                entries = SpatialInterpolation.entries,
                entryLabel = { it.label },
                onSelect = { onConfigChange(value.copy(interpolation = it)) },
            )
            AccessibleSliderColumn(
                label = "Mức binaural: ${(value.spatialBlend * 100f).roundToInt()}%",
                value = value.spatialBlend,
                onValueChange = { onConfigChange(value.copy(spatialBlend = it)) },
                valueRange = 0f..1f,
            )
            val sofaName = value.customSofaPath?.let { File(it).name }
            Text(
                if (sofaName == null) "HRTF: Steam Audio mặc định" else "HRTF SOFA: $sofaName",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onPickSofa, modifier = Modifier.weight(1f)) {
                    Text("Chọn SOFA")
                }
                if (sofaName != null) {
                    OutlinedButton(onClick = onClearSofa, modifier = Modifier.weight(1f)) {
                        Text("Dùng HRTF mặc định")
                    }
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Khoảng cách và hướng phát")
            AccessibleSliderColumn(
                label = "Khoảng cách không suy hao: ${format(value.distanceMinM, 1)} m",
                value = value.distanceMinM,
                onValueChange = { onConfigChange(value.copy(distanceMinM = it)) },
                valueRange = 0.1f..20f,
            )
            AccessibleSliderColumn(
                label = "Độ dốc suy hao: ${format(value.distanceRolloff, 2)}",
                value = value.distanceRolloff,
                onValueChange = { onConfigChange(value.copy(distanceRolloff = it)) },
                valueRange = 0.1f..4f,
            )
            AccessibleSliderColumn(
                label = "Hấp thụ không khí: ${(value.airAbsorption * 100f).roundToInt()}%",
                value = value.airAbsorption,
                onValueChange = { onConfigChange(value.copy(airAbsorption = it)) },
                valueRange = 0f..2f,
            )
            AccessibleSliderColumn(
                label = "Tính định hướng nguồn: ${(value.directivityWeight * 100f).roundToInt()}%",
                value = value.directivityWeight,
                onValueChange = { onConfigChange(value.copy(directivityWeight = it)) },
                valueRange = 0f..1f,
            )
            AccessibleSliderColumn(
                label = "Độ tập trung hướng phát: ${format(value.directivityPower, 1)}",
                value = value.directivityPower,
                onValueChange = { onConfigChange(value.copy(directivityPower = it)) },
                valueRange = 1f..8f,
            )
            AccessibleSliderColumn(
                label = "Hướng quay nguồn: ${value.sourceYawDeg.roundToInt()}°",
                value = value.sourceYawDeg,
                onValueChange = { onConfigChange(value.copy(sourceYawDeg = it)) },
                valueRange = -180f..180f,
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Không gian phản xạ")
            AccessibleSliderColumn(
                label = "Reverb Wet: ${(value.reverbWet * 100f).roundToInt()}%",
                value = value.reverbWet,
                onValueChange = { onConfigChange(value.copy(reverbWet = it)) },
                valueRange = 0f..1f,
            )
            if (value.reverbWet > 0f) {
                AccessibleSliderColumn(
                    label = "RT60 dải thấp: ${format(value.reverbRt60Low, 1)} s",
                    value = value.reverbRt60Low,
                    onValueChange = { onConfigChange(value.copy(reverbRt60Low = it)) },
                    valueRange = 0.1f..10f,
                )
                AccessibleSliderColumn(
                    label = "RT60 dải trung: ${format(value.reverbRt60Mid, 1)} s",
                    value = value.reverbRt60Mid,
                    onValueChange = { onConfigChange(value.copy(reverbRt60Mid = it)) },
                    valueRange = 0.1f..10f,
                )
                AccessibleSliderColumn(
                    label = "RT60 dải cao: ${format(value.reverbRt60High, 1)} s",
                    value = value.reverbRt60High,
                    onValueChange = { onConfigChange(value.copy(reverbRt60High = it)) },
                    valueRange = 0.1f..10f,
                )
                AccessibleSliderColumn(
                    label = "EQ reverb thấp: ${(value.reverbEqLow * 100f).roundToInt()}%",
                    value = value.reverbEqLow,
                    onValueChange = { onConfigChange(value.copy(reverbEqLow = it)) },
                    valueRange = 0f..1f,
                )
                AccessibleSliderColumn(
                    label = "EQ reverb trung: ${(value.reverbEqMid * 100f).roundToInt()}%",
                    value = value.reverbEqMid,
                    onValueChange = { onConfigChange(value.copy(reverbEqMid = it)) },
                    valueRange = 0f..1f,
                )
                AccessibleSliderColumn(
                    label = "EQ reverb cao: ${(value.reverbEqHigh * 100f).roundToInt()}%",
                    value = value.reverbEqHigh,
                    onValueChange = { onConfigChange(value.copy(reverbEqHigh = it)) },
                    valueRange = 0f..1f,
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Phạm vi và đầu ra")
            var startText by rememberSaveable { mutableStateOf(value.effectStartSeconds.toString()) }
            var endText by rememberSaveable { mutableStateOf(value.effectEndSeconds.toString()) }
            OutlinedTextField(
                value = startText,
                onValueChange = { text ->
                    startText = text
                    text.toFloatOrNull()?.let { onConfigChange(value.copy(effectStartSeconds = it)) }
                },
                label = { Text("Bắt đầu hiệu ứng, giây") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { text ->
                    endText = text
                    text.toFloatOrNull()?.let { onConfigChange(value.copy(effectEndSeconds = it)) }
                },
                label = { Text("Kết thúc, giây • -1 là hết tệp") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            AccessibleSliderColumn(
                label = "Gain đầu ra: ${format(value.outputGainDb, 1)} dB",
                value = value.outputGainDb,
                onValueChange = { onConfigChange(value.copy(outputGainDb = it)) },
                valueRange = -24f..6f,
            )
            EnumDropdown(
                label = "Kích thước block DSP",
                valueLabel = "${value.frameSize} mẫu",
                entries = listOf(256, 512, 1024, 2048, 4096),
                entryLabel = { "$it mẫu" },
                onSelect = { onConfigChange(value.copy(frameSize = it)) },
            )
            Text(
                "Limiter dùng shared gain sau render, không cắt riêng từng tai. Mọi tham số và chỉ số chất lượng được ghi vào gói chẩn đoán.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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

private fun format(value: Float, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)
