package com.example.driverattention.analysis

/** Araç hızına göre sürüş durumu */
enum class SpeedBand {
    UNKNOWN,  // GPS yok / izin yok / sinyal kayıp → Faz 4 davranışı
    STOPPED,  // < 5 km/s  (kırmızı ışık, park)
    SLOW,     // 5–30 km/s
    MEDIUM,   // 30–70 km/s
    FAST,     // 70–100 km/s
    HIGHWAY,  // > 100 km/s
}

/**
 * Bir hız bandında karar motorunun ve uyarıların nasıl davranacağı.
 *
 * @param distractionMs           dikkat dağınıklığı için gereken süre; null = ayarlardaki değer
 * @param distractionAlerts       dikkat dağınıklığı uyarısı verilsin mi
 * @param microsleepAudible       mikro uyku sesli/titreşimli mi (false = sadece görsel)
 * @param nonCriticalAlerts       esneme / PERCLOS / sık esneme uyarıları verilsin mi
 *                                (verilmese de tespit edilir ve kaydedilir)
 * @param louderAlarm             kritik alarm daha yüksek sesle ve daha uzun çalsın mı
 */
data class SpeedRules(
    val band: SpeedBand,
    val distractionMs: Long?,
    val distractionAlerts: Boolean = true,
    val microsleepAudible: Boolean = true,
    val nonCriticalAlerts: Boolean = true,
    val louderAlarm: Boolean = false,
) {
    companion object {
        /** Hız bilinmiyor: hiçbir şey değişmez (Faz 4 davranışı) */
        val UNKNOWN = SpeedRules(SpeedBand.UNKNOWN, distractionMs = null)
    }
}

/**
 * Hıza duyarlı uyarı politikası (CLAUDE.md Faz 8 tablosu).
 *
 * Fikir: Aynı davranışın tehlikesi hıza bağlıdır. 120 km/s'te 1 sn yola bakmamak ~33 m'yi
 * kör geçmek demektir; kırmızı ışıkta 5 sn telefona bakmak ise tehlikeli değildir. Bu yüzden:
 *  - Araç dururken gereksiz alarm çalınmaz (sürücü alarmlardan bıkıp uygulamayı kapatmasın).
 *  - Hız arttıkça dikkat dağınıklığına tanınan süre kısalır.
 *
 * Aralık sınırları: alt sınır dahil, üst sınır hariç — [5, 30), [30, 70), [70, 100];
 * tablo "> 100" dediği için tam 100 km/s 70–100 bandında kalır.
 */
object SpeedPolicy {
    const val STOPPED_BELOW_KMH = 5f
    const val SLOW_BELOW_KMH = 30f
    const val MEDIUM_BELOW_KMH = 70f
    const val FAST_UP_TO_KMH = 100f

    fun bandFor(speedKmh: Float?): SpeedBand = when {
        speedKmh == null || speedKmh.isNaN() -> SpeedBand.UNKNOWN
        speedKmh < STOPPED_BELOW_KMH -> SpeedBand.STOPPED
        speedKmh < SLOW_BELOW_KMH -> SpeedBand.SLOW
        speedKmh < MEDIUM_BELOW_KMH -> SpeedBand.MEDIUM
        speedKmh <= FAST_UP_TO_KMH -> SpeedBand.FAST
        else -> SpeedBand.HIGHWAY
    }

    fun rulesFor(speedKmh: Float?): SpeedRules = when (val band = bandFor(speedKmh)) {
        SpeedBand.UNKNOWN -> SpeedRules.UNKNOWN
        // Duruyor: dikkat dağınıklığı yok, mikro uyku sadece görsel, diğerleri kaydedilir ama uyarılmaz
        SpeedBand.STOPPED -> SpeedRules(
            band, distractionMs = null,
            distractionAlerts = false, microsleepAudible = false, nonCriticalAlerts = false,
        )
        SpeedBand.SLOW -> SpeedRules(band, distractionMs = 3_000)
        SpeedBand.MEDIUM -> SpeedRules(band, distractionMs = 2_000)
        SpeedBand.FAST -> SpeedRules(band, distractionMs = 1_500)
        SpeedBand.HIGHWAY -> SpeedRules(band, distractionMs = 1_000, louderAlarm = true)
    }
}

/**
 * GPS hız ölçümlerini yumuşatır: son 3 ölçümün MEDYANI.
 *
 * Neden ortalama değil de medyan? GPS bazen tek bir ölçümde saçma bir sıçrama verir
 * (ör. 40, 41, 95 km/s). Ortalama bu sıçramadan etkilenir (59), medyan etkilenmez (41).
 *
 * Ölçümler [staleAfterMs] (5 sn) içinde gelmezse (tünel, kapalı otopark, GPS kapandı) hız
 * "bilinmiyor" sayılır; eski bir hıza güvenmek, araç durmuşken "sürüşte" sanmaya yol açar.
 */
class SpeedTracker(
    private val windowSize: Int = 3,
    private val staleAfterMs: Long = 5_000,
) {
    private val samples = ArrayDeque<Pair<Long, Float>>() // (zaman, km/s)

    fun add(timestampMs: Long, speedKmh: Float) {
        if (speedKmh.isNaN() || speedKmh < 0f) return
        samples.addLast(timestampMs to speedKmh)
        while (samples.size > windowSize) samples.removeFirst()
    }

    /** Güncel (yumuşatılmış) hız; veri yoksa veya bayatsa null */
    fun current(nowMs: Long): Float? {
        val newest = samples.lastOrNull() ?: return null
        if (nowMs - newest.first > staleAfterMs) return null
        val sorted = samples.map { it.second }.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    fun reset() = samples.clear()
}
