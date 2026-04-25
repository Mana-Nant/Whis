package com.example.whisperandroid.ui.transcribe

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.whisperandroid.WhisperApplication
import com.example.whisperandroid.data.model.WhisperModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TranscribeViewModel(app: Application) : AndroidViewModel(app) {

    private val whisperApp = app as WhisperApplication

    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri = _selectedUri.asStateFlow()

    private val _selectedName = MutableStateFlow<String?>(null)
    val selectedName = _selectedName.asStateFlow()

    private val _currentModelName = MutableStateFlow<String?>(whisperApp.preferences.currentModelFileName)
    val currentModelName = _currentModelName.asStateFlow()

    fun setInput(uri: Uri, displayName: String) {
        _selectedUri.value = uri
        _selectedName.value = displayName
    }

    fun refreshCurrentModel() {
        _currentModelName.value = whisperApp.preferences.currentModelFileName
    }

    fun currentModelPath(): String? {
        val name = whisperApp.preferences.currentModelFileName ?: return null
        val file = java.io.File(whisperApp.modelRepository.modelsDir, name)
        return if (file.exists()) file.absolutePath else null
    }

    fun currentModel(): WhisperModel? =
        whisperApp.preferences.currentModelFileName?.let { WhisperModel.fromFileName(it) }
}
