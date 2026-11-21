package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.session.ShareWords


/** The words of [kind]'s share sheet (core's `ShareWords`). */
internal fun shareWords(kind: String): ShareWords = me.raddatz.veyq.mobile.session.ShareWords.of(kind)

/**
 * A node's guest links, drawn from core's `ShareSheet` (its links newest first, the form, every write and what each says;
 * a Pot's in Expenses' words): each open link copied from here or handed to another app; "New link" mints one — view only,
 * or view and contribute where the tenant allows it, an optional label only the member sees, a password and an end where
 * the member wants them or the tenant's rules require them — and shows it once. Switching a link off re-keys what is below
 * it. The row's menu, the bottom sheet and the dialogs are Material 3's; every decision is the core's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareSheet(ref: me.raddatz.veyq.mobile.navigation.NodeAt, model: AppModel, onClose: () -> Unit) {
    val sheet = remember(ref) { model.shareSheet(ref) } ?: return
    val state by sheet.state.collectAsStateWithLifecycle()
    val words = sheet.words
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(sheet) { sheet.load() }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(say(words.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(4.dp))
            Text(say(words.intro), style = MaterialTheme.typography.bodyMedium, color = role(palette.muted))
            Spacer(Modifier.padding(8.dp))
            val made = state.fresh
            val form = state.form
            when {
                made != null -> FreshLink(made) { sheet.freshSeen() }
                form != null -> NewLinkForm(
                    form, words, saving = state.saving, problem = state.problem?.let { say(it) }, submit = sheet.submit(state),
                    onChange = sheet::form, onCancel = sheet::cancelForm, onSave = { scope.launch { sheet.save() } },
                )
                else -> {
                    if (sheet.canMint) Button(onClick = sheet::addLink, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.share_new)) }
                    state.notice?.let { Text(say(it), style = MaterialTheme.typography.bodySmall, color = role(palette.muted), modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.padding(6.dp))
                    val list = state.links
                    when {
                        state.failed -> Text(stringResource(R.string.share_load_failed), color = role(palette.danger))
                        list == null -> CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                        else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(sheet.rows(state), key = { it.link.shareId }) { row ->
                                // what this member may do with it is core's (`LinkRow`, the web's dialogs' rules)
                                val id = row.link.shareId
                                LinkRow(row, busy = state.busy == id) { action ->
                                    when (action) {
                                        // the copy is the row's own (its button and the system's share sheet)
                                        me.raddatz.veyq.mobile.navigation.LinkAction.COPY -> {}
                                        me.raddatz.veyq.mobile.navigation.LinkAction.EDIT -> sheet.edit(id)
                                        me.raddatz.veyq.mobile.navigation.LinkAction.RENEW -> sheet.askRenew(id)
                                        me.raddatz.veyq.mobile.navigation.LinkAction.REACTIVATE -> { scope.launch { sheet.reactivate(id) } }
                                        me.raddatz.veyq.mobile.navigation.LinkAction.SWITCH_OFF -> { scope.launch { sheet.switchOff(id) } }
                                        me.raddatz.veyq.mobile.navigation.LinkAction.DELETE -> sheet.askDelete(id)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.renewing != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = sheet::cancelAsk,
            title = { Text(stringResource(R.string.share_renew)) },
            text = { Text(stringResource(R.string.share_renew_confirm)) },
            confirmButton = { TextButton(onClick = { scope.launch { sheet.renew() } }) { Text(stringResource(R.string.share_renew)) } },
            dismissButton = { TextButton(onClick = sheet::cancelAsk) { Text(stringResource(R.string.trash_cancel)) } },
        )
    }
    if (state.deleting != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = sheet::cancelAsk,
            title = { Text(stringResource(R.string.share_delete)) },
            text = { Text(say(words.deleteConfirm)) },
            confirmButton = {
                TextButton(onClick = { scope.launch { sheet.delete() } }) { Text(stringResource(R.string.share_delete), color = role(palette.danger)) }
            },
            dismissButton = { TextButton(onClick = sheet::cancelAsk) { Text(stringResource(R.string.trash_cancel)) } },
        )
    }
}

/**
 * A link's row: its title, dates and badges, then what it offers (core's `ShareSheet.rowActions`, ADR 0154 decision 2) —
 * every entry, in its order: the copy as the row's button, with the system's share sheet (Android's Sharesheet) first in
 * its menu; the rest in that one overflow menu (Material 3). Switching off is at once, as the web does; renew and delete
 * ask first ([onAction] runs each but the copy).
 */
@Composable
private fun LinkRow(row: me.raddatz.veyq.mobile.session.LinkRow, busy: Boolean, onAction: (me.raddatz.veyq.mobile.navigation.LinkAction) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val copied = rememberCopied()
    val link = row.link
    val actions = me.raddatz.veyq.mobile.navigation.ShareSheet.rowActions(row)
    val url = link.url
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(piece(row.title), fontWeight = FontWeight.SemiBold)
            val dates = listOfNotNull(
                stringResource(R.string.share_created, day(link.createdAt)),
                link.expiresAt?.let { stringResource(R.string.share_expires, day(it)) },
            ).joined()
            Text(dates, style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
            val badges = row.badges.map { say(it) }
            if (badges.isNotEmpty()) Text(badges.joined(), style = MaterialTheme.typography.bodySmall, color = role(palette.accent))
        }
        if (copied.shown) Text(stringResource(R.string.share_copied), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
        if (url != null && me.raddatz.veyq.mobile.navigation.LinkAction.COPY in actions) IconButton(onClick = { copy(context, url); copied.said() }) {
            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.share_copy))
        }
        if (busy) CircularProgressIndicator(Modifier.padding(12.dp).width(20.dp))
        else if (actions.isNotEmpty()) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.node_more)) }
                androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                    for (a in actions) when (a) {
                        me.raddatz.veyq.mobile.navigation.LinkAction.COPY -> if (url != null) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_send)) }, onClick = { menu = false; sendText(context, url) })
                        }
                        me.raddatz.veyq.mobile.navigation.LinkAction.EDIT ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_edit)) }, onClick = { menu = false; onAction(a) })
                        me.raddatz.veyq.mobile.navigation.LinkAction.RENEW ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_renew)) }, onClick = { menu = false; onAction(a) })
                        me.raddatz.veyq.mobile.navigation.LinkAction.REACTIVATE ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_reactivate)) }, onClick = { menu = false; onAction(a) })
                        me.raddatz.veyq.mobile.navigation.LinkAction.SWITCH_OFF ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_switch_off), color = role(palette.danger)) }, onClick = { menu = false; onAction(a) })
                        me.raddatz.veyq.mobile.navigation.LinkAction.DELETE ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.share_delete), color = role(palette.danger)) }, onClick = { menu = false; onAction(a) })
                    }
                }
            }
        }
    }
}

/**
 * The share of a space's top level (the web's `LevelView` `share(null, …)`), drawn from core's `ShareSheet.topLevel`: no
 * link roots there, so it offers "Add files only" alone — a drop box at the top, named as typed, with its first link,
 * shown once. Its form, saving, refusal and day are the core's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopLevelUploadSheet(scopeId: String, model: AppModel, onClose: () -> Unit) {
    val sheet = remember(scopeId) { model.topLevelShareSheet(scopeId) } ?: return
    val state by sheet.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(say(sheet.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(8.dp))
            val made = state.fresh
            val form = state.form
            when {
                made != null -> FreshLink(made, onClose)
                form != null -> NewLinkForm(
                    form, sheet.words, state.saving, state.problem?.let { say(it) }, sheet.submit(state),
                    onChange = sheet::form, onCancel = onClose, onSave = { scope.launch { sheet.save() } },
                )
            }
        }
    }
}

/** The link just minted, shown once: copied, or handed to another app. */
@Composable
private fun FreshLink(url: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val copied = rememberCopied()
    Text(stringResource(R.string.share_ready))
    Spacer(Modifier.padding(4.dp))
    OutlinedTextField(url, {}, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (copied.shown) Text(stringResource(R.string.share_copied), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted))
        TextButton(onClick = onDone) { Text(stringResource(R.string.share_done)) }
        TextButton(onClick = { sendText(context, url) }) { Text(stringResource(R.string.share_send)) }
        Button(onClick = { copy(context, url); copied.said() }) {
            Text(stringResource(R.string.share_copy))
        }
    }
}

/**
 * The form of a new link or of one being edited, as core's `LinkForm` has it (its rules, the web's `FolderShareDialog`):
 * its role, an optional label, a password and an end, the end a local day. Each change goes to [onChange]; [onSave] sends
 * it, [problem] says why it did not go through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewLinkForm(
    form: me.raddatz.veyq.mobile.session.LinkForm, words: ShareWords, saving: Boolean, problem: String?,
    submit: me.raddatz.veyq.mobile.words.Msg,
    onChange: ((me.raddatz.veyq.mobile.session.LinkForm) -> me.raddatz.veyq.mobile.session.LinkForm) -> Unit,
    onCancel: () -> Unit, onSave: () -> Unit,
) {
    val palette = LocalPalette.current
    val rules = form.rules
    var picking by remember { mutableStateOf(false) }
    val min = me.raddatz.veyq.mobile.session.LinkForm.PASSWORD_MIN

    if (form.roles.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in form.roles) FilterChip(selected = form.role == r, onClick = { onChange { it.withRole(r) } }, label = { Text(say(words.roleWord(r))) })
    }
    Text(say(words.hint(form.role)), style = MaterialTheme.typography.bodySmall, color = role(palette.muted), modifier = Modifier.padding(vertical = 6.dp))
    if (form.namesIt) OutlinedTextField(
        form.label, { v -> onChange { it.withLabel(v) } }, label = { Text(say(form.nameLabel)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
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
        Switch(form.withPassword, { on -> onChange { it.withPasswordOn(on) } }, enabled = form.passwordOptional)
    }
    if (form.withPassword) OutlinedTextField(
        form.password, { v -> onChange { it.withPassword(v) } }, label = { Text(stringResource(R.string.share_password_section)) }, singleLine = true,
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
        Switch(form.withEnd, { on -> onChange { it.withEndOn(on) } }, enabled = form.endOptional)
    }
    if (form.withEnd) TextButton(onClick = { picking = true }) {
        Text(stringResource(R.string.share_expires, me.raddatz.veyq.mobile.words.Shown.day(form.endDay, language())))
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
                    state.selectedDateMillis?.let { ms -> onChange { it.withEndDay(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()) } }
                    picking = false
                }) { Text(stringResource(R.string.share_done)) }
            },
        ) { DatePicker(state) }
    }
    problem?.let { Text(it, color = role(palette.danger), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel, enabled = !saving) { Text(stringResource(R.string.trash_cancel)) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSave, enabled = form.canSave && !saving) { Text(say(submit)) }
    }
}


/** "Copied" beside what was copied, shown for the core's [me.raddatz.veyq.mobile.words.Notice.COPIED_MS] (the web's copy note). */
private class Copied {
    var shown by mutableStateOf(false)
        private set
    var times by mutableStateOf(0)
        private set

    fun said() {
        shown = true
        times += 1
    }

    fun gone() {
        shown = false
    }
}

/** A [Copied] that ends itself: each copy shows it anew for its whole time. */
@Composable
private fun rememberCopied(): Copied {
    val copied = remember { Copied() }
    androidx.compose.runtime.LaunchedEffect(copied.times) {
        if (copied.shown) {
            kotlinx.coroutines.delay(me.raddatz.veyq.mobile.words.Notice.COPIED_MS)
            copied.gone()
        }
    }
    return copied
}

private fun copy(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("veyq", text))
}

/** The local day of a server instant (ISO 8601), as the web writes it (`Shown.dayOf`). */
@Composable
private fun day(instant: String): String = me.raddatz.veyq.mobile.words.Shown.dayOf(instant, language())

/** [text] handed to another app through the system's share sheet (a link to send in a chat or a mail). */
private fun sendText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    context.startActivity(android.content.Intent.createChooser(send, null))
}
