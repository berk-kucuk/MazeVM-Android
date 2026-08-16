package com.mazevm.android.oci

import com.mazevm.android.data.model.ImageReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Docker Hub's catalogue API, which is separate from the registry API in
 * [RegistryClient].
 *
 * The registry can pull an image but cannot answer "what images are there" or "which
 * architectures does this tag have". Hub exposes both anonymously, and the second one
 * matters here: this device can only run arm64, and a great many popular images are
 * amd64-only. Checking before the download rather than after saves the user a failed
 * pull and a confusing error.
 */
class DockerHub {

    data class Repository(
        val name: String,
        val description: String,
        val stars: Int,
        val pulls: Long,
        val isOfficial: Boolean,
    ) {
        /** Official images live under `library/` even though Hub prints them bare. */
        val reference: ImageReference
            get() = ImageReference(
                registry = ImageReference.DEFAULT_REGISTRY,
                repository = if (isOfficial && !name.contains('/')) "library/$name" else name,
                tag = "latest",
            )
    }

    data class Tag(
        val name: String,
        val arm64SizeBytes: Long?,
        val lastUpdated: String?,
    ) {
        /** Null size means Hub lists no linux/arm64 image for this tag. */
        val supportsArm64: Boolean get() = arm64SizeBytes != null
    }

    /**
     * Searches Hub, keeping only repositories that publish a linux/arm64 build.
     *
     * The search endpoint says nothing about architecture, so every candidate has to be
     * checked against its own tag. That costs one request each, run concurrently, and
     * it is worth it: a great many popular images are amd64-only, and without the
     * filter most of what a search returns is unusable on this device — which the user
     * would only discover after picking one.
     */
    suspend fun search(
        query: String,
        limit: Int = 25,
        arm64Only: Boolean = true,
    ): Result<List<Repository>> = withContext(Dispatchers.IO) {
        runCatching {
            if (query.isBlank()) return@runCatching emptyList()
            val url = "$HUB/v2/search/repositories/" +
                "?query=${query.trim().encoded()}&page_size=$limit"
            val json = JSONObject(get(url))
            val results = json.optJSONArray("results") ?: return@runCatching emptyList()

            val found = (0 until results.length()).map { index ->
                val entry = results.getJSONObject(index)
                Repository(
                    name = entry.optString("repo_name"),
                    description = entry.optString("short_description").trim(),
                    stars = entry.optInt("star_count"),
                    pulls = entry.optLong("pull_count"),
                    isOfficial = entry.optBoolean("is_official"),
                )
            }.filter { it.name.isNotBlank() }

            if (!arm64Only) return@runCatching found
            coroutineScope {
                found
                    .map { repository -> async { repository to supportsArm64(repository) } }
                    .awaitAll()
                    .filter { (_, supported) -> supported }
                    .map { (repository, _) -> repository }
            }
        }
    }

    /** Cached per repository, because a search re-runs as the query is typed. */
    private val arm64Cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private suspend fun supportsArm64(repository: Repository): Boolean {
        arm64Cache[repository.name]?.let { return it }
        val supported = tag(repository.reference).getOrNull()?.supportsArm64 ?: false
        arm64Cache[repository.name] = supported
        return supported
    }

    /** Lists tags newest first, each annotated with whether arm64 exists. */
    suspend fun tags(reference: ImageReference, limit: Int = 25): Result<List<Tag>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$HUB/v2/repositories/${reference.repository}/tags" +
                    "?page_size=$limit&ordering=last_updated"
                val json = JSONObject(get(url))
                val results = json.optJSONArray("results") ?: return@runCatching emptyList()

                (0 until results.length()).map { index ->
                    val entry = results.getJSONObject(index)
                    Tag(
                        name = entry.optString("name"),
                        arm64SizeBytes = entry.optJSONArray("images")?.let { images ->
                            (0 until images.length())
                                .map { images.getJSONObject(it) }
                                .firstOrNull {
                                    it.optString("os") == "linux" &&
                                        it.optString("architecture") == "arm64"
                                }
                                ?.optLong("size")
                                ?.takeIf { size -> size > 0 }
                        },
                        lastUpdated = entry.optString("last_updated").takeIf { it.isNotBlank() },
                    )
                }.filter { it.name.isNotBlank() }
            }
        }

    /** Details for a single tag, used when the user types a reference by hand. */
    suspend fun tag(reference: ImageReference): Result<Tag> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$HUB/v2/repositories/${reference.repository}/tags/${reference.tag}"
            val entry = JSONObject(get(url))
            Tag(
                name = entry.optString("name", reference.tag),
                arm64SizeBytes = entry.optJSONArray("images")?.let { images ->
                    (0 until images.length())
                        .map { images.getJSONObject(it) }
                        .firstOrNull {
                            it.optString("os") == "linux" &&
                                it.optString("architecture") == "arm64"
                        }
                        ?.optLong("size")
                        ?.takeIf { size -> size > 0 }
                },
                lastUpdated = entry.optString("last_updated").takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * A short list worth showing before the user has typed anything. Every entry here
     * is known to publish linux/arm64; the official `archlinux` image is deliberately
     * absent because it only ships amd64, and Arch Linux ARM's own build is used
     * instead.
     */
    fun suggestions(): List<Repository> = listOf(
        Repository("alpine", "Five megabytes to a shell. The quickest thing here.", 0, 0, true),
        Repository("kalilinux/kali-rolling", "Kali's own base image, without the desktop.", 0, 0, false),
        Repository("debian", "The stable base most other images are built on.", 0, 0, true),
        Repository("ubuntu", "Familiar, and what most tutorials assume.", 0, 0, true),
        Repository("fedora", "Recent packages and a newer toolchain.", 0, 0, true),
        Repository("menci/archlinuxarm", "Arch Linux ARM. The official archlinux image is amd64 only.", 0, 0, false),
        Repository("python", "Python with its toolchain already set up.", 0, 0, true),
        Repository("node", "Node.js with npm.", 0, 0, true),
    )

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} from Docker Hub")
            }
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun String.encoded(): String = URLEncoder.encode(this, "UTF-8")

    private companion object {
        const val HUB = "https://hub.docker.com"
        const val USER_AGENT = "MazeVM/1.0 (Android)"
    }
}
