import SwiftUI
import VeyqCore

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
