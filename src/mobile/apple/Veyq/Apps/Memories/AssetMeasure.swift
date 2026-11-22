import AVFoundation
import VeyqCore

/// A picked audio file's length as the system's player reads it (core's `MusicUpload.Measure`): `AVURLAsset`'s duration,
/// nil where it cannot read it — the file is then refused as one this device cannot play.
final class AssetMeasure: NSObject, MusicUploadMeasure, @unchecked Sendable {
    func durationMs(source: UploadSource, type: String, done: @escaping (KotlinLong?) -> Void) {
        guard let url = ApplePlatform.shared.urlOf(source: source).map({ $0 as URL }) else { return done(nil) }
        nonisolated(unsafe) let done = done
        Task {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            done(await AssetLength.ms(of: AVURLAsset(url: url)).map { KotlinLong(value: $0) })
        }
    }


    /// The first and the last `limitMs` of a picked track's audio, decoded by `AVAudioFile` at a frame position — never the
    /// whole file — and handed to the core as WAVs for its silence scan (inbox #932); nil where it does not decode, and the
    /// track is sealed untrimmed.
    func ends(source: UploadSource, type: String, durationMs: Int64, limitMs: Int64, done: @escaping (KotlinPair<KotlinByteArray, KotlinByteArray>?) -> Void) {
        guard let url = ApplePlatform.shared.urlOf(source: source).map({ $0 as URL }) else { return done(nil) }
        nonisolated(unsafe) let done = done
        Task.detached {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            done(Self.ends(of: url, limitMs: limitMs))
        }
    }

    /// `url`'s two ends as interleaved 32-bit float PCM, wrapped by the core as WAVs.
    static func ends(of url: URL, limitMs: Int64) -> KotlinPair<KotlinByteArray, KotlinByteArray>? {
        guard let file = try? AVAudioFile(forReading: url) else { return nil }
        let format = file.processingFormat
        let channels = Int(format.channelCount)
        guard channels > 0, format.commonFormat == .pcmFormatFloat32, file.length > 0 else { return nil }
        let want = AVAudioFrameCount(min(Double(file.length), (format.sampleRate * Double(limitMs) / 1000).rounded(.up)))
        func read(at position: AVAudioFramePosition) -> Data? {
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: want) else { return nil }
            file.framePosition = position
            do { try file.read(into: buffer, frameCount: want) } catch { return nil }
            guard let planes = buffer.floatChannelData else { return nil }
            let frames = Int(buffer.frameLength)
            var interleaved = [Float](repeating: 0, count: frames * channels)
            for c in 0..<channels {
                let plane = planes[c]
                for f in 0..<frames { interleaved[f * channels + c] = plane[f * buffer.stride] }
            }
            return interleaved.withUnsafeBufferPointer { Data(buffer: $0) }
        }
        guard let head = read(at: 0), let tail = read(at: max(0, file.length - AVAudioFramePosition(want))) else { return nil }
        let rate = Int32(format.sampleRate.rounded())
        return KotlinPair(
            first: AppleAudio.shared.floatWav(pcm: head, channels: Int32(channels), sampleRate: rate),
            second: AppleAudio.shared.floatWav(pcm: tail, channels: Int32(channels), sampleRate: rate),
        )
    }
}
