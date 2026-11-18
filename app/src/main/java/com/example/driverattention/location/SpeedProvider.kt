package com.example.driverattention.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.driverattention.analysis.SpeedTracker
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Hız bilgisinin kaynağı (CSV'deki speed_source sütunu) */
enum class SpeedSource(val csvName: String) {
    GPS("gps"),
    SIMULATED("sim"),
    NONE("none"), // izin yok / GPS kapalı / sinyal yok
}

data class SpeedReading(val kmh: Float?, val source: SpeedSource) {
    companion object {
        val NONE = SpeedReading(null, SpeedSource.NONE)
    }
}

/**
 * GPS'ten araç hızını okur (Google Play Services "Fused Location Provider").
 *
 * - Saniyede bir konum güncellemesi istenir (yüksek doğruluk = GPS).
 * - Location.speed m/s cinsindendir → × 3.6 ile km/s'e çevrilir.
 * - Ölçümler SpeedTracker'da son 3 ölçümün medyanıyla yumuşatılır; 5 sn güncelleme gelmezse
 *   (tünel, kapalı otopark) hız "bilinmiyor" olur ve uygulama Faz 4 davranışına döner.
 *
 * Konum sadece hız için kullanılır; hiçbir yere kaydedilmez veya gönderilmez.
 */
class SpeedProvider(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val tracker = SpeedTracker(windowSize = 3, staleAfterMs = 5_000)
    private var running = false

    /** Son konum güncellemesinde GPS hız bilgisi vermediyse (ör. sadece ağ konumu) true */
    @Volatile var lastFixHadNoSpeed = false
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    private fun onLocation(location: Location) {
        if (!location.hasSpeed()) {
            lastFixHadNoSpeed = true
            return
        }
        lastFixHadNoSpeed = false
        // Zaman damgası kamera kareleriyle aynı saatten (uptime) alınır: bayatlık kontrolü için
        synchronized(tracker) { tracker.add(SystemClock.uptimeMillis(), location.speed * 3.6f) }
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /** Güncellemeleri başlatır; izin yoksa hiçbir şey yapmaz (tekrar çağrılabilir) */
    @SuppressLint("MissingPermission") // izin hasPermission() ile kontrol ediliyor
    fun start() {
        if (running || !hasPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            running = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Konum izni yok", e)
        }
    }

    fun stop() {
        if (!running) return
        client.removeLocationUpdates(callback)
        running = false
        synchronized(tracker) { tracker.reset() }
    }

    /** Güncel hız (kamera karesi zamanıyla karşılaştırılır) */
    fun current(nowMs: Long): SpeedReading {
        val kmh = synchronized(tracker) { tracker.current(nowMs) }
        return if (kmh != null) SpeedReading(kmh, SpeedSource.GPS) else SpeedReading.NONE
    }

    companion object {
        private const val TAG = "SpeedProvider"
    }
}
