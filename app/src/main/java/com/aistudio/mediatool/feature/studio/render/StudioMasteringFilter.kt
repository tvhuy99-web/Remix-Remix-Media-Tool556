package com.aistudio.mediatool.feature.studio.render

import java.util.Locale

internal object StudioMasteringFilter {
    fun chain(options: StudioMasteringOptions): List<String> {
        val safe = options.sanitized()
        if (!safe.enabled) return emptyList()
        val target = String.format(Locale.US, "%.1f", safe.integratedTarget)
        val peak = String.format(Locale.US, "%.1f", safe.peakTarget)
        return listOf(
            "loudnorm=I=$target:LRA=11:TP=$peak",
            "aresample=48000",
        )
    }
}
