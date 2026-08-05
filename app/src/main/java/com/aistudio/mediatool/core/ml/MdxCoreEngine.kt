package com.aistudio.mediatool.core.ml

/** Common one-input/one-output seam for learned MDX spectrogram cores. */
internal interface MdxCoreEngine : AutoCloseable {
    val backendLabel: String

    fun writeInput(input: FloatArray)

    fun execute()

    fun readOutput(): FloatArray

    fun cancel()
}
