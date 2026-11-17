import AVFoundation
import AVKit
import ImageIO
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import VeyqCore

// A Memory's curation on Apple (ADR 0144): its people line and their list, the participants picker, the banner above every
// view with its sheet and the question before its picture is trashed, a medium's capture-time editor. Every rule is the
// core's (`Participants`, `ParticipantsPicker`, `Banner`, `BannerFrame`, `BannerEditor`, `CaptureTimeEditor`); this file
// picks, decodes, plays and draws.

/// A core model's state mirrored on the main actor: read through its `current` whenever core's watch says it changed
/// (`AppleMemories.watch`, called on core's threads). Its owner stops it when the model goes.
@MainActor
@Observable
final class CoreState<Value> {
    private(set) var value: Value
    @ObservationIgnored private let read: () -> Value
    @ObservationIgnored private var watch: AppleMemories.Watch?

    /// `read` the model's `current`; `watch` starts core's watch of its state (`AppleMemories.watch(flow:)`), calling what
    /// it is handed on core's threads.
    init(_ read: @escaping () -> Value, watch: (@escaping @Sendable () -> Void) -> AppleMemories.Watch) {
        self.read = read
        value = read()
        self.watch = watch { @Sendable [weak self] in
            Task { @MainActor [weak self] in self?.refresh() }
        }
    }

    /// Read again now: after a call that changed the model synchronously (§ Traps: a field reads core right after its edit).
    func refresh() { value = read() }

    func stop() {
        watch?.stop()
        watch = nil
    }
}

// MARK: The people line (decision 1)

/// A participant's name as the line writes it: theirs, a former member's words, nothing while the members read.
private func entryName(_ e: Participants.Entry) -> String {
    e.name ?? (e.former ? say(.memoryParticipantsFormer) : "")
}

/// The header's people line (ADR 0144 decision 1; the web's `MemoryParticipants.vue`), under the facts line: one person as
/// avatar and name, several as up to three overlapping avatars with "+N" in the third slot; a tap lists everyone — a
/// popover on a Mac and an iPad, a sheet on an iPhone (`ParticipantsList`). Nobody listed: *Add participants* for who may
/// edit, nothing for anyone else. Shown only where the core's line is (a member's, on a Memory, not in a personal space,
/// online).
struct MemoryPeopleLine: View {
    let model: AppModel
    let gallery: GalleryModel
    let line: Participants.Line
    @State private var listing = false

    private static let avatar: CGFloat = 28
    private static let step: CGFloat = 20

    var body: some View {
        if line.add {
            Button { model.openParticipants(gallery) } label: { Label(say(.memoryParticipantsAdd), systemImage: "person.badge.plus") }
                .buttonStyle(.bordered)
                .controlSize(.small)
        } else if !line.people.isEmpty {
            HStack(spacing: 8) {
                Button { listing = true } label: {
                    HStack(spacing: 8) {
                        avatars
                        Text(line.people.count == 1 ? entryName(line.people[0]) : say(.memoryParticipantsTitle))
                            .font(.subheadline)
                            .foregroundStyle(Role.text)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(say(.memoryParticipantsTitle))
                .popover(isPresented: $listing) {
                    ParticipantsList(line: line, onOpen: { id in listing = false; model.openPerson(id) }, onEdit: {
                        listing = false
                        model.openParticipants(gallery)
                    })
                    .presentationCompactAdaptation(.sheet)
                    .presentationDetents([.medium, .large])
                }
                if line.edits {
                    Button { model.openParticipants(gallery) } label: { Image(systemName: "pencil") }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(say(.memoryParticipantsEdit))
                        .help(say(.memoryParticipantsEdit))
                }
                Spacer(minLength: 0)
            }
        }
    }

    /// "+N" in the third slot: a number, the same in every language.
    private static func more(_ n: Int32) -> String { "+" + n.formatted() }

    /// Up to three avatars overlapping, each ringed in the page's ground; "+N" in the third slot for more.
    private var avatars: some View {
        let slots = line.avatars.count + (line.more > 0 ? 1 : 0)
        return ZStack(alignment: .leading) {
            ForEach(Array(line.avatars.enumerated()), id: \.element.userId) { i, e in
                PersonAvatar(name: entryName(e), userId: e.former ? nil : e.userId, size: Self.avatar)
                    .padding(2)
                    .background(Role.bg, in: Circle())
                    .opacity(e.former ? 0.5 : 1)
                    .offset(x: CGFloat(i) * Self.step)
            }
            if line.more > 0 {
                Text(Self.more(line.more))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Role.controlText)
                    .frame(width: Self.avatar, height: Self.avatar)
                    .background(Role.accentSoft, in: Circle())
                    .padding(2)
                    .background(Role.bg, in: Circle())
                    .offset(x: CGFloat(line.avatars.count) * Self.step)
            }
        }
        .frame(width: Self.avatar + 4 + Self.step * CGFloat(max(0, slots - 1)), height: Self.avatar + 4, alignment: .leading)
        .accessibilityHidden(true)
    }
}

/// Everyone listed (avatar, name; a former member greyed), a current member's row opening their profile (decision 11).
private struct ParticipantsList: View {
    let line: Participants.Line
    let onOpen: (String) -> Void
    let onEdit: () -> Void

    var body: some View {
        NavigationStack {
            List(line.people, id: \.userId) { e in
                let name = entryName(e)
                if e.opensProfile {
                    Button { onOpen(e.userId) } label: { row(e, name) }
                        .buttonStyle(.plain)
                        .accessibilityHint(say(.peopleOpen, name))
                } else {
                    row(e, name)
                }
            }
            .navigationTitle(say(.memoryParticipantsTitle))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                if line.edits {
                    ToolbarItem(placement: .primaryAction) { Button(say(.memoryParticipantsEdit)) { onEdit() } }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 300, minHeight: 280)
        #endif
    }

    private func row(_ e: Participants.Entry, _ name: String) -> some View {
        HStack(spacing: 12) {
            PersonAvatar(name: name, userId: e.former ? nil : e.userId, size: 36).opacity(e.former ? 0.5 : 1)
            Text(name).foregroundStyle(e.former ? Role.muted : Role.text)
            Spacer(minLength: 0)
            if e.opensProfile { Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(Role.muted) }
        }
        .contentShape(Rectangle())
    }
}

/// The open gallery's participants picker (core's `ParticipantsPicker`), held by the app's model while its sheet shows.
@MainActor
final class ParticipantsEdit: Identifiable {
    let picker: ParticipantsPicker
    let gallery: GalleryModel
    let state: CoreState<ParticipantsPicker.State>

    init(picker: ParticipantsPicker, gallery: GalleryModel) {
        self.picker = picker
        self.gallery = gallery
        state = CoreState({ picker.current }) { changed in AppleMemories.shared.watch(flow: picker.state) { _ in changed() } }
        load()
    }

    /// The scope's members read (again: *Try again*).
    func load() {
        let picker = picker
        Task { try? await picker.load() }
    }

    func close() { state.stop() }
}

/// The participants picker (the web's `MemoryParticipantsDialog.vue`, core's `ParticipantsPicker`) as a sheet: the space's
/// current members with the system's search field and a checkmark each, a former member still listed above them until
/// unticked; the cap said and held; Save writes the pick through the core's merge and the sheet closes once it landed.
struct ParticipantsPickerSheet: View {
    let model: AppModel
    let edit: ParticipantsEdit
    @State private var query = ""

    private var s: ParticipantsPicker.State { edit.state.value }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(say(.memoryParticipantsDialogHint)).font(.footnote).foregroundStyle(Role.muted)
                }
                if s.failed {
                    Section {
                        Text(say(.memoryParticipantsLoadError)).foregroundStyle(Role.danger)
                        Button(say(.memoryPeopleRetry)) { edit.load() }
                    }
                } else if s.members == nil {
                    ProgressView().frame(maxWidth: .infinity)
                } else {
                    Section {
                        ForEach(s.former, id: \.self) { id in
                            pickRow(say(.memoryParticipantsFormer), userId: id, former: true)
                        }
                        ForEach(s.visible, id: \.userId) { p in
                            pickRow(p.displayName, userId: p.userId, former: false)
                        }
                        if s.nobody { Text(say(.memoryParticipantsNoMembers)).foregroundStyle(Role.muted) }
                        if s.noMatches { Text(say(.memoryParticipantsNoMatches)).foregroundStyle(Role.muted) }
                    } footer: {
                        VStack(alignment: .leading, spacing: 4) {
                            if s.full { Text(say(.memoryParticipantsFull, Int(Participants.shared.MAX))) }
                            if s.said != nil { Text(say(.memoryParticipantsSaveFailed)).foregroundStyle(Role.danger) }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: say(.memoryParticipantsSearch))
            .onChange(of: query) { _, q in
                edit.picker.search(query: q)
                edit.state.refresh()
            }
            .navigationTitle(say(.memoryParticipantsDialogTitle))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { model.closeParticipants() }.disabled(s.saving) }
                ToolbarItem(placement: .confirmationAction) {
                    if s.saving { ProgressView() } else {
                        Button(say(.entrySave)) {
                            let picker = edit.picker
                            Task {
                                _ = try? await picker.save()
                                if picker.current.saved { model.closeParticipants() }
                            }
                        }
                        .disabled(!s.canSave)
                    }
                }
            }
        }
        .interactiveDismissDisabled(s.saving)
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 480)
        #endif
    }

    /// One person: the whole row ticks and unticks, its checkmark trailing (the system's multiple choice in a list).
    private func pickRow(_ name: String, userId: String, former: Bool) -> some View {
        let ticked = s.ticked(userId: userId)
        let enabled = s.togglable(userId: userId) || ticked
        return Button {
            _ = edit.picker.toggle(userId: userId)
            edit.state.refresh()
        } label: {
            HStack(spacing: 12) {
                PersonAvatar(name: name, userId: former ? nil : userId, size: 32).opacity(former ? 0.5 : 1)
                Text(name).foregroundStyle(former ? Role.muted : Role.text)
                Spacer(minLength: 0)
                if ticked { Image(systemName: "checkmark").foregroundStyle(Role.accent).fontWeight(.semibold) }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled || s.saving)
        .accessibilityAddTraits(ticked ? .isSelected : [])
    }
}

// MARK: The banner (decisions 4 and 5)

/// A banner's picture decoded once, with its surround: the same picture at most 32 px on its long edge (core's
/// `BannerFrame.surround`), scaled down with smooth filtering — stretched back over the frame, it is the blur (the web's
/// 32 px trick, no blur API).
struct BannerPicture {
    let picture: CGImage
    let surround: CGImage

    static func of(_ data: Data?) -> BannerPicture? {
        guard let data, let image = CoverImages.image(data, maxPixels: 4096) else { return nil }
        let size = BannerFrame.shared.surround(width: Int32(image.width), height: Int32(image.height))
        let w = Int(size.get(index: 0)), h = Int(size.get(index: 1))
        guard let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let small = context.makeImage() else { return nil }
        return BannerPicture(picture: image, surround: small)
    }
}

/// A banner drawn as the web draws it (ADR 0144 decision 4): the picture whole, contained in the frame, and behind it its
/// surround stretched back with smooth filtering; `content` over it (a video's play button or its player).
struct BannerDrawn<Content: View>: View {
    let picture: BannerPicture
    var description: String?
    @ViewBuilder var content: () -> Content

    var body: some View {
        Role.surfaceMuted
            .overlay { Image(decorative: picture.surround, scale: 1).resizable().interpolation(.high).scaledToFill() }
            .overlay {
                Image(decorative: picture.picture, scale: 1).resizable().scaledToFit()
                    .accessibilityLabel(description ?? "")
                    .accessibilityHidden(description == nil)
            }
            .overlay { content() }
            .clipped()
    }
}

extension BannerDrawn where Content == EmptyView {
    init(picture: BannerPicture, description: String? = nil) {
        self.init(picture: picture, description: description) { EmptyView() }
    }
}

/// The banner above the Memory's header, on every view (decision 4, owner 2026-11-17), scrolling away with it: 16:9 on a
/// column wider than 720 pt, 1:1 at or below, never taller than 60 % of the page (core's `BannerFrame`). A video shows its
/// poster with a play button; a tap plays it through the system's player over the core's reader, with its controls, the
/// Memory's music interrupted meanwhile. Nothing at all where the banner does not read or decode.
struct MemoryBanner: View {
    let model: AppModel
    let gallery: GalleryModel
    let shown: Banner.Shown
    /// The page's height: the frame is never taller than 60 % of it.
    let viewport: CGFloat
    @State private var picture: BannerPicture?
    @State private var width: CGFloat = 0
    @State private var playing = false

    var body: some View {
        Group {
            if let picture {
                let frame = BannerFrame.shared.of(columnWidth: Double(width), viewportHeight: Double(viewport))
                let video = shown.medium == .video
                BannerDrawn(picture: picture, description: video ? say(.memoryBannerVideo) : nil) {
                    if video && playing {
                        BannerVideo(model: model, gallery: gallery, type: shown.type)
                    } else if video {
                        Button { playing = true } label: {
                            Image(systemName: "play.circle.fill").font(.system(size: 64)).foregroundStyle(.white).shadow(radius: 4)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(say(.memoryBannerPlay))
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: CGFloat(frame.height))
            }
        }
        .frame(maxWidth: .infinity)
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width = $0 }
        .task(id: shown.key) {
            playing = false
            let data = (try? await AppleMemories.shared.bannerPicture(gallery: gallery)).map { $0 as Data }
            picture = BannerPicture.of(data)
        }
    }
}

/// The banner's video played as it is read (`Playback` over core's `RangeReader`, never written anywhere), with the system's
/// controls; the Memory's music is interrupted while it plays, as a lightbox video does (ADR 0142 decision 13).
private struct BannerVideo: View {
    let model: AppModel
    let gallery: GalleryModel
    let type: String?
    @State private var reader: RangeReader?
    @State private var playback: Playback?
    @State private var failed = false
    @State private var interruption: VeyqCore.MusicPlayer.Interruption?

    var body: some View {
        Group {
            if let playback {
                VideoPlayer(player: playback.player)
            } else if failed {
                Text(say(.memoryBannerPlayFailed)).foregroundStyle(.white).padding()
            } else {
                ProgressView().tint(.white)
            }
        }
        .task {
            guard let r = try? await gallery.bannerReader() else { failed = true; return }
            reader = r
            let p = Playback(reader: r, mime: type)
            playback = p
            p.player.play()
            for await status in p.player.publisher(for: \.timeControlStatus).values {
                switch status {
                case .playing: if interruption == nil { interruption = model.interruptMusic() }
                case .paused:
                    interruption?.end()
                    interruption = nil
                default: break
                }
            }
        }
        .onDisappear {
            interruption?.end()
            interruption = nil
            playback?.player.pause()
            playback = nil
            reader?.close()
            reader = nil
        }
    }
}

/// A banner's pictures decoded on this device for the core (`BannerWrite.Pictures`, adapted by `AppleMemories.bannerPictures`
/// since Swift cannot implement a suspend function): an image upright as the cover's JPEG (`NodeCover`'s long edge, never
/// enlarged, its quality), a video's first frame turned as it is shown, at the same spec. Read inside the picked file's
/// security scope; nil where it does not decode here.
enum BannerImages {
    /// The decoder as core takes it, one per dialog; each answer handed back once, on any thread.
    static func pictures() -> any BannerWritePictures {
        AppleMemories.shared.bannerPictures(
            image: { url, done in
                nonisolated(unsafe) let done = done
                Task.detached { _ = done(image(url)) }
            },
            poster: { url, done in
                nonisolated(unsafe) let done = done
                Task.detached { _ = done(await poster(url)) }
            }
        )
    }

    private static var edge: Int { Int(NodeCover.shared.LONG_EDGE) }

    static func image(_ url: URL) -> Data? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int
        else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: min(edge, max(w, h)),
        ]
        guard let picture = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return jpeg(picture)
    }

    static func poster(_ url: URL) async -> Data? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: edge, height: edge)
        guard let frame = try? await generator.image(at: .zero).image else { return nil }
        return jpeg(frame)
    }

    private static func jpeg(_ image: CGImage) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: NodeCover.shared.QUALITY] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }
}

/// The open gallery's banner dialog (core's `BannerEditor`), held by the app's model while its sheet shows: the file picked
/// (a Photos copy removed once the dialog is done with it), the pick's decoding and the save's run, which Cancel stops —
/// the old banner then stays as it was.
@MainActor
@Observable
final class BannerEdit: Identifiable {
    let editor: BannerEditor
    let gallery: GalleryModel
    @ObservationIgnored let state: CoreState<BannerEditor.State>
    /// The picked file's decoded picture for the previews.
    private(set) var picked: BannerPicture?
    /// The banner as it is, for the previews until a file is picked.
    private(set) var now: BannerPicture?
    @ObservationIgnored private var run: AppleMemories.Watch?
    @ObservationIgnored private var picking: AppleMemories.Watch?
    /// The Photos picker's copy of the file picked last: removed when another is picked and when the dialog closes.
    @ObservationIgnored private var copy: URL?

    init(editor: BannerEditor, gallery: GalleryModel) {
        self.editor = editor
        self.gallery = gallery
        state = CoreState({ editor.current }) { changed in AppleMemories.shared.watch(flow: editor.state) { _ in changed() } }
        Task { [weak self] in
            let data = (try? await AppleMemories.shared.bannerPicture(gallery: gallery)).map { $0 as Data }
            self?.now = BannerPicture.of(data)
        }
    }

    /// `source` taken as the banner (core refuses or decodes it); `copy` the Photos picker's file it reads, if it is one.
    func pick(_ source: UploadSource, copy: URL?) {
        picking?.stop()
        if let old = self.copy { AppModel.removePicked(old) }
        self.copy = copy
        picked = nil
        picking = AppleMemories.shared.bannerPick(editor: editor, source: source) { @Sendable [weak self] in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.state.refresh()
                self.picked = self.editor.current.picked.map { BannerPicture.of(AppleMemories.shared.pickedPicture(picked: $0) as Data) } ?? nil
            }
        }
    }

    /// The picked file made the banner, or the banner `remove`d: one run at a time, stopped by `cancel`.
    func save(remove: Bool = false) {
        guard run == nil else { return }
        run = AppleMemories.shared.bannerSave(editor: editor, remove: remove) { @Sendable [weak self] _ in
            Task { @MainActor [weak self] in
                self?.run = nil
                self?.state.refresh()
            }
        }
    }

    func cancel() {
        run?.stop()
        run = nil
    }

    func close() {
        cancel()
        picking?.stop()
        picking = nil
        state.stop()
        if let copy { AppModel.removePicked(copy) }
        copy = nil
    }
}

/// The banner's dialog (the web's `BannerDialog.vue`, core's `BannerEditor`) as a sheet: what a banner is, *Choose an image
/// or a video* — the system's Photos picker on iOS, the file importer on a Mac, one file, images and videos —, the two
/// previews (wide 16:9, phone 1:1) drawn as the banner is, **Save banner** once a file is picked, its progress with Cancel,
/// and **Remove banner**.
struct BannerSheet: View {
    let model: AppModel
    let edit: BannerEdit
    @State private var choosing = false
    @State private var item: PhotosPickerItem?

    private var s: BannerEditor.State { edit.state.value }
    private var video: Bool { (s.picked?.medium ?? edit.gallery.current.banner?.medium) == .video }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(say(.memoryBannerHint)).font(.footnote).foregroundStyle(Role.muted)
                    Button { choosing = true } label: { Label(say(.memoryBannerChoose), systemImage: "photo.on.rectangle") }
                        .disabled(s.saving || s.picking)
                    if s.picking {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text(say(.memoryBannerPreparing)).font(.footnote)
                        }
                    }
                    if let said = s.said { Text(Self.text(said)).foregroundStyle(Role.danger) }
                }
                if let preview = edit.picked ?? edit.now {
                    Section(say(.memoryBannerPreviewWide)) {
                        BannerDrawn(picture: preview) { mark }
                            .aspectRatio(16 / 9, contentMode: .fit)
                            .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                            .listRowInsets(EdgeInsets())
                    }
                    Section(say(.memoryBannerPreviewPhone)) {
                        BannerDrawn(picture: preview) { mark }
                            .frame(width: 240, height: 240)
                            .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                            .frame(maxWidth: .infinity)
                    }
                }
                if s.saving {
                    Section {
                        if let progress = s.progress {
                            ProgressView(value: Double(truncating: progress)) {
                                Text(say(.memoryBannerUploading, Int(Double(truncating: progress) * 100)))
                            }
                        } else {
                            ProgressView()
                        }
                        Button(say(.signinCancel)) { edit.cancel() }
                    }
                }
                if s.hasBanner {
                    Section {
                        Button(say(.memoryBannerRemove), role: .destructive) { edit.save(remove: true) }.disabled(!s.canRemove)
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(s.hasBanner ? .memoryBannerChange : .memoryBannerAdd))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { model.closeBanner() }.disabled(s.saving) }
                ToolbarItem(placement: .confirmationAction) { Button(say(.memoryBannerSave)) { edit.save() }.disabled(!s.canSave) }
            }
        }
        .interactiveDismissDisabled(s.saving)
        .onChange(of: s.done) { _, done in if done { model.closeBanner() } }
        #if os(iOS)
        .photosPicker(isPresented: $choosing, selection: $item, matching: .any(of: [.images, .videos]), preferredItemEncoding: .current)
        .onChange(of: item) {
            guard let picked = item else { return }
            item = nil
            Task {
                guard let file = try? await picked.loadTransferable(type: PickedMedia.self) else { return }
                guard let source = model.pickedSource(file.url, fromPhotos: true) else { AppModel.removePicked(file.url); return }
                edit.pick(source, copy: file.url)
            }
        }
        #else
        .fileImporter(isPresented: $choosing, allowedContentTypes: [.image, .movie]) { result in
            guard case .success(let url) = result, let source = model.pickedSource(url, fromPhotos: false) else { return }
            edit.pick(source, copy: nil)
        }
        .frame(minWidth: 480, minHeight: 560)
        #endif
    }

    /// A video's mark on the previews: its play symbol.
    @ViewBuilder private var mark: some View {
        if video { Image(systemName: "play.circle.fill").font(.system(size: 44)).foregroundStyle(.white).shadow(radius: 4) }
    }

    private static func text(_ said: BannerEditor.Said) -> String {
        switch said {
        case .tooLarge: say(.memoryBannerTooLarge, ByteCountFormatter.string(fromByteCount: BannerWrite.shared.MAX_IMAGE_BYTES, countStyle: .file))
        case .unreadable, .notMedia: say(.memoryBannerUnreadable)
        case .videoUnreadable: say(.memoryBannerVideoUnreadable)
        case .stale: say(.memoryBannerStale)
        default: say(.memoryBannerFailed)
        }
    }
}

/// Trashing the medium the banner shows, asked first (A2; the web's `onDeleteBannerPicture`): the banner stays, or — the
/// second button — goes too; Cancel deletes nothing. Its dismissal does nothing: only Cancel cancels (§ Traps, a cancel
/// on dismissal runs before the button's action).
struct BannerPictureQuestion: ViewModifier {
    let gallery: GalleryModel
    let asked: Bool
    let failed: (String) -> Void

    func body(content: Content) -> some View {
        content.alert(say(.memoryBannerPictureTitle), isPresented: Binding(get: { asked }, set: { _ in })) {
            Button(say(.memoryTrash), role: .destructive) { answer(removeBanner: false) }
            Button(say(.memoryBannerPictureRemove), role: .destructive) { answer(removeBanner: true) }
            Button(say(.signinCancel), role: .cancel) { gallery.cancelAsk() }
        } message: {
            Text(say(.memoryBannerPictureNotice))
        }
    }

    private func answer(removeBanner: Bool) {
        let gallery = gallery
        let failed = failed
        Task {
            let said = try? await gallery.trashBannerPicture(removeBanner: removeBanner)
            // the trash's own notice is its Undo (the gallery's state); a banner that did not go is said
            if removeBanner, said?.msg == .memoryRefusedFailed { failed(say(.memoryBannerFailed)) }
        }
    }
}

// MARK: A medium's capture time (decision 3)

/// The capture-time editor (the web's lightbox editor, core's `CaptureTimeEditor`): the effective time's day and minute in
/// this device's time zone with the system's date and time picker — the picked moment is already the device calendar's
/// (`Date`), handed to the core as epoch ms, which keeps the minute. *Reset to original* where a correction is set. A peer's
/// correction meanwhile reopens the picker on theirs and says so.
struct CaptureTimeSheet: View {
    let editor: CaptureTimeEditor
    @State private var held: CoreState<CaptureTimeEditor.State>
    @State private var at: Date
    @Environment(\.dismiss) private var dismiss

    init(editor: CaptureTimeEditor) {
        self.editor = editor
        let held = CoreState({ editor.current }) { changed in AppleMemories.shared.watch(flow: editor.state) { _ in changed() } }
        _held = State(initialValue: held)
        _at = State(initialValue: Self.date(held.value.atMs))
    }

    private var s: CaptureTimeEditor.State { held.value }

    private static func date(_ ms: KotlinLong?) -> Date {
        ms.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1000) } ?? Date()
    }

    var body: some View {
        NavigationStack {
            Form {
                DatePicker(say(.memoryFieldCaptured), selection: $at, displayedComponents: [.date, .hourAndMinute])
                    .disabled(s.saving)
                if let said = s.said {
                    Text(say(said == .conflict ? .mediaCapturedAtConflict : said == .unreadable ? .mediaMetaUnreadable : .mediaCapturedAtSaveFailed))
                        .foregroundStyle(Role.danger)
                }
                if s.corrected {
                    Button(say(.mediaResetCapturedAt)) {
                        let editor = editor
                        Task { _ = try? await editor.reset() }
                    }
                    .disabled(s.saving)
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.mediaEditCapturedAt))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() }.disabled(s.saving) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.entrySave)) {
                        let editor = editor
                        let ms = Int64((at.timeIntervalSince1970 * 1000).rounded())
                        Task { _ = try? await editor.set(epochMs: ms) }
                    }
                    .disabled(s.saving)
                }
            }
        }
        .interactiveDismissDisabled(s.saving)
        // the editor reopened on a peer's time: the picker follows
        .onChange(of: s.atMs) { _, ms in at = Self.date(ms) }
        .onChange(of: s.saved) { _, saved in if saved { dismiss() } }
        .onDisappear { held.stop() }
        #if os(macOS)
        .frame(minWidth: 380, minHeight: 240)
        #endif
    }
}
