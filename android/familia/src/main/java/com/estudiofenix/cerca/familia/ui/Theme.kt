package com.estudiofenix.cerca.familia.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colores de Cerca Familia: los de Cerca (verde) con un naranja propio, el del icono. */
object FamiliaColors {
    val brand = Color(0xFF1F6F5C)
    val accent = Color(0xFFC2410C)
    val ok = Color(0xFF1E8E3E)
    val danger = Color(0xFFC62828)
    val warning = Color(0xFFB45309)
}

@Composable
fun FamiliaTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF7FD3BC), onPrimary = Color(0xFF00382C),
            secondary = Color(0xFFFFB68F), onSecondary = Color(0xFF4A1A00),
            background = Color(0xFF121614), onBackground = Color(0xFFE2E6E3),
            surface = Color(0xFF1A1F1D), onSurface = Color(0xFFE2E6E3),
            surfaceVariant = Color(0xFF26302C), onSurfaceVariant = Color(0xFFC0C9C4),
            error = Color(0xFFFFB4AB),
        )
    } else {
        lightColorScheme(
            primary = FamiliaColors.brand, onPrimary = Color.White,
            secondary = FamiliaColors.accent, onSecondary = Color.White,
            background = Color(0xFFF6F4EF), onBackground = Color(0xFF1D2421),
            surface = Color.White, onSurface = Color(0xFF1D2421),
            surfaceVariant = Color(0xFFE3F0EB), onSurfaceVariant = Color(0xFF3F4945),
            error = FamiliaColors.danger,
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
