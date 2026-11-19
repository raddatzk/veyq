package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.navigation.ShareAnswer
import me.raddatz.veyq.mobile.navigation.ShareInbox

/**
 * What a Memory and a chapter take when something is shared to veyq or dropped onto it (ADR 0138 decisions 2 and 3):
 * photos and videos ([MediaUpload.isMedia]), with no bound beyond the upload's own, queued into the app's one upload queue
 * ([ShareAnswer.How.QUEUE]) where the member may upload there. Memories' answer; core asks it through `AppKinds.share`.
 *
 * In the pick mode a Memory's page, and each chapter's, offers *Add here* ([offered]), its own save: [addHere] queues the
 * files through the page's [GalleryModel.upload] and ends the share.
 */
object MemoryShare {
    val answer = ShareAnswer(ShareAnswer.How.QUEUE, { ShareAnswer.Intake { type, name, _ -> MediaUpload.isMedia(type, name) } }) { place ->
        place.online && MemoryRights.of(
            MemoryRights.Access.Member(memoriesOn = "memories" !in place.applicationsOff, inIdentityFolder = place.inIdentityFolder),
            place.kind,
        ).upload
    }

    /** Whether [gallery]'s page offers *Add here* for what waits in [inbox]: the member may upload there and a file is a medium. */
    fun offered(inbox: ShareInbox, gallery: GalleryModel): Boolean {
        val files = inbox.waiting.value?.files ?: return false
        return gallery.takesUploads && answer.split(files, { it.type }, { it.name }, { it.size }).first.isNotEmpty()
    }

    /**
     * *Add here* on [gallery]'s page: the waiting photos and videos queued into its Memory or chapter, the rest let go, the
     * share ended. What was queued and left out, or null where the page takes none (nothing waits, no right, offline).
     */
    fun addHere(inbox: ShareInbox, gallery: GalleryModel): ShareInbox.Taken? {
        if (!gallery.takesUploads) return null
        return inbox.queue(answer) { gallery.upload(it) }
    }
}
