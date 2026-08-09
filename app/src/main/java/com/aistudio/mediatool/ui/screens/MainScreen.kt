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
import androidx.compose.material.icons.filled.VolumeUp
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
    val icon: ImageVector,
    val action: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToStudio: () -> Unit,
    onNavigateToRecord: () -> Unit,
    onNavigateToTrim: () -> Unit,
    onNavigateToJoin: () -> Unit,
    onNavigateToMix: () -> Unit,
    onNavigateToImg2Vid: () -> Unit,
    onNavigateToSub: () -> Unit,
    onNavigateToStem: () -> Unit,
    onNavigateToVoiceCleanup: () -> Unit,
    onNavigateToOther: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val tools = listOf(
        ToolEntry("Phòng thu", Icons.Default.MusicNote, onNavigateToStudio),
        ToolEntry("Ghi âm", Icons.Default.Mic, onNavigateToRecord),
        ToolEntry("Cắt", Icons.Default.ContentCut, onNavigateToTrim),
        ToolEntry("Nối âm thanh", Icons.Default.Add, onNavigateToJoin),
        ToolEntry("Trộn âm thanh", Icons.Default.MusicNote, onNavigateToMix),
        ToolEntry("Công cụ khác", Icons.Default.Tune, onNavigateToOther),
        ToolEntry("Tạo video", Icons.Default.Image, onNavigateToImg2Vid),
        ToolEntry("Phụ đề", Icons.Default.Subtitles, onNavigateToSub),
        ToolEntry("Tách nhạc", Icons.Default.GraphicEq, onNavigateToStem),
        ToolEntry("Làm sạch giọng", Icons.Default.VolumeUp, onNavigateToVoiceCleanup),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MediaTool", fontWeight = FontWeight.Bold) },
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
            tools.forEach { tool -> ToolEntryCard(tool) }

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
                    Text("Cài đặt", fontWeight = FontWeight.SemiBold)
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
            Text(tool.title, fontWeight = FontWeight.SemiBold)
        }
    }
}
