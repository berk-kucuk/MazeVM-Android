package com.mazevm.android.vnc

import android.view.KeyEvent

/**
 * X11 keysyms, which is what RFB carries on the wire.
 *
 * Latin-1 and ASCII map straight through, so only the non-printing keys need a table.
 */
object Keysyms {

    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val INSERT = 0xFF63
    const val DELETE = 0xFFFF
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57

    const val SHIFT_L = 0xFFE1
    const val CONTROL_L = 0xFFE3
    const val ALT_L = 0xFFE9
    const val SUPER_L = 0xFFEB

    const val F1 = 0xFFBE

    fun function(number: Int): Int = F1 + (number - 1).coerceIn(0, 11)

    /** ASCII and Latin-1 characters are their own keysyms. */
    fun of(character: Char): Int = when (character) {
        '\n', '\r' -> RETURN
        '\t' -> TAB
        '\b' -> BACKSPACE
        in ' '..'ÿ' -> character.code
        // Anything outside Latin-1 uses the Unicode range keysyms.
        else -> 0x01000000 + character.code
    }

    /** Returns null for keys the guest has no notion of, such as Android's Back. */
    fun fromKeyEvent(event: KeyEvent): Int? = when (event.keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RETURN
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> HOME
        KeyEvent.KEYCODE_MOVE_END -> END
        KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> INSERT
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            function(event.keyCode - KeyEvent.KEYCODE_F1 + 1)
        else -> event.unicodeChar.takeIf { it != 0 }?.let { of(it.toChar()) }
    }
}
