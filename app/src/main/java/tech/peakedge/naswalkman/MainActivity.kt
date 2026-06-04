package tech.peakedge.naswalkman

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import tech.peakedge.naswalkman.ui.AppViewModel
import tech.peakedge.naswalkman.ui.MusicApp
import tech.peakedge.naswalkman.ui.theme.NasWalkmanTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestNotificationPermission()
        setContent {
            val state by viewModel.uiState.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (state.settings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            NasWalkmanTheme(darkTheme = darkTheme) {
                val systemBarColor = MaterialTheme.colorScheme.background.toArgb()
                SideEffect {
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    window.statusBarColor = Color.TRANSPARENT
                    window.navigationBarColor = systemBarColor
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isStatusBarContrastEnforced = false
                        window.isNavigationBarContrastEnforced = false
                    }
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                MusicApp(viewModel)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                42,
            )
        }
    }
}
