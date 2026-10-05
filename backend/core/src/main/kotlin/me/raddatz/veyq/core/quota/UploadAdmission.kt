package me.raddatz.veyq.core.quota

/**
 * What an application tells core's per-link limit (ADR 0073 §4): how many bytes of blobs a link's guests contributed
 * through it. Core sums the other term itself — the sessions the link's identities hold open, core's own tables —
 * and compares the two plus a start's declared sizes with the tenant's `shareLinkLimitBytes` ([StorageQuota.linkFits]).
 *
 * Dispatched by the kind of the link's root node, like `ShareGuestWrites` ([supports]); **exactly one** implementation
 * must measure a kind, or the link start is refused (`share_not_contributable`). Absence fails closed on purpose: an
 * application whose implementation went missing — an unindexed module, a dropped bean — would otherwise have its links
 * count nothing and admit everything. `UploadAdmissionPartitionSliceTest` pins the partition.
 */
interface UploadAdmission {
    /** Whether this implementation measures links rooted at a node of [nodeKind]. */
    fun supports(nodeKind: String): Boolean

    /**
     * The bytes of every blob link [shareId]'s identities contributed and that still exists — trashed ones included,
     * since trash is still in the store (ADR 0073 §2) — **not** its open sessions, which core adds.
     */
    fun linkBlobUsage(shareId: String): Long
}
