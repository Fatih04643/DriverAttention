package com.example.driverattention.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.driverattention.analysis.AlertLevel
import com.example.driverattention.analysis.FatigueScoreConstants as C
import com.example.driverattention.data.FatigueScoreEntity
import com.example.driverattention.data.SessionWithAlerts
import com.example.driverattention.ui.monitor.FatigueGreen
import com.example.driverattention.ui.monitor.FatigueOrange
import com.example.driverattention.ui.monitor.FatigueRed
import com.example.driverattention.ui.monitor.FatigueYellow

/** Uyarı seviyesine göre işaret rengi */
private fun AlertLevel.markerColor(): Color = when (this) {
    AlertLevel.CRITICAL -> Color(0xFFFF1744)
    AlertLevel.WARNING -> Color(0xFFFF9100)
    AlertLevel.INFO -> Color(0xFF448AFF)
    AlertLevel.NONE -> Color.Gray
}

/**
 * Yorgunluk skoru–zaman grafiği (Compose Canvas, ek kütüphane yok).
 *
 *  - Arka plan: seviye bölgeleri (0–30 yeşil, 30–60 sarı, 60–80 turuncu, 80–100 kırmızı)
 *  - Çizgi: 10 sn'de bir kaydedilen skorlar
 *  - İşaretler: uyarıların zamanı; dikey kesikli çizgi + skor eğrisi üzerinde renkli nokta
 *    (kırmızı = mikro uyku, turuncu = uyarı, mavi = bilgi)
 *
 * Böylece "skor ne zaman yükseldi, o sırada hangi uyarılar vardı?" tek bakışta görülür.
 */
@Composable
fun FatigueChart(item: SessionWithAlerts, modifier: Modifier = Modifier) {
    val start = item.session.startTime
    val duration = item.durationMs.coerceAtLeast(1)
    val scores = item.scores.sortedBy { it.timestamp }
    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    Canvas(modifier.fillMaxWidth().height(200.dp)) {
        val leftPad = 28.dp.toPx()
        val bottomPad = 18.dp.toPx()
        val plotW = size.width - leftPad
        val plotH = size.height - bottomPad

        fun xOf(timestamp: Long) = leftPad + ((timestamp - start).toFloat() / duration).coerceIn(0f, 1f) * plotW
        fun yOf(score: Float) = plotH * (1f - score.coerceIn(0f, 100f) / 100f)

        // ---- Seviye bölgeleri ----
        listOf(
            0f to C.MILD_THRESHOLD to FatigueGreen,
            C.MILD_THRESHOLD to C.TIRED_THRESHOLD to FatigueYellow,
            C.TIRED_THRESHOLD to C.VERY_TIRED_THRESHOLD to FatigueOrange,
            C.VERY_TIRED_THRESHOLD to 100f to FatigueRed,
        ).forEach { (range, color) ->
            val (from, to) = range
            drawRect(
                color = color.copy(alpha = 0.14f),
                topLeft = Offset(leftPad, yOf(to)),
                size = Size(plotW, yOf(from) - yOf(to)),
            )
        }

        // ---- Y ekseni etiketleri ve yatay çizgiler ----
        listOf(0f, C.MILD_THRESHOLD, C.TIRED_THRESHOLD, C.VERY_TIRED_THRESHOLD, 100f).forEach { v ->
            val y = yOf(v)
            drawLine(
                color = labelColor.copy(alpha = 0.3f),
                start = Offset(leftPad, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            val text = textMeasurer.measure("%.0f".format(v), labelStyle)
            drawText(
                text,
                topLeft = Offset(
                    leftPad - text.size.width - 4.dp.toPx(),
                    (y - text.size.height / 2f).coerceIn(0f, plotH - text.size.height / 2f),
                ),
            )
        }

        // ---- X ekseni etiketleri: başlangıç, orta, bitiş saati ----
        listOf(0f, 0.5f, 1f).forEach { f ->
            val text = textMeasurer.measure(formatTime(start + (duration * f).toLong()), labelStyle)
            val x = (leftPad + f * plotW - text.size.width * f).coerceAtLeast(leftPad)
            drawText(text, topLeft = Offset(x, plotH + 3.dp.toPx()))
        }

        // ---- Uyarı işaretleri (dikey çizgi) ----
        item.alerts.forEach { alert ->
            val level = alert.type.toAlertType()?.level ?: AlertLevel.NONE
            val x = xOf(alert.timestamp)
            drawLine(
                color = level.markerColor().copy(alpha = 0.6f),
                start = Offset(x, 0f),
                end = Offset(x, plotH),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )
        }

        // ---- Skor çizgisi ----
        drawScoreLine(scores, ::xOf, ::yOf, lineColor)

        // ---- Uyarı noktaları (skor eğrisinin üstünde) ----
        item.alerts.forEach { alert ->
            val level = alert.type.toAlertType()?.level ?: AlertLevel.NONE
            val score = interpolateScore(scores, alert.timestamp) ?: return@forEach
            val center = Offset(xOf(alert.timestamp), yOf(score))
            drawCircle(Color.White, radius = 5.dp.toPx(), center = center)
            drawCircle(level.markerColor(), radius = 3.5.dp.toPx(), center = center)
        }
    }
}

private fun DrawScope.drawScoreLine(
    scores: List<FatigueScoreEntity>,
    xOf: (Long) -> Float,
    yOf: (Float) -> Float,
    color: Color,
) {
    if (scores.isEmpty()) return
    if (scores.size == 1) {
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(xOf(scores[0].timestamp), yOf(scores[0].score)))
        return
    }
    val path = Path().apply {
        scores.forEachIndexed { i, s ->
            val p = Offset(xOf(s.timestamp), yOf(s.score))
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
    }
    drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Uyarı anındaki skoru, iki kayıt arasında doğrusal enterpolasyonla bulur */
internal fun interpolateScore(scores: List<FatigueScoreEntity>, timestamp: Long): Float? {
    if (scores.isEmpty()) return null
    if (timestamp <= scores.first().timestamp) return scores.first().score
    if (timestamp >= scores.last().timestamp) return scores.last().score
    val after = scores.indexOfFirst { it.timestamp >= timestamp }
    val a = scores[after - 1]
    val b = scores[after]
    val f = (timestamp - a.timestamp).toFloat() / (b.timestamp - a.timestamp).coerceAtLeast(1)
    return a.score + f * (b.score - a.score)
}

/** Grafiğin altındaki açıklama satırı */
@Composable
fun FatigueChartLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendDot(AlertLevel.CRITICAL.markerColor(), "Mikro uyku")
        LegendDot(AlertLevel.WARNING.markerColor(), "Uyarı")
        LegendDot(AlertLevel.INFO.markerColor(), "Bilgi")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Oturumdaki en yüksek skor (kayıt yoksa null) */
val SessionWithAlerts.peakFatigue: Float?
    get() = scores.maxOfOrNull { it.score }
