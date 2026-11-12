import AVKit
import UniformTypeIdentifiers
import VeyqCore

// A medium played as it is read (the web's range playback, ADR 0047), for every application: the system's player asks
// `ReaderLoader` for byte ranges, each served from the frames that hold it through core's `RangeReader`, so the plaintext
// is never held whole nor written anywhere (a Memory's video, a Depot file's audio or video).

/// A player over core's range reader: the asset's address is a scheme only `ReaderLoader` answers.
@MainActor
final class Playback {
    let player: AVPlayer
    private let loader: ReaderLoader

    init(reader: RangeReader, mime: String?) {
        loader = ReaderLoader(reader: reader, mime: mime)
        let asset = AVURLAsset(url: URL(string: "veyq-media://medium/original")!)
        // the loader is held here: the resource loader keeps its delegate weakly
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
    }
}

/// Answers the player's requests for the original: its size and type, then each byte range, read a piece at a time.
final class ReaderLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    let queue = DispatchQueue(label: "me.raddatz.veyq.media-loader")
    private let reader: RangeReader
    private let contentType: String
    /// The reads in flight by request, cancelled with it (touched on [queue] only).
    private var reads: [ObjectIdentifier: Task<Void, Never>] = [:]
    private static let piece: Int64 = 512 * 1024

    init(reader: RangeReader, mime: String?) {
        self.reader = reader
        contentType = UTType(mimeType: mime ?? "video/mp4")?.identifier ?? UTType.mpeg4Movie.identifier
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        if let info = loadingRequest.contentInformationRequest {
            info.contentType = contentType
            info.contentLength = reader.size
            info.isByteRangeAccessSupported = true
        }
        guard let data = loadingRequest.dataRequest else {
            loadingRequest.finishLoading()
            return true
        }
        let request = Request(loading: loadingRequest, data: data)
        let id = ObjectIdentifier(loadingRequest)
        let size = reader.size
        let end = data.requestsAllDataToEndOfResource ? size : min(size, data.requestedOffset + Int64(data.requestedLength))
        let reader = self.reader
        let queue = self.queue
        reads[id] = Task {
            var offset = data.currentOffset
            do {
                while offset < end, !Task.isCancelled {
                    let chunk = try await AppleMemories.shared.readRange(reader: reader, position: offset, length: Int32(min(end - offset, Self.piece)))
                    if chunk.isEmpty { break }
                    offset += Int64(chunk.count)
                    queue.async { request.data.respond(with: chunk) }
                }
                queue.async {
                    if !Task.isCancelled { request.loading.finishLoading() }
                    self.reads[id] = nil
                }
            } catch {
                queue.async {
                    request.loading.finishLoading(with: error)
                    self.reads[id] = nil
                }
            }
        }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        let id = ObjectIdentifier(loadingRequest)
        reads[id]?.cancel()
        reads[id] = nil
    }

    /// A request handed across to the read (the player answers it on [queue] alone).
    private struct Request: @unchecked Sendable {
        let loading: AVAssetResourceLoadingRequest
        let data: AVAssetResourceLoadingDataRequest
    }
}

