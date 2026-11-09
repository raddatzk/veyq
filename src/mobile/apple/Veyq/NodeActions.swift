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
    @State private var sharing = false

    var body: some View {
        HStack {
            if let pinned {
                Button {
                    self.pinned = !pinned
                    Task {
                        if await !model.toggleFavorite(ref) {
                            self.pinned = model.isFavorite(ref)
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
                Button("Delete Pot", systemImage: "trash", role: .destructive) { asking = true }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .accessibilityLabel("More")
        }
        .onAppear { pinned = model.isFavorite(ref) }
        .sheet(isPresented: $moving) { NodeMoveSheet(model: model, ref: ref) }
        .sheet(isPresented: $sharing) { PotShareSheet(model: model, ref: ref) }
        .alert("Delete Pot", isPresented: $asking) {
            Button("Delete", role: .destructive) { Task { await model.trash(ref) } }
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
                    let (problem, linksFailed) = await model.rename(ref, to: name)
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
