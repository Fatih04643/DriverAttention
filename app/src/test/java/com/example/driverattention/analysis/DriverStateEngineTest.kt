package com.example.driverattention.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Karar motoru zamanlama testleri. Kameraya gerek yok: 25 FPS'lik sahte kare akışı üretiyoruz.
 */
class DriverStateEngineTest {

    private val frameMs = 40L // 25 FPS

    private fun metrics(ear: Float = 0.30f, mar: Float = 0.05f, yaw: Float = 0f, pitch: Float = 0f) =
        FaceMetrics(ear, ear, ear, mar, HeadPose(yaw, pitch, 0f))

    /** [durationMs] boyunca aynı metriklerle kare gönderir, tüm tetiklenen uyarıları döndürür */
    private class Sim(val engine: DriverStateEngine = DriverStateEngine()) {
        var t = 0L
        val alerts = mutableListOf<AlertEvent>()
        lateinit var last: EngineOutput

        fun run(durationMs: Long, m: FaceMetrics?, frameMs: Long = 40L) {
            val end = t + durationMs
            while (t < end) {
                last = engine.update(t, m)
                alerts += last.newAlerts
                t += frameMs
            }
        }

        fun count(type: AlertType) = alerts.count { it.type == type }
    }

    @Test
    fun blink_doesNotTriggerMicrosleep() {
        val sim = Sim()
        sim.run(2_000, metrics())
        sim.run(300, metrics(ear = 0.1f)) // normal göz kırpma ~0.3 sn
        sim.run(2_000, metrics())
        assertEquals(0, sim.count(AlertType.MICROSLEEP))
    }

    @Test
    fun microsleep_triggersAfter1500ms_notBefore() {
        val sim = Sim()
        sim.run(1_000, metrics())
        sim.run(1_440, metrics(ear = 0.1f))
        assertEquals("1.44 sn'de henüz uyarı olmamalı", 0, sim.count(AlertType.MICROSLEEP))
        sim.run(120, metrics(ear = 0.1f))
        assertEquals(1, sim.count(AlertType.MICROSLEEP))
        assertEquals(AlertLevel.CRITICAL, sim.last.status.level)
    }

    @Test
    fun microsleep_cooldown_preventsRepeatWithin5s_thenRepeats() {
        val sim = Sim()
        sim.run(4_000, metrics(ear = 0.1f)) // 1.5 sn'de 1 kez
        assertEquals(1, sim.count(AlertType.MICROSLEEP))
        sim.run(3_000, metrics(ear = 0.1f)) // 1.5+5=6.5 sn'de 2. kez
        assertEquals(2, sim.count(AlertType.MICROSLEEP))
    }

    @Test
    fun distraction_yaw_triggersAfter2s() {
        val sim = Sim()
        sim.run(1_900, metrics(yaw = 40f))
        assertEquals(0, sim.count(AlertType.DISTRACTION))
        sim.run(200, metrics(yaw = -40f))
        assertEquals(1, sim.count(AlertType.DISTRACTION))
    }

    @Test
    fun distraction_lookingDown_usesCalibrationBaseline() {
        // Telefon aşağıda: düz bakış pitch'i +10°. -5° mutlak değer, referansa göre -15° → normal
        val engine = DriverStateEngine(DetectionConfig(baselinePitchDeg = 10f))
        val sim = Sim(engine)
        sim.run(3_000, metrics(pitch = -5f))
        assertEquals(0, sim.count(AlertType.DISTRACTION))
        // -15° mutlak, referansa göre -25° → aşağı bakıyor
        sim.run(2_100, metrics(pitch = -15f))
        assertEquals(1, sim.count(AlertType.DISTRACTION))
    }

    @Test
    fun noFace_triggersInfoAfter3s() {
        val sim = Sim()
        sim.run(2_900, null)
        assertEquals(0, sim.count(AlertType.NO_FACE))
        sim.run(200, null)
        assertEquals(1, sim.count(AlertType.NO_FACE))
        assertEquals(AlertLevel.INFO, sim.last.status.level)
    }

    @Test
    fun yawn_countedOncePerYawn_andThreeYawnsMeanFatigue() {
        val sim = Sim()
        repeat(3) {
            sim.run(1_500, metrics(mar = 0.8f)) // esneme
            sim.run(10_000, metrics())           // normal
        }
        // İlk ikisi YAWN, üçüncüsü FATIGUE_YAWNING olarak çalmalı
        assertEquals(2, sim.count(AlertType.YAWN))
        assertEquals(1, sim.count(AlertType.FATIGUE_YAWNING))
        assertEquals(3, sim.last.status.recentYawnCount)
    }

    @Test
    fun shortMouthOpening_isNotYawn() {
        val sim = Sim()
        sim.run(800, metrics(mar = 0.8f)) // konuşma / kısa açılma
        sim.run(1_000, metrics())
        assertEquals(0, sim.count(AlertType.YAWN))
    }

    @Test
    fun perclos_needsEnoughData_thenTriggersFatigue() {
        val sim = Sim()
        // Her saniyede 0.2 sn kapalı (%20) — tek tek kısa kırpmalar, mikro uyku yok
        repeat(25) {
            sim.run(800, metrics())
            sim.run(200, metrics(ear = 0.1f))
        }
        assertEquals("30 sn veri birikmeden uyarı yok", 0, sim.count(AlertType.FATIGUE_PERCLOS))
        repeat(10) {
            sim.run(800, metrics())
            sim.run(200, metrics(ear = 0.1f))
        }
        assertEquals(1, sim.count(AlertType.FATIGUE_PERCLOS))
        assertEquals(0, sim.count(AlertType.MICROSLEEP))
    }

    @Test
    fun perclosTracker_slidingWindowDropsOldFrames() {
        val tracker = PerclosTracker(windowMs = 1_000)
        tracker.add(0, true)
        tracker.add(500, false)
        assertEquals(0.5f, tracker.perclos, 1e-4f)
        tracker.add(1_600, false) // t=0 ve t=500 pencere dışında
        assertEquals(0f, tracker.perclos, 1e-4f)
    }

    @Test
    fun calibration_trimsBlinks_andComputesThreshold() {
        // 40 açık kare (EAR 0.32) + 8 göz kırpma karesi (EAR 0.05)
        val samples = List(40) { metrics(ear = 0.32f, pitch = 12f) } + List(8) { metrics(ear = 0.05f, pitch = 12f) }
        val outcome = Calibrator.compute(samples)
        assertTrue(outcome is CalibrationOutcome.Success)
        val r = (outcome as CalibrationOutcome.Success).result
        assertEquals(0.32f, r.openEar, 1e-3f)
        assertEquals(0.24f, r.earThreshold, 1e-3f)
        assertEquals(12f, r.baselinePitchDeg, 1e-3f)
    }

    @Test
    fun calibration_failsWithTooFewSamples_orClosedEyes() {
        assertTrue(Calibrator.compute(List(5) { metrics() }) is CalibrationOutcome.Failure)
        assertTrue(Calibrator.compute(List(50) { metrics(ear = 0.08f) }) is CalibrationOutcome.Failure)
    }

    @Test
    fun normalDriving_hasNoAlerts() {
        val sim = Sim()
        sim.run(60_000, metrics())
        assertTrue(sim.alerts.isEmpty())
        assertEquals(AlertLevel.NONE, sim.last.status.level)
        assertFalse(sim.last.status.activeConditions.isNotEmpty())
    }
}
