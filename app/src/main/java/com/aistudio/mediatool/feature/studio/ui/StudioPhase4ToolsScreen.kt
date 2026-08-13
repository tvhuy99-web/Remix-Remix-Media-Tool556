package com.aistudio.mediatool.feature.studio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StudioPhase4ToolsScreen(projectId: String, onNavigateBack: () -> Unit) {
    var pitchToolsOpen by remember(projectId) { mutableStateOf(false) }
    if (pitchToolsOpen) {
        StudioPitchToolsScreen(projectId = projectId, onNavigateBack = { pitchToolsOpen = false })
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        StudioPhase3ToolsScreen(projectId = projectId, onNavigateBack = onNavigateBack)
        ExtendedFloatingActionButton(
            onClick = { pitchToolsOpen = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 86.dp),
            text = { Text("Auto-Tune & bè") },
        )
    }
}
