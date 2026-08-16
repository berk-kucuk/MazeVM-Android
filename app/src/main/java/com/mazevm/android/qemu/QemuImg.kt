package com.mazevm.android.qemu

import com.mazevm.android.data.model.DiskFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Thin wrapper around the bundled `qemu-img` binary. */
class QemuImg(private val runtime: QemuRuntime) {

    class QemuImgException(val exitCode: Int, val output: String) :
        Exception("qemu-img failed ($exitCode): ${output.trim().takeLast(400)}")

    /** Creates a sparse image. qcow2 only occupies what the guest actually writes. */
    suspend fun create(target: File, sizeBytes: Long, format: DiskFormat): Result<File> =
        run("create", "-f", format.qemuName, target.absolutePath, sizeBytes.toString())
            .map { target }

    /** Grows an existing image. Shrinking is not offered; it silently loses data. */
    suspend fun resize(target: File, newSizeBytes: Long): Result<Unit> =
        run("resize", target.absolutePath, newSizeBytes.toString()).map { }

    /**
     * Converts a downloaded cloud image into qcow2 and grows it in one step, so the
     * guest sees a disk large enough to be useful rather than the 2 GiB the publisher
     * shipped.
     */
    suspend fun convertAndGrow(
        source: File,
        target: File,
        targetFormat: DiskFormat,
        finalSizeBytes: Long,
    ): Result<File> {
        val converted = run(
            "convert", "-O", targetFormat.qemuName,
            source.absolutePath, target.absolutePath,
        )
        if (converted.isFailure) return Result.failure(converted.exceptionOrNull()!!)

        val current = info(target).getOrNull()?.virtualSizeBytes ?: 0L
        if (finalSizeBytes > current) {
            val grown = resize(target, finalSizeBytes)
            if (grown.isFailure) return Result.failure(grown.exceptionOrNull()!!)
        }
        return Result.success(target)
    }

    data class ImageInfo(
        val format: String,
        val virtualSizeBytes: Long,
        val actualSizeBytes: Long,
    )

    suspend fun info(file: File): Result<ImageInfo> =
        run("info", "--output=json", file.absolutePath).mapCatching { json ->
            ImageInfo(
                format = json.jsonString("format") ?: "unknown",
                virtualSizeBytes = json.jsonLong("virtual-size") ?: 0L,
                actualSizeBytes = json.jsonLong("actual-size") ?: file.length(),
            )
        }

    /** Detects the real format of a downloaded file, which often lies about it. */
    suspend fun probeFormat(file: File): DiskFormat? =
        when (info(file).getOrNull()?.format) {
            "qcow2" -> DiskFormat.QCOW2
            "raw" -> DiskFormat.RAW
            else -> null
        }

    private suspend fun run(vararg args: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val binary = runtime.imgBinary
            check(binary.canExecute()) { "qemu-img is missing from this build" }

            val process = ProcessBuilder(listOf(binary.absolutePath) + args)
                .redirectErrorStream(true)
                .apply { environment().putAll(runtime.environment()) }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) throw QemuImgException(code, output)
            output
        }
    }
}

// qemu-img's JSON is flat and machine-generated, so a targeted scan beats pulling in a
// parser and beats org.json's exception-on-missing-key behaviour.
private fun String.jsonLong(key: String): Long? =
    Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(this)?.groupValues?.get(1)?.toLongOrNull()

private fun String.jsonString(key: String): String? =
    Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(this)?.groupValues?.get(1)
