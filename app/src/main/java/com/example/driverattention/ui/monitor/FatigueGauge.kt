package com.example.driverattention.ui.monitor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.driverattention.analysis.FatigueComponents
import com.example.driverattention.analysis.FatigueLevel
import com.example.driverattention.analysis.FatigueScore
import com.example.driverattention.analysis.FatigueScoreConstants as C
import kotlin.math.cos
import kotlin.math.sin

val FatigueGreen = Color(0xFF00C853)
val FatigueYellow = Color(0xFFFFD600)
val FatigueOrange = Color(0xFFFF9100)
val FatigueRed = Color(0xFFD50000)

fun FatigueLevel.color(): Color = when (this) {
    FatigueLevel.NORMAL -> FatigueGreen
    FatigueLevel.MILD -> FatigueYellow
    FatigueLevel.TIRED -> FatigueOrange
    FatigueLevel.VERY_TIRED -> FatigueRed
}

fun FatigueLevel.label(): String = when (this) {
    FatigueLevel.NORMAL -> "Dinç"
    FatigueLevel.MILD -> "Hafif yorgun"
    FatigueLevel.TIRED -> "Yorgun"
    FatigueLevel.VERY_TIRED -> "Çok yorgun"
}

/**
 * Yarım daire yorgunluk göstergesi (0–100).
 *
 * Arka planda seviye bölgeleri soluk renkte çizilir (0–30 yeşil, 30–60 sarı, 60–80 turuncu,
 * 80–100 kırmızı); üstte skora kadar dolu yay ve bir ibre. Yarım daire 180° olduğundan
 * 1 puan = 1.8° dönüşe karşılık gelir.
 */
@Composable
fun FatigueGauge(fatigue: FatigueScore, modifier: Modifier = Modifier) {
    val score = fatigue.score.coerceIn(0f, 100f)
    val levelColor = fatigue.level.color()

    Column(
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Yorgunluk", color = Color.White, fontSize = 12.sp)
        Box(contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.width(132.dp).height(70.dp)) {
                val stroke = 12.dp.toPx()
                val diameter = size.width - stroke
                val topLeft = Offset(stroke / 2, stroke / 2)
                val arcSize = Size(diameter, diameter)

                // Seviye bölgeleri (soluk)
                val zones = listOf(
                    0f to C.MILD_THRESHOLD to FatigueGreen,
                    C.MILD_THRESHOLD to C.TIRED_THRESHOLD to FatigueYellow,
                    C.TIRED_THRESHOLD to C.VERY_TIRED_THRESHOLD to FatigueOrange,
                    C.VERY_TIRED_THRESHOLD to 100f to FatigueRed,
                )
                zones.forEach { (range, color) ->
                    val (from, to) = range
                    drawArc(
                        color = color.copy(alpha = 0.3f),
                        startAngle = 180f + from * 1.8f,
                        sweepAngle = (to - from) * 1.8f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke),
                    )
                }
                // Skora kadar dolu yay
                drawArc(
                    color = levelColor,
                    startAngle = 180f,
                    sweepAngle = score * 1.8f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                // İbre
                val center = Offset(size.width / 2, stroke / 2 + diameter / 2)
                val angle = Math.toRadians((180.0 + score * 1.8))
                val needleLen = diameter / 2 - stroke * 1.4f
                drawLine(
                    color = Color.White,
                    start = center,
                    end = Offset(
                        center.x + needleLen * cos(angle).toFloat(),
                        center.y + needleLen * sin(angle).toFloat(),
                    ),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            Text(
                "%.0f".format(score),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(fatigue.level.label(), color = levelColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (fatigue.simulated) {
            Text(
                "SİMÜLASYON",
                modifier = Modifier.padding(top = 2.dp).background(FatigueRed, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Debug modda skorun bileşen dökümü: her satırda ham ölçüm ve skora katkısı (puan).
 * Katkıların toplamı = ham skor. Raporda "skor neden yükseldi" sorusunu cevaplar.
 */
@Composable
fun FatigueBreakdown(fatigue: FatigueScore, modifier: Modifier = Modifier) {
    val c: FatigueComponents = fatigue.components
    val rows = listOf(
        Triple("PERCLOS", "%.0f%%".format(c.perclos * 100), C.WEIGHT_PERCLOS * c.perclosNorm),
        Triple("Uzun kapanma", "${c.longClosureCount}/5dk", C.WEIGHT_LONG_CLOSURES * c.longClosuresNorm),
        Triple("Esneme", "${c.yawnCount}/10dk", C.WEIGHT_YAWNS * c.yawnsNorm),
        Triple("Dikkat dağ.", "${c.distractionCount}/5dk", C.WEIGHT_DISTRACTIONS * c.distractionsNorm),
        Triple("Sürüş", "%.0f dk".format(c.drivingMinutes), C.WEIGHT_DRIVING_TIME * c.drivingTimeNorm),
        Triple("Kırpma", c.avgBlinkMs?.let { "%.0f ms".format(it) } ?: "-", C.WEIGHT_BLINK_DURATION * c.blinkDurationNorm),
    )
    Column(
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val style = androidx.compose.ui.text.TextStyle(
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.White,
        )
        rows.forEach { (name, raw, contribution) ->
            Row(Modifier.width(150.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, style = style)
                Text("%s +%.1f".format(raw, contribution * 100), style = style)
            }
        }
        Text("ham skor: %.1f".format(fatigue.rawScore), style = style.copy(color = Color(0xFFB0BEC5)))
    }
}

/**
 * "Mola yeri bul" (skor ≥ 80): harita uygulamasında yakındaki dinlenme tesisi / benzin
 * istasyonu aramasını açar. geo: URI'si Android'in standart harita intent'idir; konum izni
 * istemez, aramayı harita uygulaması kendi konumuyla yapar.
 */
@Composable
fun RestStopButtons(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { openMapSearch(context, "dinlenme tesisi") },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = FatigueRed),
        ) { Text("Mola yeri bul", fontWeight = FontWeight.Bold) }
        Button(
            onClick = { openMapSearch(context, "benzin istasyonu") },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = FatigueRed),
        ) { Text("Benzin istasyonu") }
    }
}

private fun openMapSearch(context: Context, query: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Harita uygulaması bulunamadı", Toast.LENGTH_SHORT).show()
    }
}
