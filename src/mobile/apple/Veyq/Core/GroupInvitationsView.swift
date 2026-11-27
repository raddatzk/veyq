import SwiftUI
import VeyqCore

/// Settings' *Group invitations* (core's `SettingsSection.GROUP_INVITATIONS` over `TenantGroups.invitations`, the web's
/// `GroupInvitationsView`; uc.spaces.consent-group-add): the groups the caller was asked to join, each by its name opened
/// with this account's own key (or core's *A group*) and who asked, with *Accept* and *Decline*; nothing reaches the caller
/// until they accept. What an answer says shows under the list, which is read anew after one went through. Pushed in
/// iOS's Settings, a sheet over the Mac's.
struct GroupInvitationsView: View {
    let model: AppModel
    @State private var loading = true
    @State private var read: TenantGroups.Invitations?
    /// The invitation being answered.
    @State private var busy: String?
    @State private var said: String?

    var body: some View {
        List {
            Section {
                if loading && read == nil {
                    HStack(spacing: 12) { ProgressView(); Text(say(.invitationsLoading)) }
                } else if let line = read?.said ?? (read == nil ? Msg.invitationsLoadFailed : nil) {
                    Text(say(line)).foregroundStyle(Role.muted).accessibilityIdentifier("invitations-said")
                    if line == Msg.invitationsLoadFailed {
                        Button(say(.peopleRetry)) { Task { await load() } }.accessibilityIdentifier("invitations-retry")
                    }
                } else if let read {
                    ForEach(read.rows, id: \.scopeId) { inv in
                        InvitationRowView(invitation: inv, busy: busy != nil, accept: { answer(inv.scopeId, accept: true) }, decline: { answer(inv.scopeId, accept: false) })
                    }
                }
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    Text(say(.invitationsSub))
                    if let said { Text(said).foregroundStyle(.primary).accessibilityIdentifier("invitations-notice") }
                }
            }
        }
        .navigationTitle(say(.invitationsTitle))
        .accessibilityIdentifier("group-invitations")
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        loading = true
        read = try? await model.groups?.invitations()
        loading = false
    }

    private func answer(_ scopeId: String, accept: Bool) {
        busy = scopeId
        said = nil
        Task {
            let outcome = try? await (accept ? model.groups?.accept(scopeId: scopeId) : model.groups?.decline(scopeId: scopeId))
            if let outcome {
                said = outcome.notice.map { say(notice: $0) }
                if outcome.done { await load() }
            } else {
                said = say(.membersActionFailed)
            }
            busy = nil
        }
    }
}

/// One invitation: the group's name, who asked, and the two answers.
private struct InvitationRowView: View {
    let invitation: TenantGroups.Invitation
    let busy: Bool
    let accept: () -> Void
    let decline: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(invitation.titleWord.map { say($0) } ?? invitation.name ?? "")
                Text(say(notice: invitation.addedByLine)).font(.subheadline).foregroundStyle(Role.muted)
            }
            Spacer()
            Button(say(.invitationsDecline), action: decline)
                .buttonStyle(.bordered)
                .accessibilityIdentifier("invitation-decline-\(invitation.scopeId)")
            Button(say(.invitationsAccept), action: accept)
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("invitation-accept-\(invitation.scopeId)")
        }
        .controlSize(.small)
        .disabled(busy)
        .accessibilityIdentifier("invitation-\(invitation.scopeId)")
    }
}
