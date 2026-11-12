import SwiftUI
import VeyqCore

/// Core's move and copy sheet drawn (core's `MoveSheet`: the web's `MoveNodeDialog.vue`, `MoveBrowser.vue` and
/// `CopyNodesDialog`, owner decision 2026-10-14): one place at a time under a trail up to the space's root, the places one
/// may go into or place into — the node itself and what lies below it listed but not to be opened —, "Move here" / "Copy
/// here" where the place takes it, else why. Everything it shows and decides is the sheet's; once it moved or copied, the
/// shell says so (`AppModel.moved`, with a move's Undo while core offers it) and the sheet closes.
struct NodeMoveSheet: View {
    let model: AppModel
    let ref: NodeAt
    var copying = false
    @Environment(\.dismiss) private var dismiss
    @State private var box = MoveBox()

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(box.sheet.map { say(notice: $0.title) } ?? "")
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() }.disabled(box.state?.busy == true) }
                }
        }
        .task {
            guard box.sheet == nil, let made = model.moveSheet(ref, copying: copying) else { return }
            box.start(made)
            try? await made.load()
        }
        .onChange(of: box.state?.done != nil) { _, done in
            guard done, let sheet = box.sheet else { return }
            model.moved(sheet)
            dismiss()
        }
        .onDisappear { box.stop() }
    }

    @ViewBuilder private var content: some View {
        if let sheet = box.sheet, let st = box.state, st.loaded {
            List {
                Section {
                    Text(say(sheet.hint)).font(.subheadline).foregroundStyle(Role.muted)
                }
                .listRowBackground(Color.clear)
                if sheet.copying {
                    // what a copy leaves behind, in the web's words (core's, then the kinds' `copyLosses`)
                    Section(say(.theCopyLosses)) {
                        ForEach(st.losses, id: \.self) { Text(say($0)) }
                    }
                    .font(.footnote).foregroundStyle(Role.muted)
                    if !st.leftOut.isEmpty {
                        Section(say(.theCopyLeftOut)) {
                            ForEach(Array(st.leftOut.enumerated()), id: \.offset) { Text(say(notice: $0.element)) }
                        }
                        .font(.footnote).foregroundStyle(Role.muted)
                    }
                }
                Section {
                    // The trail: the space's root, then each folder down to the place on screen.
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 4) {
                            ForEach(Array(st.trail.enumerated()), id: \.offset) { i, crumb in
                                if i > 0 { Image(systemName: "chevron.right").font(.caption).foregroundStyle(Role.muted) }
                                Button(say(piece: crumb.name)) { sheet.go(at: crumb.nodeId) }
                                    .fontWeight(i == st.trail.count - 1 ? .bold : .regular)
                                    .disabled(i == st.trail.count - 1 || st.busy)
                            }
                        }
                    }
                    .buttonStyle(.borderless)
                }
                Section {
                    if st.places.isEmpty {
                        Text(say(notice: sheet.empty)).foregroundStyle(Role.muted)
                    }
                    ForEach(st.places, id: \.node.nodeId) { place in
                        Button { sheet.go(at: place.node.nodeId) } label: {
                            HStack {
                                Label { Text(place.node.name) } icon: { Tile(symbol: Names.kindSymbol(place.node.kind)) }
                                Spacer()
                                if let blocked = place.blocked {
                                    Text(say(NodeMove.shared.placeWord(block: blocked))).font(.caption).foregroundStyle(Role.muted)
                                } else {
                                    Image(systemName: "chevron.right").foregroundStyle(Role.muted)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(place.blocked != nil || st.busy)
                        .opacity(place.blocked == nil ? 1 : 0.45)
                    }
                }
                Section {
                    if let problem = st.problem ?? st.noRoom {
                        Text(say(notice: problem)).font(.caption).foregroundStyle(Role.danger)
                    } else if let here = sheet.hereWord(state: st) {
                        Text(say(here)).font(.caption).foregroundStyle(Role.muted)
                    }
                    Button {
                        Task { _ = try? await sheet.choose() }
                    } label: {
                        HStack { Spacer(); if st.busy { ProgressView() } else { Text(say(sheet.submit)).fontWeight(.semibold) }; Spacer() }
                    }
                    .disabled(!st.canChoose)
                }
            }
            // moving a guest's folder out ends it (the web's `endsIdentity`): core asks first, there is no way back. Only
            // Cancel cancels: a binding that cancelled on dismissal ran before the accept's action, whose confirm then found
            // nothing asked and moved nothing (measured 2026-11-12, a drop box's folder moved out on iOS)
            .alert(say(.moveIdentityTitle), isPresented: Binding(get: { st.confirming }, set: { _ in })) {
                Button(say(.moveIdentityAccept)) { Task { _ = try? await sheet.confirm() } }
                Button(say(.signinCancel), role: .cancel) { sheet.cancelConfirm() }
            } message: {
                Text(say(notice: sheet.confirmQuestion))
            }
        } else {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// Core's move sheet held for SwiftUI: its state mirrored on the main actor as it changes.
@MainActor
@Observable
final class MoveBox {
    private(set) var sheet: MoveSheet?
    private(set) var state: MoveSheet.State?
    @ObservationIgnored private var watch: MoveSheet.Watch?

    func start(_ sheet: MoveSheet) {
        self.sheet = sheet
        state = sheet.current
        watch = sheet.watch { @Sendable _ in
            Task { @MainActor [weak self] in
                if let current = self?.sheet?.current { self?.state = current }
            }
        }
    }

    func stop() { watch?.stop() }
}
