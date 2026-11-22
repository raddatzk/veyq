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
        guard let gallery = model.galleryModel(node), let state = model.galleryState(node) else { return HeaderContribution(download: download) }
        // the page's own entries of core's plan, whole (`GalleryModel.headerActions`: adding photos and videos, the music,
        // the banner), each worded by the gallery
        let actions: [OwnAction] = gallery.headerActions.compactMap { a in
            let label = gallery.label(id: a).map { say($0) } ?? a.name
            switch a.entry {
            case .upload:
                // from the photo library or from files, as the page's pickers take them
                return OwnAction(id: a, label: label, systemImage: "plus", choices: [
                    OwnChoice(label: say(.photoLibrary), systemImage: "photo.on.rectangle") { model.galleryAdd = GalleryAdd(nodeId: node.nodeId, files: false) },
                    OwnChoice(label: say(.tabDepotFiles), systemImage: "folder") { model.galleryAdd = GalleryAdd(nodeId: node.nodeId, files: true) },
                ]) {}
            case .music:
                return OwnAction(id: a, label: label, systemImage: state.musicHere ? "stop.fill" : "music.note") { gallery.toggleMusic() }
            case .banner:
                return OwnAction(id: a, label: label, systemImage: "photo.artframe") { model.openBanner(gallery) }
            // core's own entries, never a gallery's
            case .favorite, .share, .rename, .move, .theCopy, .cover, .download, .offline, .report, .delete_, .replace, .send, .openOwnPage, .reportShared:
                return nil
            }
        }
        return HeaderContribution(download: download, actions: actions)
    }, body: { ref, _, model in AnyView(MemoryScreen(model: model, ref: ref)) }, guest: { page, held, model in guest(page, held, model) })

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
            personSection: { profile, model, frame in AnyView(PersonMemoriesPage(model: model, profile: profile, frame: frame)) }
        ))
    }

    /// A link's Memory or chapter: its gallery with a guest's rights, or — on a WRITE_ONLY link — the uploader.
    private static func guest(_ page: GuestPage, _ held: GuestHeld, _ model: AppModel) -> AnyView {
        switch page {
        case let gallery as MemoriesGuest.Gallery:
            AnyView(MemoryScreen(model: model, ref: .guest(gallery.node, .memories))
                .environment(\.guestGallery, GuestGallery(canAdd: held.guest.value != nil) { c in held.open(c.nodeId) }))
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
        let offers = model.offersWasThere(scopeId)
        // the place armed to come from the photos (uc.memory.place A1, the web's opt-in): where the map services are shown, off
        let offersPlace = model.offersAutoPlace()
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
            let refused = await model.createMemory(scopeId: scopeId, parentId: parentId, name: typed, wasThere: offers && wasThere, autoPlace: offersPlace && autoPlace)
            saving = false
            if let refused { problem = refused } else { dismiss() }
        }
    }
}

/// A Memory's or a chapter's WRITE_ONLY link (the web's `MemoryShareBody`): the guest names itself — the record's notice
/// before —, then adds photos and videos; it sees only what it sent while the link is open here, and removes its own.
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
            if let named = held.guest.value {
                if up.offered {
                    NotYou(session: named) { up.forget() }
                    RecordNotice(days: days, origin: origin, guestId: named.identityId)
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
            } else {
                InlineNaming(hint: say(.shareNameVisibility), days: days, origin: origin) { name in
                    (try? await AppleSharing.shared.nameUploader(up: up, displayName: name))?.boolValue ?? false
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
                _ = up.upload(sources: urls.compactMap { model.guestMediaSource($0, fromPhotos: true) })
            }
        }
        .fileImporter(isPresented: $pickingFiles, allowedContentTypes: [.image, .movie], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            _ = up.upload(sources: urls.compactMap { model.guestMediaSource($0, fromPhotos: false) })
        }
        .onAppear {
            sent = CoreState({ (up.sent.value as? NSArray) ?? [] }) { changed in AppleMemories.shared.watch(flow: up.sent) { @Sendable _ in changed() } }
        }
        .onDisappear { sent?.stop() }
    }
}

/// A gallery shown to a link's guest (ADR 0150 decision 5): the member's page with a guest's rights — its chapters open
/// inside the link (`onChapter`), it adds once it named itself (`canAdd`), and what is the account's (the Music and Map
/// views, the music player, a drop from another app, a lightbox in a window of its own, a person's profile) is not offered.
struct GuestGallery {
    let canAdd: Bool
    let onChapter: (NodeRef) -> Void
}

extension EnvironmentValues {
    @Entry var guestGallery: GuestGallery?
}
