package com.mazevm.android.terminal

/**
 * A VT100/xterm-subset terminal.
 *
 * Only what a Linux boot and a login shell actually emit is implemented: SGR colours
 * and attributes, cursor movement, erase and scroll regions, insert/delete of lines and
 * characters, and enough mode handling to ignore the rest quietly. Anything unknown is
 * dropped rather than printed, which keeps `dmesg` output readable instead of littering
 * the screen with escape fragments.
 *
 * Not thread-safe; drive it from a single coroutine and read [snapshot] under [lock].
 */
class TerminalEmulator(
    columns: Int = 80,
    rows: Int = 24,
    private val scrollbackLimit: Int = 2000,
) {

    var columns = columns.coerceAtLeast(2); private set
    var rows = rows.coerceAtLeast(2); private set

    /** Bumped on every change so Compose can tell when to redraw. */
    var revision: Long = 0L; private set

    val lock = Any()

    private var screen = Array(this.rows) { TerminalLine(this.columns) }
    private val scrollback = ArrayDeque<TerminalLine>()

    var cursorRow = 0; private set
    var cursorColumn = 0; private set
    var cursorVisible = true; private set

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    private var currentStyle = TerminalStyle.Default
    private var savedCursor: Triple<Int, Int, TerminalStyle>? = null
    private var wrapPending = false

    /** Set when the guest asks for the alternate screen; used to reset scrollback. */
    var usingAlternateScreen = false; private set

    // -------------------------------------------------------------- public API

    val scrollbackSize: Int get() = scrollback.size

    /** Total addressable lines, scrollback first. */
    val totalRows: Int get() = scrollback.size + rows

    /** Returns the line at [index] where 0 is the oldest scrollback line. */
    fun lineAt(index: Int): TerminalLine =
        if (index < scrollback.size) scrollback[index] else screen[index - scrollback.size]

    fun resize(newColumns: Int, newRows: Int) {
        val cols = newColumns.coerceIn(2, 512)
        val rowCount = newRows.coerceIn(2, 512)
        if (cols == columns && rowCount == rows) return

        val old = screen
        screen = Array(rowCount) { TerminalLine(cols) }
        // Keep the bottom of the old screen, which is where the prompt lives.
        val copyCount = minOf(old.size, rowCount)
        val sourceStart = old.size - copyCount
        for (i in 0 until copyCount) {
            old[sourceStart + i].copyInto(screen[i], cols)
        }

        columns = cols
        rows = rowCount
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorColumn = cursorColumn.coerceIn(0, cols - 1)
        wrapPending = false
        revision++
    }

    fun reset() {
        screen = Array(rows) { TerminalLine(columns) }
        scrollback.clear()
        cursorRow = 0
        cursorColumn = 0
        scrollTop = 0
        scrollBottom = rows - 1
        currentStyle = TerminalStyle.Default
        wrapPending = false
        revision++
    }

    fun write(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) feed(bytes[i].toInt() and 0xFF)
        revision++
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------- UTF-8 decoding

    private var utf8Remaining = 0
    private var utf8Accumulator = 0

    private fun feed(byte: Int) {
        if (utf8Remaining > 0) {
            if (byte and 0xC0 == 0x80) {
                utf8Accumulator = (utf8Accumulator shl 6) or (byte and 0x3F)
                utf8Remaining--
                if (utf8Remaining == 0) consume(utf8Accumulator)
                return
            }
            // A malformed sequence: drop what we had and reinterpret this byte.
            utf8Remaining = 0
        }

        when {
            byte < 0x80 -> consume(byte)
            byte and 0xE0 == 0xC0 -> { utf8Accumulator = byte and 0x1F; utf8Remaining = 1 }
            byte and 0xF0 == 0xE0 -> { utf8Accumulator = byte and 0x0F; utf8Remaining = 2 }
            byte and 0xF8 == 0xF0 -> { utf8Accumulator = byte and 0x07; utf8Remaining = 3 }
            else -> consume(0xFFFD)
        }
    }

    // ------------------------------------------------------------ state machine

    private enum class State { GROUND, ESCAPE, CSI, OSC, CHARSET }

    private var state = State.GROUND
    private val parameterBuffer = StringBuilder()
    private val oscBuffer = StringBuilder()
    private var csiPrivate = false

    private fun consume(codePoint: Int) {
        when (state) {
            State.GROUND -> ground(codePoint)
            State.ESCAPE -> escape(codePoint)
            State.CSI -> csi(codePoint)
            State.OSC -> osc(codePoint)
            State.CHARSET -> state = State.GROUND
        }
    }

    private fun ground(codePoint: Int) {
        when (codePoint) {
            0x07 -> Unit // bell
            0x08 -> { // backspace
                wrapPending = false
                if (cursorColumn > 0) cursorColumn--
            }
            0x09 -> { // tab
                wrapPending = false
                cursorColumn = ((cursorColumn / TAB_WIDTH) + 1) * TAB_WIDTH
                if (cursorColumn >= columns) cursorColumn = columns - 1
            }
            0x0A, 0x0B, 0x0C -> { wrapPending = false; lineFeed() }
            0x0D -> { wrapPending = false; cursorColumn = 0 }
            0x1B -> { state = State.ESCAPE; parameterBuffer.setLength(0) }
            in 0x00..0x1F -> Unit
            else -> put(codePoint)
        }
    }

    private fun escape(codePoint: Int) {
        when (codePoint.toChar()) {
            '[' -> {
                state = State.CSI
                parameterBuffer.setLength(0)
                csiPrivate = false
            }
            ']' -> {
                state = State.OSC
                oscBuffer.setLength(0)
            }
            '(', ')', '*', '+' -> state = State.CHARSET
            'D' -> { lineFeed(); state = State.GROUND }
            'M' -> { reverseLineFeed(); state = State.GROUND }
            'E' -> { cursorColumn = 0; lineFeed(); state = State.GROUND }
            '7' -> { saveCursor(); state = State.GROUND }
            '8' -> { restoreCursor(); state = State.GROUND }
            'c' -> { reset(); state = State.GROUND }
            else -> state = State.GROUND
        }
    }

    private fun csi(codePoint: Int) {
        val ch = codePoint.toChar()
        when {
            ch == '?' || ch == '>' || ch == '!' -> { csiPrivate = true; return }
            ch in '0'..'9' || ch == ';' || ch == ':' || ch == ' ' -> {
                if (parameterBuffer.length < 64) parameterBuffer.append(ch)
                return
            }
        }
        dispatchCsi(ch)
        state = State.GROUND
    }

    private fun osc(codePoint: Int) {
        // Terminated by BEL, or by ESC \ which arrives as ESC then backslash.
        when (codePoint) {
            0x07 -> state = State.GROUND
            0x1B -> state = State.ESCAPE
            else -> if (oscBuffer.length < 256) oscBuffer.append(codePoint.toChar())
        }
    }

    private fun parameters(): List<Int> =
        parameterBuffer.toString()
            .split(';')
            .map { part -> part.substringBefore(':').trim().toIntOrNull() ?: 0 }

    private fun parameter(index: Int, default: Int): Int =
        parameters().getOrNull(index)?.takeIf { it > 0 } ?: default

    private fun dispatchCsi(command: Char) {
        val params = parameters()
        when (command) {
            'A' -> { cursorRow = (cursorRow - parameter(0, 1)).coerceAtLeast(0); wrapPending = false }
            'B' -> { cursorRow = (cursorRow + parameter(0, 1)).coerceAtMost(rows - 1); wrapPending = false }
            'C' -> { cursorColumn = (cursorColumn + parameter(0, 1)).coerceAtMost(columns - 1); wrapPending = false }
            'D' -> { cursorColumn = (cursorColumn - parameter(0, 1)).coerceAtLeast(0); wrapPending = false }
            'E' -> { cursorRow = (cursorRow + parameter(0, 1)).coerceAtMost(rows - 1); cursorColumn = 0 }
            'F' -> { cursorRow = (cursorRow - parameter(0, 1)).coerceAtLeast(0); cursorColumn = 0 }
            'G', '`' -> { cursorColumn = (parameter(0, 1) - 1).coerceIn(0, columns - 1); wrapPending = false }
            'd' -> { cursorRow = (parameter(0, 1) - 1).coerceIn(0, rows - 1) }
            'H', 'f' -> {
                cursorRow = (parameter(0, 1) - 1).coerceIn(0, rows - 1)
                cursorColumn = (parameter(1, 1) - 1).coerceIn(0, columns - 1)
                wrapPending = false
            }
            'J' -> eraseInDisplay(params.firstOrNull() ?: 0)
            'K' -> eraseInLine(params.firstOrNull() ?: 0)
            'L' -> insertLines(parameter(0, 1))
            'M' -> deleteLines(parameter(0, 1))
            'P' -> deleteCharacters(parameter(0, 1))
            '@' -> insertCharacters(parameter(0, 1))
            'X' -> eraseCharacters(parameter(0, 1))
            'S' -> repeat(parameter(0, 1)) { scrollUp() }
            'T' -> repeat(parameter(0, 1)) { scrollDown() }
            'm' -> applyGraphicRendition(params)
            'r' -> {
                scrollTop = (parameter(0, 1) - 1).coerceIn(0, rows - 1)
                scrollBottom = (parameter(1, rows) - 1).coerceIn(scrollTop, rows - 1)
                cursorRow = 0
                cursorColumn = 0
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h' -> setMode(params, enabled = true)
            'l' -> setMode(params, enabled = false)
            else -> Unit // device status, reports and the rest are not needed here
        }
    }

    private fun setMode(params: List<Int>, enabled: Boolean) {
        if (!csiPrivate) return
        for (param in params) {
            when (param) {
                25 -> cursorVisible = enabled
                1049, 47, 1047 -> {
                    // Alternate screen. Full-screen programs use it; approximate by
                    // clearing so leftovers from the main screen do not show through.
                    if (usingAlternateScreen != enabled) {
                        usingAlternateScreen = enabled
                        screen = Array(rows) { TerminalLine(columns) }
                        cursorRow = 0
                        cursorColumn = 0
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun put(codePoint: Int) {
        if (wrapPending) {
            cursorColumn = 0
            lineFeed()
            wrapPending = false
        }
        screen[cursorRow].set(cursorColumn, codePoint, currentStyle)
        if (cursorColumn == columns - 1) {
            wrapPending = true
        } else {
            cursorColumn++
        }
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) scrollUp()
        else if (cursorRow < rows - 1) cursorRow++
    }

    private fun reverseLineFeed() {
        if (cursorRow == scrollTop) scrollDown()
        else if (cursorRow > 0) cursorRow--
    }

    private fun scrollUp() {
        val evicted = screen[scrollTop]
        // Only the main screen contributes to scrollback; alternate-screen content is
        // transient by definition.
        if (scrollTop == 0 && !usingAlternateScreen) {
            scrollback.addLast(evicted.copy())
            while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
        }
        for (row in scrollTop until scrollBottom) screen[row] = screen[row + 1]
        screen[scrollBottom] = TerminalLine(columns)
    }

    private fun scrollDown() {
        for (row in scrollBottom downTo scrollTop + 1) screen[row] = screen[row - 1]
        screen[scrollTop] = TerminalLine(columns)
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                screen[cursorRow].clear(cursorColumn, columns, currentStyle)
                for (row in cursorRow + 1 until rows) screen[row].clear(0, columns, currentStyle)
            }
            1 -> {
                screen[cursorRow].clear(0, cursorColumn + 1, currentStyle)
                for (row in 0 until cursorRow) screen[row].clear(0, columns, currentStyle)
            }
            2, 3 -> {
                for (row in 0 until rows) screen[row].clear(0, columns, currentStyle)
                if (mode == 3) scrollback.clear()
            }
        }
    }

    private fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> screen[cursorRow].clear(cursorColumn, columns, currentStyle)
            1 -> screen[cursorRow].clear(0, cursorColumn + 1, currentStyle)
            2 -> screen[cursorRow].clear(0, columns, currentStyle)
        }
    }

    private fun eraseCharacters(count: Int) {
        screen[cursorRow].clear(cursorColumn, (cursorColumn + count).coerceAtMost(columns), currentStyle)
    }

    private fun insertLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            for (row in scrollBottom downTo cursorRow + 1) screen[row] = screen[row - 1]
            screen[cursorRow] = TerminalLine(columns)
        }
    }

    private fun deleteLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            for (row in cursorRow until scrollBottom) screen[row] = screen[row + 1]
            screen[scrollBottom] = TerminalLine(columns)
        }
    }

    private fun deleteCharacters(count: Int) =
        screen[cursorRow].deleteCharacters(cursorColumn, count, currentStyle)

    private fun insertCharacters(count: Int) =
        screen[cursorRow].insertCharacters(cursorColumn, count, currentStyle)

    private fun saveCursor() {
        savedCursor = Triple(cursorRow, cursorColumn, currentStyle)
    }

    private fun restoreCursor() {
        savedCursor?.let { (row, column, style) ->
            cursorRow = row.coerceIn(0, rows - 1)
            cursorColumn = column.coerceIn(0, columns - 1)
            currentStyle = style
        }
    }

    private fun applyGraphicRendition(params: List<Int>) {
        if (params.isEmpty()) {
            currentStyle = TerminalStyle.Default
            return
        }
        var index = 0
        while (index < params.size) {
            when (val code = params[index]) {
                0 -> currentStyle = TerminalStyle.Default
                1 -> currentStyle = currentStyle.copy(bold = true)
                2 -> currentStyle = currentStyle.copy(faint = true)
                3 -> currentStyle = currentStyle.copy(italic = true)
                4 -> currentStyle = currentStyle.copy(underline = true)
                7 -> currentStyle = currentStyle.copy(inverse = true)
                21, 22 -> currentStyle = currentStyle.copy(bold = false, faint = false)
                23 -> currentStyle = currentStyle.copy(italic = false)
                24 -> currentStyle = currentStyle.copy(underline = false)
                27 -> currentStyle = currentStyle.copy(inverse = false)
                in 30..37 -> currentStyle = currentStyle.copy(foreground = code - 30)
                39 -> currentStyle = currentStyle.copy(foreground = TerminalStyle.DEFAULT_FOREGROUND)
                in 40..47 -> currentStyle = currentStyle.copy(background = code - 40)
                49 -> currentStyle = currentStyle.copy(background = TerminalStyle.DEFAULT_BACKGROUND)
                in 90..97 -> currentStyle = currentStyle.copy(foreground = code - 90 + 8)
                in 100..107 -> currentStyle = currentStyle.copy(background = code - 100 + 8)
                38, 48 -> {
                    val consumed = readExtendedColour(params, index, isForeground = code == 38)
                    index += consumed
                }
            }
            index++
        }
    }

    /** Handles `38;5;n`, `38;2;r;g;b` and their background twins. Returns params eaten. */
    private fun readExtendedColour(params: List<Int>, at: Int, isForeground: Boolean): Int {
        return when (params.getOrNull(at + 1)) {
            5 -> {
                val index = params.getOrNull(at + 2) ?: 0
                currentStyle = if (isForeground) currentStyle.copy(foreground = index)
                else currentStyle.copy(background = index)
                2
            }
            2 -> {
                val r = params.getOrNull(at + 2) ?: 0
                val g = params.getOrNull(at + 3) ?: 0
                val b = params.getOrNull(at + 4) ?: 0
                val packed = TerminalStyle.packTrueColour(r, g, b)
                currentStyle = if (isForeground) currentStyle.copy(foreground = packed)
                else currentStyle.copy(background = packed)
                4
            }
            else -> 0
        }
    }

    private companion object {
        const val TAB_WIDTH = 8
    }
}
