package com.example.driverattention.analysis

/**
 * Bir karede ölçülen (yumuşatılmış) yüz metrikleri.
 * Faz 4'te DriverStateEngine bu değerlere bakarak karar verecek.
 */
data class FaceMetrics(
    val leftEar: Float,
    val rightEar: Float,
    val ear: Float,          // iki gözün ortalaması
    val mar: Float,
    val headPose: HeadPose?, // matris yoksa null
    // Yumuşatılmamış EAR: göz kırpma SÜRESİNİ ölçmek için (Faz 7). 5 karelik ortalama
    // ~150 ms'lik kırpmaları yayar ve süreyi bozar; bu yüzden ham değer ayrıca tutulur.
    val rawEar: Float = ear,
)

/**
 * Ham landmark'lardan [FaceMetrics] üretir ve hareketli ortalama ile yumuşatır.
 * Durum (state) tuttuğu için her kamera akışı için tek bir örnek kullanılmalı.
 */
class FaceMetricsCalculator(windowSize: Int = 5) {
    private val leftEarAvg = MovingAverage(windowSize)
    private val rightEarAvg = MovingAverage(windowSize)
    private val marAvg = MovingAverage(windowSize)
    private val yawAvg = MovingAverage(windowSize)
    private val pitchAvg = MovingAverage(windowSize)
    private val rollAvg = MovingAverage(windowSize)

    /**
     * @param landmarks Piksel cinsinden 478 nokta
     * @param transformMatrix MediaPipe dönüşüm matrisi (column-major) veya null
     */
    fun update(landmarks: List<Point2>, transformMatrix: FloatArray?): FaceMetrics {
        val (rawLeft, rawRight) = EarCalculator.leftAndRight(landmarks)
        val left = leftEarAvg.add(rawLeft)
        val right = rightEarAvg.add(rawRight)
        val mar = marAvg.add(MarCalculator.calculate(landmarks))

        val pose = transformMatrix?.let { HeadPoseEstimator.fromTransformationMatrix(it) }?.let {
            HeadPose(yaw = yawAvg.add(it.yaw), pitch = pitchAvg.add(it.pitch), roll = rollAvg.add(it.roll))
        }

        return FaceMetrics(
            leftEar = left,
            rightEar = right,
            ear = (left + right) / 2f,
            mar = mar,
            headPose = pose,
            rawEar = (rawLeft + rawRight) / 2f,
        )
    }

    /** Yüz kaybolduğunda çağrılır */
    fun reset() {
        listOf(leftEarAvg, rightEarAvg, marAvg, yawAvg, pitchAvg, rollAvg).forEach { it.reset() }
    }
}
