import AVFoundation
import Foundation
import MediaPlayer
import VeyqCore
import os

/// Debug builds only — the music's format matrix (ADR 0142 decision 9), reached without a sign-in: started with
/// `VEYQ_DEV_AUDIO_MATRIX=<dir>` in its environment, the app runs every file of that directory through the path a track
/// plays by — its bytes behind a core `RangeReader` cut into a sealed blob's frames, typed by `Sniff.stream` as an upload
/// types it, loaded and played 1.5 s by an `AVDeck` — and reads its length as the upload's measure does (`AssetLength`). One line
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
    private static let log = Logger(subsystem: "app.veyq", category: "audio")

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
            let measured = await AssetLength.ms(of: AVURLAsset(url: url))
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
        if ProcessInfo.processInfo.environment["VEYQ_DEV_AUDIO_PLAY"] == "1" { await play(dir) }
        log.notice("veyq-audio: done")
        NSLog("veyq-audio: done")
    }

    private static func report(_ line: String) {
        log.notice("veyq-audio: \(line, privacy: .public)")
        NSLog("veyq-audio: %@", line)
    }

    /// With `VEYQ_DEV_AUDIO_PLAY=1`: the core's player over two `AVDeck`s and the system's controls, a queue of the
    /// directory's MP3, M4A and FLAC — played, next, a seek, a lightbox video's interruption and its end, the crossfade into
    /// the next track by itself, stop — each step's `NowPlaying` and what Now Playing shows logged.
    private static func play(_ dir: String) async {
        let names = ["t-mp3.mp3", "t-aac.m4a", "t-flac.flac"]
        let tracks: [PlayerTrack] = names.compactMap { name in
            let url = URL(fileURLWithPath: dir).appendingPathComponent(name)
            guard let data = try? Data(contentsOf: url), let mime = AppleAudio.shared.mime(data: data) else { return nil }
            return AppleAudio.shared.localTrack(id: name, title: name, data: data, mime: mime)
        }
        let player = AppleAudio.shared.localPlayer(newDeck: { AVDeck.make($0) }, controls: SystemMusicControls())
        func step(_ what: String, wait ms: Int) async {
            try? await Task.sleep(for: .milliseconds(ms))
            let now = player.current.nowPlaying
            let shown = await MainActor.run {
                MPNowPlayingInfoCenter.default().nowPlayingInfo.map { "\($0[MPMediaItemPropertyTitle] ?? "-")@\($0[MPNowPlayingInfoPropertyElapsedPlaybackTime] ?? "-") rate=\($0[MPNowPlayingInfoPropertyPlaybackRate] ?? "-") album=\($0[MPMediaItemPropertyAlbumTitle] ?? "-")" } ?? "none"
            }
            #if os(macOS)
            let state = await MainActor.run { MPNowPlayingInfoCenter.default().playbackState.rawValue }
            #else
            let state = -1
            #endif
            report("\(what): now=\(now.map { "\($0.title) playing=\($0.playing) pos=\($0.positionMs)/\($0.durationMs)" } ?? "nil") nowPlayingInfo=\(shown) playbackState=\(state)")
        }
        player.play(queue: PlayerQueue(key: "dev", tracks: tracks, album: "Dev Memory", route: nil, startId: names[0]))
        await step("play", wait: 3000)
        player.next()
        await step("next", wait: 2500)
        player.seek(positionMs: 3000)
        await step("seek 3000", wait: 600)
        let interruption = player.interrupt()
        await step("video plays (interrupt)", wait: 1000)
        await step("video plays +1s", wait: 1000)
        interruption.end()
        await step("video ended (end)", wait: 800)
        // 12 s files: the fade starts 5 s before the end, the next track comes in on its own
        await step("by itself +4s", wait: 4000)
        await step("by itself +4s", wait: 4000)
        player.pause()
        await step("pause", wait: 800)
        player.resume()
        await step("resume", wait: 800)
        player.stop()
        await step("stop", wait: 800)
    }
    #endif
}
