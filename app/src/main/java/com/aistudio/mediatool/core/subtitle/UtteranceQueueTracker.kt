package com.aistudio.mediatool.core.subtitle

/**
 * Theo dõi các utterance TTS theo thế hệ. Callback cũ sau seek/stop sẽ bị bỏ qua,
 * và video chỉ được khôi phục âm lượng khi toàn bộ hàng đợi hiện tại đã kết thúc.
 */
enum class UtteranceCompletion { STALE, CURRENT_PENDING, CURRENT_EMPTY }

class UtteranceQueueTracker {
    private var generation = 0L
    private var sequence = 0L
    private val pending = mutableMapOf<String, Long>()

    @Synchronized
    fun enqueue(cueId: Int): String {
        val id = "tts-$generation-$cueId-${sequence++}"
        pending[id] = generation
        return id
    }

    @Synchronized
    fun isCurrent(utteranceId: String?): Boolean =
        utteranceId != null && pending[utteranceId] == generation

    /** Trả true khi utterance này là phần tử cuối cùng của thế hệ hiện tại. */
    @Synchronized
    fun complete(utteranceId: String?): Boolean = completeDetailed(utteranceId) == UtteranceCompletion.CURRENT_EMPTY

    /** Phân biệt callback cũ với callback hợp lệ còn/phần tử cuối hàng đợi. */
    @Synchronized
    fun completeDetailed(utteranceId: String?): UtteranceCompletion {
        if (utteranceId == null) return UtteranceCompletion.STALE
        val utteranceGeneration = pending.remove(utteranceId) ?: return UtteranceCompletion.STALE
        if (utteranceGeneration != generation) return UtteranceCompletion.STALE
        return if (pending.values.any { it == generation }) {
            UtteranceCompletion.CURRENT_PENDING
        } else {
            UtteranceCompletion.CURRENT_EMPTY
        }
    }

    @Synchronized
    fun invalidate() {
        generation++
        pending.clear()
    }

    @Synchronized
    fun hasPending(): Boolean = pending.values.any { it == generation }
}
