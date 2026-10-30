// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AccentBlue = Color(0xFF4F8CFF)
private val AccentCyan = Color(0xFF3DD6C8)
private val WarnAmber = Color(0xFFFFB020)
private val DangerRed = Color(0xFFFF5A5F)

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color(0xFF04121F),
    primaryContainer = Color(0xFF1A3355),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = AccentCyan,
    onSecondary = Color(0xFF00201C),
    tertiary = WarnAmber,
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFE8E8EE),
    surface = Color(0xFF14141A),
    onSurface = Color(0xFFE8E8EE),
    surfaceVariant = Color(0xFF1E1E26),
    onSurfaceVariant = Color(0xFFA8A8B8),
    error = DangerRed,
    outline = Color(0xFF3A3A46),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2A6FE0),
    secondary = Color(0xFF00968A),
    tertiary = Color(0xFFB87400),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEFF3),
    error = Color(0xFFCC2222),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ScreenSubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
