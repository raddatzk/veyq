import PhotosUI
import SwiftUI
import VeyqCore

/// What a kind's page hands core's header (the web's `contributeNodeHeader` and the page's doors): a subtitle under the
/// name, what the page knows of its object that the kind's doors wait on (`facts`: core's `AppKinds.doors` decides), the
/// doors themselves (a copy, a download; core's rights say whether each is offered) and actions of its own. Core's
/// `NodeHeaderModel` composes the rest, the same for every kind.
struct HeaderContribution {
    /// The line under the node's name (a Pot's currency); nil: none.
    var subtitle: String?
    /// What the page knows of its object (a Pot's write right, a file's content): the kind's doors follow it in core.
    var facts = AppKinds.Facts(canWrite: true, hasContent: true)
    /// The download door: writes the node's download (a file the file itself, else its ZIP) to the path given; what it left
    /// out, or nil when not whole (nothing is left at the path then).
    var download: ((String) async -> [NodeExport.Skipped]?)?
    /// The application's own actions, merged into core's plan where the kind's order puts them.
    var actions: [OwnAction] = []
}

/// An action of the application's own on its node's header.
struct OwnAction {
    let id: HeaderAction
    let label: String
    let systemImage: String
    /// Where the action offers a choice (Photo Library or Files), its entries: drawn as a submenu, `run` unused.
    var choices: [OwnChoice] = []
    let run: () -> Void
}

/// One choice of an own action that offers several.
struct OwnChoice {
    let label: String
    let systemImage: String
    let run: () -> Void
}

/// Core's header actions on a node of any kind (the web's `NodeHeader` with `coreNodeActions.ts` and `standardNodeActions`,
/// ADR 0110): core's `NodeHeaderModel` composes the rights and plans what is offered and where, from the node, the kind's
/// order and the contribution; up to `maxButtons` are toolbar buttons, the rest one "More" menu whose last section holds
/// the move, the copy, the report and the delete — the delete last and destructive, asked first. Every entry of the plan
/// is drawn, in its order (ADR 0154 decision 2): the core's through `NodeActionWords`, the page's own through its
/// `OwnAction`. What runs behind each is `NodeActionDialogs`, the same a level row's menu uses.
struct NodeHeaderActions: View {
    @Bindable var model: AppModel
    let node: NodeAt
    let contribution: HeaderContribution
    /// How many actions are toolbar buttons: a level's bar keeps its room for its title (0: one "More" menu).
    var maxButtons: Int32 = 2
    @State private var asked: HeaderAction?
    @State private var downloading = false

    /// Core's header of the node (one per node and name, held by the app model).
    private var header: NodeHeaderModel? { model.header(node, facts: contribution.facts) }

    /// Core's plan; read with the app model's `headerRevision`, so each change of the header redraws it.
    private var plan: NodeHeader.Plan? {
        _ = model.headerRevision
        return header?.plan(maxButtons: maxButtons, own: contribution.actions.map(\.id))
    }

    private var words: NodeActionWords {
        _ = model.headerRevision
        return NodeActionWords(
            kind: node.kind, pinned: header?.current.pinned?.boolValue, hasCover: model.covers[node.nodeId] != nil,
            offline: header?.current.offline, own: contribution.actions
        )
    }

    var body: some View {
        HStack {
            if let plan {
                ForEach(plan.buttons, id: \.self) { id in
                    words.entry(id) { asked = $0 }.labelStyle(.iconOnly)
                }
                if plan.hasMenu {
                    Menu {
                        ForEach(plan.menu, id: \.self) { id in words.entry(id) { asked = $0 } }
                        if !plan.menu.isEmpty, !plan.last.isEmpty { Divider() }
                        ForEach(plan.last, id: \.self) { id in words.entry(id) { asked = $0 } }
                    } label: {
                        if downloading { ProgressView() } else { Label(say(.nodeMore), systemImage: "ellipsis.circle") }
                    }
                }
            }
        }
        .onChange(of: contribution.facts) { header?.update(facts: contribution.facts) }
        .modifier(NodeActionDialogs(model: model, node: node, contribution: contribution, asked: $asked, downloading: $downloading, eager: true))
    }
}

/// A level row's menu (ADR 0154 decision 2; the web's `levelRow.ts`): core's `LevelRow.Plan` for the row's node
/// (`ShellModel.rowPlan`) drawn whole as the row's context menu — a long press on iOS, a secondary click on a Mac (the
/// HIG's place for a row's actions) —, its two groups apart; each entry runs as the header's does (`NodeActionDialogs`).
struct NodeRowMenu: ViewModifier {
    @Bindable var model: AppModel
    let node: NodeAt
    @State private var plan: LevelRow.Plan?
    @State private var asked: HeaderAction?
    @State private var downloading = false

    /// What a row's actions reach without its page: the download door (core's, every kind), the facts' defaults, and a kept
    /// link's share report where core's plan offers it (its link reads; no page is open, so it runs from the row).
    private var contribution: HeaderContribution {
        HeaderContribution(download: { [model, node] in await model.downloadNode(node, to: $0) }, actions: own)
    }

    private var own: [OwnAction] {
        [OwnAction(id: HeaderAction.reportShared, label: say(.bookmarkReportShare), systemImage: "flag") { [model, node] in model.rowReportShared(node) }]
    }

    private var words: NodeActionWords {
        NodeActionWords(kind: node.kind, pinned: model.favorites.contains { $0.nodeId == node.nodeId }, hasCover: model.covers[node.nodeId] != nil,
                        offline: nil, own: own)
    }

    func body(content: Content) -> some View {
        content
            .contextMenu {
                if let plan {
                    ForEach(plan.menu, id: \.self) { id in words.entry(id) { asked = $0 } }
                    if !plan.menu.isEmpty, !plan.last.isEmpty { Divider() }
                    ForEach(plan.last, id: \.self) { id in words.entry(id) { asked = $0 } }
                }
            }
            // the plan anew with what moves it: the reach, the favorites, the header's revision
            .task(id: "\(node.nodeId)|\(model.online)|\(model.headerRevision)|\(model.favorites.count)") { plan = await model.rowPlan(node) }
            .modifier(NodeActionDialogs(model: model, node: node, contribution: contribution, asked: $asked, downloading: $downloading, eager: false))
    }
}

/// How each entry of a node's plan is drawn: its words (core's `NodeHeader.label`, the delete's the kind's title, an own
/// entry's the page's) and its symbol. One exhaustive `switch` over the plan's entries: an entry the core adds fails to
/// compile here until it is drawn.
@MainActor
struct NodeActionWords {
    let kind: String
    let pinned: Bool?
    let hasCover: Bool
    /// "Available offline"'s state, drawn as a toggle where the header knows it; nil: a plain entry.
    let offline: OfflineToggle?
    let own: [OwnAction]

    private func word(_ id: HeaderAction) -> String {
        if let msg = NodeHeader.shared.label(id: id, kind: kind, pinned: pinned.map { KotlinBoolean(bool: $0) }, hasCover: hasCover) { return say(msg) }
        return say(AppKinds.shared.header(kind: kind)?.deleteTitle ?? .folderDelete)
    }

    /// The entry `id` as a menu item or a button; `run` asks for it.
    @ViewBuilder func entry(_ id: HeaderAction, run: @escaping @MainActor (HeaderAction) -> Void) -> some View {
        switch id.entry {
        case .favorite:
            Button { run(id) } label: {
                Label(word(id), systemImage: pinned == true ? "heart.fill" : "heart")
                    .foregroundStyle(pinned == true ? Role.favorite : Role.text)
            }
        case .share: Button { run(id) } label: { Label(word(id), systemImage: "square.and.arrow.up") }
        case .rename: Button { run(id) } label: { Label(word(id), systemImage: "pencil") }
        case .move: Button { run(id) } label: { Label(word(id), systemImage: "folder") }
        case .theCopy: Button { run(id) } label: { Label(word(id), systemImage: "doc.on.doc") }
        case .cover: Button { run(id) } label: { Label(word(id), systemImage: "photo") }
        case .download: Button { run(id) } label: { Label(word(id), systemImage: "arrow.down.circle") }
        case .offline:
            if let offline {
                // a toggle, as the system's menus show a state, with its state's line under it (core's: "Downloading 3 MB
                // of 12 MB", "On this device", "Paused: …")
                Toggle(isOn: Binding(get: { offline.on }, set: { _ in run(id) })) {
                    Label {
                        Text(word(id))
                        if let said = offline.said { Text(say(notice: said)) }
                    } icon: {
                        Image(systemName: "arrow.down.circle.dotted")
                    }
                }
            } else {
                Button { run(id) } label: { Label(word(id), systemImage: "arrow.down.circle.dotted") }
            }
        case .report: Button { run(id) } label: { Label(word(id), systemImage: "flag") }
        case .delete_: Button(role: .destructive) { run(id) } label: { Label(word(id), systemImage: "trash") }
        // the page's own entries (ADR 0154 decision 2: the core's plan holds them; the page words and runs them)
        case .upload, .music, .banner, .replace, .send, .openOwnPage, .reportShared:
            if let action = own.first(where: { $0.id == id }) {
                if action.choices.isEmpty {
                    Button { run(id) } label: { Label(action.label, systemImage: action.systemImage) }
                } else {
                    Menu {
                        ForEach(Array(action.choices.enumerated()), id: \.offset) { _, c in
                            Button(action: c.run) { Label(c.label, systemImage: c.systemImage) }
                        }
                    } label: {
                        Label(action.label, systemImage: action.systemImage)
                    }
                }
            }
        }
    }
}

/// What runs a node's actions — the dialogs behind each entry of its plan, every one core's: the share sheet, the move and
/// copy browser, the cover sheet, the rename, the report, the delete (asked first), the download, "Available offline".
/// A drawing sets `asked`; it runs here and is cleared. `eager`: the node's header model is read from the start (the open
/// node's header); a row's is read once one of its entries ran, so a level's rows make none before.
struct NodeActionDialogs: ViewModifier {
    @Bindable var model: AppModel
    let node: NodeAt
    let contribution: HeaderContribution
    @Binding var asked: HeaderAction?
    @Binding var downloading: Bool
    let eager: Bool
    @State private var dialog: HeaderAction?
    @State private var touched = false
    @State private var asking = false
    @State private var renaming = false
    @State private var newName = ""
    @State private var renameProblem: String?
    @State private var failed: String?
    @State private var downloaded: URL?
    /// What the last download left out, said once its file is saved.
    @State private var leftOut: [NodeExport.Skipped] = []

    /// Core's header of the node, once it is read (`eager`, or an entry ran).
    private var header: NodeHeaderModel? { eager || touched ? model.header(node, facts: contribution.facts) : nil }

    /// "Available offline" of the node (core's toggle: on, its state's line, the size a pin waits to be confirmed with).
    private var offline: OfflineToggle? {
        _ = model.headerRevision
        return header?.current.offline
    }

    func body(content: Content) -> some View {
        content
            .onChange(of: asked) { _, id in
                guard let id else { return }
                asked = nil
                touched = true
                run(id)
            }
            .sheet(isPresented: shown(.share)) { NodeShareSheet(model: model, ref: node) }
            .sheet(isPresented: shown(.report)) { ReportSheet(model: model, ref: node) }
            .sheet(isPresented: shown(.cover)) { CoverSheet(model: model, node: node) }
            .sheet(isPresented: shown(.move)) { NodeMoveSheet(model: model, ref: node) }
            .sheet(isPresented: shown(.theCopy)) { NodeMoveSheet(model: model, ref: node, copying: true) }
            // The system's save sheet takes the written file where the person picks; the temporary copy goes either way.
            .fileMover(isPresented: Binding(get: { downloaded != nil }, set: { if !$0 { cleanUpDownload() } }), file: downloaded) { _ in
                cleanUpDownload()
            }
            .alert(asking ? header.map { say($0.deleteTitle) } ?? "" : "", isPresented: $asking) {
                Button(say(.peopleDeleteAccept), role: .destructive) {
                    if let header { Task { await model.trash(header) } }
                }
                Button(say(.signinCancel), role: .cancel) {}
            } message: {
                if asking, let header { Text(say(notice: header.deleteQuestion())) }
            }
            // the tray's *Open* of a rename held offline: this node's name dialog again, with the name the person gave
            .onChange(of: model.renameAsked?.node, initial: true) { _, asked in
                guard eager, let r = model.renameAsked, asked == node.nodeId else { return }
                model.renameAsked = nil
                newName = r.name
                renameProblem = nil
                renaming = true
            }
            // Core's name dialog (`FolderNameDialog`): the name as it is, trimmed on save; a refusal says why and asks again.
            .alert(say(.nodeRename), isPresented: $renaming) {
                TextField(say(.peopleName), text: $newName)
                Button(say(.entrySave)) {
                    let name = newName
                    touched = true
                    guard let header else { return }
                    Task {
                        guard let r = try? await header.rename(name: name) else {
                            renameProblem = say(.nodeSaveFailed)
                            renaming = true
                            return
                        }
                        if let problem = r.problem {
                            renameProblem = say(notice: problem)
                            renaming = true
                        } else if let notice = r.notice {
                            // renamed; a link that kept the old name is said (the rename stands)
                            failed = say(notice: notice)
                        }
                    }
                }
                .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                Button(say(.signinCancel), role: .cancel) {}
            } message: {
                if let renameProblem { Text(renameProblem) }
            }
            // a pin asks the size it keeps before its first byte (core's question); nothing is kept until it is confirmed
            .alert(say(.offlinePinAskTitle), isPresented: Binding(get: { offline?.question != nil }, set: { _ in })) {
                Button(say(.offlinePinConfirm)) {
                    guard let header else { return }
                    Task { if let notice = try? await header.confirmOffline() { failed = say(notice: notice) } }
                }
                Button(say(.signinCancel), role: .cancel) { header?.cancelOffline() }
            } message: {
                if let question = offline?.question { Text(say(notice: question)) }
            }
            .alert(failed ?? "", isPresented: Binding(get: { failed != nil }, set: { if !$0 { failed = nil } })) {
                Button(say(.ok), role: .cancel) {}
            }
    }

    /// A sheet's presentation: on while `dialog` is `id`.
    private func shown(_ id: HeaderAction) -> Binding<Bool> {
        Binding(get: { dialog == id }, set: { if !$0, dialog == id { dialog = nil } })
    }

    private func run(_ id: HeaderAction) {
        switch id.entry {
        case .favorite:
            // the heart turns at once and back when the write did not land (core's)
            guard let header else { return }
            Task { if let notice = try? await header.toggleFavorite() { failed = say(notice: notice) } }
        case .rename:
            newName = node.name
            renameProblem = nil
            renaming = true
        case .delete_:
            asking = true
        case .download:
            Task { await download() }
        case .offline:
            // on: off at once; off: core reads the size it keeps and asks first (the alert above)
            guard let header else { return }
            Task { if let notice = try? await header.toggleOffline() { failed = say(notice: notice) } }
        case .share, .report, .cover, .move, .theCopy:
            dialog = id
        // the page's own entries run as the page handed them
        case .upload, .music, .banner, .replace, .send, .openOwnPage, .reportShared:
            contribution.actions.first(where: { $0.id == id })?.run()
        }
    }

    /// The node's download (a file the file itself, else its ZIP) under core's name for it. A Mac asks where in its save panel
    /// and core writes there (`FileSink`); iOS has no other way to a place the person picks than the save sheet
    /// (`fileMover`), so it writes into the app's downloads directory first, removed once the sheet is done and at start.
    private func download() async {
        guard let door = contribution.download else { return }
        _ = model.headerRevision
        let name = header?.current.downloadName ?? NodeExport.shared.fileName(name: node.name)
        #if os(macOS)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = name
        guard panel.runModal() == .OK, let url = panel.url else { return }
        downloading = true
        let written = await door(url.path)
        downloading = false
        if let written {
            if let said = Notices.shared.downloadLeftOut(leftOut: written) { failed = say(notice: said) }
        } else {
            failed = say(notice: Notices.shared.downloaded(name: node.name, leftOut: nil))
        }
        #else
        guard let dir = AppModel.downloadDir() else { return }
        let url = dir.appendingPathComponent(name)
        downloading = true
        let written = await door(url.path)
        downloading = false
        if let written {
            leftOut = written
            downloaded = url
        } else {
            try? FileManager.default.removeItem(at: dir)
            failed = say(notice: Notices.shared.downloaded(name: node.name, leftOut: nil))
        }
        #endif
    }

    private func cleanUpDownload() {
        if let url = downloaded { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        downloaded = nil
        // what the archive left out, said once the save sheet is gone
        if let said = Notices.shared.downloadLeftOut(leftOut: leftOut) {
            failed = say(notice: said)
            leftOut = []
        }
    }
}

/// Core's head of a node's page (the web's `NodeHeader` leading visual, ADR 0110): what the shell hands the body of the node
/// on screen (`\.nodeHero`), which places `NodeHeroRow` first in its list so it moves with the page as the web's header does.
struct NodeHero {
    let model: AppModel
    let node: NodeAt
    /// The cover may be changed here: a tap opens core's cover sheet.
    let canSet: Bool
}

extension EnvironmentValues {
    @Entry var nodeHero: NodeHero?
}

/// The page's head as a list's first row: the node's cover, or no row where it has none (a phone keeps the room the web's
/// empty frame would take; the cover is set from the header's menu).
struct NodeHeroRow: View {
    @Environment(\.nodeHero) private var hero

    var body: some View {
        if let hero, hero.model.covers[hero.node.nodeId] != nil {
            NodeCoverHero(model: hero.model, node: hero.node, canSet: hero.canSet)
                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                .listRowBackground(Color.clear)
        }
    }
}

/// A node's cover across the page's width, 16:9, framed as stored (core's covers); where it may be changed, a tap opens
/// core's cover sheet.
struct NodeCoverHero: View {
    @Bindable var model: AppModel
    let node: NodeAt
    let canSet: Bool
    @State private var image: CGImage?
    @State private var editing = false

    var body: some View {
        let picture = Role.surface
            .aspectRatio(16 / 9, contentMode: .fit)
            .overlay {
                if let image { Image(decorative: image, scale: 1).resizable().scaledToFill() }
            }
            .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .task(id: model.covers[node.nodeId]?.blobId) { image = await model.coverHero(node) }
        if canSet {
            Button { editing = true } label: { picture }
                .buttonStyle(.plain)
                .accessibilityLabel(say(.coverChange))
                .sheet(isPresented: $editing) { CoverSheet(model: model, node: node) }
        } else {
            picture.accessibilityHidden(true)
        }
    }
}

/// Core's cover dialog (the web's `CoverDialog` around its 16:9 `ImageCropper`): the one place a node's cover is chosen,
/// framed and removed. Its source is the stored cover, framed anew (only the crop moves: the web's `keep`), or a picture
/// picked here from the system's photo picker, uploaded with its crop; a drag pans it and a pinch or the slider zooms it
/// (core's `CoverFrame`, the web's math, so a cover framed here reopens as framed on the web and back).
struct CoverSheet: View {
    @Bindable var model: AppModel
    let node: NodeAt
    @Environment(\.dismiss) private var dismiss
    @State private var item: PhotosPickerItem?
    @State private var image: CGImage?
    @State private var picked: Data?
    @State private var storedCrop: NodeCover.Crop?
    @State private var pose: CoverFrame.Pose?
    @State private var size: CGSize = .zero
    @State private var dragged: CGSize = .zero
    @State private var pinched: CGFloat = 1
    @State private var loading = false
    @State private var saving = false
    @State private var problem: String?

    private var hasCover: Bool { model.covers[node.nodeId] != nil }

    /// The frame's math for the source on screen, once the frame's size is known.
    private var frame: CoverFrame? {
        guard let image, size.width > 0 else { return nil }
        return CoverFrame(imageWidth: Double(image.width), imageHeight: Double(image.height), frameWidth: size.width, frameHeight: size.height)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    cropper
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                        .listRowInsets(EdgeInsets())
                    if let frame, let pose {
                        Slider(value: Binding(get: { pose.zoom }, set: { self.pose = frame.zoom(pose: pose, factor: $0 / pose.zoom) }),
                               in: CoverFrame.companion.MIN_ZOOM...CoverFrame.companion.MAX_ZOOM) {
                            Text(say(.coverZoom))
                        } minimumValueLabel: {
                            Image(systemName: "minus.magnifyingglass")
                        } maximumValueLabel: {
                            Image(systemName: "plus.magnifyingglass")
                        }
                        .disabled(saving)
                    }
                } footer: {
                    Text(say(.coverHint))
                }
                Section {
                    PhotosPicker(selection: $item, matching: .images) {
                        Label(say(.coverChooseImage), systemImage: "photo.on.rectangle")
                    }
                    .disabled(saving)
                    if hasCover {
                        Button(say(.coverRemove), systemImage: "trash", role: .destructive) { Task { await finish { await model.removeCover(node) } } }
                            .disabled(saving)
                    }
                }
                if saving { ProgressView() }
                if let problem { Text(problem).foregroundStyle(Role.danger) }
            }
            .navigationTitle(say(.coverTitle))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() }.disabled(saving) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.entrySave)) { Task { await save() } }.disabled(saving || frame == nil || pose == nil)
                }
            }
        }
        .sheetFrame(minWidth: 520, minHeight: 520)
        .task {
            // the stored cover, framed as stored
            guard hasCover else { return }
            loading = true
            if let source = await model.coverSource(node) {
                storedCrop = source.crop
                image = source.image
            }
            loading = false
        }
        .onChange(of: item) { _, chosen in
            guard let chosen else { return }
            item = nil
            Task {
                guard let data = try? await chosen.loadTransferable(type: Data.self), let picture = CoverImages.image(data, maxPixels: Int(NodeCover.shared.LONG_EDGE)) else {
                    problem = say(.coverUnreadable)
                    return
                }
                problem = nil
                picked = data
                storedCrop = nil
                pose = nil
                image = picture
            }
        }
    }

    /// The 16:9 viewport: the source at its pose, a drag pans it and a pinch zooms it.
    private var cropper: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                Role.surface
                if let image, let frame, let pose {
                    let scale = frame.baseScale * pose.zoom
                    Image(decorative: image, scale: 1).resizable()
                        .frame(width: Double(image.width) * scale, height: Double(image.height) * scale)
                        .offset(x: pose.x, y: pose.y)
                } else if loading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    Image(systemName: Names.kindSymbol(node.kind)).font(.largeTitle).foregroundStyle(Role.muted)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture()
                    .onChanged { g in
                        guard let frame, let pose else { return }
                        self.pose = frame.pan(pose: pose, dx: g.translation.width - dragged.width, dy: g.translation.height - dragged.height)
                        dragged = g.translation
                    }
                    .onEnded { _ in dragged = .zero }
                    .simultaneously(with: MagnifyGesture()
                        .onChanged { g in
                            guard let frame, let pose else { return }
                            self.pose = frame.zoom(pose: pose, factor: g.magnification / pinched)
                            pinched = g.magnification
                        }
                        .onEnded { _ in pinched = 1 })
            )
            .onAppear { size = geo.size }
            .onChange(of: geo.size) { _, new in size = new }
        }
        // a source shows framed as stored (or centred, a fresh one) once the frame's size is known
        .onChange(of: frameKey) { _, _ in
            guard let frame else { return }
            pose = storedCrop.map { frame.pose(crop: $0) } ?? frame.centred(zoom: 1)
        }
    }

    /// What decides a source's first pose: the source and the frame's size.
    private var frameKey: String { "\(image.map { "\($0.width)x\($0.height)" } ?? "-")@\(size.width)x\(size.height)@\(picked?.count ?? 0)" }

    private func save() async {
        guard let frame, let pose else { return }
        let crop = frame.crop(pose: pose)
        if let picked { await finish { await model.setCover(node, data: picked, crop: crop) } } else { await finish { await model.recropCover(node, crop: crop) } }
    }

    private func finish(_ work: () async -> Bool) async {
        saving = true
        problem = nil
        let ok = await work()
        saving = false
        if ok { dismiss() } else { problem = say(.coverFailed) }
    }
}

/// What the shell says at its bottom (`AppModel.TrashNotice`): a trash's or a move's "moved" with its Undo for as long as
/// core offers it (`NodeHeaderModel`, `ShellModel.TRASH_UNDO_MS`), or a success that closes itself after the core's
/// `lifeMs` (ADR 0154 decision 4).
struct TrashNotice: View {
    @Bindable var model: AppModel

    var body: some View {
        if let notice = model.trashNotice {
            NoticeCard(text: notice.message) {
                if notice.undoable {
                    Button(say(.trashUndo)) { Task { await model.undoTrash() } }.fontWeight(.semibold)
                }
            }
            .task(id: notice.id) {
                // an Undo goes when core ends its offer; a success after its life
                guard let life = notice.lifeMs, (try? await waitCore(life)) != nil else { return }
                model.dismissTrashNotice(notice.id)
            }
        }
    }
}

/// The card a notice at the bottom is drawn in, its action trailing.
struct NoticeCard<Action: View>: View {
    let text: String
    @ViewBuilder let action: () -> Action

    var body: some View {
        HStack(spacing: 12) {
            Text(text).font(.subheadline).foregroundStyle(Role.text)
            Spacer(minLength: 0)
            action()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Role.surface, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        .shadow(color: .black.opacity(0.12), radius: 8, y: 2)
        .padding(.horizontal, 16)
        .padding(.bottom, 96)
    }
}

extension View {
    /// A notice the core raised on a screen of its own (a guest's link), as it lives (ADR 0154 decision 4): a refusal or a
    /// failure as an alert until it is closed, anything else at the bottom, closing itself after the core's `lifeMs`.
    func saidNotice(_ said: Binding<VeyqCore.Notice?>) -> some View {
        let held = said.wrappedValue.map { $0.kind == .held } ?? false
        return self
            .alert(held ? said.wrappedValue.map { say(notice: $0) } ?? "" : "",
                   isPresented: Binding(get: { held }, set: { if !$0 { said.wrappedValue = nil } })) {
                Button(say(.ok), role: .cancel) {}
            }
            .overlay(alignment: .bottom) {
                if let n = said.wrappedValue, !held {
                    NoticeCard(text: say(notice: n)) { EmptyView() }
                        .task(id: ObjectIdentifier(n)) {
                            guard (try? await waitCore(n.kind.lifeMs?.int64Value ?? Notice.companion.SAID_MS)) != nil else { return }
                            if said.wrappedValue === n { said.wrappedValue = nil }
                        }
                }
            }
    }
}

