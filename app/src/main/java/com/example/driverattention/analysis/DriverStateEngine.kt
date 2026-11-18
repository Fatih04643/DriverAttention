package com.example.driverattention.analysis

import kotlin.math.abs

/** Sürücünün o anki durumu (ekranda gösterilir) */
data class DriverStatus(
    val level: AlertLevel,
    val activeConditions: Set<AlertType>, // uyarı verilen (ekranda gösterilen) koşullar
    val perclos: Float,
    val eyesClosedMs: Long,
    val recentYawnCount: Int,
    // Tespit edilen TÜM koşullar. Araç dururken bazı koşullar tespit edilir ama uyarılmaz
    // (Faz 8); bunlar burada kalır → CSV'ye ve yorgunluk skoruna yine girer.
    val detectedConditions: Set<AlertType> = activeConditions,
    val speedBand: SpeedBand = SpeedBand.UNKNOWN,
)

/** Her kare için motorun çıktısı: anlık durum + bu karede yeni tetiklenen uyarılar */
data class EngineOutput(
    val status: DriverStatus,
    val newAlerts: List<AlertEvent>,
)

/**
 * Karar motoru: her karenin (yumuşatılmış) metriklerine bakarak sürücü durumuna karar verir.
 *
 * Temel fikir "süre koşulu": tek bir karede gözün kapalı görünmesi göz kırpmadır, tehlike
 * değildir. Bu yüzden her koşul için "ne zamandan beri sürüyor?" zaman damgası tutulur ve
 * koşul belirlenen süreyi aştığında uyarı verilir:
 *   - Mikro uyku      : EAR < eşik, ≥ 1.5 sn kesintisiz            → KRİTİK
 *   - Yorgunluk       : PERCLOS ≥ %15 (60 sn pencere)              → UYARI
 *   - Esneme          : MAR > 0.6, ≥ 1 sn; 5 dk'da ≥ 3 → yorgunluk → UYARI
 *   - Dikkat dağınıklığı: |yaw| > 30° veya pitch < -20°, ≥ 2 sn    → UYARI
 *   - Yüz yok         : ≥ 3 sn                                     → BİLGİ
 *
 * Hıza duyarlılık (Faz 8): [update]'e verilen [SpeedRules] dikkat dağınıklığı süresini
 * değiştirir ve araç dururken bazı uyarıları susturur. Koşullar yine TESPİT edilir; sadece
 * uyarıya dönüşmez. Hız bilinmiyorsa (SpeedRules.UNKNOWN) davranış Faz 4 ile aynıdır.
 *
 * Aynı uyarının art arda çalmasını önlemek için her uyarı türünün son çalma zamanı tutulur
 * (cooldown). Zaman, kameranın kare zaman damgasından gelir; böylece sınıf saf Kotlin'dir ve
 * birim testlerinde sahte zamanla test edilebilir.
 */
class DriverStateEngine(config: DetectionConfig = DetectionConfig()) {

    var config: DetectionConfig = config
        set(value) {
            field = value
            reset()
        }

    private var perclosTracker = PerclosTracker(config.perclosWindowMs)

    // "Ne zamandan beri?" zaman damgaları (null = koşul şu an yok)
    private var eyesClosedSince: Long? = null
    private var mouthOpenSince: Long? = null
    private var distractedSince: Long? = null
    private var noFaceSince: Long? = null

    private var currentYawnCounted = false     // aynı esneme iki kez sayılmasın
    private val yawnTimes = ArrayDeque<Long>() // son 5 dk'daki esnemeler
    private val lastAlertAt = mutableMapOf<AlertType, Long>()

    /**
     * @param timestampMs Karenin zamanı (ms)
     * @param metrics     Yüz metrikleri; yüz bulunamadıysa null
     * @param speedRules  Araç hızına göre kurallar (bkz. SpeedPolicy)
     */
    fun update(
        timestampMs: Long,
        metrics: FaceMetrics?,
        speedRules: SpeedRules = SpeedRules.UNKNOWN,
    ): EngineOutput {
        val t = timestampMs
        val active = mutableSetOf<AlertType>()
        var eyesClosedMs = 0L

        // Pencere dışına çıkmış eski esnemeleri at
        while (yawnTimes.isNotEmpty() && t - yawnTimes.first() > config.yawnCountWindowMs) {
            yawnTimes.removeFirst()
        }

        if (metrics == null) {
            // ---- Yüz yok ----
            if (noFaceSince == null) noFaceSince = t
            if (t - noFaceSince!! >= config.noFaceMs) active += AlertType.NO_FACE
            // Yüz yokken göz/ağız/baş süreleri anlamsız: sıfırla
            eyesClosedSince = null
            mouthOpenSince = null
            currentYawnCounted = false
            distractedSince = null
        } else {
            noFaceSince = null

            // ---- 1) Mikro uyku: gözler kesintisiz kapalı mı? ----
            val eyesClosed = metrics.ear < config.earThreshold
            perclosTracker.add(t, eyesClosed)
            if (eyesClosed) {
                if (eyesClosedSince == null) eyesClosedSince = t
                eyesClosedMs = t - eyesClosedSince!!
                if (eyesClosedMs >= config.microSleepMs) active += AlertType.MICROSLEEP
            } else {
                eyesClosedSince = null // göz açıldı: sayaç baştan başlar
            }

            // ---- 2) PERCLOS: son 60 sn'de kapalı kare oranı ----
            if (perclosTracker.coveredMs >= config.perclosMinCoverageMs &&
                perclosTracker.perclos >= config.perclosThreshold
            ) {
                active += AlertType.FATIGUE_PERCLOS
            }

            // ---- 3) Esneme: ağız yeterince uzun süre açık mı? ----
            if (metrics.mar > config.marThreshold) {
                if (mouthOpenSince == null) mouthOpenSince = t
                if (!currentYawnCounted && t - mouthOpenSince!! >= config.yawnMinMs) {
                    currentYawnCounted = true
                    yawnTimes.addLast(t)
                }
                if (currentYawnCounted) active += AlertType.YAWN
            } else {
                mouthOpenSince = null
                currentYawnCounted = false
            }

            // ---- 4) Dikkat dağınıklığı: baş yoldan başka yöne mi dönük? ----
            // Açılar kalibrasyonda ölçülen düz bakış açısına göre (fark olarak) değerlendirilir
            val pose = metrics.headPose
            val lookingAway = pose != null && (
                abs(pose.yaw - config.baselineYawDeg) > config.yawLimitDeg ||
                    pose.pitch - config.baselinePitchDeg < config.pitchDownLimitDeg
                )
            if (lookingAway) {
                if (distractedSince == null) distractedSince = t
                // Hız biliniyorsa süre hıza göre (yüksek hızda daha kısa), yoksa ayarlardaki değer
                val requiredMs = speedRules.distractionMs ?: config.distractionMs
                if (t - distractedSince!! >= requiredMs) active += AlertType.DISTRACTION
            } else {
                distractedSince = null
            }
        }

        // ---- 5) Sık esneme → yorgunluk ----
        if (yawnTimes.size >= config.yawnsForFatigue) active += AlertType.FATIGUE_YAWNING

        // ---- Hız kuralları: hangi koşullar uyarıya dönüşür? ----
        val alertable = active.filterTo(mutableSetOf()) { type ->
            when (type) {
                AlertType.DISTRACTION -> speedRules.distractionAlerts
                AlertType.YAWN, AlertType.FATIGUE_YAWNING, AlertType.FATIGUE_PERCLOS -> speedRules.nonCriticalAlerts
                AlertType.MICROSLEEP, AlertType.NO_FACE -> true // mikro uyku dururken de en az görsel
            }
        }

        // ---- Uyarı tetikleme (cooldown kontrolü) ----
        val newAlerts = alertable
            .filter { type ->
                val last = lastAlertAt[type]
                last == null || t - last >= cooldownFor(type)
            }
            .toMutableList()
        newAlerts.forEach { lastAlertAt[it] = t }
        // Üçüncü esneme anında hem "esneme" hem "sık esneme" tetiklenirse sadece ikincisini çal.
        // YAWN'ın zamanı yukarıda yine de kaydedildi; böylece sonraki karede tekrar tetiklenmez.
        if (AlertType.FATIGUE_YAWNING in newAlerts) newAlerts.remove(AlertType.YAWN)

        val status = DriverStatus(
            level = alertable.maxOfOrNull { it.level } ?: AlertLevel.NONE,
            activeConditions = alertable,
            perclos = perclosTracker.perclos,
            eyesClosedMs = eyesClosedMs,
            recentYawnCount = yawnTimes.size,
            detectedConditions = active,
            speedBand = speedRules.band,
        )
        return EngineOutput(
            status = status,
            newAlerts = newAlerts
                .sortedByDescending { it.level } // en önemli uyarı önce
                .map { type ->
                    // Araç dururken mikro uyku: kaydedilir ve ekranda gösterilir ama ses/titreşim yok
                    val audible = !(type == AlertType.MICROSLEEP && !speedRules.microsleepAudible)
                    AlertEvent(type, t, audible)
                },
        )
    }

    private fun cooldownFor(type: AlertType): Long = when (type) {
        AlertType.FATIGUE_PERCLOS, AlertType.FATIGUE_YAWNING -> config.fatigueRepeatMs
        else -> config.cooldownMs
    }

    /** İzleme yeniden başlatıldığında tüm geçmişi temizler */
    fun reset() {
        perclosTracker = PerclosTracker(config.perclosWindowMs)
        eyesClosedSince = null
        mouthOpenSince = null
        distractedSince = null
        noFaceSince = null
        currentYawnCounted = false
        yawnTimes.clear()
        lastAlertAt.clear()
    }
}
