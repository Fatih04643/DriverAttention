package com.example.driverattention.ui.monitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ButtonDefaults
import com.example.driverattention.analysis.AlertLevel
import com.example.driverattention.analysis.FatigueLevel
import com.example.driverattention.camera.CameraPreview
import com.example.driverattention.ui.calibration.CalibrationOverlay

/**
 * İzleme (Monitor) ekranı.
 * Kamera iznini kontrol eder; izin varsa önizleme + landmark overlay + FPS bilgisini gösterir.
 */
@Composable
fun MonitorScreen(viewModel: MonitorViewModel) {
    val context = LocalContext.current

    // Uygulama açıldığında izin zaten verilmiş mi?
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // Kullanıcı izni reddettiyse açıklama ekranı gösterilir
    var permissionDenied by remember { mutableStateOf(false) }

    // Android'in standart izin diyaloğunu açan başlatıcı
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionDenied = !granted
    }

    // İlk açılışta kamera + konum (Faz 8) izinleri birlikte istenir. Böylece izin pencereleri
    // kalibrasyon başlamadan kapanmış olur (pencereye bakarken kalibrasyon bozulmasın).
    val firstRunLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val camera = result[Manifest.permission.CAMERA] == true
        hasCameraPermission = camera
        permissionDenied = !camera
        viewModel.onLocationPermissionResult()
    }

    // Kamera izni önceden verilmiş ama konum hiç sorulmamışsa (Faz 8'den önceki kurulumlar):
    // sadece konumu sor; pencere kalibrasyon sırasında açıldığı için cevaptan sonra kalibrasyonu yenile
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onLocationPermissionResult()
        viewModel.restartCalibrationIfInProgress()
    }
    var locationAsked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.onLocationPermissionResult() // mevcut izin durumunu oku
        val locationGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        when {
            !hasCameraPermission -> firstRunLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
            !locationGranted && !locationAsked -> {
                locationAsked = true
                locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            hasCameraPermission -> MonitorContent(viewModel)

            permissionDenied -> PermissionRationale(
                onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    // "Bir daha sorma" seçildiyse diyalog açılmaz; kullanıcıyı uygulama ayarlarına yönlendir
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(intent)
                }
            )

            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Kamera izni bekleniyor…")
            }
        }
    }
}

/** İzin verildikten sonraki asıl ekran: kamera + overlay + bilgi paneli */
@Composable
private fun MonitorContent(viewModel: MonitorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        CameraPreview(
            analysisExecutor = viewModel.analysisExecutor,
            analyzer = viewModel.frameAnalyzer,
            modifier = Modifier.fillMaxSize(),
        )

        val face = state.face
        if (state.showOverlay && face != null) {
            LandmarkOverlay(face = face, modifier = Modifier.fillMaxSize())
        }

        // Sol üst: performans ve tespit bilgileri (sadece debug modda)
        if (state.debugMode) {
            Column(
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                val infoStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White
                )
                Text("FPS      : %.1f".format(state.fps), style = infoStyle)
                Text("İşlem    : ${state.processingTimeMs} ms", style = infoStyle)
                Text("İşlemci  : ${state.delegate}", style = infoStyle)
                if (face != null) {
                    Text("Çözünürlük: ${face.imageWidth}x${face.imageHeight}", style = infoStyle)
                }
                Text(
                    "Işık     : %.0f%s · %d ms".format(
                        state.brightness, if (state.lowLightActive) " ☾" else "", state.preprocessMs,
                    ),
                    style = infoStyle.copy(color = if (state.lowLightActive) Color(0xFFFFEB3B) else Color.White),
                )
                Text(
                    text = if (face?.hasFace == true) "Yüz: VAR (${face.landmarks.size} nokta)" else "Yüz: YOK",
                    style = infoStyle.copy(color = if (face?.hasFace == true) Color(0xFF00E676) else Color(0xFFFF5252)),
                )

                // Faz 3: canlı ölçümler (5 karelik hareketli ortalama)
                state.metrics?.let { m ->
                    Spacer(Modifier.height(6.dp))
                    Text("EAR      : %.3f".format(m.ear), style = infoStyle)
                    Text("  sol/sağ: %.3f / %.3f".format(m.leftEar, m.rightEar), style = infoStyle)
                    Text("MAR      : %.3f".format(m.mar), style = infoStyle)
                    m.headPose?.let { p ->
                        Text("Yaw      : %+6.1f°".format(p.yaw), style = infoStyle)
                        Text("Pitch    : %+6.1f°".format(p.pitch), style = infoStyle)
                        Text("Roll     : %+6.1f°".format(p.roll), style = infoStyle)
                    }
                }
                // Faz 4: eşik ve karar motoru değerleri
                Spacer(Modifier.height(6.dp))
                val thresholdSource = if (state.calibration != null) "kalibre" else "varsayılan"
                Text("EAR eşik : %.3f (%s)".format(state.earThreshold, thresholdSource), style = infoStyle)
                // Dikkat dağınıklığı kararı mutlak açıya değil, kalibrasyondaki "yola bakış"
                // referansına göre farka bakar. Referans yanlışsa burada hemen görülür.
                val refYaw = state.calibration?.baselineYawDeg ?: 0f
                val refPitch = state.calibration?.baselinePitchDeg ?: 0f
                Text("Referans : yaw %+.0f° pitch %+.0f°".format(refYaw, refPitch), style = infoStyle)
                // Faz 8: hız bandı ve o banttaki dikkat dağınıklığı süresi
                if (state.mode == MonitorMode.MONITORING) {
                    val rules = state.speedRules
                    val distraction = when {
                        !rules.distractionAlerts -> "kapalı"
                        rules.distractionMs != null -> "%.1f sn".format(rules.distractionMs / 1000f)
                        else -> "ayar"
                    }
                    Text("Hız bandı: ${rules.band}", style = infoStyle)
                    Text("Dikkat dğ: $distraction", style = infoStyle)
                }
                state.metrics?.headPose?.let { p ->
                    Text(
                        "Fark     : yaw %+.0f° pitch %+.0f°".format(p.yaw - refYaw, p.pitch - refPitch),
                        style = infoStyle,
                    )
                }
                state.status?.let { s ->
                    Text("PERCLOS  : %.1f %%".format(s.perclos * 100), style = infoStyle)
                    Text("Göz kapalı: ${s.eyesClosedMs} ms", style = infoStyle)
                    Text("Esneme/5dk: ${s.recentYawnCount}", style = infoStyle)
                }
                // Faz 7: yorgunluk skorunun bileşen dökümü (sağdaki göstergeyle çakışmasın diye burada)
                state.fatigue?.let { fatigue ->
                    Spacer(Modifier.height(6.dp))
                    FatigueBreakdown(fatigue, style = infoStyle)
                }
                if (state.csvRecording) Text("● CSV kaydı açık", style = infoStyle.copy(color = Color(0xFFFF5252)))
                state.error?.let { Text("Hata: $it", style = infoStyle.copy(color = Color(0xFFFF5252))) }
            }
        }

        // Sağ üst: durum göstergesi + yorgunluk göstergesi (Faz 7)
        Column(
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusIndicator(mode = state.mode, status = state.status)
            if (state.mode == MonitorMode.MONITORING) {
                SpeedBadge(speed = state.speed, band = state.speedRules.band, locationPermission = state.locationPermission)
            }
            state.fatigue?.let { fatigue ->
                FatigueGauge(fatigue)
            }
        }

        // Alt: uyarı banner'ı + kontrol butonları
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.status?.let { AlertBanner(it) }
            // Çok yorgun (80+): yakındaki mola yerlerini haritada aç
            if (state.fatigue?.level == FatigueLevel.VERY_TIRED) {
                RestStopButtons()
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.mode == MonitorMode.MONITORING) {
                    Button(
                        onClick = viewModel::stopMonitoring,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                    ) { Text("Durdur") }
                } else {
                    Button(
                        onClick = viewModel::startMonitoring,
                        enabled = state.mode == MonitorMode.STOPPED,
                    ) { Text("Başlat") }
                }
                FilledTonalButton(onClick = viewModel::startCalibration) { Text("Kalibre et") }
                FilledTonalButton(onClick = viewModel::toggleOverlay) {
                    Text(if (state.showOverlay) "Noktalar ✓" else "Noktalar")
                }
            }
        }

        // KRİTİK durumda tüm ekran kırmızı yanıp söner (en üstte çizilir)
        if (state.status?.level == AlertLevel.CRITICAL) {
            CriticalFlash()
        }

        // Kalibrasyon sırasında yönlendirme ekranı
        val step = state.calibrationStep
        if (state.mode == MonitorMode.CALIBRATING && step != null) {
            CalibrationOverlay(
                step = step,
                onSkip = viewModel::skipCalibration,
                onRetry = viewModel::startCalibration,
            )
        }
    }
}

@Composable
private fun PermissionRationale(onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Sürücü dikkatini izleyebilmek için ön kameraya erişim gerekli.\n" +
                "Görüntüler yalnızca cihaz üzerinde işlenir, hiçbir yere gönderilmez.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("İzin ver") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSettings) { Text("Ayarları aç") }
    }
}
