import SwiftUI
import VeyqCore

/// Depot's UI as it registers into the core UI (ADR 0154 decision 3; the web's `registerDepot`): a file's page and a drop
/// box's, each with what it contributes to core's header, and their pages behind a link; its list is core's arranged one.
@MainActor
enum DepotUi {
    /// Depot's file: what its header offers is core's (`FileModel`'s header: its facts, Replace, Send a Copy where the
    /// platform shares, its download — the file itself under its own name); its page (`FileScreen`).
    private static let file = KindPage(header: { node, model in
        guard let file = model.fileModel(node), let h = model.fileState(node)?.header else { return nil }
        return HeaderContribution(
            facts: h.facts,
            download: { await model.downloadNode(node, to: $0) },
            actions: h.own.map { a in
                let label = h.label(id: a.id).map { say($0) } ?? a.id
                if a.id == FileModel.companion.SEND {
                    return OwnAction(id: a.id, label: label, systemImage: "paperplane", slot: a.slot) { model.sendingCopy = node }
                }
                return OwnAction(id: a.id, label: label, systemImage: "square.and.arrow.down.on.square", slot: a.slot) {
                    // the content shown now is what the replace expects (core's choice), whatever a reload shows while picking
                    if file.chooseReplace() { model.replacing = file }
                }
            }
        )
    }, body: { ref, _, model in AnyView(FileScreen(model: model, ref: ref)) }, guest: { page, _, model in
        guard page is GuestPage.File else { return AnyView(EmptyView()) }
        return AnyView(FileScreen(model: model, ref: .guest(page.node, .depot)))
    })

    /// Depot's drop box: its guests' folders (`DropBoxScreen`); never renamed; its cover; its ZIP and its copy (a plain folder).
    private static let dropBox = KindPage(header: { node, model in
        HeaderContribution(
            download: { await model.downloadNode(node, to: $0) }
        )
    }, body: { ref, _, model in AnyView(DropBoxScreen(model: model, ref: ref)) }, guest: { page, held, model in
        guard let drop = page as? GuestPage.DropBox else { return AnyView(EmptyView()) }
        return AnyView(GuestDropBoxView(model: model, held: held, drop: drop.model))
    })

    static func register(_ registry: Registry.Type) {
        registry.kind(file, DepotFiles.shared.FILE)
        registry.kind(dropBox, DepotFiles.shared.DROPBOX)
        registry.application(ApplicationUi(id: .depot))
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
                    ForEach(mine, id: \.id) { item in Text(item.name + " · " + status(item)) }
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
            sent = CoreState({ (drop.sent.value as? NSArray) ?? [] }) { changed in AppleMemories.shared.watch(flow: drop.sent) { @Sendable _ in changed() } }
            let uploads = model.uploads
            queue = CoreState({ uploads.state.value as! UploadQueue.State }) { changed in AppleMemories.shared.watch(flow: uploads.state) { @Sendable _ in changed() } }
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
