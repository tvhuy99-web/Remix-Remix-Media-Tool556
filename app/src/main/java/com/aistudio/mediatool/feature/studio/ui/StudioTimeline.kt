package com.aistudio.mediatool.feature.studio.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.feature.studio.data.StudioWaveform
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import kotlin.math.ceil
import kotlin.math.max

@Composable
fun StudioTimeline(
    project: StudioProject,
    waveforms: Map<String, StudioWaveform>,
    transportFrame: Long,
    durationFrames: Long,
    pixelsPerSecond: Float,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val timelineFrames = durationFrames.coerceAtLeast(project.timelineSampleRate.toLong())
    val durationSeconds = timelineFrames.toDouble() / project.timelineSampleRate.toDouble()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val timelineWidthDp = max(
            maxWidth.value,
            (durationSeconds * pixelsPerSecond.coerceAtLeast(12f)).toFloat(),
        ).dp
        val viewportPx = with(density) { maxWidth.toPx() }
        val playheadPx = with(density) {
            ((transportFrame.toDouble() / project.timelineSampleRate.toDouble()) * pixelsPerSecond).dp.toPx()
        }

        LaunchedEffect(transportFrame, pixelsPerSecond, scrollState.maxValue) {
            if (scrollState.maxValue <= 0 || viewportPx <= 0f) return@LaunchedEffect
            val left = scrollState.value.toFloat()
            val right = left + viewportPx
            if (playheadPx > right - viewportPx * 0.15f || playheadPx < left) {
                val target = (playheadPx - viewportPx * 0.2f)
                    .toInt()
                    .coerceIn(0, scrollState.maxValue)
                scrollState.scrollTo(target)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        ) {
            TimelineRuler(
                width = timelineWidthDp,
                durationFrames = timelineFrames,
                sampleRate = project.timelineSampleRate,
                pixelsPerSecond = pixelsPerSecond,
                transportFrame = transportFrame,
                onSeek = onSeek,
            )
            project.tracks.forEach { track ->
                val active = activeTrackSource(project, track)
                TimelineTrackLane(
                    track = track,
                    waveform = active?.assetId?.let(waveforms::get),
                    sourceTake = active?.take,
                    projectSampleRate = project.timelineSampleRate,
                    durationFrames = timelineFrames,
                    transportFrame = transportFrame,
                    width = timelineWidthDp,
                    onSeek = onSeek,
                )
            }
        }
    }
}

@Composable
fun StudioTakeSelector(
    project: StudioProject,
    onSelectTake: (trackId: String, takeId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        project.tracks
            .filter { it.type == StudioTrackType.VOCAL && it.takes.isNotEmpty() }
            .forEach { track ->
                Text(
                    "${track.name}: ${track.takes.size} take",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    track.takes.forEachIndexed { index, take ->
                        AssistChip(
                            onClick = { onSelectTake(track.id, take.id) },
                            label = {
                                Text(
                                    if (track.activeTakeId == take.id) {
                                        "Take ${index + 1} ✓"
                                    } else {
                                        "Take ${index + 1}"
                                    },
                                )
                            },
                        )
                    }
                }
            }
    }
}

@Composable
private fun TimelineRuler(
    width: androidx.compose.ui.unit.Dp,
    durationFrames: Long,
    sampleRate: Int,
    pixelsPerSecond: Float,
    transportFrame: Long,
    onSeek: (Long) -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val playheadColor = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val labelPaint = remember(textColor) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor.toArgb()
            textSize = 28f
        }
    }
    Canvas(
        modifier = Modifier
            .width(width)
            .height(42.dp)
            .background(background)
            .pointerInput(durationFrames) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek((durationFrames * fraction).toLong())
                    }
                }
            },
    ) {
        val seconds = durationFrames.toDouble() / sampleRate.toDouble()
        val majorInterval = when {
            pixelsPerSecond >= 110f -> 1
            pixelsPerSecond >= 55f -> 5
            else -> 10
        }
        val majorCount = ceil(seconds / majorInterval).toInt()
        repeat(majorCount + 1) { tick ->
            val second = tick * majorInterval
            val x = ((second / seconds.coerceAtLeast(0.001)) * size.width).toFloat()
            drawLine(lineColor, Offset(x, size.height * 0.55f), Offset(x, size.height), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(formatTime(second.toLong()), x + 5f, size.height * 0.42f, labelPaint)
        }
        val playheadX = (transportFrame.toDouble() / durationFrames.toDouble() * size.width).toFloat()
            .coerceIn(0f, size.width)
        drawLine(playheadColor, Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 2f)
    }
}

@Composable
private fun TimelineTrackLane(
    track: StudioTrack,
    waveform: StudioWaveform?,
    sourceTake: StudioTake?,
    projectSampleRate: Int,
    durationFrames: Long,
    transportFrame: Long,
    width: androidx.compose.ui.unit.Dp,
    onSeek: (Long) -> Unit,
) {
    val waveColor = when (track.type) {
        StudioTrackType.BEAT -> MaterialTheme.colorScheme.primary
        StudioTrackType.VOCAL -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val playheadColor = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surface
    Column(
        modifier = Modifier
            .width(width)
            .padding(top = 4.dp),
    ) {
        val takeLabel = if (track.takes.isNotEmpty()) {
            val activeIndex = track.takes.indexOfFirst { it.id == track.activeTakeId }
            if (activeIndex >= 0) " • Take ${activeIndex + 1}/${track.takes.size}" else " • ${track.takes.size} take"
        } else {
            ""
        }
        Text(
            text = track.name + takeLabel,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Canvas(
            modifier = Modifier
                .width(width)
                .height(82.dp)
                .background(background)
                .pointerInput(durationFrames) {
                    detectTapGestures { offset ->
                        if (size.width > 0) {
                            val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeek((durationFrames * fraction).toLong())
                        }
                    }
                },
        ) {
            val centerY = size.height / 2f
            drawLine(baselineColor, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 1f)
            if (waveform != null && waveform.pointCount > 0) {
                val sourceStartTimeline = sourceTake
                    ?.let { (it.recordedTimelineFrame - it.latencyCompensationFrames).coerceAtLeast(0L) }
                    ?: 0L
                val sourceDurationTimeline = framesToTimeline(
                    waveform.totalFrames,
                    waveform.sampleRate,
                    projectSampleRate,
                )
                val startX = (sourceStartTimeline.toDouble() / durationFrames.toDouble() * size.width).toFloat()
                val assetWidth = (sourceDurationTimeline.toDouble() / durationFrames.toDouble() * size.width)
                    .toFloat()
                    .coerceAtLeast(1f)
                val targetLines = assetWidth.toInt().coerceIn(1, 12_000)
                val stride = max(1, waveform.pointCount / targetLines)
                val halfHeight = size.height * 0.42f
                var point = 0
                while (point < waveform.pointCount) {
                    val x = startX + (point.toFloat() / waveform.pointCount.toFloat()) * assetWidth
                    if (x >= 0f && x <= size.width) {
                        val minimum = waveform.minima[point].toFloat() / 32768f
                        val maximum = waveform.maxima[point].toFloat() / 32768f
                        val top = centerY - maximum * halfHeight
                        val bottom = centerY - minimum * halfHeight
                        drawLine(waveColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
                    }
                    point += stride
                }
            }
            val playheadX = (transportFrame.toDouble() / durationFrames.toDouble() * size.width).toFloat()
                .coerceIn(0f, size.width)
            drawLine(playheadColor, Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 2f)
        }
    }
}

private data class ActiveTrackSource(
    val assetId: String,
    val take: StudioTake?,
)

private fun activeTrackSource(project: StudioProject, track: StudioTrack): ActiveTrackSource? {
    if (track.type == StudioTrackType.BEAT) {
        return track.primaryAssetId?.let { ActiveTrackSource(it, null) }
    }
    val take = track.activeTakeId?.let { activeId -> track.takes.firstOrNull { it.id == activeId } }
        ?: track.takes.lastOrNull()
    return take?.let { ActiveTrackSource(it.assetId, it) }
        ?: track.primaryAssetId?.let { ActiveTrackSource(it, null) }
}

private fun framesToTimeline(frames: Long, sourceRate: Int, timelineRate: Int): Long {
    if (frames <= 0L || sourceRate <= 0 || timelineRate <= 0) return 0L
    return (frames.toDouble() * timelineRate.toDouble() / sourceRate.toDouble()).toLong()
}

private fun formatTime(totalSeconds: Long): String =
    "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
