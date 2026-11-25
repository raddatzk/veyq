import SwiftUI
import VeyqCore

/// Core's header plan of the node on a TV page (`NodeHeaderModel`, ADR 0154 decision 2), drawn whole and in its order as
/// buttons the focus reaches — a TV's page is no toolbar, and a menu would cost the remote a press for one entry. On a TV
/// the plan holds the report alone, where the server takes one (`NodeHeader.viewOnly`: the DSA's notice mechanism stays
/// reachable where the content is shown, ADR 0159's decision log); nothing here takes an entry away.
struct TvNodeHeader: View {
    @Bindable var model: TvModel
    let node: NodeAt
    @State private var reporting = false

    /// Core's plan; read with the model's `headerRevision`, so each change of the header (the report offered once the
    /// server said so) redraws it.
    private var plan: NodeHeader.Plan? {
        _ = model.headerRevision
        return model.header(node)?.plan(maxButtons: 9, own: [])
    }

    var body: some View {
        HStack(spacing: 20) {
            if let plan {
                ForEach(plan.buttons, id: \.self) { entry($0) }
                ForEach(plan.menu, id: \.self) { entry($0) }
                ForEach(plan.last, id: \.self) { entry($0) }
            }
        }
        .sheet(isPresented: $reporting) {
            TvReportView(model: model, node: node)
        }
    }

    private func word(_ id: HeaderAction) -> String {
        model.header(node)?.label(id: id).map { say($0) } ?? id.name
    }

    @ViewBuilder private func entry(_ id: HeaderAction) -> some View {
        switch id.entry {
        case .report:
            Button { model.input(); reporting = true } label: { Label(word(id), systemImage: "flag") }
        // a TV's plan never holds these (core's `NodeHeader.viewOnly` takes every write and every way off the device): a
        // view-only device has no dialog behind them, so one that came would show, not run
        case .favorite, .share, .rename, .move, .theCopy, .cover, .download, .offline, .delete_,
             .upload, .music, .banner, .replace, .send, .openOwnPage, .reportShared:
            Button(word(id)) {}.disabled(true)
        }
    }
}

/// Core's report of a node on a TV (the iOS `ReportSheet`'s form for the focus engine): every choice a focusable row, the
/// reason and the contact typed on the system keyboard, the good-faith confirmation a toggle, and the privacy notice's
/// passage on the contact (GDPR Art. 13) as its QR code and address — a TV has no browser to open it in. Then the receipt.
struct TvReportView: View {
    let model: TvModel
    let node: NodeAt
    @Environment(\.dismiss) private var dismiss
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
            .navigationTitle(say(.reportTitle))
        }
        .interactiveDismissDisabled(sending)
        .onAppear { model.input() }
        .task {
            guard box.sheet == nil, let made = model.reportSheet(node) else { return }
            box.start(made)
        }
        .onDisappear { box.stop() }
    }

    private func receipt(_ sent: NodeReport.Sent) -> some View {
        Form {
            Section {
                Text(say(.reportSent))
                Text(say(.reportReceipt, sent.reference ?? "", sent.receivedAt.map(shownMoment) ?? ""))
                    .foregroundStyle(.secondary)
            }
            Section { Button(say(.reportClose)) { dismiss() } }
        }
    }

    private func form(_ sheet: VeyqCore.ReportSheet, _ st: VeyqCore.ReportSheet.State) -> some View {
        Form {
            Section {
                Text(say(.reportIntro)).foregroundStyle(.secondary)
                if sheet.nodes.count > 1 { Text(say(notice: st.items)) }
                if let tooMany = st.tooManyNotice {
                    Text(say(notice: tooMany)).foregroundStyle(Role.danger)
                    if sheet.offersEnclosing(state: st) { Button(say(sheet.enclosingWord)) { sheet.useEnclosing() } }
                }
            }
            if sheet.offersName {
                Section {
                    choice(say(.reportScopeWhole), st.whole) { sheet.whole(on: true) }
                    choice(say(.reportScopeName), !st.whole) { sheet.whole(on: false) }
                } header: {
                    Text(say(.reportScope))
                } footer: {
                    Text(say(st.scopeHint))
                }
            }
            Section {
                ForEach(sheet.categories, id: \.token) { c in
                    choice(say(c.word), st.category == c.token) { sheet.category(token: c.token) }
                }
            } header: {
                Text(say(.reportCategory))
            } footer: {
                if let note = st.note { Text(say(note)) }
            }
            Section {
                TextField(say(.reportReason), text: Binding(get: { sheet.current.reason }, set: { sheet.reason(text: $0) }), axis: .vertical)
                Toggle(say(.reportGoodFaith), isOn: Binding(get: { st.goodFaith }, set: { sheet.goodFaith(on: $0) }))
            } footer: {
                Text(say(.reportReasonHint))
            }
            Section {
                TextField(say(.reportContact), text: Binding(get: { sheet.current.contact }, set: { sheet.contact(text: $0) }))
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .autocorrectionDisabled()
            } footer: {
                Text(say(.reportContactHint))
            }
            // GDPR Art. 13 where a contact is taken: the notice's passage, read on another device
            Section(say(.reportPrivacy)) {
                HStack(spacing: 40) {
                    QrMatrixView(text: sheet.privacyUrl).frame(width: 220, height: 220)
                    VStack(alignment: .leading, spacing: 12) {
                        Text(say(.reportPrivacyElsewhere)).foregroundStyle(.secondary)
                        Text(sheet.privacyUrl)
                    }
                }
                // the passage's text is read, not pressed: the focus passes over it to the next row
                .focusable(false)
            }
            if let problem = st.problem { Section { Text(say(notice: problem)).foregroundStyle(Role.danger) } }
            Section {
                Button {
                    Task { _ = try? await sheet.send() }
                } label: {
                    if st.sending { ProgressView() } else { Text(say(.reportSend)) }
                }
                .disabled(!st.canSend)
                Button(say(.signinCancel), role: .cancel) { dismiss() }
            }
        }
        .disabled(st.sending)
    }

    /// One of several: a row the focus reaches, its checkmark where chosen.
    private func choice(_ label: String, _ chosen: Bool, _ choose: @escaping () -> Void) -> some View {
        Button(action: choose) {
            HStack {
                Text(label)
                Spacer()
                if chosen { Image(systemName: "checkmark") }
            }
        }
    }
}
