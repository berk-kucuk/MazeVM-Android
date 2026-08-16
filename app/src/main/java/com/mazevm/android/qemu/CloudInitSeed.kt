package com.mazevm.android.qemu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the "cidata" seed disc that cloud images need in order to be usable.
 *
 * Debian's and Ubuntu's generic cloud images ship with no password and no authorised
 * key, so without a seed they boot to a login prompt nobody can get past. cloud-init's
 * NoCloud source looks for a filesystem labelled `cidata` holding `user-data` and
 * `meta-data`, which is what this writes.
 *
 * The image is a hand-rolled ISO 9660 with a Joliet supplementary tree. Joliet is what
 * preserves the exact lowercase, hyphenated file names; the primary tree can only carry
 * the uppercase 8.3-ish forms that cloud-init would not recognise.
 */
object CloudInitSeed {

    private const val SECTOR = 2048
    private const val VOLUME_ID = "cidata"

    /** Sector layout. Everything before the file data is fixed-size. */
    private const val LBA_PVD = 16
    private const val LBA_SVD = 17
    private const val LBA_TERMINATOR = 18
    private const val LBA_PATH_L = 19
    private const val LBA_PATH_M = 20
    private const val LBA_PATH_L_JOLIET = 21
    private const val LBA_PATH_M_JOLIET = 22
    private const val LBA_ROOT = 23
    private const val LBA_ROOT_JOLIET = 24
    private const val LBA_FILES = 25

    data class Options(
        val hostname: String,
        val username: String,
        val password: String,
        val instanceId: String,
        /** Turning this off leaves only key-based access, which the app cannot set up yet. */
        val allowSshPassword: Boolean = true,
        /** Extra cloud-config lines appended verbatim under `runcmd`. */
        val extraRunCommands: List<String> = emptyList(),
    )

    suspend fun write(target: File, options: Options): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                target.parentFile?.mkdirs()
                val userData = userData(options).toByteArray(Charsets.UTF_8)
                val metaData = metaData(options).toByteArray(Charsets.UTF_8)
                target.writeBytes(buildIso(userData, metaData))
                target
            }
        }

    fun userData(options: Options): String = buildString {
        appendLine("#cloud-config")
        appendLine("hostname: ${options.hostname}")
        appendLine("manage_etc_hosts: true")
        appendLine("disable_root: false")
        appendLine("ssh_pwauth: ${options.allowSshPassword}")
        appendLine("users:")
        appendLine("  - name: ${options.username}")
        appendLine("    groups: [adm, sudo]")
        appendLine("    sudo: 'ALL=(ALL) NOPASSWD:ALL'")
        appendLine("    shell: /bin/bash")
        appendLine("    lock_passwd: false")
        appendLine("chpasswd:")
        appendLine("  expire: false")
        appendLine("  list: |")
        appendLine("    ${options.username}:${options.password}")
        appendLine("    root:${options.password}")
        // Emulated boots are slow enough that the default 120s can expire first.
        appendLine("growpart:")
        appendLine("  mode: auto")
        appendLine("  devices: ['/']")
        appendLine("resize_rootfs: true")
        if (options.extraRunCommands.isNotEmpty()) {
            appendLine("runcmd:")
            options.extraRunCommands.forEach { appendLine("  - $it") }
        }
    }

    fun metaData(options: Options): String = buildString {
        appendLine("instance-id: ${options.instanceId}")
        appendLine("local-hostname: ${options.hostname}")
    }

    // ---------------------------------------------------------------- ISO 9660

    private fun buildIso(userData: ByteArray, metaData: ByteArray): ByteArray {
        val userSectors = sectorsFor(userData.size)
        val metaSectors = sectorsFor(metaData.size)

        val userLba = LBA_FILES
        val metaLba = userLba + userSectors
        val totalSectors = metaLba + metaSectors

        val image = ByteArray(totalSectors * SECTOR)
        val now = isoDateTime()
        val shortNow = isoShortDateTime()

        val files = listOf(
            IsoFile("USER_DATA.;1", "user-data", userLba, userData.size),
            IsoFile("META_DATA.;1", "meta-data", metaLba, metaData.size),
        )

        val rootPrimary = directorySector(files, LBA_ROOT, shortNow, joliet = false)
        val rootJoliet = directorySector(files, LBA_ROOT_JOLIET, shortNow, joliet = true)

        writeSector(image, LBA_PVD, primaryVolumeDescriptor(totalSectors, shortNow, now))
        writeSector(image, LBA_SVD, supplementaryVolumeDescriptor(totalSectors, shortNow, now))
        writeSector(image, LBA_TERMINATOR, terminator())
        writeSector(image, LBA_PATH_L, pathTable(LBA_ROOT, littleEndian = true))
        writeSector(image, LBA_PATH_M, pathTable(LBA_ROOT, littleEndian = false))
        writeSector(image, LBA_PATH_L_JOLIET, pathTable(LBA_ROOT_JOLIET, littleEndian = true))
        writeSector(image, LBA_PATH_M_JOLIET, pathTable(LBA_ROOT_JOLIET, littleEndian = false))
        writeSector(image, LBA_ROOT, rootPrimary)
        writeSector(image, LBA_ROOT_JOLIET, rootJoliet)

        userData.copyInto(image, userLba * SECTOR)
        metaData.copyInto(image, metaLba * SECTOR)
        return image
    }

    private class IsoFile(
        val isoName: String,
        val jolietName: String,
        val lba: Int,
        val size: Int,
    )

    private fun sectorsFor(bytes: Int): Int = maxOf(1, (bytes + SECTOR - 1) / SECTOR)

    private fun writeSector(image: ByteArray, lba: Int, data: ByteArray) {
        data.copyInto(image, lba * SECTOR, 0, minOf(data.size, SECTOR))
    }

    private fun primaryVolumeDescriptor(
        totalSectors: Int,
        rootDate: ByteArray,
        volumeDate: ByteArray,
    ): ByteArray {
        val sector = ByteArray(SECTOR)
        sector[0] = 1 // primary volume descriptor
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(sector, 1)
        sector[6] = 1 // version

        padded(VOLUME_ID.uppercase(Locale.ROOT), 32, ' ').copyInto(sector, 40)
        both32(totalSectors).copyInto(sector, 80)
        both16(1).copyInto(sector, 120) // volume set size
        both16(1).copyInto(sector, 124) // volume sequence number
        both16(SECTOR).copyInto(sector, 128)
        both32(PATH_TABLE_SIZE).copyInto(sector, 132)
        le32(LBA_PATH_L).copyInto(sector, 140)
        be32(LBA_PATH_M).copyInto(sector, 148)

        rootDirectoryRecord(LBA_ROOT, rootDate).copyInto(sector, 156)

        padded("", 128, ' ').copyInto(sector, 190) // volume set identifier
        padded("MazeVM", 128, ' ').copyInto(sector, 318) // publisher
        padded("MazeVM", 128, ' ').copyInto(sector, 446) // data preparer
        padded("MAZEVM CLOUD-INIT SEED", 128, ' ').copyInto(sector, 574) // application
        padded("", 37, ' ').copyInto(sector, 702)
        padded("", 37, ' ').copyInto(sector, 739)
        padded("", 37, ' ').copyInto(sector, 776)

        volumeDate.copyInto(sector, 813) // creation
        volumeDate.copyInto(sector, 830) // modification
        NULL_DATE.copyInto(sector, 847) // expiration
        volumeDate.copyInto(sector, 864) // effective
        sector[881] = 1 // file structure version
        return sector
    }

    private fun supplementaryVolumeDescriptor(
        totalSectors: Int,
        rootDate: ByteArray,
        volumeDate: ByteArray,
    ): ByteArray {
        val sector = ByteArray(SECTOR)
        sector[0] = 2 // supplementary volume descriptor
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(sector, 1)
        sector[6] = 1

        ucs2(VOLUME_ID).let { name ->
            val field = ByteArray(32) { if (it % 2 == 0) 0 else 0x20 }
            name.copyInto(field, 0, 0, minOf(name.size, 32))
            field.copyInto(sector, 40)
        }

        both32(totalSectors).copyInto(sector, 80)
        // UCS-2 level 3 escape sequence: this is what marks the tree as Joliet.
        "%/E".toByteArray(Charsets.US_ASCII).copyInto(sector, 88)
        both16(1).copyInto(sector, 120)
        both16(1).copyInto(sector, 124)
        both16(SECTOR).copyInto(sector, 128)
        both32(PATH_TABLE_SIZE).copyInto(sector, 132)
        le32(LBA_PATH_L_JOLIET).copyInto(sector, 140)
        be32(LBA_PATH_M_JOLIET).copyInto(sector, 148)

        rootDirectoryRecord(LBA_ROOT_JOLIET, rootDate).copyInto(sector, 156)

        ucs2Field("", 128).copyInto(sector, 190)
        ucs2Field("MazeVM", 128).copyInto(sector, 318)
        ucs2Field("MazeVM", 128).copyInto(sector, 446)
        ucs2Field("MazeVM cloud-init seed", 128).copyInto(sector, 574)
        ucs2Field("", 37).copyInto(sector, 702)
        ucs2Field("", 37).copyInto(sector, 739)
        ucs2Field("", 37).copyInto(sector, 776)

        volumeDate.copyInto(sector, 813)
        volumeDate.copyInto(sector, 830)
        NULL_DATE.copyInto(sector, 847)
        volumeDate.copyInto(sector, 864)
        sector[881] = 1
        return sector
    }

    private fun terminator(): ByteArray {
        val sector = ByteArray(SECTOR)
        sector[0] = 0xFF.toByte()
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(sector, 1)
        sector[6] = 1
        return sector
    }

    /** A single-entry path table: just the root, whose identifier is one zero byte. */
    private fun pathTable(rootLba: Int, littleEndian: Boolean): ByteArray {
        val record = ByteArray(10)
        record[0] = 1 // directory identifier length
        record[1] = 0 // extended attribute length
        val location = if (littleEndian) le32(rootLba) else be32(rootLba)
        location.copyInto(record, 2)
        val parent = if (littleEndian) le16(1) else be16(1)
        parent.copyInto(record, 6)
        record[8] = 0 // identifier for the root directory
        record[9] = 0 // padding to an even length
        return record
    }

    private const val PATH_TABLE_SIZE = 10

    private fun rootDirectoryRecord(lba: Int, date: ByteArray): ByteArray =
        directoryRecord(byteArrayOf(0), lba, SECTOR, isDirectory = true, date = date)

    private fun directorySector(
        files: List<IsoFile>,
        selfLba: Int,
        date: ByteArray,
        joliet: Boolean,
    ): ByteArray {
        val sector = ByteArray(SECTOR)
        var offset = 0

        fun put(record: ByteArray) {
            record.copyInto(sector, offset)
            offset += record.size
        }

        put(directoryRecord(byteArrayOf(0), selfLba, SECTOR, isDirectory = true, date = date))
        put(directoryRecord(byteArrayOf(1), selfLba, SECTOR, isDirectory = true, date = date))

        for (file in files) {
            val name = if (joliet) ucs2("${file.jolietName};1") else
                file.isoName.toByteArray(Charsets.US_ASCII)
            put(directoryRecord(name, file.lba, file.size, isDirectory = false, date = date))
        }
        return sector
    }

    private fun directoryRecord(
        identifier: ByteArray,
        lba: Int,
        dataLength: Int,
        isDirectory: Boolean,
        date: ByteArray,
    ): ByteArray {
        var length = 33 + identifier.size
        if (length % 2 != 0) length++ // records must start on an even offset

        val record = ByteArray(length)
        record[0] = length.toByte()
        record[1] = 0
        both32(lba).copyInto(record, 2)
        both32(dataLength).copyInto(record, 10)
        date.copyInto(record, 18)
        record[25] = if (isDirectory) 0x02 else 0x00
        record[26] = 0 // file unit size
        record[27] = 0 // interleave gap
        both16(1).copyInto(record, 28)
        record[32] = identifier.size.toByte()
        identifier.copyInto(record, 33)
        return record
    }

    // ------------------------------------------------------------- primitives

    private fun le16(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    private fun be16(value: Int) = byteArrayOf(
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun le32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun be32(value: Int) = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /** ISO 9660 stores most numbers twice, little-endian then big-endian. */
    private fun both16(value: Int) = le16(value) + be16(value)

    private fun both32(value: Int) = le32(value) + be32(value)

    private fun padded(text: String, length: Int, pad: Char): ByteArray {
        val bytes = ByteArray(length) { pad.code.toByte() }
        val source = text.toByteArray(Charsets.US_ASCII)
        source.copyInto(bytes, 0, 0, minOf(source.size, length))
        return bytes
    }

    private fun ucs2(text: String): ByteArray {
        val out = ByteArray(text.length * 2)
        text.forEachIndexed { index, ch ->
            out[index * 2] = ((ch.code shr 8) and 0xFF).toByte()
            out[index * 2 + 1] = (ch.code and 0xFF).toByte()
        }
        return out
    }

    /** UCS-2 field padded with wide spaces, as Joliet requires. */
    private fun ucs2Field(text: String, length: Int): ByteArray {
        val field = ByteArray(length) { if (it % 2 == 0) 0 else 0x20 }
        val source = ucs2(text)
        source.copyInto(field, 0, 0, minOf(source.size, length))
        return field
    }

    /** The 7-byte form used inside directory records. */
    private fun isoShortDateTime(): ByteArray {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return byteArrayOf(
            (cal.get(Calendar.YEAR) - 1900).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte(),
            0, // GMT offset, in 15-minute intervals
        )
    }

    /** The 17-byte decimal form used inside volume descriptors. */
    private fun isoDateTime(): ByteArray {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val text = String.format(
            Locale.ROOT,
            "%04d%02d%02d%02d%02d%02d%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
            cal.get(Calendar.MILLISECOND) / 10,
        )
        return text.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    }

    private val NULL_DATE = ByteArray(17).also {
        for (i in 0 until 16) it[i] = '0'.code.toByte()
    }
}
