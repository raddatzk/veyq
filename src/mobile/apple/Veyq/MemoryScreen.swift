import AVKit
import SwiftUI
import UniformTypeIdentifiers
import VeyqCore

/// A Memory's or a chapter's page (Memories' `MemoryDetail.vue`, its gallery): the chapters a Memory holds, then every
/// medium as a square tile of its thumbnail in the grid's order (newest first by capture time), a video marked as one; a
/// tile opens the lightbox (`Lightbox`).
struct MemoryScreen: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    @State private var gallery: MemoryMedia.Gallery?
    @State private var failed = false
    @State private var chapters: [NodeRef] = []
    @State private var open: LightboxStart?

    private var items: [MemoryMedia.Item] { gallery?.items ?? [] }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                NodeHeroRow()
                if !chapters.isEmpty {
                    Text("Chapters").font(.headline).padding(.horizontal)
                    ForEach(chapters, id: \.nodeId) { c in
                        Button {
                            if let o = c.asObject() { model.open(o) }
                        } label: {
                            Label(c.name, systemImage: Names.kindSymbol(c.kind)).frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.horizontal)
                    }
                }
                if failed {
                    Text("The gallery could not be loaded.").foregroundStyle(Role.danger).padding()
                } else if gallery == nil {
                    ProgressView().frame(maxWidth: .infinity).padding()
                } else if items.isEmpty {
                    Text("No photos or videos yet.").foregroundStyle(Role.muted).padding()
                } else {
                    Text(items.count == 1 ? "1 item" : "\(items.count) items").font(.footnote).foregroundStyle(Role.muted).padding(.horizontal)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 2)], spacing: 2) {
                        ForEach(Array(items.enumerated()), id: \.element.mediaId) { index, item in
                            Button { open = LightboxStart(index: index) } label: { MediaTile(model: model, item: item) }
                                .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .navigationTitle(ref.name)
        // read again whenever the lists are
        .task(id: model.rowDetails.count) {
            if let read = await model.memoryGallery(scopeId: ref.scopeId, nodeId: ref.nodeId) { gallery = read; failed = false } else { failed = true }
            chapters = ref.kind == "MEMORY"
                ? await model.scopeNodes(ref.scopeId).filter { $0.parentId == ref.nodeId && $0.kind == "CHAPTER" }
                : []
        }
        #if os(iOS)
        .fullScreenCover(item: $open) { start in Lightbox(model: model, items: items, memoryName: ref.name, index: start.index) }
        #else
        .sheet(item: $open) { start in Lightbox(model: model, items: items, memoryName: ref.name, index: start.index).frame(minWidth: 640, minHeight: 480) }
        #endif
    }
}

/// Where the lightbox opens (a sheet's item).
private struct LightboxStart: Identifiable {
    let index: Int
    var id: Int { index }
}

/// One square tile: the medium's grid tier cropped to fill, a video marked; an icon where it has no tile to show.
private struct MediaTile: View {
    let model: AppModel
    let item: MemoryMedia.Item
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
                if item.isVideo { Image(systemName: "play.circle.fill").font(.title).foregroundStyle(.white) }
            }
            .clipped()
            .accessibilityLabel(item.name ?? "")
            .task(id: item.mediaId) {
                if let blob = item.gridBlob { picture = await model.mediaPicture(item, blob: blob) }
            }
    }
}

/// The lightbox (the web's `MediaLightbox.vue`): the gallery paged through on black, each medium's preview fitted, a video's
/// poster with a play button that streams the original as the player reads it (`MediaVideo`); its bar closes, names the
/// place in the gallery, opens the details (`MediaDetailsView`) and saves the original through the system's save sheet.
private struct Lightbox: View {
    let model: AppModel
    let items: [MemoryMedia.Item]
    let memoryName: String
    @State var index: Int
    @State private var details = false
    @State private var downloading = false
    @State private var downloaded: URL?
    @State private var failed: String?
    @Environment(\.dismiss) private var dismiss

    private var current: MemoryMedia.Item? { items.indices.contains(index) ? items[index] : nil }

    var body: some View {
        NavigationStack {
            // the pages, then the caption — stacked, not floated over the picture
            VStack(alignment: .leading, spacing: 0) {
                TabView(selection: $index) {
                    ForEach(Array(items.enumerated()), id: \.element.mediaId) { i, item in
                        LightboxPage(model: model, item: item, current: i == index).tag(i)
                    }
                }
                #if os(iOS)
                .tabViewStyle(.page(indexDisplayMode: .never))
                #endif
                VStack(alignment: .leading) {
                    if let item = current {
                        Text(item.name ?? "").font(.headline)
                        if let at = item.meta?.effectiveCapturedAt {
                            Text(Date(timeIntervalSince1970: Double(truncating: at) / 1000), format: .dateTime.day().month().year().hour().minute())
                                .font(.footnote).opacity(0.8)
                        }
                    }
                }
                .foregroundStyle(.white)
                .padding()
            }
            .background(Color.black.ignoresSafeArea())
            .navigationTitle("\(index + 1) of \(items.count)")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.black, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Label("Close", systemImage: "xmark") }
                }
                ToolbarItemGroup(placement: .primaryAction) {
                    Button { details = true } label: { Label("Details", systemImage: "info.circle") }
                    if let item = current, !item.blocked {
                        if downloading {
                            ProgressView()
                        } else {
                            Button { Task { await download(item) } } label: { Label("Download Original", systemImage: "arrow.down.circle") }
                        }
                    }
                }
            }
            .sheet(isPresented: $details) {
                if let item = current {
                    MediaDetailsView(model: model, item: item)
                        .presentationDetents([.medium, .large])
                }
            }
            .fileMover(isPresented: Binding(get: { downloaded != nil }, set: { if !$0 { cleanUpDownload() } }), file: downloaded) { _ in }
            .alert(failed ?? "", isPresented: Binding(get: { failed != nil }, set: { if !$0 { failed = nil } })) {
                Button("OK", role: .cancel) {}
            }
        }
        .environment(\.colorScheme, .dark)
    }

    /// The original, written to a temporary file under its download name, then offered to the system's save sheet.
    private func download(_ item: MemoryMedia.Item) async {
        let name = MediaDetails.shared.downloadName(item: item, memoryName: memoryName, index: Int32(items.firstIndex(of: item) ?? 0))
        downloading = true
        let url = await model.downloadMedium(item, name: name)
        downloading = false
        if let url { downloaded = url } else { failed = String(localized: "“\(name)” could not be downloaded. Please try again.") }
    }

    private func cleanUpDownload() {
        if let url = downloaded { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        downloaded = nil
    }
}

/// One medium of the lightbox: its preview fitted, a video's poster with its play button, a blocked one said so.
private struct LightboxPage: View {
    let model: AppModel
    let item: MemoryMedia.Item
    let current: Bool
    @State private var picture: CGImage?
    @State private var read = false
    @State private var playing = false

    var body: some View {
        ZStack {
            if item.blocked {
                Text("This item was blocked by the operator.").foregroundStyle(.white).padding()
            } else if playing {
                MediaVideo(model: model, item: item)
            } else {
                if let picture {
                    Image(decorative: picture, scale: 1).resizable().scaledToFit()
                } else if !read {
                    ProgressView().tint(.white)
                } else if !item.isVideo {
                    Image(systemName: "photo").font(.largeTitle).foregroundStyle(.white)
                }
                if item.isVideo {
                    Button { playing = true } label: {
                        Image(systemName: "play.circle.fill").font(.system(size: 64)).foregroundStyle(.white)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Play Video")
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // a video stops when it is paged away from
        .onChange(of: current) { if !current { playing = false } }
        .task(id: item.mediaId) {
            // a video without a poster has none to show: its original is no picture
            if !(item.isVideo && item.preview == nil) { picture = await model.mediaPicture(item, blob: item.lightboxBlob) }
            read = true
        }
    }
}

/// A video played as it is read (the web's range playback, ADR 0047): the system's player asks `ReaderLoader` for byte
/// ranges, each served from the frames that hold it through core's `RangeReader`, so the plaintext is never held whole nor
/// written anywhere. The system's controls.
private struct MediaVideo: View {
    let model: AppModel
    let item: MemoryMedia.Item
    @State private var playback: Playback?
    @State private var failed = false

    var body: some View {
        Group {
            if let playback {
                VideoPlayer(player: playback.player)
            } else if failed {
                Text("The video couldn’t be played.").foregroundStyle(.white).padding()
            } else {
                ProgressView().tint(.white)
            }
        }
        .task(id: item.mediaId) {
            guard let reader = model.mediaReader(item) else {
                failed = true
                return
            }
            let p = Playback(reader: reader, mime: item.type)
            playback = p
            p.player.play()
        }
        .onDisappear {
            playback?.player.pause()
            playback = nil
        }
    }
}

/// A player over core's range reader: the asset's address is a scheme only `ReaderLoader` answers.
@MainActor
private final class Playback {
    let player: AVPlayer
    private let loader: ReaderLoader

    init(reader: RangeReader, mime: String?) {
        loader = ReaderLoader(reader: reader, mime: mime)
        let asset = AVURLAsset(url: URL(string: "veyq-media://medium/original")!)
        // the loader is held here: the resource loader keeps its delegate weakly
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
    }
}

/// Answers the player's requests for the original: its size and type, then each byte range, read a piece at a time.
private final class ReaderLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    let queue = DispatchQueue(label: "me.raddatz.veyq.media-loader")
    private let reader: RangeReader
    private let contentType: String
    /// The reads in flight by request, cancelled with it (touched on [queue] only).
    private var reads: [ObjectIdentifier: Task<Void, Never>] = [:]
    private static let piece: Int64 = 512 * 1024

    init(reader: RangeReader, mime: String?) {
        self.reader = reader
        contentType = UTType(mimeType: mime ?? "video/mp4")?.identifier ?? UTType.mpeg4Movie.identifier
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        if let info = loadingRequest.contentInformationRequest {
            info.contentType = contentType
            info.contentLength = reader.size
            info.isByteRangeAccessSupported = true
        }
        guard let data = loadingRequest.dataRequest else {
            loadingRequest.finishLoading()
            return true
        }
        let request = Request(loading: loadingRequest, data: data)
        let id = ObjectIdentifier(loadingRequest)
        let size = reader.size
        let end = data.requestsAllDataToEndOfResource ? size : min(size, data.requestedOffset + Int64(data.requestedLength))
        let reader = self.reader
        let queue = self.queue
        reads[id] = Task {
            var offset = data.currentOffset
            do {
                while offset < end, !Task.isCancelled {
                    let chunk = try await AppleMemories.shared.readRange(reader: reader, position: offset, length: Int32(min(end - offset, Self.piece)))
                    if chunk.isEmpty { break }
                    offset += Int64(chunk.count)
                    queue.async { request.data.respond(with: chunk) }
                }
                queue.async {
                    if !Task.isCancelled { request.loading.finishLoading() }
                    self.reads[id] = nil
                }
            } catch {
                queue.async {
                    request.loading.finishLoading(with: error)
                    self.reads[id] = nil
                }
            }
        }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        let id = ObjectIdentifier(loadingRequest)
        reads[id]?.cancel()
        reads[id] = nil
    }

    /// A request handed across to the read (the player answers it on [queue] alone).
    private struct Request: @unchecked Sendable {
        let loading: AVAssetResourceLoadingRequest
        let data: AVAssetResourceLoadingDataRequest
    }
}

/// A medium's details (the web's details panel): the capture time and the file's facts at once, the camera's once its
/// EXIF tier is read, who added it, its place and position; "no details" only where nothing is known.
private struct MediaDetailsView: View {
    let model: AppModel
    let item: MemoryMedia.Item
    @State private var exif: MediaDetails.Exif?
    @State private var uploaders: [String: MediaDetails.Uploader] = [:]
    @Environment(\.dismiss) private var dismiss

    private var rows: [MediaDetails.Row] { MediaDetails.shared.rows(item: item, exif: exif) }

    private var uploader: String? {
        guard let key = item.ownerKey, let u = uploaders[key] else { return nil }
        switch u.kind {
        case .member: return u.name
        case .former: return String(localized: "Former member")
        default: return String(localized: "A guest")
        }
    }

    var body: some View {
        NavigationStack {
            let rows = self.rows
            // who added it stands before the place, as on the web
            let placeAt = rows.firstIndex { $0.field == .place || $0.field == .location } ?? rows.count
            List {
                if rows.isEmpty && uploader == nil {
                    Text("No details for this item.").foregroundStyle(Role.muted)
                }
                ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
                    if i == placeAt, let uploader { LabeledContent("Added by", value: uploader) }
                    LabeledContent(Self.label(row.field), value: Self.value(row))
                }
                if placeAt == rows.count, let uploader { LabeledContent("Added by", value: uploader) }
            }
            .navigationTitle("Details")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .task(id: item.mediaId) {
                uploaders = await model.mediaUploaders(scopeId: item.scopeId, items: [item])
                exif = await model.mediaExif(item)
            }
        }
    }

    private static func label(_ field: MediaDetails.Field) -> String {
        switch field {
        case .captured: String(localized: "Taken")
        case .fileName: String(localized: "File")
        case .fileSize: String(localized: "Size")
        case .fileFormat: String(localized: "Format")
        case .camera: String(localized: "Camera")
        case .lens: String(localized: "Lens")
        case .focalLength: String(localized: "Focal Length")
        case .focalLength35: String(localized: "Equivalent")
        case .aperture: String(localized: "Aperture")
        case .exposure: String(localized: "Exposure")
        case .exposureCompensation: String(localized: "Exposure Compensation")
        case .iso: String(localized: "ISO")
        case .flash: String(localized: "Flash")
        case .dimensions: String(localized: "Dimensions")
        case .title: String(localized: "Title")
        case .description_: String(localized: "Description")
        case .rating: String(localized: "Rating")
        case .software: String(localized: "Software")
        case .artist: String(localized: "Author")
        case .copyright: String(localized: "Copyright")
        case .altitude: String(localized: "Altitude")
        case .directionTrue, .directionMagnetic: String(localized: "Direction")
        case .gpsAccuracy: String(localized: "Position Accuracy")
        case .corrected: String(localized: "Status")
        case .place: String(localized: "Place")
        case .location: String(localized: "Location")
        default: ""
        }
    }

    private static func value(_ row: MediaDetails.Row) -> String {
        let n = row.number?.int64Value ?? 0
        switch row.field {
        case .captured: return Date(timeIntervalSince1970: Double(n) / 1000).formatted(date: .abbreviated, time: .shortened)
        case .fileSize: return ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
        case .flash: return n == 1 ? String(localized: "Fired") : String(localized: "Did not fire")
        case .directionTrue: return String(localized: "\(n)° (true north)")
        case .directionMagnetic: return String(localized: "\(n)° (magnetic north)")
        case .corrected: return String(localized: "Corrected")
        default: return row.value
        }
    }
}
