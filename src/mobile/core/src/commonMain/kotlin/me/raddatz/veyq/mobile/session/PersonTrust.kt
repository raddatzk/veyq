package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.api.apis.TenantsApi
import me.raddatz.veyq.mobile.api.models.MemberVerification
import me.raddatz.veyq.mobile.api.models.PutVerificationRequest
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.crypto.Verifications
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.trust.SafetyNumbers

/**
 * A person's safety number on their page and the compare that verifies them (uc.spaces.enforce-verified-identities, ADR
 * 0028 decision 3, ADR 0111 decisions 12–15, ADR 0148 decision 3): a port of `tenantTrust.ts` (`personInTenant`,
 * `countVerifications`, `verifyPerson`, `myChainHead`) and of what `PersonSafetyNumber.vue` and `MySafetyNumber.vue` show.
 *
 * What a page shows of another member is the person as THIS tenant anchors them: the key and head the directory serves,
 * once their join record and key chain reach them (or, for a root owner, the pin). A match of the compare — a scan of
 * their `veyqid:2` code or "They match" after reading the digits — is one action: [verify] signs "I, at my head,
 * confirmed you at yours" and writes it, sealed under the tenant's current epoch, into this account's own records there,
 * where every member reads it. Display only: nothing here gates anybody's write. A safety number is no secret.
 */
class PersonTrust internal constructor(private val tenant: TenantSession) {
    private val account = tenant.account
    private val suite = account.suite
    private val trust: Trust = account.trustOfAccount
    private val verifications = Verifications(suite)
    private val tenantId = tenant.tenant.tenantId

    /** One's own safety number: the digits, the chain code and the QR's text (drawn by the core's `QrEncoder`). */
    @Serializable
    data class Mine(val number: String, val chainCode: String, val payload: String)

    /** What another member's page shows (decision 15). */
    @Serializable
    data class Shown(
        val userId: String,
        /** Not anchored in this tenant: shown with its note, never compared to accept ("can't confirm"). */
        val anomaly: Boolean,
        /** The digits on screen: the anchored key's, or for an anomaly the directory's (an in-person compare). */
        val number: String?,
        val chainCode: String?,
        /** First seen by this account under 14 days ago. */
        val isNew: Boolean,
        /** The names of the members whose verification of the current head counts (this account's not among them). */
        val verifiedBy: List<String>,
        /** This account's own verification of the current head counts ("you"). */
        val verifiedByMe: Boolean,
        /** When their chain's newest step — a rotation the chain reaches — was made (epoch ms), or null. */
        val rotatedAt: Long?,
        /** A join record replaced a pin that was not theirs. */
        val replaced: Boolean,
    ) {
        /** [rotatedAt] as a host across the bridge shows it (`Shown.moment`); derived, so a copy keeps it true. */
        val rotatedShown: me.raddatz.veyq.mobile.words.Written? = rotatedAt?.let { me.raddatz.veyq.mobile.words.Written.moment(it) }

        val verified: Boolean get() = verifiedByMe || verifiedBy.isNotEmpty()
        val status: Status get() = when {
            anomaly -> Status.CANT_CONFIRM
            verified -> Status.VERIFIED
            isNew -> Status.NEW
            else -> Status.NOT_VERIFIED
        }
    }

    enum class Status { NOT_VERIFIED, VERIFIED, NEW, CANT_CONFIRM }

    /** What a compare came to. */
    enum class Outcome {
        /** The verification was written. */
        VERIFIED,
        /** A scan or the digits did not match: nothing written. */
        MISMATCH,
        /** Not a veyq safety code. */
        FOREIGN,
        /** A `veyqid:1` code: the other app is too old to show its head. */
        OUTDATED,
        /** The key or head moved since the page was read: nothing signed. */
        MOVED,
        /** Not anchored in this tenant: nothing signed. */
        UNANCHORED,
        /** The write did not come through. */
        FAILED,
    }

    /** What a compare is about: the anchored key's fingerprint hash and the head beside it (base64). */
    private class Compared(val fingerprint: String, val head: String)

    private var compared: Pair<String, Compared>? = null

    /** One's own safety number, from this account's chain as served — refused (null) when it does not end at this vault's
     *  identity, so a compare never shows a head the server withheld a step from. */
    suspend fun mine(): Mine? {
        val head = myChainHead() ?: return null
        val hash = trust.fingerprintHash(account.myPublic)
        return Mine(SafetyNumbers.fingerprintDigits(hash), SafetyNumbers.chainCodeDigits(unb64(head)), SafetyNumbers.payload(hash, unb64(head)))
    }

    /** [userId] as this tenant anchors them; null for one the directory lists no key for (a former member). */
    suspend fun person(userId: String): Shown? {
        val (view, members) = view() ?: return null
        val m = members[userId] ?: return null
        val anchor = runCatching { account.anchoringOfAccount.anchorIdentity(userId, ServedIdentity(m.identityPublicKey, m.keyChainHead), view) }
            .getOrElse { if (it is CancellationException) throw it else Anchor.ANOMALY }
        val pin = runCatching { account.pinOf(userId) }.getOrNull()
        val directoryNumber = runCatching { SafetyNumbers.fingerprintDigits(trust.fingerprintHash(unb64(m.identityPublicKey))) }.getOrNull()
        val chainCode = runCatching { SafetyNumbers.chainCodeDigits(unb64(m.keyChainHead)) }.getOrNull()
        if (anchor == Anchor.ANOMALY) {
            compared = null
            return Shown(userId, true, directoryNumber, chainCode, false, emptyList(), false, null, false)
        }
        val fingerprint = b64(trust.fingerprintHash(unb64(m.identityPublicKey)))
        compared = userId to Compared(fingerprint, m.keyChainHead)
        val chain = runCatching { account.keyChainOf(userId) }.getOrNull()
        val newest = chain?.steps?.maxByOrNull { it.index }
        val rotatedAt = if (chain != null && newest != null && newest.index >= 1 && chain.head == m.keyChainHead) epochMs(newest.createdAt) else null
        val counted = runCatching { verifiedBy(view, members, listOf(m)) }.getOrDefault(emptyMap())[userId].orEmpty()
        return Shown(
            userId = userId,
            anomaly = false,
            number = directoryNumber,
            chainCode = chainCode,
            isNew = pin != null && now() - pin.firstSeenAt < NEW_FOR_MS,
            verifiedBy = counted.filter { it != account.userId }.mapNotNull { tenant.people[it]?.displayName },
            verifiedByMe = account.userId in counted,
            rotatedAt = rotatedAt,
            replaced = pin?.replaced == true,
        )
    }

    /** The members whose verification of [subjectUserId]'s served head counts here — one's own page included, which shows
     *  no state: what the live case reads to see each member read the other's row. */
    internal suspend fun countedOf(subjectUserId: String): List<String> {
        val (view, members) = view() ?: return emptyList()
        val m = members[subjectUserId] ?: return emptyList()
        return verifiedBy(view, members, listOf(m))[subjectUserId].orEmpty()
    }

    /** A scan of [userId]'s code against what [person] last read of them; a match verifies them. */
    suspend fun compareScan(userId: String, text: String): Outcome {
        val c = compared?.takeIf { it.first == userId }?.second ?: return Outcome.UNANCHORED
        return when (SafetyNumbers.compareScan(text, c.fingerprint, c.head)) {
            SafetyNumbers.Verdict.FOREIGN -> Outcome.FOREIGN
            SafetyNumbers.Verdict.OUTDATED -> Outcome.OUTDATED
            SafetyNumbers.Verdict.MISMATCH -> Outcome.MISMATCH
            SafetyNumbers.Verdict.MATCH -> verify(userId)
        }
    }

    /** The digits read aloud: "They match" verifies [userId], "They don't match" writes nothing. */
    suspend fun compareDigits(userId: String, match: Boolean): Outcome = if (match) verify(userId) else Outcome.MISMATCH

    /**
     * `verifyPerson`: the compared key and head must still be what the directory serves and what this device anchors
     * here (else MOVED or UNANCHORED, nothing signed); the statement names this account's head as its chain ends at this
     * vault's identity, sealed under a fresh value key wrapped under the tenant's current key — a `409 stale_epoch` opens
     * the tenant anew and seals once more.
     */
    suspend fun verify(userId: String): Outcome {
        val c = compared?.takeIf { it.first == userId }?.second ?: return Outcome.UNANCHORED
        return try {
            val (view, members) = view() ?: return Outcome.FAILED
            val m = members[userId]
            if (m == null || m.keyChainHead != c.head || b64(trust.fingerprintHash(unb64(m.identityPublicKey))) != c.fingerprint) return Outcome.MOVED
            if (account.anchoringOfAccount.anchorIdentity(userId, ServedIdentity(m.identityPublicKey, m.keyChainHead), view) == Anchor.ANOMALY) return Outcome.UNANCHORED
            val myHead = myChainHead() ?: return Outcome.FAILED
            val address = Verifications.Address(tenantId, account.userId, userId)
            val statement = verifications.statement(address, unb64(c.head), unb64(myHead))
            val signed = Verifications.Verification(unb64(c.head), unb64(myHead), account.identitySign(statement))
            if (put(address, signed)) Outcome.VERIFIED else Outcome.FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.FAILED
        }
    }

    /** One write, and once more under the tenant opened anew where the server answered `409 stale_epoch`. */
    private suspend fun put(address: Verifications.Address, signed: Verifications.Verification): Boolean {
        val held = tenant.scopeKey(tenantId) ?: return false
        var key = held.first
        var epoch = held.second.toLong()
        var reopened: ByteArray? = null
        try {
            repeat(2) { attempt ->
                val sealed = JoinRecords(suite).sealValueField(tenantId, epoch, key, verifications.context(address, epoch), verifications.encode(signed))
                val r = account.respond {
                    TenantsApi(tenant.base, account.http).putMyVerification(
                        tenantId, address.subjectUserId, PutVerificationRequest(epoch.toInt(), b64(sealed.sealed), b64(sealed.wrappedValueKey)),
                    )
                }
                if (r.success) return true
                if (attempt > 0 || r.status != 409 || account.errorCode(r) != STALE_EPOCH) return false
                val again = account.reopenScope(tenant.tenant, tenantId) ?: return false
                reopened = again.first
                key = again.first
                epoch = again.second
            }
            return false
        } finally {
            reopened?.fill(0)
        }
    }

    /** This account's own head (base64) as its chain is served — walked, and required to end at this vault's identity. */
    private suspend fun myChainHead(): String? {
        val chain = runCatching { account.keyChainOf(account.userId) }.getOrNull() ?: return null
        val links = account.anchoringOfAccount.links(chain) ?: return null
        val walked = trust.verifyKeyChain(account.userId, links, null) as? Trust.KeyChainVerdict.Ok ?: return null
        if (!walked.keys.identityPublicKey.contentEquals(account.myPublic) || b64(walked.head) != chain.head) return null
        return chain.head
    }

    /** The tenant's standings and members, read anew (as the web's `loadTenantView` each page). */
    private suspend fun view(): Pair<TenantView, Map<String, ScopeMember>>? {
        val (key, epoch) = tenant.scopeKey(tenantId) ?: return null
        val members = account.members(tenant.base, tenantId, tenantId) ?: return null
        return TenantView.of(tenantId, members, key, epoch.toLong(), suite, account.anchoringOfAccount) to members.associateBy { it.userId }
    }

    /** `countVerifications` over [subjects]' rows: subject id → the verifier ids whose verification of the current head counts. */
    private suspend fun verifiedBy(view: TenantView, members: Map<String, ScopeMember>, subjects: List<ScopeMember>): Map<String, List<String>> {
        val (key, epoch) = tenant.scopeKey(tenantId) ?: return emptyMap()
        val rows = listAll(subjects.singleOrNull()?.userId)
        val myHead = myChainHead()
        return VerificationCount(suite, tenantId, account.userId, account.myPublic)
            .count(rows, subjects.associate { it.userId to it.keyChainHead }, members, key, epoch.toLong(), myHead) { verifierId, verifier ->
            account.anchoringOfAccount.anchorIdentity(verifierId, ServedIdentity(verifier.identityPublicKey, verifier.keyChainHead), view) != Anchor.ANOMALY
        }
    }

    private suspend fun listAll(subjectUserId: String?): List<MemberVerification> {
        val out = mutableListOf<MemberVerification>()
        var after: String? = null
        while (true) {
            val page = account.call { TenantsApi(tenant.base, account.http).listVerifications(tenantId, subjectUserId, null, after, PAGE) }
            out += page.verifications
            val last = page.verifications.lastOrNull()
            if (page.more != true || last == null) return out
            after = "${last.verifierUserId}:${last.subjectUserId}"
        }
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun epochMs(iso: String): Long? = runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrNull()

    internal companion object {
        /** A person reads as *new* for this long after this account first pinned them (ADR 0111 decision 15). */
        const val NEW_FOR_MS = 14L * 24 * 60 * 60 * 1000
        const val PAGE = 1000
        const val STALE_EPOCH = "stale_epoch"
    }
}

/** `countVerifications` (ADR 0111 decisions 12–14), apart from the session so its rules are tested over real seals. */
internal class VerificationCount(
    private val suite: me.raddatz.veyq.mobile.crypto.V1,
    private val tenantId: String,
    private val selfUserId: String,
    private val selfPublic: ByteArray,
) {
    /**
     * Which rows count (`countVerifications`): a row opens at its slot, names the subject's CURRENT head ([anchoredHeads]),
     * and verifies under the verifier's CURRENT key at the verifier's current head — this account at its own head
     * ([myHead]), anyone else anchored in this tenant ([anchored]). Nothing else: a statement by a retired key is not the
     * current key's.
     */
    suspend fun count(
        rows: List<MemberVerification>, anchoredHeads: Map<String, String>, members: Map<String, ScopeMember>,
        tenantKey: ByteArray, tenantEpoch: Long, myHead: String?, anchored: suspend (String, ScopeMember) -> Boolean,
    ): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        val verifierOk = HashMap<String, Boolean>()
        val joins = JoinRecords(suite)
        val verifications = Verifications(suite)
        for (row in rows) {
            val subjectHead = anchoredHeads[row.subjectUserId] ?: continue
            val verifier = members[row.verifierUserId] ?: continue
            val address = Verifications.Address(tenantId, row.verifierUserId, row.subjectUserId)
            val ctx = verifications.context(address, row.epoch.toLong())
            val plain = runCatching {
                joins.openValueField(tenantId, tenantEpoch, tenantKey, ctx, unb64(row.wrappedValueKey), unb64(row.sealedStatement))
            }.getOrNull() ?: continue
            val v = verifications.decode(plain) ?: continue
            if (b64(v.subjectHead) != subjectHead) continue
            val (verifierKey, verifierHead) = if (row.verifierUserId == selfUserId) {
                b64(selfPublic) to (myHead ?: continue)
            } else {
                val ok = verifierOk[row.verifierUserId] ?: runCatching { anchored(row.verifierUserId, verifier) }.getOrDefault(false)
                    .also { verifierOk[row.verifierUserId] = it }
                if (!ok) continue
                verifier.identityPublicKey to verifier.keyChainHead
            }
            if (b64(v.verifierHead) != verifierHead) continue
            val signKey = runCatching { suite.deserializeIdentityPublic(unb64(verifierKey)).sign }.getOrNull() ?: continue
            if (!verifications.verifies(address, v, signKey)) continue
            out.getOrPut(row.subjectUserId) { mutableListOf() } += row.verifierUserId
        }
        return out
    }

}
