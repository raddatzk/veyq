package me.raddatz.veyq.mobile.android

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.offline.OfflineFetching
import me.raddatz.veyq.mobile.words.Msg

/**
 * A pin's fetch outside the app (ADR 0140 decision 9): the core fetches in this process while the account is open
 * ([OfflineFetching]); leaving the app must not stop it. So while a fetch runs, [PinWorker] runs — WorkManager's worker in
 * the app's own process, promoted to a `dataSync` foreground service with its notification — and holds the process until
 * every fetch ended. It fetches nothing itself and opens no account: a process with no open account has nothing to fetch
 * (a scheduled wake-up without the person is #896).
 */
object OfflineWork {
    private const val WORK = "veyq-offline-pins"
    internal const val CHANNEL = "veyq-offline"
    internal const val NOTIFICATION = 7_140

    /** Starts [PinWorker] each time a fetch begins, for as long as [scope] lives (the app's model). */
    fun watch(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch {
            OfflineFetching.fetches.map { it.isNotEmpty() }.distinctUntilChanged().collect { busy -> if (busy) start(app) }
        }
    }

    private fun start(context: Context) {
        // KEEP: a worker that runs already holds every fetch, the new one included
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<PinWorker>().build())
    }

    /** The notification while a fetch runs: its title and how far it is, at the platform's low importance (no sound). */
    internal fun foreground(context: Context, progress: OfflineFetching.Progress?): ForegroundInfo {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName(context.words(Msg.OFFLINE_FETCH_CHANNEL)).build(),
        )
        val percent = progress?.takeIf { it.total > 0 }?.let { (it.done * 100 / it.total).toInt().coerceIn(0, 100) }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.words(Msg.OFFLINE_FETCH_TITLE))
            .apply { if (percent != null) setContentText(context.words(Msg.OFFLINE_FETCH_PERCENT, percent)) }
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        return ForegroundInfo(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

/**
 * Holds the process while a pin's fetch runs ([OfflineWork]): a foreground service with the fetch's progress, ended once no
 * fetch is left. Where the system refuses the foreground service (the app was not in front when it started), it runs as
 * an ordinary worker for the time the system grants; a fetch cut there continues at the next open (it appends).
 */
class PinWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (OfflineFetching.total() == null) return Result.success()
        promote(OfflineFetching.total())
        // a StateFlow hands the latest value only: what changed during the wait is one update
        OfflineFetching.fetches.takeWhile { it.isNotEmpty() }.collect {
            promote(OfflineFetching.total(it))
            delay(UPDATE_MS)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = OfflineWork.foreground(applicationContext, OfflineFetching.total())

    private suspend fun promote(progress: OfflineFetching.Progress?) {
        try {
            setForeground(OfflineWork.foreground(applicationContext, progress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException: started from the background; it runs as a plain worker
            android.util.Log.i("veyq-offline", "the pin's fetch runs without a foreground service: ${e::class.simpleName}")
        }
    }

    private companion object {
        /** The notification's progress at most once a second. */
        const val UPDATE_MS = 1_000L
    }
}
