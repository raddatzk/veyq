import CoreGraphics
import Foundation
import Observation
import VeyqCore

extension AppModel {
    /// What Memories holds in the app's model: the gallery on screen, its views and dialogs, the tenant's media uploads.
    var memories: MemoriesHolding { held(MemoriesHolding.self) }
}

/// What Memories holds in the app's model for the open tenant (ADR 0154 decision 3, inbox #1066): the Memory's or chapter's
/// page on screen (core's `GalleryModel`, one at a time) and the lightbox windows' galleries, its Map and Music views, the
/// curation's dialogs, and the tenant's media uploads (core's `MediaUploads`, the tiers this device derives, `MediaDeriver`).
/// Each is closed where the tenant's models close.
@MainActor
@Observable
final class MemoriesHolding: Holding {
    @ObservationIgnored private unowned let model: AppModel

    init(_ model: AppModel) { self.model = model }

    // MARK: The moments of the tenant's life (core asks them)

    func leave() {
        closeGallery(windows: true)
        galleryShown = nil
    }

    /// Memories' uploads for shell `m`: a new tenant's hand the old one's picks back (its queue was emptied), the same
    /// tenant opened again carries them on.
    func shown(_ shell: ShellModel, _ tenant: TenantSession, switched: Bool) {
        let made = shell.mediaUploads(queue: model.uploads, deriver: MediaDeriver.shared, left: { @Sendable source in
            if let url = ApplePlatform.shared.urlOf(source: source) { AppModel.removePicked(url) }
        }) { @Sendable in
            Task { @MainActor [weak self] in
                guard let self, let t = self.model.tenantSession else { return }
                await self.model.refreshLists(t)
            }
        }
        if let old = media {
            if switched { old.close() } else { old.handOver(next: made) }
        }
        media = made
    }

    func locked() { closeCuration() }

    func signedOut() {
        closeGallery(windows: true)
        galleryShown = nil
        media?.close()
        media = nil
    }

    /// A Memory's row shows its date as its second line (ADR 0143 D5): the facts its sealed meta holds.
    func rowLines(_ shell: ShellModel) async -> [String: String] {
        guard let dates = try? await shell.memoryDates() else { return [:] }
        var lines: [String: String] = [:]
        for (nodeId, date) in dates { if let text = MemoryDateText.row(date) { lines[nodeId] = text } }
        return lines
    }

    var deriver: DerivationDeriver? { MediaDeriver.shared }

    // MARK: The share's way in (ADR 0138)

    /// The Memory's or chapter's page on screen as *Add here*'s place, where it is the shell's open object.
    func addHereTarget(_ shell: ShellModel, inbox: ShareInbox, at: Shell) -> AddHere? {
        guard let shown = galleryShown, let g = openGallery?.model, g.node == shown.node, at.openObject?.nodeId == shown.node.nodeId else { return nil }
        return shell.addHere(inbox: inbox, shell: at, gallery: g)
    }

    /// *Add here* on a gallery: its photos and videos queued into its node — in the share extension handed to the app
    /// through the app group (ADR 0138 decision 6) —, what it left out said.
    func addHere(_ target: AddHere, label: String) -> Bool {
        guard let g = target as? AddHere.Gallery else { return false }
        let inbox = model.inbox
        if model.inExtension {
            guard g.gallery.takesUploads else { return true }
            let node = g.gallery.node
            let taken = inbox.queue(answer: target.answer) { sources in
                KotlinInt(int: Int32(ShareHandoff.write(sources, kind: node.kind, scopeId: node.scopeId, nodeId: node.nodeId, label: node.name)))
            }
            model.shareTaken(taken, notice: taken.flatMap { MediaUploads.companion.notTaken(count: $0.refused) })
            return true
        }
        let taken = target.take(inbox: inbox, label: g.gallery.node.name, depot: nil) { source in
            if let url = ApplePlatform.shared.urlOf(source: source) { AppModel.removePicked(url) }
        }
        model.shareTaken(taken, notice: taken.flatMap { MediaUploads.companion.notTaken(count: $0.refused) })
        return true
    }

    /// Files dropped onto a Memory's or chapter's gallery: its photos and videos uploaded there (core's one drop).
    func drop(_ shell: ShellModel, _ target: DropTarget, _ items: [Dropped]) async -> DropOutcome? {
        guard target is DropTarget.Gallery else { return nil }
        return try? await shell.drop(target: target, items: items, depot: nil, left: { @Sendable source in
            if let url = ApplePlatform.shared.urlOf(source: source) { AppModel.removePicked(url) }
        }, deletedName: say(.potDeletedPerson))
    }

    /// A share's files the extension handed over for a Memory or a chapter: queued into its node.
    func pickUp(_ target: ShareHandoff.Target, _ sources: [UploadSource]) -> Bool {
        guard !target.isLevel, let nodeId = target.nodeId, let uploads = media else { return false }
        _ = uploads.upload(scopeId: target.scopeId, nodeId: nodeId, label: target.label, sources: sources)
        return true
    }

    // MARK: A link's gallery (ADR 0150 decision 5)

    func adopt(_ page: GuestPage) -> Bool {
        guard let g = page as? MemoriesGuest.Gallery else { return false }
        if openGallery?.model !== g.model {
            closeGallery()
            galleryShown = nil
            holdGallery(g.model)
        }
        return true
    }

    func release(_ page: GuestPage) -> Bool {
        guard let g = page as? MemoriesGuest.Gallery else { return false }
        if openGallery?.model === g.model {
            closeGallery()
            galleryShown = nil
        } else {
            g.model.close()
        }
        return true
    }

    // MARK: The gallery (core's GalleryModel, Lightbox and MediaUploads)

    /// The Memory or chapter page on screen (core's `GalleryModel`, one at a time) and its watch; read anew as the core says.
    @ObservationIgnored private var openGallery: (model: GalleryModel, watch: GalleryModel.Watch)?

    /// What the Memory or chapter page on screen shows (`GalleryModel.State`) and of which node, on the main actor.
    private var galleryShown: (node: NodeAt, state: GalleryModel.State)?

    /// What `node`'s gallery shows, once its model delivered it; nil before, and for any other node.
    func galleryState(_ node: NodeAt) -> GalleryModel.State? {
        guard let shown = galleryShown, shown.node == node else { return nil }
        return shown.state
    }

    /// The page of Memory or chapter `node` (core's `GalleryModel` over the shell, its uploads through `media`). Called while a
    /// view is drawn, so it changes nothing observed: the state arrives through the watch.
    func galleryModel(_ node: NodeAt) -> GalleryModel? {
        if let held = openGallery, held.model.node == node { return held.model }
        guard let m = model.shellModel, let uploads = media else { return nil }
        closeGallery()
        let made = m.galleryModel(ref: node, uploads: uploads, music: model.galleryMusic, calendar: Self.galleryCalendar)
        holdGallery(made)
        return made
    }

    /// `made` the gallery on screen, its state followed on the main actor (the shell's page, or a link's guest's).
    private func holdGallery(_ made: GalleryModel) {
        let id = ObjectIdentifier(made)
        let watch = made.watch { @Sendable state in
            nonisolated(unsafe) let state = state
            Task { @MainActor [weak self] in
                // a state of a page closed meanwhile is dropped
                guard let self, let open = self.openGallery?.model, ObjectIdentifier(open) == id else { return }
                self.galleryShown = (open.node, state)
                // the search opened one of this page's photos (ADR 0146 decision 8): the page shows again, its lightbox on it
                if state.searchOpened != nil, self.model.shell.searching { self.model.search(false) }
            }
        }
        openGallery = (made, watch)
    }

    /// `node`'s page's live poll (core's `GalleryModel.live`, run by core) for as long as the calling task runs — the page
    /// while the app is active, so nothing is asked in the background; its cancellation stops the poll.
    func liveGallery(_ node: NodeAt) async {
        guard let live = galleryModel(node)?.startLive() else { return }
        await untilCancelled()
        live.stop()
    }

    /// The gallery goes (another opened, the tenant closed): its lightbox and a video's reader close with it. The tenant's
    /// close passes `windows`: every lightbox window's gallery goes too.
    private func closeGallery(windows: Bool = false) {
        closeCuration()
        closeMusicView()
        closeTrackList()
        openGallery?.watch.stop()
        openGallery?.model.close()
        openGallery = nil
        if windows {
            windowGalleries.values.forEach { $0.close() }
            windowGalleries = [:]
        }
    }

    /// A lightbox in a window of its own (a Mac's "Open in New Window"): its own gallery and the lightbox on it.
    struct WindowLightbox {
        let gallery: GalleryModel
        let box: VeyqCore.Lightbox
    }

    /// The galleries the lightbox windows opened, by identity: closed with their window, and all with the tenant.
    private var windowGalleries: [ObjectIdentifier: GalleryModel] = [:]

    /// A lightbox window's models for `value` (its ids): a gallery of its own over the shell — not the main window's page,
    /// so each goes without the other — and core's lightbox on the medium named (`lightboxOn`: before the gallery's first
    /// read it waits for it). Nil where the node is no longer a listed Memory or chapter, or no tenant is open.
    func windowLightbox(_ value: LightboxWindowValue) -> WindowLightbox? {
        guard let m = model.shellModel, let uploads = media, let node = m.galleryAt(scopeId: value.scopeId, nodeId: value.nodeId) else { return nil }
        let gallery = m.galleryModel(ref: node, uploads: uploads, music: model.galleryMusic, calendar: Self.galleryCalendar)
        windowGalleries[ObjectIdentifier(gallery)] = gallery
        return WindowLightbox(gallery: gallery, box: gallery.lightboxOn(mediaId: value.mediaId))
    }

    /// Whether lightbox window gallery `gallery` is still open (the tenant's close closed it otherwise).
    func holdsWindowGallery(_ gallery: GalleryModel) -> Bool { windowGalleries[ObjectIdentifier(gallery)] != nil }

    /// A lightbox window went: its gallery closes, its lightbox and a video's reader with it.
    func closeWindowGallery(_ gallery: GalleryModel) {
        gallery.close()
        windowGalleries[ObjectIdentifier(gallery)] = nil
    }

    /// Decoded tiers by blob id (a gallery asks for every tile as it scrolls past; the cache lets the system evict).
    @ObservationIgnored private let mediaPictures = NSCache<NSString, CGImageBox>()

    /// Blob `blobId`'s bytes as `read` hands them, decoded as a picture and kept; nil where it does not read or is no picture.
    func mediaPicture(_ blobId: String, read: () async -> Data?) async -> CGImage? {
        if let hit = mediaPictures.object(forKey: blobId as NSString) { return hit.image }
        guard let data = await read(), let image = CoverImages.image(data, maxPixels: 4096) else { return nil }
        mediaPictures.setObject(CGImageBox(image), forKey: blobId as NSString)
        return image
    }

    /// Memories' uploads of the open tenant (core's `MediaUploads` over the app's queue): a Photos pick's copy is removed
    /// once the queue is done with it, and all of them when the tenant closes.
    @ObservationIgnored private var media: MediaUploads?

    /// What a Memory's header asked its page to add (its `upload` entry: the photo library, or files), until the page opens
    /// the picker.
    var galleryAdd: GalleryAdd?

    // MARK: A Memory's curation (ADR 0144)

    /// The open gallery's participants picker while its sheet shows (core's `ParticipantsPicker`).
    var participantsEdit: ParticipantsEdit?
    /// The open gallery's banner dialog while its sheet shows (core's `BannerEditor`).
    var bannerEdit: BannerEdit?

    /// `gallery`'s participants picker opened over the space's current members, where its rights offer it.
    func openParticipants(_ gallery: GalleryModel) {
        guard let picker = gallery.participantsPicker() else { return }
        participantsEdit?.close()
        participantsEdit = ParticipantsEdit(picker: picker, gallery: gallery)
    }

    func closeParticipants() {
        participantsEdit?.close()
        participantsEdit = nil
    }

    /// `gallery`'s banner dialog opened, where whoever looks may set its banner, online.
    func openBanner(_ gallery: GalleryModel) {
        guard openGallery?.model === gallery, let editor = gallery.bannerEditor(pictures: BannerImages.pictures()) else { return }
        bannerEdit?.close()
        bannerEdit = BannerEdit(editor: editor, gallery: gallery)
    }

    /// The banner's dialog closed: a running save stopped (the old banner stays), a Photos copy removed.
    func closeBanner() {
        bannerEdit?.close()
        bannerEdit = nil
    }

    /// The open gallery's dialogs closed with it (another gallery, a switch, a lock, a sign-out).
    private func closeCuration() {
        closeParticipants()
        closeBanner()
    }

    /// The picked file at `url` as an upload's source (`fromPhotos`: a copy the Photos picker made, whose date is no capture
    /// time); nil where it does not read.
    func pickedSource(_ url: URL, fromPhotos: Bool) -> UploadSource? {
        guard let p = AppModel.Picked(url) else { return nil }
        return ApplePlatform.shared.source(url: url, name: p.name, size: p.size, type: p.type,
                                           modifiedAt: fromPhotos ? nil : p.modifiedAt.map { KotlinLong(value: $0) })
    }

    /// A picked photo or video as a guest uploader's source; a copy the Photos picker made is removed if it does not read.
    func guestMediaSource(_ url: URL, fromPhotos: Bool) -> UploadSource? {
        guard let p = AppModel.Picked(url) else {
            if fromPhotos { AppModel.removePicked(url) }
            return nil
        }
        return ApplePlatform.shared.source(url: url, name: p.name, size: p.size, type: p.type, modifiedAt: fromPhotos ? nil : p.modifiedAt.map { KotlinLong(value: $0) })
    }

    // MARK: The gallery's filter and the contributions (ADR 0149 decisions 5 and 6)

    /// The viewer's calendar for a gallery filter's day chips (inbox #989): an instant's day in this device's time zone
    /// (core's `LocalDays`) and its chip's words as the web writes them (`Shown.dayLabel`, ADR 0154 decision 5).
    static let galleryCalendar = GalleryFilter.CalendarCompanion.shared.local(language: shownLanguage)

    /// The Contributions view of the page `gallery` (core's `Contributions`): made when the view is asked for, its state
    /// watched by the view.
    func contributions(_ gallery: GalleryModel) -> Contributions? {
        model.shellModel?.contributions(gallery: gallery)
    }

    // MARK: Places and maps (ADR 0143)

    /// The Memories bar's Map tab over the application's list (its All tab's set, core's `MemoriesMap`).
    func memoriesMap() async -> MemoriesMap? {
        guard let m = model.shellModel else { return nil }
        let refs = memoriesListRefs()
        return try? await m.memoriesMap(memories: refs)
    }

    /// The Memories of the application's list as the tree holds them (the Map tab's set is the All tab's, ADR 0143 D6).
    private func memoriesListRefs() -> [NodeRef] {
        let ids = Set(model.applicationList(.memories).all.map(\.nodeId))
        return model.nodes.filter { ids.contains($0.nodeId) }
    }

    /// The pin's Memory opened, as its row opens it.
    func openPin(_ pin: MemoriesMap.Pin) {
        if let ref = model.objects.first(where: { $0.nodeId == pin.nodeId && $0.scopeId == pin.scopeId }) { model.open(ref) }
    }

    /// Memory or chapter `gallery`'s date editor (core's), nil where whoever looks may not edit its meta or offline.
    func dateEditor(_ gallery: GalleryModel) -> DateEditor? { model.shellModel?.dateEditor(gallery: gallery) }

    /// Memory or chapter `gallery`'s place editor (core's) over this tenant's map services.
    func placeEditor(_ gallery: GalleryModel) -> PlaceEditor? { model.shellModel?.placeEditor(gallery: gallery) }

    /// The open page's Map view tracks (core's `TrackList` over its gallery), while the view shows; its state arrives in
    /// `tracksShown`.
    @ObservationIgnored private var openTracks: (model: TrackList, watch: TrackList.Watch)?
    private(set) var tracksShown: TrackList.State?

    /// `gallery`'s tracks, made on the Map view's first ask.
    func trackList(_ gallery: GalleryModel) -> TrackList? {
        if let held = openTracks, held.model.gallery === gallery { return held.model }
        guard let m = model.shellModel, let uploads = media else { return nil }
        closeTrackList()
        let made = m.trackList(gallery: gallery, uploads: uploads, left: { @Sendable source in
            if let url = ApplePlatform.shared.urlOf(source: source) { AppModel.removePicked(url) }
        })
        let id = ObjectIdentifier(made)
        let watch = made.watch { @Sendable state in
            nonisolated(unsafe) let state = state
            Task { @MainActor [weak self] in
                guard let self, let open = self.openTracks?.model, ObjectIdentifier(open) == id else { return }
                self.tracksShown = state
            }
        }
        openTracks = (made, watch)
        return made
    }

    private func closeTrackList() {
        openTracks?.watch.stop()
        openTracks?.model.close()
        openTracks = nil
        tracksShown = nil
    }

    /// Picked GPX files (`urls`) offered to `tracks`' node: each checked on the device; what is refused is said by the view.
    func addTracks(_ tracks: TrackList, urls: [URL]) async {
        let sources: [UploadSource] = urls.compactMap { url in
            guard let p = AppModel.Picked(url) else { return nil }
            return ApplePlatform.shared.source(url: url, name: p.name, size: p.size, type: p.type, modifiedAt: p.modifiedAt.map { KotlinLong(value: $0) })
        }
        guard !sources.isEmpty else { return }
        _ = try? await tracks.add(sources: sources)
    }

    // MARK: Music (ADR 0142)

    /// The open page's Music view (core's `MusicModel` over its gallery), while the view shows; its state arrives in
    /// `musicShown`.
    @ObservationIgnored private var openMusicView: (model: MusicModel, watch: MusicModel.Watch)?
    private(set) var musicShown: MusicModel.State?

    /// `gallery`'s Music view model, made on its first ask.
    func musicModel(_ gallery: GalleryModel) -> MusicModel? {
        if let held = openMusicView, held.model.gallery === gallery { return held.model }
        guard let m = model.shellModel, let uploads = media else { return nil }
        closeMusicView()
        let made = m.musicModel(gallery: gallery, uploads: uploads, measure: AssetMeasure(), left: { @Sendable source in
            if let url = ApplePlatform.shared.urlOf(source: source) { AppModel.removePicked(url) }
        })
        let id = ObjectIdentifier(made)
        let watch = made.watch { @Sendable state in
            nonisolated(unsafe) let state = state
            Task { @MainActor [weak self] in
                guard let self, let open = self.openMusicView?.model, ObjectIdentifier(open) == id else { return }
                self.musicShown = state
            }
        }
        openMusicView = (made, watch)
        return made
    }

    private func closeMusicView() {
        openMusicView?.watch.stop()
        openMusicView?.model.close()
        openMusicView = nil
        musicShown = nil
    }

    /// Picked audio files (`urls`) added to `music`'s node as tracks: each checked on the device, the refused said.
    @discardableResult func addMusic(_ music: MusicModel, urls: [URL]) async -> [String] {
        let sources: [UploadSource] = urls.compactMap { url in
            guard let p = AppModel.Picked(url) else { return nil }
            return ApplePlatform.shared.source(url: url, name: p.name, size: p.size, type: p.type, modifiedAt: p.modifiedAt.map { KotlinLong(value: $0) })
        }
        guard !sources.isEmpty, let said = try? await music.add(sources: sources) else { return [] }
        return said.map { say(notice: $0) }
    }

    /// Queues the picked photos and videos (`urls`; `fromPhotos`: copies the Photos picker handed over, whose own dates are
    /// no capture time) into `gallery`'s Memory or chapter (core's `MediaUploads`): each one's tiers derived here
    /// (`MediaTiers`) while the one before it is on the wire; the core reads the gallery anew as each is in, the lists at
    /// the batch's end, and hands each copy back once the queue is done with it.
    func uploadMedia(_ gallery: GalleryModel, urls: [URL], fromPhotos: Bool) {
        let sources: [UploadSource] = urls.compactMap { url in
            guard let p = AppModel.Picked(url) else {
                if fromPhotos { AppModel.removePicked(url) }
                return nil
            }
            return ApplePlatform.shared.source(
                url: url, name: p.name, size: p.size, type: p.type,
                modifiedAt: fromPhotos ? nil : p.modifiedAt.map { KotlinLong(value: $0) }
            )
        }
        // a link's guest not named yet is asked its name first by the core, at the write (#1044)
        Task { _ = try? await gallery.uploadAsking(sources: sources) }
    }

    // MARK: New Memory and Add chapter

    /// A Memory `name` made in space `scopeId` under `parentId` (nil: the space's top) — New Memory, core's
    /// `ShellModel.createMemory` (the web's `MemoryNodeDialog`: checked against the loaded tree, then sent) —, opened once
    /// made (the web's), its maker listed among its participants where `wasThere` (ADR 0144 decision 2), its place armed where
    /// `autoPlace` (uc.memory.place A1). Why not, in the dialog's words, or nil.
    func createMemory(scopeId: String, parentId: String?, name: String, wasThere: Bool = false, autoPlace: Bool = false) async -> String? {
        guard let m = model.shellModel else { return say(notice: MemoryCreate.shared.refused(reason: nil)) }
        let made = try? await m.createMemory(scopeId: scopeId, parentId: parentId, name: name, wasThere: wasThere, autoPlace: autoPlace)
        model.applyShell(start: false)
        await model.readDetails(m.tenant)
        return opened(made)
    }

    /// Whether New Memory in space `scopeId` offers *I was there* (a tenant or group space, ADR 0144 decision 2).
    func offersWasThere(_ scopeId: String) -> Bool { model.shellModel?.offersWasThere(scopeId: scopeId) ?? false }

    /// Whether New Memory offers *Determine location automatically* (uc.memory.place A1: this tenant's map services shown on this device).
    func offersAutoPlace() -> Bool { model.shellModel?.offersAutoPlace() ?? false }

    /// A chapter `name` made in `gallery`'s Memory (core's `GalleryModel.createChapter`, which reads the lists anew), opened
    /// once made; why not, or nil.
    func createChapter(_ gallery: GalleryModel, name: String) async -> String? {
        let made = try? await gallery.createChapter(name: name)
        model.applyShell(start: false)
        if let m = model.shellModel { await model.readDetails(m.tenant) }
        return opened(made)
    }

    /// What a Memory's or a chapter's create came to: the node made opened (both are Memories'), or why not in words.
    private func opened(_ made: MemoryCreate.Made?) -> String? {
        // made offline (ADR 0153): the dialog closes and says it waits to be sent; nothing to open until it is
        if let made, made.queued {
            if let n = made.notice { model.show(n) }
            return nil
        }
        if let notice = made?.notice { return say(notice: notice) }
        guard let n = made?.node else { return say(notice: MemoryCreate.shared.refused(reason: nil)) }
        if let page = GalleryModel.companion.page(node: n) { model.open(page) }
        return nil
    }
}

/// What a node's header asked its page to add (its `upload` entry): from the photo library, or from files
/// (`MemoriesHolding.galleryAdd`).
struct GalleryAdd: Equatable {
    let nodeId: String
    let files: Bool
}
