@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.blob.FileSource
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.session.DeviceKeys
import me.raddatz.veyq.mobile.session.DeviceStore
import kotlin.experimental.ExperimentalNativeApi

// The six C functions of bridge.h (ADR 0123's Consequences, the bridge) — the library's whole surface. Each pointer the
// host hands in is read before the function returns; each pointer handed to the host is valid only during that call, so
// the host copies what it keeps. JSON is UTF-8 and NUL-terminated; the byte payload beside it carries the bytes of an
// `Answer.Bytes` (an avatar, a blob's range), which never travel as base64, and is null with length 0 for every other
// answer, every update and every host request. Inward, a call carries one where it hands the core bytes (a scan's frame,
// `Scan.Frame`), copied before veyq_call returns, and so does a reply (a kit page's pixels, `HostReply.Pixels`), copied
// before veyq_reply returns.

/** `void (*)(void *ctx, const char *json, const uint8_t *bytes, size_t len)`: an answer or an update. */
typealias VeyqAnswer = CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, CPointer<UByteVar>?, ULong) -> Unit>>

/** `void (*)(void *ctx, uint64_t token, const char *json, const uint8_t *bytes, size_t len)`: a host request. */
typealias VeyqHost = CPointer<CFunction<(COpaquePointer?, ULong, CPointer<ByteVar>?, CPointer<UByteVar>?, ULong) -> Unit>>

/** The platform's own Ktor engine, the one thing Linux and Windows differ in here: Curl (`linuxMain`), WinHTTP (`mingwMain`). */
internal expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * The platform's device keys over the host's device [store], reached by the core itself so no key, seal or token crosses
 * the bridge (ADR 0136's Consequences): the Secret Service (`linuxMain`), DPAPI and Windows Hello owned by
 * [Config.window] (`mingwMain`).
 */
internal expect fun platformDeviceKeys(config: Config, store: DeviceStore): DeviceKeys

/**
 * Where a received link's guest sessions are kept (ADR 0150 decision 3), beside the device keys: the Secret Service
 * (`linuxMain`), the Credential Locker (`mingwMain`).
 */
internal expect fun platformGuestSessions(): me.raddatz.veyq.mobile.sharing.GuestSessions

/**
 * Whether this platform's app draws an SVG as an image (inbox #1059, `FileView.svgAsImage`): Linux's GTK decodes it through
 * glycin's sandboxed librsvg, which runs no script and fetches nothing (`file_page.rs`' hostile-file test); Windows shows
 * it as its text.
 */
internal expect fun platformDrawsSvgAsImage(): Boolean

/** The files the host names by path, written by core's [FileSink]: one not finished is removed (ADR 0137). */
private object Files : DeviceFiles {
    override suspend fun write(path: String, produce: suspend (sink: (ByteArray) -> Unit) -> Boolean): Boolean =
        FileSink.write(path, kept = { it }, produce)

    override suspend fun read(path: String, most: Int): ByteArray {
        val source = FileSource(path, "", 0, null, null)
        try {
            // the parts joined once at the end: a receipt of 10 MiB read by appending would copy it 160 times over
            val parts = ArrayList<ByteArray>()
            var size = 0L
            try {
                while (true) {
                    val part = source.read(size, READ_STEP)
                    parts += part
                    size += part.size
                    if (size > most) throw IllegalStateException("too large")
                    if (part.size < READ_STEP) break
                }
                val out = ByteArray(size.toInt())
                var at = 0
                for (p in parts) {
                    p.copyInto(out, at)
                    at += p.size
                }
                return out
            } finally {
                // the parts are copies of the file (a kit's among them): zeroed once joined or given up
                parts.forEach { it.fill(0) }
            }
        } finally {
            source.close()
        }
    }

    override fun source(path: String): me.raddatz.veyq.mobile.blob.UploadSource? = me.raddatz.veyq.mobile.composition.PickedPaths.source(path)

    override fun walk(path: String): List<me.raddatz.veyq.mobile.depot.FolderUpload.Found>? = me.raddatz.veyq.mobile.composition.PickedPaths.walk(path)

    private const val READ_STEP = 1 shl 16
}

/** The core and its host: the host reachable by [veyqReply] before the core is composed ([Started]). */
private val started = Started<Bridge<*, *>> { it.shutdown() }

/** veyq_init's answers. */
private const val STARTED = 0
private const val ALREADY_STARTED = 1
private const val CONFIG_REFUSED = 2
private const val NO_AES = 3

/** The most a call carries in beside its JSON: a camera's 4K luminance plane is 8 MiB; more is no frame, so none is read. */
private const val MAX_INBOUND: ULong = 67_108_864uL // 64 MiB

@CName("veyq_init")
fun veyqInit(config: CPointer<ByteVar>?, host: VeyqHost?, hostCtx: COpaquePointer?): Int {
    if (host == null) return CONFIG_REFUSED
    val c = runCatching { wire.decodeFromString(Config.serializer(), config!!.toKString()) }.getOrNull() ?: return CONFIG_REFUSED
    // libsodium's AES-256-GCM needs the CPU's AES instructions (on x86-64 AVX too); without them the core refuses to
    // start (ADR 0123's Consequences, the core on Linux and on Windows).
    runCatching { LibsodiumPrimitives.randomBytes(1) }.onFailure { return NO_AES }
    val h = Host { token, json ->
        memScoped { host.invoke(hostCtx, token.toULong(), json.cstr.ptr, null, 0u) }
    }
    if (platformDrawsSvgAsImage()) me.raddatz.veyq.mobile.depot.FileView.drawSvgAsImage()
    val composed = started.start(h) {
        // a debug build's request log (inbox #947): installed only where the host's config asks for it
        val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit ->
            platformHttpClient {
                block()
                if (c.netLog) install(NetLog.plugin { line -> h.tell(HostRequest.NetLogLine(line)) })
            }
        }
        Bridge.live(LibsodiumPrimitives, client, c, HostStore(it), { platformDeviceKeys(c, it) }, Files, { path -> HostPictures(h, path) }, HostTiers(h), HostAudio(h), HostBannerPictures(h),
            webp = me.raddatz.veyq.mobile.media.WebpPicture::png, words = HostWords(h), squarePicture = HostSquarePicture(h), calendar = me.raddatz.veyq.mobile.memories.GalleryFilter.Calendar.local(c.language),
            guestSessions = platformGuestSessions())
    }
    return if (composed) STARTED else ALREADY_STARTED
}

@CName("veyq_call")
fun veyqCall(request: CPointer<ByteVar>?, bytes: CPointer<UByteVar>?, len: ULong, ctx: COpaquePointer?, answer: VeyqAnswer?) {
    val bridge = started.core ?: return
    if (answer == null) return
    val json = request?.toKString() ?: ""
    // what the host hands beside the call (a scan's frame) is copied before this returns, as bridge.h promises; NULL is
    // none, a non-NULL pointer with length 0 an empty one
    val inbound = bytes?.let { if (len > MAX_INBOUND) null else it.reinterpret<ByteVar>().readBytes(len.toInt()) }
    bridge.call(json, inbound) { a, payload ->
        memScoped {
            if (payload == null) {
                answer.invoke(ctx, a.cstr.ptr, null, 0u)
            } else {
                // An empty payload is still one (an empty range): a pointer to a byte never read, length 0 — so a host
                // tells "no payload" (NULL) from "no bytes" without reading the JSON.
                val held = if (payload.isEmpty()) ByteArray(1) else payload
                held.usePinned { pinned ->
                    answer.invoke(ctx, a.cstr.ptr, pinned.addressOf(0).reinterpret<UByteVar>(), payload.size.toULong())
                }
            }
        }
    }
}

@CName("veyq_watch")
fun veyqWatch(request: CPointer<ByteVar>?, ctx: COpaquePointer?, update: VeyqAnswer?): ULong {
    val bridge = started.core ?: return 0u
    if (update == null) return 0u
    val json = request?.toKString() ?: return 0u
    return bridge.watch(json) { u -> memScoped { update.invoke(ctx, u.cstr.ptr, null, 0u) } }.toULong()
}

@CName("veyq_unwatch")
fun veyqUnwatch(id: ULong) {
    started.core?.unwatch(id.toLong())
}

@CName("veyq_reply")
fun veyqReply(token: ULong, json: CPointer<ByteVar>?, bytes: CPointer<UByteVar>?, len: ULong) {
    // a drawing's pixels (HostReply.Pixels) beside the reply, copied before this returns, as bridge.h promises
    val payload = bytes?.let { if (len > MAX_INBOUND) null else it.reinterpret<ByteVar>().readBytes(len.toInt()) }
    // the host is reached even while veyq_init still composes the core: the composition's first store read is answered
    // from inside the host function, possibly before veyq_init returned (Started)
    started.reply(token.toLong(), json?.toKString() ?: "", payload)
}

@CName("veyq_shutdown")
fun veyqShutdown() {
    started.shutdown()
}
