import SwiftUI
import VeyqCore

/// A drop box's page for a member (Depot's `DropBoxDetail.vue`), drawn from core's `DropBoxPage`: every guest's folder in
/// the order they came, a name more than one gave numbered as the web numbers it, with the files in it — each opens its
/// file's page (download, delete) — the folder's move out (core's move browser, which asks first: the folder becomes an
/// ordinary one) and its delete, asked first, into the trash (a menu on the folder, as Files' rows have). The folders are
/// read anew with the shell's lists. Nobody's yet: the web's sentence pointing at its links.
struct DropBoxScreen: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    @State private var box = DropBoxBox()
    @State private var movingOut: DropBoxes.GuestFolder?
    @Environment(\.scenePhase) private var phase

    var body: some View {
        List {
            NodeHeroRow()
            if let folders = box.state?.folders {
                if folders.isEmpty {
                    Text(say(.dropboxEmpty)).foregroundStyle(Role.muted)
                } else {
                    Text(say(.dropboxHint))
                        .font(.footnote).foregroundStyle(Role.muted).listRowBackground(Color.clear)
                    ForEach(folders, id: \.node.id) { f in
                        Section {
                            ForEach(f.files, id: \.id) { file in
                                Button {
                                    if let object = file.asObject() { model.open(object) }
                                } label: {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(file.name).foregroundStyle(Role.text)
                                        if let details = model.rowDetails[file.id], !details.isEmpty {
                                            Text(details.joined(separator: " · ")).font(.footnote).foregroundStyle(Role.muted)
                                        }
                                    }
                                }
                            }
                        } header: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(f.label).font(.headline)
                                    Text(say(.dropboxGuestFiles, f.files.count)).font(.footnote).foregroundStyle(Role.muted)
                                }
                                Spacer()
                                Menu {
                                    Button(say(.dropboxMoveFolder, f.label), systemImage: "folder") { movingOut = f }
                                    Button(say(.dropboxDeleteFolder, f.label), systemImage: "trash", role: .destructive) { box.page?.askDelete(folder: f) }
                                } label: {
                                    Label(say(.nodeMore), systemImage: "ellipsis.circle").labelStyle(.iconOnly)
                                }
                            }
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(ref.name)
        .task(id: ref.nodeId) {
            guard box.page == nil, let page = model.dropBoxPage(NodeAt(scopeId: ref.scopeId, nodeId: ref.nodeId, kind: ref.kind, name: ref.name)) else { return }
            box.start(page)
        }
        .onDisappear { box.stop() }
        // live while the page is on screen and the app active: a guest's new folder shows up (core's poll)
        .task(id: DropBoxLiveKey(started: box.page != nil, active: phase == .active)) {
            if phase == .active { await box.live() }
        }
        .sheet(item: Binding(get: { movingOut.map { MovingOut(folder: $0) } }, set: { if $0 == nil { movingOut = nil } })) { m in
            if let page = box.page { NodeMoveSheet(model: model, ref: page.at(folder: m.folder)) }
        }
        // the question is the page's state: a binding that cancelled on dismissal would run before the button's action, so
        // only Cancel cancels, and the action hands the folder it confirmed
        .alert(say(.dropboxDeleteFolder, box.state?.deleting?.label ?? ""), isPresented: Binding(get: { box.state?.deleting != nil }, set: { _ in }), presenting: box.state?.deleting) { f in
            Button(say(.dropboxDeleteFolderAccept), role: .destructive) {
                guard let page = box.page, let node = page.delete(folder: f), let header = model.header(node, facts: page.folderFacts) else { return }
                Task { await model.trash(header) }
            }
            Button(say(.signinCancel), role: .cancel) { box.page?.cancelAsk() }
        } message: { f in
            Text(say(.dropboxDeleteFolderConfirm, f.label))
        }
    }
}

/// Core's drop box page held for SwiftUI: its state mirrored on the main actor as it changes; closed with the view.
@MainActor
@Observable
final class DropBoxBox {
    private(set) var page: DropBoxPage?
    private(set) var state: DropBoxPage.State?
    @ObservationIgnored private var watch: DropBoxPage.Watch?

    func start(_ page: DropBoxPage) {
        self.page = page
        state = page.current
        watch = page.watch { @Sendable _ in
            Task { @MainActor [weak self] in
                if let current = self?.page?.current { self?.state = current }
            }
        }
    }

    func stop() {
        watch?.stop()
        page?.close()
        page = nil
    }

    /// The page's live poll (core's `DropBoxPage.live`, run by core) for as long as the calling task runs; its
    /// cancellation stops the poll.
    func live() async {
        guard let live = page?.startLive() else { return }
        while !Task.isCancelled { try? await Task.sleep(for: .seconds(3600)) }
        live.stop()
    }
}

/// When a drop box's poll runs: once its page started, while the app is active.
private struct DropBoxLiveKey: Hashable {
    let started: Bool
    let active: Bool
}

/// A guest's folder the move browser is open for (a sheet's item).
private struct MovingOut: Identifiable {
    let folder: DropBoxes.GuestFolder
    var id: String { folder.node.id }
}
