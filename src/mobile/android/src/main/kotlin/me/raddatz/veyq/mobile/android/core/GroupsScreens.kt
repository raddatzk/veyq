package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.session.TenantGroups
import me.raddatz.veyq.mobile.words.Notice

/**
 * What Settings' *Groups* shows (core's `SettingsSection.GROUPS` over `TenantGroups.list`): still reading, the list read,
 * what the last act said ([said], with its [round] so the same words said twice show twice) and whether an act is under way.
 */
internal data class GroupsPage(
    val loading: Boolean,
    val read: TenantGroups.Groups? = null,
    val said: Notice? = null,
    val round: Int = 0,
    val working: Boolean = false,
)

/** One group's page while it is open (core's `TenantGroups.page`), over the list. */
internal data class GroupPageState(
    val scopeId: String,
    val loading: Boolean,
    val page: TenantGroups.GroupPage? = null,
    val said: Notice? = null,
    val round: Int = 0,
    val working: Boolean = false,
    /** The group's icon opened on this device (core's `ScopeIcons.icon`); null shows its initial. */
    val icon: ByteArray? = null,
)

/** Settings' *Group invitations* (core's `TenantGroups.invitations`); [busy] the proposal being answered. */
internal data class InvitationsPage(
    val loading: Boolean,
    val read: TenantGroups.Invitations? = null,
    val said: Notice? = null,
    val round: Int = 0,
    val busy: String? = null,
)

/** An owner's acts on a group's page (core's `TenantGroups` through the app's model). */
internal class GroupActions(
    val retry: () -> Unit,
    val rename: (String) -> Unit,
    /** *Add member* by username; the callback hears whether it went through (the field then clears). */
    val add: (String, (Boolean) -> Unit) -> Unit,
    val setRole: (String, Boolean) -> Unit,
    val remove: (String, Boolean) -> Unit,
    val rescind: (String) -> Unit,
    val delete: () -> Unit,
    val restore: () -> Unit,
)

/** Core's [notice] on [snackbar] as the notice host lives it, each time [round] moves. */
@Composable
private fun Said(snackbar: SnackbarHostState, notice: Notice?, round: Int) {
    val context = LocalContext.current
    LaunchedEffect(round) { notice?.let { snackbar.show(context, it) } }
}

@Composable
private fun Reading(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text(text)
    }
}

@Composable
private fun SectionHead(title: String, sub: String?) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * Settings' *Groups* (the web's `groups` category, en.spaces.group-scope): the caller's groups in the open space — each its
 * name (or *Untitled group*), the caller's role there, the deletion under way or the lock it waits for — and *New group*
 * where the space lets its members create one (core's `canCreate`, the web's `CreateGroupDialog`). A row opens its page.
 */
@Composable
internal fun GroupsDialog(page: GroupsPage, onRetry: () -> Unit, onOpen: (String) -> Unit, onCreate: (String) -> Unit, onClose: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    Said(snackbar, page.said, page.round)
    FlowDialog(title = stringResource(R.string.groups_title), secure = false, tag = "groups", onClose = onClose, snackbar = snackbar) {
        val read = page.read
        when {
            page.loading || read == null -> Reading(stringResource(R.string.members_loading))
            read.said != null -> {
                Text(say(read.said!!), modifier = Modifier.testTag("groups-said"))
                TextButton(onClick = onRetry, modifier = Modifier.testTag("groups-retry")) { Text(stringResource(R.string.people_retry)) }
            }
            else -> {
                if (read.canCreate) FilledTonalButton(onClick = { creating = true }, enabled = !page.working, modifier = Modifier.testTag("groups-create")) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.groups_create_title))
                }
                if (read.rows.isEmpty()) Text(stringResource(R.string.groups_none_owned), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("groups-empty"))
                Column(Modifier.testTag("groups-list")) {
                    read.rows.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider()
                        ListItem(
                            leadingContent = { Icon(Icons.Outlined.Groups, null) },
                            headlineContent = { Text(row.titleWord?.let { say(it) } ?: row.name) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(stringResource(if (row.owner) R.string.members_role_owner else R.string.members_role_member))
                                    // the web's pending-deletion state: the day it is deleted (core's `GroupRow.deletion`)
                                    if (row.deleted) Text(
                                        row.deletion?.let { say(it) } ?: stringResource(R.string.groups_pending_sub),
                                        color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("group-deleted"),
                                    )
                                    if (row.behind) Text(stringResource(R.string.members_pending), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("group-behind"))
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !page.working) { onOpen(row.scopeId) }.testTag("group-${row.scopeId}"),
                        )
                    }
                }
            }
        }
    }
    if (creating) CreateGroupDialog(working = page.working, onCreate = { name -> creating = false; onCreate(name) }, onDismiss = { creating = false })
}

/** *New group* (the web's `CreateGroupDialog`): its name, then *Create*. */
@Composable
private fun CreateGroupDialog(working: Boolean, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val ok = name.isNotBlank() && !working
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Groups, null) },
        title = { Text(stringResource(R.string.groups_create_title)) },
        text = {
            OutlinedTextField(
                name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.groups_name_label)) },
                modifier = Modifier.fillMaxWidth().testTag("group-create-name"),
            )
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim()) }, enabled = ok, modifier = Modifier.testTag("group-create-confirm")) { Text(stringResource(R.string.create_pot_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.signin_cancel)) } },
    )
}

/**
 * One group's page (the web's `GroupMembersPanel`; uc.spaces.add-group-member, uc.spaces.manage-group-members,
 * uc.spaces.rename-group, uc.spaces.delete-group): its name, with *Rename* for an owner; its members, each row's acts in
 * its overflow menu as core offers them — *Make co-owner*, *Remove co-owner*, *Remove* (the removal-rotation, asked first
 * with the opt-in to turn off every link of the group) and *Withdraw* for a proposal not yet answered —; *Add member* by
 * username; the owner's *Links* and *New lock* ([LinksAndLock], core's `wholeActs`) and danger zone: *Delete group*, asked
 * first, or while it is being deleted *Restore group*. Its header ([ScopeHeader]) shows the group's icon, which an owner
 * sets or removes; each row its trust badge. A member reads the page with core's note why nothing on it changes. A deleted
 * group is read-only — no add, no row acts — and says the day it is deleted.
 */
@Composable
internal fun GroupPageDialog(state: GroupPageState, actions: GroupActions, scope: ScopeActions, model: AppModel, onClose: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    Said(snackbar, state.said, state.round)
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<TenantGroups.GroupMember?>(null) }
    val page = state.page
    val title = page?.name?.takeIf { it.isNotEmpty() } ?: if (page != null && page.said == null) stringResource(R.string.groups_untitled) else stringResource(R.string.groups_title)
    FlowDialog(title = title, secure = false, tag = "group-page", onClose = onClose, snackbar = snackbar) {
        when {
            state.loading || page == null -> Reading(stringResource(R.string.members_loading))
            page.said != null && page.members.isEmpty() && page.name.isEmpty() && !page.owner -> {
                Text(say(page.said!!), modifier = Modifier.testTag("group-said"))
                TextButton(onClick = actions.retry, modifier = Modifier.testTag("group-retry")) { Text(stringResource(R.string.people_retry)) }
            }
            else -> {
                val open = !state.working
                val acts = page.owner && !page.deleted
                ScopeHeader(
                    page.name.ifEmpty { stringResource(R.string.groups_untitled) }, state.icon, owner = page.owner, enabled = open,
                    onSetIcon = scope.setIcon, onClearIcon = scope.clearIcon, model = model,
                )
                SpaceStatusLines(page.status, enabled = open, onRequeue = scope.requeue)
                // a deleted group's day (core's `GroupPage.deletion`), the web's warning line above the roster
                if (page.deleted) Text(
                    page.deletion?.let { say(it) } ?: stringResource(R.string.groups_pending_sub),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("group-deleted"),
                )
                if (acts) OutlinedButton(onClick = { renaming = true }, enabled = open, modifier = Modifier.testTag("group-rename")) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.groups_rename))
                }
                page.readonlyNote?.let { Text(say(it), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("group-readonly")) }
                SectionHead(stringResource(R.string.groups_members), stringResource(R.string.groups_members_sub))
                if (acts) AddMember(open, actions.add)
                page.said?.let { Text(say(it), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("group-said")) }
                Column(Modifier.testTag("group-members")) {
                    page.members.forEachIndexed { i, m ->
                        if (i > 0) HorizontalDivider()
                        GroupMemberRow(m, acts = !page.deleted, enabled = open, onRole = actions.setRole, onRemove = { removing = m }, onRescind = actions.rescind)
                    }
                }
                if (page.wholeActs) LinksAndLock(
                    enabled = open, lockSub = stringResource(R.string.space_lock_sub_group), lockConfirm = stringResource(R.string.space_lock_confirm_group),
                    onRevokeLinks = scope.revokeLinks, onNewLock = scope.newLock,
                )
                if (page.owner) {
                    HorizontalDivider()
                    SectionHead(stringResource(R.string.groups_danger_zone), stringResource(if (page.deleted) R.string.groups_pending_sub else R.string.groups_delete_sub))
                    if (page.deleted) Button(onClick = actions.restore, enabled = open, modifier = Modifier.testTag("group-restore")) {
                        Icon(Icons.Outlined.Restore, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.groups_restore))
                    } else OutlinedButton(
                        onClick = { deleting = true }, enabled = open,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("group-delete"),
                    ) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.groups_delete_group))
                    }
                }
                if (state.working) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
    if (renaming) RenameGroupDialog(page?.name.orEmpty(), onSave = { renaming = false; actions.rename(it) }, onDismiss = { renaming = false })
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        icon = { Icon(Icons.Outlined.Delete, null) },
        title = { Text(stringResource(R.string.groups_delete_confirm)) },
        text = { Text(stringResource(R.string.groups_delete_sub)) },
        confirmButton = {
            TextButton(onClick = { deleting = false; actions.delete() }, modifier = Modifier.testTag("group-delete-confirm")) {
                Text(stringResource(R.string.groups_delete_confirm_yes), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.signin_cancel)) } },
    )
    removing?.let { m -> RemoveGroupMemberDialog(m.title, onConfirm = { links -> removing = null; actions.remove(m.userId, links) }, onDismiss = { removing = null }) }
}

/** *Add member* (owner-only): an existing member of the space by their username; the field clears once it went through. */
@Composable
private fun AddMember(enabled: Boolean, onAdd: (String, (Boolean) -> Unit) -> Unit) {
    var username by remember { mutableStateOf("") }
    val submit = { if (username.isNotBlank()) onAdd(username.trim()) { done -> if (done) username = "" } }
    Text(stringResource(R.string.groups_add_member_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            username, { username = it }, singleLine = true, enabled = enabled,
            placeholder = { Text(stringResource(R.string.groups_username_placeholder)) },
            modifier = Modifier.weight(1f).testTag("group-add-username"),
        )
        FilledTonalButton(onClick = submit, enabled = enabled && username.isNotBlank(), modifier = Modifier.testTag("group-add")) {
            Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.groups_add_member))
        }
    }
}

@Composable
private fun GroupMemberRow(
    m: TenantGroups.GroupMember, acts: Boolean, enabled: Boolean,
    onRole: (String, Boolean) -> Unit, onRemove: () -> Unit, onRescind: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val any = acts && (m.promote || m.demote || m.remove || m.rescind)
    ListItem(
        leadingContent = { PersonAvatar(m.realName ?: m.username, m.userId, 40.dp) },
        headlineContent = { Text(m.title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOfNotNull(m.usernameLine, say(m.roleWord)).joined())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge(say(m.statusWord), "group-member-status")
                    m.pendingWord?.let { Badge(say(it), "group-member-pending") }
                    m.badgeWord?.let { Badge(say(it), "member-trust") }
                }
                m.badgeNote?.let { TrustNote(say(it)) }
            }
        },
        trailingContent = if (any) {
            {
                Box {
                    IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag("group-member-menu-${m.userId}")) {
                        Icon(Icons.Outlined.MoreVert, m.title)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (m.promote) DropdownMenuItem(
                            text = { Text(stringResource(R.string.groups_promote)) },
                            leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) },
                            onClick = { menu = false; onRole(m.userId, true) },
                            modifier = Modifier.testTag("group-member-promote"),
                        )
                        if (m.demote) DropdownMenuItem(
                            text = { Text(stringResource(R.string.groups_demote)) },
                            leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) },
                            onClick = { menu = false; onRole(m.userId, false) },
                            modifier = Modifier.testTag("group-member-demote"),
                        )
                        if (m.rescind) DropdownMenuItem(
                            text = { Text(stringResource(R.string.groups_rescind)) },
                            leadingIcon = { Icon(Icons.Outlined.Close, null) },
                            onClick = { menu = false; onRescind(m.userId) },
                            modifier = Modifier.testTag("group-member-rescind"),
                        )
                        if (m.remove) DropdownMenuItem(
                            text = { Text(stringResource(R.string.groups_remove), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.PersonRemove, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onRemove() },
                            modifier = Modifier.testTag("group-member-remove"),
                        )
                    }
                }
            }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("group-member-${m.userId}"),
    )
}

/** *Rename*: the group's new name (core seals it under a fresh value key). */
@Composable
private fun RenameGroupDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Edit, null) },
        title = { Text(stringResource(R.string.groups_rename)) },
        text = {
            OutlinedTextField(
                name, { name = it }, singleLine = true, placeholder = { Text(stringResource(R.string.groups_rename_placeholder)) },
                modifier = Modifier.fillMaxWidth().testTag("group-rename-name"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("group-rename-save")) { Text(stringResource(R.string.entry_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.signin_cancel)) } },
    )
}

/** The removal's question (the web's inline confirm): its words, and the opt-in to turn off every link of the group. */
@Composable
private fun RemoveGroupMemberDialog(who: String, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var links by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PersonRemove, null) },
        title = { Text(who) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.groups_remove_confirm))
                Row(Modifier.clickable { links = !links }.testTag("group-remove-links"), verticalAlignment = Alignment.Top) {
                    Checkbox(checked = links, onCheckedChange = { links = it })
                    Column {
                        Text(stringResource(R.string.groups_remove_revoke_links))
                        Text(stringResource(R.string.groups_remove_revoke_links_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(links) }, modifier = Modifier.testTag("group-remove-confirm")) {
                Text(stringResource(R.string.groups_remove_confirm_yes), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.signin_cancel)) } },
    )
}

/**
 * Settings' *Group invitations* (the web's `GroupInvitationsView`, uc.spaces.consent-group-add): each proposal to the
 * caller — the group's name opened with their own identity (or *A group*), who invited them —, *Accept* or *Decline*.
 */
@Composable
internal fun GroupInvitationsDialog(page: InvitationsPage, onRetry: () -> Unit, onAccept: (String) -> Unit, onDecline: (String) -> Unit, onClose: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    Said(snackbar, page.said, page.round)
    FlowDialog(title = stringResource(R.string.invitations_title), secure = false, tag = "invitations", onClose = onClose, snackbar = snackbar) {
        Text(stringResource(R.string.invitations_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val read = page.read
        when {
            page.loading || read == null -> Reading(stringResource(R.string.invitations_loading))
            read.said != null -> {
                Text(say(read.said!!), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("invitations-said"))
                if (read.rows.isEmpty() && read.said != me.raddatz.veyq.mobile.words.Msg.INVITATIONS_EMPTY) {
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("invitations-retry")) { Text(stringResource(R.string.people_retry)) }
                }
            }
            else -> Column(Modifier.testTag("invitations-list")) {
                read.rows.forEachIndexed { i, inv ->
                    if (i > 0) HorizontalDivider()
                    val busy = page.busy != null
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Groups, null) },
                        headlineContent = { Text(inv.titleWord?.let { say(it) } ?: inv.name.orEmpty()) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(say(inv.addedByLine))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onAccept(inv.scopeId) }, enabled = !busy, modifier = Modifier.testTag("invitation-accept")) {
                                        Text(stringResource(R.string.invitations_accept))
                                    }
                                    OutlinedButton(onClick = { onDecline(inv.scopeId) }, enabled = !busy, modifier = Modifier.testTag("invitation-decline")) {
                                        Text(stringResource(R.string.invitations_decline))
                                    }
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.testTag("invitation-${inv.scopeId}"),
                    )
                }
            }
        }
    }
}
