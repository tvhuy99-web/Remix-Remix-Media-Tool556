package com.aistudio.mediatool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.aistudio.mediatool.core.diagnostics.DiagnosticReportManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DiagnosticReportCard(
    modifier: Modifier = Modifier,
    errorContext: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<File?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Nhật ký chẩn đoán", fontWeight = FontWeight.Bold)
            Button(
                onClick = {
                    scope.launch {
                        isCreating = true
                        createError = null
                        try {
                            report = DiagnosticReportManager.createReport(context)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            createError = error.message ?: "Không thể tạo gói nhật ký"
                        } finally {
                            isCreating = false
                        }
                    }
                },
                enabled = !isCreating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Đang tạo...")
                } else {
                    Text(if (report == null) "Tạo gói nhật ký" else "Tạo gói mới")
                }
            }
            createError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            report?.takeIf { it.isFile && it.length() > 0L }?.let { file ->
                Text(
                    "Đã tạo: ${file.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                ResultFileActions(
                    file = file,
                    saveLabel = "Lưu ZIP",
                    shareLabel = "Gửi ZIP",
                )
            }
        }
    }
}
