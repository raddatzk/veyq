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
import me.raddatz.veyq.mobile.blob.UploadsUnderway
import me.raddatz.veyq.mobile.words.Msg

/**
 * An upload outside the app (ADR 0145 decision 1): the core's queue sends in this process ([UploadsUnderway]); leaving the
 * app must not stop it. So while a file waits or runs, [UploadWorker] runs — WorkManager's worker in the app's own
 * process, promoted to a `dataSync` foreground service with its notification — and holds the process until nothing is
 * under way. It uploads nothing itself and opens no account. A pin's fetch has its own ([OfflineWork]).
 */
object UploadWork {
    private const val WORK = "veyq-uploads"
    internal const val CHANNEL = "veyq-uploads"
    internal const val NOTIFICATION = 7_145

    /** Starts [UploadWorker] each time an upload begins, for as long as [scope] lives (the app's model). */
    fun watch(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch {
            UploadsUnderway.uploads.map { it.isNotEmpty() }.distinctUntilChanged().collect { busy -> if (busy) start(app) }
        }
    }

    private fun start(context: Context) {
        // KEEP: a worker that runs already holds every upload, the new one included
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<UploadWorker>().build())
    }

    /** The notification while files upload: how many and how far, at the platform's low importance (no sound). */
    internal fun foreground(context: Context, progress: UploadsUnderway.Progress?): ForegroundInfo {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName(context.words(Msg.UPLOAD_BACKGROUND_CHANNEL)).build(),
        )
        val percent = progress?.takeIf { it.total > 0 }?.let { (it.done * 100 / it.total).toInt().coerceIn(0, 100) }
        val files = progress?.files ?: 0
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.words(Msg.UPLOAD_BACKGROUND_TITLE, files))
            .apply { if (percent != null) setContentText(context.words(Msg.UPLOAD_BACKGROUND_PERCENT, percent)) }
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
 * Holds the process while files upload ([UploadWork]): a foreground service with the uploads' progress, ended once nothing
 * is under way. Where the system refuses the foreground service (the app was not in front when it started), it runs as an
 * ordinary worker for the time the system grants; an upload cut off there fails as any cut-off upload does.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (UploadsUnderway.total() == null) return Result.success()
        promote(UploadsUnderway.total())
        // a StateFlow hands the latest value only: what changed during the wait is one update
        UploadsUnderway.uploads.takeWhile { it.isNotEmpty() }.collect {
            promote(UploadsUnderway.total(it))
            delay(UPDATE_MS)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = UploadWork.foreground(applicationContext, UploadsUnderway.total())

    private suspend fun promote(progress: UploadsUnderway.Progress?) {
        try {
            setForeground(UploadWork.foreground(applicationContext, progress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException: started from the background; it runs as a plain worker
            android.util.Log.i("veyq-upload", "the uploads run without a foreground service: ${e::class.simpleName}")
        }
    }

    private companion object {
        /** The notification's progress at most once a second. */
        const val UPDATE_MS = 1_000L
    }
}
