import AVKit
import SwiftUI
import VeyqCore

/// Memories on a TV (ADR 0159 decision 3): a Memory's or a chapter's page — its chapters, its gallery as tiles, the
/// lightbox, the slideshow with the Memory's music, a video in the system's player over the core's range reader. Never its
/// map, never its journal. What the page may do is the core's (`GalleryModel` on a TV: `MemoryRights` view-only, its
/// header plan the music alone); nothing here takes an entry away.
@MainActor
enum MemoriesTv {
    static func register(_ registry: TvRegistry.Type) {
        registry.kind(TvKindPage { ref, model in AnyView(TvMemoryPage(model: model, ref: ref)) }, "MEMORY", "CHAPTER")
    }
}

/// The page's model while it shows (core's `GalleryModel`), its state on the main actor, its live poll.
@MainActor
@Observable
private final class TvGallery {
    let gallery: GalleryModel
    private(set) var state: GalleryModel.State
    @ObservationIgnored private let uploads: MediaUploads
    @ObservationIgnored private var watch: GalleryModel.Watch?
    @ObservationIgnored private var live: GalleryModel.Watch?

    init(shell: ShellModel, node: NodeAt, music: PageMusic?) {
        // a gallery takes its uploads' door though a TV adds nothing (its rights allow no upload)
        uploads = shell.mediaUploads(queue: UploadQueue(), deriver: MediaDeriver.shared, left: { @Sendable _ in }, changed: { @Sendable in })
        gallery = shell.galleryModel(ref: node, uploads: uploads, music: music, calendar: GalleryFilter.CalendarCompanion.shared.local(language: shownLanguage))
        state = gallery.current
        watch = gallery.watch { @Sendable [weak self] s in
            nonisolated(unsafe) let s = s
            Task { @MainActor [weak self] in self?.state = s }
        }
        live = gallery.startLive()
    }

    func close() {
        live?.stop()
        watch?.stop()
        gallery.close()
        uploads.close()
    }
}

private struct TvMemoryPage: View {
    @Bindable var model: TvModel
    let ref: ObjectRef
    @State private var held: TvGallery?
    @State private var lightbox: TvLightboxHeld?
    @State private var tiles: [String: CGImage] = [:]
    @State private var chapterCovers: [String: CGImage] = [:]

    var body: some View {
        Group {
            if let held { page(held) } else { ProgressView() }
        }
        .onAppear {
            guard held == nil, let shell = model.shellModel else { return }
            held = TvGallery(shell: shell, node: ref.at, music: model.pageMusic)
        }
        .onDisappear {
            held?.close()
            held = nil
        }
        .fullScreenCover(item: $lightbox) { box in
            TvLightbox(model: model, held: box, gallery: held?.gallery) { lightbox = nil }
        }
    }

    @ViewBuilder private func page(_ held: TvGallery) -> some View {
        let state = held.state
        let gallery = held.gallery
        ScrollView {
            VStack(alignment: .leading, spacing: 44) {
                HStack(alignment: .firstTextBaseline, spacing: 30) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(ref.name).font(.title2)
                        Text(say(.memoryItems, state.count)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if let shown = state.shown, !shown.isEmpty {
                        Button { model.input(); openSlideshow(gallery, state) } label: {
                            Label(say(.memorySlideshowStart), systemImage: "play.rectangle")
                        }
                    }
                    // the page's own entries of core's header plan, whole; on a TV the music alone is in it
                    ForEach(gallery.headerActions, id: \.self) { a in header(a, gallery, state) }
                }
                .focusSection()
                if let failure = state.failure { Text(say(failure)).foregroundStyle(Role.danger) }
                if !state.chapters.isEmpty {
                    VStack(alignment: .leading, spacing: 20) {
                        Text(say(.memoryChapters)).font(.headline).foregroundStyle(.secondary)
                        ScrollView(.horizontal) {
                            LazyHStack(spacing: 48) {
                                ForEach(state.chapters, id: \.nodeId) { c in
                                    Button {
                                        model.input()
                                        if let o = GalleryModel.companion.rowPage(row: c) { model.open(o) }
                                    } label: {
                                        TvTile(name: c.displayName, line: nil, image: chapterCovers[c.nodeId], symbol: "photo.on.rectangle.angled")
                                    }
                                    .buttonStyle(.card)
                                    .task { await chapterCover(gallery, c.nodeId) }
                                }
                            }
                            .padding(.vertical, 30)
                        }
                        .scrollClipDisabled()
                    }
                    .focusSection()
                }
                if let rows = state.shown {
                    TvGrid {
                        ForEach(Array(rows.enumerated()), id: \.element.mediaId) { i, row in
                            Button { model.input(); open(TvLightboxHeld(gallery.lightbox(index: Int32(i)))) } label: {
                                TvMediaTile(row: row, image: tiles[row.mediaId])
                            }
                            .buttonStyle(.card)
                            .task { await tile(gallery, row) }
                        }
                    }
                } else if !state.failed {
                    ProgressView()
                }
            }
            .padding(.horizontal, 20)
        }
        .onChange(of: state.searchOpened) { _, opened in
            // a search found a photo of this page: its lightbox opens on it
            guard opened != nil, let box = gallery.openLightbox else { return }
            gallery.searchOpenedSeen()
            open(TvLightboxHeld(box))
        }
    }

    @ViewBuilder private func header(_ a: HeaderAction, _ gallery: GalleryModel, _ state: GalleryModel.State) -> some View {
        let label = gallery.label(id: a).map { say($0) } ?? a.name
        switch a.entry {
        case .music:
            Button { model.input(); gallery.toggleMusic() } label: {
                Label(label, systemImage: state.musicHere ? "stop.fill" : "music.note")
            }
        // authoring and taking away: never in a TV's plan (ADR 0159 decision 2); core's own entries are never a gallery's
        case .upload, .banner, .favorite, .share, .rename, .move, .theCopy, .cover, .download, .offline, .report, .delete_,
             .replace, .send, .openOwnPage, .reportShared:
            EmptyView()
        }
    }

    private func open(_ box: TvLightboxHeld) {
        lightbox?.close()
        lightbox = box
    }

    /// The slideshow from the first item, the Memory's music with it (a TV's slideshow is a showing, ADR 0159 decision 3).
    private func openSlideshow(_ gallery: GalleryModel, _ state: GalleryModel.State) {
        let box = TvLightboxHeld(gallery.lightbox(index: 0))
        if state.hasMusic, !state.musicHere, gallery.headerActions.contains(HeaderAction.music) { gallery.playMusic(startId: nil) }
        box.box.slideshow(on: true)
        open(box)
    }

    private func tile(_ gallery: GalleryModel, _ row: GalleryModel.Row) async {
        guard tiles[row.mediaId] == nil, row.tile != nil,
              let data = (try? await AppleMemories.shared.tile(gallery: gallery, mediaId: row.mediaId)) ?? nil,
              let image = CoverImages.image(data as Data, maxPixels: 640) else { return }
        tiles[row.mediaId] = image
    }

    private func chapterCover(_ gallery: GalleryModel, _ nodeId: String) async {
        guard chapterCovers[nodeId] == nil,
              let data = (try? await AppleMemories.shared.chapterCover(gallery: gallery, nodeId: nodeId)) ?? nil,
              let image = CoverImages.image(data as Data, maxPixels: 640) else { return }
        chapterCovers[nodeId] = image
    }
}

/// A gallery item's tile: its small picture, a video's mark, what the core says of a blocked one.
private struct TvMediaTile: View {
    let row: GalleryModel.Row
    let image: CGImage?

    var body: some View {
        ZStack {
            Role.surfaceMuted
            if let image {
                Image(decorative: image, scale: 1).resizable().scaledToFill()
            } else {
                Image(systemName: row.isVideo ? "play.rectangle" : "photo").font(.system(size: 44)).foregroundStyle(Role.muted)
            }
            if row.isVideo, image != nil {
                Image(systemName: "play.circle.fill").font(.system(size: 54)).foregroundStyle(.white).shadow(radius: 6)
            }
            if let said = row.said {
                Text(say(said)).font(.caption).padding(8).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: Radius.card))
            }
        }
        .frame(width: TvTile.width, height: TvTile.width * 9 / 16)
        .clipped()
    }
}

/// A lightbox while it shows (core's `Lightbox`): its state on the main actor.
@MainActor
@Observable
final class TvLightboxHeld: Identifiable {
    let box: Lightbox
    private(set) var state: Lightbox.State
    @ObservationIgnored private var watch: Lightbox.Watch?

    init(_ box: Lightbox) {
        self.box = box
        state = box.current
        watch = box.watch { @Sendable [weak self] s in
            nonisolated(unsafe) let s = s
            Task { @MainActor [weak self] in self?.state = s }
        }
    }

    func close() {
        watch?.stop()
        watch = nil
        box.close()
    }
}

/// The lightbox on a TV: one item at a time, left and right on the remote page through them, play/pause starts and stops
/// the slideshow (the core's clock moves it on), a video plays in the system's player. Back closes it.
private struct TvLightbox: View {
    @Bindable var model: TvModel
    let held: TvLightboxHeld
    let gallery: GalleryModel?
    let onClose: () -> Void
    @State private var picture: (id: String, image: CGImage)?
    @State private var video: Playback?

    var body: some View {
        let state = held.state
        ZStack {
            Color.black.ignoresSafeArea()
            if let row = state.row {
                if let p = picture, p.id == row.mediaId {
                    Image(decorative: p.image, scale: 1).resizable().scaledToFit().ignoresSafeArea()
                } else {
                    ProgressView()
                }
                if row.isVideo {
                    Button { model.input(); play(row) } label: { Label(say(.memoryPlay), systemImage: "play.fill") }
                }
                if let said = row.said { Text(say(said)).foregroundStyle(.white).padding().background(.ultraThinMaterial) }
            }
            VStack {
                Spacer()
                HStack(spacing: 30) {
                    Text("\(state.index + 1) / \(state.count)").foregroundStyle(.white.opacity(0.8))
                    Spacer()
                    Button { model.input(); toggleSlideshow(state) } label: {
                        Label(say(state.slideshow == .playing ? .memorySlideshowPause : .memorySlideshowStart),
                              systemImage: state.slideshow == .playing ? "pause.fill" : "play.rectangle")
                    }
                }
                .padding(50)
                .opacity(state.slideshow == .playing ? 0 : 1)
            }
        }
        .focusable()
        .onMoveCommand { d in
            model.input()
            switch d {
            case .left: go(state.index - 1, state)
            case .right: go(state.index + 1, state)
            default: break
            }
        }
        .onPlayPauseCommand { model.input(); toggleSlideshow(state) }
        .onExitCommand { close() }
        .task(id: state.row?.mediaId) { await load(state.row) }
        .onChange(of: state.slideshow == .playing, initial: true) { _, on in model.playing("slideshow", on) }
        .onChange(of: state.closed) { _, closed in if closed { close() } }
        .fullScreenCover(item: Binding(get: { video.map(TvVideoItem.init) }, set: { if $0 == nil { stopVideo() } })) { item in
            TvVideo(playback: item.playback, model: model, box: held.box) { stopVideo() }
        }
    }

    private func go(_ index: Int32, _ state: Lightbox.State) {
        guard index >= 0, index < state.count else { return }
        held.box.go(index: index)
    }

    private func toggleSlideshow(_ state: Lightbox.State) {
        let on = state.slideshow != .playing
        if on, let gallery, gallery.current.hasMusic, !gallery.current.musicHere, gallery.headerActions.contains(HeaderAction.music) {
            gallery.playMusic(startId: nil)
        }
        held.box.slideshow(on: on)
    }

    private func play(_ row: GalleryModel.Row) {
        // a TV's right to play (`MemoryRights.play`): reading a medium in ranges into the player is viewing
        guard let reader = held.box.play() else { return }
        video = Playback(reader: reader, mime: row.type)
    }

    private func stopVideo() {
        held.box.stop()
        video = nil
    }

    private func load(_ row: GalleryModel.Row?) async {
        guard let row, row.picture != nil || row.tile != nil else { return }
        let data = row.picture != nil
            ? (try? await AppleMemories.shared.picture(lightbox: held.box, mediaId: row.mediaId)) ?? nil
            : nil
        let bytes: Data? = data.map { $0 as Data }
        guard let bytes, let image = CoverImages.image(bytes, maxPixels: 3840) else { return }
        picture = (row.mediaId, image)
    }

    private func close() {
        model.playing("slideshow", false)
        held.close()
        onClose()
    }
}

private struct TvVideoItem: Identifiable {
    let playback: Playback
    var id: ObjectIdentifier { ObjectIdentifier(playback) }
}

/// A video in the system's player (AVPlayerViewController through `VideoPlayer`): its transport is tvOS's own; while it
/// plays the page's music pauses (core's `videoPlaying`) and the idle lock waits.
private struct TvVideo: View {
    let playback: Playback
    let model: TvModel
    let box: Lightbox
    let onEnd: () -> Void

    var body: some View {
        VideoPlayer(player: playback.player)
            .ignoresSafeArea()
            .onAppear {
                box.videoPlaying(playing: true)
                model.playing("video", true)
                playback.player.play()
            }
            .onDisappear {
                playback.player.pause()
                box.videoPlaying(playing: false)
                model.playing("video", false)
            }
            .onExitCommand(perform: onEnd)
    }
}
