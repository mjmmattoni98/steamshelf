package dev.steamshelf.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.steamshelf.SteamShelfApp
import dev.steamshelf.data.SyncLauncher
import dev.steamshelf.model.SyncStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the library sync as long-running WorkManager work, so a 10–15 minute HowLongToBeat phase survives
 * the app going to the background and, if Android kills the process, is resumed later. The repository
 * still owns the sync and its status; this only hosts it and mirrors the status in a notification.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notifications = SyncNotifications(context)

    override suspend fun doWork(): Result {
        val repository = (applicationContext as SteamShelfApp).container.repository
        coroutineScope {
            val progress = launch {
                var shown: SyncStatus? = null
                while (true) {
                    val status = repository.sync.value
                    if (status != shown) {
                        promote(status)
                        shown = status
                    }
                    delay(NOTIFICATION_INTERVAL_MS)
                }
            }
            try {
                // Zero means an ordinary sync: only what is missing or stale is fetched.
                repository.runSync(refreshBefore = inputData.getLong(KEY_REFRESH_BEFORE, 0L).takeIf { it > 0 })
            } finally {
                // Joined, so that a late update can't put the notification back after it is taken down.
                withContext(NonCancellable) { progress.cancelAndJoin() }
                // WorkManager removes the notification itself when the sync ran as a foreground service. When
                // Android refused that (see [promote]), WorkManager can't reach its service to take it down.
                notifications.cancel()
            }
        }
        return Result.success()
    }

    // Used for expedited work before Android 12, where WorkManager starts the foreground service itself.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(id, SyncStatus(running = true))

    private suspend fun promote(status: SyncStatus) {
        try {
            setForeground(notifications.foregroundInfo(id, status))
        } catch (_: IllegalStateException) {
            // Android 12+ refuses to start a foreground service while the app is in the background (for
            // example when WorkManager resumes the sync after the process was killed). The sync then runs as
            // a regular job, and WorkManager can no longer update the notification either, so it is posted
            // directly. The next update tries the foreground service again.
            notifications.show(id, status)
        }
    }

    companion object {
        const val WORK_NAME = "library-sync"
        internal const val KEY_REFRESH_BEFORE = "refresh-before"
        // Android drops notification updates that come faster than a few per second.
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
    }
}

class WorkManagerSyncLauncher(private val context: Context) : SyncLauncher {

    override fun start(force: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            // The time of the request, not a flag: if the worker is run again it must not start the refresh over.
            .setInputData(workDataOf(SyncWorker.KEY_REFRESH_BEFORE to if (force) System.currentTimeMillis() else 0L))
            .build()
        // Appending (rather than keeping) means a sync requested while the previous one is finishing still runs.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SyncWorker.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun stop() {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.WORK_NAME)
    }
}
