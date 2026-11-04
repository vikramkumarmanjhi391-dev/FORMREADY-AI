package com.example.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity storing persistent metadata for user notes.
 * The raw sensitive note text is NEVER stored plaintext in the database;
 * it is encrypted at rest using [com.example.storage.DocumentEncryptionManager] into private vault storage.
 */
@Entity(
    tableName = "vault_notes",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["isPinned"])
    ]
)
data class VaultNoteEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val storedEncryptedFileName: String,
    val previewSnippet: String = "",
    val colorTag: String = "#F8FAFC",
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
