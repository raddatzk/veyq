package me.raddatz.veyq.core.quota

/**
 * What an application tells core's per-link limit (ADR 0073 §4): how many bytes of blobs a link's guests contributed
 * through it to the application's own rows. Core sums the other terms itself — the sessions the link's identities hold
 * open and the node attachments they uploaded, core's own tables — and compares the three plus a start's declared
 * sizes with the tenant's `shareLinkLimitBytes` ([StorageQuota.linkFits]). An application whose guest uploads all
 * become attachments still implements this for its kinds and reports 0: core's terms never admit a link alone.
 *
 * Gated by the kind of the start's target node (the share root, or a node below it the guest named — ADR 0080
 * decision 6), like `ShareGuestWrites` ([supports]); **exactly one** implementation
 * must measure a kind, or the link start is refused (`share_not_contributable`). The usage itself is every
 * implementation's [linkBlobUsage] summed, whatever the target (`StorageQuota.linkUsage`): a folder link's guests upload
 * into several applications' kinds, and each term counts only its own rows. Absence fails closed on purpose: an
 * application whose implementation went missing — an unindexed module, a dropped bean — would otherwise have its links
 * count nothing and admit everything. `UploadAdmissionPartitionSliceTest` pins the partition.
 */
interface UploadAdmission {
    /** Whether this implementation measures a link's uploads into a node of [nodeKind]. */
    fun supports(nodeKind: String): Boolean

    /**
     * The bytes of every blob link [shareId]'s identities contributed — into any node of its subtree — and that still exists — trashed ones included,
     * since trash is still in the store (ADR 0073 §2) — **not** its open sessions nor its node attachments' blobs,
     * which core adds.
     */
    fun linkBlobUsage(shareId: String): Long
}
