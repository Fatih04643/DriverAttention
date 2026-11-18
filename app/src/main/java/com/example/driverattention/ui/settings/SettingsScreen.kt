package com.example.driverattention.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.driverattention.data.AppSettings
import com.example.driverattention.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = SettingsRepository(app)

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repository.save(AppSettings()) }
    }
}

/**
 * Ayarlar ekranı. Değişiklikler anında DataStore'a kaydedilir ve izleme ekranındaki
 * karar motoruna/uyarılara otomatik uygulanır.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Ayarlar", style = MaterialTheme.typography.headlineSmall)

        Section("Göz (mikro uyku)")
        SliderSetting(
            label = "Kalibrasyon katsayısı", value = s.earCalibrationFactor, range = 0.5f..0.95f, steps = 8,
            format = { "%.2f".format(it) },
            hint = "Eşik = kalibre açık-göz EAR × katsayı. Yüksek → daha hassas.",
        ) { v -> viewModel.update { it.copy(earCalibrationFactor = v) } }
        SliderSetting(
            label = "Varsayılan EAR eşiği", value = s.defaultEarThreshold, range = 0.15f..0.30f, steps = 14,
            format = { "%.2f".format(it) },
            hint = "Kalibrasyon atlanırsa kullanılır.",
        ) { v -> viewModel.update { it.copy(defaultEarThreshold = v) } }
        SliderSetting(
            label = "Mikro uyku süresi", value = s.microSleepSec, range = 0.5f..3f, steps = 9,
            format = { "%.2f sn".format(it) },
        ) { v -> viewModel.update { it.copy(microSleepSec = v) } }
        SliderSetting(
            label = "PERCLOS eşiği", value = s.perclosPercent, range = 5f..40f, steps = 6,
            format = { "%%%d".format(it.roundToInt()) },
            hint = "Son 60 sn'de gözlerin kapalı olduğu sürenin oranı.",
        ) { v -> viewModel.update { it.copy(perclosPercent = v) } }

        Section("Esneme")
        SliderSetting(
            label = "MAR eşiği", value = s.marThreshold, range = 0.3f..1.0f, steps = 13,
            format = { "%.2f".format(it) },
        ) { v -> viewModel.update { it.copy(marThreshold = v) } }

        Section("Dikkat dağınıklığı")
        SliderSetting(
            label = "Yana bakma sınırı (yaw)", value = s.yawLimitDeg, range = 15f..60f, steps = 8,
            format = { "%d°".format(it.roundToInt()) },
        ) { v -> viewModel.update { it.copy(yawLimitDeg = v) } }
        SliderSetting(
            label = "Aşağı bakma sınırı (pitch)", value = s.pitchDownLimitDeg, range = 10f..40f, steps = 5,
            format = { "%d°".format(it.roundToInt()) },
        ) { v -> viewModel.update { it.copy(pitchDownLimitDeg = v) } }
        SliderSetting(
            label = "Dikkat dağınıklığı süresi", value = s.distractionSec, range = 1f..5f, steps = 7,
            format = { "%.1f sn".format(it) },
        ) { v -> viewModel.update { it.copy(distractionSec = v) } }

        Section("Uyarılar")
        SwitchSetting("Alarm / bip sesi", s.soundEnabled) { v -> viewModel.update { it.copy(soundEnabled = v) } }
        SwitchSetting("Titreşim", s.vibrationEnabled) { v -> viewModel.update { it.copy(vibrationEnabled = v) } }
        SwitchSetting("Sesli uyarı (Türkçe)", s.voiceEnabled) { v -> viewModel.update { it.copy(voiceEnabled = v) } }

        Section("Görünüm ve geliştirici")
        SwitchSetting(
            "Düşük ışık iyileştirmesi",
            s.lowLightEnhancement,
            hint = "Görüntü karanlıksa histogram eşitleme ile kontrastı artırır (gece sürüşü).",
        ) { v -> viewModel.update { it.copy(lowLightEnhancement = v) } }
        SwitchSetting("Yüz noktalarını göster", s.showOverlay) { v -> viewModel.update { it.copy(showOverlay = v) } }
        SwitchSetting(
            "Debug modu",
            s.debugMode,
            hint = "FPS / ölçüm panelini gösterir ve her oturumun ölçümlerini CSV dosyasına kaydeder.",
        ) { v -> viewModel.update { it.copy(debugMode = v) } }
        if (s.debugMode) {
            SwitchSetting(
                "Yorgunluk skorunu simüle et",
                s.simulateFatigue,
                hint = "Skoru elle ayarlar; sesli uyarıyı ve mola yeri butonunu masa başında denemek için. " +
                    "Açıkken göstergede SİMÜLASYON yazar ve simüle değer kaydedilir.",
            ) { v -> viewModel.update { it.copy(simulateFatigue = v) } }
            if (s.simulateFatigue) {
                SliderSetting(
                    label = "Simüle skor", value = s.simulatedFatigueScore, range = 0f..100f, steps = 19,
                    format = { "%d".format(it.roundToInt()) },
                ) { v -> viewModel.update { it.copy(simulatedFatigueScore = v) } }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        OutlinedButton(onClick = viewModel::resetToDefaults, modifier = Modifier.fillMaxWidth()) {
            Text("Varsayılanlara dön")
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    hint: String? = null,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Text(format(value), fontWeight = FontWeight.Bold)
        }
        // steps: uçlar arasındaki ara durak sayısı → değerler düzgün aralıklı olur
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
