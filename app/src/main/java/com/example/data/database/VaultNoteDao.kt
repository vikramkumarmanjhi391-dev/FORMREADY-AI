package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultNoteDao {

    @Query("SELECT * FROM vault_notes ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllNotes(): Flow<List<VaultNoteEntity>>

    @Query("SELECT COUNT(*) FROM vault_notes")
    fun getNotesCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM vault_notes")
    suspend fun getNotesCount(): Int

    @Query("SELECT * FROM vault_notes WHERE id = :id LIMIT 1")
    fun getNoteById(id: String): Flow<VaultNoteEntity?>

    @Query("SELECT * FROM vault_notes WHERE id = :id LIMIT 1")
    suspend fun getNoteByIdSync(id: String): VaultNoteEntity?

    @Query("SELECT * FROM vault_notes WHERE title LIKE '%' || :query || '%' ORDER BY isPinned DESC, updatedAt DESC")
    fun searchNotes(query: String): Flow<List<VaultNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: VaultNoteEntity)

    @Update
    suspend fun updateNote(note: VaultNoteEntity)

    @Query("UPDATE vault_notes SET title = :title, previewSnippet = :preview, colorTag = :colorTag, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNoteMetadata(id: String, title: String, preview: String, colorTag: String, updatedAt: Long)

    @Query("UPDATE vault_notes SET isPinned = :isPinned WHERE id = :id")
    suspend fun updatePinned(id: String, isPinned: Boolean)

    @Query("DELETE FROM vault_notes WHERE id = :id")
    suspend fun deleteNoteById(id: String)
}
