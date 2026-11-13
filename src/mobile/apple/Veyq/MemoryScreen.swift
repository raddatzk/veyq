import AVKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import VeyqCore

/// A Memory's or a chapter's page (Memories' `MemoryDetail.vue`, its gallery), drawn from core's `GalleryModel`: the chapters
/// a Memory holds, then every medium as a square tile of its thumbnail in the grid's order (newest first by capture time), a
/// video marked as one; a tile opens the lightbox (`Lightbox`). When it reads anew is the core's.
struct MemoryScreen: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    @State private var open: LightboxStart?
    @State private var pickingPhotos = false
    @State private var photos: [PhotosPickerItem] = []
    @State private var pickingFiles = false

    private var node: NodeAt { ref.at }
    private var state: GalleryModel.State? { model.galleryState(node) }
    private var rows: [GalleryModel.Row] { state?.rows ?? [] }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                NodeHeroRow()
                if let chapters = state?.chapters, !chapters.isEmpty {
                    Text(say(.memoryChapters)).font(.headline).padding(.horizontal)
                    ForEach(chapters, id: \.nodeId) { c in
                        Button {
                            if let o = c.asObject() { model.open(o) }
                        } label: {
                            Label(c.name, systemImage: Names.kindSymbol(c.kind)).frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.horizontal)
                    }
                }
                if state?.failed == true {
                    Text(say(.memoryGalleryFailed)).foregroundStyle(Role.danger).padding()
                } else if state?.rows == nil {
                    ProgressView().frame(maxWidth: .infinity).padding()
                } else if rows.isEmpty {
                    Text(say(.memoryGalleryEmpty)).foregroundStyle(Role.muted).padding()
                } else {
                    Text(say(.memoryItems, rows.count)).font(.footnote).foregroundStyle(Role.muted).padding(.horizontal)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 2)], spacing: 2) {
                        ForEach(Array(rows.enumerated()), id: \.element.mediaId) { index, row in
                            Button {
                                if let gallery = model.galleryModel(node) { open = LightboxStart(box: gallery.lightbox(index: Int32(index))) }
                            } label: { MediaTile(model: model, node: node, row: row) }
                                .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .navigationTitle(ref.name)
        // photos and videos come from the photo library or from files
        .createMenu(say(.memoryAdd), shown: true) {
            Button { pickingPhotos = true } label: { Label(say(.photoLibrary), systemImage: "photo.on.rectangle") }
            Button { pickingFiles = true } label: { Label(say(.tabDepotFiles), systemImage: "folder") }
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
        .fullScreenCover(item: $open, onDismiss: { open = nil }) { start in Lightbox(model: model, box: start.box) }
        #else
        .sheet(item: $open) { start in Lightbox(model: model, box: start.box).frame(minWidth: 640, minHeight: 480) }
        #endif
    }
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

/// Where the lightbox opens (a sheet's item): core's lightbox over the gallery.
private struct LightboxStart: Identifiable {
    let box: VeyqCore.Lightbox
    var id: ObjectIdentifier { ObjectIdentifier(box) }
}

/// One square tile: the medium's grid tier cropped to fill, a video marked; an icon where it has no tile to show.
private struct MediaTile: View {
    let model: AppModel
    let node: NodeAt
    let row: GalleryModel.Row
    @State private var picture: CGImage?

    var body: some View {
        Rectangle().fill(Role.line)
            .aspectRatio(1, contentMode: .fit)
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

/// The lightbox (the web's `MediaLightbox.vue`), drawn from core's `Lightbox`: the gallery paged through on black, each
/// medium's preview fitted, a video's poster with a play button that streams the original as the player reads it
/// (`MediaVideo`); its bar closes, names the place in the gallery, opens the details (`MediaDetailsView`) and saves the
/// original (core's `FileSave`): a Mac where its save panel says, iOS through the system's save sheet.
private struct Lightbox: View {
    let model: AppModel
    @State private var held: LightboxBox
    @State private var details = false
    @State private var downloading = false
    @State private var downloaded: URL?
    @State private var failed: String?
    @Environment(\.dismiss) private var dismiss

    init(model: AppModel, box: VeyqCore.Lightbox) {
        self.model = model
        _held = State(initialValue: LightboxBox(box))
    }

    private var state: VeyqCore.Lightbox.State { held.state }
    private var current: GalleryModel.Row? { state.row }

    var body: some View {
        NavigationStack {
            // the pages, then the caption — stacked, not floated over the picture
            VStack(alignment: .leading, spacing: 0) {
                TabView(selection: Binding(get: { Int(state.index) }, set: { held.box.go(index: Int32($0)) })) {
                    ForEach(Array(state.rows.enumerated()), id: \.element.mediaId) { i, row in
                        LightboxPage(model: model, held: held, row: row, playing: Int(state.index) == i ? state.reader : nil).tag(i)
                    }
                }
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
                // a Mac's sheet draws its toolbar as a light bottom bar with one primary button: Save fell away and Close and
                // Details were white on white (measured 2026-11-13) — the actions are a bar of the lightbox's own there
                HStack {
                    Button(say(.receiptsClose)) { dismiss() }.keyboardShortcut(.cancelAction)
                    Spacer()
                    actions
                }
                .padding([.horizontal, .bottom])
                #endif
            }
            .background(Color.black.ignoresSafeArea())
            .navigationTitle(say(.memoryItemPosition, Int(state.index) + 1, state.rows.count))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.black, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            #endif
            #if os(iOS)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Label(say(.receiptsClose), systemImage: "xmark") }
                }
                ToolbarItemGroup(placement: .primaryAction) { actions }
            }
            #endif
            .sheet(isPresented: $details) {
                if let row = current {
                    MediaDetailsView(box: held.box, row: row)
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
        }
        .environment(\.colorScheme, .dark)
        // the lightbox went: core closes a playing video's reader with it
        .onDisappear { held.close() }
    }

    /// Details and Save: the iOS toolbar's primary group, a Mac's own bar.
    @ViewBuilder private var actions: some View {
        Button { details = true } label: { Label(say(.memoryDetails), systemImage: "info.circle") }
        if let row = current, !row.blocked {
            if downloading {
                ProgressView()
            } else {
                Button { Task { await download() } } label: { Label(say(.memoryDownload), systemImage: "arrow.down.circle") }
            }
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
    @State private var picture: CGImage?
    @State private var read = false

    var body: some View {
        ZStack {
            if let said = row.said {
                Text(say(said)).foregroundStyle(.white).padding()
            } else if let playing {
                MediaVideo(held: held, row: row, reader: playing)
            } else {
                if let picture {
                    Image(decorative: picture, scale: 1).resizable().scaledToFit()
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
        }
        .onDisappear {
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
        }
        .navigationTitle(say(.memoryDetails))
        .task(id: row.mediaId) {
            details = try? await box.details(mediaId: row.mediaId)
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
