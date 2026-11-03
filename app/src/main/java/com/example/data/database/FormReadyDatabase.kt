package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [LockerDocumentEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FormReadyDatabase : RoomDatabase() {

    abstract fun lockerDocumentDao(): LockerDocumentDao

    companion object {
        @Volatile
        private var INSTANCE: FormReadyDatabase? = null

        fun getInstance(context: Context): FormReadyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FormReadyDatabase::class.java,
                    "formready_locker.db"
                )
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
