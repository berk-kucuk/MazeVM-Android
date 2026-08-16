package com.mazevm.android.vnc

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A remote framebuffer client speaking RFB 3.8, enough of it to drive a QEMU display.
 *
 * QEMU is told to listen on a unix socket rather than a TCP port, so nothing outside
 * the app sandbox can reach the machine's screen and no port has to be allocated. Raw
 * encoding is preferred over the wire-efficient ones because the "wire" here is a
 * socket on the same device, where memcpy beats decompression.
 */
class RfbClient(
    private val socketPath: File,
    private val scope: CoroutineScope,
) {

    sealed interface Status {
        data object Connecting : Status
        data object Connected : Status
        data class Failed(val reason: String) : Status
        data object Disconnected : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _framebuffer = MutableStateFlow<Framebuffer?>(null)
    val framebuffer: StateFlow<Framebuffer?> = _framebuffer.asStateFlow()

    /** Incremented after every applied update so the UI knows to redraw. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private var socket: LocalSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var loop: Job? = null

    private var pixels = IntArray(0)
    private var width = 0
    private var height = 0

    class Framebuffer(val width: Int, val height: Int, val pixels: IntArray)

    fun connect(timeoutMs: Long = 30_000) {
        if (loop?.isActive == true) return
        loop = scope.launch(Dispatchers.IO) {
            try {
                _status.value = Status.Connecting
                openSocket(timeoutMs)
                handshake()
                _status.value = Status.Connected
                requestUpdate(incremental = false)
                readMessages()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "VNC session ended", error)
                _status.value = Status.Failed(error.message ?: error::class.java.simpleName)
            } finally {
                closeQuietly()
            }
        }
    }

    fun disconnect() {
        loop?.cancel()
        loop = null
        closeQuietly()
        _status.value = Status.Disconnected
    }

    // ------------------------------------------------------------- connection

    private suspend fun openSocket(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (socketPath.exists()) {
                val attempt = runCatching {
                    LocalSocket().apply {
                        connect(
                            LocalSocketAddress(
                                socketPath.absolutePath,
                                LocalSocketAddress.Namespace.FILESYSTEM,
                            )
                        )
                    }
                }.getOrNull()

                if (attempt != null) {
                    socket = attempt
                    input = DataInputStream(attempt.inputStream.buffered(BUFFER))
                    output = DataOutputStream(attempt.outputStream.buffered(BUFFER))
                    return
                }
            }
            delay(200)
        }
        error("the display socket never appeared")
    }

    private fun handshake() {
        val stream = input!!
        val out = output!!

        val version = ByteArray(12).also { stream.readFully(it) }
            .toString(Charsets.US_ASCII)
        require(version.startsWith("RFB ")) { "not an RFB server" }
        // Answer with 3.8 regardless of what the server offered; QEMU speaks it.
        out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        out.flush()

        val securityCount = stream.readUnsignedByte()
        if (securityCount == 0) {
            val reason = stream.readUTF8Block()
            error("server refused the connection: $reason")
        }
        val types = ByteArray(securityCount).also { stream.readFully(it) }
        val chosen = types.map { it.toInt() and 0xFF }
        // MazeVM starts QEMU without a VNC password, so None is always on offer.
        require(SECURITY_NONE in chosen) {
            "this display needs authentication, which MazeVM does not configure"
        }
        out.writeByte(SECURITY_NONE)
        out.flush()

        val result = stream.readInt()
        if (result != 0) {
            val reason = runCatching { stream.readUTF8Block() }.getOrDefault("unknown")
            error("authentication failed: $reason")
        }

        out.writeByte(1) // shared session
        out.flush()

        width = stream.readUnsignedShort()
        height = stream.readUnsignedShort()
        stream.skipFully(16) // server pixel format, replaced below
        val nameLength = stream.readInt()
        stream.skipFully(nameLength)

        allocate(width, height)
        sendPixelFormat()
        sendEncodings()
    }

    private fun allocate(newWidth: Int, newHeight: Int) {
        width = newWidth
        height = newHeight
        pixels = IntArray(newWidth * newHeight)
        _framebuffer.value = Framebuffer(newWidth, newHeight, pixels)
    }

    /**
     * Asks for 32-bit true colour with red in the high byte, so a little-endian read of
     * each pixel lands directly in Android's ARGB_8888 layout with no shuffling.
     */
    private fun sendPixelFormat() {
        val out = output!!
        out.writeByte(0) // SetPixelFormat
        out.write(ByteArray(3)) // padding
        out.writeByte(32) // bits per pixel
        out.writeByte(24) // depth
        out.writeByte(0) // little endian
        out.writeByte(1) // true colour
        out.writeShort(255) // red max
        out.writeShort(255) // green max
        out.writeShort(255) // blue max
        out.writeByte(16) // red shift
        out.writeByte(8) // green shift
        out.writeByte(0) // blue shift
        out.write(ByteArray(3)) // padding
        out.flush()
    }

    private fun sendEncodings() {
        val encodings = intArrayOf(
            ENCODING_COPY_RECT,
            ENCODING_HEXTILE,
            ENCODING_RAW,
            ENCODING_DESKTOP_SIZE,
        )
        val out = output!!
        out.writeByte(2) // SetEncodings
        out.writeByte(0) // padding
        out.writeShort(encodings.size)
        encodings.forEach { out.writeInt(it) }
        out.flush()
    }

    // ---------------------------------------------------------------- messages

    private suspend fun readMessages() {
        val stream = input!!
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            when (val type = stream.read()) {
                -1 -> error("display closed the connection")
                0 -> {
                    readFramebufferUpdate(stream)
                    _revision.value = _revision.value + 1
                    requestUpdate(incremental = true)
                }
                1 -> skipColourMap(stream)
                2 -> Unit // bell
                3 -> skipServerCutText(stream)
                else -> error("unexpected message type $type")
            }
        }
    }

    private fun readFramebufferUpdate(stream: DataInputStream) {
        stream.skipFully(1) // padding
        val rectangles = stream.readUnsignedShort()

        // The renderer copies this array out on the UI thread, so an update has to be
        // applied as one atomic batch or a frame can be drawn half-decoded.
        synchronized(pixels) { readRectangles(stream, rectangles) }
    }

    private fun readRectangles(stream: DataInputStream, rectangles: Int) {
        repeat(rectangles) {
            val x = stream.readUnsignedShort()
            val y = stream.readUnsignedShort()
            val w = stream.readUnsignedShort()
            val h = stream.readUnsignedShort()

            when (val encoding = stream.readInt()) {
                ENCODING_RAW -> readRaw(stream, x, y, w, h)
                ENCODING_COPY_RECT -> readCopyRect(stream, x, y, w, h)
                ENCODING_HEXTILE -> readHextile(stream, x, y, w, h)
                ENCODING_DESKTOP_SIZE -> allocate(w, h)
                else -> error("unsupported encoding $encoding")
            }
        }
    }

    private fun readRaw(stream: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val row = ByteArray(w * 4)
        for (line in 0 until h) {
            stream.readFully(row)
            val target = (y + line) * width + x
            if (target < 0 || target + w > pixels.size) continue
            var source = 0
            for (column in 0 until w) {
                pixels[target + column] = ALPHA or
                    ((row[source + 2].toInt() and 0xFF) shl 16) or
                    ((row[source + 1].toInt() and 0xFF) shl 8) or
                    (row[source].toInt() and 0xFF)
                source += 4
            }
        }
    }

    private fun readCopyRect(stream: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val sourceX = stream.readUnsignedShort()
        val sourceY = stream.readUnsignedShort()
        // Copying downwards or rightwards over itself has to run backwards, which is
        // exactly the case a scrolling terminal produces.
        val rows = if (sourceY < y) (h - 1) downTo 0 else 0 until h
        for (line in rows) {
            val from = (sourceY + line) * width + sourceX
            val to = (y + line) * width + x
            if (from < 0 || to < 0 || from + w > pixels.size || to + w > pixels.size) continue
            System.arraycopy(pixels, from, pixels, to, w)
        }
    }

    /**
     * Hextile splits a rectangle into 16x16 tiles, each either raw or a background plus
     * a list of coloured sub-rectangles. QEMU falls back to it for large solid areas,
     * where it is far cheaper than raw.
     */
    private fun readHextile(stream: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        var background = 0
        var foreground = 0

        var tileY = y
        while (tileY < y + h) {
            val tileHeight = minOf(16, y + h - tileY)
            var tileX = x
            while (tileX < x + w) {
                val tileWidth = minOf(16, x + w - tileX)
                val mask = stream.readUnsignedByte()

                if (mask and HEXTILE_RAW != 0) {
                    readRaw(stream, tileX, tileY, tileWidth, tileHeight)
                    tileX += tileWidth
                    continue
                }

                if (mask and HEXTILE_BACKGROUND != 0) background = stream.readPixel()
                if (mask and HEXTILE_FOREGROUND != 0) foreground = stream.readPixel()

                fill(tileX, tileY, tileWidth, tileHeight, background)

                if (mask and HEXTILE_SUBRECTS != 0) {
                    val count = stream.readUnsignedByte()
                    val coloured = mask and HEXTILE_SUBRECTS_COLOURED != 0
                    repeat(count) {
                        val colour = if (coloured) stream.readPixel() else foreground
                        val position = stream.readUnsignedByte()
                        val dimensions = stream.readUnsignedByte()
                        fill(
                            tileX + (position shr 4),
                            tileY + (position and 0x0F),
                            (dimensions shr 4) + 1,
                            (dimensions and 0x0F) + 1,
                            colour,
                        )
                    }
                }
                tileX += tileWidth
            }
            tileY += tileHeight
        }
    }

    private fun fill(x: Int, y: Int, w: Int, h: Int, colour: Int) {
        for (line in 0 until h) {
            val start = (y + line) * width + x
            if (start < 0 || start + w > pixels.size) continue
            java.util.Arrays.fill(pixels, start, start + w, colour)
        }
    }

    private fun DataInputStream.readPixel(): Int {
        val b = readUnsignedByte()
        val g = readUnsignedByte()
        val r = readUnsignedByte()
        skipFully(1)
        return ALPHA or (r shl 16) or (g shl 8) or b
    }

    private fun skipColourMap(stream: DataInputStream) {
        stream.skipFully(3)
        val count = stream.readUnsignedShort()
        stream.skipFully(count * 6)
    }

    private fun skipServerCutText(stream: DataInputStream) {
        stream.skipFully(3)
        val length = stream.readInt()
        stream.skipFully(length)
    }

    private fun requestUpdate(incremental: Boolean) {
        val out = output ?: return
        synchronized(out) {
            out.writeByte(3) // FramebufferUpdateRequest
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(width)
            out.writeShort(height)
            out.flush()
        }
    }

    // ------------------------------------------------------------------- input

    /** [buttons] is a bitmask: 1 left, 2 middle, 4 right, 8/16 wheel up/down. */
    fun sendPointer(x: Int, y: Int, buttons: Int) {
        val out = output ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                synchronized(out) {
                    out.writeByte(5) // PointerEvent
                    out.writeByte(buttons)
                    out.writeShort(x.coerceIn(0, maxOf(0, width - 1)))
                    out.writeShort(y.coerceIn(0, maxOf(0, height - 1)))
                    out.flush()
                }
            }
        }
    }

    fun sendKey(keysym: Int, pressed: Boolean) {
        val out = output ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                synchronized(out) {
                    out.writeByte(4) // KeyEvent
                    out.writeByte(if (pressed) 1 else 0)
                    out.writeShort(0)
                    out.writeInt(keysym)
                    out.flush()
                }
            }
        }
    }

    fun tapKey(keysym: Int) {
        sendKey(keysym, pressed = true)
        sendKey(keysym, pressed = false)
    }

    fun sendText(text: String) {
        text.forEach { character -> tapKey(Keysyms.of(character)) }
    }

    /** Ctrl+Alt+Del, which is the only chord worth a dedicated button. */
    fun sendCtrlAltDel() {
        sendKey(Keysyms.CONTROL_L, true)
        sendKey(Keysyms.ALT_L, true)
        sendKey(Keysyms.DELETE, true)
        sendKey(Keysyms.DELETE, false)
        sendKey(Keysyms.ALT_L, false)
        sendKey(Keysyms.CONTROL_L, false)
    }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private companion object {
        const val TAG = "RfbClient"
        const val BUFFER = 1 shl 16
        const val ALPHA = 0xFF shl 24

        const val SECURITY_NONE = 1

        const val ENCODING_RAW = 0
        const val ENCODING_COPY_RECT = 1
        const val ENCODING_HEXTILE = 5
        const val ENCODING_DESKTOP_SIZE = -223

        const val HEXTILE_RAW = 0x01
        const val HEXTILE_BACKGROUND = 0x02
        const val HEXTILE_FOREGROUND = 0x04
        const val HEXTILE_SUBRECTS = 0x08
        const val HEXTILE_SUBRECTS_COLOURED = 0x10
    }
}

/**
 * Discards exactly [count] bytes.
 *
 * `DataInputStream.skipBytes` is allowed to skip fewer bytes than asked for, and does
 * so routinely on a socket whose buffer has run dry. A short skip here desynchronises
 * the whole RFB stream, which shows up as a display that never appears rather than as
 * an obvious error, so every skip has to be this one.
 */
private fun DataInputStream.skipFully(count: Int) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining.toLong()).toInt()
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        // skip() returning zero does not mean end of stream; a blocking read does.
        if (read() < 0) throw java.io.EOFException("stream ended while skipping")
        remaining--
    }
}

private fun DataInputStream.readUTF8Block(): String {
    val length = readInt()
    val bytes = ByteArray(length.coerceIn(0, 4096))
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}

private fun InputStream.buffered(size: Int) = java.io.BufferedInputStream(this, size)

private fun OutputStream.buffered(size: Int) = java.io.BufferedOutputStream(this, size)
