package com.example.driverattention.analysis

/**
 * EAR (Eye Aspect Ratio — Göz En-Boy Oranı) hesaplayıcı.
 *
 * Formül (Soukupová & Čech, 2016):
 *
 *          |p2 - p6| + |p3 - p5|
 *   EAR = -----------------------
 *              2 * |p1 - p4|
 *
 * Pay: gözün iki noktadan ölçülen dikey açıklığı (üst kapak ↔ alt kapak).
 * Payda: gözün yatay genişliği (köşeden köşeye), 2 ile çarpılır çünkü payda iki mesafe var.
 *
 * Neden oran? Yüz kameraya yaklaşıp uzaklaştıkça piksel mesafeleri değişir ama oran
 * değişmez. Göz açıkken EAR ≈ 0.25–0.35, kapanınca 0'a yaklaşır (≈ 0.05–0.1).
 */
object EarCalculator {

    /** Tek bir göz için EAR. [eye] = [p1, p2, p3, p4, p5, p6] (piksel) */
    fun eyeAspectRatio(eye: List<Point2>): Float {
        require(eye.size == 6) { "EAR için 6 nokta gerekli" }
        val (p1, p2, p3, p4, p5, p6) = eye
        val vertical1 = p2.distanceTo(p6)
        val vertical2 = p3.distanceTo(p5)
        val horizontal = p1.distanceTo(p4)
        if (horizontal <= 1e-6f) return 0f // Bozuk veri: sıfıra bölmeyi önle
        return (vertical1 + vertical2) / (2f * horizontal)
    }

    /** Tüm yüz noktalarından (piksel) sol ve sağ göz EAR'ını hesaplar */
    fun leftAndRight(landmarks: List<Point2>): Pair<Float, Float> {
        val left = eyeAspectRatio(FaceLandmarkIndices.LEFT_EYE.map { landmarks[it] })
        val right = eyeAspectRatio(FaceLandmarkIndices.RIGHT_EYE.map { landmarks[it] })
        return left to right
    }

    /**
     * İki gözün ortalama EAR'ı. Ortalama almak, tek gözün kısa süreli yanlış ölçümünün
     * (ör. ışık yansıması) etkisini azaltır.
     */
    fun average(landmarks: List<Point2>): Float {
        val (left, right) = leftAndRight(landmarks)
        return (left + right) / 2f
    }
}

// List için 6. bileşene kadar destructuring (Kotlin standart kütüphanesi 5'e kadar sağlar)
private operator fun <T> List<T>.component6(): T = this[5]
