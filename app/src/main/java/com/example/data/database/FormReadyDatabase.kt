package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LockerDocumentEntity::class,
        FolderEntity::class,
        VaultNoteEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class FormReadyDatabase : RoomDatabase() {

    abstract fun lockerDocumentDao(): LockerDocumentDao
    abstract fun folderDao(): FolderDao
    abstract fun vaultNoteDao(): VaultNoteDao

    companion object {
        @Volatile
        private var INSTANCE: FormReadyDatabase? = null

        /**
         * Migration from version 1 to version 2:
         * - Adds isImportant and folderId columns to locker_documents
         * - Creates custom_folders table
         * - Creates vault_notes table
         * Strictly preserves all existing user document records and encryption references.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Alter locker_documents
                db.execSQL("ALTER TABLE locker_documents ADD COLUMN isImportant INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE locker_documents ADD COLUMN folderId TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_locker_documents_folderId ON locker_documents(folderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_locker_documents_isImportant ON locker_documents(isImportant)")

                // 2. Create custom_folders table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS custom_folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        colorHex TEXT NOT NULL DEFAULT '#1E3A8A',
                        iconName TEXT NOT NULL DEFAULT 'folder',
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())

                // 3. Create vault_notes table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS vault_notes (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        storedEncryptedFileName TEXT NOT NULL,
                        previewSnippet TEXT NOT NULL DEFAULT '',
                        colorTag TEXT NOT NULL DEFAULT '#F8FAFC',
                        isPinned INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_vault_notes_createdAt ON vault_notes(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_vault_notes_isPinned ON vault_notes(isPinned)")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2
        )

        fun getInstance(context: Context): FormReadyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FormReadyDatabase::class.java,
                    "formready_locker.db"
                )
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
