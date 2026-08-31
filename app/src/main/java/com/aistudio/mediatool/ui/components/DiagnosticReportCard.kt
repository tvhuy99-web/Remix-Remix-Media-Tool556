package com.aistudio.mediatool.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.core.diagnostics.DiagnosticClipboardManager
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticReportManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DiagnosticReportCard(
    modifier: Modifier = Modifier,
    errorContext: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<File?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var isCopying by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!isClearing) showClearConfirmation = false },
            title = { Text("Xóa nhật ký?") },
            text = { Text("Chỉ xóa nhật ký trong ứng dụng.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        scope.launch {
                            isClearing = true
                            message = null
                            try {
                                val result = withContext(Dispatchers.IO) { DiagnosticLogger.clearLogs() }
                                report = null
                                message = "Đã xóa ${result.deletedFiles} tệp."
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                message = error.message ?: "Không thể xóa nhật ký"
                            } finally {
                                isClearing = false
                            }
                        }
                    },
                ) { Text("Xóa") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text("Hủy") } },
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Nhật ký", fontWeight = FontWeight.Bold)
            Button(
                onClick = {
                    scope.launch {
                        isCopying = true
                        message = null
                        try {
                            val snapshot = DiagnosticClipboardManager.create(context)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("MediaTool diagnostics", snapshot.text),
                            )
                            message = if (snapshot.truncated) {
                                "Đã sao chép phần nhật ký mới nhất vào bộ nhớ tạm (đã rút gọn)."
                            } else {
                                "Đã sao chép nhật ký vào bộ nhớ tạm."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = error.message ?: "Không thể sao chép nhật ký"
                        } finally {
                            isCopying = false
                        }
                    }
                },
                enabled = !isCreating && !isCopying && !isClearing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isCopying) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Đang sao chép...")
                } else {
                    Text("Sao chép nhật ký")
                }
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        isCreating = true
                        message = null
                        try {
                            report = DiagnosticReportManager.createReport(context)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = error.message ?: "Không thể tạo ZIP"
                        } finally {
                            isCreating = false
                        }
                    }
                },
                enabled = !isCreating && !isCopying && !isClearing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Đang tạo...")
                } else {
                    Text("Tạo ZIP")
                }
            }
            OutlinedButton(
                onClick = { showClearConfirmation = true },
                enabled = !isCreating && !isCopying && !isClearing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isClearing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Đang xóa...")
                } else {
                    Text("Xóa nhật ký")
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            report?.takeIf { it.isFile && it.length() > 0L }?.let { file ->
                ResultFileActions(
                    file = file,
                    saveLabel = "Lưu ZIP",
                    shareLabel = "Gửi ZIP",
                )
            }
        }
    }
}
