package com.mazevm.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mazevm.android.MazeApp
import com.mazevm.android.data.model.BootStyle
import com.mazevm.android.data.model.ContainerConfig
import com.mazevm.android.data.model.DiskFormat
import com.mazevm.android.data.model.DisplayMode
import com.mazevm.android.data.model.DistroImage
import com.mazevm.android.data.model.ImageRef
import com.mazevm.android.data.model.ImageReference
import com.mazevm.android.data.model.PortForward
import com.mazevm.android.data.model.VmConfig
import com.mazevm.android.data.model.VmState
import com.mazevm.android.download.DownloadService
import com.mazevm.android.download.DownloadState
import com.mazevm.android.qemu.CloudInitSeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Screen-facing state and actions.
 *
 * Everything long-lived lives on [MazeApp]; this only adapts it for Compose and owns
 * the one-shot messages the UI shows in a snackbar.
 */
class MazeViewModel(private val app: MazeApp) : ViewModel() {

    val machines = app.vmStore.machines
    val vmStates = app.vmManager.states
    val catalog = app.catalog.catalog
    val catalogRefreshing = app.catalog.refreshing
    val downloadStates = app.downloader.states
    val installedImages = app.imageLibrary.installed
    val settings = app.prefs.state

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    /** Text plus an error flag; the wording is resolved in the composables. */
    data class UiMessage(val text: String, val isError: Boolean = false)

    init {
        // A finished transfer has to be reflected in the "on device" tab and in the
        // create-machine action, so watch the whole map once rather than per download.
        viewModelScope.launch {
            app.downloader.states.collect { states ->
                if (states.values.any { it is DownloadState.Completed }) {
                    app.imageLibrary.refresh(app.catalog.images)
                }
            }
        }
    }

    val prefs get() = app.prefs
    val runtime get() = app.qemuRuntime
    val storage get() = app.storage

    fun vmProcess(vmId: String) = app.vmManager.processFor(vmId)

    fun stateOf(vmId: String): VmState = app.vmManager.stateOf(vmId)

    fun findMachine(id: String): VmConfig? = app.vmStore.find(id)

    fun findImage(id: String): DistroImage? = app.catalog.find(id)

    fun locate(imageId: String): ImageRef? = app.catalog.locate(imageId)

    /** True when the live system the rootfs flow installs from is already downloaded. */
    fun hasLiveInstaller(): Boolean =
        app.catalog.liveInstaller()?.let { app.imageLibrary.isInstalled(it) } == true

    fun consumeMessage() {
        _message.value = null
    }

    fun report(text: String, isError: Boolean = false) {
        _message.value = UiMessage(text, isError)
    }

    // ------------------------------------------------------------------ machines

    fun defaultMachine(): VmConfig {
        val defaults = app.prefs.state.value
        return VmConfig(
            id = newId(),
            name = "",
            cpuCount = defaults.defaultCpus,
            ramMb = defaults.defaultRamMb,
            createdAt = System.currentTimeMillis(),
        )
    }

    fun save(config: VmConfig) {
        viewModelScope.launch { app.vmStore.upsert(config) }
    }

    fun delete(config: VmConfig) {
        viewModelScope.launch {
            if (stateOf(config.id).isActive) app.vmManager.forceStop(config.id)
            app.vmManager.dispose(config.id)
            app.vmStore.deleteWithFiles(config)
        }
    }

    fun duplicate(config: VmConfig) {
        viewModelScope.launch {
            // The clone gets a fresh disk path only if it has none to share; a copied
            // qcow2 would silently double the storage bill.
            app.vmStore.upsert(
                config.copy(
                    id = newId(),
                    name = "${config.name} (2)",
                    createdAt = System.currentTimeMillis(),
                    lastStartedAt = 0L,
                )
            )
        }
    }

    fun start(config: VmConfig) {
        viewModelScope.launch {
            val repaired = repairBootMedia(config)
            // repairBootMedia reports its own error and returns the config unchanged
            // when it cannot fix a machine (missing archive, missing live installer).
            // Starting anyway used to happen regardless of that outcome, which just
            // reproduced the exact same firmware prompt a moment later with no new
            // information — the earlier report() was easy to miss as a snackbar that
            // had already scrolled past by the time the identical UEFI failure showed
            // up again. Checking the actual result here is what makes the difference
            // between "here is what to do" and "try again, still broken".
            if (looksUnbootable(repaired)) return@launch
            app.vmManager.start(repaired).onFailure {
                report(it.message ?: "could not start", isError = true)
            }
        }
    }

    /**
     * Rebuilds the media of a machine that cannot possibly boot, and refuses to start
     * one that still cannot.
     *
     * Machines created from a root filesystem tarball were given an empty disk and
     * nothing else, so the firmware drops them at "No bootable option or device was
     * found" every time. This is deliberately structural rather than keyed on
     * [VmConfig.distroId]: catalog ids change between schema versions, and a machine
     * whose id no longer resolves was silently skipped by the earlier id-based version
     * of this, which is exactly how the problem survived a round of fixing.
     */
    private suspend fun repairBootMedia(config: VmConfig): VmConfig {
        if (!looksUnbootable(config)) return config

        // Prefer the image the machine came from; fall back to any tarball on device,
        // because that is the only kind of image that produces this shape of machine.
        val image = config.distroId?.let { app.catalog.find(it) }
            ?.takeIf { it.bootStyle == BootStyle.ROOTFS_TAR }
            ?: app.catalog.images.firstOrNull {
                it.bootStyle == BootStyle.ROOTFS_TAR && app.imageLibrary.isInstalled(it)
            }

        val payload = image?.let { app.imageLibrary.fileFor(it) }
        val liveImage = app.catalog.liveInstaller()
        val installer = liveImage?.let { app.imageLibrary.fileFor(it) }

        if (payload == null || installer == null) {
            // Starting anyway just reproduces the firmware prompt, which tells the user
            // nothing about what to do next.
            if (payload == null) {
                report("the root filesystem archive is missing; download it again", isError = true)
                return config
            }

            // Telling someone to go and fetch a different distribution before this one
            // will boot is a dead end dressed up as an error message, so the download
            // is started here instead. This is the only image in the catalog that
            // cannot boot on its own, and the live system is what installs it.
            val alreadyFetching = liveImage != null &&
                app.downloader.stateOf(liveImage.id)?.isBusy == true
            if (liveImage != null && !alreadyFetching) {
                DownloadService.ensureRunning(app)
                app.downloader.start(liveImage)
            }
            report(
                "Arch ships a root filesystem, not a bootable image. Downloading Alpine " +
                    "to install it from — start this machine again once that finishes.",
            )
            return config
        }

        val repaired = config.copy(
            payloadPath = payload.absolutePath,
            cdromPath = installer.absolutePath,
            display = DisplayMode.SERIAL,
        )
        app.vmStore.upsert(repaired)
        return repaired
    }

    /**
     * True when the firmware will find nothing to boot: no disc, no kernel, and a disk
     * that has never been written to. A freshly created qcow2 occupies almost nothing
     * on disk regardless of the size it advertises to the guest.
     */
    private fun looksUnbootable(config: VmConfig): Boolean {
        if (config.cdromPath != null || config.kernelPath != null) return false
        val disk = config.diskPath?.let { java.io.File(it) } ?: return true
        return !disk.exists() || disk.length() < EMPTY_DISK_THRESHOLD
    }

    fun stop(vmId: String) {
        viewModelScope.launch { app.vmManager.stop(vmId) }
    }

    fun forceStop(vmId: String) {
        viewModelScope.launch { app.vmManager.forceStop(vmId) }
    }

    // -------------------------------------------------------------------- images

    fun refreshCatalog() {
        viewModelScope.launch {
            app.catalog.refresh()
            app.imageLibrary.refresh(app.catalog.images)
        }
    }

    fun download(image: DistroImage) {
        DownloadService.ensureRunning(app)
        app.downloader.start(image)
    }

    fun pauseDownload(image: DistroImage) = app.downloader.pause(image)

    fun cancelDownload(image: DistroImage) = app.downloader.cancel(image)

    fun deleteImage(image: DistroImage) {
        viewModelScope.launch { app.imageLibrary.delete(image) }
    }

    /**
     * Turns a downloaded image into a runnable machine.
     *
     * Cloud images are converted to qcow2, grown to a useful size and paired with a
     * generated cloud-init seed, because as published they have no password and a root
     * filesystem barely larger than the installed system.
     */
    fun createMachineFrom(
        ref: ImageRef,
        name: String,
        diskGb: Int,
        credentials: Credentials?,
        onCreated: (VmConfig) -> Unit,
    ) {
        val image = ref.image
        viewModelScope.launch {
            val source = app.imageLibrary.fileFor(image)
            if (source == null) {
                report("that image is not on this device", isError = true)
                return@launch
            }

            val id = newId()
            val defaults = app.prefs.state.value
            var config = VmConfig(
                id = id,
                name = name.ifBlank { ref.machineName },
                arch = image.arch,
                cpuCount = image.recommendedCpus.coerceAtLeast(defaults.defaultCpus),
                ramMb = image.recommendedRamMb,
                useUefi = image.needsUefi,
                display = DisplayMode.BOTH,
                distroId = image.id,
                createdAt = System.currentTimeMillis(),
                portForwards = listOf(PortForward(2222, 22)),
            )

            val targetBytes = diskGb.toLong() * 1024 * 1024 * 1024

            when (image.bootStyle) {
                BootStyle.DISK_IMAGE, BootStyle.CLOUD_IMAGE -> {
                    val disk = app.storage.diskFile("$id.qcow2")
                    val converted = app.qemuImg.convertAndGrow(
                        source = source,
                        target = disk,
                        targetFormat = DiskFormat.QCOW2,
                        finalSizeBytes = targetBytes,
                    )
                    if (converted.isFailure) {
                        report(
                            converted.exceptionOrNull()?.message ?: "disk conversion failed",
                            isError = true,
                        )
                        return@launch
                    }
                    config = config.copy(diskPath = disk.absolutePath)

                    if (image.bootStyle == BootStyle.CLOUD_IMAGE && credentials != null) {
                        val seed = app.storage.seedsDir.resolve("$id-seed.iso")
                        val written = CloudInitSeed.write(
                            seed,
                            CloudInitSeed.Options(
                                hostname = slugify(config.name),
                                username = credentials.username,
                                password = credentials.password,
                                instanceId = id,
                            ),
                        )
                        if (written.isSuccess) {
                            config = config.copy(cloudInitSeedPath = seed.absolutePath)
                        }
                    }
                }

                BootStyle.ISO_INSTALLER, BootStyle.LIVE_ISO -> {
                    val disk = app.storage.diskFile("$id.qcow2")
                    val created = app.qemuImg.create(disk, targetBytes, DiskFormat.QCOW2)
                    if (created.isFailure) {
                        report(
                            created.exceptionOrNull()?.message ?: "could not create the disk",
                            isError = true,
                        )
                        return@launch
                    }
                    config = config.copy(
                        diskPath = disk.absolutePath,
                        cdromPath = source.absolutePath,
                    )
                }

                BootStyle.ROOTFS_TAR -> {
                    // A tarball cannot boot. Left alone, the machine comes up to
                    // "No bootable option or device was found", which is what an empty
                    // disk under UEFI looks like. So the machine is assembled as an
                    // install bench instead: an empty target disk, the tarball exposed
                    // as a raw second drive, and a live system on the optical drive to
                    // do the work from.
                    val disk = app.storage.diskFile("$id.qcow2")
                    val created = app.qemuImg.create(disk, targetBytes, DiskFormat.QCOW2)
                    if (created.isFailure) {
                        report(
                            created.exceptionOrNull()?.message ?: "could not create the disk",
                            isError = true,
                        )
                        return@launch
                    }

                    val installerIso = app.catalog.liveInstaller()
                        ?.let { app.imageLibrary.fileFor(it) }

                    config = config.copy(
                        diskPath = disk.absolutePath,
                        // tar and gzip both read a stream, so the guest can unpack this
                        // straight off /dev/vdb without a filesystem in between.
                        payloadPath = source.absolutePath,
                        cdromPath = installerIso?.absolutePath,
                        display = DisplayMode.SERIAL,
                    )

                    if (installerIso == null) {
                        report("download Alpine as well: it is the live system this installs from")
                    }
                }
            }

            app.vmStore.upsert(config)
            app.imageLibrary.refresh(app.catalog.images)
            onCreated(config)
        }
    }

    data class Credentials(val username: String, val password: String)

    private fun slugify(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .ifBlank { "mazevm" }
            .take(32)

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    // ---------------------------------------------------------------- containers

    val containers = app.containerStore.containers
    val containerStates = app.containerManager.states
    val pullStates = app.imagePuller.states

    val containerRuntimeAvailable: Boolean get() = app.containerManager.runtime.isAvailable

    fun containerProcess(id: String) = app.containerManager.processFor(id)

    fun findContainer(id: String): ContainerConfig? = app.containerStore.find(id)

    /**
     * Pulls an image and registers a container for it.
     *
     * The container is only persisted once the pull succeeds, so a failed download
     * never leaves an entry pointing at a half-extracted filesystem.
     */
    fun createContainer(
        reference: ImageReference,
        name: String,
        onCreated: (ContainerConfig) -> Unit,
    ) {
        val id = newId()
        DownloadService.ensureRunning(app)
        app.imagePuller.pull(reference, id) { pulled ->
            viewModelScope.launch {
                val config = ContainerConfig(
                    id = id,
                    name = name.ifBlank { reference.shortName },
                    image = pulled.reference,
                    digest = pulled.digest,
                    rootfsPath = pulled.rootfs.absolutePath,
                    command = pulled.config.command,
                    environment = pulled.config.environment,
                    workingDir = pulled.config.workingDir,
                    createdAt = System.currentTimeMillis(),
                )
                app.containerStore.upsert(config)
                onCreated(config)
            }
        }
    }

    fun startContainer(config: ContainerConfig) {
        viewModelScope.launch {
            app.containerManager.start(config).onFailure {
                report(it.message ?: "could not start", isError = true)
            }
        }
    }

    fun stopContainer(id: String) {
        viewModelScope.launch { app.containerManager.stop(id) }
    }

    fun forceStopContainer(id: String) {
        viewModelScope.launch { app.containerManager.forceStop(id) }
    }

    fun deleteContainer(config: ContainerConfig) {
        viewModelScope.launch {
            if (app.containerManager.stateOf(config.id).isActive) {
                app.containerManager.forceStop(config.id)
            }
            app.containerManager.dispose(config.id)
            app.containerStore.deleteWithFiles(config)
        }
    }

    fun cancelPull(id: String) = app.imagePuller.cancel(id)

    private companion object {
        /**
         * A qcow2 that has only ever been created carries a header and little else.
         * Anything a guest has actually installed into is far larger than this.
         */
        const val EMPTY_DISK_THRESHOLD = 4L * 1024 * 1024
    }

    class Factory(private val app: MazeApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MazeViewModel(app) as T
    }
}
