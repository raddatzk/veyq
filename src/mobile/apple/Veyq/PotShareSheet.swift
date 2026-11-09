import SwiftUI
import VeyqCore

/// A Pot's guest links (the web's `PotShareDialog`, core's `FolderShareDialog` with Expenses' words): the links on it, newest
/// first, each copied from here; "New link" mints one — view only, or view and contribute where the tenant allows it, an
/// optional label only the member sees, a password and an end where the member wants them or the tenant's rules require
/// them — and shows it once to copy or share. Switching a link off, renewing and editing come with the subtree's re-key.
struct PotShareSheet: View {
    let model: AppModel
    let ref: ObjectRef
    @Environment(\.dismiss) private var dismiss
    @State private var links: [ShareLinks.Link]?
    @State private var failed = false
    @State private var creating = false
    @State private var fresh: String?

    var body: some View {
        NavigationStack {
            Group {
                if let fresh {
                    FreshLinkView(url: fresh) { self.fresh = nil }
                } else if creating {
                    NewLinkForm(rules: model.linkRules(), onCancel: { creating = false }) { role, label, password, expiresAt in
                        let minted = await model.mintLink(ref, role: role, label: label, password: password, expiresAt: expiresAt)
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
            .navigationTitle("Share this Pot")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .task { await load() }
    }

    private var list: some View {
        List {
            Section {
                Text("Create a link anyone can open in a browser — no account, no install. It shows only this Pot, and you can switch it off anytime.")
                    .font(.subheadline).foregroundStyle(Role.muted)
                if model.linkRules().on {
                    Button("New link", systemImage: "plus") { creating = true }
                }
            }
            Section {
                if failed {
                    Text("Your links could not be loaded.").foregroundStyle(Role.danger)
                } else if let links {
                    ForEach(links, id: \.shareId) { LinkRow(link: $0) }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
        }
    }

    private func load() async {
        if let read = await model.shareLinks(ref) { links = read; failed = false } else { failed = true }
    }

    private static func problem(_ outcome: ShareLinks.Outcome) -> String {
        switch outcome {
        case .passwordRequired, .expiryRejected:
            String(localized: "This space now requires a password or an end date on every link. Add what is missing and try again.")
        case .notAllowed: String(localized: "Links are switched off here.")
        case .scopeDeleting: String(localized: "This space is being deleted.")
        case .busy: String(localized: "The space’s new lock is still going in. Try again in a moment.")
        default: String(localized: "That didn’t work — please try again.")
        }
    }
}

/// One link: its label (else its role), when it was made and ends, its badges, and its copy where it can be rebuilt.
private struct LinkRow: View {
    let link: ShareLinks.Link
    @State private var copied = false

    private var roleWord: String {
        link.role == ShareLinks.shared.READ ? String(localized: "View only") : String(localized: "View + contribute")
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
    let onCancel: () -> Void
    let onCreate: (String, String, String, String?) async -> String?
    @State private var role = ShareLinks.shared.READ
    @State private var label = ""
    @State private var withPassword = false
    @State private var password = ""
    @State private var withEnd = false
    @State private var end = Date()
    @State private var problem: String?
    @State private var saving = false

    private var maxDays: Int? { rules.maxDays.map { Int(truncating: $0) } }
    private var lastDay: Date? { maxDays.map { Calendar.current.date(byAdding: .day, value: $0 - 1, to: Calendar.current.startOfDay(for: Date()))! } }

    var body: some View {
        Form {
            Section {
                Picker("Role", selection: $role) {
                    Text("View only").tag(ShareLinks.shared.READ)
                    if rules.contribute { Text("View + contribute").tag(ShareLinks.shared.READ_WRITE) }
                }
                .pickerStyle(.segmented)
                Text(role == ShareLinks.shared.READ
                     ? "Anyone with the link sees the entries, balances and overview, read-only."
                     : "Anyone with the link can also add, change and delete entries, and add and rename people.")
                    .font(.caption).foregroundStyle(Role.muted)
                TextField("Optional label (only you see it)", text: $label)
            }
            Section {
                Toggle("Password protection", isOn: $withPassword).disabled(rules.passwordRequired)
                if withPassword {
                    SecureField("Password", text: $password)
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
                    Task {
                        problem = await onCreate(role, label, withPassword ? password : "", expiresAt)
                        saving = false
                    }
                } label: {
                    HStack { Spacer(); if saving { ProgressView() } else { Text("Create link").fontWeight(.semibold) }; Spacer() }
                }
                .disabled(saving || (withPassword && password.trimmingCharacters(in: .whitespaces).count < passwordMin))
                Button("Cancel", action: onCancel).disabled(saving)
            }
        }
        .onAppear {
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
