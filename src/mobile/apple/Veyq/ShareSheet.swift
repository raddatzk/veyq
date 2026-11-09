import SwiftUI
import VeyqCore

/// The sentences a node's share sheet says in its kind's words (the web's `ShareDialogCopy`): a folder's are core's
/// (`share.folder.*`), a Pot's Expenses' (`expenses.share.*`). The rest of the sheet is the same for every kind.
struct ShareWords {
    let title: String
    let intro: String
    let contributor: String
    let viewerHint: String
    let contributorHint: String
    let deleteConfirm: String

    /// The words of `kind`'s share sheet: a Pot's, else core's folder words (the web's default `ShareDialogCopy`).
    static func of(_ kind: String) -> ShareWords {
        if kind == DepotFiles.shared.DROPBOX {
            let hint = String(localized: "Creates a drop box with this name here. Anyone with the link can send you files: each person gets a folder of their own, and nobody sees the drop box or what others sent. You manage its links on the drop box.")
            return ShareWords(
                title: String(localized: "Drop box links"),
                intro: String(localized: "Anyone with a link can add files. Nobody with it sees the drop box or what others added."),
                contributor: String(localized: "Add files only"),
                viewerHint: hint,
                contributorHint: hint,
                deleteConfirm: String(localized: "Delete this link for good? Nobody can send files with it any more. What guests already sent stays in the drop box.")
            )
        }
        if kind == DepotFiles.shared.FILE {
            return ShareWords(
                title: String(localized: "Share this file"),
                intro: String(localized: "Create a link anyone can open in a browser — no account, no install. It shows only this one file, and you can switch it off anytime."),
                contributor: String(localized: "View + contribute"),
                viewerHint: String(localized: "Anyone with the link can see this file and download it, read-only."),
                contributorHint: String(localized: "Anyone with the link can see and download this file and replace its content — the old content cannot be restored: there is no version history."),
                deleteConfirm: String(localized: "Delete this link for good? It can never be switched on or renewed again. Content guests replaced stays in this file.")
            )
        }
        if kind == PotSession.companion.KIND {
            return ShareWords(
                title: String(localized: "Share this Pot"),
                intro: String(localized: "Create a link anyone can open in a browser — no account, no install. It shows only this Pot, and you can switch it off anytime."),
                contributor: String(localized: "View + contribute"),
                viewerHint: String(localized: "Anyone with the link sees the entries, balances and overview, read-only."),
                contributorHint: String(localized: "Anyone with the link can also add, change and delete entries, and add and rename people."),
                deleteConfirm: String(localized: "Delete this link for good? It can never be switched on or renewed again. Entries guests wrote stay in the Pot.")
            )
        }
        return ShareWords(
            title: String(localized: "Share this folder"),
            intro: String(localized: "Create a link anyone can open in a browser — no account, no install. It shows this folder and everything in it, and you can switch it off anytime."),
            contributor: String(localized: "View + add files"),
            viewerHint: String(localized: "Anyone with the link can look at this folder and everything in it, read-only."),
            contributorHint: String(localized: "Anyone with the link can look, add folders and content, move things inside this folder, and upload files — also over any file already in it, yours included. A replaced file cannot be brought back: there is no version history."),
            deleteConfirm: String(localized: "Delete this link for good? It can never be switched on or renewed again. What guests added stays in this folder.")
        )
    }
}

/// A node's guest links (core's `FolderShareDialog`, a Pot's with Expenses' words, `ShareWords`): the links on it, newest
/// first, each copied from here; "New link" mints one — view only, or view and contribute where the tenant allows it, an
/// optional label only the member sees, a password and an end where the member wants them or the tenant's rules require
/// them — and shows it once to copy or share. Switching a link off re-keys what is below it.
struct ShareSheet: View {
    let model: AppModel
    let ref: NodeAt
    private var words: ShareWords { ShareWords.of(ref.kind) }
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
                        if role == ShareLinks.shared.WRITE_ONLY && !dropBox {
                            // "Add files only" on a folder: a drop box named so, here, with its first link (`createDropBoxWithLink`)
                            let made = await model.createDropBox(ref, name: label, password: password ?? "", expiresAt: expiresAt)
                            if made.outcome == .created {
                                creating = false
                                fresh = made.url
                                return nil
                            }
                            return Notices.shared.dropBoxRefused(outcome: made.outcome).map { say($0) }
                        }
                        let minted = await model.mintLink(ref, role: dropBox ? ShareLinks.shared.WRITE_ONLY : role, label: dropBox ? "" : label, password: password ?? "", expiresAt: expiresAt)
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
            .navigationTitle(words.title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .task { await load() }
        .alert("Renew link", isPresented: Binding(get: { renewing != nil }, set: { if !$0 { renewing = nil } }), presenting: renewing) { link in
            Button("Renew link") { Task { await renew(link) } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("This makes a new link and deletes the old one. Hand out the new link.")
        }
        .alert("Delete link", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { link in
            Button("Delete link", role: .destructive) { Task { await delete(link) } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text(words.deleteConfirm)
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
        say(Notices.shared.linkChanged(outcome: outcome) ?? Notices.shared.of(msg: .shareFailed))
    }

    private var list: some View {
        List {
            Section {
                Text(words.intro)
                    .font(.subheadline).foregroundStyle(Role.muted)
                if model.linkRules().on {
                    Button("New link", systemImage: "plus") { creating = true }
                }
                if let notice { Text(notice).font(.caption).foregroundStyle(Role.muted) }
            }
            Section {
                if failed {
                    Text("Your links could not be loaded.").foregroundStyle(Role.danger)
                } else if let links {
                    ForEach(links, id: \.shareId) { link in
                        // the web's rules: edit an own open link whose copy opens, renew an own or switched-off one, switch
                        // off an own one that is on, switch one on again that is off and safe to (any member: it becomes
                        // theirs), delete a switched-off one the server lets go (`FolderShareDialog`, `DropBoxShareDialog`)
                        let canEdit = !dropBox && link.mine && link.enabled && link.url != nil
                        let canRenew = !dropBox && (link.mine || !link.enabled)
                        let canSwitchOff = link.enabled && link.mine
                        let canDelete = !link.enabled && link.deletable
                        let canReactivate = !link.enabled && !link.reissueRequired
                        LinkRow(link: link, words: words, busy: busy == link.shareId)
                            // Apple's HIG: a row's actions as swipe actions and its context menu, as Mail's
                            .swipeActions(edge: .trailing) {
                                if canSwitchOff {
                                    Button("Revoke", systemImage: "nosign", role: .destructive) { Task { await switchOff(link) } }
                                }
                                if canDelete {
                                    Button("Delete link", systemImage: "trash", role: .destructive) { deleting = link }
                                }
                            }
                            .swipeActions(edge: .leading) {
                                if canReactivate {
                                    Button("Switch On Again", systemImage: "power") { Task { await reactivate(link) } }.tint(Role.accent)
                                }
                                if canRenew { Button("Renew link", systemImage: "arrow.clockwise") { renewing = link }.tint(Role.accent) }
                            }
                            .contextMenu {
                                // an open link goes to another app through the system's share sheet, first (as Safari's links)
                                if link.enabled, let url = link.url.flatMap(URL.init(string:)) {
                                    ShareLink(item: url) { Label("Share…", systemImage: "square.and.arrow.up") }
                                }
                                if canEdit { Button("Edit", systemImage: "pencil") { editing = link } }
                                if canReactivate { Button("Switch On Again", systemImage: "power") { Task { await reactivate(link) } } }
                                if canRenew { Button("Renew link", systemImage: "arrow.clockwise") { renewing = link } }
                                if canSwitchOff {
                                    Button("Revoke", systemImage: "nosign", role: .destructive) { Task { await switchOff(link) } }
                                }
                                if canDelete { Button("Delete link", systemImage: "trash", role: .destructive) { deleting = link } }
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
        switch closed {
        case .closed: notice = nil
        case .closedRekeyPending:
            notice = String(localized: "The link is switched off. What is below it gets a new lock, so the old link opens nothing that comes later either.")
        default: notice = String(localized: "That didn’t work — please try again.")
        }
        await load()
    }

    private func load() async {
        if let read = await model.shareLinks(ref) { links = read; failed = false } else { failed = true }
    }

    private static func problem(_ outcome: ShareLinks.Outcome) -> String {
        say(Notices.shared.linkRefused(outcome: outcome) ?? Notices.shared.of(msg: .shareFailed))
    }
}

/// One link: its label (else its role), when it was made and ends, its badges, and its copy where it can be rebuilt.
private struct LinkRow: View {
    let link: ShareLinks.Link
    let words: ShareWords
    let busy: Bool
    @State private var copied = false

    private var roleWord: String {
        switch link.role {
        case ShareLinks.shared.READ: String(localized: "View only")
        case ShareLinks.shared.WRITE_ONLY: String(localized: "Add files only")
        default: words.contributor
        }
    }

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(link.label.isEmpty ? roleWord : link.label).fontWeight(.semibold)
                Text([String(localized: "Created \(day(link.createdAt))"), link.expiresAt.map { String(localized: "Expires \(day($0))") }]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(Role.muted)
                let badges = [
                    link.label.isEmpty ? nil : roleWord,
                    link.passwordRequired ? String(localized: "Password") : nil,
                    link.enabled ? nil : String(localized: "Switched off"),
                    link.mine ? nil : String(localized: "By another member"),
                    link.stopped || link.reissueRequired ? String(localized: "Guests can’t open it") : nil,
                ].compactMap { $0 }
                if !badges.isEmpty { Text(badges.joined(separator: " · ")).font(.caption).foregroundStyle(Role.accent) }
            }
            Spacer()
            if let url = link.url, link.enabled {
                Button { Clipboard.copy(url); copied = true } label: {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Copy link")
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
                    NewLinkForm(rules: model.linkRules(), words: ShareWords.of("FOLDER"), uploadOnly: true, onCancel: { dismiss() }) { _, label, password, expiresAt in
                        let made = await model.createDropBox(scopeId: scopeId, parentId: nil, name: label, password: password ?? "", expiresAt: expiresAt)
                        if made.outcome == .created {
                            fresh = made.url
                            return nil
                        }
                        return Notices.shared.dropBoxRefused(outcome: made.outcome).map { say($0) }
                    }
                }
            }
            .navigationTitle("Add files only")
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
            Section("Your link is ready — copy it:") {
                Text(url).font(.footnote.monospaced()).textSelection(.enabled)
                Button(copied ? "Copied" : "Copy link", systemImage: copied ? "checkmark" : "doc.on.doc") { Clipboard.copy(url); copied = true }
                ShareLink(item: url) { Label("Share", systemImage: "square.and.arrow.up") }
            }
            Section { Button("Done", action: onDone) }
        }
    }
}

/// A link's password: at least this many characters (`FolderShareDialog`'s `PASSWORD_MIN`).
private let passwordMin = 10

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
    @State private var role = ShareLinks.shared.READ
    @State private var label = ""
    @State private var withPassword = false
    @State private var password = ""
    @State private var withEnd = false
    @State private var end = Date()
    @State private var problem: String?
    @State private var saving = false

    private var hadPassword: Bool { editing?.passwordRequired == true }
    private var maxDays: Int? { rules.maxDays.map { Int(truncating: $0) } }

    private static func parse(_ instant: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.date(from: instant) ?? ISO8601DateFormatter().date(from: instant)
    }
    private var lastDay: Date? { maxDays.map { Calendar.current.date(byAdding: .day, value: $0 - 1, to: Calendar.current.startOfDay(for: Date()))! } }

    var body: some View {
        Form {
            Section {
                if !writeOnly && !uploadOnly {
                    Picker("Role", selection: $role) {
                        Text("View only").tag(ShareLinks.shared.READ)
                        if rules.contribute { Text(words.contributor).tag(ShareLinks.shared.READ_WRITE) }
                        if uploadOffered { Text("Add files only").tag(ShareLinks.shared.WRITE_ONLY) }
                    }
                    .pickerStyle(.segmented)
                }
                Text(role == ShareLinks.shared.READ ? words.viewerHint
                     : role == ShareLinks.shared.WRITE_ONLY ? String(localized: "Creates a drop box with this name here. Anyone with the link can send you files: each person gets a folder of their own, and nobody sees the drop box or what others sent. You manage its links on the drop box.")
                     : words.contributorHint)
                    .font(.caption).foregroundStyle(Role.muted)
                // a drop box's own links have no name; the one "Add files only" makes needs one — its guests see it
                if !writeOnly {
                    TextField(role == ShareLinks.shared.WRITE_ONLY ? "Name — guests with the link see it" : "Optional label (only you see it)", text: $label)
                }
            }
            Section {
                Toggle("Password protection", isOn: $withPassword).disabled(rules.passwordRequired)
                if withPassword {
                    SecureField(hadPassword ? "•••••••• (unchanged)" : "Password", text: $password)
                    Text("At least \(passwordMin) characters.").font(.caption)
                        .foregroundStyle(!password.isEmpty && password.trimmingCharacters(in: .whitespaces).count < passwordMin ? Role.danger : Role.muted)
                }
            } footer: {
                if rules.passwordRequired { Text("This space requires a password on every link.") }
            }
            Section {
                Toggle("Expiry date", isOn: $withEnd).disabled(maxDays != nil)
                if withEnd {
                    DatePicker("Expires on", selection: $end, in: Calendar.current.startOfDay(for: Date())...(lastDay ?? .distantFuture), displayedComponents: .date)
                }
            } footer: {
                if let maxDays { Text("This space requires every link to end within \(maxDays) days of the day it is made.") }
                else { Text("The link stops working after this date.") }
            }
            if let problem { Section { Text(problem).foregroundStyle(Role.danger) } }
            Section {
                Button {
                    saving = true
                    problem = nil
                    // the end of the picked local day (`FolderShareDialog`: 23:59:59.999 there)
                    let expiresAt: String? = withEnd ? Self.endOfDay(end) : nil
                    let pw: String? = if withPassword && password.isEmpty && hadPassword { nil }
                        else if withPassword { password }
                        else if hadPassword { "" }
                        else if editing != nil { nil }
                        else { "" }
                    Task {
                        problem = await onCreate(role, label, pw, expiresAt)
                        saving = false
                    }
                } label: {
                    HStack { Spacer(); if saving { ProgressView() } else { Text(editing == nil ? "Create link" : "Save").fontWeight(.semibold) }; Spacer() }
                }
                // an edit keeps the password where the field stays blank
                .disabled(saving || (withPassword && password.trimmingCharacters(in: .whitespaces).count < passwordMin && !(hadPassword && password.isEmpty))
                          || (role == ShareLinks.shared.WRITE_ONLY && !writeOnly && label.trimmingCharacters(in: .whitespaces).isEmpty))
                Button("Cancel", action: onCancel).disabled(saving)
            }
        }
        .onAppear {
            if writeOnly || uploadOnly { role = ShareLinks.shared.WRITE_ONLY }
            if let editing {
                role = editing.role
                label = editing.label
                withPassword = rules.passwordRequired || editing.passwordRequired
                if let e = editing.expiresAt, let d = Self.parse(e) { withEnd = true; end = d; return }
                withEnd = maxDays != nil
                end = lastDay ?? Calendar.current.date(byAdding: .day, value: 30, to: Date())!
                return
            }
            withPassword = rules.passwordRequired
            withEnd = maxDays != nil
            end = lastDay ?? Calendar.current.date(byAdding: .day, value: 30, to: Date())!
        }
    }

    private static func endOfDay(_ day: Date) -> String {
        let next = Calendar.current.date(byAdding: .day, value: 1, to: Calendar.current.startOfDay(for: day))!
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: next.addingTimeInterval(-0.001))
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
