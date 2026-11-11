@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, ExperimentalAtomicApi::class)

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
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.experimental.ExperimentalNativeApi

// The six C functions of bridge.h (ADR 0123's Consequences, the bridge) — the library's whole surface. Each pointer the
// host hands in is read before the function returns; each pointer handed to the host is valid only during that call, so
// the host copies what it keeps. JSON is UTF-8 and NUL-terminated; the byte payload beside it carries the bytes of an
// `Answer.Bytes` (an avatar, a blob's range), which never travel as base64, and is null with length 0 for every other
// answer, every update and every host request. Inward, no call or reply carries one yet: what veyq_call and veyq_reply
// are handed beside the JSON is not read.

/** `void (*)(void *ctx, const char *json, const uint8_t *bytes, size_t len)`: an answer or an update. */
typealias VeyqAnswer = CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, CPointer<UByteVar>?, ULong) -> Unit>>

/** `void (*)(void *ctx, uint64_t token, const char *json, const uint8_t *bytes, size_t len)`: a host request. */
typealias VeyqHost = CPointer<CFunction<(COpaquePointer?, ULong, CPointer<ByteVar>?, CPointer<UByteVar>?, ULong) -> Unit>>

/** The platform's own Ktor engine, the one thing Linux and Windows differ in here: Curl (`linuxMain`), WinHTTP (`mingwMain`). */
internal expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

private class Running(val bridge: Bridge<*, *>, val host: Host)

/** The files the host names by path, written by core's [FileSink]: one not finished is removed (ADR 0128). */
private object Files : DeviceFiles {
    override suspend fun write(path: String, produce: suspend (sink: (ByteArray) -> Unit) -> Boolean): Boolean =
        FileSink.write(path, kept = { it }, produce)
}

private val running = AtomicReference<Running?>(null)

/** veyq_init's answers. */
private const val STARTED = 0
private const val ALREADY_STARTED = 1
private const val CONFIG_REFUSED = 2
private const val NO_AES = 3

@CName("veyq_init")
fun veyqInit(config: CPointer<ByteVar>?, host: VeyqHost?, hostCtx: COpaquePointer?): Int {
    if (host == null) return CONFIG_REFUSED
    val c = runCatching { wire.decodeFromString(Config.serializer(), config!!.toKString()) }.getOrNull() ?: return CONFIG_REFUSED
    // libsodium's AES-256-GCM needs the CPU's AES instructions (on x86-64 AVX too); without them the core refuses to
    // start (ADR 0123's Consequences, the core on Linux and on Windows).
    runCatching { LibsodiumPrimitives.randomBytes(1) }.onFailure { return NO_AES }
    if (running.load() != null) return ALREADY_STARTED
    val h = Host { token, json ->
        memScoped { host.invoke(hostCtx, token.toULong(), json.cstr.ptr, null, 0u) }
    }
    val bridge = Bridge.live(LibsodiumPrimitives, { block -> platformHttpClient(block) }, c, HostStore(h), Files)
    if (!running.compareAndSet(null, Running(bridge, h))) {
        bridge.shutdown()
        return ALREADY_STARTED
    }
    return STARTED
}

@CName("veyq_call")
fun veyqCall(request: CPointer<ByteVar>?, bytes: CPointer<UByteVar>?, len: ULong, ctx: COpaquePointer?, answer: VeyqAnswer?) {
    val r = running.load() ?: return
    if (answer == null) return
    val json = request?.toKString() ?: ""
    r.bridge.call(json) { a, payload ->
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
    val r = running.load() ?: return 0u
    if (update == null) return 0u
    val json = request?.toKString() ?: return 0u
    return r.bridge.watch(json) { u -> memScoped { update.invoke(ctx, u.cstr.ptr, null, 0u) } }.toULong()
}

@CName("veyq_unwatch")
fun veyqUnwatch(id: ULong) {
    running.load()?.bridge?.unwatch(id.toLong())
}

@CName("veyq_reply")
fun veyqReply(token: ULong, json: CPointer<ByteVar>?, bytes: CPointer<UByteVar>?, len: ULong) {
    val r = running.load() ?: return
    r.host.reply(token.toLong(), json?.toKString() ?: "")
}

@CName("veyq_shutdown")
fun veyqShutdown() {
    running.exchange(null)?.bridge?.shutdown()
}
