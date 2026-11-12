package dev.steamshelf.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import dev.steamshelf.MainActivity
import dev.steamshelf.R
import dev.steamshelf.model.SyncStatus
import dev.steamshelf.ui.common.labelRes
import java.util.UUID

/** The ongoing "syncing your library" notification: phase, progress and a stop button. */
class SyncNotifications(private val context: Context) {

    fun foregroundInfo(workId: UUID, status: SyncStatus): ForegroundInfo {
        val notification = build(workId, status)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Shows or updates the progress as an ordinary notification, for when Android doesn't allow a foreground
     * service. Does nothing without the notification permission.
     */
    fun show(workId: UUID, status: SyncStatus) {
        manager.notify(NOTIFICATION_ID, build(workId, status))
    }

    /** Takes the notification down. Android ignores this while a foreground service still owns it. */
    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    private fun build(workId: UUID, status: SyncStatus): Notification {
        ensureChannel()
        val title = context.getString(status.phase?.labelRes() ?: R.string.sync_starting)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setContentTitle(title)
            .apply {
                if (status.total > 0) {
                    setContentText(context.getString(R.string.sync_progress_count, status.done, status.total))
                    setProgress(status.total, status.done, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(
                R.drawable.ic_notification_stop,
                context.getString(R.string.sync_stop),
                WorkManager.getInstance(context).createCancelPendingIntent(workId),
            )
            .build()
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.sync_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.sync_channel_description) },
        )
    }

    private companion object {
        const val CHANNEL_ID = "library-sync"
        const val NOTIFICATION_ID = 1
    }
}
