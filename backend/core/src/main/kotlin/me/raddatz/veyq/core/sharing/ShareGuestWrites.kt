package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.blob.UploadDeclare
import me.raddatz.veyq.core.blob.UploadItemResult
import me.raddatz.veyq.core.blob.WindowItemResult
import me.raddatz.veyq.core.blob.WindowRequest

/**
 * What an application does when a guest of a share moves bytes under its root — the action body of core's two guest
 * transport calls (start, window). Core owns the gate and dispatches by the share root's node kind ([supports]); the
 * application owns what is reserved and signed (today: Memories' upload sessions and quota). What a guest's upload
 * then *becomes*, and deleting it again, are the application's own operations under the share's path (Memories'
 * `share-media`): they call [ShareGuestGate.writeGate] themselves (ADR 0079 § Application guest operations).
 *
 * **Core's gate ([ShareGuestGate.writeGate]) runs first and stays in core, in this order:** active share, password,
 * the role's upload capability, the guest identity's token, the per-share rate limit (start only), and only then the
 * call here. Every method is therefore reached by an already-authorized guest; the share is named by [scopeId] and
 * the guest by [identityId], the same two values the gate resolved.
 *
 * Whether a kind may be a share root at all is not this seam's question but [ShareRoots]' (ADR 0080 decision 6), so a
 * stored share's root kind may have no implementation here — a `FOLDER` shared with `READ_WRITE` has none today.
 * Core then fails closed: a guest's start or window on such a link is `share_not_contributable`, and so is one on a
 * kind more than one implementation [supports] (`SharingHandler.writesFor`).
 */
interface ShareGuestWrites {
    /** Whether a share rooted at a node of [nodeKind] accepts guest writes through this implementation. */
    fun supports(nodeKind: String): Boolean

    /** Reserve and open the guest's uploads for the link [shareId] (quota and per-link limit are the application's). */
    fun start(scopeId: String, shareId: String, identityId: String, declares: List<UploadDeclare>, domain: String?): List<UploadItemResult>

    /** Sign the next window of the sessions [identityId] opened. */
    fun window(scopeId: String, identityId: String, requests: List<WindowRequest>, domain: String?): List<WindowItemResult>
}
