import AVFoundation
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// A picked photo's or video's tiers on an Apple device (the web's `mediaDeriver.ts`, which core's `MediaUpload.Derived`
/// names): the `PREVIEW` JPEG from the original — upright (ImageIO applies the orientation), its long edge at most 2048,
/// never enlarged, quality 0.85 — and the `THUMBNAIL` JPEG made from that preview (512, 0.8); a video's poster, the frame
/// at `min(0.1 s, duration / 2)` turned as it is shown, at the preview's size and quality, as both tiers. All nil where the
/// original does not decode here: it then goes up alone.
enum MediaTiers {
    static let previewEdge = 2048
    static let previewQuality = 0.85
    static let thumbEdge = 512
    static let thumbQuality = 0.8

    struct Derived: Sendable {
        var preview: Data?
        var thumbnail: Data?
        var width: Int?
        var height: Int?
    }

    static func derive(_ url: URL, type: String) async -> Derived {
        if type.hasPrefix("video/") { return await video(url) }
        return image(url)
    }

    private static func image(_ url: URL) -> Derived {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int
        else { return Derived() }
        // orientations 5 to 8 turn the picture a quarter: its size as shown swaps
        let turned = ((props[kCGImagePropertyOrientation] as? Int) ?? 1) >= 5
        let (shownW, shownH) = turned ? (h, w) : (w, h)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: min(previewEdge, max(w, h)),
        ]
        guard let preview = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              let previewJpeg = jpeg(preview, quality: previewQuality) else { return Derived(width: shownW, height: shownH) }
        let thumbJpeg = CoverImages.jpeg(previewJpeg, maxPixels: min(thumbEdge, max(preview.width, preview.height)), quality: thumbQuality)
        guard let thumbJpeg else { return Derived(width: shownW, height: shownH) }
        return Derived(preview: previewJpeg, thumbnail: thumbJpeg, width: shownW, height: shownH)
    }

    private static func video(_ url: URL) async -> Derived {
        let asset = AVURLAsset(url: url)
        guard let duration = try? await asset.load(.duration),
              let track = try? await asset.loadTracks(withMediaType: .video).first,
              let (natural, transform) = try? await track.load(.naturalSize, .preferredTransform)
        else { return Derived() }
        let shown = natural.applying(transform)
        let w = Int(abs(shown.width).rounded())
        let h = Int(abs(shown.height).rounded())
        guard w > 0, h > 0 else { return Derived() }
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        let edge = Double(min(previewEdge, max(w, h)))
        generator.maximumSize = CGSize(width: edge, height: edge)
        let at = CMTime(seconds: min(0.1, duration.seconds / 2), preferredTimescale: 600)
        guard let frame = try? await generator.image(at: at).image, let poster = jpeg(frame, quality: previewQuality)
        else { return Derived(width: w, height: h) }
        return Derived(preview: poster, thumbnail: poster, width: w, height: h)
    }

    private static func jpeg(_ image: CGImage, quality: Double) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }
}
