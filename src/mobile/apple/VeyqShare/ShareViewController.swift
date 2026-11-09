import SwiftUI
import UIKit
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
            model.receiveShare(await IncomingFiles.read(providers))
            model.start()
        }
    }
}
