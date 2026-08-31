package com.aistudio.mediatool.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.ml.OnnxAcceleration
import com.aistudio.mediatool.ui.components.AccessibleSwitchRow
import com.aistudio.mediatool.ui.components.DiagnosticReportCard
import com.aistudio.mediatool.ui.components.ToolScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    var vidIndex by rememberSaveable { mutableStateOf(SettingsManager.getVidQualityIndex(context)) }
    var audIndex by rememberSaveable { mutableStateOf(SettingsManager.getAudBitrateIndex(context)) }
    var fmtIndex by rememberSaveable { mutableStateOf(SettingsManager.getAudFormatIndex(context)) }
    var fadeEnabled by rememberSaveable { mutableStateOf(SettingsManager.isFadeEnabled(context)) }
    var fadeDuration by rememberSaveable { mutableStateOf(SettingsManager.getConfiguredFadeDurationSec(context)) }
    var numThreadsIndex by rememberSaveable { mutableStateOf(SettingsManager.getNumThreadsIndex(context)) }
    var defaultSaveTreeUri by rememberSaveable { mutableStateOf(SettingsManager.getDefaultSaveTreeUri(context)) }
    var showNotices by rememberSaveable { mutableStateOf(false) }
    val thirdPartyNotices = remember(context) {
        runCatching {
            context.assets.open("third_party_notices.txt").bufferedReader().use { it.readText() }
        }.getOrElse { "Không đọc được giấy phép." }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onSuccess {
            defaultSaveTreeUri = uri.toString()
        }
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
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccessibleSwitchRow(
                    label = "Bật chuyển âm (fade)",
                    checked = fadeEnabled,
                    onCheckedChange = { fadeEnabled = it },
                )
                if (fadeEnabled) {
                    SimpleDropdown(
                        label = "Thời lượng fade",
                        values = (1..10).map { "$it giây" },
                        selectedIndex = (fadeDuration - 1).coerceIn(0, 9),
                        onSelected = { fadeDuration = it + 1 },
                    )
                    Text("Fade chỉ được áp dụng khi công tắc trên đang bật.")
                } else {
                    Text("Fade đang tắt. Xử lý media sẽ không tự thêm chuyển âm.")
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Thư mục lưu mặc định")
                    Text(
                        defaultSaveTreeUri?.let { value ->
                            val uri = Uri.parse(value)
                            "Đã chọn: ${DocumentUtils.displayName(context, uri)}"
                        } ?: "Chưa chọn. Khi chưa có thư mục mặc định, nút Lưu vẫn mở hộp chọn tệp.",
                    )
                    Button(
                        onClick = { folderPicker.launch(defaultSaveTreeUri?.let(Uri::parse)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (defaultSaveTreeUri == null) "Chọn thư mục lưu mặc định" else "Đổi thư mục lưu mặc định")
                    }
                    if (defaultSaveTreeUri != null) {
                        OutlinedButton(
                            onClick = { defaultSaveTreeUri = null },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Bỏ thư mục lưu mặc định")
                        }
                        Text(
                            "Sau khi lưu cài đặt, nút Lưu ở các công cụ sẽ ghi thẳng vào thư mục này và không hỏi tên/vị trí nữa. " +
                                "Các công cụ còn tạo kết quả tạm vẫn cần sao chép dữ liệu khi bạn bấm Lưu.",
                        )
                    }
                }

                SimpleDropdown(
                    label = "Chất lượng video",
                    values = listOf("2 Mbps", "5 Mbps", "10 Mbps", "20 Mbps", "50 Mbps"),
                    selectedIndex = vidIndex,
                    onSelected = { vidIndex = it },
                )
                SimpleDropdown(
                    label = "Chất lượng âm thanh",
                    values = listOf("128 kbps", "192 kbps", "256 kbps", "320 kbps", "Không nén"),
                    selectedIndex = audIndex,
                    onSelected = { audIndex = it },
                )
                SimpleDropdown(
                    label = "Định dạng âm thanh",
                    values = listOf("M4A", "MP3", "WAV", "FLAC"),
                    selectedIndex = fmtIndex,
                    onSelected = { fmtIndex = it },
                )
                SimpleDropdown(
                    label = "Số luồng xử lý",
                    values = listOf("1 luồng", "2 luồng", "4 luồng", "8 luồng"),
                    selectedIndex = numThreadsIndex,
                    onSelected = { numThreadsIndex = it },
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
                        SettingsManager.setFadeEnabled(context, fadeEnabled)
                        SettingsManager.setFadeDurationSec(context, fadeDuration)
                        SettingsManager.setDefaultSaveTreeUri(context, defaultSaveTreeUri)
                        SettingsManager.setNumThreadsIndex(context, numThreadsIndex)
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
