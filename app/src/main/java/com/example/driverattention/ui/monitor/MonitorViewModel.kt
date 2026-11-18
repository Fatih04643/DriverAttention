package com.example.driverattention.ui.monitor

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.driverattention.alert.AlertManager
import com.example.driverattention.analysis.CalibrationOutcome
import com.example.driverattention.analysis.CalibrationResult
import com.example.driverattention.analysis.Calibrator
import com.example.driverattention.analysis.DetectionConfig
import com.example.driverattention.analysis.DriverStateEngine
import com.example.driverattention.analysis.DriverStatus
import com.example.driverattention.analysis.FaceMetrics
import com.example.driverattention.analysis.FaceMetricsCalculator
import com.example.driverattention.analysis.AlertType
import com.example.driverattention.analysis.FatigueScore
import com.example.driverattention.analysis.FatigueScoreCalculator
import com.example.driverattention.analysis.FatigueScoreConstants
import com.example.driverattention.analysis.SpeedPolicy
import com.example.driverattention.analysis.SpeedRules
import com.example.driverattention.location.SpeedProvider
import com.example.driverattention.location.SpeedReading
import com.example.driverattention.location.SpeedSource
import com.example.driverattention.analysis.Point2
import com.example.driverattention.camera.FrameAnalyzer
import com.example.driverattention.camera.FrameInfo
import com.example.driverattention.data.AppSettings
import com.example.driverattention.data.MetricsCsvLogger
import com.example.driverattention.data.SessionRepository
import com.example.driverattention.data.SettingsRepository
import com.example.driverattention.detection.FaceFrame
import com.example.driverattention.detection.FaceLandmarkerHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil

/** Uygulamanın çalışma modu */
enum class MonitorMode { CALIBRATING, MONITORING, STOPPED }

/** Kalibrasyon ekranının o anki adımı */
sealed interface CalibrationStep {
    data class Countdown(val secondsLeft: Int) : CalibrationStep
    data class Measuring(val progress: Float) : CalibrationStep
    data class Failed(val reason: String) : CalibrationStep
}

/** Monitor ekranının tüm durumu (UI bunu gözlemler) */
data class MonitorUiState(
    val mode: MonitorMode = MonitorMode.CALIBRATING,
    val calibrationStep: CalibrationStep? = CalibrationStep.Countdown(3),
    val calibration: CalibrationResult? = null, // null = varsayılan eşik kullanılıyor
    val earThreshold: Float = DetectionConfig.DEFAULT_EAR_THRESHOLD,
    val status: DriverStatus? = null,           // sadece izleme sırasında dolu
    val face: FaceFrame? = null,
    val metrics: FaceMetrics? = null,           // yüz yoksa null
    val fps: Float = 0f,
    val processingTimeMs: Long = 0,
    val delegate: String = "-",
    val showOverlay: Boolean = true,
    val debugMode: Boolean = true,
    val brightness: Float = 0f,          // kamera karesinin ortalama parlaklığı
    val lowLightActive: Boolean = false, // bu karede histogram eşitleme uygulandı mı
    val preprocessMs: Long = 0,
    val fatigue: FatigueScore? = null, // sadece izleme sırasında dolu (Faz 7)
    val speed: SpeedReading = SpeedReading.NONE, // Faz 8: anlık hız ve kaynağı
    val speedRules: SpeedRules = SpeedRules.UNKNOWN,
    val locationPermission: Boolean = false,
    val csvRecording: Boolean = false,
    val error: String? = null,
)

/**
 * Monitor ekranının ViewModel'i (MVVM).
 *
 * Akış:  Kalibrasyon (3 sn geri sayım + 5 sn ölçüm) → İzleme → (Durdur / Başlat)
 *
 * Her kare için: landmark → metrikler (EAR/MAR/poz) → [kalibrasyon örneği | karar motoru]
 * → uyarılar (AlertManager + veritabanı) → StateFlow ile arayüze.
 * Her "izleme" dönemi veritabanında bir sürüş oturumu olarak kaydedilir.
 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    /** Tüm görüntü işleme bu tek arka plan thread'inde yapılır (ana thread bloklanmaz) */
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var helper: FaceLandmarkerHelper? = null
    private val metricsCalculator = FaceMetricsCalculator(windowSize = 5)
    private val engine = DriverStateEngine()
    private var fatigueCalculator = FatigueScoreCalculator()
    private var lastScoreSaveMs: Long? = null
    private val alertManager = AlertManager(app)
    private val speedProvider = SpeedProvider(app)
    private val sessionRepository = SessionRepository.get(app)
    private val settingsRepository = SettingsRepository(app)

    // Kare thread'i ile ana thread (buton tıklamaları) aynı alanlara eriştiği için kilit
    private val lock = Any()
    private var mode = MonitorMode.CALIBRATING
    private var settings = AppSettings()
    private var calibration: CalibrationResult? = null
    private var calibrationStartMs: Long? = null   // ilk karede belirlenir
    private var calibrationFailed = false
    private val calibrationSamples = mutableListOf<FaceMetrics>()
    private var csvLogger: MetricsCsvLogger? = null

    // --- FPS ölçümü: 1 sn'lik pencerede gelen sonuç sayısı ---
    private var fpsFrameCount = 0
    private var fpsWindowStartMs = SystemClock.uptimeMillis()
    private var lastFps = 0f

    // Analiz thread'inden okunan ayar ve son kare bilgisi
    @Volatile private var lowLightEnabled = true
    @Volatile private var lastFrameInfo = FrameInfo(0f, false, 0)

    init {
        // Model, kareleri işleyecek aynı thread'de yüklenir (GPU delegate gereksinimi)
        analysisExecutor.execute {
            helper = FaceLandmarkerHelper(
                context = getApplication(),
                onResult = ::onFaceResult,
                onError = { msg -> _uiState.update { it.copy(error = msg) } },
            ).also { h -> _uiState.update { it.copy(delegate = h.activeDelegate) } }
        }

        // Ayarlar değiştikçe (Ayarlar ekranı) eşikleri ve uyarı seçeneklerini uygula
        viewModelScope.launch {
            settingsRepository.settings.collect { applySettings(it) }
        }
    }

    /** CameraX'e verilecek analizör: her kareyi MediaPipe'a yollar */
    val frameAnalyzer = FrameAnalyzer(
        isLowLightEnabled = { lowLightEnabled },
    ) { bitmap, frameTimeMs, info ->
        lastFrameInfo = info
        helper?.detectAsync(bitmap, frameTimeMs)
    }

    private fun applySettings(newSettings: AppSettings) = synchronized(lock) {
        val thresholdsChanged = newSettings.toDetectionConfig(calibration) != settings.toDetectionConfig(calibration)
        settings = newSettings
        lowLightEnabled = newSettings.lowLightEnhancement
        alertManager.soundEnabled = newSettings.soundEnabled
        alertManager.vibrationEnabled = newSettings.vibrationEnabled
        alertManager.voiceEnabled = newSettings.voiceEnabled
        if (thresholdsChanged) engine.config = newSettings.toDetectionConfig(calibration)
        _uiState.update {
            it.copy(
                showOverlay = newSettings.showOverlay,
                debugMode = newSettings.debugMode,
                earThreshold = engine.config.earThreshold,
            )
        }
    }

    // ---------------------------------------------------------------------
    // Kare işleme (MediaPipe sonuç thread'i)
    // ---------------------------------------------------------------------

    private fun onFaceResult(frame: FaceFrame) {
        val metrics = if (frame.hasFace) {
            val points = frame.landmarks.map {
                Point2.fromNormalized(it.x(), it.y(), frame.imageWidth, frame.imageHeight)
            }
            metricsCalculator.update(points, frame.transformMatrix)
        } else {
            metricsCalculator.reset() // yüz kaybolunca eski değerler yeni yüzü etkilemesin
            null
        }

        measureFps()

        synchronized(lock) {
            when (mode) {
                MonitorMode.CALIBRATING -> processCalibration(frame.timestampMs, metrics)
                MonitorMode.MONITORING -> {
                    // ---- Hız (Faz 8): simülasyon açıksa elle verilen hız, değilse GPS ----
                    val speed = if (settings.simulateSpeed) {
                        SpeedReading(settings.simulatedSpeedKmh, SpeedSource.SIMULATED)
                    } else {
                        speedProvider.current(frame.timestampMs)
                    }
                    val speedRules = SpeedPolicy.rulesFor(speed.kmh)

                    val output = engine.update(frame.timestampMs, metrics, speedRules)
                    if (output.newAlerts.isNotEmpty()) {
                        alertManager.onAlerts(output.newAlerts, louder = speedRules.louderAlarm)
                        sessionRepository.recordAlerts(output.newAlerts)
                    }

                    // ---- Yorgunluk skoru (Faz 7) ----
                    // Kırpma süresi için yumuşatılmamış EAR kullanılır (bkz. FaceMetrics.rawEar)
                    val fatigue = fatigueCalculator.update(
                        timestampMs = frame.timestampMs,
                        eyesClosed = metrics?.let { it.rawEar < engine.config.earThreshold },
                        perclos = output.status.perclos,
                        // Araç dururken esneme uyarılmaz ama skora yine sayılır ("kaydedilir")
                        yawnActive = AlertType.YAWN in output.status.detectedConditions,
                        distractionActive = AlertType.DISTRACTION in output.status.activeConditions,
                        overrideScore = if (settings.simulateFatigue) settings.simulatedFatigueScore else null,
                    )
                    fatigue.announcement?.let { alertManager.announceFatigue(it) }
                    val lastSave = lastScoreSaveMs
                    if (lastSave == null || frame.timestampMs - lastSave >= FatigueScoreConstants.SAVE_INTERVAL_MS) {
                        sessionRepository.recordScore(fatigue.score)
                        lastScoreSaveMs = frame.timestampMs
                    }

                    val info = lastFrameInfo
                    csvLogger?.log(
                        frame.timestampMs, metrics, output.status, lastFps, frame.processingTimeMs,
                        info.brightness, info.enhanced, fatigue.score,
                        speed.kmh, speed.source.csvName,
                    )
                    _uiState.update {
                        it.copy(status = output.status, fatigue = fatigue, speed = speed, speedRules = speedRules)
                    }
                }
                MonitorMode.STOPPED -> Unit
            }
        }

        _uiState.update { state ->
            state.copy(
                face = frame,
                metrics = metrics,
                processingTimeMs = frame.processingTimeMs,
                fps = lastFps,
                brightness = lastFrameInfo.brightness,
                lowLightActive = lastFrameInfo.enhanced,
                preprocessMs = lastFrameInfo.preprocessMs,
            )
        }
    }

    private fun measureFps() {
        fpsFrameCount++
        val now = SystemClock.uptimeMillis()
        val elapsed = now - fpsWindowStartMs
        if (elapsed < 1000) return
        lastFps = fpsFrameCount * 1000f / elapsed
        fpsFrameCount = 0
        fpsWindowStartMs = now
    }

    /**
     * Kalibrasyon zamanlaması (kare zaman damgalarıyla):
     *   0–3 sn : geri sayım (kullanıcı pozisyon alır, ölçüm yok)
     *   3–8 sn : ölçüm (yüz görülen her karenin metrikleri toplanır)
     *   8. sn  : sonuç hesaplanır → başarılıysa izleme otomatik başlar
     */
    private fun processCalibration(t: Long, metrics: FaceMetrics?) {
        if (calibrationFailed) return
        val start = calibrationStartMs ?: t.also { calibrationStartMs = it }
        val elapsed = t - start

        when {
            elapsed < COUNTDOWN_MS -> {
                val secondsLeft = ceil((COUNTDOWN_MS - elapsed) / 1000.0).toInt()
                _uiState.update { it.copy(calibrationStep = CalibrationStep.Countdown(secondsLeft)) }
            }
            elapsed < COUNTDOWN_MS + MEASURE_MS -> {
                metrics?.let { calibrationSamples += it }
                val progress = (elapsed - COUNTDOWN_MS).toFloat() / MEASURE_MS
                _uiState.update { it.copy(calibrationStep = CalibrationStep.Measuring(progress)) }
            }
            else -> when (val outcome = Calibrator.compute(calibrationSamples, settings.earCalibrationFactor)) {
                is CalibrationOutcome.Success -> {
                    Log.i("Calibration", "Başarılı: ${outcome.result}")
                    beginMonitoring(outcome.result)
                }
                is CalibrationOutcome.Failure -> {
                    calibrationFailed = true
                    _uiState.update { it.copy(calibrationStep = CalibrationStep.Failed(outcome.reason)) }
                }
            }
        }
    }

    /** İzlemeyi başlatır ve yeni bir sürüş oturumu açar (lock içinde çağrılmalı) */
    private fun beginMonitoring(newCalibration: CalibrationResult?) {
        calibration = newCalibration
        engine.config = settings.toDetectionConfig(newCalibration) // motoru da sıfırlar
        // Yeni oturum = yeni sürüş: skor ve "kesintisiz sürüş süresi" sıfırdan başlar (mola verildi)
        fatigueCalculator = FatigueScoreCalculator()
        lastScoreSaveMs = null
        mode = MonitorMode.MONITORING

        sessionRepository.startSession(engine.config.earThreshold, calibrated = newCalibration != null)
        speedProvider.start() // konum izni yoksa hiçbir şey yapmaz
        csvLogger = if (settings.debugMode) MetricsCsvLogger.create(getApplication()) else null

        _uiState.update {
            it.copy(
                mode = mode,
                calibrationStep = null,
                calibration = newCalibration,
                earThreshold = engine.config.earThreshold,
                status = null,
                fatigue = null,
                csvRecording = csvLogger != null,
            )
        }
    }

    /** Açık oturumu kapatır (lock içinde çağrılmalı) */
    private fun endSessionIfMonitoring() {
        if (mode != MonitorMode.MONITORING) return
        alertManager.stop()
        speedProvider.stop() // izleme yokken GPS'i kapat (pil)
        val path = csvLogger?.let { it.close(); it.file.absolutePath }
        csvLogger = null
        sessionRepository.finishSession(path)
        _uiState.update { it.copy(csvRecording = false) }
    }

    // ---------------------------------------------------------------------
    // Kullanıcı eylemleri (ana thread)
    // ---------------------------------------------------------------------

    /** Kalibrasyonu (yeniden) başlatır */
    /** Kalibrasyon sürerken araya bir izin penceresi girdiyse ölçümü baştan al */
    fun restartCalibrationIfInProgress() {
        if (synchronized(lock) { mode == MonitorMode.CALIBRATING }) startCalibration()
    }

    fun startCalibration() = synchronized(lock) {
        endSessionIfMonitoring()
        mode = MonitorMode.CALIBRATING
        calibrationStartMs = null
        calibrationFailed = false
        calibrationSamples.clear()
        _uiState.update {
            it.copy(mode = mode, calibrationStep = CalibrationStep.Countdown(3), status = null, fatigue = null)
        }
    }

    /** Kalibrasyonu atla: varsayılan eşik (ayarlardaki EAR eşiği, açı referansı 0°) */
    fun skipCalibration() = synchronized(lock) {
        beginMonitoring(newCalibration = null)
    }

    /** "Başlat": son kalibrasyonla yeni oturum */
    fun startMonitoring() = synchronized(lock) {
        if (mode == MonitorMode.STOPPED) beginMonitoring(calibration)
    }

    fun stopMonitoring() = synchronized(lock) {
        endSessionIfMonitoring()
        mode = MonitorMode.STOPPED
        _uiState.update { it.copy(mode = mode, status = null, fatigue = null, speed = SpeedReading.NONE) }
    }

    fun toggleOverlay() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(showOverlay = !it.showOverlay) }
        }
    }

    /** Konum izni sonucu (ekrandan). İzin izleme sırasında verildiyse GPS hemen başlar. */
    fun onLocationPermissionResult() = synchronized(lock) {
        val granted = speedProvider.hasPermission()
        _uiState.update { it.copy(locationPermission = granted) }
        if (granted && mode == MonitorMode.MONITORING) speedProvider.start()
    }

    override fun onCleared() {
        synchronized(lock) { endSessionIfMonitoring() }
        alertManager.release()
        // Modeli de aynı thread'de kapat, sonra thread'i sonlandır
        analysisExecutor.execute {
            helper?.close()
            helper = null
        }
        analysisExecutor.shutdown()
    }

    companion object {
        private const val COUNTDOWN_MS = 3_000L
        private const val MEASURE_MS = 5_000L
    }
}
