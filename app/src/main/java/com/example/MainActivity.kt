package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.navigation.AppNavigation
import com.example.ui.theme.MyApplicationTheme
import com.example.core.CacheUtils
import com.example.core.media.RecordingManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    
    // Dọn dẹp cache rác lúc khởi động, giữ lại file đang ghi hoặc vừa ghi xong nếu có
    lifecycleScope.launch {
      val excludeFile = RecordingManager.outputFile.value
      CacheUtils.clearOldCache(this@MainActivity, excludeFile?.let { listOf(it) } ?: emptyList())
    }

    setContent {
      MyApplicationTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
          AppNavigation()
        }
      }
    }
  }
}
