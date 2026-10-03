package com.veyq.app.core.sharing

import com.veyq.app.core.blob.CompleteItem
import com.veyq.app.core.blob.CompleteItemResult
import com.veyq.app.core.blob.UploadDeclare
import com.veyq.app.core.blob.UploadItemResult
import com.veyq.app.core.blob.WindowItemResult
import com.veyq.app.core.blob.WindowRequest

/**
 * What an application does when a guest of a share writes under its root — the action body of the four guest write
 * calls (start, window, complete, delete-own). Core owns the gate and dispatches by the share root's node kind
 * ([supports]); the application owns what is written (today: Memories' media rows, upload sessions and quota).
 *
 * **Core's gate runs first and stays in core, in this order:** active share, password, the role's upload capability,
 * the guest identity's token, the per-share rate limit (start and complete only), and only then the call here. Every
 * method is therefore reached by an already-authorized guest; the share is named by [scopeId] and the guest by
 * [identityId], the same two values the gate resolved.
 *
 * A node kind that no implementation [supports] cannot be shared with guest writes: core refuses to create such a
 * share (`illegal_scope`), so a stored share's root kind always has exactly one implementation.
 */
interface ShareGuestWrites {
    /** Whether a share rooted at a node of [nodeKind] accepts guest writes through this implementation. */
    fun supports(nodeKind: String): Boolean

    /** Reserve and open the guest's uploads for the link [shareId] (quota and per-link limit are the application's). */
    fun start(scopeId: String, shareId: String, identityId: String, declares: List<UploadDeclare>, domain: String?): List<UploadItemResult>

    /** Sign the next window of the sessions [identityId] opened. */
    fun window(scopeId: String, identityId: String, requests: List<WindowRequest>, domain: String?): List<WindowItemResult>

    /**
     * Attach the guest's uploads to the share root. [admitted] re-checks the link under the row lock its delete holds;
     * an item whose attach finds it false answers `upload_not_found`.
     */
    fun complete(scopeId: String, items: List<CompleteItem>, identityId: String, admitted: () -> Boolean): List<CompleteItemResult>

    /**
     * Delete one contribution [identityId] owns, by a single-row ownership check. False when there is no such
     * contribution owned by this identity (a non-enumerable 404 for the caller).
     */
    fun deleteOwn(scopeId: String, mediaId: String, identityId: String): Boolean
}
