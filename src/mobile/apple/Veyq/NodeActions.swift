import PhotosUI
import SwiftUI
import VeyqCore

/// Core's actions on an open Pot's header (the web's `coreNodeActions.ts` and `standardNodeActions`, ADR 0110): the heart as
/// a button, first, while the space's pin set is loaded; the rest in one "more" menu, the delete last and destructive, asked
/// first in its kind's words. The heart turns at once and turns back when the write did not land.
struct PotHeaderActions: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    @State private var pinned: Bool?
    @State private var asking = false
    @State private var failed: String?
    @State private var renaming = false
    @State private var newName = ""
    @State private var renameProblem: String?
    @State private var moving = false
    @State private var copying = false
    @State private var pickingCover = false
    @State private var coverItem: PhotosPickerItem?
    @State private var sharing = false
    @State private var reporting = false
    @State private var downloading = false
    @State private var downloaded: URL?

    /// The web's ZIP of the Pot, written to a temporary file, then offered to the system's save sheet.
    private func download() async {
        downloading = true
        let url = await model.downloadPot(ref)
        downloading = false
        if let url { downloaded = url } else { failed = String(localized: "“\(ref.name)” could not be downloaded. Please try again.") }
    }

    private func cleanUpDownload() {
        if let url = downloaded { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        downloaded = nil
    }

    var body: some View {
        HStack {
            if let pinned {
                Button {
                    self.pinned = !pinned
                    Task {
                        if await !model.toggleFavorite(ref.at) {
                            self.pinned = model.isFavorite(ref.at)
                            failed = String(localized: "The favorite could not be saved. Try again.")
                        }
                    }
                } label: {
                    Image(systemName: pinned ? "heart.fill" : "heart").foregroundStyle(pinned ? Role.favorite : Role.text)
                }
                .accessibilityLabel(pinned ? "Remove favorite" : "Favorite")
            }
            // The Pot's links (core's share action, a header button as the web's): offered where the tenant has links on.
            if model.linkRules().on {
                Button { sharing = true } label: { Image(systemName: "square.and.arrow.up") }
                    .accessibilityLabel("Share this Pot")
            }
            Menu {
                // The rename first, the delete last and destructive (`splitRowActions`).
                Button("Rename", systemImage: "pencil") { newName = ref.name; renameProblem = nil; renaming = true }
                Button("Move to…", systemImage: "folder") { moving = true }
                // a copy writes a new Pot: offered where Expenses is on (the web's `canCopy`)
                if model.pot?.view?.canWrite == true {
                    // core's cover action: the picture from the system's photo picker, made the cover on this device
                    let has = model.covers[ref.nodeId] != nil
                    Button(has ? "Change Cover…" : "Set Cover…", systemImage: "photo") { pickingCover = true }
                    if has {
                        Button("Remove Cover", systemImage: "minus.circle") {
                            Task { if await !model.removeCover(ref.at) { failed = String(localized: "The cover could not be saved. Please try again.") } }
                        }
                    }
                    Button("Copy to…", systemImage: "doc.on.doc") { copying = true }
                }
                Button("Download", systemImage: "arrow.down.circle") { Task { await download() } }
                    .disabled(downloading)
                // the menu-only group, after a separator: report, then delete (`splitRowActions`)
                Divider()
                if model.reportOffered {
                    Button("Report", systemImage: "flag") { reporting = true }
                }
                Button("Delete Pot", systemImage: "trash", role: .destructive) { asking = true }
            } label: {
                if downloading { ProgressView() } else { Label("More", systemImage: "ellipsis.circle") }
            }
        }
        .onAppear { pinned = model.isFavorite(ref.at) }
        .sheet(isPresented: $moving) { NodeMoveSheet(model: model, ref: ref.at) }
        .sheet(isPresented: $copying) { NodeMoveSheet(model: model, ref: ref.at, copy: true) }
        .photosPicker(isPresented: $pickingCover, selection: $coverItem, matching: .images)
        .onChange(of: coverItem) { _, item in
            guard let item else { return }
            coverItem = nil
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self), await model.setCover(ref.at, data: data) else {
                    failed = String(localized: "The cover could not be saved. Please try again.")
                    return
                }
            }
        }
        .sheet(isPresented: $sharing) { PotShareSheet(model: model, ref: ref) }
        .sheet(isPresented: $reporting) { ReportSheet(model: model, ref: ref.at) }
        // The system's save sheet takes the written file where the person picks; the temporary copy goes either way.
        .fileMover(isPresented: Binding(get: { downloaded != nil }, set: { if !$0 { cleanUpDownload() } }), file: downloaded) { _ in
            cleanUpDownload()
        }
        .alert("Delete Pot", isPresented: $asking) {
            Button("Delete", role: .destructive) { Task { await model.trash(ref.at) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("“\(ref.name)” and all its entries go to the trash.")
        }
        // Core's name dialog (`FolderNameDialog`): the name as it is, trimmed on save; a refusal says why and asks again.
        .alert("Rename", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Save") {
                let name = newName
                Task {
                    let (problem, linksFailed) = await model.rename(ref.at, to: name)
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
}

/// What a delete into the trash says, at the bottom of the shell: "“x” moved to the trash." with its Undo for as long as the
/// web offers it (`TRASH_UNDO_MS`, 6 s), or a refusal; it goes by itself.
/// A folder's core actions on Home (the web's level header: `standardNodeActions` and `coreNodeActions` for a folder), in one
/// "more" menu so a level's bar keeps room for its title: the heart, then rename, move, its cover, report and delete. Its
/// links, download and copy are not offered yet.
struct FolderHeaderActions: View {
    @Bindable var model: AppModel
    let folder: NodeAt
    @State private var pinned: Bool?
    @State private var asking = false
    @State private var failed: String?
    @State private var renaming = false
    @State private var newName = ""
    @State private var renameProblem: String?
    @State private var moving = false
    @State private var pickingCover = false
    @State private var coverItem: PhotosPickerItem?
    @State private var reporting = false

    var body: some View {
        // One "more" menu, the heart its first entry (as Files' folder menu): a level's bar keeps room for its title.
        Menu {
                if let pinned {
                    Button(pinned ? "Remove from Favorites" : "Add to Favorites", systemImage: pinned ? "heart.slash" : "heart") {
                        self.pinned = !pinned
                        Task {
                            if await !model.toggleFavorite(folder) {
                                self.pinned = model.isFavorite(folder)
                                failed = String(localized: "The favorite could not be saved. Try again.")
                            }
                        }
                    }
                }
                Button("Rename", systemImage: "pencil") { newName = folder.name; renameProblem = nil; renaming = true }
                Button("Move to…", systemImage: "folder") { moving = true }
                let has = model.covers[folder.nodeId] != nil
                Button(has ? "Change Cover…" : "Set Cover…", systemImage: "photo") { pickingCover = true }
                if has {
                    Button("Remove Cover", systemImage: "minus.circle") {
                        Task { if await !model.removeCover(folder) { failed = String(localized: "The cover could not be saved. Please try again.") } }
                    }
                }
                Divider()
                if model.reportOffered {
                    Button("Report", systemImage: "flag") { reporting = true }
                }
                Button("Delete Folder", systemImage: "trash", role: .destructive) { asking = true }
        } label: {
            Label("More", systemImage: "ellipsis.circle")
        }
        .onAppear { pinned = model.isFavorite(folder) }
        .sheet(isPresented: $moving) { NodeMoveSheet(model: model, ref: folder) }
        .photosPicker(isPresented: $pickingCover, selection: $coverItem, matching: .images)
        .onChange(of: coverItem) { _, item in
            guard let item else { return }
            coverItem = nil
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self), await model.setCover(folder, data: data) else {
                    failed = String(localized: "The cover could not be saved. Please try again.")
                    return
                }
            }
        }
        .sheet(isPresented: $reporting) { ReportSheet(model: model, ref: folder) }
        .alert("Delete Folder", isPresented: $asking) {
            Button("Delete", role: .destructive) { Task { await model.trash(folder) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("“\(folder.name)” and everything in it go to the trash.")
        }
        .alert("Rename", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Save") {
                let name = newName
                Task {
                    let (problem, _) = await model.rename(folder, to: name)
                    if let problem {
                        renameProblem = problem
                        renaming = true
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
}

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
