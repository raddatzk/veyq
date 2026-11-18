@file:OptIn(ExperimentalAtomicApi::class)

package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.raddatz.veyq.mobile.account.KitPictures
import me.raddatz.veyq.mobile.audio.Deck
import me.raddatz.veyq.mobile.audio.NowPlaying
import me.raddatz.veyq.mobile.audio.PlayerCommands
import me.raddatz.veyq.mobile.audio.SystemControls
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.memories.music.MusicUpload
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
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
import kotlin.concurrent.atomics.fetchAndIncrement
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

    /**
     * [ask] from a coroutine that must not block its thread while the host answers (the music player's own lane, ADR 0142
     * decision 2: a deck's load and position): it suspends instead; a host silent for [timeout] fails it ([Silent]).
     */
    suspend fun asking(request: HostRequest): HostReply = askingWithPayload(request).also { it.payload?.fill(0) }.reply

    /** [asking], with the payload the host handed beside its reply, which the caller owns. */
    suspend fun askingWithPayload(request: HostRequest): Replied {
        val token = tokens.incrementAndFetch()
        val answer = CompletableDeferred<Replied>()
        waiting.put(token, answer)
        try {
            send(token, wire.encodeToString(HostRequest.serializer(), request))
            // withTimeoutOrNull, as [askWithPayload]: a silent host is a failure, never this coroutine's cancellation
            return withTimeoutOrNull(timeout) { answer.await() } ?: throw Silent(timeout)
        } finally {
            waiting.remove(token)
        }
    }

    /**
     * Sends [request] and waits for nothing (a deck's pause, seek, ramp and clear; the system controls' changes): the host's
     * reply finds no one waiting and is dropped. Sent on the caller's thread before this returns, so what one thread tells
     * and asks reaches the host in the order made.
     */
    fun tell(request: HostRequest) {
        send(tokens.incrementAndFetch(), wire.encodeToString(HostRequest.serializer(), request))
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
 * An Emergency Kit file's drawings (core's `KitPictures`; ADR 0136 decision 1: the platform draws, the core decides) asked
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
 * A Memories upload's tiers asked of the host ([HostRequest.MediaTiers]; ADR 0136 decision 1: the platform decodes and
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

/**
 * A banner's pictures asked of the host (ADR 0144 decision 5; ADR 0136 decision 1: the platform decodes and encodes, the
 * core holds the spec and decides): the picked file's [HostRequest.MediaTiers] with the cover's spec as its preview
 * (`NodeCover.LONG_EDGE`, `NodeCover.QUALITY`: upright, never enlarged) — the request every desktop already answers for an
 * upload, so no host decodes a banner in a way of its own —, a video's poster its first frame (at 0 ms). The preview is
 * the picture; the thumbnail the host makes beside it is dropped. Asked suspending, never on a thread the host waits on.
 * A source that names no path, a reply that is no set of tiers, and a picture the host does not decode are none (the
 * core then refuses the pick as unreadable).
 */
internal class HostBannerPictures(private val host: Host) : me.raddatz.veyq.mobile.memories.banner.BannerWrite.Pictures {
    override suspend fun image(source: UploadSource): ByteArray? = picture(source, video = false)

    override suspend fun poster(source: UploadSource): ByteArray? = picture(source, video = true)

    private suspend fun picture(source: UploadSource, video: Boolean): ByteArray? {
        val path = (source as? PathSource)?.path ?: return null
        val type = MediaUpload.typeOf(source.type, source.name, ByteArray(0)).takeIf { it.startsWith(if (video) "video/" else "image/") }
            ?: if (video) "video/mp4" else "image/jpeg"
        val r = host.askingWithPayload(
            HostRequest.MediaTiers(
                path, type, me.raddatz.veyq.mobile.session.NodeCover.LONG_EDGE, me.raddatz.veyq.mobile.session.NodeCover.QUALITY,
                MediaUpload.THUMBNAIL_EDGE, MediaUpload.THUMBNAIL_QUALITY, 0,
            ),
        )
        val t = r.reply as? HostReply.Tiers
        val packed = r.payload
        if (t == null || packed == null || t.previewSize <= 0 || t.previewSize > packed.size) {
            packed?.fill(0)
            return null
        }
        return packed.copyOfRange(0, t.previewSize).also { packed.fill(0) }
    }
}

/** A composition that decodes no banner (a test's that plays none): every pick is refused as unreadable. */
internal object NoBannerPictures : me.raddatz.veyq.mobile.memories.banner.BannerWrite.Pictures {
    override suspend fun image(source: UploadSource): ByteArray? = null
    override suspend fun poster(source: UploadSource): ByteArray? = null
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

/**
 * Where a deck's reader is held for the host (ADR 0137 decision 2): [hold] gives it an id the host reads through
 * `Reader.Read` — null while no tenant is open, so nothing is held past one —, [drop] lets that id go.
 */
internal interface HeldReaders {
    fun hold(reader: RangeReader): Long?
    fun drop(id: Long)
}

/**
 * The host's half of the music (ADR 0142 decisions 2, 3, 4 and 7) over its requests: the player's decks ([deck]), the
 * system's controls ([controls]), a picked track's length ([measure]) and an untitled track's title ([untitled]). The
 * platform plays, shows and measures; what plays, when, how loud and what is refused stays the core's.
 */
internal class HostAudio(
    private val host: Host,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** Deck [id], its reader held through [readers], reporting to [events] (the player's). */
    fun deck(id: Long, readers: HeldReaders, events: Deck.Events): HostDeck = HostDeck(host, id, readers, events)

    val controls: SystemControls = HostControls(host)

    /**
     * A picked track's length (`MusicUpload.Measure`) asked of the host by its path ([HostRequest.MediaDuration], the same
     * request a video's poster time uses), off the thread that checks; a source that names no path, a reply that is no
     * length and a host that fails all answer null — the core then refuses the file as unplayable.
     */
    val measure: MusicUpload.Measure = object : MusicUpload.Measure {
        override fun durationMs(source: me.raddatz.veyq.mobile.blob.UploadSource, type: String, done: (Long?) -> Unit) {
            val path = (source as? PathSource)?.path ?: return done(null)
            scope.launch {
                done(runCatching { (host.asking(HostRequest.MediaDuration(path)) as? HostReply.Value)?.value?.toLongOrNull() }.getOrNull())
            }
        }
    }

    /** Untitled track [number]'s title as the host says it ([HostRequest.WordsSay]); its number alone where the host says nothing. */
    fun untitled(number: Int): String =
        runCatching { (host.ask(HostRequest.WordsSay(Notice(Msg.MUSIC_UNTITLED, number))) as? HostReply.Value)?.value }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: number.toString()
}

/**
 * One of the player's decks on the host's side of the bridge (core's `Deck`, ADR 0142 decision 2; `Audio.*` host
 * requests): a load hands the host a reader by id, never bytes or a key; each load is numbered, and an event the host
 * reports for another load than the one held ([ended], [failed]: a cleared or reloaded deck's) is dropped. Its pause,
 * seek, ramp and clear are told, never waited for; its load, play and position are asked without blocking the player's lane.
 */
/** No reader held: reader ids are a counter's, never negative. */
private const val NO_READER = Long.MIN_VALUE

internal class HostDeck(
    private val host: Host,
    val id: Long,
    private val readers: HeldReaders,
    private val events: Deck.Events,
) : Deck {
    private val loads = AtomicLong(0)
    // The held reader's id, NO_READER when none. An AtomicLong, not an AtomicReference<Long?>: a reference compares a
    // boxed Long by identity, so its compareAndSet missed and a refused load never let its reader go (HostDeckReaderTest).
    private val reader = AtomicLong(NO_READER)

    override suspend fun load(reader: RangeReader, mime: String): Long {
        letReaderGo()
        val load = loads.incrementAndFetch()
        val held = readers.hold(reader) ?: throw IllegalStateException("no tenant holds the reader")
        this.reader.store(held)
        return when (val r = host.asking(HostRequest.AudioLoad(id, load, held, reader.size, mime))) {
            is HostReply.Value -> r.value?.toLongOrNull() ?: 0L
            else -> {
                if (this.reader.compareAndSet(held, NO_READER)) readers.drop(held)
                throw IllegalStateException("the host did not load it: ${(r as? HostReply.Failed)?.error ?: r::class.simpleName}")
            }
        }
    }

    override suspend fun play() {
        val r = host.asking(HostRequest.AudioPlay(id))
        if (r !is HostReply.Done) throw IllegalStateException("the host did not play it: ${(r as? HostReply.Failed)?.error ?: r::class.simpleName}")
    }

    override fun pause() = host.tell(HostRequest.AudioPause(id))

    override fun seek(positionMs: Long) = host.tell(HostRequest.AudioSeek(id, positionMs))

    /** 0 where the host does not say (a failed or silent ask): the player then sees the track at its start. */
    override suspend fun positionMs(): Long = try {
        (host.asking(HostRequest.AudioPosition(id)) as? HostReply.Value)?.value?.toLongOrNull() ?: 0L
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        0L
    }

    override fun ramp(to: Double, overMs: Long) = host.tell(HostRequest.AudioRamp(id, to, overMs))

    override fun clear() {
        // the cleared load's events are no longer this deck's
        loads.fetchAndIncrement()
        host.tell(HostRequest.AudioClear(id))
        letReaderGo()
    }

    /** The reader held for the host goes: a read of it is refused from now on. */
    private fun letReaderGo() {
        reader.exchange(NO_READER).takeIf { it != NO_READER }?.let(readers::drop)
    }

    /** The host's [Call.AudioEnded] for [load]: the player hears it only for the load this deck holds. */
    fun ended(load: Long) {
        if (load == loads.load()) events.ended()
    }

    /** The host's [Call.AudioFailed] for [load]: as [ended]. */
    fun failed(load: Long) {
        if (load == loads.load()) events.failed()
    }
}

/**
 * The system's media controls on the host's side of the bridge (core's `SystemControls`, ADR 0142 decision 3), told and
 * never waited for: the commands come back as `Player.*` calls onto the same player, so [bind] hands the host nothing but
 * the news that they reach one now.
 */
internal class HostControls(private val host: Host) : SystemControls {
    override fun bind(commands: PlayerCommands) = host.tell(HostRequest.ControlsBind)
    override fun show(now: NowPlaying) = host.tell(HostRequest.ControlsShow(NowPlayingShown.of(now)))
    override fun clear() = host.tell(HostRequest.ControlsClear)
}
