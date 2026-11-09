import PDFKit
import SwiftUI

/// A PDF's pages (PDFKit), from memory (a receipt) or a file (a Depot file's decrypted copy): nothing of the document runs
/// but its rendering, and its links are never followed — a peer's document opens nothing (`docs/apps/depot.md` § The viewer).
struct PDFPages {
    private let open: () -> PDFDocument?

    init(data: Data) { open = { PDFDocument(data: data) } }
    init(url: URL) { open = { PDFDocument(url: url) } }

    /// Takes every link click, so none is followed.
    final class Guard: NSObject, PDFViewDelegate {
        func pdfViewWillClick(onLink sender: PDFView, with url: URL) {}
    }

    fileprivate func make(_ guardian: Guard) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.delegate = guardian
        view.document = open()
        return view
    }
}

#if os(iOS)
extension PDFPages: UIViewRepresentable {
    func makeCoordinator() -> Guard { Guard() }
    func makeUIView(context: Context) -> PDFView { make(context.coordinator) }
    func updateUIView(_ view: PDFView, context: Context) {}
}
#else
extension PDFPages: NSViewRepresentable {
    func makeCoordinator() -> Guard { Guard() }
    func makeNSView(context: Context) -> PDFView { make(context.coordinator) }
    func updateNSView(_ view: PDFView, context: Context) {}
}
#endif
