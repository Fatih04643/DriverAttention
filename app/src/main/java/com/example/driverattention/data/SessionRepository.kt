package com.example.driverattention.data

import android.content.Context
import com.example.driverattention.analysis.AlertEvent
import java.util.concurrent.Executors

/**
 * Sürüş oturumu kayıtlarının tek giriş noktası (Repository deseni).
 *
 * Yazma işlemleri tek bir arka plan thread'inde SIRAYLA yapılır. Bu önemli: oturum kaydı
 * oluşturulmadan (ID alınmadan) o oturumun uyarıları yazılamaz. Tek thread'li kuyruk,
 * "önce oturum, sonra uyarılar" sırasını garanti eder ve ana thread'i hiç bekletmez.
 */
class SessionRepository private constructor(context: Context) {

    companion object {
        @Volatile private var instance: SessionRepository? = null

        /** Tek örnek: açık oturumun ID'si tüm ekranlar için aynı yerde tutulmalı */
        fun get(context: Context): SessionRepository = instance ?: synchronized(this) {
            instance ?: SessionRepository(context.applicationContext).also { instance = it }
        }
    }


    private val dao = AppDatabase.get(context).sessionDao()
    private val writeExecutor = Executors.newSingleThreadExecutor()

    // Sadece writeExecutor thread'inde okunur/yazılır
    private var currentSessionId: Long? = null

    init {
        writeExecutor.execute { dao.closeDanglingSessions() }
    }

    fun observeSessions() = dao.observeSessions()
    fun observeSession(id: Long) = dao.observeSession(id)
    suspend fun deleteSession(id: Long) = dao.deleteSession(id)

    /** Yeni oturum başlatır (önceki açık oturum varsa önce onu kapatır) */
    fun startSession(earThreshold: Float, calibrated: Boolean) {
        val now = System.currentTimeMillis()
        writeExecutor.execute {
            currentSessionId?.let { dao.finishSession(it, now, null) }
            currentSessionId = dao.insertSession(
                SessionEntity(startTime = now, earThreshold = earThreshold, calibrated = calibrated)
            )
        }
    }

    /** Yorgunluk skorunu açık oturuma ekler (ViewModel 10 sn'de bir çağırır) */
    fun recordScore(score: Float) {
        val now = System.currentTimeMillis()
        writeExecutor.execute {
            val sessionId = currentSessionId ?: return@execute
            dao.insertScore(FatigueScoreEntity(sessionId = sessionId, timestamp = now, score = score))
        }
    }

    fun recordAlerts(events: List<AlertEvent>) {
        val now = System.currentTimeMillis()
        writeExecutor.execute {
            val sessionId = currentSessionId ?: return@execute
            events.forEach {
                dao.insertAlert(
                    AlertRecordEntity(
                        sessionId = sessionId,
                        timestamp = now,
                        type = it.type.name,
                        level = it.type.level.name,
                    )
                )
            }
        }
    }

    /** Açık oturumu kapatır. [csvPath]: debug modda kaydedilen ölçüm dosyası */
    fun finishSession(csvPath: String?) {
        val now = System.currentTimeMillis()
        writeExecutor.execute {
            currentSessionId?.let { dao.finishSession(it, now, csvPath) }
            currentSessionId = null
        }
    }
}
