package com.mazevm.android.core

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Every path MazeVM writes to. Large artefacts go to the app-specific external
 * directory so they show up in the system file manager and count against the app's
 * storage entry, while small state stays in internal storage.
 */
class Storage(private val context: Context) {

    /** Falls back to internal storage when no external volume is mounted. */
    private val dataRoot: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    /** Downloaded distribution artefacts, keyed by catalog id. */
    val imagesDir: File get() = dataRoot.resolve("images").ensure()

    /** Virtual disks belonging to machines. */
    val disksDir: File get() = dataRoot.resolve("disks").ensure()

    /** Partial downloads. Cleared when a download completes or is cancelled. */
    val downloadsTempDir: File get() = dataRoot.resolve(".partial").ensure()

    /** Generated cloud-init seed images. */
    val seedsDir: File get() = dataRoot.resolve("seeds").ensure()

    /** QEMU firmware, keymaps and ROMs extracted from assets. Internal, exec-adjacent. */
    val qemuDataDir: File get() = context.filesDir.resolve("qemu").ensure()

    /** UNIX sockets for the QEMU monitor. Must be short: sun_path is 108 bytes. */
    val runtimeDir: File get() = context.cacheDir.resolve("run").ensure()

    /**
     * Extracted container root filesystems.
     *
     * These live on internal storage rather than beside the disk images, because a
     * rootfs holds hundreds of thousands of small files with modes and symlinks that an
     * emulated FAT external volume cannot represent.
     */
    val containersDir: File get() = context.filesDir.resolve("containers").ensure()

    fun containerRootfs(containerId: String): File =
        containersDir.resolve(containerId).ensure().resolve("rootfs")

    /** Scratch space for a layer blob, deleted as soon as it has been merged. */
    fun containerBlob(containerId: String, digest: String): File =
        containersDir.resolve(containerId).ensure()
            .resolve("blobs").ensure()
            .resolve(digest.substringAfter(':'))

    /** Persisted machine definitions. */
    val vmStoreFile: File get() = context.filesDir.resolve("machines.json")

    /** Persisted container definitions. */
    val containerStoreFile: File get() = context.filesDir.resolve("containers.json")

    /** Cached copy of a remotely fetched catalog. */
    val catalogCacheFile: File get() = context.filesDir.resolve("catalog.json")

    /** Rolling QEMU stdout/stderr per machine, so the log survives leaving the screen. */
    fun logFile(vmId: String): File = context.filesDir.resolve("logs").ensure().resolve("$vmId.log")

    fun diskFile(name: String): File = disksDir.resolve(name)

    fun imageFile(image: com.mazevm.android.data.model.DistroImage): File =
        imagesDir.resolve(image.id).ensure().resolve(image.fileName)

    /** Free bytes on the volume holding [dataRoot]. */
    fun freeBytes(): Long = runCatching {
        val stat = StatFs(dataRoot.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    /** Bytes occupied by images, disks, seeds and container filesystems. */
    fun usedBytes(): Long =
        listOf(imagesDir, disksDir, seedsDir, containersDir).sumOf { it.sizeRecursive() }

    private fun File.ensure(): File = apply { if (!exists()) mkdirs() }
}

fun File.sizeRecursive(): Long =
    if (!exists()) 0L
    else if (isFile) length()
    else (listFiles() ?: emptyArray()).sumOf { it.sizeRecursive() }
