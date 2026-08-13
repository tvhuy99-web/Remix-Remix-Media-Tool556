package com.aistudio.mediatool.feature.studio.render

data class StudioMasteringOptions(
    val enabled: Boolean = true,
    val integratedTarget: Float = -14f,
    val peakTarget: Float = -1f,
) {
    fun sanitized(): StudioMasteringOptions = copy(
        integratedTarget = integratedTarget.coerceIn(-23f, -9f),
        peakTarget = peakTarget.coerceIn(-3f, -0.1f),
    )
}
