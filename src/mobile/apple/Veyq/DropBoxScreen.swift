import SwiftUI
import VeyqCore

/// A drop box's page for a member (Depot's `DropBoxDetail.vue`): every guest's folder in the order they came, a name more
/// than one gave numbered as the web numbers it, with the files in it — each opens its file's page (download, delete) —
/// the folder's move out (core's move browser, which asks first: the folder becomes an ordinary one) and its delete, asked
/// first, into the trash (a menu on the folder, as Files' rows have). Nobody's yet: the web's
/// sentence pointing at its links.
struct DropBoxScreen: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    @State private var folders: [DropBoxes.GuestFolder]?
    @State private var deleting: DropBoxes.GuestFolder?
    @State private var movingOut: DropBoxes.GuestFolder?

    var body: some View {
        List {
            NodeHeroRow()
            if let folders {
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
                                    Button(say(.peopleDelete, f.label), systemImage: "trash", role: .destructive) { deleting = f }
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
        // read again whenever the lists are
        .task(id: model.listsRevision) { folders = await model.dropBoxFolders(scopeId: ref.scopeId, nodeId: ref.nodeId) }
        .sheet(item: Binding(get: { movingOut.map { MovingOut(folder: $0) } }, set: { if $0 == nil { movingOut = nil } })) { m in
            NodeMoveSheet(model: model, ref: NodeAt(scopeId: m.folder.node.scopeId, nodeId: m.folder.node.id, kind: m.folder.node.kind, name: m.folder.label))
                .onDisappear { Task { folders = await model.dropBoxFolders(scopeId: ref.scopeId, nodeId: ref.nodeId) } }
        }
        .alert(say(.dropboxDeleteFolder, deleting?.label ?? ""), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { f in
            Button(say(.peopleDeleteAccept), role: .destructive) {
                Task {
                    await model.trash(NodeAt(scopeId: f.node.scopeId, nodeId: f.node.id, kind: f.node.kind, name: f.label))
                    folders = await model.dropBoxFolders(scopeId: ref.scopeId, nodeId: ref.nodeId)
                }
            }
            Button(say(.signinCancel), role: .cancel) {}
        } message: { f in
            Text(say(.dropboxDeleteFolderConfirm, f.label))
        }
    }
}

/// A guest's folder the move browser is open for (a sheet's item).
private struct MovingOut: Identifiable {
    let folder: DropBoxes.GuestFolder
    var id: String { folder.node.id }
}
