package me.raddatz.veyq.mobile.session.rotation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.api.models.RotationScopeState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * When an account's rotation is finished on this device (the web's `tasks/scopeRotationWorker.ts` and `finishRotation`'s
 * single flight; ADR 0111 decision 8, ADR 0148 decision 7): a pass at once when the account opens ([start]) and every
 * [period] after while it is open and [online], one more when it is back online ([nudge]), all in the account's own
 * [scope] — so a pass dies with the account's close. One pass at a time: a second asker waits for the running pass and
 * gets its answer ([finish]), never racing it into a `stale_epoch`. A failed pass is silent; the next one retries.
 */
internal class RotationFinisher(
    private val scope: CoroutineScope,
    private val online: () -> Boolean,
    private val period: Duration = EVERY,
    private val pass: suspend () -> List<RotationScopeState>,
) {
    private val lock = Mutex()
    private var running: Deferred<List<RotationScopeState>>? = null
    private var loop: Job? = null

    /** The running pass's answer, or a new pass's. Throws what the pass threw; cancelled with the account. */
    suspend fun finish(): List<RotationScopeState> {
        val pending = lock.withLock {
            running?.takeIf { it.isActive } ?: scope.async { pass() }.also { running = it }
        }
        return pending.await()
    }

    /** The loop: a pass now, then one every [period], each only while [online]. Idempotent. */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                if (online()) quietly()
                delay(period)
            }
        }
    }

    /** One pass now, beside the loop's (back online after an offline unlock). */
    fun nudge() {
        scope.launch { if (online()) quietly() }
    }

    /** Whether the loop runs: false once the account closed. */
    val looping: Boolean get() = loop?.isActive == true

    private suspend fun quietly() {
        try {
            finish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // silent: the scope stays waiting, the next pass retries
        }
    }

    companion object {
        /** The web's worker period. */
        val EVERY: Duration = 45.seconds
    }
}
