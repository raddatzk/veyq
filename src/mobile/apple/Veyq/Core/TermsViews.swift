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
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if shown.hasTerms && !shown.terms.isEmpty {
                MarkdownText(markdown: shown.terms)
            } else {
                Text(say(.termsNoneSet)).foregroundStyle(Role.muted)
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

/// Settings' Terms (core's `SettingsSection.TERMS`, the web's `TenantTermsPanel` read-only): the open space's terms read in
/// the app's language when the page shows; editing them is the owner's, in the browser, which the page says.
struct TermsPageView: View {
    let model: AppModel
    @State private var loading = true
    @State private var shown: MemberTerms.TermsShown?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if loading {
                    HStack(spacing: 12) { ProgressView(); Text(say(.termsLoading)) }
                } else if let shown {
                    Text(say(.termsReadonlyNote)).foregroundStyle(Role.muted)
                    TermsText(shown: shown)
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
        .task {
            shown = await model.termsPage()
            loading = false
        }
    }
}
