import SwiftUI
import VeyqCore

/// A node's guest links, drawn from core's `ShareSheet` (its links newest first, the form, every write and what each says;
/// a Pot's in Expenses' words): each open link copied from here or handed to another app; "New link" mints one — view
/// only, or view and contribute where the tenant allows it, an optional label only the member sees, a password and an end
/// where the member wants them or the tenant's rules require them — and shows it once. Switching a link off re-keys what is
/// below it. Swipe actions, the context menu and the alerts are Apple's; every decision is the core's.
struct NodeShareSheet: View {
    let model: AppModel
    let ref: NodeAt
    @Environment(\.dismiss) private var dismiss
    @State private var box = ShareBox()

    var body: some View {
        NavigationStack {
            Group {
                if let sheet = box.sheet, let st = box.state {
                    if let fresh = st.fresh {
                        FreshLinkView(url: fresh) { sheet.freshSeen() }
                    } else if let form = st.form {
                        NewLinkForm(
                            form: form, words: sheet.words, saving: st.saving, problem: st.problem.map { say(notice: $0) },
                            submit: sheet.submit(state: st), change: { sheet.form(change: $0) }, onCancel: { sheet.cancelForm() },
                            onSave: { Task { _ = try? await sheet.save() } }
                        )
                    } else {
                        list(sheet, st)
                    }
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(box.sheet.map { say($0.words.title) } ?? "")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(say(.shareDone)) { dismiss() } } }
        }
        .task {
            guard box.sheet == nil, let made = model.shareSheet(ref) else { return }
            box.start(made)
            try? await made.load()
        }
        .onDisappear { box.stop() }
        .sheetFrame(minHeight: 480)
        // the question is the sheet's state: a binding that cancelled on dismissal would run before the button's action (the
        // trash's purge once sent nothing so, ADR 0136), so only Cancel cancels
        .alert(say(.shareRenew), isPresented: Binding(get: { box.state?.renewing != nil }, set: { _ in })) {
            Button(say(.shareRenew)) { Task { _ = try? await box.sheet?.renew() } }
            Button(say(.signinCancel), role: .cancel) { box.sheet?.cancelAsk() }
        } message: {
            Text(say(.shareRenewConfirm))
        }
        .alert(say(.shareDelete), isPresented: Binding(get: { box.state?.deleting != nil }, set: { _ in })) {
            Button(say(.shareDelete), role: .destructive) { Task { _ = try? await box.sheet?.delete() } }
            Button(say(.signinCancel), role: .cancel) { box.sheet?.cancelAsk() }
        } message: {
            Text(box.sheet.map { say($0.words.deleteConfirm) } ?? "")
        }
    }

    private func list(_ sheet: VeyqCore.ShareSheet, _ st: VeyqCore.ShareSheet.State) -> some View {
        List {
            Section {
                Text(say(sheet.words.intro))
                    .font(.subheadline).foregroundStyle(Role.muted)
                if sheet.canMint {
                    Button(say(.shareNew), systemImage: "plus") { sheet.addLink() }
                }
                if let notice = st.notice { Text(say(notice: notice)).font(.caption).foregroundStyle(Role.muted) }
            }
            Section {
                if st.failed {
                    Text(say(.shareLoadFailed)).foregroundStyle(Role.danger)
                } else if st.links != nil {
                    ForEach(sheet.rows(state: st), id: \.link.shareId) { row in
                        // what this member may do with it is core's (`LinkRow`, the web's dialogs' rules)
                        let id = row.link.shareId
                        LinkRowView(row: row, busy: st.busy == id)
                            // the row's menu, core's (`ShareSheet.rowActions`: the web's order), whole: Apple's HIG puts a
                            // row's actions in its context menu, as Mail's
                            .contextMenu {
                                ForEach(VeyqCore.ShareSheet.companion.rowActions(row: row), id: \.self) { a in linkAction(a, row, sheet) }
                            }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
        }
    }
}

/// Entry `a` of a link row's menu (core's `LinkAction`), drawn exhaustively; each runs core's.
@MainActor @ViewBuilder
private func linkAction(_ a: LinkAction, _ row: LinkRow, _ sheet: VeyqCore.ShareSheet) -> some View {
    let id = row.link.shareId
    switch a.entry {
    case .theCopy:
        // an open link goes to another app through the system's share sheet (as Safari's links); its copy where none
        if let url = row.link.url.flatMap(URL.init(string:)) {
            ShareLink(item: url) { Label(say(.shareLinkShare), systemImage: "square.and.arrow.up") }
        }
    case .edit: Button(say(.shareEdit), systemImage: "pencil") { sheet.edit(shareId: id) }
    case .renew: Button(say(.shareRenew), systemImage: "arrow.clockwise") { sheet.askRenew(shareId: id) }
    case .reactivate: Button(say(.shareReactivate), systemImage: "power") { Task { _ = try? await sheet.reactivate(shareId: id) } }
    case .switchOff: Button(say(.shareSwitchOff), systemImage: "nosign", role: .destructive) { Task { _ = try? await sheet.switchOff(shareId: id) } }
    case .delete_: Button(say(.shareDelete), systemImage: "trash", role: .destructive) { sheet.askDelete(shareId: id) }
    }
}

/// Core's share sheet held for SwiftUI: its state mirrored on the main actor as it changes.
@MainActor
@Observable
final class ShareBox {
    private(set) var sheet: VeyqCore.ShareSheet?
    private(set) var state: VeyqCore.ShareSheet.State?
    @ObservationIgnored private var watch: VeyqCore.ShareSheet.Watch?

    func start(_ sheet: VeyqCore.ShareSheet) {
        self.sheet = sheet
        state = sheet.current
        watch = sheet.watch { @Sendable _ in
            Task { @MainActor [weak self] in
                if let current = self?.sheet?.current { self?.state = current }
            }
        }
    }

    func stop() { watch?.stop() }
}

/// One link: its label (else its role), when it was made and ends, its badges, and its copy where it can be rebuilt.
private struct LinkRowView: View {
    let row: LinkRow
    let busy: Bool
    @State private var copied = false

    private var link: ShareLinks.Link { row.link }

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(say(piece: row.title)).fontWeight(.semibold)
                Text(joinedLine([say(.shareCreated, day(link.createdAt)), link.expiresAt.map { say(.shareExpires, day($0)) }]
                    .compactMap { $0 }))
                    .font(.caption).foregroundStyle(Role.muted)
                let badges = row.badges.map { say($0) }
                if !badges.isEmpty { Text(joinedLine(badges)).font(.caption).foregroundStyle(Role.accent) }
            }
            Spacer()
            if let url = link.url, row.canCopy {
                Button { Clipboard.copy(url); copied = true } label: {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(say(.shareCopy))
                .task(id: copied) { await copiedFor($copied) }
            }
            if busy { ProgressView() }
        }
    }
}

/// The share of a space's top level (the web's `LevelView` `share(null, …)`), drawn from core's `ShareSheet.topLevel`: no
/// link roots there, so it offers "Add files only" alone — a drop box at the top, named as typed, with its first link,
/// shown once. Its form, saving, refusal and day are the core's.
struct TopLevelUploadSheet: View {
    let model: AppModel
    let scopeId: String
    @Environment(\.dismiss) private var dismiss
    @State private var box = ShareBox()

    var body: some View {
        NavigationStack {
            Group {
                if let sheet = box.sheet, let st = box.state {
                    if let fresh = st.fresh {
                        FreshLinkView(url: fresh) { dismiss() }
                    } else if let form = st.form {
                        NewLinkForm(
                            form: form, words: sheet.words, saving: st.saving, problem: st.problem.map { say(notice: $0) },
                            submit: sheet.submit(state: st), change: { sheet.form(change: $0) }, onCancel: { dismiss() },
                            onSave: { Task { _ = try? await sheet.save() } }
                        )
                    }
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(box.sheet.map { say($0.title) } ?? "")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
        .task {
            guard box.sheet == nil, let made = model.topLevelShareSheet(scopeId) else { return }
            box.start(made)
        }
        .onDisappear { box.stop() }
        .sheetFrame(minHeight: 480)
    }
}

/// The link just minted, shown once: copied, or handed to another app.
private struct FreshLinkView: View {
    let url: String
    let onDone: () -> Void
    @State private var copied = false

    var body: some View {
        Form {
            Section(say(.shareReady)) {
                Text(url).font(.footnote.monospaced()).textSelection(.enabled)
                Button(copied ? say(.shareCopied) : say(.shareCopy), systemImage: copied ? "checkmark" : "doc.on.doc") { Clipboard.copy(url); copied = true }
                    .task(id: copied) { await copiedFor($copied) }
                ShareLink(item: url) { Label(say(.shareSend), systemImage: "square.and.arrow.up") }
            }
            Section { Button(say(.shareDone), action: onDone) }
        }
    }
}


/// The form of a new link or of one being edited, as core's `LinkForm` has it (its rules, the web's `FolderShareDialog`): its
/// role, an optional label, a password and an end, the end a local day. Each change goes to `change`; `onSave` sends it,
/// `problem` says why it did not go through.
private struct NewLinkForm: View {
    let form: LinkForm
    let words: ShareWords
    let saving: Bool
    let problem: String?
    let submit: Msg
    let change: (@escaping (LinkForm) -> LinkForm) -> Void
    let onCancel: () -> Void
    let onSave: () -> Void

    private var passwordMin: Int { Int(LinkForm.companion.PASSWORD_MIN) }

    var body: some View {
        let f = form
        let rules = f.rules
        Form {
            Section {
                if !f.roles.isEmpty {
                    Picker(say(.shareRole), selection: Binding(get: { f.role }, set: { r in change { $0.withRole(role: r) } })) {
                        ForEach(f.roles, id: \.self) { r in Text(say(words.roleWord(role: r))).tag(r) }
                    }
                    .pickerStyle(.segmented)
                }
                Text(say(words.hint(role: f.role))).font(.caption).foregroundStyle(Role.muted)
                if f.namesIt {
                    TextField(say(f.nameLabel), text: Binding(get: { f.label }, set: { v in change { $0.withLabel(label: v) } }))
                }
            }
            Section {
                Toggle(say(.sharePasswordSection), isOn: Binding(get: { f.withPassword }, set: { on in change { $0.withPasswordOn(on: on) } }))
                    .disabled(!f.passwordOptional)
                if f.withPassword {
                    SecureField(f.hadPassword ? say(.sharePasswordKeep) : say(.signinPassword),
                                text: Binding(get: { f.password }, set: { v in change { $0.withPassword(password: v) } }))
                    Text(say(.sharePasswordShort, passwordMin)).font(.caption)
                        .foregroundStyle(f.passwordTooShort ? Role.danger : Role.muted)
                }
            } footer: {
                if rules.passwordRequired { Text(say(.sharePasswordRequired)) }
            }
            Section {
                Toggle(say(.shareExpirySection), isOn: Binding(get: { f.withEnd }, set: { on in change { $0.withEndOn(on: on) } }))
                    .disabled(!f.endOptional)
                if f.withEnd {
                    DatePicker(
                        say(.shareExpiresOn),
                        selection: Binding(get: { Self.date(f.endDay) }, set: { d in change { $0.withEndDay(day: Self.epochDay(d)) } }),
                        in: Self.date(f.today)...(f.lastDay.map { Self.date($0.int64Value) } ?? .distantFuture),
                        displayedComponents: .date
                    )
                }
            } footer: {
                if let maxDays = rules.maxDays { Text(say(.shareExpiryByRule, Int(truncating: maxDays))) }
                else { Text(say(.shareExpiryHint)) }
            }
            if let problem { Section { Text(problem).foregroundStyle(Role.danger) } }
            Section {
                Button(action: onSave) {
                    HStack { Spacer(); if saving { ProgressView() } else { Text(say(submit)).fontWeight(.semibold) }; Spacer() }
                }
                .disabled(saving || !f.canSave)
                Button(say(.signinCancel), action: onCancel).disabled(saving)
            }
        }
    }

    /// The picker's date as core's day: the local calendar day it shows, counted since 1970-01-01.
    private static func epochDay(_ date: Date) -> Int64 {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        return Int64(utc.date(from: c)!.timeIntervalSince1970 / 86_400)
    }

    /// The local start of core's day, for the picker.
    private static func date(_ day: Int64) -> Date {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let c = utc.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: TimeInterval(day) * 86_400))
        return Calendar.current.date(from: c)!
    }
}

/// The local day of a server instant (ISO 8601), as the web writes a day (core's `Shown.dayOf`).
private func day(_ instant: String) -> String { shownDayOf(instant) }

/// The system's pasteboard, on iOS and macOS alike.
enum Clipboard {
    static func copy(_ text: String) {
        #if os(iOS)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }
}

/// "Copied" shown for the core's while (`Notice.COPIED_MS`, the web's copy note), then the copy offered again.
@MainActor private func copiedFor(_ copied: Binding<Bool>) async {
    guard copied.wrappedValue, (try? await waitCore(Notice.companion.COPIED_MS)) != nil else { return }
    copied.wrappedValue = false
}
