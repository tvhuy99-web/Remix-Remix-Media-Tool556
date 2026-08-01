package com.aistudio.mediatool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class ToolEntry(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val action: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToRecord: () -> Unit,
    onNavigateToTrim: () -> Unit,
    onNavigateToJoin: () -> Unit,
    onNavigateToMix: () -> Unit,
    onNavigateToImg2Vid: () -> Unit,
    onNavigateToSub: () -> Unit,
    onNavigateToStem: () -> Unit,
    onNavigateToOther: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val tools = listOf(
        ToolEntry("Ghi âm", "Microphone hoặc âm thanh hệ thống", Icons.Default.Mic, onNavigateToRecord),
        ToolEntry("Cắt media", "Cắt một hoặc nhiều đoạn audio/video", Icons.Default.ContentCut, onNavigateToTrim),
        ToolEntry("Nối audio", "Nối nhiều tệp theo thứ tự đã chọn", Icons.Default.Add, onNavigateToJoin),
        ToolEntry("Trộn nhiều luồng", "Ghép nhạc nền, pan và tự động ducking", Icons.Default.MusicNote, onNavigateToMix),
        ToolEntry("Hiệu ứng và trích xuất", "Đổi định dạng, lọc âm, nén video, lấy ảnh", Icons.Default.Tune, onNavigateToOther),
        ToolEntry("Tạo video từ ảnh", "Ghép ảnh và âm thanh thành video MP4", Icons.Default.Image, onNavigateToImg2Vid),
        ToolEntry("Phụ đề", "Trích xuất SRT và đọc phụ đề bằng TTS", Icons.Default.Subtitles, onNavigateToSub),
        ToolEntry("Tách nhạc bằng AI", "Tách lời, nhạc nền hoặc bốn stem trên máy", Icons.Default.GraphicEq, onNavigateToStem),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MediaTool", fontWeight = FontWeight.Bold)
                        Text(
                            "Xử lý audio và video trên thiết bị",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            tools.forEach { tool ->
                ToolEntryCard(tool)
            }

            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Card(
                onClick = onNavigateToSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cài đặt", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Định dạng, chất lượng và cấu hình xử lý AI",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolEntryCard(tool: ToolEntry) {
    Card(
        onClick = tool.action,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(tool.title, fontWeight = FontWeight.SemiBold)
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
