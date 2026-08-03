package com.aistudio.mediatool.core.ml

/**
 * Owns the single large Java tensor retained between MDX chunks.
 *
 * The tensor is released immediately after LiteRT copies the input, then the native output array is
 * accepted as the next chunk's scratch. This prevents the pipeline from permanently retaining a
 * separate input and output tensor at the same time.
 */
internal class MdxTensorSlot(private val tensorElements: Int) {
    private var tensor: FloatArray? = FloatArray(tensorElements)

    init {
        require(tensorElements > 0)
    }

    fun borrow(): FloatArray = checkNotNull(tensor) {
        "MDX tensor has already been released to LiteRT"
    }

    fun release() {
        check(tensor != null) { "MDX tensor is already released" }
        tensor = null
    }

    fun accept(output: FloatArray) {
        require(output.size == tensorElements) {
            "MDX output has ${output.size} elements, expected $tensorElements"
        }
        check(tensor == null) { "MDX tensor slot still owns an input array" }
        tensor = output
    }
}
