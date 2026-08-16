package com.mazevm.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Whether surfaces are true black (OLED) or lifted to dark grey. */
enum class ThemeVariant { Oled, DarkGrey;

    companion object {
        fun fromName(name: String?): ThemeVariant =
            entries.firstOrNull { it.name == name } ?: Oled
    }
}

/**
 * Extra colour roles Material 3 has no slot for. Composables read these through
 * [LocalMazeColors] instead of hard-coding hex values.
 */
data class MazePalette(
    val accent: Color,
    val accentContainer: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val borderStrong: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    val isOled: Boolean,
    /**
     * Fill for surfaces that float over scrolling content. Translucent so what passes
     * underneath stays faintly visible, which is the whole point of the effect: on a
     * true-black background an opaque bar would be indistinguishable from the page.
     */
    val glass: Color,
    /** The lit top edge that reads as the rim of a pane of glass. */
    val glassRim: Color,
)

val LocalMazePalette = staticCompositionLocalOf {
    MazePalette(
        accent = AccentColor.Default.color,
        accentContainer = AccentColor.Default.container,
        surface1 = MazeColors.Surface1,
        surface2 = MazeColors.Surface2,
        surface3 = MazeColors.Surface3,
        border = MazeColors.Border,
        borderStrong = MazeColors.BorderStrong,
        textSecondary = MazeColors.TextSecondary,
        textTertiary = MazeColors.TextTertiary,
        danger = MazeColors.Danger,
        warning = MazeColors.Warning,
        success = MazeColors.Success,
        isOled = true,
        glass = Color(0xB3121214),
        glassRim = Color(0x24FFFFFF),
    )
}

@Composable
fun MazeTheme(
    variant: ThemeVariant = ThemeVariant.Oled,
    accent: AccentColor = AccentColor.Default,
    content: @Composable () -> Unit,
) {
    // isSystemInDarkTheme is read only so previews in light mode still resolve; the app
    // itself is dark unconditionally.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()

    val oled = variant == ThemeVariant.Oled
    val background = if (oled) MazeColors.Black else Color(0xFF0E0E10)
    val surface1 = if (oled) MazeColors.Surface1 else Color(0xFF161618)
    val surface2 = if (oled) MazeColors.Surface2 else Color(0xFF1E1E21)
    val surface3 = if (oled) MazeColors.Surface3 else Color(0xFF26262A)

    val scheme = darkColorScheme(
        primary = accent.color,
        onPrimary = MazeColors.Black,
        primaryContainer = accent.container,
        onPrimaryContainer = accent.color,
        secondary = accent.color,
        onSecondary = MazeColors.Black,
        background = background,
        onBackground = MazeColors.TextPrimary,
        surface = background,
        onSurface = MazeColors.TextPrimary,
        surfaceVariant = surface2,
        onSurfaceVariant = MazeColors.TextSecondary,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface1,
        surfaceContainer = surface1,
        surfaceContainerHigh = surface2,
        surfaceContainerHighest = surface3,
        outline = MazeColors.BorderStrong,
        outlineVariant = MazeColors.Border,
        error = MazeColors.Danger,
        onError = MazeColors.Black,
        errorContainer = MazeColors.DangerContainer,
        onErrorContainer = MazeColors.Danger,
        scrim = Color(0xCC000000),
    )

    val palette = MazePalette(
        accent = accent.color,
        accentContainer = accent.container,
        surface1 = surface1,
        surface2 = surface2,
        surface3 = surface3,
        border = if (oled) MazeColors.Border else Color(0xFF2C2C31),
        borderStrong = MazeColors.BorderStrong,
        textSecondary = MazeColors.TextSecondary,
        textTertiary = MazeColors.TextTertiary,
        danger = MazeColors.Danger,
        warning = MazeColors.Warning,
        success = MazeColors.Success,
        isOled = oled,
        // Alpha rather than a solid colour: the blur underneath only shows through a
        // translucent fill, and 70% is the point where content is still readable
        // through it without the bar losing its edge.
        glass = surface2.copy(alpha = 0.70f),
        glassRim = Color(0x24FFFFFF),
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(LocalMazePalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MazeTypography,
            shapes = MazeShapes,
            content = content,
        )
    }
}

/** Shorthand for `LocalMazePalette.current`. */
val maze: MazePalette
    @Composable get() = LocalMazePalette.current
