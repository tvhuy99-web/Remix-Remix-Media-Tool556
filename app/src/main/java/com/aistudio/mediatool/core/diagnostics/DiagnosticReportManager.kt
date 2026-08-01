package com.aistudio.mediatool.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.aistudio.mediatool.BuildConfig
import com.aistudio.mediatool.core.FileExportManager
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.TaskStateStore
import com.aistudio.mediatool.core.media.RecordingManager
import com.aistudio.mediatool.core.ml.OnnxAcceleration
import com.aistudio.mediatool.core.ml.StemMode
import com.aistudio.mediatool.core.ml.StemModelRegistry
import com.aistudio.mediatool.core.ml.StemService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticReportManager {
    suspend fun createReport(context: Context): File = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val reportId = UUID.randomUUID().toString()
        val workDir = File(appContext.cacheDir, "diagnostic-report-$reportId")
        val logsDir = File(workDir, "logs")
        val target = FileExportManager.resultFile(appContext, "MediaTool_diagnostics", "zip")
        var committed = false

        DiagnosticLogger.info(
            component = "DiagnosticReport",
            event = "report_requested",
            sessionId = reportId,
        )
        try {
            workDir.mkdirs()
            DiagnosticLogger.flush()
            val snapshots = DiagnosticLogger.snapshotLogs(logsDir)
            File(workDir, "summary.json").writeText(
                createSummary(appContext, reportId, snapshots).toString(2),
                Charsets.UTF_8,
            )
            File(workDir, "README.txt").writeText(readme(reportId), Charsets.UTF_8)

            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                workDir.walkTopDown()
                    .filter(File::isFile)
                    .sortedBy { it.relativeTo(workDir).invariantSeparatorsPath }
                    .forEach { file ->
                        val entryName = file.relativeTo(workDir).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                        file.inputStream().buffered().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            require(target.isFile && target.length() > 0L) { "Gói nhật ký tạo ra bị rỗng" }
            committed = true
            DiagnosticLogger.info(
                component = "DiagnosticReport",
                event = "report_created",
                sessionId = reportId,
                fields = mapOf("bytes" to target.length(), "log_files" to snapshots.size),
            )
            target
        } catch (cancelled: CancellationException) {
            target.delete()
            throw cancelled
        } catch (error: Exception) {
            target.delete()
            DiagnosticLogger.error(
                component = "DiagnosticReport",
                event = "report_failed",
                sessionId = reportId,
                message = error.message,
                error = error,
            )
            throw error
        } finally {
            if (!committed) target.delete()
            workDir.deleteRecursively()
        }
    }

    private fun createSummary(context: Context, reportId: String, logs: List<File>): JSONObject {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val storage = StatFs(context.filesDir.absolutePath)
        val modeIndex = SettingsManager.getStemModeIndex(context)
        val mode = StemMode.fromSettingsIndex(modeIndex)
        val model = StemModelRegistry.resolve(mode, SettingsManager.getStemModelId(context, modeIndex))
        val acceleration = OnnxAcceleration.fromSettingsIndex(SettingsManager.getHardwareAccelIndex(context))
        val logStats = DiagnosticLogger.stats()

        val taskArray = JSONArray()
        TaskStateStore.loadAll(context).sortedBy { it.type }.forEach { task ->
            taskArray.put(
                JSONObject()
                    .put("type", task.type)
                    .put("task_id", task.taskId)
                    .put("status", task.status.name)
                    .put("progress", task.progress.toDouble())
                    .put("started_at_ms", task.startedAt)
                    .put("output_count", task.outputPaths.size)
                    .put("output_bytes", task.outputPaths.map(::File).filter(File::isFile).sumOf(File::length))
                    .put("message", DiagnosticRedactor.sanitize(task.message)),
            )
        }

        val modelArray = JSONArray()
        StemModelRegistry.all.forEach { descriptor ->
            val file = File(File(context.filesDir, "models"), descriptor.modelSpec.fileName)
            modelArray.put(
                JSONObject()
                    .put("id", descriptor.id)
                    .put("mode", descriptor.mode.name)
                    .put("present", file.isFile)
                    .put("bytes", if (file.isFile) file.length() else 0L)
                    .put("expected_bytes", descriptor.modelSpec.expectedBytes),
            )
        }

        return JSONObject()
            .put("schema", 1)
            .put("report_id", reportId)
            .put("generated_utc", utcTimestamp(System.currentTimeMillis()))
            .put(
                "app",
                JSONObject()
                    .put("version_name", BuildConfig.VERSION_NAME)
                    .put("version_code", BuildConfig.VERSION_CODE)
                    .put("build_type", BuildConfig.BUILD_TYPE)
                    .put("debug", BuildConfig.DEBUG),
            )
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", DiagnosticRedactor.sanitize(Build.MANUFACTURER, 256))
                    .put("model", DiagnosticRedactor.sanitize(Build.MODEL, 256))
                    .put("android_release", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                    .put("processors", runtime.availableProcessors()),
            )
            .put(
                "memory",
                JSONObject()
                    .put("total_bytes", memory.totalMem)
                    .put("available_bytes", memory.availMem)
                    .put("low_memory", memory.lowMemory)
                    .put("threshold_bytes", memory.threshold)
                    .put("java_heap_max_bytes", runtime.maxMemory())
                    .put("java_heap_total_bytes", runtime.totalMemory())
                    .put("java_heap_free_bytes", runtime.freeMemory()),
            )
            .put(
                "storage",
                JSONObject()
                    .put("available_bytes", storage.availableBytes)
                    .put("total_bytes", storage.totalBytes),
            )
            .put(
                "stem_settings",
                JSONObject()
                    .put("model_id", model.id)
                    .put("mode", mode.name)
                    .put("requested_accelerator", acceleration.name)
                    .put("threads", SettingsManager.getNumThreads(context))
                    .put("audio_format", SettingsManager.getAudioFormatExt(context)),
            )
            .put(
                "live_state",
                JSONObject()
                    .put("stem_processing", StemService.isProcessing.value)
                    .put("recording", RecordingManager.isRecording.value)
                    .put("recording_starting", RecordingManager.isStarting.value)
                    .put("recording_finalizing", RecordingManager.isFinalizing.value),
            )
            .put("tasks", taskArray)
            .put("models", modelArray)
            .put("recent_process_exits", recentProcessExits(context, activityManager))
            .put(
                "diagnostics",
                JSONObject()
                    .put("log_files", logs.size)
                    .put("log_bytes", logs.sumOf(File::length))
                    .put("retained_files", logStats.retainedFiles)
                    .put("retained_bytes", logStats.retainedBytes)
                    .put("dropped_events", logStats.droppedEvents),
            )
    }

    private fun recentProcessExits(context: Context, activityManager: ActivityManager): JSONArray {
        val result = JSONArray()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return result
        val exits = try {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        } catch (error: Exception) {
            DiagnosticLogger.warn(
                component = "DiagnosticReport",
                event = "process_exit_history_unavailable",
                message = error.message,
                error = error,
            )
            emptyList()
        }
        exits.forEach { exit ->
            result.put(
                JSONObject()
                    .put("timestamp_ms", exit.timestamp)
                    .put("reason", exitReasonName(exit.reason))
                    .put("reason_code", exit.reason)
                    .put("status", exit.status)
                    .put("importance", exit.importance)
                    .put("pss_kb", exit.pss)
                    .put("rss_kb", exit.rss)
                    .put("description", DiagnosticRedactor.sanitize(exit.description, 1_000)),
            )
        }
        return result
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        else -> "OTHER"
    }

    private fun readme(reportId: String): String = """
        MediaTool diagnostic report
        Report ID: $reportId

        Nội dung:
        - summary.json: phiên bản, cấu hình, RAM, dung lượng, trạng thái tác vụ và lịch sử process thoát.
        - logs/events-*.jsonl: sự kiện kỹ thuật theo thứ tự thời gian.

        Bảo vệ riêng tư:
        - Không kèm âm thanh, video, ảnh, subtitle hay model AI.
        - URI, đường dẫn, URL, tên tệp media, email, token và metadata media đã được che.
        - Mã source_id/command_id chỉ là mã băm một chiều để đối chiếu sự kiện.

        Khi báo lỗi, hãy gửi nguyên tệp ZIP này và mô tả thao tác ngay trước khi lỗi xảy ra.
    """.trimIndent() + "\n"

    private fun utcTimestamp(value: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(value))
}
