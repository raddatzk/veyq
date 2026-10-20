package me.raddatz.veyq.core.ratelimit

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.util.Collections
import java.util.WeakHashMap

/**
 * Every [TokenBucketCounter], and the pass that drops their full buckets by time (ADR 0092 § 5). A counter registers
 * itself when it is made; the set holds it **weakly**, so a counter whose owner went away — a bean of a hot reload,
 * a test's — is not kept alive by being swept.
 *
 * A full bucket is the same as an absent one, so a pass changes no limit: a key that comes back starts full either
 * way. What it changes is how long a key — a client address, an email address — stays in heap after its last request:
 * the time its bucket takes to refill, plus at most one interval.
 */
class TokenBucketSweeper {
    private val counters: MutableSet<TokenBucketCounter> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun register(counter: TokenBucketCounter) {
        counters.add(counter)
    }

    /** Drops every bucket full at [nowNanos] (`System.nanoTime()`) in every live counter. */
    fun sweep(nowNanos: Long) {
        val live = synchronized(counters) { counters.toList() }
        live.forEach { it.dropFull(nowNanos) }
    }

    companion object {
        /** The one sweeper of the app's counters; a test makes its own so its pass touches only its counters. */
        val instance = TokenBucketSweeper()
    }
}

/** The pass, once a minute, over every counter the app made. */
@ApplicationScoped
class TokenBucketSweep {
    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun sweep() = TokenBucketSweeper.instance.sweep(System.nanoTime())
}
