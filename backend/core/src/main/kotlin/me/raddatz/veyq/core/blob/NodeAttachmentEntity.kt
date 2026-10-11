package me.raddatz.veyq.core.blob

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate

/**
 * A sealed file on a node (ADR 0079 Consequences: attachments are core's; cr-files-application P3): the blob in one
 * [slot] of [nodeId]. Its `K_blob` is wrapped under the node's own key (ADR 0080 Decision 13) and sealed bound to the
 * blob id, the node id and the role (ADR 0072); the server keeps ids only. [slot] is a token the owning application
 * names — Files' is `FILE_CONTENT`, the same token as the blob role — and core compares it, never names one.
 * [shareIdentityId] is the guest identity that uploaded it (null for a member).
 *
 * The fourth arm that references a `veyq_blob` row (`scripts/validate_blob_reference_arity.py`). One blob, one
 * attachment (`ux_attachment_blob`), one attachment per slot (`ux_attachment_node_slot`). **Every** foreign key of
 * the table refuses rather than cascades, so a row goes only where its blob goes with it:
 *  - the trash purge: [AttachmentNodeSubtreeEraser] (rows, then each blob through `BlobUploadStore.deleteBlobRow`);
 *  - the group erasure: [AttachmentScopeEraser] (rows; `ScopePurge` records and deletes the scope's blobs).
 * A writer of this table takes the scope row's lock (`ScopeEntity.lockRow`) first; both erasers run under it.
 */
@Entity
@Table(name = "veyq_node_attachment")
@DynamicUpdate
class NodeAttachmentEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "node_id", nullable = false, columnDefinition = "uuid")
    lateinit var nodeId: String

    @Column(name = "slot", nullable = false, length = 16)
    lateinit var slot: String

    @Column(name = "blob_id", nullable = false, columnDefinition = "uuid")
    lateinit var blobId: String

    @Column(name = "share_identity_id", columnDefinition = "uuid")
    var shareIdentityId: String? = null

    companion object : PanacheCompanionBase<NodeAttachmentEntity, String>
}
