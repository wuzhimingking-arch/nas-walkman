package tech.peakedge.naswalkman

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import tech.peakedge.naswalkman.ui.AppViewModel
import tech.peakedge.naswalkman.ui.MusicApp
import tech.peakedge.naswalkman.ui.theme.NasWalkmanTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
