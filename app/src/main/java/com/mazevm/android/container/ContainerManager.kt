package com.mazevm.android.container

import android.content.Context
import android.util.Log
import com.mazevm.android.data.ContainerStore
import com.mazevm.android.data.model.ContainerConfig
import com.mazevm.android.data.model.VmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns every running container, mirroring [com.mazevm.android.vm.VmManager].
 *
 * Containers are cheap to start compared with a virtual machine, but they still
 * outlive the Activity, so the same foreground service keeps them alive.
 */
class ContainerManager(
    context: Context,
    private val store: ContainerStore,
    private val scope: CoroutineScope,
) {

    val runtime = ProotRuntime(context)

    private val processes = mutableMapOf<String, ContainerProcess>()

    private val _states = MutableStateFlow<Map<String, VmState>>(emptyMap())
    val states: StateFlow<Map<String, VmState>> = _states.asStateFlow()

    var onActiveCountChanged: ((Int) -> Unit)? = null

    val runningCount: Int get() = processes.count { it.value.state.value.isActive }

    fun processFor(id: String): ContainerProcess? = processes[id]

    fun stateOf(id: String): VmState = _states.value[id] ?: VmState.STOPPED

    suspend fun start(config: ContainerConfig): Result<ContainerProcess> {
        processes[config.id]?.let { existing ->
            if (existing.state.value.isActive) return Result.success(existing)
            existing.dispose()
            processes.remove(config.id)
        }

        if (!runtime.isAvailable) return Result.failure(RuntimeMissing())

        val process = ContainerProcess(config, runtime, scope)
        processes[config.id] = process
        observe(process)

        val started = process.start()
        if (started.isFailure) {
            processes.remove(config.id)
            process.dispose()
            return Result.failure(started.exceptionOrNull()!!)
        }

        store.upsert(config.copy(lastStartedAt = System.currentTimeMillis()))
        Log.i(TAG, "started ${config.name}")
        return Result.success(process)
    }

    suspend fun stop(id: String) {
        processes[id]?.stop()
    }

    suspend fun forceStop(id: String) {
        processes[id]?.forceStop()
    }

    suspend fun stopAll() {
        processes.keys.toList().forEach { stop(it) }
    }

    fun dispose(id: String) {
        processes.remove(id)?.dispose()
        _states.value = _states.value - id
        onActiveCountChanged?.invoke(runningCount)
    }

    private fun observe(process: ContainerProcess) {
        scope.launch {
            process.state.collect { state ->
                _states.value = _states.value + (process.config.id to state)
                onActiveCountChanged?.invoke(runningCount)
            }
        }
    }

    class RuntimeMissing :
        Exception("the container runtime is not present in this build")

    private companion object {
        const val TAG = "ContainerManager"
    }
}
