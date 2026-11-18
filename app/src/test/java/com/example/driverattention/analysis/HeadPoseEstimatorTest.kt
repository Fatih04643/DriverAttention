package com.example.driverattention.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class HeadPoseEstimatorTest {

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()

    /** 3x3 satır-öncelikli dönme matrisini MediaPipe gibi 4x4 sütun-öncelikli diziye çevirir */
    private fun toColumnMajor4x4(r: Array<FloatArray>): FloatArray {
        val m = FloatArray(16)
        for (row in 0..2) for (col in 0..2) m[col * 4 + row] = r[row][col]
        m[15] = 1f
        m[14] = -40f // öteleme: yüz kameradan 40 cm uzakta (sonucu etkilememeli)
        return m
    }

    // Y ekseni etrafında dönme (yaw)
    private fun rotY(deg: Float): Array<FloatArray> {
        val c = cos(rad(deg)); val s = sin(rad(deg))
        return arrayOf(floatArrayOf(c, 0f, s), floatArrayOf(0f, 1f, 0f), floatArrayOf(-s, 0f, c))
    }

    // X ekseni etrafında dönme (pitch). Pozitif açı yüzü aşağı eğer (f.y < 0)
    private fun rotX(deg: Float): Array<FloatArray> {
        val c = cos(rad(deg)); val s = sin(rad(deg))
        return arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, c, -s), floatArrayOf(0f, s, c))
    }

    // Z ekseni etrafında dönme (roll)
    private fun rotZ(deg: Float): Array<FloatArray> {
        val c = cos(rad(deg)); val s = sin(rad(deg))
        return arrayOf(floatArrayOf(c, -s, 0f), floatArrayOf(s, c, 0f), floatArrayOf(0f, 0f, 1f))
    }

    @Test
    fun identity_isLookingStraight() {
        val pose = HeadPoseEstimator.fromTransformationMatrix(
            toColumnMajor4x4(arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f)))
        )
        assertEquals(0f, pose.yaw, 1e-3f)
        assertEquals(0f, pose.pitch, 1e-3f)
        assertEquals(0f, pose.roll, 1e-3f)
    }

    @Test
    fun yaw35_isDetected() {
        val pose = HeadPoseEstimator.fromTransformationMatrix(toColumnMajor4x4(rotY(35f)))
        assertEquals(35f, pose.yaw, 1e-2f)
        assertEquals(0f, pose.pitch, 1e-2f)
    }

    @Test
    fun lookingDown_givesNegativePitch() {
        val pose = HeadPoseEstimator.fromTransformationMatrix(toColumnMajor4x4(rotX(25f)))
        assertEquals(-25f, pose.pitch, 1e-2f)
        assertEquals(0f, pose.yaw, 1e-2f)
    }

    @Test
    fun roll_isDetected() {
        // Z etrafında -15° dönme: başın tepesi +X (sağ) tarafına yatar
        val pose = HeadPoseEstimator.fromTransformationMatrix(toColumnMajor4x4(rotZ(-15f)))
        assertEquals(15f, pose.roll, 1e-2f)
    }

    @Test
    fun movingAverage_smoothsAndResets() {
        val avg = MovingAverage(3)
        assertEquals(3f, avg.add(3f), 0f)
        assertEquals(4.5f, avg.add(6f), 0f)
        assertEquals(5f, avg.add(6f), 0f)
        assertEquals(8f, avg.add(12f), 0f) // 3 düştü: (6+6+12)/3
        avg.reset()
        assertEquals(1f, avg.add(1f), 0f)
    }
}
