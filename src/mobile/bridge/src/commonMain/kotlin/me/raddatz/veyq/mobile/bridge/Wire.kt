package me.raddatz.veyq.mobile.bridge

import me.raddatz.veyq.mobile.navigation.ViewMode as CoreViewMode
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
import me.raddatz.veyq.mobile.composition.ShellModel
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
 * [wire] writing a [me.raddatz.veyq.mobile.words.Notice]'s and a [Piece]'s values in [language] (`Config.language`):
 * what a host reads is words and strings, never a size, a moment or an amount to format (ADR 0154 decision 5).
 */
internal fun wireIn(language: String): Json = Json(wire) { serializersModule = me.raddatz.veyq.mobile.words.WireWords.module(language) }

/**
 * What the host starts the core with ([veyq_init]): [device] the name this device gives itself in the account's device
 * register; [window] Windows' HWND of the host's top-level window, which owns Windows Hello's prompt (`0` elsewhere, and
 * for a prompt without an owner); [prompt] the words that prompt shows (the host's `device_unlock_prompt`, empty where
 * none shows); [offlineRoot] the directory the host names for the accounts' offline stores (ADR 0140 decision 3:
 * `$XDG_DATA_HOME/veyq/offline` on Linux, the app's data folder's `offline` on Windows), null where it keeps none — the
 * core makes and fills it, each bridge host a desktop, so a store starts with `AccountOffline.DESKTOP_LIMIT`; [language]
 * the app's words' language (`en`, `de`), the geocoder's `accept-language` as the web's is the reader's (ADR 0143
 * decision 2); [netLog] true in a debug build only, for its request log ([HostRequest.NetLogLine], inbox #947). None of
 * them secret: the device keys themselves are the core's (`platformDeviceKeys`).
 */
@Serializable
internal class Config(
    val device: String, val window: Long = 0, val prompt: String = "", val offlineRoot: String? = null, val language: String = "en",
    val netLog: Boolean = false,
)

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
     * How long the core's notices live (ADR 0154 decision 4), answered [Answer.Lifetimes]: a host asks once and maps a
     * notice's `kind` onto its control with it, holding no duration of its own. Answered at once, without waiting for the
     * core's start, so a host may ask it on its own thread before its first window.
     */
    @Serializable @SerialName("Notice.Lifetimes") data object NoticeLifetimes : Call()

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

    /**
     * The unlock's *Stop trusting this device* (`SessionModel.forgetUnlocked`, inbox #1052): the account `Gate.Unlock` shows
     * forgotten on this device, nothing sent; the gate then shows the next trusted account's unlock or the sign-in. Asked
     * first with [UnlockForgetAsk]. Refused `NOT_OFFERED` outside the unlock and while it works.
     */
    @Serializable @SerialName("Session.ForgetUnlocked") data object SessionForgetUnlocked : Call()

    /** "Stop trusting this device" for the connection whose key is [connection]: the next sign-in asks for the Secret Key. */
    @Serializable @SerialName("Session.ForgetDevice") data class SessionForgetDevice(val connection: String) : Call()

    /** "Add server": the sign-in again, which [SessionCancelAdd] leaves back to the shell. */
    @Serializable @SerialName("Session.AddServer") data object SessionAddServer : Call()
    @Serializable @SerialName("Session.CancelAdd") data object SessionCancelAdd : Call()

    /** The switcher: tenant [tenantId] of the connection whose key is [connection] (`Gate.Signed.connectionKey`). */
    @Serializable @SerialName("Session.SwitchTo") data class SessionSwitchTo(val connection: String, val tenantId: String) : Call()

    @Serializable @SerialName("Session.SignOut") data class SessionSignOut(val connection: String) : Call()

    /**
     * "Open Emergency Kit" at the sign-in: the kit file the host's open dialog gave, by its [path] (ADR 0137 decision 3),
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
     * The sign-in's *I don't have my Secret Key with me* (`SessionModel.signInLocked`, uc.account.sessions E3): the login
     * alone; the gate then shows `Gate.Locked` (or the sign-in with why). [Answer.Done].
     */
    @Serializable @SerialName("Session.SignInLocked") data class SessionSignInLocked(val server: String, val email: String, val password: String) : Call()

    /** The locked state's one action, asked first by the host (`SessionModel.signOutEverywhere`). [Answer.Done]; the gate says how it went. */
    @Serializable @SerialName("Session.SignOutEverywhere") data object SessionSignOutEverywhere : Call()

    /** The locked state's way back (`SessionModel.lockedBack`): the sign-in again, keeping nothing. [Answer.Done]. */
    @Serializable @SerialName("Session.LockedBack") data object SessionLockedBack : Call()

    /** The sign-in's *Forgot your password?* (`SessionModel.forgotPassword`) with the form's [server] and [email]. [Answer.Done]. */
    @Serializable @SerialName("Session.ForgotPassword") data class SessionForgotPassword(val server: String?, val email: String?) : Call()

    /** *Reset your password*'s Send (`SessionModel.requestRecovery`). [Answer.Done]; the gate says how it went. */
    @Serializable @SerialName("Session.RequestRecovery") data class SessionRequestRecovery(val server: String, val email: String) : Call()

    /** *Reset your password*'s Back (`SessionModel.backToSignIn`). [Answer.Done]. */
    @Serializable @SerialName("Session.BackToSignIn") data object SessionBackToSignIn : Call()

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
     * The account's Settings page (core's `AccountSettings`, `uc.account.sessions`) for the connection whose key is
     * [connection], answered with its id ([Answer.SettingsOpened]) for [Watched.Settings]; the core reads it at once
     * (`refresh`). Refused `NO_SUCH_CONNECTION`, or `NO_TENANT_OPEN` (it is reached from an open tenant's drawer). It stays
     * until [SettingsClose], its account's sign-out here, or the tenant closes. Each action is one call answered once it
     * ended — [Answer.Done], and why it did not come through is the state's `refusal`. [separator] is the reader's decimal
     * separator (the platform's locale data, one character), as the limit's free size field writes its amount.
     */
    @Serializable @SerialName("Settings.Open") data class SettingsOpen(val connection: String, val separator: String = ".") : Call()
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

    /**
     * "Storage on this device" (ADR 0140 decision 10; core's `AccountSettings`): the limit typed in its free size field,
     * [amount] in [unit] (`setOfflineLimit`: a positive number, a point or a comma its decimal sign; anything else is the
     * state's `refusal`, `account_settings_storage_limit_invalid`), or Off ([SettingsOfflineLimitOff]). Lowering it evicts
     * at once. Each answered [Answer.Done] once it ended, the section read anew ([SettingsShown.storage]).
     */
    @Serializable @SerialName("Settings.OfflineLimit") data class SettingsOfflineLimit(val settings: Long, val amount: String, val unit: me.raddatz.veyq.mobile.offline.LimitUnit) : Call()
    @Serializable @SerialName("Settings.OfflineLimitOff") data class SettingsOfflineLimitOff(val settings: Long) : Call()

    /** "Clear cache" (`clearOfflineCache`): what was kept of content viewed goes, the items available offline stay. */
    @Serializable @SerialName("Settings.ClearCache") data class SettingsClearCache(val settings: Long) : Call()

    /** A pinned item's Remove ([SettingsShown.storage]'s `items`, by its [node]): no longer available offline. */
    @Serializable @SerialName("Settings.OfflineRemove") data class SettingsOfflineRemove(val settings: Long, val node: String) : Call()

    /**
     * "Remove all offline content", asked first: [SettingsAskRemoveAll] shows its question ([SettingsShown.askingRemoveAll],
     * the words `account_settings_storage_remove_all_question` and `…_confirm`), [SettingsRemoveAll] confirms it — refused
     * by the core (nothing goes) while it is not asked —, [SettingsCancelRemoveAll] leaves it.
     */
    @Serializable @SerialName("Settings.AskRemoveAll") data class SettingsAskRemoveAll(val settings: Long) : Call()
    @Serializable @SerialName("Settings.RemoveAll") data class SettingsRemoveAll(val settings: Long) : Call()
    @Serializable @SerialName("Settings.CancelRemoveAll") data class SettingsCancelRemoveAll(val settings: Long) : Call()

    /**
     * The notices of items no longer available offline ([me.raddatz.veyq.mobile.composition.ShellModel.State]'s `gone`,
     * each named where the shell held its name) said: not said again (`goneSeen`).
     */
    @Serializable @SerialName("Shell.OfflineGoneSeen") data object ShellOfflineGoneSeen : Call()

    /** The reconnect's notices ([ShellShown.queuedSaid]: how many writes made offline were sent, how many need the person) said. */
    @Serializable @SerialName("Shell.QueuedSaidSeen") data object ShellQueuedSaidSeen : Call()

    @Serializable @SerialName("Shell.Choose") data class ShellChoose(val application: ApplicationId?) : Call()
    @Serializable @SerialName("Shell.SwitchSpace") data class ShellSwitchSpace(val scopeId: String) : Call()
    @Serializable @SerialName("Shell.Enter") data class ShellEnter(val folder: FolderRef) : Call()
    @Serializable @SerialName("Shell.Filter") data class ShellFilter(val application: ApplicationId?) : Call()
    @Serializable @SerialName("Shell.Back") data object ShellBack : Call()
    @Serializable @SerialName("Shell.Select") data class ShellSelect(val tab: BarTab) : Call()
    @Serializable @SerialName("Shell.Open") data class ShellOpen(val ref: ObjectRef) : Call()
    @Serializable @SerialName("Shell.OpenAtTop") data class ShellOpenAtTop(val ref: ObjectRef) : Call()

    /**
     * Node [nodeId] of [scopeId] opened where it lives (`ShellModel.openFound`: an object in its application, a folder on
     * Home at that folder); null: the space's top. What a held write's *Open* names as `QueuedOpen.Place` or `.Rename`.
     */
    @Serializable @SerialName("Shell.OpenAt") data class ShellOpenAt(val scopeId: String, val nodeId: String?) : Call()
    @Serializable @SerialName("Shell.Search") data class ShellSearch(val on: Boolean) : Call()
    /** A person's refresh (F5): the lists and the members read anew (`ShellModel.refresh(people = true)`). */
    @Serializable @SerialName("Shell.Refresh") data object ShellRefresh : Call()

    /** Home's level where the shell is, in its groups (`ShellModel.Lists.groups`). */
    @Serializable @SerialName("Home.Groups") data object HomeGroups : Call()

    /**
     * The search over the open tenant (ADR 0146 decision 13; core's `SearchModel`): answered [Answer.SearchOpened] with its id
     * for [Watched.Search] and its calls; one per field, dropped with the tenant. Refused `NO_TENANT_OPEN` without one.
     */
    @Serializable @SerialName("Search.Open") data object SearchOpen : Call()

    /** The field gained focus: the first builds the index, every later one revalidates it (`SearchModel.focus`). */
    @Serializable @SerialName("Search.Focus") data class SearchFocus(val search: Long) : Call()

    /** The text as typed now, applied in the host's order (`SearchModel.text`). */
    @Serializable @SerialName("Search.Text") data class SearchText(val search: Long, val text: String) : Call()

    /** Suggestion [index] of `SearchShown.suggestions` chosen: its chip set (`SearchModel.choose`). */
    @Serializable @SerialName("Search.Choose") data class SearchChoose(val search: Long, val index: Int) : Call()

    /** Chip [index] of `SearchShown.chips` removed. */
    @Serializable @SerialName("Search.RemoveChip") data class SearchRemoveChip(val search: Long, val index: Int) : Call()

    /** Backspace in an empty field: the last chip removed. */
    @Serializable @SerialName("Search.RemoveLastChip") data class SearchRemoveLastChip(val search: Long) : Call()

    /** *Show more*. */
    @Serializable @SerialName("Search.More") data class SearchMore(val search: Long) : Call()

    /**
     * Result [index] of `SearchShown.results` opened where it lives: a node through the shell (the shell's watch moves), a
     * person on their page, a photo of another Memory by its Memory's page with the request its gallery takes
     * (`GalleryShown`'s lightbox), a settings section by `SearchShown.openSettings`.
     */
    @Serializable @SerialName("Search.OpenResult") data class SearchOpenResult(val search: Long, val index: Int) : Call()

    /** The field was left: the text and the chips go, nothing is revalidated any more (`SearchModel.close`). */
    @Serializable @SerialName("Search.Close") data class SearchClose(val search: Long) : Call()

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
     * The editor of held write [write] on Pot [pot] (`PotModel.heldEditor`; a [Call.QueuedOpen]'s `QueuedOpen.Entry`, ADR
     * 0153 decision 4): the person's change made offline in the form over the newer version, named ([EditorShown.newer]);
     * *Discard mine* offered ([EditorShown.discardsMine], [EditorDiscardMine]). Answered [Answer.EditorOpened]; refused
     * `NO_SUCH_ENTRY` where the write is no held entry of that Pot (sent, discarded, another Pot's) or the Pot is not read.
     */
    @Serializable @SerialName("Editor.OpenHeld") data class EditorOpenHeld(val pot: Long, val write: String, val separator: String) : Call()

    /** *Discard mine* (`EntryEditor.discardMine`): the held change deleted unsent, answered as a save is ([Answer.EditorSaved]). */
    @Serializable @SerialName("Editor.DiscardMine") data class EditorDiscardMine(val editor: Long) : Call()

    /**
     * Files picked as editor [editor]'s receipts by their [paths], as the host's open dialog gave them (ADR 0137 decision 3):
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
     * `NO_RECEIPT` where it does not open (blocked, not held, not read), `NOT_KEPT_OFFLINE` where the Pot is shown offline
     * and this device does not keep the receipt (`PotModel.receiptFailure`: never shown here, nor fetched by a pin; its
     * word `offline_not_kept`). The host decodes it in memory, never from a file.
     */
    @Serializable @SerialName("Receipt.Open") data class ReceiptOpen(val pot: Long, val receipt: String) : Call()

    /** How many of a PDF of [total] pages a viewer renders up front, and what it says under them ([Answer.ReceiptPages]). */
    @Serializable @SerialName("Receipt.Pages") data class ReceiptPages(val total: Int) : Call()

    /**
     * Receipt [receipt] of Pot [pot] saved into the file at [path], which the host's save dialog gave (ADR 0137 decision 3:
     * the core opens and writes it; nothing is left there on a failure): done, or refused `NO_RECEIPT` (it does not open;
     * `NOT_KEPT_OFFLINE` offline where this device does not keep it) or `NOT_WRITTEN` (the file was not written).
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
     * Downloads Pot [pot] as the web's ZIP into the file at [path], which the host's save dialog gave (ADR 0137
     * decision 3: the core opens it and writes it; nothing of the file crosses). [deletedName] is the host's word for
     * an erased person (`pot_deleted_person`), which the CSV carries. [Answer.Done], or [Answer.Failed] with nothing
     * left at [path].
     */
    @Serializable @SerialName("Pot.Download") data class PotDownload(val pot: Long, val path: String, val deletedName: String) : Call()

    /** The file name the save dialog suggests for Pot [pot]'s download ([Answer.Name]): the core's, never the host's. */
    @Serializable @SerialName("Pot.ArchiveName") data class PotArchiveName(val pot: Long) : Call()

    /** Member [userId]'s picture ([Answer.Bytes], the image beside the JSON); refused where the letter shows. */
    @Serializable @SerialName("Avatar.Picture") data class AvatarPicture(val userId: String) : Call()

    /**
     * The WebP beside the call decoded by the core, for a host whose platform cannot decode it (Windows without its WebP
     * Image Extension): [Answer.Bytes] `image/png`, the picture as a PNG beside the JSON, which every platform decodes.
     * Refused `NOT_A_PICTURE` for no payload, what is no still WebP, a side past 4096 px, and where the core has no decoder
     * (the JVM). No tenant needed.
     */
    @Serializable @SerialName("Picture.Decode") data object PictureDecode : Call()

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
     * A level row's menu (`ShellModel.rowPlan`, the web's `levelRow.ts`; ADR 0154 decision 2): [Answer.RowPlan], drawn whole
     * through the host's gesture (a context menu), each entry run as the header's of that node.
     */
    @Serializable @SerialName("Row.Plan") data class RowPlan(val node: NodeAt) : Call()
    /**
     * A kept link's row's *Report shared content* (its row plan's `REPORT_SHARED`, `BookmarkPage.reportSharedFromRow`): the
     * link's screen with its report asked, held as a received link ([Answer.Received]); refused where the link does not read.
     */
    @Serializable @SerialName("Row.ReportShared") data class RowReportShared(val node: NodeAt) : Call()

    /**
     * The sign-in's entries below its fields (`SignInPlan.entries`, ADR 0154 decision 2): [Answer.SignInPlan], asked again
     * whenever the gate's [adding] or the host's [camera] (whether this device may have one) changes.
     */
    @Serializable @SerialName("SignIn.Plan") data class SignInPlan(val adding: Boolean, val camera: Boolean) : Call()

    /** The locked sign-in's entries (`LockedPlan.entries`): [Answer.LockedPlan]. */
    @Serializable @SerialName("SignIn.LockedPlan") data object LockedPlan : Call()

    /** *Reset your password*'s entries (`RecoverPlan.entries`), asked again when the gate's [sent] changes: [Answer.RecoverPlan]. */
    @Serializable @SerialName("SignIn.RecoverPlan") data class RecoverPlan(val sent: Boolean) : Call()

    /** The unlock's entries under its account (`UnlockPlan.entries`, ADR 0154 decision 2): [Answer.UnlockPlan]. */
    @Serializable @SerialName("Unlock.Plan") data object UnlockPlan : Call()

    /**
     * Why a Secret Key field's text, as typed, holds no key (`KeyIntake.problem`, ADR 0154 decision 1): [Answer.SecretKeyChecked],
     * asked on every edit of the sign-in's field and of every flow's. Only the problem comes back — nothing of the key.
     */
    @Serializable @SerialName("SecretKey.Check") data class SecretKeyCheck(val secretKey: String) : Call()

    /**
     * What is asked before [SessionForgetUnlocked], always (owner 2026-11-22): one question, naming the writes made offline
     * that wait for the account the unlock shows where some do (`QueuedShown.forgetAsk`, ADR 0153 decision 2):
     * [Answer.QueuedAsked], its ask never null.
     */
    @Serializable @SerialName("Unlock.ForgetAsk") data object UnlockForgetAsk : Call()

    /**
     * What the kind's page knows now (`NodeHeaderModel.update`) and, for a file's page, its id ([file]): the page's own
     * actions (`FileModel.Header.own`: Replace) join the plan, worded by the page; for a Memory's or a chapter's page, its
     * gallery's id ([gallery]): its own (`GalleryModel.headerActions`: adding photos and videos) join it the same way.
     */
    @Serializable @SerialName("Header.Facts") data class HeaderFacts(
        val header: Long, val facts: AppKinds.Facts?, val file: Long? = null, val gallery: Long? = null,
        /** The kept link's page ([Call.BookmarkOpen]) whose own entries join the plan (`BookmarkPage.headerActions`). */
        val bookmark: Long? = null,
    ) : Call()
    @Serializable @SerialName("Header.Close") data class HeaderClose(val header: Long) : Call()

    /** Pins or unpins (`toggleFavorite`): [Answer.Said] with the failure's notice, or none. */
    @Serializable @SerialName("Header.Pin") data class HeaderPin(val header: Long) : Call()

    /**
     * The header's "Available offline" (its plan's `offline` action, ADR 0140 decision 10; `NodeHeaderModel.toggleOffline`):
     * on, it turns off at once; off, the size it would keep is asked ([HeaderShown.offline]'s `asking` and `question`,
     * under the title `offline_pin_ask_title`) — nothing is kept until [HeaderOfflineConfirm] (`offline_pin_confirm`), or
     * [HeaderOfflineCancel]. [Answer.Said] with why not, or none. Offline the plan offers it only where the node is kept
     * already (turning it off); a pin is refused (`offline_read_only`) and a size asked online is dropped.
     */
    @Serializable @SerialName("Header.Offline") data class HeaderOffline(val header: Long) : Call()
    @Serializable @SerialName("Header.OfflineConfirm") data class HeaderOfflineConfirm(val header: Long) : Call()
    @Serializable @SerialName("Header.OfflineCancel") data class HeaderOfflineCancel(val header: Long) : Call()

    /**
     * To the trash (`trash`): [Answer.Trashed] with what it says and whether its undo is offered now (the state's undo,
     * read as the call returns: the host's toast takes its Undo at once, and goes when the state's undo is null again).
     */
    @Serializable @SerialName("Header.Trash") data class HeaderTrash(val header: Long) : Call()

    /** The trash's undo while offered (`undoTrash`): [Answer.Said] with why it failed, or none. */
    @Serializable @SerialName("Header.Undo") data class HeaderUndo(val header: Long) : Call()

    /** Renames the node (`rename`): [Answer.Named]. */
    @Serializable @SerialName("Header.Rename") data class HeaderRename(val header: Long, val name: String) : Call()

    /**
     * Downloads the header's node into the file at [path], which the host's save dialog gave — a file the file itself, any
     * other node with everything under it as the web's ZIP (`ShellModel.download`; ADR 0137 decision 3: the core opens it, writes it and removes a download
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
     * [path] (ADR 0137 decision 3: the core reads it; the host removes its file after), framed by [crop] — or, [path]
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
     * The bytes file [file] shows now ([Answer.Bytes], beside the JSON; ADR 0137 decision 1): an image's or a PDF's, asked
     * again when [FileShown.revision] turns. Refused where it shows neither (a text is in the state, a medium is read through
     * a reader).
     */
    @Serializable @SerialName("File.Bytes") data class FileBytes(val file: Long) : Call()

    /** A PDF viewer's pages of a file's PDF of [total] pages ([Answer.ReceiptPages]: `FileView.pagesShown`, `pagesCut`). */
    @Serializable @SerialName("File.Pages") data class FilePages(val total: Int) : Call()

    /** The page a host's web view shows an SVG in, and how it zooms ([Answer.SvgPage]: core's `SvgView`, inbox #1059). */
    @Serializable @SerialName("File.SvgPage") data object FileSvgPage : Call()

    /** The raster of an SVG laid out at [width] × [height] at [ratio] pixels each, within the cap ([Answer.SvgRaster]:
     *  core's `SvgView.plan`): Linux draws an SVG's zoom into one such raster. */
    @Serializable @SerialName("File.SvgRaster") data class FileSvgRaster(val width: Double, val height: Double, val ratio: Double) : Call()
    @Serializable @SerialName("File.Close") data class FileClose(val file: Long) : Call()

    /**
     * The medium file [file] plays, opened for reading in ranges ([Answer.Reader]: its id, its size, its sniffed type; ADR 0137
     * decision 2): a player reads it through [ReaderRead] from a thread of its own, never decrypted whole or to disk (ADR
     * 0047). Refused where the page shows no medium. The reader closes with [ReaderClose], with its page ([FileClose]) and
     * with its tenant.
     */
    @Serializable @SerialName("File.Reader") data class FileReader(val file: Long) : Call()

    /**
     * Saves a copy of file [file] ("Save a copy", the file's download: the file itself) into the file at [path], which the
     * host's save dialog gave (ADR 0137 decision 3: the core opens and writes it, nothing of it crosses): answered at once
     * with the save's id ([Answer.Saving]) once it runs, for [Watched.Save] and [SaveCancel]. Refused where the file has
     * nothing to save. A save outlives its page; every save stops with the tenant.
     */
    @Serializable @SerialName("File.Save") data class FileSave(val file: Long, val path: String) : Call()

    /** Stops save [save] and answers once it stopped, nothing left at its path ([Answer.Done]; refused where it had ended). */
    @Serializable @SerialName("Save.Cancel") data class SaveCancel(val save: Long) : Call()

    /**
     * The header's Replace on file [file]'s page, chosen before the host's open dialog opens (`FileModel.chooseReplace`, the
     * web's rule, ADR 0136's Consequences: the replace expects the content shown now, whatever a peer writes while the dialog is open). Refused
     * where the page offers no replace.
     */
    @Serializable @SerialName("File.ChooseReplace") data class FileChooseReplace(val file: Long) : Call()

    /**
     * The files at [paths] (each as the host's open dialog or a drop gave it) uploaded as new files under [parentId] (null:
     * the space's top) of [scopeId], one batch labelled [label] (the level's name as the host shows it) — `DepotUploads.upload`.
     * The target is judged again here (`ShellModel.takesUpload`, ADR 0154 decision 1): one the core does not offer is
     * refused `DROP_NOT_HERE` before any path is opened.
     * The core opens each path itself and reads it at offsets a frame at a time (ADR 0137 decision 3): nothing of a file
     * crosses, and a file of any size is never held whole. A path that is no file that reads is left out. Answered once
     * queued ([Answer.Queued]: how many); a name a file already holds offers its replace through [UploadsShown.said].
     */
    @Serializable @SerialName("Upload.Files") data class UploadFiles(val scopeId: String, val parentId: String?, val label: String, val paths: List<String>) : Call()

    /**
     * The directory at [path] uploaded whole under [parentId] of [scopeId] (`DepotUploads.uploadFolder`): walked by the core
     * (`PickedPaths.walk`: a link reported and never followed), its folders made while [UploadsShown.preparing] says so,
     * then its files queued as one batch under its own name. Answered once that is done ([Answer.Queued]: how many files,
     * 0 where none went); what it left out or why it went nowhere arrives as [UploadsShown.said]. Refused where [path] is
     * no directory that lists, and `DROP_NOT_HERE` — before it is walked — where the core does not offer the target
     * (`ShellModel.takesUpload`).
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

    // --- the writes made offline (ADR 0153 decisions 2, 4 and 5), the open tenant's account's

    /** *Discard* (`QueuedTray.discard`): write [write] deleted unsent with every write waiting for it. Refused `NO_SUCH_QUEUED`. */
    @Serializable @SerialName("Queued.Discard") data class QueuedDiscard(val write: String) : Call()

    /**
     * *Open* of held write [write] (`QueuedTray.opens`): where it is decided, typed ([Answer.QueuedOpens]) — a Pot entry's
     * held editor ([EditorOpenHeld], on the Pot opened by its ids), the node's rename dialog with the person's name in it
     * (a rename that lands drops the held one), or the page it was made on. Refused `NO_SUCH_QUEUED` where it is not held.
     */
    @Serializable @SerialName("Queued.Open") data class QueuedOpen(val write: String) : Call()

    /**
     * The name and type refused write [write]'s file [index] is offered under by the host's save dialog
     * (`QueuedTray.copyName`; [QueuedItem]'s `copies` says how many it carries): [Answer.QueuedCopyName]. Refused `NO_SUCH_QUEUED`.
     */
    @Serializable @SerialName("Queued.CopyName") data class QueuedCopyName(val write: String, val index: Int) : Call()

    /**
     * *Save a copy* (`QueuedTray.saveCopy`): refused write [write]'s file [index] written as plaintext into [path], the one
     * the host's save dialog gave (ADR 0137 decision 3, ADR 0047) — the only plaintext a queued write ever becomes, none
     * of it on the wire. [Answer.Done]; refused `NO_SUCH_QUEUED` (none, not refused, no such file) or `NOT_WRITTEN`
     * (nothing left at [path]).
     */
    @Serializable @SerialName("Queued.SaveCopy") data class QueuedSaveCopy(val write: String, val index: Int, val path: String) : Call()

    /**
     * What is asked before [loss] while writes wait (`QueuedTray.ask`, ADR 0153 decision 2): [Answer.QueuedAsked], its
     * question and confirm, or none (nothing waits: the host goes on without asking). *Cancel* keeps them to send first.
     */
    @Serializable @SerialName("Queued.Ask") data class QueuedAsk(val loss: me.raddatz.veyq.mobile.offline.QueuedLoss) : Call()

    /**
     * [QueuedAsk] for connection [connection]'s account, whichever tenant is open (`QueuedTray.ask` over that account's
     * outbox): Manage's sign-out and "Stop trusting" of an account that is not the open one (inbox #1043).
     * [Answer.QueuedAsked]; refused `NO_SUCH_CONNECTION`.
     */
    @Serializable @SerialName("Queued.AskFor") data class QueuedAskFor(val connection: String, val loss: me.raddatz.veyq.mobile.offline.QueuedLoss) : Call()

    /**
     * Files picked or dropped into a level, screened first (ADR 0152 decisions 1 and 6, `DepotUploads.screen`): where the
     * member may create there, the first that is a veyq download by its content becomes an import offered ([Answer.ImportOffered],
     * watched as [Watched.Import]) and every other file is queued as [UploadFiles] queues it; with none, the answer is
     * [Answer.Queued]. Paths in, never bytes (ADR 0137): the core opens each and reads its end and directory only.
     * [deletedName]: how an erased person of an imported Pot is named (the host's `pot_deleted_person`). Refused
     * `DROP_NOT_HERE` before any path is opened where the core does not offer the target (`ShellModel.takesUpload`).
     */
    @Serializable @SerialName("Import.Screen")
    data class ImportScreen(val scopeId: String, val parentId: String?, val label: String, val paths: List<String>, val deletedName: String) : Call()

    /**
     * A directory picked or dropped into a level, screened first (`DepotUploads.screenFolder`): one holding
     * `.veyq/manifest.json` at its root is a download unpacked and offered as an import ([Answer.ImportOffered]); any other is
     * uploaded as [UploadFolder] uploads it ([Answer.Queued]). Refused `DROP_NOT_HERE` as [ImportScreen] is.
     */
    @Serializable @SerialName("Import.ScreenFolder")
    data class ImportScreenFolder(val scopeId: String, val parentId: String?, val label: String, val path: String, val deletedName: String) : Call()

    /** Import [import] ([ImportShown.canImport]): everything its dry run does not leave out, under its level. */
    @Serializable @SerialName("Import.Run") data class ImportRun(val import: Long) : Call()

    /** *Upload as a file* ([ImportShown.canAsFile]): the offered file queued as an ordinary upload; the import closes. */
    @Serializable @SerialName("Import.AsFile") data class ImportAsFile(val import: Long) : Call()

    /** *Cancel*, or the dialog closed: a run stops and what it made goes to the trash; the import closes. */
    @Serializable @SerialName("Import.Cancel") data class ImportCancel(val import: Long) : Call()

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

    /**
     * A level's Select mode ([LevelTool.SELECT], inbox #1049): on (`ShellModel.selectStart`), off (*Cancel*,
     * `selectCancel`), a row turned (`selectToggle`), *All* and *None* — each [Answer.Done], the selection then shown in
     * [ShellShown.selection].
     */
    @Serializable @SerialName("Select.Start") data object SelectStart : Call()
    @Serializable @SerialName("Select.Cancel") data object SelectCancel : Call()
    @Serializable @SerialName("Select.Toggle") data class SelectToggle(val nodeId: String) : Call()
    @Serializable @SerialName("Select.All") data object SelectAll : Call()
    @Serializable @SerialName("Select.None") data object SelectNone : Call()

    /**
     * The selection's *Move to…* ([copy] false) or *Copy to…*: one move sheet over every selected row it reaches
     * (`ShellModel.selectionMoveSheet`), answered as [MoveOpen] is ([Answer.MoveOpened], then the `Move.*` calls).
     */
    @Serializable @SerialName("Select.Move") data class SelectMove(val copy: Boolean, val deletedName: String) : Call()

    /** The selection's *Report*: one report sheet (`ShellModel.selectionReportSheet`), answered as [ReportOpen] is. */
    @Serializable @SerialName("Select.Report") data object SelectReport : Call()

    /**
     * The selection's *Delete* (`ShellModel.selectionTrash`): its one question ([Answer.SelectionDelete]); [SelectDeleteRun]
     * sends it ([Answer.Trashed]: what it says, whether its Undo is offered), [SelectDeleteUndo] restores what went
     * ([Answer.Said]), [SelectDeleteClose] lets it go once the Undo is over (or the question was declined).
     */
    @Serializable @SerialName("Select.Delete") data object SelectDelete : Call()
    @Serializable @SerialName("Select.DeleteRun") data class SelectDeleteRun(val delete: Long) : Call()
    @Serializable @SerialName("Select.DeleteUndo") data class SelectDeleteUndo(val delete: Long) : Call()
    @Serializable @SerialName("Select.DeleteClose") data class SelectDeleteClose(val delete: Long) : Call()

    /**
     * A new folder where the shell is (`ShellModel.createFolderHere`), or at the shown space's top where [top] (the drawer's
     * New, `ShellModel.createFolderAtTop`): [Answer.Named], the dialog's outcome (ADR 0154 decision 1).
     */
    @Serializable @SerialName("Shell.CreateFolder") data class ShellCreateFolder(val name: String, val top: Boolean = false) : Call()

    /**
     * Files and folders dropped onto [on] (`ShellModel.drop`, ADR 0154 decision 1): the host names what it dropped on and
     * tells each item a file from a folder by its file system ([DropItem]); the core judges the target — the level shown
     * ([ShellShown.dropsHere]), a folder's row ([ShellShown.dropRows], never a row by its kind), a gallery (its rights) —,
     * sorts the items through the target's answer, uploads them, and offers a level's veyq download as an import, one per
     * drop. Paths in, never bytes (ADR 0137 decision 3). [Answer.Dropped]; [deletedName] names an imported Pot's erased
     * person (the host's `pot_deleted_person`).
     */
    @Serializable @SerialName("Shell.Drop") data class Drop(val on: DropOn, val items: List<DropItem>, val deletedName: String) : Call()

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

    /** A selection above the cap: the folder holding it reported instead (`useEnclosing`, [ReportShown.offersEnclosing]). */
    @Serializable @SerialName("Report.Enclosing") data class ReportEnclosing(val report: Long) : Call()

    /**
     * A QR scan (core's `QrScan`, the QR CR's D5) reading codes as [kind] says, answered with its id
     * ([Answer.ScanOpened]) for [Watched.Scan] and the calls below. It starts `STARTING`: the host opens its camera and
     * says what it did ([ScanCamera]). It stays until [ScanClose]; a scan is no tenant's (a sign-in scans before one opens).
     * [server] is the sign-in form's server as typed for a [ScanKind.KIT] scan (the other-server notice's), null for
     * every other kind. A [ScanKind.LINK] scan is device link [link]'s own (`DeviceLink.scan`): what it reads goes
     * straight into the link, never back; refused `NO_SUCH_LINK` for no such link, `NOT_OFFERED` for one that shows its
     * code (it scans none).
     */
    @Serializable @SerialName("Scan.Open") data class ScanOpen(val kind: ScanKind, val server: String? = null, val link: Long? = null) : Call()

    /** What the host's camera did: `RUNNING` (frames follow), `NONE` (the device has none), `DENIED` (the app may not). */
    @Serializable @SerialName("Scan.Camera") data class ScanCamera(val scan: Long, val camera: me.raddatz.veyq.mobile.qr.QrScan.Camera) : Call()

    /**
     * One camera frame: its luminance plane (the Y of YUV, or GRAY8), [width] × [height] at [stride] bytes a row, rides
     * beside the JSON as `veyq_call`'s inbound payload (ADR 0137 decision 1's other half), never in it. The core fits it to
     * the scan's bound (`QrFrames.fit`, one rule for every camera) and hands it to the scan. Answered at once
     * ([Answer.FrameTaken]): taken, or dropped while a decode runs or the scan reads none; the host sends its next frame
     * after this answer (the call is the backpressure). Refused `NOT_A_FRAME` without a payload or one shorter than the
     * plane. What a code says never comes back: [ScanShown.found] is what the scan's kind made of it.
     */
    @Serializable @SerialName("Scan.Frame") data class ScanFrame(val scan: Long, val width: Int, val height: Int, val stride: Int) : Call()

    /** Ends scan [scan] (`QrScan.close`): a decode in flight finds nothing after it. */
    @Serializable @SerialName("Scan.Close") data class ScanClose(val scan: Long) : Call()

    /**
     * A device link (ADR 0059; core's `DeviceLink`, ADR 0136's Consequences), answered with its id ([Answer.LinkOpened])
     * for [Watched.Link] and the `Link.*` calls. The trusted device's two, from Settings' "Add a device" over the
     * connection whose key is [connection] — *add a new device* shows a code, *sign in with a QR code* scans one —, need
     * a tenant open and close with it (refused `NO_TENANT_OPEN`, `NO_SUCH_CONNECTION`).
     */
    @Serializable @SerialName("Link.ShowForNewDevice") data class LinkShowForNewDevice(val connection: String) : Call()
    @Serializable @SerialName("Link.ScanSignInCode") data class LinkScanSignInCode(val connection: String) : Call()

    /**
     * The new device's two, from the sign-in form over its [server] as typed (`SessionModel.scanFromTrusted`,
     * `showForSignIn`; nothing a scan reads changes it): refused `NO_SIGN_IN` while no sign-in is shown. Once the link is
     * done the account it signed in is open — the session's watch says so, with the linked offer (`Offer.linked`).
     */
    @Serializable @SerialName("Link.ScanFromTrusted") data class LinkScanFromTrusted(val server: String) : Call()
    @Serializable @SerialName("Link.ShowForSignIn") data class LinkShowForSignIn(val server: String) : Call()

    /**
     * Link [link]'s code as a picture ([Answer.Bytes], `image/png`, beside the JSON; the bridge's `drawnCode`) at most
     * [px] pixels a side: the code's text never crosses. Refused `NO_BYTES` while the link shows none (it scans, or its
     * code is not drawn yet, or it ended).
     */
    @Serializable @SerialName("Link.Code") data class LinkCode(val link: Long, val px: Int) : Call()

    /**
     * The slide's end (`DeviceLink.confirm`, the trusted device's): the new device named [label] is granted the account.
     * Once: a second confirmation, one racing it, a blank name or one at another step is refused `NOT_TAKEN`.
     */
    @Serializable @SerialName("Link.Confirm") data class LinkConfirm(val link: Long, val label: String) : Call()

    /** The six digits the trusted device shows, typed on the new device (`DeviceLink.enterCode`): sent once; refused `NOT_TAKEN` else. */
    @Serializable @SerialName("Link.EnterCode") data class LinkEnterCode(val link: Long, val code: String) : Call()

    /**
     * Ends link [link] (`DeviceLink.close`): its polls stop, its scan closes, nothing more is sent. A new device's link
     * whose grant is already being spent finishes its sign-in all the same (the core's rule): the screen ends, not that.
     */
    @Serializable @SerialName("Link.Close") data class LinkClose(val link: Long) : Call()

    /**
     * A `veyq:` link the system handed the app (ADR 0150 § O1: a desktop's command line or activation, a protocol launch):
     * [uri] as it arrived, inward once. The core reads its host alone (`DeepLink.parse`) and answers the screen it opens
     * ([Answer.DeepLinked]); nothing of it is kept, logged or sent back. Stateless, signed in or not.
     */
    @Serializable @SerialName("DeepLink") data class DeepLink(val uri: String) : Call()

    /**
     * A received link pasted into *Open a link* (ADR 0150 decisions 4, 7): [text] as the person pasted it — the link with
     * its key, inward once and never back. Answered with its id ([Answer.ReceivedOpened]) for [Watched.Received] and the
     * `Received.*` calls, or with why the app does not open it ([Answer.ReceivedProblem]); signed in or not. Loaded at once.
     */
    @Serializable @SerialName("Received.Open") data class ReceivedOpen(val text: String) : Call()

    /** The link's password as the person typed it (`ShareGates.submitPassword`): inward, never back. */
    @Serializable @SerialName("Received.Password") data class ReceivedPassword(val received: Long, val password: String) : Call()
    @Serializable @SerialName("Received.AcceptTerms") data class ReceivedAcceptTerms(val received: Long) : Call()
    @Serializable @SerialName("Received.AcceptNote") data class ReceivedAcceptNote(val received: Long) : Call()

    /** Child [node] of the level on screen opened as its kind's guest page. */
    @Serializable @SerialName("Received.OpenNode") data class ReceivedOpenNode(val received: Long, val node: String) : Call()

    /** *Back*: a step inside the link, or at its root the question ([ReceivedShown.leaving]). */
    @Serializable @SerialName("Received.Back") data class ReceivedBack(val received: Long) : Call()
    @Serializable @SerialName("Received.Stay") data class ReceivedStay(val received: Long) : Call()

    /** The question confirmed: the link let go (its key zeroed) — the host returns where the person was. */
    @Serializable @SerialName("Received.Leave") data class ReceivedLeave(val received: Long) : Call()

    /** *Not you?* */
    @Serializable @SerialName("Received.ForgetGuest") data class ReceivedForgetGuest(val received: Long) : Call()

    /** *Keep*: the link kept as a bookmark at the top of space [scope] (signed in only), named by its host. */
    @Serializable @SerialName("Received.Keep") data class ReceivedKeep(val received: Long, val scope: String) : Call()

    /** Where *Keep* may keep the link (`GuestScreen.keepPlaces`: the personal space first), [Answer.KeepPlaces]. */
    @Serializable @SerialName("Received.KeepPlaces") data class ReceivedKeepPlaces(val received: Long) : Call()

    /**
     * Files at [paths] added by a WRITE_ONLY link's guest (a drop box's, a Memory's uploader), or by a READ_WRITE guest into
     * the folder on screen ([ReceivedPage.writes], #1034: each its own file, the link read anew as each settles). A guest
     * not named yet is asked its name by the core at the write ([ReceivedShown.naming], ADR 0154 decision 1, #1042): the
     * call answers once it was: [Answer.Done], refused `NOT_CHOSEN` where it declined (nothing is said, as a declined move:
     * `AskedUpload.declined`, #1091) and `NOT_TAKEN` where nothing was taken (a failed upload, said so).
     */
    @Serializable @SerialName("Received.Upload") data class ReceivedUpload(val received: Long, val paths: List<String>) : Call()
    /**
     * A READ_WRITE guest's *Upload folder* into the level on screen (`DepotGuest.uploadFolder`, owner 2026-11-22): the
     * directory at [path] walked by the bridge, its folders made and its files queued as the guest; [Answer.Queued] with
     * what is said.
     */
    @Serializable @SerialName("Received.UploadFolder") data class ReceivedUploadFolder(val received: Long, val path: String) : Call()

    /**
     * The file on screen replaced by the file at [path] as the link's READ_WRITE guest (#1035; the web's share page), once
     * Replace was chosen on its page ([FileChooseReplace]): queued on the link's own queue, never a tenant's, so a signed-out
     * guest replaces too; the page reads the file anew once it settled (`GuestAccess.replaceAsking`, ADR 0154 decision 1: a
     * guest not named yet is asked its name first — [ReceivedShown.naming] — and the call answers once it was).
     * [Answer.Queued]; refused `NOT_OFFERED` where no file shows, `NOT_CHOSEN` where the core did not queue it (Replace not
     * chosen or not offered by `FileRights`, a READ link, the naming declined); a path that does not read is said by name.
     */
    @Serializable @SerialName("Received.Replace") data class ReceivedReplace(val received: Long, val path: String) : Call()

    /** The link's screen closed without asking (the app quits): as [ReceivedLeave]. */
    @Serializable @SerialName("Received.Close") data class ReceivedClose(val received: Long) : Call()

    /**
     * The link reported to its own operator (`GuestScreen.reportSheet`, ADR 0150 decision 6): a report sheet for the
     * `Report.*` calls ([Answer.ReportOpened]); refused before the gates passed.
     */
    @Serializable @SerialName("Received.Report") data class ReceivedReport(val received: Long) : Call()

    /** The report the link asked for ([ReceivedShown.reportAsked]) opened by the host: not asked again. */
    @Serializable @SerialName("Received.ReportShown") data class ReceivedReportShown(val received: Long) : Call()

    /**
     * A viewing link's row [node] reported to the link's own operator (`GuestReport.nodeSheet`, #1033: whole, or by its name
     * below the root) — [Received.Report] reports the page on screen. [Answer.ReportOpened]; refused `NOT_OFFERED` on an
     * upload link or a node the link does not serve.
     */
    @Serializable @SerialName("Received.ReportNode") data class ReceivedReportNode(val received: Long, val node: String) : Call()

    /** A READ_WRITE guest's new folder [name] in the folder on screen ([ReceivedPage.writes], #1034): [Answer.Named], the dialog's outcome. */
    @Serializable @SerialName("Received.NewFolder") data class ReceivedNewFolder(val received: Long, val name: String) : Call()

    /**
     * The name dialog's save while the core asks a guest's name at a write ([ReceivedShown.naming]; `GuestNaming.name`, ADR
     * 0154 decision 1): [Answer.Named] — a [Answer.Named.problem] keeps the dialog open; once named the waiting write goes on.
     */
    @Serializable @SerialName("Received.NamingSave") data class ReceivedNamingSave(val received: Long, val name: String) : Call()

    /** The name dialog's cancel (`GuestNaming.decline`): the waiting write is not made. [Answer.Done]. */
    @Serializable @SerialName("Received.NamingDecline") data class ReceivedNamingDecline(val received: Long) : Call()

    /**
     * A guest level's row [node]'s *Move to…* ([ReceivedRow.menu], inbox #1063): the member's move sheet over the guest's
     * door (`GuestLevelMoves.sheet`), answered [Answer.MoveOpened] for [Watched.Move] and the `Move.*` calls; refused
     * `NOT_OFFERED` where the guest does not move it.
     */
    @Serializable @SerialName("Received.Move") data class ReceivedMove(val received: Long, val node: String) : Call()

    /**
     * A guest level's Select mode ([ReceivedPage.tools], inbox #1063): on, off (*Cancel*), a row turned, *All* and *None* —
     * each [Answer.Done], the selection then shown in [ReceivedPage.selection]; [ReceivedSelectMove] its *Move to…* over
     * the selected rows the guest moves ([Answer.MoveOpened], then the `Move.*` calls).
     */
    @Serializable @SerialName("Received.SelectStart") data class ReceivedSelectStart(val received: Long) : Call()
    @Serializable @SerialName("Received.SelectCancel") data class ReceivedSelectCancel(val received: Long) : Call()
    @Serializable @SerialName("Received.SelectToggle") data class ReceivedSelectToggle(val received: Long, val node: String) : Call()
    @Serializable @SerialName("Received.SelectAll") data class ReceivedSelectAll(val received: Long) : Call()
    @Serializable @SerialName("Received.SelectNone") data class ReceivedSelectNone(val received: Long) : Call()
    @Serializable @SerialName("Received.SelectMove") data class ReceivedSelectMove(val received: Long) : Call()

    /**
     * Kept link [node]'s page (core's `BookmarkPage`, ADR 0150 decision 6): its meta opened, the share shown in place while
     * the tenant has `core.share-embedding` on, else opened on its own page. Answered with its id
     * ([Answer.BookmarkOpened]) for [Watched.Bookmark] and the `Bookmark.*` calls. Loaded at once.
     */
    @Serializable @SerialName("Bookmark.Open") data class BookmarkOpen(val node: NodeAt) : Call()

    /** *Open on its own page* (the header's `open-own-page`): the link's screen above the shell ([Answer.ReceivedOpened]). */
    @Serializable @SerialName("Bookmark.OwnPage") data class BookmarkOwnPage(val bookmark: Long) : Call()

    /** *Report shared content*: the link's screen with its report asked ([Answer.ReceivedOpened]), to the share's own operator. */
    @Serializable @SerialName("Bookmark.ReportShared") data class BookmarkReportShared(val bookmark: Long) : Call()

    /** The bookmark's page left: an in-place link let go with it. */
    @Serializable @SerialName("Bookmark.Close") data class BookmarkClose(val bookmark: Long) : Call()

    /**
     * The bookmark dialog's link as typed (core's `BookmarkCreate.link`): why it is none to keep, the host its name defaults
     * to, and whether space [scopeId] is a group space's ([Answer.BookmarkChecked]). Inward only: the link never comes back.
     */
    @Serializable @SerialName("Bookmark.Check") data class BookmarkCheck(val scopeId: String, val text: String) : Call()

    /**
     * New → Bookmark saved (core's `BookmarkCreate.save`): link [text] with its [password] (empty: none), named [name]
     * (empty: its host), in [scopeId] under [parentId] (null: the space's top). [Answer.Created], or refused
     * ([Answer.Why.NOT_TAKEN]: [me.raddatz.veyq.mobile.words.Msg.BOOKMARK_CREATE_FAILED], the form kept).
     */
    @Serializable @SerialName("Bookmark.Create") data class BookmarkCreate(
        val scopeId: String, val parentId: String?, val text: String, val password: String, val name: String,
    ) : Call()

    /**
     * A Memory [name] made where New makes it (`ShellModel.createMemory`: in [scopeId] under [parentId], null the space's
     * top; `MemoryCreate` checks the name against the loaded tree before anything is sent): [Answer.Created] — the node made,
     * which the host opens ([ShellOpen]), or why not, as the web's `MemoryNodeDialog` says it.
     */
    @Serializable @SerialName("Shell.CreateMemory") data class ShellCreateMemory(
        val scopeId: String, val parentId: String?, val name: String,
        /** *I was there* ticked (ADR 0144 decision 2): the maker sealed as the Memory's participant at its birth, where [ShellOffersWasThere]. */
        val wasThere: Boolean = false,
        /** *Determine location automatically* ticked (uc.memory.place A1): a pending `auto` place sealed at its birth, where [Answer.WasThere.autoPlace]. */
        val autoPlace: Boolean = false,
    ) : Call()

    /**
     * Node [nodeId]'s cover as a list row draws it (`ShellModel.rowCover`, the phones' `NodeCover.openFramed`): [Answer.RowCover]
     * with the stored picture beside the JSON, or no bytes where the host already holds that frame ([held], the answer's
     * `frame` it last took; null: none) — a row redrawn on every state opens a cover only once it changed. Refused
     * `NO_COVER` where the node names none.
     */
    @Serializable @SerialName("Shell.RowCover") data class ShellRowCover(val nodeId: String, val held: String?) : Call()

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
     * Medium [mediaId]'s tile: the grid's tier, whole ([Answer.Bytes], beside the JSON; ADR 0137 decision 1) for the host to
     * decode and cache by the row's [GalleryRow.tile]; refused `NO_BYTES` where the row draws its icon or it did not read.
     */
    @Serializable @SerialName("Gallery.Tile") data class GalleryTile(val gallery: Long, val mediaId: String) : Call()

    /** Chapter [nodeId]'s cover picture as stored ([Answer.Bytes]), for its row; refused `NO_COVER` where it has none. */
    @Serializable @SerialName("Gallery.ChapterCover") data class GalleryChapterCover(val gallery: Long, val nodeId: String) : Call()

    /**
     * Gallery [gallery]'s rows laid out as the justified grid [width] wide (core's `GalleryModel.layout`, ADR 0136's Consequences: the
     * web's `MediaGrid.vue`; in the host's own unit, a GTK or WinUI view's logical pixels), seen from [top] down [viewHeight]:
     * [Answer.GridLayout] — the rows a view mounts there (`MediaGridLayout.Layout.window`: the view's and one view each way,
     * ADR 0105), each with its place and its tiles' frames by medium id, and the grid's whole height. Asked anew when the
     * width, the rows or the scroll change; the host places what it is given and lays out nothing itself.
     */
    @Serializable @SerialName("Gallery.Layout") data class GalleryLayout(val gallery: Long, val width: Double, val top: Double, val viewHeight: Double) : Call()

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
     * The host showed the lightbox [GalleryShown.searchOpened] asked for (its own [LightboxOpen] on that medium): the request
     * goes (`GalleryModel.searchOpenedSeen`, ADR 0146 decision 8).
     */
    @Serializable @SerialName("Gallery.SearchOpenedSeen") data class GallerySearchOpenedSeen(val gallery: Long) : Call()

    /**
     * A chapter [name] made in the gallery's Memory (`GalleryModel.createChapter`, offered where [GalleryShown.creates] holds
     * it): [Answer.Created], the chapter to open or why not.
     */
    @Serializable @SerialName("Gallery.CreateChapter") data class GalleryCreateChapter(val gallery: Long, val name: String) : Call()

    /**
     * The files at [paths] (each as the host's open dialog or a drop gave it) added to gallery [gallery]'s Memory or chapter
     * (`GalleryModel.upload` onto the app's one queue, `MediaUploads`): the core opens each path and reads it at offsets a
     * frame at a time (ADR 0137 decision 3), each one's tiers asked of the host on the queue's own thread
     * ([HostRequest.MediaTiers]). One that is no photo or video, or no file that reads, is left out. Answered once queued
     * ([Answer.Queued]: how many, and what is said of those left out); refused `NOT_OFFERED` where the rights add nothing,
     * `NOT_CHOSEN` where a link's guest declined to name itself at the upload (nothing is said, #1091).
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

    /** The current medium's mark flipped (the toolbar's heart, ADR 0149 decision 4): [Answer.Said] what did not land, or nothing. */
    @Serializable @SerialName("Lightbox.Mark") data class LightboxMark(val lightbox: Long) : Call()

    /** The current photo's or video's report sheet (ADR 0149 decision 6): [Answer.ReportOpened]; refused `NOT_OFFERED` where the server takes none. */
    @Serializable @SerialName("Lightbox.Report") data class LightboxReport(val lightbox: Long) : Call()

    /** Medium [mediaId]'s mark flipped from its tile's menu (`GalleryModel.toggleMark`): [Answer.Said]. */
    @Serializable @SerialName("Marks.Toggle") data class MarksToggle(val gallery: Long, val mediaId: String) : Call()

    /** What [GalleryShown.markSaid] said was seen (`GalleryModel.markSeen`): it clears, so a host that draws it says it once (inbox #997). */
    @Serializable @SerialName("Marks.Seen") data class MarksSeen(val gallery: Long) : Call()

    /** The filter's text (`GalleryFilter.State.text`): the grid and the lightbox narrow at once ([GalleryShown.filter]). */
    @Serializable @SerialName("Filter.Text") data class FilterText(val gallery: Long, val text: String) : Call()

    /** One of [FilterShown.suggestions] chosen by its [chip]: the chip joins, the text becomes the suggestion's rest. */
    @Serializable @SerialName("Filter.Chip") data class FilterChip(val gallery: Long, val chip: String) : Call()

    /** The chip [chip] taken back. */
    @Serializable @SerialName("Filter.Remove") data class FilterRemove(val gallery: Long, val chip: String) : Call()

    /** The marks' mode (refused `NOT_OFFERED` where [FilterShown.modeOffered] is false: a personal scope, a guest). */
    @Serializable @SerialName("Filter.Mode") data class FilterMode(val gallery: Long, val mode: MarkMode) : Call()

    /** The filter cleared: every medium shown again. */
    @Serializable @SerialName("Filter.Clear") data class FilterClear(val gallery: Long) : Call()

    /** A page's Contributions view (`Contributions`, ADR 0149 decision 6): [Answer.BucketsOpened]; refused `NOT_OFFERED` where `rights.buckets` is false. */
    @Serializable @SerialName("Buckets.Open") data class BucketsOpen(val gallery: Long) : Call()
    @Serializable @SerialName("Buckets.Pick") data class BucketsPick(val buckets: Long, val bucketId: String) : Call()
    /** A sub-bucket named [name] under the chosen primary ([BucketsShown.createsSub]): [Answer.BucketsDone]; refused under a sub-bucket. */
    @Serializable @SerialName("Buckets.Create") data class BucketsCreate(val buckets: Long, val name: String) : Call()
    @Serializable @SerialName("Buckets.Rename") data class BucketsRename(val buckets: Long, val name: String) : Call()
    @Serializable @SerialName("Buckets.Delete") data class BucketsDelete(val buckets: Long) : Call()
    /** The chosen bucket's [mediaIds] moved into bucket [target], one of [BucketsShown.moveTargets] (`REFUSED` otherwise: never across roots). */
    @Serializable @SerialName("Buckets.Move") data class BucketsMove(val buckets: Long, val mediaIds: List<String>, val target: String) : Call()

    /**
     * The lightbox over the chosen bucket's media, opened on [mediaId] (`Contributions.lightbox`, inbox #996): it pages that
     * bucket, not the gallery. Answered [Answer.LightboxOpened] — the same lightbox as [LightboxOpen]'s (one per gallery, an
     * open one closes; [Watched.Lightbox] and the `Lightbox.*` calls); refused `NO_MEDIUM` before a bucket is chosen.
     */
    @Serializable @SerialName("Buckets.Lightbox") data class BucketsLightbox(val buckets: Long, val mediaId: String) : Call()
    @Serializable @SerialName("Buckets.Seen") data class BucketsSeen(val buckets: Long) : Call()
    @Serializable @SerialName("Buckets.Close") data class BucketsClose(val buckets: Long) : Call()

    /** The account's view choice on this device (ADR 0149 decision 8): every level and a person's Memories follow ([ShellShown.view]). */
    @Serializable @SerialName("View.Set") data class ViewSet(val mode: ViewModeShown) : Call()

    /** The tiles' frames for [count] tiles at [width] (`TileGrid`, no tenant): [Answer.Tiles]. */
    @Serializable @SerialName("View.Tiles") data class ViewTiles(val width: Double, val count: Int, val labelHeight: Double = 0.0) : Call()

    /** The player's view went (`stop`): the medium's reader closes. */
    @Serializable @SerialName("Lightbox.Stop") data class LightboxStop(val lightbox: Long) : Call()

    /**
     * The current medium's original opened for the player (`Lightbox.play`; ADR 0137 decision 2): [Answer.Reader], read
     * through [ReaderRead] from the player's own thread, never decrypted whole or to disk (ADR 0047); refused `NO_MEDIUM`
     * for a picture, a blocked medium or where the rights download nothing. The core closes it when the lightbox pages away,
     * the player stops ([LightboxStop]), the lightbox, its gallery or the tenant closes: a read after that is refused.
     */
    @Serializable @SerialName("Medium.Reader") data class MediumReader(val lightbox: Long) : Call()

    /**
     * The current medium's original saved into the file at [path], which the host's save dialog gave (`Lightbox.save` under
     * [LightboxShown.downloadName]; ADR 0137 decision 3: the core opens and writes it): answered at once with the save's id
     * ([Answer.Saving]) for [Watched.Save] and [SaveCancel], as a file's Save a copy. Refused `NOTHING_TO_DOWNLOAD` for a
     * blocked medium or where the rights download nothing. A save outlives its lightbox; every save stops with the tenant.
     */
    @Serializable @SerialName("Medium.Save") data class MediumSave(val lightbox: Long, val path: String) : Call()

    // --- a node's music (ADR 0142): the account's one player, a page's Music view, the host's decks -------------------------

    /**
     * Gallery [gallery]'s header music action (`GalleryModel.toggleMusic`, ADR 0142 decision 6; its words are the header
     * plan's): its node's music started shuffled, or stopped where it is the player's queue ([GalleryShown.musicHere]).
     * Refused `NOT_OFFERED` where the page plays no music (the composition has no decks).
     */
    @Serializable @SerialName("Gallery.Music") data class GalleryMusic(val gallery: Long) : Call()

    /**
     * The lightbox's video started ([playing] true) or paused or ended (false), as the host's player reports it
     * (`Lightbox.videoPlaying`, ADR 0142 decision 13): the music is interrupted while it plays and plays again after.
     */
    @Serializable @SerialName("Lightbox.Video") data class LightboxVideo(val lightbox: Long, val playing: Boolean) : Call()

    /**
     * The account's player (`MusicPlayer`, ADR 0142 decisions 1, 3 and 5): what the bar, the lightbox's compact controls and
     * the system's controls ask of it — the system's commands come back as these calls, after [HostRequest.ControlsBind].
     * Each is answered at once (the player acts on its own lane; [Watched.Player] shows what came of it); refused
     * `NO_PLAYER` while no tenant with a player is open. The system's "play" is [PlayerResume].
     */
    @Serializable @SerialName("Player.Pause") data object PlayerPause : Call()
    @Serializable @SerialName("Player.Resume") data object PlayerResume : Call()
    @Serializable @SerialName("Player.Next") data object PlayerNext : Call()
    @Serializable @SerialName("Player.Previous") data object PlayerPrevious : Call()

    /** Within the playing track's span, in ms from its start ([NowPlayingShown.durationMs] long). */
    @Serializable @SerialName("Player.Seek") data class PlayerSeek(val positionMs: Long) : Call()

    /** The bar closed: the queue stops and the system's controls are cleared ([HostRequest.ControlsClear]). */
    @Serializable @SerialName("Player.Stop") data object PlayerStop : Call()

    /** The bar's node name: the shell opens the node whose music plays ([NowPlayingShown.route]); refused `NO_PLAYER` where none plays. */
    @Serializable @SerialName("Player.OpenNode") data object PlayerOpenNode : Call()

    /**
     * Gallery [gallery]'s Music view (`ShellModel.musicModel`, core's `MusicModel`, ADR 0142 decision 8), answered with its
     * id ([Answer.MusicOpened]) for [Watched.Music] and the calls below; it closes with [MusicClose], its gallery or the
     * tenant. Refused `NOT_OFFERED` where the page plays no music.
     */
    @Serializable @SerialName("Music.Open") data class MusicOpen(val gallery: Long) : Call()
    @Serializable @SerialName("Music.Close") data class MusicClose(val music: Long) : Call()

    /** Track [mediaId] played first, then the rest shuffled (`MusicModel.play`). */
    @Serializable @SerialName("Music.Play") data class MusicPlay(val music: Long, val mediaId: String) : Call()

    /**
     * Track [mediaId] renamed to [name] (null or blank: named by its file again; `MusicModel.rename`, the web's raw
     * read-modify-write): [Answer.Said] with why it did not land (a peer's rename won, it failed, offline), or none.
     */
    @Serializable @SerialName("Music.Rename") data class MusicRename(val music: Long, val mediaId: String, val name: String?) : Call()

    /** Track [mediaId] deleted the gallery's way (`MusicModel.remove`): [GalleryTrash]'s answers; its Undo is [GalleryUndo]. */
    @Serializable @SerialName("Music.Remove") data class MusicRemove(val music: Long, val mediaId: String) : Call()

    /**
     * Track [mediaId]'s file saved into the file at [path] the host's save dialog gave (under [MusicTrackShown.fileName];
     * `MusicModel.save`): [Answer.Saving], as [MediumSave]; refused `NOTHING_TO_DOWNLOAD` where it may not be downloaded.
     */
    @Serializable @SerialName("Music.Download") data class MusicDownload(val music: Long, val mediaId: String, val path: String) : Call()

    /**
     * Track [mediaId]'s report, an ITEM (`MusicModel.reportSheet`, ADR 0142 decision 11): [Answer.ReportOpened] for the
     * report sheet's own calls and [Watched.Report]; refused `NOT_OFFERED` where reports are not offered.
     */
    @Serializable @SerialName("Music.Report") data class MusicReport(val music: Long, val mediaId: String) : Call()

    /**
     * The audio files at [paths] (as the host's open dialog gave them) added as tracks (`MusicModel.add`, ADR 0142 decision
     * 7): the core opens each and checks it on the device — its size, its bytes, its length as the host reads it
     * ([HostRequest.MediaDuration]) — and queues the rest into the node. [Answer.MusicAdded] with what is said of those
     * refused, in the order picked; a path that is no file that reads is left out.
     */
    @Serializable @SerialName("Music.Add") data class MusicAdd(val music: Long, val paths: List<String>) : Call()

    /** What [MusicShown.said] said was seen (`MusicModel.seen`): it goes. */
    @Serializable @SerialName("Music.Seen") data class MusicSeen(val music: Long) : Call()

    /**
     * Deck [deck] played its load [load] to the file's end (`Deck.Events.ended`, ADR 0142 decision 2), reported by the host
     * on any thread. An event of a load the deck no longer holds (cleared, or loaded anew since) is dropped.
     */
    @Serializable @SerialName("Audio.Ended") data class AudioEnded(val deck: Long, val load: Long) : Call()

    /** Deck [deck]'s platform player stopped playing load [load] after it loaded (a stream error: `Deck.Events.failed`). */
    @Serializable @SerialName("Audio.Failed") data class AudioFailed(val deck: Long, val load: Long) : Call()

    // --- a Memory's date, place and maps (ADR 0143): the core's raster map, the editors, the Map view ---------------------

    /**
     * The Memories bar's Map tab (`ShellModel.memoriesMap` over the application's All list, ADR 0143 decision 6): every
     * located Memory a pin, answered with the map's id ([Answer.MapOpened]) for [Watched.Map] and the `Map.*` calls; it
     * closes with [MapClose] or the tenant. Refused `NO_MAP_SERVICES` where the tenant has no map services.
     */
    @Serializable @SerialName("Map.OpenMemories") data object MapOpenMemories : Call()

    /**
     * What map [map] shows (ADR 0143 decision 11): its centre ([x], [y]) in normalised Web Mercator space and its fractional
     * [zoom], its size in layout pixels and the host's pixels per layout pixel ([scale], carried for the host's own drawing:
     * the tiles are chosen in layout pixels, as the web's). Sent as a gesture settles and at most every 100 ms during one;
     * a centre moved by the host is the member's (a place's map then takes it as the place, `PlaceEditor.frame`). Without
     * a centre (the first, and a resize) the size alone: the map opens at the core's view for that size, or keeps its own.
     * Applied in the order the host called. The core's view comes back in [MapShown.view]; the host adopts it when its
     * [MapViewShown.moved] turns.
     */
    @Serializable @SerialName("Map.View") data class MapView(
        val map: Long, val x: Double?, val y: Double?, val zoom: Double?, val width: Double, val height: Double, val scale: Double,
    ) : Call()

    /**
     * Tile [tile] (`z/x/y`, a [TileShown.tile] of the view shown) of map [map]: its bytes as the tile host served them
     * (PNG or JPEG, decoded by the host), [Answer.Bytes] (ADR 0137 decision 1). Refused `NO_BYTES` for a tile not in view,
     * before the consent, after a withdrawal, or one the host did not answer; the host translates its last frame by the
     * drag meanwhile.
     */
    @Serializable @SerialName("Map.Tile") data class MapTile(val map: Long, val tile: String) : Call()

    /**
     * A tap on the cluster whose first pin is node [nodeId] ([ClusterShown.pin]) of the Memories map [map]
     * (`MemoriesMap.tap`): [Answer.MapTapped] — its one Memory to open, or its pins listed where they share a spot; else
     * the map frames them (its view moved). Refused `NOT_OFFERED` on a map without pins.
     */
    @Serializable @SerialName("Map.Tap") data class MapTap(val map: Long, val nodeId: String) : Call()

    @Serializable @SerialName("Map.Close") data class MapClose(val map: Long) : Call()

    /**
     * This device's consent to the tenant's map services given for the hosts served now (`MapServices.turnOn`: the consent's
     * *Turn on*, on a map and in the settings), or withdrawn (`turnOff`: *Turn map services off*), which stops every map at
     * once. Refused `NO_MAP_SERVICES` while no tenant with map services is open.
     */
    @Serializable @SerialName("Map.TurnOn") data object MapTurnOn : Call()
    @Serializable @SerialName("Map.TurnOff") data object MapTurnOff : Call()

    /** The date of every Memory and chapter this tenant opens (`ShellModel.memoryDates`): a Memories row's second line. */
    @Serializable @SerialName("Memories.Dates") data object MemoriesDates : Call()

    /**
     * Medium [mediaId]'s small map in lightbox [lightbox]'s details (`Lightbox.Details.map`, ADR 0143 decision 7): its
     * point at zoom 15 with its direction, [Answer.MapOpened]; it closes with the lightbox. Refused `NOT_OFFERED` for a
     * medium without GPS ([Answer.MediumDetails.map] false).
     */
    @Serializable @SerialName("Lightbox.Map") data class LightboxMap(val lightbox: Long, val mediaId: String) : Call()

    /**
     * Medium [mediaId]'s place name resolved once, as its details show (`Lightbox.resolvePlace`; never while the slideshow
     * runs): [Answer.PlaceResolved] with the name sealed, or none.
     */
    @Serializable @SerialName("Lightbox.Place") data class LightboxPlace(val lightbox: Long, val mediaId: String) : Call()

    /**
     * Gallery [gallery]'s date editor (`ShellModel.dateEditor`, ADR 0143 decision 5), answered [Answer.DateOpened] for
     * [Watched.Date]; refused `NOT_OFFERED` where whoever looks may not edit its meta or offline ([GalleryShown.editsFacts]).
     * It closes with [DateClose], its gallery or the tenant. Its fields are set in the order the host called.
     */
    @Serializable @SerialName("Date.Open") data class DateOpen(val gallery: Long) : Call()
    @Serializable @SerialName("Date.Mode") data class DateChoose(val date: Long, val mode: DateMode) : Call()

    /** The start day (`yyyy-MM-dd`, the host's date picker's), or empty. */
    @Serializable @SerialName("Date.From") data class DateFrom(val date: Long, val day: String) : Call()
    @Serializable @SerialName("Date.To") data class DateTo(val date: Long, val day: String) : Call()

    /** Saves what the fields hold (`DateEditor.save`): [Answer.FactsSaved]; refused `NOT_TAKEN` where nothing may be saved. */
    @Serializable @SerialName("Date.Save") data class DateSave(val date: Long) : Call()
    @Serializable @SerialName("Date.Close") data class DateClose(val date: Long) : Call()

    /**
     * Gallery [gallery]'s place editor (`ShellModel.placeEditor`, ADR 0143 decision 5), answered [Answer.PlaceOpened] with
     * its map's id (null without map services) for [Watched.Place] and [Watched.Map]; a member's pan of that map
     * ([MapView] with a centre) is the place's frame. Refused `NOT_OFFERED` as [DateOpen]. It closes with [PlaceClose] (its
     * map too), its gallery or the tenant.
     */
    @Serializable @SerialName("Place.Open") data class PlaceOpen(val gallery: Long) : Call()

    /** The member typed [name] as the place's name (`PlaceEditor.typeName`): theirs now. Applied in the order the host called. */
    @Serializable @SerialName("Place.Name") data class PlaceTyped(val place: Long, val name: String) : Call()

    /** The level picked from the picker ([LevelShown.level], `PlaceEditor.pickLevel`). */
    @Serializable @SerialName("Place.Level") data class PlaceLevel(val place: Long, val level: String) : Call()

    /** Between an own name and the map's (`PlaceEditor.toggleNameMode`). */
    @Serializable @SerialName("Place.ToggleName") data class PlaceToggleName(val place: Long) : Call()

    /** *Follow the photos* (`PlaceEditor.followAgain`): the place is the photos' medoid again, and its map moves there. */
    @Serializable @SerialName("Place.Follow") data class PlaceFollow(val place: Long) : Call()

    /** Saves the place the fields hold (`PlaceEditor.save`): [Answer.FactsSaved]; refused `NOT_TAKEN` without a name. */
    @Serializable @SerialName("Place.Save") data class PlaceSave(val place: Long) : Call()

    /** *Remove place* (`PlaceEditor.remove`): the date and the cover kept. [Answer.FactsSaved]. */
    @Serializable @SerialName("Place.Remove") data class PlaceRemove(val place: Long) : Call()
    @Serializable @SerialName("Place.Close") data class PlaceClose(val place: Long) : Call()

    /**
     * Gallery [gallery]'s Map view (`ShellModel.trackList`, core's `TrackList`, ADR 0143 decision 8): its tracks read one at
     * a time while it is open, answered [Answer.TracksOpened] with its map's id (null without map services: the list alone)
     * for [Watched.Tracks] and [Watched.Map]. It closes with [TracksClose] (its map too), its gallery or the tenant.
     */
    @Serializable @SerialName("Tracks.Open") data class TracksOpen(val gallery: Long) : Call()
    @Serializable @SerialName("Tracks.Close") data class TracksClose(val tracks: Long) : Call()

    /** Track [mediaId] shown on the map or hidden (`TrackList.setShown`; this view only). */
    @Serializable @SerialName("Tracks.Show") data class TracksShow(val tracks: Long, val mediaId: String, val shown: Boolean) : Call()

    /** Track [mediaId] selected (`TrackList.select`; null: none): drawn heavier, and its map frames it. */
    @Serializable @SerialName("Tracks.Select") data class TracksSelect(val tracks: Long, val mediaId: String?) : Call()

    /**
     * The GPX files at [paths] (as the host's open dialog gave them, filtered to `.gpx`) offered as tracks (`TrackList.add`):
     * each checked on the device; [Answer.TracksAdded] with what is said of those refused, the rest asked for their labels
     * ([TracksShown.asking]) before anything is uploaded. A path that is no file that reads is left out.
     */
    @Serializable @SerialName("Tracks.Add") data class TracksAdd(val tracks: Long, val paths: List<String>) : Call()

    /** The asked files uploaded with [labels], one per [TracksShown.asking] in its order (`TrackList.confirm`): [Answer.Queued]. */
    @Serializable @SerialName("Tracks.Confirm") data class TracksConfirm(val tracks: Long, val labels: List<TrackLabelShown>) : Call()

    /** *Cancel* on the labels (`TrackList.cancel`): nothing is uploaded. */
    @Serializable @SerialName("Tracks.Cancel") data class TracksCancel(val tracks: Long) : Call()

    /** Track [mediaId] labelled [label] (`TrackList.edit`): [Answer.Said] with why it did not land, or none. */
    @Serializable @SerialName("Tracks.Edit") data class TracksEdit(val tracks: Long, val mediaId: String, val label: TrackLabelShown) : Call()

    /** Track [mediaId] deleted the gallery's way (`TrackList.remove`): [GalleryTrash]'s answers; its Undo is [GalleryUndo]. */
    @Serializable @SerialName("Tracks.Remove") data class TracksRemove(val tracks: Long, val mediaId: String) : Call()

    /** Track [mediaId]'s GPX saved into the file at [path] the host's save dialog gave (`TrackList.save`): [Answer.Saving]. */
    @Serializable @SerialName("Tracks.Download") data class TracksDownload(val tracks: Long, val mediaId: String, val path: String) : Call()

    /** Track [mediaId]'s report, an ITEM (`TrackList.reportSheet`): [Answer.ReportOpened]; refused `NOT_OFFERED`. */
    @Serializable @SerialName("Tracks.Report") data class TracksReport(val tracks: Long, val mediaId: String) : Call()

    /** What [TracksShown.said] said was seen (`TrackList.seen`). */
    @Serializable @SerialName("Tracks.Seen") data class TracksSeen(val tracks: Long) : Call()

    // --- curating a Memory (ADR 0144 decision 8): its participants, a medium's capture time, its banner; a person's profile ----

    /**
     * Gallery [gallery]'s participants picker (`GalleryModel.participantsPicker`, ADR 0144 decision 1): the scope's current
     * members read at once, answered [Answer.ParticipantsOpened] for [Watched.Participants]; refused `NOT_OFFERED` where the
     * people line is not shown or whoever looks may not edit ([PeopleShown.edits]). It closes with [ParticipantsClose], its
     * gallery or the tenant. Its search and ticks are applied in the order the host called.
     */
    @Serializable @SerialName("Participants.Open") data class ParticipantsOpen(val gallery: Long) : Call()

    /** The members read again (`ParticipantsPicker.load`: *Try again* after [ParticipantsShown.failed]). */
    @Serializable @SerialName("Participants.Load") data class ParticipantsLoad(val participants: Long) : Call()

    /** The search field's text (`search`): it hides rows only, the pick is untouched. */
    @Serializable @SerialName("Participants.Search") data class ParticipantsSearch(val participants: Long, val query: String) : Call()

    /**
     * [userId] ticked or unticked (`toggle`): [Answer.Done], or refused `NOT_TAKEN` for an id the members' list did not
     * return, or one more past the cap ([ParticipantsShown.full]).
     */
    @Serializable @SerialName("Participants.Toggle") data class ParticipantsToggle(val participants: Long, val userId: String) : Call()

    /**
     * The pick written (`save`: merged with a peer's write meanwhile, never a conflict): [Answer.FactsSaved] — `SAVED` (the
     * host closes the picker) or `FAILED` ([ParticipantsShown.said] says why); refused `NOT_TAKEN` while nothing may be saved.
     */
    @Serializable @SerialName("Participants.Save") data class ParticipantsSave(val participants: Long) : Call()
    @Serializable @SerialName("Participants.Close") data class ParticipantsClose(val participants: Long) : Call()

    /**
     * Medium [mediaId]'s capture-time editor in lightbox [lightbox]'s details (`Lightbox.captureTimeEditor`, ADR 0144
     * decision 3): answered [Answer.CaptureTimeOpened] for [Watched.CaptureTime]; refused `NOT_OFFERED` where whoever looks
     * may not write its meta, offline, or for a medium not in the gallery. It closes with [CaptureTimeClose], its lightbox,
     * its gallery or the tenant.
     */
    @Serializable @SerialName("CaptureTime.Open") data class CaptureTimeOpen(val lightbox: Long, val mediaId: String) : Call()

    /**
     * The capture time set to [epochMs] (`CaptureTimeEditor.set`): the local date and time the host's pickers gave,
     * converted by the host's own calendar (ADR 0144 decision 3; the core holds it to the minute and does no zone
     * arithmetic). [Answer.FactsSaved]: `SAVED`, `REOPENED` on a peer's correction (the editor shows theirs now), or `FAILED`.
     */
    @Serializable @SerialName("CaptureTime.Set") data class CaptureTimeSet(val captureTime: Long, val epochMs: Long) : Call()

    /** *Reset to original* (`reset`): as [CaptureTimeSet]; refused `NOT_TAKEN` where no correction is set. */
    @Serializable @SerialName("CaptureTime.Reset") data class CaptureTimeReset(val captureTime: Long) : Call()
    @Serializable @SerialName("CaptureTime.Close") data class CaptureTimeClose(val captureTime: Long) : Call()

    /**
     * Gallery [gallery]'s banner picture (`GalleryModel.bannerPicture`, ADR 0144 decision 4): an image banner's JPEG or a
     * video banner's poster, whole ([Answer.Bytes]; the host caches it by [BannerShown.picture]); refused `NO_BYTES` where
     * there is none or it does not read — the host then draws no frame.
     */
    @Serializable @SerialName("Banner.Picture") data class BannerPicture(val gallery: Long) : Call()

    /**
     * Gallery [gallery]'s video banner opened for the player on a tap (`GalleryModel.bannerReader`; ADR 0137 decision 2):
     * [Answer.Reader], read through [ReaderRead] from the player's own thread; refused `NO_MEDIUM` for an image banner or
     * none. It closes with [ReaderClose], its gallery or the tenant.
     */
    @Serializable @SerialName("Banner.Play") data class BannerPlay(val gallery: Long) : Call()

    /**
     * The banner's frame for a column [columnWidth] wide in a viewport [viewportHeight] high, in the host's own unit
     * (`BannerFrame.of`: 16:9 above 720, 1:1 at or below, never taller than 60 % of the viewport), and the surround's size
     * for a picture of [pictureWidth] × [pictureHeight] pixels (`BannerFrame.surround`): [Answer.BannerFramed]. No tenant needed.
     */
    @Serializable @SerialName("Banner.Frame") data class BannerFrame(val columnWidth: Double, val viewportHeight: Double, val pictureWidth: Int, val pictureHeight: Int) : Call()

    /**
     * Gallery [gallery]'s banner dialog (`GalleryModel.bannerEditor` over the host's decoding, [HostRequest.MediaTiers] with
     * the cover's spec), answered [Answer.BannerOpened] for [Watched.Banner]; refused `NOT_OFFERED` where whoever looks may
     * not set the banner, or offline ([GalleryShown.editsBanner]). It closes with [BannerClose] (a save running cancelled),
     * its gallery or the tenant.
     */
    @Serializable @SerialName("Banner.Open") data class BannerOpen(val gallery: Long) : Call()

    /**
     * The file at [path], as the host's open dialog gave it (images and videos, one file), picked (`BannerEditor.pick`;
     * ADR 0137 decision 3: the core opens it and never echoes the path): refused or decoded for the previews —
     * [BannerEditorShown.said] or [BannerEditorShown.picked]. [Answer.Done] once it is either; refused `NO_SUCH_PATH` where
     * the path is no file that reads, `NOT_TAKEN` while a save runs.
     */
    @Serializable @SerialName("Banner.Pick") data class BannerPick(val banner: Long, val path: String) : Call()

    /** The picked file's decoded picture for the two previews ([Answer.Bytes], a JPEG); refused `NO_BYTES` before a pick. */
    @Serializable @SerialName("Banner.Preview") data class BannerPreview(val banner: Long) : Call()

    /**
     * *Save banner* (`BannerEditor.save`): answered when it ended, [Answer.BannerSaved] — its progress meanwhile on
     * [Watched.Banner]; [BannerCancel] stops it, the old banner as it was. Refused `NOT_TAKEN` where nothing is picked.
     */
    @Serializable @SerialName("Banner.Save") data class BannerSave(val banner: Long) : Call()

    /** A running save stopped (its upload cancelled; the old banner stays): [Answer.Done]; refused `NOT_TAKEN` where none runs. */
    @Serializable @SerialName("Banner.Cancel") data class BannerCancel(val banner: Long) : Call()

    /** *Remove banner* (`BannerEditor.remove`, one write): [Answer.BannerSaved]; refused `NOT_TAKEN` where there is none. */
    @Serializable @SerialName("Banner.Remove") data class BannerRemove(val banner: Long) : Call()
    @Serializable @SerialName("Banner.Close") data class BannerClose(val banner: Long) : Call()

    /**
     * The question a delete of the banner's own picture asked ([GalleryShown.bannerQuestion], ADR 0144 decision 5's A2)
     * answered (`GalleryModel.trashBannerPicture`): the medium trashed with its Undo, the banner kept — or, [removeBanner],
     * removed too. [GalleryTrash]'s answers. [GalleryCancelAsk] declines: nothing is deleted.
     */
    @Serializable @SerialName("Gallery.TrashBannerPicture") data class GalleryTrashBannerPicture(val gallery: Long, val removeBanner: Boolean) : Call()

    /**
     * Whether New Memory in [scopeId] offers *I was there*, ticked (`ShellModel.offersWasThere`, ADR 0144 decision 2: a
     * tenant or group scope, never a personal one): [Answer.WasThere].
     */
    @Serializable @SerialName("Shell.OffersWasThere") data class ShellOffersWasThere(val scopeId: String) : Call()

    /**
     * Person [userId]'s profile opened over what is on screen (`ShellModel.openPerson`, ADR 0144 decision 11): the shell's
     * state then names them (`Shell.person`), and the host opens its page's model with [PersonOpen]. Offered only from a
     * way in the core names ([ParticipantShown.opensProfile], [Answer.MediumDetails.profile]).
     */
    @Serializable @SerialName("Shell.OpenPerson") data class ShellOpenPerson(val userId: String) : Call()

    /**
     * Kept link [node]'s page opened over what is on screen (`ShellModel.openBookmark`, ADR 0150 decision 6, #1032): a
     * level's row of kind `SHARE_BOOKMARK` (`Home.level` lists it), a search's hit. The shell's state then names it
     * (`Shell.bookmark`, [ShellShown.headed]), and the host opens its page's model with [BookmarkOpen].
     */
    @Serializable @SerialName("Shell.OpenBookmark") data class ShellOpenBookmark(val node: NodeAt) : Call()

    /**
     * Person [userId]'s page (`ShellModel.profile`, core's frame, and each application's section — Memories'
     * `ShellModel.personMemories`, read at once), answered [Answer.PersonOpened] for [Watched.Person]. A row opens its
     * Memory with [ShellOpen] ([PersonRowShown.ref]). It stays until [PersonClose] or the tenant closes.
     */
    @Serializable @SerialName("Person.Open") data class PersonOpen(val userId: String) : Call()

    // ---- the account's flows (ADR 0148 decision 11): each a model with an id, its watch, plain calls per action ----

    /** Any device a session row names renamed (core's `AccountSettings.renameFamily`, the web's `renameDeviceOfFamily`). */
    @Serializable @SerialName("Settings.RenameFamily") data class SettingsRenameFamily(val settings: Long, val family: String, val label: String) : Call()

    /** One's own profile in the tenant open now (core's `ProfileModel`, read at once), [Answer.ProfileOpened] for [Watched.Profile]. */
    @Serializable @SerialName("Profile.Open") data object ProfileOpen : Call()
    @Serializable @SerialName("Profile.Username") data class ProfileUsername(val profile: Long, val value: String) : Call()
    @Serializable @SerialName("Profile.FirstName") data class ProfileFirstName(val profile: Long, val value: String) : Call()
    @Serializable @SerialName("Profile.LastName") data class ProfileLastName(val profile: Long, val value: String) : Call()
    @Serializable @SerialName("Profile.Consent") data class ProfileConsent(val profile: Long, val on: Boolean) : Call()

    /**
     * A picture the host's picker gave ([path]) with the square cropper's [pose] (core's `CoverFrame` with an equal width
     * and height): the core asks the host to render it to `ProfileModel.EDGE` square RGBA ([HostRequest.PictureSquare]),
     * then encodes, pads and seals it on the save.
     */
    @Serializable @SerialName("Profile.Picture") data class ProfilePicture(val profile: Long, val path: String, val pose: me.raddatz.veyq.mobile.session.CoverFrame.Pose) : Call()
    @Serializable @SerialName("Profile.RemovePicture") data class ProfileRemovePicture(val profile: Long) : Call()
    @Serializable @SerialName("Profile.DiscardPicture") data class ProfileDiscardPicture(val profile: Long) : Call()

    /** The picture to draw (the draft's, else the one held): [Answer.Bytes] with the bytes beside the JSON. */
    @Serializable @SerialName("Profile.PictureBytes") data class ProfilePictureBytes(val profile: Long) : Call()
    @Serializable @SerialName("Profile.Save") data class ProfileSave(val profile: Long) : Call()
    @Serializable @SerialName("Profile.Close") data class ProfileClose(val profile: Long) : Call()

    /** One's own safety number in the tenant open now (core's `PersonTrust.mine`): [Answer.SafetyNumber]. */
    @Serializable @SerialName("Trust.Mine") data object TrustMine : Call()

    /** One's own safety code as a picture (`QrPng` of its `veyqid:2` text): [Answer.Bytes], a PNG beside the JSON. */
    @Serializable @SerialName("Trust.MineCode") data class TrustMineCode(val px: Int) : Call()

    /** Another member's state on their page (`PersonTrust.person`): [Answer.PersonTrust]. */
    @Serializable @SerialName("Trust.Person") data class TrustPerson(val userId: String) : Call()

    /** The digits compared aloud: "They match" ([match]) or not (`PersonTrust.compareDigits`): [Answer.TrustOutcome]. */
    @Serializable @SerialName("Trust.CompareDigits") data class TrustCompareDigits(val userId: String, val match: Boolean) : Call()

    /** What a [ScanKind.SAFETY] scan found, compared (`PersonTrust.compareScan`): [Answer.TrustOutcome]. */
    @Serializable @SerialName("Trust.CompareScanned") data class TrustCompareScanned(val userId: String, val scan: Long) : Call()

    /** Change the password of the account [connection] names (core's `PasswordChange`): [Answer.FlowOpened] for [Watched.PasswordChange]. */
    @Serializable @SerialName("PasswordChange.Open") data class PasswordChangeOpen(val connection: String) : Call()

    /** The change, the secrets typed on their way to the core (each crosses once, never back). */
    @Serializable @SerialName("PasswordChange.Run") data class PasswordChangeRun(
        val flow: Long, val currentPassword: String, val newPassword: String, val confirmPassword: String, val secretKey: String,
    ) : Call()
    @Serializable @SerialName("PasswordChange.Close") data class PasswordChangeClose(val flow: Long) : Call()

    /**
     * The new password's field was left (ADR 0151 decision 7): the breached-password check runs on [newPassword], typed
     * on its way to the core (it crosses once, never back) — the check's state arrives in [Update.PasswordChange]'s
     * `breach`. Never sent while typing.
     */
    @Serializable @SerialName("PasswordChange.Check") data class PasswordChangeCheck(val flow: Long, val newPassword: String) : Call()

    /** The new password was edited — no text: the check's verdict, and any acceptance of it, is gone. */
    @Serializable @SerialName("PasswordChange.Edited") data class PasswordChangeEdited(val flow: Long) : Call()

    /** *Use it anyway* for a breached new password ([on]), or taken back. */
    @Serializable @SerialName("PasswordChange.UseAnyway") data class PasswordChangeUseAnyway(val flow: Long, val on: Boolean) : Call()

    /**
     * A kit file the host's dialog picked for flow [flow]'s Secret Key (ADR 0151 decision 3: the password change, the
     * address change or the rotation): the core reads [path] — never more than a kit file may hold — and draws its pages
     * through the host's `PdfPages`/`PdfPage`/`PictureLuma` requests, as at [SessionSignInKit]; the key is held by the
     * flow's intake, the flow's update says what it shows (`kit`). [Answer.Done], `NO_KIT` where no kit was read.
     */
    @Serializable @SerialName("Kit.Read") data class KitRead(val flow: Long, val path: String) : Call()

    /** *Use another*: flow [flow]'s held kit ends, its key zeroed. */
    @Serializable @SerialName("Kit.Clear") data class KitClear(val flow: Long) : Call()

    /** Change the address (core's `AddressChange`), its request mailed through the tenant open now: [Answer.FlowOpened]. */
    @Serializable @SerialName("AddressChange.Open") data class AddressChangeOpen(val connection: String) : Call()
    @Serializable @SerialName("AddressChange.Request") data class AddressChangeRequest(val flow: Long, val newEmail: String, val password: String, val secretKey: String) : Call()

    /** A new Emergency Kit with the account's address, once the password and Secret Key passed: [Answer.KitOpened] for [Watched.Kit]. */
    @Serializable @SerialName("AddressChange.Kit") data class AddressChangeKit(val flow: Long, val password: String, val secretKey: String) : Call()
    @Serializable @SerialName("AddressChange.Close") data class AddressChangeClose(val flow: Long) : Call()

    /**
     * The kit written by the core straight into [path], the file the host's save dialog gave (ADR 0137 decision 3) — a
     * path, never bytes —, in [language], dated [createdEpochDay] (the person's local date, days since 1970-01-01).
     */
    @Serializable @SerialName("Kit.Save") data class KitSave(val kit: Long, val path: String, val language: String, val createdEpochDay: Long) : Call()

    /** "I saved it" ([KitStep] takes it only once a file was written). */
    @Serializable @SerialName("Kit.Confirm") data class KitConfirm(val kit: Long, val checked: Boolean) : Call()
    @Serializable @SerialName("Kit.Close") data class KitClose(val kit: Long) : Call()

    /** Replace the keys (core's `KeyRotation`, its gate read at once): [Answer.FlowOpened] for [Watched.KeyRotation]. */
    @Serializable @SerialName("KeyRotation.Open") data class KeyRotationOpen(val connection: String) : Call()

    /** "Also choose a new password" on or off. */
    @Serializable @SerialName("KeyRotation.ChooseNewPassword") data class KeyRotationChooseNewPassword(val flow: Long, val on: Boolean) : Call()

    /** The gate: answered [Answer.KitOpened] with the kit step to save, or [Answer.Done] (refused, or resumed: the state says). */
    @Serializable @SerialName("KeyRotation.Prove") data class KeyRotationProve(
        val flow: Long, val password: String, val secretKey: String, val newPassword: String = "", val confirmPassword: String = "",
    ) : Call()

    /** The danger step: the publish, refused until the kit step is ready. A done rotation re-opens the tenant and outlives
     *  it (inbox #984): its watch goes on with the done step until [KeyRotationClose] or a sign-out. */
    @Serializable @SerialName("KeyRotation.Run") data class KeyRotationRun(val flow: Long) : Call()

    /** *Links to renew* read anew — after a link's share dialog ([ShareOpen] with the link's node) closed (inbox #977). */
    @Serializable @SerialName("KeyRotation.RefreshLinks") data class KeyRotationRefreshLinks(val flow: Long) : Call()
    @Serializable @SerialName("KeyRotation.Close") data class KeyRotationClose(val flow: Long) : Call()

    /** The rotation's new password's field was left: the breached-password check runs on [newPassword] (as [PasswordChangeCheck]). */
    @Serializable @SerialName("KeyRotation.Check") data class KeyRotationCheck(val flow: Long, val newPassword: String) : Call()

    /** The rotation's new password was edited — no text. */
    @Serializable @SerialName("KeyRotation.Edited") data class KeyRotationEdited(val flow: Long) : Call()

    /** *Use it anyway* for the rotation's breached new password ([on]), or taken back. */
    @Serializable @SerialName("KeyRotation.UseAnyway") data class KeyRotationUseAnyway(val flow: Long, val on: Boolean) : Call()

    /** Erase the account (core's `AccountErasure`, its preview read at once): [Answer.FlowOpened] for [Watched.Erasure]. */
    @Serializable @SerialName("Erasure.Open") data class ErasureOpen(val connection: String) : Call()

    /** A last-owner tenant's choice: [newOwner] one of its members, or null to delete it along. */
    @Serializable @SerialName("Erasure.Choose") data class ErasureChoose(val flow: Long, val tenantId: String, val newOwner: String?) : Call()

    /** The erasure with the password typed; once erased the account is signed out here, answered as [SessionSignOut] is. */
    @Serializable @SerialName("Erasure.Run") data class ErasureRun(val flow: Long, val password: String) : Call()
    @Serializable @SerialName("Erasure.Close") data class ErasureClose(val flow: Long) : Call()

    /** The Memories section's List | Map (`PersonMemories.view`; not stored), where offered ([PersonMemoriesShown.offersViews]). */
    @Serializable @SerialName("Person.View") data class PersonView(val person: Long, val view: MemoriesView) : Call()

    /** The Memories section read again (*Try again* after [PersonMemoriesShown.failed]). */
    @Serializable @SerialName("Person.Retry") data class PersonRetry(val person: Long) : Call()

    /**
     * The Memories section's map (ADR 0144 decision 13: ADR 0143's Memories map over the person's located Memories), as
     * [MapOpenMemories]: [Answer.MapOpened] for [Watched.Map] and the `Map.*` calls, a pin opened with [ShellOpen]
     * ([Answer.MapTapped]); it closes with [MapClose], its person's page or the tenant. Refused `NO_MAP_SERVICES` where the
     * tenant has none.
     */
    @Serializable @SerialName("Person.Map") data class PersonMap(val person: Long) : Call()
    @Serializable @SerialName("Person.Close") data class PersonClose(val person: Long) : Call()
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

    /**
     * A device link's own scan ([Call.ScanOpen]'s `link`): the code of its direction goes into the link (the other
     * direction's is skipped with the link's hint, anything else silently), found as its scheme (`veyqpair`,
     * `veyqsignin`) — the code's text never crosses.
     */
    LINK,

    /**
     * A safety code's scan on another member's page (ADR 0148 decision 3): only a `veyqid:` code is taken, found as
     * `veyqid`; the host then compares it with [Call.TrustCompareScanned]. A safety number is no secret.
     */
    SAFETY,

    /**
     * An Emergency Kit's code scanned for a flow's Secret Key ([Call.ScanOpen]'s `link` is the flow: the password change,
     * the address change or the rotation; ADR 0151 decision 3): a v3 `veyqkit:` token, found as `veyqkit`; the kit goes
     * to that flow's intake, its key never across the bridge.
     */
    FLOW_KIT,
}

/** A call's one answer; what the call changed arrives as the watched states' updates. */
@Serializable
internal sealed class Answer {
    @Serializable @SerialName("Done") data object Done : Answer()
    @Serializable @SerialName("Refused") data class Refused(val why: Why) : Answer()

    /**
     * The core's lifetimes ([Call.NoticeLifetimes]): a `SAID` notice closes itself after [saidMs], a `HELD` one stays until
     * closed, an `UNDO` one while its state offers the undo ([undoMs], the window the core runs); "copied" shows for
     * [copiedMs], the player's "nothing plays" for [nothingPlaysMs]. Beside them what a host draws a notice and a line by
     * (ADR 0154 decisions 4 and 5): the words a notice it raises itself lives as `SAID` or `UNDO` by ([saidWords],
     * [undoWords]; every other word is `HELD`, core's `NoticeKind.of`), and what a line's parts are joined with
     * ([separator], `Shown.SEPARATOR`).
     */
    @Serializable @SerialName("Lifetimes")
    data class Lifetimes(
        val saidMs: Long, val undoMs: Long, val copiedMs: Long, val nothingPlaysMs: Long,
        val saidWords: List<String> = emptyList(), val undoWords: List<String> = emptyList(), val separator: String = "",
    ) : Answer()

    /** The call threw: the exception's class, never its message (which may hold what it was about). */
    @Serializable @SerialName("Failed") data class Failed(val error: String) : Answer()
    @Serializable @SerialName("Groups") data class Groups(val groups: List<Home.Group>) : Answer()
    @Serializable @SerialName("PotOpened") data class PotOpened(val pot: Long) : Answer()
    @Serializable @SerialName("Name") data class Name(val name: String) : Answer()

    /**
     * Bytes the UI shows, carried beside this JSON as the callback's payload (`bytes`, `len`), never in it (ADR 0137
     * decision 1): an answer of this kind always has a payload, an empty one included. [mediaType] is the core's
     * sniffed type (`Sniff`), null where the core does not know it. (`type` is the wire's tag, so the field is named
     * otherwise.)
     */
    @Serializable @SerialName("Bytes") data class Bytes(val mediaType: String?) : Answer()

    /**
     * A blob opened for reading in ranges (ADR 0137 decision 2): [reader] is its id for [Call.ReaderRead] and
     * [Call.ReaderClose], [size] its plaintext's length. Every reader of a tenant closes when the tenant does.
     */
    @Serializable @SerialName("Reader") data class Reader(val reader: Long, val size: Long, val mediaType: String?) : Answer()

    @Serializable @SerialName("HeaderOpened") data class HeaderOpened(val header: Long) : Answer()

    /** A level row's menu ([Call.RowPlan]): [menu], then after a separator [last], each entry with its words. */
    @Serializable @SerialName("RowPlan") data class RowPlan(val menu: List<HeaderShown.PlanAction>, val last: List<HeaderShown.PlanAction>) : Answer()

    /** The sign-in's entries in their order ([Call.SignInPlan]). */
    @Serializable @SerialName("SignInPlan") data class SignInPlan(val entries: List<me.raddatz.veyq.mobile.session.SignInEntry>) : Answer()

    /** The locked sign-in's entries in their order ([Call.LockedPlan]). */
    @Serializable @SerialName("LockedPlan") data class LockedPlan(val entries: List<me.raddatz.veyq.mobile.session.LockedEntry>) : Answer()

    /** *Reset your password*'s entries in their order ([Call.RecoverPlan]). */
    @Serializable @SerialName("RecoverPlan") data class RecoverPlan(val entries: List<me.raddatz.veyq.mobile.session.RecoverEntry>) : Answer()

    /**
     * A Secret Key field's text checked ([Call.SecretKeyCheck]): null where it reads as a key or a kit's token; a
     * [me.raddatz.veyq.mobile.crypto.SecretKeyProblem.TYPO] is said under the field (`secret_key_typo`), a `MALFORMED` one
     * says nothing while typed.
     */
    @Serializable @SerialName("SecretKeyChecked") data class SecretKeyChecked(val problem: me.raddatz.veyq.mobile.crypto.SecretKeyProblem?) : Answer()

    /** The unlock's entries in their order ([Call.UnlockPlan]). */
    @Serializable @SerialName("UnlockPlan") data class UnlockPlan(val entries: List<me.raddatz.veyq.mobile.session.UnlockEntry>) : Answer()
    @Serializable @SerialName("FileOpened") data class FileOpened(val file: Long) : Answer()

    /** A copy being saved ([Call.FileSave]): its id, for [Watched.Save] and [Call.SaveCancel]. */
    @Serializable @SerialName("Saving") data class Saving(val save: Long) : Answer()

    /**
     * An import offered by [Call.ImportScreen] or [Call.ImportScreenFolder] (ADR 0152 decision 6): its id for [Watched.Import]
     * and the `Import.*` calls, with the other files queued ([queued]) and what is said of those that did not read ([notice]).
     */
    @Serializable @SerialName("ImportOffered") data class ImportOffered(val import: Long, val queued: Int, val notice: Notice? = null) : Answer()

    /**
     * An upload call's files queued ([Call.UploadFiles], [Call.UploadFolder], [Call.UploadReplace], [Call.MemoryUpload]): how
     * many, and what is said of those left out ([notice]: `MediaUploads.notTaken`; null where none was); where more than one
     * line is said (a guest's folder upload, [Call.ReceivedUploadFolder]: each link and unreadable entry, each folder not
     * made), the first is [notice] and the rest [more], each said.
     */
    @Serializable @SerialName("Queued") data class Queued(val count: Int, val notice: Notice? = null, val more: List<Notice> = emptyList()) : Answer()

    /** Where a held write's *Open* goes ([Call.QueuedOpen]): the core's typed target, never a write's content. */
    @Serializable @SerialName("QueuedOpens") data class QueuedOpens(val open: me.raddatz.veyq.mobile.offline.QueuedOpen) : Answer()

    /** What a refused write's file is saved under ([Call.QueuedCopyName]): its name and type, null where the write names none. */
    @Serializable @SerialName("QueuedCopyName") data class QueuedCopyName(val name: String?, val mediaType: String?) : Answer()

    /** The ask before a loss ([Call.QueuedAsk]); null: nothing waits, nothing to ask. */
    @Serializable @SerialName("QueuedAsked") data class QueuedAsked(val ask: me.raddatz.veyq.mobile.offline.QueuedAsk?) : Answer()
    @Serializable @SerialName("TrashOpened") data class TrashOpened(val trash: Long) : Answer()

    /** What an action says: its notice, or null where it says nothing (it went through). */
    @Serializable @SerialName("Said") data class Said(val notice: Notice?) : Answer()

    /** A trash: what it says, and whether its undo is offered ([HeaderShown.undo] says when the offer ended). */
    @Serializable @SerialName("Trashed") data class Trashed(val notice: Notice, val undoable: Boolean) : Answer()

    /** A selection's delete held as [delete]: its question's [title] and [question] (`SelectionTrash`), asked before it runs. */
    @Serializable @SerialName("SelectionDelete") data class SelectionDelete(val delete: Long, val title: Notice, val question: Notice) : Answer()

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

    /**
     * A name dialog's save (core's `NameAnswer`, ADR 0154 decision 1: a rename, New Folder, a guest's naming): why the name
     * is not taken ([problem]: the dialog stays open, the word under its field — [keepsOpen]), or what to say as it closes
     * ([notice]); [queued]: made offline, it waits to be sent and shows where it was made.
     */
    @Serializable @SerialName("Named") data class Named(val problem: Notice?, val notice: Notice?, val queued: Boolean, val keepsOpen: Boolean) : Answer()

    /**
     * What a drop came to ([Call.Drop], core's `DropOutcome`): [queued] files uploaded (or queued offline) at once, [import]
     * the import offered for one veyq download among it (for [Watched.Import]; null: none), [notices] said in order whatever
     * the outcome; [refused]: the target takes no drop here, nothing of it was taken.
     */
    @Serializable @SerialName("Dropped")
    data class Dropped(val queued: Int, val notices: List<Notice>, val import: Long? = null, val refused: Boolean = false) : Answer()

    /** The pixels a cover's crop shows ([Call.CoverCut]): from ([x], [y]), [width] × [height]. */
    @Serializable @SerialName("CoverCut") data class CoverCut(val x: Int, val y: Int, val width: Int, val height: Int) : Answer()

    /**
     * An entry's history ([Call.PotHistory]), newest first (`PotView.history`, each line `historyLine`); whether the entry
     * is deleted, whether it is offered a restore now (`PotModel.restorable`, the core's clock) and until when its
     * receipts stay (`PotView.purgeAfter`, epoch ms; null: a live entry, or no time served).
     */
    @Serializable @SerialName("EntryHistory") data class EntryHistory(
        val rows: List<HistoryShown>, val deleted: Boolean, val restorable: Boolean, val until: Long?,
    ) : Answer() {
        /** [until] as the host shows it (ADR 0154 decision 5). */
        val untilText: me.raddatz.veyq.mobile.words.ShownText? = until?.let { me.raddatz.veyq.mobile.words.ShownText.moment(it) }
    }

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

    /**
     * An SVG's page ([Call.FileSvgPage], core's `SvgView`): the [document] served at [documentUrl] and the SVG's bytes at
     * [pictureUrl], both from memory, under the page's [policy], every other request and navigation refused; [steps] are the
     * zoom's steps and [scripts] the host's own script that sets each (paired by index).
     */
    @Serializable @SerialName("SvgPage") data class SvgPage(
        val document: String, val documentUrl: String, val pictureUrl: String, val policy: String, val steps: List<Double>, val scripts: List<String>,
    ) : Answer()

    /** An SVG's raster ([Call.FileSvgRaster]): its pixel size and the pixels per laid-out unit it holds (the cap wins). */
    @Serializable @SerialName("SvgRaster") data class SvgRaster(val width: Int, val height: Int, val ratio: Double) : Answer()

    /** A Pot's settings opened ([Call.PotSettingsOpen]): their id, for [Watched.PotSettings] and their calls. */
    @Serializable @SerialName("PotSettingsOpened") data class PotSettingsOpened(val settings: Long) : Answer()

    /** "New pot" opened ([Call.PotCreateOpen]): its id, for [Watched.PotCreate] and its calls. */
    @Serializable @SerialName("PotCreateOpened") data class PotCreateOpened(val create: Long) : Answer()

    /**
     * What a settings change or a New pot action came to, read as the call returns: [stored] (the host empties its field,
     * or closes New pot), and what to say ([notice]: why it was not stored; or, stored, what still went wrong).
     */
    @Serializable @SerialName("Stored") data class Stored(val stored: Boolean, val notice: Notice?) : Answer()

    /** A device link opened (`Link.*` of a role): its id, for [Watched.Link] and the link's calls. */
    @Serializable @SerialName("LinkOpened") data class LinkOpened(val link: Long) : Answer()

    /**
     * The screen a `veyq:` link opens ([Call.DeepLink]): [DeepScreen.LINK] *Open a link* — on the sign-in or the unlock
     * screen, and signed in over the open tenant —, [DeepScreen.OPEN] the app where it is. Nothing of the link.
     */
    @Serializable @SerialName("DeepLinked") data class DeepLinked(val screen: DeepScreen) : Answer()

    /** A received link opened ([Call.ReceivedOpen]): its id, for [Watched.Received] and the `Received.*` calls. */
    @Serializable @SerialName("ReceivedOpened") data class ReceivedOpened(val received: Long) : Answer()

    /**
     * A pasted text the app does not open as a link, and why ([problem]); [browser]: the host offers *Open in the browser*
     * with the text it holds (a moderator's review) — the text itself never comes back.
     */
    @Serializable @SerialName("ReceivedProblem") data class ReceivedProblem(val problem: Msg, val browser: Boolean) : Answer()

    /** A kept link's page opened ([Call.BookmarkOpen]): its id, for [Watched.Bookmark] and the `Bookmark.*` calls. */
    @Serializable @SerialName("BookmarkOpened") data class BookmarkOpened(val bookmark: Long) : Answer()

    /** The spaces *Keep* offers ([Call.ReceivedKeepPlaces]), the personal space first, each with its words. */
    @Serializable @SerialName("KeepPlaces") data class KeepPlaces(val places: List<ShellShown.ShownSpace>) : Answer()

    /**
     * The bookmark dialog's link checked ([Call.BookmarkCheck]): why it is none to keep (null: fine, or empty), the host the
     * name defaults to once it reads, whether the place is a group space's ([Msg.BOOKMARK_WHO_CAN_OPEN]).
     */
    @Serializable @SerialName("BookmarkChecked") data class BookmarkChecked(val problem: Msg?, val host: String?, val groupSpace: Boolean) : Answer()

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

    /**
     * A gallery laid out ([Call.GalleryLayout]): the [rows] of the view's window, top to bottom, the grid's whole [height]
     * (the scroll's extent) and how many rows it has in all ([count]), in the unit the host asked in.
     */
    @Serializable @SerialName("GridLayout") data class GridLayout(val rows: List<GridRow>, val height: Double, val count: Int) : Answer()

    /**
     * A list row's cover ([Call.ShellRowCover]): its [frame] (the key the host keeps the picture by and hands back as
     * `held`), where its [crop] sits on the picture ([Call.CoverCut] cuts it), and whether the picture rides beside the JSON
     * ([sent], its sniffed [mediaType]) — false where the host holds this frame already.
     */
    @Serializable @SerialName("RowCover") data class RowCover(val frame: String, val crop: NodeCover.Crop, val sent: Boolean, val mediaType: String?) : Answer()

    /**
     * A Memory or a chapter made ([Call.ShellCreateMemory], [Call.GalleryCreateChapter]): the node to open, or why not;
     * [queued]: made offline, waiting to be sent ([notice] says so) — the dialog closes, nothing opens yet.
     */
    @Serializable @SerialName("Created") data class Created(val node: NodeAt?, val notice: Notice?, val queued: Boolean = false) : Answer()

    /**
     * A medium's details ([Call.LightboxDetails]), in the web's order: each row with its label and how it shows; who added
     * it ([uploader]; null: not known) and where that row stands among them ([uploaderAt], `MediaDetails.uploaderAt`).
     */
    @Serializable @SerialName("MediumDetails") data class MediumDetails(
        val rows: List<DetailRow>, val uploader: Piece?, val uploaderAt: Int,
        /** Whether the medium has a small map ([Call.LightboxMap]: its GPS, `Lightbox.Details.map`). */
        val map: Boolean = false,
        /**
         * The uploader's user id where their name opens their profile ([Call.ShellOpenPerson]; `MediaDetails.Uploader.profile`:
         * a current member's only); null for a former member, a guest, or none known.
         */
        val profile: String? = null,
    ) : Answer()

    /** A page's Contributions view opened ([Call.BucketsOpen]): its id, for [Watched.Buckets] and its calls. */
    @Serializable @SerialName("BucketsOpened") data class BucketsOpened(val buckets: Long) : Answer()

    /** A curation's outcome ([Call.BucketsCreate] and its siblings): done, refused or failed. */
    @Serializable @SerialName("BucketsDone") data class BucketsDone(val outcome: me.raddatz.veyq.mobile.memories.Buckets.Outcome) : Answer()

    /** The tiles' grid ([Call.ViewTiles]): its columns and each square cover's frame. */
    @Serializable @SerialName("Tiles") data class Tiles(val columns: Int, val frames: List<TileFrame>) : Answer()

    /** A page's Music view opened ([Call.MusicOpen]): its id, for [Watched.Music] and its calls. */
    @Serializable @SerialName("MusicOpened") data class MusicOpened(val music: Long) : Answer()

    /** Picked files added as tracks ([Call.MusicAdd]): what is said of each one refused, in the order picked (none: all queued). */
    @Serializable @SerialName("MusicAdded") data class MusicAdded(val said: List<Notice>) : Answer()

    /** A map opened ([Call.MapOpenMemories], [Call.LightboxMap]): its id, for [Watched.Map] and the `Map.*` calls. */
    @Serializable @SerialName("MapOpened") data class MapOpened(val map: Long) : Answer()

    /**
     * A cluster tapped ([Call.MapTap]): the Memory to open ([open]: [PinShown.page] through `Shell.Open`), or the pins
     * that share a spot ([listed]); both empty where the map framed them (its view moved).
     */
    @Serializable @SerialName("MapTapped") data class MapTapped(val open: PinShown?, val listed: List<PinShown>) : Answer()

    /** Each Memory's and chapter's date by its node ([Call.MemoriesDates]). */
    @Serializable @SerialName("MemoryDates") data class MemoryDates(val dates: List<NodeDate>) : Answer()

    /** A photo's place name ([Call.LightboxPlace]): the name sealed now, or null. */
    @Serializable @SerialName("PlaceResolved") data class PlaceResolved(val name: String?) : Answer()

    /** A date editor opened ([Call.DateOpen]). */
    @Serializable @SerialName("DateOpened") data class DateOpened(val date: Long) : Answer()

    /** A place editor opened ([Call.PlaceOpen]) and its map (null: no map services). */
    @Serializable @SerialName("PlaceOpened") data class PlaceOpened(val place: Long, val map: Long?) : Answer()

    /** A Map view opened ([Call.TracksOpen]) and its map (null: no map services). */
    @Serializable @SerialName("TracksOpened") data class TracksOpened(val tracks: Long, val map: Long?) : Answer()

    /**
     * What an editor's save came to (`EditorSave`): saved; re-opened on a peer's value written meanwhile (the editor's state
     * shows it now); or failed.
     */
    @Serializable @SerialName("FactsSaved") data class FactsSaved(val outcome: FactsSave) : Answer()

    /** Picked GPX files offered ([Call.TracksAdd]): what is said of each one refused, in the order picked. */
    @Serializable @SerialName("TracksAdded") data class TracksAdded(val said: List<Notice>) : Answer()

    /** A participants picker opened ([Call.ParticipantsOpen]): its id, for [Watched.Participants] and its calls. */
    @Serializable @SerialName("ParticipantsOpened") data class ParticipantsOpened(val participants: Long) : Answer()

    /** A capture-time editor opened ([Call.CaptureTimeOpen]): its id, for [Watched.CaptureTime] and its calls. */
    @Serializable @SerialName("CaptureTimeOpened") data class CaptureTimeOpened(val captureTime: Long) : Answer()

    /** A banner dialog opened ([Call.BannerOpen]): its id, for [Watched.Banner] and its calls. */
    @Serializable @SerialName("BannerOpened") data class BannerOpened(val banner: Long) : Answer()

    /**
     * The banner's frame ([Call.BannerFrame]): its [width] and [height] in the host's unit, whether it is the wide (16:9)
     * one, and the surround's picture size in pixels ([surroundWidth] × [surroundHeight]: the picture scaled down to it,
     * then stretched back behind the contained picture with smooth filtering — a blur by resampling, ADR 0144 decision 4).
     */
    @Serializable @SerialName("BannerFramed") data class BannerFramed(val width: Double, val height: Double, val wide: Boolean, val surroundWidth: Int, val surroundHeight: Int) : Answer()

    /** What a banner's save or removal came to ([Call.BannerSave], [Call.BannerRemove]). */
    @Serializable @SerialName("BannerSaved") data class BannerSaved(val outcome: BannerSave) : Answer()

    /**
     * What New Memory there offers ([Call.ShellOffersWasThere]): *I was there*, ticked ([offered]); *Determine location
     * automatically*, unticked ([autoPlace], uc.memory.place A1: where this device's map services are shown).
     */
    @Serializable @SerialName("WasThere") data class WasThere(val offered: Boolean, val autoPlace: Boolean = false) : Answer()

    /** A person's page opened ([Call.PersonOpen]): its id, for [Watched.Person] and its calls. */
    @Serializable @SerialName("PersonOpened") data class PersonOpened(val person: Long) : Answer()

    /** One's own profile opened ([Call.ProfileOpen]). */
    @Serializable @SerialName("ProfileOpened") data class ProfileOpened(val profile: Long) : Answer()

    /** One of the account's flows opened (password, address, rotation, erasure): its id for its watch and its calls. */
    @Serializable @SerialName("FlowOpened") data class FlowOpened(val flow: Long) : Answer()

    /** A kit step opened ([Call.AddressChangeKit], [Call.KeyRotationProve]): its id for [Watched.Kit] and `Kit.*`. */
    @Serializable @SerialName("KitOpened") data class KitOpened(val kit: Long) : Answer()

    /** One's own safety number: its six groups of digits and its two-group chain code (the code itself is `Trust.MineCode`). */
    @Serializable @SerialName("SafetyNumber") data class SafetyNumber(val number: String, val chainCode: String) : Answer()

    /** Another member's state on their page; null where they are no member here. */
    @Serializable @SerialName("PersonTrust") data class PersonTrust(val shown: me.raddatz.veyq.mobile.session.PersonTrust.Shown?, val status: me.raddatz.veyq.mobile.session.PersonTrust.Status?) : Answer()

    /** What a compare came to. */
    @Serializable @SerialName("TrustOutcome") data class TrustOutcome(val outcome: me.raddatz.veyq.mobile.session.PersonTrust.Outcome) : Answer()

    /** A search opened ([Call.SearchOpen]): its id, for [Watched.Search] and its calls. */
    @Serializable @SerialName("SearchOpened") data class SearchOpened(val search: Long) : Answer()

    enum class Why {
        NOT_A_CALL, NO_TENANT_OPEN, NO_SUCH_CONNECTION, NO_SUCH_TENANT, NO_SUCH_POT, NO_SUCH_READER, NO_PICTURE, NOTHING_TO_DOWNLOAD,
        NO_SUCH_HEADER, NO_SUCH_TRASH, NO_SUCH_ROW, NO_SUCH_MOVE, NO_SUCH_SHARE, NO_SUCH_REPORT, NO_COVER, NO_SUCH_ENTRY, NO_SUCH_EDITOR,
        NO_RECEIPT, NOT_WRITTEN, NO_SUCH_SETTINGS, NO_SUCH_CREATE, NO_SUCH_FILE, NO_BYTES, NO_SUCH_SAVE, SAVE_ENDED, NO_MEDIUM,

        /** An upload: no replace offered or chosen on that page; a path that is no file (or directory) that reads; no such said. */
        NO_REPLACE, NOT_CHOSEN, NO_SUCH_PATH, NO_SUCH_SAID,

        /** An upload call into a target the core does not offer (`ShellModel.takesUpload`, ADR 0154 decision 1): its word is `drop_not_here`. */
        DROP_NOT_HERE,

        /** A selection's action that reaches no selected row (`ShellModel.selected`), or a selection's delete the bridge does not hold. */
        NOTHING_SELECTED, NO_SUCH_DELETE,

        /** An import: none open by that id (ADR 0152). */
        NO_SUCH_IMPORT,

        /** A write made offline: none by that id in the open tenant's account's outbox, or not in the state the call needs (ADR 0153). */
        NO_SUCH_QUEUED,

        /** A receipt the Pot shown offline does not keep (ADR 0153 decision 6): its word is `offline_not_kept`. */
        NOT_KEPT_OFFLINE,

        /** A drop box's page: none by that id; no guest's folder listed by that id; a delete that was not asked. */
        NO_SUCH_DROPBOX, NO_SUCH_FOLDER, NOT_ASKED,

        /** A scan: none by that id; a frame without its plane beside the JSON, or one shorter than its size says. */
        NO_SUCH_SCAN, NOT_A_FRAME,

        /** [Call.PictureDecode]: nothing the core decodes. */
        NOT_A_PICTURE,

        /** An account's Settings: none by that id (closed, or its tenant closed). */
        NO_SUCH_ACCOUNT_PAGE,

        /** Memories: no page or lightbox by that id; what the page does not offer whoever looks (its rights, its kind). */
        NO_SUCH_GALLERY, NO_SUCH_LIGHTBOX, NOT_OFFERED,

        /** A kit file ([Call.SessionSignInKit]) in which no kit was read, or one picked while no sign-in is shown. */
        NO_KIT,

        /** A change of trust (`TrustChange`): no offer to answer; the person cancelled the prompt; not enrolled; the
         *  password was not the account's. */
        NO_OFFER, TRUST_CANCELLED, TRUST_FAILED, WRONG_PASSWORD,

        /** A device link: none by that id (closed, or its tenant closed); no sign-in shown for a new device's; a
         *  confirmation or a code the link did not take. */
        NO_SUCH_LINK, NO_SIGN_IN, NOT_TAKEN,

        /** A received link: none by that id (left or closed). */
        NO_SUCH_RECEIVED,

        /** A kept link's page: none by that id (closed). */
        NO_SUCH_BOOKMARK,

        /** The music: no player (no tenant open, or a composition without decks); no Music view by that id; no deck by that id. */
        NO_PLAYER, NO_SUCH_MUSIC, NO_SUCH_DECK,

        /** No Contributions view by that id (closed, or its gallery or tenant was). */
        NO_SUCH_BUCKETS,

        /** The maps: no map by that id (closed, or its page or tenant was); the tenant has no map services; no date or place
         *  editor, no Map view by that id. */
        NO_SUCH_MAP, NO_MAP_SERVICES, NO_SUCH_DATE, NO_SUCH_PLACE, NO_SUCH_TRACKS,

        /** Curating a Memory and a person's page: no participants picker, capture-time editor, banner dialog or person's page by that id. */
        NO_SUCH_PARTICIPANTS, NO_SUCH_CAPTURE_TIME, NO_SUCH_BANNER, NO_SUCH_PERSON,

        /** One of the account's flows (a profile, the password, the address, the rotation, the erasure, a kit step): none by that id. */
        NO_SUCH_FLOW,

        /** A search: none by that id (never opened, or its tenant closed). */
        NO_SUCH_SEARCH,
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
     * Pot [pot]'s live poll (`PotModel.live`, ADR 0136 decision 2), for as long as this watch stands: a host watches it
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

    /** A level selection's delete held by [Call.SelectDelete]: its Undo's offer, which the core ends after its window. */
    @Serializable @SerialName("SelectionDelete") data class SelectionDelete(val delete: Long) : Watched()

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

    /** The writes made offline of the open tenant's account (`QueuedTray.items`): the uploads tray's list of its own. */
    @Serializable @SerialName("QueuedTray") data object QueuedTray : Watched()

    /** An import offered by [Call.ImportScreen] or [Call.ImportScreenFolder]: its dialog ([ImportShown]). */
    @Serializable @SerialName("Import") data class Import(val import: Long) : Watched()

    /** A drop box's page opened by [Call.DropBoxOpen]. */
    @Serializable @SerialName("DropBox") data class DropBox(val box: Long) : Watched()

    /**
     * Drop box [box]'s live poll (`DropBoxPage.live`: whether the scope's tree moved, a guest's new folder among it), for as
     * long as this watch stands: a host watches it while the page shows. It delivers no update of its own — a new folder
     * arrives on [DropBox]'s — and ends with the watch or the page.
     */
    @Serializable @SerialName("DropBoxLive") data class DropBoxLive(val box: Long) : Watched()

    /** A QR scan opened by [Call.ScanOpen]. */
    @Serializable @SerialName("Scan") data class Scan(val scan: Long) : Watched()

    /** A device link opened by a `Link.*` call of a role. */
    @Serializable @SerialName("Link") data class Link(val link: Long) : Watched()

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

    /**
     * Settings' "Storage on this device" read anew at each change of the pins or their fetches (`followStorage`), while it
     * stands: a host watches it while the page shows. It delivers no update of its own — the section arrives on
     * [Settings]' — and ends with the watch or the page.
     */
    @Serializable @SerialName("SettingsStorageLive") data class SettingsStorageLive(val settings: Long) : Watched()

    /** A lightbox opened by [Call.LightboxOpen]. */
    @Serializable @SerialName("Lightbox") data class Lightbox(val lightbox: Long) : Watched()

    /** The account's player: what the bar draws ([PlayerShown]), from tenant to tenant. */
    @Serializable @SerialName("Player") data object Player : Watched()

    /** A Music view opened by [Call.MusicOpen]. */
    @Serializable @SerialName("Music") data class Music(val music: Long) : Watched()

    /** A Contributions view opened by [Call.BucketsOpen]. */
    @Serializable @SerialName("Buckets") data class Buckets(val buckets: Long) : Watched()

    /** A map opened by [Call.MapOpenMemories], [Call.LightboxMap], [Call.PlaceOpen] or [Call.TracksOpen]. */
    @Serializable @SerialName("Map") data class Map(val map: Long) : Watched()

    /** The open tenant's map services (Settings → Map services): what a map shows and whom the consent names. */
    @Serializable @SerialName("MapServices") data object MapServices : Watched()

    /** A date editor opened by [Call.DateOpen]. */
    @Serializable @SerialName("Date") data class Date(val date: Long) : Watched()

    /** A place editor opened by [Call.PlaceOpen]. */
    @Serializable @SerialName("Place") data class Place(val place: Long) : Watched()

    /** A Map view opened by [Call.TracksOpen]. */
    @Serializable @SerialName("Tracks") data class Tracks(val tracks: Long) : Watched()

    /** A participants picker opened by [Call.ParticipantsOpen]. */
    @Serializable @SerialName("Participants") data class Participants(val participants: Long) : Watched()

    /** A capture-time editor opened by [Call.CaptureTimeOpen]. */
    @Serializable @SerialName("CaptureTime") data class CaptureTime(val captureTime: Long) : Watched()

    /** A banner dialog opened by [Call.BannerOpen]. */
    @Serializable @SerialName("Banner") data class Banner(val banner: Long) : Watched()

    /** A person's page opened by [Call.PersonOpen]. */
    @Serializable @SerialName("Person") data class Person(val person: Long) : Watched()

    @Serializable @SerialName("Profile") data class Profile(val profile: Long) : Watched()
    @Serializable @SerialName("PasswordChange") data class PasswordChange(val flow: Long) : Watched()
    @Serializable @SerialName("AddressChange") data class AddressChange(val flow: Long) : Watched()
    @Serializable @SerialName("KeyRotation") data class KeyRotation(val flow: Long) : Watched()
    @Serializable @SerialName("Erasure") data class Erasure(val flow: Long) : Watched()
    @Serializable @SerialName("Kit") data class Kit(val kit: Long) : Watched()

    /** A search opened by [Call.SearchOpen]. */
    @Serializable @SerialName("Search") data class Search(val search: Long) : Watched()

    /** A received link opened by [Call.ReceivedOpen]. */
    @Serializable @SerialName("Received") data class Received(val received: Long) : Watched()

    /** A kept link's page opened by [Call.BookmarkOpen]. */
    @Serializable @SerialName("Bookmark") data class Bookmark(val bookmark: Long) : Watched()
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
     * Offline (ADR 0153 decision 6) the state's `offline` says as of when — read-only: its view's `canWrite` is off — and
     * [failure] what the page says where the Pot did not open (`PotModel.State.failure`: offline, that this device does
     * not keep it; else that it did not open); null where it opened.
     */
    @Serializable @SerialName("Pot") data class Pot(
        val pot: Long, val state: PotModel.State?, val pictures: List<String> = emptyList(),
        @Serializable(with = MsgKey::class) val failure: Msg? = state?.failure,
    ) : Update()

    /** A header's state; null once it was closed (or an id the bridge does not hold). */
    @Serializable @SerialName("Header") data class Header(val header: Long, val shown: HeaderShown?) : Update()

    /** A space's trash sheet; null once it was closed. */
    @Serializable @SerialName("Trash") data class Trash(val trash: Long, val shown: TrashShown?) : Update()

    /** A move or copy sheet; null once it was closed. */
    @Serializable @SerialName("Move") data class Move(val move: Long, val shown: MoveShown?) : Update()

    /** A selection's delete: what its Undo says while the core offers it; null once the offer ended (or it was closed). */
    @Serializable @SerialName("SelectionDelete") data class SelectionDelete(val delete: Long, val undo: Notice?) : Update()

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

    /**
     * The writes made offline, oldest first ([me.raddatz.veyq.mobile.offline.QueuedItem]: each under the name of the place
     * it writes into, its state and line, what it offers); empty where none waits or this device keeps no store; null while
     * no tenant is open. An item carries its kind's word and the name the person gave, never the write's content or a key.
     */
    @Serializable @SerialName("QueuedTray") data class QueuedTray(val items: List<me.raddatz.veyq.mobile.offline.QueuedItem>?) : Update()

    /** Import [import]'s dialog as it is now; null once it is gone. */
    @Serializable @SerialName("Import") data class Import(val import: Long, val shown: ImportShown?) : Update()

    /** A drop box's page; null once it was closed (or an id the bridge does not hold). */
    @Serializable @SerialName("DropBox") data class DropBox(val box: Long, val shown: DropBoxShown?) : Update()

    /** A QR scan; null for an id the bridge does not hold (it was closed). */
    @Serializable @SerialName("Scan") data class Scan(val scan: Long, val shown: ScanShown?) : Update()

    /** A device link; null once it was closed (or its tenant was, or an id the bridge does not hold). */
    @Serializable @SerialName("Link") data class Link(val link: Long, val shown: LinkShown?) : Update()

    /** A received link's screen ([Watched.Received]); null once it was left or closed. Never its link nor its key. */
    @Serializable @SerialName("Received") data class Received(val received: Long, val shown: ReceivedShown?) : Update()

    /** A kept link's page ([Watched.Bookmark]); null once it was closed. Never its link, its password nor its key. */
    @Serializable @SerialName("Bookmark") data class Bookmark(val bookmark: Long, val shown: BookmarkShown?) : Update()

    /** An account's Settings; null once it was closed (or an id the bridge does not hold). */
    @Serializable @SerialName("Settings") data class Settings(val settings: Long, val shown: SettingsShown?) : Update()

    /** A Memory's or a chapter's page; null once it was closed (or its tenant was). */
    @Serializable @SerialName("Gallery") data class Gallery(val gallery: Long, val shown: GalleryShown?) : Update()

    /** A lightbox; null once it was closed (or its gallery was). */
    @Serializable @SerialName("Lightbox") data class Lightbox(val lightbox: Long, val shown: LightboxShown?) : Update()

    /** The account's player; null while no tenant with a player is open. */
    @Serializable @SerialName("Player") data class Player(val shown: PlayerShown?) : Update()

    /** A Music view; null for an id the bridge does not hold (closed, or its gallery or tenant was). */
    @Serializable @SerialName("Music") data class Music(val music: Long, val shown: MusicShown?) : Update()

    /** A Contributions view; null for an id the bridge does not hold (closed, or its gallery or tenant was). */
    @Serializable @SerialName("Buckets") data class Buckets(val buckets: Long, val shown: BucketsShown?) : Update()

    /** A map; null for an id the bridge does not hold (closed, or its page or tenant was). */
    @Serializable @SerialName("Map") data class Map(val map: Long, val shown: MapShown?) : Update()

    /** The open tenant's map services; null while no tenant with map services is open. */
    @Serializable @SerialName("MapServices") data class MapServices(val shown: MapServicesShown?) : Update()

    /** A date editor; null once it closed (or its gallery or tenant did). */
    @Serializable @SerialName("Date") data class Date(val date: Long, val shown: DateShown?) : Update()

    /** A place editor; null once it closed (or its gallery or tenant did). */
    @Serializable @SerialName("Place") data class Place(val place: Long, val shown: PlaceShown?) : Update()

    /** A Map view; null once it closed (or its gallery or tenant did). */
    @Serializable @SerialName("Tracks") data class Tracks(val tracks: Long, val shown: TracksShown?) : Update()

    /** A participants picker; null once it closed (or its gallery or tenant did). */
    @Serializable @SerialName("Participants") data class Participants(val participants: Long, val shown: ParticipantsShown?) : Update()

    /** A capture-time editor; null once it closed (or its lightbox, gallery or tenant did). */
    @Serializable @SerialName("CaptureTime") data class CaptureTime(val captureTime: Long, val shown: CaptureTimeShown?) : Update()

    /** A banner dialog; null once it closed (or its gallery or tenant did). */
    @Serializable @SerialName("Banner") data class Banner(val banner: Long, val shown: BannerEditorShown?) : Update()

    /** A person's page; null once it closed (or its tenant did). */
    @Serializable @SerialName("Person") data class Person(val person: Long, val shown: PersonShown?) : Update()

    /**
     * Each of the account's flows: its core model's state, null once it closed (or its tenant did, or the account signed out).
     * A flow that asks for the Secret Key carries its intake's state ([kit]: the held kit's address and server, the
     * notices, a file in which no kit was read — never the key), one that chooses a password its breached-password check's
     * ([breach]: the verdict and the count — never the password), each null once the flow closed (ADR 0151 decisions 3, 7).
     */
    @Serializable @SerialName("Profile") data class Profile(val profile: Long, val shown: me.raddatz.veyq.mobile.session.ProfileModel.State?) : Update()
    @Serializable @SerialName("PasswordChange") data class PasswordChange(
        val flow: Long,
        val shown: me.raddatz.veyq.mobile.session.PasswordChange.State?,
        val kit: me.raddatz.veyq.mobile.account.KeyIntake.IntakeState? = null,
        val breach: me.raddatz.veyq.mobile.account.BreachCheck.BreachState? = null,
    ) : Update()
    @Serializable @SerialName("AddressChange") data class AddressChange(
        val flow: Long,
        val shown: me.raddatz.veyq.mobile.session.AddressChange.State?,
        val kit: me.raddatz.veyq.mobile.account.KeyIntake.IntakeState? = null,
    ) : Update()
    @Serializable @SerialName("KeyRotation") data class KeyRotation(
        val flow: Long,
        val shown: me.raddatz.veyq.mobile.session.rotation.KeyRotation.State?,
        val kit: me.raddatz.veyq.mobile.account.KeyIntake.IntakeState? = null,
        val breach: me.raddatz.veyq.mobile.account.BreachCheck.BreachState? = null,
    ) : Update()
    @Serializable @SerialName("Erasure") data class Erasure(val flow: Long, val shown: me.raddatz.veyq.mobile.session.AccountErasure.State?) : Update()
    @Serializable @SerialName("Kit") data class Kit(val kit: Long, val shown: me.raddatz.veyq.mobile.account.KitStep.State?) : Update()

    /**
     * A search (ADR 0146 decision 7): core's `SearchShown` as it is — names, parts, words and ids, never a key, a seal or a
     * token (`SecretFreeSchemaTest`); null once its tenant closed.
     */
    @Serializable @SerialName("Search") data class Search(val search: Long, val shown: me.raddatz.veyq.mobile.search.SearchShown?) : Update()
}

/**
 * A Memory's or a chapter's page as the host draws it (core's `GalleryModel`, ADR 0136 decision 2; one view for a member and
 * a link's guest, whose rights only remove functions), each field the model's own answer:
 * - its [node], its [rows] in the grid's order (null while the first read runs), [failed] (the last read did not: the rows
 *   before stay) with what it says ([failure], `GalleryModel.State.failure`), how many it holds ([count], `memory_items`), and a Memory's [chapters] (each cover through
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
    /** What the page says in the grid's place where [failed]: offline, that the store does not hold it; else the load failed. */
    @Serializable(with = MsgKey::class) val failure: Msg?,
    val count: Notice,
    val chapters: List<NodeAt>,
    val canUpload: Boolean,
    val canSave: Boolean,
    val creates: List<ShellShown.ShownCreate>,
    val undo: Notice?,
    val purging: String?,
    val purgeQuestion: Notice?,
    val said: Notice?,
    /** Shown from the offline store, read-only, as of when (ADR 0140 decision 5): a time, nothing else; null online. */
    val offline: me.raddatz.veyq.mobile.session.OfflineAsOf? = null,
    /**
     * Shown offline where writes made here are queued (`State.queues`, ADR 0153): uploads, marks, a trash, a capture time and
     * a chapter offered and kept to be sent; every other write refuses offline by its own flag.
     */
    val queues: Boolean = false,
    /** The filter (ADR 0149 decision 5): [rows] are the media it shows; null where it is not offered. */
    val filter: FilterShown? = null,
    /** Whether the Contributions view is offered ([Call.BucketsOpen]; `rights.buckets` and `Contributions.OFFERED`). */
    val contributions: Boolean = false,
    /** What the last mark toggle said (too many, not saved); null once seen ([Call.MarksSeen]). */
    val markSaid: Notice? = null,
    /** Whether a medium's mark may be flipped here ([Call.MarksToggle]; `GalleryModel.State.marking` with the own set read): a tile's menu offers it (#1001). */
    val marking: Boolean = false,
    /** Whether the page offers its Music view ([Call.MusicOpen]; `MusicModel.shown`: it has music, or whoever looks may add some). */
    val musicView: Boolean = false,
    /** This node's music is the player's queue now, playing or paused (the header's action then stops it). */
    val musicHere: Boolean = false,
    /** The date the header's facts line shows (`GalleryModel.State.date`: a manual one as sealed, else the `auto` range); null before it reads. */
    val date: MemoryDateShown? = null,
    /** The place's name the facts line shows (`MemoryFacts.Facts.placeName`); null: none, or pending. */
    val place: String? = null,
    /**
     * The place is armed and its name still to come from the photos (`MemoryLocation.pending`: one made with *Determine
     * location automatically*, uc.memory.place A1): the facts line says *Location pending…* where [place] is null.
     */
    val placePending: Boolean = false,
    /** Whether the facts line's date and place open their editors ([Call.DateOpen], [Call.PlaceOpen]; `GalleryModel.State.editsFacts`). */
    val editsFacts: Boolean = false,
    /** How many GPX tracks the node holds (its Map view's, [Call.TracksOpen]; the view itself is offered always). */
    val tracks: Int = 0,
    /**
     * The header's people line beside the facts line (`GalleryModel.State.people`, ADR 0144 decision 1): null where it is
     * not shown — not a member's, not a Memory, a personal scope, offline, or nobody listed and no right to add.
     */
    val people: PeopleShown? = null,
    /** The banner above the header (`State.banner`, ADR 0144 decision 4); null: none, and no frame drawn. */
    val banner: BannerShown? = null,
    /** Whether the banner's dialog is offered ([Call.BannerOpen]; `State.editsBanner`: the right, online). */
    val editsBanner: Boolean = false,
    /**
     * The medium whose delete asks about the banner first (`State.bannerPicture`, decision 5's A2: its original is the
     * banner's file): the host asks, *Remove the banner too* offered — [Call.GalleryTrashBannerPicture] or
     * [Call.GalleryCancelAsk]. Null: nothing asked.
     */
    val bannerQuestion: String? = null,
    /**
     * The photos and videos this device is sending into the node (`State.uploading`, ADR 0145 decision 3): placeholder
     * tiles before [rows], laid out by [Call.GalleryLayout] under their [UploadTileShown.id].
     */
    val uploading: List<UploadTileShown> = emptyList(),
    /**
     * A medium the search asked this page to open (`GalleryModel.State.searchOpened`, ADR 0146 decision 8): the host opens its
     * lightbox on it ([Call.LightboxOpen] at its row) and says so ([Call.GallerySearchOpenedSeen]). Null: nothing asked.
     */
    val searchOpened: String? = null,
    /**
     * The [chapters] made offline and not sent yet (`GalleryModel.State.chapterSaid`, ADR 0153 decision 5, #1040): each
     * chapter's id with the line under its name (*Waiting to send*, or why it is held). Such a chapter opens as any other.
     */
    val chaptersUnsent: List<ChapterUnsent> = emptyList(),
)

/** A chapter of a [GalleryShown] made offline and not sent yet: its [node] id and the line under its name ([said]). */
@Serializable
@SerialName("ChapterUnsent")
internal class ChapterUnsent(val node: String, val said: Notice)

/**
 * A placeholder tile of a [GalleryShown] (core's `UploadTile`): its layout id ([id], never a medium's: a [GridTile.mediaId]
 * equal to it is this tile), the kind's glyph ([video]), how far it is ([progress] 0..1; [preparing] while nothing was
 * sent yet), whether it failed ([failed]: the uploads list retries it) and what a screen reader says of it ([label]). No
 * picture: the host draws the glyph and the progress.
 */
@Serializable
@SerialName("UploadTileShown")
internal class UploadTileShown(
    val id: String, val video: Boolean, val progress: Double, val preparing: Boolean, val failed: Boolean, val label: Notice,
    /** Added offline, sealed on this device, waiting to be sent (`UploadTile.waiting`, ADR 0153 decision 5); [said] its line. */
    val waiting: Boolean = false,
    val said: Notice? = null,
)

/**
 * A Memory's people line as the header draws it (core's `Participants.Line`, ADR 0144 decision 1): everyone listed
 * ([people], in order), the avatars drawn by user id ([avatars]: all of up to three, else the first two with [more] as
 * "+N" in the third slot; each picture through [Call.AvatarPicture] where [ShellShown.pictures] holds it, else the letter),
 * whether the names read ([named]: none is a former member before), whether whoever looks may edit ([edits]:
 * [Call.ParticipantsOpen]) and the *Add participants* chip of an empty list ([add]). Ids and names only.
 */
@Serializable
@SerialName("PeopleShown")
internal class PeopleShown(val people: List<ParticipantShown>, val avatars: List<String>, val more: Int, val named: Boolean, val edits: Boolean, val add: Boolean)

/**
 * One participant: the user id, the display name (null for a former member, said with their words, or while names read),
 * whether they are a former member (greyed), and whether the name opens their profile ([Call.ShellOpenPerson]: a current
 * member only, uc.memory.participants A1).
 */
@Serializable
@SerialName("ParticipantShown")
internal class ParticipantShown(val userId: String, val name: String?, val former: Boolean, val opensProfile: Boolean)

/** A banner's medium (core's `Banner.Medium`). */
@Serializable
internal enum class BannerMedium { IMAGE, VIDEO }

/**
 * A banner as the page draws it (core's `Banner.Shown`): its [medium], the cache key of its picture ([picture]: an image's,
 * a video's poster; it changes exactly when the picture does — the bytes through [Call.BannerPicture]) and a video's
 * [mediaType] for its player ([Call.BannerPlay]). No ref, wrap or digest crosses (ADR 0144 decision 8).
 */
@Serializable
@SerialName("BannerShown")
internal class BannerShown(val medium: BannerMedium, val picture: String, val mediaType: String?)

/**
 * The participants picker as the host draws it (core's `ParticipantsPicker.State`): the scope's current members the search
 * shows ([members]; null while they read or where they did not — [failed], *Try again*), above them the ids picked that no
 * member holds any more ([former], greyed, ticked until unticked), the search's [query], whether the pick is at the cap
 * ([full]: the web's `full` words), whether the search hides every member ([noMatches]) or there is nobody to choose
 * ([nobody]), whether Save is offered and runs, what the last save said ([said]) and whether it saved ([saved]: the host
 * closes the picker).
 */
@Serializable
@SerialName("ParticipantsShown")
internal class ParticipantsShown(
    val members: List<MemberShown>?,
    val former: List<MemberShown>,
    val failed: Boolean,
    val query: String,
    val full: Boolean,
    val noMatches: Boolean,
    val nobody: Boolean,
    val canSave: Boolean,
    val saving: Boolean,
    val said: CurationSaid?,
    val saved: Boolean,
)

/** One row of the picker: the user id, the display name (null for a former member), whether it is ticked, and whether its tick may change now. */
@Serializable
@SerialName("MemberShown")
internal class MemberShown(val userId: String, val name: String?, val ticked: Boolean, val togglable: Boolean)

/** What an editor of a Memory's curation says after a save that did not land: a peer's correction won, the meta does not open here, or it failed. */
@Serializable
internal enum class CurationSaid { CONFLICT, UNREADABLE, FAILED }

/**
 * A medium's capture-time editor as the host draws it (core's `CaptureTimeEditor.State`): the effective time ([atMs], epoch
 * ms the host shows in the device's zone with its own pickers; null: none known), whether a correction is set
 * ([corrected]: *Reset to original* offered), the original time a reset returns to ([originalMs]), whether a save runs,
 * what the last one said ([said]: [CurationSaid.CONFLICT] reopens on the peer's time) and whether it saved (the host closes it).
 */
@Serializable
@SerialName("CaptureTimeShown")
internal class CaptureTimeShown(val atMs: Long?, val corrected: Boolean, val originalMs: Long?, val saving: Boolean, val said: CurationSaid?, val saved: Boolean)

/** What the banner dialog says (core's `BannerEditor.Said`): a pick refused, or a save that did not go. */
@Serializable
internal enum class BannerSaid { TOO_LARGE, UNREADABLE, VIDEO_UNREADABLE, NOT_MEDIA, STALE, BUSY, FAILED }

/** What a banner's save or removal came to: done (the dialog closes), a peer's change meanwhile, busy, failed, or cancelled. */
@Serializable
internal enum class BannerSave { DONE, STALE, BUSY, FAILED, CANCELLED }

/**
 * The banner dialog as the host draws it (core's `BannerEditor.State`): whether the Memory has a banner now, whether a
 * picked file is being decoded, the file picked ([picked], its previews' picture through [Call.BannerPreview]), whether a
 * save or removal runs and how far its upload is ([progress], 0–1; null: a removal, or not started), what it says, whether
 * it is done (the dialog closes), and whether *Save banner* and *Remove banner* are offered.
 */
@Serializable
@SerialName("BannerEditorShown")
internal class BannerEditorShown(
    val hasBanner: Boolean,
    val picking: Boolean,
    val picked: BannerPickedShown?,
    val saving: Boolean,
    val progress: Double?,
    val said: BannerSaid?,
    val done: Boolean,
    val canSave: Boolean,
    val canRemove: Boolean,
    /** The bound a too large image is refused above, as its words name it (`BannerWrite.MAX_IMAGE_NAMED`, `Shown.size`). */
    val tooLargeBound: me.raddatz.veyq.mobile.words.ShownText = me.raddatz.veyq.mobile.words.ShownText.size(me.raddatz.veyq.mobile.memories.banner.BannerWrite.MAX_IMAGE_NAMED),
)

/** A picked file as the dialog shows it: its medium, a video's type, and which pick it is ([pick]: a new number per pick, so the host asks its preview anew). */
@Serializable
@SerialName("BannerPickedShown")
internal class BannerPickedShown(val medium: BannerMedium, val mediaType: String?, val pick: Long)

/** A person's Memories section's view (core's `PersonMemories.View`). */
@Serializable
internal enum class MemoriesView { LIST, TILES, MAP }

/**
 * A person's page as the host draws it (core's `PersonProfile`, ADR 0144 decision 11): who [userId] is — the display name
 * ([name]; null: the former-member words where [former], else the unknown-member ones), their picture at its largest
 * where the core holds it ([picture]: [Call.AvatarPicture], else the letter), the `~username` line under a real name
 * ([username]) — and each application's section in the registry's order ([sections]); Memories' is [memories] (null where
 * it is not among them).
 */
@Serializable
@SerialName("PersonShown")
internal class PersonShown(
    val userId: String,
    val name: String?,
    val former: Boolean,
    val picture: Boolean,
    val username: String?,
    val sections: List<ApplicationId>,
    val memories: PersonMemoriesShown?,
)

/**
 * "Memories with {name}" (core's `PersonMemories.State`, ADR 0144 decision 13): loading, failed (*Try again*:
 * [Call.PersonRetry]), empty, the [rows] newest start day first, undated last, then by name — [partial] where some could not
 * be read —, whether List | Map is offered ([offersViews]) and which shows ([view]; the map through [Call.PersonMap]), and
 * how many of the rows have a pin ([located]).
 */
@Serializable
@SerialName("PersonMemoriesShown")
internal class PersonMemoriesShown(
    val loading: Boolean,
    val failed: Boolean,
    val empty: Boolean,
    val partial: Boolean,
    val offersViews: Boolean,
    val view: MemoriesView,
    val rows: List<PersonRowShown>,
    val located: Int,
)

/**
 * One of a person's Memories as a row (`PersonMemories.Row`): the Memory ([ref], opened with [Call.ShellOpen]; its cover
 * through [Call.ShellRowCover]), its date and place as sealed, and the group it lies in ([group], named [groupName]; null in
 * the tenant's space).
 */
@Serializable
@SerialName("PersonRowShown")
internal class PersonRowShown(val ref: ObjectRef, val date: MemoryDateShown?, val place: String?, val group: SpaceRef?, val groupName: Piece?)

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
    /** Its badge (ADR 0149 decision 4): how many marked it (null: a personal scope, no count; 0: nobody) and whether the caller did. */
    val marks: Int? = 0,
    val mine: Boolean = false,
    /** What its tile offers beside opening it (`GalleryModel.tileActions`, ADR 0154 decision 2): the mark, where allowed. */
    val tileActions: List<me.raddatz.veyq.mobile.memories.TileAction> = emptyList(),
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
    /** What the player plays while it holds a queue: the compact controls' (`Lightbox.State.music`; their commands are `Player.*`). */
    val music: NowPlayingShown? = null,
    /** Whether the heart is offered ([Call.LightboxMark]; `Lightbox.canMark`: a member's, online, the own set read). */
    val canMark: Boolean = false,
    /** Whether the caller marked the current medium. */
    val marked: Boolean = false,
    /** Whether *Report* is offered for the current photo or video ([Call.LightboxReport]); the server may still take none. */
    val canReport: Boolean = false,
)

/** The marks' mode of a gallery's filter (`GalleryFilter.Mode`). */
@Serializable
internal enum class MarkMode { ALL, EVERYONE, MINE }

/**
 * A gallery's filter as the host draws it (`GalleryFilter`, ADR 0149 decision 5): whether the field is offered at all
 * ([offered]: `rights.filterFacts`), the marks' [mode] and whether it is offered ([modeOffered]), the chips chosen and the
 * suggestions for the [text] (each by its identity, its label as the chip reads, the text that stays), and how many of [total]
 * media the grid shows now ([shown]). Stored nowhere.
 */
@Serializable
@SerialName("FilterShown")
internal class FilterShown(
    val offered: Boolean,
    val mode: MarkMode,
    val modeOffered: Boolean,
    val chips: List<ChipShown>,
    val text: String,
    val suggestions: List<ChipShown>,
    val shown: Int,
    val total: Int,
)

/** A filter chip: its identity [chip] (`GalleryFilter.key`, what [Call.FilterChip] and [Call.FilterRemove] name), its [label], the text that stays ([rest], a suggestion's). */
@Serializable
@SerialName("ChipShown")
internal class ChipShown(val chip: String, val label: me.raddatz.veyq.mobile.words.ShownText, val rest: String? = null)

/**
 * A Contributions view as the host draws it (`Contributions.State`): each bucket row at its depth with its label, its count,
 * whether it is in the caller's own root; the chosen one ([selected]) and its media's rows in the gallery's order; what the
 * server takes on it — a sub-bucket under it ([createsSub]: a primary), its rename and delete ([renames]: a sub-bucket), the
 * buckets a medium of it may move into ([moveTargets]: its root's others, inbox #991); loading, failed, and the last
 * curation refused ([refused]).
 */
@Serializable
@SerialName("BucketsShown")
internal class BucketsShown(
    val rows: List<BucketRowShown>,
    val selected: String?,
    /** The chosen bucket's media as the page's rows draw them (its name, tile, badge), whatever the page's filter shows (#1001). */
    val media: List<GalleryRow>,
    val createsSub: Boolean,
    val renames: Boolean,
    val moveTargets: List<String>,
    val loading: Boolean,
    val failed: Boolean,
    val refused: me.raddatz.veyq.mobile.memories.Buckets.Outcome?,
)

/** One bucket of the picker (`Buckets.Row`): [own] where it is in the caller's own root (their primary, "You" until it exists, and its sub-buckets). */
@Serializable
@SerialName("BucketRowShown")
internal class BucketRowShown(val bucketId: String, val label: Piece, val depth: Int, val mediaCount: Int, val isPrimary: Boolean, val own: Boolean)

/** A view of a level or a person's Memories (`ViewMode`, ADR 0149 decision 8). */
@Serializable
internal enum class ViewModeShown { LIST, TILES, MAP }

/** One tile's square cover ([Answer.Tiles], `TileGrid.Frame`). */
@Serializable
@SerialName("TileFrame")
internal class TileFrame(val x: Double, val y: Double, val size: Double)

/**
 * What plays now (core's `NowPlaying`, ADR 0142 decision 5) for the bar, the lightbox's compact controls and the system's
 * controls ([HostRequest.ControlsShow]): the track's id and [title] (an untitled one already named by its number), the
 * node's name ([album]) and its page ([route], which [Call.PlayerOpenNode] opens), whether it plays (false while paused, a
 * video's interruption included), and where it stands in its span ([positionMs] of [durationMs]; 0: not known). The queue's
 * own key stays in the core.
 */
@Serializable
@SerialName("NowPlayingShown")
internal class NowPlayingShown(
    val trackId: String,
    val title: String,
    val album: String?,
    val route: ObjectRef?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
) {
    /** [positionMs] and [durationMs] as the player's clocks show them (`Shown.length`, ADR 0154 decision 5). */
    val positionText: me.raddatz.veyq.mobile.words.ShownText = me.raddatz.veyq.mobile.words.ShownText.length(positionMs)
    val durationText: me.raddatz.veyq.mobile.words.ShownText = me.raddatz.veyq.mobile.words.ShownText.length(durationMs)

    companion object {
        fun of(now: me.raddatz.veyq.mobile.audio.NowPlaying) =
            NowPlayingShown(now.trackId, now.title, now.album, now.route, now.playing, now.positionMs, now.durationMs)
    }
}

/**
 * The account's player as the bar draws it (core's `MusicPlayer.State`): what plays ([nowPlaying]; null: the bar hides), and
 * why the last queue ended by itself ([notice]: none of its tracks played on this device, ADR 0142 decision 9), until the
 * next play.
 */
@Serializable
@SerialName("PlayerShown")
internal class PlayerShown(val nowPlaying: NowPlayingShown?, val notice: Notice?)

/**
 * A page's Music view as the host draws it (core's `MusicModel`, ADR 0142 decisions 6–11), each field the model's own
 * answer: its [node], its [tracks] as the view lists them (null while the gallery's first read runs), what it offers whoever
 * looks ([canAdd], [canRename], [canDownload], [canReport]; each track's delete [MusicTrackShown.canRemove]), [offline]
 * (read-only), whether picked files are being checked ([checking]) and what the last add or rename left to say ([said],
 * until [Call.MusicSeen]).
 */
@Serializable
@SerialName("MusicShown")
internal class MusicShown(
    val node: NodeAt,
    val tracks: List<MusicTrackShown>?,
    val canAdd: Boolean,
    val canRename: Boolean,
    val canDownload: Boolean,
    val canReport: Boolean,
    val offline: Boolean,
    val checking: Boolean,
    val said: Notice?,
)

/**
 * One track as the Music view lists it (`MusicModel.Shown`): its medium's id, its [title] — null where untitled, said as
 * [untitled] (`music_untitled` with its number) —, its own name as a member gave it ([ownName], what Rename starts from;
 * null where named by its file), its file's name ([fileName], what a download is saved as), its whole length as shown
 * ([length], `m:ss`; null where unknown), whether it is the one playing, whether it did not play on this device in this
 * session ([unplayable]), whether the operator blocked it, and whether its delete is offered ([canRemove]).
 */
@Serializable
@SerialName("MusicTrackShown")
internal class MusicTrackShown(
    val mediaId: String,
    val title: String?,
    val untitled: Notice?,
    val ownName: String?,
    val fileName: String?,
    val length: String?,
    val playing: Boolean,
    val unplayable: Boolean,
    val blocked: Boolean,
    val canRemove: Boolean,
)

/**
 * A map as the host draws it (ADR 0143 decisions 1 and 11; the core's map: `MapServices`, `MapTiles.TileView`,
 * `Viewport`): what its place shows ([state]); the view the core holds ([view]; null until the host's first
 * [Call.MapView]); the tiles that cover it, each placed in normalised Web Mercator space and asked by its id with
 * [Call.MapTile] — none unless [state] is shown —; the tile provider's [attribution], drawn beside it; and the overlays,
 * every point projected by the core: the Map view's [tracks], the photos' [dots], the Memories map's [clusters], a photo's
 * [photo], and the place editor's fixed pin at the centre ([centrePin]). The host's one formula from a normalised point
 * `p` to its pixels is `((p.x − view.x) · 256 · 2^zoom + width/2, (p.y − view.y) · 256 · 2^zoom + height/2)`; nothing else
 * is computed there. No address, key or token rides here: the tile host is named in [MapStatus.Consent] alone.
 */
@Serializable
@SerialName("MapShown")
internal class MapShown(
    val state: MapStatus,
    val view: MapViewShown?,
    val tiles: List<TileShown>,
    val attribution: String?,
    val tracks: List<TrackLineShown>,
    val dots: List<MapPointShown>,
    val clusters: List<ClusterShown>,
    val photo: PhotoShown?,
    val centrePin: Boolean,
)

/** What a map's place shows (core's `MapState`). */
@Serializable
internal sealed class MapStatus {
    /** No map is served (switched off, or not configured): no map. */
    @Serializable @SerialName("MapState.NotOffered") data object NotOffered : MapStatus()

    /** Offline: the capabilities were not read, so no map; the place says a map needs a connection. */
    @Serializable @SerialName("MapState.Offline") data object Offline : MapStatus()

    /**
     * This device's consent is asked for the tile host [tileHost] and the geocoder host [geocoderHost] (host names, as
     * the consent shows them; null where not served), [renewal] where an earlier consent named other hosts.
     */
    @Serializable @SerialName("MapState.Consent") data class Consent(val tileHost: String?, val geocoderHost: String?, val renewal: Boolean) : MapStatus()

    /** The map is shown: the consent holds for the hosts served now. */
    @Serializable @SerialName("MapState.Shown") data object Shown : MapStatus()
}

/**
 * The view the core holds: its centre in degrees ([lat], [lng]) and in normalised Mercator space ([x], [y]), its
 * fractional [zoom], its size in layout pixels; [moved] turns each time the core moved it itself (its opening, a cluster
 * framed, *Follow the photos*, a track selected), so the host adopts it then and keeps its own gesture otherwise.
 */
@Serializable
@SerialName("MapViewShown")
internal class MapViewShown(
    val lat: Double, val lng: Double, val x: Double, val y: Double, val zoom: Double, val width: Double, val height: Double, val moved: Long,
)

/** One tile in view (core's `PlacedTile`): its id `z/x/y` for [Call.MapTile], its top-left corner and edge in normalised space. */
@Serializable
@SerialName("TileShown")
internal class TileShown(val tile: String, val x: Double, val y: Double, val size: Double)

/** A point in normalised Web Mercator space (core's `MapPoint`). */
@Serializable
@SerialName("MapPointShown")
internal class MapPointShown(val x: Double, val y: Double)

/**
 * A track's line on the Map view's map (core's `TrackList.Entry`, drawn while shown and read): its medium, its [color]
 * (ARGB), its activity's [dash] (on-off lengths in pixels; null: solid), whether it is the selected one (drawn heavier),
 * and each segment's simplified points.
 */
@Serializable
@SerialName("TrackLineShown")
internal class TrackLineShown(val mediaId: String, val color: Long, val dash: List<Int>?, val selected: Boolean, val segments: List<List<MapPointShown>>)

/**
 * A cluster of the Memories map at the zoom shown (core's `MemoriesMap.clusters`): drawn at its members' centroid [at],
 * [count] Memories, its first [pin] (the one a lone pin shows, and the one [Call.MapTap] names it by).
 */
@Serializable
@SerialName("ClusterShown")
internal class ClusterShown(val at: MapPointShown, val count: Int, val pin: PinShown)

/**
 * A located Memory (core's `MemoriesMap.Pin`): its [node], its page ([page], what `Shell.Open` opens), and the node whose
 * cover it shows ([cover]: its own id, asked with `Shell.RowCover`; null where its row has none).
 */
@Serializable
@SerialName("PinShown")
internal class PinShown(val node: NodeAt, val page: ObjectRef?, val cover: String?)

/** A photo's point on its small map, with the direction it faced (whole degrees, true north; null: not known). */
@Serializable
@SerialName("PhotoShown")
internal class PhotoShown(val at: MapPointShown, val direction: Int?)

/**
 * The tenant's map services as Settings → Map services shows them: what a map's place shows now ([state]) and the hosts
 * this device's consent was given for ([consentTileHost], [consentGeocoderHost]; both null without one).
 */
@Serializable
@SerialName("MapServicesShown")
internal class MapServicesShown(val state: MapStatus, val consentTileHost: String?, val consentGeocoderHost: String?)

/** A Memory's date mode (core's `MemoryDate.Mode`). */
@Serializable
internal enum class DateMode { AUTO, SINGLE, RANGE, ONGOING }

/** A Memory's date (core's `MemoryDate`): its mode and its days (`yyyy-MM-dd`, the host says them; null: none). */
@Serializable
@SerialName("MemoryDateShown")
internal class MemoryDateShown(val mode: DateMode, val from: String?, val to: String?) {
    /**
     * Its days as the web's `memoryDateRange` writes them (ADR 0154 decision 5): the one day or the closed range; for an
     * ongoing one its first day alone, which the host words as "Since …"; empty without a day.
     */
    val text: me.raddatz.veyq.mobile.words.ShownText = if (mode == DateMode.ONGOING) me.raddatz.veyq.mobile.words.ShownText.memoryDay(from.orEmpty()) else me.raddatz.veyq.mobile.words.ShownText.dayRange(from.orEmpty(), to)
}

/** A node's date, by its id ([Answer.MemoryDates]). */
@Serializable
@SerialName("NodeDate")
internal class NodeDate(val nodeId: String, val date: MemoryDateShown)

/**
 * The date editor as the host draws it (core's `DateEditor.State`): the [mode] and the two days its pickers hold, whether
 * a range ends before it starts ([rangeInvalid], said, never saved), whether Save is offered, and the `auto` range the
 * media give now ([auto], the auto mode's preview).
 */
@Serializable
@SerialName("DateShown")
internal class DateShown(val mode: DateMode, val from: String, val to: String, val rangeInvalid: Boolean, val canSave: Boolean, val auto: MemoryDateShown) {
    /** The pickers' days as the host shows them (ADR 0154 decision 5); empty where none. */
    val fromText: me.raddatz.veyq.mobile.words.ShownText = me.raddatz.veyq.mobile.words.ShownText.day(from)
    val toText: me.raddatz.veyq.mobile.words.ShownText = me.raddatz.veyq.mobile.words.ShownText.day(to)
}

/** How a place is positioned (core's `MemoryLocation.DerivedMode`) and named (`NameSource`). */
@Serializable
internal enum class PlaceMode { AUTO, FIXED }

@Serializable
internal enum class PlaceNaming { GEOCODED, MANUAL }

/** One level of the reverse lookup (core's `GeoLevel`): its token ([level], what [Call.PlaceLevel] picks) and its name. */
@Serializable
@SerialName("LevelShown")
internal class LevelShown(val level: String, val name: String)

/**
 * The place editor as the host draws it (core's `PlaceEditor.State`): whether its map stands ([withMap]; else the name
 * field alone, the consent's offer where the map would stand), [mode] (`auto` follows the photos), the [name] and how it
 * came ([naming]), the level picker's [levels] and the one chosen, whether the picker is offered ([levelPicker]), whether
 * Save is offered, whether *Follow the photos* is ([canFollow]: the photos have a medoid) and *Remove place*
 * ([canRemove]: the Memory has a place).
 */
@Serializable
@SerialName("PlaceShown")
internal class PlaceShown(
    val withMap: Boolean,
    val mode: PlaceMode,
    val name: String,
    val naming: PlaceNaming,
    val levels: List<LevelShown>,
    val chosenLevel: String?,
    val levelPicker: Boolean,
    val canSave: Boolean,
    val canFollow: Boolean,
    val canRemove: Boolean,
)

/** What an editor's save came to ([Answer.FactsSaved]). */
@Serializable
internal enum class FactsSave { SAVED, REOPENED, FAILED }

/** What a member gives a GPX track ([Call.TracksConfirm], [Call.TracksEdit]): its own name (null: named by its file), its activity token. */
@Serializable
@SerialName("TrackLabelShown")
internal class TrackLabelShown(val name: String?, val activity: String?)

/** A track's read (core's `TrackList.Status`). */
@Serializable
internal enum class TrackStatus { LOADING, READY, FAILED }

/**
 * A Map view as the host draws it (core's `TrackList.State`, ADR 0143 decision 8): its [node], its [tracks] as the list
 * orders them (null while the gallery's first read runs), the [activities] a track may be given (tokens, each said by its
 * words), what it offers whoever looks ([canAdd], [canEdit], [canDownload], [canReport]; each track's delete
 * [TrackShown.canRemove]), [offline] (read-only, no map), whether picked files are being checked ([checking]), the
 * accepted files whose labels are asked now ([asking]: [Call.TracksConfirm] or [Call.TracksCancel]) and what the last add
 * or edit left to say ([said], until [Call.TracksSeen]).
 */
@Serializable
@SerialName("TracksShown")
internal class TracksShown(
    val node: NodeAt,
    val tracks: List<TrackShown>?,
    val activities: List<String>,
    val canAdd: Boolean,
    val canEdit: Boolean,
    val canDownload: Boolean,
    val canReport: Boolean,
    val offline: Boolean,
    val checking: Boolean,
    val asking: List<AskedTrackShown>,
    val said: Notice?,
)

/**
 * One track as the Map view lists it (`TrackList.Entry`): its medium, its [name] (null: said by its [number]), the name
 * it falls back to ([fileTrackName], the edit's hint), the member's own ([ownName]), its [activity] token and [dash], its
 * file's name (the download's), its [color] (ARGB), its read's [status], the [facts] beside it, whether it is [shown] and
 * [selected] on the map, whether the operator [blocked] it, and whether its delete is offered ([canRemove]).
 */
@Serializable
@SerialName("TrackShown")
internal class TrackShown(
    val mediaId: String,
    val name: String?,
    val number: Int,
    val fileTrackName: String?,
    val ownName: String?,
    val activity: String?,
    val dash: List<Int>?,
    val fileName: String?,
    val color: Long,
    val status: TrackStatus,
    val facts: List<TrackFactShown>,
    val shown: Boolean,
    val selected: Boolean,
    val blocked: Boolean,
    val canRemove: Boolean,
)

/**
 * One of a track's facts (core's `TrackFacts.Fact`): a distance — [distance] metres (whole) or kilometres (one decimal,
 * [kilometres]), its number written in the host's locale — or a phrase in [words].
 */
@Serializable
@SerialName("TrackFactShown")
internal class TrackFactShown(val distance: Double?, val kilometres: Boolean, val words: Notice?) {
    /** [distance] with its unit as the host shows it (`Shown.distance`, ADR 0154 decision 5). */
    val distanceText: me.raddatz.veyq.mobile.words.ShownText? = distance?.let { me.raddatz.veyq.mobile.words.ShownText.distance(it, kilometres) }
}

/** A picked file waiting for its label (`TrackList.Asked`): its name, and the name it falls back to (the field's hint). */
@Serializable
@SerialName("AskedTrackShown")
internal class AskedTrackShown(val fileName: String, val fileTrackName: String?)

/**
 * One row of a gallery's justified grid ([Answer.GridLayout], `MediaGridLayout.Row`): its [index] among all rows, its [top]
 * from the grid's origin, its [height], and its [tiles] left to right.
 */
@Serializable
@SerialName("GridRow")
internal class GridRow(val index: Int, val top: Double, val height: Double, val tiles: List<GridTile>)

/** One tile of a [GridRow]: medium [mediaId]'s frame, [left] from the row's start and [width] wide (the row's height). */
@Serializable
@SerialName("GridTile")
internal class GridTile(val mediaId: String, val left: Double, val width: Double)

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
) {
    /** [size] and [moment] as the host shows them (ADR 0154 decision 5). */
    val sizeText: me.raddatz.veyq.mobile.words.ShownText? = size?.let { me.raddatz.veyq.mobile.words.ShownText.size(it) }
    val momentText: me.raddatz.veyq.mobile.words.ShownText? = moment?.let { me.raddatz.veyq.mobile.words.ShownText.moment(it) }
}

/**
 * An account's Settings page as the host draws it (core's `AccountSettings.State`, `uc.account.sessions`), each
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
    /**
     * "Storage on this device" (ADR 0140 decision 10): sizes, the limit's field and the pinned items (each named from the
     * tree the core holds, never from the store); null where this device keeps no store of the account — then
     * [storageUnavailable] says why where it is to say (`account_settings_storage_no_trust`).
     */
    val storage: me.raddatz.veyq.mobile.session.AccountSettings.Storage? = null,
    @Serializable(with = MsgKey::class) val storageUnavailable: Msg? = null,
    /** "Remove all offline content" asked ([Call.SettingsAskRemoveAll]): its question shows. */
    val askingRemoveAll: Boolean = false,
    /** The limit field's units, each with its word. */
    val limitUnits: List<SettingsLimitUnit> = emptyList(),
    /**
     * The page's sections, in their order (`AccountSettings.State.sections`, ADR 0154 decision 2: the web's order, Sign
     * out last): the host draws each, whole, every one anchored by its `id` (a search's settings result scrolls there).
     */
    val sections: List<me.raddatz.veyq.mobile.search.SettingsSection> = emptyList(),
)

/** A unit of the storage limit's field and its word. */
@Serializable
@SerialName("SettingsLimitUnit")
internal class SettingsLimitUnit(val unit: me.raddatz.veyq.mobile.offline.LimitUnit, @Serializable(with = MsgKey::class) val word: Msg)

/**
 * One session family: created and last used (epoch ms; null where the server sent none), whether it is this device's,
 * the register's name for its device (null: the dates show), and whether "Sign out and remove saved key" is offered.
 */
@Serializable
@SerialName("SettingsFamily")
internal class SettingsFamily(val id: String, val createdAt: Long?, val lastUsedAt: Long?, val current: Boolean, val label: String?, val removesKey: Boolean) {
    /** [createdAt] and [lastUsedAt] as the host shows them (ADR 0154 decision 5). */
    val createdText: me.raddatz.veyq.mobile.words.ShownText? = createdAt?.let { me.raddatz.veyq.mobile.words.ShownText.moment(it) }
    val lastUsedText: me.raddatz.veyq.mobile.words.ShownText? = lastUsedAt?.let { me.raddatz.veyq.mobile.words.ShownText.moment(it) }
}

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
 * A device link as the host draws it (core's `DeviceLink.Shown`, field for field): never the code's text, the channel
 * key, the grant or a token. The code itself is a picture ([Call.LinkCode]) while [step] is `SHOWING`. [number] is the
 * five digits both screens compare (*add a new device*), [code] the six digits the trusted device shows for the new one
 * to type (*sign in with a QR code*) — both on screen by design, never derived from the secret —; [label] the name the
 * new device was given, once linked. Each enum is the core's under a name of its own (the generated hosts take a type's
 * last name part).
 */
@Serializable
@SerialName("LinkShown")
internal class LinkShown(
    val role: LinkRole,
    val step: LinkStep,
    val number: String?,
    val code: String?,
    val label: String?,
    val refusal: LinkRefusal?,
    val hint: LinkHint?,
)

/** `DeviceLink.Role`: trusted or new, showing or scanning. */
@Serializable
internal enum class LinkRole { SHOW_FOR_NEW_DEVICE, SCAN_SIGN_IN_CODE, SCAN_FROM_TRUSTED, SHOW_FOR_SIGN_IN }

/** `DeviceLink.Step`. */
@Serializable
internal enum class LinkStep { OPENING, SHOWING, SCANNING, CODE, WAITING, CONFIRMING, SEALING, FINISHING, DONE, ENDED }

/** `DeviceLink.Refusal`: why a link ended. */
@Serializable
internal enum class LinkRefusal { OFF, GONE, REFUSED, FAILED }

/** `DeviceLink.Hint`: what the words add. */
@Serializable
internal enum class LinkHint { OTHER_DIRECTION, SAME_SERVER }

/** Where an import's dialog is (core's `ImportDialog.Step`). */
@Serializable
internal enum class ImportStep { READING, READY, RUNNING, INVALID, DONE }

/**
 * An import's dialog as the host draws it (core's `ImportDialog`, the web's `ImportSubtreeDialog.vue`; ADR 0152 decisions 5
 * and 6): its [step]; the dry run's [summary] (items, size, where) with what it leaves out ([leftOut], each the web's line);
 * whether the tenant has room ([fits]: null where the server did not say); a run's progress in nodes ([done] of [total],
 * [progress]: the web's line); what it says ([said]: a refusal, a failure, or once [ImportStep.DONE] the imported line the
 * host shows as its toast); the buttons it offers ([canImport] with its word [importWord] — *Import* or *Import the rest* —,
 * [canAsFile]); and [closed], after which the host takes the dialog down. Nothing of the download crosses: no path, no
 * byte, no key — only counts, names the manifest gives and words.
 */
@Serializable
@SerialName("ImportShown")
internal class ImportShown(
    val step: ImportStep,
    val root: String,
    val summary: Notice?,
    val leftOut: List<Notice>,
    val fits: Boolean?,
    val done: Int,
    val total: Int,
    val progress: Notice?,
    val said: List<Notice>,
    val canImport: Boolean,
    @Serializable(with = MsgKey::class) val importWord: Msg,
    val canAsFile: Boolean,
    val closed: Boolean,
)

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
    /** The base currency and the categories show (`PotView.metaShown`): a member's; a link's guest sees its people alone. */
    val metaShown: Boolean = true,
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
    /** A held change's editor ([Call.EditorOpenHeld]): who saved the newer version and when (`State.newer`); null otherwise. */
    val newer: Notice? = null,
    /** *Discard mine* is offered ([Call.EditorDiscardMine]). */
    val discardsMine: Boolean = false,
) {
    /** [date] as the host shows it (ADR 0154 decision 5). */
    val dateText: me.raddatz.veyq.mobile.words.ShownText = me.raddatz.veyq.mobile.words.ShownText.day(date)
}

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
    /** Whether the sheet offers "only its name" beside the whole ([whole]'s switch): a node's does; a track's, an ITEM, does not (`ReportSheet.offersName`, ADR 0142 decision 11). */
    val offersName: Boolean = true,
    /** "You are reporting n items." (`ReportSheet.State.items`). */
    val items: Notice? = null,
    /** Why nothing can be sent with this many (a selection above the cap), or null. */
    val tooMany: Notice? = null,
    /** Whether the folder holding them is offered instead ([Call.ReportEnclosing]), with its words. */
    val offersEnclosing: Boolean = false,
    @Serializable(with = MsgKey::class) val enclosingWord: Msg = Msg.REPORT_ENCLOSING,
) {
    /** [receivedAt] as the host shows it (ADR 0154 decision 5). */
    val receivedText: me.raddatz.veyq.mobile.words.ShownText? = receivedAt?.let { me.raddatz.veyq.mobile.words.ShownText.moment(it) }

    /** A category: the contract's name of it (its `category` on [Call.ReportCategory]) and its words. */
    @Serializable class ShownCategory(val id: String, @Serializable(with = MsgKey::class) val word: Msg)
}

/**
 * One revision of an entry's history as the host draws it: its line (`PotView.historyLine`), who wrote it (null: no one
 * known — the host's "Someone") and when the server stored it (epoch ms).
 */
@Serializable
internal class HistoryShown(val line: List<Piece>, val writer: String?, val at: Long) {
    /** [at] as the host shows it (ADR 0154 decision 5). */
    val atText: me.raddatz.veyq.mobile.words.ShownText = me.raddatz.veyq.mobile.words.ShownText.moment(at)
}

/**
 * A file's page as the host draws it (core's `FileModel`, ADR 0136's Consequences), each field the model's own answer:
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
    val end: me.raddatz.veyq.mobile.blob.FileSave.End?,
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
    /** "Available offline" (`State.offline`): its toggle's state; null where it is not offered. [Call.HeaderOffline]. */
    val offline: me.raddatz.veyq.mobile.offline.OfflineToggle? = null,
) {
    /** `NodeHeader.Plan`: the bar's buttons, the menu, and the menu's last group after a separator. */
    @Serializable class Plan(val buttons: List<PlanAction>, val menu: List<PlanAction>, val last: List<PlanAction>)

    /**
     * One action (ADR 0154 decision 2): its entry, a [me.raddatz.veyq.mobile.navigation.HeaderAction] the host draws
     * exhaustively (the generated Rust and C# enum: an entry added fails the host's build until drawn), and its words —
     * core's, or the kind's page's own (`GalleryModel.label`, `FileModel.Header.label`, `BookmarkPage.label`). Not named
     * `Action`: the generated C# would clash with `System.Action`.
     */
    @Serializable class PlanAction(val id: me.raddatz.veyq.mobile.navigation.HeaderAction, @Serializable(with = MsgKey::class) val word: Msg?)
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
    val here: Notice?,
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
        /** Its menu, in the web's order (`ShareSheet.rowActions`, ADR 0154 decision 2): drawn whole, each entry its word. */
        val actions: List<me.raddatz.veyq.mobile.navigation.LinkAction> = emptyList(),
    ) {
        /** The local days it was made and ends, as the host shows them (ADR 0154 decision 5). */
        val createdText: me.raddatz.veyq.mobile.words.ShownText = me.raddatz.veyq.mobile.words.ShownText.dayOf(createdAt)
        val expiresText: me.raddatz.veyq.mobile.words.ShownText? = expiresAt?.let { me.raddatz.veyq.mobile.words.ShownText.dayOf(it) }
    }

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
        @Serializable class ShownEnd(val on: Boolean, val optional: Boolean, val day: Long, val today: Long, val lastDay: Long?, val hint: Notice) {
            /** [day] as the host shows it (ADR 0154 decision 5). */
            val dayText: me.raddatz.veyq.mobile.words.ShownText = me.raddatz.veyq.mobile.words.ShownText.day(day)
        }
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
    class Row(val id: String, val kind: String, val name: String, val deletedAt: String, val purgeAfter: String, val restorable: Boolean, val retryable: Boolean) {
        /** The local days it was deleted and is purged, as the host shows them (ADR 0154 decision 5). */
        val deletedText: me.raddatz.veyq.mobile.words.ShownText = me.raddatz.veyq.mobile.words.ShownText.dayOf(deletedAt)
        val purgeText: me.raddatz.veyq.mobile.words.ShownText = me.raddatz.veyq.mobile.words.ShownText.dayOf(purgeAfter)
    }
}

/**
 * What the shell shows beside its state, each value the core's own function's answer — what the Android and Apple apps
 * ask the core for while they draw (their `AppModel` and shell screens), so a host without Kotlin computes none of it
 * (ADR 0136 decision 1). Built by [Bridge] one line per field; a rule found there is a defect to move into the core.
 */
@Serializable
internal class ShellShown(
    /** The tenant open now, on the server [server] (its account's label) of the connection [connection]. */
    val tenant: TenantRef,
    val server: String,
    val connection: String,
    /** The switcher's servers, each with the tenants it lists (`Hidden.visibleTenants`). Superseded by [switcher]. */
    val servers: List<ShownServer>,
    /**
     * The switcher's tenant rows (`ShellWords.switcher`): every row its tenant's name and the line that says where it is —
     * its domain, and the account's address where two signed-in accounts list the same tenant (owner 2026-11-19).
     */
    val switcher: List<ShownSwitcherEntry> = emptyList(),
    /** The drawer's sections that have something in them (`Lists.drawerSections`): an empty one is not drawn (owner 2026-11-19). */
    val drawerSections: List<me.raddatz.veyq.mobile.navigation.DrawerSection> = emptyList(),
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
    /** What the level's New offers (`ShellModel.createsHere`: offline only what is queued), each with its words. */
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
     * for everyone else. A change of the set is a new update; the bytes never ride one (ADR 0137 decision 1).
     */
    val pictures: List<String>,
    /**
     * The pictures' round (`AvatarsModel.held`): moves with every sync that changed the pictures held, a picture replaced
     * under the same id among it (the people read anew by [Call.ShellRefresh]). A host asks every id of [pictures] again
     * when the set or this moved.
     */
    val picturesRound: Int = 0,
    /**
     * The banner offline (`ShellModel.State.banner`: "Offline — content as of {time}", the time the open page's own — a
     * Pot's kept ledger, a gallery's kept listing — where it has one, else the lists'); null online. The one offline
     * banner: a host draws no page's time of its own. Offline only what ADR 0153 decision 3 queues is offered: New offers
     * [creates] (`ShellModel.createsHere`) where [canCreateHere].
     */
    val offlineBanner: Notice? = null,
    /** Under the offline banner while writes made offline wait: their count (`ShellModel.queuedBanner`); null while none does. */
    val queuedBanner: Notice? = null,
    /**
     * What the level the shell shows holds that is not sent yet (`ShellModel.queuedAt`, ADR 0153 decision 5): the folders, Memories,
     * chapters and files made offline there, each drawn in place among its rows with its state and line.
     */
    val queuedHere: List<me.raddatz.veyq.mobile.offline.QueuedItem> = emptyList(),
    /**
     * After a reconnect that sent writes made offline (`ShellModel.queuedSent`): how many were sent and how many need the
     * person, until [Call.ShellQueuedSaidSeen]; empty otherwise.
     */
    val queuedSaid: List<Notice> = emptyList(),
    /** How a level is drawn (the account's choice on this device, ADR 0149 decision 8; [Call.ViewSet]) and what it offers. */
    val view: ViewModeShown = ViewModeShown.valueOf(CoreViewMode.FIRST.name),
    val views: List<ViewModeShown> = CoreViewMode.LEVEL.map { ViewModeShown.valueOf(it.name) },
    /** Whether the level shown takes a drop (`ShellModel.dropLevel`, ADR 0154 decision 1): [Call.Drop] onto [DropOn.Level]. */
    val dropsHere: Boolean = false,
    /** The folders among the level's rows that take a drop (`ShellModel.dropTarget`, never by their kind): [DropOn.Row]. */
    val dropRows: List<String> = emptyList(),
    /**
     * The shown level's plan (`ShellModel.levelPlan`, ADR 0154 decision 2): its New in the level's head and its tools in
     * the level's tools row, each drawn whole and in order; null where no level shows (an object, a person, a kept link,
     * Search).
     */
    val level: ShownLevel? = null,
    /** What the drawer's New offers (`ShellModel.drawerCreates`): its entry is [DrawerSection.NEW] in [drawerSections]. */
    val drawerCreates: List<ShownCreate> = emptyList(),
    /**
     * The shown level's selection while its Select mode is on (`ShellModel.State.selection`, inbox #1049): the selected ids
     * (each row and tile drawn checked, a tap turning it, [Call.SelectToggle]), the count's words and the bar's actions, each
     * drawn whole and in order; null while the mode is off.
     */
    val selection: ShownSelection? = null,
) {
    /** A level's selection as the bar draws it: [selected], [count], then *All*, *None*, the [actions] and *Cancel*. */
    @Serializable class ShownSelection(val selected: List<String>, val count: Notice, val actions: List<ShownSelectionAction>)

    /** One action of the selection's bar: its words, whether some selected row allows it, and what it cannot reach. */
    @Serializable class ShownSelectionAction(
        val id: me.raddatz.veyq.mobile.navigation.SelectionAction,
        @Serializable(with = MsgKey::class) val word: Msg,
        val enabled: Boolean,
        val cannot: Notice?,
    )

    /** A level's plan: New's entries with their words, then the tools row's (`LevelPlan`). */
    @Serializable class ShownLevel(val creates: List<ShownCreate>, val tools: List<me.raddatz.veyq.mobile.navigation.LevelTool>)

    @Serializable class ShownServer(val connection: String, val label: String, val tenants: List<TenantRef>)
    /** A switcher row ([me.raddatz.veyq.mobile.navigation.ShellWords.SwitcherEntry]); [line] its name and detail for a one-label menu item. */
    @Serializable class ShownSwitcherEntry(val connection: String, val tenant: TenantRef, val name: String, val detail: String, val line: String, val current: Boolean)
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
     * An Emergency Kit's read (`KitImport`; ADR 0136 decision 1: the platform draws, the core decides), each on the file
     * at [path] the host itself gave ([Call.SessionSignInKit]) — a path and a number outward, never a byte of the file:
     * how many pages the platform's renderer opens the PDF with ([HostReply.Value], the count as its text; [HostReply.Failed]
     * where it does not open).
     */
    @Serializable @SerialName("Pdf.Pages") data class PdfPages(val path: String) : HostRequest()

    /** Page [page] (1-based) drawn [width] px wide on white, answered as [HostReply.Pixels]. */
    @Serializable @SerialName("Pdf.Page") data class PdfPage(val path: String, val page: Int, val width: Int) : HostRequest()

    /**
     * The picture at [path] cropped as the square cropper's [pose] says (core's `CoverFrame`, its frame [edge] × [edge])
     * and drawn [edge] × [edge] pixels: answered [HostReply.Pixels] with RGBA, 4 bytes a pixel, beside the JSON — one's own
     * profile picture, which the core encodes, pads and seals (ADR 0148 decision 2).
     */
    @Serializable @SerialName("Picture.Square") data class PictureSquare(val path: String, val pose: me.raddatz.veyq.mobile.session.CoverFrame.Pose, val edge: Int) : HostRequest()

    /** The file decoded as a picture, scaled so its longer side is [longSide] px, over white: [HostReply.Pixels]. */
    @Serializable @SerialName("Picture.Luma") data class PictureLuma(val path: String, val longSide: Int) : HostRequest()

    /**
     * A picked video's length (a Memories upload, [Call.MemoryUpload]; ADR 0136 decision 1: the platform decodes, the core
     * decides): [HostReply.Value], its milliseconds as text; null or [HostReply.Failed] where the platform cannot tell. The
     * core takes the poster's time from it (`MediaUpload.posterAtMs`) for [MediaTiers].
     */
    @Serializable @SerialName("Media.Duration") data class MediaDuration(val path: String) : HostRequest()

    /**
     * A picked track's audio from [fromMs] up to [toMs] (null: its end), decoded by the platform (inbox #932; Media
     * Foundation on Windows, GStreamer on Linux) for the core's silence scan (`MusicUpload.Measure.ends`): answered
     * [HostReply.Wav] with a WAV file of that slice's PCM as the reply's payload, or [HostReply.Failed] where the platform
     * does not decode it — the track is then sealed untrimmed. Only the slice is decoded, never the whole file; a path and
     * two numbers outward.
     */
    @Serializable @SerialName("Media.Slice") data class MediaSlice(val path: String, val fromMs: Long, val toMs: Long?) : HostRequest()

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

    /**
     * One of the player's two decks (core's `Deck`, ADR 0142 decision 2: the platform plays bytes, the core decides which,
     * when and how loud), each named by [deck]; the host makes its platform player for a deck at its first load and lets it
     * go at [AudioClear]. The core asks them in order from one thread; the ones it waits for are answered as said, the
     * others ([AudioPause], [AudioSeek], [AudioRamp], [AudioClear]) `Done` at once — the core does not wait for those.
     *
     * Load [load] (numbered by the core; the host names it in [Call.AudioEnded] and [Call.AudioFailed]) of the track readable
     * through reader [reader] ([size] bytes, of type [mediaType]; ADR 0137 decision 2: read through `Reader.Read` from the
     * player's own thread, never decrypted whole or to disk), readied at its start, its volume as last ramped: answered
     * [HostReply.Value] with the file's length in ms (null where the platform cannot say), or [HostReply.Failed] where the
     * platform cannot open it — the core then skips the track (ADR 0142 decision 9). The reader is refused once the deck is
     * cleared or loaded anew.
     */
    @Serializable @SerialName("Audio.Load") data class AudioLoad(val deck: Long, val load: Long, val reader: Long, val size: Long, val mediaType: String) : HostRequest()

    /** Starts or continues playing what was loaded: [HostReply.Done], or [HostReply.Failed] where it refuses (the core skips it). */
    @Serializable @SerialName("Audio.Play") data class AudioPlay(val deck: Long) : HostRequest()
    @Serializable @SerialName("Audio.Pause") data class AudioPause(val deck: Long) : HostRequest()

    /** Moves to [positionMs] of the file. */
    @Serializable @SerialName("Audio.Seek") data class AudioSeek(val deck: Long, val positionMs: Long) : HostRequest()

    /** Where in the file it stands: [HostReply.Value], its ms as text. Asked about four times a second while a track plays. */
    @Serializable @SerialName("Audio.Position") data class AudioPosition(val deck: Long) : HostRequest()

    /**
     * The volume (0.0 silent, 1.0 full) moved to [to] over [overMs], smoothly — steps of at most 50 ms, run by the host
     * (a crossfade is two of these, ADR 0142 decision 2) —; `0` sets it at once.
     */
    @Serializable @SerialName("Audio.Ramp") data class AudioRamp(val deck: Long, val to: Double, val overMs: Long) : HostRequest()

    /** Stops, lets the platform's player go and closes its stream (its reader is refused from now on). */
    @Serializable @SerialName("Audio.Clear") data class AudioClear(val deck: Long) : HostRequest()

    /**
     * The system's media controls (core's `SystemControls`, ADR 0142 decision 3: Windows' SMTC, Linux's MPRIS), each
     * answered `Done` at once, unwaited: a queue loaded — from now on the system's play, pause, next, previous and seek
     * reach the player as `Player.*` calls ([Call.PlayerResume] is its play) —; what plays now ([ControlsShow], at each
     * change and once a second while it plays); the queue stopped ([ControlsClear]: the commands unbound, nothing shown).
     */
    @Serializable @SerialName("Controls.Bind") data object ControlsBind : HostRequest()
    @Serializable @SerialName("Controls.Show") data class ControlsShow(val now: NowPlayingShown) : HostRequest()
    @Serializable @SerialName("Controls.Clear") data object ControlsClear : HostRequest()

    /**
     * Words the core needs as text where a core function takes a string, not a notice (the core renders no word, ADR 0136
     * decision 3): [words] as the host says them, answered [HostReply.Value]. An untitled track's title (`music_untitled`
     * with its number, ADR 0142 decision 4), which its queue carries to the bar and the system's controls.
     */
    @Serializable @SerialName("Words.Say") data class WordsSay(val words: Notice) : HostRequest()

    /**
     * One line of a debug build's request log (inbox #947; Android's `DevNetLog`, logcat `veyq-net`): a request to a host
     * outside the developer's `*.localhost` instance as it was answered — method, address, the `User-Agent` sent, whether a
     * `Referer`, cookie or `Authorization` went (never a value) and the status ([NetLog]). Sent only where the host's
     * [Config.netLog] asked for it, which a release build never does; Linux writes it to stderr, Windows to its `log.txt`.
     * Told, never waited for. The hosts' generated types are named by the `@SerialName` (`HostRequest::NetLog` in Rust,
     * `HostRequest.NetLog` in C#), never by this class's name.
     */
    @Serializable @SerialName("Net.Log") data class NetLogLine(val line: String) : HostRequest()
}

@Serializable
internal sealed class HostReply {
    @Serializable @SerialName("Value") data class Value(val value: String?) : HostReply()
    @Serializable @SerialName("Done") data object Done : HostReply()

    /**
     * A drawing ([HostRequest.PdfPage], [HostRequest.PictureLuma]): its luminance (8 bits a pixel, 0 black, 255 white),
     * [width] × [height] packed row after row, as `veyq_reply`'s payload beside this JSON (ADR 0137 decision 3, extended
     * inward: only the host can draw, so a drawn page's pixels ride in beside a reply), never in it. A payload shorter
     * than the size is no drawing.
     */
    @Serializable @SerialName("Pixels") data class Pixels(val width: Int, val height: Int) : HostReply()

    /**
     * A picked medium's tiers ([HostRequest.MediaTiers]): the preview's [previewSize] bytes then the thumbnail's, packed as
     * `veyq_reply`'s payload beside this JSON (ADR 0137 decision 3 extended inward, as [Pixels]), never in it, and the size
     * it is shown at ([width] × [height], upright; null where unknown). [previewSize] 0 and no payload: not decoded here.
     */
    @Serializable @SerialName("Tiers") data class Tiers(val width: Int?, val height: Int?, val previewSize: Int) : HostReply()

    /** A decoded slice of audio ([HostRequest.MediaSlice]): a WAV file as `veyq_reply`'s payload beside this JSON. */
    @Serializable @SerialName("Wav") data object Wav : HostReply()

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

/** What a `veyq:` link opens (core's `DeepLink`, ADR 0150 § O1): the app where it is, or *Open a link*. */
@Serializable
internal enum class DeepScreen { OPEN, LINK }

/** Where a received link stands (core's `ShareGates.State`, ADR 0150 decision 2). */
@Serializable
internal enum class ReceivedGate { LOADING, REFUSED, LOCKED, TERMS, NOTE, READY, UNAVAILABLE }

/** Why the app does not open a link's instance (core's `ShareInstance.Refusal`, decision 8): with *Open in the browser*. */
@Serializable
internal enum class ReceivedRefusal { UNREACHABLE, OTHER_VERSION, NO_LINKS }

/** What a received link's page is (core's `GuestPage`): the member's page of its kind, a guest's rights. */
@Serializable
internal enum class ReceivedPageKind { LEVEL, FILE, GALLERY, POT, DROP_BOX, UPLOADER, NOT_SHOWN }

/**
 * One child of a guest's level: [displayName] is what its row draws (a folder numbered where a sibling folder shares its
 * name, `GuestLevel.Row.displayName`), [name] what a report reads.
 */
@Serializable
@SerialName("ReceivedRow")
internal class ReceivedRow(
    val id: String, val kind: String, val name: String, val identity: Boolean, val displayName: String = name,
    /** The row's menu as the core plans it (`GuestLevelMoves.rowPlan`, inbox #1063): Move where the guest moves its node ([Call.ReceivedMove]). */
    val menu: List<HeaderShown.PlanAction> = emptyList(),
)

/**
 * The page a received link shows: a level's rows, or the id of the member's own model it opened under the existing calls
 * — [file] for `File.*`/[Watched.File], [gallery] for `Gallery.*`/[Watched.Gallery], [pot] for `Pot.*`/[Watched.Pot] —, or a
 * WRITE_ONLY guest's page ([sent]: what it sent in this visit, by name).
 */
@Serializable
@SerialName("ReceivedPage")
internal class ReceivedPage(
    val kind: ReceivedPageKind, val name: String, val nodeKind: String, val rows: List<ReceivedRow>,
    val file: Long?, val gallery: Long?, val pot: Long?, val sent: List<String>,
    /** A READ_WRITE guest's level: New folder ([Call.ReceivedNewFolder]) and the upload ([Call.ReceivedUpload]) offered (`GuestLevel.writes`, #1034). */
    val writes: Boolean = false,
    /** A guest level's New (`GuestLevel.creates`, a plan drawn whole): a folder, files, a folder uploaded where it writes. */
    val creates: List<ShellShown.ShownCreate> = emptyList(),
    /** A guest level's tools (`GuestLevelMoves.tools`, inbox #1063): Select where some row moves ([Call.ReceivedSelectStart]). */
    val tools: List<me.raddatz.veyq.mobile.navigation.LevelTool> = emptyList(),
    /** A guest level's selection while its Select mode is on (`GuestLevelMoves.selection`): the member's bar over it. */
    val selection: ShellShown.ShownSelection? = null,
    /** The page's title: a level's folder numbered where a sibling folder shares its name (`GuestLevel.title`), else [name]. */
    val title: String = name,
)

/**
 * A received link's screen as the host draws it (ADR 0150 decisions 2–4, 7): its host, the gate it stands at with the
 * texts that gate shows (the space's terms and the operator's section, the note, as markdown), the root's name and the
 * link's role once open, the page, the guest's name, whether *Back* at the root asked ([leaving]) and the link was let go
 * ([left]), whether *Keep* is offered. **Never the link, its key or anything sealed** (`ReceivedBridgeTest` scans every
 * update of a guest session for the key's canaries).
 */
@Serializable
@SerialName("ReceivedShown")
internal class ReceivedShown(
    val host: String,
    val gate: ReceivedGate,
    val refusal: ReceivedRefusal?,
    val askedAgain: Boolean,
    val busy: Boolean,
    val terms: String,
    val operatorSection: String,
    val record: String,
    val note: String,
    val rootName: String,
    val role: String,
    val page: ReceivedPage?,
    val atRoot: Boolean,
    val guestName: String?,
    val leaving: Boolean,
    val left: Boolean,
    val keepOffered: Boolean,
    val reportAsked: Boolean,
    /**
     * The days the link's instance keeps a writing guest's address, port, time and `Origin` (ADR 0093 § 10,
     * `PublicShare.guestWriteRecordDays`): the host shows the web's notice (`share_guest_record_*`) before any write while
     * it is set; null for a link that does not write, or before the gates passed.
     */
    val recordDays: Int? = null,
    /** The link's instance's privacy notice at its guest-write record passage (the notice's *more*): its origin's, no share id. */
    val privacy: String? = null,
    /**
     * A write waits for the guest's name (`GuestNaming.asking`, ADR 0154 decision 1): the host shows its name dialog,
     * answered by [Call.ReceivedNamingSave] or [Call.ReceivedNamingDecline].
     */
    val naming: Boolean = false,
)

/** What a drop landed on ([Call.Drop]; core's `DropTarget`): the core judges it. */
@Serializable
internal sealed class DropOn {
    /** The level the shell shows, its title [label] as the host draws it (the batch's name). */
    @Serializable @SerialName("DropOnLevel") data class Level(val label: String) : DropOn()

    /** The row of [folder] on the level shown ([ShellShown.dropRows]). */
    @Serializable @SerialName("DropOnRow") data class Row(val folder: NodeAt) : DropOn()

    /** Gallery [gallery]'s Memory or chapter (its page open, [Watched.Gallery]). */
    @Serializable @SerialName("DropOnGallery") data class Gallery(val gallery: Long) : DropOn()

    /** The bucket Contributions view [buckets] shows ([Call.BucketsOpen]): its photos and videos go into it (inbox #1065). */
    @Serializable @SerialName("DropOnBuckets") data class Buckets(val buckets: Long) : DropOn()
}

/** One dropped item as the host's file system tells it ([Call.Drop]; core's `Dropped`): never sorted or judged by the host. */
@Serializable
internal sealed class DropItem {
    /** A file at [path]: the core opens it and reads it at offsets. */
    @Serializable @SerialName("DroppedFile") data class File(val path: String) : DropItem()

    /** A folder at [path]: the core walks it. */
    @Serializable @SerialName("DroppedFolder") data class Folder(val path: String) : DropItem()

    /** An item with no local path (a virtual or remote item), said by its [name]. */
    @Serializable @SerialName("DroppedNotLocal") data class NotLocal(val name: String) : DropItem()
}

/** Where a kept link's page stands (core's `BookmarkPage.State`). */
@Serializable
internal enum class BookmarkState { LOADING, UNREADABLE, IN_PLACE, ON_ITS_OWN }

/**
 * A kept link's page as the host draws it (ADR 0150 decision 6): where it stands, the link's host once the meta read
 * ([Msg.BOOKMARK_EMBED_REGION]) and, shown in place, the received link's id for [Watched.Received] and the `Received.*`
 * calls. **Never the link, its password or its key.**
 */
@Serializable
@SerialName("BookmarkShown")
internal class BookmarkShown(val state: BookmarkState, val host: String, val received: Long?)
