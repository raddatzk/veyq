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
     * "Open Emergency Kit" at the sign-in: the kit file the host's open dialog gave, by its [path] (ADR 0131 decision 3),
     * read by the core under `KitImport.FILE_CAP` and taken as the phones' kit is (`SessionModel.readKit`); [server] is
     * the form's server as typed now (the other-server notice's). Where the file needs drawing, the core asks the host
     * ([HostRequest.PdfPages], [HostRequest.PdfPage], [HostRequest.PictureLuma]). [Answer.Done]: the gate's `kit` says
     * what the form now holds — its email and server, never the Secret Key, which the core holds; `NO_KIT` where the
     * file held none (the gate says so) or the sign-in is not shown.
     */
    @Serializable @SerialName("Session.SignInKit") data class SessionSignInKit(val path: String, val server: String) : Call()

    /** The Secret Key row's "Enter it instead": the kit's key the core held ends (`SessionModel.clearKit`). */
    @Serializable @SerialName("Session.SignInKitClear") data object SessionSignInKitClear : Call()

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

    /**
     * The account's Settings page (core's `AccountSettings`, `cr-app-account-settings` D4) for the connection whose key is
     * [connection], answered with its id ([Answer.SettingsOpened]) for [Watched.Settings]; the core reads it at once
     * (`refresh`). Refused `NO_SUCH_CONNECTION`, or `NO_TENANT_OPEN` (it is reached from an open tenant's drawer). It stays
     * until [SettingsClose], its account's sign-out here, or the tenant closes. Each action is one call answered once it
     * ended — [Answer.Done], and why it did not come through is the state's `refusal`.
     */
    @Serializable @SerialName("Settings.Open") data class SettingsOpen(val connection: String) : Call()
    @Serializable @SerialName("Settings.Close") data class SettingsClose(val settings: Long) : Call()

    /** Everything read anew (`refresh`): the address, the sessions, the device names, "Add a device". */
    @Serializable @SerialName("Settings.Refresh") data class SettingsRefresh(val settings: Long) : Call()

    /** This device's name in the account's device register, as the person typed it (`rename`; a blank one is none). */
    @Serializable @SerialName("Settings.Rename") data class SettingsRename(val settings: Long, val label: String) : Call()

    /**
     * Session family [family] signed out (`signOut`). This device's own is the sign-out of this account here: the page
     * closes and the way in follows, as [SessionSignOut]'s.
     */
    @Serializable @SerialName("Settings.SignOut") data class SettingsSignOut(val settings: Long, val family: String) : Call()
    @Serializable @SerialName("Settings.SignOutOthers") data class SettingsSignOutOthers(val settings: Long) : Call()

    /** ADR 0058's escalation for family [family]: signed out, then its device's saved key removed (`signOutAndRemoveKey`). */
    @Serializable @SerialName("Settings.SignOutAndRemoveKey") data class SettingsSignOutAndRemoveKey(val settings: Long, val family: String) : Call()

    /** The saved key of [device], a device with one and no session ([SettingsShown.keptDevices]), removed. */
    @Serializable @SerialName("Settings.RemoveSavedKey") data class SettingsRemoveSavedKey(val settings: Long, val device: String) : Call()
    @Serializable @SerialName("Settings.RemoveOtherSavedKeys") data class SettingsRemoveOtherSavedKeys(val settings: Long) : Call()

    /** Stay signed in at [level] (one of [SettingsShown.levels]): answered as [ManageStaySignedIn] is, Manage's own call. */
    @Serializable @SerialName("Settings.StaySignedIn") data class SettingsStaySignedIn(val settings: Long, val level: StaySignedIn) : Call()

    /** Back to the password trust with the person's [password], answered as [ManagePasswordTrust] is. */
    @Serializable @SerialName("Settings.PasswordTrust") data class SettingsPasswordTrust(val settings: Long, val password: String) : Call()

    /** "Stop trusting this device" (`stopTrusting`): its saved key goes, the session stays. */
    @Serializable @SerialName("Settings.StopTrusting") data class SettingsStopTrusting(val settings: Long) : Call()

    /** "Sign out" of this account on this device (`signOutHere`): the page closes and the way in follows. */
    @Serializable @SerialName("Settings.SignOutHere") data class SettingsSignOutHere(val settings: Long) : Call()

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
     * Files picked as editor [editor]'s receipts by their [paths], as the host's open dialog gave them (ADR 0131 decision 3):
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
     * Receipt [receipt] of Pot [pot] saved into the file at [path], which the host's save dialog gave (ADR 0131 decision 3:
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
     * Downloads Pot [pot] as the web's ZIP into the file at [path], which the host's save dialog gave (ADR 0131
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

    /**
     * What the kind's page knows now (`NodeHeaderModel.update`) and, for a file's page, its id ([file]): the page's own
     * actions (`FileModel.Header.own`: Replace) join the plan, worded by the page; for a Memory's or a chapter's page, its
     * gallery's id ([gallery]): its own (`GalleryModel.headerActions`: adding photos and videos) join it the same way.
     */
    @Serializable @SerialName("Header.Facts") data class HeaderFacts(val header: Long, val facts: AppKinds.Facts?, val file: Long? = null, val gallery: Long? = null) : Call()
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
     * Downloads the header's node into the file at [path], which the host's save dialog gave — a file the file itself, any
     * other node with everything under it as the web's ZIP (`ShellModel.download`; ADR 0131 decision 3: the core opens it, writes it and removes a download
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
     * [path] (ADR 0131 decision 3: the core reads it; the host removes its file after), framed by [crop] — or, [path]
     * null, the stored picture framed anew. [Answer.Said] with why not, or none.
     */
    @Serializable @SerialName("Header.CoverSave") data class HeaderCoverSave(val header: Long, val path: String?, val crop: NodeCover.Crop) : Call()

    /** Removes the cover (`removeCover`): [Answer.Said]. */
    @Serializable @SerialName("Header.CoverRemove") data class HeaderCoverRemove(val header: Long) : Call()

    /**
     * File [node]'s page (`ShellModel.fileModel`, core's `FileModel`) as a model of its own, answered with its id
     * ([Answer.FileOpened]) for [Watched.File]: what it shows decided by the bytes, its facts, its header's facts. It stays
     * until [FileClose] or the tenant closes.
     */
    @Serializable @SerialName("File.Open") data class FileOpen(val node: NodeAt) : Call()

    /**
     * The bytes file [file] shows now ([Answer.Bytes], beside the JSON; ADR 0131 decision 1): an image's or a PDF's, asked
     * again when [FileShown.revision] turns. Refused where it shows neither (a text is in the state, a medium is read through
     * a reader).
     */
    @Serializable @SerialName("File.Bytes") data class FileBytes(val file: Long) : Call()

    /** A PDF viewer's pages of a file's PDF of [total] pages ([Answer.ReceiptPages]: `FileView.pagesShown`, `pagesCut`). */
    @Serializable @SerialName("File.Pages") data class FilePages(val total: Int) : Call()
    @Serializable @SerialName("File.Close") data class FileClose(val file: Long) : Call()

    /**
     * The medium file [file] plays, opened for reading in ranges ([Answer.Reader]: its id, its size, its sniffed type; ADR 0131
     * decision 2): a player reads it through [ReaderRead] from a thread of its own, never decrypted whole or to disk (ADR
     * 0047). Refused where the page shows no medium. The reader closes with [ReaderClose], with its page ([FileClose]) and
     * with its tenant.
     */
    @Serializable @SerialName("File.Reader") data class FileReader(val file: Long) : Call()

    /**
     * Saves a copy of file [file] ("Save a copy", the file's download: the file itself) into the file at [path], which the
     * host's save dialog gave (ADR 0131 decision 3: the core opens and writes it, nothing of it crosses): answered at once
     * with the save's id ([Answer.Saving]) once it runs, for [Watched.Save] and [SaveCancel]. Refused where the file has
     * nothing to save. A save outlives its page; every save stops with the tenant.
     */
    @Serializable @SerialName("File.Save") data class FileSave(val file: Long, val path: String) : Call()

    /** Stops save [save] and answers once it stopped, nothing left at its path ([Answer.Done]; refused where it had ended). */
    @Serializable @SerialName("Save.Cancel") data class SaveCancel(val save: Long) : Call()

    /**
     * The header's Replace on file [file]'s page, chosen before the host's open dialog opens (`FileModel.chooseReplace`, the
     * web's rule, ADR 0130's Consequences: the replace expects the content shown now, whatever a peer writes while the dialog is open). Refused
     * where the page offers no replace.
     */
    @Serializable @SerialName("File.ChooseReplace") data class FileChooseReplace(val file: Long) : Call()

    /**
     * The files at [paths] (each as the host's open dialog or a drop gave it) uploaded as new files under [parentId] (null:
     * the space's top) of [scopeId], one batch labelled [label] (the level's name as the host shows it) — `DepotUploads.upload`.
     * The core opens each path itself and reads it at offsets a frame at a time (ADR 0131 decision 3): nothing of a file
     * crosses, and a file of any size is never held whole. A path that is no file that reads is left out. Answered once
     * queued ([Answer.Queued]: how many); a name a file already holds offers its replace through [UploadsShown.said].
     */
    @Serializable @SerialName("Upload.Files") data class UploadFiles(val scopeId: String, val parentId: String?, val label: String, val paths: List<String>) : Call()

    /**
     * The directory at [path] uploaded whole under [parentId] of [scopeId] (`DepotUploads.uploadFolder`): walked by the core
     * (`PickedPaths.walk`: a link reported and never followed), its folders made while [UploadsShown.preparing] says so,
     * then its files queued as one batch under its own name. Answered once that is done ([Answer.Queued]: how many files,
     * 0 where none went); what it left out or why it went nowhere arrives as [UploadsShown.said]. Refused where [path] is
     * no directory that lists.
     */
    @Serializable @SerialName("Upload.Folder") data class UploadFolder(val scopeId: String, val parentId: String?, val path: String) : Call()

    /**
     * File [file]'s content replaced with the file at [path] (`DepotUploads.replace`), expected over the content shown when
     * Replace was chosen ([FileChooseReplace]): answered [Answer.Queued] once queued; refused where Replace was not chosen
     * or [path] is no file that reads. Someone's replace meanwhile offers "Replace anyway" through [UploadsShown.said].
     */
    @Serializable @SerialName("Upload.Replace") data class UploadReplace(val file: Long, val path: String) : Call()

    /** Cancels upload item [item] (a waiting one at once, a running one at its next frame); its empty file is given up. */
    @Serializable @SerialName("Upload.Cancel") data class UploadCancel(val item: String) : Call()

    /** Tries failed upload item [item] again ([UploadsShown.Item.canRetry]). */
    @Serializable @SerialName("Upload.Retry") data class UploadRetry(val item: String) : Call()

    /** Cancels every upload still waiting or running. */
    @Serializable @SerialName("Upload.CancelAll") data object UploadCancelAll : Call()

    /** The tray closed batch [batch] (one with a failure stays until this; one all in leaves by itself). */
    @Serializable @SerialName("Upload.Acknowledge") data class UploadAcknowledge(val batch: String) : Call()

    /** What the uploads said ([UploadsShown.said], by its id) taken by its action: the replace it offers is queued. */
    @Serializable @SerialName("Upload.TakeOffer") data class UploadTakeOffer(val said: Long) : Call()

    /** What the uploads said was shown and dismissed without its action: the next waits. */
    @Serializable @SerialName("Upload.Seen") data class UploadSeen(val said: Long) : Call()

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

    /**
     * The share of space [scopeId]'s top level (`ShellModel.topLevelShareSheet`, core's `ShareSheet.topLevel`): "Add files
     * only" alone, its form open from the start, the drop box made at the space's top with its first link; answered with its
     * id ([Answer.ShareOpened]) for [Watched.Share] and the `Share.*` calls ([ShareShown.atTopLevel]). Offered where
     * [ShellShown.topLevelShare] says so.
     */
    @Serializable @SerialName("Share.OpenTopLevel") data class ShareOpenTopLevel(val scopeId: String) : Call()

    /**
     * Drop box [node]'s page (`ShellModel.dropBoxPage`, core's `DropBoxPage`: its guests' folders, read anew on each
     * revision of the shell's lists — a [ShellRefresh] reads a guest's new folder), answered with its id
     * ([Answer.DropBoxOpened]) for [Watched.DropBox]. It stays until [DropBoxClose] or the tenant closes.
     */
    @Serializable @SerialName("DropBox.Open") data class DropBoxOpen(val node: NodeAt) : Call()
    @Serializable @SerialName("DropBox.Close") data class DropBoxClose(val box: Long) : Call()

    /**
     * "Move {name} out…" on the guest's folder listed as [folder] (its node id): the move sheet on it (`DropBoxPage.at`),
     * answered [Answer.MoveOpened] for [Watched.Move] and the `Move.*` calls (its move out of the drop box asked first).
     */
    @Serializable @SerialName("DropBox.MoveOut") data class DropBoxMoveOut(val box: Long, val folder: String) : Call()

    /** "Delete {name}" on the guest's folder listed as [folder]: asked first (`askDelete`); [DropBoxCancelAsk] declines it. */
    @Serializable @SerialName("DropBox.AskDelete") data class DropBoxAskDelete(val box: Long, val folder: String) : Call()
    @Serializable @SerialName("DropBox.CancelAsk") data class DropBoxCancelAsk(val box: Long) : Call()

    /**
     * The delete asked, answered for [folder] (`DropBoxPage.delete`): the folder trashed through its header with the core's
     * facts of a guest's folder ([Answer.Trashed]: what it says, whether its undo is offered); refused `NOT_ASKED` where
     * this folder's delete was not asked — nothing trashed.
     */
    @Serializable @SerialName("DropBox.Delete") data class DropBoxDelete(val box: Long, val folder: String) : Call()

    /** The last delete's undo while offered: [Answer.Said] with why it failed, or none. */
    @Serializable @SerialName("DropBox.Undo") data class DropBoxUndo(val box: Long) : Call()

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

    /**
     * A QR scan (core's `QrScan`, the QR CR's D5) reading codes as [kind] says, answered with its id
     * ([Answer.ScanOpened]) for [Watched.Scan] and the calls below. It starts `STARTING`: the host opens its camera and
     * says what it did ([ScanCamera]). It stays until [ScanClose]; a scan is no tenant's (a sign-in scans before one opens).
     * [server] is the sign-in form's server as typed for a [ScanKind.KIT] scan (the other-server notice's), null for
     * every other kind.
     */
    @Serializable @SerialName("Scan.Open") data class ScanOpen(val kind: ScanKind, val server: String? = null) : Call()

    /** What the host's camera did: `RUNNING` (frames follow), `NONE` (the device has none), `DENIED` (the app may not). */
    @Serializable @SerialName("Scan.Camera") data class ScanCamera(val scan: Long, val camera: me.raddatz.veyq.mobile.qr.QrScan.Camera) : Call()

    /**
     * One camera frame: its luminance plane (the Y of YUV, or GRAY8), [width] × [height] at [stride] bytes a row, rides
     * beside the JSON as `veyq_call`'s inbound payload (ADR 0131 decision 1's other half), never in it. The core fits it to
     * the scan's bound (`QrFrames.fit`, one rule for every camera) and hands it to the scan. Answered at once
     * ([Answer.FrameTaken]): taken, or dropped while a decode runs or the scan reads none; the host sends its next frame
     * after this answer (the call is the backpressure). Refused `NOT_A_FRAME` without a payload or one shorter than the
     * plane. What a code says never comes back: [ScanShown.found] is what the scan's kind made of it.
     */
    @Serializable @SerialName("Scan.Frame") data class ScanFrame(val scan: Long, val width: Int, val height: Int, val stride: Int) : Call()

    /** Ends scan [scan] (`QrScan.close`): a decode in flight finds nothing after it. */
    @Serializable @SerialName("Scan.Close") data class ScanClose(val scan: Long) : Call()

    /**
     * A Memory [name] made where New makes it (`ShellModel.createMemory`: in [scopeId] under [parentId], null the space's
     * top; `MemoryCreate` checks the name against the loaded tree before anything is sent): [Answer.Created] — the node made,
     * which the host opens ([ShellOpen]), or why not, as the web's `MemoryNodeDialog` says it.
     */
    @Serializable @SerialName("Shell.CreateMemory") data class ShellCreateMemory(val scopeId: String, val parentId: String?, val name: String) : Call()

    /**
     * Memory or chapter [node]'s page (`ShellModel.galleryModel`, core's `GalleryModel`, opened as a member over the tenant's
     * Memories uploads), answered with its id ([Answer.GalleryOpened]) for [Watched.Gallery] and the calls below; refused
     * `NOT_OFFERED` for another kind. It reads anew on each revision of the shell's lists, when a medium this device sent
     * into it is in, and, while [Watched.GalleryLive] stands, when its media moved. It stays until [GalleryClose] or the
     * tenant closes (its lightbox and that lightbox's reader with it).
     */
    @Serializable @SerialName("Gallery.Open") data class GalleryOpen(val node: NodeAt) : Call()
    @Serializable @SerialName("Gallery.Close") data class GalleryClose(val gallery: Long) : Call()

    /** The gallery read anew now (a desktop's Refresh, F5 / Ctrl+R). */
    @Serializable @SerialName("Gallery.Reload") data class GalleryReload(val gallery: Long) : Call()

    /**
     * Medium [mediaId]'s tile: the grid's tier, whole ([Answer.Bytes], beside the JSON; ADR 0131 decision 1) for the host to
     * decode and cache by the row's [GalleryRow.tile]; refused `NO_BYTES` where the row draws its icon or it did not read.
     */
    @Serializable @SerialName("Gallery.Tile") data class GalleryTile(val gallery: Long, val mediaId: String) : Call()

    /** Chapter [nodeId]'s cover picture as stored ([Answer.Bytes]), for its row; refused `NO_COVER` where it has none. */
    @Serializable @SerialName("Gallery.ChapterCover") data class GalleryChapterCover(val gallery: Long, val nodeId: String) : Call()

    /**
     * Medium [mediaId] deleted the way the rights say (`GalleryModel.trash`): a member's to the trash, out of the rows at
     * once — [Answer.Trashed] with what it says and whether its Undo is offered now ([GalleryShown.undo] says when the offer
     * ended: the window is the core's) —; a guest's own one asked first ([GalleryShown.purging]; [Answer.Said] with none).
     */
    @Serializable @SerialName("Gallery.Trash") data class GalleryTrash(val gallery: Long, val mediaId: String) : Call()

    /** The last trash's Undo while offered (`GalleryModel.undo`): [Answer.Said] with why it was not restored, or none. */
    @Serializable @SerialName("Gallery.Undo") data class GalleryUndo(val gallery: Long) : Call()

    /** The purge asked, answered (`purge`): [Answer.Said] with why it did not go, or none. [GalleryCancelAsk] declines it. */
    @Serializable @SerialName("Gallery.Purge") data class GalleryPurge(val gallery: Long) : Call()
    @Serializable @SerialName("Gallery.CancelAsk") data class GalleryCancelAsk(val gallery: Long) : Call()

    /** What [GalleryShown.said] said was seen (`seen`): it goes. */
    @Serializable @SerialName("Gallery.Seen") data class GallerySeen(val gallery: Long) : Call()

    /**
     * A chapter [name] made in the gallery's Memory (`GalleryModel.createChapter`, offered where [GalleryShown.creates] holds
     * it): [Answer.Created], the chapter to open or why not.
     */
    @Serializable @SerialName("Gallery.CreateChapter") data class GalleryCreateChapter(val gallery: Long, val name: String) : Call()

    /**
     * The files at [paths] (each as the host's open dialog or a drop gave it) added to gallery [gallery]'s Memory or chapter
     * (`GalleryModel.upload` onto the app's one queue, `MediaUploads`): the core opens each path and reads it at offsets a
     * frame at a time (ADR 0131 decision 3), each one's tiers asked of the host on the queue's own thread
     * ([HostRequest.MediaTiers]). One that is no photo or video, or no file that reads, is left out. Answered once queued
     * ([Answer.Queued]: how many, and what is said of those left out); refused `NOT_OFFERED` where the rights add nothing.
     */
    @Serializable @SerialName("Memory.Upload") data class MemoryUpload(val gallery: Long, val paths: List<String>) : Call()

    /**
     * The lightbox over gallery [gallery] at row [index] (`GalleryModel.lightbox`, core's `Lightbox`: one per gallery, an
     * open one closes), answered with its id ([Answer.LightboxOpened]) for [Watched.Lightbox] and the calls below. It
     * follows the gallery's reads (its medium kept by id) and closes with [LightboxClose] or its gallery.
     */
    @Serializable @SerialName("Lightbox.Open") data class LightboxOpen(val gallery: Long, val index: Int) : Call()
    @Serializable @SerialName("Lightbox.Close") data class LightboxClose(val lightbox: Long) : Call()

    /** Paged to row [index] by the person (`go`): the slideshow pauses, a video playing on the page left is closed. */
    @Serializable @SerialName("Lightbox.Go") data class LightboxGo(val lightbox: Long, val index: Int) : Call()

    /** Medium [mediaId]'s picture in the lightbox: its preview, else its original, whole ([Answer.Bytes]); refused `NO_BYTES`. */
    @Serializable @SerialName("Lightbox.Picture") data class LightboxPicture(val lightbox: Long, val mediaId: String) : Call()

    /** The slideshow started ([on]) or paused (`slideshow`): its clock is the core's ([LightboxShown.slideshow]). */
    @Serializable @SerialName("Lightbox.Slideshow") data class LightboxSlideshow(val lightbox: Long, val on: Boolean) : Call()

    /**
     * Medium [mediaId]'s details (`Lightbox.details`: the EXIF tier read once per medium, the uploaders once per gallery):
     * [Answer.MediumDetails]; refused `NO_MEDIUM` where it is not in the gallery.
     */
    @Serializable @SerialName("Lightbox.Details") data class LightboxDetails(val lightbox: Long, val mediaId: String) : Call()

    /** The current medium deleted as its gallery deletes it ([GalleryTrash]'s answers). */
    @Serializable @SerialName("Lightbox.Trash") data class LightboxTrash(val lightbox: Long) : Call()

    /** The player's view went (`stop`): the medium's reader closes. */
    @Serializable @SerialName("Lightbox.Stop") data class LightboxStop(val lightbox: Long) : Call()

    /**
     * The current medium's original opened for the player (`Lightbox.play`; ADR 0131 decision 2): [Answer.Reader], read
     * through [ReaderRead] from the player's own thread, never decrypted whole or to disk (ADR 0047); refused `NO_MEDIUM`
     * for a picture, a blocked medium or where the rights download nothing. The core closes it when the lightbox pages away,
     * the player stops ([LightboxStop]), the lightbox, its gallery or the tenant closes: a read after that is refused.
     */
    @Serializable @SerialName("Medium.Reader") data class MediumReader(val lightbox: Long) : Call()

    /**
     * The current medium's original saved into the file at [path], which the host's save dialog gave (`Lightbox.save` under
     * [LightboxShown.downloadName]; ADR 0131 decision 3: the core opens and writes it): answered at once with the save's id
     * ([Answer.Saving]) for [Watched.Save] and [SaveCancel], as a file's Save a copy. Refused `NOTHING_TO_DOWNLOAD` for a
     * blocked medium or where the rights download nothing. A save outlives its lightbox; every save stops with the tenant.
     */
    @Serializable @SerialName("Medium.Save") data class MediumSave(val lightbox: Long, val path: String) : Call()
}

/**
 * What a scan reads a code as (`Scan.Open`): the parser the core holds for it, so the code's text stays in the core.
 * A caller's own kind (an Emergency Kit's, a device link's) joins with its CR.
 */
@Serializable
internal enum class ScanKind {
    /** A code of any of veyq's schemes (`QrSchemes.of`), found as its scheme alone — the desktops' debug harness's. */
    SCHEME,

    /**
     * An Emergency Kit's code, the sign-in's own scan (`SessionModel.scanKit`: a v3 `veyqkit:` token; any other code, an
     * older kit's too, is skipped and scanning goes on), found as `veyqkit` — the kit itself goes to the sign-in
     * (`takeKit`: email and server filled, the Secret Key held by the core), never across the bridge.
     */
    KIT,
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
     * Bytes the UI shows, carried beside this JSON as the callback's payload (`bytes`, `len`), never in it (ADR 0131
     * decision 1): an answer of this kind always has a payload, an empty one included. [mediaType] is the core's
     * sniffed type (`Sniff`), null where the core does not know it. (`type` is the wire's tag, so the field is named
     * otherwise.)
     */
    @Serializable @SerialName("Bytes") data class Bytes(val mediaType: String?) : Answer()

    /**
     * A blob opened for reading in ranges (ADR 0131 decision 2): [reader] is its id for [Call.ReaderRead] and
     * [Call.ReaderClose], [size] its plaintext's length. Every reader of a tenant closes when the tenant does.
     */
    @Serializable @SerialName("Reader") data class Reader(val reader: Long, val size: Long, val mediaType: String?) : Answer()

    @Serializable @SerialName("HeaderOpened") data class HeaderOpened(val header: Long) : Answer()
    @Serializable @SerialName("FileOpened") data class FileOpened(val file: Long) : Answer()

    /** A copy being saved ([Call.FileSave]): its id, for [Watched.Save] and [Call.SaveCancel]. */
    @Serializable @SerialName("Saving") data class Saving(val save: Long) : Answer()

    /**
     * An upload call's files queued ([Call.UploadFiles], [Call.UploadFolder], [Call.UploadReplace], [Call.MemoryUpload]): how
     * many, and what is said of those left out ([notice]: `MediaUploads.notTaken`; null where none was).
     */
    @Serializable @SerialName("Queued") data class Queued(val count: Int, val notice: Notice? = null) : Answer()
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
    @Serializable @SerialName("DropBoxOpened") data class DropBoxOpened(val box: Long) : Answer()
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

    /** A scan opened ([Call.ScanOpen]): its id, for [Watched.Scan] and the scan's calls. */
    @Serializable @SerialName("ScanOpened") data class ScanOpened(val scan: Long) : Answer()

    /** An account's Settings opened ([Call.SettingsOpen]): its id, for [Watched.Settings] and its calls. */
    @Serializable @SerialName("SettingsOpened") data class SettingsOpened(val settings: Long) : Answer()

    /** A frame sent ([Call.ScanFrame]): [taken] to be read, or dropped (a decode runs, or the scan reads none now). */
    @Serializable @SerialName("FrameTaken") data class FrameTaken(val taken: Boolean) : Answer()

    /** A Memory's or a chapter's page opened ([Call.GalleryOpen]): its id, for [Watched.Gallery] and its calls. */
    @Serializable @SerialName("GalleryOpened") data class GalleryOpened(val gallery: Long) : Answer()

    /** A lightbox opened ([Call.LightboxOpen]): its id, for [Watched.Lightbox] and its calls. */
    @Serializable @SerialName("LightboxOpened") data class LightboxOpened(val lightbox: Long) : Answer()

    /** A Memory or a chapter made ([Call.ShellCreateMemory], [Call.GalleryCreateChapter]): the node to open, or why not. */
    @Serializable @SerialName("Created") data class Created(val node: NodeAt?, val notice: Notice?) : Answer()

    /**
     * A medium's details ([Call.LightboxDetails]), in the web's order: each row with its label and how it shows; who added
     * it ([uploader]; null: not known) and where that row stands among them ([uploaderAt], `MediaDetails.uploaderAt`).
     */
    @Serializable @SerialName("MediumDetails") data class MediumDetails(val rows: List<DetailRow>, val uploader: Piece?, val uploaderAt: Int) : Answer()

    enum class Why {
        NOT_A_CALL, NO_TENANT_OPEN, NO_SUCH_CONNECTION, NO_SUCH_TENANT, NO_SUCH_POT, NO_SUCH_READER, NO_PICTURE, NOTHING_TO_DOWNLOAD,
        NO_SUCH_HEADER, NO_SUCH_TRASH, NO_SUCH_ROW, NO_SUCH_MOVE, NO_SUCH_SHARE, NO_SUCH_REPORT, NO_COVER, NO_SUCH_ENTRY, NO_SUCH_EDITOR,
        NO_RECEIPT, NOT_WRITTEN, NO_SUCH_SETTINGS, NO_SUCH_CREATE, NO_SUCH_FILE, NO_BYTES, NO_SUCH_SAVE, SAVE_ENDED, NO_MEDIUM,

        /** An upload: no replace offered or chosen on that page; a path that is no file (or directory) that reads; no such said. */
        NO_REPLACE, NOT_CHOSEN, NO_SUCH_PATH, NO_SUCH_SAID,

        /** A drop box's page: none by that id; no guest's folder listed by that id; a delete that was not asked. */
        NO_SUCH_DROPBOX, NO_SUCH_FOLDER, NOT_ASKED,

        /** A scan: none by that id; a frame without its plane beside the JSON, or one shorter than its size says. */
        NO_SUCH_SCAN, NOT_A_FRAME,

        /** An account's Settings: none by that id (closed, or its tenant closed). */
        NO_SUCH_ACCOUNT_PAGE,

        /** Memories: no page or lightbox by that id; what the page does not offer whoever looks (its rights, its kind). */
        NO_SUCH_GALLERY, NO_SUCH_LIGHTBOX, NOT_OFFERED,

        /** A kit file ([Call.SessionSignInKit]) in which no kit was read, or one picked while no sign-in is shown. */
        NO_KIT,

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
     * Pot [pot]'s live poll (`PotModel.live`, ADR 0130 decision 2), for as long as this watch stands: a host watches it
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

    /** A file's page opened by [Call.FileOpen]. */
    @Serializable @SerialName("File") data class File(val file: Long) : Watched()

    /** A copy being saved by [Call.FileSave]. */
    @Serializable @SerialName("Save") data class Save(val save: Long) : Watched()

    /** The uploads of the tenant open now (`DepotUploads`), whichever that is: the tray, what it says once. */
    @Serializable @SerialName("Uploads") data object Uploads : Watched()

    /** A drop box's page opened by [Call.DropBoxOpen]. */
    @Serializable @SerialName("DropBox") data class DropBox(val box: Long) : Watched()

    /** A QR scan opened by [Call.ScanOpen]. */
    @Serializable @SerialName("Scan") data class Scan(val scan: Long) : Watched()

    /** An account's Settings opened by [Call.SettingsOpen]. */
    @Serializable @SerialName("Settings") data class Settings(val settings: Long) : Watched()

    /** A Memory's or a chapter's page opened by [Call.GalleryOpen]. */
    @Serializable @SerialName("Gallery") data class Gallery(val gallery: Long) : Watched()

    /**
     * Gallery [gallery]'s live poll (`GalleryModel.live`, the web's `mediaRevisionPolling.ts`), for as long as this watch
     * stands: a host watches it while the page shows and its window is active. It delivers no update of its own — a moved
     * gallery arrives on [Gallery]'s — and ends with the watch or the page.
     */
    @Serializable @SerialName("GalleryLive") data class GalleryLive(val gallery: Long) : Watched()

    /** A lightbox opened by [Call.LightboxOpen]. */
    @Serializable @SerialName("Lightbox") data class Lightbox(val lightbox: Long) : Watched()
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

    /** A file's page; null once it was closed (or an id the bridge does not hold). */
    @Serializable @SerialName("File") data class File(val file: Long, val shown: FileShown?) : Update()

    /** A copy being saved; null for an id the bridge does not hold (its tenant closed). */
    @Serializable @SerialName("Save") data class Save(val save: Long, val shown: SaveShown?) : Update()

    /** The uploads; null while no tenant is open. */
    @Serializable @SerialName("Uploads") data class Uploads(val shown: UploadsShown?) : Update()

    /** A drop box's page; null once it was closed (or an id the bridge does not hold). */
    @Serializable @SerialName("DropBox") data class DropBox(val box: Long, val shown: DropBoxShown?) : Update()

    /** A QR scan; null for an id the bridge does not hold (it was closed). */
    @Serializable @SerialName("Scan") data class Scan(val scan: Long, val shown: ScanShown?) : Update()

    /** An account's Settings; null once it was closed (or an id the bridge does not hold). */
    @Serializable @SerialName("Settings") data class Settings(val settings: Long, val shown: SettingsShown?) : Update()

    /** A Memory's or a chapter's page; null once it was closed (or its tenant was). */
    @Serializable @SerialName("Gallery") data class Gallery(val gallery: Long, val shown: GalleryShown?) : Update()

    /** A lightbox; null once it was closed (or its gallery was). */
    @Serializable @SerialName("Lightbox") data class Lightbox(val lightbox: Long, val shown: LightboxShown?) : Update()
}

/**
 * A Memory's or a chapter's page as the host draws it (core's `GalleryModel`, ADR 0130 decision 2; one view for a member and
 * a link's guest, whose rights only remove functions), each field the model's own answer:
 * - its [node], its [rows] in the grid's order (null while the first read runs), [failed] (the last read did not: the rows
 *   before stay), how many it holds ([count], `memory_items`), and a Memory's [chapters] (each cover through
 *   [Call.GalleryChapterCover]);
 * - what it offers whoever looks: [canUpload] ([Call.MemoryUpload]; the header's own "upload" too), [canSave] (a medium's
 *   original saved and played), each row's delete ([GalleryRow.canDelete]) and its New ([creates]: a chapter on a Memory);
 * - a trash's Undo while the core offers it ([undo]: the host shows Undo until this is null again), the guest's own medium
 *   whose purge is asked ([purging], with its question), and what the last delete or Undo left to say ([said], until
 *   [Call.GallerySeen]).
 * The tiles' bytes never ride here: a row names its tier's blob ([GalleryRow.tile]), asked with [Call.GalleryTile].
 */
@Serializable
@SerialName("GalleryShown")
internal class GalleryShown(
    val node: NodeAt,
    val rows: List<GalleryRow>?,
    val failed: Boolean,
    val count: Notice,
    val chapters: List<NodeAt>,
    val canUpload: Boolean,
    val canSave: Boolean,
    val creates: List<ShellShown.ShownCreate>,
    val undo: Notice?,
    val purging: String?,
    val purgeQuestion: Notice?,
    val said: Notice?,
)

/**
 * One medium of a gallery (`GalleryModel.Row`): its id, its original's sealed name and type (claims), whether it is a video
 * or blocked (its lightbox then says [said]), the blob its tile draws ([tile]; null: the icon) and its lightbox shows
 * ([picture]; null: a video without a poster, or blocked) — what a host's decoded cache keys by —, its capture time (epoch
 * ms, the host says it), its size as shown, and whether its delete is offered ([canDelete], `GalleryModel.deletes`).
 */
@Serializable
@SerialName("GalleryRow")
internal class GalleryRow(
    val mediaId: String,
    val name: String?,
    val mediaType: String?,
    val video: Boolean,
    val blocked: Boolean,
    val tile: String?,
    val picture: String?,
    val capturedAt: Long?,
    val width: Int?,
    val height: Int?,
    @Serializable(with = MsgKey::class) val said: Msg?,
    val canDelete: Boolean,
)

/**
 * A lightbox as the host draws it (core's `Lightbox`): where it is ([index] of [count], [position] its words; -1 and none
 * where the gallery holds none), the current medium ([row]; its picture through [Call.LightboxPicture], a video through
 * [Call.MediumReader]), whether its reader is open ([playing]), the slideshow's state (null before it first started; the
 * model runs its clock), what its original is saved as ([downloadName], what a save dialog proposes; null for none) and
 * whether its delete is offered ([canTrash]). A video never plays by itself.
 */
@Serializable
@SerialName("LightboxShown")
internal class LightboxShown(
    val index: Int,
    val count: Int,
    val position: Notice?,
    val row: GalleryRow?,
    val playing: Boolean,
    val slideshow: SlideshowState?,
    val downloadName: String?,
    val canTrash: Boolean,
)

/** Where a lightbox's slideshow stands (`Lightbox.Slideshow`; its own name: the generated hosts take a type's last name part). */
@Serializable
internal enum class SlideshowState { PLAYING, PAUSED, ENDED }

/**
 * One row of a medium's details (`MediaDetails.Row`, shown by `MediaDetails.shown`): its [label], and its value — in words
 * ([words]), a size the host writes its way ([size], bytes), a moment it says its way ([moment], epoch ms), or as read
 * ([text]); exactly one of them.
 */
@Serializable
@SerialName("DetailRow")
internal class DetailRow(
    @Serializable(with = MsgKey::class) val label: Msg,
    val words: Notice?,
    val size: Long?,
    val moment: Long?,
    val text: String?,
)

/**
 * An account's Settings page as the host draws it (core's `AccountSettings.State`, `cr-app-account-settings` D1), each
 * field the core's: the address and whether the server holds it confirmed (null until read); this device's name and how
 * it trusts the account ([trusted], [level] and the [levels] it may move to, as [ManagedShown]'s); the session families,
 * this device's first (null until the list first read); the devices with a saved key and no session ([keptDevices]);
 * whether "Remove every saved key except this device's" ([otherSavedKeys]) and "Sign out every other" ([hasOthers]) have
 * anything to do; whether "Add a device" is offered ([addDevice], `core.device-linking`); [working] while an action runs;
 * why the last action or the list did not come through ([refusal]) and why the device names are missing
 * ([registerRefusal]). A family's id is the server's name for it, no token; nothing of a key crosses.
 */
@Serializable
@SerialName("SettingsShown")
internal class SettingsShown(
    val email: String,
    val emailConfirmed: Boolean?,
    val device: String,
    val trusted: TrustedDevice.Method?,
    val level: StaySignedIn?,
    val levels: List<StaySignedIn>,
    val families: List<SettingsFamily>?,
    val keptDevices: List<SettingsKeptDevice>,
    val otherSavedKeys: Boolean,
    val hasOthers: Boolean,
    val addDevice: Boolean,
    val working: Boolean,
    @Serializable(with = MsgKey::class) val refusal: Msg?,
    @Serializable(with = MsgKey::class) val registerRefusal: Msg?,
)

/**
 * One session family: created and last used (epoch ms; null where the server sent none), whether it is this device's,
 * the register's name for its device (null: the dates show), and whether "Sign out and remove saved key" is offered.
 */
@Serializable
@SerialName("SettingsFamily")
internal class SettingsFamily(val id: String, val createdAt: Long?, val lastUsedAt: Long?, val current: Boolean, val label: String?, val removesKey: Boolean)

/** A device the register knows that holds a saved key and no session: [Call.SettingsRemoveSavedKey] by its [deviceId]. */
@Serializable
@SerialName("SettingsKeptDevice")
internal class SettingsKeptDevice(val deviceId: String, val label: String)

/**
 * A QR scan as the host draws it (core's `QrScan.State`): the camera's preview while [state] is `STARTING` or
 * `SCANNING`, the caller's words for every other. [found] is what the scan's kind made of the code it settled on (a
 * [ScanKind.SCHEME] scan's scheme), never the code's text; null before `FOUND`.
 */
@Serializable
@SerialName("ScanShown")
internal class ScanShown(val state: ScanState, val found: String?)

/** A scan's state as [ScanShown] names it (its own name: the generated hosts take a type's last name part). */
@Serializable
internal enum class ScanState { STARTING, NO_CAMERA, DENIED, SCANNING, FOUND, CLOSED }

/**
 * The uploads as the host draws them (core's `DepotUploads` over the app's one upload queue, the web's `UploadStatusMenu`),
 * each field one core function's answer: the tray's one line ([summary], `Notices.uploadSummary`; null while nothing is
 * listed: no tray) with how many run ([active]) and how far all is ([progress], 0 to 1); a picked directory whose folders
 * are being made ([preparing], `upload_preparing_folder`); each batch with its items; and what the uploads say once
 * ([said]): a folder's outcome, or a replace offered with its action's words — answered by [Call.UploadTakeOffer] or
 * [Call.UploadSeen], the next one waiting until then.
 */
@Serializable
@SerialName("UploadsShown")
internal class UploadsShown(
    val summary: Notice?,
    val active: Int,
    val progress: Double,
    val preparing: Notice?,
    val batches: List<Batch>,
    val said: Said?,
) {
    /** One batch: where it goes ([label]), whether all its items settled ([finished]: the tray may close it). */
    @Serializable @SerialName("UploadBatch")
    class Batch(val id: String, val label: String, val finished: Boolean, val items: List<Item>)

    /**
     * One file of a batch: its [status] and [progress] (0 to 1 while it is sent), its line ([line], `Notices.uploadStatus`:
     * why where it failed), and whether it is cancelled ([canCancel]: while it may still go up) or tried again ([canRetry]).
     */
    @Serializable @SerialName("UploadItem")
    class Item(
        val id: String, val name: String, val sizeBytes: Long, val status: me.raddatz.veyq.mobile.blob.UploadQueue.Status,
        val progress: Double, val line: Notice, val failed: Boolean, val canCancel: Boolean, val canRetry: Boolean,
    )

    /** What is said now: one line per [notices], and the offer's button ([action]; null where it offers nothing). */
    @Serializable @SerialName("UploadSaid")
    class Said(val id: Long, val notices: List<Notice>, @Serializable(with = MsgKey::class) val action: Msg?)
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
 * when the field is not being typed into or the text is not one it sent (ADR 0123's Consequences, Expenses whole).
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
 * A file's page as the host draws it (core's `FileModel`, ADR 0130's Consequences), each field the model's own answer:
 * - [view], what shows in the content's place, and [words], what the page says there where it shows no content (opening,
 *   failed, not uploaded yet, too large, empty, not shown here — and a medium, for a host that does not play one);
 * - an image ([mediaType], decoded to [edge] pixels on its longest side at most) and a PDF, their bytes through
 *   [Call.FileBytes], asked again when [revision] turns; a text's [text] (its first megabyte) and, where it was cut, what
 *   the page says above it ([truncated]); a medium's [mediaType] and [size];
 * - its [facts] (size, type, modified) and [uploader] line; what its header knows ([headerFacts], for [Call.HeaderFacts]),
 *   and the name a copy is saved as ([downloadName]). Null where the file is not read yet.
 */
@Serializable
@SerialName("FileShown")
internal class FileShown(
    val node: NodeAt,
    val view: View,
    @Serializable(with = MsgKey::class) val words: Msg?,
    val mediaType: String?,
    val edge: Int?,
    val text: String?,
    val truncated: Notice?,
    val size: Long?,
    val revision: Int,
    val facts: List<Piece>,
    val uploader: Notice?,
    val headerFacts: AppKinds.Facts?,
    val downloadName: String?,
) {
    /** Named on the wire so no host's own `View` is shadowed. */
    @Serializable @SerialName("FileViewKind")
    enum class View { LOADING, FAILED, NOT_UPLOADED, MESSAGE, IMAGE, TEXT, PDF, MEDIA }
}

/**
 * A copy being saved as the host draws it (core's `FileSave`): its [name], how much is [written] of [total] ([fraction],
 * 0 to 1), how it ended ([end]; null while it runs) and what it says ([notice]: its progress, then its end).
 */
@Serializable
@SerialName("SaveShown")
internal class SaveShown(
    val name: String,
    val written: Long,
    val total: Long,
    val fraction: Double,
    val end: me.raddatz.veyq.mobile.depot.FileSave.End?,
    val notice: Notice,
)

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
    /**
     * The file name a download's save dialog suggests (`NodeHeaderModel.State.downloadName`): a file's own name, any other
     * node's archive (`NodeExport.fileName`) — the web's names.
     */
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
 * A drop box's page as the host draws it (`DropBoxPage`), each field one core function's answer: the drop box, its guests'
 * folders in the order they came (null while the first read runs) — each by its node id with its label (`DropBoxes.labels`:
 * a name more than one guest gave numbered), how many files it holds, its menu's words and its files (opened as any object,
 * [Call.ShellOpen]) —, whether the last read failed, the words when nobody dropped anything yet ([empty]) and above the
 * folders ([hint]), and the folder whose delete is asked ([deleting]) with its question.
 */
@Serializable
internal class DropBoxShown(
    val node: NodeAt,
    val folders: List<ShownGuestFolder>?,
    val failed: Boolean,
    @Serializable(with = MsgKey::class) val empty: Msg,
    @Serializable(with = MsgKey::class) val hint: Msg,
    val deleting: String?,
    val deleteTitle: Notice?,
    val deleteQuestion: Notice?,
    @Serializable(with = MsgKey::class) val deleteAccept: Msg,
) {
    @Serializable class ShownGuestFolder(
        val id: String,
        val label: String,
        val count: Notice,
        val moveOut: Notice,
        val delete: Notice,
        val files: List<ObjectRef>,
    )
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
    /** The share of a space's top level ([Call.ShareOpenTopLevel]): its form alone, nothing listed — once the form closes, the sheet is done. */
    val atTopLevel: Boolean,
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
 * (ADR 0130 decision 1). Built by [Bridge] one line per field; a rule found there is a defect to move into the core.
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
    /** What the level's New offers (`AppKinds.createsWhere`), each with its words. */
    val creates: List<ShownCreate>,
    /** Whether New is offered where the shell is (`ShellModel.canCreateHere`: never in a guest's folder). */
    val canCreateHere: Boolean,
    /** Whether the level offers its top level's share, "Add files only" ([Call.ShareOpenTopLevel]; `ShellModel.offersTopLevelShare`). */
    val topLevelShare: Boolean,
    /** The node the content heads (`Shell.openObject`, else the folder Home shows: `Shell.folderAt`); null on a space's root. */
    val headed: NodeAt?,
    /** The signed-in member's user id, whose picture the switcher shows when [pictures] holds it. */
    val me: String?,
    /**
     * The user ids whose picture the core holds (`AvatarsModel`), each asked for with [Call.AvatarPicture]: the letter
     * for everyone else. A change of the set is a new update; the bytes never ride one (ADR 0131 decision 1).
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

    /**
     * An Emergency Kit's read (`KitImport`; ADR 0130 decision 1: the platform draws, the core decides), each on the file
     * at [path] the host itself gave ([Call.SessionSignInKit]) — a path and a number outward, never a byte of the file:
     * how many pages the platform's renderer opens the PDF with ([HostReply.Value], the count as its text; [HostReply.Failed]
     * where it does not open).
     */
    @Serializable @SerialName("Pdf.Pages") data class PdfPages(val path: String) : HostRequest()

    /** Page [page] (1-based) drawn [width] px wide on white, answered as [HostReply.Pixels]. */
    @Serializable @SerialName("Pdf.Page") data class PdfPage(val path: String, val page: Int, val width: Int) : HostRequest()

    /** The file decoded as a picture, scaled so its longer side is [longSide] px, over white: [HostReply.Pixels]. */
    @Serializable @SerialName("Picture.Luma") data class PictureLuma(val path: String, val longSide: Int) : HostRequest()

    /**
     * A picked video's length (a Memories upload, [Call.MemoryUpload]; ADR 0130 decision 1: the platform decodes, the core
     * decides): [HostReply.Value], its milliseconds as text; null or [HostReply.Failed] where the platform cannot tell. The
     * core takes the poster's time from it (`MediaUpload.posterAtMs`) for [MediaTiers].
     */
    @Serializable @SerialName("Media.Duration") data class MediaDuration(val path: String) : HostRequest()

    /**
     * The tiers of the picked photo or video at [path] (the web's `mediaDeriver.ts`, the core's spec in the request): the
     * `PREVIEW` JPEG — upright, its long edge at most [previewEdge] px, never enlarged, at [previewQuality] (0–1) — and the
     * `THUMBNAIL` JPEG made from it ([thumbnailEdge], [thumbnailQuality]); a video's poster is its frame at [posterAtMs],
     * as both. Asked on the upload queue's own thread, never the host's: answered [HostReply.Tiers] with the JPEGs as the
     * reply's payload, or a [HostReply.Tiers] with no size where the platform does not decode it (the original then goes
     * alone). [mediaType] is the core's type of the file (its bytes'): a path and numbers outward, never a byte of it.
     */
    @Serializable @SerialName("Media.Tiers") data class MediaTiers(
        val path: String, val mediaType: String, val previewEdge: Int, val previewQuality: Double, val thumbnailEdge: Int,
        val thumbnailQuality: Double, val posterAtMs: Long,
    ) : HostRequest()
}

@Serializable
internal sealed class HostReply {
    @Serializable @SerialName("Value") data class Value(val value: String?) : HostReply()
    @Serializable @SerialName("Done") data object Done : HostReply()

    /**
     * A drawing ([HostRequest.PdfPage], [HostRequest.PictureLuma]): its luminance (8 bits a pixel, 0 black, 255 white),
     * [width] × [height] packed row after row, as `veyq_reply`'s payload beside this JSON (ADR 0131 decision 3, extended
     * inward: only the host can draw, so a drawn page's pixels ride in beside a reply), never in it. A payload shorter
     * than the size is no drawing.
     */
    @Serializable @SerialName("Pixels") data class Pixels(val width: Int, val height: Int) : HostReply()

    /**
     * A picked medium's tiers ([HostRequest.MediaTiers]): the preview's [previewSize] bytes then the thumbnail's, packed as
     * `veyq_reply`'s payload beside this JSON (ADR 0131 decision 3 extended inward, as [Pixels]), never in it, and the size
     * it is shown at ([width] × [height], upright; null where unknown). [previewSize] 0 and no payload: not decoded here.
     */
    @Serializable @SerialName("Tiers") data class Tiers(val width: Int?, val height: Int?, val previewSize: Int) : HostReply()

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
