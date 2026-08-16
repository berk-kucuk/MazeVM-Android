package com.mazevm.android.download

/** Progress of one catalog image, keyed by its id. */
sealed interface DownloadState {

    data object Queued : DownloadState

    data class Running(
        val bytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : DownloadState {
        val fraction: Float
            get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    data class Paused(val bytes: Long, val totalBytes: Long) : DownloadState

    /** Hashing the finished file. On a phone this can take a noticeable moment. */
    data class Verifying(val fraction: Float) : DownloadState

    /** Decompressing a .xz/.gz/.zip artefact into its final form. */
    data class Extracting(val fraction: Float) : DownloadState

    data class Failed(val reason: String, val resumable: Boolean) : DownloadState

    data object Completed : DownloadState

    val isBusy: Boolean
        get() = this is Running || this is Verifying || this is Extracting || this is Queued
}
