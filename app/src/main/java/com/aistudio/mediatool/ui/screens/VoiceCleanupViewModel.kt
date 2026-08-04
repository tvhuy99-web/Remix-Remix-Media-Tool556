package com.aistudio.mediatool.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.mediatool.core.ml.DownloadState
import com.aistudio.mediatool.core.ml.ModelDownloader
import com.aistudio.mediatool.core.ml.VoiceCleanupModelDescriptor
import com.aistudio.mediatool.core.ml.VoiceCleanupModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceCleanupViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val downloader = ModelDownloader(appContext)
    val model: VoiceCleanupModelDescriptor = VoiceCleanupModelRegistry.mossFormer2

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    private var downloadJob: Job? = null
    private var initializationJob: Job? = null

    init {
        refreshModelState()
    }

    fun refreshModelState() {
        downloadJob?.cancel()
        initializationJob?.cancel()
        downloadJob = null
        initializationJob = viewModelScope.launch {
            _downloadState.value = withContext(Dispatchers.IO) {
                when {
                    downloader.isModelDownloaded(model.modelSpec) -> {
                        DownloadState.Success(downloader.modelFile(model.modelSpec))
                    }
                    downloader.partialFile(model.modelSpec).length() > 0L -> {
                        val progress = downloader.partialFile(model.modelSpec).length().toDouble() /
                            model.modelSpec.expectedBytes.toDouble()
                        DownloadState.Downloading(progress.toFloat().coerceIn(0f, 0.999f))
                    }
                    else -> DownloadState.Idle
                }
            }
        }
    }

    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            initializationJob?.join()
            if (_downloadState.value is DownloadState.Success) return@launch
            downloader.downloadModel(model.modelSpec, model.id).collect(_downloadState::emit)
        }
    }

    fun pauseDownload() {
        downloadJob?.cancel()
        downloadJob = null
        viewModelScope.launch(Dispatchers.IO) {
            val partial = downloader.partialFile(model.modelSpec)
            _downloadState.value = if (partial.length() > 0L) {
                val progress = partial.length().toDouble() / model.modelSpec.expectedBytes.toDouble()
                DownloadState.Downloading(progress.toFloat().coerceIn(0f, 0.999f))
            } else {
                DownloadState.Idle
            }
        }
    }

    fun discardPartialDownload() {
        downloadJob?.cancel()
        downloadJob = null
        viewModelScope.launch(Dispatchers.IO) {
            downloader.deletePartial(model.modelSpec)
            _downloadState.value = DownloadState.Idle
        }
    }
}
