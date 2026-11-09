import SwiftUI
import VeyqCore

/// Core's move browser (the web's `MoveNodeDialog.vue` and `MoveBrowser.vue`, owner decision 2026-10-14): it opens at the
/// node's place, shows one place at a time under a trail up to the space's root, lists the places one may go into or move
/// into — the node itself and what lies below it listed but not to be opened — and offers "Move here" where the place
/// takes it, else says why.
struct NodeMoveSheet: View {
    let model: AppModel
    let ref: NodeAt
    /// The browser as "Copy to…" (the web's `CopyNodesDialog`): a copy may go where its original is.
    var copy = false
    @Environment(\.dismiss) private var dismiss
    @State private var tree: [NodeRef]?
    @State private var at: String?
    @State private var moving = false
    @State private var problem: String?

    private var rootName: String { Names.spaceName(model.spaces.first { $0.scopeId == ref.scopeId }) }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(copy ? "Copy “\(ref.name)”" : "Move “\(ref.name)”")
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
            let block = NodeMove.shared.hereBlock(tree: tree, moved: moved, at: at, allows: model.placement, copy: copy)
            List {
                Section {
                    if copy {
                        Text("Open the place the copy should go to and choose “Copy here”.")
                            .font(.subheadline).foregroundStyle(Role.muted)
                    } else {
                        Text("Open the place it should go to and choose “Move here”. Everything inside it moves along and stays readable.")
                            .font(.subheadline).foregroundStyle(Role.muted)
                    }
                }
                .listRowBackground(Color.clear)
                if copy {
                    // what a copy leaves behind, in the web's words (core's, then the kind's `copyLosses`)
                    Section("What does not come along") {
                        Text("Shares, favourites, the trash and the original upload times. You become the owner of the copy.")
                        Text("The history of each entry and the deleted entries: a copy starts every entry at its current version.")
                        Text("A Pot’s cover.")
                    }
                    .font(.footnote).foregroundStyle(Role.muted)
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
                    if let problem {
                        Text(problem).font(.caption).foregroundStyle(Role.danger)
                    } else if let block {
                        Text(Self.hereText(block)).font(.caption).foregroundStyle(Role.muted)
                    }
                    Button {
                        Task { await move(to: at, from: moved.parentId) }
                    } label: {
                        HStack { Spacer(); if moving { ProgressView() } else { Text(copy ? "Copy here" : "Move here").fontWeight(.semibold) }; Spacer() }
                    }
                    .disabled(block != nil || moving)
                }
            }
        } else {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func move(to: String?, from: String?) async {
        moving = true
        problem = nil
        let toName = to.flatMap { t in tree?.first { $0.nodeId == t }?.name } ?? rootName
        if copy {
            let outcome = await model.copyPot(ref, to: to, toName: toName)
            moving = false
            if outcome == .copied { dismiss() } else { problem = Self.copyProblem(outcome) }
            return
        }
        let outcome = await model.move(ref, to: to, toName: toName, from: from)
        moving = false
        if outcome == .moved { dismiss() } else { problem = Self.problemText(outcome) }
    }

    /// Why a copy did not go through.
    private static func copyProblem(_ outcome: PotCopy.Outcome) -> String {
        switch outcome {
        case .nameTaken: String(localized: "There is no free name for the copy there. Rename it first, or choose another place.")
        case .nothingToCopy: String(localized: "Nothing here can be copied by this version of veyq.")
        case .busy: problemText(.busy)
        case .blocked, .refused: problemText(.notAllowed)
        default: String(localized: "The copy did not go through. Nothing was left behind; please try again.")
        }
    }

    private static func hereText(_ block: NodeMove.Block) -> String {
        switch block {
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

/// Each kind's placement (`registry.placementAllows`): a folder, a Pot and a Memory at the space's root or in a folder, a
/// chapter in a Memory; a kind the app does not know nowhere.
final class KindPlacement: NodeMovePlacementAllows {
    func allows(kind: String, parentKind: String?) -> Bool {
        switch kind {
        case "FOLDER", PotSession.companion.KIND, "MEMORY": parentKind == nil || parentKind == "FOLDER"
        case "CHAPTER": parentKind == "MEMORY"
        default: false
        }
    }
}
