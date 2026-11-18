package com.example.driverattention.detection

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Tek bir karenin yüz analiz sonucu.
 *
 * @param landmarks     Yüzün 478 noktası (normalize: x ve y 0..1 arası). Yüz yoksa boş liste.
 * @param imageWidth    Modele verilen görüntünün genişliği (piksel)
 * @param imageHeight   Modele verilen görüntünün yüksekliği (piksel)
 * @param transformMatrix Yüzün 4x4 dönüşüm matrisi (Faz 3'te baş pozu için kullanılacak), yoksa null
 * @param processingTimeMs Karenin kameradan alınmasından sonucun gelmesine kadar geçen süre
 */
class FaceFrame(
    val landmarks: List<NormalizedLandmark>,
    val imageWidth: Int,
    val imageHeight: Int,
    val transformMatrix: FloatArray?,
    val processingTimeMs: Long,
    val timestampMs: Long, // karenin kameradan alındığı an (SystemClock.uptimeMillis)
) {
    val hasFace: Boolean get() = landmarks.isNotEmpty()
}

/**
 * MediaPipe Face Landmarker sarmalayıcısı.
 *
 * - LIVE_STREAM modu: kareler asenkron işlenir, sonuç bir dinleyiciye (callback) gelir.
 *   Model meşgulken gelen kareler MediaPipe tarafından atlanır, böylece gecikme birikmez.
 * - Önce GPU ile başlatmayı dener; cihaz desteklemezse CPU'ya düşer.
 *
 * ÖNEMLİ: Bu sınıf, kareleri gönderen aynı arka plan thread'inde oluşturulmalı
 * (GPU delegate'i thread'e bağlıdır).
 */
class FaceLandmarkerHelper(
    context: Context,
    private val onResult: (FaceFrame) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var faceLandmarker: FaceLandmarker? = null

    /** Gerçekte hangi işlemcinin kullanıldığı ("GPU" / "CPU") — ekranda gösterilir */
    var activeDelegate: String = "-"
        private set

    // Sonuç geldiğinde görüntü boyutunu bilmek için son gönderilen karenin boyutu
    @Volatile private var lastWidth = 0
    @Volatile private var lastHeight = 0

    init {
        faceLandmarker = create(context, Delegate.GPU)?.also { activeDelegate = "GPU" }
            ?: create(context, Delegate.CPU)?.also { activeDelegate = "CPU" }

        if (faceLandmarker == null) {
            onError("Face Landmarker başlatılamadı")
        }
    }

    private fun create(context: Context, delegate: Delegate): FaceLandmarker? = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_PATH)
            .setDelegate(delegate)
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)                                 // Sadece sürücünün yüzü
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFacialTransformationMatrixes(true)    // Baş pozu (Faz 3) için
            .setResultListener(::handleResult)
            .setErrorListener { e -> onError(e.message ?: "MediaPipe hatası") }
            .build()

        FaceLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        Log.w(TAG, "$delegate ile başlatılamadı: ${e.message}")
        null
    }

    /**
     * Bir kareyi asenkron analiz için gönderir.
     * @param bitmap      Doğru yöne döndürülmüş (dik) ve aynalanmış kare
     * @param frameTimeMs Karenin alındığı an (SystemClock.uptimeMillis). MediaPipe artan zaman damgası ister.
     */
    fun detectAsync(bitmap: Bitmap, frameTimeMs: Long) {
        val landmarker = faceLandmarker ?: return
        lastWidth = bitmap.width
        lastHeight = bitmap.height
        val mpImage = BitmapImageBuilder(bitmap).build()
        landmarker.detectAsync(mpImage, frameTimeMs)
    }

    // MediaPipe sonuç dinleyicisi (MediaPipe'ın kendi thread'inde çağrılır)
    private fun handleResult(result: FaceLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: com.google.mediapipe.framework.image.MPImage) {
        val now = SystemClock.uptimeMillis()
        val landmarks = result.faceLandmarks().firstOrNull() ?: emptyList()
        // Matris çıktısı Optional<List<FloatArray>>; açık değilse veya yüz yoksa null
        val matrix = result.facialTransformationMatrixes()
            .orElse(null)
            ?.firstOrNull()

        onResult(
            FaceFrame(
                landmarks = landmarks,
                imageWidth = lastWidth,
                imageHeight = lastHeight,
                transformMatrix = matrix,
                // result.timestampMs() = detectAsync'e verdiğimiz kare zamanı
                processingTimeMs = now - result.timestampMs(),
                timestampMs = result.timestampMs(),
            )
        )
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }

    companion object {
        private const val TAG = "FaceLandmarkerHelper"
        const val MODEL_PATH = "face_landmarker.task"
    }
}
