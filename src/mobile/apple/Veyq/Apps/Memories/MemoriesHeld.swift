import AVFoundation
import ImageIO
import SwiftUI
import UniformTypeIdentifiers
import VeyqCore

// What the app's model holds for an application's open sheet or window (ADR 0154 decision 3): Memories' participants
// picker and banner dialog, the banner's decoded pictures, a Mac's lightbox window's value. Stored properties of
// `AppModel` (core UI), so their types are the core UI's, beside it — as `AppModel`'s per-application methods stay core's
// on every platform; drawn by Memories (`MemoryCuration.swift`, `MemoryScreen.swift`).

/// The open gallery's participants picker (core's `ParticipantsPicker`), held by the app's model while its sheet shows.
@MainActor
final class ParticipantsEdit: Identifiable {
    let picker: ParticipantsPicker
    let gallery: GalleryModel
    let state: CoreState<ParticipantsPicker.State>

    init(picker: ParticipantsPicker, gallery: GalleryModel) {
        self.picker = picker
        self.gallery = gallery
        state = CoreState({ picker.current }) { changed in AppleMemories.shared.watch(flow: picker.state) { @Sendable _ in changed() } }
        load()
    }

    /// The scope's members read (again: *Try again*).
    func load() {
        let picker = picker
        Task { try? await picker.load() }
    }

    func close() { state.stop() }
}

/// A banner's picture decoded once, with its surround: the same picture at most 32 px on its long edge (core's
/// `BannerFrame.surround`), scaled down with smooth filtering — stretched back over the frame, it is the blur (the web's
/// 32 px trick, no blur API).
struct BannerPicture {
    let picture: CGImage
    let surround: CGImage

    static func of(_ data: Data?) -> BannerPicture? {
        guard let data, let image = CoverImages.image(data, maxPixels: 4096) else { return nil }
        let size = BannerFrame.shared.surround(width: Int32(image.width), height: Int32(image.height))
        let w = Int(size.get(index: 0)), h = Int(size.get(index: 1))
        guard let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let small = context.makeImage() else { return nil }
        return BannerPicture(picture: image, surround: small)
    }
}

/// A banner's pictures decoded on this device for the core (`BannerWrite.Pictures`, adapted by `AppleMemories.bannerPictures`
/// since Swift cannot implement a suspend function): an image upright as the cover's JPEG (`NodeCover`'s long edge, never
/// enlarged, its quality), a video's first frame turned as it is shown, at the same spec. Read inside the picked file's
/// security scope; nil where it does not decode here.
enum BannerImages {
    /// The decoder as core takes it, one per dialog; each answer handed back once, on any thread.
    static func pictures() -> any BannerWritePictures {
        AppleMemories.shared.bannerPictures(
            image: { url, done in
                nonisolated(unsafe) let done = done
                Task.detached { _ = done(image(url)) }
            },
            poster: { url, done in
                nonisolated(unsafe) let done = done
                Task.detached { _ = done(await poster(url)) }
            }
        )
    }

    private static var edge: Int { Int(NodeCover.shared.LONG_EDGE) }

    static func image(_ url: URL) -> Data? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int
        else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: min(edge, max(w, h)),
        ]
        guard let picture = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return jpeg(picture)
    }

    static func poster(_ url: URL) async -> Data? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: edge, height: edge)
        guard let frame = try? await generator.image(at: .zero).image else { return nil }
        return jpeg(frame)
    }

    private static func jpeg(_ image: CGImage) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: NodeCover.shared.QUALITY] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }
}

/// The open gallery's banner dialog (core's `BannerEditor`), held by the app's model while its sheet shows: the file picked
/// (a Photos copy removed once the dialog is done with it), the pick's decoding and the save's run, which Cancel stops —
/// the old banner then stays as it was.
@MainActor
@Observable
final class BannerEdit: Identifiable {
    let editor: BannerEditor
    let gallery: GalleryModel
    @ObservationIgnored let state: CoreState<BannerEditor.State>
    /// The picked file's decoded picture for the previews.
    private(set) var picked: BannerPicture?
    /// The banner as it is, for the previews until a file is picked.
    private(set) var now: BannerPicture?
    @ObservationIgnored private var run: AppleMemories.Watch?
    @ObservationIgnored private var picking: AppleMemories.Watch?
    /// The Photos picker's copy of the file picked last: removed when another is picked and when the dialog closes.
    @ObservationIgnored private var copy: URL?

    init(editor: BannerEditor, gallery: GalleryModel) {
        self.editor = editor
        self.gallery = gallery
        state = CoreState({ editor.current }) { changed in AppleMemories.shared.watch(flow: editor.state) { @Sendable _ in changed() } }
        Task { [weak self] in
            let data = (try? await AppleMemories.shared.bannerPicture(gallery: gallery)).map { $0 as Data }
            self?.now = BannerPicture.of(data)
        }
    }

    /// `source` taken as the banner (core refuses or decodes it); `copy` the Photos picker's file it reads, if it is one.
    func pick(_ source: UploadSource, copy: URL?) {
        picking?.stop()
        if let old = self.copy { AppModel.removePicked(old) }
        self.copy = copy
        picked = nil
        picking = AppleMemories.shared.bannerPick(editor: editor, source: source) { @Sendable [weak self] in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.state.refresh()
                self.picked = self.editor.current.picked.map { BannerPicture.of(AppleMemories.shared.pickedPicture(picked: $0) as Data) } ?? nil
            }
        }
    }

    /// The picked file made the banner, or the banner `remove`d: one run at a time, stopped by `cancel`.
    func save(remove: Bool = false) {
        guard run == nil else { return }
        run = AppleMemories.shared.bannerSave(editor: editor, remove: remove) { @Sendable [weak self] _ in
            Task { @MainActor [weak self] in
                self?.run = nil
                self?.state.refresh()
            }
        }
    }

    func cancel() {
        run?.stop()
        run = nil
    }

    func close() {
        cancel()
        picking?.stop()
        picking = nil
        state.stop()
        if let copy { AppModel.removePicked(copy) }
        copy = nil
    }
}

/// A Mac's lightbox window's value: the ids alone (opaque; a window's value is what the system may keep), never a name or a
/// key. (Declared on every platform, as `AppModel.windowLightbox` is; only a Mac opens such a window.)
struct LightboxWindowValue: Codable, Hashable {
    let scopeId: String
    let nodeId: String
    let mediaId: String
}
