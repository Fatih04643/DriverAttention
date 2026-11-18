package com.example.driverattention.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import com.example.driverattention.detection.FaceFrame
import kotlin.math.max

import com.example.driverattention.analysis.FaceLandmarkIndices

/**
 * Yüz landmark noktalarını kamera önizlemesinin üzerine çizer.
 *
 * Koordinat dönüşümü: MediaPipe noktaları 0..1 arası normalize verir (görüntüye göre).
 * PreviewView FILL_CENTER modunda görüntüyü, ekranı tamamen dolduracak kadar büyütüp
 * taşan kısmı ortadan kırpar. Aynı ölçek ve kaydırmayı burada da uyguluyoruz:
 *   scale = max(ekranG / resimG, ekranY / resimY)
 *   ekranX = x * resimG * scale + (ekranG - resimG * scale) / 2
 */
@Composable
fun LandmarkOverlay(face: FaceFrame, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (!face.hasFace || face.imageWidth == 0) return@Canvas

        val imgW = face.imageWidth.toFloat()
        val imgH = face.imageHeight.toFloat()
        val scale = max(size.width / imgW, size.height / imgH)
        val offsetX = (size.width - imgW * scale) / 2f
        val offsetY = (size.height - imgH * scale) / 2f

        val normal = ArrayList<Offset>(face.landmarks.size)
        val highlighted = ArrayList<Offset>(16)

        face.landmarks.forEachIndexed { index, lm ->
            val point = Offset(lm.x() * imgW * scale + offsetX, lm.y() * imgH * scale + offsetY)
            if (index in FaceLandmarkIndices.HIGHLIGHTED) highlighted += point else normal += point
        }

        // Tüm yüz ağı: küçük yeşil noktalar
        drawPoints(normal, PointMode.Points, Color(0xAA00E676), strokeWidth = 3f, cap = StrokeCap.Round)
        // EAR/MAR için kullanılan noktalar: büyük sarı noktalar
        drawPoints(highlighted, PointMode.Points, Color(0xFFFFEB3B), strokeWidth = 9f, cap = StrokeCap.Round)
    }
}
