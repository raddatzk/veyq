package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.session.ShareLinks
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A link's password: at least this many characters (`FolderShareDialog`'s `PASSWORD_MIN`). */
private const val PASSWORD_MIN = 10

/**
 * The sentences a node's share sheet says in its kind's words (the web's `ShareDialogCopy`): a folder's are core's
 * (`share.folder.*`), a Pot's Expenses' (`expenses.share.*`). The rest of the sheet is the same for every kind.
 */
internal class ShareWords(
    @androidx.annotation.StringRes val title: Int,
    @androidx.annotation.StringRes val intro: Int,
    @androidx.annotation.StringRes val contributor: Int,
    @androidx.annotation.StringRes val viewerHint: Int,
    @androidx.annotation.StringRes val contributorHint: Int,
    @androidx.annotation.StringRes val deleteConfirm: Int,
)

/** The words of [kind]'s share sheet: a Pot's, a file's, else core's folder words (the web's default `ShareDialogCopy`). */
internal fun shareWords(kind: String): ShareWords =
    if (kind == me.raddatz.veyq.mobile.depot.DepotFiles.DROPBOX) ShareWords(
        R.string.dropbox_share_title, R.string.dropbox_share_hint, R.string.share_role_uploader,
        R.string.dropbox_upload_hint, R.string.dropbox_upload_hint, R.string.dropbox_share_delete_confirm,
    ) else if (kind == me.raddatz.veyq.mobile.depot.DepotFiles.FILE) ShareWords(
        R.string.share_file_title, R.string.share_file_intro, R.string.share_file_role_contributor,
        R.string.share_file_role_viewer_hint, R.string.share_file_role_contributor_hint, R.string.share_file_delete_confirm,
    ) else if (kind == me.raddatz.veyq.mobile.expenses.PotSession.KIND) ShareWords(
        R.string.share_title, R.string.share_intro, R.string.share_role_contributor,
        R.string.share_role_viewer_hint, R.string.share_role_contributor_hint, R.string.share_delete_confirm,
    ) else ShareWords(
        R.string.share_folder_title, R.string.share_folder_intro, R.string.share_folder_role_contributor,
        R.string.share_folder_role_viewer_hint, R.string.share_folder_role_contributor_hint, R.string.share_folder_delete_confirm,
    )

/**
 * A node's guest links (core's `FolderShareDialog`, a Pot's with Expenses' words, [shareWords]): the links on it, newest
 * first, each copied from here; "New link" mints one — view only, or view and contribute where the tenant allows it, an
 * optional label only the member sees, a password and an end where the member wants them or the tenant's rules require
 * them — and shows it once to copy or share. Switching a link off re-keys what is below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareSheet(ref: me.raddatz.veyq.mobile.navigation.NodeAt, model: AppModel, onClose: () -> Unit) {
    val words = shareWords(ref.kind)
    // a drop box's own links: WRITE_ONLY only, no name of their own; a folder offers "Add files only", which makes one
    val dropBox = ref.kind == me.raddatz.veyq.mobile.depot.DepotFiles.DROPBOX
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val rules = model.linkRules()
    var links by remember { mutableStateOf<List<ShareLinks.Link>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var fresh by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ShareLinks.Link?>(null) }
    var renewing by remember { mutableStateOf<ShareLinks.Link?>(null) }
    var deleting by remember { mutableStateOf<ShareLinks.Link?>(null) }
    val changedText = { o: me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome ->
        when (o) {
            me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.DONE -> null
            me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.DONE_REKEY_PENDING -> context.getString(R.string.share_closed_rekeying)
            me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.PASSWORD_LOST -> context.getString(R.string.share_password_lost)
            me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.GONE -> context.getString(R.string.share_gone)
            me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.RULES -> context.getString(R.string.share_requirement_refused)
            else -> context.getString(R.string.share_failed)
        }
    }
    suspend fun load() {
        val read = model.shareLinks(ref)
        if (read == null) failed = true else { links = read; failed = false }
    }
    LaunchedEffect(ref) { load() }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(words.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(4.dp))
            Text(stringResource(words.intro), style = MaterialTheme.typography.bodyMedium, color = role(palette.muted))
            Spacer(Modifier.padding(8.dp))
            val made = fresh
            when {
                made != null -> FreshLink(made) { fresh = null }
                editing != null -> NewLinkForm(rules, words, onCancel = { editing = null }, editing = editing, writeOnly = dropBox) { role, label, password, expiresAt ->
                    val link = editing!!
                    val r = model.editLink(ref, link.shareId, me.raddatz.veyq.mobile.session.ShareLinkChanges.Edit(label, password, expiresAt, role))
                    changedText(r.outcome).also {
                        if (it == null) {
                            editing = null
                            scope.launch { load() }
                        }
                    }
                }
                creating -> NewLinkForm(
                    rules, words, onCancel = { creating = false }, writeOnly = dropBox,
                    uploadOffered = ref.kind == "FOLDER" && rules.dropBox,
                ) { role, label, password, expiresAt ->
                    if (role == ShareLinks.WRITE_ONLY && !dropBox) {
                        // "Add files only" on a folder: a drop box named so, here, with its first link (`createDropBoxWithLink`)
                        val made = model.createDropBox(ref, label, password.orEmpty(), expiresAt)
                        return@NewLinkForm when (made.outcome) {
                            me.raddatz.veyq.mobile.depot.DropBoxes.Outcome.CREATED -> {
                                creating = false
                                fresh = made.url
                                null
                            }
                            me.raddatz.veyq.mobile.depot.DropBoxes.Outcome.NAME_TAKEN -> context.getString(R.string.node_slug_taken)
                            me.raddatz.veyq.mobile.depot.DropBoxes.Outcome.LINK_FAILED -> context.getString(R.string.dropbox_link_failed)
                            else -> context.getString(R.string.share_failed)
                        }
                    }
                    val minted = model.mintLink(ref, if (dropBox) ShareLinks.WRITE_ONLY else role, if (dropBox) "" else label, password.orEmpty(), expiresAt)
                    if (minted.outcome == ShareLinks.Outcome.CREATED) {
                        creating = false
                        fresh = minted.url
                        scope.launch { load() }
                        null
                    } else context.getString(mintProblem(minted.outcome))
                }
                else -> {
                    if (rules.on) Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.share_new)) }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = role(palette.muted), modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.padding(6.dp))
                    val list = links
                    when {
                        failed -> Text(stringResource(R.string.share_load_failed), color = role(palette.danger))
                        list == null -> CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                        else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(list, key = { it.shareId }) { link ->
                                LinkRow(
                                    link, words, busy = busy == link.shareId,
                                    // the web's rules: edit an own open link whose copy opens, renew an own or switched-off one,
                                    // delete a switched-off one the server lets go (`FolderShareDialog`)
                                    onEdit = if (!dropBox && link.mine && link.enabled && link.url != null) ({ editing = link }) else null,
                                    onRenew = if (!dropBox && (link.mine || !link.enabled)) ({ renewing = link }) else null,
                                    onDelete = if (!link.enabled && link.deletable) ({ deleting = link }) else null,
                                    onSwitchOff = if (link.enabled && link.mine) {
                                    {
                                        busy = link.shareId
                                        scope.launch {
                                            val closed = model.switchOffLink(ref, link.shareId)
                                            busy = null
                                            notice = when (closed) {
                                                ShareLinks.Closed.CLOSED -> null
                                                ShareLinks.Closed.CLOSED_REKEY_PENDING -> context.getString(R.string.share_closed_rekeying)
                                                ShareLinks.Closed.FAILED -> context.getString(R.string.share_failed)
                                            }
                                            load()
                                        }
                                    }
                                } else null,
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
    renewing?.let { link ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renewing = null },
            title = { Text(stringResource(R.string.share_renew)) },
            text = { Text(stringResource(R.string.share_renew_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    renewing = null
                    busy = link.shareId
                    scope.launch {
                        val r = model.renewLink(ref, link.shareId)
                        busy = null
                        notice = changedText(r.outcome)
                        if (r.url != null) fresh = r.url
                        load()
                    }
                }) { Text(stringResource(R.string.share_renew)) }
            },
            dismissButton = { TextButton(onClick = { renewing = null }) { Text(stringResource(R.string.trash_cancel)) } },
        )
    }
    deleting?.let { link ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.share_delete)) },
            text = { Text(stringResource(words.deleteConfirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    busy = link.shareId
                    scope.launch {
                        notice = changedText(model.deleteLink(ref, link.shareId).outcome)
                        busy = null
                        load()
                    }
                }) { Text(stringResource(R.string.share_delete), color = role(palette.danger)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.trash_cancel)) } },
        )
    }
}

@Composable
private fun LinkRow(
    link: ShareLinks.Link, words: ShareWords, busy: Boolean, onSwitchOff: (() -> Unit)?,
    onEdit: (() -> Unit)? = null, onRenew: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val copied = stringResource(R.string.share_copied)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                link.label.ifEmpty { stringResource(roleWord(link.role, words)) },
                fontWeight = FontWeight.SemiBold,
            )
            val dates = listOfNotNull(
                stringResource(R.string.share_created, day(link.createdAt)),
                link.expiresAt?.let { stringResource(R.string.share_expires, day(it)) },
            ).joinToString(" · ")
            Text(dates, style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
            val badges = listOfNotNull(
                if (link.label.isNotEmpty()) stringResource(roleWord(link.role, words)) else null,
                if (link.passwordRequired) stringResource(R.string.share_password_badge) else null,
                if (!link.enabled) stringResource(R.string.share_switched_off) else null,
                if (!link.mine) stringResource(R.string.share_by_member) else null,
                if (link.stopped || link.reissueRequired) stringResource(R.string.share_stopped) else null,
            )
            if (badges.isNotEmpty()) Text(badges.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = role(palette.accent))
        }
        val url = link.url
        if (url != null && link.enabled) IconButton(onClick = { copy(context, url); android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show() }) {
            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.share_copy))
        }
        // The row's other actions in one overflow menu (Material 3); switching off is at once, as the web does: the Pot gets a
        // new key, so the link opens nothing any more. Renew and delete ask first.
        // an open link goes to another app through the system's share sheet (Android's Sharesheet), first in the menu
        val share = if (url != null && link.enabled) ({ sendText(context, url) }) else null
        if (busy) CircularProgressIndicator(Modifier.padding(12.dp).width(20.dp))
        else if (share != null || onEdit != null || onRenew != null || onSwitchOff != null || onDelete != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.node_more)) }
                androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                    share?.let { a -> androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_send)) }, onClick = { menu = false; a() }) }
                    onEdit?.let { a -> androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_edit)) }, onClick = { menu = false; a() }) }
                    onRenew?.let { a -> androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_renew)) }, onClick = { menu = false; a() }) }
                    onSwitchOff?.let { a ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_switch_off), color = role(palette.danger)) }, onClick = { menu = false; a() })
                    }
                    onDelete?.let { a ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_delete), color = role(palette.danger)) }, onClick = { menu = false; a() })
                    }
                }
            }
        }
    }
}

/** The link just minted, shown once: copied, or handed to another app. */
@Composable
private fun FreshLink(url: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.share_copied)
    Text(stringResource(R.string.share_ready))
    Spacer(Modifier.padding(4.dp))
    OutlinedTextField(url, {}, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDone) { Text(stringResource(R.string.share_done)) }
        TextButton(onClick = { sendText(context, url) }) { Text(stringResource(R.string.share_send)) }
        Button(onClick = { copy(context, url); android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show() }) {
            Text(stringResource(R.string.share_copy))
        }
    }
}

/**
 * The form of a new link: its role, an optional label, a password and an end. A rule of the tenant turns its switch on and
 * keeps it on; the end is a day no later than the rule's last ([ShareLinks.Rules.maxDays] after today, the day before
 * excluded), sent as the end of that local day. [onCreate] answers why not, or null once minted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewLinkForm(
    rules: ShareLinks.Rules, words: ShareWords, onCancel: () -> Unit,
    /** A drop box's own link: its role is fixed and it has no name of its own. */
    writeOnly: Boolean = false,
    /** A folder's third role, "Add files only" (Depot's `folderUploadShare`): it makes a drop box with the name typed. */
    uploadOffered: Boolean = false,
    /** The link an edit changes (its settings fill the form; a password left blank stays as it is); null mints a new one. */
    editing: ShareLinks.Link? = null,
    /** role, label, password (null: untouched, blank: none), end; answers why not, or null once done. */
    onCreate: suspend (String, String, String?, String?) -> String?,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var role by remember { mutableStateOf(if (writeOnly) ShareLinks.WRITE_ONLY else editing?.role ?: ShareLinks.READ) }
    var label by remember { mutableStateOf(editing?.label.orEmpty()) }
    val hadPassword = editing?.passwordRequired == true
    var withPassword by remember { mutableStateOf(rules.passwordRequired || hadPassword) }
    var password by remember { mutableStateOf("") }
    val lastDay = rules.maxDays?.let { LocalDate.now().plusDays(it.toLong() - 1) }
    val editEnd = editing?.expiresAt?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull() }
    var withEnd by remember { mutableStateOf(rules.maxDays != null || editEnd != null) }
    var end by remember { mutableStateOf(editEnd ?: lastDay ?: LocalDate.now().plusDays(30)) }
    var picking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    if (!writeOnly) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = role == ShareLinks.READ, onClick = { role = ShareLinks.READ }, label = { Text(stringResource(R.string.share_role_viewer)) })
        if (rules.contribute) FilterChip(
            selected = role == ShareLinks.READ_WRITE, onClick = { role = ShareLinks.READ_WRITE },
            label = { Text(stringResource(words.contributor)) },
        )
        if (uploadOffered) FilterChip(
            selected = role == ShareLinks.WRITE_ONLY, onClick = { role = ShareLinks.WRITE_ONLY },
            label = { Text(stringResource(R.string.share_role_uploader)) },
        )
    }
    Text(
        stringResource(
            when (role) {
                ShareLinks.READ -> words.viewerHint
                ShareLinks.WRITE_ONLY -> R.string.dropbox_upload_hint
                else -> words.contributorHint
            },
        ),
        style = MaterialTheme.typography.bodySmall, color = role(palette.muted), modifier = Modifier.padding(vertical = 6.dp),
    )
    // a drop box's own links have no name; the one "Add files only" makes needs one — its guests see it
    if (!writeOnly) OutlinedTextField(
        label, { label = it },
        label = { Text(stringResource(if (role == ShareLinks.WRITE_ONLY) R.string.share_upload_name else R.string.share_label)) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.padding(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.share_password_section))
            Text(
                stringResource(if (rules.passwordRequired) R.string.share_password_by_rule else R.string.share_password_hint, PASSWORD_MIN),
                style = MaterialTheme.typography.bodySmall, color = role(palette.muted),
            )
        }
        Switch(withPassword, { withPassword = it }, enabled = !rules.passwordRequired)
    }
    if (withPassword) OutlinedTextField(
        password, { password = it }, label = { Text(stringResource(R.string.share_password_section)) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = password.isNotEmpty() && password.trim().length < PASSWORD_MIN,
        placeholder = if (hadPassword) ({ Text(stringResource(R.string.share_password_keep)) }) else null,
        supportingText = { Text(stringResource(R.string.share_password_short, PASSWORD_MIN)) }, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.padding(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.share_expiry_section))
            Text(
                rules.maxDays?.let { stringResource(R.string.share_expiry_by_rule, it) } ?: stringResource(R.string.share_expiry_hint),
                style = MaterialTheme.typography.bodySmall, color = role(palette.muted),
            )
        }
        Switch(withEnd, { withEnd = it }, enabled = rules.maxDays == null)
    }
    if (withEnd) TextButton(onClick = { picking = true }) {
        Text(stringResource(R.string.share_expires, end.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))))
    }
    if (picking) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !d.isBefore(today) && (lastDay == null || !d.isAfter(lastDay))
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { end = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text(stringResource(R.string.share_done)) }
            },
        ) { DatePicker(state) }
    }
    problem?.let { Text(it, color = role(palette.danger), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    // an edit keeps the password where the field stays blank
    val passwordOk = !withPassword || password.trim().length >= PASSWORD_MIN || (hadPassword && password.isEmpty())
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel, enabled = !saving) { Text(stringResource(R.string.trash_cancel)) }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                saving = true
                problem = null
                // the end of the picked local day (`FolderShareDialog`: 23:59:59.999 there)
                val expiresAt = if (withEnd) end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusMillis(1).toString() else null
                scope.launch {
                    val pw = when {
                        withPassword && password.isEmpty() && hadPassword -> null
                        withPassword -> password
                        hadPassword -> ""
                        editing != null -> null
                        else -> ""
                    }
                    problem = onCreate(role, label, pw, expiresAt)
                    saving = false
                }
            },
            enabled = passwordOk && !saving && (role != ShareLinks.WRITE_ONLY || writeOnly || label.isNotBlank()),
        ) { Text(stringResource(if (editing != null) R.string.share_save else R.string.share_create)) }
    }
}

/** A link's role in the sheet's words. */
private fun roleWord(role: String, words: ShareWords): Int = when (role) {
    ShareLinks.READ -> R.string.share_role_viewer
    ShareLinks.WRITE_ONLY -> R.string.share_role_uploader
    else -> words.contributor
}

private fun mintProblem(outcome: ShareLinks.Outcome): Int = when (outcome) {
    ShareLinks.Outcome.PASSWORD_REQUIRED, ShareLinks.Outcome.EXPIRY_REJECTED -> R.string.share_requirement_refused
    ShareLinks.Outcome.NOT_ALLOWED -> R.string.share_not_allowed
    ShareLinks.Outcome.SCOPE_DELETING -> R.string.delete_refused_scope_deleting
    ShareLinks.Outcome.BUSY -> R.string.move_refused_rekey_pending
    else -> R.string.share_failed
}

private fun copy(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("veyq", text))
}

/** The day of a server instant (ISO 8601), in the device's words. */
private fun day(instant: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.parse(instant).atZone(ZoneId.systemDefault()).toLocalDate())
}.getOrDefault(instant.take(10))

/** [text] handed to another app through the system's share sheet (a link to send in a chat or a mail). */
private fun sendText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    context.startActivity(android.content.Intent.createChooser(send, null))
}
