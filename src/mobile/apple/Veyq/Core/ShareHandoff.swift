import Foundation
import VeyqCore

/// A Memory's or a folder's shared files handed from the share extension to the app (ADR 0138 decision 6, owner
/// 2026-11-19): *Add here* in the extension moves its copies into the app group's container, `share-in/<id>/<n>/<file>`,
/// with `target.json` naming where they go — a Memory, a chapter, a folder or a space's top —, and the extension closes.
/// The app queues them the next time it has that space open ([pending]) into Memories' queue or Depot's, and each copy is
/// removed once the queue lets it go ([remove]); a sign-out removes what still
/// waits ([clear]), and the app's start what was never finished ([sweep]). The files are the person's own plaintext,
/// not uploaded yet — nothing veyq decrypted. macOS has no extension: there is no such directory.
enum ShareHandoff {
    struct Target: Codable {
        /// The place's kind (`FOLDER`, `MEMORY`, `CHAPTER`); a handoff written before it names none and is a Memory's.
        let kind: String?
        let scopeId: String
        /// The node the files go into; none for a space's top.
        let nodeId: String?
        /// The place's name: the upload's batch label.
        let label: String
        let files: [File]

        /// Whether the files go into Depot's queue (a folder, a space's top), not Memories'.
        var isLevel: Bool { kind == NodeRef.companion.FOLDER }
    }

    struct File: Codable {
        /// The copy's path below the handoff's directory (`<n>/<file>`).
        let path: String
        let name: String
        let type: String?
        let size: Int64
    }

    /// One handoff found waiting: where its files are, and where they go.
    struct Waiting {
        let dir: URL
        let target: Target

        /// Its files as the upload reads them, a frame at a time.
        var sources: [UploadSource] {
            target.files.map {
                ApplePlatform.shared.source(url: dir.appendingPathComponent($0.path), name: $0.name, size: $0.size, type: $0.type, modifiedAt: nil)
            }
        }
    }

    nonisolated private static let targetName = "target.json"

    /// The app group's `share-in/`, where the extension leaves a Memory's or a folder's files; nil on macOS.
    nonisolated static var root: URL? {
        #if os(iOS)
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Shared.appGroup)?
            .appendingPathComponent("share-in", isDirectory: true)
        #else
        nil
        #endif
    }

    /// The extension's *Add here*: `sources` (copies the extension made, `IncomingFiles`) moved into a new handoff for
    /// node `nodeId` of `kind` in `scopeId` (none: the space's top), named `label`; the number handed off. Written under a
    /// dot name and renamed when whole, so the app never reads half of one.
    static func write(_ sources: [UploadSource], kind: String, scopeId: String, nodeId: String?, label: String) -> Int {
        guard let root else { return 0 }
        let id = UUID().uuidString
        let staging = root.appendingPathComponent("." + id, isDirectory: true)
        let fm = FileManager.default
        var files: [File] = []
        do {
            try fm.createDirectory(at: staging, withIntermediateDirectories: true)
            for (n, source) in sources.enumerated() {
                guard let url = ApplePlatform.shared.urlOf(source: source) else { continue }
                let path = "\(n)/\(url.lastPathComponent)"
                try fm.createDirectory(at: staging.appendingPathComponent("\(n)", isDirectory: true), withIntermediateDirectories: true)
                try fm.moveItem(at: url, to: staging.appendingPathComponent(path))
                AppModel.removePicked(url) // the extension's own directory the copy left
                files.append(File(path: path, name: source.name, type: source.type, size: source.size))
            }
            let target = Target(kind: kind, scopeId: scopeId, nodeId: nodeId, label: label, files: files)
            try JSONEncoder().encode(target).write(to: staging.appendingPathComponent(targetName), options: .atomic)
            try fm.moveItem(at: staging, to: root.appendingPathComponent(id, isDirectory: true))
            return files.count
        } catch {
            try? fm.removeItem(at: staging)
            return 0
        }
    }

    /// The handoffs waiting for a space in `scopeIds`, each claimed (its `target.json` removed) so it is queued once.
    static func pending(in scopeIds: Set<String>) -> [Waiting] {
        guard let root, let names = try? FileManager.default.contentsOfDirectory(atPath: root.path) else { return [] }
        return names.filter { !$0.hasPrefix(".") }.compactMap { name in
            let dir = root.appendingPathComponent(name, isDirectory: true)
            let file = dir.appendingPathComponent(targetName)
            guard let data = try? Data(contentsOf: file), let target = try? JSONDecoder().decode(Target.self, from: data),
                  scopeIds.contains(target.scopeId) else { return nil }
            try? FileManager.default.removeItem(at: file)
            return Waiting(dir: dir, target: target)
        }
    }

    /// Removes the handed-off copy at `url` with its own directory, and the handoff once no copy of it is left; any other
    /// file is left alone.
    nonisolated static func remove(_ url: URL) {
        guard let root else { return }
        let own = url.deletingLastPathComponent().standardizedFileURL.resolvingSymlinksInPath()
        let handoff = own.deletingLastPathComponent()
        guard handoff.deletingLastPathComponent().path == root.standardizedFileURL.resolvingSymlinksInPath().path else { return }
        let fm = FileManager.default
        try? fm.removeItem(at: own)
        let left = (try? fm.contentsOfDirectory(atPath: handoff.path)) ?? []
        if left.isEmpty { try? fm.removeItem(at: handoff) }
    }

    /// A sign-out: what still waits is removed unsent.
    static func clear() {
        guard let root else { return }
        try? FileManager.default.removeItem(at: root)
    }

    /// At the app's start, before anything is queued: a handoff already claimed (its upload's process ended) and one the
    /// extension never finished writing (older than a minute) are removed.
    static func sweep() {
        guard let root, let names = try? FileManager.default.contentsOfDirectory(atPath: root.path) else { return }
        let fm = FileManager.default
        for name in names {
            let dir = root.appendingPathComponent(name, isDirectory: true)
            if name.hasPrefix(".") {
                let made = (try? dir.resourceValues(forKeys: [.creationDateKey]))?.creationDate ?? .distantPast
                if made < Date().addingTimeInterval(-60) { try? fm.removeItem(at: dir) }
            } else if !fm.fileExists(atPath: dir.appendingPathComponent(targetName).path) {
                try? fm.removeItem(at: dir)
            }
        }
    }
}
