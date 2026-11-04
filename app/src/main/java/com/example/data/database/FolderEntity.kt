package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a persistent custom folder created by the user
 * to organize exam, college, or personal documents.
 */
@Entity(tableName = "custom_folders")
data class FolderEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val colorHex: String = "#1E3A8A",
    val iconName: String = "folder",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
