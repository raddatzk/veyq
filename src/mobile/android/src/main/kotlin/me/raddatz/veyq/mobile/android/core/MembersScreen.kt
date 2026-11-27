package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.session.TenantMembers
import me.raddatz.veyq.mobile.words.Notice

/**
 * What Settings' Members shows: still reading, the roster core's `TenantMembers.read` read, what the last act said
 * ([said], with its [round] so the same words said twice show twice) and whether an act is under way.
 */
internal data class MembersPage(
    val loading: Boolean,
    val roster: TenantMembers.Roster? = null,
    val said: Notice? = null,
    val round: Int = 0,
    val working: Boolean = false,
    /** What an invite or a re-issue made, shown once over the page (its link or its code); null where none is. */
    val shown: me.raddatz.veyq.mobile.session.TenantInvites.Outcome? = null,
)

/** The owner's acts on invites (core's `TenantInvites` through the app's model). */
internal class InviteActions(
    val invite: (me.raddatz.veyq.mobile.session.TenantInvites.Way, String?, String, String?, (me.raddatz.veyq.mobile.session.TenantInvites.Outcome) -> Unit) -> Unit,
    val setLabel: (String, String) -> Unit,
    val reissue: (me.raddatz.veyq.mobile.session.TenantInvites.PendingInvite) -> Unit,
    val revoke: (String) -> Unit,
    val shownSeen: () -> Unit,
)

/**
 * Settings' *Members* (core's `SettingsSection.MEMBERS` over `TenantMembers`, the web's `TenantMembersPanel` roster;
 * uc.spaces.remove-tenant-member): every member with their role and state, in a full-screen window over the page as
 * Who's who is. A row's acts — *Make co-owner*, *Remove co-owner*, *Remove member* — are its overflow menu, offered as core
 * says; the removal asks first, with the option to turn off every link of the space. What an act says shows as a snackbar.
 * An owner also invites (*Invite member*, [InviteDialog]) and sees the pending invites under the roster ([PendingInvites],
 * uc.spaces.manage-member-invites), each with its acts in its overflow menu.
 */
@Composable
internal fun MembersDialog(
    page: MembersPage,
    onRetry: () -> Unit,
    onRole: (String, Boolean) -> Unit,
    onRemove: (String, Boolean) -> Unit,
    invites: InviteActions,
    spaceName: String,
    onClose: () -> Unit,
) {
    var inviting by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val said = page.said?.let { say(it) }
    LaunchedEffect(page.round) { said?.let { snackbar.showSnackbar(it) } }
    var removing by remember { mutableStateOf<TenantMembers.RosterMember?>(null) }
    FlowDialog(title = stringResource(R.string.members_title), secure = false, tag = "members", onClose = onClose, snackbar = snackbar) {
        Text(stringResource(R.string.members_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val roster = page.roster
        when {
            page.loading || roster == null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.members_loading))
            }
            roster.said != null -> {
                Text(say(roster.said!!), modifier = Modifier.testTag("members-said"))
                if (roster.retry) TextButton(onClick = onRetry, modifier = Modifier.testTag("members-retry")) { Text(stringResource(R.string.people_retry)) }
            }
            else -> {
                if (roster.owner) androidx.compose.material3.FilledTonalButton(
                    onClick = { inviting = true }, enabled = !page.working, modifier = Modifier.testTag("members-invite"),
                ) {
                    Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.members_invite))
                }
                Column(Modifier.testTag("members-list")) {
                    roster.rows.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider()
                        MemberRow(row, enabled = !page.working, onRole = onRole, onRemove = { removing = row })
                    }
                }
                if (roster.owner && roster.invites.isNotEmpty()) PendingInvites(roster.invites, enabled = !page.working, invites)
            }
        }
    }
    if (inviting) InviteDialog(working = page.working, onInvite = invites.invite, onClose = { inviting = false })
    page.shown?.let { InviteResultDialog(it, spaceName, onClose = invites.shownSeen) }
    removing?.let { row -> RemoveMemberDialog(onConfirm = { links -> removing = null; onRemove(row.userId, links) }, onDismiss = { removing = null }) }
}

@Composable
private fun MemberRow(row: TenantMembers.RosterMember, enabled: Boolean, onRole: (String, Boolean) -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { PersonAvatar(row.realName ?: row.username, row.userId, 40.dp) },
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOfNotNull(row.usernameLine, row.email, say(row.roleWord)).joined())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge(say(row.statusWord), "member-status")
                    row.pendingWord?.let { Badge(say(it), "member-pending") }
                }
            }
        },
        trailingContent = if (row.promote || row.demote || row.remove) {
            {
                Box {
                    IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag("member-menu-${row.userId}")) {
                        Icon(Icons.Outlined.MoreVert, row.title)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (row.promote) DropdownMenuItem(
                            text = { Text(stringResource(R.string.members_promote)) },
                            leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) },
                            onClick = { menu = false; onRole(row.userId, true) },
                            modifier = Modifier.testTag("member-promote"),
                        )
                        if (row.demote) DropdownMenuItem(
                            text = { Text(stringResource(R.string.members_demote)) },
                            leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) },
                            onClick = { menu = false; onRole(row.userId, false) },
                            modifier = Modifier.testTag("member-demote"),
                        )
                        if (row.remove) DropdownMenuItem(
                            text = { Text(stringResource(R.string.members_remove), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.PersonRemove, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onRemove() },
                            modifier = Modifier.testTag("member-remove"),
                        )
                    }
                }
            }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("member-${row.userId}"),
    )
}

@Composable
internal fun Badge(label: String, tag: String) {
    SuggestionChip(onClick = {}, enabled = false, label = { Text(label) }, modifier = Modifier.testTag(tag))
}

/** The removal's question (the web's `askWithOptions`): its words, and the opt-in to turn off every link of the space. */
@Composable
private fun RemoveMemberDialog(onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var links by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PersonRemove, null) },
        title = { Text(stringResource(R.string.members_remove)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.members_remove_confirm))
                Row(Modifier.clickable { links = !links }.testTag("member-remove-links"), verticalAlignment = Alignment.Top) {
                    Checkbox(checked = links, onCheckedChange = { links = it })
                    Column {
                        Text(stringResource(R.string.members_remove_revoke_links))
                        Text(stringResource(R.string.members_remove_revoke_links_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(links) }, modifier = Modifier.testTag("member-remove-confirm")) {
                Text(stringResource(R.string.members_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.signin_cancel)) } },
    )
}
