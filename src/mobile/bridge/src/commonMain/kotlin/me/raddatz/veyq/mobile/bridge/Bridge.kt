@file:OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.expenses.Currencies
import me.raddatz.veyq.mobile.expenses.EntryEditor
import me.raddatz.veyq.mobile.expenses.EntryForm
import me.raddatz.veyq.mobile.expenses.PotCreateSheet
import me.raddatz.veyq.mobile.expenses.PotModel
import me.raddatz.veyq.mobile.expenses.PotSettings
import me.raddatz.veyq.mobile.expenses.RevisionFormat
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeHeaderModel
import me.raddatz.veyq.mobile.navigation.TrashSheet
import me.raddatz.veyq.mobile.navigation.MoveSheet
import me.raddatz.veyq.mobile.navigation.ShareSheet
import me.raddatz.veyq.mobile.navigation.ReportSheet
import me.raddatz.veyq.mobile.session.CoverFrame
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.words.Notices
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.navigation.ShellWords
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.navigation.UseKey
import me.raddatz.veyq.mobile.session.DeviceKeys
import me.raddatz.veyq.mobile.session.DeviceStore
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The models one process holds, and what a tenant opened becomes: its shell and, per Pot opened, a Pot model. [A] is the
 * account and [T] an opened tenant, as in [SessionModel] — the real ones in [live], a test's own in a test.
 */
internal class Composition<A : Connection, T : Any>(
    val session: SessionModel<A, T>,
    /** What this device hides, which the switcher's tenants leave out ([ShellShown.servers]). */
    val hidden: Hidden,
    /**
     * The files the host names by their paths (ADR 0128 decision 3); a test's composition that writes none has
     * [DeviceFiles.None].
     */
    val files: DeviceFiles = DeviceFiles.None,
    /** The models over a tenant just opened; null where there are none (a test's tenant that is no `TenantSession`). */
    val tenant: (T) -> Tenant?,
)

/**
 * What a tenant opened offers the bridge: its shell, a Pot model per Pot opened (both null for a test's tenant, as they
 * take only a `TenantSession`), the signed-in member's user id ([me]) and the members' pictures the core holds
 * ([pictures], `AvatarsModel.state`), read when [loadPictures] runs (`AvatarsModel.load`: when is the core's rule).
 */
internal class Tenant(
    val shell: ShellModel?,
    val pot: (() -> PotModel)?,
    val me: String?,
    val pictures: StateFlow<Map<String, ByteArray>>,
    val loadPictures: suspend () -> Unit,
    /**
     * A node's header over this tenant (`ShellModel.header`), whose `closeOpen` the bridge hands in (it closes the open
     * Pots of a node gone to the trash); a test's tenant plays one over a door of its own.
     */
    val header: ((node: NodeAt, facts: AppKinds.Facts?, closeOpen: (NodeAt) -> Unit) -> NodeHeaderModel)? = shell?.let { s ->
        { node, facts, closeOpen -> s.header(node, facts, closeOpen) {} }
    },
    /** A space's trash sheet (`ShellModel.trashSheet`). */
    val trash: ((scopeId: String) -> TrashSheet)? = shell?.let { s -> { scopeId -> s.trashSheet(scopeId) {} } },
    /** A new folder where the shell is (`ShellModel.createFolderHere`): why not, or null. */
    val createFolder: (suspend (name: String) -> Notice?)? = shell?.let { s -> { name -> s.createFolderHere(name) } },
    /** A node's move or copy sheet (`ShellModel.moveSheet`), a Pot's copy by the open Pot model handed in. */
    val move: ((node: NodeAt, copying: Boolean, pot: PotModel?, deletedName: String) -> MoveSheet)? = shell?.let { s ->
        { node, copying, pot, deletedName -> s.moveSheet(node, copying, pot, deletedName) {} }
    },
    /** A node's share sheet (`ShellModel.shareSheet`). */
    val share: ((node: NodeAt) -> ShareSheet)? = shell?.let { s -> { node -> s.shareSheet(node) {} } },
    /** A node's report sheet (`ShellModel.reportSheet`). */
    val report: ((node: NodeAt) -> ReportSheet)? = shell?.let { s -> { node -> s.reportSheet(node) } },
    /**
     * "New pot" where the shell is (`PotModel.createSheet` over a Pot model of its own, [language] the host's): null where
     * the tenant has no shell's lists yet. A test's tenant plays one over a door of its own.
     */
    val createPot: ((language: String) -> PotCreateSheet?)? = if (shell != null && pot != null) { language -> pot().createSheet(shell, language) } else null,
    /** A node with everything under it into a sink (`ShellModel.download`): what it left out, or null. */
    val download: (suspend (node: NodeAt, deletedName: String, sink: (ByteArray) -> Unit) -> List<NodeExport.Skipped>?)? = shell?.let { s ->
        { node, deletedName, sink -> s.download(node, deletedName, Clock.System.now().toEpochMilliseconds(), sink) }
    },
    /** A file's page (`ShellModel.fileModel`; the desktops hand no copy to another app, so it offers no "Send a copy"). */
    val file: ((node: NodeAt) -> me.raddatz.veyq.mobile.depot.FileModel)? = shell?.let { s -> { node -> s.fileModel(node, shares = false) } },
)

/** A header the host opened, with the most buttons its bar holds. */
internal class HeldHeader(val model: NodeHeaderModel, val maxButtons: Int)

/** An entry editor the host opened on Pot [pot] ([Call.EditorOpen]), typed in with [separator]. */
internal class HeldEditor(val pot: Long, val model: EntryEditor, val separator: Char)

/** The settings of Pot [pot] the host opened ([Call.PotSettingsOpen]). */
internal class HeldSettings(val pot: Long, val model: PotSettings)

/** "New pot" the host opened ([Call.PotCreateOpen]) and the name it sent last ([Call.PotCreateName]), never echoed. */
internal class HeldCreate(val sheet: PotCreateSheet) {
    val name = MutableStateFlow("")
}

/**
 * A file on the device the host named by its path, as its save dialog gave it (ADR 0128 decision 3): the core opens and
 * writes it, and nothing of its content crosses the bridge. Natively core's `FileSink`; a JVM test plays one.
 */
internal interface DeviceFiles {
    /**
     * Writes the file at [path] with what [produce] hands its sink; it stays only when [produce] answers true, and one
     * that throws or answers false leaves nothing at [path]. Throws when [path] does not open.
     */
    suspend fun write(path: String, produce: suspend (sink: (ByteArray) -> Unit) -> Boolean): Boolean

    /** The whole file at [path] (a cover the host encoded); throws when it does not open or holds more than [most] bytes. */
    suspend fun read(path: String, most: Int): ByteArray

    /** No file is written: a test's composition that has none to offer. */
    object None : DeviceFiles {
        override suspend fun write(path: String, produce: suspend (sink: (ByteArray) -> Unit) -> Boolean) =
            throw UnsupportedOperationException("this composition writes no files")
        override suspend fun read(path: String, most: Int): ByteArray = throw UnsupportedOperationException("this composition reads no files")
    }

    companion object {
        /** The most a cover file the host encoded may hold (a 2048-pixel JPEG is a few MB at most). */
        const val COVER_MOST = 32 shl 20
    }
}

/** The tenant open now: its models, and the account and tenant the session opened them for. */
private class Open<A : Connection>(val models: Tenant, val account: A, val tenant: TenantRef)

/**
 * The dispatch (ADR 0123's Consequences, the bridge): each [Call] is one line onto a model's own function, each
 * [Watched] one model's state flow, and the registry of what is open — the tenant's models, the Pots by id, the watches
 * by id. Nothing here decides anything the person sees: a rule found here is a defect to move into the core (ADR 0127
 * decision 1).
 *
 * Every answer and update is delivered on a core thread ([Dispatchers.Default]); [compose] runs there too, so a host
 * request it makes (the store's first read) never waits on the thread that started the core.
 */
internal class Bridge<A : Connection, T : Any>(compose: () -> Composition<A, T>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val parts = scope.async { compose() }
    private val tenant = MutableStateFlow<Open<A>?>(null)
    private val ids = AtomicLong(0)
    private val pots = Registry<PotModel>()
    private val readers = Registry<RangeReader>()
    private val watches = Registry<Job>()
    private val headers = Registry<HeldHeader>()
    private val trashes = Registry<TrashSheet>()
    private val moves = Registry<MoveSheet>()
    private val shares = Registry<ShareSheet>()
    private val reports = Registry<ReportSheet>()
    private val editors = Registry<HeldEditor>()
    private val settings = Registry<HeldSettings>()
    private val creates = Registry<HeldCreate>()
    private val filePages = Registry<me.raddatz.veyq.mobile.depot.FileModel>()
    private val saves = Registry<me.raddatz.veyq.mobile.depot.FileSave>()

    /** "Manage servers" while it is open, null while closed; its spaces read by [managing]'s job. */
    private val manage = MutableStateFlow<List<ManagedShown>?>(null)
    private val managing = AtomicReference<Job?>(null)

    /** Turned by each tenant hidden or shown, so the shell's watch reads the switcher's tenants anew. */
    private val hiddenTurned = MutableStateFlow(0L)

    /**
     * One call, answered once through [answer] on a core thread — a request that is no [Call] too. The payload beside
     * the JSON is the bytes of an [Answer.Bytes] (never null for one, empty included) and null for every other answer.
     */
    fun call(request: String, answer: (json: String, payload: ByteArray?) -> Unit) {
        val call = runCatching { wire.decodeFromString(Call.serializer(), request) }.getOrNull()
        // An editor's edit is applied here, on the host's thread, in the order the host called: launched, the edits of two
        // keystrokes could land the wrong way round, and the field would end on the first.
        val edited = when (call) {
            is Call.EditorChange -> edit(call)
            is Call.EditorDropReceipt -> editors[call.editor]?.model?.dropReceipt(call.receipt).done(Answer.Why.NO_SUCH_EDITOR)
            is Call.PotCreateName -> named(call)
            else -> null
        }
        scope.launch {
            var payload: ByteArray? = null
            val a = try {
                when {
                    call == null -> Answer.Refused(Answer.Why.NOT_A_CALL)
                    edited != null -> edited
                    else -> dispatch(call) { payload = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Answer.Failed(e::class.simpleName ?: "Exception")
            }
            answer(wire.encodeToString(Answer.serializer(), a), if (a is Answer.Bytes) payload ?: ByteArray(0) else null)
        }
    }

    /** [Call.EditorChange] on its editor: done, or no such editor. */
    private fun edit(c: Call.EditorChange): Answer = editors[c.editor]?.model?.change(c.change).done(Answer.Why.NO_SUCH_EDITOR)

    /** [Call.PotCreateName] held: done, or no such sheet. */
    private fun named(c: Call.PotCreateName): Answer = creates[c.create]?.name?.let { it.value = c.name }.done(Answer.Why.NO_SUCH_CREATE)

    /** One line per call; a call whose answer is [Answer.Bytes] hands its bytes to [payload] first. */
    private suspend fun dispatch(call: Call, payload: (ByteArray) -> Unit): Answer {
        val parts = parts.await()
        val session = parts.session
        val shell = tenant.value?.models?.shell
        return when (call) {
            Call.SessionStart -> opened(session.start())
            is Call.SessionSignIn -> opened(session.signIn(call.server, call.email, call.password, call.secretKey)).also { managed(parts) }
            is Call.SessionStaySignedIn -> (session.stay(call.level)?.answer() ?: Answer.Refused(Answer.Why.NO_OFFER)).also { managed(parts) }
            Call.SessionNotNow -> if (session.notNow()) Answer.Done else Answer.Refused(Answer.Why.NO_OFFER)
            is Call.SessionUnlock -> opened(session.unlock(call.password))
            Call.SessionOtherAccount -> Answer.Done.also { session.otherAccount() }
            Call.SessionAddServer -> Answer.Done.also { session.addServer() }
            Call.SessionCancelAdd -> Answer.Done.also { session.cancelAdd() }
            is Call.SessionForgetDevice -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                session.forgetDevice(account)
                managed(parts)
                Answer.Done
            }
            Call.ManageOpen -> {
                manage.value = shownManaged(parts)
                managing.exchange(scope.launch { session.loadSpaces { managed(parts) } })?.cancel()
                Answer.Done
            }
            Call.ManageClose -> {
                managing.exchange(null)?.cancel()
                manage.value = null
                Answer.Done
            }
            is Call.ManageHideTenant -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                if (account.tenants.none { it.tenantId == call.tenantId }) return Answer.Refused(Answer.Why.NO_SUCH_TENANT)
                parts.hidden.setTenantHidden(account.key, call.tenantId, call.hidden)
                hiddenTurned.value++ // the switcher's tenants are read beside the shell's state, which did not change
                managed(parts)
                Answer.Done
            }
            is Call.ManageStaySignedIn -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                session.stay(account, call.level).answer().also { managed(parts) }
            }
            is Call.ManagePasswordTrust -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                session.trustWithPassword(account, call.password).answer().also { managed(parts) }
            }
            is Call.ManageHideSpace -> {
                parts.hidden.setSpaceHidden(call.scopeId, call.hidden)
                shell?.rebuild()
                managed(parts)
                Answer.Done
            }
            is Call.SessionSwitchTo -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val to = account.tenants.firstOrNull { it.tenantId == call.tenantId } ?: return Answer.Refused(Answer.Why.NO_SUCH_TENANT)
                opened(session.switchTo(account, to)).also { managed(parts) }
            }
            is Call.SessionSignOut -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val out = session.signOut(account)
                if (out.wasCurrent) closeTenant()
                opened(out.next).also { managed(parts) }
            }
            is Call.ShellChoose -> shell?.choose(call.application).done()
            is Call.ShellSwitchSpace -> shell?.switchSpace(call.scopeId).done()
            is Call.ShellEnter -> shell?.enter(call.folder).done()
            is Call.ShellFilter -> shell?.filter(call.application).done()
            Call.ShellBack -> shell?.goBack().done()
            is Call.ShellSelect -> shell?.select(call.tab).done()
            is Call.ShellOpen -> shell?.open(call.ref).done()
            is Call.ShellOpenAtTop -> shell?.openAtTop(call.ref).done()
            is Call.ShellSearch -> shell?.search(call.on).done()
            Call.ShellRefresh -> shell?.refresh().done()
            Call.HomeGroups -> shell?.current?.let { Answer.Groups(it.lists.groups(it.shell)) } ?: noTenant
            is Call.SearchQuery -> shell?.current?.let { Answer.Hits(ShellWords.search(it.lists.objects, call.query)) } ?: noTenant
            is Call.PotOpen -> tenant.value?.models?.pot?.let { make ->
                val id = ids.incrementAndFetch()
                val pot = make()
                pots.put(id, pot)
                scope.launch { pot.open(call.ref) }
                Answer.PotOpened(id)
            } ?: noTenant
            is Call.PotRefresh -> pots[call.pot]?.refresh().done(Answer.Why.NO_SUCH_POT)
            is Call.PotRecord -> pots[call.pot]?.let { pot -> Answer.Said(if (pot.record(call.rows)) null else pot.refusal()) } ?: noPot
            is Call.PotRestore -> pots[call.pot]?.let { pot -> Answer.Said(if (pot.restore(call.entry)) null else pot.refusal()) } ?: noPot
            is Call.PotHistory -> pots[call.pot]?.let { pot ->
                val view = pot.current?.view ?: return noPot
                val rows = view.history(call.entry).takeIf { it.isNotEmpty() } ?: return Answer.Refused(Answer.Why.NO_SUCH_ENTRY)
                Answer.EntryHistory(
                    rows.map { HistoryShown(view.historyLine(it), it.writer, it.at) },
                    view.deletedEntries.any { it.id == call.entry }, pot.restorable(call.entry), view.purgeAfter(call.entry),
                )
            } ?: noPot
            is Call.PotClose -> pots.remove(call.pot)?.close().also {
                editors.removeIf { it.pot == call.pot }.forEach { it.model.close() }
                settings.removeIf { it.pot == call.pot }
            }.done(Answer.Why.NO_SUCH_POT)
            is Call.PotSettingsOpen -> pots[call.pot]?.settings()?.let { model ->
                val id = ids.incrementAndFetch()
                settings.put(id, HeldSettings(call.pot, model))
                Answer.PotSettingsOpened(id)
            } ?: noPot
            is Call.PotSettingsClose -> settings.remove(call.settings)?.model?.clear().done(Answer.Why.NO_SUCH_SETTINGS)
            is Call.PotSettingsCurrency -> stored(call.settings) { it.setBaseCurrency(call.code) }
            is Call.PotSettingsAddCategory -> stored(call.settings) { it.addCategory(call.name) }
            is Call.PotSettingsRenameCategory -> stored(call.settings) { it.renameCategory(call.category, call.name) }
            is Call.PotSettingsAddPerson -> stored(call.settings) { it.addPerson(call.name) }
            is Call.PotSettingsAddMember -> stored(call.settings) { it.addMember(call.userId) }
            is Call.PotSettingsRename -> stored(call.settings) { it.rename(call.person, call.name) }
            is Call.PotSettingsToggle -> stored(call.settings) { it.toggleActive(call.person) }
            is Call.PotSettingsAskErase -> settings[call.settings]?.model?.askErase(call.person).done(Answer.Why.NO_SUCH_SETTINGS)
            is Call.PotSettingsErase -> stored(call.settings) { it.erase() }
            is Call.PotSettingsCancelAsk -> settings[call.settings]?.model?.cancelAsk().done(Answer.Why.NO_SUCH_SETTINGS)
            is Call.PotCreateOpen -> tenant.value?.models?.createPot?.let { make ->
                val sheet = make(call.language) ?: return noTenant
                val id = ids.incrementAndFetch()
                creates.put(id, HeldCreate(sheet))
                Answer.PotCreateOpened(id)
            } ?: noTenant
            is Call.PotCreateClose -> creates.remove(call.create)?.sheet?.close().done(Answer.Why.NO_SUCH_CREATE)
            is Call.PotCreateName -> named(call)
            is Call.PotCreateSpace -> creates[call.create]?.sheet?.space(call.scopeId).done(Answer.Why.NO_SUCH_CREATE)
            is Call.PotCreateCurrency -> creates[call.create]?.sheet?.currency(call.code).done(Answer.Why.NO_SUCH_CREATE)
            is Call.PotCreateAddTyped -> creates[call.create]?.let { Answer.Stored(it.sheet.addTyped(call.name), null) } ?: noCreate
            is Call.PotCreateAddMember -> creates[call.create]?.sheet?.addMember(call.userId).done(Answer.Why.NO_SUCH_CREATE)
            is Call.PotCreateRemove -> creates[call.create]?.sheet?.remove(call.index).done(Answer.Why.NO_SUCH_CREATE)
            is Call.PotCreateCreate -> creates[call.create]?.let { c ->
                val created = c.sheet.create(c.name.value, call.typed)
                Answer.Stored(created, c.sheet.current.notice)
            } ?: noCreate
            is Call.EditorOpen -> pots[call.pot]?.let { pot ->
                val separator = call.separator.firstOrNull() ?: '.'
                val model = pot.editor(call.entry, separator) ?: return Answer.Refused(Answer.Why.NO_SUCH_ENTRY)
                val id = ids.incrementAndFetch()
                editors.put(id, HeldEditor(call.pot, model, separator))
                Answer.EditorOpened(id)
            } ?: noPot
            is Call.EditorChange -> edit(call)
            is Call.EditorSave -> editors[call.editor]?.model?.save()?.let { Answer.EditorSaved(it.done, it.notice) } ?: noEditor
            is Call.EditorAskDelete -> editors[call.editor]?.model?.askDelete().done(Answer.Why.NO_SUCH_EDITOR)
            is Call.EditorDelete -> editors[call.editor]?.model?.let { e -> e.delete().let { Answer.EditorSaved(it, if (it) null else e.current.notice) } } ?: noEditor
            is Call.EditorCancelAsk -> editors[call.editor]?.model?.cancelAsk().done(Answer.Why.NO_SUCH_EDITOR)
            is Call.EditorClose -> editors.remove(call.editor)?.model?.close().done(Answer.Why.NO_SUCH_EDITOR)
            is Call.EditorAddReceipts -> editors[call.editor]?.model?.let { e ->
                // each file read by the core at the path given, never more than a receipt may hold
                val picked = call.paths.map { path ->
                    me.raddatz.veyq.mobile.expenses.Receipts.picked(path, runCatching { parts.files.read(path, me.raddatz.veyq.mobile.expenses.Receipts.MAX_BYTES.toInt()) }.getOrNull())
                }
                e.addReceipts(picked)
                Answer.Done
            } ?: noEditor
            is Call.EditorDropReceipt -> editors[call.editor]?.model?.dropReceipt(call.receipt).done(Answer.Why.NO_SUCH_EDITOR)
            is Call.ReceiptOpen -> pots[call.pot]?.let { pot ->
                val bytes = pot.openReceipt(call.receipt) ?: return Answer.Refused(Answer.Why.NO_RECEIPT)
                payload(bytes)
                Answer.Bytes(me.raddatz.veyq.mobile.expenses.Receipts.mediaTypeOf(bytes))
            } ?: noPot
            is Call.ReceiptPages -> Answer.ReceiptPages(me.raddatz.veyq.mobile.expenses.Receipts.pagesShown(call.total), me.raddatz.veyq.mobile.expenses.Receipts.pagesCut(call.total))
            is Call.ReceiptSave -> pots[call.pot]?.let { pot ->
                val bytes = pot.openReceipt(call.receipt) ?: return Answer.Refused(Answer.Why.NO_RECEIPT)
                val written = runCatching { parts.files.write(call.path) { sink -> sink(bytes); true } }.getOrDefault(false)
                if (written) Answer.Done else Answer.Refused(Answer.Why.NOT_WRITTEN)
            } ?: noPot
            is Call.PotDownload -> pots[call.pot]?.let { pot ->
                val written = parts.files.write(call.path) { sink -> pot.download(call.deletedName, sink) != null }
                if (written) Answer.Done else Answer.Refused(Answer.Why.NOTHING_TO_DOWNLOAD)
            } ?: Answer.Refused(Answer.Why.NO_SUCH_POT)
            is Call.PotArchiveName -> pots[call.pot]?.let { pot ->
                pot.archiveName()?.let(Answer::Name) ?: Answer.Refused(Answer.Why.NOTHING_TO_DOWNLOAD)
            } ?: Answer.Refused(Answer.Why.NO_SUCH_POT)
            is Call.AvatarPicture -> tenant.value?.models?.let { t ->
                val picture = t.pictures.value[call.userId] ?: return Answer.Refused(Answer.Why.NO_PICTURE)
                payload(picture)
                Answer.Bytes(Sniff.image(picture)?.mime)
            } ?: noTenant
            is Call.ReaderRead -> readers[call.reader]?.let { r ->
                payload(r.read(call.position, call.length))
                Answer.Bytes(null)
            } ?: Answer.Refused(Answer.Why.NO_SUCH_READER)
            is Call.ReaderClose -> readers.remove(call.reader)?.let { Answer.Done } ?: Answer.Refused(Answer.Why.NO_SUCH_READER)
            is Call.FileOpen -> tenant.value?.models?.file?.let { make ->
                val id = ids.incrementAndFetch()
                filePages.put(id, make(call.node))
                Answer.FileOpened(id)
            } ?: noTenant
            is Call.FileBytes -> filePages[call.file]?.let { f ->
                val shown = f.current.shown
                val bytes = f.bytes(shown) ?: return Answer.Refused(Answer.Why.NO_BYTES)
                payload(bytes)
                Answer.Bytes(f.mediaType(shown))
            } ?: noFile
            is Call.FilePages -> Answer.ReceiptPages(me.raddatz.veyq.mobile.depot.FileView.pagesShown(call.total), me.raddatz.veyq.mobile.depot.FileView.pagesCut(call.total))
            is Call.FileClose -> filePages.remove(call.file)?.close().done(Answer.Why.NO_SUCH_FILE)
            is Call.FileSave -> filePages[call.file]?.let { f ->
                val save = f.save { produce -> parts.files.write(call.path, produce) } ?: return Answer.Refused(Answer.Why.NOTHING_TO_DOWNLOAD)
                val id = ids.incrementAndFetch()
                saves.put(id, save)
                Answer.Saving(id)
            } ?: noFile
            is Call.SaveCancel -> saves[call.save]?.let { if (it.cancel()) Answer.Done else Answer.Refused(Answer.Why.SAVE_ENDED) }
                ?: Answer.Refused(Answer.Why.NO_SUCH_SAVE)
            is Call.HeaderOpen -> tenant.value?.models?.header?.let { make ->
                val id = ids.incrementAndFetch()
                headers.put(id, HeldHeader(make(call.node, call.facts, ::closeOpen), call.maxButtons))
                Answer.HeaderOpened(id)
            } ?: noTenant
            is Call.HeaderFacts -> headers[call.header]?.model?.update(call.facts).done(Answer.Why.NO_SUCH_HEADER)
            is Call.HeaderClose -> headers.remove(call.header)?.let { Answer.Done } ?: Answer.Refused(Answer.Why.NO_SUCH_HEADER)
            is Call.HeaderPin -> headers[call.header]?.let { Answer.Said(it.model.toggleFavorite()) } ?: noHeader
            is Call.HeaderTrash -> headers[call.header]?.let { h -> h.model.trash().let { Answer.Trashed(it, h.model.current.undo != null) } } ?: noHeader
            is Call.HeaderUndo -> headers[call.header]?.let { Answer.Said(it.model.undoTrash()) } ?: noHeader
            is Call.HeaderRename -> headers[call.header]?.let { it.model.rename(call.name).let { r -> Answer.Renamed(r.problem, r.notice) } } ?: noHeader
            is Call.HeaderCoverPicture -> headers[call.header]?.let { h ->
                val picture = h.model.coverPicture() ?: return Answer.Refused(Answer.Why.NO_COVER)
                payload(picture)
                Answer.Bytes(Sniff.image(picture)?.mime)
            } ?: noHeader
            is Call.HeaderCoverSave -> headers[call.header]?.let { h ->
                val jpeg = call.path?.let { path ->
                    runCatching { parts.files.read(path, DeviceFiles.COVER_MOST) }.getOrNull() ?: return Answer.Said(Notice(Msg.COVER_FAILED))
                }
                Answer.Said(h.model.saveCover(jpeg, call.crop))
            } ?: noHeader
            is Call.HeaderCoverRemove -> headers[call.header]?.let { Answer.Said(it.model.removeCover()) } ?: noHeader
            is Call.CoverPose -> {
                val frame = CoverFrame(call.imageWidth, call.imageHeight, call.frameWidth, call.frameHeight)
                val start = call.at?.let(frame::clamp) ?: call.crop?.let(frame::pose) ?: frame.centred()
                val pose = frame.zoom(frame.pan(start, call.panX, call.panY), call.zoom)
                val scale = frame.baseScale * pose.zoom
                Answer.CoverPosed(pose, call.imageWidth * scale, call.imageHeight * scale, frame.crop(pose))
            }
            is Call.CoverCut -> CoverFrame.pixels(call.crop, call.width, call.height).let { Answer.CoverCut(it[0], it[1], it[2], it[3]) }
            is Call.CoverFit -> NodeCover.encodedSize(call.width, call.height).let { Answer.CoverFit(it[0], it[1], NodeCover.QUALITY, NodeCover.QUALITY_PERCENT) }
            is Call.ReportOpen -> tenant.value?.models?.report?.let { make ->
                val id = ids.incrementAndFetch()
                reports.put(id, make(call.node))
                Answer.ReportOpened(id)
            } ?: noTenant
            is Call.ReportClose -> reports.remove(call.report)?.let { Answer.Done } ?: noReport
            is Call.ReportWhole -> reports[call.report]?.whole(call.whole).done(Answer.Why.NO_SUCH_REPORT)
            is Call.ReportCategory -> reports[call.report]?.category(call.category).done(Answer.Why.NO_SUCH_REPORT)
            is Call.ReportReason -> reports[call.report]?.reason(call.reason).done(Answer.Why.NO_SUCH_REPORT)
            is Call.ReportGoodFaith -> reports[call.report]?.goodFaith(call.on).done(Answer.Why.NO_SUCH_REPORT)
            is Call.ReportContact -> reports[call.report]?.contact(call.contact).done(Answer.Why.NO_SUCH_REPORT)
            is Call.ReportSend -> reports[call.report]?.let { Answer.Said(it.send().problem) } ?: noReport
            is Call.HeaderDownload -> headers[call.header]?.let { h ->
                val download = tenant.value?.models?.download ?: return noTenant
                var leftOut: List<NodeExport.Skipped>? = null
                runCatching { parts.files.write(call.path) { sink -> download(h.model.node, call.deletedName, sink).also { leftOut = it } != null } }
                Answer.Downloaded(leftOut != null, Notices.downloaded(h.model.node.name, leftOut), Notices.downloadLeftOut(leftOut))
            } ?: noHeader
            is Call.MoveOpen -> tenant.value?.models?.move?.let { make ->
                val id = ids.incrementAndFetch()
                val sheet = make(call.node, call.copy, pots.values().firstOrNull { it.current?.ref?.nodeId == call.node.nodeId }, call.deletedName)
                moves.put(id, sheet)
                scope.launch { sheet.load() }
                Answer.MoveOpened(id)
            } ?: noTenant
            is Call.MoveClose -> moves.remove(call.move)?.let { Answer.Done } ?: noMove
            is Call.MoveGo -> moves[call.move]?.go(call.at).done(Answer.Why.NO_SUCH_MOVE)
            is Call.MoveUp -> moves[call.move]?.up().done(Answer.Why.NO_SUCH_MOVE)
            is Call.MoveHere -> moves[call.move]?.let { sent(it.choose()) } ?: noMove
            is Call.MoveConfirm -> moves[call.move]?.let { sent(it.confirm()) } ?: noMove
            is Call.MoveCancelConfirm -> moves[call.move]?.cancelConfirm().done(Answer.Why.NO_SUCH_MOVE)
            is Call.MoveUndo -> moves[call.move]?.let { Answer.Said(it.undo()) } ?: noMove
            is Call.ShellCreateFolder -> tenant.value?.models?.createFolder?.let { Answer.Said(it(call.name)) } ?: noTenant
            is Call.TrashOpen -> tenant.value?.models?.trash?.let { make ->
                val id = ids.incrementAndFetch()
                val sheet = make(call.scopeId)
                trashes.put(id, sheet)
                scope.launch { sheet.load() }
                Answer.TrashOpened(id)
            } ?: noTenant
            is Call.TrashClose -> trashes.remove(call.trash)?.let { Answer.Done } ?: noTrash
            is Call.TrashRestore -> onRow(call.trash, call.row) { sheet, row -> sheet.restore(row) }
            is Call.TrashRetry -> onRow(call.trash, call.row) { sheet, row -> sheet.retry(row) }
            is Call.TrashPurge -> onRow(call.trash, call.row) { sheet, row -> sheet.purge(row) }
            is Call.TrashType -> trashes[call.trash]?.type(call.name).done(Answer.Why.NO_SUCH_TRASH)
            is Call.TrashCancelRename -> trashes[call.trash]?.cancelRename().done(Answer.Why.NO_SUCH_TRASH)
            is Call.ShareOpen -> tenant.value?.models?.share?.let { make ->
                val id = ids.incrementAndFetch()
                val sheet = make(call.node)
                shares.put(id, sheet)
                scope.launch { sheet.load() }
                Answer.ShareOpened(id)
            } ?: noTenant
            is Call.ShareClose -> shares.remove(call.share)?.let { Answer.Done } ?: noShare
            is Call.ShareAdd -> shares[call.share]?.addLink().done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareEdit -> shares[call.share]?.edit(call.link).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareRole -> shares[call.share]?.role(call.role).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareLabel -> shares[call.share]?.label(call.label).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareLockOn -> shares[call.share]?.passwordOn(call.on).done(Answer.Why.NO_SUCH_SHARE)
            is Call.SharePassword -> shares[call.share]?.password(call.password).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareEndOn -> shares[call.share]?.endOn(call.on).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareEndDay -> shares[call.share]?.endDay(call.day).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareCancelForm -> shares[call.share]?.cancelForm().done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareSave -> shares[call.share]?.let { Answer.Said(it.save().problem) } ?: noShare
            is Call.ShareFreshSeen -> shares[call.share]?.freshSeen().done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareAskRenew -> shares[call.share]?.askRenew(call.link).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareAskDelete -> shares[call.share]?.askDelete(call.link).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareCancelAsk -> shares[call.share]?.cancelAsk().done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareRenew -> shares[call.share]?.let { Answer.Said(it.renew().notice) } ?: noShare
            is Call.ShareDelete -> shares[call.share]?.let { Answer.Said(it.delete().notice) } ?: noShare
            is Call.ShareSwitchOff -> shares[call.share]?.let { Answer.Said(it.switchOff(call.link).notice) } ?: noShare
            is Call.ShareReactivate -> shares[call.share]?.let { Answer.Said(it.reactivate(call.link).notice) } ?: noShare
        }
    }

    /**
     * Holds [reader] for the host until [Call.ReaderClose] or the tenant closes, answered as [Answer.Reader] (ADR 0128
     * decision 2): the door of every call that opens a blob for reading in ranges (Depot's file, Memories' medium;
     * their CRs), and of the test's reader until then. Refused while no tenant is open.
     */
    internal fun openReader(reader: RangeReader, mediaType: String?): Answer {
        if (tenant.value == null) return noTenant
        val id = ids.incrementAndFetch()
        readers.put(id, reader)
        return Answer.Reader(id, reader.size, mediaType)
    }

    private val noTenant = Answer.Refused(Answer.Why.NO_TENANT_OPEN)
    private val noHeader = Answer.Refused(Answer.Why.NO_SUCH_HEADER)
    private val noPot = Answer.Refused(Answer.Why.NO_SUCH_POT)
    private val noTrash = Answer.Refused(Answer.Why.NO_SUCH_TRASH)
    private val noMove = Answer.Refused(Answer.Why.NO_SUCH_MOVE)
    private val noShare = Answer.Refused(Answer.Why.NO_SUCH_SHARE)
    private val noReport = Answer.Refused(Answer.Why.NO_SUCH_REPORT)
    private val noEditor = Answer.Refused(Answer.Why.NO_SUCH_EDITOR)
    private val noCreate = Answer.Refused(Answer.Why.NO_SUCH_CREATE)
    private val noFile = Answer.Refused(Answer.Why.NO_SUCH_FILE)

    /** A settings change on [id]'s model, answered once its write ended with whether it was stored and why not. */
    private suspend fun stored(id: Long, change: suspend (PotSettings) -> Boolean): Answer {
        val model = settings[id]?.model ?: return Answer.Refused(Answer.Why.NO_SUCH_SETTINGS)
        val stored = change(model)
        return Answer.Stored(stored, model.current.notice.takeIf { !stored })
    }

    /** A move sheet's state as a write's answer ([Answer.MoveSent]). */
    private fun sent(s: MoveSheet.State) = Answer.MoveSent(s.done, s.undo != null, s.confirming, s.problem)

    /** [act] on the row of trash sheet [trash] listed as [rowId] (`TrashSheet.row`). */
    private suspend fun onRow(trash: Long, rowId: String, act: suspend (TrashSheet, me.raddatz.veyq.mobile.session.NodeTrash.Trashed) -> Unit): Answer {
        val sheet = trashes[trash] ?: return noTrash
        val row = sheet.row(rowId) ?: return Answer.Refused(Answer.Why.NO_SUCH_ROW)
        act(sheet, row)
        return Answer.Done
    }

    /** The open Pots of a node gone to the trash close (a header's `closeOpen`). */
    private fun closeOpen(ref: NodeAt) {
        pots.values().filter { it.current?.ref?.nodeId == ref.nodeId }.forEach { it.close() }
    }

    /** "Manage servers" as the core has it now, when it is open (after a call that changed the servers or their trust). */
    private fun managed(parts: Composition<A, T>) {
        if (manage.value != null) manage.value = shownManaged(parts)
    }

    /** `SessionModel.managed`, each field the core's own ([ManagedShown]). */
    private fun shownManaged(parts: Composition<A, T>): List<ManagedShown> = parts.session.managed().map { m ->
        ManagedShown(m.account.key, m.label, m.trusted, m.level, m.levels, m.tenants.map { t ->
            ManagedShown.Tenant(t.tenant, t.hidden, t.current, t.shown, t.switchable, t.subtitle, t.spaces?.map { s ->
                ManagedShown.Space(s.space, ShellWords.space(s.space), s.hidden, s.canHide)
            })
        })
    }

    private fun Unit?.done(why: Answer.Why = Answer.Why.NO_TENANT_OPEN): Answer = if (this == null) Answer.Refused(why) else Answer.Done

    /** A change of trust's outcome as the call's answer. */
    private fun TrustChange.answer(): Answer = when (this) {
        TrustChange.DONE -> Answer.Done
        TrustChange.CANCELLED -> Answer.Refused(Answer.Why.TRUST_CANCELLED)
        TrustChange.WRONG_PASSWORD -> Answer.Refused(Answer.Why.WRONG_PASSWORD)
        TrustChange.FAILED -> Answer.Refused(Answer.Why.TRUST_FAILED)
    }

    /**
     * A tenant the session opened becomes the open one: its shell read and shown, its pictures read in the background
     * (the letters show meanwhile), every Pot and reader of the one before closed.
     */
    private suspend fun opened(o: SessionModel.Opened<A, T>?): Answer {
        if (o == null) return Answer.Done
        val t = parts.await().tenant(o.session) ?: return Answer.Done
        closeTenant()
        tenant.value = Open(t, o.account, o.tenant)
        scope.launch { runCatching { t.loadPictures() } }
        t.shell?.load(openLastUsed = true)
        return Answer.Done
    }

    private fun closeTenant() {
        tenant.value = null
        pots.removeAll().forEach { it.close() }
        editors.removeAll().forEach { it.model.close() }
        settings.removeAll()
        creates.removeAll().forEach { it.sheet.close() }
        filePages.removeAll().forEach { it.close() }
        // a save stops with its tenant: its partial file removed by the sink as its job is cancelled
        saves.removeAll().forEach { save -> scope.launch { save.cancel() } }
        readers.removeAll()
        headers.removeAll()
        trashes.removeAll()
        moves.removeAll()
        shares.removeAll()
        reports.removeAll()
    }

    /**
     * Observes what [request] names: [update] gets the state now and each change after, on a core thread, until
     * [unwatch]. Returns the watch's id, or 0 for a request that is no [Watched] (nothing is delivered then).
     */
    fun watch(request: String, update: (String) -> Unit): Long {
        val watched = runCatching { wire.decodeFromString(Watched.serializer(), request) }.getOrNull() ?: return 0
        val id = ids.incrementAndFetch()
        val job = scope.launch {
            updates(watched).collect { update(wire.encodeToString(Update.serializer(), it)) }
        }
        watches.put(id, job)
        return id
    }

    private suspend fun updates(w: Watched): Flow<Update> = when (w) {
        Watched.Session -> parts.await().session.gate.map { Update.Session(it) }
        Watched.Manage -> manage.map { Update.Manage(it) }
        Watched.Shell -> {
            val parts = parts.await()
            tenant.flatMapLatest { open ->
                val m = open?.models
                m?.shell?.state?.let { state ->
                    combine(state, m.pictures, hiddenTurned) { s, pictures, _ -> Update.Shell(s, s?.let { shown(parts, open, it, pictures.keys) }) }
                } ?: flowOf(Update.Shell(null))
            }
        }
        is Watched.Header -> headers[w.header]?.let { h -> h.model.state.map { Update.Header(w.header, shownHeader(h)) } }
            ?: flowOf(Update.Header(w.header, null))
        is Watched.Trash -> trashes[w.trash]?.let { t -> t.state.map { Update.Trash(w.trash, shownTrash(t, it)) } }
            ?: flowOf(Update.Trash(w.trash, null))
        is Watched.Move -> moves[w.move]?.let { m -> m.state.map { Update.Move(w.move, shownMove(m, it)) } }
            ?: flowOf(Update.Move(w.move, null))
        is Watched.Share -> shares[w.share]?.let { sh -> sh.state.map { Update.Share(w.share, shownShare(sh, it)) } }
            ?: flowOf(Update.Share(w.share, null))
        is Watched.Report -> reports[w.report]?.let { r -> r.state.map { Update.Report(w.report, shownReport(r, it)) } }
            ?: flowOf(Update.Report(w.report, null))
        // the poll runs inside this watch's own job: a host's dropped watch cancels it, and it ends with its Pot
        is Watched.PotLive -> flow { pots[w.pot]?.live() }
        is Watched.Editor -> editors[w.editor]?.let { e -> e.model.state.map { Update.Editor(w.editor, shownEditor(e, it)) } }
            ?: flowOf(Update.Editor(w.editor, null))
        is Watched.PotSettings -> settings[w.settings]?.let { held ->
            val pot = pots[held.pot]?.state ?: flowOf(null)
            combine(held.model.state, pot) { s, p -> Update.PotSettings(w.settings, p?.view?.let { shownSettings(s, it, p.saving) }) }
        } ?: flowOf(Update.PotSettings(w.settings, null))
        is Watched.PotCreate -> creates[w.create]?.let { held ->
            combine(held.sheet.state, held.name) { s, name -> Update.PotCreate(w.create, shownCreate(held.sheet, s, name)) }
        } ?: flowOf(Update.PotCreate(w.create, null))
        is Watched.File -> filePages[w.file]?.let { f -> f.state.map { Update.File(w.file, shownFile(f, it)) } }
            ?: flowOf(Update.File(w.file, null))
        is Watched.Save -> saves[w.save]?.let { sv -> sv.state.map { Update.Save(w.save, SaveShown(sv.name, it.written, it.total, it.fraction, it.end, it.notice)) } }
            ?: flowOf(Update.Save(w.save, null))
        is Watched.Pot -> {
            val pictures = tenant.value?.models?.pictures ?: flowOf(emptyMap())
            (pots[w.pot]?.state ?: flowOf(null)).combine(pictures) { s, p -> Update.Pot(w.pot, s, if (s == null) emptyList() else p.keys.sorted()) }
        }
    }

    /**
     * What the shell shows beside [s] ([ShellShown]; ADR 0123's Consequences, the bridge): each field one core
     * function's answer.
     */
    private fun shown(parts: Composition<A, T>, open: Open<A>, s: ShellModel.State, pictures: Set<String>): ShellShown {
        val shell = s.shell
        val lists = s.lists
        val space = lists.space(shell)
        return ShellShown(
            tenant = open.tenant,
            server = open.account.label,
            connection = open.account.key,
            servers = parts.session.accounts.map { c ->
                ShellShown.ShownServer(c.key, c.label, parts.hidden.visibleTenants(c.key, c.tenants, if (c == open.account) open.tenant else null))
            },
            space = space,
            spaceName = ShellWords.space(space),
            switcherSubtitle = ShellWords.switcherSubtitle(space, open.tenant.name, open.account.label),
            spaces = lists.spaces.map { ShellShown.ShownSpace(it, ShellWords.space(it)) },
            applicationWords = ApplicationId.entries.map { ShellShown.ShownApplication(it, AppKinds.applicationWord(it)) },
            groups = lists.groups(shell),
            list = shell.application?.let(lists::applicationList),
            tabs = shell.barTabs.map { ShellShown.ShownTab(it, AppKinds.tabWord(it, shell.openObject?.kind)) },
            selectedTab = shell.selectedTab,
            canGoBack = shell.canGoBack,
            isHome = shell.isHome,
            drawerOpensAtStart = shell.drawerOpensAtStart,
            creates = AppKinds.drawerCreates.map { ShellShown.ShownCreate(it, it.word) },
            canCreateHere = open.models.shell?.canCreateHere == true,
            headed = shell.openObject?.at ?: shell.space?.let(shell::folderAt),
            me = open.models.me,
            pictures = pictures.sorted(),
        )
    }

    /** An entry editor as the host draws it ([EditorShown]), each field one core function's answer. */
    private fun shownEditor(e: HeldEditor, s: EntryEditor.State): EditorShown {
        val f = s.form
        val view = pots[e.pot]?.current?.view
        val ids = s.people.map { it.id }
        fun split(split: EntryForm.FormSplit, part: Long) = EditorSplit(
            split.mode, split.expanded, EntryForm.collapsedWords(split, s.people),
            s.people.map { p -> EntryForm.typedIn(split, p.id).let { EditorSplitPerson(p.id, p.name, it != null, it.orEmpty()) } },
            EntryForm.splitHint(split, part, f.currency, f.minorUnits, e.separator),
        )
        fun money(amount: Long) = me.raddatz.veyq.mobile.words.Piece.Money(amount, view?.currency, view?.minorUnits ?: 2)
        val categories = RevisionFormat.DEFAULT_CATEGORIES + view?.ownCategories.orEmpty().map { it.first }
        return EditorShown(
            isNew = s.isNew, editable = s.editable, invalid = s.invalid,
            kind = f.kind, title = f.title, date = f.date, category = f.category,
            categories = view?.let { v -> categories.map { EditorChoice(it, v.categoryLabel(it)) } }.orEmpty(),
            currency = f.currency, currencies = Currencies.known, base = view?.currency.orEmpty(), rate = f.rate, rateFill = s.rate,
            amount = f.amount, from = f.from, to = f.to,
            people = s.people.map { EditorPerson(it.id, it.name) },
            payers = f.payers.map { EditorPayer(it.participant, it.amount) },
            canRemovePayer = f.canRemovePayer, canAddPayer = f.nextPayer(ids) != null,
            total = me.raddatz.veyq.mobile.words.Piece.Money(f.payersTotal, f.currency, f.minorUnits),
            items = f.items.map { EditorItem(it.label, it.amount, split(it.split, f.itemAmount(it))) },
            rest = if (f.kind != EntryForm.Kind.TRANSFER && f.showsRest) split(f.rest, f.restLeft) else null,
            restIsWhole = f.items.isEmpty(),
            preview = s.preview.map { EditorShare(it.name, it.given.takeIf { g -> g != 0L }?.let(::money), money(it.share)) },
            refusal = s.refusal?.let { EditorRefusal(Notice(Notices.entryProblem(it.problem)), it.at, it.item) },
            saving = s.saving, notice = s.notice, asking = s.asking, done = s.done,
            receipts = f.receipts.map { id ->
                val r = view?.receipt(id)
                EditorReceipt(id, me.raddatz.veyq.mobile.expenses.Receipts.downloadName(r?.name, r?.type), held = false, opens = r != null && !r.blocked)
            } + s.pending.map { EditorReceipt(it.id, it.name, held = true, opens = false) },
            canAddReceipt = s.canAddReceipt, refusedPick = s.refusedPick,
        )
    }

    /** A Pot's settings as the host draws them ([PotSettingsShown]), each field one core function's answer. */
    private fun shownSettings(s: PotSettings.State, view: me.raddatz.veyq.mobile.expenses.PotView, saving: Boolean) = PotSettingsShown(
        ready = view.ready, canWrite = view.canWrite, saving = saving, notice = s.notice,
        currency = view.currency, currencies = Currencies.known,
        currencyEditable = view.currencyEditable, currencyFixed = view.currencyFixed, canChangeSettings = view.canChangeSettings,
        categoriesShown = view.categoriesShown, canAddCategory = view.canAddCategory,
        categories = view.ownCategories.map { EditorChoice(it.first, view.categoryLabel(it.first)) },
        people = view.personEntries.map { me.raddatz.veyq.mobile.expenses.PotView.ShownPerson(it, view.personFacts(it)) },
        deletedPeople = view.deletedPeople, settleFirst = view.canWrite && view.anyUnsettled,
        members = view.addableMembers.map { PotMember(it.userId, it.name) },
        asking = s.asking?.let { ErasingPerson(it.personId, it.name, it.named) },
    )

    /** "New pot" as the host draws it ([PotCreateShown]), each field one core function's answer. */
    private fun shownCreate(sheet: PotCreateSheet, s: PotCreateSheet.State, name: String) = PotCreateShown(
        spaces = s.spaces.map { ShellShown.ShownSpace(it, ShellWords.space(it)) }, space = s.scopeId,
        currency = s.currency, currencies = Currencies.known,
        people = s.people.map { CreatePerson(it.name, it.userId != null) },
        offered = s.offered.map { PotMember(it.userId, it.displayName) },
        canCreate = sheet.canCreate(name), saving = s.saving, notice = s.notice, done = s.done,
    )

    /** A header as the host draws it ([HeaderShown]), each field the model's own answer. */
    private fun shownHeader(h: HeldHeader): HeaderShown {
        val m = h.model
        val s = m.current
        fun actions(ids: List<String>) = ids.map { HeaderShown.PlanAction(it, m.label(it, hasCover = s.cover != null)) }
        return HeaderShown(
            node = m.node,
            pinned = s.pinned,
            plan = m.plan(h.maxButtons)?.let { p -> HeaderShown.Plan(actions(p.buttons), actions(p.menu), actions(p.last)) },
            deleteQuestion = m.deleteQuestion(),
            undo = s.undo,
            gone = s.gone,
            archiveName = s.downloadName ?: NodeExport.fileName(m.node.name),
            cover = s.cover,
        )
    }

    /** A file's page as the host draws it ([FileShown]), each field the model's own answer. */
    private fun shownFile(f: me.raddatz.veyq.mobile.depot.FileModel, s: me.raddatz.veyq.mobile.depot.FileModel.State): FileShown {
        val shown = s.shown
        val view = when (shown) {
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Loading -> FileShown.View.LOADING
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Failed -> FileShown.View.FAILED
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.NotUploaded -> FileShown.View.NOT_UPLOADED
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Message -> FileShown.View.MESSAGE
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Image -> FileShown.View.IMAGE
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Text -> FileShown.View.TEXT
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Pdf -> FileShown.View.PDF
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Media -> FileShown.View.MEDIA
        }
        return FileShown(
            node = f.node, view = view, words = me.raddatz.veyq.mobile.depot.FileModel.words(shown), mediaType = f.mediaType(shown),
            edge = (shown as? me.raddatz.veyq.mobile.depot.FileModel.Shown.Image)?.edge,
            text = (shown as? me.raddatz.veyq.mobile.depot.FileModel.Shown.Text)?.text, truncated = me.raddatz.veyq.mobile.depot.FileModel.cut(shown),
            size = (shown as? me.raddatz.veyq.mobile.depot.FileModel.Shown.Media)?.size,
            revision = s.revision, facts = s.facts, uploader = s.uploader,
            headerFacts = s.header?.facts, downloadName = s.header?.downloadName,
        )
    }

    /** A report sheet as the host draws it ([ReportShown]), each field the sheet's own answer. */
    private fun shownReport(r: ReportSheet, s: ReportSheet.State): ReportShown = ReportShown(
        node = r.node, categories = r.categories.map { ReportShown.ShownCategory(it.token, it.word) }, whole = s.whole,
        scopeHint = s.scopeHint, category = s.category, note = s.note, reasonMax = NodeReport.REASON_MAX,
        contactMax = NodeReport.CONTACT_MAX, goodFaith = s.goodFaith, canSend = s.canSend, sending = s.sending, problem = s.problem,
        sent = s.sent != null, reference = s.sent?.reference, receivedAt = s.sent?.receivedAt, privacyUrl = r.privacyUrl,
    )

    /** A move or copy sheet as the host draws it ([MoveShown]), each field the sheet's own answer. */
    private fun shownMove(m: MoveSheet, s: MoveSheet.State): MoveShown = MoveShown(
        node = m.node, copying = m.copying, title = m.title, hint = m.hint, submit = m.submit, loaded = s.loaded,
        trail = s.trail.map { MoveShown.MoveCrumb(it.nodeId, it.name) },
        places = s.places.map { MoveShown.MovePlace(it.node.nodeId, it.node.kind, it.node.name, it.blocked?.let(NodeMove::placeWord)) },
        empty = m.empty.takeIf { s.loaded && s.places.isEmpty() },
        here = m.hereWord(s), problem = s.problem, noRoom = s.noRoom, losses = s.losses.map { Notice(it) }, leftOut = s.leftOut,
        busy = s.busy, canChoose = s.canChoose, confirming = s.confirming, confirmQuestion = m.confirmQuestion, done = s.done, undo = s.undo,
    )

    /** A share sheet as the host draws it ([ShareShown]), each field the sheet's own answer. */
    private fun shownShare(sh: ShareSheet, s: ShareSheet.State): ShareShown = ShareShown(
        node = sh.node, title = sh.words.title, intro = sh.words.intro, canMint = sh.canMint,
        links = s.links?.let {
            sh.rows(s).map { r ->
                val l = r.link
                ShareShown.ShownLink(
                    l.shareId, r.title, r.badges.map { Notice(it) }, l.createdAt, l.expiresAt, l.url.takeIf { r.canCopy }, r.canCopy, r.canEdit,
                    r.canRenew, r.canSwitchOff, r.canReactivate, r.canDelete,
                )
            }
        },
        failed = s.failed, busy = s.busy, notice = s.notice,
        form = s.form?.let { f ->
            ShareShown.ShownForm(
                f.roles.map { ShareShown.ShownForm.ShownRole(it, sh.words.roleWord(it)) }, f.role, sh.words.hint(f.role), f.namesIt,
                f.nameLabel, f.label,
                ShareShown.ShownForm.ShownLock(f.withPassword, f.passwordOptional, f.hadPassword, f.passwordTooShort, sh.passwordHint),
                ShareShown.ShownForm.ShownEnd(f.withEnd, f.endOptional, f.endDay, f.today, f.lastDay, sh.endHint),
                f.canSave,
            )
        },
        editing = s.editing, submit = sh.submit(s), saving = s.saving, problem = s.problem, freshAddress = s.fresh,
        renewing = s.renewing, deleting = s.deleting, renewQuestion = Msg.SHARE_RENEW_CONFIRM, deleteQuestion = sh.words.deleteConfirm,
    )

    /** A trash sheet as the host draws it ([TrashShown]), each field the sheet's own answer. */
    private fun shownTrash(sheet: TrashSheet, s: TrashSheet.State): TrashShown = TrashShown(
        rows = s.rows?.map { TrashShown.Row(it.id, it.kind, it.name, it.deletedAt, it.purgeAfter, sheet.canRestore(it), s.retryable(it)) },
        failed = s.failed, busy = s.busy, errorRow = s.errorRow, error = s.error, renaming = s.renaming, retryName = s.retryName,
    )

    fun unwatch(id: Long) {
        watches.remove(id)?.cancel()
    }

    /** Ends everything: the watches, the calls under way, the models. */
    fun shutdown() {
        scope.cancel()
    }

    companion object {
        /** The bridge over the real accounts: [primitives] the platform's suite, [client] its HTTP client, [store] the host's device store. */
        fun live(
            primitives: Primitives,
            client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient,
            config: Config,
            store: DeviceStore,
            /** The device's keys over [store]: the platform's (`platformDeviceKeys`), a test's own on the JVM. */
            keys: (DeviceStore) -> DeviceKeys,
            files: DeviceFiles,
            now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        ): Bridge<VeyqAccount, TenantSession> = Bridge {
            val recent = RecentUses(
                persist = { keys -> store.write(RECENT, keys.joinToString("\n") { it.encode() }) },
                initial = store.read(RECENT)?.lines()?.mapNotNull(UseKey::decode).orEmpty(),
            )
            val hidden = Hidden(
                persist = { ids -> store.write(HIDDEN, ids.joinToString("\n")) },
                initial = store.read(HIDDEN)?.lines()?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
            )
            val device = ThisDevice(store, keys(store), config.device, primitives)
            Composition(SessionModel.accounts(primitives, device, client, hidden, recent), hidden, files) { t ->
                Tenant(ShellModel(t, hidden, recent, now), { PotModel(t, now) }, t.account.userId, t.pictures.state, t.pictures::load)
            }
        }

        /** The device store's names for what the bridge itself keeps there (the others are the core's). */
        const val RECENT = "veyq.recent"
        const val HIDDEN = "veyq.hidden"
    }
}
