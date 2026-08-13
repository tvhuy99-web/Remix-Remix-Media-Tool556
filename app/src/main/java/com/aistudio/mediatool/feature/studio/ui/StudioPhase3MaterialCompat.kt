package com.aistudio.mediatool.feature.studio.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Compatibility overload for the Material3 version currently used by MediaTool. */
@Composable
internal fun ExtendedFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: @Composable () -> Unit,
) {
    androidx.compose.material3.ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        text()
    }
}
