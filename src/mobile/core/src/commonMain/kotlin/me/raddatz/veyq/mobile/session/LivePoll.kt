package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The foreground poll of a list that others change, a port of the web's `shared/state/livePoller.ts` at core's item-list
 * cadence (`ITEMS_POLL`, `core/items/itemsLive.ts`): the next tick [Cadence.intervalMs] ± [Cadence.jitter] after the
 * previous one SETTLED, so a slow request never overlaps its successor; a failed tick doubles the delay up to
 * [Cadence.maxBackoffMs], the first success is back to the interval; a skipped one (the owner is busy) asks again after
 * the plain interval and counts as neither.
 *
 * The platform runs [run] only while the screen is visible and cancels it when it hides, which is the web's visibility
 * gate: nothing is asked in the background. A new [run] — the screen shown again — ticks at once unless a tick started
 * within [Cadence.minGapMs] (a screen just opened has just read, so it waits the interval; one that comes back catches
 * up now). One poll per open list; [run] is never called twice at a time.
 */
class LivePoll(
    private val cadence: Cadence = ITEMS,
    private val now: () -> Long,
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
) {
    data class Cadence(val intervalMs: Long, val maxBackoffMs: Long, val jitter: Double, val minGapMs: Long)

    enum class Tick { DONE, SKIP, FAILED }

    private var failures = 0
    private var lastStart = now()

    /** A delay [ms] spread by the jitter: drawn from `ms * (1 ± jitter)`. */
    internal fun spread(ms: Long): Long = max(0, (ms * (1 + cadence.jitter * (2 * random() - 1))).roundToLong())

    /** The delay after a tick that came to [outcome], before spreading. */
    internal fun after(outcome: Tick): Long = when (outcome) {
        Tick.DONE -> {
            failures = 0
            cadence.intervalMs
        }
        Tick.SKIP -> cadence.intervalMs
        Tick.FAILED -> {
            failures++
            min(cadence.maxBackoffMs, cadence.intervalMs shl min(failures, 30))
        }
    }

    /** Whether a run that starts now ticks at once (no tick started within the gap). */
    internal fun catchUp(): Boolean = now() - lastStart >= cadence.minGapMs

    /**
     * The loop's three steps, for a platform that drives it itself (Swift: a Swift task's cancellation does not reach a
     * Kotlin coroutine, so the Apple app sleeps and ticks in its own task): the wait before a run's first tick, the mark
     * that a tick starts, the wait after one that came to an outcome. [run] is exactly these.
     */
    fun firstDelay(): Long = if (catchUp()) 0L else spread(cadence.intervalMs)

    fun started() {
        lastStart = now()
    }

    fun next(outcome: Tick): Long = spread(after(outcome))

    /** Ticks until cancelled; a [tick] that throws counts as failed, a cancellation ends the loop. */
    suspend fun run(tick: suspend () -> Tick) {
        var wait = firstDelay()
        while (true) {
            if (wait > 0) delay(wait)
            started()
            val outcome = try {
                tick()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Tick.FAILED
            }
            wait = next(outcome)
        }
    }

    companion object {
        /** An item list's cadence: 20 s ± 20 %, doubling to 2 min on errors, a catch-up at most every 2 s. */
        val ITEMS = Cadence(intervalMs = 20_000, maxBackoffMs = 120_000, jitter = 0.2, minGapMs = 2_000)
    }
}
