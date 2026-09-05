package com.rain.sdk.sample.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/** Corner radii: three tiers only. */
object RainRadius {
    val Small = 4.dp // inputs, badges, tiles
    val Card = 20.dp // cards, panels, dialogs
}

/**
 * Material theme mapped onto the Rain tokens so any Material component the sample still uses
 * (menus, dialogs, progress indicators) picks up ink, white surfaces, and hairline neutral borders
 * instead of stock purple. Screens build on the `ui` components rather than on Material widgets.
 */
@Composable
fun RainTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = RainColors.Ink,
        onPrimary = RainColors.White,
        primaryContainer = RainColors.Gray15,
        onPrimaryContainer = RainColors.Ink,
        secondary = RainColors.TextMuted,
        onSecondary = RainColors.White,
        secondaryContainer = RainColors.Gray05,
        onSecondaryContainer = RainColors.Ink,
        tertiary = RainColors.Pink,
        onTertiary = RainColors.White,
        background = RainColors.Canvas,
        onBackground = RainColors.Ink,
        surface = RainColors.Surface,
        onSurface = RainColors.Ink,
        surfaceVariant = RainColors.Gray05,
        onSurfaceVariant = RainColors.TextMuted,
        surfaceContainer = RainColors.Surface,
        surfaceContainerLow = RainColors.Gray05,
        surfaceContainerHigh = RainColors.Surface,
        surfaceContainerHighest = RainColors.Gray05,
        outline = RainColors.Border,
        outlineVariant = RainColors.BorderSubtle,
        error = RainColors.Danger,
        onError = RainColors.White,
        errorContainer = RainColors.DangerTint,
        onErrorContainer = RainColors.Danger,
        scrim = RainColors.OffBlack.copy(alpha = 0.32f),
    )
    val typography = Typography(
        displayLarge = RainType.Title,
        displayMedium = RainType.Title,
        displaySmall = RainType.Title,
        headlineLarge = RainType.Title,
        headlineMedium = RainType.Title,
        headlineSmall = RainType.Title,
        titleLarge = RainType.Title,
        titleMedium = RainType.Strong,
        titleSmall = RainType.Strong,
        bodyLarge = RainType.Body,
        bodyMedium = RainType.Body,
        bodySmall = RainType.Body,
        labelLarge = RainType.Strong,
        labelMedium = RainType.Label,
        labelSmall = RainType.Badge,
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(RainRadius.Small),
        small = RoundedCornerShape(RainRadius.Small),
        medium = RoundedCornerShape(RainRadius.Card),
        large = RoundedCornerShape(RainRadius.Card),
        extraLarge = RoundedCornerShape(RainRadius.Card),
    )
    MaterialTheme(colorScheme = colorScheme, shapes = shapes, typography = typography) {
        CompositionLocalProvider(
            LocalContentColor provides RainColors.Ink,
            LocalTextStyle provides RainType.Body,
            content = content,
        )
    }
}
