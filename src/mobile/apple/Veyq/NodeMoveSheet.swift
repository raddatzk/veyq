import SwiftUI
import VeyqCore

/// Core's move browser (the web's `MoveNodeDialog.vue` and `MoveBrowser.vue`, owner decision 2026-10-14): it opens at the
/// node's place, shows one place at a time under a trail up to the space's root, lists the places one may go into or move
/// into — the node itself and what lies below it listed but not to be opened — and offers "Move here" where the place
/// takes it, else says why.
struct NodeMoveSheet: View {
    let model: AppModel
    let ref: NodeAt
    /// The browser as "Copy to…" (the web's `CopyNodesDialog`), its kind's copy door: copies to the place chosen (nil: the
    /// space's top; then its name), answers why not, or nil once copied. A copy may go where its original is. Nil: the move.
    var copy: ((String?, String) async -> String?)? = nil
    /// What the copy leaves behind and out, read before anything is written (the kind's; nil: core's line alone).
    var notes: (() async -> CopyNotes)? = nil
    @State private var read: CopyNotes?
    private var copying: Bool { copy != nil }
    @Environment(\.dismiss) private var dismiss
    @State private var tree: [NodeRef]?
    @State private var at: String?
    @State private var moving = false
    @State private var problem: String?
    /// A copy the tenant has no room for, said before anything is written (the web's `CopyNodesDialog` storage check).
    @State private var noRoom: String?
    /// Moving a guest's folder out ends it (the web's `endsIdentity`): asked first, there is no way back.
    @State private var confirming = false

    private var rootName: String { Names.spaceName(model.spaces.first { $0.scopeId == ref.scopeId }) }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(copying ? "Copy “\(ref.name)”" : "Move “\(ref.name)”")
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(moving) }
                }
        }
        .task {
            let read = await model.scopeNodes(ref.scopeId)
            tree = read
            if let moved = read.first(where: { $0.nodeId == ref.nodeId }) { at = NodeMove.shared.start(tree: read, moved: moved) }
        }
    }

    @ViewBuilder private var content: some View {
        if let tree, let moved = tree.first(where: { $0.nodeId == ref.nodeId }) {
            let places = NodeMove.shared.places(tree: tree, moved: moved, at: at, allows: model.placement)
            let block = NodeMove.shared.hereBlock(tree: tree, moved: moved, at: at, allows: model.placement, copy: copying)
            List {
                Section {
                    if copying {
                        Text("Open the place the copy should go to and choose “Copy here”.")
                            .font(.subheadline).foregroundStyle(Role.muted)
                    } else {
                        Text("Open the place it should go to and choose “Move here”. Everything inside it moves along and stays readable.")
                            .font(.subheadline).foregroundStyle(Role.muted)
                    }
                }
                .listRowBackground(Color.clear)
                if copying {
                    // what a copy leaves behind, in the web's words (core's, then the kind's `copyLosses`)
                    Section("What does not come along") {
                        Text("Shares, favourites, the trash and the original upload times. You become the owner of the copy.")
                        ForEach(read?.losses ?? [], id: \.self) { Text($0) }
                    }
                    .font(.footnote).foregroundStyle(Role.muted)
                    .task {
                        read = await notes?()
                        noRoom = await model.copyNoRoom(ref)
                    }
                    if let left = read?.leftOut, !left.isEmpty {
                        Section("Left out, because this version cannot copy them:") {
                            ForEach(left, id: \.self) { Text($0) }
                        }
                        .font(.footnote).foregroundStyle(Role.muted)
                    }
                }
                Section {
                    // The trail: the space's root, then each folder down to the place on screen.
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 4) {
                            let trail: [NodeRef?] = NodeMove.shared.trail(tree: tree, at: at).map { $0 as? NodeRef }
                            ForEach(Array(trail.enumerated()), id: \.offset) { i, place in
                                if i > 0 { Image(systemName: "chevron.right").font(.caption).foregroundStyle(Role.muted) }
                                Button(place?.name ?? rootName) { at = place?.nodeId; problem = nil }
                                    .fontWeight(i == trail.count - 1 ? .bold : .regular)
                                    .disabled(i == trail.count - 1 || moving)
                            }
                        }
                    }
                    .buttonStyle(.borderless)
                }
                Section {
                    if places.isEmpty {
                        Text("No place here can take “\(ref.name)”.").foregroundStyle(Role.muted)
                    }
                    ForEach(places, id: \.node.nodeId) { place in
                        Button { at = place.node.nodeId; problem = nil } label: {
                            HStack {
                                Label { Text(place.node.name) } icon: { Tile(symbol: Names.kindSymbol(place.node.kind)) }
                                Spacer()
                                if let blocked = place.blocked {
                                    Text(blocked == .inside ? "inside the item" : "the item itself").font(.caption).foregroundStyle(Role.muted)
                                } else {
                                    Image(systemName: "chevron.right").foregroundStyle(Role.muted)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(place.blocked != nil || moving)
                        .opacity(place.blocked == nil ? 1 : 0.45)
                    }
                }
                Section {
                    if let problem = problem ?? noRoom {
                        Text(problem).font(.caption).foregroundStyle(Role.danger)
                    } else if let block {
                        Text(Self.hereText(block, copying: copying)).font(.caption).foregroundStyle(Role.muted)
                    }
                    Button {
                        if !copying && NodeMove.shared.endsIdentity(node: moved) {
                            confirming = true
                        } else {
                            Task { await move(to: at, from: moved.parentId) }
                        }
                    } label: {
                        HStack { Spacer(); if moving { ProgressView() } else { Text(copying ? "Copy here" : "Move here").fontWeight(.semibold) }; Spacer() }
                    }
                    .disabled(block != nil || moving || noRoom != nil)
                }
            }
            .alert("Move a guest’s folder out of the drop box?", isPresented: $confirming) {
                Button("Move out") { Task { await move(to: at, from: moved.parentId) } }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("“\(ref.name)” becomes an ordinary folder: the guest who sent it can no longer add anything to it, and it cannot be moved back into the drop box. Everything in it stays readable for you.")
            }
        } else {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func move(to: String?, from: String?) async {
        moving = true
        problem = nil
        let toName = to.flatMap { t in tree?.first { $0.nodeId == t }?.name } ?? rootName
        if let copy {
            let refused = await copy(to, toName)
            moving = false
            if let refused { problem = refused } else { dismiss() }
            return
        }
        let outcome = await model.move(ref, to: to, toName: toName, from: from)
        moving = false
        if outcome == .moved { dismiss() } else { problem = Self.problemText(outcome) }
    }

    private static func hereText(_ block: NodeMove.Block, copying: Bool) -> String {
        switch block {
        case .identityFolder: copying ? String(localized: "Nothing can be copied into a guest’s folder.") : String(localized: "Nothing can be moved into a guest’s folder.")
        case .inside: String(localized: "It can’t be moved into something inside it.")
        case .current: String(localized: "It is already here.")
        case .kind: String(localized: "This place can’t hold it.")
        default: String(localized: "It can’t be moved into itself.")
        }
    }

    /// Why a move did not go through, in the web's words (`node.move.refused.*`).
    static func problemText(_ outcome: NodeMove.Outcome) -> String {
        switch outcome {
        case .nameTaken: String(localized: "Something with this name already exists there. Rename it first, or choose another place.")
        case .notAllowed, .blocked: String(localized: "It can’t be placed there: that place doesn’t take this kind of item.")
        case .gone: String(localized: "It, or the place you chose, is no longer there.")
        case .conflict: String(localized: "Someone else moved or renamed it at the same time. The view has been reloaded — please try again.")
        case .scopeDeleting: String(localized: "This space is being deleted; nothing can be moved.")
        case .busy: String(localized: "The space’s new lock is still going in. Try again in a moment.")
        default: String(localized: "Moving didn’t work. Please try again.")
        }
    }
}

/// Each kind's placement (`registry.placementAllows`), core's `AppKinds.placement` as the move browser asks it.
final class KindPlacement: NodeMovePlacementAllows {
    func allows(kind: String, parentKind: String?) -> Bool {
        AppKinds.shared.placement.allows(kind: kind, parentKind: parentKind)
    }
}
