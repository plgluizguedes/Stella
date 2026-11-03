package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BordeauxLight,
    onPrimary = PureWhite,
    primaryContainer = BordeauxDark,
    onPrimaryContainer = CreamOffWhite,
    secondary = DiscreetGold,
    onSecondary = DarkBackground,
    secondaryContainer = Color(0xFF382E1E),
    onSecondaryContainer = Color(0xFFF9E8B8),
    tertiary = SoftGreen,
    onTertiary = PureWhite,
    tertiaryContainer = Color(0xFF1E3821),
    onTertiaryContainer = Color(0xFFC7E8CA),
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = GraySecondary,
    outlineVariant = Color(0xFF473C40),
    error = SoftRed,
    onError = PureWhite,
    errorContainer = Color(0xFF4A1818),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = BordeauxPrimary,
    onPrimary = PureWhite,
    primaryContainer = BordeauxSoftContainer,
    onPrimaryContainer = BordeauxDark,
    secondary = DiscreetGold,
    onSecondary = PureWhite,
    secondaryContainer = DiscreetGoldContainer,
    onSecondaryContainer = Color(0xFF493608),
    tertiary = SoftGreen,
    onTertiary = PureWhite,
    tertiaryContainer = SoftGreenContainer,
    onTertiaryContainer = Color(0xFF163819),
    background = CreamOffWhite,
    onBackground = GrayDarkText,
    surface = PureWhite,
    onSurface = GrayDarkText,
    surfaceVariant = GrayLight,
    onSurfaceVariant = GraySecondary,
    outline = GrayBorder,
    outlineVariant = Color(0xFFF0ECE9),
    error = SoftRed,
    onError = PureWhite,
    errorContainer = SoftRedContainer,
    onErrorContainer = Color(0xFF5B1111)
)

@Composable
fun MyApplicationTheme(
    // O app usa sempre o tema claro (paleta bordô e creme), mesmo com o celular no modo escuro.
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
