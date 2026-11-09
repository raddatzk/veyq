import SwiftUI
import VeyqCore

/// Core's report of a node to its server's moderators (the web's `ReportDialog.vue`, ADR 0093): what it is, why (required),
/// the reporter's good-faith confirmation (required), an optional address for the receipt and the decision with the privacy
/// notice's passage on it (GDPR Art. 13), and for one node whether it is reported whole or only by its name. On success the
/// receipt: its reference and when it was received.
struct ReportSheet: View {
    let model: AppModel
    let ref: NodeAt
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var category: String?
    @State private var reason = ""
    @State private var goodFaith = false
    @State private var contact = ""
    @State private var whole = true
    @State private var sending = false
    @State private var error: String?
    @State private var sent: NodeReport.Sent?

    /// The categories a report names, in the web's order (`REPORT_CATEGORIES`), with their words.
    private static let categories: [(String, LocalizedStringKey)] = [
        ("CHILD_SEXUAL_ABUSE", "Child sexual abuse material"),
        ("TERRORIST_CONTENT", "Terrorist content"),
        ("THREAT_TO_LIFE", "A threat to someone’s life or safety"),
        ("ILLEGAL_CONTENT", "Other illegal content"),
        ("TERMS_BREACH", "A breach of the terms"),
    ]

    var body: some View {
        NavigationStack {
            Group {
                if let sent { receipt(sent) } else { form }
            }
            .navigationTitle("Report")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(sending) } }
        }
        .interactiveDismissDisabled(sending)
    }

    private func receipt(_ sent: NodeReport.Sent) -> some View {
        Form {
            Section {
                Text("Your report was received.")
                Text("Reference \(sent.reference ?? ""), received \(Self.time(sent.receivedAt)). Keep it if you want to refer to this report.")
                    .font(.caption).foregroundStyle(Role.muted)
            }
            Section { Button("Close") { dismiss() } }
        }
    }

    private var form: some View {
        Form {
            Section {
                Text("Tell the people who run this server about something illegal or against the terms. Only its moderators can read your report. Nobody else learns of it, the person who shared it included.")
                    .font(.subheadline).foregroundStyle(Role.muted)
            }
            Section("What do you report?") {
                Picker("What do you report?", selection: $whole) {
                    Text("It and everything in it").tag(true)
                    Text("Only its name").tag(false)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                Text(whole ? "The moderators can open it and everything it holds."
                     : "The moderators can open its name and nothing else: not what it holds, not its other details.")
                    .font(.caption).foregroundStyle(Role.muted)
            }
            Section("What is it?") {
                ForEach(Self.categories, id: \.0) { token, words in
                    Button { category = token } label: {
                        HStack {
                            Text(words).foregroundStyle(Role.text)
                            Spacer()
                            if category == token { Image(systemName: "checkmark").foregroundStyle(Role.accent) }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                if category == "CHILD_SEXUAL_ABUSE" {
                    Text("You do not need to give your name for this. The moderators act on your report without it.").font(.caption).foregroundStyle(Role.accent)
                }
            }
            Section {
                TextField("Why are you reporting it?", text: $reason, axis: .vertical)
                    .lineLimit(4...8)
                    .onChange(of: reason) { _, new in if new.count > 2000 { reason = String(new.prefix(2000)) } }
                Toggle("I confirm that what I say here is accurate and complete to the best of my knowledge.", isOn: $goodFaith)
                    .font(.footnote)
            } footer: {
                Text("Say what is illegal or against the terms, and why. Required.")
            }
            Section {
                TextField("Your email address (optional)", text: $contact)
                    .textContentType(.emailAddress)
                    #if os(iOS)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
                    .onChange(of: contact) { _, new in if new.utf8.count > 254 { contact = String(new.prefix(254)) } }
                // GDPR Art. 13 applies where a contact is taken: the notice's passage on it
                if let url = URL(string: model.privacyLink("reporter-contact")) {
                    Button("How your contact is kept, and for how long") { openURL(url) }.font(.footnote)
                }
            } footer: {
                Text("For a receipt now and the decision later. The server mails the receipt and keeps the address only in a form the moderators alone can read.")
            }
            if let error { Section { Text(error).foregroundStyle(Role.danger) } }
            Section {
                Button {
                    Task { await send() }
                } label: {
                    HStack { Spacer(); if sending { ProgressView() } else { Text("Send report").fontWeight(.semibold) }; Spacer() }
                }
                .disabled(sending || category == nil || reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !goodFaith)
            }
        }
    }

    private func send() async {
        guard let category else { return }
        sending = true
        error = nil
        let r = await model.report(ref, scope: whole ? .whole : .name, category: category, reason: reason, contact: contact)
        sending = false
        if r.outcome == .sent { sent = r } else { error = Notices.shared.reportRefused(outcome: r.outcome).map { say($0) } }
    }

    private static func time(_ instant: String?) -> String {
        let parse = ISO8601DateFormatter()
        parse.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = instant.flatMap { parse.date(from: $0) ?? ISO8601DateFormatter().date(from: $0) }
        return date.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? (instant ?? "")
    }
}
