package com.example.driverattention.ui.history

import com.example.driverattention.data.FatigueScoreEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Uyarı işaretinin grafikte skor eğrisinin üstüne doğru konması için enterpolasyon */
class FatigueChartTest {

    private fun s(t: Long, score: Float) = FatigueScoreEntity(sessionId = 1, timestamp = t, score = score)

    @Test
    fun interpolate_betweenSamples() {
        val scores = listOf(s(0, 10f), s(10_000, 30f), s(20_000, 70f))
        assertEquals(20f, interpolateScore(scores, 5_000)!!, 1e-4f)
        assertEquals(50f, interpolateScore(scores, 15_000)!!, 1e-4f)
    }

    @Test
    fun interpolate_clampsOutsideRange_andHandlesEmpty() {
        val scores = listOf(s(10_000, 40f), s(20_000, 60f))
        assertEquals(40f, interpolateScore(scores, 0)!!, 0f)
        assertEquals(60f, interpolateScore(scores, 99_000)!!, 0f)
        assertNull(interpolateScore(emptyList(), 5_000))
    }
}
