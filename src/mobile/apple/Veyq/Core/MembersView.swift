import SwiftUI
import VeyqCore

/// Settings' *Members* (core's `SettingsSection.MEMBERS` over `TenantMembers`, the web's `TenantMembersPanel` roster;
/// uc.spaces.remove-tenant-member): every member of the open space with their role and state, as a grouped list. A row's
/// acts — *Make Co-Owner*, *Remove Co-Owner*, *Remove Member* — are its menu and its context menu, offered as core says;
/// the removal asks first, with the options to turn off every link of the space and to give it a new lock. Above the
/// roster the space's header ([ScopeHeader]: its icon, which an owner sets or removes, uc.spaces.set-space-icon); on each
/// row its trust badge; below, an owner's *Links* and *New lock* ([ScopeLinksAndLock]), each behind its question. What an
/// act says shows under the list. Pushed in iOS's Settings, a sheet over the Mac's.
struct MembersView: View {
    let model: AppModel
    @State private var loading = true
    @State private var roster: TenantMembers.Roster?
    @State private var said: String?
    @State private var working = false
    @State private var removing: TenantMembers.RosterMember?
    @State private var revokeLinks = false
    @State private var newLock = false
    /// The space's icon opened on this device (core's `ScopeIcons.icon`); nil shows its initial.
    @State private var icon: Data?
    @State private var choosingIcon = false
    @State private var iconNotImage = false
    @State private var removingIcon = false
    @State private var askingLinks = false
    @State private var askingLock = false
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
            if let roster, roster.said == nil {
                Section {
                    ScopeHeader(
                        name: roster.tenantName.isEmpty ? (model.tenant?.name ?? "") : roster.tenantName, icon: icon,
                        owner: roster.ownerActs, working: working, notImage: iconNotImage,
                        choose: { choosingIcon = true }, remove: { removingIcon = true }
                    )
                    SpaceStatusLines(lines: roster.status, working: working) { requeue() }
                }
            }
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
                            newLock = false
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
            if let roster, roster.ownerActs {
                ScopeLinksAndLock(lockSub: say(.spaceLockSub), working: working, revoke: { askingLinks = true }, lock: { askingLock = true })
            }
        }
        .overlay(alignment: .top) { if working { ProgressView().padding(8) } }
        .squarePicture($choosingIcon, title: say(icon == nil ? .spaceIconAdd : .spaceIconChange), notImage: $iconNotImage) { rgba in
            iconAct { id in await model.setScopeIcon(id, rgba: rgba) }
        }
        .dangerConfirm($removingIcon, title: say(.spaceIconRemove), message: say(.spaceIconRemoveConfirm), action: say(.spaceIconRemove)) {
            iconAct { id in await model.clearScopeIcon(id) }
        }
        .dangerConfirm($askingLinks, title: say(.spaceLinksRevokeAll), message: say(.spaceLinksRevokeAllConfirm), action: say(.spaceLinksRevokeAll)) {
            act { try await model.membersRevokeLinks() }
        }
        .dangerConfirm($askingLock, title: say(.spaceLockConfirmTitle), message: say(.spaceLockConfirm), action: say(.spaceLockAction)) {
            act { try await model.membersNewLock() }
        }
        .environment(\.avatars, model.avatars)
        .navigationTitle(say(.membersTitle))
        .accessibilityIdentifier("members")
        .task { await load() }
        .refreshable { await load() }
        .sheet(item: Binding(get: { removing.map(Removing.init) }, set: { if $0 == nil { removing = nil } })) { r in
            RemoveMemberSheet(revokeLinks: $revokeLinks, newLock: $newLock, confirm: {
                removing = nil
                let links = revokeLinks, lock = newLock
                act { try await model.removeMember(r.row.userId, revokeLinks: links, newLock: lock) }
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
        if let id = model.tenantScopeId { icon = await model.scopeIcon(id) }
    }

    /// An act on the space's icon: what it said shows under the list; one that went through opens the icon anew.
    private func iconAct(_ run: @escaping (String) async -> ScopeIcons.Outcome?) {
        guard let id = model.tenantScopeId else { return }
        working = true
        Task {
            let outcome = await run(id)
            said = outcome.map { say(notice: $0.notice) } ?? say(.spaceIconFailed)
            if outcome?.done == true { icon = await model.scopeIcon(id) }
            working = false
        }
    }

    /// *Start Again* on the stuck rotation's notice: a failure said under the list, the roster read anew where it went through.
    private func requeue() {
        working = true
        Task {
            if let r = try? await model.membersRequeue() {
                said = r.notice.map { say(notice: $0) }
                if r.done { roster = await model.membersRead() }
            }
            working = false
        }
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

/// The removal's question (the web's `askWithOptions`): its words, two opt-ins, each off — turn off every link of the
/// space, and give the space a new lock (run after the removal, the removed member left out of it) —, Remove and Cancel.
private struct RemoveMemberSheet: View {
    @Binding var revokeLinks: Bool
    @Binding var newLock: Bool
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
                Section {
                    Toggle(isOn: $newLock) { Text(say(.spaceLockRemoveOption)) }
                        .accessibilityIdentifier("member-remove-lock")
                } footer: {
                    Text(say(.spaceLockRemoveOptionHint))
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
        .frame(minWidth: 420, minHeight: 400)
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
                    TrustBadge(word: row.badgeWord, anomaly: row.badgeNote != nil)
                }
                TrustNote(note: row.badgeNote)
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

/// A member's trust badge (the web's `MemberTrustBadge`, core's `MemberBadge` over Who's who): *Verified*, *New* or the
/// anomaly's word, in the danger colour for the anomaly; none where core says none.
struct TrustBadge: View {
    let word: Msg?
    let anomaly: Bool

    var body: some View {
        if let word {
            Text(say(word)).font(.footnote).foregroundStyle(anomaly ? Role.danger : Role.muted).accessibilityIdentifier("member-trust")
        }
    }
}

/// The anomaly's note under a row's badges: nothing is shared with that key.
struct TrustNote: View {
    let note: Msg?

    var body: some View {
        if let note { Text(say(note)).font(.footnote).foregroundStyle(Role.muted).accessibilityIdentifier("member-trust-note") }
    }
}

/// The status lines above a space's member list (core's `SpaceStatus.lines`, the web's panels): a stuck rotation's
/// warning — a failed one with *Start Again* —, the resealing or the re-keying hint.
struct SpaceStatusLines: View {
    let lines: [SpaceStatusLine]
    let working: Bool
    let requeue: () -> Void

    var body: some View {
        ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
            VStack(alignment: .leading, spacing: 8) {
                Label(say(line.word), systemImage: line.warn ? "exclamationmark.triangle.fill" : "info.circle")
                    .foregroundStyle(line.warn ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary))
                if line.requeue {
                    Button(say(.membersCompromiseRotationRequeue), action: requeue)
                        .disabled(working)
                        .accessibilityIdentifier("space-status-requeue")
                }
            }
            .accessibilityIdentifier(line.warn ? "space-status-warn" : "space-status-info")
        }
    }
}

/// A scope's identity header (the web's `TenantAvatar` beside the name): its icon opened on this device, else its
/// initial, and its name. An `owner` adds or changes the icon through the app's one square crop (`squarePicture`) and
/// removes it after a question, both of which the screen holds; every member sees the icon.
struct ScopeHeader: View {
    let name: String
    let icon: Data?
    let owner: Bool
    let working: Bool
    let notImage: Bool
    let choose: () -> Void
    let remove: () -> Void

    var body: some View {
        HStack(spacing: 16) {
            PersonAvatar(name: name, userId: nil, size: 56, data: icon)
                .accessibilityIdentifier(icon == nil ? "scope-icon-initial" : "scope-icon")
            VStack(alignment: .leading, spacing: 8) {
                Text(name).font(.headline).accessibilityIdentifier("scope-name")
                if owner {
                    HStack(spacing: 12) {
                        Button(say(icon == nil ? .spaceIconAdd : .spaceIconChange), action: choose)
                            .accessibilityIdentifier("scope-icon-choose")
                        if icon != nil {
                            Button(say(.spaceIconRemove), role: .destructive, action: remove)
                                .accessibilityIdentifier("scope-icon-remove")
                        }
                    }
                    .buttonStyle(.borderless)
                    .disabled(working)
                    if notImage { Text(say(.profilePictureNotImage)).font(.footnote).foregroundStyle(Role.danger) }
                }
            }
        }
        .accessibilityIdentifier("scope-header")
    }
}

/// An owner's *Links* and *New lock* sections (the web's panels'): *Turn off every link* and *Give it a new lock*, each a
/// destructive button whose question the screen asks; `lockSub` is the space's or the group's words.
struct ScopeLinksAndLock: View {
    let lockSub: String
    let working: Bool
    let revoke: () -> Void
    let lock: () -> Void

    var body: some View {
        Section {
            Button(role: .destructive, action: revoke) { Label(say(.spaceLinksRevokeAll), systemImage: "link") }
                .disabled(working)
                .accessibilityIdentifier("scope-links-revoke")
        } header: {
            Text(say(.spaceLinksTitle))
        } footer: {
            Text(say(.spaceLinksSub))
        }
        Section {
            Button(role: .destructive, action: lock) { Label(say(.spaceLockAction), systemImage: "lock.rotation") }
                .disabled(working)
                .accessibilityIdentifier("scope-new-lock")
        } header: {
            Text(say(.spaceLockTitle))
        } footer: {
            Text(lockSub)
        }
    }
}
