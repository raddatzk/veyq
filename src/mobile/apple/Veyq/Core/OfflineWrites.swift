import SwiftUI
import VeyqCore

// The writes made offline (ADR 0153): drawn as the core says them — the banner's count,
// what a level holds not sent yet, the tray's list and its actions, the reconnect's notices, the ask before a loss. Every
// rule, word and state is the core's (`ShellModel.queued*`, `QueuedTray`, through `AppleQueued`); this only lays them out.

/// The open shell's writes made offline on the main actor (core's `AppleQueued`): each of its states read by `value`.
@MainActor
@Observable
final class QueuedHeld {
    let queued: AppleQueued
    let items: CoreState<[QueuedItem]>
    let banner: CoreState<VeyqCore.Notice?>
    let here: CoreState<[QueuedItem]>
    let said: CoreState<[VeyqCore.Notice]>

    init(_ shell: ShellModel) {
        let q = AppleQueued(shell: shell)
        queued = q
        items = CoreState({ (q.items.value as? [QueuedItem]) ?? [] }) { changed in
            ApplePlatform.shared.watch(flow: q.items) { @Sendable _ in changed() }
        }
        banner = CoreState({ q.banner.value as? VeyqCore.Notice }) { changed in
            ApplePlatform.shared.watch(flow: q.banner) { @Sendable _ in changed() }
        }
        here = CoreState({ (q.here.value as? [QueuedItem]) ?? [] }) { changed in
            ApplePlatform.shared.watch(flow: q.here) { @Sendable _ in changed() }
        }
        said = CoreState({ (q.said.value as? [VeyqCore.Notice]) ?? [] }) { changed in
            ApplePlatform.shared.watch(flow: q.said) { @Sendable _ in changed() }
        }
    }

    func close() {
        items.stop()
        banner.stop()
        here.stop()
        said.stop()
        queued.close()
    }
}

/// The ask before a loss while writes wait (core's `QueuedAsk`: its question and its confirm), and what the confirm runs.
struct LossAsk: Identifiable {
    let id = UUID()
    let ask: QueuedAsk
    let go: () -> Void
}

extension AppModel {
    func openQueued(_ shell: ShellModel) {
        closeQueued()
        queued = QueuedHeld(shell)
    }

    func closeQueued() {
        queued?.close()
        queued = nil
    }

    /// `go` — a sign-out, *Stop trusting*, *Remove all offline content* of `account` (nil: the open one) — asked first
    /// while writes made offline wait (core's `QueuedTray.ask`, ADR 0153 decision 2); at once where none does. Another
    /// account's outbox is asked about through a tray of its own (Manage's sign-out of an account not open, #1043).
    func askingLoss(_ loss: QueuedLoss, of account: VeyqAccount? = nil, then go: @escaping () -> Void) {
        let tray: QueuedTray
        if let account, account !== tenantSession?.account {
            tray = QueuedTray(account: account, nameOf: { _, _ in nil })
        } else if let q = queued {
            tray = q.queued.tray
        } else {
            go()
            return
        }
        Task {
            if let ask = (try? await tray.ask(loss: loss)) ?? nil {
                lossAsk = LossAsk(ask: ask, go: go)
            } else {
                go()
            }
        }
    }

    /// The tray's *Discard*: the write deleted unsent, with what waits for it.
    func discardQueued(_ id: String) {
        guard let q = queued else { return }
        Task { _ = try? await q.queued.tray.discard(id: id) }
    }

    /// The tray's *Open* of a held write: where the core says it goes (`QueuedOpen`) — the node's rename with the person's
    /// name, the page it was made on, or an application's page (a Pot entry's held editor, `Holding.openQueued`).
    func openQueued(item id: String) {
        guard let q = queued, let m = shellModel else { return }
        Task {
            guard let opens = (try? await q.queued.tray.opens(id: id)) ?? nil else { return }
            switch opens {
            case let r as QueuedOpen.Rename:
                m.openFound(scopeId: r.scope, nodeId: r.node)
                applyShell(start: false)
                renameAsked = r
            case let p as QueuedOpen.Place:
                if let node = p.node { m.openFound(scopeId: p.scope, nodeId: node) } else { m.switchSpace(scopeId: p.scope) }
                applyShell(start: false)
            default:
                // a held write of an application's page (a Pot's entry): its application opens it
                _ = holdingsOpen(opens)
            }
        }
    }

    /// The tray's *Save a copy* of a refused write's file `index` at `path` (the save panel's; core's `AppleQueued.saveCopy`).
    func saveQueuedCopy(_ id: String, index: Int32, to path: String) async -> Bool {
        guard let q = queued else { return false }
        return (try? await q.queued.saveCopy(id: id, index: index, path: path))?.boolValue ?? false
    }

    /// The name and type refused write `id`'s file `index` is saved under (core's `QueuedTray.copyName`).
    func queuedCopyName(_ id: String, index: Int32) async -> String? {
        guard let q = queued, let pair = (try? await q.queued.tray.doCopyName(id: id, index: index)) ?? nil else { return nil }
        return pair.first as? String
    }

    /// The reconnect's notices were said: the core keeps them no more.
    func queuedSaidSeen() { queued?.queued.saidSeen() }
}

/// Under the offline banner: how many writes made offline wait (core's `queuedBanner`).
struct QueuedBanner: View {
    let banner: VeyqCore.Notice

    var body: some View {
        Label(say(notice: banner), systemImage: "arrow.up.circle")
            .font(.subheadline)
            .foregroundStyle(Role.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(.bar, ignoresSafeAreaEdges: [])
    }
}

/// A write made offline in its level's place (core's `queuedHere`): its kind and name, *Waiting to send* (or why it is held
/// or refused) under it. A Memory made offline opens its page (core's `QueuedItem.page`, #1040), a folder made offline is
/// entered (core's `QueuedItem.folder`, #1095); anything else opens nothing until it is sent.
struct QueuedRow: View {
    let item: QueuedItem
    var open: (ObjectRef) -> Void = { _ in }
    var enter: (FolderRef) -> Void = { _ in }

    var body: some View {
        if let page = item.page {
            Button { open(page) } label: { row.contentShape(Rectangle()) }.buttonStyle(.plain)
        } else if let folder = item.folder {
            Button { enter(folder) } label: { row.contentShape(Rectangle()) }.buttonStyle(.plain)
        } else {
            row
        }
    }

    private var row: some View {
        HStack(spacing: 12) {
            Image(systemName: "arrow.up.circle").foregroundStyle(Role.muted).frame(width: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(say(notice: item.title)).foregroundStyle(Role.text)
                Text(say(notice: item.said)).font(.caption).foregroundStyle(item.state == .waiting ? Role.muted : Role.danger)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The tray's list of writes made offline (ADR 0153 decision 5), grouped by the place each writes into: each with what it
/// is and where it stands, *Open* for a held one, *Save a copy* for a refused one carrying files, *Discard* always.
struct QueuedTraySections: View {
    let model: AppModel
    let items: [QueuedItem]

    var body: some View {
        let groups = Dictionary(grouping: items) { $0.where ?? "" }
        ForEach(groups.keys.sorted(), id: \.self) { place in
            Section {
                ForEach(groups[place] ?? [], id: \.id) { QueuedTrayRow(model: model, item: $0) }
            } header: {
                Text(place)
            }
        }
    }
}

private struct QueuedTrayRow: View {
    let model: AppModel
    let item: QueuedItem
    @State private var copies: [URL] = []
    /// What the last copy came to (core's `QueuedShown.copySaved`), said once.
    @State private var said: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            QueuedRow(item: item)
            HStack(spacing: 12) {
                if item.opens {
                    Button(say(.offlineOpen)) { model.openQueued(item: item.id) }
                }
                if item.copies > 0, item.state == .refused {
                    Button(say(.offlineSaveCopy)) { Task { await saveCopies() } }
                }
                Spacer()
                Button(say(.offlineDiscard), role: .destructive) { model.discardQueued(item.id) }
            }
            .buttonStyle(.borderless)
            .font(.footnote)
        }
        #if os(iOS)
        .fileMover(isPresented: Binding(get: { !copies.isEmpty }, set: { if !$0 { cleanUp() } }), files: copies) { _ in cleanUp() }
        #endif
        .alert(said ?? "", isPresented: Binding(get: { said != nil }, set: { if !$0 { said = nil } })) { Button(say(.ok), role: .cancel) {} }
    }

    /// Each file the refused write carries, saved where the person picks: a Mac asks in its save panel per file, iOS writes
    /// them into the app's downloads directory and hands them to the system's save sheet (as a download's).
    private func saveCopies() async {
        #if os(macOS)
        var lines: [String] = []
        for i in 0..<item.copies {
            let name = await model.queuedCopyName(item.id, index: i)
            let panel = NSSavePanel()
            panel.nameFieldStringValue = name ?? ""
            guard panel.runModal() == .OK, let url = panel.url else { break }
            let saved = await model.saveQueuedCopy(item.id, index: i, to: url.path)
            lines.append(say(notice: QueuedShown.shared.doCopySaved(name: url.lastPathComponent, saved: saved)))
        }
        if !lines.isEmpty { said = lines.joined(separator: "\n") }
        #else
        guard let dir = AppModel.downloadDir() else { return }
        var saved: [URL] = []
        for i in 0..<item.copies {
            let name = await model.queuedCopyName(item.id, index: i) ?? "\(i + 1)"
            let url = dir.appendingPathComponent(name)
            if await model.saveQueuedCopy(item.id, index: i, to: url.path) {
                saved.append(url)
            } else {
                said = say(notice: QueuedShown.shared.doCopySaved(name: name, saved: false))
            }
        }
        if saved.isEmpty { try? FileManager.default.removeItem(at: dir) } else { copies = saved }
        #endif
    }

    private func cleanUp() {
        if let dir = copies.first?.deletingLastPathComponent() { try? FileManager.default.removeItem(at: dir) }
        copies = []
    }
}

/// The ask before a loss while writes wait (`AppModel.lossAsk`), on the view `here` names (the topmost one shown).
struct LossAsking: ViewModifier {
    @Bindable var model: AppModel
    let here: Bool

    func body(content: Content) -> some View {
        content.alert(
            model.lossAsk.map { say(notice: $0.ask.question) } ?? "",
            isPresented: Binding(get: { here && model.lossAsk != nil }, set: { if !$0 { model.lossAsk = nil } }),
            presenting: model.lossAsk
        ) { asked in
            Button(say(asked.ask.confirm), role: .destructive) {
                model.lossAsk = nil
                asked.go()
            }
            Button(say(.signinCancel), role: .cancel) { model.lossAsk = nil }
        }
    }
}

/// The reconnect's notices (core's `queuedSent`): said once, then told seen.
struct QueuedSaid: ViewModifier {
    @Bindable var model: AppModel

    func body(content: Content) -> some View {
        let said = model.queued?.said.value ?? []
        content.alert(
            said.first.map { say(notice: $0) } ?? "",
            isPresented: Binding(get: { !said.isEmpty }, set: { _ in })
        ) {
            Button(say(.ok), role: .cancel) { model.queuedSaidSeen() }
        } message: {
            if said.count > 1 { Text(said.dropFirst().map { say(notice: $0) }.joined(separator: "\n")) }
        }
    }
}
