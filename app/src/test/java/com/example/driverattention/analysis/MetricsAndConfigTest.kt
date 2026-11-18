package com.example.driverattention.analysis

import com.example.driverattention.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FaceMetricsCalculator, ayar → yapılandırma dönüşümü ve karar motorunun kenar durumları */
class MetricsAndConfigTest {

    private fun metrics(ear: Float = 0.30f, mar: Float = 0.05f, yaw: Float = 0f, pitch: Float = 0f) =
        FaceMetrics(ear, ear, ear, mar, HeadPose(yaw, pitch, 0f))

    /** 478 noktalık yapay yüz: gözler [opening] px açık, ağız [mouth] px açık */
    private fun face(opening: Float, mouth: Float = 2f): List<Point2> {
        val pts = MutableList(478) { Point2(0f, 0f) }
        fun eye(idx: IntArray, x0: Float) {
            val e = listOf(
                Point2(x0, 0f), Point2(x0 + 10, -opening / 2), Point2(x0 + 20, -opening / 2),
                Point2(x0 + 30, 0f), Point2(x0 + 20, opening / 2), Point2(x0 + 10, opening / 2),
            )
            idx.zip(e).forEach { (i, p) -> pts[i] = p }
        }
        eye(FaceLandmarkIndices.LEFT_EYE, 0f)
        eye(FaceLandmarkIndices.RIGHT_EYE, 60f)
        pts[FaceLandmarkIndices.MOUTH_LEFT_CORNER] = Point2(10f, 50f)
        pts[FaceLandmarkIndices.MOUTH_RIGHT_CORNER] = Point2(60f, 50f)
        pts[FaceLandmarkIndices.MOUTH_UPPER_INNER] = Point2(35f, 50f - mouth / 2)
        pts[FaceLandmarkIndices.MOUTH_LOWER_INNER] = Point2(35f, 50f + mouth / 2)
        return pts
    }

    // ---- FaceMetricsCalculator ----

    @Test
    fun metricsCalculator_smoothsSuddenChange() {
        val calc = FaceMetricsCalculator(windowSize = 5)
        repeat(5) { calc.update(face(opening = 9f), null) }         // EAR 0.3
        val m = calc.update(face(opening = 0f), null)              // tek karelik kapanma
        assertEquals(0.24f, m.ear, 1e-3f)                          // (4×0.3 + 0)/5 — ani düşüş yumuşar
        assertNull(m.headPose)                                     // matris yoksa poz yok
    }

    @Test
    fun metricsCalculator_resetForgetsOldFace() {
        val calc = FaceMetricsCalculator(windowSize = 5)
        repeat(5) { calc.update(face(opening = 9f), null) }
        calc.reset()
        assertEquals(0.1f, calc.update(face(opening = 3f), null).ear, 1e-3f)
    }

    @Test
    fun metricsCalculator_computesMar() {
        val m = FaceMetricsCalculator(1).update(face(opening = 9f, mouth = 40f), null)
        assertEquals(0.8f, m.mar, 1e-3f) // 40 / 50
    }

    // ---- AppSettings → DetectionConfig ----

    @Test
    fun settings_withCalibration_usesPersonalThreshold() {
        val calibration = CalibrationResult(
            openEar = 0.32f, earThreshold = 0.24f, baselineYawDeg = 5f, baselinePitchDeg = 12f, sampleCount = 100,
        )
        val config = AppSettings(earCalibrationFactor = 0.8f).toDetectionConfig(calibration)
        assertEquals(0.256f, config.earThreshold, 1e-4f) // 0.32 × 0.8
        assertEquals(5f, config.baselineYawDeg, 0f)
        assertEquals(12f, config.baselinePitchDeg, 0f)
    }

    @Test
    fun settings_withoutCalibration_usesDefaultsAndConvertsUnits() {
        val config = AppSettings(microSleepSec = 2f, perclosPercent = 20f, pitchDownLimitDeg = 25f)
            .toDetectionConfig(null)
        assertEquals(0.21f, config.earThreshold, 1e-4f)
        assertEquals(2_000L, config.microSleepMs)
        assertEquals(0.20f, config.perclosThreshold, 1e-4f)
        assertEquals(-25f, config.pitchDownLimitDeg, 0f) // "aşağı" negatif pitch'tir
    }

    @Test
    fun defaultSettings_matchDocumentedDefaults() {
        assertEquals(DetectionConfig(), AppSettings().toDetectionConfig(null))
    }

    // ---- Karar motoru: kenar durumları ----

    private class Sim(val engine: DriverStateEngine = DriverStateEngine()) {
        var t = 0L
        val alerts = mutableListOf<AlertEvent>()
        fun run(ms: Long, m: FaceMetrics?) {
            val end = t + ms
            while (t < end) {
                alerts += engine.update(t, m).newAlerts
                t += 40
            }
        }
        fun count(type: AlertType) = alerts.count { it.type == type }
    }

    @Test
    fun faceLost_resetsEyeTimer() {
        // Gözler 1 sn kapalı → yüz kayboldu → yüz geri geldi, gözler 1 sn daha kapalı.
        // Toplam 2 sn ama kesintisiz değil: mikro uyku SAYILMAMALI.
        val sim = Sim()
        sim.run(1_000, metrics(ear = 0.1f))
        sim.run(500, null)
        sim.run(1_000, metrics(ear = 0.1f))
        assertEquals(0, sim.count(AlertType.MICROSLEEP))
    }

    @Test
    fun lookingBackBriefly_resetsDistractionTimer() {
        val sim = Sim()
        repeat(5) {
            sim.run(1_500, metrics(yaw = 45f)) // 1.5 sn ayna kontrolü
            sim.run(500, metrics())           // yola geri bakış
        }
        assertEquals(0, sim.count(AlertType.DISTRACTION))
    }

    @Test
    fun fatigueAlert_repeatsOnlyEveryMinute() {
        val sim = Sim()
        repeat(3) {
            sim.run(1_200, metrics(mar = 0.8f))
            sim.run(3_000, metrics())
        }
        // 3. esnemeden sonra 50 sn daha: yorgunluk hâlâ etkin ama tekrar çalmamalı
        sim.run(50_000, metrics())
        assertEquals(1, sim.count(AlertType.FATIGUE_YAWNING))
        sim.run(15_000, metrics()) // 60 sn doldu
        assertEquals(2, sim.count(AlertType.FATIGUE_YAWNING))
    }

    @Test
    fun microsleepAndDistraction_criticalWins() {
        val sim = Sim()
        var last: EngineOutput? = null
        val engine = sim.engine
        var t = 0L
        while (t < 2_500) {
            last = engine.update(t, metrics(ear = 0.1f, pitch = -30f)) // başı düşmüş, gözler kapalı
            t += 40
        }
        assertEquals(AlertLevel.CRITICAL, last!!.status.level)
        assertTrue(AlertType.DISTRACTION in last.status.activeConditions)
    }

    @Test
    fun calibration_baselineUsesMedian_robustToHeadMovement() {
        val samples = List(42) { metrics(yaw = 3f) } + List(3) { metrics(yaw = 50f) } // birkaç ani bakış
        val r = (Calibrator.compute(samples) as CalibrationOutcome.Success).result
        assertEquals(3f, r.baselineYawDeg, 1e-3f)
    }

    /**
     * Gerçek hata (telefonda CSV kayıtlarıyla bulundu): kalibrasyon sırasında kullanıcı yukarı
     * bakıyordu, referans pitch ≈ 36° kaydedildi. Sonra yola (pitch ≈ 10°) bakarken fark -26°
     * çıktı → sürekli "Yola odaklanın". Artık böyle bir kalibrasyon reddedilmeli.
     */
    @Test
    fun calibration_rejectsLookingUpDuringMeasurement() {
        val samples = List(100) { metrics(pitch = 36f) }
        val outcome = Calibrator.compute(samples)
        assertTrue(outcome is CalibrationOutcome.Failure)
        assertTrue((outcome as CalibrationOutcome.Failure).reason.contains("yukarı"))
    }

    @Test
    fun calibration_rejectsExtremeYaw_acceptsModerateOffset() {
        assertTrue(Calibrator.compute(List(100) { metrics(yaw = 55f) }) is CalibrationOutcome.Failure)
        // Telefon biraz yanda/aşağıda: makul referans kabul edilir
        val ok = Calibrator.compute(List(100) { metrics(yaw = 20f, pitch = 15f) })
        assertTrue(ok is CalibrationOutcome.Success)
    }

    @Test
    fun calibration_rejectsMovingHead() {
        // Ölçüm boyunca baş sürekli sağa sola dönüyor (-20°..+20°)
        val samples = List(100) { metrics(yaw = -20f + 40f * it / 99) }
        val outcome = Calibrator.compute(samples)
        assertTrue(outcome is CalibrationOutcome.Failure)
        assertTrue((outcome as CalibrationOutcome.Failure).reason.contains("hareket"))
    }

    @Test
    fun goodCalibration_noFalseDistractionWhileLookingAtRoad() {
        // Doğru kalibrasyon (pitch ≈ 10°) sonrası, yola bakarken (pitch 7–15° titreşim) 1 dk boyunca uyarı yok
        val r = (Calibrator.compute(List(100) { metrics(pitch = 10f) }) as CalibrationOutcome.Success).result
        val sim = Sim(DriverStateEngine(AppSettings().toDetectionConfig(r)))
        repeat(60) { i -> sim.run(1_000, metrics(pitch = if (i % 2 == 0) 7f else 15f)) }
        assertEquals(0, sim.count(AlertType.DISTRACTION))
    }
}
