package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.session.TenantJoin
import me.raddatz.veyq.mobile.session.TenantJoin.Phase

/**
 * Joining a space from a received invite (core's `TenantJoin`, the web's `TenantJoinView`; ADR 0111, ADR 0129): a window
 * above everything, as a link's guest view is, drawing the join's phases — the invite read or why not, its code, a new
 * account with its Emergency Kit or one the person has, and done, with *Open the space* signing in as the sign-in does.
 *
 * **Secrets on screen** (`src/mobile/CLAUDE.md`): the window is flagged secure as the account's flows are — the password and
 * the Secret Key are typed here — and every secret field is masked with no reveal, its text in `remember` only, never
 * `rememberSaveable`. The kit is never drawn: core writes it into the document the save dialog made ([KitSave]). ✕ or
 * Back closes the join, which zeroes what it held.
 */
@Composable
internal fun JoinScreen(join: TenantJoin, model: AppModel) {
    val s by join.state.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    val enabled = !s.busy
    FlowDialog(stringResource(R.string.join_title), secure = true, tag = "join", onClose = { model.closeJoin(join) }) {
        when (s.phase) {
            Phase.LOADING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.join_loading), modifier = Modifier.testTag("join-loading"))
            }
            Phase.INVALID -> Said(stringResource(R.string.join_invalid), "join-invalid", error = true)
            Phase.EXPIRED -> Said(stringResource(R.string.join_expired), "join-expired", error = false)
            Phase.REDEEMED -> Said(stringResource(R.string.join_redeemed), "join-redeemed", error = false)
            Phase.CODE -> {
                Body(say(s.intro))
                Body(say(s.codeSub), muted = true)
                if (s.codeMode == me.raddatz.veyq.mobile.crypto.Invites.Mode.CODE) {
                    OutlinedTextField(
                        code, { code = it }, singleLine = true, enabled = enabled, label = { Text(say(s.codeLabel)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag("join-code"),
                    )
                } else {
                    SecretField(code, { code = it }, say(s.codeLabel), "join-code", enabled)
                }
                Refusal(s.error, "join-error")
                Button(
                    onClick = { model.joinStep(join) { it.code(code) } }, enabled = enabled && code.isNotBlank(),
                    modifier = Modifier.testTag("join-continue"),
                ) { Text(stringResource(R.string.join_continue)) }
            }
            Phase.REGISTER -> {
                Body(say(s.intro))
                Body(say(s.registerSub), muted = true)
                Details(s, enabled, username, { username = it }, firstName, { firstName = it }, lastName, { lastName = it }, email, { email = it })
                NewPasswordField(
                    join.passwordCheck, password, { password = it }, stringResource(R.string.signin_password), "join-password", enabled,
                    check = { join.checkPassword(it) }, model = model, onAnyway = join::useAnyway,
                )
                Refusal(s.error, "join-error")
                Button(
                    onClick = { model.joinStep(join) { it.register(username, firstName, lastName, email, password) } },
                    enabled = enabled && (!s.asksUsername || username.isNotBlank()) && password.length >= me.raddatz.veyq.mobile.session.PasswordChange.MIN_PASSWORD_LENGTH &&
                        (!s.asksEmail || email.isNotBlank()),
                    modifier = Modifier.testTag("join-continue"),
                ) { Text(stringResource(R.string.join_continue)) }
                if (s.offersSwitch) TextButton(onClick = { join.choose(false) }, enabled = enabled, modifier = Modifier.testTag("join-have-account")) {
                    Text(stringResource(R.string.join_have_account))
                }
            }
            Phase.KIT -> {
                Heading(stringResource(R.string.join_save_kit_title))
                Body(stringResource(R.string.join_save_kit_sub), muted = true)
                val step = join.kit
                if (step != null) {
                    KitSave(step, model)
                    val kit by step.state.collectAsStateWithLifecycle()
                    Refusal(s.error, "join-error")
                    Button(
                        onClick = { model.joinStep(join) { it.finishRegistration() } }, enabled = enabled && kit.ready,
                        modifier = Modifier.testTag("join-finish"),
                    ) { Text(say(s.joinWord)) }
                }
            }
            Phase.SIGNIN -> {
                Body(say(s.intro))
                Body(say(s.signinSub), muted = true)
                Details(s, enabled, username, { username = it }, firstName, { firstName = it }, lastName, { lastName = it }, email, { email = it })
                SecretField(password, { password = it }, stringResource(R.string.signin_password), "join-signin-password", enabled)
                // the sign-in's Secret Key control: masked, a typo said as it is typed (core's `SecretKeyProblem`)
                val typo = remember(secretKey) { model.secretKeyProblem(secretKey) == me.raddatz.veyq.mobile.crypto.SecretKeyProblem.TYPO }
                SecretField(
                    secretKey, { secretKey = it }, stringResource(R.string.signin_secret_key), "join-secret-key", enabled,
                    notice = if (typo) stringResource(R.string.secret_key_typo) else null, warn = typo,
                )
                Refusal(s.error, "join-error")
                Button(
                    onClick = { model.joinStep(join) { it.signInAndAccept(username, firstName, lastName, email, password, secretKey) } },
                    enabled = enabled && (!s.asksUsername || username.isNotBlank()) && password.isNotEmpty() && secretKey.isNotBlank() &&
                        (!s.asksEmail || email.isNotBlank()),
                    modifier = Modifier.testTag("join-accept"),
                ) { Text(say(s.joinWord)) }
                if (s.offersSwitch) TextButton(onClick = { join.choose(true) }, enabled = enabled, modifier = Modifier.testTag("join-new-account")) {
                    Text(stringResource(R.string.join_new_account))
                }
            }
            Phase.DONE -> {
                Text(say(s.done), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("join-done"))
                s.confirmationSentTo?.let { Body(say(me.raddatz.veyq.mobile.words.Msg.JOIN_CONFIRMATION_SENT, it)) }
                Body(say(s.doneNote), muted = true)
                Refusal(s.error, "join-error")
                if (s.canOpen) Button(onClick = { model.openJoinedSpace(join) }, enabled = enabled, modifier = Modifier.testTag("join-open-space")) {
                    Text(stringResource(R.string.join_open_space))
                }
            }
        }
        if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun Said(text: String, tag: String, error: Boolean) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(tag),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

/** The account step's details as the web asks them: the handle, the real name, a link invite's address. */
@Composable
private fun Details(
    s: TenantJoin.State, enabled: Boolean,
    username: String, onUsername: (String) -> Unit,
    firstName: String, onFirstName: (String) -> Unit,
    lastName: String, onLastName: (String) -> Unit,
    email: String, onEmail: (String) -> Unit,
) {
    if (s.asksUsername) {
        OutlinedTextField(
            username, onUsername, singleLine = true, enabled = enabled, label = { Text(stringResource(R.string.join_username)) },
            supportingText = { Text(stringResource(R.string.join_username_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("join-username"),
        )
        OutlinedTextField(
            firstName, onFirstName, singleLine = true, enabled = enabled, label = { Text(stringResource(R.string.profile_first_name)) },
            modifier = Modifier.fillMaxWidth().testTag("join-first-name"),
        )
        OutlinedTextField(
            lastName, onLastName, singleLine = true, enabled = enabled, label = { Text(stringResource(R.string.profile_last_name)) },
            supportingText = { Text(stringResource(R.string.join_name_hint)) },
            modifier = Modifier.fillMaxWidth().testTag("join-last-name"),
        )
    }
    if (s.asksEmail) {
        OutlinedTextField(
            email, onEmail, singleLine = true, enabled = enabled, label = { Text(stringResource(R.string.signin_email)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().testTag("join-email"),
        )
    }
}
