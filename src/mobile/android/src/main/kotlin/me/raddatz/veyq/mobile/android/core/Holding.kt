package me.raddatz.veyq.mobile.android.core

import kotlinx.coroutines.flow.StateFlow
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.navigation.AddHere
import me.raddatz.veyq.mobile.navigation.DropOutcome
import me.raddatz.veyq.mobile.navigation.DropTarget
import me.raddatz.veyq.mobile.navigation.Dropped
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.media.Derivation
import me.raddatz.veyq.mobile.navigation.MoveSheet
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.ShareInbox
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Notice

/**
 * What an application holds in the app's one model for the open tenant (ADR 0154 decision 3, inbox #1056): the models of
 * its pages on screen, its dialogs held across a rotation, its uploads. The application registers it ([ApplicationUi.holding])
 * and reaches its own through [AppModel.held]; the core UI names no application's model, it asks every holding the
 * questions below — each a moment of the tenant's life or a core flow an application answers for its own kinds. A
 * question an application does not answer keeps its default: nothing to close, not its target.
 */
internal abstract class Holding {
    /** Another tenant is about to be shown (or the same one again): what was open of the one before closes. */
    open fun leave() {}

    /** The tenant [tenant] is shown over [shell] (core's): its models are built ([switched]: another tenant than before). */
    open fun shown(shell: ShellModel, tenant: TenantSession, switched: Boolean) {}

    /** The session locked, signed out or is starting again (the gate left Signed): the tenant's dialogs and sections go. */
    open fun locked() {}

    /** The account shown signed out and no other opened: everything of the tenant closes. */
    open fun signedOut() {}

    /** A person's profile opened anew: a section of it held for a rotation is read anew. */
    open fun personOpened() {}

    /** The lists were read anew: a page on screen reads itself anew. */
    open fun listsRead() {}

    /** The last line each of this application's rows says, by node id (a Memory's date), read off the main thread. */
    open suspend fun rowLines(shell: ShellModel): Map<String, String> = emptyMap()

    /** Node [ref] went to the trash: a page of it this application holds open closes. */
    open fun trashed(ref: NodeAt) {}

    /** [node]'s move or copy sheet where its copy is this application's (a Pot's, by the open Pot); null otherwise. */
    open fun moveSheet(shell: ShellModel, node: NodeAt, copying: Boolean, changed: () -> Unit): MoveSheet? = null

    /** *Add here* on [target] where the place is this application's, its batch named [label]; null where it is another's. */
    open fun addHere(target: AddHere, label: String): AddedHere? = null

    /** Files dropped onto [target] where it is this application's; null where it is another's. */
    open suspend fun drop(shell: ShellModel, target: DropTarget, items: List<Dropped>): DropOutcome? = null

    /** Whether files dropped on the app go straight into [open], the object on screen (an open Pot's new expense). */
    open fun takesDrop(open: ObjectRef, incoming: ShareInbox.Incoming): Boolean = false

    /** The shell's uploads into a level — the tray, New's *Upload files* and *Upload folder* — where this application keeps files. */
    open val levelUploads: LevelUploads? get() = null

    /** The tiers of a picked photo or video where this application derives them (a link guest's upload uses them too). */
    open val deriver: Derivation.Deriver? get() = null
}

/** The key an application reaches its [Holding] by: [make] builds it once per model. */
internal class HoldingKey<T : Holding>(val make: (AppModel) -> T)

/** What *Add here* did on the place an application answers for: [notice] what it left out, or null. */
internal class AddedHere(val notice: Notice?)

/** What the shell's tray shows: the upload queue and a folder still being prepared ([preparing], its name). */
internal class TrayShown(val queue: UploadQueue.State, val preparing: String?)

/**
 * The shell's uploads into a level (core UI's tray and New, ADR 0154 decision 3), as the application that keeps files
 * answers them: what the tray shows ([tray]: null while no tenant is open) and its actions, and the picked files and
 * folders New puts under the level shown.
 */
internal interface LevelUploads {
    val tray: StateFlow<TrayShown?>
    fun cancelAll()
    fun acknowledge(batchId: String)
    fun cancel(id: String)
    fun retry(id: String)

    /** The picked [uris] queued as new files under [parentId] of [scopeId] ([label] the level's name). */
    fun upload(scopeId: String, parentId: String?, label: String, uris: List<android.net.Uri>)

    /** The directory the system's picker granted ([tree]) uploaded whole under [parentId] of [scopeId]. */
    fun uploadFolder(scopeId: String, parentId: String?, tree: android.net.Uri)

    /**
     * A READ_WRITE link guest's *Upload folder* into [level] (core's `GuestLevel.creates`, owner 2026-11-22): the directory
     * [tree] walked and uploaded as the guest; what it says handed to [say] (each line, none dropped).
     */
    fun guestUploadFolder(level: me.raddatz.veyq.mobile.sharing.GuestLevel, tree: android.net.Uri, say: suspend (List<me.raddatz.veyq.mobile.words.Notice>) -> Unit)
}

/**
 * "Send a copy…" of file [node] (its application's header action): what the copy is called and its type ([named], read
 * when the person confirms; null where the file has no content now).
 */
internal class SendCopy(val node: NodeAt, val named: () -> Pair<String, String>?)

/** A page on screen that answers *Add here* itself (a Memory's or a chapter's): its place on [shell], or null. */
internal interface AddHerePage {
    /** What the place depends on (the page's rights and reach): the banner asks [at] again each time it moves. */
    val changes: StateFlow<*>

    fun at(shell: me.raddatz.veyq.mobile.navigation.Shell): AddHere?
}
