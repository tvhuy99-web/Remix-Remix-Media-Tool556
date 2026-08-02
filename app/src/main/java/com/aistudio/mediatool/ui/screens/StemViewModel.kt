package com.aistudio.mediatool.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.mediatool.core.PersistentTaskStatus
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.TaskStateStore
import com.aistudio.mediatool.core.ml.DownloadState
import com.aistudio.mediatool.core.ml.ModelDownloader
import com.aistudio.mediatool.core.ml.StemMode
import com.aistudio.mediatool.core.ml.StemModelDescriptor
import com.aistudio.mediatool.core.ml.StemModelRegistry
import com.aistudio.mediatool.core.ml.StemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StemViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val downloader = ModelDownloader(appContext)
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    private val _selectedModel = MutableStateFlow(configuredModel())
    val selectedModel: StateFlow<StemModelDescriptor> = _selectedModel.asStateFlow()
    private val _fallbackNotice = MutableStateFlow<String?>(null)
    val fallbackNotice: StateFlow<String?> = _fallbackNotice.asStateFlow()
    private var downloadJob: Job? = null
    private var initializationJob: Job? = null

    init {
        inspectSelectedModel(_selectedModel.value)
    }

    fun refreshConfiguredModel() {
        val configured = configuredModel()
        if (configured.id != _selectedModel.value.id) inspectSelectedModel(configured)
    }

    fun applyLowMemoryFallbackIfNeeded() {
        val task = TaskStateStore.load(appContext, StemService.TASK_TYPE) ?: return
        val current = _selectedModel.value
        val alreadyHandled = SettingsManager.getStemLowMemoryFallbackTaskId(appContext) == task.taskId
        val wasMelBandLowMemory =
            task.status == PersistentTaskStatus.INTERRUPTED &&
                task.message?.contains("thiếu RAM", ignoreCase = true) == true &&
                current.id == StemModelRegistry.MEL_BAND_ROFORMER_ID
        if (!wasMelBandLowMemory || alreadyHandled) return

        val fallback = StemModelRegistry.demucsTwoStemLite
        SettingsManager.setStemModeIndex(appContext, fallback.mode.settingsIndex)
        SettingsManager.setStemModelId(appContext, fallback.mode.settingsIndex, fallback.id)
        SettingsManager.setStemLowMemoryFallbackTaskId(appContext, task.taskId)
        _fallbackNotice.value =
            "Mel-Band đã bị Android dừng vì thiếu RAM. Ứng dụng đã chuyển sang Demucs nhẹ; hãy tải model mới rồi thử lại."
        inspectSelectedModel(fallback)
    }

    fun selectModel(modelId: String) {
        val model = StemModelRegistry.find(modelId) ?: return
        SettingsManager.setStemModeIndex(appContext, model.mode.settingsIndex)
        SettingsManager.setStemModelId(appContext, model.mode.settingsIndex, model.id)
        _fallbackNotice.value = null
        inspectSelectedModel(model)
    }

    private fun configuredModel(): StemModelDescriptor {
        val modeIndex = SettingsManager.getStemModeIndex(appContext)
        val mode = StemMode.fromSettingsIndex(modeIndex)
        return StemModelRegistry.resolve(mode, SettingsManager.getStemModelId(appContext, modeIndex))
    }

    private fun inspectSelectedModel(model: StemModelDescriptor) {
        downloadJob?.cancel()
        initializationJob?.cancel()
        downloadJob = null
        _selectedModel.value = model
        _downloadState.value = DownloadState.Idle
        initializationJob = viewModelScope.launch {
            val spec = model.modelSpec
            val initialState = withContext(Dispatchers.IO) {
                when {
                    // SHA-256 có thể phải đọc gần 1 GB, tuyệt đối không chạy trên main thread.
                    downloader.isModelDownloaded(spec) -> DownloadState.Success(downloader.modelFile(spec))
                    downloader.partialFile(spec).length() > 0L -> {
                        val progress = downloader.partialFile(spec).length().toDouble() / spec.expectedBytes.toDouble()
                        DownloadState.Downloading(progress.toFloat().coerceIn(0f, 0.999f))
                    }
                    else -> DownloadState.Idle
                }
            }
            if (_selectedModel.value.id == model.id) _downloadState.value = initialState
        }
    }

    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        val model = _selectedModel.value
        downloadJob = viewModelScope.launch {
            initializationJob?.join()
            if (_selectedModel.value.id != model.id || _downloadState.value is DownloadState.Success) return@launch
            downloader.downloadModel(model.modelSpec, model.id).collect { state ->
                if (_selectedModel.value.id == model.id) _downloadState.value = state
            }
        }
    }

    fun pauseDownload() {
        val model = _selectedModel.value
        downloadJob?.cancel()
        downloadJob = null
        viewModelScope.launch(Dispatchers.IO) {
            val partial = downloader.partialFile(model.modelSpec)
            val state = if (partial.length() > 0L) {
                val progress = partial.length().toDouble() / model.modelSpec.expectedBytes.toDouble()
                DownloadState.Downloading(progress.toFloat().coerceIn(0f, 0.999f))
            } else {
                DownloadState.Idle
            }
            if (_selectedModel.value.id == model.id) _downloadState.value = state
        }
    }

    fun discardPartialDownload() {
        val model = _selectedModel.value
        downloadJob?.cancel()
        downloadJob = null
        viewModelScope.launch(Dispatchers.IO) {
            downloader.deletePartial(model.modelSpec)
            if (_selectedModel.value.id == model.id) _downloadState.value = DownloadState.Idle
        }
    }
}
