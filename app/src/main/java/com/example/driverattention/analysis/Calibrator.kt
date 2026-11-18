package com.example.driverattention.analysis

import kotlin.math.abs

/** Kalibrasyonla ölçülen kişiye özel değerler */
data class CalibrationResult(
    val openEar: Float,         // sürücünün gözleri açıkken ortalama EAR'ı
    val earThreshold: Float,    // openEar * 0.75
    val baselineYawDeg: Float,  // düz bakarken yaw
    val baselinePitchDeg: Float,// düz bakarken pitch
    val sampleCount: Int,
)

sealed interface CalibrationOutcome {
    data class Success(val result: CalibrationResult) : CalibrationOutcome
    data class Failure(val reason: String) : CalibrationOutcome
}

/**
 * Kalibrasyon hesabı.
 *
 * Neden gerekli? Göz şekli kişiden kişiye çok değişir: birinin açık göz EAR'ı 0.35 iken
 * diğerininki 0.24 olabilir. Sabit 0.21 eşiği ikinci kişide sürekli yanlış alarm, birincide
 * geç alarm üretir. Bu yüzden her sürücünün kendi açık-göz EAR'ını ölçüp eşiği ona göre
 * belirliyoruz:  eşik = açık_göz_EAR × 0.75
 */
object Calibrator {
    const val MIN_SAMPLES = 30          // ~25 FPS'te en az ~1.2 sn yüz görülmeli
    const val MIN_VALID_OPEN_EAR = 0.15f
    private const val BLINK_TRIM_RATIO = 0.2f

    /** Ölçüm boyunca kabul edilen en fazla baş hareketi (p10–p90 aralığı, derece) */
    const val MAX_POSE_SPREAD_DEG = 12f

    /** Yola bakarken yüzün kameraya göre en fazla ne kadar dönük olabileceği (derece) */
    const val MAX_BASELINE_PITCH_DEG = 30f
    const val MAX_BASELINE_YAW_DEG = 40f

    fun compute(
        samples: List<FaceMetrics>,
        earFactor: Float = DetectionConfig.EAR_CALIBRATION_FACTOR,
    ): CalibrationOutcome {
        if (samples.size < MIN_SAMPLES) {
            return CalibrationOutcome.Failure("Yüz yeterince algılanamadı. Kameranın yüzünüzü gördüğünden emin olun.")
        }

        // Ölçüm sırasında kullanıcı doğal olarak göz kırpar; bu kareler ortalamayı düşürür.
        // En düşük %20'lik EAR değerlerini atıp kalanların ortalamasını alıyoruz (kırpılmış ortalama).
        val sortedEar = samples.map { it.ear }.sorted()
        val kept = sortedEar.drop((sortedEar.size * BLINK_TRIM_RATIO).toInt())
        val openEar = kept.average().toFloat()

        if (openEar < MIN_VALID_OPEN_EAR) {
            return CalibrationOutcome.Failure("Gözler açık algılanamadı. Lütfen gözlerinizi açık tutarak tekrar deneyin.")
        }

        // Düz bakış açısı: medyan kullanıyoruz, çünkü anlık baş hareketlerinden ortalamaya göre daha az etkilenir
        val poses = samples.mapNotNull { it.headPose }
        val yaws = poses.map { it.yaw }
        val pitches = poses.map { it.pitch }
        val baselineYaw = yaws.median()
        val baselinePitch = pitches.median()

        // ---- Referans açı doğrulaması ----
        // Bu referans, sonraki TÜM oturumlarda "yola bakış" kabul edilir. Yanlış kaydedilirse
        // (ör. kullanıcı kalibrasyon sırasında yukarı/yana bakıyorsa) sürücü yola bakarken bile
        // sürekli "Yola odaklanın" uyarısı alır. Bu yüzden iki kontrol yapıyoruz:
        if (poses.isNotEmpty()) {
            // 1) Kararlılık: ölçüm boyunca baş sabit durmalı. Ani tek tük bakışlar sorun değil,
            //    bu yüzden en uç %10'luk dilimleri dışarıda bırakan aralığa (p10–p90) bakıyoruz.
            if (yaws.spread() > MAX_POSE_SPREAD_DEG || pitches.spread() > MAX_POSE_SPREAD_DEG) {
                return CalibrationOutcome.Failure(
                    "Ölçüm sırasında başınız hareket etti. Yola bakar gibi sabit durup tekrar deneyin."
                )
            }
            // 2) Makullük: yola bakarken yüz kameraya kabaca dönük olmalı. Çok büyük bir açı
            //    hem büyük olasılıkla yanlış yere bakıldığını gösterir, hem de bu açılarda
            //    landmark doğruluğu düşer.
            if (abs(baselinePitch) > MAX_BASELINE_PITCH_DEG || abs(baselineYaw) > MAX_BASELINE_YAW_DEG) {
                return CalibrationOutcome.Failure(
                    "Başınız kameraya göre çok %s dönük görünüyor (yaw %.0f°, pitch %.0f°). "
                        .format(directionWord(baselineYaw, baselinePitch), baselineYaw, baselinePitch) +
                        "Telefonu yüzünüze dönük yerleştirip yola bakarak tekrar deneyin."
                )
            }
        }

        return CalibrationOutcome.Success(
            CalibrationResult(
                openEar = openEar,
                earThreshold = openEar * earFactor,
                baselineYawDeg = baselineYaw,
                baselinePitchDeg = baselinePitch,
                sampleCount = samples.size,
            )
        )
    }

    /** p90 − p10: uç değerlerden etkilenmeyen yayılım ölçüsü */
    private fun List<Float>.spread(): Float {
        if (size < 2) return 0f
        val s = sorted()
        return s[(s.size * 0.9f).toInt().coerceAtMost(s.size - 1)] - s[(s.size * 0.1f).toInt()]
    }

    private fun directionWord(yaw: Float, pitch: Float): String = when {
        abs(pitch) / MAX_BASELINE_PITCH_DEG >= abs(yaw) / MAX_BASELINE_YAW_DEG ->
            if (pitch > 0) "yukarı" else "aşağı"
        else -> "yana"
    }

    private fun List<Float>.median(): Float {
        if (isEmpty()) return 0f
        val s = sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }
}
