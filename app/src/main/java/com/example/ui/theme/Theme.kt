package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

data class SemanticColors(
    val success: androidx.compose.ui.graphics.Color,
    val successSurface: androidx.compose.ui.graphics.Color,
    val warning: androidx.compose.ui.graphics.Color,
    val warningSurface: androidx.compose.ui.graphics.Color,
    val danger: androidx.compose.ui.graphics.Color,
    val dangerSurface: androidx.compose.ui.graphics.Color
)

val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightAccentFg,
    secondaryContainer = LightAccentContainer,
    onSecondaryContainer = LightAccentContainerFg,
    tertiary = LightTextMuted,
    background = LightBgCanvas,
    onBackground = LightTextPrimary,
    surface = LightBgSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightBgElevated,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorderStrong,
    outlineVariant = LightBorderSubtle,
    error = LightDanger,
    errorContainer = LightDangerSurface
)

val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkAccentFg,
    secondaryContainer = DarkAccentContainer,
    onSecondaryContainer = DarkAccentContainerFg,
    tertiary = DarkTextMuted,
    background = DarkBgCanvas,
    onBackground = DarkTextPrimary,
    surface = DarkBgSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkBgElevated,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorderStrong,
    outlineVariant = DarkBorderSubtle,
    error = DarkDanger,
    errorContainer = DarkDangerSurface
)

@Composable
fun getSemanticColors(darkTheme: Boolean = isSystemInDarkTheme()): SemanticColors {
    return if (darkTheme) {
        SemanticColors(
            success = DarkSuccess,
            successSurface = DarkSuccessSurface,
            warning = DarkWarning,
            warningSurface = DarkWarningSurface,
            danger = DarkDanger,
            dangerSurface = DarkDangerSurface
        )
    } else {
        SemanticColors(
            success = LightSuccess,
            successSurface = LightSuccessSurface,
            warning = LightWarning,
            warningSurface = LightWarningSurface,
            danger = LightDanger,
            dangerSurface = LightDangerSurface
        )
    }
}

@Composable
fun TugasKuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
