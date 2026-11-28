import SwiftUI
import VeyqCore

/// Settings' *Groups* (core's `SettingsSection.GROUPS` over `TenantGroups`, the web's `groups` category and
/// `CreateGroupDialog`; en.spaces.group-scope): the caller's groups in the open space — each by its name or core's
/// *Untitled group*, its role, a deleted one's grace period and a grant behind the group's lock —, and *New group* as the
/// list's "+" where core says the space lets this member create one; a created group opens on its page. A row opens its
/// page. Pushed in iOS's Settings, a sheet over the Mac's.
///
/// What an act says shows on the page it was done on, as Settings' Members does: Settings is a sheet over the shell on
/// iOS and a window of its own on a Mac, so the shell's notice at its bottom would be drawn where nobody looks.
struct GroupsView: View {
    let model: AppModel
    @State private var read: TenantGroups.Groups?
    @State private var loaded = false
    @State private var creating = false
    /// The group a creation opened, pushed as its page.
    @State private var opened: OpenedGroup?

    var body: some View {
        List {
            Section {
                if !loaded {
                    ProgressView().frame(maxWidth: .infinity)
                } else if let said = read?.said ?? (read == nil ? Msg.membersLoadFailed : nil) {
                    Text(say(said)).accessibilityIdentifier("groups-said")
                    Button(say(.peopleRetry)) { Task { await load() } }.accessibilityIdentifier("groups-retry")
                } else if let read {
                    ForEach(read.rows, id: \.scopeId) { row in
                        NavigationLink { GroupPageView(model: model, scopeId: row.scopeId) } label: { GroupRowView(row: row) }
                            .accessibilityIdentifier("group-\(row.scopeId)")
                    }
                }
            }
        }
        .navigationTitle(say(.groupsTitle))
        .accessibilityIdentifier("groups")
        .createAction(say(.groupsCreateTitle), shown: read?.canCreate == true) { creating = true }
        // read again each time the list shows: a page pushed from it renames, deletes or restores
        .task { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $creating) {
            CreateGroupSheet(model: model) { scopeId in
                creating = false
                opened = OpenedGroup(scopeId: scopeId)
                Task { await load() }
            }
        }
        .navigationDestination(item: $opened) { g in GroupPageView(model: model, scopeId: g.scopeId) }
    }

    private func load() async {
        read = try? await model.groups?.list()
        loaded = true
    }
}

/// A group to push, as a destination's item.
private struct OpenedGroup: Identifiable, Hashable {
    let scopeId: String
    var id: String { scopeId }
}

/// One group's row: its name (or core's word for none), the caller's role, and what core says of its state.
private struct GroupRowView: View {
    let row: TenantGroups.GroupRow

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(row.titleWord.map { say($0) } ?? row.name)
            Text(say(row.owner ? .membersRoleOwner : .membersRoleMember)).font(.subheadline).foregroundStyle(Role.muted)
            if row.deleted {
                // the web's `groups.pendingDeletion`: the day it is deleted (core's `GroupRow.deletion`)
                Text(row.deletion.map { say(notice: $0) } ?? say(.groupsPendingSub)).font(.footnote).foregroundStyle(Role.danger).accessibilityIdentifier("group-deleted")
            }
            if row.behind {
                Text(say(.membersPending)).font(.footnote).foregroundStyle(Role.muted).accessibilityIdentifier("group-behind")
            }
        }
    }
}

/// *New group* (the web's `CreateGroupDialog`): the name, then *Create*; a refusal says so in the sheet.
private struct CreateGroupSheet: View {
    let model: AppModel
    let created: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var working = false
    @State private var problem: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(say(.groupsNameLabel), text: $name)
                        .accessibilityIdentifier("group-create-name")
                        .onSubmit(create)
                        .disabled(working)
                } header: {
                    Text(say(.groupsNameLabel))
                } footer: {
                    if let problem { Text(problem).foregroundStyle(Role.danger).accessibilityIdentifier("group-create-problem") }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.groupsCreateTitle))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() }.disabled(working) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.folderCreate), action: create)
                        .disabled(working || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("group-create-confirm")
                }
            }
            .overlay { if working { ProgressView() } }
        }
        .sheetFrame(minWidth: 420, minHeight: 240)
    }

    private func create() {
        guard !working, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        working = true
        problem = nil
        let text = name
        Task {
            let outcome = try? await model.groups?.create(name: text)
            working = false
            if let outcome, outcome.done, let scopeId = outcome.scopeId {
                created(scopeId)
            } else {
                problem = outcome?.notice.map { say(notice: $0) } ?? say(.groupsCreateFailed)
            }
        }
    }
}

/// A group's page (the web's `GroupMembersPanel`): its header ([ScopeHeader]: the group's icon, which an owner sets or
/// removes) and an owner's *Rename*, a deleted group's day, a member's read-only note, an owner's *Add* by username, the
/// members with their trust badges and the acts core offers on each — its menu and its context menu —, where core's
/// `wholeActs` the *Links* and *New lock* sections ([ScopeLinksAndLock]), and an owner's danger zone: *Delete group* after
/// its question, or a deleted group's *Restore group*. The page reads itself (`.task`) and again after every act that went
/// through; what an act says shows under the members.
struct GroupPageView: View {
    let model: AppModel
    let scopeId: String
    @State private var page: TenantGroups.GroupPage?
    @State private var loading = true
    @State private var working = false
    @State private var said: String?
    @State private var username = ""
    @State private var renaming = false
    @State private var removing: GroupRemoval?
    @State private var revokeLinks = false
    @State private var confirmingDelete = false
    /// The group's icon opened on this device (core's `ScopeIcons.icon`); nil shows its initial.
    @State private var icon: Data?
    @State private var choosingIcon = false
    @State private var iconNotImage = false
    @State private var removingIcon = false
    @State private var askingLinks = false
    @State private var askingLock = false

    var body: some View {
        List {
            if loading && page == nil {
                Section { HStack(spacing: 12) { ProgressView(); Text(say(.membersLoading)) } }
            } else if loadFailed {
                Section {
                    Text(say(.membersLoadFailed)).accessibilityIdentifier("group-said")
                    Button(say(.peopleRetry)) { Task { await load() } }.accessibilityIdentifier("group-retry")
                }
            } else if let page {
                content(page)
            }
        }
        .environment(\.avatars, model.avatars)
        .navigationTitle(title)
        .accessibilityIdentifier("group-page")
        .overlay(alignment: .top) { if working { ProgressView().padding(8) } }
        .task { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $renaming) {
            RenameGroupSheet(draft: page?.name ?? "") { text in
                renaming = false
                act { try await model.groups?.rename(scopeId: scopeId, name: text) }
            }
        }
        .sheet(item: $removing) { r in
            RemoveGroupMemberSheet(revokeLinks: $revokeLinks, confirm: {
                removing = nil
                let userId = r.userId
                let links = revokeLinks
                act { try await model.groups?.remove(scopeId: scopeId, userId: userId, revokeLinks: links) }
            }, cancel: { removing = nil })
        }
        // the question's action reads only the page's own id, never state the dismissal clears
        .alert(say(.groupsDeleteConfirm), isPresented: $confirmingDelete) {
            Button(say(.groupsDeleteConfirmYes), role: .destructive) {
                act { try await model.groups?.delete(scopeId: scopeId) }
            }
            .accessibilityIdentifier("group-delete-confirm")
            Button(say(.signinCancel), role: .cancel) {}
        } message: {
            Text(say(.groupsDeleteSub))
        }
        .squarePicture($choosingIcon, title: say(icon == nil ? .spaceIconAdd : .spaceIconChange), notImage: $iconNotImage) { rgba in
            iconAct { await model.setScopeIcon(scopeId, rgba: rgba) }
        }
        .dangerConfirm($removingIcon, title: say(.spaceIconRemove), message: say(.spaceIconRemoveConfirm), action: say(.spaceIconRemove)) {
            iconAct { await model.clearScopeIcon(scopeId) }
        }
        .dangerConfirm($askingLinks, title: say(.spaceLinksRevokeAll), message: say(.spaceLinksRevokeAllConfirm), action: say(.spaceLinksRevokeAll)) {
            act { try await model.groups?.revokeAllLinks(scopeId: scopeId) }
        }
        .dangerConfirm($askingLock, title: say(.spaceLockConfirmTitle), message: say(.spaceLockConfirmGroup), action: say(.spaceLockAction)) {
            act { try await model.groups?.doNewLock(scopeId: scopeId) }
        }
    }

    private var title: String {
        guard let page, !page.name.isEmpty else { return say(.groupsUntitled) }
        return page.name
    }

    private var loadFailed: Bool { page == nil || page?.said == Msg.membersLoadFailed }

    @ViewBuilder private func content(_ page: TenantGroups.GroupPage) -> some View {
        Section {
            ScopeHeader(
                name: title, icon: icon, owner: page.owner, working: working, notImage: iconNotImage,
                choose: { choosingIcon = true }, remove: { removingIcon = true }
            )
            SpaceStatusLines(lines: page.status, working: working) {
                working = true
                said = nil
                Task {
                    if let r = try? await model.groupRequeue(scopeId) {
                        said = r.notice.map { say(notice: $0) }
                        if r.done { await load() }
                    }
                    working = false
                }
            }
            if page.deleted {
                // a deleted group's day (core's `GroupPage.deletion`), the web's warning line above the roster
                Text(page.deletion.map { say(notice: $0) } ?? say(.groupsPendingSub))
                    .font(.footnote).foregroundStyle(Role.danger).accessibilityIdentifier("group-deleted")
            }
            if page.owner {
                Button { renaming = true } label: { Label(say(.groupsRename), systemImage: "pencil") }
                    .disabled(working)
                    .accessibilityIdentifier("group-rename")
            }
        } footer: {
            if let note = page.readonlyNote { Text(say(note)).accessibilityIdentifier("group-readonly") }
        }
        if page.owner {
            // an owner adds an existing member of the space by their username (the web's inline form)
            Section {
                HStack(spacing: 12) {
                    TextField(say(.groupsUsernamePlaceholder), text: $username)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                        .onSubmit(add)
                        .accessibilityIdentifier("group-add-username")
                    Button(say(.groupsAddMember), action: add)
                        .disabled(working || username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("group-add")
                }
            } footer: {
                Text(say(.groupsAddMemberIntro))
            }
        }
        Section {
            if let empty = page.said {
                Text(say(empty)).foregroundStyle(Role.muted)
            }
            ForEach(page.members, id: \.userId) { m in
                GroupMemberRowView(member: m, working: working, role: { owner in
                    let userId = m.userId
                    act { try await model.groups?.setRole(scopeId: scopeId, userId: userId, owner: owner) }
                }, remove: {
                    revokeLinks = false
                    removing = GroupRemoval(userId: m.userId)
                }, rescind: {
                    let userId = m.userId
                    act { try await model.groups?.rescind(scopeId: scopeId, userId: userId) }
                })
            }
        } header: {
            Text(say(.groupsMembers))
        } footer: {
            VStack(alignment: .leading, spacing: 8) {
                Text(say(.groupsMembersSub))
                if let said { Text(said).foregroundStyle(.primary).accessibilityIdentifier("group-notice") }
            }
        }
        if page.wholeActs {
            ScopeLinksAndLock(lockSub: say(.spaceLockSubGroup), working: working, revoke: { askingLinks = true }, lock: { askingLock = true })
        }
        if page.owner {
            Section {
                if page.deleted {
                    Button { act { try await model.groups?.restore(scopeId: scopeId) } } label: {
                        Label(say(.groupsRestore), systemImage: "arrow.uturn.backward")
                    }
                    .disabled(working)
                    .accessibilityIdentifier("group-restore")
                } else {
                    Button(role: .destructive) { confirmingDelete = true } label: {
                        Label(say(.groupsDeleteGroup), systemImage: "trash")
                    }
                    .disabled(working)
                    .accessibilityIdentifier("group-delete")
                }
            } header: {
                Text(say(.groupsDangerZone))
            } footer: {
                Text(say(page.deleted ? .groupsPendingSub : .groupsDeleteSub))
            }
        }
    }

    private func load() async {
        loading = true
        page = try? await model.groups?.page(scopeId: scopeId)
        loading = false
        icon = await model.scopeIcon(scopeId)
    }

    /// An act on the group's icon: what it said shows under the members; one that went through opens the icon anew.
    private func iconAct(_ run: @escaping () async -> ScopeIcons.Outcome?) {
        working = true
        said = nil
        Task {
            let outcome = await run()
            said = outcome.map { say(notice: $0.notice) } ?? say(.spaceIconFailed)
            if outcome?.done == true { icon = await model.scopeIcon(scopeId) }
            working = false
        }
    }

    private func add() {
        let handle = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !working, !handle.isEmpty else { return }
        act { try await model.groups?.add(scopeId: scopeId, username: handle) } went: { username = "" }
    }

    /// One act: its words said under the members, the page read anew where it went through.
    private func act(_ run: @escaping () async throws -> TenantGroups.Outcome?, went: @escaping () -> Void = {}) {
        working = true
        said = nil
        Task {
            let outcome = try? await run()
            if let outcome {
                said = outcome.notice.map { say(notice: $0) }
                if outcome.done {
                    went()
                    await load()
                }
            } else {
                said = say(.membersActionFailed)
            }
            working = false
        }
    }
}

/// The member being removed, as a sheet's item.
private struct GroupRemoval: Identifiable {
    let userId: String
    var id: String { userId }
}

/// *Rename* (the web's inline rename, a sheet here: a name asked in an alert is lost on a Mac): the name, then *Save*.
private struct RenameGroupSheet: View {
    @State var draft: String
    let save: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                TextField(say(.groupsRenamePlaceholder), text: $draft)
                    .accessibilityIdentifier("group-rename-name")
                    .onSubmit(submit)
            }
            .formStyle(.grouped)
            .navigationTitle(say(.groupsRename))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.shareSave), action: submit)
                        .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("group-rename-save")
                }
            }
        }
        .sheetFrame(minWidth: 420, minHeight: 200)
    }

    private func submit() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        save(text)
    }
}

/// The removal's question (the web's inline confirm): it rotates the group's key; the opt-in turns off every link of the
/// group too. Cancelled only by its Cancel, so the confirm never finds its row cleared.
private struct RemoveGroupMemberSheet: View {
    @Binding var revokeLinks: Bool
    let confirm: () -> Void
    let cancel: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section { Text(say(.groupsRemoveConfirm)) }
                Section {
                    Toggle(isOn: $revokeLinks) { Text(say(.groupsRemoveRevokeLinks)) }
                        .accessibilityIdentifier("group-remove-links")
                } footer: {
                    Text(say(.groupsRemoveRevokeLinksHint))
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.groupsRemove))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel), action: cancel) }
                ToolbarItem(placement: .destructiveAction) {
                    Button(say(.groupsRemoveConfirmYes), role: .destructive, action: confirm).accessibilityIdentifier("group-remove-confirm")
                }
            }
        }
        .interactiveDismissDisabled()
        .sheetFrame(minWidth: 420, minHeight: 320)
    }
}

/// One member's row: the avatar, the title, `@username` and the role, the badges, and the acts core offers.
private struct GroupMemberRowView: View {
    let member: TenantGroups.GroupMember
    let working: Bool
    let role: (Bool) -> Void
    let remove: () -> Void
    let rescind: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            PersonAvatar(name: member.realName ?? member.username, userId: member.userId, size: 40)
            VStack(alignment: .leading, spacing: 4) {
                Text(member.title)
                Text(joinedLine([member.usernameLine, say(member.roleWord)].compactMap { $0 }))
                    .font(.subheadline).foregroundStyle(Role.muted)
                HStack(spacing: 8) {
                    Text(say(member.statusWord)).font(.footnote).foregroundStyle(member.pendingConsent ? Role.muted : Role.accent)
                    if let pending = member.pendingWord {
                        Text(say(pending)).font(.footnote).foregroundStyle(Role.muted).accessibilityIdentifier("group-member-pending")
                    }
                    TrustBadge(word: member.badgeWord, anomaly: member.badgeNote != nil)
                }
                TrustNote(note: member.badgeNote)
            }
            Spacer()
            if offers {
                Menu { acts } label: { Image(systemName: "ellipsis.circle").accessibilityLabel(member.title) }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                    .disabled(working)
                    .accessibilityIdentifier("group-member-menu-\(member.userId)")
            }
        }
        .contextMenu { if offers && !working { acts } }
        .accessibilityIdentifier("group-member-\(member.userId)")
    }

    private var offers: Bool { member.promote || member.demote || member.remove || member.rescind }

    @ViewBuilder private var acts: some View {
        if member.promote { Button { role(true) } label: { Label(say(.groupsPromote), systemImage: "arrow.up.circle") } }
        if member.demote { Button { role(false) } label: { Label(say(.groupsDemote), systemImage: "arrow.down.circle") } }
        if member.rescind { Button(action: rescind) { Label(say(.groupsRescind), systemImage: "xmark.circle") } }
        if member.remove { Button(role: .destructive, action: remove) { Label(say(.groupsRemove), systemImage: "person.badge.minus") } }
    }
}
