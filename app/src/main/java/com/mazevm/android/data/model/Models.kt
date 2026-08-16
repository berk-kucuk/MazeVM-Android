package com.mazevm.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Arch {
    @SerialName("aarch64") AARCH64,
    @SerialName("x86_64") X86_64;

    /** The `qemu-system-<x>` suffix, which is also the jniLibs binary name. */
    val qemuSuffix: String get() = if (this == AARCH64) "aarch64" else "x86_64"

    val label: String get() = if (this == AARCH64) "ARM64 (aarch64)" else "x86_64"
}

@Serializable
enum class DisplayMode {
    /** Only `-serial`, no graphics device at all. Cheapest and fastest. */
    @SerialName("serial") SERIAL,

    /** Only a VNC framebuffer. */
    @SerialName("vnc") VNC,

    /** Serial console and VNC framebuffer at the same time. */
    @SerialName("both") BOTH;

    val hasSerial: Boolean get() = this == SERIAL || this == BOTH
    val hasVnc: Boolean get() = this == VNC || this == BOTH
}

@Serializable
enum class DiskFormat {
    @SerialName("qcow2") QCOW2,
    @SerialName("raw") RAW;

    val qemuName: String get() = if (this == QCOW2) "qcow2" else "raw"
    val extension: String get() = if (this == QCOW2) "qcow2" else "img"
}

/**
 * How a downloaded artefact turns into something bootable. The UI branches on this
 * to decide what a machine created from the image should look like.
 */
@Serializable
enum class BootStyle {
    /** A ready-to-run disk image (qcow2/raw). Attach as the primary drive. */
    @SerialName("disk_image") DISK_IMAGE,

    /** A cloud image with no password; needs a cloud-init seed to be usable. */
    @SerialName("cloud_image") CLOUD_IMAGE,

    /** An installer ISO. Attach as CD and install onto a fresh disk. */
    @SerialName("iso_installer") ISO_INSTALLER,

    /** A live ISO that boots straight to a shell. */
    @SerialName("live_iso") LIVE_ISO,

    /** A bare root filesystem tarball. Needs manual partitioning; flagged as advanced. */
    @SerialName("rootfs_tar") ROOTFS_TAR,
}

/** Compression wrapper around the downloaded artefact, if any. */
@Serializable
enum class ArchiveType {
    @SerialName("none") NONE,
    @SerialName("gz") GZ,
    @SerialName("xz") XZ,
    @SerialName("bz2") BZ2,
    @SerialName("zip") ZIP;

    /** True when the payload is a single compressed file rather than a container. */
    val isSingleStream: Boolean get() = this == GZ || this == XZ || this == BZ2
}

@Serializable
data class Checksum(
    val algorithm: String,
    val value: String,
) {
    /** Java `MessageDigest` name for [algorithm]. */
    val digestName: String
        get() = when (algorithm.lowercase()) {
            "sha256" -> "SHA-256"
            "sha512" -> "SHA-512"
            "sha1" -> "SHA-1"
            "md5" -> "MD5"
            else -> algorithm.uppercase()
        }
}

@Serializable
data class PortForward(
    val hostPort: Int,
    val guestPort: Int,
    val protocol: String = "tcp",
)

/**
 * A downloadable artefact: one edition of one release of one distribution.
 *
 * Distributions publish several images per release that differ in ways a user has to
 * choose between — a netinst against a full installer, or one desktop against another
 * — so an image is never picked directly. It is reached through [Distro] and
 * [DistroRelease], which is what lets the UI ask "which release?" and "which desktop?"
 * instead of showing one flat list of near-identical names.
 */
@Serializable
data class DistroImage(
    val id: String,
    /** What distinguishes this artefact, e.g. "Netinst installer" or "Cloud image". */
    val edition: String,
    /** Desktop shipped in the image, or null when it is a console-only system. */
    val desktop: String? = null,
    val arch: Arch = Arch.AARCH64,
    val url: String,
    val downloadBytes: Long,
    val installedBytes: Long = downloadBytes,
    val archive: ArchiveType = ArchiveType.NONE,
    val bootStyle: BootStyle,
    val diskFormat: DiskFormat = DiskFormat.QCOW2,
    val checksum: Checksum? = null,
    val needsUefi: Boolean = true,
    val defaultUser: String? = null,
    val defaultPassword: String? = null,
    val recommendedRamMb: Int = 2048,
    val recommendedDiskGb: Int = 16,
    val recommendedCpus: Int = 2,
    /** Extra kernel arguments the image is known to want. */
    val kernelAppend: String? = null,
    /** Free-form English note shown under the edition. Not localised. */
    val notes: String? = null,
) {
    /** File name the artefact is stored under, after any decompression. */
    val fileName: String
        get() {
            val last = url.substringAfterLast('/').substringBefore('?')
            return when {
                archive.isSingleStream -> last.substringBeforeLast('.')
                archive == ArchiveType.ZIP -> last.removeSuffix(".zip")
                else -> last
            }
        }

    val hasDesktop: Boolean get() = desktop != null

    val isBootableDisk: Boolean
        get() = bootStyle == BootStyle.DISK_IMAGE || bootStyle == BootStyle.CLOUD_IMAGE

    val isIso: Boolean
        get() = bootStyle == BootStyle.ISO_INSTALLER || bootStyle == BootStyle.LIVE_ISO
}

/** One version of a distribution, holding every edition published for it. */
@Serializable
data class DistroRelease(
    val id: String,
    /** Shown on the release chip, e.g. "24.04 LTS". */
    val name: String,
    /** Short qualifier such as "Latest" or "Supported until 2032". */
    val note: String? = null,
    val editions: List<DistroImage> = emptyList(),
) {
    /** Distinct desktops on offer, for the desktop step of the picker. */
    val desktops: List<String> get() = editions.mapNotNull { it.desktop }.distinct()
}

/**
 * A distribution. The bundled catalog lives in `assets/catalog.json`; a newer copy may
 * be fetched from [com.mazevm.android.data.CatalogRepository.REMOTE_URL].
 */
@Serializable
data class Distro(
    val id: String,
    val name: String,
    /** One English line describing what the distribution is for. */
    val tagline: String = "",
    val homepage: String? = null,
    val releases: List<DistroRelease> = emptyList(),
) {
    val images: List<DistroImage> get() = releases.flatMap { it.editions }

    val latestRelease: DistroRelease? get() = releases.firstOrNull()
}

@Serializable
data class Catalog(
    val schema: Int = 2,
    val updated: String = "",
    val distros: List<Distro> = emptyList(),
) {
    val images: List<DistroImage> get() = distros.flatMap { it.images }

    fun image(id: String): DistroImage? = images.firstOrNull { it.id == id }

    /** Resolves an image back to the distribution and release it belongs to. */
    fun locate(imageId: String): ImageRef? {
        for (distro in distros) {
            for (release in distro.releases) {
                val image = release.editions.firstOrNull { it.id == imageId } ?: continue
                return ImageRef(distro, release, image)
            }
        }
        return null
    }
}

/** An image together with the context needed to name it in the UI. */
data class ImageRef(
    val distro: Distro,
    val release: DistroRelease,
    val image: DistroImage,
) {
    /** e.g. "Fedora 43 · Xfce" or "Kali Linux 2026.2 · Netinst installer". */
    val title: String
        get() = "${distro.name} ${release.name}"

    val subtitle: String
        get() = image.desktop?.let { "${image.edition} · $it" } ?: image.edition

    /** Default machine name when one is created from this image. */
    val machineName: String
        get() = image.desktop?.let { "${distro.name} ${release.name} $it" }
            ?: "${distro.name} ${release.name}"
}

/**
 * A configured virtual machine. Persisted as JSON by [com.mazevm.android.data.VmStore];
 * every field has a default so older files keep parsing after the schema grows.
 */
@Serializable
data class VmConfig(
    val id: String,
    val name: String,
    val arch: Arch = Arch.AARCH64,
    val machineType: String = "virt",
    val cpuModel: String = "cortex-a72",
    val cpuCount: Int = 2,
    val ramMb: Int = 2048,

    val diskPath: String? = null,
    val diskFormat: DiskFormat = DiskFormat.QCOW2,
    val cdromPath: String? = null,
    /**
     * A read-only second drive. Used to hand a root filesystem tarball to the guest:
     * `tar xzf /dev/vdb` reads it straight off the raw device, because tar and gzip
     * both consume a stream and ignore whatever trails it.
     */
    val payloadPath: String? = null,
    val kernelPath: String? = null,
    val initrdPath: String? = null,
    val kernelAppend: String? = null,

    val useUefi: Boolean = true,
    val display: DisplayMode = DisplayMode.BOTH,
    val vgaDevice: String = "virtio-gpu-pci",
    val screenWidth: Int = 1280,
    val screenHeight: Int = 720,

    val networkEnabled: Boolean = true,
    val portForwards: List<PortForward> = listOf(PortForward(2222, 22)),

    val snapshotMode: Boolean = false,
    val extraArgs: String = "",

    /** Catalog id this machine was created from, when applicable. */
    val distroId: String? = null,
    /** Path to a generated cloud-init seed ISO, for [BootStyle.CLOUD_IMAGE] machines. */
    val cloudInitSeedPath: String? = null,

    val createdAt: Long = 0L,
    val lastStartedAt: Long = 0L,
) {
    val hasBootMedia: Boolean
        get() = diskPath != null || cdromPath != null || kernelPath != null
}

enum class VmState {
    STOPPED, STARTING, RUNNING, STOPPING, FAILED;

    val isActive: Boolean get() = this == STARTING || this == RUNNING || this == STOPPING
}
