package com.mazevm.android.qemu

import android.util.Log
import com.mazevm.android.data.model.VmConfig
import com.mazevm.android.data.model.VmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * One running QEMU child process.
 *
 * The guest's serial console rides on the process pipes: QEMU is started with
 * `-serial stdio`, so its stdout carries console bytes and its stdin is the guest
 * keyboard. Diagnostics land on stderr and are mirrored to a log file, and control
 * (shutdown, reset, eject) goes over QMP, deliberately on a different channel so a
 * chatty guest cannot interfere with it.
 */
class QemuProcess(
    val vm: VmConfig,
    private val argv: List<String>,
    private val environment: Map<String, String>,
    val sockets: VmSockets,
    private val logFile: File,
    private val scope: CoroutineScope,
) {

    private var process: Process? = null
    private var stdin: OutputStream? = null
    private val readers = mutableListOf<Job>()

    val qmp = QmpClient(sockets.qmp)

    private val _state = MutableStateFlow(VmState.STOPPED)
    val state: StateFlow<VmState> = _state.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    /** Raw guest console bytes. Replays the last chunks so a reopened screen is not blank. */
    private val _serialOutput = MutableSharedFlow<ByteArray>(
        replay = 64,
        extraBufferCapacity = 256,
    )
    val serialOutput: SharedFlow<ByteArray> = _serialOutput.asSharedFlow()

    /** QEMU's own stderr, shown on the log tab. */
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    var startedAtMillis: Long = 0L
        private set

    /** The command line, for the log tab and for bug reports. */
    val commandLine: String get() = QemuCommand.render(argv)

    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(process == null) { "already started" }
            _state.value = VmState.STARTING
            _failure.value = null

            // Leftover sockets from a crashed run would make QEMU refuse to bind.
            sockets.qmp.delete()
            sockets.vnc?.delete()
            sockets.qmp.parentFile?.mkdirs()

            logFile.parentFile?.mkdirs()
            logFile.writeText(commandLine + "\n\n")
            _log.value = logFile.readText()

            val builder = ProcessBuilder(argv)
                .directory(logFile.parentFile)
                .redirectErrorStream(false)
            builder.environment().putAll(environment)

            val started = builder.start()
            process = started
            stdin = started.outputStream
            startedAtMillis = System.currentTimeMillis()

            readers += scope.launch(Dispatchers.IO) { pumpSerial(started) }
            readers += scope.launch(Dispatchers.IO) { pumpStderr(started) }
            readers += scope.launch(Dispatchers.IO) { awaitExit(started) }

            scope.launch {
                qmp.connect().onFailure {
                    Log.w(TAG, "QMP unavailable for ${vm.name}", it)
                }
            }

            _state.value = VmState.RUNNING
        }.onFailure {
            _state.value = VmState.FAILED
            _failure.value = it.message ?: it::class.java.simpleName
        }
    }

    private suspend fun pumpSerial(process: Process) {
        val buffer = ByteArray(4096)
        val input = process.inputStream
        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                _serialOutput.emit(buffer.copyOf(read))
            }
        } catch (_: Exception) {
            // The stream closing is the normal way this loop ends.
        }
    }

    private fun pumpStderr(process: Process) {
        try {
            process.errorStream.bufferedReader().forEachLine { line ->
                appendLog(line)
            }
        } catch (_: Exception) {
        }
    }

    private fun appendLog(line: String) {
        synchronized(this) {
            runCatching { logFile.appendText(line + "\n") }
            val next = _log.value + line + "\n"
            // Keep the in-memory copy bounded; the file on disk keeps everything.
            _log.value = if (next.length > LOG_MEMORY_LIMIT) {
                next.takeLast(LOG_MEMORY_LIMIT)
            } else {
                next
            }
        }
    }

    private fun awaitExit(process: Process) {
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        _exitCode.value = code
        _state.value = if (code == 0) VmState.STOPPED else VmState.FAILED
        if (code != 0) {
            _failure.value = "exit code $code"
            appendLog("\n[MazeVM] QEMU exited with code $code")
        }
        qmp.close()
        sockets.qmp.delete()
        sockets.vnc?.delete()
    }

    /** Sends bytes to the guest's serial console. */
    fun writeSerial(bytes: ByteArray) {
        val out = stdin ?: return
        runCatching {
            out.write(bytes)
            out.flush()
        }.onFailure { Log.w(TAG, "serial write failed", it) }
    }

    fun writeSerial(text: String) = writeSerial(text.toByteArray(Charsets.UTF_8))

    /** Asks the guest to shut down, then kills QEMU if it has not exited in time. */
    suspend fun stop(graceMillis: Long = 20_000) {
        val running = process ?: return
        _state.value = VmState.STOPPING

        qmp.powerDown()

        val deadline = System.currentTimeMillis() + graceMillis
        while (running.isAlive && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(250)
        }
        if (running.isAlive) forceStop()
    }

    /** Immediate termination. The guest gets no chance to flush. */
    suspend fun forceStop() {
        _state.value = VmState.STOPPING
        qmp.quit()
        kotlinx.coroutines.delay(500)
        process?.takeIf { it.isAlive }?.destroy()
        kotlinx.coroutines.delay(500)
        process?.takeIf { it.isAlive }?.destroyForcibly()
    }

    fun dispose() {
        readers.forEach { it.cancel() }
        readers.clear()
        qmp.close()
        runCatching { stdin?.close() }
        process?.takeIf { it.isAlive }?.destroyForcibly()
        process = null
    }

    private companion object {
        const val TAG = "QemuProcess"
        const val LOG_MEMORY_LIMIT = 256 * 1024
    }
}
