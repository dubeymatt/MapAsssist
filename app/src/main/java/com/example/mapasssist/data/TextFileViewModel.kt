package com.example.mapasssist.data

import android.app.Application
import androidx.lifecycle.*
import com.example.mapasssist.FirebaseSharedLibrary
import com.example.mapasssist.SharedActivityStore
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
        SharedActivityStore.record(getApplication(), "Created DNC card ${textFile.mapNo} – ${textFile.title}")
        runCatching { FirebaseSharedLibrary.publishCard(getApplication(), textFile) }.onFailure { FirebaseSharedLibrary.queueSync(getApplication()) }
    }

    fun update(textFile: TextFile) = viewModelScope.launch {
        val previous = repository.getTextFileById(textFile.id)
        repository.update(textFile)
        SharedActivityStore.record(getApplication(), "Updated DNC card ${textFile.mapNo} – ${textFile.title}")
        runCatching {
            if (previous != null && previous.mapNo != textFile.mapNo) FirebaseSharedLibrary.deleteCard(getApplication(), previous.mapNo)
            FirebaseSharedLibrary.publishCard(getApplication(), textFile)
        }.onFailure { FirebaseSharedLibrary.queueSync(getApplication()) }
    }

    fun delete(textFile: TextFile) = viewModelScope.launch {
        repository.delete(textFile)
        SharedActivityStore.record(getApplication(), "Deleted DNC card ${textFile.mapNo} – ${textFile.title}")
        runCatching { FirebaseSharedLibrary.deleteCard(getApplication(), textFile.mapNo) }.onFailure { FirebaseSharedLibrary.queueSync(getApplication()) }
    }

    fun replaceAll(textFiles: List<TextFile>) = viewModelScope.launch {
        repository.replaceAll(textFiles)
        SharedActivityStore.record(getApplication(), "Replaced all DNC cards")
        runCatching { FirebaseSharedLibrary.replaceCards(getApplication(), textFiles) }.onFailure { FirebaseSharedLibrary.queueSync(getApplication()) }
    }

    suspend fun getTextFileById(id: Int): TextFile? {
        return repository.getTextFileById(id)
    }
}
