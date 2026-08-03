package com.aistudio.mediatool.core.ml

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File

internal enum class MdxExecutionBackend {
    LITERT_GPU_FP16,
    LITERT_CPU_XNNPACK,
}

internal data class MdxEngineOpenResult(
    val engine: MdxLiteRtEngine,
    val failedAttempts: List<String>,
)

/** One-input/one-output LiteRT seam for the learned MDX spectrogram core. */
internal class MdxLiteRtEngine private constructor(
    private val environment: Environment,
    private val compiledModel: CompiledModel,
    private val inputBuffers: List<TensorBuffer>,
    private val outputBuffers: List<TensorBuffer>,
    val backend: MdxExecutionBackend,
    private val tensorElements: Int,
) : AutoCloseable {

    fun run(input: FloatArray): FloatArray {
        require(input.size == tensorElements) {
            "MDX input has ${input.size} elements, expected $tensorElements"
        }
        inputBuffers.single().writeFloat(input)
        compiledModel.run(inputBuffers, outputBuffers)
        return outputBuffers.single().readFloat().also { output ->
            require(output.size == tensorElements) {
                "MDX output has ${output.size} elements, expected $tensorElements"
            }
        }
    }

    override fun close() {
        outputBuffers.forEach { runCatching(it::close) }
        inputBuffers.forEach { runCatching(it::close) }
        runCatching(compiledModel::close)
        runCatching(environment::close)
    }

    companion object {
        fun open(
            modelFile: File,
            tensorElements: Int,
            cpuThreads: Int,
            gpuCacheDirectory: File,
            onAttemptFailed: (backend: MdxExecutionBackend, error: Throwable) -> Unit,
        ): MdxEngineOpenResult {
            require(modelFile.isFile && modelFile.length() > 0L)
            require(tensorElements > 0)
            val attempts = listOf(
                MdxExecutionBackend.LITERT_GPU_FP16,
                MdxExecutionBackend.LITERT_CPU_XNNPACK,
            )
            val failures = mutableListOf<String>()

            for ((attemptIndex, backend) in attempts.withIndex()) {
                var environment: Environment? = null
                var compiledModel: CompiledModel? = null
                var inputs: List<TensorBuffer> = emptyList()
                var outputs: List<TensorBuffer> = emptyList()

                try {
                    environment = Environment.create()
                    val options = when (backend) {
                        MdxExecutionBackend.LITERT_GPU_FP16 -> CompiledModel.Options(Accelerator.GPU).apply {
                            gpuCacheDirectory.mkdirs()
                            gpuOptions = CompiledModel.GpuOptions(
                                precision = CompiledModel.GpuOptions.Precision.FP16,
                                serializationDir = gpuCacheDirectory.absolutePath,
                                modelCacheKey = modelFile.nameWithoutExtension,
                                serializeProgramCache = true,
                            )
                        }

                        MdxExecutionBackend.LITERT_CPU_XNNPACK -> CompiledModel.Options(Accelerator.CPU).apply {
                            cpuOptions = CompiledModel.CpuOptions(
                                numThreads = cpuThreads.coerceIn(1, 8),
                                xnnPackWeightCachePath = File(
                                    gpuCacheDirectory.parentFile ?: gpuCacheDirectory,
                                    modelFile.nameWithoutExtension + ".xnnpack-cache",
                                ).absolutePath,
                            )
                        }
                    }
                    compiledModel = CompiledModel.create(modelFile.absolutePath, options, environment)
                    inputs = compiledModel.createInputBuffers()
                    outputs = compiledModel.createOutputBuffers()
                    if (inputs.size != 1 || outputs.size != 1) {
                        throw MdxModelContractException(
                            "MDX graph must expose exactly one input and one output",
                        )
                    }

                    // A backend is accepted only after it completes one real invocation.
                    inputs.single().writeFloat(FloatArray(tensorElements))
                    compiledModel.run(inputs, outputs)
                    val warmOutput = outputs.single().readFloat()
                    if (warmOutput.size != tensorElements) {
                        throw MdxModelContractException(
                            "MDX warm-up output has ${warmOutput.size} elements, expected $tensorElements",
                        )
                    }

                    return MdxEngineOpenResult(
                        engine = MdxLiteRtEngine(
                            environment = environment,
                            compiledModel = compiledModel,
                            inputBuffers = inputs,
                            outputBuffers = outputs,
                            backend = backend,
                            tensorElements = tensorElements,
                        ),
                        failedAttempts = failures.toList(),
                    )
                } catch (error: Throwable) {
                    closeAfterFailure(
                        primary = error,
                        outputs = outputs,
                        inputs = inputs,
                        compiledModel = compiledModel,
                        environment = environment,
                    )
                    if (error is InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    if (!MdxBackendFailurePolicy.isRecoverable(error)) {
                        throw error
                    }

                    failures += "${backend.name}: ${error.message ?: error::class.java.simpleName}"
                    onAttemptFailed(backend, error)
                    val hasNextBackend = attemptIndex < attempts.lastIndex
                    if (!MdxBackendFailurePolicy.shouldFallback(error, hasNextBackend)) {
                        throw IllegalStateException(
                            "Không thể mở UVR MDX-Net bằng GPU hoặc CPU: ${failures.joinToString(" | ")}",
                            error,
                        )
                    }
                }
            }

            error("Danh sách backend MDX không được để trống")
        }

        private fun closeAfterFailure(
            primary: Throwable,
            outputs: List<TensorBuffer>,
            inputs: List<TensorBuffer>,
            compiledModel: CompiledModel?,
            environment: Environment?,
        ) {
            outputs.asReversed().forEach { buffer ->
                closeAndSuppress(primary, buffer::close)
            }
            inputs.asReversed().forEach { buffer ->
                closeAndSuppress(primary, buffer::close)
            }
            if (compiledModel != null) closeAndSuppress(primary, compiledModel::close)
            if (environment != null) closeAndSuppress(primary, environment::close)
        }

        private inline fun closeAndSuppress(primary: Throwable, close: () -> Unit) {
            try {
                close()
            } catch (closeError: Throwable) {
                if (closeError !== primary) primary.addSuppressed(closeError)
            }
        }
    }
}
