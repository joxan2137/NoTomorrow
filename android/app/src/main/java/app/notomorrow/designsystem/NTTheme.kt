package app.notomorrow.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Dark-only, forced. No `isSystemInDarkTheme()`, no `dynamicDarkColorScheme()`,
 * ever. The Material colour scheme exists only so the few Material components
 * the app is allowed to touch (`ModalBottomSheet`, `BasicAlertDialog`) do not
 * paint their own palette; designed colours come from `NT.Colors`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NTTheme(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    MaterialTheme(
        colorScheme = NtColorScheme,
        typography = NtTypography,
    ) {
        CompositionLocalProvider(
            // iOS press feedback everywhere: scale 0.97 + alpha 0.90, easeOut12.
            // Never a Material ripple — a bare `Modifier.clickable {}` anywhere in
            // the app picks this up.
            LocalIndication provides NTPressScale,
            LocalOverscrollFactory provides null,      // kills the Android edge glow
            LocalTonalElevationEnabled provides false, // global kill switch for M3 tinting
            LocalContentColor provides NT.Colors.ink,
            // The iOS app uses fixed point sizes with no Dynamic Type; clamp
            // Android's font scale rather than ignoring it outright (§9.2).
            LocalDensity provides Density(base.density, base.fontScale.coerceIn(1f, 1.15f)),
            content = content,
        )
    }
}

private val NtColorScheme = darkColorScheme(
    primary = NT.Colors.ink,
    onPrimary = NT.Colors.onPrimary,
    primaryContainer = NT.Colors.surface2,
    onPrimaryContainer = NT.Colors.ink,
    secondary = NT.Colors.ember,
    onSecondary = NT.Colors.ground,
    secondaryContainer = NT.Colors.emberTint,
    onSecondaryContainer = NT.Colors.ember,
    tertiary = NT.Colors.good,
    onTertiary = NT.Colors.ground,
    background = NT.Colors.ground,
    onBackground = NT.Colors.ink,
    surface = NT.Colors.surface,
    onSurface = NT.Colors.ink,
    surfaceVariant = NT.Colors.surface2,
    onSurfaceVariant = NT.Colors.ink2,
    surfaceContainer = NT.Colors.surface,
    surfaceContainerLow = NT.Colors.surface,
    surfaceContainerLowest = NT.Colors.ground,
    surfaceContainerHigh = NT.Colors.surface2,
    surfaceContainerHighest = NT.Colors.surface3,
    inverseSurface = NT.Colors.ink,
    inverseOnSurface = NT.Colors.ground,
    error = NT.Colors.bad,
    onError = NT.Colors.ink,
    errorContainer = NT.Colors.badTint,
    onErrorContainer = NT.Colors.bad,
    outline = NT.Colors.border,
    outlineVariant = NT.Colors.hairline,
    scrim = Color.Black,
)

/**
 * Material's ramp mapped onto the NT ramp, so anything that reads
 * `MaterialTheme.typography` still lands on the right face and tracking.
 * App code always names an `NT.Fonts.*` style explicitly.
 */
private val NtTypography = Typography(
    displayLarge = NT.Fonts.largeTitle,
    displayMedium = NT.Fonts.title1,
    displaySmall = NT.Fonts.title2,
    headlineLarge = NT.Fonts.title1,
    headlineMedium = NT.Fonts.title2,
    headlineSmall = NT.Fonts.title3,
    titleLarge = NT.Fonts.title3,
    titleMedium = NT.Fonts.headline,
    titleSmall = NT.Fonts.subheadlineBold,
    bodyLarge = NT.Fonts.body,
    bodyMedium = NT.Fonts.callout,
    bodySmall = NT.Fonts.subheadline,
    labelLarge = NT.Fonts.subheadlineBold,
    labelMedium = NT.Fonts.footnote,
    labelSmall = NT.Fonts.caption,
)
