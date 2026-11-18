package com.example.driverattention.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.driverattention.ui.history.HistoryScreen
import com.example.driverattention.ui.history.SessionDetailScreen
import com.example.driverattention.ui.monitor.MonitorMode
import com.example.driverattention.ui.monitor.MonitorScreen
import com.example.driverattention.ui.monitor.MonitorViewModel
import com.example.driverattention.ui.settings.SettingsScreen

private object Routes {
    const val MONITOR = "monitor"
    const val HISTORY = "history"
    const val SESSION = "history/{sessionId}"
    const val SETTINGS = "settings"
    fun session(id: Long) = "history/$id"
}

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    BottomTab(Routes.MONITOR, "İzleme", Icons.Filled.Home),
    @Suppress("DEPRECATION") BottomTab(Routes.HISTORY, "Geçmiş", Icons.Filled.List),
    BottomTab(Routes.SETTINGS, "Ayarlar", Icons.Filled.Settings),
)

/**
 * Uygulamanın ekranlar arası geçişi (Navigation Compose) ve alt menüsü.
 *
 * MonitorViewModel Activity seviyesinde oluşturulur; böylece Geçmiş/Ayarlar'a geçip
 * dönünce kalibrasyon ve model kaybolmaz.
 *
 * Güvenlik: izleme veya kalibrasyon sürerken alt menü gizlenir — sürücü araç kullanırken
 * menüde gezinmemeli, ayrıca izleme ekranından çıkılırsa kamera kapanır.
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val monitorViewModel: MonitorViewModel = viewModel()
    val monitorState by monitorViewModel.uiState.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val showBottomBar = monitorState.mode == MonitorMode.STOPPED

    Scaffold(
        // Kenar boşluklarını her ekran kendisi yönetir (izleme ekranı tam ekran kamera)
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route ||
                            (tab.route == Routes.HISTORY && currentRoute == Routes.SESSION)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    // Sekmeler arasında geçerken geri yığınını şişirme
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.MONITOR,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.MONITOR) { MonitorScreen(monitorViewModel) }
            composable(Routes.HISTORY) {
                Box(Modifier.statusBarsPadding()) {
                    HistoryScreen(onOpenSession = { id -> navController.navigate(Routes.session(id)) })
                }
            }
            composable(
                Routes.SESSION,
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
            ) { entry ->
                Box(Modifier.statusBarsPadding()) {
                    SessionDetailScreen(
                        sessionId = entry.arguments?.getLong("sessionId") ?: 0L,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(Routes.SETTINGS) {
                Box(Modifier.statusBarsPadding()) { SettingsScreen() }
            }
        }
    }
}
