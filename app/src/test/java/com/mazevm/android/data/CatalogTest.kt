package com.mazevm.android.data

import com.mazevm.android.data.model.BootStyle
import com.mazevm.android.data.model.Catalog
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled catalog is parsed at runtime, so a typo in it would only surface as an
 * empty Images tab on a device. These checks pull the real file apart at build time.
 */
class CatalogTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val catalog: Catalog by lazy {
        val file = File("src/main/assets/catalog.json")
        assertTrue("catalog.json is missing at ${file.absolutePath}", file.exists())
        json.decodeFromString<Catalog>(file.readText())
    }

    @Test
    fun `catalog parses and is not empty`() {
        assertEquals(2, catalog.schema)
        assertTrue("no distributions", catalog.distros.isNotEmpty())
        catalog.distros.forEach { distro ->
            assertTrue("${distro.id} has no releases", distro.releases.isNotEmpty())
            distro.releases.forEach { release ->
                assertTrue("${distro.id}/${release.id} has no editions", release.editions.isNotEmpty())
            }
        }
    }

    @Test
    fun `image ids are unique`() {
        val ids = catalog.images.map { it.id }
        assertEquals("duplicate image ids: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}",
            ids.size, ids.distinct().size)
    }

    @Test
    fun `every image is reachable through locate`() {
        catalog.images.forEach { image ->
            val ref = catalog.locate(image.id)
            assertNotNull("${image.id} cannot be located", ref)
            assertEquals(image.id, ref!!.image.id)
            assertTrue("${image.id} has an empty title", ref.title.isNotBlank())
            assertTrue("${image.id} has an empty machine name", ref.machineName.isNotBlank())
        }
    }

    @Test
    fun `only archlinuxarm is served over plain http`() {
        // res/xml/network_security_config.xml permits cleartext for exactly one host.
        // Anything else added here would be silently blocked on the device.
        catalog.images.filterNot { it.url.startsWith("https://") }.forEach { image ->
            assertTrue(
                "${image.id} uses cleartext but is not archlinuxarm.org: ${image.url}",
                image.url.contains("archlinuxarm.org"),
            )
        }
    }

    @Test
    fun `sizes and checksums are present`() {
        catalog.images.forEach { image ->
            assertTrue("${image.id} has no download size", image.downloadBytes > 0)
            assertTrue("${image.id} claims to install into nothing", image.installedBytes > 0)
            assertNotNull("${image.id} has no checksum", image.checksum)
        }
    }

    @Test
    fun `the rootfs flow has a live installer to pair with`() {
        val needsInstaller = catalog.images.any { it.bootStyle == BootStyle.ROOTFS_TAR }
        if (!needsInstaller) return

        val alpine = catalog.distros.firstOrNull { it.id == "alpine" }
        assertNotNull("a rootfs image exists but Alpine is not in the catalog", alpine)
        assertTrue(
            "Alpine ships no live ISO for the rootfs flow to boot from",
            alpine!!.images.any { it.bootStyle == BootStyle.LIVE_ISO },
        )
    }

    @Test
    fun `desktop editions name their desktop`() {
        catalog.distros.forEach { distro ->
            distro.releases.forEach { release ->
                val desktops = release.editions.mapNotNull { it.desktop }
                assertEquals(
                    "${distro.id}/${release.id} repeats a desktop name",
                    desktops.size,
                    desktops.distinct().size,
                )
            }
        }
    }
}
