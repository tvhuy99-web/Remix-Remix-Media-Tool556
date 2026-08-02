#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


logger = ROOT / "app/src/main/java/com/aistudio/mediatool/core/diagnostics/DiagnosticLogger.kt"
card = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/components/DiagnosticReportCard.kt"
verify = ROOT / "scripts/verify_project.py"

replace_once(
    logger,
    "import java.util.concurrent.atomic.AtomicBoolean\nimport java.util.concurrent.atomic.AtomicLong\n",
    "import java.util.UUID\nimport java.util.concurrent.atomic.AtomicBoolean\nimport java.util.concurrent.atomic.AtomicLong\nimport java.util.concurrent.atomic.AtomicReference\n",
    "logger imports",
)

replace_once(
    logger,
    "data class DiagnosticLogStats(\n    val droppedEvents: Long,\n    val retainedFiles: Int,\n    val retainedBytes: Long,\n)\n",
    "data class DiagnosticLogStats(\n    val droppedEvents: Long,\n    val retainedFiles: Int,\n    val retainedBytes: Long,\n)\n\ndata class DiagnosticClearResult(\n    val deletedFiles: Int,\n    val deletedBytes: Long,\n    val logSessionId: String,\n)\n",
    "clear result",
)

replace_once(
    logger,
    "    private val sequence = AtomicLong(0L)\n    private val droppedEvents = AtomicLong(0L)\n",
    "    private val sequence = AtomicLong(0L)\n    private val droppedEvents = AtomicLong(0L)\n    private val logSessionId = AtomicReference(UUID.randomUUID().toString())\n",
    "logger session field",
)

replace_once(
    logger,
    "        return executor.submit(task).get(timeoutMs, TimeUnit.MILLISECONDS)\n    }\n\n    fun stats(): DiagnosticLogStats = synchronized(ioLock) {",
    "        return executor.submit(task).get(timeoutMs, TimeUnit.MILLISECONDS)\n    }\n\n    /** Xóa tuần tự toàn bộ JSONL và bắt đầu một phiên log mới. Chỉ gọi ngoài main thread. */\n    fun clearLogs(timeoutMs: Long = 8_000L): DiagnosticClearResult {\n        check(initialized.get()) { \"DiagnosticLogger chưa được khởi tạo\" }\n        check(Thread.currentThread().name != \"MediaTool-Diagnostics\") {\n            \"Không thể xóa nhật ký từ worker ghi log\"\n        }\n        val task = Callable {\n            synchronized(ioLock) {\n                val files = logDirectory().listFiles().orEmpty()\n                    .filter { it.isFile && it.extension == \"jsonl\" }\n                val deletedBytes = files.sumOf(File::length)\n                val failed = files.filterNot(File::delete)\n                check(failed.isEmpty()) {\n                    \"Không thể xóa ${failed.size} tệp nhật ký\"\n                }\n                sequence.set(0L)\n                droppedEvents.set(0L)\n                val newSessionId = UUID.randomUUID().toString()\n                logSessionId.set(newSessionId)\n                DiagnosticClearResult(\n                    deletedFiles = files.size,\n                    deletedBytes = deletedBytes,\n                    logSessionId = newSessionId,\n                )\n            }\n        }\n        val result = executor.submit(task).get(timeoutMs, TimeUnit.MILLISECONDS)\n        info(\n            component = \"DiagnosticLogger\",\n            event = \"logs_cleared\",\n            fields = mapOf(\n                \"deleted_files\" to result.deletedFiles,\n                \"deleted_bytes\" to result.deletedBytes,\n                \"log_session\" to result.logSessionId,\n            ),\n        )\n        return result\n    }\n\n    fun stats(): DiagnosticLogStats = synchronized(ioLock) {",
    "clear method",
)

replace_once(
    logger,
    "        val root = JSONObject()\n            .put(\"schema\", SCHEMA_VERSION)\n            .put(\"seq\", sequence.incrementAndGet())\n",
    "        val root = JSONObject()\n            .put(\"schema\", SCHEMA_VERSION)\n            .put(\"log_session\", safeToken(logSessionId.get()))\n            .put(\"seq\", sequence.incrementAndGet())\n",
    "log session field",
)

replace_once(
    card,
    "import androidx.compose.material3.Button\nimport androidx.compose.material3.Card\n",
    "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.Button\nimport androidx.compose.material3.Card\n",
    "card alert import",
)
replace_once(
    card,
    "import androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.Text\n",
    "import androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.OutlinedButton\nimport androidx.compose.material3.Text\nimport androidx.compose.material3.TextButton\n",
    "card button imports",
)
replace_once(
    card,
    "import com.aistudio.mediatool.core.diagnostics.DiagnosticReportManager\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.launch\n",
    "import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger\nimport com.aistudio.mediatool.core.diagnostics.DiagnosticReportManager\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext\n",
    "card coroutine imports",
)
replace_once(
    card,
    "    var isCreating by remember { mutableStateOf(false) }\n    var createError by remember { mutableStateOf<String?>(null) }\n\n    Card(\n",
    "    var isCreating by remember { mutableStateOf(false) }\n    var createError by remember { mutableStateOf<String?>(null) }\n    var showClearConfirmation by remember { mutableStateOf(false) }\n    var isClearing by remember { mutableStateOf(false) }\n    var clearMessage by remember { mutableStateOf<String?>(null) }\n\n    if (showClearConfirmation) {\n        AlertDialog(\n            onDismissRequest = { if (!isClearing) showClearConfirmation = false },\n            title = { Text(\"Xóa toàn bộ nhật ký?\") },\n            text = {\n                Text(\n                    \"Các tệp JSONL đang lưu trong ứng dụng sẽ bị xóa. \" +\n                        \"Gói ZIP bạn đã lưu hoặc gửi ra ngoài không bị ảnh hưởng. \" +\n                        \"Một phiên nhật ký mới sẽ bắt đầu ngay sau thao tác này.\",\n                )\n            },\n            confirmButton = {\n                TextButton(\n                    onClick = {\n                        showClearConfirmation = false\n                        scope.launch {\n                            isClearing = true\n                            createError = null\n                            clearMessage = null\n                            try {\n                                val result = withContext(Dispatchers.IO) {\n                                    DiagnosticLogger.clearLogs()\n                                }\n                                report = null\n                                clearMessage = \"Đã xóa ${result.deletedFiles} tệp \" +\n                                    \"(${result.deletedBytes / 1024L} KiB). Phiên nhật ký mới đã bắt đầu.\"\n                            } catch (cancelled: CancellationException) {\n                                throw cancelled\n                            } catch (error: Exception) {\n                                createError = error.message ?: \"Không thể xóa nhật ký\"\n                            } finally {\n                                isClearing = false\n                            }\n                        }\n                    },\n                ) {\n                    Text(\"Xóa\")\n                }\n            },\n            dismissButton = {\n                TextButton(onClick = { showClearConfirmation = false }) {\n                    Text(\"Hủy\")\n                }\n            },\n        )\n    }\n\n    Card(\n",
    "card state and dialog",
)
replace_once(
    card,
    "            createError?.let { Text(it, color = MaterialTheme.colorScheme.error) }\n",
    "            OutlinedButton(\n                onClick = { showClearConfirmation = true },\n                enabled = !isCreating && !isClearing,\n                modifier = Modifier.fillMaxWidth(),\n            ) {\n                if (isClearing) {\n                    CircularProgressIndicator(\n                        modifier = Modifier.padding(end = 8.dp).size(20.dp),\n                        strokeWidth = 2.dp,\n                    )\n                    Text(\"Đang xóa...\")\n                } else {\n                    Text(\"Xóa nhật ký\")\n                }\n            }\n            Text(\n                \"Nên xóa trước khi tái hiện một lỗi mới để gói ZIP chỉ chứa phiên cần kiểm tra.\",\n                style = MaterialTheme.typography.bodySmall,\n            )\n            clearMessage?.let {\n                Text(it, color = MaterialTheme.colorScheme.primary)\n            }\n            createError?.let { Text(it, color = MaterialTheme.colorScheme.error) }\n",
    "card clear button",
)

replace_once(
    verify,
    "check(\"recordCrashSync\" in diagnostic_logger and \"uncaught_exception\" in diagnostic_logger, \"Logger thiếu crash capture\")\ncheck(\"sanitizeFfmpegLogs\" in diagnostic_redactor and \"<media-uri>\" in diagnostic_redactor, \"Logger thiếu che dữ liệu media\")\n",
    "check(\"recordCrashSync\" in diagnostic_logger and \"uncaught_exception\" in diagnostic_logger, \"Logger thiếu crash capture\")\ncheck(\"clearLogs\" in diagnostic_logger and \"log_session\" in diagnostic_logger, \"Logger thiếu xóa log/tách phiên\")\ndiagnostic_card = (ROOT / \"app/src/main/java/com/aistudio/mediatool/ui/components/DiagnosticReportCard.kt\").read_text(encoding=\"utf-8\")\ncheck(\"Xóa nhật ký\" in diagnostic_card and \"showClearConfirmation\" in diagnostic_card, \"Cài đặt thiếu nút xóa nhật ký có xác nhận\")\ncheck(\"sanitizeFfmpegLogs\" in diagnostic_redactor and \"<media-uri>\" in diagnostic_redactor, \"Logger thiếu che dữ liệu media\")\n",
    "verify clear log feature",
)

print("Applied diagnostic clear-log and log-session patch")
