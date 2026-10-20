package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.blob.UploadDeclare
import me.raddatz.veyq.core.blob.UploadItemResult
import me.raddatz.veyq.core.blob.WindowItemResult
import me.raddatz.veyq.core.blob.WindowRequest

/**
 * What an application does when a guest of a share moves bytes under its root — the action body of core's two guest
 * transport calls (start, window). Core owns the gate and dispatches by the kind of the upload's **target** node
 * ([supports]): the share root, or a node below it the guest named (`ShareGuestGate.target`; ADR 0080 decision 6 — a
 * link's role applies to every node below its root as the application allows it); the
 * application owns what is reserved and signed (today: Memories' upload sessions and quota). What a guest's upload
 * then *becomes*, and deleting it again, are the application's own operations under the share's path (Memories'
 * `share-media`): they call [ShareGuestGate.writeGate] themselves (ADR 0079 § Application guest operations).
 *
 * **Core's gate ([ShareGuestGate.writeGate]) runs first and stays in core, in this order:** active share, password,
 * the role's upload capability, the guest identity's token; then the target, this writer, the link's admission and
 * [admitsGuestRole] for every item; then, for a start only, the per-share upload budget — last, so a start refused for
 * its own request spends none of it (inbox #367) — and only then the call here. Every method is therefore reached by
 * an already-authorized guest; the share is named by [scopeId] and the guest by [identityId], the same two values the
 * gate resolved.
 *
 * Whether a kind may be a share root at all is not this seam's question but [ShareRoots]' (ADR 0080 decision 6), so a
 * target's kind may have no implementation here — a `FOLDER` has none: a folder link's guests upload into the Memories
 * and the files below it, never into a folder. Core then fails closed: a guest's start or window into such a node is
 * `share_not_contributable`, and so is one into a kind more than one implementation [supports] (`SharingHandler.writesFor`).
 */
interface ShareGuestWrites {
    /** Whether a guest's uploads into a node of [nodeKind] go through this implementation. */
    fun supports(nodeKind: String): Boolean

    /**
     * Whether a guest may upload a blob in [role] — the contract's open `BlobRole` token — into a node this
     * implementation [supports]. Core names no role: it asks this for every declared item before [start] and refuses
     * the whole request `400 invalid_request` on a single no (as the contract's closed enum did until the role opened,
     * cr-files-application P3-S2) — before the share's upload budget is spent, as the enum's 400 was. No default: an application that takes guest uploads says which roles it takes.
     */
    fun admitsGuestRole(role: String): Boolean

    /** Reserve and open the guest's uploads for the link [shareId] into a node of [targetKind] (quota and per-link limit
     *  are core's reservation, the link's usage measured by the [targetKind]'s `UploadAdmission`). */
    fun start(
        scopeId: String, shareId: String, targetKind: String, identityId: String, declares: List<UploadDeclare>, domain: String?,
    ): List<UploadItemResult>

    /** Sign the next window of the sessions [identityId] opened. */
    fun window(scopeId: String, identityId: String, requests: List<WindowRequest>, domain: String?): List<WindowItemResult>
}
