package com.aistudio.mediatool.core.media

import android.content.Context
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MediaEngine(private val context: Context) {
    sealed class ExecutionState {
        data object Connecting : ExecutionState()
        data class Progress(val timeInMilliseconds: Long, val size: Long, val bitrate: Double) : ExecutionState()
        data class Success(val outputLog: String) : ExecutionState()
        data class Error(val returnCode: ReturnCode?, val failStackTrace: String?, val logs: String?) : ExecutionState()
    }

    fun executeFFmpegCommand(
        command: String,
        diagnosticPhase: String = "media_command",
    ): Flow<ExecutionState> = callbackFlow {
        val taskId = UUID.randomUUID().toString()
        val sanitization = MediaCommandSanitizer.sanitize(command)
        val effectiveCommand = sanitization.command
        val commandId = DiagnosticRedactor.stableId(effectiveCommand)
        val startedAt = SystemClock.elapsedRealtime()
        val terminal = AtomicBoolean(false)
        val lastMediaTimeMs = AtomicLong(0L)
        val lastOutputBytes = AtomicLong(0L)
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
            ),
        )
        trySend(ExecutionState.Connecting)
        var session: FFmpegSession? = null

        fun reportStartFailure(error: Throwable) {
            terminal.set(true)
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
                        }
                    }
                    close()
                },
                null,
                { statistics ->
                    lastMediaTimeMs.set(statistics.time.toLong().coerceAtLeast(0L))
                    lastOutputBytes.set(statistics.size.toLong().coerceAtLeast(0L))
                    trySend(
                        ExecutionState.Progress(
                            timeInMilliseconds = statistics.time.toLong().coerceAtLeast(0L),
                            size = statistics.size.toLong().coerceAtLeast(0L),
                            bitrate = statistics.bitrate,
                        ),
                    )
                },
            )
            if (terminal.get()) session?.let { FFmpegKit.cancel(it.sessionId) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            terminal.set(true)
            throw cancelled
        } catch (error: LinkageError) {
            reportStartFailure(error)
        } catch (error: Exception) {
            reportStartFailure(error)
        }

        awaitClose {
            if (terminal.compareAndSet(false, true)) {
                session?.let { FFmpegKit.cancel(it.sessionId) }
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
                    ),
                )
            }
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

    fun copyUriToCache(uri: Uri, prefix: String = "input"): File =
        DocumentUtils.copyToImportCache(context, uri, prefix)

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

    companion object {
        private const val TAG = "MediaEngine"
    }
}
