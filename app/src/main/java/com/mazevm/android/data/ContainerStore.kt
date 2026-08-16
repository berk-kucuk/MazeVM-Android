package com.mazevm.android.data

import android.util.Log
import com.mazevm.android.core.Storage
import com.mazevm.android.data.model.ContainerConfig
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

/** Container definitions, persisted the same way machines are: one JSON document. */
class ContainerStore(private val storage: Storage) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val writeLock = Mutex()

    private val _containers = MutableStateFlow<List<ContainerConfig>>(emptyList())
    val containers: StateFlow<List<ContainerConfig>> = _containers.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val file = storage.containerStoreFile
        if (!file.exists()) {
            _containers.value = emptyList()
            return@withContext
        }
        _containers.value = runCatching {
            json.decodeFromString(ListSerializer(ContainerConfig.serializer()), file.readText())
        }.onFailure {
            Log.e(TAG, "containers.json is unreadable; starting empty", it)
            runCatching { file.copyTo(File("${file.absolutePath}.broken"), overwrite = true) }
        }.getOrDefault(emptyList())
    }

    fun find(id: String): ContainerConfig? = _containers.value.firstOrNull { it.id == id }

    suspend fun upsert(config: ContainerConfig) = mutate { current ->
        val index = current.indexOfFirst { it.id == config.id }
        if (index >= 0) current.toMutableList().also { it[index] = config }
        else current + config
    }

    /** Removes the definition and the extracted root filesystem behind it. */
    suspend fun deleteWithFiles(config: ContainerConfig) = withContext(Dispatchers.IO) {
        mutate { current -> current.filterNot { it.id == config.id } }
        runCatching { storage.containersDir.resolve(config.id).deleteRecursively() }
        Unit
    }

    private suspend fun mutate(block: (List<ContainerConfig>) -> List<ContainerConfig>) {
        writeLock.withLock {
            val next = block(_containers.value)
            _containers.value = next
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = storage.containerStoreFile
                    val temp = File("${file.absolutePath}.tmp")
                    temp.writeText(
                        json.encodeToString(ListSerializer(ContainerConfig.serializer()), next)
                    )
                    if (!temp.renameTo(file)) {
                        file.writeText(temp.readText())
                        temp.delete()
                    }
                }.onFailure { Log.e(TAG, "could not persist containers", it) }
            }
        }
    }

    private companion object {
        const val TAG = "ContainerStore"
    }
}
