package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Core's report of a node to its server's moderators (the web's `ReportDialog.vue`, ADR 0093): what it is, why (required),
 * the reporter's good-faith confirmation (required), an optional address for the receipt and the decision with the privacy
 * notice's passage on it (GDPR Art. 13), and for one node whether it is reported whole or only by its name. On success the
 * receipt: its reference and when it was received.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportSheet(ref: me.raddatz.veyq.mobile.navigation.NodeAt, model: AppModel, onClose: () -> Unit) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sheet = remember(ref) { model.reportSheet(ref) } ?: return
    val st by sheet.state.collectAsState()
    val sending = st.sending
    // the typed text is held here too, set from the sheet's own state at once (a field must not wait for the flow)
    var reason by remember(sheet) { mutableStateOf(sheet.current.reason) }
    var contact by remember(sheet) { mutableStateOf(sheet.current.contact) }

    ModalBottomSheet(onDismissRequest = { if (!sending) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.report_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(4.dp))
            val receipt = st.sent
            if (receipt != null) {
                Text(stringResource(R.string.report_sent))
                Text(stringResource(R.string.report_receipt, receipt.reference.orEmpty(), day(receipt.receivedAt)), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Button(onClick = onClose) { Text(stringResource(R.string.report_close)) } }
                return@Column
            }
            Text(stringResource(R.string.report_intro), style = MaterialTheme.typography.bodyMedium, color = role(palette.muted))
            Spacer(Modifier.padding(6.dp))
            Text(stringResource(R.string.report_scope), fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = st.whole, onClick = { sheet.whole(true) }, label = { Text(stringResource(R.string.report_scope_whole)) }, enabled = !sending)
                FilterChip(selected = !st.whole, onClick = { sheet.whole(false) }, label = { Text(stringResource(R.string.report_scope_name)) }, enabled = !sending)
            }
            Text(say(st.scopeHint), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
            Spacer(Modifier.padding(6.dp))
            Text(stringResource(R.string.report_category), fontWeight = FontWeight.SemiBold)
            for (c in sheet.categories) {
                val token = c.token
                Row(
                    Modifier.fillMaxWidth().selectable(selected = st.category == token, enabled = !sending, role = Role.RadioButton) { sheet.category(token) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = st.category == token, onClick = null, enabled = !sending)
                    Text(say(c.word))
                }
            }
            st.note?.let { Text(say(it), style = MaterialTheme.typography.bodySmall, color = role(palette.accent)) }
            Spacer(Modifier.padding(6.dp))
            OutlinedTextField(
                reason, { sheet.reason(it); reason = sheet.current.reason }, label = { Text(stringResource(R.string.report_reason)) }, minLines = 4,
                supportingText = { Text(stringResource(R.string.report_reason_hint)) }, modifier = Modifier.fillMaxWidth(), enabled = !sending,
            )
            Row(Modifier.fillMaxWidth().toggleable(value = st.goodFaith, enabled = !sending, role = Role.Checkbox) { sheet.goodFaith(it) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(st.goodFaith, null, enabled = !sending)
                Text(stringResource(R.string.report_good_faith), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                contact, { sheet.contact(it); contact = sheet.current.contact }, label = { Text(stringResource(R.string.report_contact)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                supportingText = { Text(stringResource(R.string.report_contact_hint)) }, modifier = Modifier.fillMaxWidth(), enabled = !sending,
            )
            // GDPR Art. 13 applies where a contact is taken: the notice's passage on it (core's URL), in the browser
            TextButton(onClick = {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(sheet.privacyUrl)))
            }) { Text(stringResource(R.string.report_privacy)) }
            st.problem?.let { Text(context.text(it), color = role(palette.danger), style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose, enabled = !sending) { Text(stringResource(R.string.trash_cancel)) }
                Button(onClick = { scope.launch { sheet.send() } }, enabled = st.canSend) { Text(stringResource(R.string.report_send)) }
            }
        }
    }
}

/** A server instant (ISO 8601) in the device's words, date and time. */
private fun day(instant: String?): String = runCatching {
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
        .format(java.time.Instant.parse(instant).atZone(java.time.ZoneId.systemDefault()))
}.getOrDefault(instant.orEmpty())
