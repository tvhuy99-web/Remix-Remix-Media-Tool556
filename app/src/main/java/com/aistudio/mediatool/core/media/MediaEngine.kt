package com.aistudio.mediatool.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import com.aistudio.mediatool.BuildConfig
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class MediaEngine(private val context: Context) {
    sealed class ExecutionState {
        data object Connecting : ExecutionState()
        data class Progress(val timeInMilliseconds: Long, val size: Long, val bitrate: Double) : ExecutionState()
        data class Success(val outputLog: String) : ExecutionState()
        data class Error(val returnCode: ReturnCode?, val failStackTrace: String?, val logs: String?) : ExecutionState()
    }

    data class MaterializedInput(
        val bytes: Long,
        val transport: String,
    )

    private val registeredReadUris = ConcurrentHashMap<String, Uri>()

    fun executeFFmpegCommand(
        command: String,
        diagnosticPhase: String = "media_command",
        startupTimeoutMs: Long? = null,
    ): Flow<ExecutionState> {
        if (diagnosticPhase == VIDEO_COMPRESSION_PHASE && !isShortPreviewCommand(command)) {
            return executeHardwareVideoCompression(command, diagnosticPhase)
        }
        return executeNativeFFmpegCommand(command, diagnosticPhase, startupTimeoutMs)
    }

    private fun executeNativeFFmpegCommand(
        command: String,
        diagnosticPhase: String,
        startupTimeoutMs: Long?,
    ): Flow<ExecutionState> = callbackFlow {
        val taskId = UUID.randomUUID().toString()
        val sanitization = MediaCommandSanitizer.sanitize(command)
        val effectiveCommand = sanitization.command
        val commandId = DiagnosticRedactor.stableId(effectiveCommand)
        val startedAt = SystemClock.elapsedRealtime()
        val terminal = AtomicBoolean(false)
        val meaningfulProgress = AtomicBoolean(false)
        val lastMediaTimeMs = AtomicLong(0L)
        val lastOutputBytes = AtomicLong(0L)
        val startupWatchdog = AtomicReference<Job?>(null)
        val keepAliveHeld = AtomicBoolean(false)

        fun releaseKeepAlive(reason: String) {
            if (keepAliveHeld.compareAndSet(true, false)) {
                MediaProcessingForegroundController.release(context, taskId, reason)
            }
        }

        MediaProcessingForegroundController.acquire(
            context = context,
            taskId = taskId,
            label = backgroundLabel(diagnosticPhase),
        )
        keepAliveHeld.set(true)

        DiagnosticLogger.info(
            component = TAG,
            event = "ffmpeg_start",
            sessionId = taskId,
            fields = mapOf(
                "phase" to diagnosticPhase,
                "command_id" to commandId,
                "has_audio_filter" to effectiveCommand.contains("-af "),
                "has_video_filter" to effectiveCommand.contains("-vf "),
                "has_filter_complex" to effectiveCommand.contains("-filter_complex"),
                "command_adjustment_count" to sanitization.adjustments.size,
                "command_adjustments" to sanitization.adjustments.sorted().joinToString(","),
                "startup_timeout_ms" to startupTimeoutMs,
                "foreground_keepalive" to true,
            ),
        )
        trySend(ExecutionState.Connecting)
        var session: FFmpegSession? = null

        fun cancelStartupWatchdog() {
            startupWatchdog.getAndSet(null)?.cancel()
        }

        fun reportStartFailure(error: Throwable) {
            cancelStartupWatchdog()
            terminal.set(true)
            releaseKeepAlive("start_failure")
            val cause = if (BuildConfig.DEBUG) {
                generateSequence(error) { it.cause }
                    .joinToString(" -> ") { it.message ?: it.javaClass.simpleName }
            } else {
                when (error) {
                    is LinkageError -> "Thành phần xử lý media chưa được đóng gói đầy đủ"
                    else -> error.message ?: "Không thể khởi động FFmpeg"
                }
            }.let { DiagnosticRedactor.sanitize(it).orEmpty() }
            DiagnosticLogger.error(
                component = TAG,
                event = "ffmpeg_start_failed",
                sessionId = taskId,
                message = cause,
                fields = mapOf(
                    "phase" to diagnosticPhase,
                    "command_id" to commandId,
                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                    "failure_type" to error.javaClass.name,
                ),
                error = error,
            )
            trySend(ExecutionState.Error(null, cause, null))
            close()
        }

        try {
            session = FFmpegKit.executeAsync(
                effectiveCommand,
                { completed ->
                    if (!terminal.compareAndSet(false, true)) return@executeAsync
                    cancelStartupWatchdog()
                    val returnCode = completed.returnCode
                    val logs = safeLogs(completed.logsAsString)
                    when {
                        ReturnCode.isSuccess(returnCode) -> {
                            DiagnosticLogger.info(
                                component = TAG,
                                event = "ffmpeg_success",
                                sessionId = taskId,
                                fields = completionFields(
                                    diagnosticPhase,
                                    commandId,
                                    returnCode.toString(),
                                    startedAt,
                                    lastMediaTimeMs.get(),
                                    lastOutputBytes.get(),
                                ),
                            )
                            trySend(ExecutionState.Success(logs.orEmpty()))
                            releaseKeepAlive("success")
                        }
                        ReturnCode.isCancel(returnCode) -> {
                            DiagnosticLogger.info(
                                component = TAG,
                                event = "ffmpeg_cancelled",
                                sessionId = taskId,
                                fields = completionFields(
                                    diagnosticPhase,
                                    commandId,
                                    returnCode.toString(),
                                    startedAt,
                                    lastMediaTimeMs.get(),
                                    lastOutputBytes.get(),
                                ),
                            )
                            trySend(ExecutionState.Error(returnCode, "Đã hủy", logs))
                            releaseKeepAlive("ffmpeg_cancelled")
                        }
                        else -> {
                            DiagnosticLogger.error(
                                component = TAG,
                                event = "ffmpeg_failed",
                                sessionId = taskId,
                                fields = completionFields(
                                    diagnosticPhase,
                                    commandId,
                                    returnCode.toString(),
                                    startedAt,
                                    lastMediaTimeMs.get(),
                                    lastOutputBytes.get(),
                                ) + mapOf(
                                    "ffmpeg_tail" to DiagnosticRedactor.sanitizeFfmpegLogs(completed.logsAsString),
                                    "failure" to DiagnosticRedactor.sanitize(completed.failStackTrace),
                                ),
                            )
                            trySend(
                                ExecutionState.Error(
                                    returnCode,
                                    if (BuildConfig.DEBUG) {
                                        DiagnosticRedactor.sanitize(completed.failStackTrace)
                                    } else {
                                        "Lệnh FFmpeg thất bại"
                                    },
                                    logs,
                                ),
                            )
                            releaseKeepAlive("ffmpeg_failed")
                        }
                    }
                    close()
                },
                null,
                { statistics ->
                    lastMediaTimeMs.set(statistics.time.toLong().coerceAtLeast(0L))
                    lastOutputBytes.set(statistics.size.toLong().coerceAtLeast(0L))
                    if (lastMediaTimeMs.get() > 0L || lastOutputBytes.get() > 0L) {
                        meaningfulProgress.set(true)
                        cancelStartupWatchdog()
                    }
                    trySend(
                        ExecutionState.Progress(
                            timeInMilliseconds = statistics.time.toLong().coerceAtLeast(0L),
                            size = statistics.size.toLong().coerceAtLeast(0L),
                            bitrate = statistics.bitrate,
                        ),
                    )
                },
            )

            startupTimeoutMs
                ?.takeIf { it > 0L }
                ?.let { timeoutMs ->
                    val watchdog = launch {
                        delay(timeoutMs)
                        if (!meaningfulProgress.get() && terminal.compareAndSet(false, true)) {
                            val nativeSessionId = session?.sessionId
                            session?.let { FFmpegKit.cancel(it.sessionId) }
                            val message = "FFmpeg không bắt đầu đọc nguồn trong ${timeoutMs / 1_000L} giây"
                            DiagnosticLogger.error(
                                component = TAG,
                                event = "ffmpeg_startup_timeout",
                                sessionId = taskId,
                                message = message,
                                fields = mapOf(
                                    "phase" to diagnosticPhase,
                                    "command_id" to commandId,
                                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                                    "media_time_ms" to lastMediaTimeMs.get(),
                                    "output_bytes" to lastOutputBytes.get(),
                                    "ffmpeg_session_id" to nativeSessionId,
                                    "startup_timeout_ms" to timeoutMs,
                                ),
                            )
                            trySend(ExecutionState.Error(null, message, null))
                            releaseKeepAlive("startup_timeout")
                            close()
                        }
                    }
                    startupWatchdog.set(watchdog)
                    if (terminal.get() || meaningfulProgress.get()) cancelStartupWatchdog()
                }

            if (terminal.get()) session?.let { FFmpegKit.cancel(it.sessionId) }
        } catch (cancelled: CancellationException) {
            cancelStartupWatchdog()
            terminal.set(true)
            releaseKeepAlive("collector_coroutine_cancelled")
            throw cancelled
        } catch (error: LinkageError) {
            reportStartFailure(error)
        } catch (error: Exception) {
            reportStartFailure(error)
        }

        awaitClose {
            cancelStartupWatchdog()
            if (terminal.compareAndSet(false, true)) {
                session?.let { FFmpegKit.cancel(it.sessionId) }
                releaseKeepAlive("collector_closed")
                DiagnosticLogger.info(
                    component = TAG,
                    event = "ffmpeg_collector_cancelled",
                    sessionId = taskId,
                    fields = mapOf(
                        "phase" to diagnosticPhase,
                        "command_id" to commandId,
                        "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                        "media_time_ms" to lastMediaTimeMs.get(),
                        "output_bytes" to lastOutputBytes.get(),
                        "ffmpeg_session_id" to session?.sessionId,
                    ),
                )
            } else {
                releaseKeepAlive("collector_closed_after_terminal")
            }
        }
    }

    /**
     * OtherScreen mode 4 used a full software MPEG-4 encode. For a full-file compression request,
     * transparently replace that route with Media3/MediaCodec while preserving OtherScreen's
     * existing progress/result contract. Short 10-second previews intentionally stay on FFmpeg.
     */
    private fun executeHardwareVideoCompression(
        originalCommand: String,
        diagnosticPhase: String,
    ): Flow<ExecutionState> = callbackFlow {
        val taskId = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        val inputParameter = INPUT_PATH_REGEX.find(originalCommand)?.groupValues?.getOrNull(1)
        val inputUri = inputParameter?.let(registeredReadUris::get)
        val outputPath = OUTPUT_PATH_REGEX.find(originalCommand)?.groupValues?.getOrNull(1)
        val outputFile = outputPath?.let(::File)

        if (inputUri == null || outputFile == null) {
            DiagnosticLogger.warn(
                component = TAG,
                event = "hardware_compression_route_unavailable",
                sessionId = taskId,
                message = "Không xác định được URI nguồn hoặc đường dẫn đầu ra; dùng FFmpeg dự phòng",
            )
            val fallback = launch {
                executeNativeFFmpegCommand(
                    originalCommand,
                    "${diagnosticPhase}_software_fallback",
                    null,
                ).collect { state -> trySend(state) }
                close()
            }
            awaitClose { fallback.cancel() }
            return@callbackFlow
        }

        val keepAliveHeld = AtomicBoolean(false)
        fun releaseKeepAlive(reason: String) {
            if (keepAliveHeld.compareAndSet(true, false)) {
                MediaProcessingForegroundController.release(context, taskId, reason)
            }
        }

        MediaProcessingForegroundController.acquire(context, taskId, "Đang nén video bằng phần cứng")
        keepAliveHeld.set(true)
        trySend(ExecutionState.Connecting)

        val job = launch {
            try {
                val metadata = readVideoCompressionMetadata(inputUri)
                val requestedHeight = TARGET_HEIGHT_REGEX.find(originalCommand)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                val targetHeight = requestedHeight?.takeIf { metadata.height <= 0 || metadata.height > it }
                val qValue = Q_VALUE_REGEX.find(originalCommand)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: 10
                val qualityPercent = (((31 - qValue).coerceIn(0, 30) * 100.0) / 30.0)
                    .roundToInt()
                    .coerceIn(10, 100)
                val targetBitrate = VideoCompressionPolicy.targetVideoBitrate(
                    sourceBitrate = metadata.bitrate,
                    sourceHeight = metadata.height,
                    outputHeight = targetHeight,
                    qualityPercent = qualityPercent,
                )

                DiagnosticLogger.info(
                    component = TAG,
                    event = "hardware_compression_started",
                    sessionId = taskId,
                    fields = mapOf(
                        "phase" to diagnosticPhase,
                        "duration_ms" to metadata.durationMs,
                        "source_height" to metadata.height,
                        "source_bitrate" to metadata.bitrate,
                        "output_height" to targetHeight,
                        "quality_percent" to qualityPercent,
                        "target_bitrate" to targetBitrate,
                        "route" to "media3_mediacodec",
                        "foreground_keepalive" to true,
                    ),
                )

                val result = Media3VideoCompressor(context).compress(
                    inputUri = inputUri,
                    outputFile = outputFile,
                    targetHeight = targetHeight,
                    targetVideoBitrate = targetBitrate,
                ) { percent ->
                    val estimatedMediaTime = if (metadata.durationMs > 0L) {
                        metadata.durationMs * percent / 100L
                    } else {
                        0L
                    }
                    MediaProcessingForegroundController.update(
                        context,
                        taskId,
                        "Đang nén video bằng phần cứng: $percent%",
                    )
                    trySend(
                        ExecutionState.Progress(
                            timeInMilliseconds = estimatedMediaTime,
                            size = outputFile.length().coerceAtLeast(0L),
                            bitrate = targetBitrate / 1000.0,
                        ),
                    )
                }

                DiagnosticLogger.info(
                    component = TAG,
                    event = "hardware_compression_success",
                    sessionId = taskId,
                    fields = mapOf(
                        "phase" to diagnosticPhase,
                        "elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt),
                        "output_bytes" to outputFile.length(),
                        "reported_bytes" to result.fileSizeBytes,
                        "average_video_bitrate" to result.averageVideoBitrate,
                        "average_audio_bitrate" to result.averageAudioBitrate,
                        "target_bitrate" to targetBitrate,
                    ),
                )
                trySend(ExecutionState.Success("Media3 hardware compression completed"))
                releaseKeepAlive("hardware_compression_success")
                close()
            } catch (cancelled: CancellationException) {
                outputFile.delete()
                releaseKeepAlive("hardware_compression_cancelled")
                DiagnosticLogger.info(
                    component = TAG,
                    event = "hardware_compression_cancelled",
                    sessionId = taskId,
                    fields = mapOf("elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt)),
                )
                throw cancelled
            } catch (error: Throwable) {
                outputFile.delete()
                DiagnosticLogger.warn(
                    component = TAG,
                    event = "hardware_compression_fallback",
                    sessionId = taskId,
                    message = error.message ?: "Media3 hardware compression thất bại",
                    fields = mapOf("elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt)),
                    error = error,
                )
                MediaProcessingForegroundController.update(
                    context,
                    taskId,
                    "Phần cứng không tương thích, chuyển sang nén dự phòng",
                )
                executeNativeFFmpegCommand(
                    originalCommand,
                    "${diagnosticPhase}_software_fallback",
                    null,
                ).collect { state -> trySend(state) }
                releaseKeepAlive("software_fallback_finished")
                close()
            }
        }

        awaitClose {
            if (job.isActive) job.cancel()
            releaseKeepAlive("hardware_compression_collector_closed")
        }
    }

    fun getSafParameter(uri: Uri, mode: String = "r"): String? = runCatching {
        if (mode.contains("w")) {
            FFmpegKitConfig.getSafParameterForWrite(context, uri)
        } else {
            FFmpegKitConfig.getSafParameterForRead(context, uri)
        }
    }.recoverCatching {
        require(!mode.contains("w")) { "Không thể tạo SAF output cho URI này" }
        copyUriToCache(uri).absolutePath
    }.onSuccess { parameter ->
        if (!mode.contains("w")) {
            if (registeredReadUris.size >= MAX_REGISTERED_READ_URIS) registeredReadUris.clear()
            registeredReadUris[parameter] = uri
        }
    }.onFailure { error ->
        DiagnosticLogger.error(
            component = TAG,
            event = "saf_open_failed",
            message = error.message,
            fields = mapOf(
                "mode" to mode,
                "source_id" to DiagnosticRedactor.stableId(uri.toString()),
            ),
            error = error,
        )
    }.getOrNull()

    fun materializeInput(source: String, target: File): MaterializedInput {
        target.parentFile?.mkdirs()
        target.delete()
        val registeredUri = registeredReadUris.remove(source)
        val transport = when {
            registeredUri != null -> {
                context.contentResolver.openInputStream(registeredUri)?.use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: error("Không mở được URI nguồn đã đăng ký")
                "content_uri_to_private_cache"
            }
            File(source).isFile -> {
                File(source).inputStream().buffered().use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                "local_file_to_private_cache"
            }
            else -> error("Không tìm thấy nguồn cục bộ tương ứng với SAF parameter")
        }
        require(target.isFile && target.length() > 0L) { "Tệp nguồn cục bộ rỗng" }
        return MaterializedInput(bytes = target.length(), transport = transport)
    }

    fun copyUriToCache(uri: Uri, prefix: String = "input"): File =
        DocumentUtils.copyToImportCache(context, uri, prefix)

    private fun readVideoCompressionMetadata(uri: Uri): VideoCompressionMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val bitrate = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull() ?: 0
            require(durationMs > 0L) { "Không đọc được thời lượng video nguồn" }
            return VideoCompressionMetadata(durationMs, height, bitrate)
        } finally {
            retriever.release()
        }
    }

    private fun safeLogs(value: String?): String? {
        if (!BuildConfig.DEBUG) return null
        return DiagnosticRedactor.sanitizeFfmpegLogs(value, 8_000)
    }

    private fun completionFields(
        phase: String,
        commandId: String,
        returnCode: String,
        startedAt: Long,
        mediaTimeMs: Long,
        outputBytes: Long,
    ): Map<String, Any?> = mapOf(
        "phase" to phase,
        "command_id" to commandId,
        "return_code" to returnCode,
        "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
        "media_time_ms" to mediaTimeMs,
        "output_bytes" to outputBytes,
    )

    private fun backgroundLabel(phase: String): String = when {
        phase.contains("mode_4", ignoreCase = true) || phase.contains("compress", ignoreCase = true) -> "Đang nén video"
        phase.contains("trim", ignoreCase = true) -> "Đang cắt media"
        phase.contains("join", ignoreCase = true) || phase.contains("concat", ignoreCase = true) -> "Đang nối media"
        phase.contains("mix", ignoreCase = true) -> "Đang trộn media"
        phase.contains("slideshow", ignoreCase = true) -> "Đang tạo video"
        else -> "Đang xử lý media"
    }

    private fun isShortPreviewCommand(command: String): Boolean =
        PREVIEW_DURATION_REGEX.containsMatchIn(command)

    private data class VideoCompressionMetadata(
        val durationMs: Long,
        val height: Int,
        val bitrate: Int,
    )

    companion object {
        private const val TAG = "MediaEngine"
        private const val MAX_REGISTERED_READ_URIS = 32
        private const val VIDEO_COMPRESSION_PHASE = "other_video_mode_4"
        private val INPUT_PATH_REGEX = Regex("-i\\s+\\\"([^\\\"]+)\\\"")
        private val OUTPUT_PATH_REGEX = Regex("\\\"([^\\\"]+)\\\"\\s*$")
        private val TARGET_HEIGHT_REGEX = Regex("scale=-2:(720|480)")
        private val Q_VALUE_REGEX = Regex("-q:v\\s+(\\d+)")
        private val PREVIEW_DURATION_REGEX = Regex("(?:^|\\s)-t\\s+10(?:\\.0+)?(?:\\s|$)")
    }
}
