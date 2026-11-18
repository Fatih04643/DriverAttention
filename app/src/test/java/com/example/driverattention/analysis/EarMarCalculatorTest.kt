package com.example.driverattention.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EarMarCalculatorTest {

    /**
     * Yapay bir göz: genişlik 30 px, dikey açıklık [opening] px.
     * Sıra: p1 (sol köşe), p2, p3 (üst), p4 (sağ köşe), p5, p6 (alt)
     */
    private fun syntheticEye(opening: Float) = listOf(
        Point2(0f, 0f),
        Point2(10f, -opening / 2), Point2(20f, -opening / 2),
        Point2(30f, 0f),
        Point2(20f, opening / 2), Point2(10f, opening / 2),
    )

    @Test
    fun openEye_hasExpectedEar() {
        // EAR = (9 + 9) / (2 * 30) = 0.3
        assertEquals(0.3f, EarCalculator.eyeAspectRatio(syntheticEye(9f)), 1e-4f)
    }

    @Test
    fun closedEye_hasEarNearZero() {
        assertEquals(0f, EarCalculator.eyeAspectRatio(syntheticEye(0f)), 1e-4f)
    }

    @Test
    fun ear_isScaleInvariant() {
        // Yüz kameraya 2 kat yaklaşınca oran değişmemeli
        val eye = syntheticEye(9f)
        val scaled = eye.map { Point2(it.x * 2, it.y * 2) }
        assertEquals(EarCalculator.eyeAspectRatio(eye), EarCalculator.eyeAspectRatio(scaled), 1e-4f)
    }

    @Test
    fun ear_degenerateEye_returnsZeroInsteadOfCrash() {
        val samePoint = List(6) { Point2(5f, 5f) }
        assertEquals(0f, EarCalculator.eyeAspectRatio(samePoint), 0f)
    }

    @Test
    fun averageEar_usesBothEyesFromLandmarkList() {
        val landmarks = MutableList(478) { Point2(0f, 0f) }
        FaceLandmarkIndices.LEFT_EYE.zip(syntheticEye(9f)).forEach { (i, p) -> landmarks[i] = p }   // 0.3
        FaceLandmarkIndices.RIGHT_EYE.zip(syntheticEye(3f)).forEach { (i, p) -> landmarks[i] = p }  // 0.1
        assertEquals(0.2f, EarCalculator.average(landmarks), 1e-4f)
    }

    @Test
    fun mar_closedAndYawningMouth() {
        val left = Point2(0f, 0f)
        val right = Point2(50f, 0f)
        val closed = MarCalculator.mouthAspectRatio(Point2(25f, 0f), Point2(25f, 1f), left, right)
        val yawn = MarCalculator.mouthAspectRatio(Point2(25f, -20f), Point2(25f, 20f), left, right)
        assertEquals(0.02f, closed, 1e-4f)
        assertEquals(0.8f, yawn, 1e-4f)
        assertTrue(yawn > 0.6f) // esneme eşiğinin üzerinde
    }

    @Test
    fun normalizedToPixel_respectsAspectRatio() {
        val p = Point2.fromNormalized(0.5f, 0.5f, 480, 640)
        assertEquals(240f, p.x, 0f)
        assertEquals(320f, p.y, 0f)
    }
}
