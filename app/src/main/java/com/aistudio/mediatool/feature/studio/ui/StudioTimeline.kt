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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.feature.studio.data.StudioWaveform
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import com.aistudio.mediatool.feature.studio.domain.latencyCompensatedPlacement
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun StudioTimeline(
    project: StudioProject,
    waveforms: Map<String, StudioWaveform>,
    transportFrame: Long,
    durationFrames: Long,
    pixelsPerSecond: Float,
    selectedClipId: String?,
    punchStartFrame: Long?,
    punchEndFrame: Long?,
    onSeek: (Long) -> Unit,
    onClipSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val timelineFrames = durationFrames.coerceAtLeast(project.timelineSampleRate.toLong())
    val durationSeconds = timelineFrames.toDouble() / project.timelineSampleRate.toDouble()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "Dòng thời gian âm thanh trực quan. Không cần kéo hoặc chạm chính xác. Dùng các nút điều hướng và phần Chỉnh đoạn thu để thao tác bằng trình đọc màn hình."
            },
    ) {
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
                punchStartFrame = punchStartFrame,
                punchEndFrame = punchEndFrame,
                onSeek = onSeek,
            )
            project.tracks.forEach { track ->
                TimelineTrackLane(
                    project = project,
                    track = track,
                    waveforms = waveforms,
                    durationFrames = timelineFrames,
                    transportFrame = transportFrame,
                    selectedClipId = selectedClipId,
                    punchStartFrame = punchStartFrame,
                    punchEndFrame = punchEndFrame,
                    width = timelineWidthDp,
                    onSeek = onSeek,
                    onClipSelected = onClipSelected,
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
                    "Các bản thu · ${track.takes.size} bản",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    track.takes.forEachIndexed { index, take ->
                        FilterChip(
                            selected = track.activeTakeId == take.id,
                            onClick = { onSelectTake(track.id, take.id) },
                            label = { Text("Bản ${index + 1}") },
                        )
                    }
                }
                if (track.clips.isNotEmpty()) {
                    Text(
                        "Phần đã ghép vẫn được giữ khi bạn đổi bản đang chọn.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
}

@Composable
private fun TimelineRuler(
    width: Dp,
    durationFrames: Long,
    sampleRate: Int,
    pixelsPerSecond: Float,
    transportFrame: Long,
    punchStartFrame: Long?,
    punchEndFrame: Long?,
    onSeek: (Long) -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val playheadColor = MaterialTheme.colorScheme.primary
    val selectedRangeColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
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
        drawSelectedRange(durationFrames, punchStartFrame, punchEndFrame, selectedRangeColor)
        val seconds = durationFrames.toDouble() / sampleRate.coerceAtLeast(1).toDouble()
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
            drawContext.canvas.nativeCanvas.drawText(
                formatRulerTime(second.toLong()),
                x + 5f,
                size.height * 0.42f,
                labelPaint,
            )
        }
        drawPlayhead(transportFrame, durationFrames, playheadColor)
    }
}

@Composable
private fun TimelineTrackLane(
    project: StudioProject,
    track: StudioTrack,
    waveforms: Map<String, StudioWaveform>,
    durationFrames: Long,
    transportFrame: Long,
    selectedClipId: String?,
    punchStartFrame: Long?,
    punchEndFrame: Long?,
    width: Dp,
    onSeek: (Long) -> Unit,
    onClipSelected: (String?) -> Unit,
) {
    val waveColor = when (track.type) {
        StudioTrackType.BEAT -> MaterialTheme.colorScheme.primary
        StudioTrackType.VOCAL -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val playheadColor = MaterialTheme.colorScheme.primary
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val selectedRangeColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
    val background = MaterialTheme.colorScheme.surface
    val regions = remember(project, track, waveforms) { buildRegions(project, track, waveforms) }

    Column(modifier = Modifier.width(width).padding(top = 4.dp)) {
        val name = friendlyTrackName(track)
        val label = when {
            track.clips.isNotEmpty() -> "$name · ${track.clips.size} đoạn"
            track.takes.isNotEmpty() -> {
                val activeIndex = track.takes.indexOfFirst { it.id == track.activeTakeId }
                if (activeIndex >= 0) {
                    "$name · Bản ${activeIndex + 1}/${track.takes.size}"
                } else {
                    "$name · ${track.takes.size} bản thu"
                }
            }
            else -> name
        }
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Canvas(
            modifier = Modifier
                .width(width)
                .height(86.dp)
                .background(background)
                .pointerInput(durationFrames, regions) {
                    detectTapGestures { offset ->
                        if (size.width <= 0) return@detectTapGestures
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val frame = (durationFrames * fraction).toLong()
                        onSeek(frame)
                        val hit = regions.lastOrNull { region ->
                            region.clipId != null && frame >= region.timelineStart && frame < region.timelineEnd
                        }
                        onClipSelected(hit?.clipId)
                    }
                },
        ) {
            val centerY = size.height / 2f
            drawLine(baselineColor, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 1f)
            drawSelectedRange(durationFrames, punchStartFrame, punchEndFrame, selectedRangeColor)
            regions.forEach { region ->
                val startX = frameToX(region.timelineStart, durationFrames, size.width)
                val endX = frameToX(region.timelineEnd, durationFrames, size.width)
                if (region.clipId != null && region.clipId == selectedClipId) {
                    drawRect(
                        selectionColor,
                        topLeft = Offset(startX, 0f),
                        size = Size((endX - startX).coerceAtLeast(1f), size.height),
                    )
                }
                drawWaveRegion(
                    region = region,
                    startX = startX,
                    endX = endX,
                    centerY = centerY,
                    color = waveColor,
                )
            }
            drawPlayhead(transportFrame, durationFrames, playheadColor)
        }
    }
}

private data class WaveRegion(
    val clipId: String?,
    val waveform: StudioWaveform,
    val sourceStartFrame: Long,
    val sourceEndFrame: Long,
    val timelineStart: Long,
    val timelineEnd: Long,
)

private fun buildRegions(
    project: StudioProject,
    track: StudioTrack,
    waveforms: Map<String, StudioWaveform>,
): List<WaveRegion> {
    if (track.type == StudioTrackType.BEAT) {
        val assetId = track.primaryAssetId ?: project.beatAssetId ?: return emptyList()
        val waveform = waveforms[assetId] ?: return emptyList()
        return listOf(
            WaveRegion(
                clipId = null,
                waveform = waveform,
                sourceStartFrame = 0L,
                sourceEndFrame = waveform.totalFrames,
                timelineStart = 0L,
                timelineEnd = framesToTimeline(
                    waveform.totalFrames,
                    waveform.sampleRate,
                    project.timelineSampleRate,
                ),
            ),
        )
    }
    if (track.clips.isNotEmpty()) {
        return track.clips.mapNotNull { clip -> clipRegion(project, clip, waveforms) }
    }
    val take = activeTake(track) ?: return emptyList()
    val waveform = waveforms[take.assetId] ?: return emptyList()
    val placement = take.latencyCompensatedPlacement(project.timelineSampleRate)
    val sourceStart = placement.sourceStartFrame.coerceIn(0L, waveform.totalFrames)
    val sourceEnd = placement.sourceEndFrame.coerceIn(sourceStart, waveform.totalFrames)
    return listOf(
        WaveRegion(
            clipId = null,
            waveform = waveform,
            sourceStartFrame = sourceStart,
            sourceEndFrame = sourceEnd,
            timelineStart = placement.timelineStartFrame,
            timelineEnd = placement.timelineStartFrame + framesToTimeline(
                sourceEnd - sourceStart,
                take.inputSampleRate,
                project.timelineSampleRate,
            ),
        ),
    )
}

private fun clipRegion(
    project: StudioProject,
    clip: StudioClip,
    waveforms: Map<String, StudioWaveform>,
): WaveRegion? {
    val waveform = waveforms[clip.sourceAssetId] ?: return null
    val asset = project.asset(clip.sourceAssetId)
    val sourceRate = asset?.sampleRate ?: waveform.sampleRate
    val sourceStart = clip.sourceStartFrame.coerceIn(0L, waveform.totalFrames)
    val sourceEnd = clip.sourceEndFrame.coerceIn(sourceStart, waveform.totalFrames)
    val timelineLength = framesToTimeline(
        sourceEnd - sourceStart,
        sourceRate,
        project.timelineSampleRate,
    )
    return WaveRegion(
        clipId = clip.id,
        waveform = waveform,
        sourceStartFrame = sourceStart,
        sourceEndFrame = sourceEnd,
        timelineStart = clip.timelineStartFrame,
        timelineEnd = clip.timelineStartFrame + timelineLength,
    )
}

private fun activeTake(track: StudioTrack): StudioTake? =
    track.activeTakeId?.let { id -> track.takes.firstOrNull { it.id == id } }
        ?: track.takes.lastOrNull()

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveRegion(
    region: WaveRegion,
    startX: Float,
    endX: Float,
    centerY: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    if (region.waveform.pointCount <= 0) return
    val widthPx = (endX - startX).coerceAtLeast(1f)
    val pixelCount = widthPx.roundToInt().coerceAtLeast(1)
    val sourceLength = (region.sourceEndFrame - region.sourceStartFrame).coerceAtLeast(1L)
    val stride = max(1, pixelCount / 2_500)
    var pixel = 0
    while (pixel <= pixelCount) {
        val fraction = pixel.toDouble() / pixelCount.toDouble()
        val sourceFrame = region.sourceStartFrame + (sourceLength * fraction).toLong()
        val point = (sourceFrame / region.waveform.framesPerPoint.toLong())
            .toInt()
            .coerceIn(0, region.waveform.pointCount - 1)
        val minValue = region.waveform.minima[point].toFloat() / 32768f
        val maxValue = region.waveform.maxima[point].toFloat() / 32768f
        val x = startX + pixel.toFloat()
        drawLine(
            color = color,
            start = Offset(x, centerY - maxValue * centerY * 0.88f),
            end = Offset(x, centerY - minValue * centerY * 0.88f),
            strokeWidth = 1f,
        )
        pixel += stride
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectedRange(
    durationFrames: Long,
    startFrame: Long?,
    endFrame: Long?,
    color: androidx.compose.ui.graphics.Color,
) {
    val start = startFrame ?: return
    val end = endFrame ?: return
    if (end <= start || durationFrames <= 0L) return
    val left = frameToX(start, durationFrames, size.width)
    val right = frameToX(end, durationFrames, size.width)
    drawRect(color, Offset(left, 0f), Size((right - left).coerceAtLeast(1f), size.height))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlayhead(
    frame: Long,
    durationFrames: Long,
    color: androidx.compose.ui.graphics.Color,
) {
    if (durationFrames <= 0L) return
    val x = frameToX(frame, durationFrames, size.width)
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
}

private fun frameToX(frame: Long, durationFrames: Long, width: Float): Float {
    if (durationFrames <= 0L || width <= 0f) return 0f
    return (frame.coerceIn(0L, durationFrames).toDouble() / durationFrames.toDouble() * width).toFloat()
}

private fun framesToTimeline(frames: Long, sourceRate: Int, timelineRate: Int): Long {
    if (frames <= 0L || sourceRate <= 0 || timelineRate <= 0) return 0L
    return (frames.toDouble() * timelineRate.toDouble() / sourceRate.toDouble()).toLong()
}

private fun friendlyTrackName(track: StudioTrack): String = when {
    track.type == StudioTrackType.BEAT -> "Nhạc nền"
    track.name.isNotBlank() && !track.name.equals("Vocal", ignoreCase = true) -> track.name
    track.type == StudioTrackType.VOCAL -> "Giọng chính"
    track.type == StudioTrackType.BACKING_VOCAL -> "Giọng bè"
    track.type == StudioTrackType.ADLIB -> "Giọng phụ"
    track.type == StudioTrackType.INSTRUMENT -> "Nhạc cụ"
    else -> "Âm thanh khác"
}

private fun formatRulerTime(seconds: Long): String {
    val minutes = seconds / 60L
    val rest = seconds % 60L
    return "%d:%02d".format(minutes, rest)
}
