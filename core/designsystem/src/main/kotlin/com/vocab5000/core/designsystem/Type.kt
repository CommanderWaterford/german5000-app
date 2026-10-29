package com.vocab5000.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Bricolage Grotesque carries the app's voice: every big German word,
 * headline and score is set in it. Body and label text stay on the
 * system face for quiet, native readability.
 */
val Bricolage = FontFamily(
    Font(R.font.bricolage_medium, FontWeight.Medium),
    Font(R.font.bricolage_bold, FontWeight.Bold),
    Font(R.font.bricolage_extrabold, FontWeight.ExtraBold),
)

private val Defaults = Typography()

val G5000Typography = Typography(
    displayLarge = Defaults.displayLarge.copy(
        fontFamily = Bricolage,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1).sp,
    ),
    displayMedium = Defaults.displayMedium.copy(
        fontFamily = Bricolage,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.5).sp,
    ),
    displaySmall = Defaults.displaySmall.copy(
        fontFamily = Bricolage,
        fontWeight = FontWeight.ExtraBold,
    ),
    headlineLarge = Defaults.headlineLarge.copy(
        fontFamily = Bricolage,
        fontWeight = FontWeight.ExtraBold,
    ),
    headlineMedium = Defaults.headlineMedium.copy(
        fontFamily = Bricolage,
        fontWeight = FontWeight.Bold,
    ),
    headlineSmall = Defaults.headlineSmall.copy(
        fontFamily = Bricolage,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = Defaults.titleLarge.copy(
        fontFamily = Bricolage,
        fontWeight = FontWeight.Bold,
    ),
    titleMedium = Defaults.titleMedium.copy(
        fontFamily = Bricolage,
        fontWeight = FontWeight.Medium,
    ),
)
