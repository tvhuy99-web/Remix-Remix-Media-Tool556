package com.aistudio.mediatool.core.subtitle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class UtteranceQueueTrackerTest {
    @Test
    fun restoresOnlyAfterLastQueuedUtterance() {
        val tracker = UtteranceQueueTracker()
        val first = tracker.enqueue(1)
        val second = tracker.enqueue(2)

        assertTrue(tracker.isCurrent(first))
        assertFalse(tracker.complete(first))
        assertTrue(tracker.isCurrent(second))
        assertTrue(tracker.complete(second))
    }

    @Test
    fun callbacksFromInvalidatedGenerationAreIgnored() {
        val tracker = UtteranceQueueTracker()
        val old = tracker.enqueue(1)
        tracker.invalidate()

        assertFalse(tracker.isCurrent(old))
        assertFalse(tracker.complete(old))
        assertFalse(tracker.hasPending())
    }

    @Test
    fun detailedCompletionDistinguishesCurrentAndStaleCallbacks() {
        val tracker = UtteranceQueueTracker()
        val first = tracker.enqueue(1)
        val second = tracker.enqueue(2)

        assertEquals(UtteranceCompletion.CURRENT_PENDING, tracker.completeDetailed(first))
        assertEquals(UtteranceCompletion.STALE, tracker.completeDetailed("tts-old"))
        assertEquals(UtteranceCompletion.CURRENT_EMPTY, tracker.completeDetailed(second))
    }
}
