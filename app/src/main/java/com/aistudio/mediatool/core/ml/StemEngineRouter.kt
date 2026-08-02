package com.aistudio.mediatool.core.ml

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.flow.Flow

/** Keeps the stable ONNX waveform path isolated from the experimental MDX LiteRT path. */
class StemEngineRouter(
    context: Context,
    modelFile: File,
    model: StemModelDescriptor,
    taskId: String,
) {
    private val onnxDelegate: AudioSeparator?
    private val mdxDelegate: MdxAudioSeparator?

    init {
        when (model.backend) {
            StemInferenceBackend.WAVEFORM_ONNX -> {
                onnxDelegate = AudioSeparator(context, modelFile, model, taskId)
                mdxDelegate = null
            }

            StemInferenceBackend.MDX_LITERT -> {
                onnxDelegate = null
                mdxDelegate = MdxAudioSeparator(context, modelFile, model, taskId)
            }
        }
    }

    fun cancel() {
        onnxDelegate?.cancel()
        mdxDelegate?.cancel()
    }

    suspend fun separate(inputUri: Uri): Flow<SeparationState> =
        onnxDelegate?.separate(inputUri) ?: checkNotNull(mdxDelegate).separate(inputUri)
}
