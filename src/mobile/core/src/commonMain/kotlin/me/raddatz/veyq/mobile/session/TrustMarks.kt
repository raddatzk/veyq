package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.crypto.PairingMarks
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

/** What starting a list over came to (the web's `ResetOutcome`): written empty, or it opened after all and nothing was written. */
enum class StartOver { RESET, ALREADY_READABLE }

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
@PlatformApi
@HiddenFromObjC
class TrustMarks(private val store: DeviceStore, primitives: Primitives, masterKey: ByteArray) {
    @Serializable
    private data class Stored(
        val pins: Long = 0,
        val register: Long = 0,
        val epochs: Map<String, Long> = emptyMap(),
        val held: Map<String, HeldEpochKey> = emptyMap(),
    )

    private val key = "veyq.trust-marks." + b64(primitives.hkdfSha256(masterKey, ByteArray(0), utf8("veyq/v1/trust-marks-slot"), 16))
    private val lock = Mutex()

    private fun read(): Stored {
        val raw = runCatching { store.read(key)?.let { JSON.decodeFromString<Stored>(it) } }.getOrNull() ?: return Stored()
        // Read back defensively: anything that is not a mark counts as no mark.
        return Stored(
            pins = raw.pins.coerceAtLeast(0),
            register = raw.register.coerceAtLeast(0),
            epochs = raw.epochs.filterValues { it > 0 },
            held = raw.held.filterValues { it.epoch >= 0 && COMMITMENT.matches(it.commitment) },
        )
    }

    private fun write(s: Stored) {
        runCatching { store.write(key, JSON.encodeToString(Stored.serializer(), s)) }
    }

    suspend fun pins(): Long = lock.withLock { read().pins }

    suspend fun register(): Long = lock.withLock { read().register }

    suspend fun raiseRegister(revision: Long) = lock.withLock {
        val s = read()
        if (revision > s.register) write(s.copy(register = revision))
    }

    /** The highest epoch this device has held for [scopeId], 0 when none. */
    suspend fun floor(scopeId: String): Long = lock.withLock { read().epochs[scopeId] ?: 0 }

    suspend fun held(scopeId: String): HeldEpochKey? = lock.withLock { read().held[scopeId] }

    /**
     * A list started over on this device ([StartOver.RESET], the web's `setMark`, ADR 0028 decision 7's one way back): the
     * pin store's mark SET — lowered too — to the [revision] this device just wrote. The one door that lowers a mark; its
     * one caller is the start-over of [PinStore].
     */
    internal suspend fun setPins(revision: Long) = lock.withLock { write(read().copy(pins = revision)) }

    /** The device register's mark set to [revision] this device just wrote ([setPins]'s twin; [DeviceRegisterStore]'s start-over). */
    internal suspend fun setRegister(revision: Long) = lock.withLock { write(read().copy(register = revision)) }

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

    /** The marks a linking device hands over inside the grant (ADR 0028 Decision 7): its pins, register and floors. */
    suspend fun forLink(): PairingMarks = lock.withLock { read().let { PairingMarks(it.pins, it.register, it.epochs) } }

    /**
     * A linked device starts from the marks its linker held ([forLink]), before it reads a sealed document: each mark the
     * maximum of those and the ones this device already holds, as the web's `raiseMarks`. The held keys stay this
     * device's own (the grant carries none).
     */
    suspend fun raise(m: PairingMarks) = lock.withLock {
        val s = read()
        val epochs = HashMap(s.epochs)
        for ((scope, e) in m.epochs) if (e > 0 && e > (epochs[scope] ?: 0)) epochs[scope] = e
        write(s.copy(pins = maxOf(s.pins, m.pins), register = maxOf(s.register, m.register), epochs = epochs))
    }

    /**
     * A rotation this device made committed (the web's `raiseMarks` and `carryMarks`, ADR 0028 decision 7): every mark of
     * this slot carried into [to]'s — the new Master Key's slot —, each the maximum of both, and [to]'s pin-store and
     * register marks raised to the revisions the publish stored them at ([pins], [register]): this device wrote them.
     */
    suspend fun carryInto(to: TrustMarks, pins: Long, register: Long) {
        val mine = lock.withLock { read() }
        to.lock.withLock {
            val s = to.read()
            val epochs = HashMap(s.epochs)
            for ((scope, e) in mine.epochs) if (e > (epochs[scope] ?: 0)) epochs[scope] = e
            val held = HashMap(s.held)
            for ((scope, k) in mine.held) if ((held[scope]?.epoch ?: -1) < k.epoch) held[scope] = k
            to.write(Stored(maxOf(s.pins, mine.pins, pins), maxOf(s.register, mine.register, register), epochs, held))
        }
    }

    /** This account's marks gone from this device (its erasure, the web's `forgetMarks`). */
    suspend fun forget() = lock.withLock { runCatching { store.remove(key) } }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val COMMITMENT = Regex("^[A-Za-z0-9+/]{43}=$")
    }
}
