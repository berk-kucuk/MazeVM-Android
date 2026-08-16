package com.mazevm.android.terminal

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.floor
import kotlin.math.roundToInt

/** Lets a screen open the keyboard without owning the hidden input view. */
class TerminalController {
    internal var view: TerminalInputView? = null

    fun showKeyboard() = view?.showKeyboard()
    fun hideKeyboard() = view?.hideKeyboard()
}

/**
 * Renders a [TerminalEmulator] and routes keyboard input back to the guest.
 *
 * [revision] must be the same state object the writer updates after every chunk. It is
 * read inside the draw block on purpose: that subscribes the draw phase to it, so new
 * output repaints without going through recomposition. Reading it only during
 * composition — or not at all — leaves the canvas recorded once and frozen, which
 * looks exactly like a screenshot of the first frame.
 *
 * Text is drawn one style run at a time rather than one cell at a time: consecutive
 * cells sharing a style become a single `drawText` call, which keeps a full-screen
 * repaint of a boot log inside a frame.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun TerminalView(
    emulator: TerminalEmulator,
    revision: LongState,
    onInput: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
    controller: TerminalController = remember { TerminalController() },
    fontSizeSp: Int = 12,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val textStyle = remember(fontSizeSp) {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp)
    }

    // Every glyph in a monospace face has the same advance, so measuring a run of one
    // character gives the cell metrics for the whole grid.
    val metrics = remember(textStyle, density, measurer) {
        val layout = measurer.measure("M".repeat(20), textStyle)
        CellMetrics(
            width = layout.size.width / 20f,
            height = layout.size.height.toFloat(),
        )
    }

    // Distance scrolled up from the live screen, in pixels. Zero means "following".
    val scrollOffset = remember { mutableFloatStateOf(0f) }

    val scrollState = rememberScrollableState { delta ->
        val maxScroll = (emulator.scrollbackSize * metrics.height).coerceAtLeast(0f)
        val next = (scrollOffset.floatValue - delta).coerceIn(0f, maxScroll)
        val consumed = scrollOffset.floatValue - next
        scrollOffset.floatValue = next
        -consumed
    }

    Box(modifier = modifier.background(TerminalPalette.Background)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Reshaping the grid belongs in the layout phase. Doing it inside the
                // draw block writes state while drawing, which drops frames.
                .onSizeChanged { size ->
                    val columns = floor(size.width / metrics.width).toInt().coerceAtLeast(2)
                    val rows = floor(size.height / metrics.height).toInt().coerceAtLeast(2)
                    if (columns != emulator.columns || rows != emulator.rows) {
                        // Serial bytes arrive on a background thread, so reshaping has
                        // to take the same lock the writer does.
                        synchronized(emulator.lock) { emulator.resize(columns, rows) }
                    }
                }
                .scrollable(scrollState, Orientation.Vertical, reverseDirection = true)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { controller.showKeyboard() })
                },
        ) {
            // Both reads have to happen here, in the draw scope, for this canvas to be
            // repainted when output arrives or the view is scrolled.
            val currentRevision = revision.longValue
            val offset = scrollOffset.floatValue

            val visibleRows = floor(size.height / metrics.height).toInt().coerceAtLeast(1)
            drawTerminal(emulator, measurer, textStyle, metrics, offset, visibleRows, currentRevision)
        }

        // A one-pixel focusable View is the only reliable way to get an IME over a
        // Canvas: it owns the InputConnection and forwards every key to the guest.
        AndroidView(
            factory = { context ->
                TerminalInputView(context, onInput).also { controller.view = it }
            },
            modifier = Modifier.size(1.dp),
            update = { view ->
                view.onInput = onInput
                controller.view = view
            },
        )

        DisposableEffect(controller) {
            onDispose { controller.view = null }
        }
    }
}

private data class CellMetrics(val width: Float, val height: Float)

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawTerminal(
    emulator: TerminalEmulator,
    measurer: TextMeasurer,
    textStyle: TextStyle,
    metrics: CellMetrics,
    scrollOffset: Float,
    visibleRows: Int,
    @Suppress("UNUSED_PARAMETER") revision: Long,
) {
    synchronized(emulator.lock) {
        val scrolledLines = (scrollOffset / metrics.height).roundToInt()
        val bottomLine = emulator.totalRows - scrolledLines
        val firstLine = (bottomLine - visibleRows).coerceAtLeast(0)

        for (screenRow in 0 until visibleRows) {
            val lineIndex = firstLine + screenRow
            if (lineIndex >= emulator.totalRows) break
            drawTerminalLine(
                emulator.lineAt(lineIndex),
                measurer,
                textStyle,
                metrics,
                screenRow * metrics.height,
            )
        }

        // The cursor only means anything while the live screen is in view.
        if (emulator.cursorVisible && scrolledLines == 0) {
            val cursorRow = emulator.totalRows - emulator.rows - firstLine + emulator.cursorRow
            if (cursorRow in 0 until visibleRows) {
                drawRect(
                    color = TerminalPalette.Cursor,
                    topLeft = Offset(
                        emulator.cursorColumn * metrics.width,
                        cursorRow * metrics.height,
                    ),
                    size = Size(metrics.width, metrics.height),
                    alpha = 0.6f,
                )
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawTerminalLine(
    line: TerminalLine,
    measurer: TextMeasurer,
    textStyle: TextStyle,
    metrics: CellMetrics,
    y: Float,
) {
    val last = line.lastUsedColumn()
    if (last < 0) return

    var start = 0
    while (start <= last) {
        val style = TerminalStyle(line.styles[start])
        var end = start + 1
        while (end <= last && line.styles[end] == line.styles[start]) end++

        if (!TerminalPalette.isDefaultBackground(style)) {
            drawRect(
                color = TerminalPalette.backgroundOf(style),
                topLeft = Offset(start * metrics.width, y),
                size = Size((end - start) * metrics.width, metrics.height),
            )
        }

        val builder = StringBuilder(end - start)
        for (i in start until end) builder.appendCodePoint(line.codePoints[i])
        val text = builder.toString()

        if (text.isNotBlank()) {
            drawText(
                textMeasurer = measurer,
                text = text,
                topLeft = Offset(start * metrics.width, y),
                style = textStyle.copy(
                    color = TerminalPalette.foregroundOf(style),
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                ),
            )
        }
        start = end
    }
}

/**
 * Owns the IME connection. Soft keyboards deliver most input as `commitText` rather
 * than key events, so both paths are handled and translated into the byte sequences a
 * serial console expects.
 */
class TerminalInputView(
    context: Context,
    var onInput: (ByteArray) -> Unit,
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL keeps the IME in "dumb key" mode, so autocorrect and composing
        // regions never rewrite what the user typed at a shell prompt.
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.takeIf { it.isNotEmpty() }?.let {
                    onInput(it.toString().toByteArray(Charsets.UTF_8))
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event?.action == KeyEvent.ACTION_DOWN) handleKey(event)
                return true
            }

            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(before) { onInput(byteArrayOf(0x7F)) }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(event) || super.onKeyDown(keyCode, event)

    private fun handleKey(event: KeyEvent): Boolean {
        val sequence = TerminalKeys.encode(event) ?: return false
        onInput(sequence)
        return true
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }
}
