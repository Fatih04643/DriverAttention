package com.example.driverattention.data

import android.content.Context
import com.example.driverattention.analysis.DriverStatus
import com.example.driverattention.analysis.FaceMetrics
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug modda her karenin ölçümlerini CSV dosyasına yazar (rapordaki grafikler için).
 *
 * Dosya konumu: Android/data/com.example.driverattention/files/csv/
 * (USB ile bilgisayardan erişilebilir veya Geçmiş ekranından paylaşılabilir)
 *
 * Sayılar Locale.US ile yazılır: Türkçe yerelde ondalık ayırıcı virgül olduğu için
 * "0,25" gibi değerler CSV sütunlarını bozardı.
 */
class MetricsCsvLogger private constructor(val file: File) {

    private val writer: BufferedWriter = file.bufferedWriter()
    private var startMs: Long = -1

    init {
        writer.write(HEADER)
        writer.newLine()
    }

    /** Bir kare ekler. [timestampMs]: kare zamanı (uptime ms) */
    fun log(
        timestampMs: Long,
        metrics: FaceMetrics?,
        status: DriverStatus,
        fps: Float,
        processingMs: Long,
        brightness: Float,
        enhanced: Boolean,
        fatigueScore: Float,
        speedKmh: Float?,
        speedSource: String,
    ) {
        if (startMs < 0) startMs = timestampMs
        val p = metrics?.headPose
        val row = listOf(
            (timestampMs - startMs).toString(),
            if (metrics != null) "1" else "0",
            metrics?.ear.fmt(),
            metrics?.leftEar.fmt(),
            metrics?.rightEar.fmt(),
            metrics?.mar.fmt(),
            p?.yaw.fmt(2),
            p?.pitch.fmt(2),
            p?.roll.fmt(2),
            status.perclos.fmt(),
            status.level.name,
            status.activeConditions.joinToString("|") { it.name },
            fps.fmt(1),
            processingMs.toString(),
            brightness.fmt(1),
            if (enhanced) "1" else "0",
            fatigueScore.fmt(1),
            speedKmh.fmt(1),
            speedSource,
        )
        writer.write(row.joinToString(","))
        writer.newLine()
    }

    fun close() {
        try {
            writer.flush()
            writer.close()
        } catch (_: Exception) {
        }
    }

    private fun Float?.fmt(decimals: Int = 4): String =
        this?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: ""

    companion object {
        private const val HEADER =
            "t_ms,face,ear,ear_left,ear_right,mar,yaw,pitch,roll,perclos,level,active,fps,processing_ms,brightness,low_light_enhanced,fatigue_score,speed_kmh,speed_source"

        fun create(context: Context): MetricsCsvLogger? = try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "csv").apply { mkdirs() }
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            MetricsCsvLogger(File(dir, "session_$name.csv"))
        } catch (e: Exception) {
            null
        }
    }
}
