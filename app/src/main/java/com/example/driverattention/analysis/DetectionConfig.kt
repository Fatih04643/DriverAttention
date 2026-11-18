package com.example.driverattention.analysis

/**
 * Karar motorunun tüm eşikleri (CLAUDE.md'deki varsayılan değerler).
 * Faz 5'te Ayarlar ekranından değiştirilebilir hale gelecek.
 */
data class DetectionConfig(
    // --- Mikro uyku ---
    val earThreshold: Float = DEFAULT_EAR_THRESHOLD, // EAR bunun altındaysa göz "kapalı"
    val microSleepMs: Long = 1_500,

    // --- PERCLOS (yorgunluk) ---
    val perclosThreshold: Float = 0.15f,
    val perclosWindowMs: Long = 60_000,
    val perclosMinCoverageMs: Long = 30_000, // en az 30 sn veri birikmeden karar verme

    // --- Esneme ---
    val marThreshold: Float = 0.6f,
    val yawnMinMs: Long = 1_000,
    val yawnCountWindowMs: Long = 5 * 60_000,
    val yawnsForFatigue: Int = 3,

    // --- Dikkat dağınıklığı ---
    val yawLimitDeg: Float = 30f,        // |yaw| bunu aşarsa yana bakıyor
    val pitchDownLimitDeg: Float = -20f, // pitch bunun altındaysa aşağı bakıyor
    val distractionMs: Long = 2_000,
    // Kalibrasyonda ölçülen "düz bakış" açıları. Telefon yüzün altında/yanında durduğunda
    // düz bakış 0° çıkmaz; açılar bu referansa göre değerlendirilir.
    val baselineYawDeg: Float = 0f,
    val baselinePitchDeg: Float = 0f,

    // --- Yüz yok ---
    val noFaceMs: Long = 3_000,

    // --- Tekrar önleme (cooldown) ---
    val cooldownMs: Long = 5_000,        // aynı uyarı en erken 5 sn sonra tekrar çalar
    val fatigueRepeatMs: Long = 60_000,  // yorgunluk kalıcı bir durum: dakikada bir hatırlat
) {
    companion object {
        const val DEFAULT_EAR_THRESHOLD = 0.21f
        const val EAR_CALIBRATION_FACTOR = 0.75f

        /** Kalibrasyon sonucunu kullanan yapılandırma */
        fun fromCalibration(result: CalibrationResult) = DetectionConfig(
            earThreshold = result.earThreshold,
            baselineYawDeg = result.baselineYawDeg,
            baselinePitchDeg = result.baselinePitchDeg,
        )
    }
}
