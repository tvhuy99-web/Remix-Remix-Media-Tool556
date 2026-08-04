package com.aistudio.mediatool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.aistudio.mediatool.core.CacheUtils
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.media.RecordingManager
import com.aistudio.mediatool.core.ml.VoiceCleanupService
import com.aistudio.mediatool.navigation.AppNavigation
import com.aistudio.mediatool.ui.theme.MediaToolTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    DiagnosticLogger.info(
      component = "MainActivity",
      event = "activity_created",
      fields = mapOf("restored_instance" to (savedInstanceState != null)),
    )
    enableEdgeToEdge()

    RecordingManager.restore(this)
    VoiceCleanupService.restorePersistedState(this)

    // Dọn dẹp cache rác lúc khởi động, giữ lại file đang ghi hoặc vừa ghi xong nếu có
    lifecycleScope.launch {
      val excludeFile = RecordingManager.outputFile.value
      runCatching {
        CacheUtils.clearOldCache(this@MainActivity, excludeFile?.let { listOf(it) } ?: emptyList())
      }.onSuccess { result ->
        DiagnosticLogger.info(
          component = "CacheUtils",
          event = "cache_cleanup_complete",
          fields = mapOf(
            "scanned" to result.scanned,
            "deleted" to result.deleted,
            "failures" to result.failures,
          ),
        )
      }.onFailure { error ->
        DiagnosticLogger.warn(
          component = "CacheUtils",
          event = "cache_cleanup_failed",
          message = error.message,
          error = error,
        )
      }
    }

    setContent {
      MediaToolTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background,
        ) {
          AppNavigation()
        }
      }
    }
  }
}
