package com.example.mapasssist.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TextFileDao {
    @Query("SELECT * FROM text_files ORDER BY lastModified DESC")
    fun getAllTextFiles(): Flow<List<TextFile>>

    @Query("SELECT * FROM text_files WHERE id = :id")
    suspend fun getTextFileById(id: Int): TextFile?

    @Query("SELECT * FROM text_files ORDER BY mapNo COLLATE NOCASE")
    suspend fun getAllForBackup(): List<TextFile>

    @Query("SELECT MAX(lastModified) FROM text_files")
    suspend fun latestModified(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(textFile: TextFile)

    @Update
    suspend fun update(textFile: TextFile)

    @Delete
    suspend fun delete(textFile: TextFile)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(textFiles: List<TextFile>)

    @Query("DELETE FROM text_files")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(textFiles: List<TextFile>) {
        clearAll()
        insertAll(textFiles)
    }
}
