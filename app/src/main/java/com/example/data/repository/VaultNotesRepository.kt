package com.example.data.repository

import android.content.Context
import com.example.data.database.FormReadyDatabase
import com.example.data.database.VaultNoteDao
import com.example.data.database.VaultNoteEntity
import com.example.storage.DocumentEncryptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class VaultNotesRepository(
    private val context: Context,
    private val dao: VaultNoteDao = FormReadyDatabase.getInstance(context).vaultNoteDao(),
    private val encryptionManager: DocumentEncryptionManager = DocumentEncryptionManager(context)
) {

    private val notesDir: File
        get() = File(context.noBackupFilesDir, "document_locker/notes").apply {
            if (!exists()) mkdirs()
        }

    val allNotes: Flow<List<VaultNoteEntity>> = dao.getAllNotes()
    val notesCount: Flow<Int> = dao.getNotesCountFlow()

    suspend fun createNote(
        title: String,
        content: String,
        colorTag: String = "#F8FAFC",
        isPinned: Boolean = false
    ): Result<VaultNoteEntity> = withContext(Dispatchers.IO) {
        try {
            val noteId = "note_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val encryptedFileName = "$noteId.vault"
            val targetFile = File(notesDir, encryptedFileName)

            // Write plain content to temporary file, then encrypt atomically
            val tempSource = File(context.cacheDir, "note_plain_${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(tempSource).use { it.write(content.toByteArray(Charsets.UTF_8)) }
                encryptionManager.encryptFileAtomically(tempSource, targetFile)
            } finally {
                if (tempSource.exists()) tempSource.delete()
            }

            val snippet = content.trim().replace(Regex("\\s+"), " ").take(100)
            val entity = VaultNoteEntity(
                id = noteId,
                title = title.ifBlank { "Untitled Note" },
                storedEncryptedFileName = encryptedFileName,
                previewSnippet = snippet,
                colorTag = colorTag,
                isPinned = isPinned,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            dao.insertNote(entity)
            Result.success(entity)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDecryptedNoteContent(noteId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val entity = dao.getNoteByIdSync(noteId)
                ?: return@withContext Result.failure(NoSuchElementException("Note not found"))

            val encryptedFile = File(notesDir, entity.storedEncryptedFileName)
            if (!encryptedFile.exists()) {
                return@withContext Result.failure(IllegalStateException("Encrypted note file missing from vault"))
            }

            val tempPlainFile = File(context.cacheDir, "note_dec_${UUID.randomUUID()}.tmp")
            try {
                encryptionManager.decryptFile(encryptedFile, tempPlainFile)
                val text = tempPlainFile.readText(Charsets.UTF_8)
                Result.success(text)
            } finally {
                if (tempPlainFile.exists()) tempPlainFile.delete()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateNote(
        noteId: String,
        title: String,
        content: String,
        colorTag: String,
        isPinned: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entity = dao.getNoteByIdSync(noteId)
                ?: return@withContext Result.failure(NoSuchElementException("Note not found"))

            val targetFile = File(notesDir, entity.storedEncryptedFileName)
            val tempSource = File(context.cacheDir, "note_update_${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(tempSource).use { it.write(content.toByteArray(Charsets.UTF_8)) }
                encryptionManager.encryptFileAtomically(tempSource, targetFile)
            } finally {
                if (tempSource.exists()) tempSource.delete()
            }

            val snippet = content.trim().replace(Regex("\\s+"), " ").take(100)
            val updated = entity.copy(
                title = title.ifBlank { "Untitled Note" },
                previewSnippet = snippet,
                colorTag = colorTag,
                isPinned = isPinned,
                updatedAt = System.currentTimeMillis()
            )
            dao.updateNote(updated)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun togglePinned(noteId: String, isPinned: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            dao.updatePinned(noteId, isPinned)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteNote(noteId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entity = dao.getNoteByIdSync(noteId)
            if (entity != null) {
                val file = File(notesDir, entity.storedEncryptedFileName)
                if (file.exists()) {
                    file.delete()
                }
            }
            dao.deleteNoteById(noteId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun searchNotes(query: String): Flow<List<VaultNoteEntity>> {
        return dao.searchNotes(query.trim())
    }
}
