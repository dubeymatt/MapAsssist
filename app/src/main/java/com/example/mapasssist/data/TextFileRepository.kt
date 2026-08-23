package com.example.mapasssist.data

import kotlinx.coroutines.flow.Flow

class TextFileRepository(private val textFileDao: TextFileDao) {
    val allTextFiles: Flow<List<TextFile>> = textFileDao.getAllTextFiles()

    suspend fun getTextFileById(id: Int): TextFile? = textFileDao.getTextFileById(id)

    suspend fun insert(textFile: TextFile) {
        textFileDao.insert(textFile)
    }

    suspend fun update(textFile: TextFile) {
        textFileDao.update(textFile)
    }

    suspend fun delete(textFile: TextFile) {
        textFileDao.delete(textFile)
    }
}
