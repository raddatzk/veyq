package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
    /** The space's icon opened on this device (core's `ScopeIcons.icon`); null shows its initial. */
    val icon: ByteArray? = null,
)

/**
 * An owner's acts on a whole scope — the space's on Members, a group's on its page: its icon set from the square crop's
 * pixels or removed (core's `ScopeIcons`), every link turned off and a new lock (core's `TenantMembers` / `TenantGroups`).
 */
internal class ScopeActions(
    val setIcon: (ByteArray) -> Unit,
    val clearIcon: () -> Unit,
    val revokeLinks: () -> Unit,
    val newLock: () -> Unit,
    /** *Start again* on a failed compromise rotation's notice. */
    val requeue: () -> Unit,
)

/**
 * The status lines above a space's member list (core's `SpaceStatus.lines`, the web's panels): a warning card for a stuck
 * rotation — a failed one with *Start again* —, an information card for the resealing or the re-keying hint.
 */
@Composable
internal fun SpaceStatusLines(lines: List<me.raddatz.veyq.mobile.session.SpaceStatusLine>, enabled: Boolean, onRequeue: () -> Unit) {
    for (line in lines) {
        val colors = if (line.warn) {
            androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
        } else {
            androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        androidx.compose.material3.Card(colors = colors, modifier = Modifier.fillMaxWidth().testTag(if (line.warn) "space-status-warn" else "space-status-info")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(say(line.word), style = MaterialTheme.typography.bodyMedium)
                if (line.requeue) OutlinedButton(onClick = onRequeue, enabled = enabled, modifier = Modifier.testTag("space-status-requeue")) {
                    Text(stringResource(R.string.members_compromise_rotation_requeue))
                }
            }
        }
    }
}

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
 * says; the removal asks first, with the options to turn off every link of the space and to give it a new lock. What an act says shows as a snackbar.
 * An owner also invites (*Invite member*, [InviteDialog]) and sees the pending invites under the roster ([PendingInvites],
 * uc.spaces.manage-member-invites), each with its acts in its overflow menu. Above the roster the space's header
 * ([ScopeHeader]: its icon, which an owner sets or removes, uc.spaces.set-space-icon); on each row its trust badge; and an
 * owner's *Links* and *New lock* below ([LinksAndLock]). The removal's question also offers the new lock.
 */
@Composable
internal fun MembersDialog(
    page: MembersPage,
    onRetry: () -> Unit,
    onRole: (String, Boolean) -> Unit,
    onRemove: (String, Boolean, Boolean) -> Unit,
    invites: InviteActions,
    scope: ScopeActions,
    spaceName: String,
    model: AppModel,
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
                ScopeHeader(
                    roster.tenantName.ifEmpty { spaceName }, page.icon, owner = roster.ownerActs, enabled = !page.working,
                    onSetIcon = scope.setIcon, onClearIcon = scope.clearIcon, model = model,
                )
                SpaceStatusLines(roster.status, enabled = !page.working, onRequeue = scope.requeue)
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
                if (roster.ownerActs) LinksAndLock(
                    enabled = !page.working, lockSub = stringResource(R.string.space_lock_sub), lockConfirm = stringResource(R.string.space_lock_confirm),
                    onRevokeLinks = scope.revokeLinks, onNewLock = scope.newLock,
                )
                if (page.working) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
    if (inviting) InviteDialog(working = page.working, onInvite = invites.invite, onClose = { inviting = false })
    page.shown?.let { InviteResultDialog(it, spaceName, onClose = invites.shownSeen) }
    removing?.let { row -> RemoveMemberDialog(onConfirm = { links, lock -> removing = null; onRemove(row.userId, links, lock) }, onDismiss = { removing = null }) }
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
                    row.badgeWord?.let { Badge(say(it), "member-trust") }
                }
                row.badgeNote?.let { TrustNote(say(it)) }
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

/** The trust badge's note (an anomaly's: nothing is shared with that key), under the row's badges. */
@Composable
internal fun TrustNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("member-trust-note"))
}

/**
 * The removal's question (the web's `askWithOptions`): its words, and two opt-ins, each unchecked — turn off every link of
 * the space, and give the space a new lock (run after the removal, the removed member left out of it).
 */
@Composable
private fun RemoveMemberDialog(onConfirm: (Boolean, Boolean) -> Unit, onDismiss: () -> Unit) {
    var links by remember { mutableStateOf(false) }
    var lock by remember { mutableStateOf(false) }
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
                Row(Modifier.clickable { lock = !lock }.testTag("member-remove-lock"), verticalAlignment = Alignment.Top) {
                    Checkbox(checked = lock, onCheckedChange = { lock = it })
                    Column {
                        Text(stringResource(R.string.space_lock_remove_option))
                        Text(stringResource(R.string.space_lock_remove_option_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(links, lock) }, modifier = Modifier.testTag("member-remove-confirm")) {
                Text(stringResource(R.string.members_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.signin_cancel)) } },
    )
}

/**
 * A scope's identity header (the web's `TenantAvatar` beside the name): its icon opened on this device, else its initial,
 * and its [name]. An [owner] adds or changes the icon through the app's one square crop ([AvatarCropSheet]) and removes it
 * after a question; every member sees it.
 */
@Composable
internal fun ScopeHeader(
    name: String, icon: ByteArray?, owner: Boolean, enabled: Boolean,
    onSetIcon: (ByteArray) -> Unit, onClearIcon: () -> Unit, model: AppModel,
) {
    var cropping by remember { mutableStateOf<android.net.Uri?>(null) }
    var removing by remember { mutableStateOf(false) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) cropping = uri }
    Row(Modifier.fillMaxWidth().testTag("scope-header"), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        PictureAvatar(icon, name, 56.dp, "scope-icon")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("scope-name"))
            if (owner) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = enabled, modifier = Modifier.testTag("scope-icon-choose"),
                ) {
                    Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (icon != null) R.string.space_icon_change else R.string.space_icon_add))
                }
                if (icon != null) TextButton(onClick = { removing = true }, enabled = enabled, modifier = Modifier.testTag("scope-icon-remove")) {
                    Text(stringResource(R.string.space_icon_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    val cropTitle = stringResource(if (icon != null) R.string.space_icon_change else R.string.space_icon_add)
    cropping?.let { uri -> AvatarCropSheet(uri, model, onUse = onSetIcon, onClose = { cropping = null }, title = cropTitle) }
    if (removing) Ask(
        icon = Icons.Outlined.Delete, title = stringResource(R.string.space_icon_remove), text = stringResource(R.string.space_icon_remove_confirm),
        accept = stringResource(R.string.space_icon_remove), tag = "scope-icon-remove-confirm",
        onAccept = { removing = false; onClearIcon() }, onDismiss = { removing = false },
    )
}

/**
 * An owner's *Links* and *New lock* sections (the web's panels'): *Turn off every link* and *Give it a new lock*, each a
 * danger button behind its question; [lockSub] and [lockConfirm] are the space's or the group's words.
 */
@Composable
internal fun LinksAndLock(enabled: Boolean, lockSub: String, lockConfirm: String, onRevokeLinks: () -> Unit, onNewLock: () -> Unit) {
    var asking by remember { mutableStateOf<String?>(null) }
    HorizontalDivider()
    ScopeSection(stringResource(R.string.space_links_title), stringResource(R.string.space_links_sub))
    DangerButton(Icons.Outlined.LinkOff, stringResource(R.string.space_links_revoke_all), enabled, "scope-links-revoke") { asking = "links" }
    HorizontalDivider()
    ScopeSection(stringResource(R.string.space_lock_title), lockSub)
    DangerButton(Icons.Outlined.Lock, stringResource(R.string.space_lock_action), enabled, "scope-new-lock") { asking = "lock" }
    when (asking) {
        "links" -> Ask(
            icon = Icons.Outlined.LinkOff, title = stringResource(R.string.space_links_revoke_all), text = stringResource(R.string.space_links_revoke_all_confirm),
            accept = stringResource(R.string.space_links_revoke_all), tag = "scope-links-revoke-confirm",
            onAccept = { asking = null; onRevokeLinks() }, onDismiss = { asking = null },
        )
        "lock" -> Ask(
            icon = Icons.Outlined.Lock, title = stringResource(R.string.space_lock_confirm_title), text = lockConfirm,
            accept = stringResource(R.string.space_lock_action), tag = "scope-new-lock-confirm",
            onAccept = { asking = null; onNewLock() }, onDismiss = { asking = null },
        )
    }
}

@Composable
private fun ScopeSection(title: String, sub: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DangerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.testTag(tag),
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

/** A question before a danger act: its words, the act in the error colour, *Cancel*. */
@Composable
private fun Ask(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, accept: String, tag: String, onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, null) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onAccept, modifier = Modifier.testTag(tag)) { Text(accept, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.signin_cancel)) } },
    )
}
