package com.mazevm.android.vnc

import android.content.Context
import android.graphics.Bitmap
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.min
import kotlin.math.roundToInt

/** How a finger on the screen becomes a mouse in the guest. */
enum class TouchMode {
    /** Tapping puts the guest pointer exactly where the finger landed. */
    Direct,

    /** Dragging moves the pointer relatively, like a laptop trackpad. */
    Trackpad,
}

/** Lets a screen open the keyboard without owning the hidden input view. */
class VncController {
    internal var view: VncInputView? = null

    fun showKeyboard() = view?.showKeyboard()
    fun hideKeyboard() = view?.hideKeyboard()
}

/**
 * Draws the guest framebuffer and turns touches and keystrokes into RFB events.
 *
 * The framebuffer revision is read inside the draw block rather than during
 * composition, so an updated screen repaints without a recomposition pass. Reading it
 * only in composition would still work but would rebuild the whole subtree for every
 * frame the guest sends.
 */
@Composable
fun VncView(
    client: RfbClient,
    modifier: Modifier = Modifier,
    controller: VncController = remember { VncController() },
    touchMode: TouchMode = TouchMode.Direct,
    fitToScreen: Boolean = true,
) {
    val framebuffer by client.framebuffer.collectAsStateWithLifecycle()
    val revisionState = client.revision.collectAsStateWithLifecycle()

    val bitmap = remember(framebuffer?.width, framebuffer?.height) {
        framebuffer?.let {
            Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    // The guest pointer in framebuffer coordinates. Trackpad mode moves it relatively,
    // so it has to persist between gestures.
    var pointer by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { viewport = it }
            .pointerInput(touchMode, framebuffer?.width, viewport, scale, pan, fitToScreen) {
                val frame = framebuffer ?: return@pointerInput
                detectTapGestures(
                    onTap = { position ->
                        controller.showKeyboard()
                        val target = resolveTap(
                            position, touchMode, pointer, frame, viewport, scale, pan, fitToScreen,
                        )
                        pointer = target
                        client.click(target, button = 1)
                    },
                    onLongPress = { position ->
                        val target = resolveTap(
                            position, touchMode, pointer, frame, viewport, scale, pan, fitToScreen,
                        )
                        pointer = target
                        client.click(target, button = 4)
                    },
                )
            }
            .pointerInput(touchMode, framebuffer?.width, viewport, scale, pan, fitToScreen) {
                val frame = framebuffer ?: return@pointerInput
                detectDragGestures(
                    onDragStart = { position ->
                        if (touchMode == TouchMode.Direct) {
                            pointer = toFramebuffer(
                                position, frame, viewport, scale, pan, fitToScreen,
                            )
                        }
                        client.sendPointer(pointer.x.roundToInt(), pointer.y.roundToInt(), 1)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        pointer = when (touchMode) {
                            TouchMode.Direct -> toFramebuffer(
                                change.position, frame, viewport, scale, pan, fitToScreen,
                            )
                            TouchMode.Trackpad -> Offset(
                                (pointer.x + dragAmount.x * TRACKPAD_SPEED)
                                    .coerceIn(0f, (frame.width - 1).toFloat()),
                                (pointer.y + dragAmount.y * TRACKPAD_SPEED)
                                    .coerceIn(0f, (frame.height - 1).toFloat()),
                            )
                        }
                        client.sendPointer(pointer.x.roundToInt(), pointer.y.roundToInt(), 1)
                    },
                    onDragEnd = {
                        client.sendPointer(pointer.x.roundToInt(), pointer.y.roundToInt(), 0)
                    },
                    onDragCancel = {
                        client.sendPointer(pointer.x.roundToInt(), pointer.y.roundToInt(), 0)
                    },
                )
            }
            .pointerInput(fitToScreen) {
                if (fitToScreen) return@pointerInput
                detectTransformGestures { _, panChange, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    pan += panChange
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val frame = framebuffer ?: return@Canvas
            val target = bitmap ?: return@Canvas

            // Reading the revision here is what ties a repaint to an applied update.
            @Suppress("UNUSED_VARIABLE") val revision = revisionState.value

            // The decoder writes into this array from its own thread between updates.
            synchronized(frame.pixels) {
                target.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            }

            val layout = layoutFor(frame, viewport, scale, pan, fitToScreen)
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(
                    target,
                    android.graphics.Rect(0, 0, frame.width, frame.height),
                    android.graphics.RectF(
                        layout.left,
                        layout.top,
                        layout.left + layout.width,
                        layout.top + layout.height,
                    ),
                    null,
                )
            }
        }

        // Same trick as the terminal: a one-pixel focusable View owns the IME so the
        // guest can be typed at. Without it the display is pointer-only.
        AndroidView(
            factory = { context ->
                VncInputView(context, client).also { controller.view = it }
            },
            modifier = Modifier.size(1.dp),
            update = { view ->
                view.client = client
                controller.view = view
            },
        )

        DisposableEffect(controller) {
            onDispose { controller.view = null }
        }
    }
}

private const val TRACKPAD_SPEED = 1.4f

/** Press and release at [position], which is what a tap means to a guest. */
private fun RfbClient.click(position: Offset, button: Int) {
    val x = position.x.roundToInt()
    val y = position.y.roundToInt()
    sendPointer(x, y, 0)
    sendPointer(x, y, button)
    sendPointer(x, y, 0)
}

private data class Layout(val left: Float, val top: Float, val width: Float, val height: Float)

/** Letterboxes the framebuffer inside the viewport, honouring pinch and pan. */
private fun layoutFor(
    frame: RfbClient.Framebuffer,
    viewport: IntSize,
    scale: Float,
    pan: Offset,
    fitToScreen: Boolean,
): Layout {
    if (viewport.width == 0 || viewport.height == 0) {
        return Layout(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
    }
    val fit = min(
        viewport.width.toFloat() / frame.width,
        viewport.height.toFloat() / frame.height,
    )
    val effective = if (fitToScreen) fit else fit * scale
    val width = frame.width * effective
    val height = frame.height * effective
    val left = (viewport.width - width) / 2f + if (fitToScreen) 0f else pan.x
    val top = (viewport.height - height) / 2f + if (fitToScreen) 0f else pan.y
    return Layout(left, top, width, height)
}

private fun toFramebuffer(
    position: Offset,
    frame: RfbClient.Framebuffer,
    viewport: IntSize,
    scale: Float,
    pan: Offset,
    fitToScreen: Boolean,
): Offset {
    val layout = layoutFor(frame, viewport, scale, pan, fitToScreen)
    if (layout.width <= 0f || layout.height <= 0f) return Offset.Zero
    val x = (position.x - layout.left) / layout.width * frame.width
    val y = (position.y - layout.top) / layout.height * frame.height
    return Offset(
        x.coerceIn(0f, (frame.width - 1).toFloat()),
        y.coerceIn(0f, (frame.height - 1).toFloat()),
    )
}

private fun resolveTap(
    position: Offset,
    mode: TouchMode,
    currentPointer: Offset,
    frame: RfbClient.Framebuffer,
    viewport: IntSize,
    scale: Float,
    pan: Offset,
    fitToScreen: Boolean,
): Offset = when (mode) {
    TouchMode.Direct -> toFramebuffer(position, frame, viewport, scale, pan, fitToScreen)
    // In trackpad mode a tap clicks wherever the pointer already is, like a laptop.
    TouchMode.Trackpad -> currentPointer
}

/**
 * Carries soft-keyboard input to the guest as RFB key events.
 *
 * Unlike the terminal, which wants byte sequences, this has to send keysyms with
 * explicit press and release, and hold modifiers down around the keys they modify.
 */
class VncInputView(
    context: Context,
    var client: RfbClient,
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.takeIf { it.isNotEmpty() }?.let { client.sendText(it.toString()) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event?.action == KeyEvent.ACTION_DOWN) handleKey(event)
                return true
            }

            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(before) { client.tapKey(Keysyms.BACKSPACE) }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(event) || super.onKeyDown(keyCode, event)

    private fun handleKey(event: KeyEvent): Boolean {
        val keysym = Keysyms.fromKeyEvent(event) ?: return false
        val control = event.isCtrlPressed
        val alt = event.isAltPressed

        if (control) client.sendKey(Keysyms.CONTROL_L, pressed = true)
        if (alt) client.sendKey(Keysyms.ALT_L, pressed = true)
        client.tapKey(keysym)
        if (alt) client.sendKey(Keysyms.ALT_L, pressed = false)
        if (control) client.sendKey(Keysyms.CONTROL_L, pressed = false)
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
