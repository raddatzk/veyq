package me.raddatz.veyq.mobile.audio

import kotlin.math.floor

// The core's music player (ADR 0142 decision 1; ADR 0135 decision 6): the order a queue plays in — every client's rule,
// the web's through `@veyq/core-js/audio` (ADR 0155 decision 7.2), held by `contracts/platform/audio/v1-vectors.json`.
// Always shuffled and endless, no toggle: each round is a fresh permutation (Fisher–Yates over the suite's
// `randomBytes`), and with two or more tracks a round never starts with the track that ended the one before. Next goes
// forward; previous restarts the current track when it is more than 3 s in, and otherwise goes back through the tracks
// played. A start track plays first, the rest of its round shuffled. Pure: the randomness is handed in, and the vectors
// script every draw and the `n` it is asked with, so the order of the calls is part of the rule.

/** A uniformly random integer in `[0, n)`. */
typealias RandomInt = (n: Int) -> Int

/** How far into a track "previous" restarts it instead of going back. */
const val PREVIOUS_RESTARTS_AFTER_MS = 3000L

private const val WORDS = 4294967296.0 // 2^32
private const val RESTARTS_AFTER = 3000.0 // PREVIOUS_RESTARTS_AFTER_MS

/**
 * [randomBytes] (the suite's, `Primitives.randomBytes`) as a [RandomInt], unbiased by rejection: a 32-bit word — its four
 * bytes little-endian, as the web's `crypto.getRandomValues` fills a `Uint32Array` — at or past the largest multiple of
 * `n` below 2^32 is drawn again. On `Double`s, exact below 2^53, so no `Long` runs where the web draws.
 */
fun randomIntOf(randomBytes: (Int) -> ByteArray): RandomInt = { n ->
    if (n <= 1) {
        0
    } else {
        val limit = floor(WORDS / n) * n
        var picked = -1
        while (picked < 0) {
            val b = randomBytes(4)
            val word = (b[0].toInt() and 0xff) + (b[1].toInt() and 0xff) * 256.0 + (b[2].toInt() and 0xff) * 65536.0 +
                (b[3].toInt() and 0xff) * 16777216.0
            if (word < limit) picked = (word % n).toInt()
        }
        picked
    }
}

/** A fresh permutation of [ids] (Fisher–Yates). */
fun <T> shuffled(ids: List<T>, random: RandomInt): List<T> {
    val out = ids.toMutableList()
    for (i in out.size - 1 downTo 1) {
        val j = random(i + 1)
        val t = out[i]
        out[i] = out[j]
        out[j] = t
    }
    return out
}

/** One round: a permutation of [ids] that does not start with [last] (where there are two or more), or [first] first. */
fun nextRound(ids: List<String>, last: String?, random: RandomInt, first: String? = null): List<String> {
    if (first != null && first in ids) return listOf(first) + shuffled(ids.filter { it != first }, random)
    val round = shuffled(ids, random).toMutableList()
    if (round.size >= 2 && round[0] == last) {
        val j = 1 + random(round.size - 1)
        val t = round[0]
        round[0] = round[j]
        round[j] = t
    }
    return round
}

/** What "previous" does: restart the track playing, or go back to the one played before it. */
sealed interface PreviousStep {
    data object Restart : PreviousStep
    data class Back(val id: String) : PreviousStep
}

/** The order of one queue: the tracks played (to go back through), the rest of the round, and the next rounds. */
class PlayOrder(ids: List<String>, private val random: RandomInt, startId: String? = null) {
    private var ids: List<String> = ids.distinct()
    private val played = mutableListOf<String>()
    private var at = -1
    // The rest of the round from [head] on: read forward, never shifted — a list's `removeAt(0)` moves the whole round on
    // every step (measured on JavaScript: a 10,000-track queue 4.2× the web's `shift()`, whose engine trims in place).
    private var pending: List<String> = nextRound(this.ids, null, random, startId)
    private var head = 0

    init {
        next()
    }

    /** The track playing, or null when the queue holds none. */
    fun current(): String? = if (at >= 0) played[at] else null

    /** Go forward: through tracks gone back over first, then the round, then a fresh round. Null when no track is left. */
    fun next(): String? {
        if (at < played.size - 1) return played[++at]
        if (ids.isEmpty()) return null
        val id = take()
        played += id
        at = played.size - 1
        return id
    }

    /** The track [next] will go to, without going there: it is fixed now, so the player can open it ahead. */
    fun peek(): String? {
        if (at < played.size - 1) return played[at + 1]
        if (ids.isEmpty()) return null
        val id = take()
        played += id
        return id
    }

    /** The round's next track, a fresh round first where this one is through. */
    private fun take(): String {
        if (head >= pending.size) {
            pending = nextRound(ids, current(), random)
            head = 0
        }
        return pending[head++]
    }

    /** Go back — or restart, where the track is more than 3 s in or nothing was played before it. [positionMs] as the
     *  player reads it, fractional on the web: 3000.5 is more than 3 s. */
    fun previous(positionMs: Double): PreviousStep {
        if (positionMs > RESTARTS_AFTER || at <= 0) return PreviousStep.Restart
        return PreviousStep.Back(played[--at])
    }

    /** Drop a track that does not play here: it leaves every round, and the history. */
    fun drop(id: String) {
        ids = ids.filter { it != id }
        val rest = ArrayList<String>(pending.size - head)
        for (i in head until pending.size) if (pending[i] != id) rest += pending[i]
        pending = rest
        head = 0
        for (i in played.size - 1 downTo 0) {
            if (played[i] != id) continue
            played.removeAt(i)
            if (i <= at) at--
        }
    }

    /** How many tracks the queue still holds. */
    val size: Int get() = ids.size
}
