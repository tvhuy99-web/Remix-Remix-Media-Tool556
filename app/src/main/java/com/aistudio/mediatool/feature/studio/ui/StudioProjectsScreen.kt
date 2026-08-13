package com.aistudio.mediatool.feature.studio.ui

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.ui.components.ToolScaffold
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StudioProjectsScreen(
    onNavigateBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenLab: (String) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { StudioProjectRepository(context) }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<StudioProject>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        projects = withContext(Dispatchers.IO) { repository.listProjects() }
    }

    val beatPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || importing) return@rememberLauncherForActivityResult
        importing = true
        errorMessage = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.createFromBeat(uri) }
            }.onSuccess { project ->
                importing = false
                projects = withContext(Dispatchers.IO) { repository.listProjects() }
                onOpenProject(project.id)
            }.onFailure { error ->
                importing = false
                errorMessage = error.message ?: "Không thể tạo bài mới"
            }
        }
    }

    ToolScaffold(
        title = "Phòng thu",
        onNavigateBack = onNavigateBack,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Bài hát của bạn",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )

            Button(
                onClick = { beatPicker.launch(arrayOf("audio/*")) },
                enabled = !importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 12.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Đang tạo bài...")
                } else {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  Tạo bài mới từ nhạc nền")
                }
            }

            errorMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            if (!importing && projects.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null)
                        Text("Chưa có bài nào", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Chọn một nhạc nền để bắt đầu thu giọng.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            projects.forEach { project ->
                ProjectCard(
                    project = project,
                    updatedDate = if (project.updatedAt > 0L) {
                        DateFormat.getMediumDateFormat(context).format(Date(project.updatedAt))
                    } else {
                        null
                    },
                    onOpenProject = { onOpenProject(project.id) },
                    onOpenTools = { onOpenLab(project.id) },
                )
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: StudioProject,
    updatedDate: String?,
    onOpenProject: () -> Unit,
    onOpenTools: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(project.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        project.beatAsset()?.displayName ?: "Chưa có nhạc nền",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    updatedDate?.let {
                        Text(
                            "Chỉnh sửa $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Button(
                onClick = onOpenProject,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Mở phòng thu")
            }
            OutlinedButton(
                onClick = onOpenTools,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Chỉnh âm thanh")
            }
        }
    }
}
