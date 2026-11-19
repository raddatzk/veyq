package me.raddatz.veyq.mobile.sharing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.b64url
import me.raddatz.veyq.mobile.crypto.utf8

/**
 * A write link's guest session on this device (ADR 0150 decision 3; the web's `guestIdentity.ts` `localStorage` entry):
 * the identity the guest created, the contributor token every write presents, the name it gave (shown in *Not you?*) and,
 * for an uploader, its own key `K_up` (base64). A secret — the token writes as the guest —, so it is kept only through
 * [GuestSessions], never in `DeviceStore`.
 */
@Serializable
data class GuestSession(
    val identityId: String,
    val contributorToken: String,
    val displayName: String? = null,
    /** A WRITE_ONLY uploader's own `K_up` (base64): its uploads and its name are sealed under it (ADR 0031 point 7). */
    val uploaderKey: String? = null,
) {
    override fun toString(): String = "GuestSession($identityId)"
}

/**
 * Where each platform keeps a guest's sessions (ADR 0150 decision 3): the Keychain, the Android Keystore-wrapped store,
 * the Secret Service, the Windows credential locker — where it keeps the device's wraps. Each entry is named by
 * [GuestNames.session], never by the share's id; its value is the session's JSON. A store that refuses a write throws,
 * which the naming reports; a read that fails is no session.
 */
interface GuestSessions {
    fun read(name: String): String?
    fun write(name: String, value: String)
    fun remove(name: String)
}

/** [GuestSessions] in memory: the tests', and a host's until its platform's store is wired (the session then lasts the run). */
class MemoryGuestSessions : GuestSessions {
    private val values = HashMap<String, String>()
    override fun read(name: String): String? = values[name]
    override fun write(name: String, value: String) { values[name] = value }
    override fun remove(name: String) { values.remove(name) }
    val names: Set<String> get() = values.keys.toSet()
}

/**
 * The names under which this device keeps something of a link (ADR 0150 decision 3; changes/README § A device-local
 * defence is filed under a key the server cannot name): a truncated SHA-256 of the link's origin and the id, never the id
 * itself — the share's id is the server's, and an entry named by it would tell anyone who reads the store which links were
 * opened. The guest's session in the credential store, the terms' and the note's acceptances in `DeviceStore`.
 */
class GuestNames(private val p: Primitives) {
    private fun digest(vararg parts: String): String = b64url(p.sha256(utf8(parts.joinToString("\n")))).take(22)

    /** The guest's session of link [shareId] at [origin]. */
    fun session(origin: String, shareId: String): String = "veyq-guest:" + digest("session", origin, shareId)

    /** The pairs of terms accepted for tenant [tenantId] of [origin] (the web keys them per tenant: one acceptance for all its links). */
    fun terms(origin: String, tenantId: String): String = "share-terms:" + digest("terms", origin, tenantId)

    /** The note of link [shareId] at [origin] accepted in exactly the text whose digest is [digest]. */
    fun note(origin: String, shareId: String, digest: String): String = "share-note:" + digest("note", origin, shareId, digest)
}

internal object GuestSessionJson {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(s: GuestSession): String = json.encodeToString(GuestSession.serializer(), s)
    fun decode(s: String?): GuestSession? = s?.let { runCatching { json.decodeFromString(GuestSession.serializer(), it) }.getOrNull() }
}
