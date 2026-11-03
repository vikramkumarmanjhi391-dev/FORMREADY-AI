package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.database.FormReadyDatabase
import com.example.data.database.LockerDocumentDao
import com.example.data.database.LockerDocumentEntity
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.storage.DocumentEncryptionManager
import com.example.storage.DocumentValidationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

enum class SortOption(val label: String) {
    RECENT("Recently Added"),
    OLDEST("Oldest First"),
    NAME_AZ("Name (A–Z)"),
    SIZE_ASC("Size (Smallest)"),
    SIZE_DESC("Size (Largest)")
}

data class StorageReconciliationReport(
    val totalRecords: Int,
    val availableCount: Int,
    val missingFilesCount: Int,
    val orphanedCleanedCount: Int
)

class DocumentLockerRepository(
    private val context: Context,
    private val dao: LockerDocumentDao = FormReadyDatabase.getInstance(context).lockerDocumentDao(),
    private val encryptionManager: DocumentEncryptionManager = DocumentEncryptionManager(context)
) {
    // Encrypted vault folder in private no_backup storage to exclude from auto-backups
    private val vaultDir: File by lazy {
        File(context.noBackupFilesDir, "document_locker").apply {
            if (!exists()) mkdirs()
        }
    }

    // Temporary cache folder for sharing & previewing decrypted files
    private val decryptedCacheDir: File by lazy {
        File(context.cacheDir, "decrypted_shares").apply {
            if (!exists()) mkdirs()
        }
    }

    val allDocuments: Flow<List<LockerDocumentEntity>> = dao.getAllDocuments()

    fun getFilteredAndSortedDocuments(
        query: String,
        category: String,
        sortOption: SortOption
    ): Flow<List<LockerDocumentEntity>> {
        val baseFlow = if (query.isBlank()) {
            if (category == "All") dao.getAllDocuments() else dao.getDocumentsByCategory(category)
        } else {
            dao.searchDocuments(query.trim())
        }

        return baseFlow.map { list ->
            val filtered = if (category != "All" && query.isNotBlank()) {
                list.filter { it.category.equals(category, ignoreCase = true) }
            } else {
                list
            }

            when (sortOption) {
                SortOption.RECENT -> filtered.sortedByDescending { it.createdAt }
                SortOption.OLDEST -> filtered.sortedBy { it.createdAt }
                SortOption.NAME_AZ -> filtered.sortedBy { it.title.lowercase() }
                SortOption.SIZE_ASC -> filtered.sortedBy { it.fileSizeBytes }
                SortOption.SIZE_DESC -> filtered.sortedByDescending { it.fileSizeBytes }
            }
        }
    }

    /**
     * Checks if a document matching this file's SHA-256 hash already exists in the locker.
     */
    suspend fun checkDuplicate(uri: Uri): LockerDocumentEntity? = withContext(Dispatchers.IO) {
        val validation = DocumentValidationManager.validateUri(context, uri)
        if (validation.isValid && validation.sha256Hex.isNotBlank()) {
            dao.getDocumentByHash(validation.sha256Hex)
        } else {
            null
        }
    }

    /**
     * Imports a genuine document from an Android Storage Access Framework URI into the encrypted vault.
     * Validates magic bytes, streams encryption to disk, and persists metadata in Room.
     */
    suspend fun importDocument(
        uri: Uri,
        title: String,
        documentType: DocumentType,
        notes: String = "",
        isMasked: Boolean = false
    ): Result<LockerDocumentEntity> = withContext(Dispatchers.IO) {
        try {
            // 1. Validate source file
            val validation = DocumentValidationManager.validateUri(context, uri)
            if (!validation.isValid) {
                return@withContext Result.failure(
                    IllegalArgumentException(validation.errorMessage ?: "File validation failed")
                )
            }

            val format = validation.detectedFormat ?: FileFormat.PDF
            val originalName = uri.lastPathSegment?.substringAfterLast('/') ?: "document.${format.extension}"

            val docId = "doc_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val encryptedFileName = "$docId.vault"
            val targetEncryptedFile = File(vaultDir, encryptedFileName)
            val tempEncryptedFile = File(vaultDir, "$encryptedFileName.tmp")

            // 2. Stream-encrypt from URI to temporary file
            val inStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open input stream for URI"))

            try {
                inStream.use { input ->
                    FileOutputStream(tempEncryptedFile).use { output ->
                        encryptionManager.encryptStream(input, output)
                    }
                }
                // Atomically move to final vault file
                if (!tempEncryptedFile.renameTo(targetEncryptedFile)) {
                    tempEncryptedFile.copyTo(targetEncryptedFile, overwrite = true)
                    tempEncryptedFile.delete()
                }
            } catch (e: Exception) {
                if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
                if (targetEncryptedFile.exists()) targetEncryptedFile.delete()
                return@withContext Result.failure(e)
            }

            // 3. Persist metadata entity in Room
            val entity = LockerDocumentEntity(
                id = docId,
                title = title.ifBlank { originalName.substringBeforeLast('.') },
                documentType = documentType.name,
                category = documentType.category,
                originalFileName = originalName,
                storedEncryptedFileName = encryptedFileName,
                mimeType = validation.mimeType,
                fileFormat = format.name,
                fileSizeBytes = validation.fileSizeBytes,
                sha256Hash = validation.sha256Hex,
                createdAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis(),
                notes = notes,
                isMasked = isMasked,
                integrityStatus = "AVAILABLE"
            )

            try {
                dao.insertDocument(entity)
                Result.success(entity)
            } catch (e: Exception) {
                if (targetEncryptedFile.exists()) targetEncryptedFile.delete()
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Imports in-memory byte array directly into the vault (e.g. from Photo Studio, Signature Studio, or PDF Master).
     */
    suspend fun importBytes(
        bytes: ByteArray,
        title: String,
        documentType: DocumentType,
        originalFileName: String,
        format: FileFormat,
        notes: String = ""
    ): Result<LockerDocumentEntity> = withContext(Dispatchers.IO) {
        try {
            val docId = "doc_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val encryptedFileName = "$docId.vault"
            val targetEncryptedFile = File(vaultDir, encryptedFileName)
            val tempEncryptedFile = File(vaultDir, "$encryptedFileName.tmp")

            // Validate stream
            val validation = ByteArrayInputStream(bytes).use { stream ->
                DocumentValidationManager.validateStream(stream, bytes.size.toLong(), originalFileName)
            }
            if (!validation.isValid) {
                return@withContext Result.failure(
                    IllegalArgumentException(validation.errorMessage ?: "Validation failed on generated bytes")
                )
            }

            // Encrypt
            try {
                ByteArrayInputStream(bytes).use { input ->
                    FileOutputStream(tempEncryptedFile).use { output ->
                        encryptionManager.encryptStream(input, output)
                    }
                }
                if (!tempEncryptedFile.renameTo(targetEncryptedFile)) {
                    tempEncryptedFile.copyTo(targetEncryptedFile, overwrite = true)
                    tempEncryptedFile.delete()
                }
            } catch (e: Exception) {
                if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
                if (targetEncryptedFile.exists()) targetEncryptedFile.delete()
                return@withContext Result.failure(e)
            }

            val entity = LockerDocumentEntity(
                id = docId,
                title = title.ifBlank { originalFileName.substringBeforeLast('.') },
                documentType = documentType.name,
                category = documentType.category,
                originalFileName = originalFileName,
                storedEncryptedFileName = encryptedFileName,
                mimeType = format.mimeType,
                fileFormat = format.name,
                fileSizeBytes = bytes.size.toLong(),
                sha256Hash = validation.sha256Hex,
                createdAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis(),
                notes = notes,
                isMasked = false,
                integrityStatus = "AVAILABLE"
            )

            try {
                dao.insertDocument(entity)
                Result.success(entity)
            } catch (e: Exception) {
                if (targetEncryptedFile.exists()) targetEncryptedFile.delete()
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Decrypts a stored document into a temporary file in `cacheDir/decrypted_shares`
     * for previewing or sharing via FileProvider.
     */
    suspend fun decryptToTempFile(id: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val entity = dao.getDocumentByIdSync(id)
                ?: return@withContext Result.failure(NoSuchElementException("Document record not found"))

            val encryptedFile = File(vaultDir, entity.storedEncryptedFileName)
            if (!encryptedFile.exists()) {
                dao.updateIntegrityStatus(id, "MISSING_FILE")
                return@withContext Result.failure(IllegalStateException("Encrypted document file is missing from vault"))
            }

            val ext = try {
                FileFormat.valueOf(entity.fileFormat).extension
            } catch (e: Exception) {
                "bin"
            }
            val safeName = entity.title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val tempFile = File(decryptedCacheDir, "preview_${id}_$safeName.$ext")

            encryptionManager.decryptFile(encryptedFile, tempFile)
            Result.success(tempFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streams decrypted document directly to an external destination [OutputStream]
     * (used by SAF CreateDocument without creating intermediate unencrypted disk copies).
     */
    suspend fun exportDocumentToStream(id: String, outputStream: OutputStream): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val entity = dao.getDocumentByIdSync(id)
                ?: return@withContext Result.failure(NoSuchElementException("Document not found"))

            val encryptedFile = File(vaultDir, entity.storedEncryptedFileName)
            if (!encryptedFile.exists()) {
                dao.updateIntegrityStatus(id, "MISSING_FILE")
                return@withContext Result.failure(IllegalStateException("Vault file missing on disk"))
            }

            FileInputStream(encryptedFile).use { inStream ->
                encryptionManager.decryptStream(inStream, outputStream)
            }
            Result.success(entity.fileSizeBytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Updates document metadata without needing to re-encrypt the file content.
     */
    suspend fun updateMetadata(
        id: String,
        title: String,
        documentType: DocumentType,
        notes: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            dao.updateMetadata(
                id = id,
                title = title.trim(),
                documentType = documentType.name,
                category = documentType.category,
                notes = notes.trim(),
                timestamp = System.currentTimeMillis()
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Permanently deletes a document from the vault and removes its Room database entry.
     */
    suspend fun deleteDocument(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entity = dao.getDocumentByIdSync(id)
            if (entity != null) {
                val file = File(vaultDir, entity.storedEncryptedFileName)
                if (file.exists()) {
                    file.delete()
                }
            }
            dao.deleteDocumentById(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reconciles on-disk files with database records:
     * - Marks missing files as MISSING_FILE
     * - Cleans up abandoned `.tmp` files
     */
    suspend fun reconcileStorage(): StorageReconciliationReport = withContext(Dispatchers.IO) {
        val records = dao.getAllDocumentsSync()
        var availableCount = 0
        var missingCount = 0
        var cleanedOrphans = 0

        for (doc in records) {
            val file = File(vaultDir, doc.storedEncryptedFileName)
            if (!file.exists()) {
                missingCount++
                dao.updateIntegrityStatus(doc.id, "MISSING_FILE")
            } else {
                availableCount++
                if (doc.integrityStatus != "AVAILABLE") {
                    dao.updateIntegrityStatus(doc.id, "AVAILABLE")
                }
            }
        }

        // Clean up temporary files in vaultDir
        vaultDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                file.delete()
                cleanedOrphans++
            }
        }

        // Clean up old temporary decrypted shares older than 24 hours
        val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        decryptedCacheDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }

        StorageReconciliationReport(
            totalRecords = records.size,
            availableCount = availableCount,
            missingFilesCount = missingCount,
            orphanedCleanedCount = cleanedOrphans
        )
    }

    suspend fun getDocumentById(id: String): LockerDocumentEntity? = withContext(Dispatchers.IO) {
        dao.getDocumentByIdSync(id)
    }
}
