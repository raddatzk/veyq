package me.raddatz.veyq.core.admin.instance

import java.time.Duration

/**
 * Which ticks of the scheduled release check ask the source after it failed (ADR 0097 A2: a failed run is logged and
 * retried). Each failure in a row doubles how many ticks of the check's own rhythm [every] pass before the next
 * attempt — the next tick, then every 2nd, 4th, 8th … — up to [cap] between two attempts. Counted in ticks, not by the
 * clock, so a tick that fires a moment early is never taken for one that came too soon.
 *
 * Why: an instance with no route to its source failed and logged on every tick — every 5 s where the rhythm is short,
 * as lane L1 and the volume harness set it (collection run 2026-10-23, F9). At the default rhythm, once a day, nothing
 * changes: [cap] is a day, so every tick still asks. A success starts over, and so does a change of the Server Owner's
 * setting (on or off, the source) — a source just pointed somewhere new is read at the next tick.
 *
 * In memory: a restart asks at its first tick, as the scheduler's first run after a start does anyway.
 */
class ReleaseCheckBackoff(private val every: Duration, private val cap: Duration = Duration.ofDays(1)) {
    private var setting: Any? = null
    private var failures = 0
    private var skip = 0L

    /** Whether this tick asks the source, for the Server Owner's [setting] as it stands now. */
    @Synchronized
    fun due(setting: Any): Boolean {
        if (setting != this.setting) {
            this.setting = setting
            failures = 0
            skip = 0
        }
        if (skip > 0) {
            skip--
            return false
        }
        return true
    }

    /** A run for [setting] succeeded: the next tick asks again. */
    @Synchronized
    fun succeeded(setting: Any) {
        this.setting = setting
        failures = 0
        skip = 0
    }

    /** A run for [setting] failed; returns how long until the next attempt. */
    @Synchronized
    fun failed(setting: Any): Duration {
        this.setting = setting
        failures++
        val ticks = ticksAfter(failures)
        skip = ticks - 1
        return every.multipliedBy(ticks)
    }

    /** Ticks from one attempt to the next after [failures] failures in a row: 1, 2, 4, … at most [cap] (and at least one). */
    internal fun ticksAfter(failures: Int): Long {
        val most = maxOf(1L, cap.toMillis() / maxOf(1L, every.toMillis()))
        val doubled = 1L shl minOf(failures - 1, 40).coerceAtLeast(0)
        return minOf(doubled, most)
    }
}
