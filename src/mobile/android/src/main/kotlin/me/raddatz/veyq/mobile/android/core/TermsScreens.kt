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
internal fun TermsText(shown: MemberTerms.TermsShown) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (shown.hasTerms && shown.terms.isNotBlank()) MarkdownText(shown.terms)
        else Text(stringResource(R.string.terms_none_set), color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** What Settings' Terms page shows: still loading, the terms, or that they did not load ([shown] null). */
internal data class TermsPage(val loading: Boolean, val shown: MemberTerms.TermsShown? = null)

/**
 * Settings' Terms (core's `SettingsSection.TERMS`, the web's `TenantTermsPanel` read-only): the open space's terms and the
 * operator section, in a full-screen window over the page, as the account's flows are; editing them is the owner's, in the
 * browser, which the page says.
 */
@Composable
internal fun TermsPageDialog(page: TermsPage, onClose: () -> Unit) {
    FlowDialog(title = stringResource(R.string.terms_settings_title), secure = false, tag = "terms-page", onClose = onClose) {
        when {
            page.loading -> Row0 { CircularProgressIndicator(); Text(stringResource(R.string.terms_loading)) }
            page.shown == null -> Text(stringResource(R.string.terms_load_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("terms-load-failed"))
            else -> {
                Text(stringResource(R.string.terms_readonly_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TermsText(page.shown)
            }
        }
    }
}

@Composable
private fun Row0(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
