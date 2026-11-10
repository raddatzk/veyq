@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, ExperimentalAtomicApi::class)

package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.experimental.ExperimentalNativeApi

// The six C functions of bridge.h (ADR 0123's Consequences, the bridge) — the library's whole surface. Each pointer the host
// hands in is read before the function returns; each pointer handed to the host is valid only during that call, so the
// host copies what it keeps. JSON is UTF-8 and NUL-terminated; the byte payload beside it is unused in this slice
// (null, length 0) and reserved for bytes the UI shows (an avatar, a tile), which never travel as base64.

/** `void (*)(void *ctx, const char *json, const uint8_t *bytes, size_t len)`: an answer or an update. */
typealias VeyqAnswer = CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, CPointer<UByteVar>?, ULong) -> Unit>>

/** `void (*)(void *ctx, uint64_t token, const char *json, const uint8_t *bytes, size_t len)`: a host request. */
typealias VeyqHost = CPointer<CFunction<(COpaquePointer?, ULong, CPointer<ByteVar>?, CPointer<UByteVar>?, ULong) -> Unit>>

private class Running(val bridge: Bridge<*, *>, val host: Host)

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
    // libsodium's AES-256-GCM needs the CPU's AES instructions; without them the core refuses to start (CR Q1).
    runCatching { LibsodiumPrimitives.randomBytes(1) }.onFailure { return NO_AES }
    if (running.load() != null) return ALREADY_STARTED
    val h = Host { token, json ->
        memScoped { host.invoke(hostCtx, token.toULong(), json.cstr.ptr, null, 0u) }
    }
    val bridge = Bridge.live(LibsodiumPrimitives, { block -> HttpClient(Curl, block) }, c, HostStore(h))
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
    r.bridge.call(json) { a -> memScoped { answer.invoke(ctx, a.cstr.ptr, null, 0u) } }
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
