import SwiftUI
import VeyqCore

/// Core's Contributions view (`Contributions`) held for SwiftUI: its state mirrored on the main actor; stopped with the view.
@MainActor
final class ContributionsHeld {
    let model: Contributions
    /// The page it is the view of.
    let gallery: GalleryModel
    let state: CoreState<Contributions.State>

    init(_ model: Contributions, gallery: GalleryModel) {
        self.model = model
        self.gallery = gallery
        // core calls the watch on its threads: @Sendable (src/mobile/CLAUDE.md § Traps)
        state = CoreState({ model.current }) { changed in ApplePlatform.shared.watch(flow: model.state) { @Sendable _ in changed() } }
    }

    /// The buckets read (anew).
    func read() async {
        try? await model.read()
        state.refresh()
    }

    func stop() { state.stop() }
}

/// What restarts the Contributions view's read: another page, the view shown or not, the gallery's rows changed.
struct ContributionsKey: Equatable {
    let node: NodeAt
    let shown: Bool
    let rows: Int
}

/// A Memory's Contributions view (the web's `MemoryBuckets.vue`, ADR 0149 decision 6): the uploader picker — every
/// contributor's primary bucket with the sub-buckets curated below it, each with its count — narrowing the grid to the
/// chosen bucket's media; what the server takes on it (core's `Contributions.State`): a new sub-bucket under a primary, a
/// sub-bucket's rename and delete, a medium moved into another bucket of its root from its tile's context menu. A tile opens
/// the lightbox over the bucket on it. Writes are online only (core refuses them offline); what core refused is said once.
struct ContributionsView: View {
    let model: AppModel
    let gallery: GalleryModel
    let held: ContributionsHeld
    /// Each medium's badge, as the Photos view shows it.
    let marks: [String: GalleryModel.Mark]
    /// The page's head above the view (the banner, the facts, the view switch).
    let head: AnyView
    let onOpen: (String) -> Void
    @State private var width: CGFloat = 0
    @State private var naming = false
    @State private var renaming = false
    @State private var deleting = false
    @State private var name = ""
    @State private var confirm = ""
    /// What the last write was, for the words of its refusal.
    @State private var moving = false
    /// A selection of the chosen bucket's media is being made (core's `Contributions.picked`): a tap toggles a tile.
    @State private var selecting = false
    @State private var shifting: ShiftShown?

    /// A shift's sheet, by the model it shows.
    struct ShiftShown: Identifiable {
        let shift: SelectionShift
        var id: ObjectIdentifier { ObjectIdentifier(shift) }
    }

    private var s: Contributions.State { held.state.value }
    private static let margin = CGFloat(MediaGridLayout.shared.GAP)

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Self.margin) {
                head
                if s.loading && s.rows.isEmpty {
                    ProgressView().frame(maxWidth: .infinity).padding()
                } else if s.failed && s.rows.isEmpty {
                    Text(say(.memoryGalleryFailed)).foregroundStyle(Role.danger).padding()
                } else {
                    picker.padding(.horizontal)
                    if selecting { selectionBar.padding(.horizontal) }
                    if s.media.isEmpty {
                        Text(say(.memoryBucketsEmptyBucket)).foregroundStyle(Role.muted).padding()
                    } else {
                        grid
                    }
                }
            }
        }
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width = max(0, $0 - 2 * Self.margin) }
        .alert(say(.memoryBucketsCreateBucket), isPresented: $naming) {
            TextField(say(.nodeName), text: $name)
            Button(say(.folderCreate)) { write { try await held.model.create(name: name) } }
            Button(say(.signinCancel), role: .cancel) {}
        }
        .alert(say(.memoryBucketsBucketDialogRename), isPresented: $renaming) {
            TextField(say(.nodeName), text: $name)
            Button(say(.nodeRename)) { write { try await held.model.rename(name: name) } }
            Button(say(.signinCancel), role: .cancel) {}
        }
        // the web's: the bucket's name typed to confirm; its media go back to the primary, nothing is deleted
        .alert(say(.memoryBucketsDeleteBucketTitle), isPresented: $deleting) {
            TextField(say(.memoryBucketsDeleteBucketNameLabel), text: $confirm)
            Button(say(.memoryBucketsDeleteBucketConfirm), role: .destructive) { write { try await held.model.delete() } }
                .disabled(confirm != selectedName)
            Button(say(.signinCancel), role: .cancel) {}
        } message: {
            Text(say(.memoryBucketsDeleteBucketCopy, selectedName))
        }
        .sheet(item: $shifting, onDismiss: { held.state.refresh() }) { shown in CaptureTimeShiftSheet(shift: shown.shift) }
        .onChange(of: s.selected) { _, _ in selecting = false }
        .alert(say(moving ? .memoryBucketsMoveFailed : .memoryBucketsSaveFailed), isPresented: Binding(get: { s.refused != nil }, set: { if !$0 { held.model.seen(); held.state.refresh() } })) {
            Button(say(.ok), role: .cancel) {}
        }
    }

    private var selectedName: String { s.row.map { say(piece: $0.label) } ?? "" }

    /// The uploader picker (a menu of every bucket, a sub-bucket indented under its primary) and the chosen bucket's
    /// curation where it is the caller's.
    private var picker: some View {
        HStack(spacing: 8) {
            Picker(say(.memoryBucketsTitle), selection: Binding(get: { s.selected ?? "" }, set: { held.model.pick(bucketId: $0); held.state.refresh() })) {
                ForEach(s.rows, id: \.bucketId) { row in
                    Text(Self.rowWords(row)).tag(row.bucketId)
                }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            .fixedSize()
            Spacer(minLength: 0)
            if !selecting, s.row != nil, gallery.current.offline == nil, s.media.count >= 2 {
                Button(say(.selectAction)) { selecting = true }
                    .fixedSize()
            }
            if s.createsSub || s.renames, gallery.current.offline == nil {
                Menu {
                    if s.createsSub {
                        Button { name = ""; naming = true } label: { Label(say(.memoryBucketsCreateBucket), systemImage: "folder.badge.plus") }
                    }
                    if let row = s.row, s.renames {
                        Button { name = say(piece: row.label); renaming = true } label: { Label(say(.nodeRename), systemImage: "pencil") }
                        Button(role: .destructive) { confirm = ""; deleting = true } label: { Label(say(.memoryBucketsDeleteBucketConfirm), systemImage: "trash") }
                    }
                } label: {
                    Label(say(.nodeMore), systemImage: "ellipsis.circle").labelStyle(.iconOnly)
                }
                .menuIndicator(.hidden)
                .fixedSize()
                .help(say(.nodeMore))
            }
        }
    }

    /// The selection's bar (Photos' Select): how many are selected, *Shift Capture Time* where core offers it, Cancel.
    private var selectionBar: some View {
        HStack(spacing: 12) {
            Text(say(.selectCount, Int32(s.picked.count))).foregroundStyle(Role.muted)
            Spacer(minLength: 0)
            Button(say(.memoryShiftAction)) {
                if let shift = held.model.shift() { shifting = ShiftShown(shift: shift) }
            }
            .disabled(!s.shifts)
            Button(say(.signinCancel)) {
                selecting = false
                held.model.clearPicked()
                held.state.refresh()
            }
        }
    }

    /// A bucket as the picker lists it: indented by its depth, its count after its name.
    static func rowWords(_ row: Buckets.Row) -> String {
        String(repeating: "    ", count: Int(row.depth)) + joinedLine([say(piece: row.label), String(row.mediaCount)])
    }

    private var grid: some View {
        let rows = s.media
        let layout = GalleryModel.companion.layout(rows: rows, uploading: [], width: Double(width))
        let at = Dictionary(uniqueKeysWithValues: rows.enumerated().map { ($0.element.mediaId, $0.offset) })
        return ForEach(layout.rows, id: \.key) { r in
            ZStack(alignment: .topLeading) {
                ForEach(r.tiles, id: \.id) { t in
                    if let index = at[t.id] { tile(rows[index], frame: t) }
                }
            }
            .frame(maxWidth: .infinity, minHeight: CGFloat(r.height), maxHeight: CGFloat(r.height), alignment: .topLeading)
            .padding(.horizontal, Self.margin)
        }
    }

    private func tile(_ row: GalleryModel.Row, frame: MediaGridLayout.Tile) -> some View {
        Button {
            if selecting {
                held.model.toggle(mediaId: row.mediaId)
                held.state.refresh()
            } else {
                onOpen(row.mediaId)
            }
        } label: {
            MediaTile(model: model, node: gallery.node, row: row, mark: marks[row.mediaId], width: CGFloat(frame.width), height: CGFloat(frame.height))
                .overlay(alignment: .topTrailing) {
                    if s.picked.contains(row.mediaId) {
                        Image(systemName: "checkmark.circle.fill").symbolRenderingMode(.multicolor).font(.title3).padding(6)
                    }
                }
        }
        .accessibilityAddTraits(s.picked.contains(row.mediaId) ? .isSelected : [])
        .buttonStyle(.plain)
        .offset(x: CGFloat(frame.left))
        .contextMenu {
            // a medium moved into another bucket of its root (online; never across roots: the server's)
            if s.row != nil, gallery.current.offline == nil {
                let targets = s.moveTargets
                if targets.isEmpty {
                    Text(say(.memoryBucketsMoveEmpty))
                } else {
                    Menu {
                        ForEach(targets, id: \.bucketId) { target in
                            Button(Self.rowWords(target)) {
                                moving = true
                                Task {
                                    _ = try? await held.model.move(mediaIds: [row.mediaId], target: target.bucketId)
                                    held.state.refresh()
                                }
                            }
                        }
                    } label: {
                        Label(say(.memoryBucketsMoveMedia), systemImage: "arrow.right.square")
                    }
                }
            }
        }
    }

    /// A curation run (online; core refuses it otherwise); a refusal is said by the view's alert.
    private func write(_ run: @escaping () async throws -> Buckets.Outcome) {
        moving = false
        Task {
            _ = try? await run()
            held.state.refresh()
        }
    }
}
