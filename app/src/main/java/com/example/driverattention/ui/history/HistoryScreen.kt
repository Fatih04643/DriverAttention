package com.example.driverattention.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.driverattention.alert.shortLabel
import com.example.driverattention.analysis.AlertType
import com.example.driverattention.data.SessionWithAlerts

/** Geçmiş sürüş oturumları listesi */
@Composable
fun HistoryScreen(
    onOpenSession: (Long) -> Unit,
    viewModel: HistoryViewModel = viewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val list = sessions

    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Henüz kayıtlı sürüş yok.\nİzleme ekranında \"Başlat\" ile bir oturum başlatın.",
                textAlign = TextAlign.Center,
            )
        }

        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Sürüş Geçmişi", style = MaterialTheme.typography.headlineSmall)
            }
            items(list, key = { it.session.id }) { item ->
                SessionCard(item, onClick = { onOpenSession(item.session.id) })
            }
        }
    }
}

@Composable
private fun SessionCard(item: SessionWithAlerts, onClick: () -> Unit) {
    val counts = item.countsByType()
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDate(item.session.startTime), fontWeight = FontWeight.Bold)
                Text(
                    if (item.session.endTime == null) "devam ediyor" else formatDuration(item.durationMs),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val critical = counts[AlertType.MICROSLEEP] ?: 0
            Text(
                "Toplam ${item.alerts.size} uyarı" + if (critical > 0) " · $critical mikro uyku" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (critical > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (counts.isNotEmpty()) {
                Text(
                    counts.entries
                        .sortedByDescending { it.key.level }
                        .joinToString(" · ") { "${it.key.shortLabel()}: ${it.value}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
