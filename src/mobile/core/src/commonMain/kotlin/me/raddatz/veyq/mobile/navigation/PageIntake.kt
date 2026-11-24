package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.words.Notice

/**
 * A page that takes files put onto it (ADR 0154 decision 1, ADR 0138 decisions 3 and 9): a drop ([DropTarget.Page]) and the
 * pick mode's *Add here* ([AddHere.Page]) reach it through this, so the platform judges and sorts a drop without naming the
 * application whose page it is. Memories' gallery (a Memory's or a chapter's page) and its Contributions view's bucket
 * implement it (ADR 0156, inbox #1215).
 */
interface PageIntake {
    /** The page's node: *Add here* offers it only while it is the shell's open object. */
    val node: NodeAt

    /** What of a drop or a share the page keeps (Memories': photos and videos). */
    val answer: ShareAnswer

    /** Whether a drop lands here now ([Intake]). */
    fun dropIntake(): Intake

    /** The [sources] the page keeps, uploaded into it; how many were queued. */
    fun upload(sources: List<UploadSource>): Int

    /** What is said of the folders [names] dropped onto the page, which it does not walk (one notice each). */
    fun foldersNotTaken(names: List<String>): List<Notice>

    /** What is said of [count] dropped files [answer] did not keep; null where every one was kept. */
    fun filesNotTaken(count: Int): Notice?

    /** Whether the page offers *Add here* for what waits in [inbox]. None by default (a bucket offers none). */
    fun offersShare(inbox: ShareInbox): Boolean = false

    /** *Add here* on the page: what waits in [inbox] that it keeps, queued; null where it takes none now. */
    fun takeShare(inbox: ShareInbox): ShareInbox.Taken? = null

    /** Whether a drop lands on the page now. */
    sealed class Intake {
        /** It does: sorted through [answer] and uploaded. */
        data object Takes : Intake()

        /** It does not, and [notice] says why (another uploader's bucket, offline). */
        class Refused(val notice: Notice) : Intake()

        /** It takes nothing here (no right): the drop's ordinary refusal. */
        data object NotHere : Intake()
    }
}
