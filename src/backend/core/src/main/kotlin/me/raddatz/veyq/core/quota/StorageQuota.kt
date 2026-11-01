package me.raddatz.veyq.core.quota

import me.raddatz.veyq.core.blob.UploadDeclare
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager

/**
 * The one unit that answers "does this upload fit" (ADR 0073 §3–§5). The stored total itself is summed in
 * [StorageUsage] — the only place it is — and this class asks it; what stays here is what takes an upload
 * declaration ([tenantFits], [linkFits]) and the per-link usage ([linkUsage]).
 *
 * A check is only a reservation inside the transaction that also inserts the sessions, under [lockTenant] — without
 * the lock, N parallel starts each see the same remainder and together admit N times it. The caller owns that
 * transaction (`BlobUploadHandler.reserveAndOpen`, which every start — member, cover, link — goes through); presigning
 * happens after it commits, so the lock is never held across signatures.
 *
 * Two checks, each independent of the other (ADR 0073 §1, §4): the Server Owner's tenant quota ([tenantFits]) on
 * every start, and the tenant owner's per-link limit ([linkFits]) on a link start. A link's usage has three terms: the
 * sessions its identities hold open and the node attachments they uploaded — core's tables, summed here — and the
 * blobs they contributed to the applications' own rows, which only the applications can find ([UploadAdmission]: every
 * one's term, summed; the start's target kind — the share root or a node below it, ADR 0080 decision 6 — must have one).
 */
@ApplicationScoped
class StorageQuota(
    private val em: EntityManager,
    private val usage: StorageUsage,
    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @param:All private val admissions: MutableList<UploadAdmission>,
) {

    /** [StorageUsage.lockTenant], kept here so the upload path has one quota entry point. */
    fun lockTenant(tenantId: String): TenantEntity? = usage.lockTenant(tenantId)

    /**
     * The one [UploadAdmission] that measures a link's uploads into a node of [targetKind], or null — none, or more
     * than one, which would leave the choice to CDI bean order. Null **refuses** the link start (fail closed, like
     * `SharingHandler.writesFor`): a link whose usage nobody can measure is never admitted. Asked by the guest gate
     * before the start and again by [linkUsage] inside the reservation; `UploadAdmissionPartitionSliceTest` pins it.
     */
    fun admissionFor(targetKind: String): UploadAdmission? = admissions.filter { it.supports(targetKind) }.singleOrNull()

    /**
     * Whether [items] fit the link [shareId] **together** under [tenant]'s per-link limit: their declared sizes plus
     * what the link already holds. Call under [lockTenant], in the transaction that inserts their sessions. A link
     * whose uploads into [targetKind] no single [UploadAdmission] measures fits nothing.
     */
    fun linkFits(tenant: TenantEntity, shareId: String, targetKind: String, items: List<UploadDeclare>): Boolean {
        val held = linkUsage(shareId, targetKind) ?: return false
        return held + items.sumOf { it.byteSize } <= tenant.shareLinkLimitBytes
    }

    /**
     * Whether [items] fit [tenant]'s storage quota **together**: their declared sizes plus what the tenant already
     * holds. A tenant without a quota (the default, ADR 0073 §1) fits anything, and is not summed at all. Call under
     * [lockTenant], in the transaction that inserts their sessions.
     */
    fun tenantFits(tenant: TenantEntity, items: List<UploadDeclare>): Boolean = tenantFitsBytes(tenant, items.sumOf { it.byteSize })

    /**
     * The same rule for a bare byte count, with **no lock and no reservation**: the member's advisory preflight
     * (`checkTenantStorageFit`, ADR 0082) asks it. It answers yes or no and is never a promise - the start that follows
     * is still the one decision, under [lockTenant]. A tenant without a quota fits anything.
     */
    fun tenantFitsBytes(tenant: TenantEntity, bytes: Long): Boolean {
        val quota = tenant.storageQuotaBytes ?: return true
        return usage.tenantUsage(tenant.id) + bytes <= quota
    }

    /**
     * What link [shareId] holds (ADR 0073 §4): every open session one of its identities opened, plus the blob of every
     * node attachment one of them uploaded ([linkAttachmentUsage]), plus the blobs they contributed to the applications'
     * own rows, as every [UploadAdmission] measures its own. A blob a member
     * uploaded into the same node is not the link's, and neither is another link's. The measure is per link, never per
     * node: it counts what the link's identities contributed anywhere in its subtree (Memories: by identity, whatever
     * Memory or chapter holds the medium). Null when no single admission measures [targetKind] ([admissionFor]) —
     * whatever the core terms hold: they never measure a link on their own, so a kind whose admission went missing
     * stays refused (an application with blobs of its own rows would otherwise count only its attachments), and an
     * application whose guest uploads are all attachments registers an admission that reports 0.
     *
     * Gated by the start's target kind, not the root's: a folder link's root kind has no measure (no application takes
     * uploads into a folder) while its guests upload into Memories below. But the measure is **every** application's
     * term summed, not the target's alone: a folder link's guests upload into two applications' kinds — a Memory's media
     * and a Pot's receipts (core's blob service's term since ADR 0112, its rows by identity) — and measured by
     * the target's application only, a start into a Pot would not count the link's media, nor one into a Memory its
     * receipts, so the link could hold its limit once per application. Each term counts its own rows by identity, so no
     * blob is counted twice (`UploadAdmissionPartitionSliceTest`).
     */
    fun linkUsage(shareId: String, targetKind: String): Long? {
        admissionFor(targetKind) ?: return null
        // One statement per term, the sessions read FIRST (the return adds left to right): a completion or an attach
        // committing between two of them turns a session into a blob, and read in this order it is counted twice
        // (refused a little early), never zero times (admitted over the limit) — each reads what is committed when it runs.
        val open = sum(
            """
            SELECT COALESCE((SELECT SUM(s.byte_size) FROM veyq_upload_session s
                   JOIN veyq_share_identity i ON i.id = s.opener_share_identity_id
                  WHERE i.share_id = :key), 0)
            """,
            shareId,
        )
        return open + linkAttachmentUsage(shareId) + admissions.sumOf { it.linkBlobUsage(shareId) }
    }

    /**
     * The core blob term of [linkUsage]: the bytes of every attachment's blob (`veyq_node_attachment`) one of link
     * [shareId]'s identities uploaded — in any node of any kind, trashed ones included (ADR 0073 §2). A replace moves
     * the row's identity with the blob (`AttachmentStore.attach`), so the term counts what the slot holds now, and
     * never the replaced blob, whose row is gone. No blob is in two arms (`ux_attachment_blob`; each arm's row is born
     * with a fresh blob), so no application's term counts one of these too. A member's attachment (a Memory's cover is
     * one, inbox #341) names no identity, so no link's term counts it.
     */
    private fun linkAttachmentUsage(shareId: String): Long = sum(
        """
        SELECT COALESCE((SELECT SUM(b.byte_size) FROM veyq_node_attachment a
               JOIN veyq_blob b ON b.id = a.blob_id
               JOIN veyq_share_identity i ON i.id = a.share_identity_id
              WHERE i.share_id = :key), 0)
        """,
        shareId,
    )

    private fun sum(sql: String, key: String): Long =
        (em.createNativeQuery(sql.trimIndent()).setParameter("key", key).singleResult as Number).toLong()
}
