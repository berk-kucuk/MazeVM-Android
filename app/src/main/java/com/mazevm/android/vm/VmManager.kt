package com.mazevm.android.vm

import android.content.Context
import android.util.Log
import com.mazevm.android.core.Storage
import com.mazevm.android.data.VmStore
import com.mazevm.android.data.model.VmConfig
import com.mazevm.android.data.model.VmState
import com.mazevm.android.qemu.QemuCommand
import com.mazevm.android.qemu.QemuProcess
import com.mazevm.android.qemu.QemuRuntime
import com.mazevm.android.qemu.VmSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns every running machine.
 *
 * Machines outlive the Activity, so this lives on the Application and is kept alive by
 * [VmService] whenever at least one is up. Nothing here touches the UI thread.
 */
class VmManager(
    private val context: Context,
    private val storage: Storage,
    private val runtime: QemuRuntime,
    private val store: VmStore,
    private val scope: CoroutineScope,
) {

    private val processes = mutableMapOf<String, QemuProcess>()

    private val _states = MutableStateFlow<Map<String, VmState>>(emptyMap())
    val states: StateFlow<Map<String, VmState>> = _states.asStateFlow()

    /** Set whenever the running set changes, so the service can start or stop itself. */
    var onActiveCountChanged: ((Int) -> Unit)? = null

    val runningCount: Int get() = processes.count { it.value.state.value.isActive }

    fun processFor(vmId: String): QemuProcess? = processes[vmId]

    fun stateOf(vmId: String): VmState = _states.value[vmId] ?: VmState.STOPPED

    suspend fun start(vm: VmConfig): Result<QemuProcess> {
        processes[vm.id]?.let { existing ->
            if (existing.state.value.isActive) return Result.success(existing)
            existing.dispose()
            processes.remove(vm.id)
        }

        if (!runtime.abiSupported) {
            return Result.failure(UnsupportedDevice(runtime.deviceAbi))
        }
        if (!runtime.isAvailable(vm.arch)) {
            return Result.failure(RuntimeMissing())
        }

        runtime.prepareDataDir().onFailure { return Result.failure(it) }

        val sockets = allocateSockets(vm)
        val argv = QemuCommand.build(vm, runtime, sockets)
        Log.i(TAG, "starting ${vm.name}: ${QemuCommand.render(argv)}")

        val process = QemuProcess(
            vm = vm,
            argv = argv,
            environment = runtime.environment(),
            sockets = sockets,
            logFile = storage.logFile(vm.id),
            scope = scope,
        )
        processes[vm.id] = process
        observe(process)

        val started = process.start()
        if (started.isFailure) {
            processes.remove(vm.id)
            process.dispose()
            return Result.failure(started.exceptionOrNull()!!)
        }

        store.upsert(vm.copy(lastStartedAt = System.currentTimeMillis()))
        return Result.success(process)
    }

    suspend fun stop(vmId: String) {
        processes[vmId]?.stop()
    }

    suspend fun forceStop(vmId: String) {
        processes[vmId]?.forceStop()
    }

    suspend fun stopAll() {
        processes.keys.toList().forEach { stop(it) }
    }

    fun dispose(vmId: String) {
        processes.remove(vmId)?.dispose()
        publish()
    }

    private fun observe(process: QemuProcess) {
        scope.launch {
            process.state.collect { state ->
                _states.value = _states.value + (process.vm.id to state)
                onActiveCountChanged?.invoke(runningCount)
                if (!state.isActive && state != VmState.STARTING) {
                    // Keep the object around so the console can still show the last
                    // output and the exit reason; it is disposed when the user leaves.
                    onActiveCountChanged?.invoke(runningCount)
                }
            }
        }
    }

    private fun publish() {
        _states.value = processes.mapValues { it.value.state.value }
        onActiveCountChanged?.invoke(runningCount)
    }

    /**
     * Unix socket paths for QMP and VNC. `sun_path` is only 108 bytes, so a short
     * token is used instead of the machine's full id.
     */
    private fun allocateSockets(vm: VmConfig): VmSockets {
        val token = vm.id.take(8)
        val dir = storage.runtimeDir
        return VmSockets(
            qmp = dir.resolve("$token.qmp"),
            vnc = if (vm.display.hasVnc) dir.resolve("$token.vnc") else null,
        )
    }

    class UnsupportedDevice(val abi: String) :
        Exception("MazeVM needs an arm64-v8a device; this one is $abi")

    class RuntimeMissing :
        Exception("QEMU binaries are not present in this build")

    private companion object {
        const val TAG = "VmManager"
    }
}
