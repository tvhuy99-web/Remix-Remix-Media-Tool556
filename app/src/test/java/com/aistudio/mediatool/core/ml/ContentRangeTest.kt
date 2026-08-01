package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentRangeTest {
    @Test
    fun parsesValidRange() {
        assertEquals(10L, ContentRange.parse("bytes 10-19/100")?.start)
        assertEquals(100L, ContentRange.parse("bytes 10-19/100")?.total)
    }

    @Test
    fun rejectsWrongRange() {
        assertNull(ContentRange.parse("bytes 20-10/100"))
        assertNull(ContentRange.parse("nonsense"))
    }
}
