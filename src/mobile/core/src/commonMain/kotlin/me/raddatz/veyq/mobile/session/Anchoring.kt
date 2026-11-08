package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.api.models.KeyChain
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64

/**
 * What a served key comes to against this account's pin (ADR 0028 decision 2): FIRST_USE (no pin: the chain was read
 * and its head pinned), MATCH, ADVANCED (the chain walks from the pinned head, every step doubly signed, and the pin
 * moved), REPLACED (in a tenant only: the pinned head is not on the chain the person's join record anchors, which is at
 * least as long, so the pin was a key that was not theirs and moves, marked), or ANOMALY: never accepted, never offered
 * to anyone to accept.
 */
enum class Anchor { FIRST_USE, MATCH, ADVANCED, REPLACED, ANOMALY }

/**
 * Where a person stands in one tenant before any key is judged (ADR 0111 decisions 12 and 15, `tenantTrust.ts`):
 * JOINED — their join record opened, verifies owner → invite → newcomer, and its signer is an owner the server lists and
 * this device anchors: the chain is walked from the keys it names; ROOT — an owner the server lists with no join record,
 * judged by the pin; UNANCHORED — anyone else, never accepted; UNAVAILABLE — this device could not read the tenant, so a
 * grant's granter is judged by the pin alone, as on the web.
 */
sealed interface Standing {
    class Joined(val identityPublicKey: ByteArray, val secretKeyProofPublicKey: ByteArray) : Standing
    data object Root : Standing
    data object Unanchored : Standing
    data object Unavailable : Standing
}

/** A key served without a head beside it (a grant's snapshot of its granter's key): its [anchor], and whether it is a
 *  RETIRED key of the chain (an earlier step's), which opens a grant only at an epoch this device already held. */
class ChainKeyAnchor(val anchor: Anchor, val retired: Boolean)

/** A person's key as a directory serves it, with the head of their chain beside it (`ScopeMember`). */
class ServedIdentity(val identityPublicKey: String, val keyChainHead: String)

/**
 * The key checks for a key another account holds, a port of `anchorChainKey` and `anchorIdentity` of `identityPins.ts`.
 * A person's standing in the tenant is asked before the lock is taken, since judging it may check a signer's key in turn;
 * the check itself, which may write the pin store, runs one at a time per account, as the web serializes it.
 */
internal class Anchoring(
    private val suite: V1,
    private val trust: Trust,
    private val api: TrustApi,
    private val pins: PinStore,
    private val now: () -> Long,
) {
    private val lock = Mutex()

    /** [anchorIdentity] for a key served without a head; with [tenant], the person's standing there decides first. */
    suspend fun anchorChainKey(userId: String, identityPublicKeyB64: String, tenant: TenantView?): ChainKeyAnchor {
        val unanchored = ChainKeyAnchor(Anchor.ANOMALY, false)
        val standing = tenant?.standing(userId)
        if (standing == Standing.Unanchored) return unanchored
        return lock.withLock {
            val store = pins.load()
            val pin = store.pins[userId]
            val fingerprint = fingerprintOrNull(identityPublicKeyB64) ?: return@withLock unanchored
            if (standing is Standing.Joined) {
                val chain = api.keyChain(userId) ?: return@withLock unanchored
                val reading = readJoinedChain(pin, userId, chain, standing) ?: return@withLock unanchored
                val at = reading.fingerprints.lastIndexOf(fingerprint)
                if (at < reading.joinedAt) return@withLock unanchored
                pins.mutate(store) { recordReading(it, userId, pin, reading) }
                return@withLock ChainKeyAnchor(reading.anchor, at < reading.fingerprints.size - 1)
            }
            if (pin != null && pin.fingerprint == fingerprint) return@withLock ChainKeyAnchor(Anchor.MATCH, false)
            val chain = api.keyChain(userId) ?: return@withLock unanchored
            val reading = readChain(pin, userId, chain) ?: return@withLock unanchored
            val at = reading.fingerprints.lastIndexOf(fingerprint)
            if (at < 0) return@withLock unanchored
            pins.mutate(store) { recordReading(it, userId, pin, reading) }
            ChainKeyAnchor(reading.anchor, at < reading.fingerprints.size - 1)
        }
    }

    /**
     * The check before trusting a key a directory serves with its head: MATCH is local; the chain is read only when the
     * served head differs from the pinned one, or on first use. With [tenant], UNANCHORED and UNAVAILABLE are an anomaly
     * with no fetch, and a JOINED person's chain is always read from the step their record names.
     */
    suspend fun anchorIdentity(userId: String, served: ServedIdentity, tenant: TenantView?): Anchor {
        val standing = tenant?.standing(userId)
        if (standing == Standing.Unanchored || standing == Standing.Unavailable) return Anchor.ANOMALY
        return lock.withLock {
            val store = pins.load()
            val pin = store.pins[userId]
            val servedFingerprint = fingerprintOrNull(served.identityPublicKey) ?: return@withLock Anchor.ANOMALY
            if (standing is Standing.Joined) {
                val chain = api.keyChain(userId) ?: return@withLock Anchor.ANOMALY
                val reading = readJoinedChain(pin, userId, chain, standing)
                if (reading == null || !endsAt(reading, served, servedFingerprint)) return@withLock Anchor.ANOMALY
                pins.mutate(store) { recordReading(it, userId, pin, reading) }
                return@withLock reading.anchor
            }
            if (pin != null && pin.head == served.keyChainHead) return@withLock if (pin.fingerprint == servedFingerprint) Anchor.MATCH else Anchor.ANOMALY
            val chain = api.keyChain(userId) ?: return@withLock Anchor.ANOMALY
            val reading = readChain(pin, userId, chain)
            if (reading == null || !endsAt(reading, served, servedFingerprint)) return@withLock Anchor.ANOMALY
            pins.mutate(store) { recordReading(it, userId, pin, reading) }
            reading.anchor
        }
    }

    private fun endsAt(r: Reading, served: ServedIdentity, servedFingerprint: String) =
        r.head == served.keyChainHead && r.fingerprints.last() == servedFingerprint

    private fun fingerprintOrNull(b64Key: String): String? = runCatching { b64(trust.fingerprintHash(unb64(b64Key))) }.getOrNull()

    /** A chain read against a pin: the anchor, the newest head and index, the fingerprint each step installs, and — read
     *  from a join record — the index of the step the person joined with (no key before it counts in that tenant). */
    class Reading(val anchor: Anchor, val head: String, val index: Int, val fingerprints: List<String>, val joinedAt: Int = 0)

    private fun links(chain: KeyChain): List<Trust.KeyChainLink>? = runCatching {
        chain.steps.map {
            Trust.KeyChainLink(
                it.index.toLong(), it.previousHead?.let(::unb64), unb64(it.identityPublicKey), unb64(it.secretKeyProofPublicKey),
                it.identitySignature?.let(::unb64), it.proofSignature?.let(::unb64),
            )
        }
    }.getOrNull()

    private fun fingerprints(links: List<Trust.KeyChainLink>) =
        links.map { l -> runCatching { b64(trust.fingerprintHash(l.identityPublicKey)) }.getOrDefault("") }

    /**
     * Walks a served chain from [pin]'s head (from its genesis for an unpinned contact): every step verified, the walk
     * passing the pinned head with the pinned key at it, the chain's own head the one its steps make. Null for anything
     * else (an anomaly). Pure; never a throw.
     */
    fun readChain(pin: Trust.Pin?, userId: String, chain: KeyChain): Reading? {
        if (chain.userId != userId) return null
        val links = links(chain) ?: return null
        val from = pin?.let { runCatching { unb64(it.head) }.getOrNull() ?: return null }
        val walked = trust.verifyKeyChain(userId, links, from) as? Trust.KeyChainVerdict.Ok ?: return null
        val head = b64(walked.head)
        if (head != chain.head) return null
        val fingerprints = fingerprints(links)
        if (pin != null && (walked.fromIndex == null || fingerprints[walked.fromIndex] != pin.fingerprint)) return null
        val anchor = when {
            pin == null -> Anchor.FIRST_USE
            walked.fromIndex == walked.index -> Anchor.MATCH
            else -> Anchor.ADVANCED
        }
        return Reading(anchor, head, walked.index, fingerprints)
    }

    /**
     * Walks a served chain from the step a join record names (`readJoinedChain`): the whole chain verifies, one step
     * installs exactly the identity AND proof key the record names, and the pin stays the counter — a pinned head on the
     * chain must carry the pinned key (MATCH or ADVANCED from it); a pinned head NOT on it is REPLACED when the chain is at
     * least as long as the pinned one was, an anomaly otherwise (a shorter chain may be a rollback). Null for an anomaly.
     */
    fun readJoinedChain(pin: Trust.Pin?, userId: String, chain: KeyChain, joined: Standing.Joined): Reading? {
        if (chain.userId != userId) return null
        val links = links(chain) ?: return null
        val walked = trust.verifyKeyChain(userId, links, null) as? Trust.KeyChainVerdict.Ok ?: return null
        val head = b64(walked.head)
        if (head != chain.head) return null
        val joinedAt = links.indexOfFirst {
            it.identityPublicKey.contentEquals(joined.identityPublicKey) && it.secretKeyProofPublicKey.contentEquals(joined.secretKeyProofPublicKey)
        }
        if (joinedAt < 0) return null
        val fingerprints = fingerprints(links)
        fun reading(anchor: Anchor) = Reading(anchor, head, walked.index, fingerprints, joinedAt)
        if (pin == null) return reading(Anchor.FIRST_USE)
        val at = stepHeads(userId, links).indexOf(pin.head)
        if (at >= 0) {
            if (fingerprints[at] != pin.fingerprint) return null
            return reading(if (at == walked.index) Anchor.MATCH else Anchor.ADVANCED)
        }
        return if (pin.index >= 0 && pin.index <= walked.index) reading(Anchor.REPLACED) else null
    }

    /** The head each step of a chain makes, base64; only for links [Trust.verifyKeyChain] accepted. */
    private fun stepHeads(userId: String, links: List<Trust.KeyChainLink>): List<String> =
        links.mapIndexed { i, link ->
            b64(
                trust.keyChainHead(
                    if (i == 0) trust.keyChainGenesisStatement(userId, link)
                    else trust.keyChainStepStatement(userId, i.toLong(), link.previousHead!!, links[i - 1], link),
                ),
            )
        }

    /** Stores what a reading concluded: a first sight claims only an empty slot, a move or a replacement replaces only the
     *  pin it was judged against (a device that wrote meanwhile keeps its write). MATCH writes nothing. */
    private fun recordReading(pins: MutableMap<String, Trust.Pin>, userId: String, judgedFrom: Trust.Pin?, r: Reading) {
        val cur = pins[userId]
        val newest = r.fingerprints.last()
        val unmoved = judgedFrom != null && (cur == null || cur.head == judgedFrom.head)
        when (r.anchor) {
            Anchor.FIRST_USE -> if (cur == null) pins[userId] = Trust.Pin(newest, r.head, r.index.toLong(), now(), false)
            Anchor.ADVANCED -> if (unmoved) pins[userId] = Trust.Pin(newest, r.head, r.index.toLong(), judgedFrom!!.firstSeenAt, judgedFrom.replaced)
            Anchor.REPLACED -> if (unmoved) pins[userId] = Trust.Pin(newest, r.head, r.index.toLong(), now(), true)
            else -> Unit
        }
    }
}

/**
 * One tenant's standings, read once per opening of the tenant (`tenantTrust.ts` § `loadTenantView`, ADR 0111 decisions
 * 12, 15 and 21): its member list and every join record in it, opened with the tenant's key at its epoch. [members] is
 * null when this device could not open the tenant or list it: everyone then stands UNAVAILABLE.
 *
 * A person with a join record stands JOINED when the record opened and verifies, names another member the server lists
 * as an OWNER as its signer, and that signer's key in it is anchored in this tenant in turn. When that key is a retired
 * one of the signer's chain, or not the signer's served current key, the record counts only with the signer's
 * endorsement under their served current key, that key anchored too. A cycle of signers anchors nobody.
 */
internal class TenantView(
    private val tenantId: String,
    private val members: Map<String, ScopeMember>?,
    private val records: Map<String, JoinRecords.JoinRecord?>,
    private val joins: JoinRecords,
    private val suite: V1,
    private val anchoring: Anchoring?,
) {
    private val memo = HashMap<String, Standing>()
    private val visiting = HashSet<String>()

    suspend fun standing(userId: String): Standing = memo[userId] ?: judge(userId).also { memo[userId] = it }

    private suspend fun judge(userId: String): Standing {
        val all = members ?: return Standing.Unavailable
        val m = all[userId] ?: return Standing.Unanchored
        if (m.pendingConsent == true) return Standing.Unanchored
        if (m.sealedJoinRecord == null) return if (m.role == ScopeMember.Role.OWNER) Standing.Root else Standing.Unanchored
        val record = records[userId] ?: return Standing.Unanchored
        if (!joins.verifies(tenantId, userId, record)) return Standing.Unanchored
        val signer = all[record.signerUserId]
        if (signer == null || signer.pendingConsent == true || signer.role != ScopeMember.Role.OWNER || record.signerUserId == userId) return Standing.Unanchored
        val anchoring = anchoring ?: return Standing.Unanchored
        if (!visiting.add(userId)) return Standing.Unanchored
        try {
            val signerKey = b64(record.signerIdentityPublicKey)
            val judged = anchoring.anchorChainKey(record.signerUserId, signerKey, this)
            if (judged.anchor == Anchor.ANOMALY) return Standing.Unanchored
            if ((judged.retired || signerKey != signer.identityPublicKey) && !endorsedBySigner(m, record, signer, anchoring)) return Standing.Unanchored
        } finally {
            visiting.remove(userId)
        }
        return Standing.Joined(record.identityPublicKey, record.secretKeyProofPublicKey)
    }

    /** [m]'s endorsement verifies under [signer]'s served current key over [record] as this view opened it, and that key is
     *  anchored in this tenant. False for anything malformed. */
    private suspend fun endorsedBySigner(m: ScopeMember, record: JoinRecords.JoinRecord, signer: ScopeMember, anchoring: Anchoring): Boolean {
        val endorsement = m.joinEndorsement?.let { runCatching { unb64(it) }.getOrNull() } ?: return false
        val signKey = runCatching { suite.deserializeIdentityPublic(unb64(signer.identityPublicKey)).sign }.getOrNull() ?: return false
        if (!joins.endorsementVerifies(signKey, tenantId, m.userId, signer.userId, joins.encode(record), endorsement)) return false
        return anchoring.anchorIdentity(signer.userId, ServedIdentity(signer.identityPublicKey, signer.keyChainHead), this) != Anchor.ANOMALY
    }

    companion object {
        /** A view of a tenant this device could not open or list. */
        fun unavailable(tenantId: String, suite: V1) = TenantView(tenantId, null, emptyMap(), JoinRecords(suite), suite, null)

        /** The view of a tenant whose key ([tenantKey], at [epoch]) this device opened and whose [members] it listed. */
        fun of(tenantId: String, members: List<ScopeMember>, tenantKey: ByteArray, epoch: Long, suite: V1, anchoring: Anchoring): TenantView {
            val joins = JoinRecords(suite)
            val records = members.filter { it.sealedJoinRecord != null }.associate { m ->
                m.userId to runCatching {
                    joins.open(tenantId, m.userId, tenantKey, epoch, unb64(m.wrappedJoinRecordValueKey ?: return@runCatching null), unb64(m.sealedJoinRecord!!))
                }.getOrNull()
            }
            return TenantView(tenantId, members.associateBy { it.userId }, records, joins, suite, anchoring)
        }
    }
}
