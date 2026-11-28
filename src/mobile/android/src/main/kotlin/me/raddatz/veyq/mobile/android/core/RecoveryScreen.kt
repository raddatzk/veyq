package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.session.RecoveryFinish
import me.raddatz.veyq.mobile.session.RecoveryPhase as Phase

/**
 * Finishing a password recovery from its mailed link (core's `RecoveryFinish`, the web's `RecoverView`;
 * uc.account.recover-access): a window above everything, as a join is, drawing the web's steps — the link checked or why
 * not, the Secret Key (the flows' key control: typed, a pasted kit token, a kit file, a scan), a new password with its
 * breached-password check, and done, whose *Sign in* opens the sign-in filled with the account.
 *
 * **Secrets on screen** (`src/mobile/CLAUDE.md`): the window is flagged secure — the Secret Key and the password are typed
 * here, and the kit scanner shows the key — and every secret field is masked with no reveal, its text in `remember` only.
 * ✕ or Back closes the finish, which zeroes what it held.
 */
@Composable
internal fun RecoveryScreen(finish: RecoveryFinish, model: AppModel) {
    val s by finish.state.collectAsStateWithLifecycle()
    val intake by finish.intake.state.collectAsStateWithLifecycle()
    var secretKey by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val enabled = !s.working
    FlowDialog(say(s.title), secure = true, tag = "recovery", onClose = { model.closeRecovery(finish) }) {
        when (s.phase) {
            Phase.LOADING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.recovery_checking), modifier = Modifier.testTag("recovery-loading"))
            }
            Phase.SPENT -> Refusal(s.refusal, "recovery-spent")
            Phase.KEY -> {
                s.forAccount?.let { Body(say(it)) }
                Body(stringResource(R.string.recovery_step_key_intro), muted = true)
                FlowKeyField(
                    finish.intake, secretKey, { secretKey = it }, stringResource(R.string.signin_secret_key), "recovery-secret-key", enabled,
                    hint = stringResource(R.string.recovery_secret_key_hint), model = model,
                )
                if (s.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Refusal(s.refusal, "recovery-refusal")
                Button(
                    onClick = { model.recoveryStep(finish) { it.unlock(secretKey) } },
                    enabled = enabled && (secretKey.isNotBlank() || intake.kit != null), modifier = Modifier.testTag("recovery-continue"),
                ) { Text(stringResource(R.string.join_continue)) }
            }
            Phase.PASSWORD -> {
                s.forAccount?.let { Body(say(it)) }
                NewPasswordField(finish.breach, next, { next = it }, stringResource(R.string.password_change_new), "recovery-new", enabled, finish::check, model)
                SecretField(confirm, { confirm = it }, stringResource(R.string.recovery_confirm_password), "recovery-confirm", enabled)
                Body(stringResource(R.string.recovery_device_note), muted = true)
                if (s.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Refusal(s.refusal, "recovery-refusal")
                Button(
                    onClick = { model.recoveryStep(finish) { it.finish(next, confirm) } },
                    enabled = enabled && next.isNotEmpty() && confirm.isNotEmpty(), modifier = Modifier.testTag("recovery-submit"),
                ) { Text(stringResource(R.string.recovery_submit)) }
            }
            Phase.DONE -> {
                Text(stringResource(R.string.recovery_done), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("recovery-done"))
                Button(onClick = { model.recoveredSignIn(finish) }, modifier = Modifier.testTag("recovery-sign-in")) {
                    Text(stringResource(R.string.signin_submit))
                }
            }
        }
        Body(stringResource(R.string.recovery_no_escrow), muted = true)
    }
    // the key's kit scan, over the finish (the flows' one scanner)
    val scan by model.flowKitScan.collectAsStateWithLifecycle()
    scan?.let { KitScanner(it, onClose = model::closeFlowKitScan, onNoCamera = model::flowCameraMissing) }
}
