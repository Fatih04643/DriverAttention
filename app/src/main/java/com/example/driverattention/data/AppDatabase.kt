package com.example.driverattention.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Uygulamanın yerel SQLite veritabanı (Room). Tüm veri cihazda kalır. */
@Database(
    entities = [SessionEntity::class, AlertRecordEntity::class, FatigueScoreEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        /**
         * Sürüm 1 → 2: yorgunluk skoru tablosu eklendi (Faz 7).
         * Migration sayesinde telefondaki eski sürüş kayıtları silinmeden korunur.
         * SQL, Room'un FatigueScoreEntity için beklediği şemayla birebir aynı olmalı.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fatigue_scores` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`score` REAL NOT NULL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fatigue_scores_sessionId` ON `fatigue_scores` (`sessionId`)")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        /** Tek örnek (singleton): veritabanını birden fazla kez açmak hem yavaş hem hatalıdır */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "driver_attention.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
