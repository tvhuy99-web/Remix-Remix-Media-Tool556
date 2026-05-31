package com.example.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tiện ích dọn dẹp các tệp tạm trong cache của ứng dụng.
 */
object CacheUtils {
    /**
     * Xóa các tệp (.m4a, .mp4, .wav, v.v...) đã lưu trong cache không còn sử dụng.
     * excludeFiles: danh sách tệp không muốn bị xóa (đang dùng).
     */
    suspend fun clearOldCache(context: Context, excludeFiles: List<File> = emptyList()) {
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = context.cacheDir
                val files = cacheDir.walkBottomUp()
                val excludePaths = excludeFiles.map { it.absolutePath }
                
                files.forEach { file ->
                    if (file.isFile && !excludePaths.contains(file.absolutePath)) {
                        // Xóa các file rác không nằm trong danh sách exclude
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
