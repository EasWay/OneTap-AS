package com.example.onetap.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val BlackColorScheme = darkColorScheme(
    primary = White,
    secondary = Gray,
    tertiary = Gray,
    background = Black,
    surface = Black,
    onPrimary = Black,
    onSecondary = White,
    onTertiary = White,
    onBackground = White,
    onSurface = White,
    error = Red,
    onError = White
)

@Composable
fun OneTapTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Black.toArgb()
            window.navigationBarColor = Black.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = BlackColorScheme,
        typography = Typography,
        content = content
    )
}