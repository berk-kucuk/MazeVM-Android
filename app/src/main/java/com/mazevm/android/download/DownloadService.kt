package com.mazevm.android.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mazevm.android.MainActivity
import com.mazevm.android.MazeApp
import com.mazevm.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps multi-gigabyte image downloads running when the app is not in the foreground.
 * The transfers themselves live in [ImageDownloader] on the application scope; this
 * service only supplies the foreground status that stops Android from freezing them.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notify(buildNotification(null, 0f))
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    private fun observe() {
        val app = MazeApp.from(this)
        scope.launch {
            app.downloader.states.collectLatest { states ->
                val active = states.entries.firstOrNull { it.value.isBusy }
                if (active == null) {
                    stopSelf()
                    return@collectLatest
                }
                val name = app.catalog.locate(active.key)?.title
                val fraction = (active.value as? DownloadState.Running)?.fraction ?: 0f
                notify(buildNotification(name, fraction))
            }
        }
    }

    private fun buildNotification(imageName: String?, fraction: Float): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = imageName
            ?.let { getString(R.string.notif_download_running, it) }
            ?: getString(R.string.download_notification_channel)

        return NotificationCompat.Builder(this, MazeApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun notify(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1002

        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java),
            )
        }
    }
}
