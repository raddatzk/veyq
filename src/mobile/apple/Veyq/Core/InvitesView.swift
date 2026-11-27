import SwiftUI
import VeyqCore

/// *Invite member* (uc.spaces.add-member; the web's `InviteMemberDialog` over core's `TenantInvites`): the two ways as a
/// segmented picker — a link first (ADR 0129 decision 1), then a mail —, the link's optional label only owners see, the
/// mail's address and its optional own password instead of a generated code. While it runs it says so; what it made is
/// shown once (`InviteResultSections`), a refusal under the form. `done` runs once an invite went out, so the roster and
/// its pending list are read anew.
struct InviteMemberSheet: View {
    let model: AppModel
    let done: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var way: Way = .link
    @State private var label = ""
    @State private var email = ""
    @State private var usePassword = false
    @State private var password = ""
    @State private var working = false
    @State private var outcome: TenantInvites.Outcome?
    @State private var refused: String?

    private enum Way: Hashable { case link, mail }

    private var canSubmit: Bool {
        switch way {
        case .link: true
        case .mail: !email.trimmingCharacters(in: .whitespaces).isEmpty && (!usePassword || password.count >= 10)
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                if let outcome, outcome.done {
                    InviteResultSections(outcome: outcome, spaceName: model.tenant?.name ?? "")
                } else {
                    form
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.membersInvite))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                if outcome?.done == true {
                    ToolbarItem(placement: .confirmationAction) { Button(say(.shareDone)) { dismiss() }.accessibilityIdentifier("invite-done") }
                } else {
                    ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() }.disabled(working) }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(way == .link ? say(.membersLinkCreate) : say(.membersInviteSubmit), action: submit)
                            .disabled(!canSubmit || working)
                            .accessibilityIdentifier("invite-submit")
                    }
                }
            }
            .interactiveDismissDisabled(working)
        }
        .tint(Role.accent)
        // a password the owner chooses is typed here (masked, never shown back)
        .secretOnScreen()
        #if os(macOS)
        .frame(minWidth: 480, idealWidth: 540, minHeight: 460)
        #endif
    }

    @ViewBuilder private var form: some View {
        Section {
            Picker(say(.membersInvite), selection: $way) {
                Text(say(.membersWayLink)).tag(Way.link)
                Text(say(.membersWayMail)).tag(Way.mail)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .disabled(working)
            .accessibilityIdentifier("invite-way")
        } footer: {
            Text(way == .link ? say(.membersLinkIntro) : say(.membersInviteIntro))
        }
        switch way {
        case .link:
            Section {
                TextField(say(.membersLinkLabel), text: $label, prompt: Text(say(.membersLinkLabel)))
                    .disabled(working)
                    .onSubmit(submit)
                    .accessibilityIdentifier("invite-link-label")
            } footer: {
                Text(say(.membersLinkLabelHint))
            }
        case .mail:
            Section {
                TextField(say(.membersEmail), text: $email, prompt: Text(say(.membersEmail)))
                    .textContentType(.emailAddress)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.emailAddress)
                    #endif
                    .disabled(working)
                    .accessibilityIdentifier("invite-email")
                if usePassword {
                    SecureField(say(.membersInvitePasswordLabel), text: $password, prompt: Text(say(.membersInvitePasswordLabel)))
                        .disabled(working)
                        .accessibilityIdentifier("invite-password")
                }
                Button(usePassword ? say(.membersInviteUseCode) : say(.membersInviteUsePassword)) {
                    usePassword.toggle()
                    if !usePassword { password = "" }
                }
                .disabled(working)
                .accessibilityIdentifier("invite-use-password")
            } footer: {
                if usePassword { Text(say(.membersInvitePasswordNote)) }
            }
        }
        if working || refused != nil {
            Section {
                if working {
                    HStack(spacing: 12) { ProgressView(); Text(say(.membersInviteSecuring)) }
                }
                if let refused { Text(refused).foregroundStyle(Role.danger).accessibilityIdentifier("invite-refused") }
            }
        }
    }

    private func submit() {
        guard canSubmit, !working else { return }
        working = true
        refused = nil
        let way = way, label = label, email = email, password = usePassword ? password : nil
        Task {
            let made: TenantInvites.Outcome? = switch way {
            case .link: try? await model.inviteByLink(label: label.isEmpty ? nil : label)
            case .mail: try? await model.inviteByMail(email: email, password: password)
            }
            working = false
            guard let made else {
                refused = say(way == .link ? .membersLinkFailed : .membersInviteFailed)
                return
            }
            if made.done {
                self.password = ""
                outcome = made
                done()
            } else {
                refused = say(notice: made.notice)
            }
        }
    }
}

/// What an invite or a re-issue made, shown once: a link with the message around it — editable, as the web's
/// `InviteLinkResult` — and its Copy, Share and *Open in email*, under the warning that whoever holds it can join; or a
/// mailed invite's words and its code with Copy (none where the owner chose a password).
struct InviteResultSections: View {
    let outcome: TenantInvites.Outcome
    let spaceName: String
    @State private var message = ""
    @State private var copied = false
    @State private var codeCopied = false
    @Environment(\.openURL) private var openURL

    var body: some View {
        Section {
            Label(say(notice: outcome.notice), systemImage: "checkmark.circle").accessibilityIdentifier("invite-result-notice")
        }
        if outcome.address != nil {
            Section {
                TextEditor(text: $message)
                    .frame(minHeight: 120)
                    .accessibilityIdentifier("invite-link-message")
            } header: {
                Text(say(.membersLinkMessageLabel))
            } footer: {
                Text(say(.membersLinkWarning)).foregroundStyle(Role.danger)
            }
            Section {
                Button {
                    Clipboard.copy(message)
                    copied = true
                    Task {
                        try? await waitCore(Notice.companion.COPIED_MS)
                        copied = false
                    }
                } label: { Label(copied ? say(.membersLinkCopied) : say(.membersLinkCopy), systemImage: "doc.on.doc") }
                    .accessibilityIdentifier("invite-link-copy")
                ShareLink(item: message) { Label(say(.membersLinkShare), systemImage: "square.and.arrow.up") }
                    .accessibilityIdentifier("invite-link-share")
                if let mail = mailURL {
                    Button { openURL(mail) } label: { Label(say(.membersLinkMail), systemImage: "envelope") }
                        .accessibilityIdentifier("invite-link-mail")
                }
            }
            .onAppear { if message.isEmpty, let m = outcome.message { message = say(notice: m) } }
        } else if outcome.mailedTo != nil {
            Section {
                Text(outcome.ownChoice ? say(.membersInviteSentPasswordIntro) : say(.membersInviteSentIntro))
            }
            if let code = outcome.code {
                Section {
                    Text(code)
                        .font(.title2.monospaced())
                        .textSelection(.enabled)
                        .accessibilityIdentifier("invite-code")
                    Button {
                        Clipboard.copy(code)
                        codeCopied = true
                        Task {
                            try? await waitCore(Notice.companion.COPIED_MS)
                            codeCopied = false
                        }
                    } label: { Label(codeCopied ? say(.membersLinkCopied) : say(.membersInviteCodeCopy), systemImage: "doc.on.doc") }
                        .accessibilityIdentifier("invite-code-copy")
                } header: {
                    Text(say(.membersInviteCodeLabel))
                } footer: {
                    Text(say(.membersInviteCodeNote))
                }
            }
        }
    }

    /// `mailto:` with the invitation's subject and the message as its body (the web's `mailHref`).
    private var mailURL: URL? {
        var c = URLComponents()
        c.scheme = "mailto"
        c.path = ""
        c.queryItems = [URLQueryItem(name: "subject", value: say(.membersLinkMailSubject, spaceName)), URLQueryItem(name: "body", value: message)]
        return c.url
    }
}

/// The owners' pending invites under the roster (uc.spaces.manage-member-invites): each its address or label (or "Link
/// invitation"), its validity, the *Invited* badge, and its acts in a menu and a context menu — *Rename* (a link invite
/// only), *Send again* / *New link*, *Cancel invite* (asked first).
struct PendingInviteRow: View {
    let invite: TenantInvites.PendingInvite
    let working: Bool
    let rename: () -> Void
    let reissue: () -> Void
    let revoke: () -> Void

    private var title: String { invite.title ?? invite.titleWord.map { say($0) } ?? "" }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: invite.link ? "link" : "envelope")
                .frame(width: 40, height: 40)
                .foregroundStyle(Role.muted)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                if let subtitle = invite.subtitle {
                    Text(joinedLine(subtitle.map { say(notice: $0) })).font(.subheadline).foregroundStyle(Role.muted)
                } else {
                    Text(say(notice: invite.expires)).font(.subheadline).foregroundStyle(Role.muted)
                }
                Text(say(.membersStatusInvited)).font(.footnote).foregroundStyle(Role.accent)
            }
            Spacer()
            Menu { acts } label: { Image(systemName: "ellipsis.circle").accessibilityLabel(title) }
                .menuStyle(.borderlessButton)
                .fixedSize()
                .disabled(working)
                .accessibilityIdentifier("invite-menu-\(invite.inviteId)")
        }
        .contextMenu { acts }
        .accessibilityIdentifier("invite-\(invite.inviteId)")
    }

    @ViewBuilder private var acts: some View {
        if invite.link { Button(action: rename) { Label(say(.membersLabelEdit), systemImage: "pencil") } }
        Button(action: reissue) {
            Label(invite.link ? say(.membersReissueLink) : say(.membersReissue), systemImage: "arrow.clockwise")
        }
        Button(role: .destructive, action: revoke) { Label(say(.membersRevoke), systemImage: "xmark.circle") }
    }
}

/// A re-issue's new link or code, shown once as an invite's result is.
struct InviteResultSheet: View {
    let outcome: TenantInvites.Outcome
    let spaceName: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form { InviteResultSections(outcome: outcome, spaceName: spaceName) }
                .formStyle(.grouped)
                .navigationTitle(outcome.address != nil ? say(.membersLinkResultTitle) : say(.membersInviteCodeLabel))
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button(say(.shareDone)) { dismiss() } } }
        }
        .tint(Role.accent)
        #if os(macOS)
        .frame(minWidth: 480, idealWidth: 540, minHeight: 420)
        #endif
    }
}

/// A link invite's label edited (the web's label dialog): its draft, Save and Cancel; empty clears it.
struct InviteLabelSheet: View {
    @State var draft: String
    let save: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(say(.membersLinkLabel), text: $draft, prompt: Text(say(.membersLinkLabel)))
                        .onSubmit { save(draft) }
                        .accessibilityIdentifier("invite-label-field")
                } footer: {
                    Text(say(.membersLinkLabelHint))
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.membersLabelTitle))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.membersLabelEdit)) { save(draft) }.accessibilityIdentifier("invite-label-save")
                }
            }
        }
        .tint(Role.accent)
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 220)
        #endif
    }
}
