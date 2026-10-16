package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestIdentityUsage
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.sharing.ShareRetiredIdEntity
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.scope.ScopeEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/**
 * One node of a shared subtree (ADR 0080 Decisions 2 and 9). A descendant carries its directory entry — its name and
 * its content-key wrap, both under its parent's content key, which the guest derives from the root down. The root
 * carries none of its own placement: no parent id, no name, no wrap — those belong to the parent's key above the
 * share; the guest takes the root's key from the share `wrap` and its title from `sealedName`.
 */
data class ShareNodeRow(
    val id: String,
    /** The parent within the subtree, or null at the shared root (its content key comes from the share `wrap`). */
    val parentId: String?,
    val kind: String,
    val orderIndex: Int,
    /** The content-key epoch the node was sealed at — a descendant's content-key wrap is bound to it (ADR 0072). */
    val epoch: Int,
    /** A descendant's content key wrapped under its parent's; null at the root. */
    val wrappedContentKey: String?,
    /** A descendant's name sealed under its parent's content key; null at the root. */
    val encryptedName: String?,
    /** The placement revision a descendant's name and wrap are sealed at (ADR 0080 Decision 3); null at the root,
     *  whose entry is not served. */
    val placementRevision: Long?,
    val wrappedMeta: String?,
    /** The shared root's drop private key sealed under its content key — every reader of the subtree opens uploads through it. */
    val sealedDropPrivateKey: String?,
    /** An identity folder's identity below the root (ADR 0080 decision 11), else null — never at the root, whose entry is
     *  not served. */
    val shareIdentityId: String? = null,
    /** With [shareIdentityId]: the identity's wrapped `K_up`, which the folder's entry opens through. */
    val wrappedIdentityKey: String? = null,
)

/**
 * What a share hands its guest, fixed by its role (ADR 0031 point 7): a READ or READ_WRITE share wraps the shared
 * node's content key under `K_share`; a WRITE_ONLY share carries the node's drop public key under `K_share` instead,
 * and the node's first WRITE_ONLY share also brings the drop private key sealed under its content key, which the node
 * keeps.
 */
sealed interface ShareKeys {
    data class MemoryWrap(val wrap: String) : ShareKeys
    data class Drop(val sealedPublicKey: String, val sealedPrivateKey: String?) : ShareKeys
}

/** A blob to presign for a viewer: its object-store prefix + chunk count. */
data class ShareBlobRef(val storageKey: String, val chunkCount: Int)

/**
 * Persistence for public viewer shares (cr-0100). Operator-blind: stores the opaque share row + reads the
 * already-opaque node/blob rows to assemble the sealed subtree. The **subtree** is the authorization grain — the
 * guest resolve returns only the nodes under the shared node, an application's guest read only its rows under the
 * live nodes the view gate hands it ([liveSubtreeIds]), and a blob GET is authorized iff the blob is attached under
 * that subtree (a structural id check, never a name or content).
 */
@ApplicationScoped
class SharingStore(
    /** The blobs hung under a shared node (today: core's attachments — a Memory's cover among them — and Memories' media + derivatives). Merged without order. */
    private val contributors: Instance<SharedSubtreeContributor>,
    /** What still names a guest identity (today: core's attachments, Memories' media + buckets). Combined with "any". */
    private val identityUsages: Instance<GuestIdentityUsage>,
    private val tree: NodesStore,
) {

    fun findNode(nodeId: String): NodeEntity? = NodeEntity.findById(nodeId)

    enum class CreateResult { CREATED, ID_TAKEN, DROP_KEY_EXISTS, DROP_KEY_MISSING }

    /** Persist a share under the client-minted [id] — 256 random bits, base64url, the unguessable half of the
     *  capability (ADR 0072 keeps its form). `ID_TAKEN`, nothing written, if the id was ever used ([idTaken]). A WRITE_ONLY
     *  share's drop private key is set on the shared node **once**, by a conditional update in this transaction: a
     *  second one would orphan every upload sealed to the first, so a node that already has one is
     *  `DROP_KEY_EXISTS` (the client re-reads the node), and a request with none for a node with none is
     *  `DROP_KEY_MISSING` — nothing written in either case. */
    @Transactional
    fun create(
        id: String, ownerAccount: String, scopeId: String, nodeId: String, role: String,
        keys: ShareKeys, sealedName: String, expiresAt: Instant?, sealedNote: String?, sealedShareKey: String?,
        sealedShareKeyEpoch: Int?, sealedShareName: String?,
        passwordRequired: Boolean, pwSalt: String?, pwVerifier: String?, sealedPassword: String?, writeVerifier: String,
    ): CreateResult {
        if (idTaken(id)) return CreateResult.ID_TAKEN
        if (keys is ShareKeys.Drop) {
            if (keys.sealedPrivateKey != null) {
                val set = NodeEntity.update(
                    "sealedDropPrivateKey = ?1 where id = ?2 and sealedDropPrivateKey is null", keys.sealedPrivateKey, nodeId,
                )
                if (set != 1) return CreateResult.DROP_KEY_EXISTS
                tree.bumpTree(scopeId) // the root's row now carries the key (`NodeSummary.sealedDropPrivateKey`): a change of the listing (ADR 0081); the scope lock is held (SharingHandler.create)
            } else if (NodeEntity.findById(nodeId)?.sealedDropPrivateKey == null) {
                return CreateResult.DROP_KEY_MISSING
            }
        }
        persistShare(
            id, ownerAccount, scopeId, nodeId, role, keys, sealedName, expiresAt, sealedNote, sealedShareKey,
            sealedShareKeyEpoch, sealedShareName, passwordRequired, pwSalt, pwVerifier, sealedPassword, writeVerifier,
        )
        return CreateResult.CREATED
    }

    /** A share id is never minted twice (ADR 0029 §7): a row holds it — live or a tombstone — or the scope purge
     *  retired it when it erased the row. */
    fun idTaken(id: String): Boolean = ShareEntity.findById(id) != null || ShareRetiredIdEntity.findById(id) != null

    enum class ReissueResult { CREATED, ID_TAKEN, NOT_FOUND, DELETED }

    /** Re-issue [oldId] under the client-minted [newId] (ADR 0075): persist the new share — the old one's scope,
     *  node and role, everything else from the request — and delete the old one ([tombstone], ADR 0029 §7), in
     *  **one** transaction: a replaced link has nothing left to come back for.
     *
     *  A share is re-issued **at most once** (inbox #252), spent by a checked compare-and-set (developer-styleguide
     *  §12): the first write is `deleted` set where it is still false, and its row count decides. Of two concurrent
     *  re-issues the second's update waits on the first's row lock, then matches nothing — `DELETED`, nothing
     *  written, exactly what a later re-issue of it gets. The old row is read only **after** the spend, under the
     *  row lock that update holds, so a concurrent edit cannot slip a role change between the read and the
     *  tombstone. `ID_TAKEN`, nothing written, when [newId] was ever used — checked before the spend on purpose: a
     *  re-issue that deleted the old link and then failed would leave the curator with no link at all, believing
     *  they had a new one. Never touches the shared node's drop key — a WRITE_ONLY re-issue seals to the one it already
     *  has. */
    @Transactional
    fun reissue(
        oldId: String, newId: String, ownerAccount: String, keys: ShareKeys, sealedName: String, expiresAt: Instant?,
        sealedNote: String?, sealedShareKey: String, sealedShareKeyEpoch: Int, sealedShareName: String?,
        passwordRequired: Boolean, pwSalt: String?, pwVerifier: String?, sealedPassword: String?, writeVerifier: String,
    ): ReissueResult {
        if (idTaken(newId)) return ReissueResult.ID_TAKEN
        val spent = ShareEntity.update("deleted = true where id = ?1 and deleted = false", oldId)
        if (spent != 1) return if (ShareEntity.findById(oldId) == null) ReissueResult.NOT_FOUND else ReissueResult.DELETED
        val old = ShareEntity.findById(oldId)!! // the spent row, read under the update's lock
        tombstone(old)
        persistShare(
            newId, ownerAccount, old.scopeId, old.nodeId, old.role, keys, sealedName, expiresAt, sealedNote, sealedShareKey,
            sealedShareKeyEpoch, sealedShareName, passwordRequired, pwSalt, pwVerifier, sealedPassword, writeVerifier,
        )
        return ReissueResult.CREATED
    }

    private fun persistShare(
        id: String, ownerAccount: String, scopeId: String, nodeId: String, role: String,
        keys: ShareKeys, sealedName: String, expiresAt: Instant?, sealedNote: String?, sealedShareKey: String?,
        sealedShareKeyEpoch: Int?, sealedShareName: String?,
        passwordRequired: Boolean, pwSalt: String?, pwVerifier: String?, sealedPassword: String?, writeVerifier: String,
    ) {
        ShareEntity().apply {
            this.id = id
            this.ownerAccount = ownerAccount
            this.scopeId = scopeId
            this.nodeId = nodeId
            this.role = role
            this.wrap = (keys as? ShareKeys.MemoryWrap)?.wrap
            this.sealedDropPublicKey = (keys as? ShareKeys.Drop)?.sealedPublicKey
            this.sealedName = sealedName
            this.enabled = true
            this.expiresAt = expiresAt
            this.sealedNote = sealedNote
            this.sealedShareKey = sealedShareKey
            this.sealedShareKeyEpoch = sealedShareKeyEpoch
            this.sealedShareName = sealedShareName
            this.passwordRequired = passwordRequired // cr-0118 (ADR 0037): the access-control gate + reveal copy
            this.pwSalt = pwSalt
            this.pwVerifier = pwVerifier
            this.sealedPassword = sealedPassword
            this.writeVerifier = writeVerifier // ADR 0076: for every role — a READ link can become a READ_WRITE one
            // ADR 0075 point 4: the removals this scope has seen so far — a later removal leaves the share below it.
            // Read in this transaction: a removal that commits concurrently is either counted here or makes the
            // share look older than it is, never younger.
            this.memberRemovalsAtMint = ScopeEntity.findById(scopeId)?.memberRemovals ?: 0
            createdAt = Instant.now()
        }.persist()
    }

    /** The 3-way result of an in-place share edit (cr-0119): applied, no such owned share, or an illegal role change. */
    enum class UpdateResult { DONE, NOT_FOUND, INVALID_ROLE }

    /** Edit a share the caller owns in place (cr-0115/cr-0116/cr-0119/cr-0118; idempotent). Full-replaces the two
     *  editable, `K_share`-sealed fields (note + name — a null value clears that field); a non-null `role` changes
     *  the grant **in place** (READ ↔ READ_WRITE only — a role of, or a share currently in, WRITE_ONLY is
     *  `INVALID_ROLE`); a non-null `passwordRequired` is the tri-state password patch (true sets/changes it with the
     *  fields, false removes + clears them; null leaves it untouched). Ownership is checked first, so a share not
     *  owned by the caller is `NOT_FOUND` regardless of the role (non-enumerable, mirroring `revoke`). */
    @Transactional
    fun updateShare(
        ownerAccount: String, shareId: String, sealedNote: String?, sealedShareName: String?, role: String?,
        expiresAt: Instant?, passwordRequired: Boolean?, pwSalt: String?, pwVerifier: String?, sealedPassword: String?,
    ): UpdateResult {
        val share = liveForUpdate(shareId)?.takeIf { it.ownerAccount == ownerAccount } ?: return UpdateResult.NOT_FOUND
        if (role != null) {
            if (role == ShareEntity.WRITE_ONLY || share.role == ShareEntity.WRITE_ONLY) return UpdateResult.INVALID_ROLE
            share.role = role
        }
        if (passwordRequired != null) { // cr-0118: tri-state — set/change (true) or remove (false); absent = untouched
            share.passwordRequired = passwordRequired
            share.pwSalt = if (passwordRequired) pwSalt else null
            share.pwVerifier = if (passwordRequired) pwVerifier else null
            share.sealedPassword = if (passwordRequired) sealedPassword else null
        }
        share.sealedNote = sealedNote
        share.sealedShareName = sealedShareName
        share.expiresAt = expiresAt // full replace (cr-share-link-expiry-edit) — the manage UI re-sends the current value
        return UpdateResult.DONE
    }

    /** Replace a live share's `sealed_name` (ADR 0080 decision 14) — that column and no other, so a re-seal can never
     *  revert a concurrent edit, revoke or re-activation (`@DynamicUpdate` writes only what changed). False when there
     *  is no such share, or it is deleted: the row lock ([liveForUpdate]) makes a delete that committed first win.
     *
     *  **Lock order: the share row only** — never the scope row. `updateShare` and `revoke` take the same single lock;
     *  `reactivate` takes the scope row, then this row. Holding one lock, this write can wait for a re-activation but
     *  never hold what one waits for while it waits itself, so no order of the two deadlocks
     *  (`ShareSealedNameCoreSliceTest`). Taking the scope row after this one would be the inversion. */
    @Transactional
    fun setSealedName(shareId: String, sealedName: String): Boolean {
        val share = liveForUpdate(shareId) ?: return false
        share.sealedName = sealedName
        return true
    }

    /** A share that exists and is not deleted — every reader's lookup (ADR 0029 §7): a tombstone is not found,
     *  to the guest and to every curator operation alike. */
    fun find(shareId: String): ShareEntity? = ShareEntity.findById(shareId)?.takeIf { !it.deleted }

    /** A share row, a tombstone included — for the ONE reader that answers a deleted share differently from an
     *  unknown one: a re-issue, whose member caller gets `share_deleted` and re-reads the list (inbox #252). Its
     *  caller gates scope membership first, so a non-member still cannot tell the two apart. */
    fun findIncludingDeleted(shareId: String): ShareEntity? = ShareEntity.findById(shareId)

    /** Whether a share is enabled, and who owns it, tombstones included — by a scalar read that loads no entity, for
     *  a re-issue that re-checks them under the scope lock before [reissue] spends the row: a row loaded first would
     *  stay in the transaction's context, and the spend's own read would hand back that pre-spend copy. */
    fun enabledAndOwnerOf(shareId: String): Pair<Boolean, String>? =
        ShareEntity.getEntityManager()
            .createQuery("select s.enabled, s.ownerAccount from ShareEntity s where s.id = :id", Array<Any>::class.java)
            .setParameter("id", shareId).resultList.firstOrNull()
            ?.let { (it[0] as Boolean) to (it[1] as String) }

    /** [find] under a row lock, for every write to a share but [reissue], whose compare-and-set takes the same row
     *  lock as its first write. Without the lock an edit that read the row before a delete committed would write its
     *  own ciphertext onto the tombstone, and a re-activation would turn it back on; locked, it waits for the delete
     *  and then finds no share (`ShareDeleteRaceSliceTest`, red without the lock). */
    fun liveForUpdate(shareId: String): ShareEntity? =
        ShareEntity.findById(shareId, LockModeType.PESSIMISTIC_WRITE)?.takeIf { !it.deleted }

    /**
     * Whether the share still takes a guest's write — live and enabled — read under the row lock a delete takes
     * first ([delete]), refreshed in case this transaction already holds a copy. A guest's write calls it first in
     * the transaction that writes: a delete drops each identity nothing references any more ([dropDeadIdentities]),
     * counting when it runs, so a write gated only before it committed landed after the count — an upload whose
     * identity was gone, or an identity on a dead link nothing would drop (`GuestWriteRaceSliceTest`).
     */
    fun admitsGuests(shareId: String): Boolean {
        val share = ShareEntity.find("id", shareId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
            ?.also { ShareEntity.getEntityManager().refresh(it) } ?: return false
        return !share.deleted && share.enabled
    }

    /** The scope of a live share, by a scalar read that loads no entity — for a caller that must lock the scope
     *  before it loads the share ([liveForUpdate] on an entity already loaded would hand back that unrefreshed copy). */
    fun liveScopeOf(shareId: String): String? =
        ShareEntity.getEntityManager()
            .createQuery("select s.scopeId from ShareEntity s where s.id = :id and s.deleted = false", String::class.java)
            .setParameter("id", shareId).resultList.firstOrNull()

    fun listByOwner(ownerAccount: String): List<ShareEntity> =
        ShareEntity.find("ownerAccount = ?1 and deleted = false", ownerAccount).list()

    /** Every share on a node, any owner — for the shared-scope manage panel (cr-0104). The handler gates it on
     *  scope membership; the store just reads the node's shares. A deleted one is in no listing. */
    fun listByNode(nodeId: String): List<ShareEntity> =
        ShareEntity.find("nodeId = ?1 and deleted = false", nodeId).list()

    /** Soft-revoke a share the caller owns (idempotent). Returns false when no such share is owned by the caller. */
    @Transactional
    fun revoke(ownerAccount: String, shareId: String): Boolean {
        val share = liveForUpdate(shareId)?.takeIf { it.ownerAccount == ownerAccount } ?: return false
        share.enabled = false
        return true
    }

    /** Re-activate a soft-revoked share and adopt its ownership (cr-0104): flip `enabled` back on and take
     *  `ownerAccount`. The handler has already let the caller through the scope-access gate, in this transaction and
     *  under the scope's lock ([SharingHandler.reactivate]). False when there is no such share. */
    @Transactional
    fun reactivate(callerAccount: String, shareId: String): Boolean {
        val share = liveForUpdate(shareId) ?: return false
        share.enabled = true
        share.ownerAccount = callerAccount
        return true
    }

    /** Delete a share (ADR 0029 §7) — [tombstone] it. The handler has already decided the caller may. False when
     *  there is no such share, or it is deleted already. */
    @Transactional
    fun delete(shareId: String): Boolean {
        val share = liveForUpdate(shareId) ?: return false
        tombstone(share)
        return true
    }

    /**
     * The ONE body of every share deletion (ADR 0029 §7) — a delete, a re-issue's old link, the purge of the node
     * it is on. Off, `deleted` set, and every ciphertext column and the expiry cleared: what stays is the id (so
     * nothing is ever minted under it again), the scope, node, role and creation time, and the foreign key the link's guest
     * identities hang off. Never touches the identities' wrapped keys, the application rows under the node (Memories' media and
     * buckets), or its drop key: an uploader guest's uploads open only through them, and they belong to the node, not to the link — an
     * identity goes only once nothing of the guest's remains ([dropDeadIdentities]). Runs inside its caller's
     * transaction.
     */
    fun tombstone(share: ShareEntity) {
        share.enabled = false
        share.deleted = true
        share.wrap = null
        share.sealedDropPublicKey = null
        share.sealedName = null
        share.sealedNote = null
        share.sealedShareName = null
        share.sealedShareKey = null
        share.sealedShareKeyEpoch = null
        share.passwordRequired = false
        share.pwSalt = null
        share.pwVerifier = null
        share.sealedPassword = null
        share.writeVerifier = null
        share.expiresAt = null
        dropDeadIdentities(ShareIdentityEntity.find("shareId", share.id).list().map { it.id })
    }

    /** Delete every share on the erased nodes ([tombstone] each, ADR 0029 §7) — the node purge's call: a link on a
     *  node that no longer exists has nothing left to serve. Runs inside the purge's transaction. */
    fun tombstoneAllOn(nodeIds: Collection<String>) {
        if (nodeIds.isEmpty()) return
        ShareEntity.find("nodeId in ?1 and deleted = false", nodeIds).withLock(LockModeType.PESSIMISTIC_WRITE).list()
            .forEach { tombstone(it) }
    }

    /**
     * Delete each of the guest identities [candidateIds] that nothing can use or open any more (inbox #229, ADR 0029
     * §7) — all three at once: its link is a tombstone, so its token is no session (every guest write starts at the
     * active share); and no [GuestIdentityUsage] still names it — core's attachments, and an application's rows (Memories:
     * a medium, live or trashed, whose wrapped key is the only way to open an uploader guest's upload, or a bucket). An
     * open upload session does not hold it: it can never complete once the link is gone, and the stale-session sweep
     * reclaims its objects by storage key alone. Candidates are passed in — the caller knows which identities it
     * just orphaned; this never scans the table. Runs inside its caller's transaction.
     */
    fun dropDeadIdentities(candidateIds: Collection<String>) {
        for (id in candidateIds.toSet()) {
            val identity = ShareIdentityEntity.findById(id) ?: continue
            if (ShareEntity.findById(identity.shareId)?.deleted != true) continue
            if (!GuestIdentityUsage.unreferenced(identityUsages, id)) continue
            ShareIdentityEntity.deleteById(id)
        }
    }

    /** Soft-revoke every enabled share a removed member created in a scope (cr-0104) — their links then 404 on
     *  the next resolve/blob GET. Idempotent; other members' shares + the record shape are untouched. */
    @Transactional
    fun deactivateSharesByOwnerInScope(ownerAccount: String, scopeId: String): Int =
        ShareEntity.update(
            "enabled = false where ownerAccount = ?1 and scopeId = ?2 and enabled = true",
            ownerAccount, scopeId,
        )

    /** Turn off **every** enabled share of a scope, whoever created it, and mark **every** share of it `reissue_only`
     *  — the one body of every bulk switch-off (ADR 0075 point 5): a group removal's and a tenant removal's
     *  `revokeLinks`, and a scope owner's standalone switch-off. Every member could rebuild every link, so a link may
     *  have leaked whoever made it; each owner re-issues what they still want, and the mark keeps re-activation from
     *  reviving one. A link its owner had already switched off is marked too (inbox #258): it may have leaked as
     *  well, and unmarked, one with no recovery copy came back with one click right after the switch-off. A deleted
     *  link is left alone — it never comes back anyway. Returns how many it turned off. Runs inside its caller's
     *  transaction. */
    @Transactional
    fun turnOffAllInScope(scopeId: String): Int {
        val turnedOff = ShareEntity.update("enabled = false, reissueOnly = true where scopeId = ?1 and enabled = true", scopeId)
        ShareEntity.update("reissueOnly = true where scopeId = ?1 and reissueOnly = false and deleted = false", scopeId)
        return turnedOff
    }

    // --- contributor identities (cr-0101) --------------------------------------

    /** Does any guest identity already hold the client-minted [id]? */
    fun identityIdTaken(id: String): Boolean = ShareIdentityEntity.findById(id) != null

    /** Create an anonymous share identity under the client-minted [id]: store the sealed name (+ a WRITE_ONLY
     *  identity's wrapped key) + the token hash, return the token — or null, nothing written, if any identity
     *  already holds [id]. */
    @Transactional
    fun createIdentity(id: String, shareId: String, sealedDisplayName: String, wrappedIdentityKey: String?): String? {
        if (identityIdTaken(id)) return null
        val token = Tokens.random() // the unguessable contributor capability token handed to the client
        ShareIdentityEntity().apply {
            this.id = id
            this.shareId = shareId
            this.sealedDisplayName = sealedDisplayName
            this.wrappedIdentityKey = wrappedIdentityKey
            this.tokenHash = Tokens.hash(token)
        }.persist()
        return token
    }

    /** The contributor identity for this share whose token matches, or null (a bad/absent token → no identity). */
    fun findIdentity(shareId: String, contributorToken: String): ShareIdentityEntity? {
        val hash = Tokens.hash(contributorToken)
        return ShareIdentityEntity.find("shareId = ?1 and tokenHash = ?2", shareId, hash).firstResult()
    }

    /** The sealed subtree rooted at [rootNodeId] within [scopeId]: its live nodes, for the content-key chain. Empty
     *  when the shared root itself is trashed. */
    fun subtree(scopeId: String, rootNodeId: String): List<ShareNodeRow> {
        val live = liveSubtree(scopeId, rootNodeId)
        // An identity folder's entry opens through its identity's K_up (ADR 0080 decision 11): served beside it.
        val identityKeys = live.mapNotNull { it.shareIdentityId }.takeIf { it.isNotEmpty() }
            ?.let { ids -> ShareIdentityEntity.find("id in ?1 and wrappedIdentityKey is not null", ids.distinct()).list() }
            ?.associate { it.id to it.wrappedIdentityKey!! }.orEmpty()
        return live.map { n ->
            // The root's entry (parent id, name, wrap) is sealed under the parent's key above the share: none of it
            // is served (ADR 0080 Decision 9). Each descendant's is — the guest opens it with the chain it derives.
            val root = n.id == rootNodeId
            ShareNodeRow(
                id = n.id,
                parentId = if (root) null else n.parentId,
                kind = n.kind,
                orderIndex = n.orderIndex,
                epoch = n.epoch,
                wrappedContentKey = if (root) null else n.wrappedContentKey,
                encryptedName = if (root) null else n.encryptedName,
                placementRevision = if (root) null else n.placementRevision,
                wrappedMeta = n.wrappedMeta,
                sealedDropPrivateKey = n.sealedDropPrivateKey,
                shareIdentityId = if (root) null else n.shareIdentityId,
                wrappedIdentityKey = if (root) null else n.shareIdentityId?.let(identityKeys::get),
            )
        }
    }

    /** `node|slot|blob` of every attachment on [nodeIds] (a subtree's live nodes), sorted — the guest resolve's tag input
     *  (ADR 0081): a file's upload completing or being replaced changes no node row, and a guest sees it. */
    fun attachmentTags(scopeId: String, nodeIds: List<String>): List<String> =
        if (nodeIds.isEmpty()) emptyList()
        else NodeAttachmentEntity.find("scopeId = ?1 and nodeId in ?2", scopeId, nodeIds).list()
            .map { "${it.nodeId}|${it.slot}|${it.blobId}" }.sorted()

    /** The ids of [subtree]'s live nodes, by the same predicate — what the view gate hands an application
     *  (`ShareGuestGate.viewGate`). Empty when the shared root itself is trashed. */
    fun liveSubtreeIds(scopeId: String, rootNodeId: String): List<String> = liveSubtree(scopeId, rootNodeId).map { it.id }

    /** The subtree rooted at [rootNodeId], live nodes only, in [collectSubtreeIds]' order. */
    private fun liveSubtree(scopeId: String, rootNodeId: String): List<NodeEntity> {
        val subtreeIds = collectSubtreeIds(scopeId, rootNodeId)
        // `deletedAt is null` — a soft-deleted node (uc.memory.delete-node, ADR 0061) keeps its ROW, unlike the
        // hard delete this replaced, so without this predicate the guest resolve serves the full sealed subtree
        // of a node the user deleted. Its sibling in [subtreeBlobIds] authorizes the blob GET and carries the
        // predicate SEPARATELY on purpose: either one covering for the other is how a later change to the other
        // reopens the hole silently. `ShareDeletedNodeSliceTest` bites each on its own.
        val nodeById = NodeEntity.find("scopeId = ?1 and deletedAt is null", scopeId).list().associateBy { it.id }
        return subtreeIds.mapNotNull { nodeById[it] }
    }

    /**
     * The blob (object-store prefix + chunk count) to presign for a viewer — **iff** [blobId] is attached under
     * the shared subtree (an attachment on a subtree node — a Memory's cover is one — or an application's own arm,
     * Memories' media and derivatives). Null otherwise (→ a non-enumerable 404). A structural id check; no name or
     * content is read.
     */
    fun authorizedBlob(scopeId: String, rootNodeId: String, blobId: String): ShareBlobRef? {
        if (blobId !in subtreeBlobIds(scopeId, rootNodeId)) return null
        val blob = BlobEntity.findById(blobId)?.takeIf { it.scopeId == scopeId } ?: return null
        return ShareBlobRef(blob.storageKey, blob.chunkCount)
    }

    /** Every blob id attached under the subtree: the contributors' (attachments, live media originals + derivatives). */
    private fun subtreeBlobIds(scopeId: String, rootNodeId: String): Set<String> {
        val subtreeIds = collectSubtreeIds(scopeId, rootNodeId)
        // `deletedAt is null` — the sibling of [subtree]'s predicate, deliberately its OWN read (ADR 0061).
        // Filtering only the listing leaves a node invisible in the share and still fully downloadable by
        // anyone holding a blobId, unauthenticated, for the whole retention window.
        val liveNodes = NodeEntity.find("scopeId = ?1 and deletedAt is null", scopeId).list().filter { it.id in subtreeIds }
        if (liveNodes.isEmpty()) return emptySet() // the shared root itself is trashed
        val liveIds = liveNodes.map { it.id }
        val ids = HashSet<String>()
        // Every arm is a contributor's (each with its own live predicate): core's attachments, an application's own.
        contributors.forEach { ids.addAll(it.authorizedBlobIds(scopeId, liveIds)) }
        return ids
    }

    /** BFS the node tree within the scope from [rootNodeId], collecting the root + every descendant id. */
    private fun collectSubtreeIds(scopeId: String, rootNodeId: String): List<String> {
        // `deletedAt is null` on the DESCENDANTS. The seed root is deliberately not filtered here: whether the
        // shared root itself is live is decided by each caller's own predicate above, so removing one of those
        // reddens exactly its own guest path instead of both at once.
        val byParent = NodeEntity.find("scopeId = ?1 and deletedAt is null", scopeId).list().groupBy { it.parentId }
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque(listOf(rootNodeId))
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!out.add(cur)) continue
            byParent[cur]?.forEach { stack.addLast(it.id) }
        }
        return out.toList()
    }
}
