package com.aistudio.mediatool.core.ml

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.os.SystemClock
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class Success(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        // Checkpoint Mel-Band gần 1 GB; không đặt trần 30 phút vì mạng di động
        // ổn định nhưng chậm vẫn có thể cần lâu hơn. Read timeout vẫn phát hiện treo.
        .callTimeout(2, TimeUnit.HOURS)
        .retryOnConnectionFailure(true)
        .build()

    private val modelDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(spec: ModelSpec): File = File(modelDir, spec.fileName)

    fun partialFile(spec: ModelSpec): File = File(modelDir, spec.fileName + ".part")

    fun isModelDownloaded(spec: ModelSpec): Boolean = validateModel(modelFile(spec), spec)

    fun isModelFileValid(file: File, spec: ModelSpec): Boolean {
        val expectedPath = canonicalPathOrNull(modelFile(spec)) ?: return false
        val actualPath = canonicalPathOrNull(file) ?: return false
        return actualPath == expectedPath && validateModel(file, spec)
    }

    fun downloadModel(
        spec: ModelSpec,
        modelId: String = spec.familyPrefix.removeSuffix("-"),
    ): Flow<DownloadState> = flow {
        val sessionId = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        DiagnosticLogger.info(
            component = "ModelDownloader",
            event = "download_start",
            sessionId = sessionId,
            fields = mapOf(
                "model_id" to modelId,
                "expected_bytes" to spec.expectedBytes,
            ),
        )
        val destination = modelFile(spec)
        val partial = partialFile(spec)
        try {
        val obsoleteCount = cleanupObsoleteModels(spec)
        if (obsoleteCount > 0) {
            DiagnosticLogger.info(
                component = "ModelDownloader",
                event = "obsolete_models_removed",
                sessionId = sessionId,
                fields = mapOf("count" to obsoleteCount, "model_id" to modelId),
            )
        }
        if (validateModel(destination, spec)) {
            DiagnosticLogger.info(
                component = "ModelDownloader",
                event = "validated_cache_hit",
                sessionId = sessionId,
                fields = mapOf("model_id" to modelId, "bytes" to destination.length()),
            )
            emit(DownloadState.Success(destination))
            return@flow
        }
        if (destination.exists()) {
            val removed = destination.delete()
            DiagnosticLogger.warn(
                component = "ModelDownloader",
                event = "invalid_model_removed",
                sessionId = sessionId,
                fields = mapOf("removed" to removed, "model_id" to modelId),
            )
        }
        if (partial.length() == spec.expectedBytes) {
            if (validateModel(partial, spec)) {
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
                DiagnosticLogger.info(
                    component = "ModelDownloader",
                    event = "partial_promoted",
                    sessionId = sessionId,
                    fields = mapOf("model_id" to modelId, "bytes" to destination.length()),
                )
                emit(DownloadState.Success(destination))
                return@flow
            }
            partial.delete()
        } else if (partial.length() > spec.expectedBytes) {
            partial.delete()
        }
        val network = ensureNetwork()
        val storage = storageRequirement(spec.expectedBytes - partial.length())
        DiagnosticLogger.info(
            component = "ModelDownloader",
            event = "download_preflight_ok",
            sessionId = sessionId,
            fields = mapOf(
                "model_id" to modelId,
                "network_transport" to network.transport,
                "network_metered" to network.metered,
                "available_storage_bytes" to storage.availableBytes,
                "required_storage_bytes" to storage.requiredBytes,
                "resume_bytes" to partial.length(),
            ),
        )

        var existing = partial.length()
        var lastLoggedBucket = ((existing.toDouble() / spec.expectedBytes) * 10.0).toInt().coerceIn(0, 9)
        emit(DownloadState.Downloading(progress(existing, spec.expectedBytes)))
            val requestBuilder = Request.Builder()
                .url(spec.url)
                .header("Accept-Encoding", "identity")
            if (existing > 0L) requestBuilder.header("Range", "bytes=$existing-")
            executeCancellable(requestBuilder.build()).use { response ->
                if (!response.isSuccessful) throw IOException("Máy chủ trả về HTTP ${response.code}")
                val body = response.body ?: throw IOException("Máy chủ không trả dữ liệu model")
                val append = if (existing > 0L && response.code == 206) {
                    val range = ContentRange.parse(response.header("Content-Range"))
                        ?: throw IOException("Máy chủ trả Content-Range không hợp lệ")
                    require(range.start == existing && range.total == spec.expectedBytes) {
                        "Phản hồi tải tiếp không khớp tệp .part"
                    }
                    true
                } else {
                    // Máy chủ bỏ qua Range và trả toàn bộ tệp. Ghi lại từ đầu an toàn.
                    existing = 0L
                    false
                }
                val bodyLength = body.contentLength()
                DiagnosticLogger.info(
                    component = "ModelDownloader",
                    event = "response_opened",
                    sessionId = sessionId,
                    fields = mapOf(
                        "model_id" to modelId,
                        "http_code" to response.code,
                        "resumed" to append,
                        "resume_bytes" to existing,
                        "content_length" to bodyLength,
                    ),
                )
                if (bodyLength > 0L) {
                    val expectedBody = spec.expectedBytes - existing
                    require(bodyLength == expectedBody) {
                        "Dung lượng phản hồi không đúng: $bodyLength/$expectedBody byte"
                    }
                }

                RandomAccessFile(partial, "rw").use { output ->
                    if (append) output.seek(existing) else output.setLength(0L)
                    var copied = existing
                    var lastProgressAt = 0L
                    body.byteStream().buffered().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (copied + read > spec.expectedBytes) {
                                throw IOException("Máy chủ trả nhiều dữ liệu hơn kích thước model đã ghim")
                            }
                            output.write(buffer, 0, read)
                            copied += read
                            val now = System.currentTimeMillis()
                            if (now - lastProgressAt >= 250L) {
                                lastProgressAt = now
                                emit(DownloadState.Downloading(progress(copied, spec.expectedBytes)))
                            }
                            val bucket = ((copied.toDouble() / spec.expectedBytes) * 10.0)
                                .toInt().coerceIn(0, 10)
                            if (bucket > lastLoggedBucket) {
                                lastLoggedBucket = bucket
                                DiagnosticLogger.info(
                                    component = "ModelDownloader",
                                    event = "download_progress",
                                    sessionId = sessionId,
                                    fields = mapOf(
                                        "model_id" to modelId,
                                        "percent" to bucket * 10,
                                        "bytes" to copied,
                                    ),
                                )
                            }
                        }
                    }
                    output.fd.sync()
                }
            }

            val hashStartedAt = SystemClock.elapsedRealtime()
            if (!validateModel(partial, spec)) {
                throw IOException("Model tải về không vượt qua kiểm tra dung lượng/SHA-256")
            }
            DiagnosticLogger.info(
                component = "ModelDownloader",
                event = "sha256_validated",
                sessionId = sessionId,
                fields = mapOf(
                    "model_id" to modelId,
                    "elapsed_ms" to SystemClock.elapsedRealtime() - hashStartedAt,
                    "bytes" to partial.length(),
                ),
            )
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            if (!validateModel(destination, spec)) {
                destination.delete()
                throw IOException("Model sau khi lưu không vượt qua kiểm tra SHA-256")
            }
            emit(DownloadState.Downloading(1f))
            DiagnosticLogger.info(
                component = "ModelDownloader",
                event = "download_success",
                sessionId = sessionId,
                fields = mapOf(
                    "model_id" to modelId,
                    "bytes" to destination.length(),
                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                ),
            )
            emit(DownloadState.Success(destination))
        } catch (cancelled: CancellationException) {
            // Giữ .part để người dùng tiếp tục ở lần sau.
            DiagnosticLogger.info(
                component = "ModelDownloader",
                event = "download_cancelled",
                sessionId = sessionId,
                fields = mapOf(
                    "model_id" to modelId,
                    "partial_bytes" to partial.length(),
                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                ),
            )
            throw cancelled
        } catch (error: Exception) {
            if (partial.length() >= spec.expectedBytes && !validateModel(partial, spec)) partial.delete()
            DiagnosticLogger.error(
                component = "ModelDownloader",
                event = "download_failed",
                sessionId = sessionId,
                message = error.message,
                fields = mapOf(
                    "model_id" to modelId,
                    "partial_bytes" to partial.length(),
                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                ),
                error = error,
            )
            emit(DownloadState.Error(error.message ?: "Không thể tải model"))
        }
    }.flowOn(Dispatchers.IO)


    private suspend fun executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call: Call = client.newCall(request)
            val terminal = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                terminal.set(true)
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (terminal.compareAndSet(false, true)) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (terminal.compareAndSet(false, true)) {
                        continuation.resumeWith(Result.success(response))
                    } else {
                        response.close()
                    }
                }
            })
        }


    fun deletePartial(spec: ModelSpec): Boolean = partialFile(spec).delete()

    private fun ensureNetwork(): NetworkState {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: throw IOException("Không có kết nối Internet")
        val caps = manager.getNetworkCapabilities(network) ?: throw IOException("Không đọc được trạng thái mạng")
        require(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) { "Mạng hiện tại không có Internet" }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            throw IOException("Kết nối chưa truy cập được Internet")
        }
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        return NetworkState(transport = transport, metered = manager.isActiveNetworkMetered)
    }

    private fun storageRequirement(remainingBytes: Long): StorageState {
        val available = StatFs(modelDir.absolutePath).availableBytes
        val required = remainingBytes.coerceAtLeast(0L) + 128L * 1024L * 1024L
        require(available >= required) {
            "Không đủ dung lượng để tải model. Cần thêm khoảng ${(required - available) / (1024 * 1024)} MB"
        }
        return StorageState(availableBytes = available, requiredBytes = required)
    }

    private fun cleanupObsoleteModels(spec: ModelSpec): Int {
        var removed = 0
        modelDir.listFiles().orEmpty().forEach { file ->
            if (file.name.startsWith(spec.familyPrefix) && file.name != spec.fileName && file.name != spec.fileName + ".part") {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    private fun validateModel(file: File, spec: ModelSpec): Boolean {
        val cache = context.getSharedPreferences(MODEL_VALIDATION_PREFS, Context.MODE_PRIVATE)
        val cacheKey = "validated.${spec.fileName}"
        if (!file.isFile || file.length() != spec.expectedBytes) {
            cache.edit().remove(cacheKey).apply()
            return false
        }
        val fingerprint = "${file.length()}:${file.lastModified()}:${spec.sha256.lowercase()}"
        if (cache.getString(cacheKey, null) == fingerprint) return true

        val valid = try {
            sha256(file).equals(spec.sha256, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
        if (valid) cache.edit().putString(cacheKey, fingerprint).apply()
        else cache.edit().remove(cacheKey).apply()
        return valid
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun canonicalPathOrNull(file: File): String? = try {
        file.canonicalPath
    } catch (_: Exception) {
        null
    }

    private fun progress(bytes: Long, total: Long): Float =
        if (total <= 0L) 0f else (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)

    companion object {
        private const val MODEL_VALIDATION_PREFS = "validated_model_fingerprints_v1"
    }

    private data class NetworkState(val transport: String, val metered: Boolean)
    private data class StorageState(val availableBytes: Long, val requiredBytes: Long)
}
