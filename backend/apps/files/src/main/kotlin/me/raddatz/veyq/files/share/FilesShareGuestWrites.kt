package me.raddatz.veyq.files.share

import me.raddatz.veyq.core.blob.BlobUploadHandler
import me.raddatz.veyq.core.blob.UploadDeclare
import me.raddatz.veyq.core.blob.UploadItemResult
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.blob.WindowItemResult
import me.raddatz.veyq.core.blob.WindowRequest
import me.raddatz.veyq.core.sharing.ShareGuestWrites
import me.raddatz.veyq.files.FilesNodeKinds
import me.raddatz.veyq.files.content.FilesBlobRoles
import jakarta.enterprise.context.ApplicationScoped

/**
 * The Files half of a guest's upload on a share (cr-files-application F2, ADR 0080 decision 6): a `READ_WRITE` guest of a
 * folder link uploads into a `FILE` below the root — one it created with core's `createShareNode`, or one already
 * there, whose blob it replaces; and a drop-box guest (F3, a `WRITE_ONLY` link on a `FILES_DROPBOX`) into a `FILE` it
 * created in its own identity folder (core's `ShareGuestGate.target` lets a `WRITE_ONLY` link name nothing else below
 * its root). A `FILE` is never a share root, so a guest's target is always a node it named below the root; the drop box
 * itself takes no upload (no writer supports it). The seam half only forwards core's guest transport
 * to core's generic upload, as Memories' does; what the upload becomes is `ShareFilesHandler.complete`, through core's
 * completion dispatcher to `FilesUploadCompletion`.
 *
 * A guest's attachment names its identity (`share_identity_id`), which core's own `AttachmentGuestIdentityUsage` and
 * the per-link usage term already count: Files stores no row of its own naming a guest, so it ships no
 * `GuestIdentityUsage` (the rule of that interface's KDoc is met by core's).
 */
@ApplicationScoped
class FilesShareGuestWrites(private val uploads: BlobUploadHandler) : ShareGuestWrites {

    override fun supports(nodeKind: String): Boolean = nodeKind == FilesNodeKinds.FILE

    // A file holds one blob in one role; nothing else is a guest's to upload into it.
    override fun admitsGuestRole(role: String): Boolean = role == FilesBlobRoles.FILE_CONTENT

    override fun start(
        scopeId: String, shareId: String, targetKind: String, identityId: String, declares: List<UploadDeclare>, domain: String?,
    ): List<UploadItemResult> =
        uploads.startForLink(scopeId, shareId, targetKind, identityId, declares, domain)

    override fun window(scopeId: String, identityId: String, requests: List<WindowRequest>, domain: String?): List<WindowItemResult> =
        uploads.windowForScope(scopeId, UploadOpener.Contributor(identityId), requests, domain)
}
