package com.mazevm.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.mazevm.android.core.Prefs
import com.mazevm.android.core.Storage
import com.mazevm.android.core.applyAppLanguage
import com.mazevm.android.data.CatalogRepository
import com.mazevm.android.container.ContainerManager
import com.mazevm.android.data.ContainerStore
import com.mazevm.android.data.ImageLibrary
import com.mazevm.android.data.VmStore
import com.mazevm.android.download.ImageDownloader
import com.mazevm.android.oci.ImagePuller
import com.mazevm.android.qemu.QemuImg
import com.mazevm.android.qemu.QemuRuntime
import com.mazevm.android.vm.VmManager
import com.mazevm.android.vm.VmService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Holds the object graph.
 *
 * The graph is small and entirely singleton-scoped, so a hand-written container is
 * clearer here than a DI framework and keeps startup free of reflection.
 */
class MazeApp : Application() {

    val appScope = CoroutineScope(SupervisorJob())

    lateinit var storage: Storage private set
    lateinit var prefs: Prefs private set
    lateinit var qemuRuntime: QemuRuntime private set
    lateinit var qemuImg: QemuImg private set
    lateinit var vmStore: VmStore private set
    lateinit var containerStore: ContainerStore private set
    lateinit var imagePuller: ImagePuller private set
    lateinit var catalog: CatalogRepository private set
    lateinit var imageLibrary: ImageLibrary private set
    lateinit var downloader: ImageDownloader private set
    lateinit var vmManager: VmManager private set
    lateinit var containerManager: ContainerManager private set

    override fun onCreate() {
        super.onCreate()

        storage = Storage(this)
        prefs = Prefs(this)
        qemuRuntime = QemuRuntime(this, storage)
        qemuImg = QemuImg(qemuRuntime)
        vmStore = VmStore(storage)
        containerStore = ContainerStore(storage)
        imagePuller = ImagePuller(storage, appScope)
        catalog = CatalogRepository(this, storage)
        imageLibrary = ImageLibrary(storage)
        downloader = ImageDownloader(
            context = this,
            storage = storage,
            scope = appScope,
            wifiOnly = { prefs.state.value.wifiOnlyDownloads },
        )
        vmManager = VmManager(this, storage, qemuRuntime, vmStore, appScope)
        containerManager = ContainerManager(this, containerStore, appScope)

        applyAppLanguage(prefs.state.value.language)
        createNotificationChannels()

        // Either backend keeps the service up; it stops only when nothing is left.
        val onActive = {
            val total = vmManager.runningCount + containerManager.runningCount
            if (total > 0) VmService.ensureRunning(this) else VmService.stop(this)
        }
        vmManager.onActiveCountChanged = { onActive() }
        containerManager.onActiveCountChanged = { onActive() }

        appScope.launch {
            vmStore.load()
            containerStore.load()
            catalog.load()
            imageLibrary.refresh(catalog.images)
            qemuRuntime.prepareDataDir()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_VM,
                getString(R.string.notif_vm_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                getString(R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    companion object {
        const val CHANNEL_VM = "vm_running"
        const val CHANNEL_DOWNLOADS = "downloads"

        fun from(context: Context): MazeApp = context.applicationContext as MazeApp
    }
}
