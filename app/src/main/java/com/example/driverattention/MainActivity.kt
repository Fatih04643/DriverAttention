package com.example.driverattention

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.driverattention.ui.AppNavigation
import com.example.driverattention.ui.theme.DriverAttentionTheme

/**
 * Uygulamanın tek Activity'si. Tüm ekranlar Compose ile çizilir.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Sürüş sırasında ekranın kapanmaması için ekranı açık tut
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            DriverAttentionTheme {
                AppNavigation()
            }
        }
    }
}
