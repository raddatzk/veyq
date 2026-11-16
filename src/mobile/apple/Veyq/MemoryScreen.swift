import AVKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import VeyqCore

/// A Memory's or a chapter's page (Memories' `MemoryDetail.vue`, its gallery), drawn from core's `GalleryModel`: the chapters
/// a Memory holds, then every medium as a tile of its thumbnail in the justified grid (core's `GalleryModel.layout`, the web's
/// `MediaGrid.vue`: its aspect kept, each row filled to the width), in the grid's order (newest first by capture time), a
/// video marked as one; a tile opens the lightbox (`Lightbox`). When it reads anew is the core's.
///
/// On a Mac the lightbox opens in the window's content, where the gallery was (back and Escape return to it), and in a
/// window of its own only when asked: "Open in New Window" in the lightbox's toolbar and the File menu, a tile's context
/// menu or a ⌘-click on it (`LightboxWindow`). iOS shows it full screen.
struct MemoryScreen: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    @State private var open: LightboxStart?
    @State private var pickingPhotos = false
    @State private var photos: [PhotosPickerItem] = []
    @State private var pickingFiles = false
    @State private var namingChapter = false
    @State private var chapterName = ""
    @State private var chapterProblem: String?
    /// The grid's width between the page's margins, as last measured.
    @State private var gridWidth: CGFloat = 0
    /// Photos or music (ADR 0142 decision 6), and the Music view's model while it is asked for.
    @State private var view: MemoryView = .photos
    @State private var music: MusicModel?
    @State private var pickingMusic = false
    @State private var tracks: TrackList?
    @State private var pickingTracks = false
    @Environment(\.scenePhase) private var phase
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif

    private var node: NodeAt { ref.at }
    private var state: GalleryModel.State? { model.galleryState(node) }
    private var rows: [GalleryModel.Row] { state?.rows ?? [] }
    /// What the page adds (core's: the rights' upload, a chapter where it makes one).
    private var uploads: Bool { state?.rights.upload ?? false }
    private var chapterHere: Bool { model.galleryModel(node)?.creates.contains(MemoryCreate.shared.CHAPTER) ?? false }
    /// The room between the grid and the page's edges: the grid's own gap, so every side of a tile breathes alike.
    private static let margin = CGFloat(MediaGridLayout.shared.GAP)

    /// Whether the page offers its Music view: it has music, or whoever looks may add some (core's).
    private var musicOffered: Bool { state.map { MusicModel.companion.shown(gallery: $0) } ?? false }
    private var showsMusic: Bool { view == .music && musicOffered }
    private var musicAdds: Bool { showsMusic && (model.musicShown?.rights.add ?? false) }
    private var showsMap: Bool { view == .map }
    private var tracksAdd: Bool { showsMap && (model.tracksShown?.rights.add ?? false) }
    private var viewSwitch: MemoryViewSwitch { MemoryViewSwitch(view: $view, music: musicOffered) }

    /// The page's date and place under its header (ADR 0143 D5).
    @ViewBuilder private var factsLine: some View {
        if let state, let gallery = model.galleryModel(node) {
            MemoryFactsLine(model: model, gallery: gallery, state: state)
        }
    }

    var body: some View {
        photoChrome(Group {
            if showsMusic, let music {
                MusicList(model: model, music: music, viewSwitch: viewSwitch)
            } else if showsMap, let tracks {
                MemoryMapView(model: model, tracks: tracks, gallery: state, facts: AnyView(factsLine), viewSwitch: viewSwitch)
            } else {
                photoGrid
            }
        }
        .navigationTitle(ref.name))
        .task(id: showsMusic) {
            guard showsMusic, let gallery = model.galleryModel(node) else { return }
            music = model.musicModel(gallery)
        }
        .task(id: showsMap) {
            guard showsMap, let gallery = model.galleryModel(node) else { return }
            tracks = model.trackList(gallery)
        }
        // "Add GPX track" in the Map view: the system's picker, filtered to GPX (ADR 0143 D8)
        .createAction(say(.trackAdd), shown: tracksAdd) { pickingTracks = true }
        .background(EmptyView().fileImporter(isPresented: $pickingTracks, allowedContentTypes: trackPickTypes, allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result, !urls.isEmpty, let tracks else { return }
            // the labels are asked by the Map view, what is refused said there (core's `asking`, `said`)
            Task { await model.addTracks(tracks, urls: urls) }
        })
        // "Add music" in the Music view: the system's picker, filtered to audio (ADR 0142 decision 7)
        .createAction(say(.musicAdd), shown: musicAdds) { pickingMusic = true }
        .background(EmptyView().fileImporter(isPresented: $pickingMusic, allowedContentTypes: musicPickTypes, allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result, !urls.isEmpty, let music else { return }
            // what is refused is said by the Music view (core's `said`)
            Task { await model.addMusic(music, urls: urls) }
        })
    }

    private var photoGrid: some View {
        ScrollView {
            // lazy: only the rows near the viewport are built (ADR 0105), the grid's rows among the page's own
            LazyVStack(alignment: .leading, spacing: Self.margin) {
                NodeHeroRow()
                factsLine.padding(.horizontal)
                viewSwitch.padding(.horizontal)
                if let chapters = state?.chapters, !chapters.isEmpty {
                    Text(say(.memoryChapters)).font(.headline).padding(.horizontal)
                    ForEach(chapters, id: \.nodeId) { c in
                        Button {
                            if let o = GalleryModel.companion.rowPage(row: c) { model.open(o) }
                        } label: {
                            HStack(spacing: 12) {
                                ChapterCover(model: model, node: node, chapter: c)
                                Text(c.name)
                                Spacer(minLength: 0)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .padding(.horizontal)
                    }
                }
                if let failure = state?.failure {
                    // offline, what the store does not hold says so (muted: no failure), else the load failed (core's words)
                    Text(say(failure)).foregroundStyle(state?.offline != nil ? Role.muted : Role.danger).padding()
                } else if state?.rows == nil {
                    ProgressView().frame(maxWidth: .infinity).padding()
                } else if rows.isEmpty {
                    Text(say(.memoryGalleryEmpty)).foregroundStyle(Role.muted).padding()
                } else {
                    Text(say(.memoryItems, rows.count)).font(.footnote).foregroundStyle(Role.muted).padding(.horizontal)
                    let layout = GalleryModel.companion.layout(rows: rows, width: Double(gridWidth))
                    let at = Dictionary(uniqueKeysWithValues: rows.enumerated().map { ($0.element.mediaId, $0.offset) })
                    ForEach(layout.rows, id: \.key) { r in
                        ZStack(alignment: .topLeading) {
                            ForEach(r.tiles, id: \.id) { t in
                                if let index = at[t.id] { tile(rows[index], index: index, frame: t) }
                            }
                        }
                        .frame(maxWidth: .infinity, minHeight: CGFloat(r.height), maxHeight: CGFloat(r.height), alignment: .topLeading)
                        .padding(.horizontal, Self.margin)
                    }
                }
            }
        }
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { gridWidth = max(0, $0 - 2 * Self.margin) }
    }

    /// The page beyond its two views: the photos' creating menu, the chapter's naming, a delete's notice and question (a
    /// track's too), the live poll, the pickers and the lightbox.
    private func photoChrome(_ content: some View) -> some View {
        content
        // photos and videos come from the photo library or from files; a Memory makes its chapters here too
        .createMenu(say(uploads ? .memoryAdd : .memoryAddChapter), shown: !showsMusic && !showsMap && (uploads || chapterHere)) {
            if uploads {
                Button { pickingPhotos = true } label: { Label(say(.photoLibrary), systemImage: "photo.on.rectangle") }
                Button { pickingFiles = true } label: { Label(say(.tabDepotFiles), systemImage: "folder") }
            }
            if chapterHere {
                Button { chapterName = ""; chapterProblem = nil; namingChapter = true } label: {
                    Label(say(.memoryAddChapter), systemImage: "rectangle.stack.badge.plus")
                }
            }
        }
        .alert(say(.memoryCreateChapterTitle), isPresented: $namingChapter) {
            TextField(say(.nodeName), text: $chapterName)
            Button(say(.folderCreate)) {
                let name = chapterName
                guard let gallery = model.galleryModel(node) else { return }
                Task {
                    if let p = await model.createChapter(gallery, name: name) { chapterProblem = p; namingChapter = true }
                }
            }
            Button(say(.signinCancel), role: .cancel) {}
        } message: {
            if let chapterProblem { Text(chapterProblem) }
        }
        // a medium's delete said: "Moved to trash" with its Undo while core offers it, what a restore refused
        .overlay(alignment: .bottom) {
            if state?.undo != nil || state?.said != nil, let gallery = model.galleryModel(node), open == nil {
                GalleryNotice(gallery: gallery, undo: state?.undo, said: state?.said)
            }
        }
        .confirmationDialog(
            say(.memoryDeleteOwnTitle),
            isPresented: Binding(get: { state?.purging != nil }, set: { if !$0 { model.galleryModel(node)?.cancelAsk() } }),
            titleVisibility: .visible
        ) {
            Button(say(.memoryDeleteOwnAccept), role: .destructive) {
                guard let gallery = model.galleryModel(node) else { return }
                Task { _ = try? await gallery.purge() }
            }
            Button(say(.signinCancel), role: .cancel) { model.galleryModel(node)?.cancelAsk() }
        } message: {
            if let gallery = model.galleryModel(node) { Text(say(notice: gallery.purgeQuestion)) }
        }
        // live while the page is on screen and the app active: what another device or a guest adds shows up (core's poll)
        .task(id: GalleryLiveKey(node: node, active: phase == .active)) {
            if phase == .active { await model.liveGallery(node) }
        }
        .photosPicker(isPresented: $pickingPhotos, selection: $photos, matching: .any(of: [.images, .videos]), preferredItemEncoding: .current)
        .onChange(of: photos) {
            let picked = photos
            photos = []
            guard !picked.isEmpty else { return }
            Task {
                var urls: [URL] = []
                for item in picked {
                    if let file = try? await item.loadTransferable(type: PickedMedia.self) { urls.append(file.url) }
                }
                // the page went meanwhile: no upload reads the copies
                guard let gallery = model.galleryModel(node) else {
                    urls.forEach(AppModel.removePicked)
                    return
                }
                model.uploadMedia(gallery, urls: urls, fromPhotos: true)
            }
        }
        .background(EmptyView().fileImporter(isPresented: $pickingFiles, allowedContentTypes: [.image, .movie], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result, !urls.isEmpty, let gallery = model.galleryModel(node) else { return }
            model.uploadMedia(gallery, urls: urls, fromPhotos: false)
        })
        // the model is the shell's while the page is shown; asking for it here opens it
        .task(id: ref) { _ = model.galleryModel(node) }
        #if os(iOS)
        .fullScreenCover(item: $open, onDismiss: { open = nil }) { start in
            Lightbox(model: model, gallery: start.gallery, box: start.box, mode: .cover) { open = nil }
        }
        #else
        // in the window's content, where the gallery was: its back (and Escape) return to the gallery
        .navigationDestination(item: $open) { start in
            Lightbox(model: model, gallery: start.gallery, box: start.box, mode: .inline) { open = nil }
        }
        #endif
    }

    /// Row `row` (at `index` in the grid's order) as a tile at `frame`: a click opens the lightbox here; on a Mac a ⌘-click
    /// and its context menu's "Open in New Window" open it in a window of its own.
    private func tile(_ row: GalleryModel.Row, index: Int, frame: MediaGridLayout.Tile) -> some View {
        Button {
            #if os(macOS)
            if NSEvent.modifierFlags.contains(.command) { return openInWindow(row.mediaId) }
            #endif
            if let gallery = model.galleryModel(node) { open = LightboxStart(gallery: gallery, box: gallery.lightbox(index: Int32(index))) }
        } label: {
            MediaTile(model: model, node: node, row: row, width: CGFloat(frame.width), height: CGFloat(frame.height))
        }
        .buttonStyle(.plain)
        .offset(x: CGFloat(frame.left))
        #if os(macOS)
        .contextMenu {
            Button { openInWindow(row.mediaId) } label: { Label(say(.memoryOpenInWindow), systemImage: "macwindow.badge.plus") }
        }
        #endif
    }

    #if os(macOS)
    /// Medium `mediaId` in a lightbox window of its own (its value carries the ids alone; the window opens its own models).
    private func openInWindow(_ mediaId: String) {
        openWindow(value: LightboxWindowValue(scopeId: node.scopeId, nodeId: node.nodeId, mediaId: mediaId))
    }
    #endif
}

/// A photo or video the Photos picker handed over, copied to a directory of its own under `AppModel.pickedDir` (the picker's
/// own file is gone once the transfer returns): the person's own original, never a decrypted veyq copy. The core hands it
/// back once the queue is done with it (`MediaUploads`), which removes it; what is left is swept at start.
private struct PickedMedia: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { try copy($0.file) }
        FileRepresentation(importedContentType: .image) { try copy($0.file) }
    }

    private static func copy(_ file: URL) throws -> PickedMedia {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("picked", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let copy = dir.appendingPathComponent(file.lastPathComponent)
        try FileManager.default.copyItem(at: file, to: copy)
        return PickedMedia(url: copy)
    }
}

/// Where the lightbox opens (a cover's or a navigation destination's item): core's lightbox over the gallery.
private struct LightboxStart: Identifiable, Hashable {
    let gallery: GalleryModel
    let box: VeyqCore.Lightbox
    var id: ObjectIdentifier { ObjectIdentifier(box) }
    static func == (a: Self, b: Self) -> Bool { a.id == b.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

/// What restarts a gallery's live poll: another page, the app becoming active or not.
private struct GalleryLiveKey: Equatable {
    let node: NodeAt
    let active: Bool
}

/// A chapter's row picture: its cover, the stored picture core reads for one of this Memory's chapters
/// (`GalleryModel.chapterCover`), cropped to the tile; the kind's symbol where it has none.
private struct ChapterCover: View {
    let model: AppModel
    let node: NodeAt
    let chapter: NodeRef
    @State private var picture: CGImage?

    var body: some View {
        Group {
            if let picture {
                Image(decorative: picture, scale: 1).resizable().scaledToFill()
            } else {
                Image(systemName: Names.kindSymbol(chapter.kind)).foregroundStyle(Role.accent)
            }
        }
        .frame(width: 40, height: 40)
        .clipShape(RoundedRectangle(cornerRadius: Radius.row, style: .continuous))
        .task(id: chapter.nodeId) {
            guard let gallery = model.galleryModel(node),
                  let data = try? await AppleMemories.shared.chapterCover(gallery: gallery, nodeId: chapter.nodeId) else { return }
            picture = CoverImages.image(data as Data, maxPixels: 160)
        }
    }
}

/// What a gallery says at the page's foot: a trashed medium's "Moved to trash" with its Undo for as long as core offers it
/// (`State.undo`, the node trash's window: this view keeps no clock), else what a refused restore said (`State.said`), gone
/// once read.
private struct GalleryNotice: View {
    let gallery: GalleryModel
    let undo: Notice?
    let said: Notice?

    var body: some View {
        HStack(spacing: 12) {
            Text(say(notice: undo ?? said!)).font(.subheadline).foregroundStyle(Role.text)
            Spacer(minLength: 0)
            if undo != nil {
                Button(say(.trashUndo)) { Task { _ = try? await gallery.undo() } }.fontWeight(.semibold)
            } else {
                Button(say(.ok)) { gallery.seen() }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Role.surface, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        .shadow(color: .black.opacity(0.12), radius: 8, y: 2)
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
    }
}

/// Core's gallery state held for SwiftUI where no page holds it (a lightbox's notices): mirrored on the main actor.
@MainActor
@Observable
final class GalleryBox {
    let gallery: GalleryModel
    private(set) var state: GalleryModel.State
    @ObservationIgnored private var watch: GalleryModel.Watch?

    init(_ gallery: GalleryModel) {
        self.gallery = gallery
        state = gallery.current
        watch = gallery.watch { @Sendable _ in
            Task { @MainActor [weak self] in
                if let current = self?.gallery.current { self?.state = current }
            }
        }
    }

    func stop() { watch?.stop() }
}

/// One tile at the size the justified grid gave it: the medium's grid tier cropped to fill (its aspect is the tile's,
/// bounded), a video marked; an icon where it has no tile to show.
private struct MediaTile: View {
    let model: AppModel
    let node: NodeAt
    let row: GalleryModel.Row
    let width: CGFloat
    let height: CGFloat
    @State private var picture: CGImage?

    var body: some View {
        Rectangle().fill(Role.line)
            .frame(width: width, height: height)
            .overlay {
                if let picture {
                    Image(decorative: picture, scale: 1).resizable().scaledToFill()
                } else {
                    Image(systemName: "photo").foregroundStyle(Role.muted)
                }
            }
            .overlay {
                if row.isVideo { Image(systemName: "play.circle.fill").font(.title).foregroundStyle(.white) }
            }
            .clipped()
            .accessibilityLabel(row.name ?? "")
            .task(id: row.mediaId) {
                guard let blob = row.tile, let gallery = model.galleryModel(node) else { return }
                picture = await model.mediaPicture(blob) {
                    (try? await AppleMemories.shared.tile(gallery: gallery, mediaId: row.mediaId)).map { $0 as Data }
                }
            }
    }
}

/// Core's lightbox held for SwiftUI: its state mirrored on the main actor as it changes; closed with the view.
@MainActor
@Observable
final class LightboxBox {
    let box: VeyqCore.Lightbox
    private(set) var state: VeyqCore.Lightbox.State
    @ObservationIgnored private var watch: VeyqCore.Lightbox.Watch?

    init(_ box: VeyqCore.Lightbox) {
        self.box = box
        state = box.current
        watch = box.watch { @Sendable _ in
            Task { @MainActor [weak self] in
                if let current = self?.box.current { self?.state = current }
            }
        }
    }

    func close() {
        watch?.stop()
        box.close()
    }
}

/// Where a lightbox is shown: full screen over the app (iOS), in a Mac window's content where the gallery was, or in a Mac
/// window of its own.
private enum LightboxMode {
    case cover, inline, window
}

/// The lightbox (the web's `MediaLightbox.vue`), drawn from core's `Lightbox`: the gallery paged through on black, each
/// medium's preview fitted, a video's poster with a play button that streams the original as the player reads it
/// (`MediaVideo`); its bar closes (full screen; inline its back does, and Escape), names the place in the gallery, opens the
/// details (`MediaDetailsView`) and saves the original (core's `FileSave`): a Mac where its save panel says, iOS through the
/// system's save sheet. Inline on a Mac it offers "Open in New Window" (its toolbar, the File menu). However it is shown,
/// it closes core's lightbox when it goes, and a video's reader with it.
private struct Lightbox: View {
    let model: AppModel
    let mode: LightboxMode
    let onClose: () -> Void
    @State private var held: LightboxBox
    @State private var gallery: GalleryBox
    @State private var details = false
    @State private var downloading = false
    @State private var downloaded: URL?
    @State private var failed: String?
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif

    init(model: AppModel, gallery: GalleryModel, box: VeyqCore.Lightbox, mode: LightboxMode, onClose: @escaping () -> Void) {
        self.model = model
        self.mode = mode
        self.onClose = onClose
        _held = State(initialValue: LightboxBox(box))
        _gallery = State(initialValue: GalleryBox(gallery))
    }

    private var state: VeyqCore.Lightbox.State { held.state }
    private var current: GalleryModel.Row? { state.row }

    var body: some View {
        if mode == .inline {
            pages
        } else {
            NavigationStack { pages }
        }
    }

    #if os(macOS)
    /// The current medium in a window of its own; the lightbox here returns to the gallery.
    private func openInWindow() {
        guard let row = current else { return }
        let node = held.box.node
        openWindow(value: LightboxWindowValue(scopeId: node.scopeId, nodeId: node.nodeId, mediaId: row.mediaId))
        onClose()
    }
    #endif

    private var pages: some View {
            // the pages, then the caption — stacked, not floated over the picture
            VStack(alignment: .leading, spacing: 0) {
                // the music's compact controls while a queue is loaded, a row of their own (ADR 0142 decision 5)
                if let now = state.music, let commands = held.box.musicControls {
                    MusicCompactControls(now: now, commands: commands).padding(.horizontal).padding(.vertical, 8)
                }
                // the person's paging pages the model (and pauses its slideshow); the model's own step — the slideshow's, a
                // re-read — moves the selection, which then asks nothing back
                TabView(selection: Binding(get: { Int(state.index) }, set: { if $0 != Int(held.box.current.index) { held.box.go(index: Int32($0)) } })) {
                    ForEach(Array(state.rows.enumerated()), id: \.element.mediaId) { i, row in
                        LightboxPage(model: model, held: held, row: row, playing: Int(state.index) == i ? state.reader : nil, current: Int(state.index) == i).tag(i)
                    }
                }
                .animation(.default, value: state.index)
                #if os(iOS)
                .tabViewStyle(.page(indexDisplayMode: .never))
                #endif
                VStack(alignment: .leading) {
                    if let row = current {
                        Text(row.name ?? "").font(.headline)
                        if let at = row.capturedAt {
                            Text(Date(timeIntervalSince1970: Double(truncating: at) / 1000), format: .dateTime.day().month().year().hour().minute())
                                .font(.footnote).opacity(0.8)
                        }
                    }
                }
                .foregroundStyle(.white)
                .padding()
                #if os(macOS)
                // a Mac's sheet drew its toolbar as a light bottom bar with one primary button: Save fell away and Close and
                // Details were white on white (measured 2026-11-13) — the actions are a bar of the lightbox's own there, the
                // same inline (Close returns to the gallery) and in a window of its own (Close closes it)
                HStack {
                    Button(say(.receiptsClose)) { onClose() }.keyboardShortcut(.cancelAction)
                    Spacer()
                    if mode == .inline {
                        Button { openInWindow() } label: { Label(say(.memoryOpenInWindow), systemImage: "macwindow.badge.plus") }
                            .help(say(.memoryOpenInWindow))
                    }
                    actions
                }
                .padding([.horizontal, .bottom])
                #endif
            }
            .background(Color.black.ignoresSafeArea())
            // a medium trashed here: "Moved to trash" with its Undo while core offers it
            .overlay(alignment: .bottom) {
                if gallery.state.undo != nil || gallery.state.said != nil {
                    GalleryNotice(gallery: gallery.gallery, undo: gallery.state.undo, said: gallery.state.said).padding(.bottom, 72)
                }
            }
            // the last medium trashed: nothing is left to show
            .onChange(of: state.rows.isEmpty) { _, empty in if empty { onClose() } }
            .navigationTitle(say(.memoryItemPosition, Int(state.index) + 1, state.rows.count))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.black, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            #endif
            #if os(iOS)
            .toolbar {
                if mode == .cover {
                    ToolbarItem(placement: .cancellationAction) {
                        Button { onClose() } label: { Label(say(.receiptsClose), systemImage: "xmark") }
                    }
                }
                ToolbarItemGroup(placement: .primaryAction) { actions }
            }
            #endif
            .sheet(isPresented: $details) {
                if let row = current {
                    MediaDetailsView(model: model, box: held.box, row: row)
                        .presentationDetents([.medium, .large])
                        #if os(macOS)
                        // a Mac's sheet takes its content's ideal size, which a List has none of: it drew one row's height
                        .frame(minWidth: 420, minHeight: 440)
                        #endif
                }
            }
            #if os(iOS)
            .fileMover(isPresented: Binding(get: { downloaded != nil }, set: { if !$0 { cleanUpDownload() } }), file: downloaded) { _ in }
            #endif
            .alert(failed ?? "", isPresented: Binding(get: { failed != nil }, set: { if !$0 { failed = nil } })) {
                Button(say(.ok), role: .cancel) {}
            }
            #if os(macOS)
            // Escape returns to the gallery (inline), the File menu's "Open in New Window" detaches it
            .onExitCommand { if mode == .inline { onClose() } }
            .focusedSceneValue(\.lightboxToWindow, mode == .inline ? LightboxToWindow(open: openInWindow) : nil)
            .navigationSubtitle(mode == .window ? held.box.node.name : "")
            #endif
            .environment(\.colorScheme, .dark)
            // the lightbox went: core closes a playing video's reader with it
            .onDisappear {
                held.close()
                gallery.stop()
            }
    }

    /// The slideshow (core's clock, its end on the last medium; here only its switch), Details, Save and Move to trash: the
    /// iOS toolbar's primary group, a Mac's own bar.
    @ViewBuilder private var actions: some View {
        if state.rows.count > 1 {
            let playing = state.slideshow == .playing
            Button { held.box.slideshow(on: !playing) } label: {
                Label(say(playing ? .memorySlideshowPause : .memorySlideshowStart), systemImage: playing ? "pause.fill" : "play.rectangle")
            }
            .disabled(!playing && Int(state.index) >= state.rows.count - 1)
        }
        Button { details = true } label: { Label(say(.memoryDetails), systemImage: "info.circle") }
        if let row = current, !row.blocked {
            if downloading {
                ProgressView()
            } else {
                Button { Task { await download() } } label: { Label(say(.memoryDownload), systemImage: "arrow.down.circle") }
            }
        }
        if let row = current, gallery.gallery.deletes(mediaId: row.mediaId) {
            let box = held.box
            Button(role: .destructive) { Task { _ = try? await box.trash() } } label: { Label(say(.memoryTrash), systemImage: "trash") }
        }
    }

    /// The original saved under core's name for it (`FileSave`, written a frame at a time by core's `FileSink`). A Mac asks
    /// where in its save panel and core writes there; iOS has no other way to a place the person picks than the save sheet
    /// (`fileMover`), so it writes into the app's downloads directory first, removed once the sheet is done and at start.
    private func download() async {
        guard let name = held.box.downloadName() else { return }
        #if os(macOS)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = name
        guard panel.runModal() == .OK, let url = panel.url else { return }
        downloading = true
        let end = try? await AppleMemories.shared.save(lightbox: held.box, path: url.path)
        downloading = false
        if end != .saved { failed = say(.downloadFailed, name) }
        #else
        guard let dir = AppModel.downloadDir() else { return }
        let url = dir.appendingPathComponent(name)
        downloading = true
        let end = try? await AppleMemories.shared.save(lightbox: held.box, path: url.path)
        downloading = false
        if end == .saved {
            downloaded = url
        } else {
            try? FileManager.default.removeItem(at: dir)
            failed = say(.downloadFailed, name)
        }
        #endif
    }

    private func cleanUpDownload() {
        if let url = downloaded { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        downloaded = nil
    }
}

/// One medium of the lightbox: its preview fitted, a video's poster with its play button, a blocked one said so.
private struct LightboxPage: View {
    let model: AppModel
    let held: LightboxBox
    let row: GalleryModel.Row
    /// The current video's original while it plays (core's lightbox holds it); nil otherwise.
    let playing: RangeReader?
    /// Whether it is the page shown (a page left goes back to its fit).
    let current: Bool
    @State private var picture: CGImage?
    @State private var read = false
    /// The platform's zoom (ADR 0136's Consequences: a pinch, a double tap; the model knows nothing of it) and its pan while zoomed in.
    @State private var scale: CGFloat = 1
    @State private var settled: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var panned: CGSize = .zero

    var body: some View {
        ZStack {
            if let said = row.said {
                Text(say(said)).foregroundStyle(.white).padding()
            } else if let playing {
                MediaVideo(held: held, row: row, reader: playing)
            } else {
                if let picture {
                    zoomable(Image(decorative: picture, scale: 1).resizable().scaledToFit())
                } else if !read {
                    ProgressView().tint(.white)
                } else if !row.isVideo {
                    Image(systemName: "photo").font(.largeTitle).foregroundStyle(.white)
                }
                if row.isVideo {
                    Button { _ = held.box.play() } label: {
                        Image(systemName: "play.circle.fill").font(.system(size: 64)).foregroundStyle(.white)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(say(.memoryPlay))
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .onChange(of: current) { _, shown in if !shown { zoom(1) } }
        .task(id: row.mediaId) {
            // a video without a poster has none to show: its original is no picture
            if let blob = row.picture {
                let box = held.box
                let mediaId = row.mediaId
                picture = await model.mediaPicture(blob) {
                    (try? await AppleMemories.shared.picture(lightbox: box, mediaId: mediaId)).map { $0 as Data }
                }
            }
            read = true
        }
    }
}

extension LightboxPage {
    /// The most a picture is zoomed in (a pinch), and what a double tap zooms it to.
    private static let zoomMax: CGFloat = 5
    private static let zoomTap: CGFloat = 2.5

    private func zoom(_ to: CGFloat) {
        scale = min(max(to, 1), Self.zoomMax)
        settled = scale
        if scale == 1 { offset = .zero; panned = .zero }
    }

    /// `image` zoomed by a pinch and a double tap, panned by a drag while zoomed in (the pages' swipe waits until it is back
    /// at its fit); a touch pauses the slideshow (the web pauses on any pointer).
    func zoomable(_ image: some View) -> some View {
        image
            .scaleEffect(scale)
            .offset(offset)
            .gesture(
                MagnifyGesture()
                    .onChanged { v in
                        held.box.slideshow(on: false)
                        scale = min(max(settled * v.magnification, 1), Self.zoomMax)
                    }
                    .onEnded { _ in zoom(scale) }
            )
            .highPriorityGesture(
                DragGesture()
                    .onChanged { v in offset = CGSize(width: panned.width + v.translation.width, height: panned.height + v.translation.height) }
                    .onEnded { _ in panned = offset },
                including: scale > 1 ? .all : .subviews
            )
            .onTapGesture(count: 2) {
                held.box.slideshow(on: false)
                withAnimation { zoom(scale > 1 ? 1 : Self.zoomTap) }
            }
            .onTapGesture { held.box.slideshow(on: false) }
    }
}

/// A video played as it is read (the web's range playback, ADR 0047): the system's player asks `ReaderLoader` for byte
/// ranges, each served from the frames that hold it through core's `RangeReader`, so the plaintext is never held whole nor
/// written anywhere. The system's controls. The lightbox's model holds the reader and closes it when it pages away or closes.
private struct MediaVideo: View {
    let held: LightboxBox
    let row: GalleryModel.Row
    let reader: RangeReader
    @State private var playback: Playback?

    var body: some View {
        Group {
            if let playback {
                VideoPlayer(player: playback.player)
            } else {
                ProgressView().tint(.white)
            }
        }
        .task(id: row.mediaId) {
            let p = Playback(reader: reader, mime: row.type)
            playback = p
            p.player.play()
            // the video's own play state interrupts the music while it plays (ADR 0142 decision 13); a wait for data is
            // neither, so the music does not come back between two stalls
            let box = held.box
            for await status in p.player.publisher(for: \.timeControlStatus).values {
                switch status {
                case .playing: box.videoPlaying(playing: true)
                case .paused: box.videoPlaying(playing: false)
                default: break
                }
            }
        }
        .onDisappear {
            held.box.videoPlaying(playing: false)
            playback?.player.pause()
            playback = nil
            // the player's view left: the reader goes with it
            if held.box.current.reader === reader { held.box.stop() }
        }
    }
}

/// A medium's details (the web's details panel, core's `Lightbox.details`): the capture time and the file's facts at once,
/// the camera's once its EXIF tier is read, who added it (read once for the gallery), its place and position; "no details"
/// only where nothing is known.
private struct MediaDetailsView: View {
    let model: AppModel
    let box: VeyqCore.Lightbox
    let row: GalleryModel.Row
    @State private var details: VeyqCore.Lightbox.Details?
    @Environment(\.dismiss) private var dismiss

    private var uploader: String? {
        guard let u = details?.uploader else { return nil }
        return say(piece: MediaDetails.shared.uploaderWords(u: u))
    }

    var body: some View {
        NavigationStack {
            #if os(iOS)
            list
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button(say(.shareDone)) { dismiss() } }
                }
            #else
            // the lightbox's dark scheme reaches this sheet, whose toolbar a Mac draws light: Done was white on white —
            // a row of the sheet's own below the list instead
            VStack(spacing: 0) {
                list
                HStack {
                    Spacer()
                    Button(say(.shareDone)) { dismiss() }.keyboardShortcut(.defaultAction)
                }
                .padding()
            }
            // the sheet's own ground is light: the row takes the scheme's ground with it
            .background(.background)
            #endif
        }
    }

    private var list: some View {
        let rows = details?.rows ?? []
        // who added it stands before the place, as on the web
        let placeAt = Int(details?.uploaderAt ?? 0)
        return List {
            if details != nil && rows.isEmpty && uploader == nil {
                Text(say(.memoryNoDetail)).foregroundStyle(Role.muted)
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
                if i == placeAt, let uploader { LabeledContent(say(.memoryFieldUploadedBy), value: uploader) }
                LabeledContent(Self.label(row.field), value: Self.value(row))
            }
            if placeAt == rows.count, let uploader { LabeledContent(say(.memoryFieldUploadedBy), value: uploader) }
            // the photo's position on a small map (ADR 0143 D7)
            if let map = details?.map {
                PhotoMapRow(model: model, map: map)
                    .listRowInsets(EdgeInsets(top: 6, leading: 6, bottom: 6, trailing: 6))
            }
        }
        .navigationTitle(say(.memoryDetails))
        // read anew when the map services are turned on here: the name may be looked up now
        .task(id: "\(row.mediaId) \(model.mapsRevision)") {
            details = try? await box.details(mediaId: row.mediaId)
            // its place's name resolved once ever, as the details show (core's gates: consent, rights, no slideshow);
            // when it was, the details read anew name it
            if details?.map != nil, (try? await box.resolvePlace(mediaId: row.mediaId)) != nil {
                details = try? await box.details(mediaId: row.mediaId)
            }
        }
    }

    private static func label(_ field: MediaDetails.Field) -> String { say(MediaDetails.shared.label(field: field)) }

    private static func value(_ row: MediaDetails.Row) -> String {
        let shown = MediaDetails.shared.shown(row: row)
        if let words = shown as? MediaDetails.ShownWords { return say(notice: words.notice) }
        if let size = shown as? MediaDetails.ShownSize { return ByteCountFormatter.string(fromByteCount: size.bytes, countStyle: .file) }
        if let moment = shown as? MediaDetails.ShownMoment { return Date(timeIntervalSince1970: Double(moment.epochMs) / 1000).formatted(date: .abbreviated, time: .shortened) }
        return (shown as? MediaDetails.ShownPlain)?.text ?? row.value
    }
}

/// A Mac's lightbox window's value: the ids alone (opaque; a window's value is what the system may keep), never a name or a
/// key. (Declared on every platform, as `AppModel.windowLightbox` is; only a Mac opens such a window.)
struct LightboxWindowValue: Codable, Hashable {
    let scopeId: String
    let nodeId: String
    let mediaId: String
}

#if os(macOS)
/// The inline lightbox's "Open in New Window", for the File menu (`LightboxCommands`) while it is the focused scene's.
struct LightboxToWindow {
    let open: () -> Void
}

extension FocusedValues {
    @Entry var lightboxToWindow: LightboxToWindow?
}

/// The File menu's "Open in New Window": the inline lightbox in a window of its own; disabled while none is shown.
struct LightboxCommands: Commands {
    @FocusedValue(\.lightboxToWindow) private var toWindow

    var body: some Commands {
        CommandGroup(after: .newItem) {
            Button(say(.memoryOpenInWindow)) { toWindow?.open() }
                .disabled(toWindow == nil)
        }
    }
}

/// A lightbox in a window of its own (a Mac, asked for by "Open in New Window"): its own gallery over the shell and its own
/// core `Lightbox` on the medium the value names — the main window's page is not borrowed, so either goes without the
/// other. Closing the window closes both (the lightbox's reader with them); the tenant's close closes them too, and the
/// window with them.
struct LightboxWindow: View {
    let model: AppModel
    let value: LightboxWindowValue
    @State private var opened: AppModel.WindowLightbox?
    @State private var missing = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let opened, model.holdsWindowGallery(opened.gallery) {
                Lightbox(model: model, gallery: opened.gallery, box: opened.box, mode: .window) { dismiss() }
            } else if missing {
                Text(say(.memoryGalleryFailed)).foregroundStyle(Role.muted).padding()
            } else {
                ProgressView()
            }
        }
        .frame(minWidth: 480, minHeight: 360)
        .task {
            guard opened == nil else { return }
            opened = model.windowLightbox(value)
            missing = opened == nil
        }
        // the tenant closed (signed out, switched): its galleries went, the window goes with them
        .onChange(of: opened.map { model.holdsWindowGallery($0.gallery) }) { _, holds in
            if holds == false { dismiss() }
        }
        .onDisappear {
            if let opened { model.closeWindowGallery(opened.gallery) }
        }
    }
}
#endif
