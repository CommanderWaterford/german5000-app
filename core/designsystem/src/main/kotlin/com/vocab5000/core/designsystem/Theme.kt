package com.vocab5000.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemePreference { SYSTEM, LIGHT, DARK }

/** Brand tokens for German5000. A future language app supplies its own palette. */
object G5000Colors {
    val Ink = Color(0xFF1B2430)
    val Teal = Color(0xFF00696B)
    val TealLight = Color(0xFF6FD6D7)
    val Amber = Color(0xFF8A5100)
    val AmberLight = Color(0xFFFFB86B)
    val Paper = Color(0xFFFAFAF7)
    val Night = Color(0xFF111418)

    // Gender accents supplement written der/die/das; never used alone (spec §15.1).
    val Masculine = Color(0xFF2F5DA8)
    val Feminine = Color(0xFFA23A5A)
    val Neuter = Color(0xFF3C7A3F)
}

private val LightColors = lightColorScheme(
    primary = G5000Colors.Teal,
    onPrimary = Color.White,
    secondary = G5000Colors.Amber,
    onSecondary = Color.White,
    background = G5000Colors.Paper,
    onBackground = G5000Colors.Ink,
    surface = Color.White,
    onSurface = G5000Colors.Ink,
)

private val DarkColors = darkColorScheme(
    primary = G5000Colors.TealLight,
    onPrimary = Color(0xFF003738),
    secondary = G5000Colors.AmberLight,
    onSecondary = Color(0xFF4A2800),
    background = G5000Colors.Night,
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF1A1E23),
    onSurface = Color(0xFFE6E8EB),
    onSurfaceVariant = Color(0xFFA9B0B8),
    surfaceContainerLow = Color(0xFF161A1F),
    surfaceContainerHigh = Color(0xFF22272D),
    outline = Color(0xFF5B6169),
    outlineVariant = Color(0xFF3A3F46),
)

@Composable
fun G5000Theme(preference: ThemePreference = ThemePreference.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
