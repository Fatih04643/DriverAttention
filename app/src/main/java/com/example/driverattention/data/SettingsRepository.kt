package com.example.driverattention.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.driverattention.analysis.CalibrationResult
import com.example.driverattention.analysis.DetectionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Kullanıcı ayarları. Varsayılanlar CLAUDE.md'deki değerlerdir.
 * Süreler kullanıcıya saniye, açılar derece olarak gösterildiği için o birimlerde saklanır.
 */
data class AppSettings(
    // --- Eşikler ---
    val earCalibrationFactor: Float = DetectionConfig.EAR_CALIBRATION_FACTOR, // eşik = kalibre EAR × bu
    val defaultEarThreshold: Float = DetectionConfig.DEFAULT_EAR_THRESHOLD,   // kalibrasyon yoksa
    val microSleepSec: Float = 1.5f,
    val perclosPercent: Float = 15f,
    val marThreshold: Float = 0.6f,
    val yawLimitDeg: Float = 30f,
    val pitchDownLimitDeg: Float = 20f, // "aşağı" yönde kaç derece (pozitif tutulur)
    val distractionSec: Float = 2f,
    // --- Uyarılar ---
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    // --- Görünüm / geliştirici ---
    val lowLightEnhancement: Boolean = true, // karanlıkta histogram eşitleme
    val showOverlay: Boolean = true,
    val debugMode: Boolean = true, // FPS/ölçüm paneli + CSV kaydı
) {
    /** Kalibrasyon sonucunu da hesaba katarak karar motoru yapılandırmasını üretir */
    fun toDetectionConfig(calibration: CalibrationResult?) = DetectionConfig(
        earThreshold = calibration?.let { it.openEar * earCalibrationFactor } ?: defaultEarThreshold,
        microSleepMs = (microSleepSec * 1000).toLong(),
        perclosThreshold = perclosPercent / 100f,
        marThreshold = marThreshold,
        yawLimitDeg = yawLimitDeg,
        pitchDownLimitDeg = -pitchDownLimitDeg,
        distractionMs = (distractionSec * 1000).toLong(),
        baselineYawDeg = calibration?.baselineYawDeg ?: 0f,
        baselinePitchDeg = calibration?.baselinePitchDeg ?: 0f,
    )
}

// DataStore: ayarları cihazda küçük bir dosyada saklar (SharedPreferences'ın modern, asenkron hali)
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    private object Keys {
        val EAR_FACTOR = floatPreferencesKey("ear_factor")
        val DEFAULT_EAR = floatPreferencesKey("default_ear")
        val MICROSLEEP_SEC = floatPreferencesKey("microsleep_sec")
        val PERCLOS_PERCENT = floatPreferencesKey("perclos_percent")
        val MAR = floatPreferencesKey("mar")
        val YAW_LIMIT = floatPreferencesKey("yaw_limit")
        val PITCH_DOWN = floatPreferencesKey("pitch_down")
        val DISTRACTION_SEC = floatPreferencesKey("distraction_sec")
        val SOUND = booleanPreferencesKey("sound")
        val VIBRATION = booleanPreferencesKey("vibration")
        val VOICE = booleanPreferencesKey("voice")
        val LOW_LIGHT = booleanPreferencesKey("low_light")
        val OVERLAY = booleanPreferencesKey("overlay")
        val DEBUG = booleanPreferencesKey("debug")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            earCalibrationFactor = p[Keys.EAR_FACTOR] ?: d.earCalibrationFactor,
            defaultEarThreshold = p[Keys.DEFAULT_EAR] ?: d.defaultEarThreshold,
            microSleepSec = p[Keys.MICROSLEEP_SEC] ?: d.microSleepSec,
            perclosPercent = p[Keys.PERCLOS_PERCENT] ?: d.perclosPercent,
            marThreshold = p[Keys.MAR] ?: d.marThreshold,
            yawLimitDeg = p[Keys.YAW_LIMIT] ?: d.yawLimitDeg,
            pitchDownLimitDeg = p[Keys.PITCH_DOWN] ?: d.pitchDownLimitDeg,
            distractionSec = p[Keys.DISTRACTION_SEC] ?: d.distractionSec,
            soundEnabled = p[Keys.SOUND] ?: d.soundEnabled,
            vibrationEnabled = p[Keys.VIBRATION] ?: d.vibrationEnabled,
            voiceEnabled = p[Keys.VOICE] ?: d.voiceEnabled,
            lowLightEnhancement = p[Keys.LOW_LIGHT] ?: d.lowLightEnhancement,
            showOverlay = p[Keys.OVERLAY] ?: d.showOverlay,
            debugMode = p[Keys.DEBUG] ?: d.debugMode,
        )
    }

    suspend fun save(s: AppSettings) {
        dataStore.edit { p ->
            p[Keys.EAR_FACTOR] = s.earCalibrationFactor
            p[Keys.DEFAULT_EAR] = s.defaultEarThreshold
            p[Keys.MICROSLEEP_SEC] = s.microSleepSec
            p[Keys.PERCLOS_PERCENT] = s.perclosPercent
            p[Keys.MAR] = s.marThreshold
            p[Keys.YAW_LIMIT] = s.yawLimitDeg
            p[Keys.PITCH_DOWN] = s.pitchDownLimitDeg
            p[Keys.DISTRACTION_SEC] = s.distractionSec
            p[Keys.SOUND] = s.soundEnabled
            p[Keys.VIBRATION] = s.vibrationEnabled
            p[Keys.VOICE] = s.voiceEnabled
            p[Keys.LOW_LIGHT] = s.lowLightEnhancement
            p[Keys.OVERLAY] = s.showOverlay
            p[Keys.DEBUG] = s.debugMode
        }
    }

    /** Mevcut ayarları okuyup dönüştürerek kaydeder (ör. tek bir anahtarı değiştirmek için) */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        save(transform(settings.first()))
    }
}
