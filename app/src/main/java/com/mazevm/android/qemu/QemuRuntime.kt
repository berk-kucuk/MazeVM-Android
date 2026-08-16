package com.mazevm.android.qemu

import android.content.Context
import android.os.Build
import android.util.Log
import com.mazevm.android.core.Storage
import com.mazevm.android.data.model.Arch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Locates the QEMU binaries and keeps the firmware directory in sync with the APK.
 *
 * Android will not execute a file out of the app's data directory, so the binaries are
 * shipped as `lib*.so` inside `jniLibs` and the installer unpacks them into
 * [android.content.pm.ApplicationInfo.nativeLibraryDir], which is the one app-owned
 * directory that stays executable. `useLegacyPackaging` in the Gradle file is what
 * forces that extraction to happen.
 */
class QemuRuntime(
    private val context: Context,
    private val storage: Storage,
) {

    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    /** Firmware, keymaps and ROMs; passed to QEMU as `-L`. */
    val dataDir: File get() = storage.qemuDataDir

    fun binary(name: String): File = nativeDir.resolve("lib$name.so")

    fun systemBinary(arch: Arch): File = binary("qemu-system-${arch.qemuSuffix}")

    val imgBinary: File get() = binary("qemu-img")

    fun isAvailable(arch: Arch = Arch.AARCH64): Boolean =
        systemBinary(arch).canExecute() && imgBinary.canExecute()

    /** True when the device can run the binaries at all. */
    val abiSupported: Boolean
        get() = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    val deviceAbi: String get() = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    /** Environment every QEMU child process needs. */
    fun environment(): Map<String, String> = mapOf(
        // QEMU is linked against glib/pixman shipped alongside it in the same directory.
        "LD_LIBRARY_PATH" to nativeDir.absolutePath,
        "HOME" to context.filesDir.absolutePath,
        "TMPDIR" to context.cacheDir.absolutePath,
        "QEMU_AUDIO_DRV" to "none",
        // Without this QEMU tries to read /etc/localtime, which Android does not expose.
        "TZ" to "UTC",
    )

    suspend fun version(arch: Arch = Arch.AARCH64): String? = withContext(Dispatchers.IO) {
        if (!isAvailable(arch)) return@withContext null
        runCatching {
            val process = ProcessBuilder(systemBinary(arch).absolutePath, "--version")
                .redirectErrorStream(true)
                .apply { environment().putAll(this@QemuRuntime.environment()) }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            // "QEMU emulator version 9.1.0" -> "9.1.0"
            Regex("version\\s+([0-9][0-9.]*)").find(output)?.groupValues?.get(1)
                ?: output.lineSequence().firstOrNull()?.trim()
        }.getOrNull()
    }

    /**
     * Copies `assets/qemu-data` into [dataDir] once per app version. Firmware blobs are
     * stored uncompressed in the APK (see `noCompress` in the Gradle file) so this is a
     * straight byte copy.
     */
    suspend fun prepareDataDir(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val stamp = dataDir.resolve(".version")
            val current = versionStamp()
            if (stamp.takeIf { it.exists() }?.readText() == current) return@runCatching

            dataDir.listFiles()?.forEach { it.deleteRecursively() }
            copyAssetTree("qemu-data", dataDir)
            padFirmwareImages()
            stamp.writeText(current)
        }
    }

    private fun versionStamp(): String =
        "${context.packageManager.getPackageInfo(context.packageName, 0).versionName}" +
            "/${com.mazevm.android.BuildConfig.VERSION_CODE}"

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // A leaf: assets.list returns an empty array for files as well as empty dirs.
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetTree("$assetPath/$child", target.resolve(child))
        }
    }

    /**
     * `-drive if=pflash` refuses any image whose size is not exactly the flash size.
     * The edk2 builds ship a 2 MiB or 3 MiB blob, so pad the code image out to the
     * 64 MiB the `virt` machine expects.
     */
    private fun padFirmwareImages() {
        val code = dataDir.resolve(FIRMWARE_CODE)
        if (code.exists() && code.length() < FLASH_SIZE) {
            RandomAccessFile(code, "rw").use { it.setLength(FLASH_SIZE) }
        }
    }

    /** Read-only UEFI code flash for aarch64, or null when the build has no firmware. */
    fun firmwareCode(): File? = dataDir.resolve(FIRMWARE_CODE).takeIf { it.exists() }

    /**
     * Per-machine writable UEFI variable store. Created zero-filled on first use so
     * boot entries and the boot order survive reboots.
     */
    fun firmwareVars(vmId: String): File {
        val file = storage.disksDir.resolve("$vmId-efivars.fd")
        if (!file.exists() || file.length() != FLASH_SIZE) {
            runCatching {
                RandomAccessFile(file, "rw").use { it.setLength(FLASH_SIZE) }
            }.onFailure { Log.w(TAG, "could not create EFI variable store", it) }
        }
        return file
    }

    companion object {
        private const val TAG = "QemuRuntime"

        /** Name of the edk2 image inside `assets/qemu-data`, produced by tools/build-qemu.sh. */
        const val FIRMWARE_CODE = "QEMU_EFI.fd"

        /** The `virt` machine hard-codes 64 MiB pflash banks. */
        const val FLASH_SIZE = 64L * 1024 * 1024
    }
}
