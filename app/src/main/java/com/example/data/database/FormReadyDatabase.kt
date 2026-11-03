package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration

@Database(
    entities = [LockerDocumentEntity::class],
    version = 1,
    exportSchema = true
)
abstract class FormReadyDatabase : RoomDatabase() {

    abstract fun lockerDocumentDao(): LockerDocumentDao

    companion object {
        @Volatile
        private var INSTANCE: FormReadyDatabase? = null

        /**
         * Explicit migrations list for preserving metadata across schema updates.
         * Destructive migration is strictly forbidden to protect user documents.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            // Schema migrations will be registered here as versions evolve
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
