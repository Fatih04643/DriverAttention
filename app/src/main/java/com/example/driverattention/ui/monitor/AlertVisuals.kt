package com.example.driverattention.ui.monitor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.driverattention.alert.displayMessage
import com.example.driverattention.analysis.AlertLevel
import com.example.driverattention.analysis.DriverStatus

private val Green = Color(0xFF00C853)
private val Blue = Color(0xFF2979FF)
private val Orange = Color(0xFFFF9100)
private val Red = Color(0xFFD50000)
private val Gray = Color(0xFF757575)

/** KRİTİK durumda tüm ekranı kırmızı yanıp söndürür */
@Composable
fun CriticalFlash(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "flash")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse),
        label = "flashAlpha",
    )
    // graphicsLayer: sadece saydamlık değişir, her karede yeniden yerleşim (layout) yapılmaz
    Box(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(Red))
}

/** Sağ üstteki durum göstergesi: yeşil / mavi / turuncu / kırmızı nokta + metin */
@Composable
fun StatusIndicator(mode: MonitorMode, status: DriverStatus?, modifier: Modifier = Modifier) {
    val (color, text) = when {
        mode == MonitorMode.CALIBRATING -> Blue to "Kalibrasyon"
        mode == MonitorMode.STOPPED -> Gray to "Durduruldu"
        status == null -> Green to "Normal"
        else -> when (status.level) {
            AlertLevel.NONE -> Green to "Normal"
            AlertLevel.INFO -> Blue to "Bilgi"
            AlertLevel.WARNING -> Orange to "Uyarı"
            AlertLevel.CRITICAL -> Red to "KRİTİK"
        }
    }
    Row(
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** Etkin en önemli durumun mesajını gösteren banner (turuncu = uyarı, kırmızı = kritik, mavi = bilgi) */
@Composable
fun AlertBanner(status: DriverStatus, modifier: Modifier = Modifier) {
    val top = status.activeConditions.maxByOrNull { it.level } ?: return
    val color = when (top.level) {
        AlertLevel.CRITICAL -> Red
        AlertLevel.WARNING -> Orange
        else -> Blue
    }
    Text(
        text = top.displayMessage(),
        modifier = modifier
            .fillMaxWidth()
            .background(color, RoundedCornerShape(12.dp))
            .padding(16.dp),
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}
