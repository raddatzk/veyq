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
                            submit: sheet.submit, change: { sheet.form(change: $0) }, onCancel: { sheet.cancelForm() },
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
        // the question is the sheet's state: a binding that cancelled on dismissal would run before the button's action (Phase
        // 1's purge), so only Cancel cancels
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
                            // Apple's HIG: a row's actions as swipe actions and its context menu, as Mail's
                            .swipeActions(edge: .trailing) {
                                if row.canSwitchOff {
                                    Button(say(.shareSwitchOff), systemImage: "nosign", role: .destructive) { Task { _ = try? await sheet.switchOff(shareId: id) } }
                                }
                                if row.canDelete {
                                    Button(say(.shareDelete), systemImage: "trash", role: .destructive) { sheet.askDelete(shareId: id) }
                                }
                            }
                            .swipeActions(edge: .leading) {
                                if row.canReactivate {
                                    Button(say(.shareReactivate), systemImage: "power") { Task { _ = try? await sheet.reactivate(shareId: id) } }.tint(Role.accent)
                                }
                                if row.canRenew { Button(say(.shareRenew), systemImage: "arrow.clockwise") { sheet.askRenew(shareId: id) }.tint(Role.accent) }
                            }
                            .contextMenu {
                                // an open link goes to another app through the system's share sheet, first (as Safari's links)
                                if row.canCopy, let url = row.link.url.flatMap(URL.init(string:)) {
                                    ShareLink(item: url) { Label(say(.shareLinkShare), systemImage: "square.and.arrow.up") }
                                }
                                if row.canEdit { Button(say(.shareEdit), systemImage: "pencil") { sheet.edit(shareId: id) } }
                                if row.canReactivate { Button(say(.shareReactivate), systemImage: "power") { Task { _ = try? await sheet.reactivate(shareId: id) } } }
                                if row.canRenew { Button(say(.shareRenew), systemImage: "arrow.clockwise") { sheet.askRenew(shareId: id) } }
                                if row.canSwitchOff {
                                    Button(say(.shareSwitchOff), systemImage: "nosign", role: .destructive) { Task { _ = try? await sheet.switchOff(shareId: id) } }
                                }
                                if row.canDelete { Button(say(.shareDelete), systemImage: "trash", role: .destructive) { sheet.askDelete(shareId: id) } }
                            }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
        }
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
                Text([say(.shareCreated, day(link.createdAt)), link.expiresAt.map { say(.shareExpires, day($0)) }]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(Role.muted)
                let badges = row.badges.map { say($0) }
                if !badges.isEmpty { Text(badges.joined(separator: " · ")).font(.caption).foregroundStyle(Role.accent) }
            }
            Spacer()
            if let url = link.url, row.canCopy {
                Button { Clipboard.copy(url); copied = true } label: {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(say(.shareCopy))
            }
            if busy { ProgressView() }
        }
    }
}

/// The share of a space's top level (the web's `LevelView` `share(null, …)`): no link roots there, so it offers "Add files
/// only" alone — a drop box at the top, named as typed, with its first link (Depot's `folderUploadShare`), shown once.
struct TopLevelUploadSheet: View {
    let model: AppModel
    let scopeId: String
    @Environment(\.dismiss) private var dismiss
    @State private var fresh: String?
    @State private var form: LinkForm?
    @State private var saving = false
    @State private var problem: String?

    var body: some View {
        NavigationStack {
            Group {
                if let fresh {
                    FreshLinkView(url: fresh) { dismiss() }
                } else if let form {
                    NewLinkForm(
                        form: form, words: ShareWords.companion.of(kind: "FOLDER"), saving: saving, problem: problem, submit: .shareCreate,
                        change: { self.form = $0(form) }, onCancel: { dismiss() }, onSave: { Task { await save(form) } }
                    )
                } else {
                    ProgressView().onAppear {
                        form = LinkForm.companion.start(
                            rules: model.linkRules(), editing: nil, writeOnly: false, uploadOffered: false, uploadOnly: true,
                            today: LocalDays.shared.dayOf(epochMs: Int64(Date().timeIntervalSince1970 * 1000)), editingEndDay: nil
                        )
                    }
                }
            }
            .navigationTitle(say(.shareRoleUploader))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
    }

    private func save(_ f: LinkForm) async {
        saving = true
        problem = nil
        let made = await model.createDropBox(
            scopeId: scopeId, parentId: nil, name: f.label, password: f.submittedPassword ?? "",
            expiresAt: f.submittedEndDay.map { LocalDays.shared.endOf(day: $0.int64Value) }
        )
        if made.outcome == .created { fresh = made.url } else {
            problem = say(notice: Notices.shared.dropBoxRefused(outcome: made.outcome) ?? Notices.shared.of(msg: .shareFailed))
        }
        saving = false
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

/// The day of a server instant (ISO 8601), in the device's words.
private func day(_ instant: String) -> String {
    let parse = ISO8601DateFormatter()
    parse.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    let date = parse.date(from: instant) ?? ISO8601DateFormatter().date(from: instant)
    return date.map { $0.formatted(date: .abbreviated, time: .omitted) } ?? String(instant.prefix(10))
}

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
