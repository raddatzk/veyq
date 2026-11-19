import SwiftUI
import UniformTypeIdentifiers
import VeyqCore

// A Memory's music on screen (ADR 0142 decisions 5, 6 and 9): the Music view beside the node's photos, the bar while a
// queue is loaded, and the lightbox's compact controls. What is shown and offered is core's (`MusicModel`, `NowPlaying`);
// these views draw it and decide nothing.

/// Which of a Memory's views shows: its photos and videos, its music, or its map with its tracks (ADR 0143 D8).
enum MemoryView: Hashable {
    case photos, music, map, contributions
}

/// The view switch inside a Memory's or a chapter's screen (ADR 0142 decision 6, ADR 0143 D8, ADR 0149 decision 6): a
/// segmented control, as the system's own apps switch a page between views of the same thing — Music where the page offers
/// it, Map always, Contributions for a member.
struct MemoryViewSwitch: View {
    @Binding var view: MemoryView
    var music = true
    var contributions = false
    /// The Map view: a member's (its tracks and places are the account's); a link's guest has none (ADR 0150 decision 5).
    var map = true

    var body: some View {
        Picker(say(.memoryViewMap), selection: $view) {
            Text(say(.musicViewPhotos)).tag(MemoryView.photos)
            if music { Text(say(.musicViewMusic)).tag(MemoryView.music) }
            if map { Text(say(.memoryViewMap)).tag(MemoryView.map) }
            if contributions { Text(say(.memoryBucketsTitle)).tag(MemoryView.contributions) }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
    }
}

/// A node's Music view (the web's `MemoryMusic.vue`, core's `MusicModel`): its tracks sorted by title with their length,
/// the one playing marked, one that did not play on this device said so; a tap plays it first, then the rest shuffled.
/// A track's actions — rename, download, remove, report — are swipe actions and a context menu on iOS, a context menu on a
/// Mac (HIG). "Add music" is the page's creating action.
struct MusicList<Switch: View>: View {
    let model: AppModel
    let music: MusicModel
    /// The banner above the header and the facts and people lines under it (ADR 0143 D5, ADR 0144 decisions 1 and 4).
    var banner: AnyView?
    var facts: AnyView?
    let viewSwitch: Switch
    @State private var renaming: MusicModel.Shown?
    @State private var newName = ""
    @State private var reporting: MusicModel.Shown?
    @State private var downloaded: URL?
    @State private var failed: String?

    private var state: MusicModel.State? { model.musicShown }

    var body: some View {
        List {
            if let banner { banner }
            NodeHeroRow()
            if let facts { facts.listRowSeparator(.hidden) }
            viewSwitch
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            if let tracks = state?.tracks {
                if tracks.isEmpty {
                    Text(say(.musicEmptyHint)).foregroundStyle(Role.muted)
                }
                ForEach(tracks, id: \.mediaId) { track in
                    row(track)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .alert(say(.musicRenameTitle), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } }), presenting: renaming) { track in
            TextField(say(.musicNameLabel), text: $newName)
            Button(say(.musicRename)) {
                let name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
                Task { _ = try? await music.rename(mediaId: track.mediaId, name: name.isEmpty ? nil : name) }
            }
            Button(say(.signinCancel), role: .cancel) {}
        } message: { _ in
            Text(say(.musicNameHint))
        }
        // what an add or a rename left to say (a refused file, a conflict), once
        .alert(state?.said.map { say(notice: $0) } ?? failed ?? "", isPresented: Binding(get: { state?.said != nil || failed != nil }, set: { if !$0 { music.seen(); failed = nil } })) {
            Button(say(.ok), role: .cancel) {}
        }
        .sheet(item: Binding(get: { reporting.map(ReportOf.init) }, set: { if $0 == nil { reporting = nil } })) { of in
            ReportSheet(model: model, ref: music.gallery.node, make: { music.reportSheet(mediaId: of.mediaId) })
        }
        #if os(iOS)
        .fileMover(isPresented: Binding(get: { downloaded != nil }, set: { if !$0 { cleanUpDownload() } }), file: downloaded) { _ in }
        #endif
    }

    private func title(_ track: MusicModel.Shown) -> String {
        track.title ?? say(.musicUntitled, Int(track.number))
    }

    private func row(_ track: MusicModel.Shown) -> some View {
        let name = title(track)
        let rights = state?.rights
        return Button {
            music.play(mediaId: track.mediaId)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: track.playing ? "speaker.wave.2.fill" : "music.note")
                    .foregroundStyle(track.playing ? Role.accent : Role.muted)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).foregroundStyle(track.blocked ? Role.muted : Role.text).fontWeight(track.playing ? .semibold : .regular)
                    if track.unplayable {
                        Text(say(.musicTrackUnplayable)).font(.caption).foregroundStyle(Role.muted)
                    } else if track.blocked {
                        Text(say(.memoryBlocked)).font(.caption).foregroundStyle(Role.muted)
                    }
                }
                Spacer(minLength: 0)
                if let length = track.length {
                    Text(length).font(.callout).monospacedDigit().foregroundStyle(Role.muted)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(track.blocked)
        .accessibilityLabel(say(.musicPlayFrom, name))
        #if os(iOS)
        .swipeActions(edge: .trailing) {
            if music.removes(mediaId: track.mediaId) {
                Button(role: .destructive) { remove(track) } label: { Label(say(.musicRemove), systemImage: "trash") }
            }
            if rights?.rename == true {
                Button { startRename(track) } label: { Label(say(.musicRename), systemImage: "pencil") }
            }
        }
        #endif
        .contextMenu {
            if rights?.rename == true {
                Button { startRename(track) } label: { Label(say(.musicRename), systemImage: "pencil") }
            }
            if rights?.download == true, !track.blocked {
                Button { Task { await download(track) } } label: { Label(say(.musicDownload), systemImage: "arrow.down.circle") }
            }
            if rights?.report == true {
                Button { reporting = track } label: { Label(say(.reportAction), systemImage: "flag") }
            }
            if music.removes(mediaId: track.mediaId) {
                Divider()
                Button(role: .destructive) { remove(track) } label: { Label(say(.musicRemove), systemImage: "trash") }
            }
        }
    }

    private func startRename(_ track: MusicModel.Shown) {
        newName = track.title ?? ""
        renaming = track
    }

    private func remove(_ track: MusicModel.Shown) {
        Task {
            if let said = try? await music.remove(mediaId: track.mediaId) { failed = say(notice: said) }
        }
    }

    /// The track's file saved under its own name: a Mac where its save panel says, iOS through the system's save sheet
    /// (written into the app's downloads directory first, as the lightbox's download is).
    private func download(_ track: MusicModel.Shown) async {
        let name = track.track.fileName ?? title(track)
        #if os(macOS)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = name
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let end = try? await AppleAudio.shared.save(music: music, mediaId: track.mediaId, path: url.path)
        if end != .saved { failed = say(.musicDownloadFailed) }
        #else
        guard let dir = AppModel.downloadDir() else { return }
        let url = dir.appendingPathComponent(name)
        let end = try? await AppleAudio.shared.save(music: music, mediaId: track.mediaId, path: url.path)
        if end == .saved {
            downloaded = url
        } else {
            try? FileManager.default.removeItem(at: dir)
            failed = say(.musicDownloadFailed)
        }
        #endif
    }

    private func cleanUpDownload() {
        if let url = downloaded { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        downloaded = nil
    }
}

/// A track's report sheet, by its medium's id.
private struct ReportOf: Identifiable {
    let mediaId: String
    var id: String { mediaId }
    init(_ track: MusicModel.Shown) { mediaId = track.mediaId }
}

/// The music bar (the web's `AudioBar.vue`, ADR 0142 decision 5) while a queue is loaded: the title and the node's name —
/// which opens the node —, previous, play or pause, next, the position over the track's span (a drag seeks where it is let
/// go), the elapsed and the whole time, and close, which stops the music. Drawn at the shell's bottom: on an iPhone above
/// the tab bar, as Music's own mini player; along the window's bottom on an iPad and a Mac. When none of a queue played,
/// it says so for 5 s.
struct MusicBar: View {
    let model: AppModel
    /// Where the person drags the position to, while dragging.
    @State private var dragging: Double?

    var body: some View {
        if let now = model.nowPlaying, let commands = model.musicCommands {
            bar(now, commands)
        } else if model.musicNothingPlays {
            Text(say(.musicNothingPlays))
                .font(.subheadline)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.bar)
        }
    }

    private func bar(_ now: NowPlaying, _ commands: any PlayerCommands) -> some View {
        let total = Double(now.durationMs)
        let at = dragging ?? Double(now.positionMs)
        return VStack(spacing: 4) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(now.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if let album = now.album {
                        Button { model.openMusicNode() } label: { Text(album).font(.caption).lineLimit(1) }
                            .buttonStyle(.plain)
                            .foregroundStyle(Role.accent)
                            .accessibilityLabel(say(.musicOpenNode, album))
                            .disabled(now.route == nil)
                    }
                }
                Spacer(minLength: 0)
                MusicButtons(now: now, commands: commands)
                Button { model.stopMusic() } label: { Image(systemName: "xmark") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(say(.musicClose))
                    .help(say(.musicClose))
            }
            HStack(spacing: 8) {
                Text(musicTime(Int64(at))).font(.caption2).monospacedDigit().foregroundStyle(Role.muted)
                Slider(value: Binding(get: { min(at, max(total, 1)) }, set: { dragging = $0 }), in: 0...max(total, 1)) { editing in
                    if !editing, let to = dragging {
                        commands.seek(positionMs: Int64(to))
                        dragging = nil
                    }
                }
                .controlSize(.mini)
                .disabled(total <= 0)
                .accessibilityLabel(say(.musicProgress, now.title))
                Text(musicTime(now.durationMs)).font(.caption2).monospacedDigit().foregroundStyle(Role.muted)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
    }
}

/// Previous, play or pause, next — the bar's and the lightbox's compact controls'.
struct MusicButtons: View {
    let now: NowPlaying
    let commands: any PlayerCommands

    var body: some View {
        HStack(spacing: 16) {
            Button { commands.previous() } label: { Image(systemName: "backward.fill") }
                .accessibilityLabel(say(.musicPrevious)).help(say(.musicPrevious))
            Button { if now.playing { commands.pause() } else { commands.resume() } } label: {
                Image(systemName: now.playing ? "pause.fill" : "play.fill").frame(width: 20)
            }
            .accessibilityLabel(say(now.playing ? .musicPause : .musicResume)).help(say(now.playing ? .musicPause : .musicResume))
            Button { commands.next() } label: { Image(systemName: "forward.fill") }
                .accessibilityLabel(say(.musicNext)).help(say(.musicNext))
        }
        .buttonStyle(.borderless)
    }
}

/// The lightbox's compact controls (the web's `AudioMiniControls.vue`): the title, previous, play or pause, next — while
/// the account's player holds a queue. Closing the lightbox stops nothing.
struct MusicCompactControls: View {
    let now: NowPlaying
    let commands: any PlayerCommands

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "music.note")
            Text(now.title).font(.subheadline).lineLimit(1)
            Spacer(minLength: 0)
            MusicButtons(now: now, commands: commands)
        }
        .foregroundStyle(.white)
        .tint(.white)
    }
}

/// The audio types a track is picked from (the system's picker filtered to audio, ADR 0142 decision 7).
let musicPickTypes: [UTType] = [.audio]
