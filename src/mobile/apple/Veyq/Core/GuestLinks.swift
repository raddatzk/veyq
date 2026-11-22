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
            queue: model.uploads, deriver: model.deriver
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
            ApplePlatform.shared.watch(flow: field.state) { @Sendable _ in changed() }
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
    @ObservationIgnored private var pageWatch: ApplePlatform.Watch?
    @ObservationIgnored private var closed = false

    init(_ screen: GuestScreen, model: AppModel) {
        self.screen = screen
        self.model = model
        let view = screen.view
        state = CoreState({ screen.state.value as! GuestScreen.State }) { changed in
            ApplePlatform.shared.watch(flow: screen.state) { @Sendable _ in changed() }
        }
        gate = CoreState({ view.gates.state.value as! ShareGates.State }) { changed in
            ApplePlatform.shared.watch(flow: view.gates.state) { @Sendable _ in changed() }
        }
        busy = CoreState({ view.gates.busy.value as! KotlinBoolean }) { changed in
            ApplePlatform.shared.watch(flow: view.gates.busy) { @Sendable _ in changed() }
        }
        guest = CoreState({ view.gates.guest.value as? GuestSession }) { changed in
            ApplePlatform.shared.watch(flow: view.gates.guest) { @Sendable _ in changed() }
        }
        pageWatch = ApplePlatform.shared.watch(flow: view.page) { @Sendable _ in
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
                        // the kind's own actions (a file's download and replace, a Memory's music), as its application registered them
                        if let page = held.page, let actions = Registry.page(page.node.kind)?.guestActions { actions(page, held, model) }
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
                            .modifier(OptionalNaming(access: held.access, active: !model.editing))
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
        if let level = page as? GuestPage.Level {
            GuestLevelView(model: model, held: held, level: level.model)
        } else if !(page is GuestPage.NotShown), let guest = Registry.page(page.node.kind)?.guest {
            // the kind's page as its application registered it for a guest (the registry's `guest`)
            guest(page, held, model)
        } else {
            Text(say(.shareNotShown)).foregroundStyle(Role.muted).padding()
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .accessibilityIdentifier("guest-not-shown")
        }
    }
}

extension ObjectRef {
    /// A link's node as the member's page of application `app` takes it.
    static func guest(_ node: NodeAt, _ app: ApplicationId) -> ObjectRef {
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
    @State private var said: VeyqCore.Notice?

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
        .createMenu(say(.drawerNew), shown: !level.creates.isEmpty) {
            // core's `GuestLevel.creates`, whole: a guest's New holds what the link lets it write here, nothing else
            ForEach(level.creates, id: \.self) { c in
                Button(say(c.word), systemImage: Names.createSymbol(c)) {
                    switch c.entry {
                    case .folder: newName = ""; nameProblem = nil; naming = true
                    case .uploadFiles: pickingFiles = true
                    // what a guest never makes (the core offers none of them): nothing to begin
                    case .pot, .uploadFolder, .memory, .chapter, .bookmark: break
                    }
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
        .saidNotice($said)
    }
}

// MARK: The gates

/// A gate's column: a form's width, centred, scrolled (the sign-in's measure).
struct GateColumn<Content: View>: View {
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
struct PrimaryButton: View {
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
    @State private var said: VeyqCore.Notice?

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
            .saidNotice($said)
            .task(id: ObjectIdentifier(access)) {
                asking?.stop()
                asking = CoreState({ naming.asking.value as! KotlinBoolean }) { changed in
                    ApplePlatform.shared.watch(flow: naming.asking) { @Sendable _ in changed() }
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
struct NotYou: View {
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
struct InlineNaming: View {
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
            ApplePlatform.shared.watch(flow: create.state) { @Sendable _ in changed() }
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
