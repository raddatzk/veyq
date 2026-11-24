import Foundation
import Observation
import VeyqCore

extension AppModel {
    /// What Expenses holds in the app's model: the open Pot, its settings, New pot and the entry editor.
    var expenses: ExpensesHolding { held(ExpensesHolding.self) }
}

/// What Expenses holds in the app's model for the open tenant (ADR 0154 decision 3, inbox #1066): the Pot open in the app
/// (core's `PotModel`: its views, its live poll, its writes — a link's Pot held in the member's place while its page
/// shows), its settings, "New pot" and the entry editor; a share's files taken into a new expense, and a held entry the
/// tray's *Open* asked for.
@MainActor
@Observable
final class ExpensesHolding: Holding {
    @ObservationIgnored private unowned let model: AppModel

    init(_ model: AppModel) { self.model = model }

    // MARK: The moments of the tenant's life and the flows core asks

    func shown(_ shell: ShellModel, _ tenant: TenantSession, switched: Bool) {
        let pots = PotModel(tenant: tenant, now: { @Sendable in KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) })
        // a link's Pot shown in the member's place meanwhile: the member's goes with the tenant, the guest's with its page
        memberPot?.watch?.stop()
        memberPot = nil
        potWatch?.stop()
        potModel = pots
        potWatch = pots.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.syncPot() }
        }
        pot = nil
    }

    /// An open Pot of the node trashed closes.
    func trashed(_ ref: NodeAt) {
        guard let open = pot?.ref, open.nodeId == ref.nodeId else { return }
        potModel?.close(ref: open)
        syncPot()
    }

    /// A Pot's copy is made by the open Pot (`AppKinds.copiesByOpenModel` gives a Pot alone).
    func moveSheet(_ shell: ShellModel, _ node: NodeAt, copying: Bool, changed: @escaping @Sendable () -> Void) -> MoveSheet? {
        guard node.kind == PotSession.companion.KIND else { return nil }
        return shell.moveSheet(node: node, copying: copying, open: potModel, deletedName: say(.potDeletedPerson)) { changed() }
    }

    /// Files dropped on the app while a Pot that takes them is open open its new expense with them at once.
    func takesDrop(_ open: ObjectRef, _ incoming: ShareInbox.Incoming) -> Bool {
        open.kind == PotSession.companion.KIND && pot?.ref == open && PotShare.shared.takesDropOn(view: pot?.view, incoming: incoming)
    }

    /// The tray's *Open* of a held Pot entry: its Pot's page opens its held editor once the Pot is read.
    func openQueued(_ opens: QueuedOpen) -> Bool {
        guard let e = opens as? QueuedOpen.Entry else { return false }
        let ref = model.objects.first { $0.scopeId == e.scope && $0.nodeId == e.pot }
            ?? ObjectRef(scopeId: e.scope, nodeId: e.pot, kind: PotSession.companion.KIND, application: .expenses, name: "")
        heldOpen = e
        model.open(ref)
        return true
    }

    var editing: Bool { editorState != nil }

    // MARK: The open Pot (core's PotModel)

    /// The Pot open in the app (core's `PotModel`): its views, its live poll, its writes.
    @ObservationIgnored private(set) var potModel: PotModel?
    @ObservationIgnored private var potWatch: PotModel.Watch?
    private var potSession: PotSession? { potModel?.session }

    /// The open Pot as core's model holds it now.
    private func syncPot() {
        pot = potModel?.current.map {
            PotState(ref: $0.ref, view: $0.view, failed: $0.failed, failure: $0.failure, saving: $0.saving, message: $0.message)
        }
    }

    /// An open Pot's views: `view` nil while it loads, `failed` when it did not open — `failure` what its page says then
    /// (core's: offline not kept, else it did not open); `message` the last add's refusal.
    struct PotState {
        let ref: ObjectRef
        var view: PotView?
        var failed = false
        var failure: Msg?
        var saving = false
        var message: String?
    }

    private(set) var pot: PotState?

    /// A held Pot entry the tray's *Open* asked for: its Pot's page opens its held editor once the Pot is read.
    var heldOpen: QueuedOpen.Entry?

    /// Opens and reads the Pot `ref` unless it is the one shown.
    func loadPot(_ ref: ObjectRef) {
        guard let pots = potModel else { return }
        Task {
            try? await pots.open(ref: ref)
            syncPot()
        }
    }

    /// The open Pot's live poll (core's `LivePoll`, run by core) for as long as the calling task runs — the Pot's screen
    /// while the app is active, so nothing is asked in the background; its cancellation stops the poll.
    func livePot() async {
        guard let live = potModel?.startLive() else { return }
        await untilCancelled()
        live.stop()
    }

    /// Reads the open Pot again now (pull to refresh).
    func refreshPot() async {
        try? await potModel?.refresh()
        syncPot()
    }

    /// Records settle-up `rows` as paid, dated the core's today (core's `PotModel.record`); a refusal is the Pot's message.
    func recordSettleUp(_ rows: [PotView.SettleRow]) async {
        guard let pots = potModel else { return }
        _ = try? await pots.record(rows: rows)
        syncPot()
    }

    /// Whether deleted entry `entryId` may be restored now, by the core's clock.
    func restorable(_ entryId: String) -> Bool { potModel?.restorable(entryId: entryId) ?? false }

    /// Restores deleted entry `entryId` (core's `PotModel.restore`, its window the core's); true once it was.
    func restoreEntry(_ entryId: String) async -> Bool {
        guard let pots = potModel else { return false }
        let done = (try? await pots.restore(entryId: entryId))?.boolValue ?? false
        syncPot()
        return done
    }

    // MARK: The Pot's settings (core's PotSettings)

    /// The open Pot's settings and people (core's `PotSettings`), one per open Pot, held here; its tab draws `settingsState`,
    /// set after each of its actions (only they change it).
    @ObservationIgnored private var potSettings: PotSettings?
    @ObservationIgnored private var potSettingsOf: String?
    private(set) var settingsState: PotSettings.State?

    /// The open Pot's settings model, made anew when another Pot is open.
    @discardableResult func openSettings() -> PotSettings? {
        guard let ref = pot?.ref, pot?.view != nil else { return nil }
        if potSettingsOf != ref.nodeId {
            potSettings = potModel?.settings()
            potSettingsOf = ref.nodeId
            settingsState = potSettings?.current
        }
        return potSettings
    }

    /// One change of the open Pot's settings or people (`op`, a `PotSettings` action); true once it was stored. A new
    /// base currency is said on the Pot's row at once.
    func settingsWrite(_ op: @escaping @MainActor (PotSettings) async throws -> KotlinBoolean) async -> Bool {
        guard let s = openSettings(), let pots = potModel, let ref = pot?.ref else { return false }
        let stored = (try? await op(s))?.boolValue ?? false
        settingsState = s.current
        syncPot()
        if let facts = pots.rowDetails() { model.setRowDetail(ref.nodeId, facts) }
        return stored
    }

    /// A person's erase asked (core's state: done only by `settingsWrite { try await $0.erase() }`).
    func askErase(_ personId: String) { openSettings()?.askErase(personId: personId); settingsState = potSettings?.current }

    func cancelErase() { potSettings?.cancelAsk(); settingsState = potSettings?.current }

    // MARK: New pot (core's PotCreateSheet)

    /// "New pot" (core's `PotCreateSheet`) open in the app, watched here (its members arrive on their own); its sheet draws
    /// `createState`.
    @ObservationIgnored private var createSheet: PotCreateSheet?
    @ObservationIgnored private var createWatch: PotCreateSheet.Watch?
    private(set) var createState: PotCreateSheet.State?

    /// Opens "New pot" at the shell's place now, in the person's language's currency.
    func openCreateSheet() {
        closeCreateSheet()
        guard let shellModel = model.shellModel, let s = potModel?.createSheet(shell: shellModel, language: Locale.current.language.languageCode?.identifier ?? "") else { return }
        createSheet = s
        createState = s.current
        createWatch = s.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.createState = self?.createSheet?.current }
        }
    }

    func createSpace(_ scopeId: String) { createSheet?.space(scopeId: scopeId); createState = createSheet?.current }
    func createCurrency(_ code: String) { createSheet?.currency(code: code); createState = createSheet?.current }
    func createAddMember(_ userId: String) { createSheet?.addMember(userId: userId); createState = createSheet?.current }
    func createRemove(_ index: Int) { createSheet?.remove(index: Int32(index)); createState = createSheet?.current }

    /// A name typed added; true when there was one (the field is emptied).
    func createAddTyped(_ text: String) -> Bool {
        let added = createSheet?.addTyped(text: text) ?? false
        createState = createSheet?.current
        return added
    }

    func canCreatePot(_ name: String) -> Bool { createSheet?.canCreate(name: name) ?? false }

    /// Creates the Pot named `name` (a person still `typed` joins first) and opens it; true once it exists.
    func createPot(name: String, typed: String) async -> Bool {
        guard let s = createSheet, let shellModel = model.shellModel else { return false }
        let created = (try? await s.create(name: name, typed: typed))?.boolValue ?? false
        createState = s.current
        if created {
            model.applyShell(start: false)
            await model.readDetails(shellModel.tenant)
        }
        return created
    }

    /// Today by the core's clock (`yyyy-MM-dd`): the editor's date picker falls back to it.
    var today: String { potModel?.today() ?? "" }

    func closeCreateSheet() {
        createWatch?.stop()
        createWatch = nil
        createSheet?.close()
        createSheet = nil
        createState = nil
    }

    // MARK: The entry editor (core's EntryEditor)

    /// The entry editor open in the app (core's `EntryEditor` over the Pot's model), watched by this holding, not by its
    /// sheet: what the sheet draws is `editorState`, set at once after each edit (a field never lags what was typed) and
    /// from the watch for what the core changes itself (the rate's fill).
    @ObservationIgnored private var editor: EntryEditor?
    @ObservationIgnored private var editorWatch: EntryEditor.Watch?
    private(set) var editorState: EntryEditor.State?

    /// Opens the editor of `entryId` in the open Pot (nil: a new entry) with `pending` as its receipts; false when the
    /// Pot cannot take one.
    func openEditor(_ entryId: String?, pending: [PotSession.PendingReceipt] = []) -> Bool {
        closeEditor()
        guard let e = potModel?.editor(entryId: entryId, separator: decimalSeparator, pending: pending) else { return false }
        adoptEditor(e)
        return true
    }

    /// `e` as the editor open in the app, watched here.
    func adoptEditor(_ e: EntryEditor) {
        editor = e
        editorState = e.current
        editorWatch = e.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.editorState = self?.editor?.current }
        }
    }

    /// The editor open in the app (a held entry's *Discard mine* calls it).
    var editorModel: EntryEditor? { editor }

    func edit(_ change: EditorChange) {
        editor?.change(change: change)
        editorState = editor?.current
    }

    func addReceipts(_ picked: [EntryEditor.Picked]) {
        editor?.addReceipts(picked: picked)
        editorState = editor?.current
    }

    func dropReceipt(_ id: String) {
        editor?.dropReceipt(id: id)
        editorState = editor?.current
    }

    /// Saves the entry: done (close the editor, saying what the answer says), or not (the editor says why).
    func saveEntry() async -> EntryEditor.Saved? {
        guard let e = editor else { return nil }
        let saved = try? await e.save()
        editorState = e.current
        return saved
    }

    func askDelete() { editor?.askDelete(); editorState = editor?.current }
    func cancelAsk() { editor?.cancelAsk(); editorState = editor?.current }

    /// Deletes the entry once asked; true when it was.
    func deleteEntry() async -> Bool {
        guard let e = editor else { return false }
        let done = (try? await e.delete())?.boolValue ?? false
        editorState = e.current
        return done
    }

    func closeEditor() {
        editorWatch?.stop()
        editorWatch = nil
        editor?.close()
        editor = nil
        editorState = nil
    }

    /// The held entry `writeId`'s editor in the open Pot (core's `PotModel.heldEditor`: the person's values over the newer
    /// version, named; *Discard mine* beside its save); false where the Pot holds no such write.
    func openHeldEditor(_ writeId: String) async -> Bool {
        closeEditor()
        guard let pots = potModel, let e = (try? await pots.heldEditor(writeId: writeId, separator: decimalSeparator)) ?? nil else { return false }
        adoptEditor(e)
        return true
    }

    /// *Discard mine* in a held entry's editor: the change made offline deleted unsent, the newer version stays; whether it was.
    func discardMine() async -> Bool {
        guard let e = editorModel else { return false }
        return (try? await e.discardMine())?.boolValue ?? false
    }

    // MARK: The Pot's download and receipts

    /// Writes the open Pot `ref` as the web's ZIP (uc.platform.export-import, ADR 0082) to `path` (core's `FileSink`, which
    /// removes a download that did not finish); nil when it did not complete.
    func downloadPot(_ ref: ObjectRef, to path: String) async -> [NodeExport.Skipped]? {
        guard let session = potSession, pot?.ref == ref else { return nil }
        let written = try? await AppleSessions.shared.downloadPot(
            session: session, deletedName: say(.potDeletedPerson), nowMs: Int64(Date().timeIntervalSince1970 * 1000), path: path
        )
        return written == nil ? nil : []
    }

    /// A receipt of the open Pot opened to its bytes, or nil (blocked, or it did not read).
    func openReceipt(_ receiptId: String) async -> Data? {
        guard let session = potSession else { return nil }
        return (try? await AppleSessions.shared.openReceipt(pot: session, receiptId: receiptId)) ?? nil
    }

    /// Why the open Pot's receipt did not open (core's `PotModel.receiptFailure`): not kept offline, or unreadable.
    func receiptFailure() -> Msg { potModel?.receiptFailure() ?? .receiptsUnreadable }

    /// Forgets the open Pot's last refusal (a sheet closed).
    func clearPotMessage() { potModel?.clearMessage(); syncPot() }

    // MARK: A share's files into a new expense (ADR 0138)

    /// The waiting files, taken by the Pot opened as their place: its new expense's receipts (each read whole, within its
    /// budget), and how many it left out.
    func takeShare() async -> PotShare.Taken? {
        let taken = try? await PotShare.shared.take(inbox: model.inbox, newId: { UUID().uuidString.lowercased() })
        model.syncInbox()
        if let taken, taken.refused > 0 { model.notice = AppModel.Notice(text: say(.shareRefused, taken.refused)) }
        return taken
    }

    // MARK: A link's Pot (ADR 0150 decision 5)

    /// The member's Pot while a link's Pot is shown in its place (its page and sheets read `potModel`).
    @ObservationIgnored private var memberPot: (model: PotModel?, watch: PotModel.Watch?)?

    func adopt(_ page: GuestPage) -> Bool {
        guard let p = page as? PotGuest.Pot else { return false }
        if memberPot == nil { memberPot = (potModel, potWatch) } else { potWatch?.stop() }
        potModel = p.model
        potWatch = p.model.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.syncPot() }
        }
        pot = nil
        syncPot()
        return true
    }

    func release(_ page: GuestPage) -> Bool {
        guard let p = page as? PotGuest.Pot else { return false }
        if potModel === p.model, let member = memberPot {
            potWatch?.stop()
            potModel = member.model
            potWatch = member.watch
            memberPot = nil
            syncPot()
        }
        p.model.close(ref: nil)
        return true
    }
}
