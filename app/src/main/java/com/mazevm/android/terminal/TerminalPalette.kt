package com.mazevm.android.terminal

import androidx.compose.ui.graphics.Color

/**
 * The 256-entry xterm palette, with the first sixteen retuned for a pure-black
 * background: the stock ANSI blue and black are close to unreadable on OLED, so the
 * low-intensity end is lifted while hues stay recognisable.
 */
object TerminalPalette {

    val Background = Color(0xFF000000)
    val Foreground = Color(0xFFD8D8DC)
    val Cursor = Color(0xFF7FD6FF)
    val Selection = Color(0x407FD6FF)

    private val base16 = intArrayOf(
        0xFF16161A.toInt(), // black, lifted so it is visible against the surface
        0xFFFF6B63.toInt(), // red
        0xFF4FD675.toInt(), // green
        0xFFE8C468.toInt(), // yellow
        0xFF6FA8FF.toInt(), // blue, brightened; the stock #0000EE is unreadable here
        0xFFD98BE8.toInt(), // magenta
        0xFF5FD3D3.toInt(), // cyan
        0xFFC8C8CE.toInt(), // white
        0xFF4A4A52.toInt(), // bright black
        0xFFFF8B84.toInt(),
        0xFF77E896.toInt(),
        0xFFF2D68A.toInt(),
        0xFF95C2FF.toInt(),
        0xFFE9A9F2.toInt(),
        0xFF86E3E3.toInt(),
        0xFFF2F2F4.toInt(),
    )

    private val table: IntArray = IntArray(256).also { table ->
        base16.copyInto(table)

        // 6x6x6 colour cube, indices 16..231.
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        var index = 16
        for (r in 0..5) for (g in 0..5) for (b in 0..5) {
            table[index++] = argb(steps[r], steps[g], steps[b])
        }

        // Greyscale ramp, indices 232..255.
        for (i in 0..23) {
            val level = 8 + i * 10
            table[232 + i] = argb(level, level, level)
        }
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    fun foregroundOf(style: TerminalStyle): Color {
        val index = if (style.inverse) style.background else style.foreground
        val colour = resolve(index, isBackground = style.inverse)
        return when {
            style.faint -> colour.copy(alpha = 0.55f)
            style.bold && index < 8 -> Color(table[index + 8])
            else -> colour
        }
    }

    fun backgroundOf(style: TerminalStyle): Color {
        val index = if (style.inverse) style.foreground else style.background
        return resolve(index, isBackground = !style.inverse)
    }

    private fun resolve(index: Int, isBackground: Boolean): Color = when (index) {
        TerminalStyle.DEFAULT_FOREGROUND -> if (isBackground) Background else Foreground
        TerminalStyle.DEFAULT_BACKGROUND -> if (isBackground) Background else Foreground
        in 0..255 -> Color(table[index])
        else -> if (isBackground) Background else Foreground
    }

    /** True when a cell needs no background fill, which is the common case. */
    fun isDefaultBackground(style: TerminalStyle): Boolean =
        !style.inverse && style.background == TerminalStyle.DEFAULT_BACKGROUND
}
