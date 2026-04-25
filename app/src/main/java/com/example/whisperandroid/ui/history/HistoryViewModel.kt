package com.example.whisperandroid.ui.history

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.whisperandroid.WhisperApplication
import com.example.whisperandroid.data.db.TranscriptionEntity
import com.example.whisperandroid.util.FileUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val whisperApp = app as WhisperApplication
    private val dao = whisperApp.database.transcriptionDao()

    val items = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(entity: TranscriptionEntity) {
        viewModelScope.launch { dao.deleteById(entity.id) }
    }

    /** SAFで選んだディレクトリに .txt として保存。 */
    fun exportToTree(entity: TranscriptionEntity, treeUri: Uri, onDone: (Uri?) -> Unit) {
        viewModelScope.launch {
            val name = entity.sourceName.substringBeforeLast('.', entity.sourceName) + ".txt"
            val result = FileUtils.writeTextToTree(getApplication(), treeUri, name, entity.text)
            onDone(result)
        }
    }
}
