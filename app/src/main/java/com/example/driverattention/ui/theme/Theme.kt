package com.example.driverattention.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Araç içinde göz yormaması için koyu tema kullanıyoruz
private val DarkColors = darkColorScheme()

@Composable
fun DriverAttentionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
