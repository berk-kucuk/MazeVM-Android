package com.mazevm.android.data

import android.util.Log
import com.mazevm.android.core.Storage
import com.mazevm.android.data.model.VmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Machine definitions, persisted as one JSON document.
 *
 * There are never more than a handful of machines, so a whole-file rewrite is simpler
 * and more robust than a database, and it stays readable if a user ever needs to
 * repair it by hand.
 */
class VmStore(private val storage: Storage) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val writeLock = Mutex()

    private val _machines = MutableStateFlow<List<VmConfig>>(emptyList())
    val machines: StateFlow<List<VmConfig>> = _machines.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val file = storage.vmStoreFile
        if (!file.exists()) {
            _machines.value = emptyList()
            return@withContext
        }
        _machines.value = runCatching {
            json.decodeFromString<List<VmConfig>>(file.readText())
        }.onFailure {
            Log.e(TAG, "machines.json is unreadable; starting empty", it)
            // Keep the broken file around so a user can recover it rather than
            // silently destroying their machine list.
            runCatching { file.copyTo(File("${file.absolutePath}.broken"), overwrite = true) }
        }.getOrDefault(emptyList())
    }

    suspend fun upsert(config: VmConfig) = mutate { current ->
        val index = current.indexOfFirst { it.id == config.id }
        if (index >= 0) current.toMutableList().also { it[index] = config }
        else current + config
    }

    suspend fun delete(id: String) = mutate { current -> current.filterNot { it.id == id } }

    fun find(id: String): VmConfig? = _machines.value.firstOrNull { it.id == id }

    /** Removes the disks and firmware variable store belonging to [config]. */
    suspend fun deleteWithFiles(config: VmConfig) = withContext(Dispatchers.IO) {
        delete(config.id)
        config.diskPath?.let { runCatching { File(it).delete() } }
        config.cloudInitSeedPath?.let { runCatching { File(it).delete() } }
        runCatching { storage.disksDir.resolve("${config.id}-efivars.fd").delete() }
        runCatching { storage.logFile(config.id).delete() }
        Unit
    }

    private suspend fun mutate(block: (List<VmConfig>) -> List<VmConfig>) {
        writeLock.withLock {
            val next = block(_machines.value)
            _machines.value = next
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = storage.vmStoreFile
                    val temp = File("${file.absolutePath}.tmp")
                    temp.writeText(json.encodeToString(ListSerializer(VmConfig.serializer()), next))
                    // Rename is atomic, so a crash mid-write cannot truncate the list.
                    if (!temp.renameTo(file)) {
                        file.writeText(temp.readText())
                        temp.delete()
                    }
                }.onFailure { Log.e(TAG, "could not persist machines", it) }
            }
        }
    }

    private companion object {
        const val TAG = "VmStore"
    }
}
