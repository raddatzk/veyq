import CoreGraphics
import Foundation
import ImageIO
import PDFKit
import VeyqCore

/// An Emergency Kit file the person picked (`.fileImporter`: iCloud Drive and Files on iOS, the open panel on macOS),
/// read for the sign-in without a copy on disk (ADR 0047, en.account.key-model § The Emergency Kit): the security-scoped
/// URL read in place under an `NSFileCoordinator` (which brings an iCloud file down into the person's own iCloud
/// container, not the app's) into memory — never a document picker's `asCopy`, which copies into the app's `tmp/Inbox`.
/// A file larger than the core's cap is not read: its bytes are empty, which the core says as a kit it could not read.
final class KitBytes: @unchecked Sendable {
    let data: NSMutableData

    init(_ data: NSMutableData) { self.data = data }

    /// The bytes as `Data` over the same memory (no copy), for core, PDFKit and ImageIO.
    var view: Data { Data(bytesNoCopy: data.mutableBytes, count: data.length, deallocator: .none) }

    /// The bytes zeroed where they were read; called once the core's read ended.
    func clear() {
        if data.length > 0 { memset(data.mutableBytes, 0, data.length) }
    }

    static func read(_ url: URL) -> KitBytes {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var out = NSMutableData()
        var failure: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &failure) { at in
            let size = (try? at.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            guard size > 0, size <= Int(KitImport.shared.FILE_CAP), let buffer = NSMutableData(length: size) else { return }
            // read straight into the one buffer core copies from and this side zeroes: no intermediate `Data`
            let fd = Darwin.open(at.path, O_RDONLY)
            guard fd >= 0 else { return }
            defer { Darwin.close(fd) }
            var n = 0
            while n < size {
                let r = Darwin.read(fd, buffer.mutableBytes + n, size - n)
                if r <= 0 { break }
                n += r
            }
            buffer.length = n
            out = buffer
        }
        return KitBytes(out)
    }
}

/// What the core's kit read asks this platform to draw (core's `KitDrawing`): a PDF's pages through PDFKit and a picture
/// through ImageIO, each drawn into an 8-bit grey `CGContext` over a buffer the core copies and zeroes. Called on core's
/// threads, one call at a time, never the main one; the document is opened from the bytes in memory.
final class KitDrawer: NSObject, KitDrawing, @unchecked Sendable {
    private let bytes: KitBytes
    private var document: PDFDocument?
    private var opened = false

    init(_ bytes: KitBytes) { self.bytes = bytes }

    private func open() -> PDFDocument? {
        if !opened {
            opened = true
            document = bytes.data.length > 0 ? PDFDocument(data: bytes.view) : nil
        }
        return document
    }

    /// The document let go; the bytes are the caller's to clear.
    func close() { document = nil }

    func pdfPages() -> Int32 { Int32(open()?.pageCount ?? 0) }

    func pdfPage(page: Int32, width: Int32) -> KitDrawn? {
        guard let doc = open(), page >= 1, let p = doc.page(at: Int(page) - 1) else { return nil }
        let box = p.bounds(for: .cropBox)
        let turned = p.rotation % 180 != 0
        let pw = turned ? box.height : box.width
        let ph = turned ? box.width : box.height
        guard pw > 0, ph > 0, width > 0 else { return nil }
        let w = Int(width)
        let h = max(1, min(4 * w, Int((CGFloat(w) * ph / pw).rounded())))
        return Self.grey(w, h) { ctx in
            ctx.scaleBy(x: CGFloat(w) / pw, y: CGFloat(h) / ph)
            p.draw(with: .cropBox, to: ctx)
        }
    }

    func picture(longSide: Int32) -> KitDrawn? {
        guard bytes.data.length > 0, longSide > 0, let source = CGImageSourceCreateWithData(bytes.view as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(longSide),
            kCGImageSourceShouldCache: false,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        // ImageIO only scales down: a smaller picture is drawn up to the long side the core asked for
        let scale = CGFloat(longSide) / CGFloat(max(image.width, image.height))
        let w = max(1, Int((CGFloat(image.width) * scale).rounded()))
        let h = max(1, Int((CGFloat(image.height) * scale).rounded()))
        return Self.grey(w, h) { ctx in
            ctx.interpolationQuality = .high
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
    }

    /// A white `w`×`h` grey canvas `draw` paints on; its memory's first row is the picture's top.
    private static func grey(_ w: Int, _ h: Int, _ draw: (CGContext) -> Void) -> KitDrawn? {
        guard let buffer = NSMutableData(length: w * h),
              let ctx = CGContext(
                  data: buffer.mutableBytes, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                  space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
              )
        else { return nil }
        ctx.setFillColor(gray: 1, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        draw(ctx)
        return KitDrawn(luminance: buffer, width: Int32(w), height: Int32(h), rowStride: Int32(w))
    }
}
