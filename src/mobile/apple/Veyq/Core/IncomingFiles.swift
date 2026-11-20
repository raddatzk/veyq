import Foundation
import ImageIO
import UniformTypeIdentifiers
import VeyqCore

/// What another app hands veyq — through the share sheet (the extension) or dropped onto the app (ADR 0138) — copied once,
/// in order, each into a directory of its own under `AppModel.pickedDir` (`loadFileRepresentation`: the system's file is
/// valid only inside its callback), and handed on as a source read a frame at a time: nothing is held whole. An image as
/// JPEG where the sender offers one, a HEIC re-encoded (decision 7), any other file as it is (decision 1: a folder takes
/// any file). An attachment that carries no file — a bare text, a web page's link — is neither taken nor counted, so a
/// share of only that says nothing could be taken (until #1015). The kind chosen judges them (core's `ShareAnswer`); each
/// copy is removed once nothing reads it again (`AppModel.removePicked`).
@MainActor
enum IncomingFiles {

    /// The type identifiers a drop is offered for: any item (decision 1); where it lands judges it.
    static let accepted: [UTType] = [.item]

    static func read(_ providers: [NSItemProvider]) async -> AppModel.Incoming {
        var files: [UploadSource] = []
        var refused = 0
        for provider in providers where carriesFile(provider) {
            guard let (url, type) = await load(provider), let source = source(url, type: type, suggested: provider.suggestedName) else {
                refused += 1
                continue
            }
            files.append(source)
        }
        return AppModel.Incoming(files: files, refused: Int32(refused))
    }

    /// A drop's items as core's one drop takes them (`ShellModel.drop`, ADR 0154 decision 1): each told a file from a folder
    /// by the file system and nothing more — a folder (macOS) walked in place as Depot's *Upload folder…* walks its pick, a
    /// file copied as `read` copies it, one that did not read named by what the sender called it. An attachment carrying no
    /// file is neither taken nor said, as `read`'s. Where it lands judges, sorts and says (the core).
    static func dropped(_ providers: [NSItemProvider]) async -> [Dropped] {
        var items: [Dropped] = []
        #if os(macOS)
        let (folders, rest) = await folders(providers)
        for url in folders {
            let files = AppModel.folderFiles(url)
            items.append(Dropped.Folder(path: url.path, shownName: nil, found: AppleDepot.shared.found(root: url, files: files)))
        }
        #else
        let rest = providers
        #endif
        for provider in rest where carriesFile(provider) {
            if let (url, type) = await load(provider), let source = source(url, type: type, suggested: provider.suggestedName) {
                items.append(Dropped.File(source: source))
            } else {
                items.append(Dropped.Unread(name: provider.suggestedName ?? ""))
            }
        }
        return items
    }

    private static func source(_ url: URL, type: String, suggested: String?) -> UploadSource? {
        guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize else {
            AppModel.removePicked(url)
            return nil
        }
        let name = suggested.map { named($0, type: type) } ?? url.lastPathComponent
        return AppleMemories.shared.source(url: url, name: name, size: Int64(size), type: type, modifiedAt: nil)
    }

    /// Whether `provider` carries a file: a file's URL, or a type that is data other than a link — a text only where the
    /// sender named it (a file's). A bare text or a web page's link carries none. Not measured against Safari's and the
    /// Files app's shares yet (the owner's iPhone pass, ADR 0138's Consequences).
    private static func carriesFile(_ provider: NSItemProvider) -> Bool {
        let types = provider.registeredContentTypes
        if types.contains(where: { $0.conforms(to: .fileURL) }) { return true }
        let data = types.filter { $0.conforms(to: .data) && !$0.conforms(to: .url) }
        if data.isEmpty { return false }
        if data.allSatisfy({ $0.conforms(to: .text) }) { return provider.suggestedName != nil }
        return true
    }

    #if os(macOS)
    /// The dropped items that are folders, read in place as Depot's *Upload folder…* reads its pick, apart from the rest
    /// (decision 9: a folder dropped onto a level uploads as a folder; one holding `.veyq/manifest.json` is offered as an
    /// import, ADR 0152 decision 1).
    static func folders(_ providers: [NSItemProvider]) async -> ([URL], [NSItemProvider]) {
        var folders: [URL] = []
        var rest: [NSItemProvider] = []
        for provider in providers {
            if let url = await folderURL(provider) { folders.append(url) } else { rest.append(provider) }
        }
        return (folders, rest)
    }

    /// The folder `provider` carries, or nil where it carries none: by the file URL Finder hands where it registers one,
    /// else by the in-place representation of the folder's own type. A provider holding only `public.folder` (no
    /// `public.file-url`, none of `.data`) passed neither this nor `carriesFile`, so a dropped folder was skipped without
    /// a word while a ZIP went up (the macOS pass of 2026-11-20; which shape Finder's drop takes is not measured here —
    /// both are read).
    private static func folderURL(_ provider: NSItemProvider) async -> URL? {
        if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier), let url = await fileURL(provider) {
            return (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true ? url : nil
        }
        guard let type = provider.registeredContentTypes.first(where: { $0.conforms(to: .directory) }) else { return nil }
        return await inPlace(provider, type)
    }

    private static func fileURL(_ provider: NSItemProvider) async -> URL? {
        await withCheckedContinuation { c in
            _ = provider.loadObject(ofClass: URL.self) { url, _ in c.resume(returning: url) }
        }
    }

    /// The directory of `type` the provider holds: the sender's own where it is offered in place, else the system's copy
    /// (valid only inside the callback) copied into a directory of its own under `pickedDir`, swept at start and when the
    /// tenant closes (an upload reads its files after this returns).
    private static func inPlace(_ provider: NSItemProvider, _ type: UTType) async -> URL? {
        let dir = AppModel.pickedDir.appendingPathComponent(UUID().uuidString, isDirectory: true)
        return await withCheckedContinuation { c in
            _ = provider.loadInPlaceFileRepresentation(forTypeIdentifier: type.identifier) { file, inPlace, _ in
                guard let file else { c.resume(returning: nil); return }
                if inPlace { c.resume(returning: file); return }
                let to = dir.appendingPathComponent(file.lastPathComponent, isDirectory: true)
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
    #endif

    private static func load(_ provider: NSItemProvider) async -> (URL, String)? {
        if provider.hasItemConformingToTypeIdentifier(UTType.pdf.identifier) {
            return await copy(provider, .pdf).map { ($0, "application/pdf") }
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
            let first = provider.registeredContentTypes.first { $0.conforms(to: .movie) } ?? .movie
            return await copy(provider, first).map { ($0, first.preferredMIMEType ?? "video/quicktime") }
        }
        guard provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) else {
            // any other file as it is, by the first type the sender registered that is data
            let first = provider.registeredContentTypes.first { $0.conforms(to: .data) && !$0.conforms(to: .url) } ?? .data
            return await copy(provider, first).map { ($0, first.preferredMIMEType ?? "application/octet-stream") }
        }
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
