package com.aistudio.mediatool.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AccessibleCheckboxRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Checkbox
            )
            .padding(vertical = 4.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AccessibleSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch
            )
            .padding(vertical = 4.dp)
    ) {
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AccessibleSliderColumn(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    contentDesc: String? = null
) {
    val effectiveLabel = if (label.startsWith("Chất lượng nén:")) {
        label.replaceFirst("Chất lượng nén:", "Dung lượng mục tiêu:") + " so với bản gốc"
    } else {
        label
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(effectiveLabel, modifier = Modifier.clearAndSetSemantics {})
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = contentDesc ?: effectiveLabel
            }
        )
    }
}
