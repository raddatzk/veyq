package me.raddatz.veyq.mobile.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.api.models.DeviceRegister
import me.raddatz.veyq.mobile.api.models.DeviceRegisterUpdate
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8

/**
 * The platform's keys that never leave this device (Android Keystore, the Apple Keychain), for the two device wraps of
 * the Master Key (ADR 0005, ADR 0123 decision 8):
 *
 *  - a **bind key** per trusted account: its HMAC over `veyq/v1/device-bind` is the DEVICE wrap's device factor, so the
 *    wrap opens with the password and this device, no Secret Key;
 *  - one **unlock key** for the app: AES-256-GCM that works only after the person authenticated (biometrics or the
 *    device's own code), which seals the DEVICE_KEY wraps of the accounts the person chose to stay signed in to. One
 *    authentication may open several within a short window, so one launch asks once.
 *
 * And small secrets kept without asking the person: a remembered account's refresh token (server access, not content).
 */
interface DeviceKeys {
    /** Creates the bind key [alias], replacing one there. */
    fun createBindKey(alias: String)

    /** HMAC-SHA256 under the bind key [alias] over [message], or null when there is no such key. */
    fun bindHmac(alias: String, message: ByteArray): ByteArray?

    fun deleteBindKey(alias: String)

    /** Seals [plaintext] under the unlock key (created on first use): (iv, ciphertext‖tag). May ask the person; throws
     *  [DeviceAuthFailed] when they do not authenticate. */
    suspend fun sealUnlockable(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray>

    /** Opens what [sealUnlockable] sealed. May ask the person; throws [DeviceAuthFailed] when they do not authenticate,
     *  or when the unlock key is gone (a new biometric enrolment invalidates it on Android). */
    suspend fun openUnlockable(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray

    fun deleteUnlockKey()

    fun putSecret(alias: String, value: String)
    fun secret(alias: String): String?
    fun deleteSecret(alias: String)

    /**
     * What staying signed in would use on this device now, asked of the store itself (never a host's own probe): the
     * sign-in's switch shows or hides by it, and its label says which. May reach the platform (D-Bus, cryptngc), so it is
     * asked when a sign-in shows, not on every frame.
     */
    val staySignedIn: StaySignedIn
}

/**
 * What "Stay signed in" uses on a device: the strongest it has (the CR device-trust-on-linux-and-windows, Q1/Q2, owner
 * 2026-11-11). The same meaning on every platform; the words a platform gives each are its own.
 */
@Serializable
enum class StaySignedIn {
    /**
     * The unlock key opens only after the person authenticated to the device, enforced by the store: Windows Hello's key,
     * Android's screen lock (biometric or device credential), Apple's `.userPresence`. Windows labels it "Stay signed in
     * with Windows Hello".
     */
    PRESENCE,

    /**
     * The unlock key opens with the person's desktop sign-in and asks nothing of its own (Windows without Hello: DPAPI;
     * Linux: the Secret Service, unlocked by the login): the web's level. The plain "Stay signed in".
     */
    LOGIN,

    /**
     * Nothing can be kept that staying signed in needs (Linux without a Secret Service; Android and Apple without a screen
     * lock): the switch is hidden, since turned on it would do what it does off.
     */
    NONE,
}

/** The person did not authenticate for the unlock key ([cancelled]), or the key no longer works. */
class DeviceAuthFailed(val cancelled: Boolean, detail: String? = null) : Exception(detail ?: if (cancelled) "cancelled" else "unlock key unusable")

/**
 * One account this device trusts (the record the web keeps in IndexedDB): which server and account, which of the two
 * wraps, the wrap's id the server answered (how the unlock finds it among the served wraps), and the local alias of its
 * key. Not secret: the email is the person's own, on their own device.
 */
@Serializable
data class TrustedDevice(
    val server: String,
    val email: String,
    val userId: String,
    val method: Method,
    val wrapId: String,
    val alias: String,
) {
    enum class Method { DEVICE, DEVICE_KEY }

    /** Whose record this is: one per account per server, as a connection is. */
    val key: String get() = "${io.ktor.http.Url(server).host}#$userId"

    /** The alias under which a remembered account's refresh token is kept. */
    internal val refreshAlias: String get() = "$alias.refresh"
}

/**
 * This installation as the accounts see it: its device store (preferences), its keys, the label it names itself with
 * in the account's device register (the model's name), its id there, and the accounts it trusts.
 */
class ThisDevice(val store: DeviceStore, val keys: DeviceKeys, val label: String, private val primitives: Primitives) {
    /** A random id, kept outside the key stores (the web's `veyq:device-id`): it names this device in the register. */
    val id: String
        get() = store.read(ID) ?: uuid().also { store.write(ID, it) }

    /** Every account this device trusts, the most recently used first. */
    fun trusted(): List<TrustedDevice> =
        runCatching { store.read(TRUSTED)?.let { JSON.decodeFromString<List<TrustedDevice>>(it) } }.getOrNull().orEmpty()

    fun trusted(key: String): TrustedDevice? = trusted().firstOrNull { it.key == key }

    internal fun remember(record: TrustedDevice) =
        store.write(TRUSTED, JSON.encodeToString(listOf(record) + trusted().filter { it.key != record.key }))

    internal fun forget(key: String) = store.write(TRUSTED, JSON.encodeToString(trusted().filter { it.key != key }))

    /** Marks [key] the most recently used, so the app opens on it next time. */
    fun touch(key: String) {
        val record = trusted(key) ?: return
        remember(record)
    }

    internal fun newAlias(): String = "veyq.device." + hex(primitives.randomBytes(16))

    private fun uuid(): String {
        val b = primitives.randomBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = hex(b)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    private companion object {
        const val ID = "veyq.device-id"
        const val TRUSTED = "veyq.trusted-devices"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** The register's server calls, behind one seam so a test can play the server. */
internal interface RegisterApi {
    suspend fun get(): DeviceRegister
    /** The revision-guarded write; false on `409 stale_revision`. */
    suspend fun put(update: DeviceRegisterUpdate): Boolean
}

/**
 * The account's device register, a port of `src/web/src/features/account/deviceRegister.ts` (en.account.device-register):
 * one sealed document under the Master Key, bound to its revision, that names each device and joins it to its session
 * families and its device wraps — the join a person's other device uses to remove this one's saved key (ADR 0058). A
 * device adds itself: after every unlock (write moment 1) and after it enrols a wrap (write moment 2). Purely additive;
 * read through this device's marks like the pin store; a `409` re-reads and re-applies, bounded. Never on an unlock
 * path: a failure here is swallowed by the caller.
 */
internal class DeviceRegisterStore(
    private val suite: V1,
    private val api: RegisterApi,
    private val marks: TrustMarks,
    private val masterKey: ByteArray,
) {
    @Serializable
    data class Entry(val label: String = "", val lastSeenAt: Long = 0)

    @Serializable
    data class Plain(
        val devices: Map<String, Entry> = emptyMap(),
        val families: Map<String, String> = emptyMap(),
        val wraps: Map<String, String> = emptyMap(),
    )

    private class Opened(val revision: Long, val plain: Plain)

    private suspend fun load(): Opened {
        val served = api.get()
        val mark = marks.register()
        val blob = served.blob
        if (served.revision < mark || (blob == null && mark > 0)) throw TrustStateRefused(TrustStateRefused.Code.ROLLED_BACK)
        if (blob == null) return Opened(served.revision, Plain())
        val plain = runCatching {
            JSON.decodeFromString<Plain>(suite.open(context(served.revision), masterKey, unb64(blob)).decodeToString())
        }.getOrNull() ?: throw TrustStateRefused(TrustStateRefused.Code.UNREADABLE)
        marks.raiseRegister(served.revision)
        return Opened(served.revision, plain)
    }

    /** The register as this device opens it. */
    suspend fun read(): Plain = load().plain

    /** Records this device (keeping a label it has), the [familyId] its login created and the [wrapId] it holds. */
    suspend fun remember(deviceId: String, label: String, familyId: String?, wrapId: String?, now: Long) {
        var current = load()
        repeat(MAX_ATTEMPTS) {
            val p = current.plain
            val devices = p.devices + (deviceId to Entry(p.devices[deviceId]?.label ?: label, now))
            val next = evict(
                Plain(
                    devices = devices,
                    families = if (familyId != null) p.families + (familyId to deviceId) else p.families,
                    wraps = if (wrapId != null) p.wraps + (wrapId to deviceId) else p.wraps,
                ),
            )
            val stored = current.revision + 1
            val blob = b64(suite.seal(context(stored), masterKey, utf8(JSON.encodeToString(Plain.serializer(), next))))
            if (api.put(DeviceRegisterUpdate(blob, current.revision))) {
                marks.raiseRegister(stored)
                return
            }
            current = load()
        }
        throw IllegalStateException("device_register_unsettled")
    }

    /** The least recently seen devices beyond [MAX_DEVICES] go, with every family and wrap row naming them. */
    private fun evict(p: Plain): Plain {
        if (p.devices.size <= MAX_DEVICES) return p
        val keep = p.devices.entries.sortedByDescending { it.value.lastSeenAt }.take(MAX_DEVICES).map { it.key }.toSet()
        return Plain(p.devices.filterKeys { it in keep }, p.families.filterValues { it in keep }, p.wraps.filterValues { it in keep })
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_DEVICES = 32
        val JSON = Json { ignoreUnknownKeys = true }
        fun context(revision: Long) = Context("device-register", "revision" to revision)
    }
}
