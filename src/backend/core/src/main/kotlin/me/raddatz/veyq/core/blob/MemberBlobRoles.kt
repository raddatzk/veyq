package me.raddatz.veyq.core.blob

/**
 * The blob roles an application attaches a **member's** upload in — its values of the contract's open `BlobRole` token
 * (cr-files-application P3-S2), declared so core's member start ([BlobUploadHandler.start]) can refuse a role no
 * application knows before it reserves quota for it (inbox #366). Core names no role; it asks every implementation.
 * The guest side has its own, narrower question per target kind (`ShareGuestWrites.admitsGuestRole`).
 *
 * **A union, not a partition.** Unlike the kind seams (`NodeKindRules`, `ShareRoots`, `UploadCompletion`), nothing is
 * dispatched by a role: a member's start names no node, so core cannot ask the one application that owns the
 * upload's target, only whether *some* application attaches the role at all. Two applications declaring the same
 * token is therefore harmless — the `K_blob` wrap is sealed bound to (blob, owner, role) (ADR 0072), and the
 * completion of whichever application the client then calls decides whether the session is its own. What this cannot
 * refuse is a known role for an object that will never take it (a `FILE_CONTENT` session whose owner is no `FILE`):
 * that session is never attached and ages out with the stale-session purge, as any upload a member abandons does.
 *
 * **Fails closed.** With no implementation no role is declared, so core alone takes no member upload
 * (`CoreAloneSliceTest`): with no application, nothing could ever attach one.
 */
interface MemberBlobRoles {
    /** The roles a member's upload start may carry for this application. */
    fun roles(): Set<String>

    /** The application whose writes these roles are — its register entry (`memories`) — or null for core's own (a
     *  cover). Core's member start refuses a role while every application declaring it is switched off
     *  (uc.platform.switch-application-off): a union, so one still on keeps the role open. */
    fun application(): String? = null

    /**
     * The largest sealed `byteSize` a blob in [role] may declare, below core's per-blob cap (`veyq.blob.max-bytes`), or
     * null for none of its own — the cap of the ROLE, so it holds on every start that names it, a member's and a link
     * guest's alike (`BlobUploadHandler.admissibility`: over it, the item is `budget_exceeded` before any session or
     * quota is taken). An application caps a role by its size, which the server sees, never by its type, which is sealed
     * (Expenses' receipts, ADR 0108 decision 7). Asked of every implementation declaring the role: the smallest cap wins.
     */
    fun maxBytes(role: String): Long? = null

    companion object {
        /** Whether some implementation of [declared] attaches a member's upload in [role]; none → no. */
        fun admits(declared: List<MemberBlobRoles>, role: String): Boolean = declared.any { role in it.roles() }

        /** The smallest cap any implementation of [declared] puts on [role] ([maxBytes]); null when none does. */
        fun capOf(declared: List<MemberBlobRoles>, role: String): Long? =
            declared.filter { role in it.roles() }.mapNotNull { it.maxBytes(role) }.minOrNull()
    }
}
