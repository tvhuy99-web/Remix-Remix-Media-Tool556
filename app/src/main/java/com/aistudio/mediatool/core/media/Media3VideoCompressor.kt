package com.aistudio.mediatool.core.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hardware-first video compressor backed by Android MediaCodec through Media3 Transformer.
 * The requested bitrate is explicit so the compression slider controls output size instead of
 * mapping to the very slow software MPEG-4 qscale path previously used by OtherScreen.
 */
@OptIn(UnstableApi::class)
class Media3VideoCompressor(context: Context) {
    private val appContext = context.applicationContext

    data class Result(
        val fileSizeBytes: Long,
        val averageVideoBitrate: Int,
        val averageAudioBitrate: Int,
    )

    suspend fun compress(
        inputUri: Uri,
        outputFile: File,
        targetHeight: Int?,
        targetVideoBitrate: Int,
        onProgress: suspend (Int) -> Unit,
    ): Result {
        require(targetVideoBitrate > 0) { "Bitrate nén video phải lớn hơn 0" }
        targetHeight?.let { require(it > 0) { "Độ cao video đầu ra không hợp lệ" } }

        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val completion = CompletableDeferred<Result>()
        var transformer: Transformer? = null

        return try {
            withContext(Dispatchers.Main.immediate) {
                val videoEffects = targetHeight?.let { listOf(Presentation.createForHeight(it)) }.orEmpty()
                val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
                    .setEffects(Effects(emptyList(), videoEffects))
                    .build()

                val encoderFactory = DefaultEncoderFactory.Builder(appContext)
                    .setEnableFallback(true)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(targetVideoBitrate)
                            .build(),
                    )
                    .build()

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (!completion.isCompleted) {
                            completion.complete(
                                Result(
                                    fileSizeBytes = exportResult.fileSizeBytes,
                                    averageVideoBitrate = exportResult.averageVideoBitrate,
                                    averageAudioBitrate = exportResult.averageAudioBitrate,
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
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(listener)
                    .build()
                transformer?.start(editedMediaItem, outputFile.absolutePath)
            }

            var lastProgress = -1
            while (!completion.isCompleted) {
                val progressValue = withContext(Dispatchers.Main.immediate) {
                    val holder = ProgressHolder()
                    val state = transformer?.getProgress(holder) ?: Transformer.PROGRESS_STATE_NOT_STARTED
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null
                }
                if (progressValue != null && progressValue != lastProgress) {
                    lastProgress = progressValue.coerceIn(0, 99)
                    onProgress(lastProgress)
                }
                delay(PROGRESS_POLL_MS)
            }

            val result = completion.await()
            require(outputFile.isFile && outputFile.length() > 0L) {
                "Media3 không tạo được video nén"
            }
            onProgress(100)
            result
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                transformer?.cancel()
            }
            outputFile.delete()
            throw error
        }
    }

    private companion object {
        const val PROGRESS_POLL_MS = 500L
    }
}
