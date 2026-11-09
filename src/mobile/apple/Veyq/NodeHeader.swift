import PhotosUI
import SwiftUI
import VeyqCore

/// What a kind's page hands core's header (the web's `contributeNodeHeader`, its rights function and the page's doors): a
/// subtitle under the name, the application's narrowing of core's rights, its doors (a copy, a download — absent: not
/// offered) and actions of its own. Core builds the rest, the same for every kind (`NodeHeaderActions`).
struct HeaderContribution {
    /// The line under the node's name (a Pot's currency); nil: none.
    var subtitle: String?
    /// Whether the application lets this node's cover change here (a Pot of a switched-off Expenses: no).
    var cover = true
    /// The delete's title and question in the kind's words (`deleteTitle`, `deleteQuestion.ts`).
    let deleteTitle: String
    let deleteQuestion: String
    /// The copy door: copies to the folder chosen (nil: the space's top; then its name), answers why not, or nil once copied.
    var copy: ((String?, String) async -> String?)?
    /// The download door: writes the node's ZIP to a temporary file, its URL once whole.
    var download: (() async -> URL?)?
    /// The application's own actions, merged into core's plan where the kind's order puts them.
    var actions: [OwnAction] = []
}

/// An action of the application's own on its node's header.
struct OwnAction {
    let id: String
    let label: String
    let systemImage: String
    var slot: NodeHeader.Slot = .any
    let run: () -> Void
}

/// A kind's page as the app registers it (the web's `KindRegistration` of `app.kind`): what core's header reads of the kind
/// (it brings a share sheet, it takes a cover), what the kind's page contributes to that header (nil while the page cannot
/// tell yet) and the page's body under it (nil: not built; a folder's is core's level). Core draws the header for every
/// kind, never an application.
struct KindPage {
    let shareable: Bool
    let takesCover: Bool
    let header: @MainActor (NodeAt, AppModel) -> HeaderContribution?
    /// The body of an object of the kind on its bar tab (`key`).
    var body: (@MainActor (ObjectRef, String, AppModel) -> AnyView)?

    /// The pages this build registers, by kind: core's folder and each application's kinds (the composition root).
    static func of(_ kind: String) -> KindPage? {
        switch kind {
        case "FOLDER": folder
        case PotSession.companion.KIND: pot
        default: nil
        }
    }

    private static let folder = KindPage(shareable: true, takesCover: true, header: { node, _ in
        HeaderContribution(
            deleteTitle: String(localized: "Delete Folder"),
            deleteQuestion: String(localized: "“\(node.name)” and everything in it go to the trash.")
        )
    })

    /// Expenses' Pot: its currency under its name, its cover and copy while Expenses is on, its ZIP.
    private static let pot = KindPage(shareable: true, takesCover: true, header: { node, model in
        guard let state = model.pot, state.ref.nodeId == node.nodeId, let view = state.view else { return nil }
        // a cover and a copy write: offered where Expenses is on (the web's `canCopy`, `expensesRights`)
        let writable = view.canWrite
        let ref = state.ref
        return HeaderContribution(
            subtitle: view.currency.map { String(localized: "In \($0)") },
            cover: writable,
            deleteTitle: String(localized: "Delete Pot"),
            deleteQuestion: String(localized: "“\(node.name)” and all its entries go to the trash."),
            copy: writable ? { to, toName in
                switch await model.copyPot(node, to: to, toName: toName) {
                case .copied: nil
                case .nameTaken: String(localized: "There is no free name for the copy there. Rename it first, or choose another place.")
                case .nothingToCopy: String(localized: "Nothing here can be copied by this version of veyq.")
                case .busy: NodeMoveSheet.problemText(.busy)
                case .blocked, .refused: NodeMoveSheet.problemText(.notAllowed)
                default: String(localized: "The copy did not go through. Nothing was left behind; please try again.")
                }
            } : nil,
            download: { await model.downloadPot(ref) }
        )
    }, body: { ref, key, model in AnyView(PotScreen(model: model, ref: ref, tab: key)) })
}

/// Core's header actions on a node of any kind (the web's `NodeHeader` with `coreNodeActions.ts` and `standardNodeActions`,
/// ADR 0110): core's `NodeHeader.plan` decides what is offered and where, from the node's rights, the kind's order
/// (`AppKinds.headerOrder`) and the contribution; up to `maxButtons` are toolbar buttons, the rest one "More" menu whose last
/// section holds the move, the copy, the report and the delete — the delete last and destructive, asked first. Every dialog
/// behind an action is core's one: the share sheet, the move and copy browser, the cover sheet, the rename, the report, the
/// delete.
struct NodeHeaderActions: View {
    @Bindable var model: AppModel
    let node: NodeAt
    let contribution: HeaderContribution
    /// How many actions are toolbar buttons: a level's bar keeps its room for its title (0: one "More" menu).
    var maxButtons: Int32 = 2
    @State private var pinned: Bool?
    @State private var dialog: String?
    @State private var asking = false
    @State private var renaming = false
    @State private var newName = ""
    @State private var renameProblem: String?
    @State private var failed: String?
    @State private var downloading = false
    @State private var downloaded: URL?

    private var plan: NodeHeader.Plan {
        let page = KindPage.of(node.kind)
        let rights = NodeHeader.Rights(
            favorite: pinned != nil,
            share: page?.shareable == true && model.linkRules().on,
            rename: true,
            move: true,
            cover: page?.takesCover == true && contribution.cover,
            copy: contribution.copy != nil,
            download: contribution.download != nil,
            report: model.reportOffered,
            delete: true
        )
        return NodeHeader.shared.plan(
            rights: rights, order: AppKinds.shared.headerOrder(kind: node.kind), maxButtons: maxButtons,
            own: contribution.actions.map { NodeHeader.Action(id: $0.id, slot: $0.slot) }
        )
    }

    var body: some View {
        let plan = plan
        HStack {
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
                    if downloading { ProgressView() } else { Label("More", systemImage: "ellipsis.circle") }
                }
            }
        }
        .onAppear { pinned = model.isFavorite(node) }
        .sheet(isPresented: shown(NodeHeader.shared.SHARE)) { ShareSheet(model: model, ref: node) }
        .sheet(isPresented: shown(NodeHeader.shared.REPORT)) { ReportSheet(model: model, ref: node) }
        .sheet(isPresented: shown(NodeHeader.shared.COVER)) { CoverSheet(model: model, node: node) }
        .sheet(isPresented: shown(NodeHeader.shared.MOVE)) { NodeMoveSheet(model: model, ref: node) }
        .sheet(isPresented: shown(NodeHeader.shared.COPY)) { NodeMoveSheet(model: model, ref: node, copy: contribution.copy) }
        // The system's save sheet takes the written file where the person picks; the temporary copy goes either way.
        .fileMover(isPresented: Binding(get: { downloaded != nil }, set: { if !$0 { cleanUpDownload() } }), file: downloaded) { _ in
            cleanUpDownload()
        }
        .alert(contribution.deleteTitle, isPresented: $asking) {
            Button("Delete", role: .destructive) { Task { await model.trash(node) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(contribution.deleteQuestion)
        }
        // Core's name dialog (`FolderNameDialog`): the name as it is, trimmed on save; a refusal says why and asks again.
        .alert("Rename", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Save") {
                let name = newName
                Task {
                    let (problem, linksFailed) = await model.rename(node, to: name)
                    if let problem {
                        renameProblem = problem
                        renaming = true
                    } else if linksFailed {
                        failed = String(localized: "Renamed. Some links to it could not be updated and still show the old name to their guests.")
                    }
                }
            }
            .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
            Button("Cancel", role: .cancel) {}
        } message: {
            if let renameProblem { Text(renameProblem) }
        }
        .alert(failed ?? "", isPresented: Binding(get: { failed != nil }, set: { if !$0 { failed = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    /// A sheet's presentation: on while `dialog` is `id`.
    private func shown(_ id: String) -> Binding<Bool> {
        Binding(get: { dialog == id }, set: { if !$0, dialog == id { dialog = nil } })
    }

    private func run(_ id: String) {
        switch id {
        case NodeHeader.shared.FAVORITE:
            guard let pinned else { return }
            self.pinned = !pinned
            Task {
                if await !model.toggleFavorite(node) {
                    self.pinned = model.isFavorite(node)
                    failed = String(localized: "The favorite could not be saved. Try again.")
                }
            }
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

    @ViewBuilder private func label(_ id: String) -> some View {
        switch id {
        case NodeHeader.shared.FAVORITE:
            Label(pinned == true ? "Remove from Favorites" : "Add to Favorites", systemImage: pinned == true ? "heart.fill" : "heart")
                .foregroundStyle(pinned == true ? Role.favorite : Role.text)
        case NodeHeader.shared.SHARE: Label(ShareWords.of(node.kind).title, systemImage: "square.and.arrow.up")
        case NodeHeader.shared.RENAME: Label("Rename", systemImage: "pencil")
        case NodeHeader.shared.MOVE: Label("Move to…", systemImage: "folder")
        case NodeHeader.shared.COPY: Label("Copy to…", systemImage: "doc.on.doc")
        case NodeHeader.shared.COVER: Label(model.covers[node.nodeId] != nil ? "Change Cover…" : "Set Cover…", systemImage: "photo")
        case NodeHeader.shared.DOWNLOAD: Label("Download", systemImage: "arrow.down.circle")
        case NodeHeader.shared.REPORT: Label("Report", systemImage: "flag")
        case NodeHeader.shared.DELETE: Label(contribution.deleteTitle, systemImage: "trash")
        default:
            if let own = contribution.actions.first(where: { $0.id == id }) { Label(own.label, systemImage: own.systemImage) }
        }
    }

    /// The node's ZIP, written to a temporary file, then offered to the system's save sheet.
    private func download() async {
        guard let door = contribution.download else { return }
        downloading = true
        let url = await door()
        downloading = false
        if let url { downloaded = url } else { failed = String(localized: "“\(node.name)” could not be downloaded. Please try again.") }
    }

    private func cleanUpDownload() {
        if let url = downloaded { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        downloaded = nil
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
                .accessibilityLabel("Change Cover…")
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
                            Text("Zoom")
                        } minimumValueLabel: {
                            Image(systemName: "minus.magnifyingglass")
                        } maximumValueLabel: {
                            Image(systemName: "plus.magnifyingglass")
                        }
                        .disabled(saving)
                    }
                } footer: {
                    Text("Upload an image as the cover, then drag and zoom to frame it.")
                }
                Section {
                    PhotosPicker(selection: $item, matching: .images) {
                        Label("Choose an Image", systemImage: "photo.on.rectangle")
                    }
                    .disabled(saving)
                    if hasCover {
                        Button("Remove Cover", systemImage: "trash", role: .destructive) { Task { await finish { await model.removeCover(node) } } }
                            .disabled(saving)
                    }
                }
                if saving { ProgressView() }
                if let problem { Text(problem).foregroundStyle(Role.danger) }
            }
            .navigationTitle("Cover")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(saving) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }.disabled(saving || frame == nil || pose == nil)
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
                    problem = String(localized: "This image could not be read. Try a JPEG or PNG.")
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
        if ok { dismiss() } else { problem = String(localized: "The cover could not be saved. Please try again.") }
    }
}

/// What a delete into the trash says, at the bottom of the shell: "“x” moved to the trash." with its Undo for as long as the
/// web offers it (`TRASH_UNDO_MS`, 6 s), or a refusal; it goes by itself.
struct TrashNotice: View {
    @Bindable var model: AppModel

    var body: some View {
        if let notice = model.trashNotice {
            HStack(spacing: 12) {
                Text(notice.message).font(.subheadline).foregroundStyle(Role.text)
                Spacer(minLength: 0)
                if notice.node != nil || notice.moveBack != nil {
                    Button("Undo") { Task { await model.undoTrash() } }.fontWeight(.semibold)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Role.surface, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .shadow(color: .black.opacity(0.12), radius: 8, y: 2)
            .padding(.horizontal, 16)
            .padding(.bottom, 96)
            .task(id: notice.id) {
                try? await Task.sleep(for: .seconds(6))
                model.dismissTrashNotice(notice.id)
            }
        }
    }
}
