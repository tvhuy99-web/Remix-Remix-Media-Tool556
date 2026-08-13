package com.aistudio.mediatool.feature.studio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.feature.studio.audio.StudioSessionRuntime
import com.aistudio.mediatool.feature.studio.audio.StudioSessionStatus
import com.aistudio.mediatool.feature.studio.audio.StudioVocalAlignmentAnalyzer
import com.aistudio.mediatool.feature.studio.audio.StudioVocalAlignmentSuggestion
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** Accessible review surface for semi-automatic vocal timing suggestions. */
@Composable
fun StudioPhase3WorkspaceScreen(projectId: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { StudioProjectRepository(context) }
    val analyzer = remember(repository) { StudioVocalAlignmentAnalyzer(repository) }
    val session by StudioSessionRuntime.state.collectAsState()
    val scope = rememberCoroutineScope()

    var panelOpen by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    var suggestion by remember { mutableStateOf<StudioVocalAlignmentSuggestion?>(null) }
    var analyzedClipId by remember { mutableStateOf<String?>(null) }
    var reviewOffsetMs by remember { mutableLongStateOf(0L) }
    var stepMs by remember { mutableLongStateOf(100L) }
    var status by remember { mutableStateOf("Chọn một đoạn giọng rồi phân tích") }
    var applied by remember { mutableStateOf(false) }

    LaunchedEffect(session.selectedClipId, panelOpen) {
        if (panelOpen && analyzedClipId != null && analyzedClipId != session.selectedClipId) {
            suggestion = null
            analyzedClipId = null
            reviewOffsetMs = 0L
            applied = false
            status = "Đoạn đang chọn đã thay đổi. Hãy phân tích lại."
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        StudioWorkspaceScreen(projectId = projectId, onNavigateBack = onNavigateBack)
        ExtendedFloatingActionButton(
            onClick = {
                suggestion = null
                analyzedClipId = null
                reviewOffsetMs = 0L
                applied = false
                status = if (session.selectedClipId == null) {
                    "Chọn một đoạn giọng trên Timeline trước"
                } else {
                    "Đã có đoạn giọng. Bấm Phân tích căn nhịp để nhận gợi ý."
                }
                panelOpen = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp),
            text = { Text("Căn nhịp") },
        )
    }

    if (panelOpen) {
        val busy = session.status == StudioSessionStatus.RECORDING || session.isBusy
        AlertDialog(
            onDismissRequest = { if (!analyzing) panelOpen = false },
            title = {
                Text(
                    "Căn giọng theo nhịp",
                    modifier = Modifier.semantics { heading() },
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 540.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Phòng thu chỉ đề xuất độ dịch. Bạn có thể chỉnh lại rồi mới Áp dụng; âm thanh gốc không bị thay đổi.",
                    )
                    Text(
                        selectedClipDescription(session.project, session.selectedClipId),
                        fontWeight = FontWeight.SemiBold,
                    )

                    Button(
                        onClick = {
                            val project = session.project
                            val clipId = session.selectedClipId
                            if (project == null || clipId == null || analyzing) {
                                status = "Chọn một đoạn giọng trên Timeline trước"
                                return@Button
                            }
                            analyzing = true
                            applied = false
                            status = "Đang nghe các điểm vào giọng và so với lưới nhịp..."
                            scope.launch {
                                runCatching { analyzer.analyze(project, clipId) }
                                    .onSuccess { result ->
                                        suggestion = result
                                        analyzedClipId = clipId
                                        reviewOffsetMs = result.offsetMillis
                                        status = alignmentAnnouncement(result)
                                    }
                                    .onFailure {
                                        suggestion = null
                                        analyzedClipId = null
                                        status = it.message ?: "Chưa thể đề xuất căn nhịp cho đoạn này"
                                    }
                                analyzing = false
                            }
                        },
                        enabled = !busy && !analyzing && session.selectedClipId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (analyzing) "Đang phân tích..." else "Phân tích căn nhịp") }

                    suggestion?.let { result ->
                        Text(
                            alignmentAnnouncement(result),
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = alignmentAnnouncement(result)
                            },
                        )

                        Text("Bước chỉnh", fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ALIGNMENT_STEPS.forEach { option ->
                                FilterChip(
                                    selected = stepMs == option.first,
                                    onClick = { stepMs = option.first },
                                    enabled = !busy && !analyzing,
                                    label = { Text(option.second) },
                                    modifier = Modifier.semantics {
                                        stateDescription = if (stepMs == option.first) "Đã chọn" else "Chưa chọn"
                                    },
                                )
                            }
                        }

                        Text(
                            "Độ dịch đang duyệt: ${formatSignedMillis(reviewOffsetMs)}",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = "Độ dịch ${formatSignedMillis(reviewOffsetMs)}"
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    reviewOffsetMs = (reviewOffsetMs - stepMs).coerceIn(-30_000L, 30_000L)
                                    status = "Độ dịch ${formatSignedMillis(reviewOffsetMs)}"
                                },
                                enabled = !busy && !analyzing,
                                modifier = Modifier.weight(1f),
                            ) { Text("Lùi ${stepLabel(stepMs)}") }
                            OutlinedButton(
                                onClick = {
                                    reviewOffsetMs = (reviewOffsetMs + stepMs).coerceIn(-30_000L, 30_000L)
                                    status = "Độ dịch ${formatSignedMillis(reviewOffsetMs)}"
                                },
                                enabled = !busy && !analyzing,
                                modifier = Modifier.weight(1f),
                            ) { Text("Tiến ${stepLabel(stepMs)}") }
                        }
                        OutlinedButton(
                            onClick = {
                                reviewOffsetMs = result.offsetMillis
                                status = "Đã quay về gợi ý ${formatSignedMillis(reviewOffsetMs)}"
                            },
                            enabled = !busy && !analyzing,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Quay về gợi ý") }

                        Button(
                            onClick = {
                                if (analyzedClipId != session.selectedClipId) {
                                    status = "Đoạn đang chọn đã thay đổi. Hãy phân tích lại."
                                    return@Button
                                }
                                StudioSessionRuntime.moveSelectedByMillis(reviewOffsetMs)
                                applied = true
                                status = "Đã áp dụng ${formatSignedMillis(reviewOffsetMs)}. Có thể Hoàn tác ngay."
                            },
                            enabled = !busy && !analyzing && analyzedClipId == session.selectedClipId,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Áp dụng độ dịch") }

                        if (applied) {
                            OutlinedButton(
                                onClick = {
                                    StudioSessionRuntime.undo()
                                    applied = false
                                    status = "Đã yêu cầu Hoàn tác lần căn nhịp vừa áp dụng"
                                },
                                enabled = !busy && session.canUndo,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Hoàn tác căn nhịp") }
                        }
                    }

                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = status
                        },
                    )
                }
            },
            confirmButton = {
                Button(onClick = { panelOpen = false }, enabled = !analyzing) {
                    Text("Xong")
                }
            },
            dismissButton = {},
        )
    }
}

private val ALIGNMENT_STEPS = listOf(
    5_000L to "5 giây",
    1_000L to "1 giây",
    100L to "100 ms",
    10L to "10 ms",
)

private fun selectedClipDescription(
    project: com.aistudio.mediatool.feature.studio.domain.StudioProject?,
    clipId: String?,
): String {
    if (project == null || clipId == null) return "Chưa chọn đoạn giọng"
    project.tracks.forEach { track ->
        val index = track.clips.indexOfFirst { it.id == clipId }
        if (index >= 0) return "Đang căn: ${track.name}, đoạn ${index + 1}"
    }
    return "Chưa chọn đoạn giọng"
}

private fun alignmentAnnouncement(value: StudioVocalAlignmentSuggestion): String =
    "Gợi ý dịch ${formatSignedMillis(value.offsetMillis)}; độ tin cậy ${(value.confidence * 100).roundToLong()}%; " +
        "sai lệch trung bình từ ${value.averageErrorBeforeMillis.roundToLong()} ms còn ${value.averageErrorAfterMillis.roundToLong()} ms. " +
        "Đã dùng ${value.onsetCount} điểm vào giọng. Chưa áp dụng."

private fun formatSignedMillis(value: Long): String = when {
    value > 0L -> "+${value} ms"
    value < 0L -> "${value} ms"
    else -> "0 ms"
}

private fun stepLabel(value: Long): String = ALIGNMENT_STEPS.firstOrNull { it.first == value }?.second ?: "$value ms"
