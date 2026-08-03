package com.aistudio.mediatool.core.ml

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File

/** A fresh instance is created for every file so recurrent model state never leaks between tasks. */
internal class DpdfNetLiteRtEngine private constructor(
    private val environment: Environment,
    private val compiledModel: CompiledModel,
    private val inputBuffers: List<TensorBuffer>,
    private val outputBuffers: List<TensorBuffer>,
) : AutoCloseable {

    fun process(input: FloatArray): FloatArray {
        require(input.size == DpdfNetDsp.TENSOR_ELEMENTS) {
            "DPDFNet input has ${input.size} elements, expected ${DpdfNetDsp.TENSOR_ELEMENTS}"
        }
        inputBuffers.single().writeFloat(input)
        compiledModel.run(inputBuffers, outputBuffers)
        return outputBuffers.single().readFloat().also { output ->
            require(output.size == DpdfNetDsp.TENSOR_ELEMENTS) {
                "DPDFNet output has ${output.size} elements, expected ${DpdfNetDsp.TENSOR_ELEMENTS}"
            }
        }
    }

    override fun close() {
        outputBuffers.asReversed().forEach { runCatching(it::close) }
        inputBuffers.asReversed().forEach { runCatching(it::close) }
        runCatching(compiledModel::close)
        runCatching(environment::close)
    }

    companion object {
        fun open(modelFile: File, cpuThreads: Int, cacheDirectory: File): DpdfNetLiteRtEngine {
            require(modelFile.isFile && modelFile.length() > 0L) { "Model DPDFNet không tồn tại" }
            cacheDirectory.mkdirs()
            var environment: Environment? = null
            var compiledModel: CompiledModel? = null
            var inputs: List<TensorBuffer> = emptyList()
            var outputs: List<TensorBuffer> = emptyList()
            try {
                environment = Environment.create()
                val options = CompiledModel.Options(Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(
                        numThreads = cpuThreads.coerceIn(1, 8),
                        xnnPackWeightCachePath = File(
                            cacheDirectory,
                            modelFile.nameWithoutExtension + ".xnnpack-cache",
                        ).absolutePath,
                    )
                }
                compiledModel = CompiledModel.create(modelFile.absolutePath, options, environment)
                inputs = compiledModel.createInputBuffers()
                outputs = compiledModel.createOutputBuffers()
                require(inputs.size == 1 && outputs.size == 1) {
                    "DPDFNet TFLite phải có đúng một input và một output"
                }
                return DpdfNetLiteRtEngine(environment, compiledModel, inputs, outputs)
            } catch (error: Throwable) {
                outputs.asReversed().forEach { runCatching(it::close) }
                inputs.asReversed().forEach { runCatching(it::close) }
                runCatching { compiledModel?.close() }
                runCatching { environment?.close() }
                throw error
            }
        }
    }
}
