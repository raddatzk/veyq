package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.raddatz.veyq.mobile.theme.WebPalette
import me.raddatz.veyq.mobile.theme.WebTheme

/** One of the web's colour roles as a Compose colour. */
fun role(argb: Long): Color = Color(argb.toInt())

/** The web's palette for the current mode, for what Material's scheme has no slot for (favorite, the green create role). */
val LocalPalette = staticCompositionLocalOf { WebTheme.light }

/**
 * Material's scheme filled from the web's roles (docs/crosscutting/ui-web-styleguide.md): the accent (purple) is the
 * brand and the selection — Material's primary, so selected navigation, focus and the switch take it; the web's
 * primary (green) is creating and confirming only, Material's tertiary, which no component takes by default.
 */
fun scheme(p: WebPalette, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = role(p.accent),
        onPrimary = role(p.onFill),
        primaryContainer = role(p.accentSoft),
        onPrimaryContainer = role(p.accent),
        secondary = role(p.accent),
        onSecondary = role(p.onFill),
        secondaryContainer = role(p.accentSoft),
        onSecondaryContainer = role(p.accent),
        tertiary = role(p.primary),
        onTertiary = role(p.onFill),
        tertiaryContainer = role(p.successSoft),
        onTertiaryContainer = role(p.successText),
        error = role(p.danger),
        onError = role(p.onFill),
        errorContainer = role(p.dangerSoft),
        onErrorContainer = role(p.danger),
        background = role(p.bg),
        onBackground = role(p.text),
        surface = role(p.bg),
        onSurface = role(p.text),
        surfaceVariant = role(p.surfaceMuted),
        onSurfaceVariant = role(p.muted),
        surfaceContainerLowest = role(p.surface),
        surfaceContainerLow = role(p.surface),
        surfaceContainer = role(p.surface),
        surfaceContainerHigh = role(p.surfaceMuted),
        surfaceContainerHighest = role(p.surfaceMuted),
        outline = role(p.controlLine),
        outlineVariant = role(p.line),
        scrim = role(p.scrim),
    )
}

/** The web's radii: 6 for buttons and fields, 10 (`--app-radius-sm`) for rows, 12 (`--app-radius`) for cards. */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(WebTheme.radiusSmPx.dp),
    large = RoundedCornerShape(WebTheme.radiusPx.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

object VeyqType {
    /** The web's section label (`.sidebar-section-label`): small, heavy, uppercase, muted. */
    val sectionLabel = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp)

    /** The wordmark beside the mark: 1.35rem at weight 850. */
    val wordmark = TextStyle(fontSize = 21.sp, fontWeight = FontWeight(850), letterSpacing = 0.sp)
}

@Composable
fun VeyqTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) WebTheme.dark else WebTheme.light
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme(palette, dark), shapes = shapes, content = content)
    }
}
