package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.session.TrustReset

/**
 * *Start a list over* (core's `TrustReset`, ADR 0028 decision 7; the web's `TrustResetPanel` and `TrustResetDialog`): a
 * full-screen window over Settings listing each list this device refuses — its name, why, *Start over…* — and the
 * question before it is started over: what it costs, that the server can wait, the acknowledgement (unticked at every
 * open) and the destructive *Start over*. Core decides every row, word and outcome; no password or key is asked.
 */
@Composable
internal fun TrustResetScreen(m: TrustReset, model: AppModel) {
    val s by m.state.collectAsStateWithLifecycle()
    FlowDialog(stringResource(R.string.trust_reset_title), secure = false, tag = "trust-reset", onClose = model::closeTrustReset) {
        Body(stringResource(R.string.trust_reset_sub), muted = true)
        s.said?.let { Text(say(it), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("trust-reset-said")) }
        when {
            s.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
            s.loadFailed != null -> Refusal(s.loadFailed, "trust-reset-load-failed")
            s.none -> Body(stringResource(R.string.trust_reset_none))
            else -> Column(Modifier.testTag("trust-reset-rows")) {
                s.rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider()
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(say(row.title), style = MaterialTheme.typography.titleSmall)
                        Body(say(row.why), muted = true)
                        OutlinedButton(onClick = { m.ask(row.document) }, modifier = Modifier.testTag("trust-reset-ask-${row.document.name.lowercase()}")) {
                            Text(stringResource(R.string.trust_reset_action))
                        }
                    }
                }
            }
        }
    }
    s.asking?.let { a ->
        AlertDialog(
            onDismissRequest = { m.cancel() },
            title = { Text(say(a.title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(say(a.cost))
                    Text(stringResource(R.string.trust_reset_server_can_wait), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CheckRow(a.acknowledged, enabled = !a.working, label = stringResource(R.string.trust_reset_acknowledge), tag = "trust-reset-acknowledge") { m.acknowledge(it) }
                    Refusal(a.failed, "trust-reset-failed")
                }
            },
            confirmButton = {
                Button(
                    onClick = { model.trustResetStep { m.confirm() } }, enabled = a.acknowledged && !a.working,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.testTag("trust-reset-confirm"),
                ) { Text(stringResource(R.string.trust_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { m.cancel() }, enabled = !a.working) { Text(stringResource(R.string.signin_cancel)) } },
        )
    }
}
