package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives

/**
 * The iOS app's door into core's [BackgroundRun] (ADR 0153 decision 8): the app-refresh task's
 * handler [start]s the run and its expiration handler [Run.cancel]s it. A Swift caller of a suspend function cannot cancel
 * the coroutine behind it, so the run is a job here, and [start]'s `done` is called exactly once however it ended — the
 * run's report, or null where it was cancelled or failed — after the account the run opened was closed and its keys zeroed
 * ([BackgroundRun]'s close runs under `NonCancellable`). Nothing is decided here.
 */
object AppleBackground {
    /** A run under way; [cancel] is the system taking its time back. */
    class Run internal constructor(private val job: Job) {
        fun cancel() = job.cancel()
    }

    /** One run over [device] (the app's own [ThisDevice], so an account the app holds open is used) until [deadline] (epoch ms). */
    fun start(device: ThisDevice, deadline: Long, done: (summary: String?) -> Unit): Run =
        start(BackgroundRun(LibsodiumPrimitives, device, { block -> appleHttpClient(block) }), deadline, done)

    @PlatformApi
    @HiddenFromObjC
    fun start(run: BackgroundRun, deadline: Long, done: (summary: String?) -> Unit): Run {
        var report: BackgroundRun.Report? = null
        val job = CoroutineScope(Dispatchers.Default).launch {
            report = try {
                run.run(deadline)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // never out of the job: an uncaught exception ends the process on Kotlin/Native
                println("veyq: the background run failed: ${e::class.simpleName}")
                null
            }
        }
        // called once the job is complete, cancelled before it began included
        job.invokeOnCompletion { done(report?.let(::summary)) }
        return Run(job)
    }

    /** The outcome per account and the count sent, as the log says it: never a key, a name or an address. */
    internal fun summary(report: BackgroundRun.Report): String =
        "${report.accounts.values.joinToString { it.name }.ifEmpty { "no account" }}, sent ${report.sent}"
}
