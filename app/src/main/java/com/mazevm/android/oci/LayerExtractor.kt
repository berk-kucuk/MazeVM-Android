package com.mazevm.android.oci

import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Merges OCI image layers into a single root filesystem directory.
 *
 * Layers are ordered tar archives applied one on top of another. Because a tar has no
 * way to express "this file is gone", the format carries deletions as marker entries:
 * `.wh.<name>` removes `<name>` inherited from a lower layer, and `.wh..wh..opq`
 * empties the directory it appears in. Skipping that logic is what leaves a merged
 * rootfs full of files the image author deleted, so it is handled here rather than
 * left to a plain untar.
 */
object LayerExtractor {

    /** Everything a layer contains that this can meaningfully create without root. */
    class Result(
        val filesWritten: Int,
        val entriesSkipped: Int,
    )

    /**
     * Applies one layer into [rootfs].
     *
     * [compressed] selects gzip; uncompressed layers exist in the wild and some
     * registries serve zstd, which is reported rather than silently mis-parsed.
     */
    fun apply(
        source: InputStream,
        rootfs: File,
        compressed: Boolean = true,
        onProgress: (Long) -> Unit = {},
    ): Result {
        rootfs.mkdirs()
        val canonicalRoot = rootfs.canonicalFile

        var written = 0
        var skipped = 0
        var bytes = 0L
        var lastReported = 0L

        val stream = if (compressed) GzipCompressorInputStream(source, true) else source

        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name

                // A layer must never be able to write outside the rootfs it describes.
                val target = resolveSafely(canonicalRoot, name)
                if (target == null) {
                    skipped++
                    continue
                }

                if (applyWhiteout(canonicalRoot, name)) continue

                when {
                    entry.isDirectory -> {
                        target.mkdirs()
                        applyMode(target, entry)
                    }

                    entry.isSymbolicLink -> {
                        // Symlinks are recreated as-is inside the rootfs; proot resolves
                        // them against its own root at runtime.
                        if (writeSymlink(target, entry.linkName)) written++ else skipped++
                    }

                    entry.isLink -> {
                        val existing = resolveSafely(canonicalRoot, entry.linkName)
                        if (existing != null && existing.exists()) {
                            target.parentFile?.mkdirs()
                            target.delete()
                            runCatching { existing.copyTo(target, overwrite = true) }
                                .onSuccess { written++ }
                                .onFailure { skipped++ }
                        } else {
                            skipped++
                        }
                    }

                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        // An existing symlink would otherwise be followed and the write
                        // would land wherever it points.
                        target.delete()
                        target.outputStream().buffered(BUFFER).use { out ->
                            bytes += tar.copyTo(out, BUFFER)
                        }
                        applyMode(target, entry)
                        written++

                        if (bytes - lastReported >= PROGRESS_STEP) {
                            lastReported = bytes
                            onProgress(bytes)
                        }
                    }

                    // Device nodes, fifos and sockets need privileges this app will
                    // never have. proot supplies /dev itself, so losing them is fine.
                    else -> skipped++
                }
            }
        }

        onProgress(bytes)
        return Result(written, skipped)
    }

    /**
     * Handles a whiteout marker. Returns true when [name] was a marker and the entry
     * should not be extracted.
     */
    private fun applyWhiteout(root: File, name: String): Boolean {
        val fileName = name.substringAfterLast('/')
        if (!fileName.startsWith(WHITEOUT_PREFIX)) return false

        val directory = name.substringBeforeLast('/', missingDelimiterValue = "")

        if (fileName == OPAQUE_MARKER) {
            // Everything the lower layers put in this directory is hidden.
            val target = resolveSafely(root, directory) ?: return true
            target.listFiles()?.forEach { it.deleteRecursively() }
            return true
        }

        val removed = fileName.removePrefix(WHITEOUT_PREFIX)
        val path = if (directory.isEmpty()) removed else "$directory/$removed"
        resolveSafely(root, path)?.deleteRecursively()
        return true
    }

    /**
     * Resolves [name] inside [root], refusing anything that escapes it through `..`,
     * an absolute path, or a symlink planted by an earlier entry.
     */
    private fun resolveSafely(root: File, name: String): File? {
        val cleaned = name.removePrefix("./").trimStart('/')
        if (cleaned.isEmpty()) return null
        if (cleaned.split('/').any { it == ".." }) return null

        val candidate = File(root, cleaned)
        val parent = candidate.parentFile ?: return null

        // canonicalFile follows symlinks, which is what catches a layer that first
        // writes `link -> /` and then writes through it.
        val canonicalParent = runCatching { parent.canonicalFile }.getOrNull() ?: return null
        if (!canonicalParent.isInside(root)) return null

        return candidate
    }

    /**
     * Containment test on a path boundary rather than a string prefix: plain
     * startsWith would accept `/data/rootfs-other` as living inside `/data/rootfs`.
     */
    private fun File.isInside(root: File): Boolean {
        val rootPath = root.path
        return path == rootPath || path.startsWith("$rootPath${File.separator}")
    }

    private fun applyMode(target: File, entry: TarArchiveEntry) {
        val mode = entry.mode
        runCatching {
            target.setReadable(true, false)
            target.setWritable(mode and 0b010_000_000 != 0, true)
            // The execute bit is the one that matters: losing it breaks every binary
            // and every directory traversal in the image.
            target.setExecutable(mode and 0b001_000_000 != 0, false)
        }
    }

    /**
     * Recreates a symbolic link, returning false when it could not be made.
     *
     * A failure here is deliberately not fatal. Images are full of links that are
     * convenient rather than essential — the certificate hash aliases in
     * /etc/ssl/certs are the usual offenders, since a single one that cannot be
     * written would otherwise throw away a download of hundreds of megabytes. The
     * container still runs without them; at worst some paths resolve less tidily.
     */
    private fun writeSymlink(target: File, linkName: String): Boolean {
        val path = target.toPath()
        return runCatching {
            target.parentFile?.mkdirs()

            // deleteIfExists removes the link itself rather than following it, which is
            // what makes replacing a dangling link from an earlier layer work. A
            // directory left in the way has to go recursively.
            if (!java.nio.file.Files.deleteIfExists(path)) {
                if (target.isDirectory) target.deleteRecursively()
            }

            java.nio.file.Files.createSymbolicLink(path, java.nio.file.Paths.get(linkName))
            true
        }.getOrElse {
            Log.w(TAG, "skipping symlink ${target.name} -> $linkName", it)
            false
        }
    }

    /** True when the media type is one this can unpack. */
    fun isSupported(mediaType: String): Boolean =
        mediaType.contains("tar") && !mediaType.contains("zstd")

    fun isCompressed(mediaType: String): Boolean = mediaType.contains("gzip")

    private const val TAG = "LayerExtractor"
    private const val WHITEOUT_PREFIX = ".wh."
    private const val OPAQUE_MARKER = ".wh..wh..opq"
    private const val BUFFER = 1 shl 16
    private const val PROGRESS_STEP = 4L shl 20
}
