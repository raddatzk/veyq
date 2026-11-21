import SwiftUI
import VeyqCore

/// Core's report of a node to its server's moderators (the web's `ReportDialog.vue`, ADR 0093): what it is, why (required),
/// the reporter's good-faith confirmation (required), an optional address for the receipt and the decision with the privacy
/// notice's passage on it (GDPR Art. 13), and for one node whether it is reported whole or only by its name. On success the
/// receipt: its reference and when it was received.
struct ReportSheet: View {
    let model: AppModel
    let ref: NodeAt
    /// Another sheet than the node's own (a music track's, reported as an ITEM: ADR 0142 decision 11); nil: the node's.
    var make: (@MainActor () -> VeyqCore.ReportSheet?)? = nil
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var box = ReportBox()

    private var sending: Bool { box.state?.sending ?? false }

    var body: some View {
        NavigationStack {
            Group {
                if let sheet = box.sheet, let st = box.state {
                    if let sent = st.sent { receipt(sent) } else { form(sheet, st) }
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(say(.reportAction))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() }.disabled(sending) } }
        }
        .interactiveDismissDisabled(sending)
        #if os(macOS)
        // a Mac's sheet takes its content's ideal size, which a Form has none of (every caller's: a node's, a medium's,
        // a track's)
        .frame(minWidth: 460, minHeight: 520)
        #endif
        .task {
            guard box.sheet == nil, let made = make.map({ $0() }) ?? model.reportSheet(ref) else { return }
            box.start(made)
        }
        .onDisappear { box.stop() }
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
        .formStyle(.grouped)
    }

    private func form(_ sheet: VeyqCore.ReportSheet, _ st: VeyqCore.ReportSheet.State) -> some View {
        Form {
            Section {
                Text(say(.reportIntro))
                    .font(.subheadline).foregroundStyle(Role.muted)
            }
            if sheet.offersName { Section(say(.reportScope)) {
                Picker(say(.reportScope), selection: Binding(get: { st.whole }, set: { sheet.whole(on: $0) })) {
                    Text(say(.reportScopeWhole)).tag(true)
                    Text(say(.reportScopeName)).tag(false)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                Text(say(st.scopeHint))
                    .font(.caption).foregroundStyle(Role.muted)
            } }
            Section(say(.reportCategory)) {
                ForEach(sheet.categories, id: \.token) { c in
                    Button { sheet.category(token: c.token) } label: {
                        HStack {
                            Text(say(c.word)).foregroundStyle(Role.text)
                            Spacer()
                            if st.category == c.token { Image(systemName: "checkmark").foregroundStyle(Role.accent) }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                if let note = st.note {
                    Text(say(note)).font(.caption).foregroundStyle(Role.accent)
                }
            }
            Section {
                TextField(say(.reportReason), text: Binding(get: { sheet.current.reason }, set: { sheet.reason(text: $0) }), axis: .vertical)
                    .lineLimit(4...8)
                Toggle(say(.reportGoodFaith), isOn: Binding(get: { st.goodFaith }, set: { sheet.goodFaith(on: $0) }))
                    .font(.footnote)
            } footer: {
                Text(say(.reportReasonHint))
            }
            Section {
                TextField(say(.reportContact), text: Binding(get: { sheet.current.contact }, set: { sheet.contact(text: $0) }))
                    .textContentType(.emailAddress)
                    #if os(iOS)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
                // GDPR Art. 13 applies where a contact is taken: the notice's passage on it (core's URL)
                if let url = URL(string: sheet.privacyUrl) {
                    Button(say(.reportPrivacy)) { openURL(url) }.font(.footnote)
                }
            } footer: {
                Text(say(.reportContactHint))
            }
            if let problem = st.problem { Section { Text(say(notice: problem)).foregroundStyle(Role.danger) } }
            Section {
                Button {
                    Task { _ = try? await sheet.send() }
                } label: {
                    HStack { Spacer(); if st.sending { ProgressView() } else { Text(say(.reportSend)).fontWeight(.semibold) }; Spacer() }
                }
                .disabled(!st.canSend)
            }
        }
        // grouped on a Mac too: its default, the columns style, pushed every row into a trailing column and clipped it
        // (the macOS pass, 2026-11-19); iOS's Form is grouped already
        .formStyle(.grouped)
        .disabled(st.sending)
    }

    /// A server instant as the web writes a date and time (core's `Shown.moment`).
    private static func time(_ instant: String?) -> String { instant.map(shownMoment) ?? "" }
}

/// Core's report sheet as SwiftUI observes it: its state, moved by the sheet's watch onto the main actor.
@MainActor @Observable
final class ReportBox {
    private(set) var sheet: VeyqCore.ReportSheet?
    private(set) var state: VeyqCore.ReportSheet.State?
    @ObservationIgnored private var watch: VeyqCore.ReportSheet.Watch?

    func start(_ sheet: VeyqCore.ReportSheet) {
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
