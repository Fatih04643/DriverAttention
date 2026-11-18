package com.example.driverattention.analysis

/**
 * PERCLOS (PERcentage of eyelid CLOSure) hesaplayıcı.
 *
 * Tanım: Son [windowMs] (varsayılan 60 sn) içinde gözlerin kapalı olduğu karelerin yüzdesi.
 * Literatürde yorgunluğun en güvenilir göstergelerinden biridir: yorgun sürücü daha sık ve
 * daha uzun göz kırpar, bu yüzden kapalı kare oranı artar.
 *
 * Uygulama: Zaman damgalı bir kayan pencere (kuyruk). Her yeni kare sona eklenir, pencerenin
 * dışına düşen eski kareler baştan atılır. Kapalı kare sayısı ayrıca tutulduğu için her
 * kare O(1) maliyetle güncellenir (her seferinde tüm pencereyi saymaya gerek yok).
 */
class PerclosTracker(private val windowMs: Long = 60_000) {

    private class Sample(val timestampMs: Long, val closed: Boolean)

    private val samples = ArrayDeque<Sample>()
    private var closedCount = 0

    fun add(timestampMs: Long, eyesClosed: Boolean) {
        samples.addLast(Sample(timestampMs, eyesClosed))
        if (eyesClosed) closedCount++

        // Pencerenin dışında kalan (çok eski) kareleri at
        while (samples.isNotEmpty() && samples.first().timestampMs < timestampMs - windowMs) {
            if (samples.removeFirst().closed) closedCount--
        }
    }

    /** 0..1 arası oran (ör. 0.15 = %15) */
    val perclos: Float
        get() = if (samples.isEmpty()) 0f else closedCount.toFloat() / samples.size

    /**
     * Pencerede ne kadar sürelik veri var. Uygulama yeni açıldığında birkaç saniyelik veriyle
     * hesaplanan PERCLOS yanıltıcı olur (tek bir göz kırpma %20 gösterebilir); bu yüzden karar
     * motoru yeterli veri birikmeden PERCLOS uyarısı vermez.
     */
    val coveredMs: Long
        get() = if (samples.size < 2) 0 else samples.last().timestampMs - samples.first().timestampMs

    fun reset() {
        samples.clear()
        closedCount = 0
    }
}
