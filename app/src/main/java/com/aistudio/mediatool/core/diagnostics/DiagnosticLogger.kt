package com.aistudio.mediatool.core.diagnostics

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class DiagnosticLevel { DEBUG, INFO, WARN, ERROR }

data class DiagnosticLogStats(
    val droppedEvents: Long,
    val retainedFiles: Int,
    val retainedBytes: Long,
)

data class DiagnosticClearResult(
    val deletedFiles: Int,
    val deletedBytes: Long,
    val logSessionId: String,
)

/**
 * Nhật ký JSONL nội bộ, có giới hạn và chạy trên một worker riêng.
 *
 * - Không ghi I/O trên main thread.
 * - Tự xoay vòng ở 2 MiB, giữ tối đa 5 tệp cũ/7 ngày.
 * - Mọi chuỗi đều đi qua [DiagnosticRedactor].
 * - Hàng đợi có giới hạn để logging không bao giờ làm treo pipeline media.
 */
object DiagnosticLogger {
    private const val TAG = "MediaToolDiag"
    private const val SCHEMA_VERSION = 1
    private const val CURRENT_FILE = "diagnostics-current.jsonl"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val MAX_ROTATED_FILES = 5
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val QUEUE_CAPACITY = 512

    private val initialized = AtomicBoolean(false)
    private val handlingCrash = AtomicBoolean(false)
    private val sequence = AtomicLong(0L)
    private val droppedEvents = AtomicLong(0L)
    private val logSessionId = AtomicReference(UUID.randomUUID().toString())
    private val ioLock = Any()
    @Volatile private var appContext: Context? = null

    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "MediaTool-Diagnostics").apply { isDaemon = true } },
        RejectedExecutionHandler { task, pool ->
            droppedEvents.incrementAndGet()
            if (!pool.isShutdown) {
                pool.queue.poll()
                if (!pool.queue.offer(task)) droppedEvents.incrementAndGet()
            }
        },
    )

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (!initialized.compareAndSet(false, true)) return
        runCatching {
            synchronized(ioLock) {
                logDirectory().mkdirs()
                cleanupLocked()
            }
        }.onFailure { error ->
            Log.e(TAG, "Không thể khởi tạo nhật ký: ${error.javaClass.simpleName}")
        }
        info(
            component = "Application",
            event = "process_start",
            fields = mapOf(
                "pid" to Process.myPid(),
                "elapsed_realtime_ms" to SystemClock.elapsedRealtime(),
            ),
        )
    }

    fun debug(
        component: String,
        event: String,
        sessionId: String? = null,
        message: String? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) = enqueue(DiagnosticLevel.DEBUG, component, event, sessionId, message, fields, null)

    fun info(
        component: String,
        event: String,
        sessionId: String? = null,
        message: String? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) = enqueue(DiagnosticLevel.INFO, component, event, sessionId, message, fields, null)

    fun warn(
        component: String,
        event: String,
        sessionId: String? = null,
        message: String? = null,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) = enqueue(DiagnosticLevel.WARN, component, event, sessionId, message, fields, error)

    fun error(
        component: String,
        event: String,
        sessionId: String? = null,
        message: String? = null,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) = enqueue(DiagnosticLevel.ERROR, component, event, sessionId, message, fields, error)

    fun recordCrashSync(thread: Thread, error: Throwable) {
        if (!handlingCrash.compareAndSet(false, true)) return
        try {
            if (thread.name != "MediaTool-Diagnostics") flush(750L)
            writeEvent(
                Event(
                    timestampMs = System.currentTimeMillis(),
                    elapsedMs = SystemClock.elapsedRealtime(),
                    level = DiagnosticLevel.ERROR,
                    component = "Application",
                    event = "uncaught_exception",
                    sessionId = null,
                    message = error.message ?: "Uncaught ${error.javaClass.simpleName}",
                    fields = mapOf(
                        "thread" to thread.name,
                        "thread_state" to thread.state.name,
                        "fatal" to true,
                    ),
                    error = error,
                ),
                forceSync = true,
            )
        } catch (_: Throwable) {
            // Trình xử lý crash tuyệt đối không được che lỗi gốc.
        } finally {
            handlingCrash.set(false)
        }
    }

    fun flush(timeoutMs: Long = 2_000L): Boolean {
        if (!initialized.get()) return true
        if (Thread.currentThread().name == "MediaTool-Diagnostics") return true
        return runCatching {
            executor.submit(Runnable {}).get(timeoutMs.coerceAtLeast(100L), TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)
    }

    /** Sao chép nhất quán các log trên chính worker ghi log. Chỉ gọi từ Dispatchers.IO. */
    fun snapshotLogs(destination: File, timeoutMs: Long = 8_000L): List<File> {
        check(initialized.get()) { "DiagnosticLogger chưa được khởi tạo" }
        val task = Callable {
            synchronized(ioLock) {
                destination.mkdirs()
                logFilesLocked().mapIndexed { index, source ->
                    File(destination, "events-${index + 1}.jsonl").also { target ->
                        source.inputStream().buffered().use { input ->
                            target.outputStream().buffered().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
        }
        return executor.submit(task).get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Xóa tuần tự toàn bộ JSONL và bắt đầu một phiên log mới. Chỉ gọi ngoài main thread. */
    fun clearLogs(timeoutMs: Long = 8_000L): DiagnosticClearResult {
        check(initialized.get()) { "DiagnosticLogger chưa được khởi tạo" }
        check(Thread.currentThread().name != "MediaTool-Diagnostics") {
            "Không thể xóa nhật ký từ worker ghi log"
        }
        val task = Callable {
            synchronized(ioLock) {
                val files = logDirectory().listFiles().orEmpty()
                    .filter { it.isFile && it.extension == "jsonl" }
                val deletedBytes = files.sumOf(File::length)
                val failed = files.filterNot(File::delete)
                check(failed.isEmpty()) {
                    "Không thể xóa ${failed.size} tệp nhật ký"
                }
                sequence.set(0L)
                droppedEvents.set(0L)
                val newSessionId = UUID.randomUUID().toString()
                logSessionId.set(newSessionId)
                DiagnosticClearResult(
                    deletedFiles = files.size,
                    deletedBytes = deletedBytes,
                    logSessionId = newSessionId,
                )
            }
        }
        val result = executor.submit(task).get(timeoutMs, TimeUnit.MILLISECONDS)
        info(
            component = "DiagnosticLogger",
            event = "logs_cleared",
            fields = mapOf(
                "deleted_files" to result.deletedFiles,
                "deleted_bytes" to result.deletedBytes,
                "log_session" to result.logSessionId,
            ),
        )
        return result
    }

    fun stats(): DiagnosticLogStats = synchronized(ioLock) {
        val files = if (initialized.get()) logFilesLocked() else emptyList()
        DiagnosticLogStats(
            droppedEvents = droppedEvents.get(),
            retainedFiles = files.size,
            retainedBytes = files.sumOf(File::length),
        )
    }

    private fun enqueue(
        level: DiagnosticLevel,
        component: String,
        event: String,
        sessionId: String?,
        message: String?,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) {
        if (!initialized.get()) return
        val record = Event(
            timestampMs = System.currentTimeMillis(),
            elapsedMs = SystemClock.elapsedRealtime(),
            level = level,
            component = component,
            event = event,
            sessionId = sessionId,
            message = message,
            fields = fields.toMap(),
            error = error,
        )
        executor.execute { writeEvent(record) }
    }

    private fun writeEvent(record: Event, forceSync: Boolean = false) {
        try {
            val encoded = encode(record) + "\n"
            val bytes = encoded.toByteArray(Charsets.UTF_8)
            synchronized(ioLock) {
                val directory = logDirectory().apply { mkdirs() }
                val current = File(directory, CURRENT_FILE)
                if (current.length() + bytes.size > MAX_FILE_BYTES) rotateLocked(current)
                FileOutputStream(current, true).use { output ->
                    output.write(bytes)
                    if (forceSync) output.fd.sync()
                }
            }
            val safeMessage = DiagnosticRedactor.sanitize(record.message, 512).orEmpty()
            val line = "${record.component}/${record.event}${if (safeMessage.isBlank()) "" else ": $safeMessage"}"
            when (record.level) {
                DiagnosticLevel.ERROR -> Log.e(TAG, line)
                DiagnosticLevel.WARN -> Log.w(TAG, line)
                else -> Unit
            }
        } catch (loggingFailure: Throwable) {
            droppedEvents.incrementAndGet()
            Log.e(TAG, "Không thể ghi nhật ký chẩn đoán: ${loggingFailure.javaClass.simpleName}")
        }
    }

    private fun encode(record: Event): String {
        val root = JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("log_session", safeToken(logSessionId.get()))
            .put("seq", sequence.incrementAndGet())
            .put("ts_utc", utcTimestamp(record.timestampMs))
            .put("elapsed_ms", record.elapsedMs)
            .put("level", record.level.name)
            .put("component", safeToken(record.component))
            .put("event", safeToken(record.event))
        record.sessionId?.let { root.put("session", safeToken(it)) }
        DiagnosticRedactor.sanitize(record.message)?.let { root.put("message", it) }

        val fieldsObject = JSONObject()
        record.fields.toSortedMap().forEach { (key, value) ->
            fieldsObject.put(safeToken(key), safeValue(value))
        }
        if (droppedEvents.get() > 0L) fieldsObject.put("logger_dropped_events", droppedEvents.get())
        if (fieldsObject.length() > 0) root.put("fields", fieldsObject)

        record.error?.let { error ->
            root.put("exception_class", error.javaClass.name)
            root.put("stack_trace", DiagnosticRedactor.stackTrace(error))
        }
        return root.toString()
    }

    private fun safeValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Boolean, is Byte, is Short, is Int, is Long -> value
        is Float -> if (value.isFinite()) value else value.toString()
        is Double -> if (value.isFinite()) value else value.toString()
        is Enum<*> -> value.name
        else -> DiagnosticRedactor.sanitize(value.toString()).orEmpty()
    }

    private fun safeToken(value: String): String = value
        .replace(Regex("[^A-Za-z0-9_.:-]"), "_")
        .take(96)
        .ifBlank { "unknown" }

    private fun rotateLocked(current: File) {
        if (current.isFile && current.length() > 0L) {
            val rotated = File(
                current.parentFile,
                "diagnostics-${fileTimestamp(System.currentTimeMillis())}-${sequence.get()}.jsonl",
            )
            if (!current.renameTo(rotated)) {
                current.copyTo(rotated, overwrite = true)
                current.delete()
            }
        }
        cleanupLocked()
    }

    private fun cleanupLocked() {
        val now = System.currentTimeMillis()
        val rotated = logDirectory().listFiles().orEmpty()
            .filter {
                it.isFile && it.name != CURRENT_FILE &&
                    it.name.startsWith("diagnostics-") && it.name.endsWith(".jsonl")
            }
            .sortedByDescending(File::lastModified)
        rotated.forEachIndexed { index, file ->
            if (index >= MAX_ROTATED_FILES || now - file.lastModified() > RETENTION_MS) file.delete()
        }
    }

    private fun logFilesLocked(): List<File> {
        val files = logDirectory().listFiles().orEmpty()
            .filter { it.isFile && it.length() > 0L && it.extension == "jsonl" }
        return files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
    }

    private fun logDirectory(): File {
        val context = checkNotNull(appContext) { "DiagnosticLogger chưa có Context" }
        return File(context.filesDir, "diagnostics")
    }

    private fun utcTimestamp(value: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(value))

    private fun fileTimestamp(value: Long): String = SimpleDateFormat(
        "yyyyMMdd'T'HHmmss'Z'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(value))

    private data class Event(
        val timestampMs: Long,
        val elapsedMs: Long,
        val level: DiagnosticLevel,
        val component: String,
        val event: String,
        val sessionId: String?,
        val message: String?,
        val fields: Map<String, Any?>,
        val error: Throwable?,
    )
}
