package com.mazevm.android.container

import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream

/**
 * A pseudo-terminal, backed by bionic's forkpty.
 *
 * The QEMU backend gets its console from the emulator's own serial port, but a
 * container is just a process, and a process on a pipe is not a terminal: isatty()
 * says no, so the shell turns off line editing, job control and colour, and nothing
 * full-screen works at all. This gives the child a real tty instead.
 */
object Pty {

    init {
        System.loadLibrary("mazepty")
    }

    @JvmStatic
    private external fun forkExec(
        executable: String,
        argv: Array<String>,
        envp: Array<String>,
        workingDir: String?,
        columns: Int,
        rows: Int,
        pidOut: IntArray,
    ): Int

    @JvmStatic
    external fun resize(fd: Int, columns: Int, rows: Int)

    @JvmStatic
    external fun waitFor(pid: Int): Int

    @JvmStatic
    external fun terminate(pid: Int, force: Boolean)

    @JvmStatic
    external fun closeFd(fd: Int)

    @JvmStatic
    external fun dupFd(fd: Int): Int

    /**
     * A running child together with the two ends of its terminal.
     *
     * The read and write sides get separate duplicated descriptors on purpose. Every
     * stream that wraps a descriptor expects to own it and closes it, so pointing both
     * at one descriptor closes it twice — and since Android 10 fdsan aborts the entire
     * process when a descriptor is closed by anything other than its recorded owner,
     * which shows up as the app dying outright rather than as an exception.
     */
    class Session(
        val pid: Int,
        private val readSide: ParcelFileDescriptor,
        private val writeSide: ParcelFileDescriptor,
    ) {
        val fd: Int get() = readSide.fd

        /** Guest output. Reads block until the child writes or exits. */
        val input: InputStream = ParcelFileDescriptor.AutoCloseInputStream(readSide)

        /** Guest keyboard. */
        val output: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)

        fun resize(columns: Int, rows: Int) = resize(fd, columns, rows)

        fun stop(force: Boolean = false) = terminate(pid, force)

        fun close() {
            // Each stream owns exactly one descriptor, so closing the streams is what
            // releases both. The descriptors are not closed again here.
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    fun start(
        executable: String,
        argv: List<String>,
        environment: List<String>,
        workingDir: String? = null,
        columns: Int = 80,
        rows: Int = 24,
    ): Session {
        val pidOut = IntArray(1)
        val fd = forkExec(
            executable = executable,
            argv = argv.toTypedArray(),
            envp = environment.toTypedArray(),
            workingDir = workingDir,
            columns = columns,
            rows = rows,
            pidOut = pidOut,
        )
        check(fd >= 0) { "could not start a pseudo-terminal" }

        val duplicate = dupFd(fd)
        if (duplicate < 0) {
            closeFd(fd)
            error("could not duplicate the terminal descriptor")
        }

        // adoptFd transfers ownership, so each descriptor now has exactly one owner
        // and the native side must not close either of them.
        return Session(
            pid = pidOut[0],
            readSide = ParcelFileDescriptor.adoptFd(fd),
            writeSide = ParcelFileDescriptor.adoptFd(duplicate),
        )
    }
}
