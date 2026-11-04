package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class CategoryCountResult(
    val category: String,
    val count: Int
)

@Dao
interface LockerDocumentDao {

    @Query("SELECT * FROM locker_documents ORDER BY createdAt DESC")
    fun getAllDocuments(): Flow<List<LockerDocumentEntity>>

    @Query("SELECT * FROM locker_documents ORDER BY createdAt DESC")
    suspend fun getAllDocumentsSync(): List<LockerDocumentEntity>

    @Query("SELECT * FROM locker_documents WHERE id = :id LIMIT 1")
    fun getDocumentById(id: String): Flow<LockerDocumentEntity?>

    @Query("SELECT * FROM locker_documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentByIdSync(id: String): LockerDocumentEntity?

    @Query("SELECT * FROM locker_documents WHERE sha256Hash = :hash LIMIT 1")
    suspend fun getDocumentByHash(hash: String): LockerDocumentEntity?

    @Query("SELECT * FROM locker_documents WHERE category = :category ORDER BY createdAt DESC")
    fun getDocumentsByCategory(category: String): Flow<List<LockerDocumentEntity>>

    @Query("SELECT * FROM locker_documents WHERE isImportant = 1 AND integrityStatus != 'PENDING_DELETE' ORDER BY createdAt DESC")
    fun getImportantDocuments(): Flow<List<LockerDocumentEntity>>

    @Query("SELECT * FROM locker_documents WHERE folderId = :folderId AND integrityStatus != 'PENDING_DELETE' ORDER BY createdAt DESC")
    fun getDocumentsByFolder(folderId: String): Flow<List<LockerDocumentEntity>>

    @Query("SELECT category, COUNT(*) as count FROM locker_documents WHERE integrityStatus != 'PENDING_DELETE' GROUP BY category")
    fun getCategoryCounts(): Flow<List<CategoryCountResult>>

    @Query("SELECT COUNT(*) FROM locker_documents WHERE integrityStatus != 'PENDING_DELETE'")
    fun getDocumentCountFlow(): Flow<Int>

    fun getTotalCount(): Flow<Int> = getDocumentCountFlow()

    @Query("SELECT COUNT(*) FROM locker_documents WHERE isImportant = 1 AND integrityStatus != 'PENDING_DELETE'")
    fun getImportantCountFlow(): Flow<Int>

    fun getImportantCount(): Flow<Int> = getImportantCountFlow()

    @Query("""
        SELECT * FROM locker_documents 
        WHERE (title LIKE '%' || :query || '%' 
           OR notes LIKE '%' || :query || '%' 
           OR originalFileName LIKE '%' || :query || '%'
           OR category LIKE '%' || :query || '%')
          AND integrityStatus != 'PENDING_DELETE'
        ORDER BY createdAt DESC
    """)
    fun searchDocuments(query: String): Flow<List<LockerDocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: LockerDocumentEntity)

    @Update
    suspend fun updateDocument(document: LockerDocumentEntity)

    @Query("UPDATE locker_documents SET title = :title, documentType = :documentType, category = :category, notes = :notes, lastUpdatedAt = :timestamp WHERE id = :id")
    suspend fun updateMetadata(id: String, title: String, documentType: String, category: String, notes: String, timestamp: Long)

    @Query("UPDATE locker_documents SET integrityStatus = :status WHERE id = :id")
    suspend fun updateIntegrityStatus(id: String, status: String)

    @Query("UPDATE locker_documents SET isImportant = :isImportant WHERE id = :id")
    suspend fun updateImportantStatus(id: String, isImportant: Boolean)

    @Query("UPDATE locker_documents SET folderId = :folderId WHERE id = :id")
    suspend fun assignToFolder(id: String, folderId: String?)

    @Query("UPDATE locker_documents SET folderId = NULL WHERE folderId = :folderId")
    suspend fun unassignAllFromFolder(folderId: String)

    @Query("SELECT COUNT(*) FROM locker_documents WHERE folderId = :folderId AND integrityStatus != 'PENDING_DELETE'")
    fun getDocumentCountByFolder(folderId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM locker_documents WHERE folderId = :folderId AND integrityStatus != 'PENDING_DELETE'")
    suspend fun getDocumentCountByFolderSync(folderId: String): Int

    @Query("DELETE FROM locker_documents WHERE id = :id")
    suspend fun deleteDocumentById(id: String)

    @Query("SELECT COUNT(*) FROM locker_documents WHERE integrityStatus != 'PENDING_DELETE'")
    suspend fun getDocumentCount(): Int
}
