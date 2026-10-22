package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.http.ClientSeen
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteObject
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.persistence.createOrConflict
import jakarta.enterprise.context.ApplicationScoped

/** What [IdentityFolders.ensure] answers. */
sealed interface IdentityFolderOutcome {
    /** The identity's folder: [created] when this call made it, else the one it already had (its stored wrap). */
    data class Folder(val nodeId: String, val epoch: Int, val placementRevision: Long, val wrappedContentKey: String, val created: Boolean) :
        IdentityFolderOutcome
    /** The link is gone under its lock (revoked, deleted) — as the gate's 404. */
    object NotFound : IdentityFolderOutcome
    /** The root is trashed or gone, or the identity's folder is in the trash (a member removed it). */
    object NotContributable : IdentityFolderOutcome
    /** The epoch is above the scope's current one; nothing stored. */
    object StaleEpoch : IdentityFolderOutcome
    /** The client-minted node id is taken (in any scope); nothing stored. */
    object IdTaken : IdentityFolderOutcome
}

/**
 * A `WRITE_ONLY` guest's **identity folder** (ADR 0080 decision 11): ONE core `FOLDER` per (link root, identity), the
 * guest's own place in a drop box it holds no key of. The guest mints the folder's id and content key and seals its
 * name, slug and key wrap under its identity key `K_up` (which it handed over wrapped to the root's drop public key in
 * `createShareIdentity`); the server stores that ciphertext with the identity's id ([NodeEntity.shareIdentityId]) and
 * opens nothing. Below it the guest creates with core's guest create (`ShareNodesHandler`, bounded to this folder) and
 * uploads into its nodes (`ShareGuestGate.target`).
 *
 * The **mechanism** is core's — it names no kind. Which roots take identity folders is the root kind's application's
 * question, asked before this (Depot: a `DEPOT_DROPBOX`); a placement outside `NodeKindRules` happens only here (no
 * rule admits a `FOLDER` under an application's drop box, so nobody else can place one there, and a rename or a move
 * inside is refused by the same rules — a member's move out is the folder's one placement, which ends its identity
 * link, `NodesStore.applyUpdate`).
 *
 * **One per identity, under concurrency:** decided under the scope lock (every tree writer's first lock, ADR 0078 READ
 * COMMITTED: a concurrent first call that committed is seen), with `ux_node_parent_identity` as the backstop whose loser
 * answers as its replay — the folder the winner made.
 */
@ApplicationScoped
class IdentityFolders(private val nodes: NodesStore, private val guestWriteRecords: GuestWriteRecords) {

    /**
     * The identity folder of [gated]'s identity in its link's root — created from the request when it has none.
     * [gated] must be a `WRITE_ONLY` admission whose root kind the caller's application takes identity folders under.
     * Lock order as every guest write: the scope row, then the share row ([ShareWriteGate.Admitted.admitted]). The call
     * that creates the folder records [seen] (ADR 0093 § 10: the guest named it), in its transaction; one that finds it
     * wrote nothing and records nothing.
     */
    fun ensure(
        gated: ShareWriteGate.Admitted, nodeId: String, encryptedName: String, slugToken: String, epoch: Int,
        wrappedContentKey: String, seen: ClientSeen?,
    ): IdentityFolderOutcome {
        require(gated.role == ShareEntity.WRITE_ONLY) { "an identity folder is a WRITE_ONLY guest's" }
        val scopeId = gated.scopeId
        val root = gated.rootNodeId
        return createOrConflict(conflict = { existing(root, gated.identityId) }) {
            nodes.lockScope(scopeId)
            if (!gated.admitted()) return@createOrConflict IdentityFolderOutcome.NotFound
            if (nodes.findInScope(scopeId, root) == null) return@createOrConflict IdentityFolderOutcome.NotContributable
            existing(root, gated.identityId)?.let { return@createOrConflict it }
            // Off the scope root, at or below the current epoch, stored as sent (ADR 0080 decision 6: the rule a
            // guest's create takes); the seals bind it, so the reader opens at it.
            val current = nodes.currentEpoch(scopeId)
            if (current == null || epoch > current) return@createOrConflict IdentityFolderOutcome.StaleEpoch
            if (nodes.idTaken(nodeId)) return@createOrConflict IdentityFolderOutcome.IdTaken
            nodes.createIdentityFolder(
                nodeId, scopeId, root, gated.identityId, encryptedName, slugToken, epoch, wrappedContentKey,
                editor = PrincipalKey.Share(gated.identityId),
            )
            NodeEntity.flush() // the unique index decides here, inside the retry's reach, not at commit
            guestWriteRecords.record(seen, gated.identityId, GuestWriteObject.NODE, nodeId, GuestWriteRecords.NAME)
            IdentityFolderOutcome.Folder(nodeId, epoch, 0, wrappedContentKey, created = true)
        }
    }

    /** The identity's folder as stored, live; [IdentityFolderOutcome.NotContributable] when it is in the trash; null when
     *  there is none. */
    private fun existing(root: String, identityId: String): IdentityFolderOutcome? {
        val folder = nodes.identityFolder(root, identityId) ?: return null
        if (folder.deletedAt != null) return IdentityFolderOutcome.NotContributable
        return IdentityFolderOutcome.Folder(folder.id, folder.epoch, folder.placementRevision, folder.wrappedContentKey, created = false)
    }
}

/**
 * A guest identity is still in use while a node names it, live or trashed:
 *  - as an identity folder's owner (`veyq_node.share_identity_id` — a trashed folder can be restored, and the identity's
 *    wrapped `K_up` is the only way into its entry), ADR 0080 decision 11. A folder a member moved out names none;
 *  - as the last editor of a node's name or meta (ADR 0093 § 8, en.platform.field-editor): who wrote a field is kept as
 *    long as the field, so a moderator still learns it after the identity's last upload went.
 * The foreign keys (`fk_node_share_identity`, `fk_node_name_editor_identity`, `fk_node_meta_editor_identity`) would
 * refuse its delete otherwise.
 */
@ApplicationScoped
class NodeGuestIdentityUsage : GuestIdentityUsage {
    override fun stillReferenced(identityId: String): Boolean =
        NodeEntity.count("shareIdentityId = ?1 or nameEditorIdentityId = ?1 or metaEditorIdentityId = ?1", identityId) > 0
}
