package com.example.mapasssist.data

import android.app.Application
import androidx.lifecycle.*
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
    }

    fun update(textFile: TextFile) = viewModelScope.launch {
        repository.update(textFile)
    }

    fun delete(textFile: TextFile) = viewModelScope.launch {
        repository.delete(textFile)
    }

    suspend fun getTextFileById(id: Int): TextFile? {
        return repository.getTextFileById(id)
    }
}
