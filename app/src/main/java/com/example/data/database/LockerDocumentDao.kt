package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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

    @Query("""
        SELECT * FROM locker_documents 
        WHERE title LIKE '%' || :query || '%' 
           OR notes LIKE '%' || :query || '%' 
           OR originalFileName LIKE '%' || :query || '%'
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

    @Query("DELETE FROM locker_documents WHERE id = :id")
    suspend fun deleteDocumentById(id: String)

    @Query("SELECT COUNT(*) FROM locker_documents")
    suspend fun getDocumentCount(): Int
}
