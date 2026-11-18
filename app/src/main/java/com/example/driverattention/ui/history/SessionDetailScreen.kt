package com.example.driverattention.ui.history

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.driverattention.alert.displayMessage
import com.example.driverattention.alert.shortLabel
import com.example.driverattention.analysis.AlertLevel
import com.example.driverattention.data.SessionWithAlerts
import java.io.File

private fun AlertLevel.color(): Color = when (this) {
    AlertLevel.CRITICAL -> Color(0xFFD50000)
    AlertLevel.WARNING -> Color(0xFFFF9100)
    AlertLevel.INFO -> Color(0xFF2979FF)
    AlertLevel.NONE -> Color.Gray
}

/** Tek oturumun ayrıntısı: özet, uyarı sayıları, zaman çizelgesi, CSV paylaşma */
@Composable
fun SessionDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val item = sessions?.firstOrNull { it.session.id == sessionId }
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Oturum bulunamadı") }
        return
    }

    val counts = item.countsByType()
    val sortedAlerts = item.alerts.sortedBy { it.timestamp }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("← Geri") }
            Text(formatDate(item.session.startTime), style = MaterialTheme.typography.headlineSmall)
            Text(
                "Süre: ${formatDuration(item.durationMs)} · EAR eşiği: %.3f (%s)".format(
                    item.session.earThreshold,
                    if (item.session.calibrated) "kalibre" else "varsayılan",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Uyarı sayıları ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Uyarılar (${item.alerts.size})", fontWeight = FontWeight.Bold)
                    if (counts.isEmpty()) Text("Bu sürüşte uyarı yok 👍")
                    counts.entries.sortedByDescending { it.key.level }.forEach { (type, count) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(type.level.color(), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(type.shortLabel(), Modifier.weight(1f))
                            Text("$count", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ---- Zaman çizelgesi ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Zaman çizelgesi", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    AlertTimeline(item)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(item.session.startTime), style = MaterialTheme.typography.labelSmall)
                        Text(
                            formatTime(item.session.startTime + item.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        // ---- Uyarı listesi ----
        items(sortedAlerts, key = { it.id }) { alert ->
            val type = alert.type.toAlertType()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background((type?.level ?: AlertLevel.NONE).color(), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(formatTime(alert.timestamp), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(type?.displayMessage() ?: alert.type)
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }

        // ---- İşlemler ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.session.csvPath?.let { path ->
                    Button(onClick = { shareCsv(context, path) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Ölçümleri paylaş (CSV)")
                    }
                }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Oturumu sil")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Oturum silinsin mi?") },
            text = { Text("Bu sürüşün tüm uyarı kayıtları kalıcı olarak silinecek.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    item.session.csvPath?.let { File(it).delete() }
                    viewModel.delete(sessionId)
                    onBack()
                }) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") } },
        )
    }
}

/**
 * Yatay zaman çizelgesi: çubuk oturumun tamamını temsil eder, her uyarı başlangıçtan
 * geçen süreye orantılı konumda, seviyesinin renginde bir işaret olarak çizilir.
 */
@Composable
private fun AlertTimeline(item: SessionWithAlerts) {
    val start = item.session.startTime
    val duration = item.durationMs.coerceAtLeast(1)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val barTop = size.height * 0.35f
        val barHeight = size.height * 0.3f
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, barTop),
            size = androidx.compose.ui.geometry.Size(size.width, barHeight),
            cornerRadius = CornerRadius(barHeight / 2),
        )
        item.alerts.forEach { alert ->
            val level = alert.type.toAlertType()?.level ?: AlertLevel.NONE
            val x = ((alert.timestamp - start).toFloat() / duration).coerceIn(0f, 1f) * size.width
            drawLine(
                color = level.color(),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 4.dp.toPx(),
            )
        }
    }
}

/** CSV dosyasını Android paylaşım menüsüyle gönderir (e-posta, Drive, WhatsApp vb.) */
private fun shareCsv(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "CSV dosyası bulunamadı", Toast.LENGTH_SHORT).show()
        return
    }
    // FileProvider: dosyayı diğer uygulamalara güvenli, geçici izinli bir content:// adresiyle verir
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Ölçümleri paylaş"))
}
