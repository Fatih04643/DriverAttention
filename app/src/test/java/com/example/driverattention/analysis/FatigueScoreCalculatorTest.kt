package com.example.driverattention.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.driverattention.analysis.FatigueScoreConstants as C

class FatigueScoreCalculatorTest {

    /** 20 FPS'lik sahte kare akışı */
    private class Sim(val calc: FatigueScoreCalculator = FatigueScoreCalculator()) {
        var t = 0L
        lateinit var last: FatigueScore
        val announcements = mutableListOf<Pair<Long, FatigueLevel>>()

        fun run(
            ms: Long,
            eyesClosed: Boolean? = false,
            perclos: Float = 0f,
            yawn: Boolean = false,
            distraction: Boolean = false,
            override: Float? = null,
        ) {
            val end = t + ms
            while (t < end) {
                last = calc.update(t, eyesClosed, perclos, yawn, distraction, override)
                last.announcement?.let { announcements += t to it }
                t += 50
            }
        }
    }

    @Test
    fun weights_sumToOne() {
        assertEquals(1f, C.ALL_WEIGHTS.sum(), 1e-5f)
    }

    @Test
    fun alertDriver_staysNormal() {
        val sim = Sim()
        sim.run(5 * 60_000L)
        assertEquals(FatigueLevel.NORMAL, sim.last.level)
        // Tek katkı sürüş süresi: 5/120 × 0.10 × 100 ≈ 0.4
        assertTrue("skor ${sim.last.score}", sim.last.score < 1f)
    }

    @Test
    fun perclos_isNormalizedAndCapped() {
        val sim = Sim()
        sim.run(1_000, perclos = 0.15f)
        assertEquals(0.5f, sim.last.components.perclosNorm, 1e-4f)
        sim.run(1_000, perclos = 0.60f)
        assertEquals(1f, sim.last.components.perclosNorm, 1e-4f) // üst sınır
    }

    @Test
    fun blinksAndLongClosures_areSeparatedBy500ms() {
        val sim = Sim()
        repeat(3) {
            sim.run(200, eyesClosed = true)   // normal kırpma
            sim.run(3_000)
        }
        repeat(2) {
            sim.run(800, eyesClosed = true)   // uzun kapanma
            sim.run(3_000)
        }
        val c = sim.last.components
        assertEquals(2, c.longClosureCount)
        assertEquals(0.4f, c.longClosuresNorm, 1e-4f) // 2 / 5
        assertEquals(200f, c.avgBlinkMs!!, 1f)
        assertEquals(0.2f, c.blinkDurationNorm, 0.01f) // (200 − 150) / 250
    }

    @Test
    fun faceLostDuringClosure_isNotCounted() {
        val sim = Sim()
        sim.run(400, eyesClosed = true)
        sim.run(2_000, eyesClosed = null) // yüz kayboldu
        sim.run(1_000)
        assertEquals(0, sim.last.components.longClosureCount)
        assertNull(sim.last.components.avgBlinkMs)
    }

    @Test
    fun yawns_countedOncePerEpisode_andExpireAfter10Minutes() {
        val sim = Sim()
        repeat(2) {
            sim.run(3_000, yawn = true) // tek esneme 3 sn sürse de 1 olay
            sim.run(5_000)
        }
        assertEquals(2, sim.last.components.yawnCount)
        assertEquals(0.5f, sim.last.components.yawnsNorm, 1e-4f) // 2 / 4
        sim.run(10 * 60_000L)
        assertEquals(0, sim.last.components.yawnCount)
    }

    @Test
    fun distractions_countedPerEpisode() {
        val sim = Sim()
        repeat(6) {
            sim.run(2_500, distraction = true)
            sim.run(2_000)
        }
        assertEquals(6, sim.last.components.distractionCount)
        assertEquals(1f, sim.last.components.distractionsNorm, 1e-4f) // üst sınır
    }

    @Test
    fun drivingTime_reachesFullAt120Minutes() {
        val sim = Sim()
        sim.run(60 * 60_000L)
        assertEquals(0.5f, sim.last.components.drivingTimeNorm, 0.01f)
    }

    @Test
    fun ema_respondsGraduallyToSuddenFatigue() {
        val sim = Sim()
        // Her şey en kötü: ham skor yüksek ama EMA yavaş yükselir
        sim.run(1_000, perclos = 0.6f)
        val afterOneSecond = sim.last.score
        assertTrue("1 sn sonra skor küçük olmalı: $afterOneSecond", afterOneSecond < 5f)
        sim.run(60_000, perclos = 0.6f)
        // Sadece PERCLOS bileşeni dolu: ham ≈ 35. 60 sn'de EMA ≈ %95'ine ulaşır
        assertTrue("60 sn sonra ≈ 33: ${sim.last.score}", sim.last.score in 31f..35.5f)
    }

    @Test
    fun levels_followThresholds() {
        assertEquals(FatigueLevel.NORMAL, FatigueLevel.fromScore(29.9f))
        assertEquals(FatigueLevel.MILD, FatigueLevel.fromScore(30f))
        assertEquals(FatigueLevel.TIRED, FatigueLevel.fromScore(60f))
        assertEquals(FatigueLevel.VERY_TIRED, FatigueLevel.fromScore(80f))
    }

    @Test
    fun announcement_tiredAtMostOncePer10Minutes() {
        val sim = Sim()
        sim.run(5 * 60_000L, override = 65f)
        assertEquals(1, sim.announcements.size)
        assertEquals(FatigueLevel.TIRED, sim.announcements[0].second)
        sim.run(6 * 60_000L, override = 65f) // 10 dk doldu → tekrar
        assertEquals(2, sim.announcements.size)
    }

    @Test
    fun announcement_escalationIsImmediate() {
        val sim = Sim()
        sim.run(30_000, override = 65f)
        sim.run(5_000, override = 85f) // 30 sn sonra kötüleşti: beklemeden uyar
        assertEquals(listOf(FatigueLevel.TIRED, FatigueLevel.VERY_TIRED), sim.announcements.map { it.second })
        sim.run(60_000, override = 85f) // aynı seviyede tekrar yok
        assertEquals(2, sim.announcements.size)
    }

    @Test
    fun noAnnouncementBelowTired() {
        val sim = Sim()
        sim.run(60_000, override = 55f)
        assertTrue(sim.announcements.isEmpty())
        assertTrue(sim.last.simulated)
    }

    @Test
    fun fullyFatigued_reachesVeryTired() {
        val sim = Sim()
        // 2 saatlik sürüşün son 10 dakikası: tüm belirtiler var
        sim.run(110 * 60_000L)
        repeat(30) {
            sim.run(700, eyesClosed = true, perclos = 0.35f)     // uzun kapanma
            sim.run(5_000, perclos = 0.35f, yawn = it % 6 == 0)
            sim.run(2_500, perclos = 0.35f, distraction = it % 5 == 0)
            sim.run(450, eyesClosed = true, perclos = 0.35f)     // yavaş kırpma
            sim.run(3_000, perclos = 0.35f)
        }
        assertEquals("skor ${sim.last.score} ham ${sim.last.rawScore}", FatigueLevel.VERY_TIRED, sim.last.level)
    }
}
