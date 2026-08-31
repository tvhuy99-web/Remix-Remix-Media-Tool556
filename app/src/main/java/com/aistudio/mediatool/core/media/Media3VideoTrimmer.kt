package com.aistudio.mediatool.core.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fast path for a single video clip.
 *
 * Media3 trim optimization re-encodes only the minimum GOP-sized area required around the
 * trim point and transmuxes the compatible remainder. If the optimization cannot be applied,
 * Transformer automatically falls back to a normal hardware-backed export.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class Media3VideoTrimmer(context: Context) {
    private val appContext = context.applicationContext

    data class Result(
        val fileSizeBytes: Long,
        val videoBitrate: Int,
        val audioBitrate: Int,
        val optimizationResult: Int,
    )

    suspend fun trim(
        inputUri: Uri,
        outputFile: File,
        segment: TimelineSegment,
    ): Result {
        require(segment.startMs >= 0L) { "Mốc bắt đầu video không hợp lệ" }
        segment.endMs?.let { end ->
            require(end > segment.startMs) { "Mốc kết thúc phải lớn hơn mốc bắt đầu" }
        }

        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val completion = CompletableDeferred<Result>()
        var transformer: Transformer? = null

        withContext(Dispatchers.Main.immediate) {
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(segment.startMs)
                .apply {
                    segment.endMs?.let { setEndPositionMs(it) }
                }
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(clipping)
                .build()
            val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (!completion.isCompleted) {
                        completion.complete(
                            Result(
                                fileSizeBytes = exportResult.fileSizeBytes,
                                videoBitrate = exportResult.videoBitrate,
                                audioBitrate = exportResult.audioBitrate,
                                optimizationResult = exportResult.optimizationResult,
                            ),
                        )
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    if (!completion.isCompleted) completion.completeExceptionally(exportException)
                }
            }

            transformer = Transformer.Builder(appContext)
                .experimentalSetTrimOptimizationEnabled(true)
                .addListener(listener)
                .build()
            transformer?.start(editedMediaItem, outputFile.absolutePath)
        }

        return try {
            completion.await()
        } catch (error: Throwable) {
            withContext(Dispatchers.Main.immediate) {
                transformer?.cancel()
            }
            outputFile.delete()
            throw error
        }
    }
}
