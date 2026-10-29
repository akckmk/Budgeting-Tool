package com.budgetingtool.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A bright, friendly palette: violet, mint, coral, sunshine and sky.
private val Violet = Color(0xFF6C4DF6)
private val Mint = Color(0xFF14C79A)
private val Coral = Color(0xFFFF5C8A)

private val LightColors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E0FF),
    onPrimaryContainer = Color(0xFF22106B),
    secondary = Mint,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2F8EC),
    onSecondaryContainer = Color(0xFF00382A),
    tertiary = Coral,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0E9),
    background = Color(0xFFFAF8FF),
    surface = Color(0xFFFAF8FF),
    surfaceVariant = Color(0xFFEEEAFB),
    surfaceContainer = Color(0xFFF2EEFF),
    surfaceContainerHigh = Color(0xFFEDE8FF),
    surfaceContainerLow = Color(0xFFF6F3FF),
    surfaceContainerLowest = Color.White,
    onSurface = Color(0xFF1C1A2B),
    onSurfaceVariant = Color(0xFF5B5873),
    error = Color(0xFFE53950),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7A6FF),
    onPrimary = Color(0xFF2A1580),
    primaryContainer = Color(0xFF4A31C9),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = Color(0xFF5FE3BC),
    secondaryContainer = Color(0xFF00513D),
    tertiary = Color(0xFFFF8FB0),
    background = Color(0xFF15131F),
    surface = Color(0xFF15131F),
    surfaceVariant = Color(0xFF2A2740),
    onSurfaceVariant = Color(0xFFCAC4E3),
)

private val BubblyShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val BubblyType = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
    )
}

val OkColor = Color(0xFF12B886)
val WarnColor = Color(0xFFFFA62B)
val OverColor = Color(0xFFF03E5E)
val IncomeColor = Color(0xFF0CA678)

/** Gradient pairs for the rounded "bubble" cards. */
object Bubble {
    val Violet = listOf(Color(0xFF7B5CFF), Color(0xFFB34DFF))
    val Mint = listOf(Color(0xFFB8F5E2), Color(0xFFDDFBF1))
    val Sun = listOf(Color(0xFFFFE8A3), Color(0xFFFFF4D1))
    val Coral = listOf(Color(0xFFFFC9D9), Color(0xFFFFE6EE))
    val Sky = listOf(Color(0xFFC6E4FF), Color(0xFFE5F2FF))
    val Lilac = listOf(Color(0xFFE2D9FF), Color(0xFFF1EDFF))
    val Gray = listOf(Color(0xFFE9E8EF), Color(0xFFF4F3F8))
    val MintDeep = Color(0xFF0B8F6B)
    val VioletDeep = Color(0xFF5232D8)

    private val rotation = listOf(Coral, Sky, Sun, Lilac, Mint)
    fun forKey(key: String) = rotation[(key.hashCode() and 0x7fffffff) % rotation.size]
}

fun budgetColor(fraction: Double, warnAt: Double = 0.8): Color = when {
    fraction > 1.0 -> OverColor
    fraction >= warnAt -> WarnColor
    else -> OkColor
}

@Composable
fun BudgetTheme(content: @Composable () -> Unit) {
    // Fixed bright palette (not wallpaper-based dynamic color) so the app always looks cheerful.
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = BubblyShapes,
        typography = BubblyType,
        content = content,
    )
}
