package com.aistudio.mediatool.core

data class SlideshowInterval(
    val startMs: Long?,
    val endMs: Long?,
)

data class SlideshowSlot(
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

object SlideshowTiming {
    fun distributeDurations(totalDurationMs: Long, imageCount: Int): List<Long> {
        require(totalDurationMs > 0)
        require(imageCount > 0)
        val base = totalDurationMs / imageCount
        var remainder = totalDurationMs % imageCount
        return List(imageCount) {
            base + if (remainder-- > 0) 1 else 0
        }
    }

    fun parseInterval(startText: String, endText: String): SlideshowInterval {
        val start = startText.trim()
        val end = endText.trim()
        require(start.isBlank() == end.isBlank()) {
            "Mốc bắt đầu và kết thúc của một ảnh phải được nhập cùng nhau"
        }
        if (start.isBlank()) return SlideshowInterval(null, null)
        val startMs = start.toLongOrNull()?.takeIf { it >= 0L }
            ?: error("Mốc bắt đầu ảnh phải là số nguyên mili-giây không âm")
        val endMs = end.toLongOrNull()?.takeIf { it >= 0L }
            ?: error("Mốc kết thúc ảnh phải là số nguyên mili-giây không âm")
        require(endMs > startMs) { "Mốc kết thúc ảnh phải lớn hơn mốc bắt đầu" }
        return SlideshowInterval(startMs, endMs)
    }

    /**
     * Mốc tùy chỉnh là vị trí tuyệt đối. Các ảnh để trống được chia đều vào
     * khoảng trống giữa hai mốc tùy chỉnh, không làm tổng timeline vượt audio.
     */
    fun buildSchedule(totalDurationMs: Long, intervals: List<SlideshowInterval>): List<SlideshowSlot> {
        require(totalDurationMs > 0L) { "Không đọc được thời lượng âm thanh" }
        require(intervals.isNotEmpty()) { "Cần ít nhất một ảnh" }

        intervals.forEachIndexed { index, interval ->
            require((interval.startMs == null) == (interval.endMs == null)) {
                "Ảnh ${index + 1} có mốc thời gian chưa đầy đủ"
            }
            if (interval.startMs != null && interval.endMs != null) {
                require(interval.startMs >= 0L && interval.endMs > interval.startMs) {
                    "Ảnh ${index + 1} có mốc thời gian không hợp lệ"
                }
                require(interval.endMs <= totalDurationMs) {
                    "Ảnh ${index + 1} kết thúc sau thời lượng âm thanh"
                }
            }
        }

        val slots = MutableList<SlideshowSlot?>(intervals.size) { null }
        var cursor = 0L
        var index = 0
        while (index < intervals.size) {
            val interval = intervals[index]
            if (interval.startMs != null && interval.endMs != null) {
                require(interval.startMs >= cursor) {
                    "Mốc ảnh ${index + 1} bị chồng lấn hoặc không đúng thứ tự"
                }
                slots[index] = SlideshowSlot(interval.startMs, interval.endMs)
                cursor = interval.endMs
                index++
                continue
            }

            val groupStart = index
            while (index < intervals.size && intervals[index].startMs == null) index++
            val groupCount = index - groupStart
            val boundary = intervals.getOrNull(index)?.startMs ?: totalDurationMs
            require(boundary >= cursor) { "Nhóm ảnh tự động bị chồng với mốc tùy chỉnh kế tiếp" }
            val available = boundary - cursor
            require(available >= groupCount * MIN_SLOT_MS) {
                "Không đủ khoảng trống cho $groupCount ảnh tự động trước mốc kế tiếp"
            }
            val durations = distributeDurations(available, groupCount)
            durations.forEachIndexed { offset, duration ->
                slots[groupStart + offset] = SlideshowSlot(cursor, cursor + duration)
                cursor += duration
            }
        }
        return slots.map { requireNotNull(it) }
    }

    private const val MIN_SLOT_MS = 100L
}
