package com.fnnas.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6B5F),
    onPrimary = Color.White,
    secondary = Color(0xFF9A4E45),
    tertiary = Color(0xFF4D5D8C),
    background = Color(0xFFF7F9F6),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE4ECE8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86D5C4),
    onPrimary = Color(0xFF00382F),
    secondary = Color(0xFFFFB4A8),
    tertiary = Color(0xFFBBC7FF),
    background = Color(0xFF111512),
    surface = Color(0xFF171C19),
    surfaceVariant = Color(0xFF3F4945),
)

@Composable
fun FnNasMusicTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
