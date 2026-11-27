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

    var body: some View {
        List {
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
