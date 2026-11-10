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
    private static var categories: [(String, String)] { NodeReport.shared.CATEGORIES.map { ($0.token, say($0.word)) } }

    var body: some View {
        NavigationStack {
            Group {
                if let sent { receipt(sent) } else { form }
            }
            .navigationTitle(say(.reportAction))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() }.disabled(sending) } }
        }
        .interactiveDismissDisabled(sending)
    }

    private func receipt(_ sent: NodeReport.Sent) -> some View {
        Form {
            Section {
                Text(say(.reportSent))
                Text(say(.reportReceipt, sent.reference ?? "", Self.time(sent.receivedAt)))
                    .font(.caption).foregroundStyle(Role.muted)
            }
            Section { Button(say(.receiptsClose)) { dismiss() } }
        }
    }

    private var form: some View {
        Form {
            Section {
                Text(say(.reportIntro))
                    .font(.subheadline).foregroundStyle(Role.muted)
            }
            Section(say(.reportScope)) {
                Picker(say(.reportScope), selection: $whole) {
                    Text(say(.reportScopeWhole)).tag(true)
                    Text(say(.reportScopeName)).tag(false)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                Text(whole ? say(.reportScopeWholeHint)
                     : say(.reportScopeNameHint))
                    .font(.caption).foregroundStyle(Role.muted)
            }
            Section(say(.reportCategory)) {
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
                if let note = NodeReport.shared.note(category: category) {
                    Text(say(note)).font(.caption).foregroundStyle(Role.accent)
                }
            }
            Section {
                TextField(say(.reportReason), text: $reason, axis: .vertical)
                    .lineLimit(4...8)
                    .onChange(of: reason) { _, new in let cut = NodeReport.shared.reason(text: new); if cut != new { reason = cut } }
                Toggle(say(.reportGoodFaith), isOn: $goodFaith)
                    .font(.footnote)
            } footer: {
                Text(say(.reportReasonHint))
            }
            Section {
                TextField(say(.reportContact), text: $contact)
                    .textContentType(.emailAddress)
                    #if os(iOS)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
                    .onChange(of: contact) { _, new in let cut = NodeReport.shared.contact(text: new); if cut != new { contact = cut } }
                // GDPR Art. 13 applies where a contact is taken: the notice's passage on it
                if let url = URL(string: model.privacyLink(NodeReport.shared.PRIVACY_ANCHOR)) {
                    Button(say(.reportPrivacy)) { openURL(url) }.font(.footnote)
                }
            } footer: {
                Text(say(.reportContactHint))
            }
            if let error { Section { Text(error).foregroundStyle(Role.danger) } }
            Section {
                Button {
                    Task { await send() }
                } label: {
                    HStack { Spacer(); if sending { ProgressView() } else { Text(say(.reportSend)).fontWeight(.semibold) }; Spacer() }
                }
                .disabled(sending || !NodeReport.shared.canSend(category: category, reason: reason, goodFaith: goodFaith))
            }
        }
    }

    private func send() async {
        guard let category else { return }
        sending = true
        error = nil
        let r = await model.report(ref, scope: whole ? .whole : .name, category: category, reason: reason, contact: contact)
        sending = false
        if r.outcome == .sent { sent = r } else { error = Notices.shared.reportRefused(outcome: r.outcome).map { say(notice: $0) } }
    }

    private static func time(_ instant: String?) -> String {
        let parse = ISO8601DateFormatter()
        parse.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = instant.flatMap { parse.date(from: $0) ?? ISO8601DateFormatter().date(from: $0) }
        return date.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? (instant ?? "")
    }
}
