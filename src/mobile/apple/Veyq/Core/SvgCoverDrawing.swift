import CoreGraphics
import Foundation
import VeyqCore
#if os(iOS)
import SwiftUI
import UIKit
import WebKit
#else
import AppKit
#endif

/// A cover drawn from an SVG (core's `SvgCover`, uc.platform.node-cover main flow 3; the web's `rasterizeSvg`): the core
/// checks its markup and reads its own size, the device draws it once at the size the core names, and the core stores one
/// WebP of the drawing. A Mac draws it with AppKit's own SVG support (`NSImage`); an iPhone and an iPad, which decode no SVG
/// in ImageIO, through the core's one page in a `WKWebView` (the SVG as an `<img>` of a `data:` URL, script off, a policy
/// that loads only `data:` images), snapshot at that size on a transparent background.
enum SvgCoverDrawing {
    /// A view of the sheet a pick is made in, which an iPhone's web view is drawn beside (a Mac needs none).
    final class Anchor {
        #if os(iOS)
        weak var view: UIView?
        #endif
    }

    #if os(iOS)
    /// The sheet's anchor as a view in its hierarchy: invisible, it only names the window.
    struct Probe: UIViewRepresentable {
        let anchor: Anchor
        func makeUIView(context: Self.Context) -> UIView {
            let v = UIView()
            v.isUserInteractionEnabled = false
            v.accessibilityElementsHidden = true
            anchor.view = v
            return v
        }
        func updateUIView(_ uiView: UIView, context: Self.Context) { anchor.view = uiView }
    }
    #endif

    /// A drawing: the picture the frame shows and its straight RGBA, what the core encodes.
    struct Drawn {
        let image: CGImage
        let rgba: Data
    }

    /// `data` drawn as a cover where it is an SVG the core takes; nil otherwise (no SVG, refused, no size of its own).
    /// `anchor` is a view of the sheet the pick was made in (an iPhone's web view draws only in a window).
    @MainActor
    static func draw(_ data: Data, anchor: Anchor) async -> Drawn? {
        let bytes = AppleKit.shared.bytes(data: data)
        guard Sniff.shared.svg(b: bytes), let plan = SvgCover.shared.plan(bytes: bytes) else { return nil }
        let w = Int(plan.width), h = Int(plan.height)
        #if os(iOS)
        guard let window = anchor.view?.window,
              let picture = await snapshot(SvgCover.shared.document(bytes: bytes, plan: plan), in: window, width: w, height: h) else { return nil }
        return rendered(w, h) { ctx in ctx.draw(picture, in: CGRect(x: 0, y: 0, width: w, height: h)) }
        #else
        guard let ns = NSImage(data: data) else { return nil }
        return rendered(w, h) { ctx in
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
            ns.draw(in: NSRect(x: 0, y: 0, width: w, height: h))
            NSGraphicsContext.restoreGraphicsState()
        }
        #endif
    }

    /// A transparent `w` × `h` RGBA context drawn by `paint`: its picture, and its pixels with the alpha divided out.
    private static func rendered(_ w: Int, _ h: Int, paint: (CGContext) -> Void) -> Drawn? {
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        ctx.clear(CGRect(x: 0, y: 0, width: w, height: h))
        paint(ctx)
        guard let image = ctx.makeImage(), let base = ctx.data else { return nil }
        let px = base.bindMemory(to: UInt8.self, capacity: w * h * 4)
        var rgba = Data(count: w * h * 4)
        rgba.withUnsafeMutableBytes { (out: UnsafeMutableRawBufferPointer) in
            for i in stride(from: 0, to: w * h * 4, by: 4) {
                let a = UInt32(px[i + 3])
                for c in 0..<3 {
                    out[i + c] = a == 0 ? 0 : UInt8(min(255, (UInt32(px[i + c]) * 255 + a / 2) / a))
                }
                out[i + 3] = px[i + 3]
            }
        }
        return Drawn(image: image, rgba: rgba)
    }

    #if os(iOS)
    /// `page` laid out `width` points wide behind `window`'s content and snapshot `width` pixels wide.
    @MainActor
    private static func snapshot(_ page: String, in window: UIWindow, width: Int, height: Int) async -> CGImage? {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = false
        config.websiteDataStore = .nonPersistent()
        let web = WKWebView(frame: CGRect(x: 0, y: 0, width: width, height: height), configuration: config)
        web.isOpaque = false
        web.backgroundColor = .clear
        web.scrollView.backgroundColor = .clear
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.isUserInteractionEnabled = false
        web.alpha = 0.011
        web.accessibilityElementsHidden = true
        window.insertSubview(web, at: 0)
        defer { web.removeFromSuperview() }
        let loaded = Loaded()
        web.navigationDelegate = loaded
        web.loadHTMLString(page, baseURL: nil)
        guard await loaded.wait() else { return nil }
        let shot = WKSnapshotConfiguration()
        shot.rect = CGRect(x: 0, y: 0, width: width, height: height)
        shot.snapshotWidth = NSNumber(value: Double(width) / Double(window.screen.scale))
        shot.afterScreenUpdates = true
        return (try? await web.takeSnapshot(configuration: shot))?.cgImage
    }

    /// Hears the page's load: done, failed, or a navigation of its own refused.
    private final class Loaded: NSObject, WKNavigationDelegate {
        private var done: CheckedContinuation<Bool, Never>?
        private var result: Bool?

        @MainActor
        func wait() async -> Bool {
            if let result { return result }
            return await withCheckedContinuation { done = $0 }
        }

        private func finish(_ ok: Bool) {
            guard result == nil else { return }
            result = ok
            done?.resume(returning: ok)
            done = nil
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { finish(true) }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { finish(false) }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { finish(false) }
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            decisionHandler(action.navigationType == .other && action.request.url?.absoluteString == "about:blank" ? .allow : .cancel)
        }
    }
    #endif
}
