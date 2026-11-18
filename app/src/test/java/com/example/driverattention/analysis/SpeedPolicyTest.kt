package com.example.driverattention.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedPolicyTest {

    // ---- Bantlar ve sınırlar ----

    @Test
    fun bands_followTableBoundaries() {
        assertEquals(SpeedBand.UNKNOWN, SpeedPolicy.bandFor(null))
        assertEquals(SpeedBand.STOPPED, SpeedPolicy.bandFor(0f))
        assertEquals(SpeedBand.STOPPED, SpeedPolicy.bandFor(4.9f))
        assertEquals(SpeedBand.SLOW, SpeedPolicy.bandFor(5f))
        assertEquals(SpeedBand.SLOW, SpeedPolicy.bandFor(29.9f))
        assertEquals(SpeedBand.MEDIUM, SpeedPolicy.bandFor(30f))
        assertEquals(SpeedBand.FAST, SpeedPolicy.bandFor(70f))
        assertEquals(SpeedBand.FAST, SpeedPolicy.bandFor(100f))   // "> 100" dendiği için 100 dahil değil
        assertEquals(SpeedBand.HIGHWAY, SpeedPolicy.bandFor(100.1f))
    }

    @Test
    fun rules_matchTable() {
        assertNull(SpeedPolicy.rulesFor(null).distractionMs) // ayarlardaki değer kullanılır
        assertEquals(3_000L, SpeedPolicy.rulesFor(20f).distractionMs)
        assertEquals(2_000L, SpeedPolicy.rulesFor(50f).distractionMs)
        assertEquals(1_500L, SpeedPolicy.rulesFor(90f).distractionMs)
        assertEquals(1_000L, SpeedPolicy.rulesFor(120f).distractionMs)

        val stopped = SpeedPolicy.rulesFor(0f)
        assertFalse(stopped.distractionAlerts)
        assertFalse(stopped.microsleepAudible)
        assertFalse(stopped.nonCriticalAlerts)

        assertTrue(SpeedPolicy.rulesFor(120f).louderAlarm)
        assertFalse(SpeedPolicy.rulesFor(90f).louderAlarm)
    }

    // ---- GPS yumuşatma ----

    @Test
    fun tracker_usesMedianOfLastThree() {
        val tracker = SpeedTracker()
        tracker.add(0, 40f)
        tracker.add(1_000, 41f)
        tracker.add(2_000, 95f) // GPS sıçraması
        assertEquals(41f, tracker.current(2_000)!!, 0f)
        tracker.add(3_000, 42f) // en eski (40) düşer: 41, 95, 42 → 42
        assertEquals(42f, tracker.current(3_000)!!, 0f)
    }

    @Test
    fun tracker_becomesUnknownWhenStale() {
        val tracker = SpeedTracker()
        assertNull(tracker.current(0)) // hiç veri yok
        tracker.add(0, 60f)
        assertEquals(60f, tracker.current(4_000)!!, 0f)
        assertNull(tracker.current(6_000)) // tünel: 5 sn'den eski
    }

    @Test
    fun tracker_ignoresInvalidValues() {
        val tracker = SpeedTracker()
        tracker.add(0, Float.NaN)
        tracker.add(0, -3f)
        assertNull(tracker.current(0))
    }

    // ---- Karar motoru + hız ----

    private fun metrics(ear: Float = 0.30f, mar: Float = 0.05f, yaw: Float = 0f) =
        FaceMetrics(ear, ear, ear, mar, HeadPose(yaw, 0f, 0f))

    private class Sim(val engine: DriverStateEngine = DriverStateEngine()) {
        var t = 0L
        val alerts = mutableListOf<AlertEvent>()
        lateinit var last: EngineOutput
        fun run(ms: Long, m: FaceMetrics?, speed: Float?) {
            val rules = SpeedPolicy.rulesFor(speed)
            val end = t + ms
            while (t < end) {
                last = engine.update(t, m, rules)
                alerts += last.newAlerts
                t += 40
            }
        }
        fun count(type: AlertType) = alerts.count { it.type == type }
    }

    @Test
    fun stopped_noDistractionAlert_butDetected() {
        val sim = Sim()
        sim.run(10_000, metrics(yaw = 45f), speed = 0f) // kırmızı ışıkta telefona bakıyor
        assertEquals(0, sim.count(AlertType.DISTRACTION))
        assertTrue(AlertType.DISTRACTION in sim.last.status.detectedConditions)
        assertFalse(AlertType.DISTRACTION in sim.last.status.activeConditions)
        assertEquals(SpeedBand.STOPPED, sim.last.status.speedBand)
    }

    @Test
    fun stopped_microsleepIsVisualOnly() {
        val sim = Sim()
        sim.run(2_000, metrics(ear = 0.1f), speed = 0f)
        val event = sim.alerts.single { it.type == AlertType.MICROSLEEP }
        assertFalse("dururken ses/titreşim olmamalı", event.audible)
        assertEquals(AlertLevel.CRITICAL, sim.last.status.level) // ekranda yine kırmızı
    }

    @Test
    fun moving_microsleepIsAudible() {
        val sim = Sim()
        sim.run(2_000, metrics(ear = 0.1f), speed = 50f)
        assertTrue(sim.alerts.single { it.type == AlertType.MICROSLEEP }.audible)
    }

    @Test
    fun stopped_yawnCountedButNotAlerted() {
        val sim = Sim()
        sim.run(1_500, metrics(mar = 0.8f), speed = 0f)
        assertEquals(0, sim.count(AlertType.YAWN))
        assertEquals(1, sim.last.status.recentYawnCount) // kaydedildi
        assertTrue(AlertType.YAWN in sim.last.status.detectedConditions)
    }

    @Test
    fun distractionTime_shrinksWithSpeed() {
        // 1.2 sn yana bakış: şehir içinde normal, otoyolda uyarı
        val city = Sim().apply { run(1_200, metrics(yaw = 45f), speed = 50f) }
        assertEquals(0, city.count(AlertType.DISTRACTION))
        val highway = Sim().apply { run(1_200, metrics(yaw = 45f), speed = 120f) }
        assertEquals(1, highway.count(AlertType.DISTRACTION))
        // 2.5 sn: yavaş giderken (3 sn) hâlâ yok
        val slow = Sim().apply { run(2_500, metrics(yaw = 45f), speed = 20f) }
        assertEquals(0, slow.count(AlertType.DISTRACTION))
    }

    @Test
    fun unknownSpeed_keepsPhase4Behaviour() {
        val sim = Sim()
        sim.run(1_900, metrics(yaw = 45f), speed = null)
        assertEquals(0, sim.count(AlertType.DISTRACTION))
        sim.run(200, metrics(yaw = 45f), speed = null)
        assertEquals(1, sim.count(AlertType.DISTRACTION)) // ayarlardaki 2 sn
    }
}
