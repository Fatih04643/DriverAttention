package com.example.driverattention.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** Bir karenin ön işleme bilgisi (debug paneli ve CSV için) */
data class FrameInfo(
    val brightness: Float,    // ortalama parlaklık 0–255
    val enhanced: Boolean,    // düşük ışık iyileştirmesi uygulandı mı
    val preprocessMs: Long,   // döndürme + iyileştirme süresi
)

/**
 * CameraX'ten gelen her kareyi MediaPipe'ın kullanabileceği Bitmap'e çevirir.
 *
 * Kamera sensörü telefona göre genelde 90°/270° döndürülmüş durumdadır; ayrıca ön kamera
 * önizlemesi ayna gibi (yatayda ters) gösterilir. Landmark noktalarının ekrandaki yüzle
 * birebir örtüşmesi için kareyi burada:
 *   1) rotationDegrees kadar döndürüyoruz (dik hale getirme),
 *   2) yatayda aynalıyoruz (önizleme ile aynı görünüm),
 *   3) karanlıksa ve ayar açıksa histogram eşitleme uyguluyoruz (bkz. LowLightEnhancer).
 *
 * Bu sınıf analiz executor'ında (arka plan thread'i) çalışır, ana thread'i bloklamaz.
 */
class FrameAnalyzer(
    private val isLowLightEnabled: () -> Boolean,
    private val onFrame: (bitmap: Bitmap, frameTimeMs: Long, info: FrameInfo) -> Unit,
) : ImageAnalysis.Analyzer {

    private val enhancer = LowLightEnhancer()
    private var pixelBuffer = IntArray(0) // her karede yeniden ayırmamak için

    override fun analyze(image: ImageProxy) {
        val frameTimeMs = SystemClock.uptimeMillis()

        // use{} sayesinde kare iş bitince kapatılır; kapatılmazsa CameraX yeni kare göndermez
        val upright = image.use { proxy ->
            val raw = proxy.toBitmap() // RGBA_8888 formatında olduğu için hızlı dönüşüm
            val matrix = Matrix().apply {
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f) // ön kamera aynalama
            }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, false)
        }

        // Parlaklığı ölç; karanlıksa iyileştir
        val w = upright.width
        val h = upright.height
        if (pixelBuffer.size != w * h) pixelBuffer = IntArray(w * h)
        upright.getPixels(pixelBuffer, 0, w, 0, 0, w, h)
        val brightness = enhancer.meanBrightness(pixelBuffer)

        val shouldEnhance = isLowLightEnabled() && enhancer.isDark(brightness)
        val output = if (shouldEnhance) {
            enhancer.enhance(pixelBuffer)
            Bitmap.createBitmap(pixelBuffer, w, h, Bitmap.Config.ARGB_8888)
        } else {
            upright
        }

        val info = FrameInfo(
            brightness = brightness,
            enhanced = shouldEnhance,
            preprocessMs = SystemClock.uptimeMillis() - frameTimeMs,
        )
        onFrame(output, frameTimeMs, info)
    }
}
