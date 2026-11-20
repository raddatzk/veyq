package me.raddatz.veyq.mobile.android

import android.app.Activity
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.BackgroundRun
import me.raddatz.veyq.mobile.session.ThisDevice
import java.util.concurrent.TimeUnit

/**
 * This installation's one [ThisDevice] in the process: the app's model and the background sync's worker read the same
 * records and switches, so an account the app holds open is the one a run finds (core's `BackgroundRun`: never opened
 * twice). [host] is the activity a prompt for the presence key is shown over, set while one is resumed — a worker has
 * none, and a run never asks (the core runs only the login level).
 */
object AndroidDevice {
    @Volatile
    var host: java.lang.ref.WeakReference<Activity>? = null

    private var keys: KeystoreDeviceKeys? = null
    private var device: ThisDevice? = null

    /** The device's keys in the Keystore: its wraps, its unlock keys, its sealed secrets. */
    @Synchronized
    fun keys(context: Context): KeystoreDeviceKeys =
        keys ?: KeystoreDeviceKeys(context.applicationContext) { host?.get() }.also { keys = it }

    @Synchronized
    fun of(context: Context): ThisDevice = device ?: ThisDevice(
        PreferencesStore(context.applicationContext),
        keys(context),
        android.os.Build.MODEL,
        BouncyCastlePrimitives,
        // ADR 0140 decision 3: app-private, credential-encrypted, left out of Auto Backup by the directory itself
        offlineRoot = java.io.File(context.applicationContext.noBackupFilesDir, "offline").path,
        offlineLimit = me.raddatz.veyq.mobile.offline.AccountOffline.PHONE_LIMIT,
        // ADR 0153 decision 8: a phone syncs on the system's schedule
        backgroundRuns = true,
    ).also { device = it }
}

/**
 * *Sync in the background* (ADR 0153 decision 8, cr-app-background-sync B3): one unique periodic WorkManager work at the
 * system's minimum of 15 minutes, on a network and not on low battery, scheduled while the core says a run is wanted
 * ([ThisDevice.backgroundWanted]: an account at the login level with its switch on) and cancelled when not. Its worker runs
 * core's [BackgroundRun] within the worker's own time — no foreground promotion, which Android refuses a worker started
 * in the background. WorkManager keeps the work's own records in its database (`src/mobile/CLAUDE.md` § Nothing on disk):
 * the work's name and constraints, never an account.
 */
object BackgroundWork {
    const val WORK = "veyq-background-sync"
    private const val TAG = "veyq-sync"

    /**
     * The work's period: WorkManager's floor (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`, 15 minutes); the system
     * may run it later.
     */
    private const val PERIOD_MINUTES = 15L

    /**
     * The time a run hands the core: a worker is stopped after 10 minutes (WorkManager's own limit); the run ends a minute
     * before, so its close — the keys zeroed — is never the part cut off.
     */
    private const val RUN_MS = 9L * 60 * 1000

    /** Schedules or cancels the work as the core says, for as long as [scope] lives (the app's model). */
    fun watch(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch { AndroidDevice.of(app).backgroundWanted.collect { schedule(app, it) } }
    }

    /** The work kept to what the core says is [wanted]: scheduled (its last request replacing one there) or cancelled. */
    fun schedule(context: Context, wanted: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!wanted) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .addTag(TAG)
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    internal suspend fun run(context: Context) {
        val device = AndroidDevice.of(context)
        val started = System.currentTimeMillis()
        val report = BackgroundRun(BouncyCastlePrimitives, device, { block -> Network.client(block) }).run(started + RUN_MS)
        // the outcome per account and the count, never a key, a name or an address
        android.util.Log.i(TAG, "background run: ${report.accounts.values.joinToString { it.name }.ifEmpty { "no account" }}, sent ${report.sent}, ${System.currentTimeMillis() - started} ms")
        // an account the run found ended (a refused refresh) may have been the last one wanting it
        if (!device.backgroundWanted.value) schedule(context, false)
    }
}

/** [BackgroundWork]'s worker: core's run, within the worker's own time. A run that failed is the next period's to try. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        BackgroundWork.run(applicationContext)
        return Result.success()
    }
}
