import SwiftUI
import VeyqCore

/// The sentences a node's share sheet says in its kind's words (the web's `ShareDialogCopy`): a folder's are core's
/// (`share.folder.*`), a Pot's Expenses' (`expenses.share.*`). The rest of the sheet is the same for every kind.
/// A node's guest links (core's `FolderShareDialog`, a Pot's with Expenses' words, `ShareWords`): the links on it, newest
/// first, each copied from here; "New link" mints one — view only, or view and contribute where the tenant allows it, an
/// optional label only the member sees, a password and an end where the member wants them or the tenant's rules require
/// them — and shows it once to copy or share. Switching a link off re-keys what is below it.
struct ShareSheet: View {
    let model: AppModel
    let ref: NodeAt
    private var words: ShareWords { ShareWords.companion.of(kind: ref.kind) }
    /// A drop box's own links: WRITE_ONLY only, no name of their own; a folder offers "Add files only", which makes one.
    private var dropBox: Bool { ref.kind == DepotFiles.shared.DROPBOX }
    @Environment(\.dismiss) private var dismiss
    @State private var links: [ShareLinks.Link]?
    @State private var failed = false
    @State private var creating = false
    @State private var fresh: String?
    @State private var busy: String?
    @State private var notice: String?
    @State private var editing: ShareLinks.Link?
    @State private var renewing: ShareLinks.Link?
    @State private var deleting: ShareLinks.Link?

    var body: some View {
        NavigationStack {
            Group {
                if let fresh {
                    FreshLinkView(url: fresh) { self.fresh = nil }
                } else if let link = editing {
                    NewLinkForm(rules: model.linkRules(), words: words, editing: link, writeOnly: dropBox, onCancel: { editing = nil }) { role, label, password, expiresAt in
                        let r = await model.editLink(ref, shareId: link.shareId, edit: ShareLinkChanges.Edit(label: label, password: password, expiresAt: expiresAt, role: role))
                        guard r.outcome == .done else { return Self.changed(r.outcome) }
                        editing = nil
                        await load()
                        return nil
                    }
                } else if creating {
                    NewLinkForm(
                        rules: model.linkRules(), words: words, writeOnly: dropBox,
                        uploadOffered: ref.kind == "FOLDER" && model.linkRules().dropBox, onCancel: { creating = false }
                    ) { role, label, password, expiresAt in
                        let mint = LinkRow.companion.mint(role: role, label: label, onDropBox: dropBox)
                        if mint.makesDropBox {
                            // "Add files only" on a folder: a drop box named so, here, with its first link (`createDropBoxWithLink`)
                            let made = await model.createDropBox(ref, name: mint.label, password: password ?? "", expiresAt: expiresAt)
                            if made.outcome == .created {
                                creating = false
                                fresh = made.url
                                return nil
                            }
                            return Notices.shared.dropBoxRefused(outcome: made.outcome).map { say(notice: $0) }
                        }
                        let minted = await model.mintLink(ref, role: mint.role, label: mint.label, password: password ?? "", expiresAt: expiresAt)
                        guard minted.outcome == .created, let url = minted.url else { return Self.problem(minted.outcome) }
                        creating = false
                        fresh = url
                        await load()
                        return nil
                    }
                } else {
                    list
                }
            }
            .navigationTitle(say(words.title))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(say(.shareDone)) { dismiss() } } }
        }
        .task { await load() }
        .alert(say(.shareRenew), isPresented: Binding(get: { renewing != nil }, set: { if !$0 { renewing = nil } }), presenting: renewing) { link in
            Button(say(.shareRenew)) { Task { await renew(link) } }
            Button(say(.signinCancel), role: .cancel) {}
        } message: { _ in
            Text(say(.shareRenewConfirm))
        }
        .alert(say(.shareDelete), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { link in
            Button(say(.shareDelete), role: .destructive) { Task { await delete(link) } }
            Button(say(.signinCancel), role: .cancel) {}
        } message: { _ in
            Text(say(words.deleteConfirm))
        }
    }

    private func renew(_ link: ShareLinks.Link) async {
        busy = link.shareId
        let r = await model.renewLink(ref, shareId: link.shareId)
        busy = nil
        notice = r.outcome == .done ? nil : Self.changed(r.outcome)
        if let url = r.url { fresh = url }
        await load()
    }

    private func reactivate(_ link: ShareLinks.Link) async {
        busy = link.shareId
        let r = await model.reactivateLink(ref, shareId: link.shareId)
        busy = nil
        notice = r.outcome == .done ? nil : Self.changed(r.outcome)
        await load()
    }

    private func delete(_ link: ShareLinks.Link) async {
        busy = link.shareId
        let r = await model.deleteLink(ref, shareId: link.shareId)
        busy = nil
        notice = r.outcome == .done ? nil : Self.changed(r.outcome)
        await load()
    }

    private static func changed(_ outcome: ShareLinkChanges.Outcome) -> String {
        say(notice: Notices.shared.linkChanged(outcome: outcome) ?? Notices.shared.of(msg: .shareFailed))
    }

    private var list: some View {
        List {
            Section {
                Text(say(words.intro))
                    .font(.subheadline).foregroundStyle(Role.muted)
                if model.linkRules().on {
                    Button(say(.shareNew), systemImage: "plus") { creating = true }
                }
                if let notice { Text(notice).font(.caption).foregroundStyle(Role.muted) }
            }
            Section {
                if failed {
                    Text(say(.shareLoadFailed)).foregroundStyle(Role.danger)
                } else if let links {
                    ForEach(links, id: \.shareId) { link in
                        // what this member may do with it is core's (`LinkRow`, the web's dialogs' rules)
                        let row = LinkRow(link: link, dropBox: dropBox, words: words)
                        let canEdit = row.canEdit
                        let canRenew = row.canRenew
                        let canSwitchOff = row.canSwitchOff
                        let canDelete = row.canDelete
                        let canReactivate = row.canReactivate
                        LinkRowView(row: row, busy: busy == link.shareId)
                            // Apple's HIG: a row's actions as swipe actions and its context menu, as Mail's
                            .swipeActions(edge: .trailing) {
                                if canSwitchOff {
                                    Button(say(.shareSwitchOff), systemImage: "nosign", role: .destructive) { Task { await switchOff(link) } }
                                }
                                if canDelete {
                                    Button(say(.shareDelete), systemImage: "trash", role: .destructive) { deleting = link }
                                }
                            }
                            .swipeActions(edge: .leading) {
                                if canReactivate {
                                    Button(say(.shareReactivate), systemImage: "power") { Task { await reactivate(link) } }.tint(Role.accent)
                                }
                                if canRenew { Button(say(.shareRenew), systemImage: "arrow.clockwise") { renewing = link }.tint(Role.accent) }
                            }
                            .contextMenu {
                                // an open link goes to another app through the system's share sheet, first (as Safari's links)
                                if row.canCopy, let url = link.url.flatMap(URL.init(string:)) {
                                    ShareLink(item: url) { Label(say(.shareLinkShare), systemImage: "square.and.arrow.up") }
                                }
                                if canEdit { Button(say(.shareEdit), systemImage: "pencil") { editing = link } }
                                if canReactivate { Button(say(.shareReactivate), systemImage: "power") { Task { await reactivate(link) } } }
                                if canRenew { Button(say(.shareRenew), systemImage: "arrow.clockwise") { renewing = link } }
                                if canSwitchOff {
                                    Button(say(.shareSwitchOff), systemImage: "nosign", role: .destructive) { Task { await switchOff(link) } }
                                }
                                if canDelete { Button(say(.shareDelete), systemImage: "trash", role: .destructive) { deleting = link } }
                            }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
        }
    }

    /// Switched off at once, as the web does: the Pot gets a new key, so the link opens nothing any more.
    private func switchOff(_ link: ShareLinks.Link) async {
        busy = link.shareId
        let closed = await model.switchOffLink(ref, shareId: link.shareId)
        busy = nil
        notice = Notices.shared.linkClosed(closed: closed).map { say(notice: $0) }
        await load()
    }

    private func load() async {
        if let read = await model.shareLinks(ref) { links = read; failed = false } else { failed = true }
    }

    private static func problem(_ outcome: ShareLinks.Outcome) -> String {
        say(notice: Notices.shared.linkRefused(outcome: outcome) ?? Notices.shared.of(msg: .shareFailed))
    }
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

    var body: some View {
        NavigationStack {
            Group {
                if let fresh {
                    FreshLinkView(url: fresh) { dismiss() }
                } else {
                    NewLinkForm(rules: model.linkRules(), words: ShareWords.companion.of(kind: "FOLDER"), uploadOnly: true, onCancel: { dismiss() }) { _, label, password, expiresAt in
                        let made = await model.createDropBox(scopeId: scopeId, parentId: nil, name: label, password: password ?? "", expiresAt: expiresAt)
                        if made.outcome == .created {
                            fresh = made.url
                            return nil
                        }
                        return Notices.shared.dropBoxRefused(outcome: made.outcome).map { say(notice: $0) }
                    }
                }
            }
            .navigationTitle(say(.shareRoleUploader))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
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


/// The form of a new link: its role, an optional label, a password and an end. A rule of the tenant turns its switch on and
/// keeps it on; the end is a day no later than the rule's last, sent as the end of that local day. `onCreate` answers why
/// not, or nil once minted.
private struct NewLinkForm: View {
    let rules: ShareLinks.Rules
    let words: ShareWords
    /// The link an edit changes (its settings fill the form; a password left blank stays as it is); nil mints a new one.
    var editing: ShareLinks.Link? = nil
    /// A drop box's own link: its role is fixed and it has no name of its own.
    var writeOnly = false
    /// A folder's third role, "Add files only" (Depot's `folderUploadShare`): it makes a drop box with the name typed.
    var uploadOffered = false
    /// That role alone (the space's top level, where no link roots): no role to pick.
    var uploadOnly = false
    let onCancel: () -> Void
    /// role, label, password (nil: untouched, blank: none), end; answers why not, or nil once done.
    let onCreate: (String, String, String?, String?) async -> String?
    /// Core's form (`LinkForm`: its rules, the web's `FolderShareDialog`), a new one each change.
    @State private var form: LinkForm?
    @State private var problem: String?
    @State private var saving = false

    private var passwordMin: Int { Int(LinkForm.companion.PASSWORD_MIN) }

    var body: some View {
        if let form { content(form) } else {
            ProgressView().onAppear {
                let endDay = editing?.expiresAt.flatMap(Self.parse).map(Self.epochDay)
                form = LinkForm.companion.start(
                    rules: rules, editing: editing, writeOnly: writeOnly, uploadOffered: uploadOffered, uploadOnly: uploadOnly,
                    today: Self.epochDay(Date()), editingEndDay: endDay.map { KotlinLong(value: $0) }
                )
            }
        }
    }

    @ViewBuilder private func content(_ f: LinkForm) -> some View {
        Form {
            Section {
                if !f.roles.isEmpty {
                    Picker(say(.shareRole), selection: Binding(get: { f.role }, set: { form = f.withRole(role: $0) })) {
                        ForEach(f.roles, id: \.self) { r in Text(say(words.roleWord(role: r))).tag(r) }
                    }
                    .pickerStyle(.segmented)
                }
                Text(say(words.hint(role: f.role))).font(.caption).foregroundStyle(Role.muted)
                if f.namesIt {
                    TextField(say(f.nameLabel), text: Binding(get: { f.label }, set: { form = f.withLabel(label: $0) }))
                }
            }
            Section {
                Toggle(say(.sharePasswordSection), isOn: Binding(get: { f.withPassword }, set: { form = f.withPasswordOn(on: $0) }))
                    .disabled(!f.passwordOptional)
                if f.withPassword {
                    SecureField(f.hadPassword ? say(.sharePasswordKeep) : say(.signinPassword),
                                text: Binding(get: { f.password }, set: { form = f.withPassword(password: $0) }))
                    Text(say(.sharePasswordShort, passwordMin)).font(.caption)
                        .foregroundStyle(f.passwordTooShort ? Role.danger : Role.muted)
                }
            } footer: {
                if rules.passwordRequired { Text(say(.sharePasswordRequired)) }
            }
            Section {
                Toggle(say(.shareExpirySection), isOn: Binding(get: { f.withEnd }, set: { form = f.withEndOn(on: $0) }))
                    .disabled(!f.endOptional)
                if f.withEnd {
                    DatePicker(
                        say(.shareExpiresOn),
                        selection: Binding(get: { Self.date(f.endDay) }, set: { form = f.withEndDay(day: Self.epochDay($0)) }),
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
                Button {
                    saving = true
                    problem = nil
                    let sent = f
                    Task {
                        problem = await onCreate(sent.role, sent.label, sent.submittedPassword, sent.submittedEndDay.map { Self.endOfDay($0.int64Value) })
                        saving = false
                    }
                } label: {
                    HStack { Spacer(); if saving { ProgressView() } else { Text(editing == nil ? say(.shareCreate) : say(.entrySave)).fontWeight(.semibold) }; Spacer() }
                }
                .disabled(saving || !f.canSave)
                Button(say(.signinCancel), action: onCancel).disabled(saving)
            }
        }
    }

    private static func parse(_ instant: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.date(from: instant) ?? ISO8601DateFormatter().date(from: instant)
    }

    /// A local calendar day as core counts it: days since 1970-01-01.
    private static func epochDay(_ date: Date) -> Int64 {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        return Int64(utc.date(from: c)!.timeIntervalSince1970 / 86_400)
    }

    /// The local start of core's day.
    private static func date(_ day: Int64) -> Date {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let c = utc.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: TimeInterval(day) * 86_400))
        return Calendar.current.date(from: c)!
    }

    /// The end of a local day as an instant (`FolderShareDialog`: 23:59:59.999 there).
    private static func endOfDay(_ day: Int64) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: date(day + 1).addingTimeInterval(-0.001))
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
