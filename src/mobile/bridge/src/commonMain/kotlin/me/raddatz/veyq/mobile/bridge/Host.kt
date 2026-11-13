@file:OptIn(ExperimentalAtomicApi::class)

package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.raddatz.veyq.mobile.account.KitPictures
import me.raddatz.veyq.mobile.qr.QrFrame
import me.raddatz.veyq.mobile.session.DeviceAuthFailed
import me.raddatz.veyq.mobile.session.DeviceKeys
import me.raddatz.veyq.mobile.session.DeviceStore
import me.raddatz.veyq.mobile.session.StaySignedIn
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.seconds

/** Values by id, read and changed from any thread: a map replaced whole on each change. */
internal class Registry<V> {
    private val map = AtomicReference(emptyMap<Long, V>())

    operator fun get(id: Long): V? = map.load()[id]

    fun put(id: Long, value: V) {
        while (true) {
            val m = map.load()
            if (map.compareAndSet(m, m + (id to value))) return
        }
    }

    fun remove(id: Long): V? {
        while (true) {
            val m = map.load()
            val v = m[id] ?: return null
            if (map.compareAndSet(m, m - id)) return v
        }
    }

    fun values(): Collection<V> = map.load().values

    fun keys(): Set<Long> = map.load().keys

    fun removeAll(): Collection<V> {
        while (true) {
            val m = map.load()
            if (map.compareAndSet(m, emptyMap())) return m.values
        }
    }

    /** Removes every value [which] picks and returns them. */
    fun removeIf(which: (V) -> Boolean): Collection<V> {
        while (true) {
            val m = map.load()
            val gone = m.filterValues(which)
            if (gone.isEmpty() || map.compareAndSet(m, m - gone.keys)) return gone.values
        }
    }
}

/**
 * The host's one function, the other way (ADR 0123's Consequences, the bridge): the core sends a [HostRequest] under a
 * token and waits for the host's [HostReply] through [reply], which may come on any thread. [send] must not wait for
 * the core: a host that answers only after the core answered it would wait for itself.
 */
internal class Host(private val timeout: kotlin.time.Duration = TIMEOUT, private val send: (token: Long, request: String) -> Unit) {
    private val tokens = AtomicLong(0)
    private val waiting = Registry<CompletableDeferred<Replied>>()

    /** A reply and the payload the host handed beside it (a drawing's pixels, [HostReply.Pixels]), null where none. */
    class Replied(val reply: HostReply, val payload: ByteArray?)

    /** Asks the host and blocks this core thread until it answers; a host silent for [timeout] fails the ask ([Silent]). */
    fun ask(request: HostRequest): HostReply = askWithPayload(request).also { it.payload?.fill(0) }.reply

    /** [ask], with the payload the host handed beside its reply, which the caller owns. */
    fun askWithPayload(request: HostRequest): Replied {
        val token = tokens.incrementAndFetch()
        val answer = CompletableDeferred<Replied>()
        waiting.put(token, answer)
        try {
            send(token, wire.encodeToString(HostRequest.serializer(), request))
            // Never `withTimeout`: its TimeoutCancellationException is a CancellationException, which a call's coroutine
            // passes on as its own cancellation — the call then ends with no answer at all, and the host waits forever
            // (measured 2026-11-13: the kit test's SessionStart, its store read's reply lost). A silent host is a failure.
            return runBlocking { withTimeoutOrNull(timeout) { answer.await() } } ?: throw Silent(timeout)
        } finally {
            waiting.remove(token)
        }
    }

    /** The host did not reply within [timeout]: an ordinary failure of the ask, answered as one ([Answer.Failed]). */
    class Silent(timeout: kotlin.time.Duration) : IllegalStateException("the host did not reply within $timeout")

    /**
     * The host's answer to [token], and [payload] beside it (already the core's own copy); one the core no longer waits
     * for (timed out, or never asked) is dropped, its payload zeroed.
     */
    fun reply(token: Long, json: String, payload: ByteArray? = null) {
        val r = runCatching { wire.decodeFromString(HostReply.serializer(), json) }
            .getOrElse { HostReply.Failed("the host's reply did not read") }
        val waiter = waiting[token]
        if (waiter == null || !waiter.complete(Replied(r, payload))) payload?.fill(0)
    }

    companion object {
        val TIMEOUT = 30.seconds
    }
}

/**
 * The process's one core between `veyq_init` and `veyq_shutdown`, and the [Host] it asks. The host is published before
 * [start] composes the core: a composition asks the host at once (the store's first read, from a core thread), and a
 * host answers from inside its host function — so the reply ([reply]) can arrive before the start has returned. Were the
 * host published only with the started core, that reply would find nothing, be dropped, and the ask would wait out its
 * timeout (measured 2026-11-13 under load: the kit test's `SessionStart` never answered, every core thread idle).
 */
internal class Started<B : Any>(private val stop: (B) -> Unit) {
    private class Running<B : Any>(val host: Host) {
        val core = AtomicReference<B?>(null)
    }

    private val running = AtomicReference<Running<B>?>(null)

    /** The started core, null before [start] composed it and after [shutdown]. */
    val core: B? get() = running.load()?.core?.load()

    /** Composes the core over [host], already reachable through [reply]; false (nothing composed) where one runs. */
    fun start(host: Host, compose: (Host) -> B): Boolean {
        val r = Running<B>(host)
        if (!running.compareAndSet(null, r)) return false
        val core = compose(host)
        r.core.store(core)
        // a shutdown while this composed found no core to stop: it is stopped here
        if (running.load() !== r) stop(core)
        return true
    }

    /** The host's reply to [token], and [payload] beside it (the core's own copy), handed to the host that asked. */
    fun reply(token: Long, json: String, payload: ByteArray?) {
        val r = running.load()
        if (r == null) payload?.fill(0) else r.host.reply(token, json, payload)
    }

    /** Stops the core; a new [start] may follow. */
    fun shutdown() {
        running.exchange(null)?.core?.load()?.let(stop)
    }
}

/** The device store (`DeviceStore`: the trust marks, the trusted accounts, this device's id), kept by the host. */
internal class HostStore(private val host: Host) : DeviceStore {
    override fun read(key: String): String? = when (val r = host.ask(HostRequest.StoreRead(key))) {
        is HostReply.Value -> r.value
        else -> throw IllegalStateException("the host's store did not read: ${r.describe()}")
    }

    override fun write(key: String, value: String) = done(host.ask(HostRequest.StoreWrite(key, value)))

    override fun remove(key: String) = done(host.ask(HostRequest.StoreRemove(key)))

    private fun done(r: HostReply) {
        if (r !is HostReply.Done) throw IllegalStateException("the host's store did not write: ${r.describe()}")
    }

    private fun HostReply.describe() = (this as? HostReply.Failed)?.error ?: this::class.simpleName
}

/**
 * An Emergency Kit file's drawings (core's `KitPictures`; ADR 0130 decision 1: the platform draws, the core decides) asked
 * of the host for the file at [path], which the host itself picked: a page count, a page or a picture drawn into the
 * reply's payload ([HostReply.Pixels]). The core's `KitImport` decides whether and what to ask, so a kit found in the
 * file's bytes asks nothing. A reply that is no drawing of its size answers null, as a platform that cannot draw does.
 */
internal class HostPictures(private val host: Host, private val path: String) : KitPictures {
    override suspend fun pdfPages(): Int? = (host.ask(HostRequest.PdfPages(path)) as? HostReply.Value)?.value?.toIntOrNull()?.takeIf { it > 0 }

    override suspend fun pdfPage(page: Int, width: Int): QrFrame? = drawn(host.askWithPayload(HostRequest.PdfPage(path, page, width)))

    override suspend fun picture(longSide: Int): QrFrame? = drawn(host.askWithPayload(HostRequest.PictureLuma(path, longSide)))

    private fun drawn(r: Host.Replied): QrFrame? {
        val pixels = r.reply as? HostReply.Pixels
        val plane = r.payload
        if (pixels == null || plane == null || pixels.width <= 0 || pixels.height <= 0 ||
            plane.size.toLong() < pixels.width.toLong() * pixels.height
        ) {
            plane?.fill(0)
            return null
        }
        return QrFrame(pixels.width, pixels.height, pixels.width, plane)
    }
}

/** A composition that draws nothing (a test's that plays none, and a kit file that did not read: nothing to draw). */
internal object NoPictures : KitPictures {
    override suspend fun pdfPages(): Int? = null
    override suspend fun pdfPage(page: Int, width: Int): QrFrame? = null
    override suspend fun picture(longSide: Int): QrFrame? = null
}

/**
 * The device keys of a composition without a key store (a test's, and LiveBridgeTest's first walk): no bind key, no
 * unlock key, no secret kept, so a sign-in never trusts this device and every start is the full sign-in — the core's own
 * path on a build whose keychain refuses. It forwards nothing: what these functions carry (a Master Key to seal, a
 * refresh token to keep) must never cross the bridge.
 */
internal object NoDeviceKeys : DeviceKeys {
    override fun createBindKey(alias: String) = throw UnsupportedOperationException("this device keeps no keys")
    override fun bindHmac(alias: String, message: ByteArray): ByteArray? = null
    override fun deleteBindKey(alias: String) {}
    override suspend fun sealUnlockable(plaintext: ByteArray, aad: ByteArray, level: StaySignedIn): Pair<ByteArray, ByteArray> =
        throw DeviceAuthFailed(cancelled = false, detail = "this device keeps no keys")
    override suspend fun openUnlockable(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray, level: StaySignedIn): ByteArray =
        throw DeviceAuthFailed(cancelled = false, detail = "this device keeps no keys")
    override fun deleteUnlockKey(level: StaySignedIn) {}
    override fun putSecret(alias: String, value: String) = throw UnsupportedOperationException("this device keeps no keys")
    override fun secret(alias: String): String? = null
    override fun deleteSecret(alias: String) {}
    override val staySignedIn = emptySet<StaySignedIn>()
}
