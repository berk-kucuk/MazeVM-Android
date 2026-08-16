package com.mazevm.android.data

import com.mazevm.android.core.Storage
import com.mazevm.android.core.sizeRecursive
import com.mazevm.android.data.model.DistroImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Which catalog images are actually present on this device. */
class ImageLibrary(private val storage: Storage) {

    data class Installed(
        val imageId: String,
        val file: File,
        val sizeBytes: Long,
    )

    private val _installed = MutableStateFlow<Map<String, Installed>>(emptyMap())
    val installed: StateFlow<Map<String, Installed>> = _installed.asStateFlow()

    fun fileFor(image: DistroImage): File? =
        storage.imageFile(image).takeIf { it.exists() && it.length() > 0 }

    fun isInstalled(image: DistroImage): Boolean = fileFor(image) != null

    /** Rescans the images directory. Cheap: one stat per catalog entry. */
    suspend fun refresh(images: List<DistroImage>) = withContext(Dispatchers.IO) {
        _installed.value = images.mapNotNull { image ->
            fileFor(image)?.let { file ->
                image.id to Installed(image.id, file, file.length())
            }
        }.toMap()
    }

    suspend fun delete(image: DistroImage) = withContext(Dispatchers.IO) {
        storage.imagesDir.resolve(image.id).deleteRecursively()
        _installed.value = _installed.value - image.id
    }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        storage.imagesDir.sizeRecursive()
    }
}
