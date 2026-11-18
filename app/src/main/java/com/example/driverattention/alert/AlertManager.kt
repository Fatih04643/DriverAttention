package com.example.driverattention.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.driverattention.analysis.AlertEvent
import com.example.driverattention.analysis.AlertLevel
import com.example.driverattention.analysis.AlertType
import com.example.driverattention.analysis.FatigueLevel
import java.util.Locale

/** Ekranda gösterilecek kısa uyarı metni */
fun AlertType.displayMessage(): String = when (this) {
    AlertType.MICROSLEEP -> "UYANIN! Gözleriniz kapalı"
    AlertType.DISTRACTION -> "Dikkat! Yola odaklanın"
    AlertType.FATIGUE_PERCLOS -> "Yorgunluk belirtisi — mola verin"
    AlertType.YAWN -> "Esneme algılandı"
    AlertType.FATIGUE_YAWNING -> "Sık esniyorsunuz — mola verin"
    AlertType.NO_FACE -> "Yüz algılanamıyor"
}

/** Geçmiş ekranındaki sayaçlar için kısa ad */
fun AlertType.shortLabel(): String = when (this) {
    AlertType.MICROSLEEP -> "Mikro uyku"
    AlertType.DISTRACTION -> "Dikkat dağınıklığı"
    AlertType.FATIGUE_PERCLOS -> "Yorgunluk (PERCLOS)"
    AlertType.YAWN -> "Esneme"
    AlertType.FATIGUE_YAWNING -> "Sık esneme"
    AlertType.NO_FACE -> "Yüz yok"
}

/** Sesli (TextToSpeech) uyarı metni; null = sadece bip */
private fun AlertType.spokenMessage(): String? = when (this) {
    AlertType.MICROSLEEP, AlertType.DISTRACTION -> "Dikkat! Lütfen yola odaklanın."
    AlertType.FATIGUE_PERCLOS, AlertType.FATIGUE_YAWNING -> "Yorgun görünüyorsunuz, mola verin."
    AlertType.YAWN, AlertType.NO_FACE -> null
}

/**
 * Sesli ve titreşimli uyarıları yönetir. (Görsel uyarılar — kırmızı yanıp sönme, turuncu
 * banner — Compose arayüzünde DriverStatus'a göre çizilir.)
 *
 *  - KRİTİK : yüksek sesli alarm tonu + güçlü titreşim + ardından Türkçe sesli uyarı
 *  - UYARI  : kısa bip + kısa titreşim (+ yorgunluk/dikkat için sesli uyarı)
 *  - BİLGİ  : sadece ekranda gösterilir, ses yok
 *
 * Alarm sesi STREAM_ALARM kanalından çalınır: medya sesi kısık olsa bile duyulur.
 */
class AlertManager(context: Context) {

    // Faz 5'te Ayarlar ekranına bağlanacak
    @Volatile var soundEnabled = true
    @Volatile var vibrationEnabled = true
    @Volatile var voiceEnabled = true

    private val mainHandler = Handler(Looper.getMainLooper())

    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
    } catch (e: RuntimeException) {
        Log.w(TAG, "ToneGenerator oluşturulamadı", e)
        null
    }

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    @Volatile private var ttsReady = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale.forLanguageTag("tr-TR"))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) Log.w(TAG, "Türkçe TTS dili yüklü değil, sesli uyarı kapalı")
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } else {
                Log.w(TAG, "TextToSpeech başlatılamadı: $status")
            }
        }
    }

    /**
     * Karar motorunun bu karede tetiklediği uyarıları çalar. Aynı anda birden fazla uyarı
     * gelirse sesler üst üste binmesin diye sadece en önemlisi çalınır.
     */
    fun onAlerts(events: List<AlertEvent>) {
        val event = events.maxByOrNull { it.type.level } ?: return
        when (event.type.level) {
            AlertLevel.CRITICAL -> playCritical(event.type)
            AlertLevel.WARNING -> playWarning(event.type)
            AlertLevel.INFO, AlertLevel.NONE -> Unit // sadece görsel
        }
    }

    private fun playCritical(type: AlertType) {
        if (soundEnabled) toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1_500)
        // Güçlü titreşim: 3 uzun darbe
        vibrate(longArrayOf(0, 500, 150, 500, 150, 500), intArrayOf(0, 255, 0, 255, 0, 255))
        // Sesli uyarı alarm tonu bittikten sonra (üst üste binmesin)
        type.spokenMessage()?.let { msg -> mainHandler.postDelayed({ speak(msg) }, 1_600) }
    }

    private fun playWarning(type: AlertType) {
        if (soundEnabled) toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        vibrate(longArrayOf(0, 250), intArrayOf(0, 200))
        type.spokenMessage()?.let { msg -> mainHandler.postDelayed({ speak(msg) }, 400) }
    }

    /**
     * Yorgunluk skoru uyarısı (Faz 7). Ne zaman çağrılacağına FatigueScoreCalculator karar
     * verir (10 dk'da en fazla 1 kez, seviye yükselirse hemen).
     *  - TIRED (60–79)      : sesli öneri
     *  - VERY_TIRED (80–100): titreşim + sesli uyarı
     * Sesli uyarı kapalıysa sürücü fark etsin diye kısa bir bip çalınır.
     */
    fun announceFatigue(level: FatigueLevel) {
        val message = when (level) {
            FatigueLevel.TIRED -> "Yorgunluk belirtileri artıyor, mola vermeyi düşünün."
            FatigueLevel.VERY_TIRED -> "Lütfen en kısa sürede mola verin."
            else -> return
        }
        if (level == FatigueLevel.VERY_TIRED) {
            vibrate(longArrayOf(0, 400, 200, 400), intArrayOf(0, 255, 0, 255))
        }
        if (voiceEnabled && ttsReady) {
            // QUEUE_ADD: o an çalan kritik bir uyarıyı kesmesin, ardından söylensin
            mainHandler.post { tts.speak(message, TextToSpeech.QUEUE_ADD, null, "fatigue") }
        } else if (soundEnabled) {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        }
    }

    private fun vibrate(timings: LongArray, amplitudes: IntArray) {
        val v = vibrator ?: return
        if (!vibrationEnabled || !v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(timings, -1)
        }
    }

    private fun speak(text: String) {
        if (voiceEnabled && ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert")
        }
    }

    /** Çalan tüm sesleri ve titreşimi durdurur (izleme durdurulunca) */
    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        toneGenerator?.stopTone()
        vibrator?.cancel()
        if (ttsReady) tts.stop()
    }

    fun release() {
        stop()
        toneGenerator?.release()
        tts.shutdown()
    }

    companion object {
        private const val TAG = "AlertManager"
    }
}
