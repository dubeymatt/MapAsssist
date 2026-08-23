package com.example.mapasssist.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "text_files")
data class TextFile(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val content: String,
    val lastModified: Long = System.currentTimeMillis()
)
