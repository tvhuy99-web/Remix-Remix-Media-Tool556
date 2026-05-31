package com.example.core.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class Success(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES) // The file is quite large
        .build()

    fun isModelDownloaded(modelName: String): Boolean {
        val file = getModelFile(modelName)
        return file.exists() && file.length() > 0 // A simple check
    }

    private fun getModelFile(modelName: String): File {
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        return File(modelDir, modelName)
    }

    fun downloadModel(url: String, fileName: String): Flow<DownloadState> = flow {
        val file = getModelFile(fileName)
        if (file.exists() && file.length() > 0) {
            emit(DownloadState.Success(file))
            return@flow
        }

        emit(DownloadState.Downloading(0f))

        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(DownloadState.Error("Tải thất bại: Mã HTTP ${response.code}"))
                return@flow
            }

            val body = response.body ?: throw IOException("Không tìm thấy nội dung")
            val totalBytes = body.contentLength()

            var downloadedBytes = 0L
            
            // Create a temporary file to avoid partial downloads being seen as successful
            val tempFile = File(file.absolutePath + ".tmp")
            
            val source = body.source()
            val sink = tempFile.sink().buffer()
            
            val buffer = okio.Buffer()
            var read: Long
            
            var lastUpdate = System.currentTimeMillis()

            while (source.read(buffer, 8192).also { read = it } != -1L) {
                sink.write(buffer, read)
                downloadedBytes += read
                
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 250) { // Update 4 times a sec max
                     lastUpdate = now
                     val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else -1f
                     emit(DownloadState.Downloading(progress))
                }
            }

            sink.flush()
            sink.close()
            source.close()

            // Rename to actual file
            if (tempFile.renameTo(file)) {
                emit(DownloadState.Success(file))
            } else {
                emit(DownloadState.Error("Không thể hoàn tất file lưu trữ"))
            }

        } catch (e: Exception) {
            Log.e("ModelDownloader", "Lỗi tải model", e)
            emit(DownloadState.Error(e.message ?: "Lỗi không xác định khi tải model"))
        }

    }.flowOn(Dispatchers.IO)
}
