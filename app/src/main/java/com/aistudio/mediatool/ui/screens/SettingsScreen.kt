package com.aistudio.mediatool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.ml.OnnxAcceleration
import com.aistudio.mediatool.core.ml.StemMode
import com.aistudio.mediatool.core.ml.StemModelRegistry
import com.aistudio.mediatool.ui.components.DiagnosticReportCard
import com.aistudio.mediatool.ui.components.ToolScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    var vidIndex by rememberSaveable { mutableStateOf(SettingsManager.getVidQualityIndex(context)) }
    var audIndex by rememberSaveable { mutableStateOf(SettingsManager.getAudBitrateIndex(context)) }
    var fmtIndex by rememberSaveable { mutableStateOf(SettingsManager.getAudFormatIndex(context)) }
    var fadeDuration by rememberSaveable { mutableStateOf(SettingsManager.getFadeDurationSec(context)) }
    var numThreadsIndex by rememberSaveable { mutableStateOf(SettingsManager.getNumThreadsIndex(context)) }
    var stemModeIndex by rememberSaveable { mutableStateOf(SettingsManager.getStemModeIndex(context)) }
    var stemModelId by rememberSaveable {
        val mode = StemMode.fromSettingsIndex(stemModeIndex)
        mutableStateOf(StemModelRegistry.resolve(mode, SettingsManager.getStemModelId(context, stemModeIndex)).id)
    }
    var showNotices by rememberSaveable { mutableStateOf(false) }
    val thirdPartyNotices = remember(context) {
        runCatching {
            context.assets.open("third_party_notices.txt").bufferedReader().use { it.readText() }
        }.getOrElse { "Không đọc được giấy phép." }
    }

    if (showNotices) {
        AlertDialog(
            onDismissRequest = { showNotices = false },
            title = { Text("Giấy phép") },
            text = {
                Text(
                    text = thirdPartyNotices,
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showNotices = false }) { Text("Đóng") } },
        )
    }

    ToolScaffold(
        title = "Cài đặt",
        onNavigateBack = { navController.popBackStack() },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SimpleDropdown(
                    label = "Fade",
                    values = (0..10).map { if (it == 0) "Tắt" else "$it giây" },
                    selectedIndex = fadeDuration,
                    onSelected = { fadeDuration = it },
                )
                SimpleDropdown(
                    label = "Video",
                    values = listOf("2 Mbps", "5 Mbps", "10 Mbps", "20 Mbps", "50 Mbps"),
                    selectedIndex = vidIndex,
                    onSelected = { vidIndex = it },
                )
                SimpleDropdown(
                    label = "Audio",
                    values = listOf("128 kbps", "192 kbps", "256 kbps", "320 kbps", "Lossless"),
                    selectedIndex = audIndex,
                    onSelected = { audIndex = it },
                )
                SimpleDropdown(
                    label = "Định dạng",
                    values = listOf("M4A", "MP3", "WAV", "FLAC"),
                    selectedIndex = fmtIndex,
                    onSelected = { fmtIndex = it },
                )

                Spacer(Modifier.height(4.dp))
                Text("Tách Audio AI", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                SimpleDropdown(
                    label = "Luồng CPU",
                    values = listOf("1 luồng", "2 luồng", "4 luồng", "8 luồng"),
                    selectedIndex = numThreadsIndex,
                    onSelected = { numThreadsIndex = it },
                )

                val selectedMode = StemMode.fromSettingsIndex(stemModeIndex)
                SimpleDropdown(
                    label = "Chế độ",
                    values = listOf("2 stem", "4 stem"),
                    selectedIndex = stemModeIndex,
                    onSelected = { index ->
                        stemModeIndex = index
                        val mode = StemMode.fromSettingsIndex(index)
                        stemModelId = StemModelRegistry.resolve(
                            mode,
                            SettingsManager.getStemModelId(context, index),
                        ).id
                    },
                )

                val models = StemModelRegistry.modelsFor(selectedMode)
                val selectedModel = StemModelRegistry.resolve(selectedMode, stemModelId)
                SimpleDropdown(
                    label = "Mô hình",
                    values = models.map { it.displayName },
                    selectedIndex = models.indexOfFirst { it.id == selectedModel.id }.coerceAtLeast(0),
                    onSelected = { index -> stemModelId = models[index].id },
                )

                DiagnosticReportCard()

                OutlinedButton(
                    onClick = { showNotices = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Giấy phép")
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        SettingsManager.setVidQualityIndex(context, vidIndex)
                        SettingsManager.setAudBitrateIndex(context, audIndex)
                        SettingsManager.setAudFormatIndex(context, fmtIndex)
                        SettingsManager.setFadeDurationSec(context, fadeDuration)
                        SettingsManager.setNumThreadsIndex(context, numThreadsIndex)
                        SettingsManager.setStemModeIndex(context, stemModeIndex)
                        SettingsManager.setStemModelId(context, stemModeIndex, stemModelId)
                        SettingsManager.setHardwareAccelIndex(context, OnnxAcceleration.XNNPACK.settingsIndex)
                        navController.popBackStack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Lưu")
                }
                TextButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Hủy")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdown(
    label: String,
    values: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = values.getOrElse(selectedIndex) { values.firstOrNull().orEmpty() },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = { Text(value) },
                    onClick = {
                        onSelected(index)
                        expanded = false
                    },
                )
            }
        }
    }
}
