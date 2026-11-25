package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Checkbox
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.qr.QrEncoder
import me.raddatz.veyq.mobile.qr.QrMatrix

/**
 * Whether the screen is drawn for a TV ([TvRoot] provides it): a dialog the phone draws for touch is then drawn for the
 * remote — the report ([ReportSheet] routes to [TvReportDialog]).
 */
internal val LocalTvForm = staticCompositionLocalOf { false }

/**
 * Core's report [sheet] on a TV (ADR 0159's decision log: the DSA's notice mechanism stays reachable where the content is
 * shown): the phone's form drawn for focus and the remote, not the phone's bottom sheet — whose text fields hold the focus
 * against the D-pad (measured on the TV emulator, [tvField]) and whose privacy link opens a browser a TV does not have. Every
 * choice is a focusable list item (Compose for TV's own focus look), the reason and the contact the system keyboard's,
 * the privacy notice's passage its QR code and address, read on another device.
 */
@Composable
internal fun TvReportDialog(sheet: me.raddatz.veyq.mobile.navigation.ReportSheet, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val st by sheet.state.collectAsState()
    val sending = st.sending
    var reason by remember(sheet) { mutableStateOf(sheet.current.reason) }
    var contact by remember(sheet) { mutableStateOf(sheet.current.contact) }
    val first = remember { FocusRequester() }
    val done = remember { FocusRequester() }

    Dialog(onDismissRequest = { if (!sending) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.widthIn(max = 760.dp).fillMaxWidth(0.7f).fillMaxHeight(0.9f).testTag("tv-report"),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.report_title), style = MaterialTheme.typography.headlineSmall)
                val receipt = st.sent
                if (receipt != null) {
                    Text(stringResource(R.string.report_sent), modifier = Modifier.testTag("tv-report-sent"))
                    Text(
                        stringResource(R.string.report_receipt, receipt.reference.orEmpty(), me.raddatz.veyq.mobile.words.Shown.moment(receipt.receivedAt.orEmpty(), context.language)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onClose, modifier = Modifier.focusRequester(done).testTag("tv-report-close")) { Text(stringResource(R.string.report_close)) }
                    LaunchedEffect(Unit) { runCatching { done.requestFocus() } }
                    return@Column
                }
                Text(stringResource(R.string.report_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sheet.nodes.size > 1) Text(say(st.items))
                st.tooManyNotice?.let { tooMany ->
                    Text(say(tooMany), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (sheet.offersEnclosing(st)) OutlinedButton(onClick = sheet::useEnclosing) { Text(say(sheet.enclosingWord)) }
                }
                if (sheet.offersName) {
                    FormHeading(stringResource(R.string.report_scope))
                    Choice(stringResource(R.string.report_scope_whole), st.whole, !sending, Modifier.focusRequester(first).testTag("tv-report-whole")) { sheet.whole(true) }
                    Choice(stringResource(R.string.report_scope_name), !st.whole, !sending, Modifier.testTag("tv-report-name")) { sheet.whole(false) }
                    Text(say(st.scopeHint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FormHeading(stringResource(R.string.report_category))
                sheet.categories.forEachIndexed { i, c ->
                    val token = c.token
                    val focus = if (i == 0 && !sheet.offersName) Modifier.focusRequester(first) else Modifier
                    Choice(say(c.word), st.category == token, !sending, focus.testTag("tv-report-category-$token")) { sheet.category(token) }
                }
                st.note?.let { Text(say(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                // the system keyboard types them; the D-pad leaves them up and down ([tvField])
                androidx.compose.material3.OutlinedTextField(
                    reason, { sheet.reason(it); reason = sheet.current.reason }, label = { androidx.compose.material3.Text(stringResource(R.string.report_reason)) },
                    maxLines = 4, supportingText = { androidx.compose.material3.Text(stringResource(R.string.report_reason_hint)) },
                    modifier = Modifier.fillMaxWidth().tvField().testTag("tv-report-reason"), enabled = !sending,
                )
                ListItem(
                    selected = false, onClick = { sheet.goodFaith(!st.goodFaith) }, enabled = !sending,
                    headlineContent = { Text(stringResource(R.string.report_good_faith), style = MaterialTheme.typography.bodyMedium) },
                    leadingContent = { Checkbox(st.goodFaith, null, enabled = !sending) },
                    modifier = Modifier.testTag("tv-report-good-faith"),
                )
                androidx.compose.material3.OutlinedTextField(
                    contact, { sheet.contact(it); contact = sheet.current.contact }, label = { androidx.compose.material3.Text(stringResource(R.string.report_contact)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    supportingText = { androidx.compose.material3.Text(stringResource(R.string.report_contact_hint)) },
                    modifier = Modifier.fillMaxWidth().tvField().testTag("tv-report-contact"), enabled = !sending,
                )
                // GDPR Art. 13 where a contact is taken: a TV has no browser, so the passage is read on another device
                FormHeading(stringResource(R.string.report_privacy))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    remember(sheet.privacyUrl) { runCatching { QrEncoder.encode(sheet.privacyUrl) }.getOrNull() }?.let {
                        QrPicture(it, stringResource(R.string.report_privacy), 120.dp, "tv-report-privacy-qr")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.report_privacy_elsewhere), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sheet.privacyUrl, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("tv-report-privacy-url"))
                    }
                }
                st.problem?.let { Text(context.text(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    OutlinedButton(onClick = onClose, enabled = !sending, modifier = Modifier.testTag("tv-report-cancel")) { Text(stringResource(R.string.trash_cancel)) }
                    Button(onClick = { scope.launch { sheet.send() } }, enabled = st.canSend, modifier = Modifier.testTag("tv-report-send")) { Text(stringResource(R.string.report_send)) }
                }
            }
        }
    }
    // the remote starts on the form's first choice, not nowhere
    LaunchedEffect(sheet) { runCatching { first.requestFocus() } }
}

@Composable
private fun FormHeading(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

/** One of several: a focusable list item with its radio mark, chosen with the centre key. */
@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        selected = selected, onClick = onClick, enabled = enabled, modifier = modifier,
        headlineContent = { Text(label) }, leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
    )
}

/** Core's QR [matrix] drawn dark on light whatever the theme (a scanner reads that), its quiet zone included. */
@Composable
private fun QrPicture(matrix: QrMatrix, alt: String, side: Dp, tag: String) {
    Box(Modifier.size(side)) {
        Canvas(Modifier.size(side).semantics { contentDescription = alt }.testTag(tag)) {
            val n = matrix.side
            val cell = size.minDimension / n
            drawRect(Color.White)
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix.dark(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
