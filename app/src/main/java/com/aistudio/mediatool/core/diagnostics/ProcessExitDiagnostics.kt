package com.aistudio.mediatool.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.system.OsConstants
import com.aistudio.mediatool.core.PersistentTaskState
import com.aistudio.mediatool.core.PersistentTaskStatus
import com.aistudio.mediatool.core.TaskStateStore

/**
 * Ghi checkpoint nhỏ bằng commit đồng bộ để vẫn còn dữ liệu nếu tiến trình bị
 * native abort hoặc low-memory-killer kết thúc trước khi Kotlin kịp bắt lỗi.
 */
object ProcessExitDiagnostics {
    private const val PREFS = "process_exit_diagnostics_v1"
    private const val KEY_ACTIVE = "active"
    private const val KEY_TASK_TYPE = "task_type"
    private const val KEY_TASK_ID = "task_id"
    private const val KEY_PHASE = "phase"
    private const val KEY_PROGRESS = "progress"
    private const val KEY_MODEL_ID = "model_id"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_HANDLED_EXIT_AT = "handled_exit_at"

    data class Checkpoint(
        val taskType: String,
        val taskId: String,
        val phase: String,
        val progress: Float,
        val modelId: String?,
        val startedAt: Long,
        val updatedAt: Long,
    )

    fun checkpoint(
        context: Context,
        taskType: String,
        taskId: String,
        phase: String,
        progress: Float,
        modelId: String? = null,
    ) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previousTaskId = prefs.getString(KEY_TASK_ID, null)
        val startedAt = if (previousTaskId == taskId) {
            prefs.getLong(KEY_STARTED_AT, now).takeIf { it > 0L } ?: now
        } else {
            now
        }
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_TASK_TYPE, taskType)
            .putString(KEY_TASK_ID, taskId)
            .putString(KEY_PHASE, phase)
            .putFloat(KEY_PROGRESS, progress.coerceIn(0f, 1f))
            .putString(KEY_MODEL_ID, modelId)
            .putLong(KEY_STARTED_AT, startedAt)
            .putLong(KEY_UPDATED_AT, now)
            .commit()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val summary = listOf(
                taskType,
                (progress.coerceIn(0f, 1f) * 100).toInt().toString(),
                phase,
                modelId.orEmpty(),
            ).joinToString("|").toByteArray(Charsets.UTF_8).let { bytes ->
                if (bytes.size <= 128) bytes else bytes.copyOf(128)
            }
            runCatching {
                app.getSystemService(ActivityManager::class.java).setProcessStateSummary(summary)
            }.onFailure { error ->
                DiagnosticLogger.warn(
                    component = "ProcessExitDiagnostics",
                    event = "state_summary_failed",
                    sessionId = taskId,
                    message = error.message,
                    error = error,
                )
            }
        }
    }

    fun finish(context: Context, taskType: String, taskId: String) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_TASK_TYPE, null) == taskType &&
            prefs.getString(KEY_TASK_ID, null) == taskId
        ) {
            prefs.edit().putBoolean(KEY_ACTIVE, false).commit()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                app.getSystemService(ActivityManager::class.java)
                    .setProcessStateSummary("idle".toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun recoverPreviousExit(context: Context) {
        val app = context.applicationContext
        val checkpoint = load(app) ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val handledAt = prefs.getLong(KEY_HANDLED_EXIT_AT, 0L)
        val exitInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                app.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(app.packageName, 0, 8)
                    .firstOrNull { info ->
                        info.timestamp > handledAt &&
                            info.timestamp >= checkpoint.startedAt - 5_000L
                    }
            }.getOrNull()
        } else {
            null
        }

        val reasonName = exitInfo?.let(::reasonName) ?: "UNKNOWN"
        val likelyLowMemory = exitInfo?.let(::isLikelyLowMemory) == true
        val message = buildUserMessage(checkpoint, reasonName, likelyLowMemory)
        val stateSummary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            exitInfo?.processStateSummary?.toString(Charsets.UTF_8)
        } else {
            null
        }

        DiagnosticLogger.error(
            component = "ProcessExitDiagnostics",
            event = "previous_process_exit",
            sessionId = checkpoint.taskId,
            message = message,
            fields = mapOf(
                "task_type" to checkpoint.taskType,
                "phase" to checkpoint.phase,
                "progress_percent" to (checkpoint.progress * 100f).toInt(),
                "model_id" to checkpoint.modelId,
                "exit_reason" to reasonName,
                "exit_status" to exitInfo?.status,
                "exit_importance" to exitInfo?.importance,
                "exit_pss_kb" to exitInfo?.pss,
                "exit_rss_kb" to exitInfo?.rss,
                "exit_description" to exitInfo?.description,
                "process_state_summary" to stateSummary,
                "low_memory_report_supported" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ActivityManager.isLowMemoryKillReportSupported()
                } else {
                    false
                },
            ),
        )

        TaskStateStore.save(
            app,
            PersistentTaskState(
                taskId = checkpoint.taskId,
                type = checkpoint.taskType,
                status = PersistentTaskStatus.INTERRUPTED,
                progress = checkpoint.progress,
                message = message,
                startedAt = checkpoint.startedAt,
            ),
        )
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_HANDLED_EXIT_AT, exitInfo?.timestamp ?: System.currentTimeMillis())
            .commit()
    }

    private fun load(context: Context): Checkpoint? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        val taskType = prefs.getString(KEY_TASK_TYPE, null) ?: return null
        val taskId = prefs.getString(KEY_TASK_ID, null) ?: return null
        return Checkpoint(
            taskType = taskType,
            taskId = taskId,
            phase = prefs.getString(KEY_PHASE, "unknown").orEmpty(),
            progress = prefs.getFloat(KEY_PROGRESS, 0f).coerceIn(0f, 1f),
            modelId = prefs.getString(KEY_MODEL_ID, null),
            startedAt = prefs.getLong(KEY_STARTED_AT, 0L),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    private fun buildUserMessage(
        checkpoint: Checkpoint,
        reasonName: String,
        likelyLowMemory: Boolean,
    ): String {
        val percent = (checkpoint.progress * 100f).toInt()
        val phase = userPhase(checkpoint.phase)
        return when {
            likelyLowMemory -> "Tác vụ trước bị Android dừng vì thiếu RAM ở $percent% ($phase)"
            reasonName == "CRASH_NATIVE" -> "Tác vụ trước gặp lỗi native ở $percent% ($phase)"
            reasonName == "ANR" -> "Tác vụ trước bị treo ở $percent% ($phase)"
            else -> "Tác vụ trước bị dừng đột ngột ở $percent% ($phase)"
        }
    }

    private fun userPhase(phase: String): String = when {
        phase.startsWith("inference_chunk") -> "suy luận AI"
        phase == "buffers_allocating" || phase == "buffers_ready" -> "chuẩn bị bộ nhớ"
        phase == "session_opening" || phase == "session_opened" -> "mở model"
        phase == "decode_input" || phase == "decode_complete" -> "đọc âm thanh"
        phase == "encoding" -> "xuất tệp"
        else -> "xử lý AI"
    }

    private fun isLikelyLowMemory(info: ApplicationExitInfo): Boolean =
        info.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
            info.reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
            (info.reason == ApplicationExitInfo.REASON_SIGNALED && info.status == OsConstants.SIGKILL)

    private fun reasonName(info: ApplicationExitInfo): String = when (info.reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN_${info.reason}"
    }
}
