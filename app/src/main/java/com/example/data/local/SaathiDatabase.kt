package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MemoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SaathiDatabase : RoomDatabase() {
    abstract fun saathiDao(): SaathiDao

    companion object {
        @Volatile
        private var INSTANCE: SaathiDatabase? = null

        fun getInstance(context: Context): SaathiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SaathiDatabase::class.java,
                    "saathi_local.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
