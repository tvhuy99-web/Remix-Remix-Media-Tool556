package com.aistudio.mediatool.core

import android.content.Context
import java.io.File

enum class PersistentTaskStatus { IDLE, RUNNING, SUCCESS, FAILED, CANCELLED, INTERRUPTED }

data class PersistentTaskState(
    val taskId: String,
    val type: String,
    val status: PersistentTaskStatus,
    val progress: Float = 0f,
    val message: String? = null,
    val outputPaths: List<String> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
)

/**
 * Lưu trạng thái tối thiểu của từng loại tác vụ. Mỗi loại có namespace riêng để
 * ghi âm và tách nhạc không ghi đè trạng thái của nhau.
 */
object TaskStateStore {
    private const val PREFS = "persistent_task_state_v2"
    private const val KNOWN_TYPES_KEY = "known_types"

    fun save(context: Context, state: PersistentTaskState) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = prefix(state.type)
        val knownTypes = prefs.getStringSet(KNOWN_TYPES_KEY, emptySet()).orEmpty().toMutableSet()
        knownTypes += state.type
        val oldOutputCount = prefs.getInt("${prefix}outputCount", 0)
        val previousTaskId = prefs.getString("${prefix}taskId", null)
        val stableStartedAt = if (previousTaskId == state.taskId) {
            prefs.getLong("${prefix}startedAt", state.startedAt).takeIf { it > 0L } ?: state.startedAt
        } else {
            state.startedAt
        }
        val editor = prefs.edit()
            .putStringSet(KNOWN_TYPES_KEY, knownTypes)
            .putString("${prefix}taskId", state.taskId)
            .putString("${prefix}status", state.status.name)
            .putFloat("${prefix}progress", state.progress.coerceIn(0f, 1f))
            .putString("${prefix}message", state.message)
            .putInt("${prefix}outputCount", state.outputPaths.size)
            .putLong("${prefix}startedAt", stableStartedAt)
            .remove("${prefix}outputs")
        repeat(oldOutputCount) { editor.remove("${prefix}output.$it") }
        state.outputPaths.forEachIndexed { index, path ->
            editor.putString("${prefix}output.$index", path)
        }
        editor.apply()
    }

    fun load(context: Context, type: String): PersistentTaskState? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = prefix(type)
        val taskId = prefs.getString("${prefix}taskId", null) ?: return null
        val status = runCatching {
            PersistentTaskStatus.valueOf(
                prefs.getString("${prefix}status", PersistentTaskStatus.IDLE.name)!!,
            )
        }.getOrDefault(PersistentTaskStatus.INTERRUPTED)
        val outputCount = prefs.getInt("${prefix}outputCount", -1)
        val outputs = if (outputCount >= 0) {
            (0 until outputCount).mapNotNull { prefs.getString("${prefix}output.$it", null) }
        } else {
            // Migration from the first reconstruction format. Order was not guaranteed there.
            prefs.getStringSet("${prefix}outputs", emptySet()).orEmpty().sorted()
        }
        val normalizedStatus = when {
            status == PersistentTaskStatus.RUNNING -> PersistentTaskStatus.INTERRUPTED
            status == PersistentTaskStatus.SUCCESS && outputs.none(::isValidOutput) -> PersistentTaskStatus.INTERRUPTED
            else -> status
        }
        return PersistentTaskState(
            taskId = taskId,
            type = type,
            status = normalizedStatus,
            progress = prefs.getFloat("${prefix}progress", 0f),
            message = prefs.getString("${prefix}message", null),
            outputPaths = outputs,
            startedAt = prefs.getLong("${prefix}startedAt", 0L),
        )
    }

    fun loadAll(context: Context): List<PersistentTaskState> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KNOWN_TYPES_KEY, emptySet()).orEmpty()
            .mapNotNull { load(context, it) }
    }

    fun outputFiles(context: Context): List<File> =
        loadAll(context).flatMap { it.outputPaths }.map(::File).filter(::isValidOutput)

    fun clear(context: Context, type: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = prefix(type)
        val knownTypes = prefs.getStringSet(KNOWN_TYPES_KEY, emptySet()).orEmpty().toMutableSet()
        knownTypes -= type
        val editor = prefs.edit().putStringSet(KNOWN_TYPES_KEY, knownTypes)
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    private fun prefix(type: String): String = "${type.replace(Regex("[^A-Za-z0-9_.-]"), "_")}."

    private fun isValidOutput(path: String): Boolean = isValidOutput(File(path))
    private fun isValidOutput(file: File): Boolean = file.isFile && file.length() > 0L
}
