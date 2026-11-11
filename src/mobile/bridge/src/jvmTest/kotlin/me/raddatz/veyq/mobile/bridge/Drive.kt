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

internal suspend fun Bridge<*, *>.ask(json: String, timeout: Duration = 10.seconds): Answer =
    askWithPayload(json, timeout).also { (a, payload) -> check(payload == null) { "$a came with a payload" } }.first

/** A call whose answer may carry a payload beside its JSON: the answer and the payload, as the host gets both. */
internal suspend fun Bridge<*, *>.askWithPayload(call: Call, timeout: Duration = 10.seconds): Pair<Answer, ByteArray?> =
    askWithPayload(wire.encodeToString(Call.serializer(), call), timeout)

internal suspend fun Bridge<*, *>.askWithPayload(json: String, timeout: Duration = 10.seconds): Pair<Answer, ByteArray?> {
    val answered = CompletableDeferred<Pair<String, ByteArray?>>()
    var count = 0
    call(json) { a, payload -> count++; answered.complete(a to payload) }
    val (a, payload) = withTimeout(timeout) { answered.await() }
    check(count == 1) { "answered $count times" }
    val answer = wire.decodeFromString(Answer.serializer(), a)
    check((answer is Answer.Bytes) == (payload != null)) { "$answer with a payload of ${payload?.size}" }
    return answer to payload
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

/** The way in as it is now: a fresh watch delivers the present state first. */
internal suspend fun Bridge<*, *>.gateNow(): me.raddatz.veyq.mobile.session.SessionModel.Gate =
    watching(Watched.Session).let { w -> (w.next() as Update.Session).gate.also { unwatch(w.id) } }

/** core's `FileSink` as the JVM plays it: the file stays only when the producer answered true. */
internal object JvmFiles : DeviceFiles {
    override suspend fun read(path: String, most: Int): ByteArray =
        java.io.File(path).readBytes().also { if (it.size > most) throw IllegalStateException("too large") }

    override suspend fun write(path: String, produce: suspend (sink: (ByteArray) -> Unit) -> Boolean): Boolean {
        val file = java.io.File(path)
        var keep = false
        try {
            file.outputStream().buffered().use { out -> keep = produce { out.write(it) } }
            return keep
        } finally {
            if (!keep) file.delete()
        }
    }
}
