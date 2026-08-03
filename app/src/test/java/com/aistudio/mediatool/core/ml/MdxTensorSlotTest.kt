package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MdxTensorSlotTest {
    @Test
    fun outputBecomesTheNextInputScratch() {
        val slot = MdxTensorSlot(16)
        slot.release()
        val output = FloatArray(16) { it.toFloat() }

        slot.accept(output)

        assertSame(output, slot.borrow())
    }

    @Test
    fun releasedInputIsNoLongerRetained() {
        val slot = MdxTensorSlot(8)

        slot.release()

        assertThrows(IllegalStateException::class.java) { slot.borrow() }
        assertThrows(IllegalStateException::class.java) { slot.release() }
    }

    @Test
    fun wrongSizedOutputIsRejected() {
        val slot = MdxTensorSlot(8)
        slot.release()

        assertThrows(IllegalArgumentException::class.java) {
            slot.accept(FloatArray(7))
        }
    }
}
