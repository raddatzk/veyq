import AVFoundation
import MediaPlayer
import VeyqCore

// A Memory's music on Apple (ADR 0142 decisions 2 and 3): the core's player drives two decks — each an `AVPlayer` over
// `ReaderLoader`, so a track plays as it is read and its plaintext is never written — and lends the system's controls:
// Now Playing and the remote commands (the lock screen, Control Center, headphones, a Mac's media keys); on iOS the
// `.playback` audio session, so the music plays on in the background and with the screen off (Info.plist's
// `UIBackgroundModes` `audio`). Everything the player decides is the core's: a deck plays the bytes it is handed and ramps
// its volume when asked.

/// One deck: an `AVPlayer` over a track's reader (core's `AppleDeck`, made a `Deck` by `AppleAudio.deck`). Every call runs
/// on the deck's own queue; the core calls from its player's lane.
final class AVDeck: NSObject, AppleDeck, @unchecked Sendable {
    private let events: any DeckEvents
    private let queue = DispatchQueue(label: "app.veyq.music-deck")
    private var player: AVPlayer?
    private var loader: ReaderLoader?
    private var status: NSKeyValueObservation?
    private var observers: [NSObjectProtocol] = []
    private var ramping: DispatchSourceTimer?
    /// The volume last asked for, kept for the next track this deck loads.
    private var volume: Float = 1
    /// Bumped by every load and clear: a late answer of a track gone is dropped.
    private var generation = 0

    init(events: any DeckEvents) {
        self.events = events
    }

    /// The core's deck over a new `AVDeck` reporting to `events`.
    static func make(_ events: any DeckEvents) -> any Deck {
        AppleAudio.shared.deck(apple: AVDeck(events: events))
    }

    func load(reader: RangeReader, mime: String, done: @escaping (KotlinLong, String?) -> Void) {
        queue.async { [self] in
            drop()
            generation += 1
            let gen = generation
            let loader = ReaderLoader(reader: reader, mime: mime)
            let asset = AVURLAsset(url: URL(string: "veyq-media://track/original")!)
            asset.resourceLoader.setDelegate(loader, queue: loader.queue)
            let item = AVPlayerItem(asset: asset)
            let player = AVPlayer(playerItem: item)
            player.volume = volume
            // a deck plays only when the core says so: no stall wait that would start it late on its own
            player.automaticallyWaitsToMinimizeStalling = false
            #if os(macOS)
            player.preventsDisplaySleepDuringVideoPlayback = false
            #endif
            self.loader = loader
            self.player = player
            var answered = false
            status = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
                self?.queue.async {
                    guard let self, gen == self.generation, !answered else { return }
                    switch item.status {
                    case .readyToPlay:
                        answered = true
                        let seconds = item.duration.seconds
                        done(KotlinLong(value: seconds.isFinite ? Int64(seconds * 1000) : 0), nil)
                    case .failed:
                        answered = true
                        done(KotlinLong(value: 0), item.error?.localizedDescription ?? "failed")
                    default: break
                    }
                }
            }
            let center = NotificationCenter.default
            observers = [
                center.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: nil) { [weak self] _ in
                    self?.queue.async { if gen == self?.generation { self?.events.ended() } }
                },
                center.addObserver(forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: item, queue: nil) { [weak self] _ in
                    self?.queue.async { if gen == self?.generation { self?.events.failed() } }
                },
            ]
        }
    }

    func play(done: @escaping (String?) -> Void) {
        queue.async { [self] in
            guard let player, player.currentItem?.status == .readyToPlay else { return done("not_loaded") }
            player.play()
            done(nil)
        }
    }

    func pause() {
        queue.async { [self] in player?.pause() }
    }

    func seek(positionMs: Int64) {
        queue.async { [self] in
            player?.seek(to: CMTime(value: positionMs, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
        }
    }

    func positionMs() -> Int64 {
        queue.sync {
            guard let t = player?.currentTime().seconds, t.isFinite else { return 0 }
            return Int64(t * 1000)
        }
    }

    func ramp(to: Double, overMs: Int64) {
        queue.async { [self] in
            ramping?.cancel()
            ramping = nil
            let target = Float(max(0, min(1, to)))
            guard overMs > 0, let player else {
                volume = target
                player?.volume = target
                return
            }
            // stepped every 25 ms (ADR 0142 decision 2: steps of at most 50 ms), linearly from where it stands
            let from = player.volume
            let steps = max(1, Int(overMs / 25))
            var step = 0
            let timer = DispatchSource.makeTimerSource(queue: queue)
            timer.schedule(deadline: .now() + .milliseconds(25), repeating: .milliseconds(25))
            timer.setEventHandler { [weak self, weak player] in
                step += 1
                let v = from + (target - from) * Float(step) / Float(steps)
                player?.volume = v
                self?.volume = v
                if step >= steps {
                    self?.ramping?.cancel()
                    self?.ramping = nil
                }
            }
            ramping = timer
            timer.resume()
        }
    }

    func clear() {
        queue.async { [self] in
            generation += 1
            drop()
        }
    }

    /// The player and its loader let go (on the queue).
    private func drop() {
        ramping?.cancel()
        ramping = nil
        status?.invalidate()
        status = nil
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        loader = nil
    }
}

/// The system's media controls for the core's player (core's `SystemControls`): Now Playing and the remote commands, on
/// iOS with the `.playback` audio session — active while a queue is bound, given back at its stop — whose interruptions
/// (a call, an alarm) pause the music and resume it where the system says so, and whose lost route (headphones out)
/// pauses it.
final class SystemMusicControls: NSObject, SystemControls, @unchecked Sendable {
    private var commands: (any PlayerCommands)?
    private var targets: [(MPRemoteCommand, Any)] = []
    private var sessionObservers: [NSObjectProtocol] = []
    /// Whether the music played when an interruption began (it resumes only then).
    private var playedBeforeInterruption = false
    private var playing = false

    func bind(commands: any PlayerCommands) {
        nonisolated(unsafe) let commands = commands
        DispatchQueue.main.async { [self] in
            unbind()
            self.commands = commands
            let center = MPRemoteCommandCenter.shared()
            let add: (MPRemoteCommand, @escaping (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus) -> Void = { command, handler in
                command.isEnabled = true
                self.targets.append((command, command.addTarget(handler: handler)))
            }
            add(center.playCommand) { _ in commands.resume(); return .success }
            add(center.pauseCommand) { _ in commands.pause(); return .success }
            add(center.togglePlayPauseCommand) { [weak self] _ in
                if self?.playing == true { commands.pause() } else { commands.resume() }
                return .success
            }
            add(center.nextTrackCommand) { _ in commands.next(); return .success }
            add(center.previousTrackCommand) { _ in commands.previous(); return .success }
            add(center.changePlaybackPositionCommand) { event in
                guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
                commands.seek(positionMs: Int64(e.positionTime * 1000))
                return .success
            }
            #if os(iOS)
            let session = AVAudioSession.sharedInstance()
            try? session.setCategory(.playback, mode: .default)
            try? session.setActive(true)
            let nc = NotificationCenter.default
            sessionObservers = [
                nc.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] note in
                    self?.interrupted(note)
                },
                nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { [weak self] note in
                    let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
                    if reason == .oldDeviceUnavailable { self?.commands?.pause() }
                },
            ]
            #endif
        }
    }

    #if os(iOS)
    private func interrupted(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            playedBeforeInterruption = playing
            commands?.pause()
        case .ended:
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map(AVAudioSession.InterruptionOptions.init) ?? []
            if playedBeforeInterruption, options.contains(.shouldResume) {
                try? AVAudioSession.sharedInstance().setActive(true)
                commands?.resume()
            }
            playedBeforeInterruption = false
        @unknown default: break
        }
    }
    #endif

    func show(now: NowPlaying) {
        nonisolated(unsafe) let now = now
        DispatchQueue.main.async { [self] in
            playing = now.playing
            var info: [String: Any] = [
                MPMediaItemPropertyTitle: now.title,
                MPMediaItemPropertyPlaybackDuration: Double(now.durationMs) / 1000,
                MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(now.positionMs) / 1000,
                MPNowPlayingInfoPropertyPlaybackRate: now.playing ? 1.0 : 0.0,
                MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            ]
            if let album = now.album { info[MPMediaItemPropertyAlbumTitle] = album }
            let center = MPNowPlayingInfoCenter.default()
            center.nowPlayingInfo = info
            #if os(macOS)
            center.playbackState = now.playing ? .playing : .paused
            #endif
        }
    }

    func clear() {
        DispatchQueue.main.async { [self] in
            unbind()
            playing = false
            let center = MPNowPlayingInfoCenter.default()
            center.nowPlayingInfo = nil
            #if os(macOS)
            center.playbackState = .stopped
            #endif
            #if os(iOS)
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            #endif
        }
    }

    private func unbind() {
        for (command, target) in targets { command.removeTarget(target) }
        targets = []
        sessionObservers.forEach(NotificationCenter.default.removeObserver)
        sessionObservers = []
        commands = nil
    }
}

/// A picked audio file's length as the system's player reads it (core's `MusicUpload.Measure`): `AVURLAsset`'s duration,
/// nil where it cannot read it — the file is then refused as one this device cannot play.
final class AssetMeasure: NSObject, MusicUploadMeasure, @unchecked Sendable {
    func durationMs(source: UploadSource, type: String, done: @escaping (KotlinLong?) -> Void) {
        guard let url = AppleMemories.shared.urlOf(source: source).map({ $0 as URL }) else { return done(nil) }
        nonisolated(unsafe) let done = done
        Task {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            done(await Self.durationMs(of: AVURLAsset(url: url)).map { KotlinLong(value: $0) })
        }
    }

    /// `asset`'s length in ms where it is playable and says one.
    static func durationMs(of asset: AVURLAsset) async -> Int64? {
        guard let (duration, playable) = try? await asset.load(.duration, .isPlayable), playable, duration.seconds.isFinite, duration.seconds > 0 else { return nil }
        return Int64(duration.seconds * 1000)
    }

    /// The first and the last `limitMs` of a picked track's audio, decoded by `AVAudioFile` at a frame position — never the
    /// whole file — and handed to the core as WAVs for its silence scan (inbox #932); nil where it does not decode, and the
    /// track is sealed untrimmed.
    func ends(source: UploadSource, type: String, durationMs: Int64, limitMs: Int64, done: @escaping (KotlinPair<KotlinByteArray, KotlinByteArray>?) -> Void) {
        guard let url = AppleMemories.shared.urlOf(source: source).map({ $0 as URL }) else { return done(nil) }
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

/// `ms` as the bar writes a time: `m:ss`.
func musicTime(_ ms: Int64) -> String {
    let s = max(0, ms) / 1000
    return String(format: "%d:%02d", s / 60, s % 60)
}
