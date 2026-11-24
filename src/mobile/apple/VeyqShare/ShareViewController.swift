import SwiftUI
import UIKit
import VeyqCore

/// veyq's share extension (ADR 0138): what another app shares goes to the place picked in veyq's own navigation. The
/// extension is the app's views on the app's model in the extension role: it signs in only from what the app left
/// (decision 5); the chosen Pot's new expense takes the files as its receipts, and a Memory's *Add here* hands them to the
/// app through the app group, which uploads them (decisions 2 and 6). A share that carries no file but a link or a text is
/// a received link (inbox #1015, ADR 0150 § S): an extension cannot open its app, and handing the link over would keep its
/// key on disk, so the link opens here as its guest, as *Open a link* opens it in the app, and the extension closes once
/// it is left.
final class ShareViewController: UIViewController {
    private let model = AppModel(inExtension: true)

    override func viewDidLoad() {
        super.viewDidLoad()
        // each application into the core UI's registry, before anything is drawn (ADR 0154 decision 3)
        Applications.register()
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

        model.guests.onDone = { [weak self] in self?.extensionContext?.completeRequest(returningItems: nil) }
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        Task { @MainActor in
            if let text = await IncomingFiles.sharedText(providers) {
                model.guests.receive(text, model)
            } else {
                model.receiveShare(await IncomingFiles.read(providers))
            }
            model.start()
        }
    }
}
