package com.mazevm.android.container

import android.content.Context
import android.util.Log
import com.mazevm.android.data.model.ContainerConfig
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

/**
 * One running container.
 *
 * Deliberately shaped like [com.mazevm.android.qemu.QemuProcess] — same state flow,
 * same byte-level output flow — so the console screen can drive either without caring
 * which backend it is looking at.
 */
class ContainerProcess(
    val config: ContainerConfig,
    private val runtime: ProotRuntime,
    private val scope: CoroutineScope,
) {

    private var session: Pty.Session? = null
    private var reader: Job? = null
    private var waiter: Job? = null

    private val _state = MutableStateFlow(VmState.STOPPED)
    val state: StateFlow<VmState> = _state.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val _output = MutableSharedFlow<ByteArray>(replay = 64, extraBufferCapacity = 256)
    val output: SharedFlow<ByteArray> = _output.asSharedFlow()

    var startedAtMillis: Long = 0L
        private set

    /** The proot invocation, shown on the log tab. */
    var commandLine: String = ""
        private set

    suspend fun start(columns: Int = 80, rows: Int = 24): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(session == null) { "already started" }
                _state.value = VmState.STARTING
                _failure.value = null

                val plan = runtime.plan(config)
                commandLine = plan.render()

                Log.i(TAG, "starting ${config.name}: $commandLine")

                val started = Pty.start(
                    executable = plan.executable,
                    argv = plan.argv,
                    environment = plan.environment,
                    workingDir = null,
                    columns = columns,
                    rows = rows,
                )
                session = started
                startedAtMillis = System.currentTimeMillis()

                reader = scope.launch(Dispatchers.IO) { pump(started) }
                waiter = scope.launch(Dispatchers.IO) { awaitExit(started) }

                _state.value = VmState.RUNNING
            }.onFailure {
                _state.value = VmState.FAILED
                _failure.value = it.message ?: it::class.java.simpleName
            }
        }

    private suspend fun pump(session: Pty.Session) {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val read = session.input.read(buffer)
                if (read <= 0) break
                _output.emit(buffer.copyOf(read))
            }
        } catch (_: Exception) {
            // The terminal closing is how this loop normally ends.
        }
    }

    private fun awaitExit(session: Pty.Session) {
        val code = Pty.waitFor(session.pid)
        _exitCode.value = code
        _state.value = if (code == 0) VmState.STOPPED else VmState.FAILED
        if (code != 0) _failure.value = "exit code $code"
        session.close()
    }

    fun write(bytes: ByteArray) {
        val out = session?.output ?: return
        runCatching {
            out.write(bytes)
            out.flush()
        }.onFailure { Log.w(TAG, "write failed", it) }
    }

    /** Tells the guest the window changed so full-screen programs reflow. */
    fun resize(columns: Int, rows: Int) {
        session?.let { runCatching { it.resize(columns, rows) } }
    }

    suspend fun stop() {
        _state.value = VmState.STOPPING
        session?.stop(force = false)
        kotlinx.coroutines.delay(2_000)
        if (_state.value.isActive) forceStop()
    }

    suspend fun forceStop() {
        _state.value = VmState.STOPPING
        session?.stop(force = true)
        kotlinx.coroutines.delay(300)
    }

    fun dispose() {
        reader?.cancel()
        waiter?.cancel()
        session?.stop(force = true)
        session?.close()
        session = null
    }

    private companion object {
        const val TAG = "ContainerProcess"
    }
}

/**
 * Builds the proot invocation for a container.
 *
 * proot is shipped as `libproot.so` in the native library directory, which is the one
 * app-owned place Android still allows execution from. The guest's own binaries live
 * in the app's data directory, where it does not, so `libmaze-exec.so` is preloaded to
 * route their exec through the system linker.
 */
class ProotRuntime(private val context: Context) {

    class Plan(
        val executable: String,
        val argv: List<String>,
        val environment: List<String>,
    ) {
        fun render(): String = argv.joinToString(" ") {
            if (it.any(Char::isWhitespace)) "'$it'" else it
        }
    }

    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    val prootBinary: File get() = nativeDir.resolve("libproot.so")

    /**
     * proot's own loader, prebuilt and installed into nativeLibraryDir by
     * tools/build-container-runtime.sh. It has to live here rather than being
     * self-extracted by proot at runtime: nativeLibraryDir is the only location
     * Android still permits execution from on targetSdk 29+, and proot's default
     * self-extraction target (PROOT_TMP_DIR, cache storage) is not it.
     */
    private val loaderBinary: File get() = nativeDir.resolve("libproot-loader.so")

    val isAvailable: Boolean get() = prootBinary.canExecute() && loaderBinary.canExecute()

    fun plan(config: ContainerConfig): Plan {
        check(isAvailable) { "the container runtime is not present in this build" }

        val rootfs = File(config.rootfsPath)
        check(rootfs.isDirectory) { "the container filesystem is missing" }

        val argv = buildList {
            add(prootBinary.absolutePath)

            // -0 makes the guest believe it is root, which every package manager
            // insists on. It is a lie told inside proot; nothing is actually elevated.
            add("-0")
            add("-r"); add(rootfs.absolutePath)
            add("-w"); add(config.workingDir.ifBlank { "/" })

            // Android's own /proc, /sys and /dev are bound in: the image ships those
            // directories empty, and a shell without /dev/null or /proc is unusable.
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-b"); add("/dev")
            add("-b"); add("/dev/urandom:/dev/random")

            // Android has no /etc/resolv.conf to bind, so ImagePuller writes one into
            // the rootfs at pull time instead.

            config.binds.forEach { add("-b"); add(it) }

            // Link-to-symlink keeps package managers working: bionic's hard links fail
            // on some Android filesystems, and dpkg leans on them heavily.
            add("--link2symlink")

            // Stands in for memfd_create, which bionic does not expose to apps, by
            // backing it with ashmem. Anything using shared memory inside the container
            // fails without it.
            add("--ashmem-memfd")

            add("--kill-on-exit")

            addAll(config.command.ifEmpty { listOf("/bin/sh") })
        }

        val environment = buildList {
            // PROOT_LOADER must point at a real, executable file: proot takes it on
            // faith with no existence check and substitutes it straight into the
            // traced process's execve, so a bad path here doesn't fail cleanly, it
            // corrupts the exec and comes out as an opaque "execve: Bad address" with
            // nothing pointing back at the loader as the cause. Leaving it unset is
            // not a safe fallback either: proot would then self-extract its embedded
            // loader into PROOT_TMP_DIR (cache storage), and Android 10+ refuses to
            // execute anything outside nativeLibraryDir regardless of chmod, so a
            // self-extracted loader can be written but never run.
            add("PROOT_LOADER=${loaderBinary.absolutePath}")
            add("PROOT_TMP_DIR=${context.cacheDir.absolutePath}")
            add("HOME=/root")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")

            // No LD_PRELOAD here. An exec shim was tried, to route execve through the
            // system linker and get around Android refusing to execute anything in the
            // app's data directory. It turned out to be unnecessary: proot never has
            // the kernel exec a guest binary at all — it substitutes its own loader,
            // which lives in nativeLibraryDir where execution is allowed, and the
            // loader maps the guest ELF itself. Worse, the variable is inherited by
            // every process in the container, where the path is outside the rootfs and
            // the library is a bionic object the guest's glibc loader could never load,
            // so every single command printed an ld.so error before running.

            // Whatever the image declared wins over the defaults above.
            addAll(config.environment)
        }

        return Plan(
            executable = prootBinary.absolutePath,
            argv = argv,
            environment = environment,
        )
    }
}
