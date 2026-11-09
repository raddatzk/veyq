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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
 * A Pot's guest links (the web's `PotShareDialog`, core's `FolderShareDialog` with Expenses' words): the links on it, newest
 * first, each copied from here; "New link" mints one — view only, or view and contribute where the tenant allows it, an
 * optional label only the member sees, a password and an end where the member wants them or the tenant's rules require
 * them — and shows it once to copy or share. Switching a link off, renewing and editing come with the subtree's re-key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PotShareSheet(ref: ObjectRef, model: AppModel, onClose: () -> Unit) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val rules = model.linkRules()
    var links by remember { mutableStateOf<List<ShareLinks.Link>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var fresh by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        val read = model.shareLinks(ref)
        if (read == null) failed = true else { links = read; failed = false }
    }
    LaunchedEffect(ref) { load() }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.share_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(4.dp))
            Text(stringResource(R.string.share_intro), style = MaterialTheme.typography.bodyMedium, color = role(palette.muted))
            Spacer(Modifier.padding(8.dp))
            val made = fresh
            when {
                made != null -> FreshLink(made) { fresh = null }
                creating -> NewLinkForm(rules, onCancel = { creating = false }) { role, label, password, expiresAt ->
                    val minted = model.mintLink(ref, role, label, password, expiresAt)
                    if (minted.outcome == ShareLinks.Outcome.CREATED) {
                        creating = false
                        fresh = minted.url
                        scope.launch { load() }
                        null
                    } else context.getString(mintProblem(minted.outcome))
                }
                else -> {
                    if (rules.on) Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.share_new)) }
                    Spacer(Modifier.padding(6.dp))
                    val list = links
                    when {
                        failed -> Text(stringResource(R.string.share_load_failed), color = role(palette.danger))
                        list == null -> CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                        else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(list, key = { it.shareId }) { link ->
                                LinkRow(link)
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(link: ShareLinks.Link) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val copied = stringResource(R.string.share_copied)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                link.label.ifEmpty { stringResource(if (link.role == ShareLinks.READ) R.string.share_role_viewer else R.string.share_role_contributor) },
                fontWeight = FontWeight.SemiBold,
            )
            val dates = listOfNotNull(
                stringResource(R.string.share_created, day(link.createdAt)),
                link.expiresAt?.let { stringResource(R.string.share_expires, day(it)) },
            ).joinToString(" · ")
            Text(dates, style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
            val badges = listOfNotNull(
                if (link.label.isNotEmpty()) stringResource(if (link.role == ShareLinks.READ) R.string.share_role_viewer else R.string.share_role_contributor) else null,
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
        TextButton(onClick = {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, url)
            context.startActivity(android.content.Intent.createChooser(send, null))
        }) { Text(stringResource(R.string.share_send)) }
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
private fun NewLinkForm(rules: ShareLinks.Rules, onCancel: () -> Unit, onCreate: suspend (String, String, String, String?) -> String?) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var role by remember { mutableStateOf(ShareLinks.READ) }
    var label by remember { mutableStateOf("") }
    var withPassword by remember { mutableStateOf(rules.passwordRequired) }
    var password by remember { mutableStateOf("") }
    val lastDay = rules.maxDays?.let { LocalDate.now().plusDays(it.toLong() - 1) }
    var withEnd by remember { mutableStateOf(rules.maxDays != null) }
    var end by remember { mutableStateOf(lastDay ?: LocalDate.now().plusDays(30)) }
    var picking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = role == ShareLinks.READ, onClick = { role = ShareLinks.READ }, label = { Text(stringResource(R.string.share_role_viewer)) })
        if (rules.contribute) FilterChip(
            selected = role == ShareLinks.READ_WRITE, onClick = { role = ShareLinks.READ_WRITE },
            label = { Text(stringResource(R.string.share_role_contributor)) },
        )
    }
    Text(
        stringResource(if (role == ShareLinks.READ) R.string.share_role_viewer_hint else R.string.share_role_contributor_hint),
        style = MaterialTheme.typography.bodySmall, color = role(palette.muted), modifier = Modifier.padding(vertical = 6.dp),
    )
    OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.share_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
    val passwordOk = !withPassword || password.trim().length >= PASSWORD_MIN
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
                    problem = onCreate(role, label, if (withPassword) password else "", expiresAt)
                    saving = false
                }
            },
            enabled = passwordOk && !saving,
        ) { Text(stringResource(R.string.share_create)) }
    }
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
