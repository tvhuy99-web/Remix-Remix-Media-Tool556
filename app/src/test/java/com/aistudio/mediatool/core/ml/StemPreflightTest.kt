package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertTrue
import org.junit.Test

class StemPreflightTest {
    @Test
    fun fourStemRequiresMoreResourcesThanTwoStem() {
        val two = StemPreflight.estimate(60_000, 2)
        val four = StemPreflight.estimate(60_000, 4)
        assertTrue(four.temporaryBytes > two.temporaryBytes)
        assertTrue(four.recommendedRamBytes > two.recommendedRamBytes)
    }
}
