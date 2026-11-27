package me.raddatz.veyq.mobile.android.core

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.android.BuildDefaults
import me.raddatz.veyq.mobile.android.R

@Composable
fun AppRoot(model: AppModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    val offer by model.offer.collectAsStateWithLifecycle()
    val trustWorking by model.trustWorking.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val link by model.link.collectAsStateWithLifecycle()
    val kitScan by model.kitScan.collectAsStateWithLifecycle()
    val maps by model.maps.collectAsStateWithLifecycle()
    val settingsAt by model.settingsAt.collectAsStateWithLifecycle()
    val accountFlow by model.accountFlow.collectAsStateWithLifecycle()
    val ended by model.ended.collectAsStateWithLifecycle()
    val terms by model.terms.collectAsStateWithLifecycle()
    val termsPage by model.termsPage.collectAsStateWithLifecycle()
    val whosWho by model.whosWho.collectAsStateWithLifecycle()
    val members by model.members.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // the tenant's terms gate (uc.account.accept-terms) stands in place of the shell, the offer behind it
        val gate = terms
        if (gate != null) TermsGateScreen(gate, onAccept = model::acceptTerms, onSignOut = model::signOutAtTerms)
        else when (val s = screen) {
            Screen.Starting -> StartingScreen()
            is Screen.SignIn -> UnderTray(model) {
                SignInScreen(
                    s, { server, email, password, secretKey -> model.signIn(server, email, password, secretKey) }, model::cancelAdd,
                    onKit = model::readKit, onClearKit = model::clearKit, onScanKit = model::scanKit, kitScan = kitScan, onCloseKitScan = model::closeKitScan,
                    onScanFromTrusted = model::scanFromTrusted, onShowForSignIn = model::showForSignIn, onOpenLink = model.guests::ask,
                    secretKeyProblem = model::secretKeyProblem,
                    onLocked = model::signInLocked, onForgot = model::forgotPassword,
                )
            }
            is Screen.Locked -> LockedScreen(s, onSignOutEverywhere = model::signOutEverywhere, onBack = model::lockedBack)
            is Screen.Recover -> RecoverScreen(s, onSend = model::requestRecovery, onBack = model::backToSignIn)
            // a TV's picker (core's `Gate.Picker`): drawn by the TV activity, never reached on a phone
            is Screen.Picker -> StartingScreen()
            is Screen.Unlock -> UnderTray(model) {
                UnlockScreen(s, model::unlock, model::otherAccount, onForget = model::forgetUnlocked, onOpenLink = model.guests::ask)
            }
            is Screen.Signed -> offer?.let { OfferScreen(it, trustWorking, model::answerOffer) }
                ?: settings?.let { SettingsScreen(it, model.settingsActions, maps, at = settingsAt, onAt = model::settingsAtSeen) }
                ?: s.managing?.let { ManageScreen(it, model) } ?: ShellScreen(s, model)
        }
        // the ask before a sign-out, Stop trusting or Remove all offline content deletes writes made offline (ADR 0153)
        QueuedLossDialog(model)
        // a device-linking ceremony, over the sign-in or Settings (ADR 0059): a secure dialog of its own
        link?.let { DeviceLinkScreen(it, onAgain = model::linkAgain, onClose = model::closeLink) }
        // one of the account's flows (ADR 0148), over Settings or one's own person page: a dialog of its own
        if (screen is Screen.Signed) accountFlow?.let { AccountFlowDialog(it, model) }
        // Settings' Terms (core's `SettingsSection.TERMS`), over the page
        if (terms == null) termsPage?.let { TermsPageDialog(it, model.appLanguage(), model.termsEdits, onClose = model::closeTerms) }
        // Settings' Members (core's `SettingsSection.MEMBERS`), over the page
        if (terms == null) members?.let { MembersDialog(it, onRetry = model::openMembers, onRole = model::setMemberRole, onRemove = model::removeMember, onClose = model::closeMembers) }
        // Settings' Who's who (core's `SettingsSection.WHOS_WHO`), over the page; a row opens the person's profile
        if (terms == null) whosWho?.let { WhosWhoDialog(it, onRetry = model::openWhosWho, onOpen = model::openPersonFromWhosWho, onClose = model::closeWhosWho) }
        // the account was erased here: the sign-in says so once (the web's `explainEnding('erased')`)
        ended?.let { msg ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = model::endedSeen,
                text = { androidx.compose.material3.Text(say(msg), modifier = Modifier.testTag("erasure-ended")) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = model::endedSeen) { androidx.compose.material3.Text(stringResource(R.string.ok)) } },
            )
        }
        // a received link open as its guest (ADR 0150 decision 4): a screen above the sign-in or the shell, which stay
        // where they were; *Open a link*'s field over either
        val guest by model.guests.screen.collectAsStateWithLifecycle()
        guest?.let { GuestLinkScreen(it, model) }
        val asking by model.guests.asking.collectAsStateWithLifecycle()
        asking?.let { OpenLinkDialog(it, onOpen = model.guests::openAsked, onCancel = model.guests::cancelAsk) }
    }
}

/**
 * A way-in screen (the sign-in, the unlock) under the tray over the app's one queue alone (core's `UploadsTray`): a link
 * guest's uploads left running signed out show there as the shell's do, under the status bar (ADR 0145 decision 6, inbox
 * #1250). With nothing listed the tray takes no room.
 */
@Composable
private fun UnderTray(model: AppModel, content: @Composable () -> Unit) {
    // the status bar's inset taken here, so the screen's own safe-drawing padding does not add it again
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        UploadTray(model.signedOutTray)
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
fun SignInScreen(
    state: Screen.SignIn,
    onSignIn: (String, String, String, String) -> Unit,
    onCancel: () -> Unit = {},
    onKit: (android.net.Uri, String) -> Unit = { _, _ -> },
    onClearKit: () -> Unit = {},
    onScanKit: ((String) -> Unit)? = null,
    /** The kit scan on screen: the model's, so a rotation keeps it (#937). */
    kitScan: me.raddatz.veyq.mobile.qr.QrScan<*>? = null,
    onCloseKitScan: () -> Unit = {},
    onScanFromTrusted: ((String) -> Unit)? = null,
    onShowForSignIn: ((String) -> Unit)? = null,
    /** *Open a link* (ADR 0150 decision 4): a received link opened as its guest, no session needed. */
    onOpenLink: (() -> Unit)? = null,
    /** Why the Secret Key as typed is none (core's, inbox #1058): a typo is said under the field as it is typed. */
    secretKeyProblem: (String) -> me.raddatz.veyq.mobile.crypto.SecretKeyProblem? = { null },
    /** *I don't have my Secret Key with me* (uc.account.sessions E3): the server, the email and the password. */
    onLocked: (String, String, String) -> Unit = { _, _, _ -> },
    /** *Forgot your password?* (uc.account.recover-access): the server and the email as typed. */
    onForgot: (String, String) -> Unit = { _, _ -> },
) {
    var server by rememberSaveable { mutableStateOf(state.server ?: BuildDefaults.SERVER) }
    var email by rememberSaveable { mutableStateOf(state.email.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var secretKey by rememberSaveable { mutableStateOf("") }
    // a kit read fills the email and the server it gave (the core's gate); both stay editable
    androidx.compose.runtime.LaunchedEffect(state.kit) {
        if (state.kit != null) {
            email = state.email.orEmpty()
            state.server?.let { server = it }
            secretKey = ""
        }
    }
    // the system's document picker (Storage Access Framework): a PDF or a picture of the kit's QR, read in memory
    val pickKit = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { onKit(it, server) } }
    // the camera's scan of the kit's QR, first on a phone or tablet; offered while the device may have a camera — a
    // scan that finds none drops the entry for this screen (an emulator names a camera feature it lacks)
    val context = androidx.compose.ui.platform.LocalContext.current
    var noCamera by rememberSaveable {
        mutableStateOf(!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY))
    }
    kitScan?.let { KitScanner(it, onClose = onCloseKitScan, onNoCamera = { noCamera = true }) }
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
        val kit = state.kit
        if (kit != null) {
            // the kit's Secret Key is the core's, never shown: the row says where it came from, its clear goes back to typing
            OutlinedTextField(
                stringResource(R.string.signin_kit_held), {}, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.signin_secret_key)) },
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = onClearKit, enabled = !state.working) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Close, stringResource(R.string.signin_kit_clear))
                    }
                },
            )
        } else {
            // a typo is said as it is typed (the web's SecretKeyField); a key that is no key at all says nothing until the sign-in
            val typo = androidx.compose.runtime.remember(secretKey) { secretKeyProblem(secretKey) == me.raddatz.veyq.mobile.crypto.SecretKeyProblem.TYPO }
            OutlinedTextField(secretKey, { secretKey = it }, label = { Text(stringResource(R.string.signin_secret_key)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("signin-secret-key"), isError = typo,
                supportingText = if (typo) { { Text(stringResource(R.string.secret_key_typo), modifier = Modifier.testTag("signin-secret-key-typo")) } } else null,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
        kit?.typedServer?.let { before ->
            Text(
                stringResource(R.string.signin_kit_other_server, kit.server.orEmpty(), before),
                style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted),
            )
        }
        state.error?.let { Text(say(it), color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(say(it), color = role(LocalPalette.current.primary), modifier = Modifier.testTag("signin-notice")) }
        // the locked sign-in's way back keeps nothing on this device, and the form says so (uc.account.sessions E3)
        if (state.keepsNothing) Text(stringResource(R.string.locked_back_hint), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted))
        // the ways in, every one core's plan offers and in its order (`SignInPlan`, ADR 0154 decision 2: the web's —
        // Submit first); a scan is offered while the device may have a camera. Each entry's look is Android's.
        val palette = LocalPalette.current
        for (entry in me.raddatz.veyq.mobile.session.SignInPlan.entries(adding = state.adding, camera = !noCamera)) when (entry) {
            // confirming is the web's green role (`--app-primary`), not the accent
            me.raddatz.veyq.mobile.session.SignInEntry.SUBMIT -> Button(
                onClick = { onSignIn(server, email, password, if (state.kit != null) "" else secretKey) },
                enabled = !state.working,
                shape = MaterialTheme.shapes.small,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = role(palette.primary), contentColor = role(palette.onFill)),
                modifier = Modifier.fillMaxWidth().height(44.dp).testTag("signin-submit"),
            ) {
                if (state.working) CircularProgressIndicator(Modifier.height(20.dp)) else Text(stringResource(R.string.signin_submit))
            }
            me.raddatz.veyq.mobile.session.SignInEntry.CANCEL ->
                androidx.compose.material3.TextButton(onClick = onCancel, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.signin_cancel))
                }
            // ADR 0059 §1: another device lets this one in, over the server above (nothing in a scan changes it)
            me.raddatz.veyq.mobile.session.SignInEntry.LINK_SHOW -> onShowForSignIn?.let { show ->
                androidx.compose.material3.OutlinedButton(
                    onClick = { show(server) },
                    enabled = !state.working,
                    modifier = Modifier.fillMaxWidth().testTag("signin-link-show"),
                ) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.QrCode2, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.signin_link_show))
                }
            }
            me.raddatz.veyq.mobile.session.SignInEntry.LINK_SCAN -> onScanFromTrusted?.let { scan ->
                androidx.compose.material3.OutlinedButton(
                    onClick = { scan(server) },
                    enabled = !state.working,
                    modifier = Modifier.fillMaxWidth().testTag("signin-link-scan"),
                ) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.QrCodeScanner, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.signin_link_scan))
                }
            }
            // the system's document picker: a PDF or a picture of the kit's QR
            me.raddatz.veyq.mobile.session.SignInEntry.KIT_OPEN -> androidx.compose.material3.OutlinedButton(
                onClick = { pickKit.launch(arrayOf("application/pdf", "image/*")) },
                enabled = !state.working,
                modifier = Modifier.fillMaxWidth().testTag("signin-kit-open"),
            ) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.FileOpen, null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.signin_kit_open))
            }
            me.raddatz.veyq.mobile.session.SignInEntry.KIT_SCAN -> onScanKit?.let { scan ->
                androidx.compose.material3.OutlinedButton(
                    onClick = { scan(server) },
                    enabled = !state.working,
                    modifier = Modifier.fillMaxWidth().testTag("signin-kit-scan"),
                ) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.QrCodeScanner, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.signin_kit_scan))
                }
            }
            // uc.account.sessions E3: the login alone, to sign every device out — the email and the password needed
            me.raddatz.veyq.mobile.session.SignInEntry.LOCKED -> androidx.compose.material3.TextButton(
                onClick = { onLocked(server, email, password) },
                enabled = !state.working && email.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("signin-locked"),
            ) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Lock, null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.signin_locked))
            }
            me.raddatz.veyq.mobile.session.SignInEntry.FORGOT -> androidx.compose.material3.TextButton(
                onClick = { onForgot(server, email) },
                enabled = !state.working,
                modifier = Modifier.fillMaxWidth().testTag("signin-forgot"),
            ) { Text(stringResource(R.string.signin_forgot)) }
            me.raddatz.veyq.mobile.session.SignInEntry.OPEN_LINK -> onOpenLink?.let { OpenLinkButton(it) }
        }
    }
}

/**
 * Signed in, still locked (uc.account.sessions E3, core's `Gate.Locked`): what this state is, that this device keeps
 * nothing, then core's entries (`LockedPlan`) — *Sign out everywhere, including here* in the error colour, asked first
 * with the web's question, and the way back. The system's Back is the way back.
 */
@Composable
fun LockedScreen(state: Screen.Locked, onSignOutEverywhere: () -> Unit, onBack: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = !state.working, onBack = onBack)
    val palette = LocalPalette.current
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).formColumn(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Brand()
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.locked_title), style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(state.email, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.locked_note), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.locked_scope_note), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
        state.error?.let { Text(say(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("locked-error")) }
        for (entry in me.raddatz.veyq.mobile.session.LockedPlan.entries()) when (entry) {
            me.raddatz.veyq.mobile.session.LockedEntry.SIGN_OUT_EVERYWHERE -> Button(
                onClick = { asking = true },
                enabled = !state.working,
                shape = MaterialTheme.shapes.small,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth().height(44.dp).testTag("locked-sign-out-everywhere"),
            ) {
                if (state.working) CircularProgressIndicator(Modifier.height(20.dp)) else Text(stringResource(R.string.locked_action))
            }
            me.raddatz.veyq.mobile.session.LockedEntry.BACK -> {
                androidx.compose.material3.TextButton(onClick = onBack, enabled = !state.working, modifier = Modifier.fillMaxWidth().testTag("locked-back")) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Key, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.locked_back))
                }
                Text(stringResource(R.string.locked_back_hint), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
            }
        }
    }
    if (asking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.locked_confirm_title)) },
            text = { Text(stringResource(R.string.locked_confirm)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { asking = false; onSignOutEverywhere() },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("locked-confirm"),
                ) { Text(stringResource(R.string.locked_action)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text(stringResource(R.string.signin_cancel)) } },
        )
    }
}

/**
 * *Reset your password* (uc.account.recover-access steps 1–2, core's `Gate.Recover`): the web's intro, the server and the
 * address, core's entries (`RecoverPlan`) — Send until the link was asked for, then Back —, the answer, that the mailed
 * link opens the web's page which finishes the reset, and that nothing helps without the kit. The system's Back is Back.
 */
@Composable
fun RecoverScreen(state: Screen.Recover, onSend: (String, String) -> Unit, onBack: () -> Unit) {
    var server by rememberSaveable { mutableStateOf(state.server ?: BuildDefaults.SERVER) }
    var email by rememberSaveable { mutableStateOf(state.email.orEmpty()) }
    androidx.activity.compose.BackHandler(enabled = !state.working, onBack = onBack)
    val palette = LocalPalette.current
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).formColumn(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Brand()
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(stringResource(R.string.recovery_intro), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(server, { server = it }, label = { Text(stringResource(R.string.signin_server)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            enabled = !state.sent, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.recovery_email)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("recover-email"), enabled = !state.sent, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        if (state.sent) {
            Text(stringResource(R.string.recovery_sent), color = role(palette.primary), modifier = Modifier.testTag("recover-sent"))
            Text(stringResource(R.string.recovery_on_web), style = MaterialTheme.typography.bodyMedium)
        }
        state.error?.let { Text(say(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("recover-error")) }
        for (entry in me.raddatz.veyq.mobile.session.RecoverPlan.entries(state.sent)) when (entry) {
            me.raddatz.veyq.mobile.session.RecoverEntry.SEND -> Button(
                onClick = { onSend(server, email) },
                enabled = !state.working && server.isNotBlank() && email.isNotBlank(),
                shape = MaterialTheme.shapes.small,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = role(palette.primary), contentColor = role(palette.onFill)),
                modifier = Modifier.fillMaxWidth().height(44.dp).testTag("recover-send"),
            ) {
                if (state.working) CircularProgressIndicator(Modifier.height(20.dp)) else Text(stringResource(R.string.recovery_send))
            }
            me.raddatz.veyq.mobile.session.RecoverEntry.BACK ->
                androidx.compose.material3.TextButton(onClick = onBack, enabled = !state.working, modifier = Modifier.fillMaxWidth().testTag("recover-back")) {
                    Text(stringResource(R.string.back))
                }
        }
        Text(stringResource(R.string.recovery_no_escrow), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
    }
}

/** *Open a link* under the sign-in (ADR 0150 decision 4; owner 2026-11-19): a received link needs no session. */
@Composable
private fun OpenLinkButton(onClick: () -> Unit) {
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp), color = role(LocalPalette.current.line))
    androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("signin-open-link")) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Link, null, Modifier.padding(end = 8.dp))
        Text(stringResource(R.string.link_open))
    }
}

@Composable
internal fun StartingScreen() {
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
 * asks for the person's authentication again for a remembered one; then the core's entries under it (`UnlockPlan`,
 * ADR 0154 decision 2), whole and in their order — the way to another account, *Open a link*, and *Stop trusting this
 * device* in the error colour (inbox #1052), always asked first by the core.
 */
@Composable
fun UnlockScreen(state: Screen.Unlock, onUnlock: (String) -> Unit, onOther: () -> Unit, onForget: () -> Unit, onOpenLink: (() -> Unit)? = null) {
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
        for (entry in me.raddatz.veyq.mobile.session.UnlockPlan.entries(inExtension = false)) when (entry) {
            me.raddatz.veyq.mobile.session.UnlockEntry.SUBMIT -> Button(
                onClick = { onUnlock(password) },
                enabled = !state.working,
                shape = MaterialTheme.shapes.small,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = role(palette.primary), contentColor = role(palette.onFill)),
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                if (state.working) CircularProgressIndicator(Modifier.height(20.dp)) else Text(stringResource(if (login) R.string.unlock_retry else R.string.unlock_submit))
            }
            me.raddatz.veyq.mobile.session.UnlockEntry.OTHER_ACCOUNT ->
                androidx.compose.material3.TextButton(onClick = onOther, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.unlock_other))
                }
            me.raddatz.veyq.mobile.session.UnlockEntry.OPEN_LINK -> onOpenLink?.let { OpenLinkButton(it) }
            me.raddatz.veyq.mobile.session.UnlockEntry.FORGET ->
                androidx.compose.material3.TextButton(onClick = onForget, enabled = !state.working, modifier = Modifier.fillMaxWidth().testTag("unlock-forget")) {
                    Text(stringResource(R.string.manage_forget_device), color = MaterialTheme.colorScheme.error)
                }
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
            OfferChoice(stringResource(R.string.offer_not_now), stringResource(if (offer.linked) R.string.offer_not_now_linked_hint else R.string.offer_not_now_hint), androidx.compose.material.icons.Icons.Outlined.Password, enabled = !working) { onAnswer(null) }
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
