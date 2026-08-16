package com.mazevm.android.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.mazevm.android.core.Storage
import com.mazevm.android.data.model.ArchiveType
import com.mazevm.android.data.model.Checksum
import com.mazevm.android.data.model.DistroImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Downloads catalog images with resume, checksum verification and decompression.
 *
 * Distribution images run from 90 MiB to nearly 4 GiB, and phone connections drop, so
 * every transfer writes to a `.part` file and resumes with a Range request rather than
 * starting over.
 */
class ImageDownloader(
    private val context: Context,
    private val storage: Storage,
    private val scope: CoroutineScope,
    /** Read at the start of every transfer, so toggling the setting takes effect. */
    private val wifiOnly: () -> Boolean,
) {

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /** True while at least one transfer is live, which is what keeps the service up. */
    val hasActiveWork: Boolean get() = _states.value.values.any { it.isBusy }

    fun stateOf(imageId: String): DownloadState? = _states.value[imageId]

    fun start(image: DistroImage) {
        if (jobs[image.id]?.isActive == true) return
        setState(image.id, DownloadState.Queued)
        jobs[image.id] = scope.launch(Dispatchers.IO) {
            runCatching { download(image) }
                .onFailure { error ->
                    if (currentCoroutineContext().isActive) {
                        Log.e(TAG, "download of ${image.id} failed", error)
                        setState(
                            image.id,
                            DownloadState.Failed(
                                reason = error.message ?: error::class.java.simpleName,
                                resumable = error is IOException,
                            ),
                        )
                    }
                }
        }
    }

    /** Stops the transfer but keeps the partial file so it can pick up later. */
    fun pause(image: DistroImage) {
        jobs.remove(image.id)?.cancel()
        val partial = partialFile(image)
        setState(
            image.id,
            DownloadState.Paused(partial.length(), image.downloadBytes),
        )
    }

    /** Stops the transfer and discards the partial file. */
    fun cancel(image: DistroImage) {
        jobs.remove(image.id)?.cancel()
        partialFile(image).delete()
        _states.value = _states.value - image.id
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    // --------------------------------------------------------------- internals

    private suspend fun download(image: DistroImage) {
        val partial = partialFile(image)
        val finalFile = storage.imageFile(image)

        if (finalFile.exists() && finalFile.length() > 0) {
            setState(image.id, DownloadState.Completed)
            return
        }

        // These transfers run to several gigabytes, so a mobile connection is worth
        // refusing outright rather than warning about after the fact.
        if (wifiOnly() && isMetered()) throw MeteredConnection()

        ensureSpace(image)
        transfer(image, partial)

        image.checksum?.let { checksum ->
            setState(image.id, DownloadState.Verifying(0f))
            val actual = digest(image.id, partial, checksum)
            if (!actual.equals(checksum.value, ignoreCase = true)) {
                partial.delete()
                throw ChecksumMismatch(expected = checksum.value, actual = actual)
            }
        }

        if (image.archive == ArchiveType.NONE) {
            finalFile.parentFile?.mkdirs()
            if (!partial.renameTo(finalFile)) {
                partial.copyTo(finalFile, overwrite = true)
                partial.delete()
            }
        } else {
            setState(image.id, DownloadState.Extracting(0f))
            Archives.extract(partial, finalFile, image.archive) { fraction ->
                setState(image.id, DownloadState.Extracting(fraction))
            }
            partial.delete()
        }

        setState(image.id, DownloadState.Completed)
    }

    private suspend fun transfer(image: DistroImage, partial: File) {
        partial.parentFile?.mkdirs()
        var offset = partial.length()

        // A partial larger than the expected size means the catalog moved on; start over.
        if (image.downloadBytes in 1 until offset) {
            partial.delete()
            offset = 0
        }

        val connection = openConnection(image.url, offset)
        val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!resumed) offset = 0

        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            throw IOException("HTTP ${connection.responseCode} from ${image.url}")
        }

        val reportedLength = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
        val total = if (reportedLength > 0) offset + reportedLength else image.downloadBytes

        val buffer = ByteArray(BUFFER)
        var written = offset
        var lastTick = System.nanoTime()
        var lastBytes = written
        var rate = 0L

        try {
            connection.inputStream.use { input ->
                RandomAccessFile(partial, "rw").use { output ->
                    output.seek(offset)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read

                        val now = System.nanoTime()
                        val elapsed = now - lastTick
                        if (elapsed >= PROGRESS_INTERVAL_NANOS) {
                            rate = ((written - lastBytes) * 1_000_000_000L) / elapsed
                            lastTick = now
                            lastBytes = written
                            setState(image.id, DownloadState.Running(written, total, rate))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        setState(image.id, DownloadState.Running(written, total.coerceAtLeast(written), rate))
    }

    private fun openConnection(url: String, offset: Long): HttpURLConnection {
        var current = URL(url)
        var redirects = 0

        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }

            val code = connection.responseCode
            // Mirrors redirect constantly, and the platform will not follow an
            // http -> https hop on its own, so it is handled here.
            if (code in listOf(301, 302, 303, 307, 308) && redirects < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("redirect without a target")
                current = URL(current, location)
                redirects++
                continue
            }
            return connection
        }
    }

    private suspend fun digest(imageId: String, file: File, checksum: Checksum): String =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance(checksum.digestName)
            val total = file.length().coerceAtLeast(1)
            var processed = 0L
            val buffer = ByteArray(BUFFER)

            file.inputStream().buffered(BUFFER).use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    processed += read
                    setState(imageId, DownloadState.Verifying(processed.toFloat() / total))
                }
            }
            digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        }

    private fun ensureSpace(image: DistroImage) {
        val needed = image.downloadBytes + image.installedBytes
        val free = storage.freeBytes()
        if (free in 1 until needed) {
            throw InsufficientSpace(needed - free)
        }
    }

    private fun isMetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun partialFile(image: DistroImage): File =
        storage.downloadsTempDir.resolve("${image.id}.part")

    private fun setState(imageId: String, state: DownloadState) {
        _states.value = _states.value + (imageId to state)
    }

    class ChecksumMismatch(val expected: String, val actual: String) :
        Exception("checksum mismatch: expected $expected, got $actual")

    class InsufficientSpace(val shortfallBytes: Long) :
        Exception("needs ${shortfallBytes / (1024 * 1024)} MiB more free space")

    class MeteredConnection :
        Exception("waiting for Wi-Fi; downloads over mobile data are turned off")

    private companion object {
        const val TAG = "ImageDownloader"
        const val BUFFER = 1 shl 16
        const val MAX_REDIRECTS = 8
        const val PROGRESS_INTERVAL_NANOS = 400_000_000L
        const val USER_AGENT = "MazeVM/1.0 (Android)"
    }
}
