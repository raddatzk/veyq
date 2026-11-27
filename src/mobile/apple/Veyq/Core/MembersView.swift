import SwiftUI
import VeyqCore

/// Settings' *Members* (core's `SettingsSection.MEMBERS` over `TenantMembers`, the web's `TenantMembersPanel` roster;
/// uc.spaces.remove-tenant-member): every member of the open space with their role and state, as a grouped list. A row's
/// acts — *Make Co-Owner*, *Remove Co-Owner*, *Remove Member* — are its menu and its context menu, offered as core says;
/// the removal asks first in an alert, with the option to turn off every link of the space. What an act says shows under
/// the list. Pushed in iOS's Settings, a sheet over the Mac's.
struct MembersView: View {
    let model: AppModel
    @State private var loading = true
    @State private var roster: TenantMembers.Roster?
    @State private var said: String?
    @State private var working = false
    @State private var removing: TenantMembers.RosterMember?
    @State private var revokeLinks = false
    /// *Invite member*'s sheet (owners only).
    @State private var inviting = false
    /// A re-issue's new link or code, shown once.
    @State private var reissued: Reissued?
    /// The link invite whose label is being edited.
    @State private var labelling: TenantInvites.PendingInvite?
    /// The invite whose cancellation is being asked.
    @State private var revoking: TenantInvites.PendingInvite?

    var body: some View {
        List {
            if roster?.owner == true {
                // the owners' *Invite member* (uc.spaces.add-member), the web's button above the roster
                Section {
                    Button { inviting = true } label: { Label(say(.membersInvite), systemImage: "person.badge.plus") }
                        .disabled(working)
                        .accessibilityIdentifier("members-invite")
                }
            }
            Section {
                if loading {
                    HStack(spacing: 12) { ProgressView(); Text(say(.membersLoading)) }
                } else if let line = roster?.said ?? (roster == nil ? Msg.membersLoadFailed : nil) {
                    Text(say(line)).accessibilityIdentifier("members-said")
                    if roster?.retry ?? true {
                        Button(say(.peopleRetry)) { Task { await load() } }.accessibilityIdentifier("members-retry")
                    }
                } else if let roster {
                    ForEach(roster.rows, id: \.userId) { row in
                        MemberRowView(row: row, working: working, role: { owner in act { try await model.setMemberRole(row.userId, owner: owner) } }, remove: {
                            revokeLinks = false
                            removing = row
                        })
                    }
                }
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    Text(say(.membersSub))
                    if let said { Text(said).foregroundStyle(.primary).accessibilityIdentifier("members-notice") }
                }
            }
            // the owners' pending invites (uc.spaces.manage-member-invites); none: no section
            if let roster, roster.owner, !roster.invites.isEmpty {
                Section {
                    ForEach(roster.invites, id: \.inviteId) { invite in
                        PendingInviteRow(invite: invite, working: working, rename: { labelling = invite }, reissue: { reissue(invite) }, revoke: { revoking = invite })
                    }
                    if working {
                        HStack(spacing: 12) { ProgressView(); Text(say(.membersInviteSecuring)) }
                    }
                } header: {
                    Text(say(.membersPendingTitle))
                } footer: {
                    Text(say(.membersPendingSub))
                }
            }
        }
        .environment(\.avatars, model.avatars)
        .navigationTitle(say(.membersTitle))
        .accessibilityIdentifier("members")
        .task { await load() }
        .refreshable { await load() }
        .sheet(item: Binding(get: { removing.map(Removing.init) }, set: { if $0 == nil { removing = nil } })) { r in
            RemoveMemberSheet(revokeLinks: $revokeLinks, confirm: {
                removing = nil
                act { try await model.removeMember(r.row.userId, revokeLinks: revokeLinks) }
            }, cancel: { removing = nil })
        }
        .sheet(isPresented: $inviting) {
            InviteMemberSheet(model: model) { Task { roster = await model.membersRead() } }
        }
        .sheet(item: $reissued) { r in InviteResultSheet(outcome: r.outcome, spaceName: model.tenant?.name ?? "") }
        .sheet(item: Binding(get: { labelling.map(Labelling.init) }, set: { if $0 == nil { labelling = nil } })) { l in
            InviteLabelSheet(draft: l.invite.label ?? "") { text in
                labelling = nil
                let id = l.invite.inviteId
                inviteAct { try await model.setInviteLabel(id, label: text) }
            }
        }
        .alert(say(.membersRevokeConfirmTitle), isPresented: Binding(get: { revoking != nil }, set: { if !$0 { revoking = nil } }), presenting: revoking) { invite in
            Button(say(.membersRevoke), role: .destructive) {
                revoking = nil
                let id = invite.inviteId
                inviteAct { try await model.revokeInvite(id) }
            }
            Button(say(.signinCancel), role: .cancel) { revoking = nil }
        } message: { _ in
            Text(say(.membersRevokeConfirm))
        }
    }

    private func load() async {
        loading = true
        roster = await model.membersRead()
        loading = false
    }

    /// One act: its words said under the list, the roster read anew where it went through.
    private func act(_ run: @escaping () async throws -> TenantMembers.Outcome?) {
        working = true
        Task {
            let outcome = try? await run()
            if let outcome {
                said = say(notice: outcome.notice)
                if outcome.done { roster = await model.membersRead() }
            }
            working = false
        }
    }

    /// An invite's act (a label, a revocation): its words said under the list, the roster read anew where it went through.
    private func inviteAct(_ run: @escaping () async throws -> TenantInvites.Outcome?) {
        working = true
        Task {
            let outcome = try? await run()
            if let outcome {
                said = say(notice: outcome.notice)
                if outcome.done { roster = await model.membersRead() }
            }
            working = false
        }
    }

    /// *Send again* / *New link*: a new invite in the old one's place, its new code or link shown once.
    private func reissue(_ invite: TenantInvites.PendingInvite) {
        working = true
        Task {
            let outcome = try? await model.reissueInvite(invite)
            working = false
            guard let outcome else { return }
            if outcome.done && (outcome.address != nil || outcome.code != nil) {
                said = nil
                reissued = Reissued(outcome: outcome)
            } else {
                said = say(notice: outcome.notice)
            }
            if outcome.done { roster = await model.membersRead() }
        }
    }
}

/// A re-issue's outcome, as a sheet's item.
private struct Reissued: Identifiable {
    let outcome: TenantInvites.Outcome
    let id = UUID()
}

/// The link invite whose label is edited, as a sheet's item.
private struct Labelling: Identifiable {
    let invite: TenantInvites.PendingInvite
    var id: String { invite.inviteId }
}

/// The member being removed, as a sheet's item.
private struct Removing: Identifiable {
    let row: TenantMembers.RosterMember
    var id: String { row.userId }
}

/// The removal's question (the web's `askWithOptions`): its words, the opt-in to turn off every link of the space, Remove and Cancel.
private struct RemoveMemberSheet: View {
    @Binding var revokeLinks: Bool
    let confirm: () -> Void
    let cancel: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section { Text(say(.membersRemoveConfirm)) }
                Section {
                    Toggle(isOn: $revokeLinks) { Text(say(.membersRemoveRevokeLinks)) }
                        .accessibilityIdentifier("member-remove-links")
                } footer: {
                    Text(say(.membersRemoveRevokeLinksHint))
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.membersRemove))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel), action: cancel) }
                ToolbarItem(placement: .destructiveAction) {
                    Button(say(.membersRemove), role: .destructive, action: confirm).accessibilityIdentifier("member-remove-confirm")
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 320)
        #endif
    }
}

/// One member's row: the avatar, the title, `@username`, the address and the role, the badges, and the acts core offers.
private struct MemberRowView: View {
    let row: TenantMembers.RosterMember
    let working: Bool
    let role: (Bool) -> Void
    let remove: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            PersonAvatar(name: row.realName ?? row.username, userId: row.userId, size: 40)
            VStack(alignment: .leading, spacing: 4) {
                Text(row.title)
                Text(Shown.shared.joined(parts: [row.usernameLine, row.email, say(row.roleWord)].compactMap { $0 }))
                    .font(.subheadline).foregroundStyle(Role.muted)
                HStack(spacing: 8) {
                    Text(say(row.statusWord)).font(.footnote).foregroundStyle(Role.accent)
                    if let pending = row.pendingWord { Text(say(pending)).font(.footnote).foregroundStyle(Role.muted).accessibilityIdentifier("member-pending") }
                }
            }
            Spacer()
            if offers {
                Menu { acts } label: { Image(systemName: "ellipsis.circle").accessibilityLabel(row.title) }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                    .disabled(working)
                    .accessibilityIdentifier("member-menu-\(row.userId)")
            }
        }
        .contextMenu { if offers { acts } }
        .accessibilityIdentifier("member-\(row.userId)")
    }

    private var offers: Bool { row.promote || row.demote || row.remove }

    @ViewBuilder private var acts: some View {
        if row.promote { Button { role(true) } label: { Label(say(.membersPromote), systemImage: "arrow.up.circle") } }
        if row.demote { Button { role(false) } label: { Label(say(.membersDemote), systemImage: "arrow.down.circle") } }
        if row.remove { Button(role: .destructive, action: remove) { Label(say(.membersRemove), systemImage: "person.badge.minus") } }
    }
}
