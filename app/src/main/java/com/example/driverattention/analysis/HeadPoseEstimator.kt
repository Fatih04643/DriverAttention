package com.example.driverattention.analysis

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Baş pozu (derece cinsinden).
 *
 * @param yaw   Sağa/sola dönüş. Pozitif = sürücü kendi sağına bakıyor, negatif = soluna.
 * @param pitch Yukarı/aşağı eğim. Pozitif = yukarı, negatif = aşağı (ör. telefona bakma).
 * @param roll  Başın yana yatması. Pozitif = ekranda sağa yatma.
 */
data class HeadPose(val yaw: Float, val pitch: Float, val roll: Float)

/**
 * MediaPipe'ın "facial transformation matrix" çıktısından baş pozunu hesaplar.
 *
 * Bu 4x4 matris, MediaPipe'ın standart (kanonik) 3B yüz modelini kameradaki yüzün
 * konumuna taşıyan dönüşümdür:  [ R  t ]
 *                                [ 0  1 ]
 * R (sol üst 3x3) dönme matrisi, t ise öteleme (yüzün kameraya göre konumu, cm).
 *
 * Kanonik modelde yüz +Z yönüne (kameraya) bakar, +Y yukarıdır. Bu yüzden:
 *   - Yüzün baktığı yön  f = R · (0,0,1) = R'nin 3. sütunu
 *   - Yüzün "yukarısı"   u = R · (0,1,0) = R'nin 2. sütunu
 * Açılar bu vektörlerden atan2 ile bulunur:
 *   yaw   = atan2(f.x, f.z)   → yön vektörü sağa/sola ne kadar dönmüş
 *   pitch = atan2(f.y, √(f.x² + f.z²)) → yukarı/aşağı ne kadar eğilmiş
 *   roll  = atan2(u.x, u.y)   → başın tepesi yana ne kadar yatmış
 *
 * Not: Kamera görüntüsünü önizlemeyle aynı olsun diye aynaladığımız için (selfie görünümü)
 * görüntüde +X = ekranın sağı = sürücünün sağı olur.
 */
object HeadPoseEstimator {

    /**
     * @param matrix MediaPipe'tan gelen 16 elemanlı matris, SÜTUN-ÖNCELİKLİ (column-major,
     *               OpenGL düzeni): eleman(satır, sütun) = matrix[sütun * 4 + satır]
     */
    fun fromTransformationMatrix(matrix: FloatArray): HeadPose {
        require(matrix.size == 16) { "4x4 matris bekleniyordu" }
        fun r(row: Int, col: Int) = matrix[col * 4 + row]

        // Yüzün baktığı yön (3. sütun)
        val fx = r(0, 2)
        val fy = r(1, 2)
        val fz = r(2, 2)
        // Yüzün yukarı yönü (2. sütun)
        val ux = r(0, 1)
        val uy = r(1, 1)

        val yaw = Math.toDegrees(atan2(fx, fz).toDouble()).toFloat()
        val pitch = Math.toDegrees(atan2(fy, sqrt(fx * fx + fz * fz)).toDouble()).toFloat()
        val roll = Math.toDegrees(atan2(ux, uy).toDouble()).toFloat()

        return HeadPose(yaw = yaw, pitch = pitch, roll = roll)
    }
}
