package com.example.mapasssist.data

import android.app.Application
import androidx.lifecycle.*
import com.example.mapasssist.GoogleDriveBackup
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
        GoogleDriveBackup.queueAutomaticSharedSync(getApplication())
    }

    fun update(textFile: TextFile) = viewModelScope.launch {
        repository.update(textFile)
        GoogleDriveBackup.queueAutomaticSharedSync(getApplication())
    }

    fun delete(textFile: TextFile) = viewModelScope.launch {
        repository.delete(textFile)
        GoogleDriveBackup.queueAutomaticSharedSync(getApplication())
    }

    fun replaceAll(textFiles: List<TextFile>) = viewModelScope.launch {
        repository.replaceAll(textFiles)
        GoogleDriveBackup.queueAutomaticSharedSync(getApplication())
    }

    suspend fun getTextFileById(id: Int): TextFile? {
        return repository.getTextFileById(id)
    }
}
