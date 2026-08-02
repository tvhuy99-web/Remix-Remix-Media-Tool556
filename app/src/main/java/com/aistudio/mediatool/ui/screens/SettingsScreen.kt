package com.aistudio.mediatool.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.ml.StemMode
import com.aistudio.mediatool.core.ml.StemModelRegistry
import com.aistudio.mediatool.ui.components.DiagnosticReportCard
import com.aistudio.mediatool.ui.components.ToolScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current

    // State loaded from persistency
    var vidIndex by rememberSaveable { mutableStateOf(com.aistudio.mediatool.core.SettingsManager.getVidQualityIndex(context)) }
    var audIndex by rememberSaveable { mutableStateOf(com.aistudio.mediatool.core.SettingsManager.getAudBitrateIndex(context)) }
    var fmtIndex by rememberSaveable { mutableStateOf(com.aistudio.mediatool.core.SettingsManager.getAudFormatIndex(context)) }
    var fadeDuration by rememberSaveable { mutableStateOf(com.aistudio.mediatool.core.SettingsManager.getFadeDurationSec(context)) }
    var hwAccelIndex by rememberSaveable { mutableStateOf(com.aistudio.mediatool.core.SettingsManager.getHardwareAccelIndex(context)) }
    var numThreadsIndex by rememberSaveable { mutableStateOf(com.aistudio.mediatool.core.SettingsManager.getNumThreadsIndex(context)) }
    var stemModeIndex by rememberSaveable { mutableStateOf(com.aistudio.mediatool.core.SettingsManager.getStemModeIndex(context)) }
    var stemModelId by rememberSaveable {
        val mode = StemMode.fromSettingsIndex(stemModeIndex)
        mutableStateOf(
            StemModelRegistry.resolve(mode, SettingsManager.getStemModelId(context, stemModeIndex)).id,
        )
    }
    var showNotices by rememberSaveable { mutableStateOf(false) }
    val configuredStemMode = StemMode.fromSettingsIndex(stemModeIndex)
    val configuredStemModel = StemModelRegistry.resolve(configuredStemMode, stemModelId)
    val thirdPartyNotices = remember(context) {
        runCatching {
            context.assets.open("third_party_notices.txt").bufferedReader().use { it.readText() }
        }.getOrElse { "Không thể đọc thông tin giấy phép đi kèm ứng dụng." }
    }

    if (showNotices) {
        AlertDialog(
            onDismissRequest = { showNotices = false },
            title = { Text("Giấy phép thành phần bên thứ ba") },
            text = {
                Text(
                    text = thirdPartyNotices,
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showNotices = false }) { Text("Đóng") }
            },
        )
    }

    ToolScaffold(
        title = "Cài đặt",
        onNavigateBack = { navController.popBackStack() },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var expandedFade by remember { mutableStateOf(false) }
                    val fadeList = (0..10).map { if (it == 0) "Tắt (0s)" else "$it giây" }

                    ExposedDropdownMenuBox(expanded = expandedFade, onExpandedChange = { expandedFade = it }, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = fadeList.getOrNull(fadeDuration) ?: fadeList[0],
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Độ dài Fade In/Out (Chung)", color = Color(0xFFE91E63), fontWeight = FontWeight.SemiBold) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFade) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expandedFade, onDismissRequest = { expandedFade = false }) {
                            fadeList.forEachIndexed { index, title ->
                                DropdownMenuItem(text = { Text(title) }, onClick = { fadeDuration = index; expandedFade = false })
                            }
                        }
                    }

                    var expandedVid by remember { mutableStateOf(false) }
                    val vidList = listOf("2 Mbps (Nhẹ, tiết kiệm)", "5 Mbps (Mặc định, chuẩn đẹp)", "10 Mbps (Chất lượng cao)", "20 Mbps (Rất nét, File lớn)", "50 Mbps (Studio/4K, File siêu lớn)")
                    
                    ExposedDropdownMenuBox(expanded = expandedVid, onExpandedChange = { expandedVid = it }, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = vidList.getOrNull(vidIndex) ?: vidList[1],
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Chất lượng Video đầu ra", color = Color(0xFF00A0FF), fontWeight = FontWeight.SemiBold) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedVid) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expandedVid, onDismissRequest = { expandedVid = false }) {
                            vidList.forEachIndexed { index, mode ->
                                DropdownMenuItem(text = { Text(mode) }, onClick = { vidIndex = index; expandedVid = false })
                            }
                        }
                    }

                    var expandedAud by remember { mutableStateOf(false) }
                    val audList = listOf("128 kbps (Cơ bản)", "192 kbps (Cân bằng)", "256 kbps (Chất lượng cao)", "320 kbps (Tối đa cho MP3/AAC)", "Lossless (WAV/FLAC; MP3/AAC sẽ chuyển sang FLAC)")
                    
                    ExposedDropdownMenuBox(expanded = expandedAud, onExpandedChange = { expandedAud = it }, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = audList.getOrNull(audIndex) ?: audList[3],
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Chất lượng Âm thanh (Audio Bitrate)", color = Color(0xFF00AA00), fontWeight = FontWeight.SemiBold) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAud) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expandedAud, onDismissRequest = { expandedAud = false }) {
                            audList.forEachIndexed { index, mode ->
                                DropdownMenuItem(text = { Text(mode) }, onClick = { audIndex = index; expandedAud = false })
                            }
                        }
                    }

                    var expandedFmt by remember { mutableStateOf(false) }
                    val fmtList = listOf("AAC (.m4a) - Nhẹ và tương thích", "MP3 (.mp3) - Phổ biến", "WAV (.wav) - Không nén, tệp rất lớn", "FLAC (.flac) - Nén không mất dữ liệu")
                    
                    ExposedDropdownMenuBox(expanded = expandedFmt, onExpandedChange = { expandedFmt = it }, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = fmtList.getOrNull(fmtIndex) ?: fmtList[0],
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Định dạng xuất âm thanh (Khi trích xuất/Audio)", fontWeight = FontWeight.SemiBold) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFmt) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expandedFmt, onDismissRequest = { expandedFmt = false }) {
                            fmtList.forEachIndexed { index, mode ->
                                DropdownMenuItem(text = { Text(mode) }, onClick = { fmtIndex = index; expandedFmt = false })
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Tối ưu Tốc độ Tách Audio (AI Model)", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

                    var expandedHw by remember { mutableStateOf(false) }
                    val hwList = listOf("CPU", "NNAPI", "XNNPACK", "QNN GPU + fallback thông minh (Snapdragon)")
                    ExposedDropdownMenuBox(expanded = expandedHw, onExpandedChange = { expandedHw = it }, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = hwList.getOrNull(hwAccelIndex) ?: hwList[0],
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Bộ tăng tốc phần cứng (Hardware AI)", color = Color(0xFF673AB7), fontWeight = FontWeight.SemiBold) },
                            supportingText = {
                                if (hwAccelIndex == 3) {
                                    Text("Ưu tiên QNN GPU; node chưa hỗ trợ dùng CPU EP. Nếu session QNN không mở được, ứng dụng thử XNNPACK rồi CPU.")
                                }
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedHw) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expandedHw, onDismissRequest = { expandedHw = false }) {
                            hwList.forEachIndexed { index, mode ->
                                DropdownMenuItem(text = { Text(mode) }, onClick = { hwAccelIndex = index; expandedHw = false })
                            }
                        }
                    }

                    var expandedThreads by remember { mutableStateOf(false) }
                    val threadsList = listOf("1 luồng", "2 luồng", "4 luồng", "8 luồng")
                    ExposedDropdownMenuBox(expanded = expandedThreads, onExpandedChange = { expandedThreads = it }, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = threadsList.getOrNull(numThreadsIndex) ?: threadsList[2],
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Số luồng xử lý CPU", color = Color(0xFFFF5722), fontWeight = FontWeight.SemiBold) },
                            supportingText = {
                                if (hwAccelIndex == 3) Text("Số luồng được dùng cho node CPU fallback và phương án XNNPACK dự phòng.")
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedThreads) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expandedThreads, onDismissRequest = { expandedThreads = false }) {
                            threadsList.forEachIndexed { index, mode ->
                                DropdownMenuItem(text = { Text(mode) }, onClick = { numThreadsIndex = index; expandedThreads = false })
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Cấu Hình AI (Chế độ Nâng Cao)", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

                    var expandedStemMode by remember { mutableStateOf(false) }
                    val stemModes = listOf("Tiêu chuẩn (2 file: Lời & Nhạc Nền)", "Nâng cao (4 file: Lời, Trống, Bass, Khác)")
                    ExposedDropdownMenuBox(expanded = expandedStemMode, onExpandedChange = { expandedStemMode = it }, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = stemModes.getOrNull(stemModeIndex) ?: stemModes[0],
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Chế độ Tách (Stems)", color = Color(0xFF009688), fontWeight = FontWeight.SemiBold) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedStemMode) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expandedStemMode, onDismissRequest = { expandedStemMode = false }) {
                            stemModes.forEachIndexed { index, mode ->
                                DropdownMenuItem(
                                    text = { Text(mode) },
                                    onClick = {
                                        stemModeIndex = index
                                        val selectedMode = StemMode.fromSettingsIndex(index)
                                        stemModelId = StemModelRegistry.resolve(
                                            selectedMode,
                                            SettingsManager.getStemModelId(context, index),
                                        ).id
                                        expandedStemMode = false
                                    },
                                )
                            }
                        }
                    }

                    val selectedMode = StemMode.fromSettingsIndex(stemModeIndex)
                    val availableModels = StemModelRegistry.modelsFor(selectedMode)
                    val selectedModel = configuredStemModel
                    var expandedStemModel by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expandedStemModel,
                        onExpandedChange = { expandedStemModel = it },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = selectedModel.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Mô hình tách stem", fontWeight = FontWeight.SemiBold) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedStemModel) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = expandedStemModel,
                            onDismissRequest = { expandedStemModel = false },
                        ) {
                            availableModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.displayName) },
                                    onClick = {
                                        stemModelId = model.id
                                        expandedStemModel = false
                                    },
                                )
                            }
                        }
                    }

                    DiagnosticReportCard()

                    OutlinedButton(
                        onClick = { showNotices = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Xem giấy phép và thông báo bên thứ ba")
                    }
                }
            }

            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { 
                    com.aistudio.mediatool.core.SettingsManager.setVidQualityIndex(context, vidIndex)
                    com.aistudio.mediatool.core.SettingsManager.setAudBitrateIndex(context, audIndex)
                    com.aistudio.mediatool.core.SettingsManager.setAudFormatIndex(context, fmtIndex)
                    com.aistudio.mediatool.core.SettingsManager.setFadeDurationSec(context, fadeDuration)
                    com.aistudio.mediatool.core.SettingsManager.setHardwareAccelIndex(context, hwAccelIndex)
                    com.aistudio.mediatool.core.SettingsManager.setNumThreadsIndex(context, numThreadsIndex)
                    com.aistudio.mediatool.core.SettingsManager.setStemModeIndex(context, stemModeIndex)
                    com.aistudio.mediatool.core.SettingsManager.setStemModelId(context, stemModeIndex, stemModelId)
                    navController.popBackStack()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("LƯU CÀI ĐẶT & THOÁT")
                }
                TextButton(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                    Text("HỦY & QUAY LẠI")
                }
            }
        }
    }
}
