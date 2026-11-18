package com.neo.codexquota.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LightColors = lightColorScheme(
    primary = Color(0xFF16775C), onPrimary = Color.White,
    primaryContainer = Color(0xFFD1F1E0), onPrimaryContainer = Color(0xFF103E33),
    background = Color(0xFFF5F7F3), onBackground = Color(0xFF182D27),
    surface = Color(0xFFFAFCF8), onSurface = Color(0xFF182D27),
    surfaceVariant = Color(0xFFE3EBE4), onSurfaceVariant = Color(0xFF52645A),
    error = Color(0xFFAD382D),
)
val DarkColors = darkColorScheme(
    primary = Color(0xFF83DAB5), onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF22513F), onPrimaryContainer = Color(0xFFC3F2DC),
    background = Color(0xFF101A16), onBackground = Color(0xFFE0EAE2),
    surface = Color(0xFF18251E), onSurface = Color(0xFFE0EAE2),
    surfaceVariant = Color(0xFF304237), onSurfaceVariant = Color(0xFFB3C5B8),
    error = Color(0xFFFFB4A8),
)

@Composable
fun QuotaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
