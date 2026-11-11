import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// A cover's pictures on an Apple device, through ImageIO on iOS and macOS alike: the JPEG a cover is uploaded as (the web's
/// `COVER_SPEC`, core's `NodeCover.LONG_EDGE` and `QUALITY`, turned upright as the picture's orientation says), and a list's
/// small picture of one.
enum CoverImages {
    /// The picture `data` at most `maxPixels` on its long edge, upright; nil when it is no picture ImageIO decodes.
    static func image(_ data: Data, maxPixels: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// The picture `data` as a JPEG at most `maxPixels` on its long edge, at `quality`; nil when it is no picture.
    static func jpeg(_ data: Data, maxPixels: Int, quality: Double) -> Data? {
        guard let image = image(data, maxPixels: maxPixels) else { return nil }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }
}
