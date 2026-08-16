package com.mazevm.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * MazeVM is an OLED-first app: the base surface is #000000 so unlit pixels stay off.
 * Elevation is expressed with hairline borders and a very small set of near-black
 * greys rather than Material's tonal overlays, which would wash the black out.
 */
object MazeColors {
    val Black = Color(0xFF000000)

    /** Cards and sheets. Just light enough to separate from true black. */
    val Surface1 = Color(0xFF0A0A0B)
    val Surface2 = Color(0xFF121214)
    val Surface3 = Color(0xFF1A1A1D)

    val Border = Color(0xFF232327)
    val BorderStrong = Color(0xFF34343A)

    val TextPrimary = Color(0xFFF2F2F4)
    val TextSecondary = Color(0xFF9A9AA2)
    val TextTertiary = Color(0xFF66666E)

    val Danger = Color(0xFFFF5F56)
    val DangerContainer = Color(0xFF2A0F0E)
    val Warning = Color(0xFFFFBD2E)
    val Success = Color(0xFF3BD17A)
}

/**
 * Accent choices offered in Settings. Each is tuned to stay legible on pure black.
 * White is the default: it matches the monochrome logo and is the only accent that
 * costs an OLED panel nothing extra to light.
 */
enum class AccentColor(val hex: Long) {
    White(0xFFFFFFFF),
    Arctic(0xFF7FD6FF),
    Terminal(0xFF3BD17A),
    Amber(0xFFFFB454),
    Magenta(0xFFFF7AC6),
    Violet(0xFFB18CFF);

    val color: Color get() = Color(hex)

    /** Low-opacity fill used behind selected rows and chips. */
    val container: Color get() = color.copy(alpha = 0.14f)

    companion object {
        val Default = White

        fun fromName(name: String?): AccentColor =
            entries.firstOrNull { it.name == name } ?: Default
    }
}
