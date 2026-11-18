package com.example.driverattention.analysis

import kotlin.math.sqrt

/**
 * Piksel cinsinden 2B nokta.
 *
 * Analiz sınıfları MediaPipe tiplerine bağımlı değildir; bu sayede telefon olmadan
 * (saf Kotlin) birim testleri yazılabilir.
 */
data class Point2(val x: Float, val y: Float) {

    /** İki nokta arası Öklid mesafesi: √((x1-x2)² + (y1-y2)²) */
    fun distanceTo(other: Point2): Float {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        /**
         * MediaPipe'ın normalize (0..1) koordinatını piksele çevirir.
         *
         * Neden gerekli? Normalize x genişliğe, y yüksekliğe göre ölçeklidir. Görüntü 480x640
         * gibi kare olmayan bir boyuttaysa, normalize uzayda ölçülen mesafeler yatay ve dikeyde
         * farklı ölçekte olur ve EAR/MAR oranları bozulur. Piksele çevirince iki eksen eşitlenir.
         */
        fun fromNormalized(nx: Float, ny: Float, imageWidth: Int, imageHeight: Int) =
            Point2(nx * imageWidth, ny * imageHeight)
    }
}
