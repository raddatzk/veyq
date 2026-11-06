package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.backup.ErasureKind
import me.raddatz.veyq.core.backup.ErasureLedger
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.CoreNodeKinds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestIdentityUsage
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.sharing.ShareRetiredIdEntity
import me.raddatz.veyq.core.crypto.SealedValue
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
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
    /** The node's own key's epoch (ADR 0114 decision 3) — what a guest's write under the node states. */
    val epoch: Int,
    /** The scope's epoch when the node's key was minted — a descendant's content-key wrap is bound to its own (ADR 0072). */
    val scopeEpoch: Int,
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
    private val ledger: ErasureLedger,
    /** A measure on a link or a guest identity ends with it (ADR 0093 § 6): [tombstone], [dropDeadIdentities]. */
    private val measures: MeasureTargets,
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
        keys: ShareKeys, sealedName: String, expiresAt: Instant?, sealedNote: String?, recoveryCopy: SealedValue?,
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
            id, ownerAccount, scopeId, nodeId, role, keys, sealedName, expiresAt, sealedNote, recoveryCopy,
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
     *  A share is re-issued **at most once** (inbox #252), spent under the old row's lock (developer-styleguide §12):
     *  the row is read locked and refreshed, and only a row not deleted yet is closed ([close], a delete — ADR 0114
     *  decision 1: an open READ or READ_WRITE link marks its root's subtree due in this transaction, and the new link
     *  follows the root's next key like any open link). Of two concurrent re-issues the second's read waits on the
     *  first's row lock, then finds a tombstone — `DELETED`, nothing written, exactly what a later re-issue of it gets.
     *  A concurrent edit cannot slip a role change between the read and the tombstone: it waits on the same lock.
     *  `ID_TAKEN`, nothing written, when [newId] was ever used — checked before the spend on purpose: a re-issue that
     *  deleted the old link and then failed would leave the curator with no link at all, believing they had a new one.
     *  Never touches the shared node's drop key — a WRITE_ONLY re-issue seals to the one it already has. The caller
     *  holds the scope row's lock (lock order: the scope row, the share rows, the node rows). */
    @Transactional
    fun reissue(
        oldId: String, newId: String, ownerAccount: String, keys: ShareKeys, sealedName: String, expiresAt: Instant?,
        sealedNote: String?, recoveryCopy: SealedValue, sealedShareKeyEpoch: Int, sealedShareName: String?,
        passwordRequired: Boolean, pwSalt: String?, pwVerifier: String?, sealedPassword: String?, writeVerifier: String,
    ): ReissueResult {
        if (idTaken(newId)) return ReissueResult.ID_TAKEN
        val old = lockedRow(oldId) ?: return ReissueResult.NOT_FOUND
        if (old.deleted) return ReissueResult.DELETED
        close(old, CloseKind.DELETE)
        persistShare(
            newId, ownerAccount, old.scopeId, old.nodeId, old.role, keys, sealedName, expiresAt, sealedNote, recoveryCopy,
            sealedShareKeyEpoch, sealedShareName, passwordRequired, pwSalt, pwVerifier, sealedPassword, writeVerifier,
        )
        return ReissueResult.CREATED
    }

    private fun persistShare(
        id: String, ownerAccount: String, scopeId: String, nodeId: String, role: String,
        keys: ShareKeys, sealedName: String, expiresAt: Instant?, sealedNote: String?, recoveryCopy: SealedValue?,
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
            // ADR 0093 § 8: [ownerAccount] is the authenticated caller of a create and of a re-issue — the editor of
            // every sealed field this write sets.
            nameEditorUserId = ownerAccount
            this.enabled = true
            this.expiresAt = expiresAt
            this.sealedNote = sealedNote
            noteEditorUserId = ownerAccount.takeIf { sealedNote != null }
            sealedShareKey = recoveryCopy?.value
            wrappedRecoveryValueKey = recoveryCopy?.wrappedValueKey
            this.sealedShareKeyEpoch = sealedShareKeyEpoch // the mint's epoch too: a rotation leaves it (ADR 0075 point 4)
            this.sealedShareName = sealedShareName
            shareNameEditorUserId = ownerAccount.takeIf { sealedShareName != null }
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

    /** What a reopen stores back on a link (ADR 0114 decision 7): the root's current key under its `K_share` — [keys]
     *  of its role — and the root's current name under it, sealed by [editor]. */
    data class Reopen(val keys: ShareKeys, val sealedName: String, val editor: String)

    /** Edit a share the caller owns in place (cr-0115/cr-0116/cr-0119/cr-0118; idempotent). Full-replaces the two
     *  editable, `K_share`-sealed fields (note + name — a null value clears that field); a non-null `role` changes
     *  the grant **in place** (READ ↔ READ_WRITE only — a role of, or a share currently in, WRITE_ONLY is
     *  `INVALID_ROLE`); a non-null `passwordRequired` is the tri-state password patch (true sets/changes it with the
     *  fields, false removes + clears them; null leaves it untouched). Ownership is checked first, so a share not
     *  owned by the caller is `NOT_FOUND` regardless of the role (non-enumerable, mirroring `revoke`).
     *
     *  The end date decides whether the link is open (ADR 0114 decisions 6 and 7): [reopen] — which the caller passes
     *  exactly when [reopensByDate] holds, having judged the root's epoch — stores the root's key and name back and
     *  clears the sweep's mark; an end date the edit leaves passed on a link whose close was not carried out yet closes
     *  it now ([close], as the sweep would). Joins the caller's transaction, which holds the scope row's lock and then
     *  this share row's. */
    @Transactional
    fun updateShare(
        ownerAccount: String, shareId: String, sealedNote: String?, sealedShareName: String?, role: String?,
        expiresAt: Instant?, passwordRequired: Boolean?, pwSalt: String?, pwVerifier: String?, sealedPassword: String?,
        reopen: Reopen?, now: Instant,
    ): UpdateResult {
        val share = liveForUpdate(shareId)?.takeIf { it.ownerAccount == ownerAccount } ?: return UpdateResult.NOT_FOUND
        check((reopen != null) == reopensByDate(share, expiresAt, now)) { "a reopen is the caller's to decide, under the lock" }
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
        // Both fields are full-replaced, so the caller — the owner, checked above — is the editor of each that is set
        // (ADR 0093 § 8), and a cleared one has none.
        share.sealedNote = sealedNote
        share.noteEditorUserId = ownerAccount.takeIf { sealedNote != null }
        share.sealedShareName = sealedShareName
        share.shareNameEditorUserId = ownerAccount.takeIf { sealedShareName != null }
        share.expiresAt = expiresAt // full replace (cr-share-link-expiry-edit) — the manage UI re-sends the current value
        if (reopen != null) {
            storeKeys(share, reopen)
        } else if (share.enabled && !share.expiryClosed && expiresAt != null && !expiresAt.isAfter(now)) {
            close(share, CloseKind.EXPIRY)
        }
        return UpdateResult.DONE
    }

    /** Whether an edit setting [expiresAt] reopens [share] (ADR 0114 decision 7): an enabled link whose end date has
     *  passed, the date moved into the future or cleared. A switched-off link stays closed whatever its date. */
    fun reopensByDate(share: ShareEntity, expiresAt: Instant?, now: Instant): Boolean =
        share.enabled && !share.deleted && share.expiresAt?.isAfter(now) == false && expiresAt?.isAfter(now) != false

    /** What a re-seal of a link's name ([setSealedName]) did. */
    enum class SealedNameResult { DONE, NOT_FOUND, CLOSED }

    /** Replace a live share's `sealed_name` (ADR 0080 decision 14) — that column and no other, so a re-seal can never
     *  revert a concurrent edit, revoke or re-activation (`@DynamicUpdate` writes only what changed). [NOT_FOUND] when
     *  there is no such share, or it is deleted: the row lock ([liveForUpdate]) makes a delete that committed first win.
     *  [CLOSED] when the link is closed — switched off, or its end date passed ([OPEN]'s predicate): nothing new is
     *  sealed under a closed link's `K_share`, and its reopen carries the root's current name (ADR 0114 decisions 5 and
     *  7). Decided under the same lock, so a revoke that committed first wins too.
     *
     *  **Lock order: the share row only** — never the scope row. `updateShare`, `revoke`, `delete` and
     *  `reactivate` take the scope row, then this row. Holding one lock, this write can wait for any of them but
     *  never hold what one waits for while it waits itself, so no order of the two deadlocks
     *  (`ShareSealedNameCoreSliceTest`). Taking the scope row after this one would be the inversion. */
    @Transactional
    fun setSealedName(shareId: String, sealedName: String, editor: PrincipalKey.User, now: Instant = Instant.now()): SealedNameResult {
        val share = liveForUpdate(shareId) ?: return SealedNameResult.NOT_FOUND
        if (!share.enabled || share.expiresAt?.isAfter(now) == false) return SealedNameResult.CLOSED
        share.sealedName = sealedName
        share.nameEditorUserId = editor.userId // ADR 0093 § 8: the member who re-sealed it
        return SealedNameResult.DONE
    }

    /** A share that exists and is not deleted — every reader's lookup (ADR 0029 §7): a tombstone is not found,
     *  to the guest and to every curator operation alike. */
    fun find(shareId: String): ShareEntity? = ShareEntity.findById(shareId)?.takeIf { !it.deleted }

    /** A share row, a tombstone included — for the ONE reader that answers a deleted share differently from an
     *  unknown one: a re-issue, whose member caller gets `share_deleted` and re-reads the list (inbox #252). Its
     *  caller gates scope membership first, so a non-member still cannot tell the two apart. */
    fun findIncludingDeleted(shareId: String): ShareEntity? = ShareEntity.findById(shareId)

    /** A share's state as a re-issue re-checks it under the scope lock before [reissue] spends the row. */
    data class LinkState(val enabled: Boolean, val owner: String, val deleted: Boolean)

    /** Whether a share is enabled, who owns it and whether it is deleted, tombstones included — by a scalar read that
     *  loads no entity, for a re-issue that re-checks them under the scope lock before [reissue] spends the row: a row
     *  loaded first would stay in the transaction's context, and the spend's own read would hand back that pre-spend
     *  copy. A concurrent re-issue that spent the row first is seen here, before the root's epoch gate, whose answer its
     *  close changed (the root is due now). */
    fun stateOf(shareId: String): LinkState? =
        ShareEntity.getEntityManager()
            .createQuery("select s.enabled, s.ownerAccount, s.deleted from ShareEntity s where s.id = :id", Array<Any>::class.java)
            .setParameter("id", shareId).resultList.firstOrNull()
            ?.let { LinkState(it[0] as Boolean, it[1] as String, it[2] as Boolean) }

    /** The share row's lock, and no copy of the row left in the transaction's context: a re-issue takes it before the
     *  shared node's (scope → share → node, every guest write's order, ADR 0109 point 6), and its spend's own read must
     *  still load the row after the spend ([stateOf]'s reason) — so the locked copy is detached at once. */
    fun lockShareRow(shareId: String) {
        ShareEntity.find("id", shareId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
            ?.let { ShareEntity.getEntityManager().detach(it) }
    }

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

    /** Soft-revoke a share the caller owns (idempotent) — a close ([close], ADR 0114 decision 1); a switched-off one is
     *  left as it is. Lock order: the scope row (the share's scope from a scalar read, so the share's first load is the
     *  locked one), then the share row, then the subtree's node rows. False when no such share is owned by the caller. */
    @Transactional
    fun revoke(ownerAccount: String, shareId: String): Boolean {
        ScopeEntity.lockRow(liveScopeOf(shareId) ?: return false)
        val share = liveForUpdate(shareId)?.takeIf { it.ownerAccount == ownerAccount } ?: return false
        if (share.enabled) close(share, CloseKind.REVOKE)
        return true
    }

    /** Re-activate a soft-revoked share and adopt its ownership (cr-0104): flip `enabled` back on and take
     *  `ownerAccount`, storing back what its close cleared ([reopen], ADR 0114 decision 7) — null only for a share that
     *  is enabled already, which keeps its keys. A link whose end date has passed stays closed by its date: it takes
     *  none of the keys (nothing new is sealed under a closed link, decision 5) and counts as closed by the sweep, whose
     *  marks its switch-off already made. The handler has already let the caller through the scope-access gate and the
     *  root's epoch gate, in this transaction and under the scope's lock ([SharingHandler.reactivate]). False when there
     *  is no such share. */
    @Transactional
    fun reactivate(callerAccount: String, shareId: String, reopen: Reopen?, now: Instant): Boolean {
        val share = liveForUpdate(shareId) ?: return false
        check(share.enabled == (reopen == null)) { "a switched-off share is reopened with its keys" }
        // Still switched off here, so the end date's close marks nothing again: the switch-off carried it out.
        if (reopen != null && share.expiresAt?.isAfter(now) == false) close(share, CloseKind.EXPIRY)
        else if (reopen != null) storeKeys(share, reopen)
        share.enabled = true
        share.ownerAccount = callerAccount
        return true
    }

    /** A reopen's writes: the root's key of the link's role and its name, and no sweep mark. */
    private fun storeKeys(share: ShareEntity, reopen: Reopen) {
        share.wrap = (reopen.keys as? ShareKeys.MemoryWrap)?.wrap
        share.sealedDropPublicKey = (reopen.keys as? ShareKeys.Drop)?.sealedPublicKey
        share.sealedName = reopen.sealedName
        share.nameEditorUserId = reopen.editor // ADR 0093 § 8: the member who sealed it
        share.expiryClosed = false
    }

    /** Delete a share (ADR 0029 §7) — a close ([close]) that [tombstone]s it. The handler has already decided the caller
     *  may. Lock order as [revoke]'s. False when there is no such share, or it is deleted already. */
    @Transactional
    fun delete(shareId: String): Boolean {
        ScopeEntity.lockRow(liveScopeOf(shareId) ?: return false)
        val share = liveForUpdate(shareId) ?: return false
        close(share, CloseKind.DELETE)
        return true
    }

    /** How a link closes (ADR 0114 decision 1) — what [close] writes beside clearing its keys. */
    enum class CloseKind {
        /** Switched off: a revoke, a bulk switch-off, a removed member's links. */
        REVOKE,
        /** Deleted: a delete, a re-issue's old link — [tombstone]. */
        DELETE,
        /** Its end date passed, carried out by the sweep or by an edit that sets a passed date. */
        EXPIRY,
        /**
         * Its key is lost: no member opens its recovery copy, so no re-key can wrap the root's new key for it — switched
         * off, as a revoke, by the root's node re-key that names it (`RekeyStore.commit`, ADR 0115 decision 2). Its
         * reopen needs that copy too, so only a re-issue could bring it back.
         */
        KEY_LOST,
    }

    /**
     * The ONE body of every close of a link (ADR 0114 decisions 1, 2, 5 and 8): every write that turns a link off,
     * deletes it or carries out its end date goes through here (`scripts/validate_link_close_arity.py`) — but the
     * purge's [tombstoneAllOn], whose links have no node left below them. In the caller's transaction, which holds the
     * scope row's lock and then the share row's: the close and every mark land together (decision 8).
     *
     * - [kind]'s write: `enabled` off, the tombstone, or the sweep's mark.
     * - The link's keys go — its `wrap` and its sealed drop public key (decision 5): nothing new is sealed under a closed
     *   link, and its reopen brings the root's current key (decision 7). A tombstone clears them anyway.
     * - A READ or READ_WRITE link whose close was not carried out yet ([unclosed]: enabled, not deleted, not swept)
     *   marks every node of its root's subtree, live and trashed, the root included, **due** for its re-key
     *   ([markSubtreeDue]): from here on nothing is written there under a key the link's holder has
     *   (`node_rekey_pending`), and any member's worker re-keys it. A `WRITE_ONLY` link marks nothing (decision 2): its
     *   `K_share` opens only the drop public key and its guest's own `K_up`. A link closed already marks nothing again.
     */
    fun close(share: ShareEntity, kind: CloseKind) {
        val marks = unclosed(share) && share.role != ShareEntity.WRITE_ONLY
        when (kind) {
            CloseKind.REVOKE, CloseKind.KEY_LOST -> share.enabled = false
            CloseKind.DELETE -> tombstone(share)
            CloseKind.EXPIRY -> share.expiryClosed = true
        }
        share.wrap = null
        share.sealedDropPublicKey = null
        if (marks) markSubtreeDue(share.scopeId, share.nodeId)
    }

    /** Whether [share]'s close has not been carried out: enabled, not deleted, not swept. An enabled link whose end
     *  date passed before the sweep reached it counts — closed by its date, but still keyed and its subtree unmarked. */
    private fun unclosed(share: ShareEntity): Boolean = share.enabled && !share.deleted && !share.expiryClosed

    /**
     * Mark every node of [rootNodeId]'s subtree due (ADR 0114 decision 4): one recursive read of the parent ids, live and
     * trashed nodes alike, and the marks in pages — after the scope's and the share's locks, the order every writer
     * takes; then the tree revision, a leaf, under the scope lock held (ADR 0081: the re-key listing serves the mark).
     */
    private fun markSubtreeDue(scopeId: String, rootNodeId: String) {
        val ids = subtreeIdsAll(scopeId, rootNodeId)
        if (ids.isEmpty()) return
        ids.chunked(MARK_PAGE).forEach { NodeEntity.update("rekeyDue = true where id in ?1", it) }
        tree.bumpTree(scopeId)
    }

    /** The ids of [rootNodeId] and every node below it in [scopeId], live and trashed, by one recursive query on the
     *  parent ids (H2 in its MariaDB mode and MariaDB both run it); empty when there is no such node. */
    fun subtreeIdsAll(scopeId: String, rootNodeId: String): List<String> =
        NodeEntity.getEntityManager().createNativeQuery(
            """
            WITH RECURSIVE sub (id) AS (
                SELECT n.id FROM veyq_node n WHERE n.id = :root AND n.scope_id = :scope
                UNION ALL
                SELECT c.id FROM veyq_node c JOIN sub ON c.parent_id = sub.id WHERE c.scope_id = :scope
            )
            SELECT CAST(sub.id AS CHAR(36)) FROM sub
            """.trimIndent(),
        ).setParameter("root", rootNodeId).setParameter("scope", scopeId).resultList.map { it.toString() }

    /** A share row under its lock, refreshed — a copy this transaction already loaded would carry its state from
     *  before the lock. A tombstone included. */
    private fun lockedRow(shareId: String): ShareEntity? =
        ShareEntity.find("id", shareId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
            ?.also { ShareEntity.getEntityManager().refresh(it) }

    /** Every share of [scopeId] matching [where] (JPQL on `s`), locked in id order — the order two lockers of several
     *  share rows agree on — and refreshed. */
    private fun lockedShares(scopeId: String, where: String, params: Map<String, Any> = emptyMap()): List<ShareEntity> {
        val q = ShareEntity.getEntityManager()
            .createQuery("select s from ShareEntity s where s.scopeId = :scope and $where order by s.id", ShareEntity::class.java)
            .setParameter("scope", scopeId)
        params.forEach { (k, v) -> q.setParameter(k, v) }
        return q.setLockMode(LockModeType.PESSIMISTIC_WRITE).resultList.onEach { ShareEntity.getEntityManager().refresh(it) }
    }

    /** The scopes holding an enabled, not deleted link whose end date has passed and whose close the sweep has not
     *  carried out — the end-date sweep's work list (ADR 0114 decision 6). */
    fun scopesWithExpiredUnclosedLinks(now: Instant): List<String> =
        ShareEntity.getEntityManager()
            .createQuery("select distinct s.scopeId from ShareEntity s where $EXPIRED_UNCLOSED", String::class.java)
            .setParameter("now", now).resultList

    /** Close every link of [scopeId] whose end date has passed and whose close was not carried out ([close], an end
     *  date's), under the scope row's lock and then the share rows', each re-read there. Returns how many it closed. */
    @Transactional
    fun closeExpiredIn(scopeId: String, now: Instant): Int {
        ScopeEntity.lockRow(scopeId) ?: return 0
        val due = lockedShares(scopeId, EXPIRED_UNCLOSED, mapOf("now" to now))
        due.forEach { close(it, CloseKind.EXPIRY) }
        return due.size
    }

    /**
     * The ONE body of every share deletion (ADR 0029 §7) — a delete and a re-issue's old link through [close],
     * the purge of the node it is on directly (its links have nothing left below them to re-key, ADR 0114 decision 1). Off, `deleted` set, and every ciphertext column and the expiry cleared: what stays is the id (so
     * nothing is ever minted under it again), the scope, node, role and creation time, and the foreign key the link's guest
     * identities hang off. Never touches the identities' wrapped keys, the application rows under the node (Memories' media and
     * buckets), or its drop key: an uploader guest's uploads open only through them, and they belong to the node, not to the link — an
     * identity goes only once nothing of the guest's remains ([dropDeadIdentities]). A moderation measure on the link — a
     * block, or a guest reporter's bar — ends with it ([MeasureTargets.erased]): nothing of the link serves again, so the
     * measure would stand on nothing forever and hold its case's records with it. Runs inside its caller's transaction,
     * which holds the share row's lock (every caller locks it first).
     */
    fun tombstone(share: ShareEntity) {
        share.enabled = false
        share.deleted = true
        share.wrap = null
        share.sealedDropPublicKey = null
        share.sealedName = null
        share.sealedNote = null
        share.sealedShareName = null
        share.nameEditorUserId = null // a cleared field has no editor (ADR 0093 § 8)
        share.noteEditorUserId = null
        share.shareNameEditorUserId = null
        share.sealedShareKey = null
        share.wrappedRecoveryValueKey = null
        share.sealedShareKeyEpoch = null
        share.passwordRequired = false
        share.pwSalt = null
        share.pwVerifier = null
        share.sealedPassword = null
        share.writeVerifier = null
        share.expiresAt = null
        // The erasure ledger's line (ADR 0056 decision 8): a restore of a capture older than this brings the link back
        // live, its keys and password with it, and nothing in its row would delete it again.
        ledger.erased(ErasureKind.SHARE, share.id, share.scopeId)
        measures.erased(MeasureTargets.ON_LINK, listOf(share.id))
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
     * just orphaned; this never scans the table. A block of a dropped guest uploader ends with it ([MeasureTargets.erased]).
     * Runs inside its caller's transaction.
     */
    fun dropDeadIdentities(candidateIds: Collection<String>) {
        val dropped = mutableListOf<String>()
        for (id in candidateIds.toSet()) {
            val identity = ShareIdentityEntity.findById(id) ?: continue
            if (ShareEntity.findById(identity.shareId)?.deleted != true) continue
            if (!GuestIdentityUsage.unreferenced(identityUsages, id)) continue
            ShareIdentityEntity.deleteById(id)
            dropped += id
        }
        measures.erased(listOf(BlockKind.GUEST_IDENTITY, BlockKind.CONTENT_UPLOADER), dropped)
    }

    /** Soft-revoke every enabled share a removed member created in a scope (cr-0104) — their links then 404 on
     *  the next resolve/blob GET; each is a close ([close], ADR 0114 decision 1). Idempotent; other members' shares +
     *  the record shape are untouched. Runs inside its caller's transaction, which holds the scope row's lock (a
     *  removal's: `ScopesStore.rotate`, the tenant cascade). */
    @Transactional
    fun deactivateSharesByOwnerInScope(ownerAccount: String, scopeId: String): Int {
        val owned = lockedShares(scopeId, "s.ownerAccount = :owner and s.enabled = true", mapOf("owner" to ownerAccount))
        owned.forEach { close(it, CloseKind.REVOKE) }
        return owned.size
    }

    /** Turn off **every** enabled share of a scope, whoever created it, and mark **every** share of it `reissue_only`
     *  — the one body of every bulk switch-off (ADR 0075 point 5): a group removal's and a tenant removal's
     *  `revokeLinks`, and a scope owner's standalone switch-off. Every member could rebuild every link, so a link may
     *  have leaked whoever made it; each owner re-issues what they still want, and the mark keeps re-activation from
     *  reviving one. A link its owner had already switched off is marked too (inbox #258): it may have leaked as
     *  well, and unmarked, one with no recovery copy came back with one click right after the switch-off. A deleted
     *  link is left alone — it never comes back anyway. Each link it turns off is a close ([close], ADR 0114 decision
     *  1). Returns how many it turned off. Runs inside its caller's transaction, which holds the scope row's lock. */
    @Transactional
    fun turnOffAllInScope(scopeId: String): Int {
        val enabled = lockedShares(scopeId, "s.enabled = true")
        enabled.forEach {
            it.reissueOnly = true
            close(it, CloseKind.REVOKE)
        }
        ShareEntity.update("reissueOnly = true where scopeId = ?1 and reissueOnly = false and deleted = false", scopeId)
        return enabled.size
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
                scopeEpoch = n.scopeEpoch,
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
        // A bookmark is a member's received link, not the space's content (ADR 0120 decision 7): no link above it serves
        // it — not its entry, its wrapped key or its meta, which holds another link's key — and so no guest reaches it, no
        // moderator's review of a reported folder reads it, and no hold of one copies it. It holds no child to lose.
        return subtreeIds.mapNotNull { id -> nodeById[id]?.takeIf { it.kind != CoreNodeKinds.SHARE_BOOKMARK } }
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

    companion object {
        /** How many node ids one due-mark statement names. */
        private const val MARK_PAGE = 500

        /** An enabled, not deleted link whose end date is at or before `:now` and whose close the sweep has not carried
         *  out — JPQL on `ShareEntity s`. */
        private const val EXPIRED_UNCLOSED =
            "s.enabled = true and s.deleted = false and s.expiryClosed = false and s.expiresAt is not null and s.expiresAt <= :now"

        /** A link that is **open** (ADR 0114 decision 1) — enabled, not deleted, before its end date — as JPQL on
         *  `ShareEntity` bound to `:now`: the links a re-key wraps the root's new key under (decision 5). */
        const val OPEN = "enabled = true and deleted = false and (expiresAt is null or expiresAt > :now)"
    }
}
