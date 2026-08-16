package com.mazevm.android.terminal

import android.view.KeyEvent

/**
 * Translates Android key events into the byte sequences a Linux console expects.
 *
 * The guest is running a real getty, so these are the xterm sequences that `terminfo`
 * describes rather than anything app-specific.
 */
object TerminalKeys {

    const val ESC = 0x1B.toByte()

    /** Returns null when the event carries no meaning for the guest. */
    fun encode(event: KeyEvent): ByteArray? {
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed

        namedKey(event.keyCode)?.let { return it }

        val unicode = event.unicodeChar
        if (unicode == 0) return null

        return when {
            // Ctrl+A..Ctrl+Z and the handful of control codes above them.
            ctrl -> control(unicode.toChar())?.let { byteArrayOf(it) }
            // Alt is Meta: ESC then the character, which is what readline expects.
            alt -> byteArrayOf(ESC) + unicode.toChar().toString().toByteArray(Charsets.UTF_8)
            else -> unicode.toChar().toString().toByteArray(Charsets.UTF_8)
        }
    }

    private fun namedKey(keyCode: Int): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> byteArrayOf(0x0D)
        KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)
        KeyEvent.KEYCODE_FORWARD_DEL -> csi("3~")
        KeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
        KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(ESC)
        KeyEvent.KEYCODE_DPAD_UP -> csi("A")
        KeyEvent.KEYCODE_DPAD_DOWN -> csi("B")
        KeyEvent.KEYCODE_DPAD_RIGHT -> csi("C")
        KeyEvent.KEYCODE_DPAD_LEFT -> csi("D")
        KeyEvent.KEYCODE_MOVE_HOME -> csi("H")
        KeyEvent.KEYCODE_MOVE_END -> csi("F")
        KeyEvent.KEYCODE_PAGE_UP -> csi("5~")
        KeyEvent.KEYCODE_PAGE_DOWN -> csi("6~")
        KeyEvent.KEYCODE_INSERT -> csi("2~")
        KeyEvent.KEYCODE_F1 -> escapeO("P")
        KeyEvent.KEYCODE_F2 -> escapeO("Q")
        KeyEvent.KEYCODE_F3 -> escapeO("R")
        KeyEvent.KEYCODE_F4 -> escapeO("S")
        KeyEvent.KEYCODE_F5 -> csi("15~")
        KeyEvent.KEYCODE_F6 -> csi("17~")
        KeyEvent.KEYCODE_F7 -> csi("18~")
        KeyEvent.KEYCODE_F8 -> csi("19~")
        KeyEvent.KEYCODE_F9 -> csi("20~")
        KeyEvent.KEYCODE_F10 -> csi("21~")
        KeyEvent.KEYCODE_F11 -> csi("23~")
        KeyEvent.KEYCODE_F12 -> csi("24~")
        else -> null
    }

    /** Maps a printable character to its control code, e.g. `c` to 0x03. */
    fun control(character: Char): Byte? {
        val upper = character.uppercaseChar()
        return when {
            upper in 'A'..'Z' -> (upper - 'A' + 1).toByte()
            upper == '@' || upper == ' ' -> 0
            upper == '[' -> 0x1B
            upper == '\\' -> 0x1C
            upper == ']' -> 0x1D
            upper == '^' -> 0x1E
            upper == '_' || upper == '?' -> 0x1F
            else -> null
        }
    }

    fun csi(tail: String): ByteArray =
        byteArrayOf(ESC, '['.code.toByte()) + tail.toByteArray(Charsets.US_ASCII)

    private fun escapeO(tail: String): ByteArray =
        byteArrayOf(ESC, 'O'.code.toByte()) + tail.toByteArray(Charsets.US_ASCII)

    /** Convenience sequences the on-screen key row sends. */
    val Up get() = csi("A")
    val Down get() = csi("B")
    val Right get() = csi("C")
    val Left get() = csi("D")
    val Escape get() = byteArrayOf(ESC)
    val Tab get() = byteArrayOf(0x09.toByte())
    val Enter get() = byteArrayOf(0x0D.toByte())
    val CtrlC get() = byteArrayOf(0x03.toByte())
    val CtrlD get() = byteArrayOf(0x04.toByte())
    val CtrlZ get() = byteArrayOf(0x1A.toByte())
    val CtrlL get() = byteArrayOf(0x0C.toByte())
}
