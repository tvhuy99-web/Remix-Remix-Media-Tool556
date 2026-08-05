package com.aistudio.mediatool.core.ml

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Installs a large personal-use model shipped inside a special APK build. */
class BundledModelInstaller(private val context: Context) {
    private val modelDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun install(
        spec: ModelSpec,
        assetPath: String,
        modelId: String,
    ): Flow<DownloadState> = flow {
        val sessionId = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        val destination = File(modelDir, spec.fileName)
        val partial = File(modelDir, spec.fileName + ".part")

        try {
            if (validate(destination, spec)) {
                emit(DownloadState.Success(destination))
                return@flow
            }
            destination.delete()
            partial.delete()

            val available = StatFs(modelDir.absolutePath).availableBytes
            val required = spec.expectedBytes + STORAGE_HEADROOM_BYTES
            require(available >= required) {
                "Không đủ dung lượng để cài model. Cần thêm khoảng ${(required - available) / MIB} MB"
            }

            val asset = try {
                context.assets.open(assetPath)
            } catch (_: FileNotFoundException) {
                throw IOException(
                    "Bản APK này không chứa MDX23C Vocal HQ. Hãy cài đúng bản APK MDX23C bundled.",
                )
            }

            DiagnosticLogger.info(
                component = TAG,
                event = "bundled_install_start",
                sessionId = sessionId,
                fields = mapOf(
                    "model_id" to modelId,
                    "asset_path" to assetPath,
                    "expected_bytes" to spec.expectedBytes,
                    "available_storage_bytes" to available,
                ),
            )

            emit(DownloadState.Downloading(0f))
            asset.buffered(BUFFER_SIZE).use { input ->
                partial.outputStream().buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var copied = 0L
                    var lastProgressAt = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > spec.expectedBytes) {
                            throw IOException("Model đóng gói lớn hơn dung lượng đã ghim")
                        }
                        output.write(buffer, 0, read)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressAt >= 250L) {
                            lastProgressAt = now
                            emit(DownloadState.Downloading(progress(copied, spec.expectedBytes)))
                        }
                    }
                    output.flush()
                }
            }

            if (!validate(partial, spec)) {
                partial.delete()
                throw IOException("Model đóng gói không vượt qua kiểm tra dung lượng/SHA-256")
            }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            if (!validate(destination, spec)) {
                destination.delete()
                throw IOException("Model sau khi cài không vượt qua kiểm tra SHA-256")
            }

            DiagnosticLogger.info(
                component = TAG,
                event = "bundled_install_success",
                sessionId = sessionId,
                fields = mapOf(
                    "model_id" to modelId,
                    "bytes" to destination.length(),
                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                ),
            )
            emit(DownloadState.Downloading(1f))
            emit(DownloadState.Success(destination))
        } catch (cancelled: CancellationException) {
            DiagnosticLogger.info(
                component = TAG,
                event = "bundled_install_cancelled",
                sessionId = sessionId,
                fields = mapOf("model_id" to modelId, "partial_bytes" to partial.length()),
            )
            throw cancelled
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = TAG,
                event = "bundled_install_failed",
                sessionId = sessionId,
                message = error.message,
                fields = mapOf("model_id" to modelId, "partial_bytes" to partial.length()),
                error = error,
            )
            emit(DownloadState.Error(error.message ?: "Không thể cài model đóng gói"))
        }
    }.flowOn(Dispatchers.IO)

    private fun validate(file: File, spec: ModelSpec): Boolean =
        file.isFile && file.length() == spec.expectedBytes &&
            runCatching { sha256(file).equals(spec.sha256, ignoreCase = true) }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun progress(bytes: Long, total: Long): Float =
        if (total <= 0L) 0f else (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)

    private companion object {
        const val TAG = "BundledModelInstaller"
        const val BUFFER_SIZE = 1024 * 1024
        const val MIB = 1024L * 1024L
        const val STORAGE_HEADROOM_BYTES = 128L * MIB
    }
}
