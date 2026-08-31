package com.aistudio.mediatool.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.math.abs

/**
 * Chooses the cheapest safe video-trim pipeline.
 *
 * Single segment:
 * 1) FFmpeg stream-copy first: no video/audio decode and no re-encode.
 * 2) If stream-copy is not valid enough for the source/keyframe layout, try Media3 trim optimization.
 * 3) If an audio fade is requested, re-encode only audio and keep video as stream-copy.
 * 4) If optimized paths are unsupported, fall back to the compatibility FFmpeg re-encode pipeline.
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
                streamCopySingleSegmentTrim(
                    inputUri = inputUri,
                    outputFile = outputFile,
                    segment = segments.single(),
                    sourceDurationSec = sourceDurationSec,
                    expectedDurationSec = expectedDurationSec,
                    sourceHasAudio = sourceHasAudio,
                    requestedFadeSec = requestedFadeSec,
                    onProgress = onProgress,
                )
                return
            } catch (cancelled: CancellationException) {
                DiagnosticLogger.info(
                    component = "VideoTrimCoordinator",
                    event = "stream_copy_cancelled",
                    fields = mapOf("expected_duration_ms" to (expectedDurationSec * 1000.0).toLong()),
                )
                throw cancelled
            } catch (error: Exception) {
                outputFile.delete()
                DiagnosticLogger.warn(
                    component = "VideoTrimCoordinator",
                    event = "stream_copy_fallback_to_media3",
                    message = "Stream-copy không phù hợp; thử Media3 trim optimization",
                    fields = mapOf(
                        "expected_duration_ms" to (expectedDurationSec * 1000.0).toLong(),
                        "has_audio" to sourceHasAudio,
                        "fade_ms" to (requestedFadeSec.coerceAtLeast(0.0) * 1000.0).toLong(),
                    ),
                    error = error,
                )
                onProgress("Đang chuyển sang cắt video chính xác hơn...")
            }

            try {
                media3SingleSegmentTrim(
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
                DiagnosticLogger.info(
                    component = "VideoTrimCoordinator",
                    event = "media3_trim_cancelled",
                    fields = mapOf("expected_duration_ms" to (expectedDurationSec * 1000.0).toLong()),
                )
                throw cancelled
            } catch (error: Exception) {
                outputFile.delete()
                DiagnosticLogger.warn(
                    component = "VideoTrimCoordinator",
                    event = "media3_fallback_to_ffmpeg",
                    message = "Media3 trim không hoàn tất; chuyển sang FFmpeg tương thích",
                    fields = mapOf(
                        "expected_duration_ms" to (expectedDurationSec * 1000.0).toLong(),
                        "has_audio" to sourceHasAudio,
                        "fade_ms" to (requestedFadeSec.coerceAtLeast(0.0) * 1000.0).toLong(),
                    ),
                    error = error,
                )
                onProgress("Đang chuyển sang cắt video tương thích...")
            }
        } else {
            DiagnosticLogger.info(
                component = "VideoTrimCoordinator",
                event = "multi_segment_ffmpeg_route",
                fields = mapOf(
                    "segments" to segments.size,
                    "expected_duration_ms" to (expectedDurationSec * 1000.0).toLong(),
                ),
            )
        }

        try {
            ffmpegFallback(
                inputUri = inputUri,
                outputFile = outputFile,
                segments = segments,
                sourceDurationSec = sourceDurationSec,
                sourceHasAudio = sourceHasAudio,
                requestedFadeSec = requestedFadeSec,
                onProgress = onProgress,
            )
        } catch (cancelled: CancellationException) {
            DiagnosticLogger.info(
                component = "VideoTrimCoordinator",
                event = "ffmpeg_fallback_cancelled",
                fields = mapOf(
                    "segments" to segments.size,
                    "expected_duration_ms" to (expectedDurationSec * 1000.0).toLong(),
                ),
            )
            throw cancelled
        }
    }

    private suspend fun streamCopySingleSegmentTrim(
        inputUri: Uri,
        outputFile: File,
        segment: TimelineSegment,
        sourceDurationSec: Double,
        expectedDurationSec: Double,
        sourceHasAudio: Boolean,
        requestedFadeSec: Double,
        onProgress: suspend (String) -> Unit,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val fadeSec = if (sourceHasAudio) {
            AudioMath.clampedFadeDuration(requestedFadeSec, expectedDurationSec)
        } else {
            0.0
        }
        val workDir = File(appContext.cacheDir, "stream_copy_trim_${System.currentTimeMillis()}")
            .apply { mkdirs() }
        try {
            val streamCopyOutput = if (fadeSec > 0.0) File(workDir, "stream_copy.mp4") else outputFile
            val safPath = mediaEngine.getSafParameter(inputUri)
                ?: error("Không thể mở tệp đã chọn")
            val built = FastVideoTrimCommandBuilder.build(
                inputPath = safPath,
                outputPath = streamCopyOutput.absolutePath,
                segment = segment,
                sourceDurationSec = sourceDurationSec,
            )

            DiagnosticLogger.info(
                component = "VideoTrimCoordinator",
                event = "stream_copy_started",
                fields = mapOf(
                    "start_ms" to segment.startMs,
                    "end_ms" to segment.endMs,
                    "expected_duration_ms" to (built.expectedDurationSec * 1000.0).toLong(),
                    "audio_fade_only" to (fadeSec > 0.0),
                ),
            )
            onProgress("Đang cắt video siêu nhanh...")

            var succeeded = false
            mediaEngine.executeFFmpegCommand(
                built.command,
                diagnosticPhase = "trim_video_stream_copy",
            ).collect { state ->
                when (state) {
                    is MediaEngine.ExecutionState.Progress -> onProgress("Đang cắt video siêu nhanh...")
                    is MediaEngine.ExecutionState.Success -> succeeded = true
                    is MediaEngine.ExecutionState.Error -> Unit
                    else -> Unit
                }
            }
            require(succeeded && streamCopyOutput.isFile && streamCopyOutput.length() > 0L) {
                "Stream-copy không tạo được video kết quả"
            }

            val streamCopyToleranceSec = maxOf(3.0, expectedDurationSec * 0.12)
            val actualDurationSec = validateVideoOutput(
                streamCopyOutput,
                expectedDurationSec,
                toleranceSec = streamCopyToleranceSec,
            )

            if (fadeSec > 0.0) {
                applyAudioFadeOnly(
                    inputFile = streamCopyOutput,
                    outputFile = outputFile,
                    expectedDurationSec = actualDurationSec,
                    fadeSec = AudioMath.clampedFadeDuration(fadeSec, actualDurationSec),
                    route = "stream_copy",
                    onProgress = onProgress,
                )
            }

            DiagnosticLogger.info(
                component = "VideoTrimCoordinator",
                event = "stream_copy_success",
                fields = mapOf(
                    "elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt),
                    "output_bytes" to outputFile.length(),
                    "expected_duration_ms" to (expectedDurationSec * 1000.0).toLong(),
                    "actual_duration_ms" to (actualDurationSec * 1000.0).toLong(),
                    "audio_fade_only" to (fadeSec > 0.0),
                ),
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun media3SingleSegmentTrim(
        inputUri: Uri,
        outputFile: File,
        segment: TimelineSegment,
        expectedDurationSec: Double,
        sourceHasAudio: Boolean,
        requestedFadeSec: Double,
        onProgress: suspend (String) -> Unit,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val fadeSec = if (sourceHasAudio) {
            AudioMath.clampedFadeDuration(requestedFadeSec, expectedDurationSec)
        } else {
            0.0
        }
        val workDir = File(appContext.cacheDir, "media3_video_trim_${System.currentTimeMillis()}")
            .apply { mkdirs() }
        try {
            val media3Output = if (fadeSec > 0.0) File(workDir, "trimmed.mp4") else outputFile
            DiagnosticLogger.info(
                component = "VideoTrimCoordinator",
                event = "media3_trim_started",
                fields = mapOf(
                    "start_ms" to segment.startMs,
                    "end_ms" to segment.endMs,
                    "expected_duration_ms" to (expectedDurationSec * 1000.0).toLong(),
                    "audio_fade_only" to (fadeSec > 0.0),
                ),
            )
            onProgress("Đang cắt video chính xác...")

            val result = media3Trimmer.trim(
                inputUri = inputUri,
                outputFile = media3Output,
                segment = segment,
            )
            val actualDurationSec = validateVideoOutput(media3Output, expectedDurationSec)

            if (fadeSec > 0.0) {
                applyAudioFadeOnly(
                    inputFile = media3Output,
                    outputFile = outputFile,
                    expectedDurationSec = actualDurationSec,
                    fadeSec = AudioMath.clampedFadeDuration(fadeSec, actualDurationSec),
                    route = "media3",
                    onProgress = onProgress,
                )
            }

            DiagnosticLogger.info(
                component = "VideoTrimCoordinator",
                event = "media3_trim_success",
                fields = mapOf(
                    "elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt),
                    "output_bytes" to outputFile.length(),
                    "media3_reported_bytes" to result.fileSizeBytes,
                    "video_bitrate" to result.videoBitrate,
                    "audio_bitrate" to result.audioBitrate,
                    "optimization_result" to result.optimizationResult,
                    "audio_fade_only" to (fadeSec > 0.0),
                    "expected_duration_ms" to (expectedDurationSec * 1000.0).toLong(),
                    "actual_duration_ms" to (actualDurationSec * 1000.0).toLong(),
                ),
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun applyAudioFadeOnly(
        inputFile: File,
        outputFile: File,
        expectedDurationSec: Double,
        fadeSec: Double,
        route: String,
        onProgress: suspend (String) -> Unit,
    ) {
        require(fadeSec > 0.0) { "Fade phải lớn hơn 0" }
        val startedAt = SystemClock.elapsedRealtime()
        val fadeOutStart = (expectedDurationSec - fadeSec).coerceAtLeast(0.0)
        val fadeFilter = "afade=t=in:st=0:d=$fadeSec,afade=t=out:st=$fadeOutStart:d=$fadeSec"

        DiagnosticLogger.info(
            component = "VideoTrimCoordinator",
            event = "audio_fade_only_started",
            fields = mapOf(
                "route" to route,
                "fade_ms" to (fadeSec * 1000.0).toLong(),
                "expected_duration_ms" to (expectedDurationSec * 1000.0).toLong(),
            ),
        )
        onProgress("Đang hoàn thiện âm thanh...")

        var succeeded = false
        mediaEngine.executeFFmpegCommand(
            "-y -i \"${inputFile.absolutePath}\" -af \"$fadeFilter\" " +
                "-map 0:v:0 -map 0:a:0 -c:v copy -c:a aac -b:a 160k -shortest " +
                "-movflags +faststart \"${outputFile.absolutePath}\"",
            diagnosticPhase = "trim_video_audio_fade_only_$route",
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

        DiagnosticLogger.info(
            component = "VideoTrimCoordinator",
            event = "audio_fade_only_success",
            fields = mapOf(
                "route" to route,
                "elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt),
                "output_bytes" to outputFile.length(),
            ),
        )
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
        val startedAt = SystemClock.elapsedRealtime()
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

        DiagnosticLogger.info(
            component = "VideoTrimCoordinator",
            event = "ffmpeg_fallback_started",
            fields = mapOf(
                "segments" to segments.size,
                "expected_duration_ms" to (built.expectedDurationSec * 1000.0).toLong(),
            ),
        )
        onProgress("Đang cắt video tương thích...")

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
        val actualDurationSec = validateVideoOutput(outputFile, built.expectedDurationSec)
        DiagnosticLogger.info(
            component = "VideoTrimCoordinator",
            event = "ffmpeg_fallback_success",
            fields = mapOf(
                "elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt),
                "output_bytes" to outputFile.length(),
                "segments" to segments.size,
                "expected_duration_ms" to (built.expectedDurationSec * 1000.0).toLong(),
                "actual_duration_ms" to (actualDurationSec * 1000.0).toLong(),
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

    private fun validateVideoOutput(
        file: File,
        expectedDurationSec: Double,
        toleranceSec: Double = maxOf(1.0, expectedDurationSec * 0.08),
    ): Double {
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
            val actualSec = durationMs / 1000.0
            if (expectedDurationSec > 0.0) {
                require(abs(actualSec - expectedDurationSec) <= toleranceSec) {
                    "Thời lượng video kết quả sai: mong đợi khoảng %.2f giây, nhận %.2f giây"
                        .format(expectedDurationSec, actualSec)
                }
            }
            return actualSec
        } finally {
            retriever.release()
        }
    }
}
