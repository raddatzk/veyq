package me.raddatz.veyq.mobile.expenses

/**
 * A map and a set keyed by strings, in insertion order, for the ledger's hot paths. On the JVM and natively they are
 * `LinkedHashMap`/`LinkedHashSet`. On JavaScript they wrap the engine's own `Map`/`Set` (`StringMaps.js.kt`): Kotlin/JS's
 * hash map hashes a string anew on every lookup, and with it a 10,000-entry Pot's ledger spent most of its time in
 * `put` and `get` (cr-web-expenses-on-the-core's optimisation round, profiled). Iteration order is insertion order on
 * every platform, which the ledger's lists are built by.
 */
internal expect fun <V> stringMap(): MutableMap<String, V>

internal expect fun stringSet(): MutableSet<String>

/** [this] keyed by [key], the last of equal keys kept, in order. */
internal inline fun <T> Iterable<T>.associateByString(key: (T) -> String): MutableMap<String, T> {
    val m = stringMap<T>()
    for (t in this) m[key(t)] = t
    return m
}

/** Whether some string appears twice in [this]. */
internal fun List<String>.hasTwice(): Boolean {
    val seen = stringSet()
    for (s in this) if (!seen.add(s)) return true
    return false
}

/** [this] without repeats, the first of each kept, in order. */
internal fun List<String>.distinctStrings(): List<String> {
    val seen = stringSet()
    return filter { seen.add(it) }
}

/** The one id of a revision in a Pot's list, `entryId:revision` (a revision is far inside an `Int`, whose text is cheap on
 *  every platform; a `Long`'s is not on JavaScript). */
internal fun revisionIdOf(entryId: String, revision: Long): String =
    entryId + ":" + (if (revision >= Int.MIN_VALUE && revision <= Int.MAX_VALUE) revision.toInt().toString() else revision.toString())
