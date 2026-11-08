import SwiftUI
import UIKit
import UniformTypeIdentifiers
import VeyqCore

/// veyq's share extension (ADR 0124): what another app shares goes to the place picked in veyq's own navigation. The
/// extension is the app's views on the app's model in the extension role: it signs in only from what the app left
/// (decision 5), and the chosen Pot's new expense takes the files as its receipts (decision 2).
final class ShareViewController: UIViewController {
    private let model = AppModel(inExtension: true)

    override func viewDidLoad() {
        super.viewDidLoad()
        model.onShareDone = { [weak self] saved in
            guard let context = self?.extensionContext else { return }
            if saved {
                context.completeRequest(returningItems: nil)
            } else {
                context.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
            }
        }
        let host = UIHostingController(rootView: RootView(model: model))
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)

        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        Task { @MainActor in
            model.receiveShare(await Self.read(providers))
            model.start()
        }
    }

    /// What a share extension holds whole at once (decision 6): the files beyond it are left out, as too large ones are.
    private static let budget = 64 * 1024 * 1024

    /// Every attachment read once, in order: an image as JPEG where the sender offers one (decision 7), a PDF as it is.
    private static func read(_ providers: [NSItemProvider]) async -> AppModel.Incoming {
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
        return AppModel.Incoming(files: files, refused: refused)
    }

    private static func load(_ provider: NSItemProvider) async -> (Data, String)? {
        if provider.hasItemConformingToTypeIdentifier(UTType.pdf.identifier) {
            return await data(provider, UTType.pdf).map { ($0, "application/pdf") }
        }
        guard provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) else { return nil }
        if provider.hasItemConformingToTypeIdentifier(UTType.jpeg.identifier), let d = await data(provider, UTType.jpeg) {
            return (d, "image/jpeg")
        }
        // A sender with no JPEG of its own (a HEIC only, a PNG): a HEIC re-encoded (decision 7), any other image as it is.
        let first = provider.registeredContentTypes.first { $0.conforms(to: .image) } ?? .image
        guard let d = await data(provider, first) else { return nil }
        if first.conforms(to: .heic) || first.conforms(to: .heif) {
            guard let jpeg = UIImage(data: d)?.jpegData(compressionQuality: 0.9) else { return nil }
            return (jpeg, "image/jpeg")
        }
        return (d, first.preferredMIMEType ?? "")
    }

    private static func data(_ provider: NSItemProvider, _ type: UTType) async -> Data? {
        await withCheckedContinuation { c in
            _ = provider.loadDataRepresentation(for: type) { data, _ in c.resume(returning: data) }
        }
    }

    /// The sender's name for the file, with the extension of what was taken (a HEIC taken as a JPEG is named so).
    private static func named(_ name: String, type: String) -> String {
        guard let ext = UTType(mimeType: type)?.preferredFilenameExtension else { return name }
        let base = (name as NSString).deletingPathExtension
        return "\(base.isEmpty ? name : base).\(ext)"
    }
}
