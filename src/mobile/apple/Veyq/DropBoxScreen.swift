import SwiftUI
import VeyqCore

/// A drop box's page for a member (Depot's `DropBoxDetail.vue`): every guest's folder in the order they came, a name more
/// than one gave numbered as the web numbers it, with the files in it — each opens its file's page (download, delete) —
/// and the folder's delete, asked first, into the trash (a context menu, as Files' rows have). Nobody's yet: the web's
/// sentence pointing at its links.
struct DropBoxScreen: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    @State private var folders: [DropBoxes.GuestFolder]?
    @State private var deleting: DropBoxes.GuestFolder?

    var body: some View {
        List {
            NodeHeroRow()
            if let folders {
                if folders.isEmpty {
                    Text("Nobody has dropped anything yet. Share a link to collect files.").foregroundStyle(Role.muted)
                } else {
                    Text("Open a file to download or delete it. To remove everything someone sent, delete their folder.")
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
                                    Text(f.files.count == 1 ? "1 file" : "\(f.files.count) files").font(.footnote).foregroundStyle(Role.muted)
                                }
                                Spacer()
                                Menu {
                                    Button("Delete \(f.label)", systemImage: "trash", role: .destructive) { deleting = f }
                                } label: {
                                    Label("More", systemImage: "ellipsis.circle").labelStyle(.iconOnly)
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
        .task(id: model.rowDetails.count) { folders = await model.dropBoxFolders(scopeId: ref.scopeId, nodeId: ref.nodeId) }
        .alert("Delete \(deleting?.label ?? "")", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { f in
            Button("Delete", role: .destructive) {
                Task {
                    await model.trash(NodeAt(scopeId: f.node.scopeId, nodeId: f.node.id, kind: f.node.kind, name: f.label))
                    folders = await model.dropBoxFolders(scopeId: ref.scopeId, nodeId: ref.nodeId)
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: { f in
            Text("“\(f.label)” and everything in it move to the trash. You can restore it from there until it is removed for good.")
        }
    }
}
