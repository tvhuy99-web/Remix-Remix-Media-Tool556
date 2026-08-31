package com.aistudio.mediatool.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.math.abs

/**
 * Chooses the cheapest safe video-trim pipeline.
 *
 * Single segment:
 * 1) Media3 trim optimization (minimal video transcode + transmux where possible).
 * 2) If an audio fade is requested, re-encode only audio and stream-copy the already trimmed video.
 * 3) If either optimized step is unsupported, fall back to the compatibility FFmpeg pipeline.
 *
 * Multiple segments use the compatibility FFmpeg filter graph directly.
 */
class VideoTrimCoordinator(
    context: Context,
    private val mediaEngine: MediaEngine,
) {
    private val appContext = context.applicationContext
    private val media3Trimmer = Media3VideoTrimmer(appContext)

    suspend fun trim(
        inputUri: Uri,
        outputFile: File,
        segments: List<TimelineSegment>,
        sourceDurationSec: Double,
        sourceHasAudio: Boolean,
        requestedFadeSec: Double,
        onProgress: suspend (String) -> Unit,
    ) {
        require(segments.isNotEmpty()) { "Cần ít nhất một đoạn video" }
        val expectedDurationSec = expectedDuration(segments, sourceDurationSec)
        require(expectedDurationSec > 0.0) { "Tổng thời lượng các đoạn cần cắt bằng 0" }

        if (segments.size == 1) {
            try {
                optimizedSingleSegmentTrim(
                    inputUri = inputUri,
                    outputFile = outputFile,
                    segment = segments.single(),
                    expectedDurationSec = expectedDurationSec,
                    sourceHasAudio = sourceHasAudio,
                    requestedFadeSec = requestedFadeSec,
                    onProgress = onProgress,
                )
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                outputFile.delete()
                DiagnosticLogger.warn(
                    component = "VideoTrimCoordinator",
                    event = "optimized_trim_fallback",
                    message = "Cắt nhanh không hoàn tất; chuyển sang FFmpeg tương thích",
                    fields = mapOf(
                        "expected_duration_ms" to (expectedDurationSec * 1000.0).toLong(),
                        "has_audio" to sourceHasAudio,
                        "fade_ms" to (requestedFadeSec.coerceAtLeast(0.0) * 1000.0).toLong(),
                    ),
                    error = error,
                )
                onProgress("Đang chuyển sang cắt video tương thích...")
            }
        }

        ffmpegFallback(
            inputUri = inputUri,
            outputFile = outputFile,
            segments = segments,
            sourceDurationSec = sourceDurationSec,
            sourceHasAudio = sourceHasAudio,
            requestedFadeSec = requestedFadeSec,
            onProgress = onProgress,
        )
    }

    private suspend fun optimizedSingleSegmentTrim(
        inputUri: Uri,
        outputFile: File,
        segment: TimelineSegment,
        expectedDurationSec: Double,
        sourceHasAudio: Boolean,
        requestedFadeSec: Double,
        onProgress: suspend (String) -> Unit,
    ) {
        val fadeSec = if (sourceHasAudio) {
            AudioMath.clampedFadeDuration(requestedFadeSec, expectedDurationSec)
        } else {
            0.0
        }
        val workDir = File(appContext.cacheDir, "fast_video_trim_${System.currentTimeMillis()}")
            .apply { mkdirs() }
        try {
            val media3Output = if (fadeSec > 0.0) File(workDir, "trimmed.mp4") else outputFile
            onProgress("Đang cắt video nhanh...")
            val result = media3Trimmer.trim(
                inputUri = inputUri,
                outputFile = media3Output,
                segment = segment,
            )
            validateVideoOutput(media3Output, expectedDurationSec)

            if (fadeSec > 0.0) {
                onProgress("Đang hoàn thiện âm thanh...")
                val fadeOutStart = (expectedDurationSec - fadeSec).coerceAtLeast(0.0)
                val fadeFilter = "afade=t=in:st=0:d=$fadeSec,afade=t=out:st=$fadeOutStart:d=$fadeSec"
                var succeeded = false
                mediaEngine.executeFFmpegCommand(
                    "-y -i \"${media3Output.absolutePath}\" -af \"$fadeFilter\" " +
                        "-map 0:v:0 -map 0:a:0? -c:v copy -c:a aac -b:a 160k " +
                        "-movflags +faststart \"${outputFile.absolutePath}\"",
                    diagnosticPhase = "trim_video_audio_fade_only",
                ).collect { state ->
                    when (state) {
                        is MediaEngine.ExecutionState.Progress -> onProgress("Đang hoàn thiện âm thanh...")
                        is MediaEngine.ExecutionState.Success -> succeeded = true
                        is MediaEngine.ExecutionState.Error -> Unit
                        else -> Unit
                    }
                }
                require(succeeded && outputFile.isFile && outputFile.length() > 0L) {
                    "Không thể áp dụng fade âm thanh cho video"
                }
                validateVideoOutput(outputFile, expectedDurationSec)
            }

            DiagnosticLogger.info(
                component = "VideoTrimCoordinator",
                event = "optimized_trim_success",
                fields = mapOf(
                    "output_bytes" to outputFile.length(),
                    "media3_reported_bytes" to result.fileSizeBytes,
                    "video_bitrate" to result.videoBitrate,
                    "audio_bitrate" to result.audioBitrate,
                    "optimization_result" to result.optimizationResult,
                    "audio_fade_only" to (fadeSec > 0.0),
                    "expected_duration_ms" to (expectedDurationSec * 1000.0).toLong(),
                ),
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun ffmpegFallback(
        inputUri: Uri,
        outputFile: File,
        segments: List<TimelineSegment>,
        sourceDurationSec: Double,
        sourceHasAudio: Boolean,
        requestedFadeSec: Double,
        onProgress: suspend (String) -> Unit,
    ) {
        val safPath = mediaEngine.getSafParameter(inputUri)
            ?: error("Không thể mở tệp đã chọn")
        val built = TrimVideoCommandBuilder.build(
            inputPath = safPath,
            outputPath = outputFile.absolutePath,
            segments = segments,
            sourceDurationSec = sourceDurationSec,
            sourceHasAudio = sourceHasAudio,
            requestedFadeSec = requestedFadeSec,
        )

        var succeeded = false
        mediaEngine.executeFFmpegCommand(
            built.command,
            diagnosticPhase = "trim_video_ffmpeg_fallback",
        ).collect { state ->
            when (state) {
                is MediaEngine.ExecutionState.Progress -> onProgress("Đang cắt video tương thích...")
                is MediaEngine.ExecutionState.Success -> succeeded = true
                is MediaEngine.ExecutionState.Error -> Unit
                else -> Unit
            }
        }
        require(succeeded && outputFile.isFile && outputFile.length() > 0L) {
            "FFmpeg không tạo được video kết quả"
        }
        validateVideoOutput(outputFile, built.expectedDurationSec)
        DiagnosticLogger.info(
            component = "VideoTrimCoordinator",
            event = "ffmpeg_fallback_success",
            fields = mapOf(
                "output_bytes" to outputFile.length(),
                "segments" to segments.size,
                "expected_duration_ms" to (built.expectedDurationSec * 1000.0).toLong(),
            ),
        )
    }

    private fun expectedDuration(segments: List<TimelineSegment>, sourceDurationSec: Double): Double =
        segments.sumOf { segment ->
            val startSec = segment.startMs / 1000.0
            val endSec = segment.endMs?.div(1000.0)
            when {
                endSec != null && endSec > startSec -> endSec - startSec
                sourceDurationSec > startSec -> sourceDurationSec - startSec
                else -> 0.0
            }
        }

    private fun validateVideoOutput(file: File, expectedDurationSec: Double) {
        require(file.isFile && file.length() > 0L) {
            "Video kết quả không tồn tại hoặc đang rỗng"
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                .equals("yes", true)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            require(hasVideo && width > 0 && height > 0) {
                "Video kết quả không có luồng hình hợp lệ"
            }
            require(durationMs > 0L) {
                "Video kết quả không có thời lượng hợp lệ"
            }
            if (expectedDurationSec > 0.0) {
                val actualSec = durationMs / 1000.0
                val toleranceSec = maxOf(1.0, expectedDurationSec * 0.08)
                require(abs(actualSec - expectedDurationSec) <= toleranceSec) {
                    "Thời lượng video kết quả sai: mong đợi khoảng %.2f giây, nhận %.2f giây"
                        .format(expectedDurationSec, actualSec)
                }
            }
        } finally {
            retriever.release()
        }
    }
}
