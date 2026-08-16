package com.mazevm.android.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.OutputStreamWriter

/**
 * Minimal QEMU Machine Protocol client over a unix socket.
 *
 * Only the handful of commands MazeVM issues are modelled. QMP is line-delimited JSON,
 * and asynchronous events are interleaved with command replies, so [request] skips any
 * line carrying an `event` key while waiting for its answer.
 */
class QmpClient(private val socketPath: File) {

    private var socket: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * QEMU creates the socket a moment after the process starts, so connection is
     * retried until [timeoutMs] elapses.
     */
    suspend fun connect(timeoutMs: Long = 15_000): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastError: Exception? = null

            while (System.currentTimeMillis() < deadline) {
                if (socketPath.exists()) {
                    try {
                        val local = LocalSocket()
                        local.connect(
                            LocalSocketAddress(
                                socketPath.absolutePath,
                                LocalSocketAddress.Namespace.FILESYSTEM,
                            )
                        )
                        socket = local
                        reader = local.inputStream.bufferedReader()
                        writer = OutputStreamWriter(local.outputStream)

                        // Greeting first, then leave negotiation mode.
                        reader?.readLine()
                        request("qmp_capabilities")
                        return@runCatching
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                delay(150)
            }
            throw lastError ?: IllegalStateException("QMP socket never appeared")
        }
    }

    /** Sends a command and returns its `return` payload, or null for a void reply. */
    suspend fun request(
        command: String,
        arguments: Map<String, Any>? = null,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val out = writer ?: return@withContext null
        val input = reader ?: return@withContext null

        val payload = JSONObject().put("execute", command)
        arguments?.let { payload.put("arguments", JSONObject(it)) }

        out.write(payload.toString())
        out.write("\n")
        out.flush()

        var result: JSONObject? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (json.has("event")) continue
            if (json.has("error")) {
                throw QmpException(json.getJSONObject("error").optString("desc", command))
            }
            if (json.has("return")) {
                result = json.optJSONObject("return")
                break
            }
        }
        result
    }

    /** ACPI shutdown request. The guest decides whether to honour it. */
    suspend fun powerDown() = runCatching { request("system_powerdown") }

    /** Kills the machine immediately, losing anything not flushed to disk. */
    suspend fun quit() = runCatching { request("quit") }

    suspend fun reset() = runCatching { request("system_reset") }

    suspend fun pause() = runCatching { request("stop") }

    suspend fun resume() = runCatching { request("cont") }

    /** Removes the installer disc so the machine boots from disk on the next reset. */
    suspend fun ejectCdrom(deviceId: String = "cd0") =
        runCatching { request("eject", mapOf("id" to deviceId, "force" to true)) }

    fun close() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { socket?.close() }
        writer = null
        reader = null
        socket = null
    }

    class QmpException(message: String) : Exception(message)
}
