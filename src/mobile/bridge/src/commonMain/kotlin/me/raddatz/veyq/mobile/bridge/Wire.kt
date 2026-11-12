package me.raddatz.veyq.mobile.bridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.expenses.EditorChange
import me.raddatz.veyq.mobile.expenses.EntryEditor
import me.raddatz.veyq.mobile.expenses.EntryForm
import me.raddatz.veyq.mobile.expenses.PotModel
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ArrangedList
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.FolderRef
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece
import me.raddatz.veyq.mobile.session.CoverFrame
import me.raddatz.veyq.mobile.session.NodeCover

/**
 * What crosses the bridge (ADR 0123's Consequences, the bridge), as kotlinx.serialization writes it: UTF-8 JSON, every
 * field present (a default too, a null as `null`), a sealed class tagged by `type` with its serial name. The host's
 * types are generated from these descriptors (`Bindings.kt`), so what is written here is what the host reads — and a
 * call the host sends with a field this side does not know is refused, never half-read.
 */
internal val wire = Json {
    encodeDefaults = true
    explicitNulls = true
    classDiscriminator = "type"
}

/**
 * What the host starts the core with ([veyq_init]): [device] the name this device gives itself in the account's device
 * register; [window] Windows' HWND of the host's top-level window, which owns Windows Hello's prompt (`0` elsewhere, and
 * for a prompt without an owner); [prompt] the words that prompt shows (the host's `device_unlock_prompt`, empty where
 * none shows). None of them secret: the device keys themselves are the core's (`platformDeviceKeys`).
 */
@Serializable
internal class Config(val device: String, val window: Long = 0, val prompt: String = "")

/**
 * One action of the host ([veyq_call]): each a model's own function, dispatched by [Bridge] one line per case. The
 * sign-in's password and Secret Key are what the person typed, on their way to the core — the one place something
 * secret crosses, inward, and never back.
 */
@Serializable
internal sealed class Call {
    /** The way in, once per process: the trusted accounts resume, or the sign-in shows (SessionModel.start). */
    @Serializable @SerialName("Session.Start") data object SessionStart : Call()

    /**
     * The full sign-in, which holds the password trust; where the device offers a level, the shell's gate then carries
     * the offer to stay signed in (`Gate.Signed.offer`), answered by [SessionStaySignedIn] or [SessionNotNow].
     */
    @Serializable @SerialName("Session.SignIn")
    data class SessionSignIn(val server: String, val email: String, val password: String, val secretKey: String) : Call()

    /**
     * The offer's answer: stay signed in at [level], one of the offer's. [Answer.Done]; [Answer.Why.TRUST_CANCELLED] when
     * the person cancelled the prompt (the offer stands); [Answer.Why.TRUST_FAILED] when it was not enrolled (the offer
     * ends, the password trust stays); [Answer.Why.NO_OFFER] without an offer.
     */
    @Serializable @SerialName("Session.StaySignedIn") data class SessionStaySignedIn(val level: StaySignedIn) : Call()

    /** The offer's "Not now": the password trust stays and the account is not offered again on this device. */
    @Serializable @SerialName("Session.NotNow") data object SessionNotNow : Call()

    /**
     * The unlock of the trusted account `Gate.Unlock` shows: its [password] for a DEVICE record; for DEVICE_KEY `""`, the
     * core asking the person itself (Windows Hello's prompt). A cancelled prompt leaves the unlock standing.
     */
    @Serializable @SerialName("Session.Unlock") data class SessionUnlock(val password: String) : Call()

    /** From the unlock to the full sign-in, for another account. */
    @Serializable @SerialName("Session.OtherAccount") data object SessionOtherAccount : Call()

    /** "Stop trusting this device" for the connection whose key is [connection]: the next sign-in asks for the Secret Key. */
    @Serializable @SerialName("Session.ForgetDevice") data class SessionForgetDevice(val connection: String) : Call()

    /** "Add server": the sign-in again, which [SessionCancelAdd] leaves back to the shell. */
    @Serializable @SerialName("Session.AddServer") data object SessionAddServer : Call()
    @Serializable @SerialName("Session.CancelAdd") data object SessionCancelAdd : Call()

    /** The switcher: tenant [tenantId] of the connection whose key is [connection] (`Gate.Signed.connectionKey`). */
    @Serializable @SerialName("Session.SwitchTo") data class SessionSwitchTo(val connection: String, val tenantId: String) : Call()

    @Serializable @SerialName("Session.SignOut") data class SessionSignOut(val connection: String) : Call()

    /**
     * "Manage servers" opened: [Watched.Manage] delivers every server with its trust from now on, each tenant's spaces as
     * they read; [ManageClose] ends it (null again).
     */
    @Serializable @SerialName("Manage.Open") data object ManageOpen : Call()
    @Serializable @SerialName("Manage.Close") data object ManageClose : Call()

    /**
     * "Manage servers"' switches (`Hidden`): tenant [tenantId] of the connection whose key is [connection] left out of the
     * switcher on this device while [hidden], or space [scopeId] left out of the whole app. Nothing is hidden from the
     * server. The shell and "Manage servers" follow.
     */
    @Serializable @SerialName("Manage.HideTenant") data class ManageHideTenant(val connection: String, val tenantId: String, val hidden: Boolean) : Call()
    @Serializable @SerialName("Manage.HideSpace") data class ManageHideSpace(val scopeId: String, val hidden: Boolean) : Call()

    /**
     * "Manage servers"' change of trust for the connection whose key is [connection]: stay signed in at [level] (one of
     * [ManagedShown.levels]), replacing how this device trusts it. Answered as [SessionStaySignedIn] is.
     */
    @Serializable @SerialName("Manage.StaySignedIn") data class ManageStaySignedIn(val connection: String, val level: StaySignedIn) : Call()

    /**
     * Back to the password trust for the connection whose key is [connection]: the person's [password], checked first
     * ([Answer.Why.WRONG_PASSWORD]) — what they typed, on its way to the core, as `Session.SignIn`'s is.
     */
    @Serializable @SerialName("Manage.PasswordTrust") data class ManagePasswordTrust(val connection: String, val password: String) : Call()

    @Serializable @SerialName("Shell.Choose") data class ShellChoose(val application: ApplicationId?) : Call()
    @Serializable @SerialName("Shell.SwitchSpace") data class ShellSwitchSpace(val scopeId: String) : Call()
    @Serializable @SerialName("Shell.Enter") data class ShellEnter(val folder: FolderRef) : Call()
    @Serializable @SerialName("Shell.Filter") data class ShellFilter(val application: ApplicationId?) : Call()
    @Serializable @SerialName("Shell.Back") data object ShellBack : Call()
    @Serializable @SerialName("Shell.Select") data class ShellSelect(val tab: BarTab) : Call()
    @Serializable @SerialName("Shell.Open") data class ShellOpen(val ref: ObjectRef) : Call()
    @Serializable @SerialName("Shell.OpenAtTop") data class ShellOpenAtTop(val ref: ObjectRef) : Call()
    @Serializable @SerialName("Shell.Search") data class ShellSearch(val on: Boolean) : Call()
    @Serializable @SerialName("Shell.Refresh") data object ShellRefresh : Call()

    /** Home's level where the shell is, in its groups (`ShellModel.Lists.groups`). */
    @Serializable @SerialName("Home.Groups") data object HomeGroups : Call()

    /** Search's hits over what the shell lists (`ShellWords.search`). */
    @Serializable @SerialName("Search.Query") data class SearchQuery(val query: String) : Call()

    /** Opens Pot [ref] as a model of its own, answered with its id ([Answer.PotOpened]) for [Watched.Pot]. */
    @Serializable @SerialName("Pot.Open") data class PotOpen(val ref: ObjectRef) : Call()
    /** Reads Pot [pot] again now, its receipts too (`PotModel.refresh`: a desktop's Refresh, F5 / Ctrl+R). */
    @Serializable @SerialName("Pot.Refresh") data class PotRefresh(val pot: Long) : Call()
    @Serializable @SerialName("Pot.Close") data class PotClose(val pot: Long) : Call()

    /**
     * Records settle-up [rows] of Pot [pot] as paid (`PotModel.record`: one row of the view's `settleUp`, or all of
     * them), dated the core's today: [Answer.Said] with the refusal, or none (stored).
     */
    @Serializable @SerialName("Pot.Record") data class PotRecord(val pot: Long, val rows: List<PotView.SettleRow>) : Call()

    /**
     * Restores deleted entry [entry] of Pot [pot] (`PotModel.restore`, its window on the core's clock): [Answer.Said]
     * with why not (outside its window nothing is sent), or none (stored).
     */
    @Serializable @SerialName("Pot.Restore") data class PotRestore(val pot: Long, val entry: String) : Call()

    /** Entry [entry]'s history in Pot [pot] and whether it is restored now ([Answer.EntryHistory]). */
    @Serializable @SerialName("Pot.History") data class PotHistory(val pot: Long, val entry: String) : Call()

    /**
     * The entry editor (core's `EntryEditor`, `PotModel.editor`) of live entry [entry] of Pot [pot], or of a new entry
     * (null: dated the core's today); [separator] is the reader's decimal separator (the platform's locale data, one
     * character), as amounts are typed. Answered with its id ([Answer.EditorOpened]) for [Watched.Editor]; refused with
     * `NO_SUCH_ENTRY` where the core opens none (no such live entry, a Pot not read or without a base currency).
     */
    @Serializable @SerialName("Editor.Open") data class EditorOpen(val pot: Long, val entry: String?, val separator: String) : Call()

    /**
     * One edit of editor [editor]'s form (`EntryEditor.change`). Applied the moment the host calls, in the order it called
     * (two keystrokes' edits never land the wrong way round); what follows arrives on [Watched.Editor].
     */
    @Serializable @SerialName("Editor.Change") data class EditorChange(val editor: Long, val change: me.raddatz.veyq.mobile.expenses.EditorChange) : Call()

    /**
     * Saves editor [editor]'s form (`EntryEditor.save`), answered once the write ended ([Answer.EditorSaved]): done — the
     * host closes the editor at once, saying the notice where there is one (receipts left out) — or not, with why (a
     * stale save shows the other writer's version now; a form that cannot be saved shows its refusal at its field).
     */
    @Serializable @SerialName("Editor.Save") data class EditorSave(val editor: Long) : Call()

    /** Asks for editor [editor]'s entry's delete (`EntryEditor.askDelete`): nothing is written yet. */
    @Serializable @SerialName("Editor.AskDelete") data class EditorAskDelete(val editor: Long) : Call()

    /** Deletes editor [editor]'s entry once asked (`EntryEditor.delete`), answered as a save is ([Answer.EditorSaved]). */
    @Serializable @SerialName("Editor.Delete") data class EditorDelete(val editor: Long) : Call()

    /** The delete asked is not wanted (`EntryEditor.cancelAsk`). */
    @Serializable @SerialName("Editor.CancelAsk") data class EditorCancelAsk(val editor: Long) : Call()

    /** Editor [editor] is gone (`EntryEditor.close`); a rate still being asked is forgotten. */
    @Serializable @SerialName("Editor.Close") data class EditorClose(val editor: Long) : Call()

    /**
     * Files picked as editor [editor]'s receipts by their [paths], as the host's open dialog gave them (ADR 0128 decision 3):
     * the core opens each and reads at most `Receipts.MAX_BYTES` of it (nothing of its content crosses), names it by its
     * path and types it by its bytes (`Receipts.picked`), and the editor holds each it takes until the save
     * (`EntryEditor.addReceipts`); one it refuses — no image or PDF, too large, the 101st, a path that does not open —
     * shows as [EditorShown.refusedPick]. Answered once read.
     */
    @Serializable @SerialName("Editor.AddReceipts") data class EditorAddReceipts(val editor: Long, val paths: List<String>) : Call()

    /** Receipt [receipt] taken off editor [editor]'s entry (`EntryEditor.dropReceipt`), applied in the order called. */
    @Serializable @SerialName("Editor.DropReceipt") data class EditorDropReceipt(val editor: Long, val receipt: String) : Call()

    /**
     * Receipt [receipt] of Pot [pot] opened to its bytes (`PotModel.openReceipt`), answered as [Answer.Bytes] with what the
     * bytes are (`Receipts.mediaTypeOf`: an image's type, `application/pdf`, or null — a file only saved); refused
     * `NO_RECEIPT` where it does not open (blocked, not held, not read). The host decodes it in memory, never from a file.
     */
    @Serializable @SerialName("Receipt.Open") data class ReceiptOpen(val pot: Long, val receipt: String) : Call()

    /** How many of a PDF of [total] pages a viewer renders up front, and what it says under them ([Answer.ReceiptPages]). */
    @Serializable @SerialName("Receipt.Pages") data class ReceiptPages(val total: Int) : Call()

    /**
     * Receipt [receipt] of Pot [pot] saved into the file at [path], which the host's save dialog gave (ADR 0128 decision 3:
     * the core opens and writes it; nothing is left there on a failure): done, or refused `NO_RECEIPT` (it does not open)
     * or `NOT_WRITTEN` (the file was not written).
     */
    @Serializable @SerialName("Receipt.Save") data class ReceiptSave(val pot: Long, val receipt: String, val path: String) : Call()

    /**
     * The settings and people of Pot [pot] (core's `PotSettings`, `PotModel.settings`), answered with their id
     * ([Answer.PotSettingsOpened]) for [Watched.PotSettings]; refused `NO_SUCH_POT` while the Pot is not read. They close
     * with [PotSettingsClose] or with their Pot.
     */
    @Serializable @SerialName("PotSettings.Open") data class PotSettingsOpen(val pot: Long) : Call()
    @Serializable @SerialName("PotSettings.Close") data class PotSettingsClose(val settings: Long) : Call()

    /**
     * Each change of the settings is one write of the open Pot, answered once it ended ([Answer.Stored]: whether it was
     * stored — a host empties its field on that —, and why not). The typed text comes with the call: no draft is the
     * core's. The base currency [code] (`setBaseCurrency`: refused once the Pot holds money).
     */
    @Serializable @SerialName("PotSettings.Currency") data class PotSettingsCurrency(val settings: Long, val code: String) : Call()
    @Serializable @SerialName("PotSettings.AddCategory") data class PotSettingsAddCategory(val settings: Long, val name: String) : Call()
    @Serializable @SerialName("PotSettings.RenameCategory") data class PotSettingsRenameCategory(val settings: Long, val category: String, val name: String) : Call()
    @Serializable @SerialName("PotSettings.AddPerson") data class PotSettingsAddPerson(val settings: Long, val name: String) : Call()
    @Serializable @SerialName("PotSettings.AddMember") data class PotSettingsAddMember(val settings: Long, val userId: String) : Call()
    @Serializable @SerialName("PotSettings.Rename") data class PotSettingsRename(val settings: Long, val person: String, val name: String) : Call()

    /** Person [person] deactivated, or activated again (`toggleActive`). */
    @Serializable @SerialName("PotSettings.Toggle") data class PotSettingsToggle(val settings: Long, val person: String) : Call()

    /** Person [person]'s erase asked (`askErase`: [PotSettingsShown.asking]); nothing is written until [PotSettingsErase]. */
    @Serializable @SerialName("PotSettings.AskErase") data class PotSettingsAskErase(val settings: Long, val person: String) : Call()
    @Serializable @SerialName("PotSettings.Erase") data class PotSettingsErase(val settings: Long) : Call()
    @Serializable @SerialName("PotSettings.CancelAsk") data class PotSettingsCancelAsk(val settings: Long) : Call()

    /**
     * "New pot" where the shell is (core's `PotCreateSheet`, `PotModel.createSheet`), its currency the one of [language]
     * (the host's words' language or locale tag), answered with its id ([Answer.PotCreateOpened]) for [Watched.PotCreate].
     */
    @Serializable @SerialName("PotCreate.Open") data class PotCreateOpen(val language: String) : Call()
    @Serializable @SerialName("PotCreate.Close") data class PotCreateClose(val create: Long) : Call()

    /**
     * The name as typed, applied the moment the host calls, in the order it called (as an editor's edit): what
     * [PotCreateShown.canCreate] is judged on and [PotCreateCreate] creates. It is the host's text, never echoed.
     */
    @Serializable @SerialName("PotCreate.Name") data class PotCreateName(val create: Long, val name: String) : Call()
    @Serializable @SerialName("PotCreate.Space") data class PotCreateSpace(val create: Long, val scopeId: String) : Call()
    @Serializable @SerialName("PotCreate.Currency") data class PotCreateCurrency(val create: Long, val code: String) : Call()

    /** A person's name typed added (`addTyped`): [Answer.Stored] says whether there was one, so the host empties its field. */
    @Serializable @SerialName("PotCreate.AddTyped") data class PotCreateAddTyped(val create: Long, val name: String) : Call()
    @Serializable @SerialName("PotCreate.AddMember") data class PotCreateAddMember(val create: Long, val userId: String) : Call()

    /** The person at [index] of [PotCreateShown.people] taken out again. */
    @Serializable @SerialName("PotCreate.Remove") data class PotCreateRemove(val create: Long, val index: Int) : Call()

    /**
     * Creates the Pot under the name sent last, a name still [typed] in the people's field taken first (`create`):
     * [Answer.Stored] — stored, the Pot exists (the host closes; the shell opens it), with what to say where its people
     * were not added; not stored, why.
     */
    @Serializable @SerialName("PotCreate.Create") data class PotCreateCreate(val create: Long, val typed: String) : Call()

    /**
     * Downloads Pot [pot] as the web's ZIP into the file at [path], which the host's save dialog gave (ADR 0128
     * decision 3: the core opens it and writes it; nothing of the file crosses). [deletedName] is the host's word for
     * an erased person (`pot_deleted_person`), which the CSV carries. [Answer.Done], or [Answer.Failed] with nothing
     * left at [path].
     */
    @Serializable @SerialName("Pot.Download") data class PotDownload(val pot: Long, val path: String, val deletedName: String) : Call()

    /** The file name the save dialog suggests for Pot [pot]'s download ([Answer.Name]): the core's, never the host's. */
    @Serializable @SerialName("Pot.ArchiveName") data class PotArchiveName(val pot: Long) : Call()

    /** Member [userId]'s picture ([Answer.Bytes], the image beside the JSON); refused where the letter shows. */
    @Serializable @SerialName("Avatar.Picture") data class AvatarPicture(val userId: String) : Call()

    /** [length] bytes of reader [reader] from [position] ([Answer.Bytes]): fewer only at the end, none past it. */
    @Serializable @SerialName("Reader.Read") data class ReaderRead(val reader: Long, val position: Long, val length: Int) : Call()
    @Serializable @SerialName("Reader.Close") data class ReaderClose(val reader: Long) : Call()

    /**
     * The header of [node] (`ShellModel.header`, core's `NodeHeaderModel`) as a model of its own, answered with its id
     * ([Answer.HeaderOpened]) for [Watched.Header]: its kind's page knowing [facts] (null: not yet — a Pot not read), its
     * bar holding at most [maxButtons] buttons (the platform's answer, `NodeHeader.plan`). A header stays until
     * [HeaderClose] or the tenant closes, so a trash's undo outlives the page that left.
     */
    @Serializable @SerialName("Header.Open") data class HeaderOpen(val node: NodeAt, val facts: AppKinds.Facts?, val maxButtons: Int) : Call()

    /** What the kind's page knows now (`NodeHeaderModel.update`). */
    @Serializable @SerialName("Header.Facts") data class HeaderFacts(val header: Long, val facts: AppKinds.Facts?) : Call()
    @Serializable @SerialName("Header.Close") data class HeaderClose(val header: Long) : Call()

    /** Pins or unpins (`toggleFavorite`): [Answer.Said] with the failure's notice, or none. */
    @Serializable @SerialName("Header.Pin") data class HeaderPin(val header: Long) : Call()

    /**
     * To the trash (`trash`): [Answer.Trashed] with what it says and whether its undo is offered now (the state's undo,
     * read as the call returns: the host's toast takes its Undo at once, and goes when the state's undo is null again).
     */
    @Serializable @SerialName("Header.Trash") data class HeaderTrash(val header: Long) : Call()

    /** The trash's undo while offered (`undoTrash`): [Answer.Said] with why it failed, or none. */
    @Serializable @SerialName("Header.Undo") data class HeaderUndo(val header: Long) : Call()

    /** Renames the node (`rename`): [Answer.Renamed]. */
    @Serializable @SerialName("Header.Rename") data class HeaderRename(val header: Long, val name: String) : Call()

    /**
     * Downloads the header's node with everything under it as the web's ZIP into the file at [path], which the host's
     * save dialog gave (`ShellModel.download`; ADR 0128 decision 3: the core opens it, writes it and removes a download
     * that did not finish). [deletedName] is the host's word for an erased person, which a Pot's CSV carries.
     * [Answer.Downloaded]. The name the dialog suggests is [HeaderShown.archiveName].
     */
    @Serializable @SerialName("Header.Download") data class HeaderDownload(val header: Long, val path: String, val deletedName: String) : Call()

    /**
     * The header's cover picture as stored (`coverPicture`): [Answer.Bytes], the JPEG beside the JSON (at most
     * `NodeCover.LONG_EDGE` on its long edge, one payload as a picture is); refused where there is none. Its frame is
     * [HeaderShown.cover].
     */
    @Serializable @SerialName("Header.CoverPicture") data class HeaderCoverPicture(val header: Long) : Call()

    /**
     * Sets the cover (`saveCover`): the JPEG the host encoded to the core's spec ([Answer.CoverFit]) and wrote to the file at
     * [path] (ADR 0128 decision 3: the core reads it; the host removes its file after), framed by [crop] — or, [path]
     * null, the stored picture framed anew. [Answer.Said] with why not, or none.
     */
    @Serializable @SerialName("Header.CoverSave") data class HeaderCoverSave(val header: Long, val path: String?, val crop: NodeCover.Crop) : Call()

    /** Removes the cover (`removeCover`): [Answer.Said]. */
    @Serializable @SerialName("Header.CoverRemove") data class HeaderCoverRemove(val header: Long) : Call()

    /**
     * Where a picture of [imageWidth] × [imageHeight] sits in a cover's frame of [frameWidth] × [frameHeight] (core's
     * `CoverFrame`, the web's cropper; asked per change, the math never the host's): from [at] — or, none, from [crop] (a
     * stored cover reopens as framed) or centred (a fresh picture) — moved by [panX], [panY] and zoomed by [zoom] about the
     * frame's centre. [Answer.CoverPosed]: the pose to draw and the crop it shows.
     */
    @Serializable @SerialName("Cover.Pose") data class CoverPose(
        val imageWidth: Double, val imageHeight: Double, val frameWidth: Double, val frameHeight: Double,
        val at: CoverFrame.Pose?, val crop: NodeCover.Crop?, val panX: Double, val panY: Double, val zoom: Double,
    ) : Call()

    /** The size and quality a picked picture of [width] × [height] is encoded at as a cover (`NodeCover.encodedSize`). */
    @Serializable @SerialName("Cover.Fit") data class CoverFit(val width: Int, val height: Int) : Call()

    /**
     * The part of a cover picture of [width] × [height] pixels that [crop] shows (`CoverFrame.pixels`, the web's
     * `coverImageStyle`): what a page's head cuts out before it draws the cover ([Answer.CoverCut]).
     */
    @Serializable @SerialName("Cover.Cut") data class CoverCut(val crop: NodeCover.Crop, val width: Int, val height: Int) : Call()

    /**
     * The move ([copy] false) or copy sheet of [node] (`ShellModel.moveSheet`, core's `MoveSheet`, read at once), answered
     * with its id ([Answer.MoveOpened]) for [Watched.Move]. A Pot copies by the Pot model the host opened for it
     * (`Pot.Open`); [deletedName] is the host's word for an erased person, which a copied Pot carries. A sheet stays until
     * [MoveClose] or the tenant closes, so a move's undo outlives the dialog.
     */
    @Serializable @SerialName("Move.Open") data class MoveOpen(val node: NodeAt, val copy: Boolean, val deletedName: String) : Call()
    @Serializable @SerialName("Move.Close") data class MoveClose(val move: Long) : Call()

    /** Shows place [at] (null: the space's root): a crumb of the trail or a place listed (`go`). */
    @Serializable @SerialName("Move.Go") data class MoveGo(val move: Long, val at: String?) : Call()

    /** One place up the trail (`up`). */
    @Serializable @SerialName("Move.Up") data class MoveUp(val move: Long) : Call()

    /** "Move here" / "Copy here" (`choose`): [Answer.MoveSent]; a move ending a guest's folder asks first. */
    @Serializable @SerialName("Move.Here") data class MoveHere(val move: Long) : Call()

    /** The guest folder's confirm answered (`confirm`): [Answer.MoveSent]; or declined (`cancelConfirm`). */
    @Serializable @SerialName("Move.Confirm") data class MoveConfirm(val move: Long) : Call()
    @Serializable @SerialName("Move.CancelConfirm") data class MoveCancelConfirm(val move: Long) : Call()

    /** The move's undo while offered (`undo`): [Answer.Said] with why it failed, or none. */
    @Serializable @SerialName("Move.Undo") data class MoveUndo(val move: Long) : Call()

    /** A new folder where the shell is (`ShellModel.createFolderHere`): [Answer.Said] with why not, or none. */
    @Serializable @SerialName("Shell.CreateFolder") data class ShellCreateFolder(val name: String) : Call()

    /** A space's trash sheet (`ShellModel.trashSheet`, read at once), answered with its id ([Answer.TrashOpened]). */
    @Serializable @SerialName("Trash.Open") data class TrashOpen(val scopeId: String) : Call()
    @Serializable @SerialName("Trash.Close") data class TrashClose(val trash: Long) : Call()

    /** Restore of the row listed as [row] (`TrashSheet.restore`); where its name was taken the row asks for another. */
    @Serializable @SerialName("Trash.Restore") data class TrashRestore(val trash: Long, val row: String) : Call()

    /** The asking row's new name as typed (`TrashSheet.type`), and its restore under it (`retry`); or no new name. */
    @Serializable @SerialName("Trash.Type") data class TrashType(val trash: Long, val name: String) : Call()
    @Serializable @SerialName("Trash.Retry") data class TrashRetry(val trash: Long, val row: String) : Call()
    @Serializable @SerialName("Trash.CancelRename") data class TrashCancelRename(val trash: Long) : Call()

    /** "Delete forever" of the row the person confirmed (`TrashSheet.purge(row)`): the host asks first. */
    @Serializable @SerialName("Trash.Purge") data class TrashPurge(val trash: Long, val row: String) : Call()

    /**
     * The share sheet of [node] (`ShellModel.shareSheet`, core's `ShareSheet`, its links read at once), answered with its
     * id ([Answer.ShareOpened]) for [Watched.Share]. It stays until [ShareClose] or the tenant closes.
     */
    @Serializable @SerialName("Share.Open") data class ShareOpen(val node: NodeAt) : Call()
    @Serializable @SerialName("Share.Close") data class ShareClose(val share: Long) : Call()

    /** "New link": the form opens (`addLink`). */
    @Serializable @SerialName("Share.Add") data class ShareAdd(val share: Long) : Call()

    /** "Edit" on the link listed as [link] (its share id): the form opens with its settings (`edit`). */
    @Serializable @SerialName("Share.Edit") data class ShareEdit(val share: Long, val link: String) : Call()

    /** The form's changes as the person made them, one each (`LinkForm.with…` through the sheet), and its close. */
    @Serializable @SerialName("Share.Role") data class ShareRole(val share: Long, val role: String) : Call()
    @Serializable @SerialName("Share.Label") data class ShareLabel(val share: Long, val label: String) : Call()
    @Serializable @SerialName("Share.LockOn") data class ShareLockOn(val share: Long, val on: Boolean) : Call()

    /** The link's password as typed, on its way to the core; the state says only whether it is long enough. */
    @Serializable @SerialName("Share.Password") data class SharePassword(val share: Long, val password: String) : Call()
    @Serializable @SerialName("Share.EndOn") data class ShareEndOn(val share: Long, val on: Boolean) : Call()

    /** The end as a local day, days since 1970-01-01 ([ShareShown.ShownForm.ShownEnd.day]). */
    @Serializable @SerialName("Share.EndDay") data class ShareEndDay(val share: Long, val day: Long) : Call()
    @Serializable @SerialName("Share.CancelForm") data class ShareCancelForm(val share: Long) : Call()

    /** The form sent (`save`): [Answer.Said] with why it did not go through, or none. */
    @Serializable @SerialName("Share.Save") data class ShareSave(val share: Long) : Call()

    /** The address shown once was seen ("Done", `freshSeen`). */
    @Serializable @SerialName("Share.FreshSeen") data class ShareFreshSeen(val share: Long) : Call()

    /** "Renew link" / "Delete link" on [link]: asked first (`askRenew`, `askDelete`), the question the sheet's state. */
    @Serializable @SerialName("Share.AskRenew") data class ShareAskRenew(val share: Long, val link: String) : Call()
    @Serializable @SerialName("Share.AskDelete") data class ShareAskDelete(val share: Long, val link: String) : Call()
    @Serializable @SerialName("Share.CancelAsk") data class ShareCancelAsk(val share: Long) : Call()

    /** The question answered (`renew`, `delete`): [Answer.Said] with what it left to say. */
    @Serializable @SerialName("Share.Renew") data class ShareRenew(val share: Long) : Call()
    @Serializable @SerialName("Share.Delete") data class ShareDelete(val share: Long) : Call()

    /** "Revoke" and "Switch on again" on [link], at once (`switchOff`, `reactivate`): [Answer.Said]. */
    @Serializable @SerialName("Share.SwitchOff") data class ShareSwitchOff(val share: Long, val link: String) : Call()
    @Serializable @SerialName("Share.Reactivate") data class ShareReactivate(val share: Long, val link: String) : Call()

    /**
     * The report sheet of [node] (`ShellModel.reportSheet`, core's `ReportSheet`), answered with its id
     * ([Answer.ReportOpened]) for [Watched.Report]. It stays until [ReportClose] or the tenant closes. The reason and the
     * contact the person typed cross inward only; the core cuts them to their room ([ReportShown.reasonMax]).
     */
    @Serializable @SerialName("Report.Open") data class ReportOpen(val node: NodeAt) : Call()
    @Serializable @SerialName("Report.Close") data class ReportClose(val report: Long) : Call()
    @Serializable @SerialName("Report.Whole") data class ReportWhole(val report: Long, val whole: Boolean) : Call()
    @Serializable @SerialName("Report.Category") data class ReportCategory(val report: Long, val category: String) : Call()
    @Serializable @SerialName("Report.Reason") data class ReportReason(val report: Long, val reason: String) : Call()
    @Serializable @SerialName("Report.GoodFaith") data class ReportGoodFaith(val report: Long, val on: Boolean) : Call()
    @Serializable @SerialName("Report.Contact") data class ReportContact(val report: Long, val contact: String) : Call()

    /** Sends the report once (`send`): [Answer.Said] with why not, or none (the receipt is [ReportShown.reference]). */
    @Serializable @SerialName("Report.Send") data class ReportSend(val report: Long) : Call()
}

/** A call's one answer; what the call changed arrives as the watched states' updates. */
@Serializable
internal sealed class Answer {
    @Serializable @SerialName("Done") data object Done : Answer()
    @Serializable @SerialName("Refused") data class Refused(val why: Why) : Answer()

    /** The call threw: the exception's class, never its message (which may hold what it was about). */
    @Serializable @SerialName("Failed") data class Failed(val error: String) : Answer()
    @Serializable @SerialName("Groups") data class Groups(val groups: List<Home.Group>) : Answer()
    @Serializable @SerialName("Hits") data class Hits(val hits: List<ObjectRef>) : Answer()
    @Serializable @SerialName("PotOpened") data class PotOpened(val pot: Long) : Answer()
    @Serializable @SerialName("Name") data class Name(val name: String) : Answer()

    /**
     * Bytes the UI shows, carried beside this JSON as the callback's payload (`bytes`, `len`), never in it (ADR 0128
     * decision 1): an answer of this kind always has a payload, an empty one included. [mediaType] is the core's
     * sniffed type (`Sniff`), null where the core does not know it. (`type` is the wire's tag, so the field is named
     * otherwise.)
     */
    @Serializable @SerialName("Bytes") data class Bytes(val mediaType: String?) : Answer()

    /**
     * A blob opened for reading in ranges (ADR 0128 decision 2): [reader] is its id for [Call.ReaderRead] and
     * [Call.ReaderClose], [size] its plaintext's length. Every reader of a tenant closes when the tenant does.
     */
    @Serializable @SerialName("Reader") data class Reader(val reader: Long, val size: Long, val mediaType: String?) : Answer()

    @Serializable @SerialName("HeaderOpened") data class HeaderOpened(val header: Long) : Answer()
    @Serializable @SerialName("TrashOpened") data class TrashOpened(val trash: Long) : Answer()

    /** What an action says: its notice, or null where it says nothing (it went through). */
    @Serializable @SerialName("Said") data class Said(val notice: Notice?) : Answer()

    /** A trash: what it says, and whether its undo is offered ([HeaderShown.undo] says when the offer ended). */
    @Serializable @SerialName("Trashed") data class Trashed(val notice: Notice, val undoable: Boolean) : Answer()

    /**
     * A move or copy sent (or asked first): what it says once done ([done]; the dialog closes), whether its undo is offered
     * now, whether the guest folder's confirm is asked, and why it did not go through ([problem]) — read as the call
     * returns, so the host's toast takes its Undo at once ([MoveShown.undo] says when the offer ended).
     */
    @Serializable @SerialName("MoveSent") data class MoveSent(val done: Notice?, val undoable: Boolean, val confirming: Boolean, val problem: Notice?) : Answer()
    @Serializable @SerialName("MoveOpened") data class MoveOpened(val move: Long) : Answer()
    @Serializable @SerialName("ShareOpened") data class ShareOpened(val share: Long) : Answer()
    @Serializable @SerialName("ReportOpened") data class ReportOpened(val report: Long) : Answer()

    /**
     * A picture's place in a cover's frame ([Call.CoverPose]): the pose (its top-left at [CoverFrame.Pose.x],
     * [CoverFrame.Pose.y]), the size the picture is drawn at there ([width] × [height], the frame's units) and the crop that
     * pose shows.
     */
    @Serializable @SerialName("CoverPosed") data class CoverPosed(val pose: CoverFrame.Pose, val width: Double, val height: Double, val crop: NodeCover.Crop) : Answer()

    /** What a picked picture is encoded at as a cover: its size, and the JPEG quality (0–1, and 0–100 for a percentage). */
    @Serializable @SerialName("CoverFit") data class CoverFit(val width: Int, val height: Int, val quality: Double, val qualityPercent: Int) : Answer()

    /** A download: whether the file was written, what it says (done or failed) and what it left out, or null. */
    @Serializable @SerialName("Downloaded") data class Downloaded(val written: Boolean, val notice: Notice, val leftOut: Notice?) : Answer()

    /** A rename: why it did not land ([problem]: the dialog asks again), or what to say once it did ([notice]). */
    @Serializable @SerialName("Renamed") data class Renamed(val problem: Notice?, val notice: Notice?) : Answer()

    /** The pixels a cover's crop shows ([Call.CoverCut]): from ([x], [y]), [width] × [height]. */
    @Serializable @SerialName("CoverCut") data class CoverCut(val x: Int, val y: Int, val width: Int, val height: Int) : Answer()

    /**
     * An entry's history ([Call.PotHistory]), newest first (`PotView.history`, each line `historyLine`); whether the entry
     * is deleted, whether it is offered a restore now (`PotModel.restorable`, the core's clock) and until when its
     * receipts stay (`PotView.purgeAfter`, epoch ms; null: a live entry, or no time served).
     */
    @Serializable @SerialName("EntryHistory") data class EntryHistory(
        val rows: List<HistoryShown>, val deleted: Boolean, val restorable: Boolean, val until: Long?,
    ) : Answer()

    /** An entry editor opened ([Call.EditorOpen]): its id, for [Watched.Editor] and the editor's calls. */
    @Serializable @SerialName("EditorOpened") data class EditorOpened(val editor: Long) : Answer()

    /**
     * What an editor's save or delete came to (`EntryEditor.Saved`): [done] — the host closes the editor at once — and
     * what to say ([notice]: the receipts left out of a done save; why one that is not done did not land; none for a form
     * whose refusal shows at its field).
     */
    @Serializable @SerialName("EditorSaved") data class EditorSaved(val done: Boolean, val notice: Notice?) : Answer()

    /** A PDF viewer's pages ([Call.ReceiptPages]): how many it renders ([shown], `Receipts.pagesShown`) and what it says
     *  under them when that is not all ([cut], `Receipts.pagesCut`). */
    @Serializable @SerialName("ReceiptPages") data class ReceiptPages(val shown: Int, val cut: Notice?) : Answer()

    /** A Pot's settings opened ([Call.PotSettingsOpen]): their id, for [Watched.PotSettings] and their calls. */
    @Serializable @SerialName("PotSettingsOpened") data class PotSettingsOpened(val settings: Long) : Answer()

    /** "New pot" opened ([Call.PotCreateOpen]): its id, for [Watched.PotCreate] and its calls. */
    @Serializable @SerialName("PotCreateOpened") data class PotCreateOpened(val create: Long) : Answer()

    /**
     * What a settings change or a New pot action came to, read as the call returns: [stored] (the host empties its field,
     * or closes New pot), and what to say ([notice]: why it was not stored; or, stored, what still went wrong).
     */
    @Serializable @SerialName("Stored") data class Stored(val stored: Boolean, val notice: Notice?) : Answer()

    enum class Why {
        NOT_A_CALL, NO_TENANT_OPEN, NO_SUCH_CONNECTION, NO_SUCH_TENANT, NO_SUCH_POT, NO_SUCH_READER, NO_PICTURE, NOTHING_TO_DOWNLOAD,
        NO_SUCH_HEADER, NO_SUCH_TRASH, NO_SUCH_ROW, NO_SUCH_MOVE, NO_SUCH_SHARE, NO_SUCH_REPORT, NO_COVER, NO_SUCH_ENTRY, NO_SUCH_EDITOR,
        NO_RECEIPT, NOT_WRITTEN, NO_SUCH_SETTINGS, NO_SUCH_CREATE,

        /** A change of trust (`TrustChange`): no offer to answer; the person cancelled the prompt; not enrolled; the
         *  password was not the account's. */
        NO_OFFER, TRUST_CANCELLED, TRUST_FAILED, WRONG_PASSWORD,
    }
}

/** What a watch observes ([veyq_watch]): one screen model's state. */
@Serializable
internal sealed class Watched {
    @Serializable @SerialName("Session") data object Session : Watched()

    /** The shell over the tenant open now, whichever that is: a switch moves the watch to the new tenant's shell. */
    @Serializable @SerialName("Shell") data object Shell : Watched()
    @Serializable @SerialName("Pot") data class Pot(val pot: Long) : Watched()

    /**
     * Pot [pot]'s live poll (`PotModel.live`, ADR 0127 decision 2), for as long as this watch stands: a host watches it
     * while the Pot's page shows and its window is active, and drops the watch when either ends. It delivers no update of
     * its own — what a tick changed arrives on [Pot]'s. Its lifetime is the poll's, so a page torn down stops it.
     */
    @Serializable @SerialName("PotLive") data class PotLive(val pot: Long) : Watched()

    /** An entry editor opened by [Call.EditorOpen]. */
    @Serializable @SerialName("Editor") data class Editor(val editor: Long) : Watched()

    /** A Pot's settings opened by [Call.PotSettingsOpen]. */
    @Serializable @SerialName("PotSettings") data class PotSettings(val settings: Long) : Watched()

    /** "New pot" opened by [Call.PotCreateOpen]. */
    @Serializable @SerialName("PotCreate") data class PotCreate(val create: Long) : Watched()

    /** "Manage servers" while it is open ([Call.ManageOpen]). */
    @Serializable @SerialName("Manage") data object Manage : Watched()

    /** A header opened by [Call.HeaderOpen]. */
    @Serializable @SerialName("Header") data class Header(val header: Long) : Watched()

    /** A space's trash opened by [Call.TrashOpen]. */
    @Serializable @SerialName("Trash") data class Trash(val trash: Long) : Watched()

    /** A move or copy sheet opened by [Call.MoveOpen]. */
    @Serializable @SerialName("Move") data class Move(val move: Long) : Watched()

    /** A share sheet opened by [Call.ShareOpen]. */
    @Serializable @SerialName("Share") data class Share(val share: Long) : Watched()

    /** A report sheet opened by [Call.ReportOpen]. */
    @Serializable @SerialName("Report") data class Report(val report: Long) : Watched()
}

/** One state a watch delivers: the first at once, then each change. */
@Serializable
internal sealed class Update {
    /**
     * The way in. The offer to stay signed in rides `Gate.Signed.offer` (its levels the store's own answer); a
     * `Gate.Unlock` of a `DEVICE_KEY` record names its level (`TrustedDevice.level`), by which the unlock words what opens
     * the account (a `LOGIN` key asks nothing, so its unlock shows only after a resume that failed).
     */
    @Serializable @SerialName("Session") data class Session(val gate: SessionModel.Gate) : Update()

    /** "Manage servers": every server signed in to, null while it is closed. */
    @Serializable @SerialName("Manage") data class Manage(val servers: List<ManagedShown>?) : Update()

    /** Null while no tenant is open, or its lists are not read yet; [shown] beside a state, null with none. */
    @Serializable @SerialName("Shell") data class Shell(val state: ShellModel.State?, val shown: ShellShown? = null) : Update()

    /**
     * Null while nothing is open in this Pot model (closed, or an id the bridge does not hold). [pictures] are the user
     * ids whose picture the core holds ([Call.AvatarPicture]); a person whose member id is not among them shows the letter.
     */
    @Serializable @SerialName("Pot") data class Pot(val pot: Long, val state: PotModel.State?, val pictures: List<String> = emptyList()) : Update()

    /** A header's state; null once it was closed (or an id the bridge does not hold). */
    @Serializable @SerialName("Header") data class Header(val header: Long, val shown: HeaderShown?) : Update()

    /** A space's trash sheet; null once it was closed. */
    @Serializable @SerialName("Trash") data class Trash(val trash: Long, val shown: TrashShown?) : Update()

    /** A move or copy sheet; null once it was closed. */
    @Serializable @SerialName("Move") data class Move(val move: Long, val shown: MoveShown?) : Update()

    /** A share sheet; null once it was closed. */
    @Serializable @SerialName("Share") data class Share(val share: Long, val shown: ShareShown?) : Update()

    /** A report sheet; null once it was closed. */
    @Serializable @SerialName("Report") data class Report(val report: Long, val shown: ReportShown?) : Update()

    /** An entry editor; null once it was closed (or an id the bridge does not hold). */
    @Serializable @SerialName("Editor") data class Editor(val editor: Long, val shown: EditorShown?) : Update()

    /** A Pot's settings; null once they were closed (or their Pot was). */
    @Serializable @SerialName("PotSettings") data class PotSettings(val settings: Long, val shown: PotSettingsShown?) : Update()

    /** "New pot"; null once it was closed. */
    @Serializable @SerialName("PotCreate") data class PotCreate(val create: Long, val shown: PotCreateShown?) : Update()
}

/**
 * A Pot's settings and people as the host draws them (core's `PotSettings` over the open Pot's `PotView`), each field one
 * core function's answer:
 * - [ready] (a base currency set), [canWrite], [saving] (a write runs: controls wait), [notice] (the last change's refusal);
 * - the base [currency] among [currencies] (`Currencies.known`), [currencyEditable] (chosen here) or [currencyFixed] (the
 *   Pot holds money: say so);
 * - its own [categories] (each with its words) shown where [categoriesShown], renamed where [canChangeSettings], one added
 *   where [canAddCategory];
 * - its [people] with their facts and what may be done to each (`PotView.PersonEntry`), [deletedPeople], [settleFirst]
 *   (someone still owes: say why some cannot be deactivated or erased), the space's [members] still to add;
 * - [asking]: an erase asked (`PotSettings.Asked`), answered with [Call.PotSettingsErase] or [Call.PotSettingsCancelAsk].
 */
@Serializable
@SerialName("PotSettingsShown")
internal class PotSettingsShown(
    val ready: Boolean,
    val canWrite: Boolean,
    val saving: Boolean,
    val notice: Notice?,
    val currency: String?,
    val currencies: List<String>,
    val currencyEditable: Boolean,
    val currencyFixed: Boolean,
    val canChangeSettings: Boolean,
    val categoriesShown: Boolean,
    val canAddCategory: Boolean,
    val categories: List<EditorChoice>,
    val people: List<PotView.ShownPerson>,
    val deletedPeople: Int,
    val settleFirst: Boolean,
    val members: List<PotMember>,
    val asking: ErasingPerson?,
)

/** A member of the space: their user id and the name they read by. */
@Serializable @SerialName("PotMember")
internal class PotMember(val userId: String, val name: String)

/** An erase asked: who, by name; [named] — an entry names them, so they are deleted (else removed): the question's words. */
@Serializable @SerialName("ErasingPerson")
internal class ErasingPerson(val person: String, val name: String, val named: Boolean)

/**
 * "New pot" as the host draws it (core's `PotCreateSheet`): the [spaces] it may go to with their names (a picker only for
 * more than one) and the one chosen ([space]; null: the tenant has none), the base [currency] among [currencies], the
 * [people] it starts with (a name typed, or a member: [CreatePerson.member]) and the space's members still [offered];
 * [canCreate] (`canCreate` of the name sent last), [saving], [notice] (why the last create did not land), [done].
 */
@Serializable
@SerialName("PotCreateShown")
internal class PotCreateShown(
    val spaces: List<ShellShown.ShownSpace>,
    val space: String?,
    val currency: String,
    val currencies: List<String>,
    val people: List<CreatePerson>,
    val offered: List<PotMember>,
    val canCreate: Boolean,
    val saving: Boolean,
    val notice: Notice?,
    val done: Boolean,
)

/** One of New pot's people: the name, and whether they are a member of the space (else a name typed). */
@Serializable @SerialName("CreatePerson")
internal class CreatePerson(val name: String, val member: Boolean)

/**
 * An entry editor as the host draws it (core's `EntryEditor.State` and its form, `EntryForm`), each field one core
 * function's answer; every typed text is the text typed (the core keeps strings), so a host sets a field from it only
 * when the field is not being typed into or the text is not one it sent (D5 of the Expenses CR).
 *
 * - [isNew] (no history, no delete), [editable] (false: a reader's, read only), [invalid] (the entry does not add up);
 * - the form: [kind], [title] (a transfer's note), [date] (`yyyy-MM-dd`), [category] among [categories] (each with its
 *   words, `PotView.categoryLabel`), [currency] among [currencies] (`Currencies.known`) and the Pot's [base], [rate] (shown
 *   while the currency is not the base) with its fill ([rateFill], `EntryEditor.Rate`; null: the hint);
 * - a transfer's [amount], [from] and [to]; an expense's or a refund's [payers] (whether one may be removed or added,
 *   `canRemovePayer`, `nextPayer`), their [total] (`payersTotal`), the [items] and the [rest] (null while it does not show,
 *   `showsRest`; [restIsWhole] while there are no items);
 * - [people] offered; [preview], the shares in the base currency (`EntryEditor.State.preview`);
 * - [refusal] once a save was tried, at its field; [saving], [notice] (the last write's), [asking] (the delete), [done].
 * - its [receipts] (the kept ones, then those picked), whether another may be picked ([canAddReceipt],
 *   `EntryEditor.State.canAddReceipt`) and whether the last pick refused a file ([refusedPick]).
 */
@Serializable
@SerialName("EditorShown")
internal class EditorShown(
    val isNew: Boolean,
    val editable: Boolean,
    val invalid: Boolean,
    val kind: EntryForm.Kind,
    val title: String,
    val date: String,
    val category: String,
    val categories: List<EditorChoice>,
    val currency: String,
    val currencies: List<String>,
    val base: String,
    val rate: String,
    val rateFill: EntryEditor.Rate?,
    val amount: String,
    val from: String,
    val to: String,
    val people: List<EditorPerson>,
    val payers: List<EditorPayer>,
    val canRemovePayer: Boolean,
    val canAddPayer: Boolean,
    val total: Piece,
    val items: List<EditorItem>,
    val rest: EditorSplit?,
    val restIsWhole: Boolean,
    val preview: List<EditorShare>,
    val refusal: EditorRefusal?,
    val saving: Boolean,
    val notice: Notice?,
    val asking: Boolean,
    val done: Boolean,
    val receipts: List<EditorReceipt>,
    val canAddReceipt: Boolean,
    val refusedPick: Boolean,
)

/**
 * One receipt of the entry edited: [id], the [name] it is listed and saved under (`Receipts.downloadName`); [held] — picked
 * and not uploaded yet (uploaded with the save) — or kept; [opens]: a kept one the Pot holds and may open ([Call.ReceiptOpen]).
 */
@Serializable @SerialName("EditorReceipt")
internal class EditorReceipt(val id: String, val name: String, val held: Boolean, val opens: Boolean)

/** A category the editor offers: its id and its words. */
@Serializable @SerialName("EditorChoice")
internal class EditorChoice(val id: String, val label: Piece)

/** A person the editor offers (`PotView.offered`), in join order. */
@Serializable @SerialName("EditorPerson")
internal class EditorPerson(val id: String, val name: String)

/** One payer (a refund's receiver): who, and the amount typed. */
@Serializable @SerialName("EditorPayer")
internal class EditorPayer(val person: String, val amount: String)

/** One item: its label and amount as typed, and its own split. */
@Serializable @SerialName("EditorItem")
internal class EditorItem(val label: String, val amount: String, val split: EditorSplit)

/**
 * A split (an item's, or the rest's): its [mode]; folded ([expanded] false) it is one line, [folded]
 * (`EntryForm.collapsedWords`); each person offered with whether they are on it and the value typed for them in the mode
 * (`EntryForm.typedIn`); what it says under its rows ([hint], `EntryForm.splitHint`).
 */
@Serializable @SerialName("EditorSplit")
internal class EditorSplit(val mode: EntryForm.Mode, val expanded: Boolean, val folded: Notice, val people: List<EditorSplitPerson>, val hint: Notice?)

@Serializable @SerialName("EditorSplitPerson")
internal class EditorSplitPerson(val person: String, val name: String, val on: Boolean, val value: String)

/** One person of the preview: their name, what they gave (null: nothing) and their share, in the base currency. */
@Serializable @SerialName("EditorShare")
internal class EditorShare(val name: String, val given: Piece?, val share: Piece)

/** Why the form cannot be saved ([words]), at which field ([at]: `EntryForm.Refusal.at`; [item] an item's index). */
@Serializable @SerialName("EditorRefusal")
internal class EditorRefusal(val words: Notice, val at: String?, val item: Int?)

/**
 * A report sheet as the host draws it (`ReportSheet`), each field one core function's answer: the node; the categories in
 * the web's order with their words; whether the node is reported whole and what that choice says ([scopeHint]); the
 * category chosen and its note; the room of the reason and the contact (the host's fields hold no more); the good-faith
 * switch; whether Send is offered, a send under way and why the last did not go through; once sent, the receipt's
 * reference and when the server received it (an instant the host says in its words); the privacy notice's passage on a
 * reporter's contact ([privacyUrl], built by the core from the tenant's origin). The typed reason and contact are not
 * echoed: the host's fields hold them.
 */
@Serializable
internal class ReportShown(
    val node: NodeAt,
    val categories: List<ShownCategory>,
    val whole: Boolean,
    @Serializable(with = MsgKey::class) val scopeHint: Msg,
    val category: String?,
    @Serializable(with = MsgKey::class) val note: Msg?,
    val reasonMax: Int,
    val contactMax: Int,
    val goodFaith: Boolean,
    val canSend: Boolean,
    val sending: Boolean,
    val problem: Notice?,
    val sent: Boolean,
    val reference: String?,
    val receivedAt: String?,
    val privacyUrl: String,
) {
    /** A category: the contract's name of it (its `category` on [Call.ReportCategory]) and its words. */
    @Serializable class ShownCategory(val id: String, @Serializable(with = MsgKey::class) val word: Msg)
}

/**
 * One revision of an entry's history as the host draws it: its line (`PotView.historyLine`), who wrote it (null: no one
 * known — the host's "Someone") and when the server stored it (epoch ms).
 */
@Serializable
internal class HistoryShown(val line: List<Piece>, val writer: String?, val at: Long)

/**
 * A node's header as the host draws it (`NodeHeaderModel`), each field one core function's answer: the node, whether it
 * is pinned (null while its pin set is not read: no heart), the plan with each action's words (`plan`, `label`; null while
 * the kind's page cannot tell yet), the delete's question (`deleteQuestion`), the trash's undo while the core offers it
 * (`State.undo`: the host shows its Undo until this is null again — the window is the core's, never a host's timer) and
 * whether the node went to the trash.
 */
@Serializable
internal class HeaderShown(
    val node: NodeAt,
    val pinned: Boolean?,
    val plan: Plan?,
    val deleteQuestion: Notice,
    val undo: Notice?,
    val gone: Boolean,
    /** The file name a download's save dialog suggests (`NodeExport.fileName`: the web's archive name). */
    val archiveName: String,
    /** Where the frame of the node's cover sits (`State.cover`); null while it has none. Its picture: [Call.HeaderCoverPicture]. */
    val cover: NodeCover.Crop?,
) {
    /** `NodeHeader.Plan`: the bar's buttons, the menu, and the menu's last group after a separator. */
    @Serializable class Plan(val buttons: List<PlanAction>, val menu: List<PlanAction>, val last: List<PlanAction>)

    /**
     * One action: its id (`NodeHeader`'s, or an application's own) and its words (null: an application's own). Not named
     * `Action`: the generated C# would clash with `System.Action`.
     */
    @Serializable class PlanAction(val id: String, @Serializable(with = MsgKey::class) val word: Msg?)
}

/**
 * A move or copy sheet as the host draws it (`MoveSheet`), each field one core function's answer: the node and whether it
 * is copied; the title, the hint and the button's words; whether the tree is read ([loaded]); the trail from the space's
 * root to the place shown (each but the last can be chosen: [Call.MoveGo]); the places listed there, a blocked one with
 * why ([MovePlace.blocked]) and not to be opened, and what an empty place says; why the place takes nothing ([here]); the
 * last write's problem; a copy's no-room notice with its size; what a copy leaves behind ([losses], each one line) and
 * out ([leftOut]); whether a write runs, whether "here" can be chosen, the guest folder's confirm and its question; what it
 * said once done ([done]: the dialog closes) and the move's undo while the core offers it ([undo]: the host's toast shows
 * its Undo until this is null again — the window is the core's).
 */
@Serializable
internal class MoveShown(
    val node: NodeAt,
    val copying: Boolean,
    val title: Notice,
    @Serializable(with = MsgKey::class) val hint: Msg,
    @Serializable(with = MsgKey::class) val submit: Msg,
    val loaded: Boolean,
    val trail: List<MoveCrumb>,
    val places: List<MovePlace>,
    val empty: Notice?,
    @Serializable(with = MsgKey::class) val here: Msg?,
    val problem: Notice?,
    val noRoom: Notice?,
    val losses: List<Notice>,
    val leftOut: List<Notice>,
    val busy: Boolean,
    val canChoose: Boolean,
    val confirming: Boolean,
    val confirmQuestion: Notice,
    val done: Notice?,
    val undo: Notice?,
) {
    /** A place on the trail: the space's root ([nodeId] null) or a folder, with its name. */
    @Serializable class MoveCrumb(val nodeId: String?, val name: Piece)

    /** A place listed: its node, and why it cannot be opened ([blocked]: the node itself, or below it; null: it can). */
    @Serializable class MovePlace(val nodeId: String, val kind: String, val name: String, @Serializable(with = MsgKey::class) val blocked: Msg?)
}

/**
 * A share sheet as the host draws it (`ShareSheet`), each field one core function's answer: the node, the sheet's title
 * and intro in its kind's words, whether "New link" is offered; the links (null while the first read runs) each with its
 * row's words and what may be done with it ([ShownLink], `LinkRow`); the row a write runs on, what the last one said
 * ([notice]); the form while it is open ([form]: the link it edits in [editing], its button's words, [saving], why it did
 * not go through in [problem]); the address minted or renewed, shown once until [Call.ShareFreshSeen] ([freshAddress]); the
 * link whose renew or delete is asked and each question.
 *
 * A link's address carries its key in its fragment (`#k=`): it crosses outward because it is what the person copies and
 * hands out, in [freshAddress] and [ShownLink.address] alone (`SecretFreeSchemaTest`'s one named allowance for it).
 */
@Serializable
internal class ShareShown(
    val node: NodeAt,
    @Serializable(with = MsgKey::class) val title: Msg,
    @Serializable(with = MsgKey::class) val intro: Msg,
    val canMint: Boolean,
    val links: List<ShownLink>?,
    val failed: Boolean,
    val busy: String?,
    val notice: Notice?,
    val form: ShownForm?,
    val editing: String?,
    @Serializable(with = MsgKey::class) val submit: Msg,
    val saving: Boolean,
    val problem: Notice?,
    val freshAddress: String?,
    val renewing: String?,
    val deleting: String?,
    @Serializable(with = MsgKey::class) val renewQuestion: Msg,
    @Serializable(with = MsgKey::class) val deleteQuestion: Msg,
) {
    /**
     * One link: its id, its title (its label, else its role), its badges, the instants it was made and ends (the host says
     * their local day), its address where it may be copied ([canCopy]; null otherwise), and what this member may do with it.
     */
    @Serializable class ShownLink(
        val shareId: String,
        val title: Piece,
        val badges: List<Notice>,
        val createdAt: String,
        val expiresAt: String?,
        val address: String?,
        val canCopy: Boolean,
        val canEdit: Boolean,
        val canRenew: Boolean,
        val canSwitchOff: Boolean,
        val canReactivate: Boolean,
        val canDelete: Boolean,
    )

    /**
     * The form (`LinkForm`): the roles to pick from with their words (empty: the role is fixed), the role and its hint, the
     * name field ([namesIt], its label, the text), the password's switch ([lock]) and the end's ([end]), and whether it may
     * be sent. The password itself never comes back: the host's entry holds what was typed.
     */
    @Serializable class ShownForm(
        val roles: List<ShownRole>,
        val role: String,
        @Serializable(with = MsgKey::class) val hint: Msg,
        val namesIt: Boolean,
        @Serializable(with = MsgKey::class) val nameLabel: Msg,
        val label: String,
        val lock: ShownLock,
        val end: ShownEnd,
        val canSave: Boolean,
    ) {
        @Serializable class ShownRole(val role: String, @Serializable(with = MsgKey::class) val word: Msg)

        /**
         * The password's switch: on, whether it may be turned off (a rule holds it), whether a blank entry keeps the link's
         * old one ([kept]), whether what was typed is too short, and what it says under it.
         */
        @Serializable class ShownLock(val on: Boolean, val optional: Boolean, val kept: Boolean, val tooShort: Boolean, val hint: Notice)

        /** The end's switch: on, whether it may be turned off, the day picked, today and the last day a pick may take (local days since 1970-01-01), and what it says under it. */
        @Serializable class ShownEnd(val on: Boolean, val optional: Boolean, val day: Long, val today: Long, val lastDay: Long?, val hint: Notice)
    }
}

/**
 * A space's trash as the host draws it (`TrashSheet.State`): its rows (null while the first read runs), each with what
 * the sheet offers on it; the row a write runs on, the row an error stands under and the error, the row that asks for
 * another name and the name typed.
 */
@Serializable
internal class TrashShown(
    val rows: List<Row>?,
    val failed: Boolean,
    val busy: String?,
    val errorRow: String?,
    @Serializable(with = MsgKey::class) val error: Msg?,
    val renaming: String?,
    val retryName: String,
) {
    /**
     * A row: the deleted subtree's root, its kind and name, when it was deleted and when it goes for good (the server's
     * ISO instants, said as days), [restorable] whether Restore is offered (`canRestore`), [retryable] whether the restore
     * under the typed name is (`State.retryable`).
     */
    @Serializable
    class Row(val id: String, val kind: String, val name: String, val deletedAt: String, val purgeAfter: String, val restorable: Boolean, val retryable: Boolean)
}

/**
 * What the shell shows beside its state, each value the core's own function's answer — what the Android and Apple apps
 * ask the core for while they draw (their `AppModel` and shell screens), so a host without Kotlin computes none of it
 * (ADR 0127 decision 1). Built by [Bridge] one line per field; a rule found there is a defect to move into the core.
 */
@Serializable
internal class ShellShown(
    /** The tenant open now, on the server [server] (its account's label) of the connection [connection]. */
    val tenant: TenantRef,
    val server: String,
    val connection: String,
    /** The switcher's servers, each with the tenants it lists (`Hidden.visibleTenants`). */
    val servers: List<ShownServer>,
    /** The space Home shows (`Lists.space`), its name (`ShellWords.space`) and the line under it (`switcherSubtitle`). */
    val space: SpaceRef?,
    val spaceName: Piece,
    val switcherSubtitle: String,
    /** The tenant's spaces with their names (`ShellWords.space`). */
    val spaces: List<ShownSpace>,
    /** Every application's name (`AppKinds.applicationWord`), for the drawer's and Home's filters' rows. */
    val applicationWords: List<ShownApplication>,
    /** Home's level in its groups (`Lists.groups`). */
    val groups: List<Home.Group>,
    /** The chosen application's own list (`Lists.applicationList`); null on Home. */
    val list: ArrangedList?,
    /** The bar's places (`Shell.barTabs`, each with `AppKinds.tabWord`) and the one selected (`Shell.selectedTab`). */
    val tabs: List<ShownTab>,
    val selectedTab: BarTab?,
    val canGoBack: Boolean,
    val isHome: Boolean,
    val drawerOpensAtStart: Boolean,
    /** What the sidebar's New offers (`AppKinds.drawerCreates`), each with its words. */
    val creates: List<ShownCreate>,
    /** Whether New is offered where the shell is (`ShellModel.canCreateHere`: never in a guest's folder). */
    val canCreateHere: Boolean,
    /** The node the content heads (`Shell.openObject`, else the folder Home shows: `Shell.folderAt`); null on a space's root. */
    val headed: NodeAt?,
    /** The signed-in member's user id, whose picture the switcher shows when [pictures] holds it. */
    val me: String?,
    /**
     * The user ids whose picture the core holds (`AvatarsModel`), each asked for with [Call.AvatarPicture]: the letter
     * for everyone else. A change of the set is a new update; the bytes never ride one (ADR 0128 decision 1).
     */
    val pictures: List<String>,
) {
    @Serializable class ShownServer(val connection: String, val label: String, val tenants: List<TenantRef>)
    @Serializable class ShownSpace(val space: SpaceRef, val name: Piece)
    @Serializable class ShownApplication(val application: ApplicationId, @Serializable(with = MsgKey::class) val word: Msg)
    @Serializable class ShownTab(val tab: BarTab, @Serializable(with = MsgKey::class) val word: Msg)
    @Serializable class ShownCreate(val create: AppKinds.Create, @Serializable(with = MsgKey::class) val word: Msg)
}

/**
 * One server on "Manage servers", as Android's `ManageScreen` and Apple's `ManageView` read it from the core
 * (`SessionModel.managed`): its connection's key and label, how this device trusts it ([trusted]: null when it does not,
 * DEVICE with the password, DEVICE_KEY staying signed in at [level]), the levels it may change to ([levels], the
 * device's: [Call.ManageStaySignedIn]; back to the password trust is [Call.ManagePasswordTrust]), and its tenants.
 */
@Serializable
internal class ManagedShown(
    val connection: String,
    val label: String,
    val trusted: TrustedDevice.Method?,
    val level: StaySignedIn?,
    val levels: List<StaySignedIn>,
    val tenants: List<Tenant>,
) {
    /**
     * A tenant: [shown] its switch (on while shown; the current one always is), [switchable] whether it may be turned,
     * [subtitle] the line under its name; [spaces] null while they read.
     */
    @Serializable
    class Tenant(val tenant: TenantRef, val hidden: Boolean, val current: Boolean, val shown: Boolean, val switchable: Boolean, val subtitle: Piece, val spaces: List<Space>?)

    /** A space of a tenant, with its name (`ShellWords.space`), shown unless [hidden]; [canHide] its switch's. */
    @Serializable
    class Space(val space: SpaceRef, val name: Piece, val hidden: Boolean, val canHide: Boolean)
}

/** What the core asks of the host ([veyq_init]'s host function), answered through [veyq_reply] with a [HostReply]. */
@Serializable
internal sealed class HostRequest {
    /** The device store's value under [name] (`DeviceStore.read`): [HostReply.Value], null when there is none. */
    @Serializable @SerialName("Store.Read") data class StoreRead(val name: String) : HostRequest()
    @Serializable @SerialName("Store.Write") data class StoreWrite(val name: String, val value: String) : HostRequest()
    @Serializable @SerialName("Store.Remove") data class StoreRemove(val name: String) : HostRequest()
}

@Serializable
internal sealed class HostReply {
    @Serializable @SerialName("Value") data class Value(val value: String?) : HostReply()
    @Serializable @SerialName("Done") data object Done : HostReply()

    /** The host could not do it (a store that does not write): the core's call fails as a store's would. */
    @Serializable @SerialName("Failed") data class Failed(val error: String) : HostReply()
}

/**
 * Every type that crosses, by direction: what the host sends ([inward]) and what the core sends ([outward]). The
 * generator writes the host's types from these roots and the tests derive their cases from them, so a root added here
 * is generated and tested without a list of its own anywhere else.
 */
internal object Roots {
    val inward: List<KSerializer<*>> = listOf(Config.serializer(), Call.serializer(), Watched.serializer(), HostReply.serializer())
    val outward: List<KSerializer<*>> = listOf(Answer.serializer(), Update.serializer(), HostRequest.serializer())
    val all: List<KSerializer<*>> get() = inward + outward
}
