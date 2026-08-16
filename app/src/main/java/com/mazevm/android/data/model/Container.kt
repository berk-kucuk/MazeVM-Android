package com.mazevm.android.data.model

import kotlinx.serialization.Serializable

/**
 * How an instance is executed.
 *
 * The two are not substitutes. A virtual machine boots its own kernel, so it can run a
 * desktop, load modules and open raw sockets, but every instruction goes through
 * software emulation. A container borrows Android's kernel, so ARM64 code runs at full
 * speed and starts instantly, but it inherits the app's total lack of privilege: no
 * netfilter, no raw sockets, no modules, no real isolation.
 */
@Serializable
enum class Backend {
    QEMU,
    CONTAINER,
}

/** A reference to an image in an OCI registry, e.g. `docker.io/library/alpine:latest`. */
@Serializable
data class ImageReference(
    val registry: String = DEFAULT_REGISTRY,
    val repository: String,
    val tag: String = "latest",
) {
    /** What the user typed, normalised back into one line. */
    val canonical: String
        get() = when {
            registry == DEFAULT_REGISTRY && repository.startsWith("library/") ->
                "${repository.removePrefix("library/")}:$tag"
            registry == DEFAULT_REGISTRY -> "$repository:$tag"
            else -> "$registry/$repository:$tag"
        }

    /** Short label for a card title, e.g. "alpine". */
    val shortName: String
        get() = repository.substringAfterLast('/')

    companion object {
        const val DEFAULT_REGISTRY = "docker.io"

        /**
         * Parses Docker's shorthand the way the CLI does: a bare name means an official
         * image on Docker Hub, one path segment means a Hub user, and anything with a
         * dot or a port in the first segment is a registry host.
         */
        fun parse(input: String): ImageReference? {
            val trimmed = input.trim().removePrefix("docker://")
            if (trimmed.isEmpty()) return null

            val withoutDigest = trimmed.substringBefore('@')
            val firstSegment = withoutDigest.substringBefore('/')
            val looksLikeHost = firstSegment.contains('.') ||
                firstSegment.contains(':') ||
                firstSegment == "localhost"

            val registry = if (looksLikeHost && withoutDigest.contains('/')) {
                firstSegment
            } else {
                DEFAULT_REGISTRY
            }

            val remainder = if (registry == DEFAULT_REGISTRY) {
                withoutDigest
            } else {
                withoutDigest.substringAfter('/')
            }

            // The tag separator is the last colon, but only when it is not part of a
            // host:port that ended up here.
            val colon = remainder.lastIndexOf(':')
            val slash = remainder.lastIndexOf('/')
            val hasTag = colon > slash
            val repositoryPart = if (hasTag) remainder.substring(0, colon) else remainder
            val tag = if (hasTag) remainder.substring(colon + 1) else "latest"

            if (repositoryPart.isBlank() || tag.isBlank()) return null

            val repository = if (registry == DEFAULT_REGISTRY && !repositoryPart.contains('/')) {
                "library/$repositoryPart"
            } else {
                repositoryPart
            }

            return ImageReference(registry, repository, tag)
        }
    }
}

/**
 * A container instance. Kept separate from [VmConfig] rather than folded into it,
 * because almost nothing they configure overlaps: a container has no firmware, no
 * emulated hardware and no display, and a machine has no image reference or layer set.
 */
@Serializable
data class ContainerConfig(
    val id: String,
    val name: String,
    val image: ImageReference,
    /** Manifest digest the rootfs was built from, so an update can be detected. */
    val digest: String? = null,
    /** Directory holding the extracted, merged layers. */
    val rootfsPath: String,
    /** Entry point, taken from the image config unless overridden. */
    val command: List<String> = listOf("/bin/sh"),
    val environment: List<String> = emptyList(),
    val workingDir: String = "/",
    /** Extra host paths visible inside, as `hostPath:guestPath`. */
    val binds: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val lastStartedAt: Long = 0L,
)

/** Everything the runtime needs from an image's config blob. */
data class ImageConfig(
    val command: List<String>,
    val environment: List<String>,
    val workingDir: String,
)

/** Progress of a `docker pull`-equivalent. */
sealed interface PullState {
    data object Resolving : PullState

    data class Downloading(
        val layer: Int,
        val layerCount: Int,
        val bytes: Long,
        val totalBytes: Long,
    ) : PullState {
        val fraction: Float
            get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    data class Extracting(val layer: Int, val layerCount: Int) : PullState

    data class Failed(val reason: String) : PullState

    data object Completed : PullState

    val isBusy: Boolean
        get() = this is Resolving || this is Downloading || this is Extracting
}
