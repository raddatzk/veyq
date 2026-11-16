import AVFoundation
import Foundation
import VeyqCore
import os

/// Debug builds only — the music's format matrix (ADR 0142 decision 9), reached without a sign-in: started with
/// `VEYQ_DEV_AUDIO_MATRIX=<dir>` in its environment, the app runs every file of that directory through the path a track
/// plays by — its bytes behind a core `RangeReader` cut into a sealed blob's frames, typed by `Sniff.stream` as an upload
/// types it, loaded and played 1.5 s by an `AVDeck` — and reads its length as the upload's `AssetMeasure` does. One line
/// per file to the unified log (`veyq-audio:`), then `veyq-audio: done`. A release build compiles none of it.
enum DevAudio {
    static var requested: String? {
        #if DEBUG
        ProcessInfo.processInfo.environment["VEYQ_DEV_AUDIO_MATRIX"]
        #else
        nil
        #endif
    }

    #if DEBUG
    private static let log = Logger(subsystem: "me.raddatz.veyq", category: "audio")

    private final class Events: NSObject, DeckEvents, @unchecked Sendable {
        var didEnd = false
        var didFail = false
        func ended() { didEnd = true }
        func failed() { didFail = true }
    }

    static func run(_ dir: String) async {
        let files = ((try? FileManager.default.contentsOfDirectory(atPath: dir)) ?? []).sorted()
        for name in files where !name.hasPrefix(".") {
            let url = URL(fileURLWithPath: dir).appendingPathComponent(name)
            guard let data = try? Data(contentsOf: url) else { continue }
                        let mime = AppleAudio.shared.mime(data: data) ?? "none"
            let measured = await AssetMeasure.durationMs(of: AVURLAsset(url: url))
            let events = Events()
            let apple = AVDeck(events: events)
            let deck = AppleAudio.shared.deck(apple: apple)
            var line = "\(name) mime=\(mime) measure=\(measured.map { "\($0)" } ?? "nil")"
            do {
                let ms = try await deck.load(reader: AppleAudio.shared.reader(data: data), mime: mime)
                line += " load=\(ms.int64Value)"
                try await deck.play()
                try? await Task.sleep(for: .milliseconds(1500))
                let at = try await deck.positionMs()
                line += " played=\(at.int64Value) failed=\(events.didFail)"
            } catch {
                line += " load_error=\(error.localizedDescription)"
            }
            deck.clear()
            log.notice("veyq-audio: \(line, privacy: .public)")
            NSLog("veyq-audio: %@", line)
        }
        log.notice("veyq-audio: done")
        NSLog("veyq-audio: done")
    }
    #endif
}
