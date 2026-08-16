package com.mazevm.android.terminal

/**
 * One row of the terminal: code points plus a packed style per cell.
 *
 * Cells are stored in parallel arrays rather than as objects because a screen plus
 * scrollback is tens of thousands of cells and per-cell allocation would dominate.
 */
class TerminalLine(val columns: Int) {

    val codePoints = IntArray(columns) { ' '.code }
    val styles = IntArray(columns) { TerminalStyle.Default.packed }

    fun set(column: Int, codePoint: Int, style: TerminalStyle) {
        if (column !in 0 until columns) return
        codePoints[column] = codePoint
        styles[column] = style.packed
    }

    fun clear(from: Int, until: Int, style: TerminalStyle) {
        val start = from.coerceIn(0, columns)
        val end = until.coerceIn(start, columns)
        // Erasing paints the current background, which is how `clear` fills a coloured
        // screen rather than punching holes in it.
        val blank = style.copy(foreground = TerminalStyle.DEFAULT_FOREGROUND).packed
        for (i in start until end) {
            codePoints[i] = ' '.code
            styles[i] = blank
        }
    }

    fun deleteCharacters(at: Int, count: Int, style: TerminalStyle) {
        if (at !in 0 until columns || count <= 0) return
        val shift = count.coerceAtMost(columns - at)
        for (i in at until columns - shift) {
            codePoints[i] = codePoints[i + shift]
            styles[i] = styles[i + shift]
        }
        clear(columns - shift, columns, style)
    }

    fun insertCharacters(at: Int, count: Int, style: TerminalStyle) {
        if (at !in 0 until columns || count <= 0) return
        val shift = count.coerceAtMost(columns - at)
        for (i in columns - 1 downTo at + shift) {
            codePoints[i] = codePoints[i - shift]
            styles[i] = styles[i - shift]
        }
        clear(at, at + shift, style)
    }

    fun copy(): TerminalLine = TerminalLine(columns).also {
        codePoints.copyInto(it.codePoints)
        styles.copyInto(it.styles)
    }

    fun copyInto(target: TerminalLine, targetColumns: Int) {
        val count = minOf(columns, targetColumns)
        codePoints.copyInto(target.codePoints, 0, 0, count)
        styles.copyInto(target.styles, 0, 0, count)
    }

    /** Trailing blanks are never worth drawing. */
    fun lastUsedColumn(): Int {
        var last = -1
        for (i in columns - 1 downTo 0) {
            if (codePoints[i] != ' '.code || styles[i] != TerminalStyle.Default.packed) {
                last = i
                break
            }
        }
        return last
    }

    fun asString(): String {
        val builder = StringBuilder(columns)
        for (i in 0..lastUsedColumn()) builder.appendCodePoint(codePoints[i])
        return builder.toString()
    }
}
