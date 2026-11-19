import Foundation
import ImageIO
import UniformTypeIdentifiers
import VeyqCore

/// What another app hands veyq — through the share sheet (the extension) or dropped onto the app (ADR 0138) — copied once,
/// in order, each into a directory of its own under `AppModel.pickedDir` (`loadFileRepresentation`: the system's file is
/// valid only inside its callback), and handed on as a source read a frame at a time: nothing is held whole. An image as
/// JPEG where the sender offers one, a HEIC re-encoded (decision 7), any other image, a video and a PDF as they are. The
/// kind chosen judges them (core's `ShareAnswer`); each copy is removed once nothing reads it again (`AppModel.removePicked`).
@MainActor
enum IncomingFiles {

    /// The type identifiers a drop or a share is offered for.
    static let accepted: [UTType] = [.image, .movie, .pdf]

    static func read(_ providers: [NSItemProvider]) async -> AppModel.Incoming {
        var files: [UploadSource] = []
        var refused = 0
        for provider in providers {
            guard let (url, type) = await load(provider), let source = source(url, type: type, suggested: provider.suggestedName) else {
                refused += 1
                continue
            }
            files.append(source)
        }
        return AppModel.Incoming(files: files, refused: Int32(refused))
    }

    private static func source(_ url: URL, type: String, suggested: String?) -> UploadSource? {
        guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize else {
            AppModel.removePicked(url)
            return nil
        }
        let name = suggested.map { named($0, type: type) } ?? url.lastPathComponent
        return AppleMemories.shared.source(url: url, name: name, size: Int64(size), type: type, modifiedAt: nil)
    }

    private static func load(_ provider: NSItemProvider) async -> (URL, String)? {
        if provider.hasItemConformingToTypeIdentifier(UTType.pdf.identifier) {
            return await copy(provider, .pdf).map { ($0, "application/pdf") }
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
            let first = provider.registeredContentTypes.first { $0.conforms(to: .movie) } ?? .movie
            return await copy(provider, first).map { ($0, first.preferredMIMEType ?? "video/quicktime") }
        }
        guard provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) else { return nil }
        if provider.hasItemConformingToTypeIdentifier(UTType.jpeg.identifier), let url = await copy(provider, .jpeg) {
            return (url, "image/jpeg")
        }
        // A sender with no JPEG of its own (a HEIC only, a PNG): a HEIC re-encoded, any other image as it is.
        let first = provider.registeredContentTypes.first { $0.conforms(to: .image) } ?? .image
        guard let url = await copy(provider, first) else { return nil }
        if first.conforms(to: .heic) || first.conforms(to: .heif) {
            return jpeg(url).map { ($0, "image/jpeg") }
        }
        return (url, first.preferredMIMEType ?? "")
    }

    /// The provider's file of `type` copied, inside the callback, into a directory of its own under `pickedDir`.
    private static func copy(_ provider: NSItemProvider, _ type: UTType) async -> URL? {
        let dir = AppModel.pickedDir.appendingPathComponent(UUID().uuidString, isDirectory: true)
        return await withCheckedContinuation { c in
            _ = provider.loadFileRepresentation(for: type, openInPlace: false) { file, _, _ in
                guard let file else { c.resume(returning: nil); return }
                let to = dir.appendingPathComponent(file.lastPathComponent)
                do {
                    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                    try FileManager.default.copyItem(at: file, to: to)
                    c.resume(returning: to)
                } catch {
                    try? FileManager.default.removeItem(at: dir)
                    c.resume(returning: nil)
                }
            }
        }
    }

    /// An image file re-encoded as JPEG beside it (ImageIO, file to file, on iOS and macOS alike), the original removed;
    /// nil when it does not decode.
    private static func jpeg(_ url: URL) -> URL? {
        let out = url.deletingPathExtension().appendingPathExtension("jpg")
        defer { try? FileManager.default.removeItem(at: url) }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil), CGImageSourceGetCount(source) > 0,
              let dest = CGImageDestinationCreateWithURL(out as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            AppModel.removePicked(url)
            return nil
        }
        CGImageDestinationAddImageFromSource(dest, source, 0, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else {
            AppModel.removePicked(url)
            return nil
        }
        return out
    }

    /// The sender's name for the file, with the extension of what was taken (a HEIC taken as a JPEG is named so).
    private static func named(_ name: String, type: String) -> String {
        guard let ext = UTType(mimeType: type)?.preferredFilenameExtension else { return name }
        let base = (name as NSString).deletingPathExtension
        return "\(base.isEmpty ? name : base).\(ext)"
    }
}
