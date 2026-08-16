package com.mazevm.android.oci

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Layer merging is where an image quietly comes out wrong: a missed whiteout leaves
 * deleted files behind, and a missed path check lets an image write outside its own
 * root. Both failures are invisible until something downstream breaks, so they are
 * pinned here.
 */
class LayerExtractorTest {

    // A private sandbox, not a directory under build/. One of these tests deliberately
    // plants a symlink pointing at the parent, and Kotlin's deleteRecursively follows
    // symlinks, so cleaning up inside the build tree would take Gradle's own output
    // with it.
    private val sandbox: File = Files.createTempDirectory("maze-layer-test").toFile()
    private val rootfs = File(sandbox, "rootfs")
    private val outside: File get() = sandbox

    @After
    fun cleanUp() {
        deleteWithoutFollowingLinks(sandbox.toPath())
    }

    /** Removes a tree, unlinking symlinks rather than descending through them. */
    private fun deleteWithoutFollowingLinks(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    // ------------------------------------------------------------------- helpers

    private class LayerBuilder {
        private val bytes = ByteArrayOutputStream()
        private val tar = TarArchiveOutputStream(GzipCompressorOutputStream(bytes))

        init {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        }

        fun file(path: String, content: String = "x", mode: Int = 0b110_100_100) = apply {
            val entry = TarArchiveEntry(path)
            val data = content.toByteArray()
            entry.size = data.size.toLong()
            entry.mode = mode
            tar.putArchiveEntry(entry)
            tar.write(data)
            tar.closeArchiveEntry()
        }

        fun directory(path: String) = apply {
            tar.putArchiveEntry(TarArchiveEntry(path.trimEnd('/') + "/"))
            tar.closeArchiveEntry()
        }

        fun symlink(path: String, target: String) = apply {
            val entry = TarArchiveEntry(path, TarArchiveEntry.LF_SYMLINK)
            entry.linkName = target
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
        }

        fun build(): ByteArrayInputStream {
            tar.finish()
            tar.close()
            return ByteArrayInputStream(bytes.toByteArray())
        }
    }

    private fun apply(builder: LayerBuilder) =
        LayerExtractor.apply(builder.build(), rootfs, compressed = true)

    // --------------------------------------------------------------------- tests

    @Test
    fun `plain files and directories land in the rootfs`() {
        apply(LayerBuilder().directory("etc").file("etc/os-release", "NAME=Test"))

        assertTrue(File(rootfs, "etc").isDirectory)
        assertEquals("NAME=Test", File(rootfs, "etc/os-release").readText())
    }

    @Test
    fun `a later layer replaces a file from an earlier one`() {
        apply(LayerBuilder().file("etc/hosts", "first"))
        apply(LayerBuilder().file("etc/hosts", "second"))

        assertEquals("second", File(rootfs, "etc/hosts").readText())
    }

    @Test
    fun `whiteout marker deletes the inherited file and leaves no marker behind`() {
        apply(LayerBuilder().file("usr/bin/tool", "payload").file("usr/bin/keep", "keep"))
        apply(LayerBuilder().file("usr/bin/.wh.tool"))

        assertFalse("the whited-out file survived", File(rootfs, "usr/bin/tool").exists())
        assertFalse("the marker itself was extracted", File(rootfs, "usr/bin/.wh.tool").exists())
        assertTrue("an unrelated file was removed", File(rootfs, "usr/bin/keep").exists())
    }

    @Test
    fun `whiteout removes a whole inherited directory`() {
        apply(LayerBuilder().directory("opt/app").file("opt/app/data", "x"))
        apply(LayerBuilder().file("opt/.wh.app"))

        assertFalse(File(rootfs, "opt/app").exists())
    }

    @Test
    fun `opaque marker empties the directory it appears in`() {
        apply(
            LayerBuilder()
                .directory("var/cache")
                .file("var/cache/one", "1")
                .file("var/cache/two", "2")
        )
        apply(LayerBuilder().file("var/cache/.wh..wh..opq").file("var/cache/three", "3"))

        assertFalse(File(rootfs, "var/cache/one").exists())
        assertFalse(File(rootfs, "var/cache/two").exists())
        assertTrue("the new layer's own file was removed too", File(rootfs, "var/cache/three").exists())
        assertFalse(File(rootfs, "var/cache/.wh..wh..opq").exists())
    }

    @Test
    fun `entries that climb out of the rootfs are refused`() {
        val result = apply(
            LayerBuilder()
                .file("../escaped", "no")
                .file("etc/../../escaped2", "no")
                .file("safe", "yes")
        )

        assertTrue(File(rootfs, "safe").exists())
        assertFalse(File(outside, "escaped").exists())
        assertFalse(File(outside, "escaped2").exists())
        assertEquals("both traversal attempts should be skipped", 2, result.entriesSkipped)
    }

    @Test
    fun `a symlink cannot be used to write outside the rootfs`() {
        // The classic layer attack: plant a link to somewhere else, then write through
        // it in the same or a later layer.
        apply(LayerBuilder().symlink("escape", outside.absolutePath))
        apply(LayerBuilder().file("escape/pwned", "no"))

        assertFalse("wrote through a symlink", File(outside, "pwned").exists())
    }

    @Test
    fun `a symlink replaces whatever already occupies its path`() {
        // Certificate hash aliases arrive this way: a later layer drops a link over a
        // name an earlier layer wrote as a regular file.
        apply(LayerBuilder().file("etc/ssl/certs/002c0b4f.0", "old"))
        apply(LayerBuilder().symlink("etc/ssl/certs/002c0b4f.0", "GlobalSign_Root_R46.pem"))

        val link = File(rootfs, "etc/ssl/certs/002c0b4f.0").toPath()
        assertTrue("should now be a symlink", Files.isSymbolicLink(link))
        assertEquals("GlobalSign_Root_R46.pem", Files.readSymbolicLink(link).toString())
    }

    @Test
    fun `a symlink that cannot be created does not abort the layer`() {
        // A directory in the way that cannot simply be unlinked stands in for any
        // filesystem-level refusal. What matters is that the rest of the layer still
        // lands: one unwritable link must not discard a download of hundreds of
        // megabytes.
        val blocked = File(rootfs, "etc/blocked")
        blocked.mkdirs()
        File(blocked, "child").writeText("in the way")

        val result = apply(
            LayerBuilder()
                .file("etc/before", "1")
                .symlink("etc/blocked", "somewhere")
                .file("etc/after", "2")
        )

        assertTrue("entries before the link were lost", File(rootfs, "etc/before").exists())
        assertTrue("entries after the link were lost", File(rootfs, "etc/after").exists())
        assertTrue("the layer reported nothing written", result.filesWritten >= 2)
    }

    @Test
    fun `a sibling directory sharing the rootfs name prefix is not treated as inside`() {
        // /…/rootfs-other must not pass a containment check against /…/rootfs.
        val sibling = File(sandbox, "rootfs-other")
        sibling.mkdirs()

        apply(LayerBuilder().file("safe", "yes"))

        assertTrue(File(rootfs, "safe").exists())
        assertEquals("nothing should have been written next door", 0, sibling.list()?.size)
    }

    @Test
    fun `the execute bit survives extraction`() {
        apply(LayerBuilder().file("bin/sh", "#!/bin/sh", mode = 0b111_101_101))

        assertTrue("binaries would be unrunnable", File(rootfs, "bin/sh").canExecute())
    }

    @Test
    fun `media types are classified correctly`() {
        assertTrue(LayerExtractor.isSupported("application/vnd.oci.image.layer.v1.tar+gzip"))
        assertTrue(LayerExtractor.isCompressed("application/vnd.oci.image.layer.v1.tar+gzip"))
        assertTrue(LayerExtractor.isSupported("application/vnd.oci.image.layer.v1.tar"))
        assertFalse(LayerExtractor.isCompressed("application/vnd.oci.image.layer.v1.tar"))
        assertFalse(LayerExtractor.isSupported("application/vnd.oci.image.layer.v1.tar+zstd"))
    }
}
