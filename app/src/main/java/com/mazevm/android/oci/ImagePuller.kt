package com.mazevm.android.oci

import android.util.Log
import com.mazevm.android.core.Storage
import com.mazevm.android.data.model.ImageConfig
import com.mazevm.android.data.model.ImageReference
import com.mazevm.android.data.model.PullState
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
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * The `docker pull` half of the container backend, without a daemon.
 *
 * Resolves a tag to its arm64 manifest, streams each layer, verifies the digest the
 * registry advertised, and merges the layers into one root filesystem directory.
 */
class ImagePuller(
    private val storage: Storage,
    private val scope: CoroutineScope,
) {

    private val registry = RegistryClient()

    private val _states = MutableStateFlow<Map<String, PullState>>(emptyMap())
    val states: StateFlow<Map<String, PullState>> = _states.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    val hasActiveWork: Boolean get() = _states.value.values.any { it.isBusy }

    fun stateOf(key: String): PullState? = _states.value[key]

    /** Result of a completed pull, everything needed to build a ContainerConfig. */
    data class Pulled(
        val reference: ImageReference,
        val digest: String,
        val rootfs: File,
        val config: ImageConfig,
    )

    fun pull(
        reference: ImageReference,
        containerId: String,
        onComplete: (Pulled) -> Unit,
    ) {
        val key = containerId
        if (jobs[key]?.isActive == true) return

        setState(key, PullState.Resolving)
        jobs[key] = scope.launch(Dispatchers.IO) {
            runCatching { run(reference, containerId) }
                .onSuccess { pulled ->
                    setState(key, PullState.Completed)
                    onComplete(pulled)
                }
                .onFailure { error ->
                    if (currentCoroutineContext().isActive) {
                        Log.e(TAG, "pull of ${reference.canonical} failed", error)
                        setState(
                            key,
                            PullState.Failed(error.message ?: error::class.java.simpleName),
                        )
                    }
                }
        }
    }

    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
        _states.value = _states.value - key
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun run(reference: ImageReference, containerId: String): Pulled {
        val manifest = registry.resolve(reference).getOrElse { throw it }

        manifest.layers.firstOrNull { !LayerExtractor.isSupported(it.mediaType) }?.let {
            throw IOException("unsupported layer format ${it.mediaType}")
        }

        val config = registry.imageConfig(reference, manifest).getOrElse { throw it }

        val rootfs = storage.containerRootfs(containerId)
        // A partial rootfs from an interrupted pull would merge badly with a fresh set
        // of layers, so start from nothing.
        rootfs.deleteRecursively()
        rootfs.mkdirs()

        var completedBytes = 0L
        manifest.layers.forEachIndexed { index, layer ->
            currentCoroutineContext().ensureActive()

            setState(
                containerId,
                PullState.Downloading(
                    layer = index + 1,
                    layerCount = manifest.layers.size,
                    bytes = completedBytes,
                    totalBytes = manifest.totalLayerBytes,
                ),
            )

            val blob = storage.containerBlob(containerId, layer.digest)
            download(reference, layer.digest, blob) { readSoFar ->
                setState(
                    containerId,
                    PullState.Downloading(
                        layer = index + 1,
                        layerCount = manifest.layers.size,
                        bytes = completedBytes + readSoFar,
                        totalBytes = manifest.totalLayerBytes,
                    ),
                )
            }
            completedBytes += layer.size

            setState(containerId, PullState.Extracting(index + 1, manifest.layers.size))
            blob.inputStream().buffered(BUFFER).use { input ->
                LayerExtractor.apply(
                    source = input,
                    rootfs = rootfs,
                    compressed = LayerExtractor.isCompressed(layer.mediaType),
                )
            }
            // The compressed blob is dead weight once merged; images run to gigabytes.
            blob.delete()
        }

        prepareRootfs(rootfs)

        return Pulled(
            reference = reference,
            digest = manifest.digest,
            rootfs = rootfs,
            config = config,
        )
    }

    /**
     * Streams a blob to disk, checking it against the digest the registry named.
     * Layers are content-addressed, so this is a real integrity check rather than a
     * courtesy one.
     */
    private suspend fun download(
        reference: ImageReference,
        digest: String,
        target: File,
        onProgress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()

        val algorithm = digest.substringBefore(':')
        val expected = digest.substringAfter(':')
        val messageDigest = MessageDigest.getInstance(
            when (algorithm) {
                "sha256" -> "SHA-256"
                "sha512" -> "SHA-512"
                else -> throw IOException("unknown digest algorithm $algorithm")
            }
        )

        var read = 0L
        var lastReported = 0L

        registry.openBlob(reference, digest).use { raw ->
            DigestInputStream(raw, messageDigest).use { input ->
                target.outputStream().buffered(BUFFER).use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count <= 0) break
                        out.write(buffer, 0, count)
                        read += count
                        if (read - lastReported >= PROGRESS_STEP) {
                            lastReported = read
                            onProgress(read)
                        }
                    }
                }
            }
        }

        val actual = messageDigest.digest()
            .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            target.delete()
            throw IOException("layer digest mismatch: expected $expected, got $actual")
        }
        onProgress(read)
    }

    /**
     * Fills in what an image assumes the runtime provides.
     *
     * An OCI image is only a filesystem: it has no `/etc/resolv.conf`, and its `/proc`,
     * `/sys` and `/dev` are empty mount points. proot binds the real ones over them,
     * but the directories have to exist first, and without a resolver configuration
     * nothing inside can resolve a hostname.
     */
    private fun prepareRootfs(rootfs: File) {
        listOf("proc", "sys", "dev", "tmp", "etc").forEach {
            File(rootfs, it).mkdirs()
        }

        val resolv = File(rootfs, "etc/resolv.conf")
        if (!resolv.exists() || resolv.length() == 0L) {
            // Android does not expose the system resolver through a file, so a public
            // resolver is written rather than leaving DNS broken.
            runCatching {
                resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }
        }

        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists() || hosts.length() == 0L) {
            runCatching { hosts.writeText("127.0.0.1 localhost\n::1 localhost\n") }
        }
    }

    private fun setState(key: String, state: PullState) {
        _states.value = _states.value + (key to state)
    }

    private companion object {
        const val TAG = "ImagePuller"
        const val BUFFER = 1 shl 16
        const val PROGRESS_STEP = 1L shl 20
    }
}
