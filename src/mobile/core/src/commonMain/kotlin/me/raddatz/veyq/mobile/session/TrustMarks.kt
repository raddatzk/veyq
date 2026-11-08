package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.utf8

/**
 * A small key-value store on this device that the platform provides (Android's SharedPreferences, Apple's
 * UserDefaults). It holds the trust marks, which are no secret, and nothing else.
 */
interface DeviceStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}

/** A [DeviceStore] in memory: for tests, and for a device that keeps no marks (the named no-mark case). */
class MemoryDeviceStore : DeviceStore {
    private val values = HashMap<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

/** Why a sealed account document was refused (ADR 0028 decision 7), never read as the empty store. */
class TrustStateRefused(val code: Code) : Exception(code.name) {
    enum class Code { ROLLED_BACK, UNREADABLE }
}

/** The newest epoch key this device held of one scope, as its commitment: the key's succession public key, base64. */
@Serializable
data class HeldEpochKey(val epoch: Long, val commitment: String)

/**
 * This device's trust marks for one account, a port of `src/web/src/features/account/trustMarks.ts` (ADR 0028 decision
 * 7, ADR 0024 decision 6, ADR 0115 decision 5): the highest pin-store revision it opened, per scope the highest epoch
 * it held (the floor) and the newest epoch key it held (the key a later epoch's succession statements must start from).
 *
 * Local and never synced: a synced mark would be one more thing the server could roll back. The slot is a one-way
 * derivation of the account's Master Key, so two accounts on one device never meet and no server can steer a device
 * to an empty slot. The storage format is this app's own; only the derivation of the slot is the web's.
 *
 * Marks only rise. Storage is best effort, since a mark that did not land is a lower mark (safer for the server,
 * never a lockout); the refusals that read them are fail-closed.
 */
internal class TrustMarks(private val store: DeviceStore, primitives: Primitives, masterKey: ByteArray) {
    @Serializable
    private data class Stored(val pins: Long = 0, val epochs: Map<String, Long> = emptyMap(), val held: Map<String, HeldEpochKey> = emptyMap())

    private val key = "veyq.trust-marks." + b64(primitives.hkdfSha256(masterKey, ByteArray(0), utf8("veyq/v1/trust-marks-slot"), 16))
    private val lock = Mutex()

    private fun read(): Stored {
        val raw = runCatching { store.read(key)?.let { JSON.decodeFromString<Stored>(it) } }.getOrNull() ?: return Stored()
        // Read back defensively: anything that is not a mark counts as no mark.
        return Stored(
            pins = raw.pins.coerceAtLeast(0),
            epochs = raw.epochs.filterValues { it > 0 },
            held = raw.held.filterValues { it.epoch >= 0 && COMMITMENT.matches(it.commitment) },
        )
    }

    private fun write(s: Stored) {
        runCatching { store.write(key, JSON.encodeToString(Stored.serializer(), s)) }
    }

    suspend fun pins(): Long = lock.withLock { read().pins }

    /** The highest epoch this device has held for [scopeId], 0 when none. */
    suspend fun floor(scopeId: String): Long = lock.withLock { read().epochs[scopeId] ?: 0 }

    suspend fun held(scopeId: String): HeldEpochKey? = lock.withLock { read().held[scopeId] }

    suspend fun raisePins(revision: Long) = lock.withLock {
        val s = read()
        if (revision > s.pins) write(s.copy(pins = revision))
    }

    /** This device now holds epoch [epoch] of [scopeId] with the key whose commitment is [commitment]: the floor rises to
     *  it and, when newer than the held one, it becomes the held key. An epoch-0 floor refuses nothing and is not stored. */
    suspend fun hold(scopeId: String, epoch: Long, commitment: String) = lock.withLock {
        val s = read()
        val epochs = if (epoch > 0 && epoch > (s.epochs[scopeId] ?: 0)) s.epochs + (scopeId to epoch) else s.epochs
        val current = s.held[scopeId]
        val held = if (COMMITMENT.matches(commitment) && (current == null || epoch > current.epoch)) s.held + (scopeId to HeldEpochKey(epoch, commitment)) else s.held
        write(s.copy(epochs = epochs, held = held))
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val COMMITMENT = Regex("^[A-Za-z0-9+/]{43}=$")
    }
}
