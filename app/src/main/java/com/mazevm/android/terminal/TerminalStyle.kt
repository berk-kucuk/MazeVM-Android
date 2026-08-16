package com.mazevm.android.terminal

/**
 * Cell attributes, packed into a single Int so a line is two flat arrays.
 *
 * Layout: bits 0..24 foreground, 25..49 would not fit, so colours are stored as two
 * 12-bit slots plus flags. A colour slot holds either a palette index (0..255) or a
 * 24-bit RGB value flattened to the 6x6x6 cube; true colour is quantised because the
 * alternative is doubling the memory for a difference nobody sees on a console.
 */
@JvmInline
value class TerminalStyle(val packed: Int) {

    val foreground: Int get() = packed and COLOUR_MASK
    val background: Int get() = (packed shr COLOUR_BITS) and COLOUR_MASK
    val bold: Boolean get() = packed and FLAG_BOLD != 0
    val faint: Boolean get() = packed and FLAG_FAINT != 0
    val italic: Boolean get() = packed and FLAG_ITALIC != 0
    val underline: Boolean get() = packed and FLAG_UNDERLINE != 0
    val inverse: Boolean get() = packed and FLAG_INVERSE != 0

    fun copy(
        foreground: Int = this.foreground,
        background: Int = this.background,
        bold: Boolean = this.bold,
        faint: Boolean = this.faint,
        italic: Boolean = this.italic,
        underline: Boolean = this.underline,
        inverse: Boolean = this.inverse,
    ): TerminalStyle {
        var value = (foreground and COLOUR_MASK) or
            ((background and COLOUR_MASK) shl COLOUR_BITS)
        if (bold) value = value or FLAG_BOLD
        if (faint) value = value or FLAG_FAINT
        if (italic) value = value or FLAG_ITALIC
        if (underline) value = value or FLAG_UNDERLINE
        if (inverse) value = value or FLAG_INVERSE
        return TerminalStyle(value)
    }

    companion object {
        private const val COLOUR_BITS = 9
        private const val COLOUR_MASK = (1 shl COLOUR_BITS) - 1

        private const val FLAG_BOLD = 1 shl 18
        private const val FLAG_FAINT = 1 shl 19
        private const val FLAG_ITALIC = 1 shl 20
        private const val FLAG_UNDERLINE = 1 shl 21
        private const val FLAG_INVERSE = 1 shl 22

        /** Sentinels that mean "whatever the theme says", not a palette entry. */
        const val DEFAULT_FOREGROUND = 256
        const val DEFAULT_BACKGROUND = 257

        val Default = TerminalStyle(
            DEFAULT_FOREGROUND or (DEFAULT_BACKGROUND shl COLOUR_BITS)
        )

        /** Folds a 24-bit colour into the xterm 6x6x6 cube, which starts at index 16. */
        fun packTrueColour(r: Int, g: Int, b: Int): Int {
            fun level(v: Int) = (v.coerceIn(0, 255) * 5 + 127) / 255
            return 16 + 36 * level(r) + 6 * level(g) + level(b)
        }
    }
}
