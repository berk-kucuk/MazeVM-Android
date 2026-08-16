package com.mazevm.android.vm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mazevm.android.MainActivity
import com.mazevm.android.MazeApp
import com.mazevm.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps QEMU alive while the app is in the background.
 *
 * Without a foreground service Android freezes the process, which stops the emulated
 * CPU dead. A partial wake lock is held on top so a long install is not interrupted by
 * the device suspending; the screen is not kept on by this service.
 */
class VmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification(runningNames()))
        observeMachines()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> scope.launch {
                MazeApp.from(this@VmService).vmManager.stopAll()
                stopSelf()
            }
        }
        // Restarting with no machines running would be pointless, so do not ask for it.
        return START_NOT_STICKY
    }

    private fun observeMachines() {
        val manager = MazeApp.from(this).vmManager
        watcher?.cancel()
        watcher = scope.launch {
            manager.states.collectLatest {
                val names = runningNames()
                if (names.isEmpty()) {
                    stopSelf()
                } else {
                    notify(buildNotification(names))
                }
            }
        }
    }

    private fun runningNames(): List<String> {
        val app = MazeApp.from(this)
        return app.vmManager.states.value
            .filterValues { it.isActive }
            .keys
            .mapNotNull { id -> app.vmStore.find(id)?.name }
    }

    private fun buildNotification(names: List<String>): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAll = PendingIntent.getService(
            this,
            1,
            Intent(this, VmService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = when {
            names.isEmpty() -> getString(R.string.notif_vm_channel)
            names.size == 1 -> getString(R.string.notif_vm_running, names.first())
            else -> names.joinToString(", ")
        }

        return NotificationCompat.Builder(this, MazeApp.CHANNEL_VM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_vm_action_stop), stopAll)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun notify(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Holds the CPU awake for as long as a machine is running.
     *
     * Deliberately without a timeout. An emulated install runs for hours, and a lock
     * that expires part-way through would suspend the guest mid-write with no warning
     * and nothing in the log to explain it. There is no leak to guard against instead:
     * this service only exists while a machine is active, and the lock is released in
     * onDestroy.
     */
    private fun acquireWakeLock() {
        val power = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_ALL = "com.mazevm.android.STOP_ALL"

        /** Shows up in `dumpsys power`, which is how a stuck lock gets diagnosed. */
        private const val WAKE_LOCK_TAG = "MazeVM:machine"

        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VmService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VmService::class.java))
        }
    }
}
