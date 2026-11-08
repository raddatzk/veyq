package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * What a served key comes to against this account's pin (ADR 0028 decision 2): FIRST_USE (no pin: the chain was read
 * and its head pinned), MATCH, ADVANCED (the chain walks from the pinned head, every step doubly signed, and the pin
 * moved), or ANOMALY: never accepted as a granter, never offered to anyone to accept.
 */
enum class Anchor { FIRST_USE, MATCH, ADVANCED, ANOMALY }

/**
 * Where a granter stands in a group's tenant before their key is judged (ADR 0111 decisions 12 and 15,
 * `tenantTrust.ts`): ROOT — an owner the server lists with no join record, judged by the pin; UNANCHORED — anyone else,
 * never accepted; UNAVAILABLE — this device could not read the tenant, so the pin alone decides, as on the web.
 *
 * The web's fourth standing, JOINED (a join record that opens and verifies owner → invite → newcomer), is not built
 * here: a member with a join record reads as UNANCHORED, so a group whose granter joined by invite stays closed in the
 * app. That refuses more than the web, never less.
 */
enum class Standing { ROOT, UNANCHORED, UNAVAILABLE }

/** A key served without a head beside it (a grant's snapshot of its granter's key): its [anchor], and whether it is a
 *  RETIRED key of the chain (an earlier step's), which opens a grant only at an epoch this device already held. */
class ChainKeyAnchor(val anchor: Anchor, val retired: Boolean)

/**
 * The key check for a key another account signed with: `anchorChainKey` of `identityPins.ts`. One at a time per
 * account, as the web serializes it, since each check may write the store.
 */
internal class Anchoring(
    private val suite: V1,
    private val trust: Trust,
    private val api: TrustApi,
    private val pins: PinStore,
    private val now: () -> Long,
) {
    private val lock = Mutex()

    suspend fun anchorChainKey(userId: String, identityPublicKeyB64: String, standing: Standing?): ChainKeyAnchor = lock.withLock {
        val unanchored = ChainKeyAnchor(Anchor.ANOMALY, false)
        if (standing == Standing.UNANCHORED) return unanchored
        val store = pins.load()
        val pin = store.pins[userId]
        val fingerprint = fingerprintOrNull(identityPublicKeyB64) ?: return unanchored
        if (pin != null && pin.fingerprint == fingerprint) return ChainKeyAnchor(Anchor.MATCH, false)
        val chain = api.keyChain(userId) ?: return unanchored
        val reading = readChain(pin, userId, chain) ?: return unanchored
        val at = reading.fingerprints.lastIndexOf(fingerprint)
        if (at < 0) return unanchored
        pins.mutate(store) { recordReading(it, userId, pin, reading) }
        ChainKeyAnchor(reading.anchor, at < reading.fingerprints.size - 1)
    }

    private fun fingerprintOrNull(b64Key: String): String? = runCatching { b64(trust.fingerprintHash(unb64(b64Key))) }.getOrNull()

    /** A chain read against a pin: the anchor, the newest head and index, and the fingerprint each step installs. */
    class Reading(val anchor: Anchor, val head: String, val index: Int, val fingerprints: List<String>)

    /**
     * Walks a served chain from [pin]'s head (from its genesis for an unpinned contact): every step verified, the walk
     * passing the pinned head with the pinned key at it, the chain's own head the one its steps make. Null for anything
     * else (an anomaly). Pure; never a throw.
     */
    fun readChain(pin: Trust.Pin?, userId: String, chain: KeyChain): Reading? {
        if (chain.userId != userId) return null
        val links = runCatching {
            chain.steps.map {
                Trust.KeyChainLink(
                    it.index.toLong(), it.previousHead?.let(::unb64), unb64(it.identityPublicKey), unb64(it.secretKeyProofPublicKey),
                    it.identitySignature?.let(::unb64), it.proofSignature?.let(::unb64),
                )
            }
        }.getOrNull() ?: return null
        val from = pin?.let { runCatching { unb64(it.head) }.getOrNull() ?: return null }
        val walked = trust.verifyKeyChain(userId, links, from) as? Trust.KeyChainVerdict.Ok ?: return null
        val head = b64(walked.head)
        if (head != chain.head) return null
        val fingerprints = links.map { l -> runCatching { b64(trust.fingerprintHash(l.identityPublicKey)) }.getOrDefault("") }
        if (pin != null && (walked.fromIndex == null || fingerprints[walked.fromIndex] != pin.fingerprint)) return null
        val anchor = when {
            pin == null -> Anchor.FIRST_USE
            walked.fromIndex == walked.index -> Anchor.MATCH
            else -> Anchor.ADVANCED
        }
        return Reading(anchor, head, walked.index, fingerprints)
    }

    /** Stores what a reading concluded: a first sight claims only an empty slot, a move replaces only the pin it was judged
     *  against (a device that wrote meanwhile keeps its write). MATCH writes nothing. */
    private fun recordReading(pins: MutableMap<String, Trust.Pin>, userId: String, judgedFrom: Trust.Pin?, r: Reading) {
        val cur = pins[userId]
        val newest = r.fingerprints.last()
        if (r.anchor == Anchor.FIRST_USE && cur == null) pins[userId] = Trust.Pin(newest, r.head, r.index.toLong(), now(), false)
        if (r.anchor == Anchor.ADVANCED && judgedFrom != null && (cur == null || cur.head == judgedFrom.head)) {
            pins[userId] = Trust.Pin(newest, r.head, r.index.toLong(), judgedFrom.firstSeenAt, judgedFrom.replaced)
        }
    }
}
