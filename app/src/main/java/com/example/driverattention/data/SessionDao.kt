package com.example.driverattention.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Veritabanı erişim arayüzü — SQL sorgularını Room derleme zamanında kontrol eder */
@Dao
interface SessionDao {

    @Insert
    fun insertSession(session: SessionEntity): Long

    @Query("UPDATE sessions SET endTime = :endTime, csvPath = :csvPath WHERE id = :sessionId")
    fun finishSession(sessionId: Long, endTime: Long, csvPath: String?)

    @Insert
    fun insertAlert(alert: AlertRecordEntity)

    /** Tüm oturumlar, en yenisi üstte. Flow: veri değişince ekran otomatik güncellenir */
    @Transaction
    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    fun observeSessions(): Flow<List<SessionWithAlerts>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun observeSession(sessionId: Long): Flow<SessionWithAlerts?>

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    /** Uygulama beklenmedik kapanırsa bitiş zamanı yazılamamış oturumları kapat */
    @Query(
        "UPDATE sessions SET endTime = COALESCE((SELECT MAX(timestamp) FROM alerts WHERE sessionId = sessions.id), startTime) " +
            "WHERE endTime IS NULL"
    )
    fun closeDanglingSessions()
}
