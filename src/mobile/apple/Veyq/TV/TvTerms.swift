import SwiftUI
import VeyqCore

/// A TV's terms gate (uc.account.accept-terms; core's `Gate.Signed.terms`): in place of the shell, the space's terms and the
/// operator section, each paragraph a focus stop so the remote scrolls through them, then "I Agree" and "Sign Out". A TV
/// opens no browser, so the privacy notice's passage on the guest-write record is offered as a QR code and its address,
/// as the report's is.
struct TvTermsGate: View {
    let model: TvModel
    let gate: SessionModelTermsGate

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text(say(.termsGateTitle)).font(.title2.bold())
                Text(say(.termsGateIntro)).foregroundStyle(.secondary)
                paragraphs(gate.shown.hasTerms && !gate.shown.terms.isEmpty ? gate.shown.terms : say(.termsNoneSet))
                Divider()
                Text(say(.termsOperatorSectionLabel)).font(.headline)
                Text(say(.termsOperatorSectionNote)).foregroundStyle(.secondary)
                paragraphs(gate.shown.operatorSection)
                if !gate.shown.record.isEmpty {
                    paragraphs(gate.shown.record)
                    HStack(spacing: 40) {
                        QrMatrixView(text: gate.shown.privacyUrl).frame(width: 220, height: 220)
                        VStack(alignment: .leading, spacing: 12) {
                            Text(say(.termsRecordLink))
                            Text(say(.reportPrivacyElsewhere)).foregroundStyle(.secondary)
                            Text(gate.shown.privacyUrl)
                        }
                    }
                }
                if let error = gate.error { Text(say(error)).foregroundStyle(Role.danger) }
                HStack(spacing: 32) {
                    Button(say(.termsAccept)) { model.acceptTerms() }.disabled(gate.working)
                    Button(say(.drawerSignOut)) { model.removeHere() }.disabled(gate.working)
                }
            }
            .padding(60)
        }
    }

    /// A Markdown text's paragraphs (a heading's line bold), each focusable so the remote reaches the next.
    private func paragraphs(_ markdown: String) -> some View {
        // a heading is its own line: the lines after it in the same block are the paragraph under it
        let blocks = markdown.components(separatedBy: "\n\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
            .flatMap { block -> [String] in
                guard block.hasPrefix("#"), let nl = block.firstIndex(of: "\n") else { return [block] }
                let rest = block[block.index(after: nl)...].trimmingCharacters(in: .whitespacesAndNewlines)
                return rest.isEmpty ? [String(block[..<nl])] : [String(block[..<nl]), rest]
            }
        return ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
            Group {
                if block.hasPrefix("#") {
                    Text(block.drop(while: { $0 == "#" }).trimmingCharacters(in: .whitespaces)).bold()
                } else {
                    Text(block.replacingOccurrences(of: "**", with: ""))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusable()
        }
    }
}
