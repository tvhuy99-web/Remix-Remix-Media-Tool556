package com.aistudio.mediatool.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Fixed-shape ONNX Runtime engine for an MDX learned spectrogram core.
 *
 * STFT, iSTFT and full-song overlap-add remain in Kotlin. The graph must expose one float32 input
 * and one float32 output with shape [1, 4, frequencyBins, timeFrames].
 */
internal class MdxOnnxEngine private constructor(
    private val environment: OrtEnvironment,
    private val sessionOptions: OrtSession.SessionOptions,
    private val session: OrtSession,
    private val runOptions: OrtSession.RunOptions,
    private val inputName: String,
    private val outputName: String,
    private val tensorShape: LongArray,
    private val tensorElements: Int,
    private val provider: OnnxAcceleration,
) : MdxCoreEngine {
    private val inputBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(Math.multiplyExact(tensorElements, Float.SIZE_BYTES))
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var inputTensor: OnnxTensor? = null
    private var result: OrtSession.Result? = null
    private var phase = InvocationPhase.READY_FOR_INPUT

    override val backendLabel: String
        get() = "ONNX_${provider.name}"

    override fun writeInput(input: FloatArray) {
        check(phase == InvocationPhase.READY_FOR_INPUT) {
            "MDX ONNX invocation is not ready for input: $phase"
        }
        require(input.size == tensorElements) {
            "MDX ONNX input has ${input.size} elements, expected $tensorElements"
        }
        inputBuffer.clear()
        inputBuffer.put(input)
        inputBuffer.rewind()
        inputTensor = OnnxTensor.createTensor(environment, inputBuffer, tensorShape)
        phase = InvocationPhase.INPUT_WRITTEN
    }

    override fun execute() {
        check(phase == InvocationPhase.INPUT_WRITTEN) {
            "MDX ONNX input must be written before execute: $phase"
        }
        val tensor = checkNotNull(inputTensor)
        result = session.run(mapOf(inputName to tensor), setOf(outputName), runOptions)
        phase = InvocationPhase.OUTPUT_READY
    }

    override fun readOutput(): FloatArray {
        check(phase == InvocationPhase.OUTPUT_READY) {
            "MDX ONNX output is not ready: $phase"
        }
        return try {
            val outputTensor = result?.get(0) as? OnnxTensor
                ?: throw MdxModelContractException("MDX ONNX graph did not return a float tensor")
            val shape = (outputTensor.info as? TensorInfo)?.shape
                ?: throw MdxModelContractException("Cannot read MDX ONNX output shape")
            requireShape("output", shape, tensorShape)
            val buffer = outputTensor.floatBuffer
                ?: throw MdxModelContractException("MDX ONNX output has no float buffer")
            require(buffer.capacity() >= tensorElements) {
                "MDX ONNX output has ${buffer.capacity()} elements, expected $tensorElements"
            }
            FloatArray(tensorElements).also { output ->
                buffer.rewind()
                buffer.get(output)
            }
        } finally {
            closeInvocation()
        }
    }

    override fun cancel() {
        runCatching { runOptions.setTerminate(true) }
    }

    override fun close() {
        closeInvocation()
        runCatching(runOptions::close)
        runCatching(session::close)
        runCatching(sessionOptions::close)
    }

    private fun closeInvocation() {
        runCatching { result?.close() }
        result = null
        runCatching { inputTensor?.close() }
        inputTensor = null
        phase = InvocationPhase.READY_FOR_INPUT
    }

    private enum class InvocationPhase {
        READY_FOR_INPUT,
        INPUT_WRITTEN,
        OUTPUT_READY,
    }

    companion object {
        fun open(
            modelFile: File,
            model: StemModelDescriptor,
            cpuThreads: Int,
            configuredAcceleration: OnnxAcceleration,
            onAttemptFailed: (backend: OnnxAcceleration, error: Throwable) -> Unit,
        ): MdxEngineOpenResult {
            require(model.backend == StemInferenceBackend.MDX_ONNX)
            require(modelFile.isFile && modelFile.length() > 0L)
            val contract = requireNotNull(model.mdx)
            val tensorShape = longArrayOf(
                1L,
                4L,
                contract.frequencyBins.toLong(),
                contract.timeFrames.toLong(),
            )
            val requested = configuredAcceleration
                .takeIf { it in model.allowedAccelerators }
                ?: OnnxAcceleration.CPU
            val attempts = listOf(requested, OnnxAcceleration.CPU).distinct()
            val failures = mutableListOf<String>()
            val environment = OrtEnvironment.getEnvironment()

            attempts.forEach { provider ->
                var options: OrtSession.SessionOptions? = null
                var openedSession: OrtSession? = null
                var openedRunOptions: OrtSession.RunOptions? = null
                try {
                    options = createOptions(provider, cpuThreads)
                    openedSession = environment.createSession(modelFile.absolutePath, options)
                    val inputName = model.tensor.inputName.takeIf(openedSession.inputNames::contains)
                        ?: openedSession.inputNames.singleOrNull()
                        ?: throw MdxModelContractException("MDX ONNX graph must expose exactly one input")
                    val outputName = model.tensor.outputName.takeIf(openedSession.outputNames::contains)
                        ?: openedSession.outputNames.singleOrNull()
                        ?: throw MdxModelContractException("MDX ONNX graph must expose exactly one output")
                    val inputShape = (openedSession.inputInfo[inputName]?.info as? TensorInfo)?.shape
                        ?: throw MdxModelContractException("Cannot read MDX ONNX input shape")
                    val outputShape = (openedSession.outputInfo[outputName]?.info as? TensorInfo)?.shape
                        ?: throw MdxModelContractException("Cannot read MDX ONNX output shape")
                    requireShape("input", inputShape, tensorShape)
                    requireShape("output", outputShape, tensorShape)
                    openedRunOptions = OrtSession.RunOptions()
                    return MdxEngineOpenResult(
                        engine = MdxOnnxEngine(
                            environment = environment,
                            sessionOptions = options,
                            session = openedSession,
                            runOptions = openedRunOptions,
                            inputName = inputName,
                            outputName = outputName,
                            tensorShape = tensorShape,
                            tensorElements = contract.tensorElements,
                            provider = provider,
                        ),
                        failedAttempts = failures.toList(),
                    )
                } catch (error: Throwable) {
                    runCatching { openedRunOptions?.close() }
                    runCatching { openedSession?.close() }
                    runCatching { options?.close() }
                    failures += "${provider.name}: ${error.message ?: error::class.java.simpleName}"
                    onAttemptFailed(provider, error)
                }
            }

            throw IllegalStateException(
                "Không thể mở MDX ONNX bằng CPU/XNNPACK: ${failures.joinToString(" | ")}",
            )
        }

        private fun createOptions(
            provider: OnnxAcceleration,
            requestedThreads: Int,
        ): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val threading = OnnxThreadingPolicy.resolve(provider.settingsIndex, requestedThreads)
            setIntraOpNumThreads(threading.ortIntraOpThreads)
            when (provider) {
                OnnxAcceleration.CPU -> Unit
                OnnxAcceleration.XNNPACK -> {
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addXnnpack(
                        hashMapOf(
                            "intra_op_num_threads" to requireNotNull(threading.xnnpackThreads).toString(),
                        ),
                    )
                }
                OnnxAcceleration.NNAPI -> error("MDX ONNX phase 1 does not enable NNAPI")
            }
        }

        private fun requireShape(label: String, actual: LongArray, expected: LongArray) {
            require(actual.size == expected.size && actual.indices.all { axis ->
                actual[axis] <= 0L || actual[axis] == expected[axis]
            }) {
                "MDX ONNX $label shape ${actual.joinToString("x")} does not match ${expected.joinToString("x")}"
            }
        }
    }
}
