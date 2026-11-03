package com.example.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room database entity storing persistent metadata for documents in the encrypted vault.
 * Contains no raw sensitive document bytes or encryption keys.
 */
@Entity(
    tableName = "locker_documents",
    indices = [
        Index(value = ["sha256Hash"]),
        Index(value = ["category"]),
        Index(value = ["documentType"]),
        Index(value = ["createdAt"])
    ]
)
data class LockerDocumentEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val documentType: String, // DocumentType enum name
    val category: String,     // Identity, Education, Reservation, Civil, Disability, Employment, General
    val originalFileName: String,
    val storedEncryptedFileName: String, // relative file name in noBackupFilesDir/document_locker
    val mimeType: String,
    val fileFormat: String,   // PDF, JPG, PNG
    val fileSizeBytes: Long,  // Genuine unencrypted file size in bytes
    val sha256Hash: String,   // SHA-256 hash for duplicate detection and integrity verification
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val notes: String = "",
    val isMasked: Boolean = false,
    val integrityStatus: String = "AVAILABLE" // AVAILABLE, MISSING_FILE, INTEGRITY_FAILED
)
