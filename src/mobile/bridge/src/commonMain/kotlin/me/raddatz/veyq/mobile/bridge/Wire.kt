package me.raddatz.veyq.mobile.bridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.expenses.PotModel
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ArrangedList
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.FolderRef
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey
import me.raddatz.veyq.mobile.words.Piece

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
     * The full sign-in, then this device trusted: staying signed in when [remember] (what it uses is
     * [Update.Session.staySignedIn]), else the password trust.
     */
    @Serializable @SerialName("Session.SignIn")
    data class SessionSignIn(val server: String, val email: String, val password: String, val secretKey: String, val remember: Boolean) : Call()

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
    @Serializable @SerialName("Pot.Refresh") data class PotRefresh(val pot: Long) : Call()
    @Serializable @SerialName("Pot.Close") data class PotClose(val pot: Long) : Call()

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

    enum class Why { NOT_A_CALL, NO_TENANT_OPEN, NO_SUCH_CONNECTION, NO_SUCH_TENANT, NO_SUCH_POT, NO_SUCH_READER, NO_PICTURE, NOTHING_TO_DOWNLOAD }
}

/** What a watch observes ([veyq_watch]): one screen model's state. */
@Serializable
internal sealed class Watched {
    @Serializable @SerialName("Session") data object Session : Watched()

    /** The shell over the tenant open now, whichever that is: a switch moves the watch to the new tenant's shell. */
    @Serializable @SerialName("Shell") data object Shell : Watched()
    @Serializable @SerialName("Pot") data class Pot(val pot: Long) : Watched()

    /** "Manage servers" while it is open ([Call.ManageOpen]). */
    @Serializable @SerialName("Manage") data object Manage : Watched()
}

/** One state a watch delivers: the first at once, then each change. */
@Serializable
internal sealed class Update {
    /**
     * The way in. Beside a `Gate.SignIn`, [staySignedIn] is what its "Stay signed in" would use on this device, the
     * core's store's own answer (`SessionModel.staySignedIn`): `NONE` hides the switch, `LOGIN` is the plain one,
     * `PRESENCE` the one the person confirms (Windows: "with Windows Hello"). Beside a `Gate.Unlock` of a
     * `DEVICE_KEY` record it is the same answer, by which the unlock words what opens the account (a `LOGIN` store asks
     * nothing, so its unlock shows only after a resume that failed). Null beside every other gate.
     */
    @Serializable @SerialName("Session") data class Session(val gate: SessionModel.Gate, val staySignedIn: StaySignedIn? = null) : Update()

    /** "Manage servers": every server signed in to, null while it is closed. */
    @Serializable @SerialName("Manage") data class Manage(val servers: List<ManagedShown>?) : Update()

    /** Null while no tenant is open, or its lists are not read yet; [shown] beside a state, null with none. */
    @Serializable @SerialName("Shell") data class Shell(val state: ShellModel.State?, val shown: ShellShown? = null) : Update()

    /**
     * Null while nothing is open in this Pot model (closed, or an id the bridge does not hold). [pictures] are the user
     * ids whose picture the core holds ([Call.AvatarPicture]); a person whose member id is not among them shows the letter.
     */
    @Serializable @SerialName("Pot") data class Pot(val pot: Long, val state: PotModel.State?, val pictures: List<String> = emptyList()) : Update()
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
    /** What the sidebar's New offers (`AppKinds.drawerCreates`' words). */
    val creates: List<@Serializable(with = MsgKey::class) Msg>,
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
}

/**
 * One server on "Manage servers", as Android's `ManageScreen` and Apple's `ManageView` read it from the core
 * (`SessionModel.managed`): its connection's key and label, how this device trusts it ([trusted]: null when it does not,
 * DEVICE with the password, DEVICE_KEY staying signed in), and its tenants.
 */
@Serializable
internal class ManagedShown(val connection: String, val label: String, val trusted: TrustedDevice.Method?, val tenants: List<Tenant>) {
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
