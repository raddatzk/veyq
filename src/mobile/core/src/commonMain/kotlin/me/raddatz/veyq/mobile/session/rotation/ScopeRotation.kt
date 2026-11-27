package me.raddatz.veyq.mobile.session.rotation

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CancellationException
import me.raddatz.veyq.mobile.api.apis.ContentApi
import me.raddatz.veyq.mobile.api.apis.RekeyApi
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.infrastructure.HttpResponse
import me.raddatz.veyq.mobile.api.models.InviteGrantEntry
import me.raddatz.veyq.mobile.api.models.OpenInvite
import me.raddatz.veyq.mobile.api.models.RotateScopeRequest
import me.raddatz.veyq.mobile.api.models.ScopeGrantEntry
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.api.models.ScopeRekeyMember
import me.raddatz.veyq.mobile.api.models.ScopeRekeyMemberWrap
import me.raddatz.veyq.mobile.api.models.ScopeRekeyParts
import me.raddatz.veyq.mobile.api.models.ScopeRekeyRewrap
import me.raddatz.veyq.mobile.api.models.ScopeRekeyRootEntry
import me.raddatz.veyq.mobile.api.models.ScopeRekeyRootNode
import me.raddatz.veyq.mobile.api.models.ScopeRekeyShareWrap
import me.raddatz.veyq.mobile.api.models.ScopeRekeyStage
import me.raddatz.veyq.mobile.api.models.ScopeRekeyInviteLabelWrap
import me.raddatz.veyq.mobile.api.models.ScopeRekeyVerificationWrap
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.crypto.ValueKeys
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.session.Anchor
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.ServedIdentity
import me.raddatz.veyq.mobile.session.TenantView
import me.raddatz.veyq.mobile.session.VeyqAccount

/** The server refused a call of a rotation with [status] and its error [code]. */
class RotationRefused(val status: Int, val code: String?) : Exception("HTTP $status${code?.let { " $it" } ?: ""}")

/** A scope's epoch moved on between reading it and committing its next one — another member's commit (the web's). */
class EpochMovedOn(val scopeId: String) : Exception("epoch_moved_on: $scopeId")

/** An opened scope a rotation is built on: its tenant, kind, the epoch of the one key it holds, and that key (the caller
 *  of the opener owns it and zeroes it). */
@PlatformApi
@HiddenFromObjC
class OpenedScope(val scopeId: String, val tenantId: String, val kind: String, val epoch: Long, val key: ByteArray)

/** Opens the scope a rotation is built on — the caller's own grant, at whatever epoch it lags (`E_n`); null where it does
 *  not open. The web's `RotationOpener` and `TenantOpener`. */
internal typealias RotationOpener = suspend (scopeId: String) -> OpenedScope?

/**
 * An application's part of a rotation's scope-level move (the web's `ScopeRekeyParticipant`, ADR 0115 decision 2):
 * every wrap of its own under the scope key, re-wrapped by [ScopeRekeyContext.rewrap] and staged under the rotation's
 * re-key id. Reached only through `AppKinds.scopeRekeyParticipants` — core names no application's tables.
 */
fun interface ScopeRekeyParticipant {
    suspend fun stage(context: ScopeRekeyContext)
}

/** What a [ScopeRekeyParticipant] is handed: the scope, its origin, the re-key id, the epoch moved from, and the re-wrap. */
class ScopeRekeyContext internal constructor(
    @PlatformApi
    @HiddenFromObjC
    val account: VeyqAccount,
    val base: String,
    val scopeId: String,
    val rekeyId: String,
    val fromEpoch: Int,
    private val rewrapOne: (Context, String) -> String,
) {
    /** [wrapped]'s `K_v` of [value] moved to the new key, or a stand-in nobody opens where it does not open (`rewrapValueKey`). */
    fun rewrap(value: Context, wrapped: String): String = rewrapOne(value, wrapped)

    /** One call of the participant: the response, or [RotationRefused] for any other answer than a 2xx. */
    @PlatformApi
    @HiddenFromObjC
    suspend fun <T : Any> send(f: suspend () -> HttpResponse<T>): HttpResponse<T> = sent(account, f)
}

internal suspend fun <T : Any> sent(account: VeyqAccount, f: suspend () -> HttpResponse<T>): HttpResponse<T> {
    val r = account.respond(f)
    if (!r.success) throw RotationRefused(r.status, account.errorCode(r))
    return r
}

/**
 * A scope-key rotation (ADR 0115), the port of `scopes.ts` `commitRotation` and `scopeRekey.ts`: open the caller's own
 * grant (`E_n`), mint a fresh `E_{n+1}` that replaces it, re-grant the members — and for a TENANT carry every open member
 * invite —, re-wrap every scope-level value key and re-seal the scope-root entries under the new key and stage them
 * ([stageScopeLevelRekey], decision 2), sign the succession from `E_n` (decision 5) and commit stating the epoch it
 * creates. A `409 stale_epoch` / `rotation_incomplete` or a `403 grant_behind` is rebuilt [REBUILDS] time from a re-opened
 * grant over the members read again — one rebuild, never a loop —; a `409 rekey_parts_stale` / `rekey_parts_missing` is
 * staged again under the same key and id at most [RESTAGES] times. The key is minted inside the attempt and zeroed in
 * `finally`, with the opened one; the epoch committed is held ([VeyqAccount.holdEpochKey]).
 *
 * Every member whose key is anchored is re-granted, the rotator itself unconditionally to the identity it holds; every
 * other one (an anomaly, judged in the scope's tenant) is declared pending (ADR 0111 decisions 9, 10, 15).
 */
@PlatformApi
@HiddenFromObjC
class ScopeRotation(
    private val account: VeyqAccount,
    private val participants: List<ScopeRekeyParticipant>,
    /** A kind's slug-token input (`AppKinds.tokenInput`): a root entry's token is recomputed under the new key. */
    private val tokenInput: (kind: String, name: String) -> String?,
) {
    private val suite = account.suite
    private val p = account.primitives
    private val trust = Trust(suite)
    private val joins = JoinRecords(suite)
    private val values = ValueKeys(suite)

    /**
     * Commits [scopeId]'s next epoch: built first from [members] (the list the caller read), a rebuild from the members
     * read again. [open] opens the caller's grant ([planOpener] for the finish), [openTenant] the scope's TENANT to read who
     * is anchored there. Returns the epoch committed; throws the refusal of the last attempt.
     *
     * With [removes] it is a removal-rotation (the web's `rotateGroupForRemoval`): that member is carried in no grant and
     * the server deletes the membership with the commit (`removesUserId`); [revokeLinks] turns off every link of the scope
     * in the same transaction (ADR 0075).
     *
     * [excluding] (the web's `excluding`, a tenant's new lock after a removal) is dropped from the list and from every
     * re-read by the caller's own choice, however the server lists them, but never sent as a `removesUserId`: the new
     * epoch is never wrapped to them, and a server that kept their membership meets the rotation's cover check.
     */
    suspend fun commitRotation(
        scopeId: String, members: List<ScopeMember>, open: RotationOpener, openTenant: RotationOpener,
        removes: String? = null, revokeLinks: Boolean = false, excluding: String? = null,
    ): Long {
        var attempt = 0
        var base: String? = null
        while (true) {
            // the rebuild reads the directory again: the answer that sent it here may be a membership the list lacks
            val listed = (if (attempt == 0) members else sent(account) { ScopesApi(base!!, account.http).listScopeMembers(scopeId) }.body())
                .let { listedFor(it, removes, excluding) }
            val opened = open(scopeId) ?: throw IllegalStateException("cannot open scope to rotate")
            base = account.originOfTenant(opened.tenantId)
            val newKey = p.randomBytes(32)
            try {
                return attempt(scopeId, base, listed, opened, newKey, openTenant, removes, revokeLinks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // superseded or incomplete: re-read, re-open and rebuild from a fresh key, a bounded number of times
                if (attempt < REBUILDS && isRebuildable(e)) {
                    attempt++
                    continue
                }
                throw e
            } finally {
                newKey.fill(0)
                opened.key.fill(0)
            }
        }
    }

    private suspend fun attempt(
        scopeId: String, base: String, listed: List<ScopeMember>, opened: OpenedScope, newKey: ByteArray, openTenant: RotationOpener,
        removes: String? = null, revokeLinks: Boolean = false,
    ): Long {
        val epoch = opened.epoch + 1
        val grants = ArrayList<ScopeGrantEntry>()
        val pendingUserIds = ArrayList<String>()
        fun grantTo(userId: String, identityB64: String) {
            val g = account.sealGrant(newKey, scopeId, opened.kind, epoch, userId, identityB64)
            grants += ScopeGrantEntry(userId, g.wrappedGrant, g.grantSignature, g.recipientIdentityDigest)
        }
        // who is anchored is judged in the scope's tenant; a PERSONAL scope has no other member to judge
        val tenant = if (opened.kind == "PERSONAL") null else loadTenantView(base, opened.tenantId, openTenant)
        for (m in listed) {
            if (m.userId == account.userId) grantTo(m.userId, account.identityPublicB64)
            else if (account.anchoringOfAccount.anchorIdentity(m.userId, ServedIdentity(m.identityPublicKey, m.keyChainHead), tenant) == Anchor.ANOMALY) pendingUserIds += m.userId
            else grantTo(m.userId, m.identityPublicKey)
        }
        val invites = if (opened.kind == "TENANT" && tenant != null) carryOpenInvites(base, scopeId, newKey, opened.kind, epoch, tenant) else Invites(emptyList(), emptyList())
        // the succession statement: the new key's succession public key, signed by the old key's
        val (successionPublic, newSeed) = trust.successionKeyPair(newKey)
        newSeed.fill(0)
        val oldSeed = trust.successionKeyPair(opened.key).second
        val successionSignature = try {
            p.ed25519Sign(oldSeed, trust.scopeSuccessionStatement(scopeId, epoch, successionPublic))
        } finally {
            oldSeed.fill(0)
        }
        val rekeyId = NodeCreate.uuid(account)
        val request = RotateScopeRequest(
            epoch = epoch.toInt(), successionPublicKey = b64(successionPublic), successionSignature = b64(successionSignature), rekeyId = rekeyId,
            grants = grants, pendingUserIds = pendingUserIds, inviteGrants = invites.grants, pendingInviteIds = invites.pending,
            removesUserId = removes, revokeLinks = if (revokeLinks) true else null,
        )
        val run = Run(base, scopeId, rekeyId, opened.epoch, opened.key, epoch, newKey)
        var restage = 0
        var rotated: Int
        while (true) {
            try {
                stageScopeLevelRekey(run)
                rotated = sent(account) { ScopesApi(base, account.http).rotateScope(scopeId, request) }.body().epoch
                break
            } catch (e: RotationRefused) {
                // a row written while this rotation staged: list it again and stage again, under the same key and id
                if (restage < RESTAGES && isRestageable(e)) {
                    restage++
                    continue
                }
                throw e
            }
        }
        // this device made the epoch, so it holds it: a later epoch's succession must start from this key
        account.holdEpochKey(scopeId, epoch, newKey)
        return rotated.toLong()
    }

    /** The web's `loadTenantView` over [openTenant]: the TENANT opened and its members listed, else a view of nothing. */
    private suspend fun loadTenantView(base: String, tenantId: String, openTenant: RotationOpener): TenantView {
        val opened = try {
            openTenant(tenantId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return TenantView.unavailable(tenantId, suite)
        try {
            val rows = try {
                sent(account) { ScopesApi(base, account.http).listScopeMembers(tenantId) }.body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return TenantView.unavailable(tenantId, suite)
            }
            return TenantView.of(tenantId, rows, opened.key, opened.epoch, suite, account.anchoringOfAccount)
        } finally {
            opened.key.fill(0)
        }
    }

    private class Invites(val grants: List<InviteGrantEntry>, val pending: List<String>)

    /**
     * A tenant's open member invites carried into the new epoch (ADR 0111 decision 2): each whose key its owner signed,
     * under a signer key this device anchors, re-granted like a member — to its invite key, the invite's id as recipient —;
     * any other declared pending. Sequential: the pin store's writes must not race.
     */
    private suspend fun carryOpenInvites(base: String, scopeId: String, newKey: ByteArray, kind: String, epoch: Long, tenant: TenantView): Invites {
        val grants = ArrayList<InviteGrantEntry>()
        val pending = ArrayList<String>()
        for (open in sent(account) { ScopesApi(base, account.http).listOpenInvites(scopeId) }.body()) {
            if (inviteVouched(scopeId, open, tenant)) {
                val g = account.sealGrant(newKey, scopeId, kind, epoch, open.inviteId, open.invitePublicKey)
                grants += InviteGrantEntry(open.inviteId, g.wrappedGrant, g.grantSignature, g.recipientIdentityDigest)
            } else {
                pending += open.inviteId
            }
        }
        return Invites(grants, pending)
    }

    /** Whether an open invite's key is the one its owner signed, under a signer key anchored here. False for anything
     *  malformed: every byte came from the server. */
    private suspend fun inviteVouched(tenantId: String, open: OpenInvite, tenant: TenantView): Boolean = try {
        val signer = suite.deserializeIdentityPublic(unb64(open.signerIdentityPublicKey))
        val expiresAt = kotlin.time.Instant.parse(open.expiresAt).epochSeconds
        val statement = joins.inviteKeyStatement(tenantId, open.inviteId, unb64(open.invitePublicKey), expiresAt)
        // only a signature that verified may pin its signer's key
        p.ed25519Verify(signer.sign, statement, unb64(open.inviteKeySignature)) &&
            account.anchoringOfAccount.anchorIdentity(open.signerUserId, ServedIdentity(open.signerIdentityPublicKey, open.signerKeyChainHead), tenant) != Anchor.ANOMALY
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** One staging run: the scope, its origin, the re-key id, and both keys with their epochs. */
    internal class Run(
        val base: String, val scopeId: String, val rekeyId: String,
        val oldEpoch: Long, val oldKey: ByteArray, val newEpoch: Long, val newKey: ByteArray,
    )

    /**
     * Stages every scope-level move under [Run.rekeyId] (`stageScopeLevelRekey`): core's, page by page from
     * `listScopeRekeyParts`, then each application's through its [ScopeRekeyParticipant]. Staging twice replaces what the
     * first staged. A wrap or a root entry that does not open under the old key is staged as one nobody opens.
     */
    internal suspend fun stageScopeLevelRekey(run: Run) {
        val api = RekeyApi(run.base, account.http)
        val kindOf = rootKinds(run.base, run.scopeId)
        var since: String? = null
        var first = true
        while (true) {
            val page = sent(account) { api.listScopeRekeyParts(run.scopeId, since, STAGE_BATCH) }.body()
            stageOf(run, page, kindOf, first)?.let { stage -> sent(account) { api.stageScopeRekey(run.scopeId, run.rekeyId, stage) } }
            first = false
            if (!page.more || page.cursor == null) break
            since = page.cursor
        }
        val context = ScopeRekeyContext(account, run.base, run.scopeId, run.rekeyId, run.oldEpoch.toInt()) { value, wrapped ->
            values.rewrap(run.oldKey, run.oldEpoch, run.newKey, run.newEpoch, value, wrapped)
        }
        for (participant in participants) participant.stage(context)
    }

    /** The kind of every live scope-root node, read from the tree (the parts listing serves none): once per run, and once
     *  more for a root created since. */
    private fun rootKinds(base: String, scopeId: String): suspend (String) -> String {
        var kinds: Map<String, String>? = null
        var reread = false
        suspend fun read() = sent(account) { ContentApi(base, account.http).listNodes(scopeId) }.body()
            .filter { it.parentId == null }.associate { it.id to it.kind }
        return { nodeId ->
            var k = kinds ?: read().also { kinds = it }
            if (nodeId !in k && !reread) {
                reread = true
                k = read().also { kinds = it }
            }
            k[nodeId] ?: throw IllegalStateException("scope rekey: the root node $nodeId is not in the tree")
        }
    }

    /** One listed page moved into the stage that carries it, or null for a page with nothing to move. */
    private suspend fun stageOf(run: Run, page: ScopeRekeyParts, kindOf: suspend (String) -> String, first: Boolean): ScopeRekeyStage? {
        val scopeId = run.scopeId
        fun rewrap(value: Context, wrapped: String) =
            ScopeRekeyRewrap(values.rewrap(run.oldKey, run.oldEpoch, run.newKey, run.newEpoch, value, wrapped), wrapped)
        val rootNodes = page.rootNodes.map { n -> resealRootEntry(run, n, if (n.trashed) null else kindOf(n.nodeId)) }
        // a wrap nobody opens loses the link its key: its root's node re-key closes it as such (ADR 0114)
        val shares = page.shares.map { s ->
            val r = rewrap(Context("share-key-recovery", "scopeId" to scopeId, "nodeId" to s.nodeId, "shareId" to s.shareId, "epoch" to s.sealedShareKeyEpoch.toLong()), s.wrappedRecoveryValueKey)
            ScopeRekeyShareWrap(s.shareId, r.wrappedValueKey, r.replacedValueKey)
        }
        val members = page.members.map { rewrapMember(scopeId, it, ::rewrap) }
        val verifications = page.verifications.map { v ->
            val r = rewrap(
                Context("member-verification", "scopeId" to scopeId, "userId" to v.verifierUserId, "subjectUserId" to v.subjectUserId, "epoch" to v.epoch.toLong()),
                v.wrappedValueKey,
            )
            ScopeRekeyVerificationWrap(v.verifierUserId, v.subjectUserId, r.wrappedValueKey, r.replacedValueKey)
        }
        // an open link invite's private label (ADR 0129 decision 7): its value key moved, the sealed label left as it is
        val inviteLabels = page.inviteLabels.map { l ->
            val r = rewrap(Context("member-invite-label", "scopeId" to scopeId, "inviteId" to l.inviteId), l.wrappedValueKey)
            ScopeRekeyInviteLabelWrap(l.inviteId, r.wrappedValueKey, r.replacedValueKey)
        }
        // the name and the icon ride on the first page only
        val groupName = page.groupName?.takeIf { first }?.let { rewrap(Context("group-name", "scopeId" to scopeId, "nameEpoch" to it.nameEpoch.toLong()), it.wrappedNameValueKey) }
        val icon = page.icon?.takeIf { first }?.let { rewrap(Context("space-icon", "scopeId" to scopeId, "iconEpoch" to it.iconEpoch.toLong()), it.wrappedIconValueKey) }
        if (groupName == null && icon == null && rootNodes.isEmpty() && shares.isEmpty() && members.isEmpty() && verifications.isEmpty() && inviteLabels.isEmpty()) return null
        return ScopeRekeyStage(
            fromEpoch = run.oldEpoch.toInt(), groupName = groupName, icon = icon, rootNodes = rootNodes, shares = shares,
            members = members, verifications = verifications, inviteLabels = inviteLabels,
        )
    }

    /** A member's profile fields' value keys under the new tenant key, each exactly where the row holds one. */
    private fun rewrapMember(tenantId: String, m: ScopeRekeyMember, rewrap: (Context, String) -> ScopeRekeyRewrap): ScopeRekeyMemberWrap {
        fun at(purpose: String) = Context(purpose, "scopeId" to tenantId, "userId" to m.userId)
        return ScopeRekeyMemberWrap(
            userId = m.userId,
            name = m.wrappedNameValueKey?.let { rewrap(at("member-name"), it) },
            avatar = m.wrappedAvatarValueKey?.let { rewrap(at("member-avatar"), it) },
            joinRecord = m.wrappedJoinRecordValueKey?.let { rewrap(joins.recordContext(tenantId, m.userId), it) },
        )
    }

    /**
     * A scope-root entry under the new key: the node's unchanged key re-wrapped, its name re-sealed and — for a live node —
     * its slug token recomputed, bound to its `scopeEpoch` and the next placement revision. A name or a key wrap that does
     * not open under the old key is staged as one nobody opens ([unopenable]), a name's slug token as one under no held key.
     */
    private fun resealRootEntry(run: Run, n: ScopeRekeyRootNode, kind: String?): ScopeRekeyRootEntry {
        val scopeId = run.scopeId
        fun at(revision: Long) = arrayOf<Pair<String, Any?>>("scopeId" to scopeId, "nodeId" to n.nodeId, "epoch" to n.scopeEpoch.toLong(), "placementRevision" to revision)
        val now = at(n.placementRevision)
        val next = at(n.placementRevision + 1)
        val name = runCatching { suite.openField(Context("node-name", *now), run.oldKey, unb64(n.encryptedName)).decodeToString() }.getOrNull()
        val nodeKey = runCatching { suite.open(Context("content-key", *now), run.oldKey, unb64(n.wrappedContentKey)) }.getOrNull()
        fun token(key: ByteArray, k: String, of: String) =
            b64(suite.slugToken(key, scopeId, tokenInput(k, of) ?: throw IllegalStateException("scope rekey: no slug rule for the kind $k")))
        try {
            return ScopeRekeyRootEntry(
                nodeId = n.nodeId, placementRevision = n.placementRevision, scopeEpoch = n.scopeEpoch,
                encryptedName = if (name != null) b64(suite.sealField(Context("node-name", *next), run.newKey, utf8(name)))
                else unopenable(n.encryptedName) { key, length -> b64(suite.sealField(Context("node-name", *next), key, utf8("x".repeat(length)))) },
                slugToken = when {
                    kind == null -> null
                    name != null -> token(run.newKey, kind, name)
                    else -> unopenable("") { key, _ -> token(key, kind, n.nodeId) }
                },
                wrappedContentKey = if (nodeKey != null) b64(suite.seal(Context("content-key", *next), run.newKey, nodeKey))
                else unopenable(n.wrappedContentKey) { key, length -> b64(suite.seal(Context("content-key", *next), key, p.randomBytes(length))) },
            )
        } finally {
            nodeKey?.fill(0)
        }
    }

    /**
     * The stand-in for a root entry's seal the rotator cannot open (`scopeRekey.ts` `unopenable`): [seal] under a fresh key
     * dropped at once, so it opens under no key anyone holds, of the length of [replaced]. Never random bytes under the new
     * key, which would open as garbage or as a key that is no one's.
     */
    internal fun unopenable(replaced: String, seal: (key: ByteArray, length: Int) -> String): String {
        val key = p.randomBytes(32)
        try {
            val overhead = byteLength(seal(key, 0))
            return seal(key, maxOf(0, byteLength(replaced) - overhead))
        } finally {
            key.fill(0)
        }
    }

    private fun byteLength(b64: String): Int = runCatching { unb64(b64).size }.getOrDefault(0)

    companion object {
        /** How often a rebuildable refusal is rebuilt: once (ADR 0024 decision 4) — a bound, never a loop. */
        const val REBUILDS = 1

        /** How often a commit refused `rekey_parts_stale` / `rekey_parts_missing` is staged again. */
        const val RESTAGES = 2

        /** The parts listing's page, and so each stage's. */
        const val STAGE_BATCH = 256

        /** A rotation built on a superseded epoch, a member list or invites a join changed, or a lagging grant's listing. */
        /** Who a rotation grants to: the list read, without the member it [removes] and the one it is [excluding] — every row
         *  of either, a duplicate included. **Load-bearing:** the new epoch is never wrapped to them. */
        internal fun listedFor(members: List<ScopeMember>, removes: String?, excluding: String?): List<ScopeMember> =
            members.filter { it.userId != removes && it.userId != excluding }

        fun isRebuildable(e: Exception): Boolean = e is RotationRefused && (
            (e.status == 409 && (e.code == "stale_epoch" || e.code == "rotation_incomplete")) || (e.status == 403 && e.code == "grant_behind")
            )

        /** A row written since it was read, or one that appeared since. */
        fun isRestageable(e: RotationRefused): Boolean = e.status == 409 && (e.code == "rekey_parts_stale" || e.code == "rekey_parts_missing")
    }
}
