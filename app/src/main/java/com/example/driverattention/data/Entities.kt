package com.example.driverattention.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Bir sürüş oturumu: "Başlat"tan "Durdur"a kadar geçen süre.
 * Zamanlar epoch milisaniye (System.currentTimeMillis).
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,   // oturum sürerken null
    val earThreshold: Float,     // bu oturumda kullanılan göz kapalı eşiği
    val calibrated: Boolean,     // eşik kalibrasyondan mı geldi?
    val csvPath: String? = null, // debug modda kaydedilen ölçüm dosyası
)

/**
 * Oturum sırasında tetiklenen tek bir uyarı.
 * Oturum silinince uyarıları da otomatik silinir (CASCADE).
 */
@Entity(
    tableName = "alerts",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionId")],
)
data class AlertRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val type: String,  // AlertType.name
    val level: String, // AlertLevel.name
)

/** Oturum + o oturumun tüm uyarıları (Room @Relation ile tek sorguda) */
data class SessionWithAlerts(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val alerts: List<AlertRecordEntity>,
)
