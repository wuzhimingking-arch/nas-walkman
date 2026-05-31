package tech.peakedge.naswalkman.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = AppTeal,
    onPrimary = Color.White,
    primaryContainer = AppTealSoft,
    onPrimaryContainer = AppTealDark,
    secondary = AppBlue,
    onSecondary = Color.White,
    tertiary = AppFavorite,
    onTertiary = Color.White,
    background = AppBackgroundLight,
    onBackground = AppTextPrimaryLight,
    surface = AppSurfaceLight,
    onSurface = AppTextPrimaryLight,
    surfaceVariant = Color(0xFFF1F5F5),
    onSurfaceVariant = AppTextSecondaryLight,
    outline = Color(0xFFD9E0E2),
    outlineVariant = AppDividerLight,
    error = AppDanger,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF39D6C4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF103D38),
    onPrimaryContainer = Color(0xFFA9F3EA),
    secondary = Color(0xFF8AB4FF),
    tertiary = Color(0xFFFF8A98),
    background = AppBackgroundDark,
    onBackground = AppTextPrimaryDark,
    surface = AppSurfaceDark,
    onSurface = AppTextPrimaryDark,
    surfaceVariant = AppSurfaceVariantDark,
    onSurfaceVariant = AppTextSecondaryDark,
    outline = Color(0xFF38434D),
    outlineVariant = Color(0xFF26313A),
    error = Color(0xFFFF8A8A),
)

@Composable
fun NasWalkmanTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
