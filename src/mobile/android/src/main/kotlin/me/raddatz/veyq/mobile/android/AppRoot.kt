package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppRoot(model: AppModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = screen) {
            Screen.Starting -> StartingScreen()
            is Screen.SignIn -> SignInScreen(s, model::signIn, model::cancelAdd)
            is Screen.Unlock -> UnlockScreen(s, model::unlock, model::otherAccount)
            is Screen.Signed -> s.managing?.let { ManageScreen(it, model) } ?: ShellScreen(s, model)
        }
    }
}

@Composable
fun SignInScreen(state: Screen.SignIn, onSignIn: (String, String, String, String, Boolean) -> Unit, onCancel: () -> Unit = {}) {
    var server by rememberSaveable { mutableStateOf(state.server ?: BuildDefaults.SERVER) }
    var email by rememberSaveable { mutableStateOf(state.email.orEmpty()) }
    var remember by rememberSaveable { mutableStateOf(false) }
    var password by rememberSaveable { mutableStateOf("") }
    var secretKey by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).formColumn(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Brand()
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (state.adding) R.string.signin_add_title else R.string.signin_title),
            style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        OutlinedTextField(server, { server = it }, label = { Text(stringResource(R.string.signin_server)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.signin_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.signin_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        OutlinedTextField(secretKey, { secretKey = it }, label = { Text(stringResource(R.string.signin_secret_key)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        // Staying signed in is an explicit choice, never the default (ADR 0005 § 13): it says what it costs.
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.signin_remember), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.signin_remember_hint), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted))
            }
            androidx.compose.material3.Switch(checked = remember, onCheckedChange = { remember = it }, enabled = !state.working)
        }
        state.error?.let { Text(say(it), color = MaterialTheme.colorScheme.error) }
        // Confirming is the web's green role (`--app-primary`), not the accent.
        val palette = LocalPalette.current
        Button(
            onClick = { onSignIn(server, email, password, secretKey, remember) },
            enabled = !state.working,
            shape = MaterialTheme.shapes.small,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = role(palette.primary), contentColor = role(palette.onFill)),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            if (state.working) CircularProgressIndicator(Modifier.height(20.dp)) else Text(stringResource(R.string.signin_submit))
        }
        if (state.adding) {
            androidx.compose.material3.TextButton(onClick = onCancel, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.signin_cancel))
            }
        }
    }
}

@Composable
private fun StartingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Brand()
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator()
    }
}

/**
 * An account this device trusts (ADR 0123 decision 8): the password alone for a password-trusted one, a button that
 * asks for the person's authentication again for a remembered one; and the way to another account.
 */
@Composable
fun UnlockScreen(state: Screen.Unlock, onUnlock: (String) -> Unit, onOther: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    val remembered = state.record.method == me.raddatz.veyq.mobile.session.TrustedDevice.Method.DEVICE_KEY
    val palette = LocalPalette.current
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).formColumn(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Brand()
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(state.record.email, style = MaterialTheme.typography.bodyLarge)
        Text(io.ktor.http.Url(state.record.server).host, style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
        Text(
            stringResource(if (remembered) R.string.unlock_remembered_hint else R.string.unlock_password_hint),
            style = MaterialTheme.typography.bodyMedium, color = role(palette.muted),
        )
        if (!remembered) {
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.signin_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
        state.error?.let { Text(say(it), color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { onUnlock(password) },
            enabled = !state.working,
            shape = MaterialTheme.shapes.small,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = role(palette.primary), contentColor = role(palette.onFill)),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            if (state.working) CircularProgressIndicator(Modifier.height(20.dp)) else Text(stringResource(R.string.unlock_submit))
        }
        androidx.compose.material3.TextButton(onClick = onOther, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.unlock_other))
        }
    }
}

/** A form's column: on a wide window it reads at a form's width, centred, as on Apple — never a line of fields across a tablet. */
private fun Modifier.formColumn(): Modifier =
    this.wrapContentWidth().widthIn(max = 520.dp).padding(24.dp)
