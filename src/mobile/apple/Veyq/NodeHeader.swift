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

/// Core's cover dialog (the web's `CoverDialog`): the node's cover as it shows, 16:9, and the one place it is chosen, changed
/// and removed — a picture from the system's photo picker, made the cover on this device; Remove where there is one.
struct CoverSheet: View {
    @Bindable var model: AppModel
    let node: NodeAt
    @Environment(\.dismiss) private var dismiss
    @State private var item: PhotosPickerItem?
    @State private var saving = false
    @State private var problem: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ZStack {
                        Role.surface
                        if let cover = model.covers[node.nodeId]?.image {
                            Image(decorative: cover, scale: 1).resizable().scaledToFill()
                        } else {
                            Image(systemName: Names.kindSymbol(node.kind)).font(.largeTitle).foregroundStyle(Role.muted)
                        }
                    }
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                    .listRowInsets(EdgeInsets())
                } footer: {
                    Text("Choose a picture as the cover.")
                }
                Section {
                    PhotosPicker(selection: $item, matching: .images) {
                        Label("Choose a Picture", systemImage: "photo.on.rectangle")
                    }
                    .disabled(saving)
                    if model.covers[node.nodeId] != nil {
                        Button("Remove Cover", systemImage: "trash", role: .destructive) { Task { await remove() } }
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
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(saving) } }
        }
        .onChange(of: item) { _, picked in
            guard let picked else { return }
            item = nil
            Task { await set(picked) }
        }
    }

    private func set(_ picked: PhotosPickerItem) async {
        saving = true
        problem = nil
        let ok: Bool
        if let data = try? await picked.loadTransferable(type: Data.self) { ok = await model.setCover(node, data: data) } else { ok = false }
        saving = false
        if ok { dismiss() } else { problem = String(localized: "The cover could not be saved. Please try again.") }
    }

    private func remove() async {
        saving = true
        problem = nil
        let ok = await model.removeCover(node)
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
