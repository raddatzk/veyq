package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.sharing.GuestAccess

// Depot's part of a link's guest access (ADR 0150 decision 5): a file's Replace, as extensions of core's [GuestAccess], so
// every client calls it where it did (Swift's `access.replace(file:source:)`, a category of `GuestAccess`).

/**
 * A GuestAccess.READ_WRITE guest's Replace of [file]'s content with [source] (the web's share page: `guestFileUploadJob` with a
 * `replace` target), queued on this link's own [queue] — never a tenant's: signed out or in, the link writes as its
 * guest (#1035). The page reads the file anew once it settled. False — nothing queued, [source] the caller's to close —
 * on a READ link, before the guest named itself, without a queue, or where Replace was not chosen on [file]
 * (`FileModel.chooseReplace`, the choice then left standing).
 */
fun GuestAccess.replace(file: FileModel, source: UploadSource): Boolean {
    if (queue == null) return false
    if (role != GuestAccess.READ_WRITE || session.value == null) return false
    val job = file.replace(source) ?: return false
    enqueue(listOf(job), source.name, file.node.nodeId, onSettled = { file.reload() })
    return true
}

/**
 * [replace] as the file page's Replace offers it to a GuestAccess.READ_WRITE guest ([FileRights]
 * alone, named or not; ADR 0154 decision 1): a guest not named yet is asked its name first ([naming]); false — nothing
 * queued, [source] the caller's to close — where it declined, or where [replace] refuses.
 */
suspend fun GuestAccess.replaceAsking(file: FileModel, source: UploadSource): Boolean {
    if (role != GuestAccess.READ_WRITE || queue == null) return false
    naming.request() ?: return false
    return replace(file, source)
}

