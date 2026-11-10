package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A test's side of the bridge: calls and watches as the host makes them, as JSON both ways. */
internal suspend fun Bridge<*, *>.ask(call: Call, timeout: Duration = 10.seconds): Answer = ask(wire.encodeToString(Call.serializer(), call), timeout)

internal suspend fun Bridge<*, *>.ask(json: String, timeout: Duration = 10.seconds): Answer {
    val answered = CompletableDeferred<String>()
    var count = 0
    call(json) { count++; answered.complete(it) }
    val a = withTimeout(timeout) { answered.await() }
    check(count == 1) { "answered $count times" }
    return wire.decodeFromString(Answer.serializer(), a)
}

/**
 * A watch's updates as they arrive, as the host gets them: JSON. [next] and [until] read each back as an [Update]; a
 * Pot's view is sent and never read back (`PotView.AsShown`), so a Pot's updates are read as JSON ([untilJson]).
 */
internal class Watching(val id: Long, private val updates: Channel<String>) {
    suspend fun next(timeout: Duration = 10.seconds): Update = withTimeout(timeout) { decode(updates.receive()) }

    /** The first update [pick] takes, the ones before it passed over. */
    suspend fun <T : Any> until(timeout: Duration = 30.seconds, pick: (Update) -> T?): T = untilJson(timeout) { pick(decode(it.toString())) }

    suspend fun <T : Any> untilJson(timeout: Duration = 30.seconds, pick: (JsonObject) -> T?): T = withTimeout(timeout) {
        var found: T? = null
        while (found == null) found = pick(wire.parseToJsonElement(updates.receive()).jsonObject)
        found
    }

    fun pending(): String? = updates.tryReceive().getOrNull()

    private fun decode(json: String) = wire.decodeFromString(Update.serializer(), json)
}

internal fun Bridge<*, *>.watching(w: Watched): Watching {
    val ch = Channel<String>(Channel.UNLIMITED)
    val id = watch(wire.encodeToString(Watched.serializer(), w)) { ch.trySend(it) }
    return Watching(id, ch)
}
