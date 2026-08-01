package com.aistudio.mediatool.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.core.FileExportManager
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ResultFileActions(
    file: File,
    modifier: Modifier = Modifier,
    saveLabel: String = "Lưu",
    shareLabel: String = "Chia sẻ",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(FileExportManager.mimeTypeFor(file)),
    ) { destination ->
        destination ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { FileExportManager.copyToUri(context, file, destination) }
                .onSuccess { Toast.makeText(context, "Đã lưu ${file.name}", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Không thể lưu: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { saveLauncher.launch(file.name) },
            modifier = Modifier.weight(1f),
            enabled = file.isFile && file.length() > 0L,
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Text(saveLabel)
        }
        OutlinedButton(
            onClick = {
                runCatching { FileExportManager.shareFile(context, file) }
                    .onFailure { Toast.makeText(context, "Không thể chia sẻ: ${it.message}", Toast.LENGTH_LONG).show() }
            },
            modifier = Modifier.weight(1f),
            enabled = file.isFile && file.length() > 0L,
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Text(shareLabel)
        }
    }
}
