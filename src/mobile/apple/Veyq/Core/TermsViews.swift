import SwiftUI
import VeyqCore

/// The member's terms gate (uc.account.accept-terms; core's `Gate.Signed.terms`, the web's `TermsAcceptView`): shown in place
/// of the shell while it stands — the space's terms, the operator section after them, "I Agree" and the one other way out,
/// "Sign Out". No shell is behind it, so nothing goes back from it.
struct TermsGateView: View {
    let gate: SessionModelTermsGate
    let onAccept: () -> Void
    let onSignOut: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack { Spacer(); Brand(); Spacer() }
                Text(say(.termsGateTitle)).font(.title2.bold())
                Text(say(.termsGateIntro)).foregroundStyle(Role.muted)
                TermsText(shown: gate.shown)
                if let error = gate.error {
                    Text(say(error)).foregroundStyle(Role.danger).accessibilityIdentifier("terms-error")
                }
                Button(action: onAccept) {
                    if gate.working { ProgressView().frame(maxWidth: .infinity) } else { Text(say(.termsAccept)).frame(maxWidth: .infinity) }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(gate.working)
                .keyboardShortcut(.defaultAction)
                .accessibilityIdentifier("terms-accept")
                Button(say(.drawerSignOut), action: onSignOut)
                    .frame(maxWidth: .infinity)
                    .disabled(gate.working)
                    .accessibilityIdentifier("terms-sign-out")
            }
            .frame(maxWidth: 560)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .background(Role.bg)
        .accessibilityIdentifier("terms-gate")
    }
}

/// The terms as core's `MemberTerms.TermsShown` hands them, the gate's and Settings' page alike: the space's text (or that it has
/// none), the operator section under its label and note, and the guest-write record's pointer with its link to the
/// server's privacy notice, opened in the browser.
struct TermsText: View {
    let shown: MemberTerms.TermsShown
    var withTerms = true
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if withTerms {
                if shown.hasTerms && !shown.terms.isEmpty {
                    MarkdownText(markdown: shown.terms)
                } else {
                    Text(say(.termsNoneSet)).foregroundStyle(Role.muted)
                }
            }
            Divider()
            Text(say(.termsOperatorSectionLabel)).font(.headline)
            Text(say(.termsOperatorSectionNote)).font(.footnote).foregroundStyle(Role.muted)
            if !shown.operatorSection.isEmpty { MarkdownText(markdown: shown.operatorSection) }
            if !shown.record.isEmpty {
                MarkdownText(markdown: shown.record)
                if let url = URL(string: shown.privacyUrl) {
                    Button { openURL(url) } label: { Label(say(.termsRecordLink), systemImage: "safari") }
                        .accessibilityIdentifier("terms-privacy")
                }
            }
        }
    }
}

/// Settings' Terms (core's `SettingsSection.TERMS`, the web's `TenantTermsPanel`): for an owner the editor — one text per
/// language that has terms, *Add Language*, each language's *Insert Template* and remove (both asked first), the blank
/// language's warning, *Save Terms* and the preview —, for a member the terms read-only; the operator section below both.
/// Every rule is core's draft (`MemberTerms.TermsDraft`); the view keeps the draft it was handed and draws it.
struct TermsPageView: View {
    let model: AppModel
    @State private var loading = true
    @State private var editing: MemberTerms.TermsEditing?
    @State private var draft: MemberTerms.TermsDraft?
    @State private var saving = false
    @State private var said: String?
    @State private var asking: Asking?

    /// A question asked before an edit: its title, its words, and the edit.
    private struct Asking: Identifiable {
        let id = UUID()
        let title: String
        let message: String
        var destroys = true
        let run: () -> Void
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if loading {
                    HStack(spacing: 12) { ProgressView(); Text(say(.termsLoading)) }
                } else if let editing, editing.owner, let draft {
                    Text(say(.termsEditorSub)).foregroundStyle(Role.muted)
                    if draft.showsRevision { Text(say(.termsRevision, draft.revision)).font(.headline).accessibilityIdentifier("terms-revision") }
                    editor(draft)
                    TermsText(shown: editing.shown, withTerms: false)
                } else if let editing {
                    Text(say(.termsReadonlyNote)).foregroundStyle(Role.muted)
                    TermsText(shown: editing.shown)
                } else {
                    Text(say(.termsLoadFailed)).foregroundStyle(Role.danger).accessibilityIdentifier("terms-load-failed")
                }
            }
            .frame(maxWidth: 560, alignment: .leading)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle(say(.termsSettingsTitle))
        .accessibilityIdentifier("terms-page")
        .alert(asking?.title ?? "", isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } }), presenting: asking) { a in
            Button(a.title, role: a.destroys ? .destructive : nil) { a.run() }.accessibilityIdentifier("terms-ask-confirm")
            Button(say(.signinCancel), role: .cancel) {}
        } message: { a in Text(a.message) }
        .task {
            editing = await model.termsEditing()
            draft = editing?.draft
            loading = false
        }
    }

    @ViewBuilder private func editor(_ draft: MemberTerms.TermsDraft) -> some View {
        ForEach(draft.texts, id: \.lang) { t in
            VStack(alignment: .leading, spacing: 6) {
                Text(t.name).font(.headline)
                TextEditor(text: Binding(get: { t.text }, set: { self.draft = self.draft?.withText(lang: t.lang, text: $0) }))
                    .font(.body.monospaced())
                    .frame(minHeight: 160)
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(Role.muted.opacity(0.4)))
                    .disabled(saving)
                    .accessibilityLabel(t.name)
                    .accessibilityIdentifier("terms-text-\(t.lang)")
                HStack {
                    if t.templated {
                        Button(say(.termsInsertTemplate)) {
                            asking = Asking(title: say(.termsInsertTemplate), message: say(t.templateAsk, t.name), destroys: t.templateReplaces) { self.draft = self.draft?.withTemplate(lang: t.lang) }
                        }
                        .accessibilityIdentifier("terms-template-\(t.lang)")
                    }
                    Button(say(.termsRemoveLang, t.name), role: .destructive) {
                        asking = Asking(title: say(.termsRemoveLang, t.name), message: say(.termsRemoveLangAsk, t.name)) { self.draft = self.draft?.without(lang: t.lang) }
                    }
                    .accessibilityIdentifier("terms-remove-\(t.lang)")
                }
                .buttonStyle(.borderless)
                .disabled(saving)
            }
        }
        if !draft.addable.isEmpty {
            Menu(say(.termsAddLanguage)) {
                ForEach(draft.addable, id: \.lang) { l in
                    Button(l.name) { self.draft = self.draft?.withLanguage(lang: l.lang) }
                }
            }
            .fixedSize()
            .disabled(saving)
            .accessibilityIdentifier("terms-add")
        }
        if let warning = draft.blankWarning {
            Text(say(warning, draft.blankNames ?? "")).foregroundStyle(Role.danger).accessibilityIdentifier("terms-blank")
        }
        Text(say(.termsMarkdownHint, "{contact}")).font(.footnote).foregroundStyle(Role.muted)
        Button { save() } label: {
            if saving { ProgressView() } else { Text(say(.termsSave)) }
        }
        .buttonStyle(.borderedProminent)
        .disabled(saving)
        .accessibilityIdentifier("terms-save")
        if let said { Text(said).accessibilityIdentifier("terms-notice") }
        let preview = draft.preview(language: AppleSessions.shared.appLanguage())
        if !preview.isEmpty {
            Divider()
            Text(say(.termsPreview)).font(.headline)
            MarkdownText(markdown: preview)
        }
    }

    private func save() {
        guard let draft else { return }
        saving = true
        Task {
            if let saved = await model.saveTerms(draft) {
                said = say(notice: saved.notice)
                if let next = saved.draft { self.draft = next }
            }
            saving = false
        }
    }
}
