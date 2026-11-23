import PhotosUI
import SwiftUI
import VeyqCore

/// Memories' UI as it registers into the core UI (ADR 0154 decision 3; the web's `registerMemories`): the Memory's and the
/// chapter's page — the music and the banner it contributes to core's header, its gallery (`MemoryScreen`), a link's
/// gallery or uploader —, its own list with the bar's Map tab, New Memory, its section of a person's profile, and on a Mac
/// the lightbox's window with the menu command that opens it.
@MainActor
enum MemoriesUi {
    /// Memories' Memory and chapter: their gallery (`MemoryScreen`); their cover, copy and download are core's. Their
    /// header plays or stops their music (core's `GalleryModel.headerActions`' MUSIC, ADR 0142 decision 6) and opens the banner's dialog where whoever
    /// looks may set it, online (ADR 0144 decision 5; the web's header `banner`); its `upload` entry opens the page's pickers.
    private static let memory = KindPage(header: { node, model in
        let download: (String) async -> [NodeExport.Skipped]? = { await model.downloadNode(node, to: $0) }
        guard let gallery = model.memories.galleryModel(node), let state = model.memories.galleryState(node) else { return HeaderContribution(download: download) }
        // the page's own entries of core's plan, whole (`GalleryModel.headerActions`: adding photos and videos, the music,
        // the banner), each worded by the gallery
        let actions: [OwnAction] = gallery.headerActions.compactMap { a in
            let label = gallery.label(id: a).map { say($0) } ?? a.name
            switch a.entry {
            case .upload:
                // from the photo library or from files, as the page's pickers take them
                return OwnAction(id: a, label: label, systemImage: "plus", choices: [
                    OwnChoice(label: say(.photoLibrary), systemImage: "photo.on.rectangle") { model.memories.galleryAdd = GalleryAdd(nodeId: node.nodeId, files: false) },
                    OwnChoice(label: say(.tabDepotFiles), systemImage: "folder") { model.memories.galleryAdd = GalleryAdd(nodeId: node.nodeId, files: true) },
                ]) {}
            case .music:
                return OwnAction(id: a, label: label, systemImage: state.musicHere ? "stop.fill" : "music.note") { gallery.toggleMusic() }
            case .banner:
                return OwnAction(id: a, label: label, systemImage: "photo.artframe") { model.memories.openBanner(gallery) }
            // core's own entries, never a gallery's
            case .favorite, .share, .rename, .move, .theCopy, .cover, .download, .offline, .report, .delete_, .replace, .send, .openOwnPage, .reportShared:
                return nil
            }
        }
        return HeaderContribution(download: download, actions: actions)
    }, body: { ref, _, model in AnyView(MemoryScreen(model: model, ref: ref)) }, guest: { page, held, model in guest(page, held, model) },
    guestActions: { page, _, model in AnyView(GuestMusicAction(model: model, page: page)) })

    static func register(_ registry: Registry.Type) {
        registry.kind(memory, "MEMORY", "CHAPTER")
        registry.application(ApplicationUi(
            id: .memories,
            list: { model, key in
                // the bar's Map tab: every Memory of the list with a place, on MapKit's map (ADR 0143 D6)
                key == "memories.map" ? AnyView(MemoriesMapTab(model: model)) : AnyView(MemoriesList(model: model))
            },
            // New Memory: made at the level shown and opened (the web's `MemoryNodeDialog`)
            creates: [.memory: { model, space, folder in
                AnyView(Group { if let space { NewMemorySheet(model: model, scopeId: space, parentId: folder) } })
            }],
            personSection: { profile, model, frame in AnyView(PersonMemoriesPage(model: model, profile: profile, frame: frame)) },
            holding: { MemoriesHolding($0) }
        ))
    }

    /// A link's Memory or chapter: its gallery with a guest's rights, or — on a WRITE_ONLY link — the uploader.
    private static func guest(_ page: GuestPage, _ held: GuestHeld, _ model: AppModel) -> AnyView {
        switch page {
        case let gallery as MemoriesGuest.Gallery:
            AnyView(MemoryScreen(model: model, ref: .guest(gallery.node, .memories))
                .environment(\.guestGallery, GuestGallery { c in held.open(c.nodeId) }))
        case let up as MemoriesGuest.Uploader:
            AnyView(GuestUploaderView(model: model, held: held, up: up.model))
        default:
            AnyView(EmptyView())
        }
    }

    #if os(macOS)
    /// The File menu's "Open in New Window" for the inline lightbox.
    static var commands: some Commands { LightboxCommands() }

    /// A Memory's lightbox in a window of its own, only when asked ("Open in New Window"): its value carries ids alone, and
    /// it is not restored at the next start (its models are the open tenant's, opened anew each time).
    static func windows(_ model: AppModel) -> some Scene {
        WindowGroup(id: "lightbox", for: LightboxWindowValue.self) { $value in
            if let value { LightboxWindow(model: model, value: value) }
        }
        .restorationBehavior(.disabled)
        .defaultSize(width: 960, height: 720)
        // a veyq: link never opens a lightbox window (it goes to the main one)
        .handlesExternalEvents(matching: [])
    }
    #endif
}

/// Memories' own list (its Memories); its New is the drawer's (core's `LevelPlan`: an application's list offers none).
struct MemoriesList: View {
    @Bindable var model: AppModel

    var body: some View {
        ArrangedListView(model: model, list: model.applicationList(.memories), onOpen: model.open, key: "app:\(ApplicationId.memories)")
            .navigationTitle(Names.application(.memories))
    }
}

/// New Memory (the web's `MemoryNodeDialog`) as a sheet: its name and, in a tenant or group space, **I was there** — on
/// each time it opens, never remembered (ADR 0144 decision 2); on, the Memory is born listing its maker among its
/// participants. Made in space `scopeId` under `parentId` and opened; a refusal says why and stays.
struct NewMemorySheet: View {
    let model: AppModel
    let scopeId: String
    let parentId: String?
    @State private var name = ""
    @State private var wasThere = true
    @State private var autoPlace = false
    @State private var problem: String?
    @State private var saving = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let offers = model.memories.offersWasThere(scopeId)
        // the place armed to come from the photos (uc.memory.place A1, the web's opt-in): where the map services are shown, off
        let offersPlace = model.memories.offersAutoPlace()
        NavigationStack {
            Form {
                Section {
                    TextField(say(.nodeName), text: $name)
                        .onSubmit { create(offers, offersPlace) }
                } footer: {
                    if let problem { Text(problem).foregroundStyle(Role.danger) }
                }
                if offersPlace {
                    Toggle(isOn: $autoPlace) {
                        Text(say(.memoryPlaceAuto))
                        Text(say(.memoryPlaceAutoHint))
                    }
                }
                if offers {
                    Toggle(isOn: $wasThere) {
                        Text(say(.memoryParticipantsWasThere))
                        Text(say(.memoryParticipantsWasThereHint))
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.memoryCreateMemoryTitle))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() }.disabled(saving) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.folderCreate)) { create(offers, offersPlace) }
                        .disabled(saving || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .interactiveDismissDisabled(saving)
        #if os(macOS)
        .frame(minWidth: 380, minHeight: 220)
        #endif
    }

    private func create(_ offers: Bool, _ offersPlace: Bool) {
        guard !saving, !name.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        saving = true
        problem = nil
        let typed = name
        Task {
            let refused = await model.memories.createMemory(scopeId: scopeId, parentId: parentId, name: typed, wasThere: offers && wasThere, autoPlace: offersPlace && autoPlace)
            saving = false
            if let refused { problem = refused } else { dismiss() }
        }
    }
}

/// A Memory's or a chapter's WRITE_ONLY link (the web's `MemoryShareBody`): the guest adds photos and videos at once; a
/// guest not named yet is asked its name at that upload by the core (`GuestUploader.uploadAsking`, its sheet the link's
/// `AskedNaming`, the record's notice in it; #1090: no form in the page). Named, "You are here as …" with *Not you?*; it
/// sees only what it sent while the link is open here, and removes its own.
private struct GuestUploaderView: View {
    @Bindable var model: AppModel
    let held: GuestHeld
    let up: GuestUploader
    @State private var sent: CoreState<NSArray>?
    @State private var pickingPhotos = false
    @State private var photos: [PhotosPickerItem] = []
    @State private var pickingFiles = false
    @State private var failed = false

    private var days: Int? { held.access?.guestWriteRecordDays?.intValue }
    private var origin: String { held.access?.origin ?? "" }

    var body: some View {
        GateColumn {
            Text(say(.shareUploaderIntro)).foregroundStyle(Role.muted)
            if up.offered {
                if let named = held.guest.value {
                    NotYou(session: named) { up.forget() }
                    RecordNotice(days: days, origin: origin, guestId: named.identityId)
                }
                Menu {
                    Button { pickingPhotos = true } label: { Label(say(.photoLibrary), systemImage: "photo.on.rectangle") }
                    Button { pickingFiles = true } label: { Label(say(.tabDepotFiles), systemImage: "folder") }
                } label: {
                    Label(say(.shareAddPhotos), systemImage: "plus").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Role.primary)
                .accessibilityIdentifier("guest-uploader-pick")
                Text(say(.shareUploaderSession)).font(.footnote).foregroundStyle(Role.muted)
                ForEach((sent?.value as? [GuestUploader.Sent]) ?? [], id: \.mediaId) { s in
                    HStack {
                        Text(s.name).accessibilityIdentifier("guest-uploader-sent")
                        Spacer()
                        Button(say(.entryDelete), role: .destructive) {
                            Task { failed = !((try? await up.remove(mediaId: s.mediaId))?.boolValue ?? false) }
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }
        }
        .alert(say(.shareDeleteFailed), isPresented: $failed) { Button(say(.ok), role: .cancel) {} }
        .photosPicker(isPresented: $pickingPhotos, selection: $photos, matching: .any(of: [.images, .videos]), preferredItemEncoding: .current)
        .onChange(of: photos) {
            let picked = photos
            photos = []
            guard !picked.isEmpty else { return }
            Task {
                var urls: [URL] = []
                for item in picked {
                    if let file = try? await item.loadTransferable(type: PickedMedia.self) { urls.append(file.url) }
                }
                upload(urls.compactMap { model.memories.guestMediaSource($0, fromPhotos: true) })
            }
        }
        .fileImporter(isPresented: $pickingFiles, allowedContentTypes: [.image, .movie], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            upload(urls.compactMap { model.memories.guestMediaSource($0, fromPhotos: false) })
        }
        .onAppear {
            sent = CoreState({ (up.sent.value as? NSArray) ?? [] }) { changed in ApplePlatform.shared.watch(flow: up.sent) { @Sendable _ in changed() } }
        }
        .onDisappear { sent?.stop() }
    }

    /// The picked media queued as the guest; one not named yet is asked its name first (the core's `uploadAsking`);
    /// declined, nothing is queued or said.
    private func upload(_ sources: [UploadSource]) {
        Task {
            let queued = (try? await up.uploadAsking(sources: sources))?.queued ?? 0
            if queued == 0 { sources.forEach { $0.close() } }
        }
    }
}

/// A gallery shown to a link's guest (ADR 0150 decision 5): the member's page with a guest's rights — its chapters open
/// inside the link (`onChapter`), it adds where the rights let it, named or not — the core asks an unnamed guest's name
/// at the upload (`GalleryModel.uploadAsking`, #1044) —, and what is the account's (the Music and Map
/// views, the music player, a drop from another app, a lightbox in a window of its own, a person's profile) is not offered.
struct GuestGallery {
    let onChapter: (NodeRef) -> Void
}

extension EnvironmentValues {
    @Entry var guestGallery: GuestGallery?
}

/// A link's Memory's music played or stopped on the link's bar, as the member's header offers it (#1034).
private struct GuestMusicAction: View {
    @Bindable var model: AppModel
    let page: GuestPage

    var body: some View {
        if let g = page as? MemoriesGuest.Gallery, let gallery = model.memories.galleryModel(g.node), let state = model.memories.galleryState(g.node),
           gallery.headerActions.contains(HeaderAction.music) {
            Button { gallery.toggleMusic() } label: {
                Label(gallery.label(id: .music).map { say($0) } ?? "", systemImage: state.musicHere ? "stop.fill" : "music.note")
            }
            .accessibilityIdentifier("guest-music")
        }
    }
}
