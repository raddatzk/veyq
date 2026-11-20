package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.offline.PinKinds

/**
 * The accounts open in this process, each as the connection that opened it ([VeyqAccount.close] takes it off): what a
 * scheduled run uses instead of opening an account a second time (cr-app-background-sync B1, `OfflineStores.openShared`'s
 * rule — one store per account, one writer). Keyed by the [ThisDevice] instance, so two devices a test plays never meet.
 */
internal object OpenAccounts {
    private val held = MutableStateFlow<List<VeyqAccount>>(emptyList())

    fun opened(account: VeyqAccount) = held.update { it + account }

    fun closed(account: VeyqAccount) = held.update { l -> l.filter { it !== account } }

    /** The account [key] of [device] open now (the latest connection where a sign-in still holds a duplicate), or null. */
    fun of(device: ThisDevice, key: String): VeyqAccount? =
        held.value.lastOrNull { it.thisDevice === device && it.key == key && !it.isClosed && it.reach.value !is Reach.Ended }
}

/**
 * A run on the system's schedule while the person has not opened the app (ADR 0153 decision 8; cr-app-background-sync
 * B1): Android's periodic work and iOS's app refresh call [run], and a host does nothing else — it schedules the run
 * ([ThisDevice.backgroundWanted]) and hands it its time ([run]'s deadline). Every rule is here:
 *
 *  - **Which accounts:** each this device remembers at the **login level** whose *Sync in the background* is on
 *    ([ThisDevice.backgroundAccounts]); never one at the presence level or with the password trust — those ask the person,
 *    and nobody is there to ask —, and nothing on a platform that runs no scheduled sync ([ThisDevice.backgroundRuns]).
 *  - **How it opens:** exactly as an app start at that level ([VeyqAccount.resumeOnline]): the kept refresh token renews
 *    the session (no new session family), the login key opens the device's wrap without asking. Never the start's offline
 *    twin: a run without the server has nothing to send or fetch, so it opens nothing. Before any request, the kept wrap is
 *    tried under the login key: a key the system keeps closed now (an iPhone locked, B4) ends the run having sent nothing.
 *    A refused refresh ends the record and wipes the store, as at a start; an account already open in this process (the
 *    app in front, or left a moment ago) is used as it is, never opened twice, and left open.
 *  - **What it does:** the queue sent ([VeyqAccount.sendQueued], ADR 0153 decision 4), then the pins synced
 *    ([me.raddatz.veyq.mobile.offline.OfflinePins.sync], ADR 0140 decision 9) — the person's own writes first, so a long
 *    pin fetch never holds them back to the next run.
 *  - **Its end:** the deadline is asked between writes and bounds the pins' pass; what is left waits for the next run or
 *    the app. The account it opened is closed before [run] returns — the Master Key, the identity and every scope key
 *    zeroed ([VeyqAccount.retire], which keeps the session the next start resumes with).
 */
class BackgroundRun(
    private val primitives: Primitives,
    private val device: ThisDevice,
    private val client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient,
    private val kinds: PinKinds = me.raddatz.veyq.mobile.AppKinds.pins,
    @OptIn(kotlin.time.ExperimentalTime::class)
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    /** What one account's run came to. */
    enum class Outcome {
        /** The queue sent and the pins synced, within the time. */
        DONE,

        /** The time ran out (or a write waits on the server): the rest is the next run's or the app's. */
        UNFINISHED,

        /** The server not reached: nothing was opened. */
        UNREACHABLE,

        /** The login key is closed now (a locked iPhone): nothing was sent. */
        KEY_CLOSED,

        /** This device's trust in the account ended (a refused refresh, a wrap no longer served): the record and store gone. */
        ENDED,

        /** Anything else; the next run tries again. */
        FAILED,
    }

    /** Each account's outcome by its key, in the order run, and how many queued writes were sent in all. */
    data class Report(val accounts: Map<String, Outcome>, val sent: Int)

    /** A test's look at each account the run opened itself, before it is closed. */
    internal var onOpened: (VeyqAccount) -> Unit = {}

    /** The run, until [deadline] (epoch ms): each account in turn; one not begun before the deadline is left for the next run. */
    suspend fun run(deadline: Long): Report = withContext(Dispatchers.Default) {
        // on a dispatcher of its own: the pins' bound is the clock's, never a caller's virtual time
        val outcomes = LinkedHashMap<String, Outcome>()
        var sent = 0
        for (record in device.backgroundAccounts()) {
            if (now() >= deadline) break
            val (outcome, n) = try {
                one(record, deadline)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("veyq: a background run of an account failed: ${e::class.simpleName}")
                Outcome.FAILED to 0
            }
            outcomes[record.key] = outcome
            sent += n
        }
        Report(outcomes, sent)
    }

    private suspend fun one(record: TrustedDevice, deadline: Long): Pair<Outcome, Int> {
        // never a level that asks: the record list is filtered so already, and this holds it where the list is not
        if (!runs(record)) return Outcome.FAILED to 0
        OpenAccounts.of(device, record.key)?.let { return over(it, deadline) }
        if (!keyOpens(record)) return Outcome.KEY_CLOSED to 0
        val account = try {
            VeyqAccount.resumeOnline(primitives, record, device, client, send = false)
        } catch (e: SignInFailure) {
            return when (e.reason) {
                SignInFailure.Reason.UNREACHABLE -> Outcome.UNREACHABLE
                SignInFailure.Reason.SESSION_ENDED -> Outcome.ENDED
                // a wrap the server no longer serves forgot the trust ([VeyqAccount.resumeOnline]); one that does not open
                // under the key now is the key closed
                SignInFailure.Reason.DEVICE_NOT_TRUSTED -> if (device.trusted(record.key) == null) Outcome.ENDED else Outcome.KEY_CLOSED
                SignInFailure.Reason.DEVICE_AUTH_CANCELLED -> Outcome.KEY_CLOSED
                else -> Outcome.FAILED
            } to 0
        }
        return try {
            onOpened(account)
            over(account, deadline)
        } finally {
            // a run the system stops (its time up) still closes: the keys are zeroed whatever cancelled it
            withContext(NonCancellable) { account.retire() }
        }
    }

    /**
     * Whether the login key opens this device's kept wrap now — tried before any request, the opened key zeroed at once. True
     * where nothing is kept to try (the resume's own open decides then).
     */
    private suspend fun keyOpens(record: TrustedDevice): Boolean {
        val kept = device.keptWrap(record.key)?.takeIf { it.wrapId == record.wrapId } ?: return true
        val iv = kept.kdfParams ?: return true
        return try {
            device.keys.openUnlockable(unb64(iv), unb64(kept.wrappedKey), VeyqAccount.DEVICE_KEY_AAD, StaySignedIn.LOGIN).fill(0)
            true
        } catch (e: DeviceAuthFailed) {
            false
        }
    }

    private suspend fun over(account: VeyqAccount, deadline: Long): Pair<Outcome, Int> {
        // an account the app holds offline: its own probe would reconnect it; the run asks now
        if (account.reach.value is Reach.Offline && !account.reconnect()) return Outcome.UNREACHABLE to 0
        if (account.reach.value is Reach.Ended) return Outcome.ENDED to 0
        val report = account.sendQueued { now() < deadline }
        var finished = report == null || report.waiting == 0
        val pins = account.offlinePins
        if (pins != null) {
            val left = deadline - now()
            if (left <= 0) {
                finished = false
            } else {
                pins.use(kinds)
                if (withTimeoutOrNull(left) { pins.sync() } == null) finished = false
            }
        }
        val outcome = when {
            account.reach.value is Reach.Ended -> Outcome.ENDED
            account.reach.value !is Reach.Online -> Outcome.UNREACHABLE
            finished -> Outcome.DONE
            else -> Outcome.UNFINISHED
        }
        return outcome to (report?.sent ?: 0)
    }

    companion object {
        /**
         * Whether a scheduled run may open an account this device trusts by [record]: remembered (DEVICE_KEY) at the login
         * level, whose key opens without asking. A record kept before the levels is the presence level, never run.
         */
        fun runs(record: TrustedDevice): Boolean =
            record.method == TrustedDevice.Method.DEVICE_KEY && record.unlockLevel == StaySignedIn.LOGIN
    }
}
