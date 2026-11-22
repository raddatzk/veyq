import Foundation
import Observation
import SwiftUI
import VeyqCore

extension AppModel {
    /// What Depot holds in the app's model: the shell's uploads and the file page on screen.
    var depot: DepotHolding { held(DepotHolding.self) }
}

/// What Depot holds in the app's model for the open tenant (ADR 0154 decision 3, inbox #1066): the shell's uploads (core's
/// `DepotUploads` over the app's one queue, one per open tenant) — the tray draws them, New's *Upload files* and *Upload
/// folder*, a level's drop and *Add here* and a share's handoff into a level go through them, and what they say the shell
/// presents (`DepotShell`) —, and the file page on screen with its Replace and "Send a Copy…".
@MainActor
@Observable
final class DepotHolding: Holding, LevelUploads {
    @ObservationIgnored private unowned let model: AppModel

    init(_ model: AppModel) { self.model = model }

    // MARK: The moments of the tenant's life (core asks them)

    func leave() { closeFile() }

    func shown(_ shell: ShellModel, _ tenant: TenantSession, switched: Bool) {
        closeDepot()
        let changed: @Sendable () -> Void = {
            Task { @MainActor [weak self] in
                guard let self, let t = self.model.tenantSession else { return }
                await self.model.refreshLists(t)
            }
        }
        #if os(macOS)
        // a Mac's uploads offer a download's import (ADR 0152 decision 1); the phones' do not (owner 2026-11-19)
        let d = shell.depotUploadsImporting(queue: model.uploads, deriver: model.deriver, changed: changed)
        #else
        let d = shell.depotUploads(queue: model.uploads, changed: changed)
        #endif
        uploads = d
        state = d.current
        watch = d.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.sync() }
        }
    }

    func signedOut() {
        closeDepot()
        closeFile()
    }

    /// The file on screen reads itself anew: a replaced blob is shown.
    func listsRead() { openFile?.model.reload() }

    var levelUploads: LevelUploads? { self }

    // MARK: The shell's uploads (core's DepotUploads)

    /// The uploads as the shell shows them (core's `DepotUploads` over the app's queue, one per open tenant).
    @ObservationIgnored private var uploads: DepotUploads?
    /// Their state on the main actor: the tray draws its queue, the shell presents what it says (`said`).
    private(set) var state: DepotUploads.State?
    @ObservationIgnored private var watch: DepotUploads.Watch?

    private func closeDepot() {
        watch?.stop()
        watch = nil
        uploads?.close()
        uploads = nil
        state = nil
    }

    /// The uploads' state on the main actor; what they say (`said`) the shell shows as an alert of its own, one at a time —
    /// but a said that offers nothing and only says something went through lives as a success does (`show`), seen at once.
    private func sync() {
        guard let d = uploads else { return }
        let current = d.current
        if let said = current.said, said.action == nil, !said.notices.isEmpty, said.notices.allSatisfy({ $0.kind == .said }) {
            model.show(said.notices)
            d.seen(id: said.id)
            state = d.current
            return
        }
        state = current
    }

    /// What the uploads said was seen (the alert answered).
    func seen(_ said: DepotUploads.Said) { uploads?.seen(id: said.id) }

    /// The offer `said` makes taken (its "Replace anyway").
    func take(_ said: DepotUploads.Said) { uploads?.take(said: said) }

    var tray: TrayShown? { state.map { TrayShown(queue: $0.queue, preparing: $0.preparing) } }
    func cancelAll() { uploads?.cancelAll() }
    func acknowledge(_ batchId: String) { uploads?.acknowledge(batchId: batchId) }
    func cancel(_ id: String) { uploads?.cancel(id: id) }
    func retry(_ id: String) { uploads?.retry(id: id) }

    /// Queues the picked `urls` as new files under the level shown (core's `DepotUploads.upload`): a name a file already
    /// holds offers its replace.
    func upload(_ urls: [URL]) {
        guard let d = uploads, let scopeId = model.shell.space else { return }
        let sources: [UploadSource] = urls.compactMap { url in
            guard let p = AppModel.Picked(url) else { return nil }
            return ApplePlatform.shared.source(url: url, name: p.name, size: p.size, type: p.type, modifiedAt: p.modifiedAt.map { KotlinLong(value: $0) })
        }
        #if os(macOS)
        // the first picked veyq download is offered as an import, *Upload as a file* beside it (ADR 0152 decision 1)
        let (parentId, label) = (model.shell.folder?.nodeId, model.levelLabel)
        Task {
            let screened = try? await d.screen(scopeId: scopeId, parentId: parentId, label: label, sources: sources, left: nil, deletedName: say(.potDeletedPerson))
            model.offerImport(screened?.offer)
        }
        #else
        _ = d.upload(scopeId: scopeId, parentId: model.shell.folder?.nodeId, label: model.levelLabel, sources: sources)
        #endif
    }

    /// Depot's "Upload folder…": the directory at `url` (the system's folder picker) walked here — every entry by its path
    /// below it, hidden ones included, a link said as one and never followed — and handed to core's
    /// `DepotUploads.uploadFolder`, which keeps, prepares, queues and says.
    func uploadFolder(_ url: URL) async {
        guard let d = uploads, let scopeId = model.shell.space else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        let files = AppModel.folderFiles(url)
        if scoped { url.stopAccessingSecurityScopedResource() }
        let found = ApplePlatform.shared.found(root: url, entries: files)
        #if os(macOS)
        // a folder holding `.veyq/manifest.json` is a download unpacked, offered as an import (ADR 0152 decision 1); a screen
        // that threw says so, never nothing (the macOS pass of 2026-11-20 saw a dropped folder do nothing)
        do {
            let screened = try await d.screenFolder(
                scopeId: scopeId, parentId: model.shell.folder?.nodeId, label: model.levelLabel, shownName: nil, path: url.path, found: found,
                deletedName: say(.potDeletedPerson)
            )
            AppModel.dropLog("folder screened: \(files.count) entries, import offered \(screened.offer != nil), queued \(screened.queued)")
            model.offerImport(screened.offer)
        } catch {
            AppModel.dropLog("folder screen failed: \(type(of: error))")
            model.notice = AppModel.Notice(text: say(.depotFolderFailed, url.lastPathComponent))
        }
        #else
        try? await d.uploadFolder(scopeId: scopeId, parentId: model.shell.folder?.nodeId, shownName: nil, path: url.path, found: found)
        #endif
    }

    /// A guest's *Upload folder*: the directory at `url` walked as a member's is and handed to core's
    /// `DepotGuest.uploadFolder` (through `AppleSharing`, off the main actor); what it says, each line.
    func guestUploadFolder(_ url: URL, level: GuestLevel) async -> [VeyqCore.Notice] {
        let scoped = url.startAccessingSecurityScopedResource()
        let files = AppModel.folderFiles(url)
        if scoped { url.stopAccessingSecurityScopedResource() }
        let found = ApplePlatform.shared.found(root: url, entries: files)
        return (try? await AppleSharing.shared.guestUploadFolder(level: level, path: url.path, found: found)) ?? []
    }

    // MARK: The share's way in (ADR 0138)

    /// *Add here* on the level shown: its files into the queue under `label` — in the share extension handed to the app
    /// through the app group (ADR 0138 decision 6); on a Mac a veyq download among them offered as an import (ADR 0152
    /// decision 1). A level takes any file (`FolderShare`), so nothing is left out to say.
    func addHere(_ target: AddHere, label: String) -> Bool {
        guard let l = target as? AddHere.Level else { return false }
        let inbox = model.inbox
        if model.inExtension {
            guard l.answer.mayChoose(place: l.place) else { return true }
            let taken = inbox.queue(answer: target.answer) { sources in
                KotlinInt(int: Int32(ShareHandoff.write(sources, kind: l.place.kind, scopeId: l.scopeId, nodeId: l.parentId, label: label)))
            }
            model.shareTaken(taken, notice: nil)
            return true
        }
        #if os(macOS)
        Task {
            let offered = try? await l.takeOrOffer(inbox: inbox, label: label, depot: uploads, left: { @Sendable source in
                if let url = ApplePlatform.shared.urlOf(source: source) { AppModel.removePicked(url) }
            }, deletedName: say(.potDeletedPerson))
            guard let offered else {
                model.shareTaken(nil, notice: nil)
                return
            }
            model.offerImport(offered.offer)
            model.shareTaken(offered.taken, notice: nil)
        }
        #else
        let taken = target.take(inbox: inbox, label: label, depot: uploads) { source in
            if let url = ApplePlatform.shared.urlOf(source: source) { AppModel.removePicked(url) }
        }
        model.shareTaken(taken, notice: nil)
        #endif
        return true
    }

    /// Files dropped onto the level shown or a folder's row: uploaded under it (core's one drop).
    func drop(_ shell: ShellModel, _ target: DropTarget, _ items: [Dropped]) async -> DropOutcome? {
        guard target is DropTarget.Level || target is DropTarget.Row else { return nil }
        return try? await shell.drop(target: target, items: items, depot: uploads, left: { @Sendable source in
            if let url = ApplePlatform.shared.urlOf(source: source) { AppModel.removePicked(url) }
        }, deletedName: say(.potDeletedPerson))
    }

    /// A share's files the extension handed over for a folder or a space's top: queued under it.
    func pickUp(_ target: ShareHandoff.Target, _ sources: [UploadSource]) -> Bool {
        guard target.isLevel, let d = uploads else { return false }
        _ = d.upload(scopeId: target.scopeId, parentId: target.nodeId, label: target.label, sources: sources) { source in
            if let url = ApplePlatform.shared.urlOf(source: source) { AppModel.removePicked(url) }
        }
        return true
    }

    // MARK: The file page (core's FileModel)

    /// The file page on screen (core's `FileModel`, one at a time) and its watch; read anew whenever the lists are.
    @ObservationIgnored private var openFile: (model: FileModel, watch: FileModel.Watch)?

    /// What the file page on screen shows (`FileModel.State`) and of which node, on the main actor.
    private var fileShown: (node: NodeAt, state: FileModel.State)?

    /// What file `node`'s page shows, once its model delivered it; nil before, and for any other node.
    func fileState(_ node: NodeAt) -> FileModel.State? {
        guard let shown = fileShown, shown.node == node else { return nil }
        return shown.state
    }

    /// The page of file `node` (core's `FileModel` over the shell): iOS hands a copy on through its share sheet, a Mac does
    /// not. Called while a view is drawn, so it changes nothing observed: the state arrives through the watch.
    func fileModel(_ node: NodeAt) -> FileModel? {
        if let held = openFile, held.model.node == node { return held.model }
        guard let m = model.shellModel else { return nil }
        closeFile()
        #if os(iOS)
        let shares = true
        #else
        let shares = false
        #endif
        let made = m.fileModel(ref: node, shares: shares)
        holdFile(made)
        return made
    }

    /// `made` the file page on screen, its state followed on the main actor (the shell's page, or a link's guest's).
    private func holdFile(_ made: FileModel) {
        let id = ObjectIdentifier(made)
        let watch = made.watch { @Sendable state in
            nonisolated(unsafe) let state = state
            Task { @MainActor [weak self] in
                // a state of a page closed meanwhile is dropped
                guard let self, let open = self.openFile?.model, ObjectIdentifier(open) == id else { return }
                self.fileShown = (open.node, state)
            }
        }
        openFile = (made, watch)
    }

    private func closeFile() {
        openFile?.watch.stop()
        openFile?.model.close()
        openFile = nil
    }

    /// Drop box `ref`'s page (core's `DropBoxPage`: its guests' folders read anew with the lists, a delete asked first).
    func dropBoxPage(_ ref: NodeAt) -> DropBoxPage? { model.shellModel?.dropBoxPage(ref: ref) }

    /// The file page whose content the person is picking a replacement for (the header's "Replace…"), while they pick.
    var replacing: FileModel? {
        didSet { if let replacing { replaceChosen = replacing } }
    }
    /// The file page Replace was chosen on, kept past the importer's dismissal: its binding clears `replacing` before the
    /// completion runs, which then found nothing to replace (the picked file was dropped, measured 2026-11-12).
    @ObservationIgnored var replaceChosen: FileModel?

    /// The header's "Replace…": the content `file` showed when Replace was chosen replaced with the picked file at `url`.
    func replaceFile(_ file: FileModel, url: URL) {
        guard let d = uploads, let p = AppModel.Picked(url) else { return }
        let source = ApplePlatform.shared.source(url: url, name: p.name, size: p.size, type: p.type, modifiedAt: p.modifiedAt.map { KotlinLong(value: $0) })
        _ = d.replace(file: file, source: source)
    }

    /// The file a person is about to send a copy of to another app ("Send a Copy…"), while the shell says first that the
    /// copy leaves veyq decrypted. The data are theirs; the app says what happens and never forbids it.
    var sendingCopy: NodeAt?

    /// A decrypted copy on its way to the system's share sheet, until the sheet is done with it.
    struct SentCopy: Identifiable {
        let url: URL
        var id: URL { url }
    }

    var sentCopy: SentCopy?

    /// A decrypted copy of file `ref` under its own name (core's `downloadName`), for the system's share sheet.
    func copyForSending(_ ref: NodeAt) async -> SentCopy? {
        guard let header = fileModel(ref)?.current.header, header.facts.hasContent else { return nil }
        model.clearSentCopies()
        let dir = AppModel.sharedDir.appendingPathComponent(UUID().uuidString, isDirectory: true)
        guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil else { return nil }
        let url = dir.appendingPathComponent(header.downloadName)
        // the file itself (core's download of a file): the one decrypted copy the person asked for, swept at the next start
        guard await model.downloadNode(ref, to: url.path) != nil else { return nil }
        return SentCopy(url: url)
    }

    // MARK: A link's file (ADR 0150 decision 5)

    func adopt(_ page: GuestPage) -> Bool {
        guard let f = page as? DepotGuest.File else { return false }
        if openFile?.model !== f.model {
            closeFile()
            fileShown = nil
            holdFile(f.model)
        }
        return true
    }

    func release(_ page: GuestPage) -> Bool {
        guard let f = page as? DepotGuest.File else { return false }
        if openFile?.model === f.model {
            closeFile()
            fileShown = nil
        } else {
            f.model.close()
        }
        return true
    }

    /// A READ_WRITE guest's replace of `file`'s content with the picked file (ADR 0150 decision 5): the job the guest's file
    /// door makes, in the app's one queue; the page reads the file anew once it settled.
    func guestReplace(_ access: GuestAccess, file: FileModel, url: URL) {
        guard let source = model.guestSource(url) else { return }
        // core's `GuestAccess.replaceAsking`: a guest not named yet is asked its name first (the core's one naming flow,
        // ADR 0154 decision 1); then the link's own queue (the app's one, handed to the link), signed in or out (#1035)
        Task {
            let queued = (try? await access.replaceAsking(file: file, source: source))?.boolValue ?? false
            if !queued { source.close() }
        }
    }
}

/// What Depot presents over the shell (its `ApplicationUi.shell`): what the uploads say (a folder's outcome, a replace
/// offered), "Send a Copy…" said first and then the system's share sheet, and a file's "Replace…" picker.
struct DepotShell: View {
    @Bindable var model: AppModel

    var body: some View {
        let depot = model.depot
        Color.clear
            // what the uploads say (core's `DepotUploads.said`), one at a time; the alert sits on a view of its own per said,
            // since SwiftUI did not present a second alert of the same modifier after the first was answered (measured
            // 2026-11-12, the second replace offer of a batch). Its dismissal sees it, its action takes the offer it shows —
            // the said is handed in, not read back (the dismissal may run first).
            .background {
                let said = depot.state?.said
                Color.clear
                    .alert(said.map { $0.notices.map { say(notice: $0) }.joined(separator: "\n") } ?? "",
                           isPresented: Binding(get: { said != nil }, set: { if !$0, let said { depot.seen(said) } }),
                           presenting: said) { s in
                        if let action = s.action {
                            Button(say(action), role: .destructive) { depot.take(s) }
                            Button(say(.signinCancel), role: .cancel) {}
                        } else {
                            Button(say(.ok), role: .cancel) {}
                        }
                    }
                    .id(said?.id ?? -1)
            }
            // "Send a Copy…": what happens is said first — the copy leaves veyq decrypted —, then the system's share sheet
            .alert(say(.fileSendTitle), isPresented: Binding(get: { depot.sendingCopy != nil }, set: { if !$0 { depot.sendingCopy = nil } }), presenting: depot.sendingCopy) { node in
                Button(say(.fileSendTitle)) {
                    Task {
                        if let copy = await depot.copyForSending(node) { depot.sentCopy = copy } else {
                            model.notice = AppModel.Notice(text: say(.downloadFailed, node.name))
                        }
                    }
                }
                Button(say(.signinCancel), role: .cancel) {}
            } message: { node in
                Text(say(.fileSendNotice, node.name))
            }
            #if os(iOS)
            .sheet(item: Binding(get: { depot.sentCopy }, set: { depot.sentCopy = $0 })) { copy in
                ActivitySheet(url: copy.url) { model.clearSentCopies() }
            }
            #endif
            // a file's "Replace…": the system's picker hands over one file
            .fileImporter(isPresented: Binding(get: { depot.replacing != nil }, set: { if !$0 { depot.replacing = nil } }), allowedContentTypes: [.item]) { result in
                // the dismissal may clear `replacing` before this runs: the file Replace was chosen on is kept apart
                let file = depot.replacing ?? depot.replaceChosen
                depot.replacing = nil
                depot.replaceChosen = nil
                guard let file, case .success(let url) = result else { return }
                depot.replaceFile(file, url: url)
            }
    }
}
