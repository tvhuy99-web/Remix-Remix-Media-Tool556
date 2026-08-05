package com.aistudio.mediatool.ui.components

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.io.File

/** A source that can be auditioned on a compact post-processing result screen. */
data class AudioResultChoice(
    val id: String,
    val label: String,
    val uri: Uri,
    val outputFile: File? = null,
)

/**
 * Shared result layout for AI audio tools.
 *
 * The source selector stays first, the player follows the current selection, and save/share actions
 * only exist for generated files. The original input is intentionally preview-only.
 */
@Composable
fun AudioResultContent(
    choices: List<AudioResultChoice>,
    selectedId: String,
    onSelected: (String) -> Unit,
    processAnotherLabel: String,
    onProcessAnother: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (choices.isEmpty()) return
    val selected = choices.firstOrNull { it.id == selectedId } ?: choices.first()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { choice ->
                val isSelected = choice.id == selected.id
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelected(choice.id) },
                    label = { Text(choice.label) },
                    modifier = Modifier.semantics {
                        contentDescription = choice.label
                        stateDescription = if (isSelected) "Đang chọn" else "Chưa chọn"
                    },
                )
            }
        }

        UnifiedAudioPlayer(
            sources = listOf(AudioPreviewSource(selected.id, selected.label, selected.uri)),
            title = "Phát",
        )

        selected.outputFile?.let { file ->
            ResultFileActions(file = file)
        }

        Button(
            onClick = onProcessAnother,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(processAnotherLabel)
        }
        OutlinedButton(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Quay lại")
        }
    }
}
