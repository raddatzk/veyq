@file:OptIn(ExperimentalAtomicApi::class)

package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.session.DeviceAuthFailed
import me.raddatz.veyq.mobile.session.DeviceKeys
import me.raddatz.veyq.mobile.session.DeviceStore
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

    fun removeAll(): Collection<V> {
        while (true) {
            val m = map.load()
            if (map.compareAndSet(m, emptyMap())) return m.values
        }
    }
}

/**
 * The host's one function, the other way (CR D6): the core sends a [HostRequest] under a token and waits for the
 * host's [HostReply] through [reply], which may come on any thread. [send] must not wait for the core: a host that
 * answers only after the core answered it would wait for itself.
 */
internal class Host(private val timeout: kotlin.time.Duration = TIMEOUT, private val send: (token: Long, request: String) -> Unit) {
    private val tokens = AtomicLong(0)
    private val waiting = Registry<CompletableDeferred<HostReply>>()

    /** Asks the host and blocks this core thread until it answers; a host silent for [timeout] fails the ask. */
    fun ask(request: HostRequest): HostReply {
        val token = tokens.incrementAndFetch()
        val answer = CompletableDeferred<HostReply>()
        waiting.put(token, answer)
        try {
            send(token, wire.encodeToString(HostRequest.serializer(), request))
            return runBlocking { withTimeout(timeout) { answer.await() } }
        } finally {
            waiting.remove(token)
        }
    }

    /** The host's answer to [token]; one the core no longer waits for (timed out, or never asked) is dropped. */
    fun reply(token: Long, json: String) {
        val r = runCatching { wire.decodeFromString(HostReply.serializer(), json) }
            .getOrElse { HostReply.Failed("the host's reply did not read") }
        waiting[token]?.complete(r)
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
 * The device keys of a host without a key store (CR § Context: device trust is not in this slice): no bind key, no
 * unlock key, no secret kept, so a sign-in never trusts this device and every start is the full sign-in — the core's own
 * path on a build whose keychain refuses. It forwards nothing: what these functions carry (a Master Key to seal, a
 * refresh token to keep) must never cross the bridge.
 */
internal object NoDeviceKeys : DeviceKeys {
    override fun createBindKey(alias: String) = throw UnsupportedOperationException("this device keeps no keys")
    override fun bindHmac(alias: String, message: ByteArray): ByteArray? = null
    override fun deleteBindKey(alias: String) {}
    override suspend fun sealUnlockable(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> =
        throw DeviceAuthFailed(cancelled = false, detail = "this device keeps no keys")
    override suspend fun openUnlockable(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        throw DeviceAuthFailed(cancelled = false, detail = "this device keeps no keys")
    override fun deleteUnlockKey() {}
    override fun putSecret(alias: String, value: String) = throw UnsupportedOperationException("this device keeps no keys")
    override fun secret(alias: String): String? = null
    override fun deleteSecret(alias: String) {}
}
