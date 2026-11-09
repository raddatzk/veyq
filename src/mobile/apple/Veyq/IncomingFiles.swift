import Foundation
import ImageIO
import UniformTypeIdentifiers
import VeyqCore

/// What another app hands veyq — through the share sheet (the extension) or dropped onto the app (ADR 0126) — read once, in
/// order, into what a Pot's new expense takes as its receipts: an image as JPEG where the sender offers one, a HEIC
/// re-encoded (decision 7), any other image and a PDF as they are; at most a Pot's receipts' count, each within its size,
/// and at most [budget] held at once (decision 6). What does not fit is counted as left out.
@MainActor
enum IncomingFiles {
    /// What is held whole at once: a share extension's memory is bounded; the files beyond it are left out as too large ones are.
    static let budget = 64 * 1024 * 1024

    /// The type identifiers a drop or a share is offered for.
    static let accepted: [UTType] = [.image, .pdf]

    static func read(_ providers: [NSItemProvider]) async -> AppModel.Incoming {
        var files: [PotSession.PendingReceipt] = []
        var refused = 0
        var held = 0
        for provider in providers {
            guard let (data, type) = await load(provider) else { refused += 1; continue }
            let name = provider.suggestedName.map { named($0, type: type) }
            let fits = PotShare.shared.takes(type: type, name: name, size: Int64(data.count))
                && files.count < Int(PotShare.shared.MAX_FILES) && held + data.count <= budget
            guard fits else { refused += 1; continue }
            held += data.count
            files.append(AppleSessions.shared.pendingReceipt(id: UUID().uuidString.lowercased(), data: data, type: type, name: name, modifiedAt: nil))
        }
        return AppModel.Incoming(files: files, refused: Int32(refused))
    }

    private static func load(_ provider: NSItemProvider) async -> (Data, String)? {
        if provider.hasItemConformingToTypeIdentifier(UTType.pdf.identifier) {
            return await data(provider, .pdf).map { ($0, "application/pdf") }
        }
        guard provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) else { return nil }
        if provider.hasItemConformingToTypeIdentifier(UTType.jpeg.identifier), let d = await data(provider, .jpeg) {
            return (d, "image/jpeg")
        }
        // A sender with no JPEG of its own (a HEIC only, a PNG): a HEIC re-encoded, any other image as it is.
        let first = provider.registeredContentTypes.first { $0.conforms(to: .image) } ?? .image
        guard let d = await data(provider, first) else { return nil }
        if first.conforms(to: .heic) || first.conforms(to: .heif) {
            return jpeg(d).map { ($0, "image/jpeg") }
        }
        return (d, first.preferredMIMEType ?? "")
    }

    private static func data(_ provider: NSItemProvider, _ type: UTType) async -> Data? {
        await withCheckedContinuation { c in
            _ = provider.loadDataRepresentation(for: type) { data, _ in c.resume(returning: data) }
        }
    }

    /// An image's bytes re-encoded as JPEG (ImageIO, on iOS and macOS alike); nil when they do not decode.
    private static func jpeg(_ data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) > 0 else { return nil }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImageFromSource(dest, source, 0, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }

    /// The sender's name for the file, with the extension of what was taken (a HEIC taken as a JPEG is named so).
    private static func named(_ name: String, type: String) -> String {
        guard let ext = UTType(mimeType: type)?.preferredFilenameExtension else { return name }
        let base = (name as NSString).deletingPathExtension
        return "\(base.isEmpty ? name : base).\(ext)"
    }
}
