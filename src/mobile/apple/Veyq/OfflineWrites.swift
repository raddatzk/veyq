import SwiftUI
import VeyqCore

// The writes made offline (ADR 0153; cr-app-offline-writes phase 8): drawn as the core says them — the banner's count,
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
            AppleMemories.shared.watch(flow: q.items) { @Sendable _ in changed() }
        }
        banner = CoreState({ q.banner.value as? VeyqCore.Notice }) { changed in
            AppleMemories.shared.watch(flow: q.banner) { @Sendable _ in changed() }
        }
        here = CoreState({ (q.here.value as? [QueuedItem]) ?? [] }) { changed in
            AppleMemories.shared.watch(flow: q.here) { @Sendable _ in changed() }
        }
        said = CoreState({ (q.said.value as? [VeyqCore.Notice]) ?? [] }) { changed in
            AppleMemories.shared.watch(flow: q.said) { @Sendable _ in changed() }
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
    /// while writes made offline wait (core's `QueuedTray.ask`, ADR 0153 decision 2); at once where none does. The tray is
    /// the open account's: another account's outbox is not asked about (the core's, phase 6's deviation 3).
    func askingLoss(_ loss: QueuedLoss, of account: VeyqAccount? = nil, then go: @escaping () -> Void) {
        guard let q = queued, account == nil || account === tenantSession?.account else { go(); return }
        Task {
            if let ask = (try? await q.queued.tray.ask(loss: loss)) ?? nil {
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

    /// The tray's *Open* of a held write: where the core says it goes (`QueuedOpen`) — a Pot entry's held editor, the
    /// node's rename with the person's name, the page it was made on.
    func openQueued(item id: String) {
        guard let q = queued, let m = shellModel else { return }
        Task {
            guard let opens = (try? await q.queued.tray.opens(id: id)) ?? nil else { return }
            switch opens {
            case let e as QueuedOpen.Entry:
                let ref = objects.first { $0.scopeId == e.scope && $0.nodeId == e.pot }
                    ?? ObjectRef(scopeId: e.scope, nodeId: e.pot, kind: PotSession.companion.KIND, application: .expenses, name: "")
                heldOpen = e
                open(ref)
            case let r as QueuedOpen.Rename:
                m.openFound(scopeId: r.scope, nodeId: r.node)
                applyShell(start: false)
                renameAsked = r
            case let p as QueuedOpen.Place:
                if let node = p.node { m.openFound(scopeId: p.scope, nodeId: node) } else { m.switchSpace(scopeId: p.scope) }
                applyShell(start: false)
            default:
                return
            }
        }
    }

    /// The held entry `writeId`'s editor in the open Pot (core's `PotModel.heldEditor`: the person's values over the newer
    /// version, named; *Discard mine* beside its save); false where the Pot holds no such write.
    func openHeldEditor(_ writeId: String) async -> Bool {
        closeEditor()
        guard let pots = potModel, let e = (try? await pots.heldEditor(writeId: writeId, separator: decimalSeparator)) ?? nil else { return false }
        adoptEditor(e)
        return true
    }

    /// *Discard mine* in a held entry's editor: the change made offline deleted unsent, the newer version stays; whether it was.
    func discardMine() async -> Bool {
        guard let e = editorModel else { return false }
        return (try? await e.discardMine())?.boolValue ?? false
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
/// or refused) under it; nothing to open until it is sent.
struct QueuedRow: View {
    let item: QueuedItem

    var body: some View {
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
