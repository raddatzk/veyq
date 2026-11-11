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
    /// The download door: writes the node's ZIP to a temporary file; it and what it left out, or nil when not whole.
    var download: (() async -> Downloaded?)?
    /// The application's own actions, merged into core's plan where the kind's order puts them.
    var actions: [OwnAction] = []
}

/// A download written: the file, and what it left out.
struct Downloaded {
    let url: URL
    var leftOut: [NodeExport.Skipped] = []
}

/// An action of the application's own on its node's header.
struct OwnAction {
    let id: String
    let label: String
    let systemImage: String
    var slot: NodeHeader.Slot = .any
    let run: () -> Void
}

/// A kind's page as the app registers it (the web's `KindRegistration` of `app.kind`): what the kind's page contributes to
/// core's header (nil while the page cannot tell yet) and the page's body under it (nil: not built; a folder's is core's
/// level). What the header offers is core's (`NodeHeaderModel`, `AppKinds`); core draws it for every kind, never an
/// application.
struct KindPage {
    let header: @MainActor (NodeAt, AppModel) -> HeaderContribution?
    /// The body of an object of the kind on its bar tab (`key`).
    var body: (@MainActor (ObjectRef, String, AppModel) -> AnyView)?

    /// The pages this build registers, by kind: core's folder and each application's kinds (the composition root).
    static func of(_ kind: String) -> KindPage? {
        switch kind {
        case "FOLDER": folder
        case PotSession.companion.KIND: pot
        case DepotFiles.shared.FILE: file
        case DepotFiles.shared.DROPBOX: dropBox
        case "MEMORY", "CHAPTER": memory
        default: nil
        }
    }

    /// Depot's drop box: its guests' folders (`DropBoxScreen`); never renamed; its cover; its ZIP and its copy (a plain folder).
    /// Memories' Memory and chapter: their gallery (`MemoryScreen`); their cover is core's.
    private static let memory = KindPage(header: { _, _ in HeaderContribution() }, body: { ref, _, model in AnyView(MemoryScreen(model: model, ref: ref)) })

    private static let dropBox = KindPage(header: { node, model in
        HeaderContribution(
            download: { await model.downloadFolder(node) }
        )
    }, body: { ref, _, model in AnyView(DropBoxScreen(model: model, ref: ref)) })

    /// Depot's file: its download under its own name, its replace (or first upload), its page (`FileScreen`).
    private static let file = KindPage(header: { node, model in
        let has = model.files[node.nodeId]?.hasContent == true
        return HeaderContribution(
            facts: AppKinds.Facts(canWrite: true, hasContent: has),
            download: { await model.downloadFile(node) },
            actions: [OwnAction(id: "replace", label: say(DepotFiles.shared.replaceWord(f: model.files[node.nodeId])), systemImage: "square.and.arrow.down.on.square") {
                model.replacing = node.nodeId
            }] + sendAction(node, model, has)
        )
    }, body: { ref, _, model in AnyView(FileScreen(model: model, ref: ref)) })

    /// Core's folder: its ZIP and its copy with everything under it the build exports and copies (`NodeExport`, `SubtreeCopy`).
    private static let folder = KindPage(header: { node, model in
        HeaderContribution(
            download: { await model.downloadFolder(node) }
        )
    })

    /// Expenses' Pot: its currency under its name, its cover and copy while Expenses is on, its ZIP.
    private static let pot = KindPage(header: { node, model in
        guard let state = model.pot, state.ref.nodeId == node.nodeId, let view = state.view else { return nil }
        let ref = state.ref
        return HeaderContribution(
            subtitle: view.subtitle.map { say(notice: $0) },
            // a cover and a copy write: core offers them where Expenses is on (the web's `canCopy`, `expensesRights`)
            facts: AppKinds.Facts(canWrite: view.canWrite, hasContent: true),
            download: { await model.downloadPot(ref).map { Downloaded(url: $0) } }
        )
    }, body: { ref, key, model in AnyView(PotScreen(model: model, ref: ref, tab: key)) })
}

/// A file's "Send a Copy…" (iOS: the system's share sheet; a Mac downloads instead), after the shell's notice.
@MainActor private func sendAction(_ node: NodeAt, _ model: AppModel, _ has: Bool) -> [OwnAction] {
    #if os(iOS)
    guard has else { return [] }
    return [OwnAction(id: "send", label: say(.fileSend), systemImage: "paperplane", slot: .menu) { model.sendingCopy = node }]
    #else
    return []
    #endif
}

/// Core's header actions on a node of any kind (the web's `NodeHeader` with `coreNodeActions.ts` and `standardNodeActions`,
/// ADR 0110): core's `NodeHeaderModel` composes the rights and plans what is offered and where, from the node, the kind's
/// order and the contribution; up to `maxButtons` are toolbar buttons, the rest one "More" menu whose last section holds
/// the move, the copy, the report and the delete — the delete last and destructive, asked first. Every dialog behind an
/// action is core's one: the share sheet, the move and copy browser, the cover sheet, the rename, the report, the delete.
struct NodeHeaderActions: View {
    @Bindable var model: AppModel
    let node: NodeAt
    let contribution: HeaderContribution
    /// How many actions are toolbar buttons: a level's bar keeps its room for its title (0: one "More" menu).
    var maxButtons: Int32 = 2
    @State private var dialog: String?
    @State private var asking = false
    @State private var renaming = false
    @State private var newName = ""
    @State private var renameProblem: String?
    @State private var failed: String?
    @State private var downloading = false
    @State private var downloaded: URL?
    /// What the last download left out, said once its file is saved.
    @State private var leftOut: [NodeExport.Skipped] = []

    /// Core's header of the node (one per node and name, held by the app model).
    private var header: NodeHeaderModel? { model.header(node, facts: contribution.facts) }

    /// Core's plan; read with the app model's `headerRevision`, so each change of the header redraws it.
    private var plan: NodeHeader.Plan? {
        _ = model.headerRevision
        return header?.plan(maxButtons: maxButtons, own: contribution.actions.map { NodeHeader.Action(id: $0.id, slot: $0.slot) })
    }

    private var pinned: Bool? {
        _ = model.headerRevision
        return header?.current.pinned?.boolValue
    }

    var body: some View {
        HStack {
            if let plan {
                ForEach(plan.buttons, id: \.self) { id in
                    Button { run(id) } label: { label(id) }
                        .labelStyle(.iconOnly)
                }
                if plan.hasMenu {
                    Menu {
                        ForEach(plan.menu, id: \.self) { id in Button { run(id) } label: { label(id) } }
                        if !plan.menu.isEmpty, !plan.last.isEmpty { Divider() }
                        ForEach(plan.last, id: \.self) { id in
                            Button(role: id == NodeHeader.shared.DELETE ? .destructive : nil) { run(id) } label: { label(id) }
                        }
                    } label: {
                        if downloading { ProgressView() } else { Label(say(.nodeMore), systemImage: "ellipsis.circle") }
                    }
                }
            }
        }
        .onChange(of: contribution.facts) { header?.update(facts: contribution.facts) }
        .sheet(isPresented: shown(NodeHeader.shared.SHARE)) { ShareSheet(model: model, ref: node) }
        .sheet(isPresented: shown(NodeHeader.shared.REPORT)) { ReportSheet(model: model, ref: node) }
        .sheet(isPresented: shown(NodeHeader.shared.COVER)) { CoverSheet(model: model, node: node) }
        .sheet(isPresented: shown(NodeHeader.shared.MOVE)) { NodeMoveSheet(model: model, ref: node) }
        .sheet(isPresented: shown(NodeHeader.shared.COPY)) { NodeMoveSheet(model: model, ref: node, copying: true) }
        // The system's save sheet takes the written file where the person picks; the temporary copy goes either way.
        .fileMover(isPresented: Binding(get: { downloaded != nil }, set: { if !$0 { cleanUpDownload() } }), file: downloaded) { _ in
            cleanUpDownload()
        }
        .alert(header.map { say($0.deleteTitle) } ?? "", isPresented: $asking) {
            Button(say(.peopleDeleteAccept), role: .destructive) {
                if let header { Task { await model.trash(header) } }
            }
            Button(say(.signinCancel), role: .cancel) {}
        } message: {
            if let header { Text(say(notice: header.deleteQuestion())) }
        }
        // Core's name dialog (`FolderNameDialog`): the name as it is, trimmed on save; a refusal says why and asks again.
        .alert(say(.nodeRename), isPresented: $renaming) {
            TextField(say(.peopleName), text: $newName)
            Button(say(.entrySave)) {
                let name = newName
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
        .alert(failed ?? "", isPresented: Binding(get: { failed != nil }, set: { if !$0 { failed = nil } })) {
            Button(say(.ok), role: .cancel) {}
        }
    }

    /// A sheet's presentation: on while `dialog` is `id`.
    private func shown(_ id: String) -> Binding<Bool> {
        Binding(get: { dialog == id }, set: { if !$0, dialog == id { dialog = nil } })
    }

    private func run(_ id: String) {
        switch id {
        case NodeHeader.shared.FAVORITE:
            // the heart turns at once and back when the write did not land (core's)
            guard let header else { return }
            Task { if let notice = try? await header.toggleFavorite() { failed = say(notice: notice) } }
        case NodeHeader.shared.RENAME:
            newName = node.name
            renameProblem = nil
            renaming = true
        case NodeHeader.shared.DELETE:
            asking = true
        case NodeHeader.shared.DOWNLOAD:
            Task { await download() }
        default:
            if let own = contribution.actions.first(where: { $0.id == id }) { own.run() } else { dialog = id }
        }
    }

    /// A core action's words (core's `NodeHeaderModel.label`).
    private func word(_ id: String) -> String {
        header?.label(id: id, hasCover: model.covers[node.nodeId] != nil).map { say($0) } ?? id
    }

    @ViewBuilder private func label(_ id: String) -> some View {
        switch id {
        case NodeHeader.shared.FAVORITE:
            Label(word(id), systemImage: pinned == true ? "heart.fill" : "heart")
                .foregroundStyle(pinned == true ? Role.favorite : Role.text)
        case NodeHeader.shared.SHARE: Label(word(id), systemImage: "square.and.arrow.up")
        case NodeHeader.shared.RENAME: Label(word(id), systemImage: "pencil")
        case NodeHeader.shared.MOVE: Label(word(id), systemImage: "folder")
        case NodeHeader.shared.COPY: Label(word(id), systemImage: "doc.on.doc")
        case NodeHeader.shared.COVER: Label(word(id), systemImage: "photo")
        case NodeHeader.shared.DOWNLOAD: Label(word(id), systemImage: "arrow.down.circle")
        case NodeHeader.shared.REPORT: Label(word(id), systemImage: "flag")
        case NodeHeader.shared.DELETE: Label(word(id), systemImage: "trash")
        default:
            if let own = contribution.actions.first(where: { $0.id == id }) { Label(own.label, systemImage: own.systemImage) }
        }
    }

    /// The node's ZIP, written to a temporary file, then offered to the system's save sheet.
    private func download() async {
        guard let door = contribution.download else { return }
        downloading = true
        let written = await door()
        downloading = false
        if let written {
            leftOut = written.leftOut
            downloaded = written.url
        } else {
            failed = say(notice: Notices.shared.downloaded(name: node.name, leftOut: nil))
        }
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
                guard let data = try? await chosen.loadTransferable(type: Data.self), let picture = CoverImages.image(data, maxPixels: 2048) else {
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

/// What a delete into the trash says, at the bottom of the shell: "“x” moved to the trash." with its Undo for as long as core
/// offers it (`NodeHeaderModel`, `TRASH_UNDO_MS`, 6 s), or a refusal; it goes by itself.
struct TrashNotice: View {
    @Bindable var model: AppModel

    var body: some View {
        if let notice = model.trashNotice {
            HStack(spacing: 12) {
                Text(notice.message).font(.subheadline).foregroundStyle(Role.text)
                Spacer(minLength: 0)
                if notice.header != nil || notice.move != nil {
                    Button(say(.trashUndo)) { Task { await model.undoTrash() } }.fontWeight(.semibold)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Role.surface, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .shadow(color: .black.opacity(0.12), radius: 8, y: 2)
            .padding(.horizontal, 16)
            .padding(.bottom, 96)
            .task(id: notice.id) {
                // a trash's or a move's undo goes when core ends its offer; any other notice after the same time
                guard notice.header == nil, notice.move == nil else { return }
                try? await Task.sleep(for: .milliseconds(ShellModel.companion.TRASH_UNDO_MS))
                model.dismissTrashNotice(notice.id)
            }
        }
    }
}

