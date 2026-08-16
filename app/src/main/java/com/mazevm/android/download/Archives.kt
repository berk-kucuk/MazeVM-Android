package com.mazevm.android.download

import com.mazevm.android.data.model.ArchiveType
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Decompression for the wrappers distributions actually publish. */
object Archives {

    /**
     * Expands [source] into [target]. For single-stream formats the payload is the
     * whole file; for zip the largest entry is taken, which is always the disk image
     * in the archives this app deals with.
     *
     * [onProgress] receives a 0..1 fraction based on bytes consumed from [source],
     * which is the only figure available before the payload size is known.
     */
    fun extract(
        source: File,
        target: File,
        type: ArchiveType,
        onProgress: (Float) -> Unit = {},
    ) {
        require(type != ArchiveType.NONE) { "nothing to extract" }
        target.parentFile?.mkdirs()

        val total = source.length().coerceAtLeast(1)
        val counting = CountingInputStream(source.inputStream().buffered(BUFFER)) { read ->
            onProgress((read.toFloat() / total).coerceIn(0f, 1f))
        }

        counting.use { raw ->
            val payload: InputStream = when (type) {
                ArchiveType.GZ -> GZIPInputStream(raw, BUFFER)
                ArchiveType.XZ -> XZInputStream(raw)
                ArchiveType.BZ2 -> BZip2CompressorInputStream(raw, true)
                ArchiveType.ZIP -> largestZipEntry(raw)
                ArchiveType.NONE -> raw
            }
            target.outputStream().buffered(BUFFER).use { out ->
                payload.copyTo(out, BUFFER)
            }
        }
    }

    /**
     * Positions a zip stream on its largest entry. Distribution zips hold the image
     * plus a licence or checksum file, so size is a reliable discriminator.
     */
    private fun largestZipEntry(raw: InputStream): InputStream {
        val zip = ZipInputStream(raw)
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) return zip
            entry = zip.nextEntry
        }
        error("archive contains no files")
    }

    private const val BUFFER = 1 shl 16
}

private class CountingInputStream(
    private val delegate: InputStream,
    private val onRead: (Long) -> Unit,
) : InputStream() {

    private var count = 0L
    private var lastNotified = 0L

    override fun read(): Int = delegate.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()

    private fun advance(bytes: Long) {
        count += bytes
        // Reporting on every read would flood the UI; a megabyte is fine-grained enough.
        if (count - lastNotified >= 1L shl 20) {
            lastNotified = count
            onRead(count)
        }
    }
}
