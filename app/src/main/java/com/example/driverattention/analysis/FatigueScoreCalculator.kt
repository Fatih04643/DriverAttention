package com.example.driverattention.analysis

import com.example.driverattention.analysis.FatigueScoreConstants as C

/** Skora göre yorgunluk seviyesi (küçükten büyüğe sıralı) */
enum class FatigueLevel {
    NORMAL,     // 0–29  yeşil
    MILD,       // 30–59 sarı    — ekranda bilgi
    TIRED,      // 60–79 turuncu — sesli öneri
    VERY_TIRED; // 80–100 kırmızı — sesli uyarı + titreşim + mola yeri butonu

    companion object {
        fun fromScore(score: Float): FatigueLevel = when {
            score >= C.VERY_TIRED_THRESHOLD -> VERY_TIRED
            score >= C.TIRED_THRESHOLD -> TIRED
            score >= C.MILD_THRESHOLD -> MILD
            else -> NORMAL
        }
    }
}

/**
 * Skoru oluşturan bileşenler: ham ölçüm + 0..1 normalize değer.
 * Debug ekranında ve raporda "skor neden yükseldi?" sorusunu cevaplamak için.
 */
data class FatigueComponents(
    val perclos: Float,
    val longClosureCount: Int,
    val yawnCount: Int,
    val distractionCount: Int,
    val drivingMinutes: Float,
    val avgBlinkMs: Float?, // pencerede hiç kırpma yoksa null
    // normalize (0..1)
    val perclosNorm: Float,
    val longClosuresNorm: Float,
    val yawnsNorm: Float,
    val distractionsNorm: Float,
    val drivingTimeNorm: Float,
    val blinkDurationNorm: Float,
) {
    companion object {
        val ZERO = FatigueComponents(0f, 0, 0, 0, 0f, null, 0f, 0f, 0f, 0f, 0f, 0f)
    }
}

data class FatigueScore(
    val score: Float,              // yumuşatılmış skor (0–100) — ekranda gösterilen
    val rawScore: Float,           // o saniyenin ham skoru
    val level: FatigueLevel,
    val components: FatigueComponents,
    val announcement: FatigueLevel?, // bu güncellemede sesli uyarı yapılmalıysa seviyesi
    val simulated: Boolean = false,
)

/**
 * Yorgunluk skoru (0–100).
 *
 * Mikro uyku / esneme gibi anlık uyarılar "şu an" ne olduğunu söyler. Bu skor ise son
 * dakikalardaki belirtileri biriktirerek sürücünün GENEL yorgunluk eğilimini gösterir:
 * tek bir esneme skoru az yükseltir, ama sık kırpma + uzun kapanmalar + esnemeler + uzun
 * sürüş bir araya gelince skor belirgin şekilde artar.
 *
 * Her karede [update] çağrılır; skor saniyede bir hesaplanır ve üstel hareketli ortalama
 * (EMA) ile yumuşatılır:   skor ← skor + α · (ham − skor),   α = 0.05
 * α = 0.05 ve saniyede bir güncellemeyle skor ani değişimlere ~20 sn'lik gecikmeyle
 * tepki verir: tek bir kötü saniye skoru zıplatmaz, kalıcı eğilim ise 1 dk içinde yansır.
 *
 * Saf Kotlin: zaman parametre olarak gelir, birim testlerinde sahte zamanla test edilir.
 */
class FatigueScoreCalculator {

    private var startMs: Long? = null
    private var lastComputeMs: Long? = null
    private var smoothed = 0f
    private var last: FatigueScore = FatigueScore(0f, 0f, FatigueLevel.NORMAL, FatigueComponents.ZERO, null)

    // Göz kapanma bölümleri (episode) takibi
    private var closedSince: Long? = null
    private val longClosures = ArrayDeque<Long>()             // bitiş zamanları
    private val blinks = ArrayDeque<Pair<Long, Long>>()       // (bitiş zamanı, süre ms)

    // Olay sayımı: koşulun "başladığı an" (yükselen kenar) bir olaydır
    private val yawns = ArrayDeque<Long>()
    private val distractions = ArrayDeque<Long>()
    private var wasYawning = false
    private var wasDistracted = false

    // Sesli uyarı politikası
    private var lastAnnounceMs: Long? = null
    private var lastAnnouncedLevel: FatigueLevel? = null

    /**
     * @param eyesClosed         bu karede göz kapalı mı (ham EAR < eşik); yüz yoksa null
     * @param perclos            karar motorunun PERCLOS değeri (0..1)
     * @param yawnActive         karar motorunda YAWN koşulu etkin mi
     * @param distractionActive  karar motorunda DISTRACTION koşulu etkin mi
     * @param overrideScore      debug simülasyonu: skoru bu değere sabitler
     */
    fun update(
        timestampMs: Long,
        eyesClosed: Boolean?,
        perclos: Float,
        yawnActive: Boolean,
        distractionActive: Boolean,
        overrideScore: Float? = null,
    ): FatigueScore {
        val t = timestampMs
        if (startMs == null) startMs = t

        trackEyeClosure(t, eyesClosed)
        if (yawnActive && !wasYawning) yawns.addLast(t)
        if (distractionActive && !wasDistracted) distractions.addLast(t)
        wasYawning = yawnActive
        wasDistracted = distractionActive

        // Skor saniyede bir hesaplanır (her karede hesaplamak gereksiz)
        val lastCompute = lastComputeMs
        if (lastCompute != null && t - lastCompute < C.UPDATE_INTERVAL_MS) {
            return last.copy(announcement = null)
        }
        lastComputeMs = t

        dropOld(t)
        val components = computeComponents(t, perclos)
        val raw = 100f * (
            C.WEIGHT_PERCLOS * components.perclosNorm +
                C.WEIGHT_LONG_CLOSURES * components.longClosuresNorm +
                C.WEIGHT_YAWNS * components.yawnsNorm +
                C.WEIGHT_DISTRACTIONS * components.distractionsNorm +
                C.WEIGHT_DRIVING_TIME * components.drivingTimeNorm +
                C.WEIGHT_BLINK_DURATION * components.blinkDurationNorm
            )

        smoothed = if (overrideScore != null) {
            overrideScore.coerceIn(0f, 100f)
        } else {
            smoothed + C.EMA_ALPHA * (raw - smoothed)
        }

        val level = FatigueLevel.fromScore(smoothed)
        last = FatigueScore(
            score = smoothed,
            rawScore = raw,
            level = level,
            components = components,
            announcement = decideAnnouncement(t, level),
            simulated = overrideScore != null,
        )
        return last
    }

    /** Göz kapanma bölümlerini ölçer: açılınca süreye göre "kırpma" ya da "uzun kapanma" */
    private fun trackEyeClosure(t: Long, eyesClosed: Boolean?) {
        when (eyesClosed) {
            null -> closedSince = null // yüz kayboldu: yarım kalan bölüm ölçülemez, atla
            true -> if (closedSince == null) closedSince = t
            false -> closedSince?.let { since ->
                val duration = t - since
                if (duration > C.LONG_CLOSURE_MIN_MS) {
                    longClosures.addLast(t)
                } else if (duration > 0) {
                    blinks.addLast(t to duration)
                }
                closedSince = null
            }
        }
    }

    private fun dropOld(t: Long) {
        while (longClosures.isNotEmpty() && t - longClosures.first() > C.LONG_CLOSURE_WINDOW_MS) longClosures.removeFirst()
        while (blinks.isNotEmpty() && t - blinks.first().first > C.BLINK_WINDOW_MS) blinks.removeFirst()
        while (yawns.isNotEmpty() && t - yawns.first() > C.YAWN_WINDOW_MS) yawns.removeFirst()
        while (distractions.isNotEmpty() && t - distractions.first() > C.DISTRACTION_WINDOW_MS) distractions.removeFirst()
    }

    private fun computeComponents(t: Long, perclos: Float): FatigueComponents {
        val drivingMinutes = (t - (startMs ?: t)) / 60_000f
        val avgBlink = if (blinks.isEmpty()) null else blinks.map { it.second }.average().toFloat()
        return FatigueComponents(
            perclos = perclos,
            longClosureCount = longClosures.size,
            yawnCount = yawns.size,
            distractionCount = distractions.size,
            drivingMinutes = drivingMinutes,
            avgBlinkMs = avgBlink,
            perclosNorm = (perclos / C.PERCLOS_FULL).coerceIn(0f, 1f),
            longClosuresNorm = (longClosures.size / C.LONG_CLOSURES_FULL).coerceAtMost(1f),
            yawnsNorm = (yawns.size / C.YAWNS_FULL).coerceAtMost(1f),
            distractionsNorm = (distractions.size / C.DISTRACTIONS_FULL).coerceAtMost(1f),
            drivingTimeNorm = (drivingMinutes / C.DRIVING_FULL_MINUTES).coerceIn(0f, 1f),
            blinkDurationNorm = avgBlink?.let { ((it - C.BLINK_BASELINE_MS) / C.BLINK_RANGE_MS).coerceIn(0f, 1f) } ?: 0f,
        )
    }

    /**
     * Sesli uyarı politikası:
     *  - Sadece TIRED (60+) ve VERY_TIRED (80+) seviyelerinde konuşulur.
     *  - Aynı ya da daha düşük seviye için 10 dk'da en fazla 1 kez (sürücüyü bıktırmamak için).
     *  - Ama seviye YÜKSELİRSE (60 → 80) beklemeden hemen uyarılır: durum kötüleşiyor.
     */
    private fun decideAnnouncement(t: Long, level: FatigueLevel): FatigueLevel? {
        if (level < FatigueLevel.TIRED) return null
        val lastTime = lastAnnounceMs
        val lastLevel = lastAnnouncedLevel
        val cooldownOver = lastTime == null || t - lastTime >= C.ANNOUNCE_COOLDOWN_MS
        val escalated = lastLevel != null && level > lastLevel && lastTime != null &&
            t - lastTime < C.ANNOUNCE_COOLDOWN_MS
        if (!cooldownOver && !escalated) return null
        lastAnnounceMs = t
        lastAnnouncedLevel = level
        return level
    }
}
