package com.example.mapasssist.data

import android.app.Application
import androidx.lifecycle.*
import com.example.mapasssist.GoogleDriveBackup
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
        GoogleDriveBackup.queueAutomaticSharedSync(getApplication())
    }

    fun update(textFile: TextFile) = viewModelScope.launch {
        repository.update(textFile)
        SharedActivityStore.record(getApplication(), "Updated DNC card ${textFile.mapNo} – ${textFile.title}")
        GoogleDriveBackup.queueAutomaticSharedSync(getApplication())
    }

    fun delete(textFile: TextFile) = viewModelScope.launch {
        repository.delete(textFile)
        SharedActivityStore.record(getApplication(), "Deleted DNC card ${textFile.mapNo} – ${textFile.title}")
        GoogleDriveBackup.queueAutomaticSharedSync(getApplication())
    }

    fun replaceAll(textFiles: List<TextFile>) = viewModelScope.launch {
        repository.replaceAll(textFiles)
        SharedActivityStore.record(getApplication(), "Replaced all DNC cards")
        GoogleDriveBackup.queueAutomaticSharedSync(getApplication())
    }

    suspend fun getTextFileById(id: Int): TextFile? {
        return repository.getTextFileById(id)
    }
}
