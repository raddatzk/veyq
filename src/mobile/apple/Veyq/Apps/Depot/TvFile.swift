import AVKit
import SwiftUI
import VeyqCore

/// Depot on a TV (ADR 0159 decision 3): Home's tree is the shell's level; a file opens in the TV's viewers when it is a
/// picture, a video or audio, and any other stays listed, offers no download and says it opens on another device. The
/// file's rights on a TV hold no download, replace, copy or send (`FileRights` view-only); nothing here takes any away.
@MainActor
enum DepotTv {
    static func register(_ registry: TvRegistry.Type) {
        registry.kind(TvKindPage { ref, model in AnyView(TvFilePage(model: model, ref: ref)) }, DepotFiles.shared.FILE)
    }
}

/// The file's model while it shows (core's `FileModel`), its state on the main actor.
@MainActor
@Observable
private final class TvFileHeld {
    let file: FileModel
    private(set) var state: FileModel.State
    @ObservationIgnored private var watch: FileModel.Watch?

    init(shell: ShellModel, node: NodeAt) {
        file = shell.fileModel(ref: node, shares: false)
        state = file.current
        watch = file.watch { @Sendable [weak self] s in
            nonisolated(unsafe) let s = s
            Task { @MainActor [weak self] in self?.state = s }
        }
    }

    func close() {
        watch?.stop()
        file.close()
    }
}

private struct TvFilePage: View {
    @Bindable var model: TvModel
    let ref: ObjectRef
    @State private var held: TvFileHeld?
    @State private var playback: Playback?

    var body: some View {
        VStack(alignment: .leading, spacing: 30) {
            HStack(alignment: .firstTextBaseline, spacing: 30) {
                Text(ref.name).font(.title2)
                Spacer()
                // core's header plan of the node: on a TV the report alone, where the server takes one
                TvNodeHeader(model: model, node: ref.at)
            }
            .focusSection()
            if let held {
                let facts = pieceLine(held.state.facts)
                if !facts.isEmpty { Text(facts).foregroundStyle(.secondary) }
                shown(held.state.shown)
            } else {
                ProgressView()
            }
        }
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onAppear {
            guard held == nil, let shell = model.shellModel else { return }
            held = TvFileHeld(shell: shell, node: ref.at)
        }
        .onDisappear {
            stop()
            held?.close()
            held = nil
        }
    }

    /// The core's answer drawn whole: what a TV shows, and what it does not say so.
    @ViewBuilder private func shown(_ shown: FileModel.Shown) -> some View {
        switch shown {
        case let s as FileModel.ShownImage:
            if let data = AppleDepot.shared.bytes(shown: s), let image = CoverImages.image(data as Data, maxPixels: Int(s.edge)) {
                Image(decorative: image, scale: 1).resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                TvMessage(text: say(.tvOpensElsewhere))
            }
        case let s as FileModel.ShownMedia:
            if let playback {
                VideoPlayer(player: playback.player)
                    .onAppear {
                        model.playing("file", true)
                        playback.player.play()
                    }
            } else {
                Button { model.input(); playback = Playback(reader: s.reader, mime: s.mediaType) } label: {
                    Label(say(.memoryPlay), systemImage: "play.fill")
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        // a text, a PDF, a message (an archive, a format the core does not show): listed, no download — it opens on
        // another device (the decision log's reading for Android, mirrored)
        case is FileModel.ShownText, is FileModel.ShownPdf, is FileModel.ShownMessage:
            TvMessage(text: say(.tvOpensElsewhere))
        case is FileModel.ShownNotUploaded:
            TvMessage(text: say(.depotNotUploaded))
        case is FileModel.ShownFailed:
            TvMessage(text: say(.fileOpenFailed))
        default:
            VStack(spacing: 20) {
                ProgressView()
                Text(say(.fileOpening)).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func stop() {
        playback?.player.pause()
        playback = nil
        model.playing("file", false)
    }
}
