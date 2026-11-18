package com.example.driverattention.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Uygulamanın yerel SQLite veritabanı (Room). Tüm veri cihazda kalır. */
@Database(
    entities = [SessionEntity::class, AlertRecordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Tek örnek (singleton): veritabanını birden fazla kez açmak hem yavaş hem hatalıdır */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "driver_attention.db",
            ).build().also { instance = it }
        }
    }
}
