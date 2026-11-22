import SwiftUI
import UniformTypeIdentifiers
import VeyqCore

/// Depot's UI as it registers into the core UI (ADR 0154 decision 3; the web's `registerDepot`): a file's page and a drop
/// box's, each with what it contributes to core's header, and their pages behind a link; its list is core's arranged one.
@MainActor
enum DepotUi {
    /// Depot's file: what its header offers is core's (`FileModel`'s header: its facts, Replace, Send a Copy where the
    /// platform shares, its download — the file itself under its own name); its page (`FileScreen`).
    private static let file = KindPage(header: { node, model in
        guard let file = model.depot.fileModel(node), let h = model.depot.fileState(node)?.header else { return nil }
        return HeaderContribution(
            facts: h.facts,
            download: { await model.downloadNode(node, to: $0) },
            // the page's own entries of core's plan, whole (`FileModel.Header.own`: Replace, Send a Copy)
            actions: h.own.compactMap { a in
                let label = h.label(id: a).map { say($0) } ?? a.name
                switch a.entry {
                case .send:
                    return OwnAction(id: a, label: label, systemImage: "paperplane") { model.depot.sendingCopy = node }
                case .replace:
                    return OwnAction(id: a, label: label, systemImage: "square.and.arrow.down.on.square") {
                        // the content shown now is what the replace expects (core's choice), whatever a reload shows while picking
                        if file.chooseReplace() { model.depot.replacing = file }
                    }
                // core's own entries and other pages', never a file's
                case .favorite, .share, .rename, .move, .theCopy, .cover, .download, .offline, .report, .delete_, .upload, .music, .banner, .openOwnPage, .reportShared:
                    return nil
                }
            }
        )
    }, body: { ref, _, model in AnyView(FileScreen(model: model, ref: ref)) }, guest: { page, _, model in
        guard page is DepotGuest.File else { return AnyView(EmptyView()) }
        return AnyView(FileScreen(model: model, ref: .guest(page.node, .depot)))
    }, guestActions: { page, held, model in
        guard let file = page as? DepotGuest.File else { return AnyView(EmptyView()) }
        return AnyView(GuestFileActions(model: model, held: held, file: file.model))
    })

    /// Depot's drop box: its guests' folders (`DropBoxScreen`); never renamed; its cover; its ZIP and its copy (a plain folder).
    private static let dropBox = KindPage(header: { node, model in
        HeaderContribution(
            download: { await model.downloadNode(node, to: $0) }
        )
    }, body: { ref, _, model in AnyView(DropBoxScreen(model: model, ref: ref)) }, guest: { page, held, model in
        guard let drop = page as? DepotGuest.DropBox else { return AnyView(EmptyView()) }
        return AnyView(GuestDropBoxView(model: model, held: held, drop: drop.model))
    })

    static func register(_ registry: Registry.Type) {
        // an SVG is drawn by WebKit's image mode, closed around it (SvgPicture.swift, inbox #1059)
        FileView.shared.drawSvgAsImage()
        registry.kind(file, DepotFiles.shared.FILE)
        registry.kind(dropBox, DepotFiles.shared.DROPBOX)
        registry.application(ApplicationUi(
            id: .depot,
            holding: { DepotHolding($0) },
            shell: { AnyView(DepotShell(model: $0)) }
        ))
    }
}

/// A drop box's WRITE_ONLY link (the web's `DropBoxShareBody`): the guest names itself — the record's notice before —, then
/// sends files, each into its own folder; what it sent while the link is open here is listed, nothing else of the drop box.
private struct GuestDropBoxView: View {
    @Bindable var model: AppModel
    let held: GuestHeld
    let drop: GuestDropBox
    @State private var sent: CoreState<NSArray>?
    @State private var queue: CoreState<UploadQueue.State>?
    @State private var picking = false

    private var days: Int? { held.access?.guestWriteRecordDays?.intValue }
    private var origin: String { held.access?.origin ?? "" }

    var body: some View {
        GateColumn {
            Text(say(.shareDropboxTitle)).font(.title2.bold())
            Text(say(.shareDropboxHint, drop.node.name)).foregroundStyle(Role.muted)
            if let named = held.guest.value {
                NotYou(session: named) { drop.forget() }
                RecordNotice(days: days, origin: origin, guestId: named.identityId)
                PrimaryButton(title: say(.shareDropboxPick)) { picking = true }.accessibilityIdentifier("guest-drop-pick")
                let mine = (queue?.value.batches ?? []).filter { $0.label == drop.node.name }.flatMap { $0.items }
                let names = (sent?.value as? [String]) ?? []
                if !names.isEmpty || !mine.isEmpty {
                    Text(say(.shareDropboxSession)).font(.headline).padding(.top, 8)
                    ForEach(mine, id: \.id) { item in Text(joinedLine([item.name, status(item)])) }
                    ForEach(names.filter { n in !mine.contains { $0.name == n } }, id: \.self) { n in
                        Text(n).accessibilityIdentifier("guest-drop-sent")
                    }
                }
            } else {
                InlineNaming(hint: say(.shareDropboxNameHint), days: days, origin: origin) { name in
                    (try? await AppleSharing.shared.nameDrop(drop: drop, displayName: name))?.boolValue ?? false
                }
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            _ = drop.upload(sources: urls.compactMap(model.guestSource))
        }
        .onAppear {
            sent = CoreState({ (drop.sent.value as? NSArray) ?? [] }) { changed in ApplePlatform.shared.watch(flow: drop.sent) { @Sendable _ in changed() } }
            let uploads = model.uploads
            queue = CoreState({ uploads.state.value as! UploadQueue.State }) { changed in ApplePlatform.shared.watch(flow: uploads.state) { @Sendable _ in changed() } }
        }
        .onDisappear {
            sent?.stop()
            queue?.stop()
        }
    }

    private func status(_ item: UploadQueue.Item) -> String {
        if item.active { return say(.shareDropboxSending) }
        return item.status == .failed ? say(.shareDropboxFailed) : say(.shareDropboxSent)
    }
}

/// A file's own actions on the link's bar: its download (a copy where the person picks) and a named READ_WRITE guest's
/// replace (core's `FileModel` header decides whether it is offered).
private struct GuestFileActions: View {
    @Bindable var model: AppModel
    let held: GuestHeld
    let file: FileModel
    @State private var replacing = false
    @State private var downloaded: URL?
    @State private var failed: String?

    private var header: FileModel.Header? { model.depot.fileState(file.node)?.header }

    var body: some View {
        if let h = header {
            Button { Task { await download(h) } } label: { Label(say(.nodeDownload), systemImage: "arrow.down.circle") }
                .fileMover(isPresented: Binding(get: { downloaded != nil }, set: { if !$0 { cleanUp() } }), file: downloaded) { _ in cleanUp() }
                .alert(failed ?? "", isPresented: Binding(get: { failed != nil }, set: { if !$0 { failed = nil } })) {
                    Button(say(.ok), role: .cancel) {}
                }
            // Replace where the file's rights hold it (`FileRights`, the header's own): a guest not named yet is asked its
            // name at the write by the core (`GuestAccess.replaceAsking`), never here
            if h.own.contains(HeaderAction.replace) {
                Button { if file.chooseReplace() { replacing = true } } label: {
                    Label(h.label(id: .replace).map { say($0) } ?? "", systemImage: "square.and.arrow.down.on.square")
                }
                .fileImporter(isPresented: $replacing, allowedContentTypes: [.item]) { result in
                    if case .success(let url) = result, let access = held.screen.view.access { model.depot.guestReplace(access, file: file, url: url) }
                }
            }
        }
    }

    /// The file under its download name: a Mac asks where in its save panel, iOS writes into the app's downloads directory
    /// and hands it to the save sheet (as a member's download, `NodeHeaderActions`).
    private func download(_ h: FileModel.Header) async {
        #if os(macOS)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = h.downloadName
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let end = try? await AppleSharing.shared.saveFile(file: file, path: url.path)
        if end != .saved { failed = say(.fileOpenFailed) }
        #else
        guard let dir = AppModel.downloadDir() else { return }
        let url = dir.appendingPathComponent(h.downloadName)
        let end = try? await AppleSharing.shared.saveFile(file: file, path: url.path)
        if end == .saved {
            downloaded = url
        } else {
            try? FileManager.default.removeItem(at: dir)
            failed = say(.fileOpenFailed)
        }
        #endif
    }

    private func cleanUp() {
        if let url = downloaded { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        downloaded = nil
    }
}
