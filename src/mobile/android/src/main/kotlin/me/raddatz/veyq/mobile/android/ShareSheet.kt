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
import me.raddatz.veyq.mobile.session.ShareWords
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle


/**
 * The sentences a node's share sheet says in its kind's words (the web's `ShareDialogCopy`): a folder's are core's
 * (`share.folder.*`), a Pot's Expenses' (`expenses.share.*`). The rest of the sheet is the same for every kind.
 */
/** The words of [kind]'s share sheet (core's `ShareWords`). */
internal fun shareWords(kind: String): ShareWords = me.raddatz.veyq.mobile.session.ShareWords.of(kind)

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
    val changedText = { o: me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome -> me.raddatz.veyq.mobile.words.Notices.linkChanged(o)?.let { context.text(it) } }
    suspend fun load() {
        val read = model.shareLinks(ref)
        if (read == null) failed = true else { links = read; failed = false }
    }
    LaunchedEffect(ref) { load() }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(say(words.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(4.dp))
            Text(say(words.intro), style = MaterialTheme.typography.bodyMedium, color = role(palette.muted))
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
                    val mint = me.raddatz.veyq.mobile.session.LinkRow.mint(role, label, onDropBox = dropBox)
                    if (mint.makesDropBox) {
                        // "Add files only" on a folder: a drop box named so, here, with its first link (`createDropBoxWithLink`)
                        val made = model.createDropBox(ref, mint.label, password.orEmpty(), expiresAt)
                        return@NewLinkForm when (made.outcome) {
                            me.raddatz.veyq.mobile.depot.DropBoxes.Outcome.CREATED -> {
                                creating = false
                                fresh = made.url
                                null
                            }
                            else -> me.raddatz.veyq.mobile.words.Notices.dropBoxRefused(made.outcome)?.let { context.text(it) }
                        }
                    }
                    val minted = model.mintLink(ref, mint.role, mint.label, password.orEmpty(), expiresAt)
                    if (minted.outcome == ShareLinks.Outcome.CREATED) {
                        creating = false
                        fresh = minted.url
                        scope.launch { load() }
                        null
                    } else context.text(me.raddatz.veyq.mobile.words.Notices.linkRefused(minted.outcome) ?: me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.SHARE_FAILED))
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
                                // what this member may do with it is core's (`LinkRow`, the web's dialogs' rules)
                                val row = me.raddatz.veyq.mobile.session.LinkRow(link, dropBox, words)
                                LinkRow(
                                    row, busy = busy == link.shareId,
                                    onEdit = if (row.canEdit) ({ editing = link }) else null,
                                    onRenew = if (row.canRenew) ({ renewing = link }) else null,
                                    onDelete = if (row.canDelete) ({ deleting = link }) else null,
                                    onReactivate = if (row.canReactivate) {
                                        {
                                            busy = link.shareId
                                            scope.launch {
                                                val r = model.reactivateLink(ref, link.shareId)
                                                busy = null
                                                notice = changedText(r.outcome)
                                                load()
                                            }
                                        }
                                    } else null,
                                    onSwitchOff = if (row.canSwitchOff) {
                                    {
                                        busy = link.shareId
                                        scope.launch {
                                            val closed = model.switchOffLink(ref, link.shareId)
                                            busy = null
                                            notice = me.raddatz.veyq.mobile.words.Notices.linkClosed(closed)?.let { context.text(it) }
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
            text = { Text(say(words.deleteConfirm)) },
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
    row: me.raddatz.veyq.mobile.session.LinkRow, busy: Boolean, onSwitchOff: (() -> Unit)?,
    onEdit: (() -> Unit)? = null, onRenew: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
    onReactivate: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val copied = stringResource(R.string.share_copied)
    val link = row.link
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(piece(row.title), fontWeight = FontWeight.SemiBold)
            val dates = listOfNotNull(
                stringResource(R.string.share_created, day(link.createdAt)),
                link.expiresAt?.let { stringResource(R.string.share_expires, day(it)) },
            ).joinToString(" · ")
            Text(dates, style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
            val badges = row.badges.map { say(it) }
            if (badges.isNotEmpty()) Text(badges.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = role(palette.accent))
        }
        val url = link.url
        if (url != null && row.canCopy) IconButton(onClick = { copy(context, url); android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show() }) {
            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.share_copy))
        }
        // The row's other actions in one overflow menu (Material 3); switching off is at once, as the web does: the Pot gets a
        // new key, so the link opens nothing any more. Renew and delete ask first.
        // an open link goes to another app through the system's share sheet (Android's Sharesheet), first in the menu
        val share = if (url != null && row.canCopy) ({ sendText(context, url) }) else null
        if (busy) CircularProgressIndicator(Modifier.padding(12.dp).width(20.dp))
        else if (share != null || onEdit != null || onRenew != null || onReactivate != null || onSwitchOff != null || onDelete != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.node_more)) }
                androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                    share?.let { a -> androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_send)) }, onClick = { menu = false; a() }) }
                    onEdit?.let { a -> androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_edit)) }, onClick = { menu = false; a() }) }
                    onReactivate?.let { a -> androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_reactivate)) }, onClick = { menu = false; a() }) }
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

/**
 * The share of a space's top level (the web's `LevelView` `share(null, …)`): no link roots there, so it offers "Add files
 * only" alone — a drop box at the top, named as typed, with its first link (Depot's `folderUploadShare`), shown once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopLevelUploadSheet(scopeId: String, model: AppModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var fresh by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.share_role_uploader), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(8.dp))
            val made = fresh
            if (made != null) FreshLink(made, onClose)
            else NewLinkForm(model.linkRules(), shareWords("FOLDER"), onCancel = onClose, uploadOnly = true) { _, label, password, expiresAt ->
                val r = model.createDropBox(scopeId, null, label, password.orEmpty(), expiresAt)
                when (r.outcome) {
                    me.raddatz.veyq.mobile.depot.DropBoxes.Outcome.CREATED -> { fresh = r.url; null }
                    else -> me.raddatz.veyq.mobile.words.Notices.dropBoxRefused(r.outcome)?.let { context.text(it) }
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
 * The form of a new link or of one being edited (core's `LinkForm`: its rules, the web's `FolderShareDialog`): its role,
 * an optional label, a password and an end, the end a local day sent as the end of that day. [onCreate] answers why
 * not, or null once done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewLinkForm(
    rules: ShareLinks.Rules, words: ShareWords, onCancel: () -> Unit,
    /** A drop box's own link: its role is fixed and it has no name of its own. */
    writeOnly: Boolean = false,
    /** A folder's third role, "Add files only" (Depot's `folderUploadShare`): it makes a drop box with the name typed. */
    uploadOffered: Boolean = false,
    /** That role alone (the space's top level, where no link roots): no role to pick. */
    uploadOnly: Boolean = false,
    /** The link an edit changes (its settings fill the form; a password left blank stays as it is); null mints a new one. */
    editing: ShareLinks.Link? = null,
    /** role, label, password (null: untouched, blank: none), end; answers why not, or null once done. */
    onCreate: suspend (String, String, String?, String?) -> String?,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var form by remember {
        val editEnd = editing?.expiresAt?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() }.getOrNull() }
        mutableStateOf(me.raddatz.veyq.mobile.session.LinkForm.start(rules, editing, writeOnly, uploadOffered, uploadOnly, LocalDate.now().toEpochDay(), editEnd))
    }
    var picking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val min = me.raddatz.veyq.mobile.session.LinkForm.PASSWORD_MIN

    if (form.roles.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in form.roles) FilterChip(selected = form.role == r, onClick = { form = form.withRole(r) }, label = { Text(say(words.roleWord(r))) })
    }
    Text(say(words.hint(form.role)), style = MaterialTheme.typography.bodySmall, color = role(palette.muted), modifier = Modifier.padding(vertical = 6.dp))
    if (form.namesIt) OutlinedTextField(
        form.label, { form = form.withLabel(it) }, label = { Text(say(form.nameLabel)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.padding(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.share_password_section))
            Text(
                stringResource(if (rules.passwordRequired) R.string.share_password_by_rule else R.string.share_password_hint, min),
                style = MaterialTheme.typography.bodySmall, color = role(palette.muted),
            )
        }
        Switch(form.withPassword, { form = form.withPasswordOn(it) }, enabled = form.passwordOptional)
    }
    if (form.withPassword) OutlinedTextField(
        form.password, { form = form.withPassword(it) }, label = { Text(stringResource(R.string.share_password_section)) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = form.passwordTooShort,
        placeholder = if (form.hadPassword) ({ Text(stringResource(R.string.share_password_keep)) }) else null,
        supportingText = { Text(stringResource(R.string.share_password_short, min)) }, modifier = Modifier.fillMaxWidth(),
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
        Switch(form.withEnd, { form = form.withEndOn(it) }, enabled = form.endOptional)
    }
    if (form.withEnd) TextButton(onClick = { picking = true }) {
        Text(stringResource(R.string.share_expires, LocalDate.ofEpochDay(form.endDay).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))))
    }
    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(form.endDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    return d >= form.today && (form.lastDay == null || d <= form.lastDay!!)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { form = form.withEndDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()) }
                    picking = false
                }) { Text(stringResource(R.string.share_done)) }
            },
        ) { DatePicker(state) }
    }
    problem?.let { Text(it, color = role(palette.danger), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel, enabled = !saving) { Text(stringResource(R.string.trash_cancel)) }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                saving = true
                problem = null
                // the end of the picked local day (`FolderShareDialog`: 23:59:59.999 there)
                val expiresAt = form.submittedEndDay?.let { LocalDate.ofEpochDay(it).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusMillis(1).toString() }
                val sent = form
                scope.launch {
                    problem = onCreate(sent.role, sent.label, sent.submittedPassword, expiresAt)
                    saving = false
                }
            },
            enabled = form.canSave && !saving,
        ) { Text(stringResource(if (editing != null) R.string.share_save else R.string.share_create)) }
    }
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
