package com.example.driverattention.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLightEnhancerTest {

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun grayOf(p: Int) = p and 0xFF

    /** 20..59 aralığında gri tonlardan oluşan karanlık, düşük kontrastlı görüntü */
    private fun darkImage(): IntArray = IntArray(10_000) { gray(20 + it % 40) }

    @Test
    fun meanBrightness_ofUniformImage() {
        val enhancer = LowLightEnhancer()
        val img = IntArray(1000) { gray(100) }
        assertEquals(100f, enhancer.meanBrightness(img), 1f)
    }

    @Test
    fun darkImage_isDetectedAsDark_brightIsNot() {
        val e = LowLightEnhancer(darknessThreshold = 80)
        assertTrue(e.isDark(e.meanBrightness(darkImage())))
        assertFalse(e.isDark(e.meanBrightness(IntArray(1000) { gray(150) })))
    }

    @Test
    fun lut_isMonotonic() {
        // Eşleme sıralamayı bozmamalı: koyu piksel, daha açık pikselden daha parlak olamaz
        val lut = LowLightEnhancer().buildLut(darkImage())
        for (v in 1..255) assertTrue("LUT[$v] < LUT[${v - 1}]", lut[v] >= lut[v - 1])
    }

    @Test
    fun enhance_increasesBrightnessAndContrast() {
        val e = LowLightEnhancer()
        val img = darkImage()
        val beforeMean = e.meanBrightness(img)
        val beforeRange = img.maxOf(::grayOf) - img.minOf(::grayOf)

        e.enhance(img)

        val afterMean = e.meanBrightness(img)
        val afterRange = img.maxOf(::grayOf) - img.minOf(::grayOf)
        assertTrue("parlaklık artmalı ($beforeMean → $afterMean)", afterMean > beforeMean + 30)
        assertTrue("kontrast artmalı ($beforeRange → $afterRange)", afterRange > beforeRange * 3)
    }

    @Test
    fun enhance_keepsAlphaAndGrayStaysGray() {
        val img = darkImage()
        LowLightEnhancer().enhance(img)
        img.forEach { p ->
            assertEquals(0xFF, p ushr 24)
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            assertTrue(r == g && g == b) // renk tonu korunur
        }
    }

    @Test
    fun clipLimit_preventsExtremeStretchingOfFlatImage() {
        // Neredeyse tek renkli (gürültü) görüntü: sınırsız eşitleme bunu siyah-beyaza patlatırdı
        val img = IntArray(10_000) { gray(if (it % 100 == 0) 31 else 30) }
        val lut = LowLightEnhancer().buildLut(img)
        assertTrue("30 → ${lut[30]}, 31 → ${lut[31]}", lut[31] - lut[30] < 128)
    }
}
