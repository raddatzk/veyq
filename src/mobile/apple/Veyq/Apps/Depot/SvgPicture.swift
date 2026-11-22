import SwiftUI
import VeyqCore
import WebKit

/// An SVG on a file's page (inbox #1059; ADR 0155 § The file facts' slice D15): drawn by WebKit in its image mode — the SVG is
/// only the `<img>` of core's page (`SvgView.document`), and a browser's image mode runs no script and loads nothing an SVG
/// names (measured on macOS: ten hostile files naming a loopback listener, no connection). Around it, the web view is closed:
/// the page's script off, a store of its own in memory, a content rule that blocks every load but this view's scheme, no
/// navigation but its own page, no window, no link preview; the page and the picture are served from memory by a scheme
/// handler (`veyq-svg:`), nothing written. A `foreignObject` never gets here (`SvgView.refused`, in the core's open). The zoom
/// lays the picture out again at each step (`SvgView.zoomScript`, the app's own script, which WebKit runs beside the page's
/// script turned off), so WebKit paints its vectors anew, only the tiles in view: sharp, no bitmap past the view. A pinch
/// (iOS) and the trackpad's magnification (macOS) zoom too.
struct SvgPicture: View {
    let shown: FileModel.ShownImage
    let name: String
    @State private var zoom = 1.0
    @State private var web = SvgWeb()

    private var steps: [Double] { SvgView.shared.ZOOM_STEPS.map { $0.doubleValue } }

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Spacer()
                Button(say(.fileZoomOut), systemImage: "minus.magnifyingglass") { set(SvgView.shared.zoomOut(zoom: zoom)) }
                    .disabled(zoom <= steps.first!)
                Text("\(Int((zoom * 100).rounded())) %").monospacedDigit().accessibilityAddTraits(.updatesFrequently)
                Button(say(.fileZoomIn), systemImage: "plus.magnifyingglass") { set(SvgView.shared.zoomIn(zoom: zoom)) }
                    .disabled(zoom >= steps.last!)
                Button(say(.fileZoomFit), systemImage: "arrow.down.right.and.arrow.up.left") { set(1) }
                    .disabled(zoom == 1)
            }
            .labelStyle(.iconOnly)
            SvgWebView(web: web, picture: AppleDepot.shared.bytes(shown: shown).map { $0 as Data })
                .accessibilityLabel(name)
        }
    }

    private func set(_ next: Double) {
        zoom = next
        web.view?.evaluateJavaScript(SvgView.shared.zoomScript(zoom: next))
    }
}

/// The one web view of an SVG's page, held across SwiftUI's updates.
@MainActor final class SvgWeb {
    var view: WKWebView?
}

/// The scheme the page and its picture are served under: the app's own, answered from memory, never the network.
private let scheme = "veyq-svg"

/// Blocks every load but this view's scheme: a second wall behind the image mode and the page's policy.
private let rules = """
[{"trigger":{"url-filter":".*"},"action":{"type":"block"}},
 {"trigger":{"url-filter":"^veyq-svg:"},"action":{"type":"ignore-previous-rules"}}]
"""

/// The rule list compiled once (WebKit compiles it off the main thread).
@MainActor private var compiledRules: WKContentRuleList?

@MainActor private func configuration(_ handler: SvgScheme) -> WKWebViewConfiguration {
    let config = WKWebViewConfiguration()
    config.setURLSchemeHandler(handler, forURLScheme: scheme)
    config.websiteDataStore = .nonPersistent()
    config.defaultWebpagePreferences.allowsContentJavaScript = false
    config.preferences.javaScriptCanOpenWindowsAutomatically = false
    config.suppressesIncrementalRendering = false
    #if os(iOS)
    config.dataDetectorTypes = []
    config.allowsInlineMediaPlayback = false
    #endif
    return config
}

/// Loads the page once the rule list is in place: no load runs unblocked.
@MainActor private func load(_ view: WKWebView) {
    let start = {
        view.load(URLRequest(url: URL(string: "\(scheme)://svg/\(SvgView.shared.DOCUMENT_NAME)")!))
    }
    if let compiledRules {
        view.configuration.userContentController.add(compiledRules)
        start()
        return
    }
    WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "veyq-svg-block", encodedContentRuleList: rules) { list, error in
        MainActor.assumeIsolated {
            guard let list else {
                NSLog("veyq-file: the SVG view's rule list did not compile: \(String(describing: error))")
                return
            }
            compiledRules = list
            view.configuration.userContentController.add(list)
            start()
        }
    }
}

/// Serves the page and the picture from memory, nothing else.
final class SvgScheme: NSObject, WKURLSchemeHandler {
    private let picture: Data

    init(picture: Data) { self.picture = picture }

    func webView(_ webView: WKWebView, start task: any WKURLSchemeTask) {
        guard let url = task.request.url else { return task.didFailWithError(URLError(.badURL)) }
        let body: Data
        let type: String
        switch url.lastPathComponent {
        case SvgView.shared.DOCUMENT_NAME:
            body = Data(SvgView.shared.document().utf8)
            type = "text/html"
        case SvgView.shared.PICTURE_NAME:
            body = picture
            type = FileView.shared.SVG
        default:
            return task.didFailWithError(URLError(.resourceUnavailable))
        }
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: [
            "Content-Type": type, "Cache-Control": "no-store", "Content-Security-Policy": SvgView.shared.CONTENT_SECURITY_POLICY,
        ])!
        task.didReceive(response)
        task.didReceive(body)
        task.didFinish()
    }

    func webView(_ webView: WKWebView, stop task: any WKURLSchemeTask) {}
}

/// Allows the view's own page and nothing else: no link, no other navigation, no window.
final class SvgNavigation: NSObject, WKNavigationDelegate, WKUIDelegate {
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
        action.request.url?.scheme == scheme && action.request.url?.lastPathComponent == SvgView.shared.DOCUMENT_NAME
            && action.navigationType == .other && action.targetFrame?.isMainFrame == true ? .allow : .cancel
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
}

@MainActor private func makeView(web: SvgWeb, picture: Data, navigation: SvgNavigation) -> WKWebView {
    let view = WKWebView(frame: .zero, configuration: configuration(SvgScheme(picture: picture)))
    view.navigationDelegate = navigation
    view.uiDelegate = navigation
    view.allowsBackForwardNavigationGestures = false
    #if os(iOS)
    view.allowsLinkPreview = false
    view.isOpaque = false
    view.backgroundColor = .clear
    #else
    view.allowsMagnification = true
    view.allowsLinkPreview = false
    view.setValue(false, forKey: "drawsBackground")
    #endif
    web.view = view
    load(view)
    return view
}

#if os(iOS)
private struct SvgWebView: UIViewRepresentable {
    let web: SvgWeb
    let picture: Data?

    func makeCoordinator() -> SvgNavigation { SvgNavigation() }
    func makeUIView(context: UIViewRepresentableContext<SvgWebView>) -> WKWebView { makeView(web: web, picture: picture ?? Data(), navigation: context.coordinator) }
    func updateUIView(_ view: WKWebView, context: UIViewRepresentableContext<SvgWebView>) {}
    static func dismantleUIView(_ view: WKWebView, coordinator: SvgNavigation) { view.stopLoading() }
}
#else
private struct SvgWebView: NSViewRepresentable {
    let web: SvgWeb
    let picture: Data?

    func makeCoordinator() -> SvgNavigation { SvgNavigation() }
    func makeNSView(context: NSViewRepresentableContext<SvgWebView>) -> WKWebView { makeView(web: web, picture: picture ?? Data(), navigation: context.coordinator) }
    func updateNSView(_ view: WKWebView, context: NSViewRepresentableContext<SvgWebView>) {}
    static func dismantleNSView(_ view: WKWebView, coordinator: SvgNavigation) { view.stopLoading() }
}
#endif
