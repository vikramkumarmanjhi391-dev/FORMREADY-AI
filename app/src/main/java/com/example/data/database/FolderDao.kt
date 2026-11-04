package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class FolderWithCount(
    val id: String,
    val name: String,
    val colorHex: String,
    val iconName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val documentCount: Int
) {
    val folder: FolderEntity
        get() = FolderEntity(
            id = id,
            name = name,
            colorHex = colorHex,
            iconName = iconName,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
}

@Dao
interface FolderDao {

    @Query("SELECT * FROM custom_folders ORDER BY name ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM custom_folders ORDER BY name ASC")
    suspend fun getAllFoldersSync(): List<FolderEntity>

    @Query("""
        SELECT f.id, f.name, f.colorHex, f.iconName, f.createdAt, f.updatedAt,
               COUNT(d.id) as documentCount
        FROM custom_folders f
        LEFT JOIN locker_documents d ON d.folderId = f.id AND d.integrityStatus != 'PENDING_DELETE'
        GROUP BY f.id
        ORDER BY f.name ASC
    """)
    fun getFoldersWithCounts(): Flow<List<FolderWithCount>>

    fun getAllFoldersWithCounts(): Flow<List<FolderWithCount>> = getFoldersWithCounts()

    @Query("SELECT COUNT(*) FROM custom_folders")
    fun getFolderCount(): Flow<Int>

    @Query("SELECT * FROM custom_folders WHERE id = :id LIMIT 1")
    fun getFolderById(id: String): Flow<FolderEntity?>

    @Query("SELECT * FROM custom_folders WHERE id = :id LIMIT 1")
    suspend fun getFolderByIdSync(id: String): FolderEntity?

    @Query("SELECT * FROM custom_folders WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchFolders(query: String): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Update
    suspend fun updateFolder(folder: FolderEntity)

    @Query("UPDATE custom_folders SET name = :name, colorHex = :colorHex, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateFolderDetails(id: String, name: String, colorHex: String, updatedAt: Long)

    @Query("DELETE FROM custom_folders WHERE id = :id")
    suspend fun deleteFolderById(id: String)
}
