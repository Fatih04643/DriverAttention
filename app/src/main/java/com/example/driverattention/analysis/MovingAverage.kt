package com.example.driverattention.analysis

/**
 * Basit hareketli ortalama (son N değer).
 *
 * Landmark noktaları kareden kareye birkaç piksel titrer; bu da EAR/MAR/açı değerlerinde
 * gürültü yaratır. Son ~5 karenin ortalamasını almak gürültüyü azaltır, ama pencere çok
 * büyük olursa göz kırpma gibi hızlı olaylar geç algılanır. 5 kare ≈ 25 FPS'te 0.2 sn.
 */
class MovingAverage(private val windowSize: Int = 5) {
    init {
        require(windowSize > 0)
    }

    private val values = FloatArray(windowSize) // halka tampon (ring buffer)
    private var count = 0
    private var nextIndex = 0
    private var sum = 0f

    /** Yeni değeri ekler ve güncel ortalamayı döndürür */
    fun add(value: Float): Float {
        if (count == windowSize) {
            sum -= values[nextIndex] // en eski değeri çıkar
        } else {
            count++
        }
        values[nextIndex] = value
        sum += value
        nextIndex = (nextIndex + 1) % windowSize
        return sum / count
    }

    /** Yüz kaybolunca eski değerler yeni yüzü etkilemesin diye sıfırlanır */
    fun reset() {
        count = 0
        nextIndex = 0
        sum = 0f
    }
}
