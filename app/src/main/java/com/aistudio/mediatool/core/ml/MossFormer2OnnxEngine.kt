package com.aistudio.mediatool.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class MossFormer2OnnxEngine private constructor(
    private val environment: OrtEnvironment,
    private val options: OrtSession.SessionOptions,
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String,
) : AutoCloseable {
    private val inputBuffer = ByteBuffer
        .allocateDirect(MossFormer2Dsp.FRAMES * MossFormer2Dsp.FEATURES * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val runOptions = OrtSession.RunOptions()

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        runCatching { runOptions.setTerminate(true) }
    }

    fun process(features: FloatArray): FloatArray {
        require(features.size == MossFormer2Dsp.FRAMES * MossFormer2Dsp.FEATURES)
        check(!cancelled) { "Đã hủy suy luận MossFormer2" }
        inputBuffer.clear()
        inputBuffer.put(features)
        inputBuffer.rewind()

        OnnxTensor.createTensor(
            environment,
            inputBuffer,
            longArrayOf(1L, MossFormer2Dsp.FRAMES.toLong(), MossFormer2Dsp.FEATURES.toLong()),
        ).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor), setOf(outputName), runOptions).use { result ->
                val output = result.get(0) as? OnnxTensor
                    ?: error("MossFormer2 không trả về tensor float")
                val shape = (output.info as? TensorInfo)?.shape
                    ?: error("Không đọc được shape đầu ra MossFormer2")
                require(
                    shape.size == 3 && shape[0] == 1L &&
                        shape[1] == MossFormer2Dsp.FRAMES.toLong() &&
                        shape[2] == MossFormer2Dsp.BINS.toLong()
                ) { "Shape đầu ra MossFormer2 không đúng: ${shape.joinToString(" x ")}" }
                val source = output.floatBuffer
                    ?: error("Tensor MossFormer2 không chứa float")
                val expected = MossFormer2Dsp.FRAMES * MossFormer2Dsp.BINS
                require(source.remaining() >= expected) { "Tensor MossFormer2 bị thiếu dữ liệu" }
                return FloatArray(expected).also { mask ->
                    source.get(mask)
                    require(mask.all(Float::isFinite)) {
                        "Mask MossFormer2 chứa giá trị không hữu hạn"
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching(runOptions::close)
        runCatching(session::close)
        runCatching(options::close)
    }

    companion object {
        fun open(modelFile: File, cpuThreads: Int): MossFormer2OnnxEngine {
            require(modelFile.isFile && modelFile.length() > 0L) { "Model MossFormer2 không tồn tại" }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(cpuThreads.coerceIn(1, 8))
                addConfigEntry("session.intra_op.allow_spinning", "0")
            }
            var session: OrtSession? = null
            try {
                session = environment.createSession(modelFile.absolutePath, options)
                val inputName = session.inputNames.singleOrNull()
                    ?: error("MossFormer2 phải có đúng một input")
                val outputName = session.outputNames.singleOrNull()
                    ?: error("MossFormer2 phải có đúng một output")
                validateInput(session, inputName)
                validateOutput(session, outputName)
                return MossFormer2OnnxEngine(environment, options, session, inputName, outputName)
            } catch (error: Throwable) {
                runCatching { session?.close() }
                runCatching(options::close)
                throw error
            }
        }

        private fun validateInput(session: OrtSession, name: String) {
            val shape = (session.inputInfo[name]?.info as? TensorInfo)?.shape ?: return
            require(shape.size == 3)
            require(shape[0] <= 0L || shape[0] == 1L)
            require(shape[1] <= 0L || shape[1] == MossFormer2Dsp.FRAMES.toLong())
            require(shape[2] <= 0L || shape[2] == MossFormer2Dsp.FEATURES.toLong())
        }

        private fun validateOutput(session: OrtSession, name: String) {
            val shape = (session.outputInfo[name]?.info as? TensorInfo)?.shape ?: return
            require(shape.size == 3)
            require(shape[0] <= 0L || shape[0] == 1L)
            require(shape[1] <= 0L || shape[1] == MossFormer2Dsp.FRAMES.toLong())
            require(shape[2] <= 0L || shape[2] == MossFormer2Dsp.BINS.toLong())
        }
    }
}
