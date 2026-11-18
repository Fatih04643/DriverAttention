package com.example.driverattention.analysis

/**
 * MAR (Mouth Aspect Ratio — Ağız En-Boy Oranı) hesaplayıcı.
 *
 *          |13 - 14|        (iç dudaklar arası dikey açıklık)
 *   MAR = ----------
 *         |78 - 308|        (ağız köşeleri arası yatay genişlik)
 *
 * Ağız kapalıyken MAR ≈ 0, konuşurken ≈ 0.1–0.4, esnerken genellikle 0.6'nın üzerine çıkar.
 * EAR'da olduğu gibi oran kullandığımız için yüzün kameraya uzaklığından bağımsızdır.
 */
object MarCalculator {

    fun mouthAspectRatio(upper: Point2, lower: Point2, leftCorner: Point2, rightCorner: Point2): Float {
        val width = leftCorner.distanceTo(rightCorner)
        if (width <= 1e-6f) return 0f
        return upper.distanceTo(lower) / width
    }

    /** Tüm yüz noktalarından (piksel) MAR */
    fun calculate(landmarks: List<Point2>): Float = mouthAspectRatio(
        upper = landmarks[FaceLandmarkIndices.MOUTH_UPPER_INNER],
        lower = landmarks[FaceLandmarkIndices.MOUTH_LOWER_INNER],
        leftCorner = landmarks[FaceLandmarkIndices.MOUTH_LEFT_CORNER],
        rightCorner = landmarks[FaceLandmarkIndices.MOUTH_RIGHT_CORNER],
    )
}
