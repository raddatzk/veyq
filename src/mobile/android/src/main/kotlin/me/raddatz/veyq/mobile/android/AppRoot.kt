package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Password
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppRoot(model: AppModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    val offer by model.offer.collectAsStateWithLifecycle()
    val trustWorking by model.trustWorking.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = screen) {
            Screen.Starting -> StartingScreen()
            is Screen.SignIn -> SignInScreen(s, { server, email, password, secretKey -> model.signIn(server, email, password, secretKey) }, model::cancelAdd)
            is Screen.Unlock -> UnlockScreen(s, model::unlock, model::otherAccount)
            is Screen.Signed -> offer?.let { OfferScreen(it, trustWorking, model::answerOffer) }
                ?: s.managing?.let { ManageScreen(it, model) } ?: ShellScreen(s, model)
        }
    }
}

@Composable
fun SignInScreen(state: Screen.SignIn, onSignIn: (String, String, String, String) -> Unit, onCancel: () -> Unit = {}) {
    var server by rememberSaveable { mutableStateOf(state.server ?: BuildDefaults.SERVER) }
    var email by rememberSaveable { mutableStateOf(state.email.orEmpty()) }
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
        state.error?.let { Text(say(it), color = MaterialTheme.colorScheme.error) }
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
    // a remembered account's words follow its record's level: the presence level asks again; at LOGIN nothing asks, so
    // this shows only after a resume that failed, and says so (owner 2026-11-11, the desktops' words)
    val login = remembered && state.record.unlockLevel == me.raddatz.veyq.mobile.session.StaySignedIn.LOGIN
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
            stringResource(
                when {
                    login -> R.string.unlock_login_hint
                    remembered -> R.string.unlock_remembered_hint
                    else -> R.string.unlock_password_hint
                },
            ),
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
            if (state.working) CircularProgressIndicator(Modifier.height(20.dp)) else Text(stringResource(if (login) R.string.unlock_retry else R.string.unlock_submit))
        }
        androidx.compose.material3.TextButton(onClick = onOther, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.unlock_other))
        }
    }
}

/**
 * The offer to stay signed in after a sign-in (ADR 0123 decision 8): a full-screen step, as
 * Android's own setup asks for a screen lock or a fingerprint after an account was added — each level the core offers
 * ([offer], in its order) with the line that says what it means, then "Not now", and where to change it later. A
 * cancelled prompt keeps it (the core's rule); back does not skip it, "Not now" does.
 */
@Composable
fun OfferScreen(offer: me.raddatz.veyq.mobile.session.SessionModel.Offer, working: Boolean, onAnswer: (me.raddatz.veyq.mobile.session.StaySignedIn?) -> Unit) {
    val palette = LocalPalette.current
    androidx.activity.compose.BackHandler {}
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).formColumn(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Outlined.Fingerprint, null,
            tint = role(palette.accent), modifier = Modifier.height(48.dp).widthIn(min = 48.dp),
        )
        Text(stringResource(R.string.offer_title), style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(role(palette.surface))) {
            for (level in offer.levels) {
                val (title, hint, icon) = when (level) {
                    me.raddatz.veyq.mobile.session.StaySignedIn.PRESENCE -> Triple(R.string.offer_presence, R.string.offer_presence_hint, androidx.compose.material.icons.Icons.Outlined.Fingerprint)
                    me.raddatz.veyq.mobile.session.StaySignedIn.LOGIN -> Triple(R.string.signin_remember, R.string.offer_login_hint_phone, androidx.compose.material.icons.Icons.Outlined.LockOpen)
                }
                OfferChoice(stringResource(title), stringResource(hint), icon, enabled = !working) { onAnswer(level) }
            }
            OfferChoice(stringResource(R.string.offer_not_now), stringResource(R.string.offer_not_now_hint), androidx.compose.material.icons.Icons.Outlined.Password, enabled = !working) { onAnswer(null) }
        }
        if (working) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.offer_footer, stringResource(R.string.switcher_manage)),
            style = MaterialTheme.typography.bodySmall, color = role(palette.muted),
        )
    }
}

/** One answer of the offer: a list item, its line saying what it means. */
@Composable
private fun OfferChoice(title: String, hint: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    androidx.compose.material3.ListItem(
        headlineContent = { Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
        supportingContent = { Text(hint, color = role(palette.muted)) },
        leadingContent = { androidx.compose.material3.Icon(icon, null, tint = role(palette.accent)) },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = role(palette.surface)),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    )
}

/** A form's column: on a wide window it reads at a form's width, centred, as on Apple — never a line of fields across a tablet. */
private fun Modifier.formColumn(): Modifier =
    this.wrapContentWidth().widthIn(max = 520.dp).padding(24.dp)
