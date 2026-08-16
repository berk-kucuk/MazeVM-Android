package com.mazevm.android.data

import android.content.Context
import android.util.Log
import com.mazevm.android.core.Storage
import com.mazevm.android.data.model.BootStyle
import com.mazevm.android.data.model.Catalog
import com.mazevm.android.data.model.Distro
import com.mazevm.android.data.model.DistroImage
import com.mazevm.android.data.model.ImageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * The list of downloadable distributions.
 *
 * A copy ships in the APK so the app is useful offline and on first launch. Release
 * URLs move and checksums change, so the same document can be re-fetched from
 * [REMOTE_URL] and cached; whichever copy has the newer `updated` field wins.
 */
class CatalogRepository(
    private val context: Context,
    private val storage: Storage,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _catalog = MutableStateFlow(Catalog())
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val images: List<DistroImage> get() = _catalog.value.images

    val distros: List<Distro> get() = _catalog.value.distros

    fun find(id: String): DistroImage? = _catalog.value.image(id)

    fun locate(imageId: String): ImageRef? = _catalog.value.locate(imageId)

    /** The Alpine live image, which the rootfs-tarball flow uses as its installer. */
    fun liveInstaller(): DistroImage? =
        _catalog.value.distros
            .firstOrNull { it.id == "alpine" }
            ?.images
            ?.firstOrNull { it.bootStyle == BootStyle.LIVE_ISO }

    /** Loads the cached copy if present, otherwise the one bundled in assets. */
    suspend fun load() = withContext(Dispatchers.IO) {
        val bundled = readBundled()
        val cached = readCached()
        _catalog.value = when {
            cached == null -> bundled
            cached.updated >= bundled.updated -> cached
            else -> bundled
        }
    }

    /**
     * Fetches a newer catalog. Failure is not an error the user has to act on: the
     * bundled list keeps working, so this reports back and moves on.
     */
    suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        _refreshing.value = true
        try {
            runCatching {
                val connection = (URL(REMOTE_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                val body = connection.use { it.inputStream.bufferedReader().readText() }
                val parsed = json.decodeFromString<Catalog>(body)
                require(parsed.distros.isNotEmpty()) { "catalog is empty" }

                storage.catalogCacheFile.writeText(body)
                if (parsed.updated >= _catalog.value.updated) _catalog.value = parsed
            }.onFailure { Log.i(TAG, "catalog refresh failed, keeping current copy", it) }
        } finally {
            _refreshing.value = false
        }
    }

    private fun readBundled(): Catalog = runCatching {
        context.assets.open("catalog.json").bufferedReader().use {
            json.decodeFromString<Catalog>(it.readText())
        }
    }.getOrElse {
        Log.e(TAG, "bundled catalog is unreadable", it)
        Catalog()
    }

    private fun readCached(): Catalog? = runCatching {
        storage.catalogCacheFile.takeIf { it.exists() }?.let {
            json.decodeFromString<Catalog>(it.readText())
        }
    }.getOrNull()

    companion object {
        private const val TAG = "CatalogRepository"

        /**
         * Where an updated catalog is looked for. Point this at your own fork if you
         * publish a different set of images.
         */
        const val REMOTE_URL =
            "https://raw.githubusercontent.com/mazevm/catalog/main/catalog.json"
    }
}

private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
    try {
        block(this)
    } finally {
        disconnect()
    }
