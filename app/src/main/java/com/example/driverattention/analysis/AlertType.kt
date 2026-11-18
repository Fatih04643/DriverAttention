package com.example.driverattention.analysis

/** Uyarı önem seviyeleri (küçükten büyüğe sıralı — karşılaştırma için sıra önemli) */
enum class AlertLevel { NONE, INFO, WARNING, CRITICAL }

/** Karar motorunun tespit edebildiği durumlar ve seviyeleri (CLAUDE.md tablosu) */
enum class AlertType(val level: AlertLevel) {
    /** Gözler ≥ 1.5 sn kesintisiz kapalı */
    MICROSLEEP(AlertLevel.CRITICAL),

    /** Baş ≥ 2 sn yoldan başka yöne dönük */
    DISTRACTION(AlertLevel.WARNING),

    /** Son 60 sn'de gözlerin kapalı olduğu karelerin oranı ≥ %15 */
    FATIGUE_PERCLOS(AlertLevel.WARNING),

    /** Ağız ≥ 1 sn boyunca açık (MAR > eşik) */
    YAWN(AlertLevel.WARNING),

    /** 5 dk içinde ≥ 3 esneme */
    FATIGUE_YAWNING(AlertLevel.WARNING),

    /** ≥ 3 sn yüz tespit edilemedi */
    NO_FACE(AlertLevel.INFO),
}

/** Tetiklenen tek bir uyarı (ses/titreşim bu olaylara göre çalınır, Faz 5'te kaydedilecek) */
data class AlertEvent(
    val type: AlertType,
    val timestampMs: Long,
    // false = sadece görsel (ör. araç dururken mikro uyku, Faz 8). Kayda yine geçer.
    val audible: Boolean = true,
)
