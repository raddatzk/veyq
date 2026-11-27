package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.session.PasswordChange
import me.raddatz.veyq.mobile.session.TenantInvites
import me.raddatz.veyq.mobile.words.Notice

/**
 * *Invite member* (uc.spaces.add-member, the web's `InviteMemberDialog`): the two ways, a link first (ADR 0129 decision
 * 1) — the link's optional label, or an address with a generated code or a password the owner chooses —, each made on
 * this device by core's `TenantInvites` (*Securing the invitation on this device…* meanwhile). A refusal says why under
 * the form; one that went through closes the form and its link or code shows once ([InviteResultDialog]). The link and
 * the code are made to be handed on (src/mobile/CLAUDE.md § Secrets on screen): the window is no secure one, the owner's
 * chosen password is masked.
 */
@Composable
internal fun InviteDialog(
    working: Boolean,
    onInvite: (TenantInvites.Way, String?, String, String?, (TenantInvites.Outcome) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    var way by remember { mutableStateOf(TenantInvites.Way.LINK) }
    var label by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var ownPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var refusal by remember { mutableStateOf<Notice?>(null) }
    val enabled = !working
    val submit = {
        refusal = null
        onInvite(way, label.ifBlank { null }, email, if (way == TenantInvites.Way.MAIL && ownPassword) password else null) { o ->
            if (o.done) onClose() else refusal = o.notice
        }
    }
    FlowDialog(stringResource(R.string.members_invite), secure = false, tag = "invite", onClose = onClose) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val ways = TenantInvites.Way.entries
            ways.forEachIndexed { i, w ->
                SegmentedButton(
                    selected = way == w, onClick = { way = w; refusal = null }, enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, ways.size),
                    modifier = Modifier.testTag("invite-way-${w.name.lowercase()}"),
                ) {
                    Text(stringResource(if (w == TenantInvites.Way.LINK) R.string.members_way_link else R.string.members_way_mail))
                }
            }
        }
        when (way) {
            TenantInvites.Way.LINK -> {
                Body(stringResource(R.string.members_link_intro), muted = true)
                OutlinedTextField(
                    label, { if (it.length <= TenantInvites.LABEL_MAX_LENGTH) label = it }, singleLine = true, enabled = enabled,
                    label = { Text(stringResource(R.string.members_link_label)) },
                    supportingText = { Text(stringResource(R.string.members_link_label_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("invite-label"),
                )
                Refusal(refusal, "invite-refusal")
                Button(onClick = submit, enabled = enabled, modifier = Modifier.testTag("invite-link-create")) {
                    Text(stringResource(R.string.members_link_create))
                }
            }
            TenantInvites.Way.MAIL -> {
                Body(stringResource(R.string.members_invite_intro), muted = true)
                OutlinedTextField(
                    email, { email = it }, singleLine = true, enabled = enabled,
                    label = { Text(stringResource(R.string.members_email)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth().testTag("invite-email"),
                )
                if (ownPassword) {
                    SecretField(
                        password, { password = it }, stringResource(R.string.members_invite_password_label), "invite-password", enabled,
                        hint = stringResource(R.string.members_invite_password_note),
                    )
                }
                TextButton(onClick = { ownPassword = !ownPassword; password = "" }, enabled = enabled, modifier = Modifier.testTag("invite-password-toggle")) {
                    Text(stringResource(if (ownPassword) R.string.members_invite_use_code else R.string.members_invite_use_password))
                }
                Refusal(refusal, "invite-refusal")
                Button(
                    onClick = submit,
                    enabled = enabled && email.isNotBlank() && (!ownPassword || password.length >= PasswordChange.MIN_PASSWORD_LENGTH),
                    modifier = Modifier.testTag("invite-mail-submit"),
                ) { Text(stringResource(R.string.members_invite_submit)) }
            }
        }
        if (working) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Body(stringResource(R.string.members_invite_securing), muted = true)
        }
    }
}

@Composable
private fun Refusal(notice: Notice?, tag: String) {
    notice?.let { Text(say(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(tag)) }
}

/**
 * What an invite or a re-issue made, shown once (the web's `InviteLinkResult` and the mailed invite's code): a link with
 * its warning and the message around it — editable, copied, shared through the system's sheet or opened in a mail —, or
 * the code to pass on (the owner's own password is not shown back). Leaving it lets the secret go.
 */
@Composable
internal fun InviteResultDialog(outcome: TenantInvites.Outcome, spaceName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val link = outcome.address != null
    FlowDialog(
        stringResource(if (link) R.string.members_link_result_title else R.string.members_invite), secure = false, tag = "invite-result", onClose = onClose,
    ) {
        Text(say(outcome.notice), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("invite-result-said"))
        if (link) {
            Text(stringResource(R.string.members_link_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            val initial = outcome.message?.let { say(it) } ?: outcome.address.orEmpty()
            var message by remember(outcome) { mutableStateOf(initial) }
            OutlinedTextField(
                message, { message = it }, minLines = 5,
                label = { Text(stringResource(R.string.members_link_message_label)) },
                modifier = Modifier.fillMaxWidth().testTag("invite-result-message"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CopyButton(message, R.string.members_link_copy, "invite-result-copy")
                OutlinedButton(onClick = { shareText(context, message) }, modifier = Modifier.testTag("invite-result-share")) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.members_link_share))
                }
                val subject = say(Notice(me.raddatz.veyq.mobile.words.Msg.MEMBERS_LINK_MAIL_SUBJECT, spaceName))
                OutlinedButton(
                    onClick = { mailText(context, subject, message) },
                    modifier = Modifier.testTag("invite-result-mail"),
                ) {
                    Icon(Icons.Outlined.Email, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.members_link_mail))
                }
            }
        } else {
            Body(stringResource(if (outcome.ownChoice) R.string.members_invite_sent_password_intro else R.string.members_invite_sent_intro))
            outcome.code?.let { code ->
                OutlinedTextField(
                    code, {}, readOnly = true, singleLine = true,
                    label = { Text(stringResource(R.string.members_invite_code_label)) },
                    supportingText = { Text(stringResource(R.string.members_invite_code_note)) },
                    textStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().testTag("invite-result-code"),
                )
                CopyButton(code, R.string.members_invite_code_copy, "invite-result-code-copy")
            }
        }
        Button(onClick = onClose, modifier = Modifier.testTag("invite-result-done")) { Text(stringResource(R.string.ok)) }
    }
}

/** A copy of [text] to the clipboard, its button saying *Copied* for the core's copy note's time. */
@Composable
private fun CopyButton(text: String, word: Int, tag: String) {
    val context = LocalContext.current
    var times by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(times) {
        if (shown) {
            kotlinx.coroutines.delay(Notice.COPIED_MS)
            shown = false
        }
    }
    OutlinedButton(
        onClick = {
            context.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("veyq", text))
            shown = true
            times += 1
        },
        modifier = Modifier.testTag(tag),
    ) {
        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(if (shown) R.string.members_link_copied else word))
    }
}

/** [text] handed to another app through the system's share sheet. */
private fun shareText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(android.content.Intent.createChooser(send, null)) }
}

/** A new mail with [subject] and [body], in the person's mail app (`mailto:`, no recipient: the owner picks one). */
private fun mailText(context: android.content.Context, subject: String, body: String) {
    val uri = android.net.Uri.parse("mailto:?subject=" + android.net.Uri.encode(subject) + "&body=" + android.net.Uri.encode(body))
    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, uri)) }
}

/**
 * The owners' pending invites under the roster (uc.spaces.manage-member-invites, the web's `TenantMembersPanel`): each
 * row's title (the address, the label, or *Link invitation*), its expiry, the *Invited* badge, and its acts in its
 * overflow menu — *Rename* (a link invite), *Send again* / *New link*, *Cancel invite* (asked first).
 */
@Composable
internal fun PendingInvites(rows: List<TenantInvites.PendingInvite>, enabled: Boolean, actions: InviteActions) {
    var renaming by remember { mutableStateOf<TenantInvites.PendingInvite?>(null) }
    var revoking by remember { mutableStateOf<TenantInvites.PendingInvite?>(null) }
    Column(Modifier.padding(top = 16.dp).testTag("members-pending"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Heading(stringResource(R.string.members_pending_title))
        Body(stringResource(R.string.members_pending_sub), muted = true)
        rows.forEachIndexed { i, invite ->
            if (i > 0) HorizontalDivider()
            PendingRow(invite, enabled, onRename = { renaming = invite }, onReissue = { actions.reissue(invite) }, onRevoke = { revoking = invite })
        }
    }
    renaming?.let { invite ->
        LabelDialog(invite.label.orEmpty(), onSave = { renaming = null; actions.setLabel(invite.inviteId, it) }, onDismiss = { renaming = null })
    }
    revoking?.let { invite ->
        DangerConfirm(
            stringResource(R.string.members_revoke_confirm_title), stringResource(R.string.members_revoke_confirm), stringResource(R.string.members_revoke),
            onConfirm = { revoking = null; actions.revoke(invite.inviteId) }, onDismiss = { revoking = null },
        )
    }
}

@Composable
private fun PendingRow(invite: TenantInvites.PendingInvite, enabled: Boolean, onRename: () -> Unit, onReissue: () -> Unit, onRevoke: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val title = invite.title ?: invite.titleWord?.let { say(it) }.orEmpty()
    ListItem(
        leadingContent = { Icon(if (invite.link) Icons.Outlined.Link else Icons.Outlined.Email, null) },
        headlineContent = { Text(title, modifier = Modifier.testTag("invite-title")) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                invite.subtitle?.let { line -> Text(line.map { say(it) }.joined()) } ?: Text(say(invite.expires))
                Badge(stringResource(R.string.members_status_invited), "invite-status")
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag("invite-menu-${invite.inviteId}")) {
                    Icon(Icons.Outlined.MoreVert, title)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (invite.link) DropdownMenuItem(
                        text = { Text(stringResource(R.string.members_label_edit)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onRename() },
                        modifier = Modifier.testTag("invite-rename"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (invite.link) R.string.members_reissue_link else R.string.members_reissue)) },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                        onClick = { menu = false; onReissue() },
                        modifier = Modifier.testTag("invite-reissue"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.members_revoke), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.LinkOff, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onRevoke() },
                        modifier = Modifier.testTag("invite-revoke"),
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("invite-${invite.inviteId}"),
    )
}

/** *Rename* a link invite: its label, empty to clear it (core's `TenantInvites.setLabel`). */
@Composable
private fun LabelDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.members_label_title)) },
        text = {
            OutlinedTextField(
                label, { if (it.length <= TenantInvites.LABEL_MAX_LENGTH) label = it }, singleLine = true,
                label = { Text(stringResource(R.string.members_link_label)) },
                supportingText = { Text(stringResource(R.string.members_link_label_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("invite-label-field"),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(label) }, modifier = Modifier.testTag("invite-label-save")) { Text(stringResource(R.string.entry_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.entry_cancel)) } },
    )
}
