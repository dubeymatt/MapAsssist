package com.example.mapasssist.data

import android.app.Application
import androidx.lifecycle.*
import com.example.mapasssist.FirebaseSharedLibrary
import com.example.mapasssist.SharedActivityStore
import com.example.mapasssist.data.DncEntryCodec
import kotlinx.coroutines.launch

class TextFileViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: TextFileRepository
    val allTextFiles: LiveData<List<TextFile>>

    init {
        val textFileDao = AppDatabase.getDatabase(application).textFileDao()
        repository = TextFileRepository(textFileDao)
        allTextFiles = repository.allTextFiles.asLiveData()
    }

    fun insert(textFile: TextFile) = viewModelScope.launch {
        repository.insert(textFile)
        SharedActivityStore.recordDnc(getApplication(), "added", textFile.mapNo, textFile.title)
        runCatching { FirebaseSharedLibrary.publishCard(getApplication(), textFile) }.onFailure { FirebaseSharedLibrary.queueSync(getApplication()) }
    }

    fun update(textFile: TextFile) = viewModelScope.launch {
        val previous = repository.getTextFileById(textFile.id)
        repository.update(textFile)
        val action = when {
            previous == null -> "modified"
            previous.entriesJson != textFile.entriesJson -> when {
                DncEntryCodec.decode(textFile.entriesJson).size > DncEntryCodec.decode(previous.entriesJson).size -> "entry added"
                DncEntryCodec.decode(textFile.entriesJson).size < DncEntryCodec.decode(previous.entriesJson).size -> "entry removed"
                else -> "entry modified"
            }
            else -> "modified"
        }
        SharedActivityStore.recordDnc(getApplication(), action, textFile.mapNo, textFile.title)
        runCatching {
            FirebaseSharedLibrary.updateCard(getApplication(), previous?.mapNo, textFile)
        }.onFailure { FirebaseSharedLibrary.queueSync(getApplication()) }
    }

    fun delete(textFile: TextFile) = viewModelScope.launch {
        repository.delete(textFile)
        SharedActivityStore.recordDnc(getApplication(), "removed", textFile.mapNo, textFile.title)
        runCatching { FirebaseSharedLibrary.deleteCard(getApplication(), textFile.mapNo) }.onFailure { FirebaseSharedLibrary.queueSync(getApplication()) }
    }

    fun replaceAll(textFiles: List<TextFile>) = viewModelScope.launch {
        repository.replaceAll(textFiles)
        SharedActivityStore.record(getApplication(), "DNC cards replaced")
        runCatching { FirebaseSharedLibrary.replaceCards(getApplication(), textFiles) }.onFailure { FirebaseSharedLibrary.queueSync(getApplication()) }
    }

    /** Restores a backup locally first. The Settings screen decides whether it should replace a live library. */
    fun restoreBackupLocally(textFiles: List<TextFile>, onComplete: () -> Unit) = viewModelScope.launch {
        repository.replaceAll(textFiles)
        onComplete()
    }

    suspend fun getTextFileById(id: Int): TextFile? {
        return repository.getTextFileById(id)
    }
}
