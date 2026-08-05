package com.aistudio.mediatool.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.ml.BundledModelInstaller
import com.aistudio.mediatool.core.ml.DownloadState
import com.aistudio.mediatool.core.ml.ModelDownloader
import com.aistudio.mediatool.core.ml.StemMode
import com.aistudio.mediatool.core.ml.StemModelDescriptor
import com.aistudio.mediatool.core.ml.StemModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class StemViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val downloader = ModelDownloader(appContext)
    private val bundledInstaller = BundledModelInstaller(appContext)
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    private val _selectedModel = MutableStateFlow(configuredModel())
    val selectedModel: StateFlow<StemModelDescriptor> = _selectedModel.asStateFlow()
    private var downloadJob: Job? = null
    private var initializationJob: Job? = null

    init {
        inspectSelectedModel(_selectedModel.value)
    }

    fun refreshConfiguredModel() {
        val configured = configuredModel()
        if (configured.id != _selectedModel.value.id) inspectSelectedModel(configured)
    }

    fun selectModel(modelId: String) {
        val model = StemModelRegistry.find(modelId) ?: return
        SettingsManager.setStemModeIndex(appContext, model.mode.settingsIndex)
        SettingsManager.setStemModelId(appContext, model.mode.settingsIndex, model.id)
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
                deleteRemovedMelBandFiles()
                when {
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

    private fun deleteRemovedMelBandFiles() {
        File(appContext.filesDir, "models").listFiles().orEmpty()
            .filter { it.name.startsWith(REMOVED_MEL_BAND_PREFIX) }
            .forEach(File::delete)
    }

    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        val model = _selectedModel.value
        downloadJob = viewModelScope.launch {
            initializationJob?.join()
            if (_selectedModel.value.id != model.id || _downloadState.value is DownloadState.Success) return@launch
            val states = if (model.id == StemModelRegistry.MDX23C_VOCAL_PERSONAL_ID) {
                bundledInstaller.install(
                    spec = model.modelSpec,
                    assetPath = MDX23C_BUNDLED_ASSET,
                    modelId = model.id,
                )
            } else {
                downloader.downloadModel(model.modelSpec, model.id)
            }
            states.collect { state ->
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

    companion object {
        private const val REMOVED_MEL_BAND_PREFIX = "melband-roformer-kj-vocals-"
        private const val MDX23C_BUNDLED_ASSET = "models/mdx23c-vocals-core.onnx"
    }
}
