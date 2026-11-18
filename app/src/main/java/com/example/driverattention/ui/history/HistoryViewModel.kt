package com.example.driverattention.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.driverattention.analysis.AlertType
import com.example.driverattention.data.SessionRepository
import com.example.driverattention.data.SessionWithAlerts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = SessionRepository.get(app)

    /** Tüm oturumlar; veritabanı değişince otomatik güncellenir */
    val sessions: StateFlow<List<SessionWithAlerts>?> = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(sessionId: Long) {
        viewModelScope.launch { repository.deleteSession(sessionId) }
    }
}

// ---- Biçimlendirme yardımcıları ----

private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("tr-TR"))
private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("tr-TR"))

fun formatDate(epochMs: Long): String = dateFormat.format(Date(epochMs))
fun formatTime(epochMs: Long): String = timeFormat.format(Date(epochMs))

/** 754_000 ms → "12 dk 34 sn" */
fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "$h sa $m dk"
        m > 0 -> "$m dk $s sn"
        else -> "$s sn"
    }
}

/** Oturum süresi (devam ediyorsa şimdiye kadar) */
val SessionWithAlerts.durationMs: Long
    get() = (session.endTime ?: System.currentTimeMillis()) - session.startTime

/** Veritabanındaki metin → AlertType (bilinmeyen tür gelirse null) */
fun String.toAlertType(): AlertType? = runCatching { AlertType.valueOf(this) }.getOrNull()

/** Uyarı türüne göre sayılar */
fun SessionWithAlerts.countsByType(): Map<AlertType, Int> =
    alerts.mapNotNull { it.type.toAlertType() }.groupingBy { it }.eachCount()
