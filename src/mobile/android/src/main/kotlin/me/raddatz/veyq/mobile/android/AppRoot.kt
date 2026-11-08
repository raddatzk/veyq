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
            is Screen.SignIn -> SignInScreen(s, model::signIn, model::cancelAdd)
            is Screen.Signed -> s.managing?.let { ManageScreen(it, model) } ?: ShellScreen(s, model)
        }
    }
}

@Composable
fun SignInScreen(state: Screen.SignIn, onSignIn: (String, String, String, String) -> Unit, onCancel: () -> Unit = {}) {
    var server by rememberSaveable { mutableStateOf(BuildDefaults.SERVER) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var secretKey by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
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
        state.error?.let { Text(signInError(it), color = MaterialTheme.colorScheme.error) }
        // Confirming is the web's green role (`--app-primary`), not the accent.
        val palette = LocalPalette.current
        Button(
            onClick = { onSignIn(server, email, password, secretKey) },
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
private fun signInError(reason: String): String = stringResource(
    when (reason) {
        "UNREACHABLE" -> R.string.signin_error_unreachable
        "INCOMPATIBLE" -> R.string.signin_error_incompatible
        "CREDENTIALS" -> R.string.signin_error_credentials
        "SECRET_KEY" -> R.string.signin_error_secret_key
        "NO_TENANT" -> R.string.signin_error_no_tenant
        else -> R.string.signin_error_other
    },
)
