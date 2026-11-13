@file:OptIn(ExperimentalAtomicApi::class)

package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.account.KitPictures
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.memories.MediaUpload
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

    /** Asks the host and blocks this core thread until it answers; a host silent for [timeout] fails the ask. */
    fun ask(request: HostRequest): HostReply = askWithPayload(request).also { it.payload?.fill(0) }.reply

    /** [ask], with the payload the host handed beside its reply, which the caller owns. */
    fun askWithPayload(request: HostRequest): Replied {
        val token = tokens.incrementAndFetch()
        val answer = CompletableDeferred<Replied>()
        waiting.put(token, answer)
        try {
            send(token, wire.encodeToString(HostRequest.serializer(), request))
            return runBlocking { withTimeout(timeout) { answer.await() } }
        } finally {
            waiting.remove(token)
        }
    }

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

/**
 * A file the host picked, opened by the core at its [path] (`DeviceFiles.source`): read through [inner] at offsets, as any
 * source, and named by its path so the host can be asked for its tiers ([HostTiers]) — the path is the host's own, nothing
 * of the file crosses.
 */
internal class PathSource(val path: String, private val inner: UploadSource) :
    UploadSource(inner.name, inner.size, inner.type, inner.modifiedAt) {
    override suspend fun read(from: Long, length: Int): ByteArray = inner.read(from, length)
    override fun close() = inner.close()
}

/**
 * A Memories upload's tiers asked of the host ([HostRequest.MediaTiers]; ADR 0130 decision 1: the platform decodes and
 * encodes, the core holds the spec and decides): the JPEGs ride in beside the reply ([HostReply.Tiers]). [derive] returns at
 * once — the asks run on [scope], so the queue's thread that called never waits on the host there, and the thread that
 * sent the call never does at all; a video's poster time is the core's from the length the host tells
 * ([HostRequest.MediaDuration], `MediaUpload.posterAtMs`). What the host does not decode, a reply that is no set of tiers,
 * and a source that names no path go up alone ([NONE]).
 */
internal class HostTiers(
    private val host: Host,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : MediaUpload.Deriver {
    override fun derive(source: UploadSource, type: String, done: (MediaUpload.Derived) -> Unit) {
        val path = (source as? PathSource)?.path ?: return done(NONE)
        scope.launch { done(runCatching { tiers(path, type) }.getOrDefault(NONE)) }
    }

    private fun tiers(path: String, type: String): MediaUpload.Derived {
        val at = if (type.startsWith("video/")) {
            MediaUpload.posterAtMs((host.ask(HostRequest.MediaDuration(path)) as? HostReply.Value)?.value?.toLongOrNull() ?: 0L)
        } else {
            0L
        }
        val r = host.askWithPayload(
            HostRequest.MediaTiers(
                path, type, MediaUpload.PREVIEW_EDGE, MediaUpload.PREVIEW_QUALITY, MediaUpload.THUMBNAIL_EDGE, MediaUpload.THUMBNAIL_QUALITY, at,
            ),
        )
        val t = r.reply as? HostReply.Tiers
        val packed = r.payload
        // both tiers or none: a preview without its thumbnail (or a payload shorter than the preview) is no set of tiers
        if (t == null || packed == null || t.previewSize <= 0 || t.previewSize >= packed.size) {
            packed?.fill(0)
            return NONE
        }
        return MediaUpload.Derived(packed.copyOfRange(0, t.previewSize), packed.copyOfRange(t.previewSize, packed.size), t.width, t.height)
            .also { packed.fill(0) }
    }

    companion object {
        /** No tiers: the original goes up alone. */
        val NONE = MediaUpload.Derived(null, null, null, null)
    }
}

/** A composition that derives no tiers (a test's that plays none): every original goes up alone. */
internal object NoTiers : MediaUpload.Deriver {
    override fun derive(source: UploadSource, type: String, done: (MediaUpload.Derived) -> Unit) = done(HostTiers.NONE)
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
