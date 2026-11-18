package com.example.driverattention.analysis

/**
 * Yorgunluk skorunun tüm ağırlıkları ve sınırları (CLAUDE.md Faz 7 tablosu).
 *
 * Bilerek Ayarlar ekranına konmadı: skor, raporda tablo olarak verilen sabit bir modeldir.
 * Kullanıcı ağırlıkları değiştirebilseydi farklı oturumların skorları karşılaştırılamazdı.
 *
 *   skor = 100 × Σ (ağırlık × bileşen),   her bileşen 0..1 arasına normalize edilir
 */
object FatigueScoreConstants {

    // ---- 1) PERCLOS (60 sn pencere): min(PERCLOS / 0.30, 1) ----
    const val WEIGHT_PERCLOS = 0.35f
    const val PERCLOS_FULL = 0.30f

    // ---- 2) Uzun göz kapanmaları (> 0.5 sn), son 5 dk: min(sayı / 5, 1) ----
    const val WEIGHT_LONG_CLOSURES = 0.20f
    const val LONG_CLOSURE_MIN_MS = 500L
    const val LONG_CLOSURE_WINDOW_MS = 5 * 60_000L
    const val LONG_CLOSURES_FULL = 5f

    // ---- 3) Esneme sayısı, son 10 dk: min(sayı / 4, 1) ----
    const val WEIGHT_YAWNS = 0.15f
    const val YAWN_WINDOW_MS = 10 * 60_000L
    const val YAWNS_FULL = 4f

    // ---- 4) Dikkat dağınıklığı olayları, son 5 dk: min(sayı / 5, 1) ----
    const val WEIGHT_DISTRACTIONS = 0.10f
    const val DISTRACTION_WINDOW_MS = 5 * 60_000L
    const val DISTRACTIONS_FULL = 5f

    // ---- 5) Kesintisiz sürüş süresi: min(dakika / 120, 1) ----
    const val WEIGHT_DRIVING_TIME = 0.10f
    const val DRIVING_FULL_MINUTES = 120f

    // ---- 6) Göz kırpma süresi ortalaması, son 2 dk: clamp((ort_ms − 150) / 250, 0, 1) ----
    const val WEIGHT_BLINK_DURATION = 0.10f
    const val BLINK_WINDOW_MS = 2 * 60_000L
    const val BLINK_BASELINE_MS = 150f // dinç bir sürücünün tipik kırpma süresi
    const val BLINK_RANGE_MS = 250f    // 150 ms → 0,  400 ms ve üstü → 1

    // ---- Yumuşatma ----
    const val UPDATE_INTERVAL_MS = 1_000L // skor saniyede bir hesaplanır
    const val EMA_ALPHA = 0.05f           // üstel hareketli ortalama katsayısı

    // ---- Seviye eşikleri ----
    const val MILD_THRESHOLD = 30f
    const val TIRED_THRESHOLD = 60f
    const val VERY_TIRED_THRESHOLD = 80f

    // ---- Uyarı ve kayıt ----
    const val ANNOUNCE_COOLDOWN_MS = 10 * 60_000L // sesli uyarı 10 dk'da en fazla 1 kez
    const val SAVE_INTERVAL_MS = 10_000L          // veritabanına 10 sn'de bir kayıt

    /** Ağırlıkların toplamı 1 olmalı (birim testte kontrol edilir) */
    val ALL_WEIGHTS = listOf(
        WEIGHT_PERCLOS, WEIGHT_LONG_CLOSURES, WEIGHT_YAWNS,
        WEIGHT_DISTRACTIONS, WEIGHT_DRIVING_TIME, WEIGHT_BLINK_DURATION,
    )
}
