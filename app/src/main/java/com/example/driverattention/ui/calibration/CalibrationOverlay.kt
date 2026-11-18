package com.example.driverattention.ui.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.driverattention.ui.monitor.CalibrationStep

/**
 * Kalibrasyon ekranı. Kamera önizlemesinin üzerine yarı saydam olarak çizilir, böylece
 * kullanıcı kendini görüp pozisyon alabilir.
 *
 *  1) Geri sayım: telefonu yerleştirip yola bakar gibi düz bakması istenir
 *  2) Ölçüm: ~5 sn boyunca açık göz EAR'ı ve düz bakış açıları ölçülür
 *  3) Hata olursa: tekrar dene / varsayılan eşikle devam et
 */
@Composable
fun CalibrationOverlay(
    step: CalibrationStep,
    onSkip: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(Color(0x66000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .background(Color(0xCC000000), RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Kalibrasyon", style = MaterialTheme.typography.headlineSmall, color = Color.White)

            when (step) {
                is CalibrationStep.Countdown -> {
                    Text(
                        "Telefonu sürüş konumuna yerleştirin ve yola bakar gibi düz ileri bakın.",
                        textAlign = TextAlign.Center, color = Color.White,
                    )
                    Text(
                        "${step.secondsLeft}",
                        fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFEB3B),
                    )
                    TextButton(onClick = onSkip) { Text("Atla (varsayılan eşik)") }
                }

                is CalibrationStep.Measuring -> {
                    Text(
                        "Ölçülüyor… Gözlerinizi doğal şekilde açık tutun, başınızı oynatmayın.",
                        textAlign = TextAlign.Center, color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { step.progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    TextButton(onClick = onSkip) { Text("Atla (varsayılan eşik)") }
                }

                is CalibrationStep.Failed -> {
                    Text(step.reason, textAlign = TextAlign.Center, color = Color(0xFFFF8A80))
                    Button(onClick = onRetry) { Text("Tekrar dene") }
                    OutlinedButton(onClick = onSkip) { Text("Varsayılan eşikle devam et") }
                }
            }
        }
    }
}
