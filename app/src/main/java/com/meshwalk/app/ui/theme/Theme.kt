package com.meshwalk.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// MeshWalk brand colors — vibrant teal/cyan palette
val MeshTeal = Color(0xFF00BFA5)
val MeshTealDark = Color(0xFF00897B)
val MeshCyan = Color(0xFF18FFFF)
val MeshSurface = Color(0xFFF8FFFE)
val MeshSurfaceDark = Color(0xFF0F1A19)
val MeshCardDark = Color(0xFF1A2C2A)

private val LightColorScheme = lightColorScheme(
    primary = MeshTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00352F),
    secondary = Color(0xFF4DB6AC),
    onSecondary = Color.White,
    tertiary = MeshCyan,
    background = MeshSurface,
    surface = MeshSurface,
    surfaceVariant = Color(0xFFE0F2F1),
    onSurface = Color(0xFF1B1B1F),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFFB0BEC5)
)

private val DarkColorScheme = darkColorScheme(
    primary = MeshTeal,
    onPrimary = Color(0xFF003730),
    primaryContainer = MeshTealDark,
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF003731),
    tertiary = MeshCyan,
    background = MeshSurfaceDark,
    surface = MeshSurfaceDark,
    surfaceVariant = MeshCardDark,
    onSurface = Color(0xFFE6E1E5),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099)
)

@Composable
fun MeshWalkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
