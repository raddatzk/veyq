package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.api.models.IdentityPinEntry
import me.raddatz.veyq.mobile.api.models.IdentityPinEntryPage
import me.raddatz.veyq.mobile.api.models.IdentityPins
import me.raddatz.veyq.mobile.api.models.IdentityPinsWrite
import me.raddatz.veyq.mobile.api.models.KeyChain
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.unhex

/** The server calls the pin store and the key check make, behind one seam so a test can serve what a server might. */
internal interface TrustApi {
    suspend fun pinHead(): IdentityPins
    suspend fun pinEntries(after: String?, limit: Int): IdentityPinEntryPage
    /** The revision-guarded write; false on `409 stale_revision` (another device wrote first), a throw on anything else. */
    suspend fun writePins(write: IdentityPinsWrite): Boolean
    /** A person's key chain, or null when the server serves none to this account (`404`). */
    suspend fun keyChain(userId: String): KeyChain?
}

/**
 * The account's identity-pin store, a port of `src/web/src/features/account/identityPins.ts` (ADR 0028 decision 2,
 * ADR 0104), byte for byte the web's, since every device of the account reads and writes the same store: one sealed
 * entry per pin under a sealed head bound to the store's revision, the head's digest over every entry.
 *
 * Every read goes through this device's [TrustMarks]: a head older than one this device opened, or missing after it
 * opened one, is [TrustStateRefused.Code.ROLLED_BACK]; one that does not open is UNREADABLE; never the empty store. The
 * entries are then checked against the head's digest and count; a mismatch with the head unmoved is a rollback. Every
 * write is one compare-and-set on the head's revision, re-read and re-applied on a `409`, bounded.
 */
internal class PinStore(
    private val suite: V1,
    private val trust: Trust,
    private val api: TrustApi,
    private val marks: TrustMarks,
    private val masterKey: ByteArray,
) {
    class Opened(val revision: Long, val pins: Map<String, Trust.Pin>, val entries: Map<String, String>)

    private val indexKey = trust.identityPinIndexKey(masterKey)

    /** The store this session last verified, reused while the head's revision and digest are the ones it was verified at. */
    private var verified: Triple<Long, String, Opened>? = null

    suspend fun load(): Opened = read(api.pinHead())

    private suspend fun read(first: IdentityPins): Opened {
        var served = first
        for (attempt in 1..MAX_ATTEMPTS) {
            val (count, digest) = openHead(served)
            verified?.let { (rev, d, store) -> if (rev == served.revision && d == digest) return store }
            val store = verifyEntries(served.revision, count, digest, listEntries())
            if (store != null) {
                verified = Triple(served.revision, digest, store)
                return store
            }
            val now = api.pinHead()
            if (now.revision == served.revision) throw TrustStateRefused(TrustStateRefused.Code.ROLLED_BACK)
            served = now
        }
        throw IllegalStateException("identity_pins_unsettled")
    }

    /** The head through the marks (`openAccountDocument`): (count, base64 digest). */
    private suspend fun openHead(served: IdentityPins): Pair<Int, String> {
        val mark = marks.pins()
        val blob = served.blob
        if (served.revision < mark || (blob == null && mark > 0)) throw TrustStateRefused(TrustStateRefused.Code.ROLLED_BACK)
        if (blob == null) return 0 to b64(trust.identityPinSetDigest(emptyList()))
        val head = runCatching { trust.decodePinHead(suite.open(headContext(served.revision), masterKey, unb64(blob))) }.getOrNull()
            ?: throw TrustStateRefused(TrustStateRefused.Code.UNREADABLE)
        marks.raisePins(served.revision)
        return head.first to b64(head.second)
    }

    /** Every listed entry, page by page; a listing that does not advance or runs past the store's bound is unreadable. */
    private suspend fun listEntries(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var after: String? = null
        while (true) {
            val page = api.pinEntries(after, PAGE)
            for (e in page.propertyEntries) {
                if (!HEX_ID.matches(e.pinId) || (after != null && e.pinId <= after) || e.pinId in out) throw TrustStateRefused(TrustStateRefused.Code.UNREADABLE)
                out[e.pinId] = e.entry
                after = e.pinId
            }
            if (out.size > MAX_ENTRIES || (page.more && page.propertyEntries.isEmpty())) throw TrustStateRefused(TrustStateRefused.Code.UNREADABLE)
            if (!page.more) return out
        }
    }

    private fun setDigest(entries: Map<String, String>): String =
        b64(trust.identityPinSetDigest(entries.map { (id, e) -> unhex(id) to unb64(e) }))

    /** The digest and count first (a mismatch is null, for the caller to tell a rollback from a head that moved), then
     *  every entry opened as the pin of its id: its userId must derive the id it is listed under. */
    private fun verifyEntries(revision: Long, count: Int, digest: String, listed: Map<String, String>): Opened? {
        val computed = runCatching { setDigest(listed) }.getOrNull() ?: return null
        if (computed != digest || listed.size != count) return null
        val pins = HashMap<String, Trust.Pin>()
        for ((id, entry) in listed) {
            val opened = runCatching { trust.decodePinEntry(suite.open(entryContext(unhex(id)), masterKey, unb64(entry))) }.getOrNull()
            if (opened == null || hex(trust.identityPinId(indexKey, opened.first)) != id) throw TrustStateRefused(TrustStateRefused.Code.UNREADABLE)
            pins[opened.first] = opened.second
        }
        return Opened(revision, pins, listed)
    }

    /**
     * Applies [apply] to the pins and stores the changed entries with a new head, guarded by [base]'s revision. On a
     * `409 stale_revision` the store is read again and the same change re-applied on top of it, so what a concurrent
     * device wrote stands. A change that changes nothing writes nothing.
     */
    suspend fun mutate(base: Opened, apply: (MutableMap<String, Trust.Pin>) -> Unit) {
        var store = base
        repeat(MAX_ATTEMPTS) {
            val next = store.pins.toMutableMap().also(apply)
            val changed = next.keys.filter { store.pins[it] != next[it] }
            val removed = store.pins.keys.filter { it !in next }
            if (changed.isEmpty() && removed.isEmpty()) return
            val entries = store.entries.toMutableMap()
            val put = changed.map { userId ->
                val pinId = trust.identityPinId(indexKey, userId)
                val entry = b64(suite.seal(entryContext(pinId), masterKey, trust.encodePinEntry(userId, next.getValue(userId))))
                entries[hex(pinId)] = entry
                IdentityPinEntry(hex(pinId), entry)
            }
            val del = removed.map { hex(trust.identityPinId(indexKey, it)) }
            del.forEach { entries.remove(it) }
            val stored = store.revision + 1
            val digest = trust.identityPinSetDigest(entries.map { (id, e) -> unhex(id) to unb64(e) })
            val head = b64(suite.seal(headContext(stored), masterKey, trust.encodePinHead(entries.size, digest)))
            if (api.writePins(IdentityPinsWrite(store.revision, head, put, del, false))) {
                marks.raisePins(stored)
                val written = Opened(stored, next, entries)
                verified = Triple(stored, b64(digest), written)
                return
            }
            store = load()
        }
        throw IllegalStateException("identity_pins_unsettled")
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val PAGE = 1000
        const val MAX_ENTRIES = 25_000
        val HEX_ID = Regex("^[0-9a-f]{64}$")
        fun headContext(revision: Long) = Context("identity-pins", "revision" to revision)
        fun entryContext(pinId: ByteArray) = Context("identity-pin", "pinId" to pinId)
    }
}

