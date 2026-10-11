package com.mazevm.android.core

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

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

    /**
     * Scratch space for a layer blob, deleted as soon as it has been merged.
     *
     * The digest comes from the registry's manifest JSON. It used to be split
     * on ':' and used as a file name as is, so a manifest naming a layer
     * "sha256:../../../machines.json" had the pull truncate, overwrite and then
     * delete any file the app can write. Only a well-formed digest is accepted.
     */
    fun containerBlob(containerId: String, digest: String): File {
        require(DIGEST.matches(digest)) { "malformed layer digest" }
        return containersDir.resolve(containerId).ensure()
            .resolve("blobs").ensure()
            .resolve(digest.substringAfter(':'))
    }

    /** Persisted machine definitions. */
    val vmStoreFile: File get() = context.filesDir.resolve("machines.json")

    /** Persisted container definitions. */
    val containerStoreFile: File get() = context.filesDir.resolve("containers.json")

    /** Cached copy of a remotely fetched catalog. */
    val catalogCacheFile: File get() = context.filesDir.resolve("catalog.json")

    /** Rolling QEMU stdout/stderr per machine, so the log survives leaving the screen. */
    fun logFile(vmId: String): File = context.filesDir.resolve("logs").ensure().resolve("$vmId.log")

    fun diskFile(name: String): File = disksDir.resolve(name)

    fun imageFile(image: com.mazevm.android.data.model.DistroImage): File {
        // Both come from the (remotely refreshable) catalog; never let them
        // name anything outside images/.
        require(isSafeName(image.id) && isSafeName(image.fileName)) { "unsafe image name" }
        return imagesDir.resolve(image.id).ensure().resolve(image.fileName)
    }

    /** Directory an image lives in, or null when the catalog id is not a plain name. */
    fun imageDir(id: String): File? = if (isSafeName(id)) imagesDir.resolve(id) else null

    /** Free bytes on the volume holding [dataRoot]. */
    fun freeBytes(): Long = runCatching {
        val stat = StatFs(dataRoot.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    /** Bytes occupied by images, disks, seeds and container filesystems. */
    fun usedBytes(): Long =
        listOf(imagesDir, disksDir, seedsDir, containersDir).sumOf { it.sizeRecursive() }

    private fun File.ensure(): File = apply { if (!exists()) mkdirs() }

    companion object {
        private val DIGEST = Regex("^(sha256:[0-9a-f]{64}|sha512:[0-9a-f]{128})$")
        private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$")

        /** A single path component with no separators, no leading dot and no "..". */
        fun isSafeName(name: String): Boolean = SAFE_NAME.matches(name) && ".." !in name
    }
}

fun File.sizeRecursive(): Long =
    if (!exists()) 0L
    else if (isFile) length()
    else (listFiles() ?: emptyArray()).sumOf { it.sizeRecursive() }

/**
 * Recursive delete that never follows a symbolic link.
 *
 * Kotlin's deleteRecursively() walks with isDirectory/listFiles, which follow
 * links: on a tree an image or guest controls (a container rootfs), a link
 * `evil -> /data/user/0/<app>/files` made "delete this container" or a layer
 * whiteout delete the app's own data outside the tree. Here a link is removed
 * as a link and its target is never visited.
 */
fun File.deleteRecursivelyNoFollow(): Boolean {
    val root: Path = toPath()
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
    return runCatching {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(root)
        } else {
            // walkFileTree without FOLLOW_LINKS reports a link as a plain file.
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    runCatching { Files.deleteIfExists(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        true
    }.getOrDefault(false)
}

/** True if this path is itself a symbolic link (not following it). */
fun File.isSymlink(): Boolean = Files.isSymbolicLink(toPath())

