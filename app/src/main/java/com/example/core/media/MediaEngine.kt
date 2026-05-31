package com.example.core.media

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Lõi xử lý đa phương tiện.
 * Đã chuyển sang sử dụng FFmpegKit (phiên bản Full LTS) để đảm bảo chất lượng âm thanh 
 * được xử lý ở mức cao nhất, hỗ trợ nhiều codec và tùy chỉnh chuyên sâu.
 */
class MediaEngine(private val context: Context) {

    /**
     * Trạng thái tiến trình chạy lệnh FFmpeg.
     */
    sealed class ExecutionState {
        object Connecting : ExecutionState()
        data class Progress(val timeInMilliseconds: Long, val size: Long, val bitrate: Double) : ExecutionState()
        data class Success(val outputLog: String) : ExecutionState()
        data class Error(val returnCode: ReturnCode?, val failStackTrace: String?, val logs: String?) : ExecutionState()
    }

    /**
     * Thực thi lệnh FFmpeg bất kỳ và trả về Flow trạng thái tiến trình.
     * Cung cấp khả năng tùy chỉnh lệnh chuyên sâu, ví dụ đảm bảo audio chất lượng cao:
     * -c:a aac -b:a 320k hoặc -c:a flac
     */
    fun executeFFmpegCommand(command: String): Flow<ExecutionState> = callbackFlow {
        trySend(ExecutionState.Connecting)

        try {
            val session: FFmpegSession = FFmpegKit.executeAsync(
                command,
                { session ->
                    val returnCode = session.returnCode
                    if (ReturnCode.isSuccess(returnCode)) {
                        trySend(ExecutionState.Success(session.logsAsString ?: ""))
                    } else if (ReturnCode.isCancel(returnCode)) {
                        trySend(ExecutionState.Error(returnCode, "Canceled by user", session.logsAsString))
                    } else {
                        trySend(ExecutionState.Error(returnCode, session.failStackTrace ?: "Command failed", session.logsAsString))
                    }
                    close()
                },
                { log -> 
                },
                { statistics -> 
                    trySend(ExecutionState.Progress(
                        timeInMilliseconds = statistics.time.toLong(),
                        size = statistics.size,
                        bitrate = statistics.bitrate
                    ))
                }
            )

            awaitClose {
                FFmpegKit.cancel(session.sessionId)
            }
        } catch (e: Throwable) {
            val causeStr = generateSequence(e) { it.cause }.joinToString(" -> ") { it.toString() }
            trySend(ExecutionState.Error(null, "Native Error: $causeStr", null))
            close(e)
        }
    }

    /**
     * Lấy đường dẫn SAF hợp lệ từ Content URI (ví dụ ContentResolver trả về) 
     * để tương thích trơn tru với các lệnh FFmpeg.
     */
    fun getSafParameter(uri: Uri, mode: String = "r"): String? {
        return try {
            // Force load
            try {
                com.arthenica.ffmpegkit.FFmpegKit.executeAsync("ffmpeg -version", { _ -> }, { _ -> }, { _ -> })
            } catch(th: Throwable) {
                // Ignore
            }
            val safUrl = FFmpegKitConfig.getSafParameterForRead(context, uri)
            android.util.Log.e("MediaEngine", "Got SAF: $safUrl for URI: $uri")
            safUrl
        } catch (e: Throwable) {
            val causeStr = generateSequence(e) { it.cause }.joinToString(" -> ") { it.toString() }
            android.util.Log.e("MediaEngine", "Error getting SAF param: $causeStr", e)
            try {
                // Fallback: Copy to cache dir
                val tempFile = java.io.File(context.cacheDir, "temp_media_${System.currentTimeMillis()}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.absolutePath
            } catch (copyErr: Throwable) {
                android.util.Log.e("MediaEngine", "Fallback copy failed: ${copyErr.message}", copyErr)
                null
            }
        }
    }
}
