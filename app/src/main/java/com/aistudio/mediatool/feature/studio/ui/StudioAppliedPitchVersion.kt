package com.aistudio.mediatool.feature.studio.ui

internal data class StudioAppliedPitchVersion(
    val sourceTrackId: String,
    val generatedTrackId: String,
    val sourceWasMuted: Boolean,
)
