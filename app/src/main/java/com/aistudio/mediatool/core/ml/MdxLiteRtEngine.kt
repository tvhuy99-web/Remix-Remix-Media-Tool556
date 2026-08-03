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
    private var invocationPhase = InvocationPhase.READY_FOR_INPUT

    /** Copies one host tensor into LiteRT. The caller may release its Java array after this returns. */
    fun writeInput(input: FloatArray) {
        check(invocationPhase == InvocationPhase.READY_FOR_INPUT) {
            "MDX invocation is not ready for input: $invocationPhase"
        }
        require(input.size == tensorElements) {
            "MDX input has ${input.size} elements, expected $tensorElements"
        }
        inputBuffers.single().writeFloat(input)
        invocationPhase = InvocationPhase.INPUT_WRITTEN
    }

    /** Runs inference using the already-copied input tensor. */
    fun execute() {
        check(invocationPhase == InvocationPhase.INPUT_WRITTEN) {
            "MDX input must be written before execute: $invocationPhase"
        }
        compiledModel.run(inputBuffers, outputBuffers)
        invocationPhase = InvocationPhase.OUTPUT_READY
    }

    /**
     * Materializes the native output once. LiteRT 2.1.6 returns a new FloatArray here, so callers
     * should recycle that array as the next input scratch and release the previous input before run.
     */
    fun readOutput(): FloatArray {
        check(invocationPhase == InvocationPhase.OUTPUT_READY) {
            "MDX output is not ready: $invocationPhase"
        }
        return try {
            outputBuffers.single().readFloat().also { output ->
                if (output.size != tensorElements) {
                    throw MdxModelContractException(
                        "MDX output has ${output.size} elements, expected $tensorElements",
                    )
                }
            }
        } finally {
            invocationPhase = InvocationPhase.READY_FOR_INPUT
        }
    }

    private fun warmUpWithoutMaterializingOutput() {
        // The temporary zero tensor is eligible for collection before native inference starts.
        writeInput(FloatArray(tensorElements))
        execute()
        invocationPhase = InvocationPhase.READY_FOR_INPUT
    }

    override fun close() {
        outputBuffers.forEach { runCatching(it::close) }
        inputBuffers.forEach { runCatching(it::close) }
        runCatching(compiledModel::close)
        runCatching(environment::close)
    }

    private enum class InvocationPhase {
        READY_FOR_INPUT,
        INPUT_WRITTEN,
        OUTPUT_READY,
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

                    val engine = MdxLiteRtEngine(
                        environment = environment,
                        compiledModel = compiledModel,
                        inputBuffers = inputs,
                        outputBuffers = outputs,
                        backend = backend,
                        tensorElements = tensorElements,
                    )
                    // Warm-up still accepts/rejects the backend, but avoids a full Java output copy.
                    engine.warmUpWithoutMaterializingOutput()

                    return MdxEngineOpenResult(
                        engine = engine,
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
