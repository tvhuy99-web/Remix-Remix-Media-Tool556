package com.aistudio.mediatool.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import com.aistudio.mediatool.core.FileExportManager
import com.aistudio.mediatool.core.PersistentTaskState
import com.aistudio.mediatool.core.PersistentTaskStatus
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.TaskStateStore
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Owns full-video compression outside a Compose screen lifecycle.
 * Pressing Home or recreating OtherScreen therefore cannot cancel the compression coroutine.
 */
object VideoCompressionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<PersistentTaskState?>(null)
    val state: StateFlow<PersistentTaskState?> = _state.asStateFlow()

    @Volatile private var processingJob: Job? = null

    fun restore(context: Context) {
        if (processingJob?.isActive == true) return
        _state.value = TaskStateStore.load(context.applicationContext, TASK_TYPE)
    }

    @Synchronized
    fun start(
        context: Context,
        inputUri: Uri,
        qualityPercent: Int,
        resolutionIndex: Int,
    ): Boolean {
        if (processingJob?.isActive == true) return false

        val appContext = context.applicationContext
        val taskId = UUID.randomUUID().toString()
        val initial = PersistentTaskState(
            taskId = taskId,
            type = TASK_TYPE,
            status = PersistentTaskStatus.RUNNING,
            progress = 0f,
            message = "Đang chuẩn bị nén video",
        )
        publish(appContext, initial)

        processingJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val outputFile = FileExportManager.resultFile(appContext, "compressed_video", "mp4")
            MediaProcessingForegroundController.acquire(appContext, taskId, "Đang nén video 0%")
            try {
                val metadata = readMetadata(appContext, inputUri)
                val targetHeight = VideoCompressionPolicy.effectiveHeight(
                    sourceHeight = metadata.height,
                    resolutionIndex = resolutionIndex,
                )
                val targetBitrate = VideoCompressionPolicy.targetVideoBitrate(
                    sourceBitrate = metadata.bitrate,
                    sourceHeight = metadata.height,
                    outputHeight = targetHeight,
                    qualityPercent = qualityPercent,
                )

                DiagnosticLogger.info(
                    component = TAG,
                    event = "compression_started",
                    sessionId = taskId,
                    fields = mapOf(
                        "route" to "media3_hardware",
                        "duration_ms" to metadata.durationMs,
                        "source_bitrate" to metadata.bitrate,
                        "source_height" to metadata.height,
                        "output_height" to targetHeight,
                        "target_bitrate" to targetBitrate,
                        "quality_percent" to qualityPercent.coerceIn(10, 100),
                    ),
                )

                val hardwareResult = runCatching {
                    Media3VideoCompressor(appContext).compress(
                        inputUri = inputUri,
                        outputFile = outputFile,
                        targetHeight = targetHeight,
                        targetVideoBitrate = targetBitrate,
                    ) { percent ->
                        publishProgress(appContext, taskId, percent, "Đang nén video bằng phần cứng: $percent%")
                    }
                }

                if (hardwareResult.isFailure) {
                    val hardwareError = hardwareResult.exceptionOrNull()
                    outputFile.delete()
                    DiagnosticLogger.warn(
                        component = TAG,
                        event = "hardware_compression_fallback",
                        sessionId = taskId,
                        message = hardwareError?.message ?: "Media3 hardware export thất bại",
                        fields = mapOf(
                            "target_bitrate" to targetBitrate,
                            "output_height" to targetHeight,
                        ),
                        error = hardwareError,
                    )
                    publishProgress(appContext, taskId, 1, "Phần cứng không tương thích, đang chuyển sang bộ nén dự phòng")
                    runFfmpegFallback(
                        context = appContext,
                        taskId = taskId,
                        inputUri = inputUri,
                        outputFile = outputFile,
                        sourceDurationMs = metadata.durationMs,
                        targetHeight = targetHeight,
                        targetBitrate = targetBitrate,
                    )
                } else {
                    val result = hardwareResult.getOrThrow()
                    DiagnosticLogger.info(
                        component = TAG,
                        event = "compression_hardware_success",
                        sessionId = taskId,
                        fields = mapOf(
                            "elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt),
                            "output_bytes" to outputFile.length(),
                            "reported_bytes" to result.fileSizeBytes,
                            "average_video_bitrate" to result.averageVideoBitrate,
                            "average_audio_bitrate" to result.averageAudioBitrate,
                            "target_bitrate" to targetBitrate,
                        ),
                    )
                }

                require(outputFile.isFile && outputFile.length() > 0L) {
                    "Không tạo được video nén"
                }
                val success = PersistentTaskState(
                    taskId = taskId,
                    type = TASK_TYPE,
                    status = PersistentTaskStatus.SUCCESS,
                    progress = 1f,
                    message = "Nén video hoàn tất",
                    outputPaths = listOf(outputFile.absolutePath),
                )
                publish(appContext, success)
                MediaProcessingForegroundController.update(appContext, taskId, "Nén video hoàn tất")
            } catch (cancelled: CancellationException) {
                outputFile.delete()
                publish(
                    appContext,
                    PersistentTaskState(
                        taskId = taskId,
                        type = TASK_TYPE,
                        status = PersistentTaskStatus.CANCELLED,
                        message = "Đã hủy nén video",
                    ),
                )
                DiagnosticLogger.info(
                    component = TAG,
                    event = "compression_cancelled",
                    sessionId = taskId,
                    fields = mapOf("elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt)),
                )
                throw cancelled
            } catch (error: Throwable) {
                outputFile.delete()
                val message = error.message ?: "Không thể nén video"
                publish(
                    appContext,
                    PersistentTaskState(
                        taskId = taskId,
                        type = TASK_TYPE,
                        status = PersistentTaskStatus.FAILED,
                        message = message,
                    ),
                )
                DiagnosticLogger.error(
                    component = TAG,
                    event = "compression_failed",
                    sessionId = taskId,
                    message = message,
                    fields = mapOf("elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt)),
                    error = error,
                )
            } finally {
                MediaProcessingForegroundController.release(appContext, taskId, "compression_finished")
                synchronized(this@VideoCompressionManager) {
                    processingJob = null
                }
            }
        }
        return true
    }

    @Synchronized
    fun cancel(): Boolean {
        val job = processingJob ?: return false
        if (!job.isActive) return false
        job.cancel()
        return true
    }

    private suspend fun runFfmpegFallback(
        context: Context,
        taskId: String,
        inputUri: Uri,
        outputFile: File,
        sourceDurationMs: Long,
        targetHeight: Int?,
        targetBitrate: Int,
    ) {
        val mediaEngine = MediaEngine(context)
        val input = mediaEngine.getSafParameter(inputUri)
            ?: error("Không thể mở video nguồn cho bộ nén dự phòng")
        val resize = targetHeight?.let { "-vf \"scale=-2:$it\" " }.orEmpty()
        val threads = SettingsManager.getNumThreads(context)
        val command = buildString {
            append("-y -i \"")
            append(input)
            append("\" ")
            append(resize)
            append("-c:v mpeg4 -b:v ")
            append(targetBitrate)
            append(" -threads ")
            append(threads)
            append(" -c:a aac -b:a 160k -pix_fmt yuv420p -movflags +faststart \"")
            append(outputFile.absolutePath)
            append("\"")
        }

        var succeeded = false
        mediaEngine.executeFFmpegCommand(
            command,
            diagnosticPhase = "video_compression_software_fallback",
        ).collect { state ->
            when (state) {
                is MediaEngine.ExecutionState.Progress -> {
                    val percent = if (sourceDurationMs > 0L) {
                        ((state.timeInMilliseconds * 100L) / sourceDurationMs).toInt().coerceIn(1, 99)
                    } else {
                        1
                    }
                    publishProgress(context, taskId, percent, "Đang nén video dự phòng: $percent%")
                }
                is MediaEngine.ExecutionState.Success -> succeeded = true
                is MediaEngine.ExecutionState.Error -> Unit
                else -> Unit
            }
        }
        require(succeeded && outputFile.isFile && outputFile.length() > 0L) {
            "Bộ nén video dự phòng không tạo được kết quả"
        }
        DiagnosticLogger.info(
            component = TAG,
            event = "compression_software_fallback_success",
            sessionId = taskId,
            fields = mapOf(
                "output_bytes" to outputFile.length(),
                "target_bitrate" to targetBitrate,
                "threads" to threads,
            ),
        )
    }

    private fun publishProgress(context: Context, taskId: String, percent: Int, message: String) {
        val normalized = percent.coerceIn(0, 100)
        val next = PersistentTaskState(
            taskId = taskId,
            type = TASK_TYPE,
            status = PersistentTaskStatus.RUNNING,
            progress = normalized / 100f,
            message = message,
        )
        publish(context, next)
        MediaProcessingForegroundController.update(context, taskId, message)
    }

    private fun publish(context: Context, next: PersistentTaskState) {
        _state.value = next
        TaskStateStore.save(context, next)
    }

    private fun readMetadata(context: Context, uri: Uri): SourceMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val hasVideo = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                .equals("yes", true)
            require(hasVideo) { "Tệp đã chọn không có luồng video" }
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            require(durationMs > 0L) { "Không đọc được thời lượng video" }
            return SourceMetadata(
                durationMs = durationMs,
                bitrate = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull() ?: 0,
                height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0,
            )
        } finally {
            retriever.release()
        }
    }

    private data class SourceMetadata(
        val durationMs: Long,
        val bitrate: Int,
        val height: Int,
    )

    const val TASK_TYPE = "video_compression"
    private const val TAG = "VideoCompressionManager"
}
