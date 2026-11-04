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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import com.example.data.database.FolderDao
import com.example.data.database.FolderEntity
import com.example.data.database.FolderWithCount
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
    val integrityFailedCount: Int,
    val orphanedVaultFilesCount: Int,
    val orphanedVaultFileNames: List<String> = emptyList(),
    val abandonedTempCleanedCount: Int
)

class DocumentLockerRepository(
    private val context: Context,
    private val dao: LockerDocumentDao = FormReadyDatabase.getInstance(context).lockerDocumentDao(),
    private val folderDao: FolderDao = FormReadyDatabase.getInstance(context).folderDao(),
    private val encryptionManager: DocumentEncryptionManager = DocumentEncryptionManager(context)
) {
    constructor(
        context: Context,
        dao: LockerDocumentDao,
        encryptionManager: DocumentEncryptionManager
    ) : this(
        context = context,
        dao = dao,
        folderDao = FormReadyDatabase.getInstance(context).folderDao(),
        encryptionManager = encryptionManager
    )

    companion object {
        const val STATUS_AVAILABLE = "AVAILABLE"
        const val STATUS_MISSING_FILE = "MISSING_FILE"
        const val STATUS_INTEGRITY_FAILED = "INTEGRITY_FAILED"
        const val STATUS_PENDING_DELETE = "PENDING_DELETE"
    }

    // Encrypted vault folder in private no_backup storage to exclude from auto-backups
    val vaultDir: File by lazy {
        File(context.noBackupFilesDir, "document_locker").apply {
            if (!exists()) mkdirs()
        }
    }

    // Temporary cache folder for sharing & previewing decrypted files
    val decryptedCacheDir: File by lazy {
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

    suspend fun checkDuplicate(uri: Uri): LockerDocumentEntity? = withContext(Dispatchers.IO) {
        val validation = DocumentValidationManager.validateUri(context, uri)
        if (validation.isValid && validation.sha256Hex.isNotBlank()) {
            dao.getDocumentByHash(validation.sha256Hex)
        } else {
            null
        }
    }

    suspend fun checkDuplicateFile(file: File): LockerDocumentEntity? = withContext(Dispatchers.IO) {
        val validation = DocumentValidationManager.validateFile(file)
        if (validation.isValid && validation.sha256Hex.isNotBlank()) {
            dao.getDocumentByHash(validation.sha256Hex)
        } else {
            null
        }
    }

    /**
     * Imports a genuine document from a disk [File] into the encrypted vault without loading into RAM.
     * Validates deep format, stream-encrypts directly to vault, and persists metadata in Room.
     */
    suspend fun importFile(
        file: File,
        title: String,
        documentType: DocumentType,
        originalFileName: String = file.name,
        notes: String = "",
        isImportant: Boolean = false,
        folderId: String? = null
    ): Result<LockerDocumentEntity> = withContext(Dispatchers.IO) {
        try {
            // 1. Deep validate source file
            val validation = DocumentValidationManager.validateFile(file)
            if (!validation.isValid) {
                return@withContext Result.failure(
                    IllegalArgumentException(validation.errorMessage ?: "File validation failed")
                )
            }

            val format = validation.detectedFormat ?: FileFormat.PDF
            val docId = "doc_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val encryptedFileName = "$docId.vault"
            val targetEncryptedFile = File(vaultDir, encryptedFileName)

            // 2. Stream-encrypt from file directly to target vault file atomically
            try {
                encryptionManager.encryptFileAtomically(file, targetEncryptedFile)
            } catch (e: Exception) {
                if (targetEncryptedFile.exists()) targetEncryptedFile.delete()
                return@withContext Result.failure(e)
            }

            // 3. Persist metadata entity in Room
            val entity = LockerDocumentEntity(
                id = docId,
                title = title.ifBlank { originalFileName.substringBeforeLast('.') },
                documentType = documentType.name,
                category = documentType.category,
                originalFileName = originalFileName,
                storedEncryptedFileName = encryptedFileName,
                mimeType = validation.mimeType,
                fileFormat = format.name,
                fileSizeBytes = validation.fileSizeBytes,
                sha256Hash = validation.sha256Hex,
                createdAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis(),
                notes = notes,
                isMasked = false,
                integrityStatus = STATUS_AVAILABLE,
                isImportant = isImportant,
                folderId = folderId
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
     * Imports a genuine document from an Android Storage Access Framework URI into the encrypted vault.
     */
    suspend fun importDocument(
        uri: Uri,
        title: String,
        documentType: DocumentType,
        notes: String = "",
        isMasked: Boolean = false,
        isImportant: Boolean = false,
        folderId: String? = null
    ): Result<LockerDocumentEntity> = withContext(Dispatchers.IO) {
        val stagingFile = File(context.cacheDir, "import_stage_${UUID.randomUUID()}.tmp")
        try {
            val inStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open input stream for URI"))

            FileOutputStream(stagingFile).use { outStream ->
                val buffer = ByteArray(16384)
                var bytesRead: Int
                var total = 0L
                inStream.use { input ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        outStream.write(buffer, 0, bytesRead)
                        total += bytesRead
                        if (total > DocumentValidationManager.MAX_FILE_SIZE_BYTES) {
                            return@withContext Result.failure(
                                IllegalArgumentException("Document exceeds maximum permitted size of 50 MB")
                            )
                        }
                    }
                }
            }

            val originalName = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
            importFile(
                file = stagingFile,
                title = title,
                documentType = documentType,
                originalFileName = originalName,
                notes = notes,
                isImportant = isImportant,
                folderId = folderId
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (stagingFile.exists()) stagingFile.delete()
        }
    }

    /**
     * Backward-compatible byte array importer (streams through temporary file, never keeping duplicate buffers).
     */
    suspend fun importBytes(
        bytes: ByteArray,
        title: String,
        documentType: DocumentType,
        originalFileName: String,
        format: FileFormat,
        notes: String = ""
    ): Result<LockerDocumentEntity> = withContext(Dispatchers.IO) {
        val stagingFile = File(context.cacheDir, "import_bytes_${UUID.randomUUID()}.${format.extension}")
        try {
            FileOutputStream(stagingFile).use { it.write(bytes) }
            importFile(stagingFile, title, documentType, originalFileName, notes)
        } finally {
            if (stagingFile.exists()) stagingFile.delete()
        }
    }

    /**
     * Decrypts a stored document into a temporary file in `cacheDir/decrypted_shares`
     * for previewing or sharing via FileProvider.
     * Enforces authenticated AES-GCM verification before releasing the file.
     */
    suspend fun decryptToTempFile(id: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val entity = dao.getDocumentByIdSync(id)
                ?: return@withContext Result.failure(NoSuchElementException("Document record not found"))

            val encryptedFile = File(vaultDir, entity.storedEncryptedFileName)
            if (!encryptedFile.exists()) {
                dao.updateIntegrityStatus(id, STATUS_MISSING_FILE)
                return@withContext Result.failure(IllegalStateException("Encrypted document file is missing from vault"))
            }

            val ext = try {
                FileFormat.valueOf(entity.fileFormat).extension
            } catch (e: Exception) {
                "bin"
            }
            val safeName = entity.title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val tempFile = File(decryptedCacheDir, "preview_${id}_$safeName.$ext")

            try {
                encryptionManager.decryptFile(encryptedFile, tempFile)
                dao.updateIntegrityStatus(id, STATUS_AVAILABLE)
                Result.success(tempFile)
            } catch (e: SecurityException) {
                dao.updateIntegrityStatus(id, STATUS_INTEGRITY_FAILED)
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Exports a document to an external URI (SAF) with full authenticated decryption.
     * Guaranteed never to release unverified plaintext bytes if authentication fails.
     */
    suspend fun exportDocumentToUri(id: String, destinationUri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val entity = dao.getDocumentByIdSync(id)
                ?: return@withContext Result.failure(NoSuchElementException("Document record not found"))

            val encryptedFile = File(vaultDir, entity.storedEncryptedFileName)
            if (!encryptedFile.exists()) {
                dao.updateIntegrityStatus(id, STATUS_MISSING_FILE)
                return@withContext Result.failure(IllegalStateException("Vault file missing on disk"))
            }

            // Stage 1: Authenticated decryption into private temp file
            val verifiedTemp = File(context.cacheDir, "export_verified_${UUID.randomUUID()}.tmp")
            try {
                encryptionManager.decryptFile(encryptedFile, verifiedTemp)
                dao.updateIntegrityStatus(id, STATUS_AVAILABLE)
            } catch (secEx: SecurityException) {
                dao.updateIntegrityStatus(id, STATUS_INTEGRITY_FAILED)
                if (verifiedTemp.exists()) verifiedTemp.delete()
                return@withContext Result.failure(secEx)
            }

            // Stage 2: Write verified plaintext to destination URI output stream
            try {
                val outStream = context.contentResolver.openOutputStream(destinationUri)
                    ?: return@withContext Result.failure(IOException("Cannot open output stream for destination URI"))

                var bytesWritten = 0L
                outStream.use { output ->
                    FileInputStream(verifiedTemp).use { input ->
                        val buffer = ByteArray(16384)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesWritten += read
                        }
                        output.flush()
                    }
                }
                Result.success(bytesWritten)
            } finally {
                if (verifiedTemp.exists()) verifiedTemp.delete()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Backward-compatible stream exporter with authenticated decryption staging.
     */
    suspend fun exportDocumentToStream(id: String, outputStream: OutputStream): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val entity = dao.getDocumentByIdSync(id)
                ?: return@withContext Result.failure(NoSuchElementException("Document not found"))

            val encryptedFile = File(vaultDir, entity.storedEncryptedFileName)
            if (!encryptedFile.exists()) {
                dao.updateIntegrityStatus(id, STATUS_MISSING_FILE)
                return@withContext Result.failure(IllegalStateException("Vault file missing on disk"))
            }

            val bytesWritten = encryptionManager.decryptAndStreamAuthenticated(encryptedFile, outputStream)
            dao.updateIntegrityStatus(id, STATUS_AVAILABLE)
            Result.success(bytesWritten)
        } catch (e: SecurityException) {
            dao.updateIntegrityStatus(id, STATUS_INTEGRITY_FAILED)
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
     * Permanently deletes a document:
     * - First marks status as PENDING_DELETE in Room
     * - Deletes encrypted vault file from disk, verifying result
     * - If file deletion fails, preserves database record and reports error
     * - Removes database record only after file is confirmed deleted
     */
    suspend fun deleteDocument(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entity = dao.getDocumentByIdSync(id)
                ?: return@withContext Result.failure(NoSuchElementException("Document record not found"))

            // 1. Mark as PENDING_DELETE in database
            dao.updateIntegrityStatus(id, STATUS_PENDING_DELETE)

            val file = File(vaultDir, entity.storedEncryptedFileName)
            if (file.exists()) {
                val deleted = file.delete()
                if (!deleted) {
                    return@withContext Result.failure(
                        IOException("Failed to delete encrypted vault file from storage. Database record preserved.")
                    )
                }
            }

            // 2. Remove Room record now that file is deleted
            dao.deleteDocumentById(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Storage reconciliation:
     * - Identifies missing files and marks MISSING_FILE
     * - Preserves INTEGRITY_FAILED states
     * - Identifies orphaned .vault files without database records
     * - Resolves PENDING_DELETE records
     * - Cleans abandoned .tmp files older than 1 hour
     */
    suspend fun reconcileStorage(): StorageReconciliationReport = withContext(Dispatchers.IO) {
        val records = dao.getAllDocumentsSync()
        var availableCount = 0
        var missingCount = 0
        var integrityFailedCount = 0
        var pendingDeleteResolved = 0
        var abandonedTempCleaned = 0

        val knownVaultFiles = mutableSetOf<String>()

        for (doc in records) {
            val file = File(vaultDir, doc.storedEncryptedFileName)
            knownVaultFiles.add(doc.storedEncryptedFileName)

            if (doc.integrityStatus == STATUS_PENDING_DELETE) {
                if (file.exists()) {
                    if (file.delete()) {
                        dao.deleteDocumentById(doc.id)
                        pendingDeleteResolved++
                    }
                } else {
                    dao.deleteDocumentById(doc.id)
                    pendingDeleteResolved++
                }
                continue
            }

            if (!file.exists()) {
                missingCount++
                if (doc.integrityStatus != STATUS_MISSING_FILE) {
                    dao.updateIntegrityStatus(doc.id, STATUS_MISSING_FILE)
                }
            } else {
                if (doc.integrityStatus == STATUS_INTEGRITY_FAILED) {
                    integrityFailedCount++
                } else {
                    availableCount++
                    if (doc.integrityStatus != STATUS_AVAILABLE) {
                        dao.updateIntegrityStatus(doc.id, STATUS_AVAILABLE)
                    }
                }
            }
        }

        // Identify orphaned .vault files (never deleted silently)
        val orphanedVaultFiles = mutableListOf<String>()
        vaultDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".vault")) {
                if (!knownVaultFiles.contains(file.name)) {
                    orphanedVaultFiles.add(file.name)
                }
            }
        }

        // Clean abandoned temporary files (> 1 hour old)
        val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000L)
        vaultDir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".tmp") || file.name.contains("_stage_"))) {
                if (file.lastModified() < oneHourAgo) {
                    if (file.delete()) abandonedTempCleaned++
                }
            }
        }

        decryptedCacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < oneHourAgo) {
                if (file.delete()) abandonedTempCleaned++
            }
        }

        StorageReconciliationReport(
            totalRecords = records.size - pendingDeleteResolved,
            availableCount = availableCount,
            missingFilesCount = missingCount,
            integrityFailedCount = integrityFailedCount,
            orphanedVaultFilesCount = orphanedVaultFiles.size,
            orphanedVaultFileNames = orphanedVaultFiles,
            abandonedTempCleanedCount = abandonedTempCleaned
        )
    }

    suspend fun getDocumentById(id: String): LockerDocumentEntity? = withContext(Dispatchers.IO) {
        dao.getDocumentByIdSync(id)
    }

    // --- Phase 4: Folder and Organization Operations ---

    val foldersWithCounts: Flow<List<FolderWithCount>> = folderDao.getAllFoldersWithCounts()
    val allFolders: Flow<List<FolderEntity>> = folderDao.getAllFolders()

    fun getDocumentsInFolder(folderId: String): Flow<List<LockerDocumentEntity>> {
        return dao.getDocumentsByFolder(folderId)
    }

    fun getImportantDocuments(): Flow<List<LockerDocumentEntity>> {
        return dao.getImportantDocuments()
    }

    fun getCategoryCounts(): Flow<Map<String, Int>> {
        return dao.getCategoryCounts().map { list ->
            list.associate { it.category to it.count }
        }
    }

    fun getTotalDocumentCount(): Flow<Int> = dao.getTotalCount()

    fun getImportantDocumentCount(): Flow<Int> = dao.getImportantCount()

    fun getFolderCount(): Flow<Int> = folderDao.getFolderCount()

    suspend fun createFolder(name: String, colorHex: String, iconName: String): String = withContext(Dispatchers.IO) {
        val folderId = "folder_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val folder = FolderEntity(
            id = folderId,
            name = name.trim(),
            colorHex = colorHex,
            iconName = iconName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        folderDao.insertFolder(folder)
        folderId
    }

    suspend fun updateFolder(folder: FolderEntity) = withContext(Dispatchers.IO) {
        folderDao.updateFolder(folder.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * Deletes a custom folder. Safe operation: unassigns all associated documents first
     * (setting folderId = null) so no documents are deleted or orphaned.
     */
    suspend fun deleteFolder(folderId: String) = withContext(Dispatchers.IO) {
        dao.unassignAllFromFolder(folderId)
        folderDao.deleteFolderById(folderId)
    }

    suspend fun assignDocumentToFolder(documentId: String, folderId: String?) = withContext(Dispatchers.IO) {
        dao.assignToFolder(documentId, folderId)
    }

    suspend fun toggleImportant(documentId: String, isImportant: Boolean) = withContext(Dispatchers.IO) {
        dao.updateImportantStatus(documentId, isImportant)
    }
}
