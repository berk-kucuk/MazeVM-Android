package com.mazevm.android.oci

import android.util.Log
import com.mazevm.android.data.model.ImageConfig
import com.mazevm.android.data.model.ImageReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A read-only OCI distribution client.
 *
 * Pulling an image needs no Docker daemon at all: a registry is an HTTPS API that
 * hands out a manifest and a set of tar.gz layers. This speaks just enough of it to
 * resolve a tag to the arm64 manifest and stream the blobs.
 *
 * Authentication is the anonymous token flow. The registry answers an unauthenticated
 * request with a `WWW-Authenticate` challenge naming a token service; fetching a token
 * from it with no credentials is what grants pull access to public images.
 */
class RegistryClient {

    data class Descriptor(val digest: String, val size: Long, val mediaType: String)

    data class Manifest(
        val digest: String,
        val config: Descriptor,
        val layers: List<Descriptor>,
    ) {
        val totalLayerBytes: Long get() = layers.sumOf { it.size }
    }

    /** Cached per repository; tokens are short-lived but a pull is one burst. */
    private val tokens = mutableMapOf<String, String>()

    /**
     * Resolves [reference] to the manifest for [architecture].
     *
     * Most published tags point at an index listing one manifest per platform, so the
     * right one has to be selected before anything can be downloaded. Single-platform
     * repositories skip the index and are handled by falling through.
     */
    suspend fun resolve(
        reference: ImageReference,
        architecture: String = "arm64",
    ): Result<Manifest> = withContext(Dispatchers.IO) {
        runCatching {
            val body = fetchManifest(reference, reference.tag)
            val json = JSONObject(body.text)

            if (!json.has("manifests")) {
                return@runCatching parseManifest(body.digest, json)
            }

            val manifests = json.getJSONArray("manifests")
            var selected: String? = null
            for (index in 0 until manifests.length()) {
                val entry = manifests.getJSONObject(index)
                val platform = entry.optJSONObject("platform") ?: continue
                if (platform.optString("os") != "linux") continue
                if (platform.optString("architecture") != architecture) continue
                // Skip attestation entries, which carry no runnable filesystem.
                if (platform.optString("os") == "unknown") continue
                selected = entry.getString("digest")
                break
            }

            val digest = selected ?: throw IOException(
                "${reference.canonical} publishes no linux/$architecture image"
            )
            val platformBody = fetchManifest(reference, digest)
            parseManifest(digest, JSONObject(platformBody.text))
        }
    }

    /** Reads Cmd, Env and WorkingDir out of the image's config blob. */
    suspend fun imageConfig(
        reference: ImageReference,
        manifest: Manifest,
    ): Result<ImageConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val text = openBlob(reference, manifest.config.digest).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val config = JSONObject(text).optJSONObject("config") ?: JSONObject()

            val entrypoint = config.optJSONArray("Entrypoint").toStringList()
            val cmd = config.optJSONArray("Cmd").toStringList()
            val command = (entrypoint + cmd).ifEmpty { listOf("/bin/sh") }

            ImageConfig(
                command = command,
                environment = config.optJSONArray("Env").toStringList(),
                workingDir = config.optString("WorkingDir").ifBlank { "/" },
            )
        }
    }

    /** Opens a layer or config blob for streaming. The caller closes it. */
    suspend fun openBlob(reference: ImageReference, digest: String): InputStream =
        withContext(Dispatchers.IO) {
            val url = "https://${host(reference)}/v2/${reference.repository}/blobs/$digest"
            val connection = authorised(url, reference, ACCEPT_ANY)
            if (connection.responseCode !in 200..299) {
                val code = connection.responseCode
                connection.disconnect()
                throw IOException("HTTP $code fetching blob $digest")
            }
            connection.inputStream
        }

    // ------------------------------------------------------------------ internals

    private class Body(val text: String, val digest: String)

    private fun fetchManifest(reference: ImageReference, tagOrDigest: String): Body {
        val url = "https://${host(reference)}/v2/${reference.repository}/manifests/$tagOrDigest"
        val connection = authorised(url, reference, ACCEPT_MANIFESTS)
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException(
                    "HTTP ${connection.responseCode} resolving ${reference.canonical}"
                )
            }
            val text = connection.inputStream.bufferedReader().readText()
            // Content-Digest is what the registry itself calls this document.
            val digest = connection.getHeaderField("Docker-Content-Digest")
                ?: tagOrDigest
            return Body(text, digest)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseManifest(digest: String, json: JSONObject): Manifest {
        val config = json.getJSONObject("config")
        val layers = json.getJSONArray("layers")

        return Manifest(
            digest = digest,
            config = Descriptor(
                digest = config.getString("digest"),
                size = config.optLong("size"),
                mediaType = config.optString("mediaType"),
            ),
            layers = (0 until layers.length()).map { index ->
                val layer = layers.getJSONObject(index)
                Descriptor(
                    digest = layer.getString("digest"),
                    size = layer.optLong("size"),
                    mediaType = layer.optString("mediaType"),
                )
            },
        )
    }

    /**
     * Performs a request, obtaining a bearer token first if the registry asks for one.
     * Redirects are followed by hand because blob downloads are routinely handed off to
     * a CDN on another host, where the Authorization header must not be resent.
     */
    private fun authorised(
        url: String,
        reference: ImageReference,
        accept: String,
    ): HttpURLConnection {
        var attempt = open(url, accept, tokens[reference.repository])

        if (attempt.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            val challenge = attempt.getHeaderField("WWW-Authenticate")
            attempt.disconnect()
            val token = requestToken(challenge, reference)
                ?: throw IOException("registry refused anonymous access")
            tokens[reference.repository] = token
            attempt = open(url, accept, token)
        }

        var redirects = 0
        while (attempt.responseCode in REDIRECTS && redirects < MAX_REDIRECTS) {
            val location = attempt.getHeaderField("Location")
            attempt.disconnect()
            if (location.isNullOrBlank()) throw IOException("redirect without a target")
            val target = URL(URL(url), location).toString()
            // A signed CDN URL carries its own credentials in the query string, and
            // sending ours as well makes some storage backends reject it outright.
            attempt = open(target, accept, token = null)
            redirects++
        }
        return attempt
    }

    private fun open(url: String, accept: String, token: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", USER_AGENT)
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    /** Parses `Bearer realm="…",service="…",scope="…"` and redeems it. */
    private fun requestToken(challenge: String?, reference: ImageReference): String? {
        val realm = challenge?.let { extract(it, "realm") }
            ?: defaultTokenRealm(reference)
            ?: return null
        val service = challenge?.let { extract(it, "service") }
        val scope = challenge?.let { extract(it, "scope") }
            ?: "repository:${reference.repository}:pull"

        val url = buildString {
            append(realm)
            append(if (realm.contains('?')) "&" else "?")
            append("scope=").append(scope.urlEncoded())
            service?.let { append("&service=").append(it.urlEncoded()) }
        }

        return runCatching {
            val connection = open(url, "application/json", token = null)
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val json = JSONObject(connection.inputStream.bufferedReader().readText())
                // Docker Hub calls it "token"; some registries call it "access_token".
                json.optString("token").ifBlank { json.optString("access_token") }
                    .ifBlank { null }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "token request failed", it) }.getOrNull()
    }

    private fun defaultTokenRealm(reference: ImageReference): String? =
        if (host(reference) == DOCKER_HUB_HOST) DOCKER_HUB_TOKEN_URL else null

    private fun host(reference: ImageReference): String =
        if (reference.registry == ImageReference.DEFAULT_REGISTRY) DOCKER_HUB_HOST
        else reference.registry

    private fun extract(header: String, key: String): String? =
        Regex("$key=\"([^\"]*)\"").find(header)?.groupValues?.get(1)

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    private fun org.json.JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList()
        else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

    private companion object {
        const val TAG = "RegistryClient"
        const val USER_AGENT = "MazeVM/1.0 (Android)"

        /** docker.io is a friendly alias; the API actually lives here. */
        const val DOCKER_HUB_HOST = "registry-1.docker.io"
        const val DOCKER_HUB_TOKEN_URL = "https://auth.docker.io/token?service=registry.docker.io"

        val REDIRECTS = listOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 8

        val ACCEPT_MANIFESTS = listOf(
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.docker.distribution.manifest.v2+json",
        ).joinToString(",")

        const val ACCEPT_ANY = "*/*"
    }
}
