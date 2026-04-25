package com.example.whisperandroid.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.whisperandroid.WhisperApplication
import com.example.whisperandroid.data.model.WhisperModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val whisperApp = app as WhisperApplication
    private val repo = whisperApp.modelRepository
    private val prefs = whisperApp.preferences

    private val _installed = MutableStateFlow<List<File>>(emptyList())
    val installed = _installed.asStateFlow()

    private val _currentModel = MutableStateFlow<String?>(prefs.currentModelFileName)
    val currentModel = _currentModel.asStateFlow()

    private val _autoExport = MutableStateFlow(prefs.autoExportEnabled)
    val autoExport = _autoExport.asStateFlow()

    private val _exportDir = MutableStateFlow<String?>(prefs.defaultExportTreeUri)
    val exportDir = _exportDir.asStateFlow()

    private val _downloading = MutableStateFlow<Map<String, Int>>(emptyMap()) // fileName -> percent
    val downloading = _downloading.asStateFlow()

    init { refreshInstalled() }

    fun refreshInstalled() {
        _installed.value = repo.listInstalled()
    }

    fun setCurrentModel(fileName: String) {
        prefs.currentModelFileName = fileName
        _currentModel.value = fileName
    }

    fun toggleAutoExport(on: Boolean) {
        prefs.autoExportEnabled = on
        _autoExport.value = on
    }

    fun setExportDir(uri: Uri) {
        prefs.defaultExportTreeUri = uri.toString()
        _exportDir.value = uri.toString()
    }

    fun startDownload(model: WhisperModel) {
        val id = repo.enqueueDownload(model)
        viewModelScope.launch {
            repo.pollProgress(id).collect { percent ->
                _downloading.value = _downloading.value + (model.fileName to percent)
            }
        }
        viewModelScope.launch {
            repo.observeDownload(id).collect { ok ->
                _downloading.value = _downloading.value - model.fileName
                if (ok) refreshInstalled()
            }
        }
    }

    fun importModel(uri: Uri, displayName: String) {
        viewModelScope.launch {
            repo.importFromUri(uri, displayName)
            refreshInstalled()
        }
    }

    fun deleteModel(file: File) {
        viewModelScope.launch {
            repo.deleteFile(file)
            if (prefs.currentModelFileName == file.name) {
                prefs.currentModelFileName = null
                _currentModel.value = null
            }
            refreshInstalled()
        }
    }
}
