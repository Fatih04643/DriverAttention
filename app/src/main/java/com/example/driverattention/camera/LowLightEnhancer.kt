package com.example.driverattention.camera

import kotlin.math.roundToInt

/**
 * Düşük ışık iyileştirmesi: kontrast sınırlı histogram eşitleme.
 *
 * Problem: Gece veya tünelde ön kamera görüntüsü karanlık ve düşük kontrastlı olur; piksel
 * değerleri 0–60 gibi dar bir aralığa sıkışır. Yüz landmark modeli bu durumda göz kapaklarını
 * daha az kararlı bulur → EAR titrer, yanlış alarm riski artar.
 *
 * Histogram eşitleme: Her parlaklık değerini, görüntüdeki "kendisinden koyu piksellerin oranı"na
 * (kümülatif dağılım, CDF) göre yeniden eşler. Böylece dar aralığa sıkışmış değerler 0–255
 * aralığına yayılır ve detaylar (göz kapağı kenarları) belirginleşir:
 *     yeniDeğer(v) = 255 × CDF(v)
 *
 * İki iyileştirme yaptık:
 *  1) Kontrast sınırlama (CLAHE'deki fikir): Karanlık görüntüde histogramın bir iki kutusu çok
 *     yüksek olur; bunları olduğu gibi eşitlemek sensör gürültüsünü de aşırı büyütür. Bu yüzden
 *     her kutu ortalamanın [clipLimit] katıyla sınırlanır, taşan kısım tüm kutulara eşit dağıtılır.
 *  2) Harmanlama: Sonuç, orijinal değerlerle [strength] oranında karıştırılır (doğal görünüm).
 *
 * Renkler bozulmasın diye eşleme tablosu parlaklık (Y) kanalından hesaplanır ve aynı tablo
 * R, G, B kanallarına uygulanır.
 *
 * Sınıf Android Bitmap'e bağımlı değildir (IntArray ARGB pikseller); birim testi yazılabilir.
 */
class LowLightEnhancer(
    /** Ortalama parlaklık bunun altındaysa iyileştirme uygulanır (0–255) */
    val darknessThreshold: Int = 80,
    private val clipLimit: Float = 4f,
    private val strength: Float = 0.8f,
) {

    /** Parlaklık histogramı (256 kutu) — her karede yeniden kullanılır */
    private val histogram = IntArray(256)
    private val lut = IntArray(256)

    /**
     * Ortalama parlaklık (0–255). Hız için her 4. piksel örneklenir (640x480'de ~77 bin piksel).
     * Y = 0.299·R + 0.587·G + 0.114·B  (insan gözünün yeşile daha duyarlı olmasını hesaba katar)
     */
    fun meanBrightness(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0f
        var sum = 0L
        var count = 0
        var i = 0
        while (i < pixels.size) {
            sum += luminance(pixels[i])
            count++
            i += 4
        }
        return sum.toFloat() / count
    }

    fun isDark(brightness: Float) = brightness < darknessThreshold

    /** Pikselleri yerinde (in-place) iyileştirir */
    fun enhance(pixels: IntArray) {
        if (pixels.isEmpty()) return
        buildLut(pixels)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24 and 0xFF
            val r = lut[p shr 16 and 0xFF]
            val g = lut[p shr 8 and 0xFF]
            val b = lut[p and 0xFF]
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Eşleme tablosunu (LUT) hesaplar: test için erişilebilir */
    internal fun buildLut(pixels: IntArray): IntArray {
        // 1) Parlaklık histogramı
        histogram.fill(0)
        for (p in pixels) histogram[luminance(p)]++

        // 2) Kontrast sınırlama: çok kalabalık kutuları kırp, fazlalığı eşit dağıt
        val limit = (clipLimit * pixels.size / 256f).toInt().coerceAtLeast(1)
        var excess = 0
        for (v in 0..255) {
            if (histogram[v] > limit) {
                excess += histogram[v] - limit
                histogram[v] = limit
            }
        }
        val share = excess / 256
        for (v in 0..255) histogram[v] += share

        // 3) Kümülatif dağılım → yeni değer, orijinalle harmanla
        val total = histogram.sum().toFloat()
        var cumulative = 0
        for (v in 0..255) {
            cumulative += histogram[v]
            val equalized = 255f * cumulative / total
            lut[v] = ((1 - strength) * v + strength * equalized).roundToInt().coerceIn(0, 255)
        }
        return lut
    }

    private fun luminance(p: Int): Int {
        val r = p shr 16 and 0xFF
        val g = p shr 8 and 0xFF
        val b = p and 0xFF
        // Tamsayı yaklaşımı: (77R + 150G + 29B) / 256 ≈ 0.299R + 0.587G + 0.114B
        return (77 * r + 150 * g + 29 * b) shr 8
    }
}
