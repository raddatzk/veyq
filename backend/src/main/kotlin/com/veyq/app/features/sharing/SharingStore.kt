package com.veyq.app.features.sharing

import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.media.BlobEntity
import com.veyq.app.features.media.MediaDerivativeEntity
import com.veyq.app.features.media.MediaEntity
import com.veyq.app.features.media.MediaStore
import com.veyq.app.shared.crypto.Tokens
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** One node of a shared subtree (the name stays sealed under `K_SC`, so it is not returned — the root's comes from `sealedName`). */
data class ShareNodeRow(
    val id: String,
    /** The parent within the subtree, or null at the shared root (its `K_mem` comes from the share `wrap`). */
    val parentId: String?,
    val kind: String,
    val orderIndex: Int,
    /** The content-key epoch the node was sealed at — a chapter's `K_mem` wrap is bound to it (ADR 0072). */
    val epoch: Int,
    val wrappedContentKey: String?,
    val wrappedMeta: String?,
    /** The Memory's drop private key sealed under its `K_mem` — every reader of the subtree opens uploads through it. */
    val sealedDropPrivateKey: String?,
)

/** One media under a shared subtree — the original blob ref + role-tagged derivatives, tagged with its owning node. */
data class ShareMediaRow(
    val nodeId: String,
    val mediaId: String,
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val manifest: List<Long>?,
    val wrappedBlobKey: String,
    val derivatives: List<ShareDerivativeRow>,
    val wrappedMeta: String?,
    /** The guest identity that added this media; null for a member's. `resolve` serves it for an uploader's only. */
    val identityId: String?,
)

/** One derivative tier's blob ref under a shared subtree. */
data class ShareDerivativeRow(
    val kind: String,
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val manifest: List<Long>?,
    val wrappedBlobKey: String,
)

/** The sealed subtree of a share: its nodes (for the `K_mem` chain) + every media attached under it. */
data class SubtreeData(val nodes: List<ShareNodeRow>, val media: List<ShareMediaRow>)

/**
 * What a share hands its guest, fixed by its role (ADR 0031 point 7): a VIEWER or CONTRIBUTOR share wraps `K_mem`
 * under `K_share`; an UPLOADER share carries the Memory's drop public key under `K_share` instead, and the Memory's
 * first UPLOADER share also brings the drop private key sealed under `K_mem`, which the Memory keeps.
 */
sealed interface ShareKeys {
    data class MemoryWrap(val wrap: String) : ShareKeys
    data class Drop(val sealedPublicKey: String, val sealedPrivateKey: String?) : ShareKeys
}

/** A blob to presign for a viewer: its object-store prefix + chunk count. */
data class ShareBlobRef(val storageKey: String, val chunkCount: Int)

/**
 * Persistence for public viewer shares (cr-0100). Operator-blind: stores the opaque share row + reads the
 * already-opaque node/media/blob rows to assemble the sealed subtree. The **subtree** is the authorization
 * grain — the guest resolve returns only nodes/media under the shared node, and a blob GET is authorized iff
 * the blob is attached under that subtree (a structural id check, never a name or content).
 */
@ApplicationScoped
class SharingStore(
    /** cr-0107: the delete-own path bumps the node's live-updates counters via the media store's single bump helper. */
    private val mediaStore: MediaStore,
) {

    fun findNode(nodeId: String): NodeEntity? = NodeEntity.findById(nodeId)

    enum class CreateResult { CREATED, ID_TAKEN, DROP_KEY_EXISTS, DROP_KEY_MISSING }

    /** Persist a share under the client-minted [id] — 256 random bits, base64url, the unguessable half of the
     *  capability (ADR 0072 keeps its form). `ID_TAKEN`, nothing written, if any share already holds it. An UPLOADER
     *  share's drop private key is set on the Memory **once**, by a conditional update in this transaction: a
     *  second one would orphan every upload sealed to the first, so a Memory that already has one is
     *  `DROP_KEY_EXISTS` (the client re-reads the node), and a request with none for a Memory with none is
     *  `DROP_KEY_MISSING` — nothing written in either case. */
    @Transactional
    fun create(
        id: String, ownerAccount: String, scopeId: String, nodeId: String, role: String,
        keys: ShareKeys, sealedName: String, expiresAt: Instant?, sealedNote: String?, sealedShareKey: String?,
        sealedShareKeyEpoch: Int?, sealedShareName: String?,
        passwordRequired: Boolean, pwSalt: String?, pwVerifier: String?, sealedPassword: String?,
    ): CreateResult {
        if (ShareEntity.findById(id) != null) return CreateResult.ID_TAKEN
        if (keys is ShareKeys.Drop) {
            if (keys.sealedPrivateKey != null) {
                val set = NodeEntity.update(
                    "sealedDropPrivateKey = ?1 where id = ?2 and sealedDropPrivateKey is null", keys.sealedPrivateKey, nodeId,
                )
                if (set != 1) return CreateResult.DROP_KEY_EXISTS
            } else if (NodeEntity.findById(nodeId)?.sealedDropPrivateKey == null) {
                return CreateResult.DROP_KEY_MISSING
            }
        }
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
            createdAt = Instant.now()
        }.persist()
        return CreateResult.CREATED
    }

    /** The 3-way result of an in-place share edit (cr-0119): applied, no such owned share, or an illegal role change. */
    enum class UpdateResult { DONE, NOT_FOUND, INVALID_ROLE }

    /** Edit a share the caller owns in place (cr-0115/cr-0116/cr-0119/cr-0118; idempotent). Full-replaces the two
     *  editable, `K_share`-sealed fields (note + name — a null value clears that field); a non-null `role` changes
     *  the grant **in place** (VIEWER ↔ CONTRIBUTOR only — a role of, or a share currently in, UPLOADER is
     *  `INVALID_ROLE`); a non-null `passwordRequired` is the tri-state password patch (true sets/changes it with the
     *  fields, false removes + clears them; null leaves it untouched). Ownership is checked first, so a share not
     *  owned by the caller is `NOT_FOUND` regardless of the role (non-enumerable, mirroring `revoke`). */
    @Transactional
    fun updateShare(
        ownerAccount: String, shareId: String, sealedNote: String?, sealedShareName: String?, role: String?,
        expiresAt: Instant?, passwordRequired: Boolean?, pwSalt: String?, pwVerifier: String?, sealedPassword: String?,
    ): UpdateResult {
        val share = ShareEntity.findById(shareId)?.takeIf { it.ownerAccount == ownerAccount } ?: return UpdateResult.NOT_FOUND
        if (role != null) {
            if (role == ShareEntity.UPLOADER || share.role == ShareEntity.UPLOADER) return UpdateResult.INVALID_ROLE
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

    fun find(shareId: String): ShareEntity? = ShareEntity.findById(shareId)

    fun listByOwner(ownerAccount: String): List<ShareEntity> =
        ShareEntity.find("ownerAccount = ?1", ownerAccount).list()

    /** Every share on a node, any owner — for the shared-scope manage panel (cr-0104). The handler gates it on
     *  scope membership; the store just reads the node's shares. */
    fun listByNode(nodeId: String): List<ShareEntity> =
        ShareEntity.find("nodeId = ?1", nodeId).list()

    /** Soft-revoke a share the caller owns (idempotent). Returns false when no such share is owned by the caller. */
    @Transactional
    fun revoke(ownerAccount: String, shareId: String): Boolean {
        val share = ShareEntity.findById(shareId)?.takeIf { it.ownerAccount == ownerAccount } ?: return false
        share.enabled = false
        return true
    }

    /** Re-activate a soft-revoked share and adopt its ownership (cr-0104): flip `enabled` back on and take
     *  `ownerAccount`. The handler has already let the caller through the scope-access gate. False when there is no
     *  such share. */
    @Transactional
    fun reactivate(callerAccount: String, shareId: String): Boolean {
        val share = ShareEntity.findById(shareId) ?: return false
        share.enabled = true
        share.ownerAccount = callerAccount
        return true
    }

    /** Soft-revoke every enabled share a removed member created in a scope (cr-0104) — their links then 404 on
     *  the next resolve/blob GET. Idempotent; other members' shares + the record shape are untouched. */
    @Transactional
    fun deactivateSharesByOwnerInScope(ownerAccount: String, scopeId: String): Int =
        ShareEntity.update(
            "enabled = false where ownerAccount = ?1 and scopeId = ?2 and enabled = true",
            ownerAccount, scopeId,
        )

    // --- contributor identities (cr-0101) --------------------------------------

    /** Create an anonymous share identity under the client-minted [id]: store the sealed name (+ an UPLOADER
     *  identity's wrapped key) + the token hash, return the token — or null, nothing written, if any identity
     *  already holds [id]. */
    @Transactional
    fun createIdentity(id: String, shareId: String, sealedDisplayName: String, wrappedIdentityKey: String?): String? {
        if (ShareIdentityEntity.findById(id) != null) return null
        val token = Tokens.random() // the unguessable contributor capability token handed to the client
        ShareIdentityEntity().apply {
            this.id = id
            this.shareId = shareId
            this.sealedDisplayName = sealedDisplayName
            this.wrappedIdentityKey = wrappedIdentityKey
            this.tokenHash = Tokens.hash(token)
            createdAt = Instant.now()
        }.persist()
        return token
    }

    /** The contributor identity for this share whose token matches, or null (a bad/absent token → no identity). */
    fun findIdentity(shareId: String, contributorToken: String): ShareIdentityEntity? {
        val hash = Tokens.hash(contributorToken)
        return ShareIdentityEntity.find("shareId = ?1 and tokenHash = ?2", shareId, hash).firstResult()
    }

    /**
     * The wrapped identity key of every UPLOADER identity — of any share — that added a media served in [subtree]
     * (identity id → wrap): that media is sealed under the identity's own key and opens only through this wrap
     * (ADR 0031 point 7). This is ALL a guest reader is told about any identity. Never a CONTRIBUTOR identity, not
     * even the reading share's own — a flipped VIEWER link still has its contributor identities — and never a name:
     * a guest's name is for the Memory's members, who read it through the bucket list (ADR 0030 point 8).
     */
    fun uploaderKeys(subtree: SubtreeData): Map<String, String> {
        val ids = subtree.media.mapNotNull { it.identityId }.toSet()
        if (ids.isEmpty()) return emptyMap()
        return ShareIdentityEntity.find("id in ?1 and wrappedIdentityKey is not null", ids).list()
            .associate { it.id to it.wrappedIdentityKey!! }
    }

    /**
     * Delete one contributed media the caller owns (cr-0102, the UPLOADER/CONTRIBUTOR delete-own). Authorized
     * **iff** the media is in [scopeId] and its `share_identity_id` equals [identityId] — a single-row
     * **ownership** check, never gallery filtering. Deletes the media row + its derivative rows + all blob rows,
     * and puts their store objects (original + every derivative) on the reclaim list in the same transaction.
     * False when no such media is owned by this identity (→ a non-enumerable 404).
     */
    @Transactional
    fun deleteContributedMedia(scopeId: String, mediaId: String, identityId: String): Boolean {
        val media = MediaEntity.findById(mediaId)
            ?.takeIf { it.scopeId == scopeId && it.shareIdentityId == identityId } ?: return false
        val nodeId = media.nodeId // captured before the row is deleted — the node whose gallery must reconcile
        mediaStore.purgeMediaRows(media) // derivative → media → blob rows, FK order (shared helper)
        // cr-0107 (ADR 0033): a media-row removal moves BOTH counters — media_revision (the change signal) and
        // delete_revision (which gates the id-set), so an open gallery's next poll gets the current mediaId set
        // and drops the removed tile. Same transaction ⇒ atomic with the delete. (A LIVE media is removed here —
        // unlike the two trash purges — so it bumps; purgeMediaRows leaves the revision bump to the caller.)
        mediaStore.bumpMediaRevision(nodeId, deleted = true)
        return true
    }

    /** The sealed subtree rooted at [rootNodeId] within [scopeId]: nodes (for the `K_mem` chain) + its live media. */
    fun subtree(scopeId: String, rootNodeId: String): SubtreeData {
        val subtreeIds = collectSubtreeIds(scopeId, rootNodeId)
        // `deletedAt is null` — a soft-deleted node (uc.memory.delete-node, ADR 0061) keeps its ROW, unlike the
        // hard delete this replaced, so without this predicate the guest resolve serves the full sealed subtree
        // of a Memory the user deleted. Its sibling in [subtreeBlobIds] authorizes the blob GET and carries the
        // predicate SEPARATELY on purpose: either one covering for the other is how a later change to the other
        // reopens the hole silently. `ShareDeletedNodeSliceTest` bites each on its own.
        val nodeById = NodeEntity.find("scopeId = ?1 and deletedAt is null", scopeId).list().associateBy { it.id }
        val liveIds = subtreeIds.filter { it in nodeById }
        if (liveIds.isEmpty()) return SubtreeData(emptyList(), emptyList()) // the shared root itself is trashed
        val nodes = liveIds.map { nodeById.getValue(it) }.map { n ->
            ShareNodeRow(
                id = n.id,
                parentId = if (n.id == rootNodeId) null else n.parentId,
                kind = n.kind,
                orderIndex = n.orderIndex,
                epoch = n.epoch,
                wrappedContentKey = n.wrappedContentKey,
                wrappedMeta = n.wrappedMeta,
                sealedDropPrivateKey = n.sealedDropPrivateKey,
            )
        }
        // `deletedAt is null` — a trashed medium (uc.memory.delete-media, cr-0110) is a soft-delete, so without
        // this predicate the guest paths keep serving it for the whole retention window. Both share reads must
        // carry it: this listing and [subtreeBlobIds], which authorizes the blob GET.
        val media = MediaEntity.find("scopeId = ?1 and nodeId in ?2 and deletedAt is null", scopeId, liveIds).list().mapNotNull { m ->
            val blob = BlobEntity.findById(m.blobId) ?: return@mapNotNull null
            val derivatives = MediaDerivativeEntity.find("mediaId = ?1", m.id).list().mapNotNull { d ->
                val derivBlob = BlobEntity.findById(d.blobId) ?: return@mapNotNull null
                ShareDerivativeRow(d.kind, derivBlob.id, derivBlob.chunkCount, derivBlob.byteSize, decodeManifest(derivBlob.manifest), derivBlob.wrappedBlobKey)
            }
            ShareMediaRow(m.nodeId, m.id, blob.id, blob.chunkCount, blob.byteSize, decodeManifest(blob.manifest), blob.wrappedBlobKey, derivatives, m.wrappedMeta, m.shareIdentityId)
        }
        return SubtreeData(nodes, media)
    }

    /**
     * The blob (object-store prefix + chunk count) to presign for a viewer — **iff** [blobId] is attached under
     * the shared subtree (an original/derivative media blob on a subtree node, or a subtree node's standalone
     * cover blob). Null otherwise (→ a non-enumerable 404). A structural id check; no name or content is read.
     */
    fun authorizedBlob(scopeId: String, rootNodeId: String, blobId: String): ShareBlobRef? {
        if (blobId !in subtreeBlobIds(scopeId, rootNodeId)) return null
        val blob = BlobEntity.findById(blobId)?.takeIf { it.scopeId == scopeId } ?: return null
        return ShareBlobRef(blob.storageKey, blob.chunkCount)
    }

    /** Every blob id attached under the subtree: live media originals + derivatives + node cover blobs. */
    private fun subtreeBlobIds(scopeId: String, rootNodeId: String): Set<String> {
        val subtreeIds = collectSubtreeIds(scopeId, rootNodeId)
        // `deletedAt is null` — the sibling of [subtree]'s predicate, deliberately its OWN read (ADR 0061).
        // Filtering only the listing leaves a Memory invisible in the share and still fully downloadable by
        // anyone holding a blobId, unauthenticated, for the whole retention window.
        val liveNodes = NodeEntity.find("scopeId = ?1 and deletedAt is null", scopeId).list().filter { it.id in subtreeIds }
        if (liveNodes.isEmpty()) return emptySet() // the shared root itself is trashed
        val liveIds = liveNodes.map { it.id }
        val ids = HashSet<String>()
        // Trashed media are excluded here too — else the listing hides a medium the blob GET still presigns.
        val media = MediaEntity.find("scopeId = ?1 and nodeId in ?2 and deletedAt is null", scopeId, liveIds).list()
        for (m in media) {
            ids.add(m.blobId)
            MediaDerivativeEntity.find("mediaId = ?1", m.id).list().forEach { ids.add(it.blobId) }
        }
        liveNodes.mapNotNull { it.coverBlobId }.forEach { ids.add(it) }
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

    // Manifest = a JSON array of per-chunk ciphertext sizes (mirrors MediaStore's hand-rolled encoding).
    private fun decodeManifest(s: String?): List<Long>? =
        s?.trim()?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim().toLong() }
}
