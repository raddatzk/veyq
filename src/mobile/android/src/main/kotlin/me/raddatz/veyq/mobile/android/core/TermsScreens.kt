package me.raddatz.veyq.mobile.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.MemberTerms

/**
 * The member's terms gate (uc.account.accept-terms; core's `Gate.Signed.terms`, the web's `TermsAcceptView`): drawn in
 * place of the shell while it stands — the space's terms, the operator section after them, *I agree* and the one other
 * way out, *Sign out*. The system's Back leaves nothing: there is no shell behind it to go back to.
 */
@Composable
internal fun TermsGateScreen(gate: SessionModel.TermsGate, onAccept: () -> Unit, onSignOut: () -> Unit) {
    BackHandler(enabled = true) {}
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("terms-gate"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Brand()
            Text(stringResource(R.string.terms_gate_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.terms_gate_intro), style = MaterialTheme.typography.bodyMedium)
            TermsText(gate.shown)
            gate.error?.let { Text(say(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("terms-error")) }
            PrimaryButton(stringResource(R.string.terms_accept), Modifier.testTag("terms-accept"), enabled = !gate.working, working = gate.working, onClick = onAccept)
            TextButton(onClick = onSignOut, enabled = !gate.working, modifier = Modifier.fillMaxWidth().testTag("terms-sign-out")) {
                Text(stringResource(R.string.drawer_sign_out))
            }
        }
    }
}

/**
 * The terms as core's `MemberTerms.TermsShown` hands them, the gate's and Settings' page alike: the space's text (or that it has
 * none), then the operator section under its label and note, and the guest-write record's pointer with its link to the
 * server's privacy notice, opened in the browser.
 */
@Composable
internal fun TermsText(shown: MemberTerms.TermsShown, withTerms: Boolean = true) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (withTerms) {
            if (shown.hasTerms && shown.terms.isNotBlank()) MarkdownText(shown.terms)
            else Text(stringResource(R.string.terms_none_set), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Text(stringResource(R.string.terms_operator_section_label), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.terms_operator_section_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (shown.operatorSection.isNotBlank()) MarkdownText(shown.operatorSection)
        if (shown.record.isNotBlank()) {
            MarkdownText(shown.record)
            TextButton(
                onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(shown.privacyUrl))) } },
                modifier = Modifier.testTag("terms-privacy"),
            ) {
                Icon(Icons.Outlined.OpenInBrowser, null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.terms_record_link))
            }
        }
    }
}

/**
 * What Settings' Terms page shows: still loading, the terms as the caller may use them (core's `MemberTerms.editing`;
 * null where they did not load), the owner's [draft] as edited, what the last save said ([said], with its [round]) and
 * whether a save is under way.
 */
internal data class TermsPage(
    val loading: Boolean,
    val editing: MemberTerms.TermsEditing? = null,
    val draft: MemberTerms.TermsDraft? = null,
    val said: me.raddatz.veyq.mobile.words.Notice? = null,
    val round: Int = 0,
    val saving: Boolean = false,
)

/** The owner's edits of Settings' Terms, each one core's draft rule (`MemberTerms.TermsDraft`). */
internal interface TermsEdits {
    fun text(lang: String, text: String)
    fun add(lang: String)
    fun remove(lang: String)
    fun template(lang: String)
    fun save()
}

/**
 * Settings' Terms (core's `SettingsSection.TERMS`, the web's `TenantTermsPanel`): for an owner the editor — one text per
 * language that has terms, *Add language*, each language's *Insert template* and remove (both asked first), the blank
 * language's warning, *Save terms* and the preview —, for a member the terms read-only; the operator section below both,
 * in a full-screen window over the page, as the account's flows are.
 */
@Composable
internal fun TermsPageDialog(page: TermsPage, language: String, edits: TermsEdits, onClose: () -> Unit) {
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    val said = page.said?.let { say(it) }
    androidx.compose.runtime.LaunchedEffect(page.round) { said?.let { snackbar.showSnackbar(it) } }
    FlowDialog(title = stringResource(R.string.terms_settings_title), secure = false, tag = "terms-page", onClose = onClose, snackbar = snackbar) {
        val editing = page.editing
        val draft = page.draft
        when {
            page.loading -> Row0 { CircularProgressIndicator(); Text(stringResource(R.string.terms_loading)) }
            editing == null -> Text(stringResource(R.string.terms_load_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("terms-load-failed"))
            editing.owner && draft != null -> {
                Text(stringResource(R.string.terms_editor_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (draft.showsRevision) Text(stringResource(R.string.terms_revision, draft.revision), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("terms-revision"))
                TermsEditor(draft, language, page.saving, edits)
                TermsText(editing.shown, withTerms = false)
            }
            else -> {
                Text(stringResource(R.string.terms_readonly_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TermsText(editing.shown)
            }
        }
    }
}

/** The owner's editor over core's draft: each language's field with its acts, then the warning, the hint, Save and the preview. */
@Composable
private fun TermsEditor(draft: MemberTerms.TermsDraft, language: String, saving: Boolean, edits: TermsEdits) {
    var asking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var asked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val context = LocalContext.current
    draft.texts.forEach { t ->
        val templateQuestion = say(t.templateAsk, t.name)
        val removeQuestion = say(me.raddatz.veyq.mobile.words.Msg.TERMS_REMOVE_LANG_ASK, t.name)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("terms-lang-${t.lang}")) {
            androidx.compose.material3.OutlinedTextField(
                value = t.text,
                onValueChange = { edits.text(t.lang, it) },
                label = { Text(t.name) },
                placeholder = { Text(stringResource(R.string.terms_placeholder)) },
                minLines = 6,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().testTag("terms-text-${t.lang}"),
            )
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.templated) TextButton(onClick = {
                    asked = context.getString(R.string.terms_insert_template)
                    asking = templateQuestion to { edits.template(t.lang) }
                }, enabled = !saving, modifier = Modifier.testTag("terms-template-${t.lang}")) { Text(stringResource(R.string.terms_insert_template)) }
                TextButton(onClick = {
                    asked = context.getString(R.string.terms_remove_lang, t.name)
                    asking = removeQuestion to { edits.remove(t.lang) }
                }, enabled = !saving, modifier = Modifier.testTag("terms-remove-${t.lang}")) { Text(stringResource(R.string.terms_remove_lang, t.name)) }
            }
        }
    }
    if (draft.addable.isNotEmpty()) {
        var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        Box {
            androidx.compose.material3.OutlinedButton(onClick = { menu = true }, enabled = !saving, modifier = Modifier.testTag("terms-add")) {
                Text(stringResource(R.string.terms_add_language))
            }
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                draft.addable.forEach { l ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(l.name) }, onClick = { menu = false; edits.add(l.lang) }, modifier = Modifier.testTag("terms-add-${l.lang}"))
                }
            }
        }
    }
    draft.blankWarning?.let { Text(say(it, draft.blankNames.orEmpty()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("terms-blank")) }
    Text(stringResource(R.string.terms_markdown_hint, "{contact}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PrimaryButton(stringResource(R.string.terms_save), Modifier.testTag("terms-save"), enabled = !saving, working = saving, onClick = edits::save)
    val preview = draft.preview(language)
    if (preview.isNotEmpty()) {
        HorizontalDivider()
        Text(stringResource(R.string.terms_preview), style = MaterialTheme.typography.titleSmall)
        MarkdownText(preview)
    }
    asking?.let { (question, run) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(asked) },
            text = { Text(question) },
            confirmButton = { TextButton(onClick = { asking = null; run() }, modifier = Modifier.testTag("terms-ask-confirm")) { Text(asked) } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.signin_cancel)) } },
        )
    }
}

@Composable
private fun Row0(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
