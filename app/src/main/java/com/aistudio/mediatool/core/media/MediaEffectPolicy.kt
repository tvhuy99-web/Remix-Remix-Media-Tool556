package com.aistudio.mediatool.core.media

object MediaEffectPolicy {
    /** Chỉ các màn hình thật sự hiển thị hiệu ứng mới được dựng audio filter. */
    fun supportsAudioFilters(isVideoMode: Boolean, modeIndex: Int): Boolean =
        (!isVideoMode && modeIndex == 0) ||
            (isVideoMode && (modeIndex == 0 || modeIndex == 1))
}
