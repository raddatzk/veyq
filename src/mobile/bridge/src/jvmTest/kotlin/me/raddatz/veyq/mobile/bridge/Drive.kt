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

/** A call with [inbound] beside its JSON (a scan's frame), as `veyq_call` hands it on. */
internal suspend fun Bridge<*, *>.askWith(call: Call, inbound: ByteArray?, timeout: Duration = 10.seconds): Answer =
    askWithPayload(wire.encodeToString(Call.serializer(), call), timeout, inbound).also { (a, payload) -> check(payload == null) { "$a came with a payload" } }.first

/**
 * Whether no call was answered yet in this test JVM. The first one pays the JVM's cold start of the bridge — the wire's
 * serializers, the composition's and the core's classes loaded, linked and run interpreted — which is no answer's cost:
 * measured 2026-11-23 (`./gradlew :bridge:jvmTest`, load 7–10, a probe around `AccountFlowsDispatchTest.signedIn`) 295 ms
 * for the JVM's first sign-in, 0 ms for each of the next four. Under a machine-wide load (inbox #1101: 12–20, six other
 * modules' tests beside it) that cold start alone outran the 10 s budget, in whichever test ran first. So the first call
 * waits up to [COLD_START]; every later one keeps its own budget, which stays a hang's detector for the core's answer.
 */
private val cold = java.util.concurrent.atomic.AtomicBoolean(true)

/** The first call's budget ([cold]): a hang's limit, not a measure. */
private val COLD_START: Duration = 60.seconds

internal suspend fun Bridge<*, *>.askWithPayload(json: String, timeout: Duration = 10.seconds, inbound: ByteArray? = null): Pair<Answer, ByteArray?> {
    val answered = CompletableDeferred<Pair<String, ByteArray?>>()
    var count = 0
    val budget = if (cold.getAndSet(false)) maxOf(timeout, COLD_START) else timeout
    call(json, inbound) { a, payload -> count++; answered.complete(a to payload) }
    val (a, payload) = withTimeout(budget) { answered.await() }
    check(count == 1) { "answered $count times" }
    val answer = wire.decodeFromString(Answer.serializer(), a)
    check((answer is Answer.Bytes || answer is Answer.RowCover && answer.sent) == (payload != null)) { "$answer with a payload of ${payload?.size}" }
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

/**
 * core's `FileSink`, `FileSource` and `PickedPaths` as the JVM plays them: a written file stays only when the producer
 * answered true; a picked path is read at offsets ([reads] records each read, `<name>@<from>+<length>`, and each whole
 * read, `<name>@whole`, so a test tells the two apart); a directory walked without following a link.
 */
internal object JvmFiles : DeviceFiles {
    val reads: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    /** Names whose first read past the first frame fails once (a network or disk hiccup mid-upload), then reads. */
    val failOnce: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override suspend fun read(path: String, most: Int): ByteArray {
        reads += "${java.io.File(path).name}@whole"
        return java.io.File(path).readBytes().also { if (it.size > most) throw IllegalStateException("too large") }
    }

    override fun source(path: String): me.raddatz.veyq.mobile.blob.UploadSource? {
        val f = java.io.File(path)
        if (!f.isFile || !f.canRead()) return null
        return me.raddatz.veyq.mobile.blob.UploadSource.of(f.name, f.length(), me.raddatz.veyq.mobile.blob.MediaTypes.byName(f.name), f.lastModified()) { from, length ->
            reads += "${f.name}@$from+$length"
            if (from > 0 && failOnce.remove(f.name)) throw java.io.IOException("${f.name} did not read this once")
            java.io.RandomAccessFile(f, "r").use { raf ->
                val buf = ByteArray(maxOf(0, minOf(length.toLong(), raf.length() - from).toInt()))
                raf.seek(from)
                raf.readFully(buf)
                buf
            }
        }
    }

    override fun walk(path: String): List<me.raddatz.veyq.mobile.blob.DirectoryWalk.Found>? {
        val root = java.nio.file.Paths.get(path)
        if (!java.nio.file.Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
        val out = ArrayList<me.raddatz.veyq.mobile.blob.DirectoryWalk.Found>()
        fun visit(dir: java.nio.file.Path) {
            val names = java.nio.file.Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
            for (n in names) {
                val p = dir.resolve(n)
                val at = root.relativize(p).toString().replace('\\', '/')
                when {
                    java.nio.file.Files.isSymbolicLink(p) -> out += me.raddatz.veyq.mobile.blob.DirectoryWalk.Found(at, me.raddatz.veyq.mobile.blob.DirectoryWalk.Kind.LINK)
                    java.nio.file.Files.isDirectory(p) -> { out += me.raddatz.veyq.mobile.blob.DirectoryWalk.Found(at, me.raddatz.veyq.mobile.blob.DirectoryWalk.Kind.DIRECTORY); visit(p) }
                    else -> out += me.raddatz.veyq.mobile.blob.DirectoryWalk.Found(at, me.raddatz.veyq.mobile.blob.DirectoryWalk.Kind.FILE, source(p.toString()))
                }
            }
        }
        visit(root)
        return out
    }

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
