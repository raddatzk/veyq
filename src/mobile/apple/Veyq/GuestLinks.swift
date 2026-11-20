import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import VeyqCore

// MARK: The app's received links (ADR 0150 decisions 3–5)

/// *Open a link*'s field (`asking`) — on the sign-in and the unlock (owner 2026-11-19: not in the shell) — and the one link open as its guest
/// (`screen`), a screen in the place of whatever was on screen: the sign-in or the shell, which stay as they were in the app
/// model and are shown again once the link is left. Every guest view of the app — opened here, by a bookmark's page or its
/// *Open on its own page* — is made by one `GuestOpener` over the app's group defaults (the terms' and the note's
/// acceptances), the Keychain (a write link's guest session, `KeychainGuestSessions`) and the app's one upload queue.
/// Nothing of a link is kept here once it is left: the core's model holds the key and zeroes it (`GuestScreen.leave`).
@MainActor
@Observable
final class GuestLinks {
    private(set) var asking: AskedLink?
    private(set) var screen: GuestHeld?
    @ObservationIgnored private var made: GuestOpener?

    /// The opener every guest view of this run is made by; a guest's Memory plays its music with the app's player (#1034).
    func opener(_ model: AppModel) -> GuestOpener {
        if let made {
            made.music = model.guestMusic()
            return made
        }
        let o = AppleSharing.shared.opener(
            store: AppleSharing.shared.store(defaults: Shared.defaults),
            sessions: AppleSharing.shared.sessions(keychain: KeychainStore(movesLegacy: false)),
            language: Locale.current.language.languageCode?.identifier ?? "en",
            queue: model.uploads, deriver: MediaDeriver.shared
        )
        o.music = model.guestMusic()
        made = o
        return o
    }

    /// *Open a link* asked: its field, empty.
    func ask(_ model: AppModel) {
        asking?.stop()
        asking = AskedLink(opener(model).field())
    }

    func cancelAsk() {
        asking?.stop()
        asking = nil
    }

    /// The field's link opened as its guest (the field closes; its text, which holds the key, is not kept).
    func openAsked(_ model: AppModel) {
        guard let field = asking, let s = field.link.open(tenant: model.guestTenant) else { return }
        cancelAsk()
        show(s, model)
    }

    /// `s` shown in the place of the sign-in or the shell, and loaded; a link open before is let go.
    func show(_ s: GuestScreen, _ model: AppModel) {
        screen?.close()
        screen = GuestHeld(s, model: model)
        Task { _ = try? await AppleSharing.shared.load(screen: s) }
    }

    /// The open link let go (its question answered, or the person signed out under it): the screen goes.
    func closed(_ held: GuestHeld) {
        held.close()
        if screen === held { screen = nil }
    }
}

/// *Open a link*'s field while it is open: core's `OpenLink` and its state on the main actor.
@MainActor
final class AskedLink: Identifiable {
    let link: AppleOpenLink
    let state: CoreState<OpenLink.State>

    init(_ link: AppleOpenLink) {
        self.link = link
        let field = link.field
        state = CoreState({ field.state.value as! OpenLink.State }) { changed in
            AppleMemories.shared.watch(flow: field.state) { @Sendable _ in changed() }
        }
    }

    /// The text as typed or pasted, read back at once (a field reads core right after its edit, § Traps).
    func text(_ value: String) {
        link.field.text(value: value)
        state.refresh()
    }

    func stop() { state.stop() }
}

/// One link open as its guest on screen (core's `GuestScreen`): its states on the main actor, and the page behind its gates
/// handed to the app model before a view draws it — the member's page of each kind reads its model through the app model
/// (`galleryModel`, `fileModel`, `pot`), so the guest's model is held there while its page shows, and let go after.
@MainActor
@Observable
final class GuestHeld: Identifiable {
    let screen: GuestScreen
    @ObservationIgnored private weak var model: AppModel?
    let state: CoreState<GuestScreen.State>
    let gate: CoreState<ShareGates.State>
    let busy: CoreState<KotlinBoolean>
    /// The guest's session of this link on this device (a write link's identity).
    let guest: CoreState<GuestSession?>
    /// The page behind the gates, once the app model holds its model; nil before.
    private(set) var page: GuestPage?
    /// How deep inside the link the page on screen is (1: its root).
    private(set) var depth = 0
    @ObservationIgnored private var pageWatch: AppleMemories.Watch?
    @ObservationIgnored private var closed = false

    init(_ screen: GuestScreen, model: AppModel) {
        self.screen = screen
        self.model = model
        let view = screen.view
        state = CoreState({ screen.state.value as! GuestScreen.State }) { changed in
            AppleMemories.shared.watch(flow: screen.state) { @Sendable _ in changed() }
        }
        gate = CoreState({ view.gates.state.value as! ShareGates.State }) { changed in
            AppleMemories.shared.watch(flow: view.gates.state) { @Sendable _ in changed() }
        }
        busy = CoreState({ view.gates.busy.value as! KotlinBoolean }) { changed in
            AppleMemories.shared.watch(flow: view.gates.busy) { @Sendable _ in changed() }
        }
        guest = CoreState({ view.gates.guest.value as? GuestSession }) { changed in
            AppleMemories.shared.watch(flow: view.gates.guest) { @Sendable _ in changed() }
        }
        pageWatch = AppleMemories.shared.watch(flow: view.page) { @Sendable _ in
            Task { @MainActor [weak self] in self?.pageChanged() }
        }
    }

    /// The link's host (the header names the root once the gates passed).
    var host: String { screen.host }
    var access: GuestAccess? { screen.view.access }

    private func pageChanged() {
        guard !closed else { return }
        let next = screen.view.page.value as? GuestPage
        if next === page { return }
        if let page { model?.releaseGuestPage(page) }
        if let next { model?.adoptGuestPage(next) }
        page = next
        depth = (screen.view.stack.value as? [Any])?.count ?? 0
    }

    /// The link let go: its page's model goes, its key is zeroed (`GuestScreen.leave`).
    func close() {
        guard !closed else { return }
        closed = true
        pageWatch?.stop()
        if let page { model?.releaseGuestPage(page) }
        page = nil
        if !state.value.left { screen.leave() }
        state.stop()
        gate.stop()
        busy.stop()
        guest.stop()
    }

    func back() { Task { _ = try? await AppleSharing.shared.back(screen: screen) } }
    func open(_ nodeId: String) { Task { _ = try? await AppleSharing.shared.open(screen: screen, nodeId: nodeId) } }
    func enter() { Task { _ = try? await AppleSharing.shared.enter(screen: screen) } }
}

// MARK: Open a link

/// *Open a link* (ADR 0150 decisions 4 and 8): the received link typed or pasted — the system's `PasteButton`, which reads
/// the pasteboard on its tap only and so asks nothing —, checked as it is typed (core's `OpenLink`), opened as its guest, or
/// refused with its reason and, for a moderator's review, *Open in the browser* (the link as received, handed to the
/// system's browser). Covered while the scene is not active: the pasted link holds its key (`secretOnScreen`).
struct OpenLinkSheet: View {
    let model: AppModel
    let asked: AskedLink
    @Environment(\.openURL) private var openURL

    private var state: OpenLink.State { asked.state.value }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    // a prompt gives an empty field on a Mac's grouped form its click target (#1030)
                    TextField(say(.linkPasteHint), text: Binding(get: { state.text }, set: { asked.text($0) }), prompt: Text(say(.linkPasteHint)))
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        #endif
                        .onSubmit { if state.canOpen { model.guests.openAsked(model) } }
                        .accessibilityIdentifier("open-link-field")
                    PasteButton(payloadType: String.self) { strings in
                        guard let text = strings.first else { return }
                        Task { @MainActor in asked.text(text) }
                    }
                    .labelStyle(.titleAndIcon)
                } footer: {
                    if let problem = state.problem { Text(say(problem)).foregroundStyle(Role.danger) }
                }
                if let link = state.browser, let url = URL(string: link) {
                    Section {
                        Button { openURL(url) } label: { Label(say(.linkOpenInBrowser), systemImage: "safari") }
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.linkOpen))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.entryCancel)) { model.guests.cancelAsk() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.linkOpen)) { model.guests.openAsked(model) }
                        .disabled(!state.canOpen)
                        .accessibilityIdentifier("open-link-open")
                }
            }
        }
        .secretOnScreen()
        #if os(macOS)
        // a Mac's sheet takes its content's ideal size, which a Form has none of
        .frame(minWidth: 460, minHeight: 260)
        #endif
    }
}

extension View {
    /// *Open a link*'s sheet over this view while its field is open.
    func openLinkSheet(_ model: AppModel) -> some View {
        sheet(item: Binding(get: { model.guests.asking }, set: { if $0 == nil { model.guests.cancelAsk() } })) { asked in
            OpenLinkSheet(model: model, asked: asked)
        }
    }
}

// MARK: The guest's screen

/// The link open as its guest (ADR 0150 decision 4): a screen of its own in the place of the sign-in or the shell — its own
/// navigation bar with the page's name and the link's host under it, *Back* inside the link —, no drawer and no bar. *Back*
/// at its root asks first (the key lives only in memory: leaving drops the link unless it was kept) and then returns where
/// the person was. Its actions: a file's download and a named READ_WRITE guest's replace, *Keep* for a signed-in person, and
/// *Report* to the share's own operator.
struct GuestLinkView: View {
    @Bindable var model: AppModel
    let held: GuestHeld
    @State private var keeping = false
    @State private var reporting = false
    /// Whether the link's instance takes reports (it publishes a moderation key).
    @State private var reportOffered = false

    private var ready: Bool { held.gate.value is ShareGates.StateReady }
    private var title: String { ready ? (held.page?.node.name ?? held.host) : held.host }

    var body: some View {
        NavigationStack {
            GuestContent(model: model, held: held)
                // the music bar while a queue plays, as under the shell (a guest's Memory's music, #1034)
                .safeAreaInset(edge: .bottom, spacing: 0) { MusicBar(model: model) }
                .navigationTitle(title)
                .navigationSubtitle(ready ? held.host : "")
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar {
                    ToolbarItem(placement: .navigation) {
                        Button { held.back() } label: { Image(systemName: "chevron.backward") }
                            .accessibilityLabel(say(.back))
                            .accessibilityIdentifier("guest-back")
                    }
                    ToolbarItemGroup(placement: .automatic) {
                        if let file = held.page as? GuestPage.File { GuestFileActions(model: model, held: held, file: file.model) }
                        // a Memory's music played or stopped, as the member's header offers it (#1034)
                        if let g = held.page as? GuestPage.Gallery, let gallery = model.galleryModel(g.node), let state = model.galleryState(g.node),
                           gallery.headerActions.contains(where: { $0.id == GalleryModel.companion.MUSIC }) {
                            Button { gallery.toggleMusic() } label: {
                                Label(gallery.label(id: GalleryModel.companion.MUSIC).map { say($0) } ?? "", systemImage: state.musicHere ? "stop.fill" : "music.note")
                            }
                            .accessibilityIdentifier("guest-music")
                        }
                        if ready, held.screen.keepOffered {
                            Button { keeping = true } label: { Label(say(.linkKeep), systemImage: "bookmark") }
                                .accessibilityIdentifier("guest-keep")
                        }
                        // an upload link's guest reports the link; a viewing guest the page on screen (#1033) — while the
                        // link's instance takes reports
                        if ready, reportOffered {
                            Button { reporting = true } label: { Label(say(.reportAction), systemImage: "flag") }
                                .accessibilityIdentifier("guest-report")
                        }
                    }
                }
        }
        .background(Role.bg)
        .tint(Role.accent)
        // the question at the root: a binding that ignores its dismissal (§ Traps), each button answers it
        .alert(say(.linkLeaveTitle), isPresented: Binding(get: { held.state.value.leaving }, set: { _ in })) {
            Button(say(.linkLeaveAccept), role: .destructive) { held.screen.leave() }
            Button(say(.entryCancel), role: .cancel) { held.screen.stay() }
        } message: {
            Text(say(.linkLeaveBody))
        }
        .onChange(of: held.state.value.left) { _, left in if left { model.guests.closed(held) } }
        // the report to the share's own operator: asked by the link (`&report`, a bookmark's) or by its action
        .sheet(isPresented: Binding(get: { reporting || held.state.value.reporting }, set: { if !$0 { reporting = false; held.screen.reportShown() } })) {
            if let node = held.page?.node {
                ReportSheet(model: model, ref: node, make: { held.screen.reportSheet() })
            }
        }
        .sheet(isPresented: $keeping) { KeepSheet(model: model, held: held) }
        .task(id: ready) {
            guard ready, let access = held.access else { return }
            reportOffered = (try? await GuestReport.shared.offered(access: access))?.boolValue ?? false
        }
    }
}

/// The link behind its gates (ADR 0150 decision 2; the web's share page): the instance, the password, the terms, the note,
/// each before any content, then the page of the node on screen as its kind's member page with a guest's rights. Drawn on
/// the link's own screen and inside a bookmark's page (in place).
struct GuestContent: View {
    @Bindable var model: AppModel
    let held: GuestHeld
    @Environment(\.openURL) private var openURL

    var body: some View {
        Group {
            switch held.gate.value {
            case let g as ShareGates.StateRefused:
                GateColumn {
                    Text(refusal(g.refusal)).accessibilityIdentifier("guest-refused")
                    if let link = held.screen.browserLink, let url = URL(string: link) {
                        Button { openURL(url) } label: { Label(say(.linkOpenInBrowser), systemImage: "safari") }
                    }
                }
            case let g as ShareGates.StateLocked:
                PasswordGate(held: held, wrong: g.wrong)
            case let g as ShareGates.StateTerms:
                GateColumn {
                    Text(say(.shareTermsTitle)).font(.title2.bold())
                    Text(say(.shareTermsIntro)).foregroundStyle(Role.muted)
                    if !g.markdown.isEmpty { MarkdownText(markdown: g.markdown) }
                    if !g.operatorSection.isEmpty {
                        Divider()
                        MarkdownText(markdown: g.operatorSection)
                    }
                    if !g.record.isEmpty { MarkdownText(markdown: g.record) }
                    PrimaryButton(title: say(.shareAcceptContinue)) {
                        held.screen.view.gates.acceptTerms()
                        held.enter()
                    }
                    .accessibilityIdentifier("guest-accept-terms")
                }
            case let g as ShareGates.StateNote:
                GateColumn {
                    Text(say(.shareNoteTitle)).font(.title2.bold())
                    Text(say(.shareNoteIntro)).foregroundStyle(Role.muted)
                    MarkdownText(markdown: g.markdown)
                    PrimaryButton(title: say(.shareAcceptContinue)) {
                        held.screen.view.gates.acceptNote()
                        held.enter()
                    }
                    .accessibilityIdentifier("guest-accept-note")
                }
            case is ShareGates.StateUnavailable:
                GateColumn {
                    Text(say(.shareUnavailable)).font(.title2.bold()).accessibilityIdentifier("guest-unavailable")
                    Text(say(.shareUnavailableHint)).foregroundStyle(Role.muted)
                }
            case let g as ShareGates.StateReady:
                VStack(alignment: .leading, spacing: 0) {
                    if !g.note.isEmpty { MarkdownText(markdown: g.note).padding(.horizontal).padding(.vertical, 8) }
                    // a READ_WRITE link's guest: who this device is here as, or the way to name itself before its first write
                    // (where the core offers its naming, `GuestNaming.offered`; no role read here)
                    if let access = held.access, access.naming.offered {
                        GuestLine(held: held, access: access).padding(.horizontal).padding(.vertical, 4)
                    }
                    if let page = held.page {
                        GuestPageBody(model: model, held: held, page: page).id(ObjectIdentifier(page))
                            .modifier(OptionalNaming(access: held.access, active: model.editorState == nil))
                    } else {
                        ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
            default:
                VStack(spacing: 12) {
                    ProgressView()
                    Text(say(.shareLoading)).foregroundStyle(Role.muted)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Role.bg)
        // the guest's pages are the link's: no cover of the shell's page is placed above them
        .environment(\.nodeHero, nil)
    }

    private func refusal(_ r: ShareInstance.Refusal) -> String {
        switch r {
        case .unreachable: say(.linkRefusedUnreachable, held.host)
        case .otherVersion: say(.linkRefusedOtherVersion, held.host)
        default: say(.linkRefusedNoLinks, held.host)
        }
    }
}

/// One node's page behind the gates: the member's page of its kind (share view = member view), a guest's rights.
private struct GuestPageBody: View {
    @Bindable var model: AppModel
    let held: GuestHeld
    let page: GuestPage

    var body: some View {
        switch page {
        case let level as GuestPage.Level:
            GuestLevelView(model: model, held: held, level: level.model)
        case let file as GuestPage.File:
            FileScreen(model: model, ref: Self.ref(file.node, .depot))
        case let gallery as GuestPage.Gallery:
            MemoryScreen(model: model, ref: Self.ref(gallery.node, .memories))
                .environment(\.guestGallery, GuestGallery(canAdd: held.guest.value != nil) { c in held.open(c.nodeId) })
        case let pot as GuestPage.Pot:
            GuestPotView(model: model, ref: Self.ref(pot.node, .expenses))
        case let drop as GuestPage.DropBox:
            GuestDropBoxView(model: model, held: held, drop: drop.model)
        case let up as GuestPage.Uploader:
            GuestUploaderView(model: model, held: held, up: up.model)
        default:
            Text(say(.shareNotShown)).foregroundStyle(Role.muted).padding()
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .accessibilityIdentifier("guest-not-shown")
        }
    }

    /// The guest's node as the member's page takes it.
    static func ref(_ node: NodeAt, _ app: ApplicationId) -> ObjectRef {
        ObjectRef(scopeId: node.scopeId, nodeId: node.nodeId, kind: node.kind, application: app, name: node.name)
    }
}

/// A folder of the link (core's level over the guest's tree): its rows, each opened as its kind's page, and — for a named
/// READ_WRITE guest outside an identity folder (`GuestLevel.writes`, #1034; the web's `ShareTreePage`) — the level's New:
/// a folder, files uploaded here. The link is read anew after each write, so what was added is listed.
private struct GuestLevelView: View {
    @Bindable var model: AppModel
    let held: GuestHeld
    let level: GuestLevel
    @State private var naming = false
    @State private var newName = ""
    @State private var nameProblem: String?
    @State private var pickingFiles = false
    @State private var said: String?

    var body: some View {
        let rows = level.rows
        Group {
            if rows.isEmpty {
                Text(say(.listEmpty)).foregroundStyle(Role.muted).padding()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            } else {
                List(rows, id: \.nodeId) { r in
                    Button { held.open(r.nodeId) } label: {
                        HStack(spacing: 12) {
                            Image(systemName: r.kind == "FOLDER" ? "folder" : Names.kindSymbol(r.kind)).foregroundStyle(Role.accent).frame(width: 28)
                            Text(r.name).foregroundStyle(Role.text)
                            Spacer(minLength: 0)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .scrollContentBackground(.hidden)
                .accessibilityIdentifier("guest-level")
            }
        }
        // what a guest adds here: a folder and files (Depot's level action); a guest makes no Memory, Pot or drop box
        // offered by the core alone (`GuestLevel.writes`): a guest not named yet is asked its name at the write (`naming`)
        .createMenu(say(.drawerNew), shown: level.writes) {
            ForEach([AppKinds.Create.folder, AppKinds.Create.uploadFiles], id: \.self) { c in
                Button(say(c.word), systemImage: Names.createSymbol(c)) {
                    if c == .folder { newName = ""; nameProblem = nil; naming = true } else { pickingFiles = true }
                }
            }
        }
        .fileImporter(isPresented: $pickingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            let sources = urls.compactMap(model.guestSource)
            Task {
                // the core asks a guest not named yet its name first (`GuestLevel.uploadAsking`); declined, none is queued
                let queued = (try? await level.uploadAsking(sources: sources))?.int32Value ?? 0
                if queued == 0 { sources.forEach { $0.close() } }
            }
        }
        .folderNaming($naming, name: $newName, problem: $nameProblem) { name in
            nameAnswered(try? await AppleSharing.shared.createFolder(level: level, name: name)) { said = $0 }
        }
        .alert(said ?? "", isPresented: Binding(get: { said != nil }, set: { if !$0 { said = nil } })) {
            Button(say(.ok), role: .cancel) {}
        }
    }
}

/// A Pot of a link: the member's Pot page over the guest's Pot (the app model reads and writes through it while it shows),
/// its Entries, Overview and — where the core offers them (`PotView.settingsOffered`: a guest who writes, #1035) —
/// Settings as a segmented control of its own; the guest's screen has no bar.
private struct GuestPotView: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    @State private var chosen = "expenses.entries"

    /// The places offered now: Settings once the core offers them.
    private var places: [String] {
        let settings = model.pot.map { $0.ref == ref && $0.view?.settingsOffered == true } ?? false
        return ["expenses.entries", "expenses.overview"] + (settings ? ["expenses.settings"] : [])
    }

    private func word(_ key: String) -> String {
        switch key {
        case "expenses.overview": say(.tabExpensesOverview)
        case "expenses.settings": say(.tabExpensesSettings)
        default: say(.tabExpensesEntries)
        }
    }

    var body: some View {
        let place = places.contains(chosen) ? chosen : "expenses.entries"
        VStack(spacing: 0) {
            Picker(say(.tabExpensesEntries), selection: $chosen) {
                ForEach(places, id: \.self) { Text(word($0)).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .padding()
            PotScreen(model: model, ref: ref, tab: place)
        }
    }
}

/// A file's own actions on the link's bar: its download (a copy where the person picks) and a named READ_WRITE guest's
/// replace (core's `FileModel` header decides whether it is offered).
private struct GuestFileActions: View {
    @Bindable var model: AppModel
    let held: GuestHeld
    let file: FileModel
    @State private var replacing = false
    @State private var downloaded: URL?
    @State private var failed: String?

    private var header: FileModel.Header? { model.fileState(file.node)?.header }

    var body: some View {
        if let h = header {
            Button { Task { await download(h) } } label: { Label(say(.nodeDownload), systemImage: "arrow.down.circle") }
                .fileMover(isPresented: Binding(get: { downloaded != nil }, set: { if !$0 { cleanUp() } }), file: downloaded) { _ in cleanUp() }
                .alert(failed ?? "", isPresented: Binding(get: { failed != nil }, set: { if !$0 { failed = nil } })) {
                    Button(say(.ok), role: .cancel) {}
                }
            // Replace where the file's rights hold it (`FileRights`, the header's own): a guest not named yet is asked its
            // name at the write by the core (`GuestAccess.replaceAsking`), never here
            if h.own.contains(where: { $0.id == FileModel.companion.REPLACE }) {
                Button { if file.chooseReplace() { replacing = true } } label: {
                    Label(h.label(id: FileModel.companion.REPLACE).map { say($0) } ?? "", systemImage: "square.and.arrow.down.on.square")
                }
                .fileImporter(isPresented: $replacing, allowedContentTypes: [.item]) { result in
                    if case .success(let url) = result, let access = held.screen.view.access { model.guestReplace(access, file: file, url: url) }
                }
            }
        }
    }

    /// The file under its download name: a Mac asks where in its save panel, iOS writes into the app's downloads directory
    /// and hands it to the save sheet (as a member's download, `NodeHeaderActions`).
    private func download(_ h: FileModel.Header) async {
        #if os(macOS)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = h.downloadName
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let end = try? await AppleSharing.shared.saveFile(file: file, path: url.path)
        if end != .saved { failed = say(.fileOpenFailed) }
        #else
        guard let dir = AppModel.downloadDir() else { return }
        let url = dir.appendingPathComponent(h.downloadName)
        let end = try? await AppleSharing.shared.saveFile(file: file, path: url.path)
        if end == .saved {
            downloaded = url
        } else {
            try? FileManager.default.removeItem(at: dir)
            failed = say(.fileOpenFailed)
        }
        #endif
    }

    private func cleanUp() {
        if let url = downloaded { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        downloaded = nil
    }
}

// MARK: The gates

/// A gate's column: a form's width, centred, scrolled (the sign-in's measure).
private struct GateColumn<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) { content() }
                .frame(maxWidth: 560, alignment: .leading)
                .padding(24)
                .frame(maxWidth: .infinity)
        }
    }
}

/// A gate's or a page's one confirming action: the web's green role (`--app-primary`).
private struct PrimaryButton: View {
    let title: String
    var working = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group { if working { ProgressView() } else { Text(title) } }.frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.roundedRectangle(radius: Radius.control))
        .controlSize(.large)
        .tint(Role.primary)
        .foregroundStyle(Role.onFill)
    }
}

/// The password a link asks for: masked, its token derived off the main thread, a wrong one said under the field.
private struct PasswordGate: View {
    let held: GuestHeld
    let wrong: Bool
    @State private var password = ""

    private var busy: Bool { held.busy.value.boolValue }

    var body: some View {
        GateColumn {
            Text(say(.sharePasswordTitle)).font(.title2.bold())
            Text(say(.sharePasswordIntro)).foregroundStyle(Role.muted)
            SecureField(say(.sharePasswordLabel), text: $password, prompt: Text(say(.sharePasswordLabel)))
                .textContentType(.password)
                .textFieldStyle(.roundedBorder)
                .onSubmit(unlock)
                .accessibilityIdentifier("guest-password")
            if wrong { Text(say(.sharePasswordWrong)).foregroundStyle(Role.danger) }
            PrimaryButton(title: say(.sharePasswordUnlock), working: busy, action: unlock)
                .disabled(password.isEmpty || busy)
                .accessibilityIdentifier("guest-unlock")
        }
    }

    private func unlock() {
        guard !password.isEmpty, !busy else { return }
        let typed = password
        Task { _ = try? await AppleSharing.shared.unlock(screen: held.screen, password: typed) }
    }
}

/// A text the share's owner or its operator wrote in Markdown (the terms, the note): its paragraphs, a heading's line bold, a
/// list's items with a bullet — shown, never followed (no link in it is opened from here), selectable.
struct MarkdownText: View {
    let markdown: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(Self.blocks(markdown).enumerated()), id: \.offset) { _, block in
                if block.hasPrefix("#") {
                    Text(block.drop(while: { $0 == "#" }).trimmingCharacters(in: .whitespaces)).bold()
                } else {
                    Text(block)
                }
            }
        }
        .textSelection(.enabled)
        .foregroundStyle(Role.text)
    }

    static func blocks(_ markdown: String) -> [String] {
        markdown.trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: "\n\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .map { block in
                if block.hasPrefix("#") { return block }
                return block.split(separator: "\n", omittingEmptySubsequences: false).map { line -> String in
                    let l = line.trimmingCharacters(in: .whitespaces)
                    return (l.hasPrefix("- ") || l.hasPrefix("* ")) ? "• " + l.dropFirst(2) : l
                }.joined(separator: "\n").replacingOccurrences(of: "**", with: "")
            }
    }
}

// MARK: A guest's naming and the record of its writes (#1018)

/// Whose session this device holds on a READ_WRITE link (the web's `GuestNaming`): "You are here as …" with *Not you?*, or
/// the way to name itself, which a guest's first write needs; the record kept of a guest's writes said in both (#1018).
private struct GuestLine: View {
    let held: GuestHeld
    let access: GuestAccess
    @State private var naming = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let named = held.guest.value {
                NotYou(session: named) { access.forgetGuest() }
            } else {
                Button(say(.shareGuestNameTitle)) { naming = true }.accessibilityIdentifier("guest-name-ask")
            }
            RecordNotice(days: access.guestWriteRecordDays?.intValue, origin: access.origin, guestId: held.guest.value?.identityId)
        }
        .sheet(isPresented: $naming) {
            NamingSheet(title: say(.shareGuestNameTitle), hint: say(.shareGuestNameHint), days: access.guestWriteRecordDays?.intValue, origin: access.origin) { name in
                ((try? await AppleSharing.shared.nameGuest(access: access, displayName: name))?.boolValue ?? false) ? nil : say(.shareJoinFailed)
            }
        }
    }
}

/// The name a write asks of a READ_WRITE link's guest not named yet (core's one naming flow, `GuestAccess.naming`; the
/// web's `requestGuest`): shown while the core asks (`asking`), its save core's (`name`: a problem keeps it open, a notice
/// is said once it closed), its cancel the write declined (`decline`).
struct AskedNaming: ViewModifier {
    let access: GuestAccess
    /// False while a sheet above this view asks instead (an entry editor's save, `EntryEditorSheet`).
    var active = true
    @State private var asking: CoreState<KotlinBoolean>?
    @State private var said: String?

    func body(content: Content) -> some View {
        let naming = access.naming
        content
            .sheet(isPresented: Binding(get: { active && asking?.value.boolValue == true }, set: { if !$0 { Task { try? await naming.decline() } } })) {
                NamingSheet(
                    title: say(.shareGuestNameTitle), hint: say(.shareGuestNameHint), days: access.guestWriteRecordDays?.intValue,
                    origin: access.origin, onCancel: { Task { try? await naming.decline() } }
                ) { name in
                    nameAnswered(try? await naming.name(displayName: name)) { said = $0 }
                }
            }
            .alert(said ?? "", isPresented: Binding(get: { said != nil }, set: { if !$0 { said = nil } })) {
                Button(say(.ok), role: .cancel) {}
            }
            .task(id: ObjectIdentifier(access)) {
                asking?.stop()
                asking = CoreState({ naming.asking.value as! KotlinBoolean }) { changed in
                    AppleMemories.shared.watch(flow: naming.asking) { @Sendable _ in changed() }
                }
            }
            .onDisappear { asking?.stop() }
    }
}

/// `AskedNaming` where the link has a guest's access.
struct OptionalNaming: ViewModifier {
    let access: GuestAccess?
    var active = true

    func body(content: Content) -> some View {
        if let access { content.modifier(AskedNaming(access: access, active: active)) } else { content }
    }
}

/// "You are here as …" and *Not you?*, which asks first and then forgets this device's session (the web's words).
private struct NotYou: View {
    let session: GuestSession
    let onForget: () -> Void
    @State private var asking = false

    var body: some View {
        HStack {
            Text(session.displayName.map { say(.shareGuestAs, $0) } ?? say(.shareGuestAsUnnamed)).accessibilityIdentifier("guest-as")
            Button(say(.shareGuestNotYou)) { asking = true }.buttonStyle(.borderless)
        }
        .alert(say(.shareGuestNotYouTitle), isPresented: Binding(get: { asking }, set: { _ in })) {
            Button(say(.shareGuestNotYouAccept), role: .destructive) { asking = false; onForget() }
            Button(say(.entryCancel), role: .cancel) { asking = false }
        } message: {
            Text(session.displayName.map { say(.shareGuestNotYouConfirm, $0) } ?? say(.shareGuestNotYouConfirmUnnamed))
        }
    }
}

/// The guest-write record's notice (ADR 0093 § 10, GDPR Art. 13; the web's `GuestWriteRecordNotice`): before a guest's first
/// write and beside its session, what the share's instance keeps of each write and for how long (`days`, the N its resolve
/// served), the right to object, and its privacy notice's passage, opened in the browser; with a session, the guest id to
/// name in an objection. `days` nil (nothing recorded) shows nothing.
struct RecordNotice: View {
    let days: Int?
    let origin: String
    var guestId: String?
    @Environment(\.openURL) private var openURL

    var body: some View {
        if let days {
            VStack(alignment: .leading, spacing: 4) {
                Text(say(.shareGuestRecordKept, days) + " " + say(.shareGuestRecordObjection))
                if let url = URL(string: origin + "/privacy#guest-write-record") {
                    Button(say(.shareGuestRecordMore)) { openURL(url) }.buttonStyle(.borderless)
                }
                if let guestId { Text(say(.shareGuestId) + " " + guestId).textSelection(.enabled) }
            }
            .font(.footnote)
            .foregroundStyle(Role.muted)
            .accessibilityIdentifier("guest-record-notice")
        }
    }
}

/// A guest naming itself (the web's naming dialog): its name, who sees it, the record's notice, *Continue*.
private struct NamingSheet: View {
    let title: String
    let hint: String
    let days: Int?
    let origin: String
    /// The sheet's cancel, beside its dismissal (a write that asked declines).
    var onCancel: () -> Void = {}
    /// The save: the problem's words to show under the field (the sheet stays), nil once it may close.
    let onName: (String) async -> String?
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var working = false
    @State private var failed: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(hint)
                    TextField(say(.shareYourName), text: $name, prompt: Text(say(.shareYourNamePlaceholder)))
                        .onChange(of: name) { failed = nil }
                        .accessibilityIdentifier("guest-name-field")
                } footer: {
                    Text(say(.shareNameVisibility))
                }
                Section { RecordNotice(days: days, origin: origin) }
                if let failed { Section { Text(failed).foregroundStyle(Role.danger) } }
            }
            .formStyle(.grouped)
            .navigationTitle(title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.entryCancel)) { onCancel(); dismiss() }.disabled(working) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.shareJoin)) {
                        working = true
                        let typed = name
                        Task {
                            let problem = await onName(typed)
                            working = false
                            if let problem { failed = problem } else { dismiss() }
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || working)
                    .accessibilityIdentifier("guest-name-continue")
                }
            }
        }
        .interactiveDismissDisabled(working)
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 380)
        #endif
    }
}

/// A drop page's naming in its body (the web's drop forms): the field, its hint, the record's notice, *Continue*.
private struct InlineNaming: View {
    let hint: String
    let days: Int?
    let origin: String
    let onName: (String) async -> Bool
    @State private var name = ""
    @State private var working = false
    @State private var failed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField(say(.shareYourName), text: $name, prompt: Text(say(.shareYourNamePlaceholder)))
                .textFieldStyle(.roundedBorder)
                .onChange(of: name) { failed = false }
                .accessibilityIdentifier("guest-name-field")
            Text(hint).font(.footnote).foregroundStyle(Role.muted)
            RecordNotice(days: days, origin: origin)
            if failed { Text(say(.shareJoinFailed)).foregroundStyle(Role.danger) }
            PrimaryButton(title: say(.shareJoin), working: working) {
                working = true
                let typed = name.trimmingCharacters(in: .whitespaces)
                Task {
                    failed = !(await onName(typed))
                    working = false
                }
            }
            .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || working)
            .accessibilityIdentifier("guest-name-continue")
        }
    }
}

// MARK: The WRITE_ONLY pages

/// A drop box's WRITE_ONLY link (the web's `DropBoxShareBody`): the guest names itself — the record's notice before —, then
/// sends files, each into its own folder; what it sent while the link is open here is listed, nothing else of the drop box.
private struct GuestDropBoxView: View {
    @Bindable var model: AppModel
    let held: GuestHeld
    let drop: GuestDropBox
    @State private var sent: CoreState<NSArray>?
    @State private var queue: CoreState<UploadQueue.State>?
    @State private var picking = false

    private var days: Int? { held.access?.guestWriteRecordDays?.intValue }
    private var origin: String { held.access?.origin ?? "" }

    var body: some View {
        GateColumn {
            Text(say(.shareDropboxTitle)).font(.title2.bold())
            Text(say(.shareDropboxHint, drop.node.name)).foregroundStyle(Role.muted)
            if let named = held.guest.value {
                NotYou(session: named) { drop.forget() }
                RecordNotice(days: days, origin: origin, guestId: named.identityId)
                PrimaryButton(title: say(.shareDropboxPick)) { picking = true }.accessibilityIdentifier("guest-drop-pick")
                let mine = (queue?.value.batches ?? []).filter { $0.label == drop.node.name }.flatMap { $0.items }
                let names = (sent?.value as? [String]) ?? []
                if !names.isEmpty || !mine.isEmpty {
                    Text(say(.shareDropboxSession)).font(.headline).padding(.top, 8)
                    ForEach(mine, id: \.id) { item in Text(item.name + " · " + status(item)) }
                    ForEach(names.filter { n in !mine.contains { $0.name == n } }, id: \.self) { n in
                        Text(n).accessibilityIdentifier("guest-drop-sent")
                    }
                }
            } else {
                InlineNaming(hint: say(.shareDropboxNameHint), days: days, origin: origin) { name in
                    (try? await AppleSharing.shared.nameDrop(drop: drop, displayName: name))?.boolValue ?? false
                }
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            _ = drop.upload(sources: urls.compactMap(model.guestSource))
        }
        .onAppear {
            sent = CoreState({ (drop.sent.value as? NSArray) ?? [] }) { changed in AppleMemories.shared.watch(flow: drop.sent) { @Sendable _ in changed() } }
            let uploads = model.uploads
            queue = CoreState({ uploads.state.value as! UploadQueue.State }) { changed in AppleMemories.shared.watch(flow: uploads.state) { @Sendable _ in changed() } }
        }
        .onDisappear {
            sent?.stop()
            queue?.stop()
        }
    }

    private func status(_ item: UploadQueue.Item) -> String {
        if item.active { return say(.shareDropboxSending) }
        return item.status == .failed ? say(.shareDropboxFailed) : say(.shareDropboxSent)
    }
}

/// A Memory's or a chapter's WRITE_ONLY link (the web's `MemoryShareBody`): the guest names itself — the record's notice
/// before —, then adds photos and videos; it sees only what it sent while the link is open here, and removes its own.
private struct GuestUploaderView: View {
    @Bindable var model: AppModel
    let held: GuestHeld
    let up: GuestUploader
    @State private var sent: CoreState<NSArray>?
    @State private var pickingPhotos = false
    @State private var photos: [PhotosPickerItem] = []
    @State private var pickingFiles = false
    @State private var failed = false

    private var days: Int? { held.access?.guestWriteRecordDays?.intValue }
    private var origin: String { held.access?.origin ?? "" }

    var body: some View {
        GateColumn {
            Text(say(.shareUploaderIntro)).foregroundStyle(Role.muted)
            if let named = held.guest.value {
                if up.offered {
                    NotYou(session: named) { up.forget() }
                    RecordNotice(days: days, origin: origin, guestId: named.identityId)
                    Menu {
                        Button { pickingPhotos = true } label: { Label(say(.photoLibrary), systemImage: "photo.on.rectangle") }
                        Button { pickingFiles = true } label: { Label(say(.tabDepotFiles), systemImage: "folder") }
                    } label: {
                        Label(say(.shareAddPhotos), systemImage: "plus").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Role.primary)
                    .accessibilityIdentifier("guest-uploader-pick")
                    Text(say(.shareUploaderSession)).font(.footnote).foregroundStyle(Role.muted)
                    ForEach((sent?.value as? [GuestUploader.Sent]) ?? [], id: \.mediaId) { s in
                        HStack {
                            Text(s.name).accessibilityIdentifier("guest-uploader-sent")
                            Spacer()
                            Button(say(.entryDelete), role: .destructive) {
                                Task { failed = !((try? await up.remove(mediaId: s.mediaId))?.boolValue ?? false) }
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }
            } else {
                InlineNaming(hint: say(.shareNameVisibility), days: days, origin: origin) { name in
                    (try? await AppleSharing.shared.nameUploader(up: up, displayName: name))?.boolValue ?? false
                }
            }
        }
        .alert(say(.shareDeleteFailed), isPresented: $failed) { Button(say(.ok), role: .cancel) {} }
        .photosPicker(isPresented: $pickingPhotos, selection: $photos, matching: .any(of: [.images, .videos]), preferredItemEncoding: .current)
        .onChange(of: photos) {
            let picked = photos
            photos = []
            guard !picked.isEmpty else { return }
            Task {
                var urls: [URL] = []
                for item in picked {
                    if let file = try? await item.loadTransferable(type: PickedMedia.self) { urls.append(file.url) }
                }
                _ = up.upload(sources: urls.compactMap { model.guestMediaSource($0, fromPhotos: true) })
            }
        }
        .fileImporter(isPresented: $pickingFiles, allowedContentTypes: [.image, .movie], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            _ = up.upload(sources: urls.compactMap { model.guestMediaSource($0, fromPhotos: false) })
        }
        .onAppear {
            sent = CoreState({ (up.sent.value as? NSArray) ?? [] }) { changed in AppleMemories.shared.watch(flow: up.sent) { @Sendable _ in changed() } }
        }
        .onDisappear { sent?.stop() }
    }
}

// MARK: Keep

/// *Keep*: the places the link may be kept — the personal space first —, then the bookmark's dialog filled with the link.
private struct KeepSheet: View {
    @Bindable var model: AppModel
    let held: GuestHeld
    @Environment(\.dismiss) private var dismiss
    @State private var places: [SpaceRef]?
    @State private var create: BookmarkCreateHeld?
    @State private var kept: String?

    var body: some View {
        NavigationStack {
            Group {
                if let create {
                    BookmarkForm(create: create) {
                        model.bookmarkMade()
                        kept = say(.linkKept, create.placeName)
                    } onClose: { dismiss() }
                } else if let places {
                    List(places, id: \.scopeId) { p in
                        Button {
                            if let made = held.screen.keep(space: p, parentId: nil, password: nil) {
                                create = BookmarkCreateHeld(made, placeName: Names.spaceName(p))
                            }
                        } label: {
                            Label(Names.spaceName(p), systemImage: Names.spaceSymbol(p.kind)).foregroundStyle(Role.text)
                        }
                        .accessibilityIdentifier("guest-keep-place")
                    }
                    .navigationTitle(say(.linkKeepWhere))
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button(say(.entryCancel)) { dismiss() } } }
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
        .secretOnScreen()
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 420)
        #endif
        .task { places = (try? await AppleSharing.shared.keepPlaces(screen: held.screen)) ?? [] }
        .onDisappear { create?.stop() }
        .alert(kept ?? "", isPresented: Binding(get: { kept != nil }, set: { _ in })) {
            Button(say(.ok), role: .cancel) { kept = nil; dismiss() }
        }
    }
}

// MARK: The bookmark's dialog

/// Core's `BookmarkCreate` and its state on the main actor.
@MainActor
final class BookmarkCreateHeld {
    let create: BookmarkCreate
    let state: CoreState<BookmarkCreate.State>
    /// The place's name, said once the link is kept.
    let placeName: String

    init(_ create: BookmarkCreate, placeName: String) {
        self.create = create
        self.placeName = placeName
        state = CoreState({ create.state.value as! BookmarkCreate.State }) { changed in
            AppleMemories.shared.watch(flow: create.state) { @Sendable _ in changed() }
        }
    }

    func stop() { state.stop() }
}

/// The bookmark's dialog (ADR 0150 decision 6; the web's `BookmarkCreateDialog`): the link checked as it is typed, its
/// password, its name (the host until one is typed), the group space's line that everyone who opens the place opens the
/// link. Its fields read core right after each edit (§ Traps), each with a prompt so a Mac's click focuses it (#1030).
struct BookmarkForm: View {
    let create: BookmarkCreateHeld
    let onMade: () -> Void
    let onClose: () -> Void

    private var state: BookmarkCreate.State { create.state.value }

    var body: some View {
        Form {
            Section {
                Text(say(.bookmarkCreateIntro)).font(.subheadline).foregroundStyle(Role.muted)
            }
            Section {
                TextField(say(.bookmarkLinkLabel), text: Binding(get: { state.text }, set: { create.create.link(text: $0); create.state.refresh() }), prompt: Text(say(.bookmarkLinkLabel)))
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    #endif
                    .accessibilityIdentifier("bookmark-link")
                if let problem = state.problem { Text(say(problem)).font(.footnote).foregroundStyle(Role.danger) }
                SecureField(say(.bookmarkPasswordLabel), text: Binding(get: { state.password }, set: { create.create.password(text: $0); create.state.refresh() }), prompt: Text(say(.bookmarkPasswordLabel)))
            } footer: {
                Text(say(.bookmarkPasswordHint))
            }
            Section {
                TextField(say(.nodeName), text: Binding(get: { state.name }, set: { create.create.name(text: $0); create.state.refresh() }), prompt: Text(state.host ?? say(.nodeName)))
                    .accessibilityIdentifier("bookmark-name")
            } footer: {
                if create.create.groupSpace { Text(say(.bookmarkWhoCanOpen)) }
            }
            if state.failed { Section { Text(say(.bookmarkCreateFailed)).foregroundStyle(Role.danger) } }
        }
        .formStyle(.grouped)
        .navigationTitle(say(.bookmarkCreateTitle))
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(say(.entryCancel), action: onClose).disabled(state.saving) }
            ToolbarItem(placement: .confirmationAction) {
                Button(say(.bookmarkSave)) {
                    Task {
                        let made = (try? await AppleSharing.shared.save(create: create.create))?.boolValue ?? false
                        create.state.refresh()
                        if made { onMade() }
                    }
                }
                .disabled(!state.canSave)
                .accessibilityIdentifier("bookmark-save")
            }
        }
    }
}

/// New → Bookmark (ADR 0150 decision 6): the bookmark's dialog at the level the shell shows, in a sheet of its own.
struct NewBookmarkSheet: View {
    @Bindable var model: AppModel
    let scopeId: String
    let parentId: String?
    @Environment(\.dismiss) private var dismiss
    @State private var create: BookmarkCreateHeld?

    var body: some View {
        NavigationStack {
            if let create {
                BookmarkForm(create: create) {
                    model.bookmarkMade()
                    dismiss()
                } onClose: { dismiss() }
            } else {
                ProgressView()
            }
        }
        .secretOnScreen()
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 440)
        #endif
        .onAppear {
            if create == nil, let made = model.bookmarkCreate(scopeId: scopeId, parentId: parentId) {
                create = BookmarkCreateHeld(made, placeName: "")
            }
        }
        .onDisappear { create?.stop() }
    }
}

// MARK: A guest's gallery

/// A gallery shown to a link's guest (ADR 0150 decision 5): the member's page with a guest's rights — its chapters open
/// inside the link (`onChapter`), it adds once it named itself (`canAdd`), and what is the account's (the Music and Map
/// views, the music player, a drop from another app, a lightbox in a window of its own, a person's profile) is not offered.
struct GuestGallery {
    let canAdd: Bool
    let onChapter: (NodeRef) -> Void
}

extension EnvironmentValues {
    @Entry var guestGallery: GuestGallery?
}
