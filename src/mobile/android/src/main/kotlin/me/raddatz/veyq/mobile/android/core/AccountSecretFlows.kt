package me.raddatz.veyq.mobile.android.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.account.BreachCheck
import me.raddatz.veyq.mobile.account.KeyIntake
import me.raddatz.veyq.mobile.account.KitStep
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.session.AccountErasure
import me.raddatz.veyq.mobile.session.AddressChange
import me.raddatz.veyq.mobile.session.PasswordChange
import me.raddatz.veyq.mobile.session.rotation.KeyRotation
import me.raddatz.veyq.mobile.words.Msg

/**
 * The account's flows where a password or the Secret Key is typed (ADR 0148 decisions 5–8): the password, the address,
 * the keys replaced with a new Emergency Kit saved first, the erasure — each a secure [FlowDialog] (see [AccountFlowDialog]).
 */
/** A secret typed: masked, no reveal, the platform's password keyboard (no suggestions, no learning). */
@Composable
internal fun SecretField(
    value: String, onValue: (String) -> Unit, label: String, tag: String, enabled: Boolean = true, hint: String? = null,
    /** Called when the field is left (its focus lost after it had it): the breached-password check's moment. */
    onLeave: (() -> Unit)? = null,
    /** Drawn under the field in place of [hint] (the breach check's notice); [warn] draws it as a warning. */
    notice: String? = null,
    warn: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onValue, label = { Text(label) }, singleLine = true, enabled = enabled,
        supportingText = (notice ?: hint)?.let { { Text(it, modifier = Modifier.testTag("$tag-notice")) } },
        isError = warn,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag(tag).onFocusChanged {
            if (focused && !it.isFocused) onLeave?.invoke()
            focused = it.isFocused
        },
    )
}

/**
 * A flow's Secret Key section (ADR 0151 decision 4): the sign-in's control — the key typed or a `veyqkit:` token pasted
 * (the core's intake swaps it), a kit file opened through the system's picker, a scan where a camera may be — and a kit
 * held shown as at the sign-in, the key never drawn, with *Enter it instead*. A kit of another account warns, one made at
 * another server informs (decision 2); neither blocks.
 */
@Composable
internal fun FlowKeyField(
    intake: KeyIntake, value: String, onValue: (String) -> Unit, label: String, tag: String, enabled: Boolean, hint: String?, model: AppModel,
) {
    val state by intake.state.collectAsStateWithLifecycle()
    val cameraMissing by model.flowNoCamera.collectAsStateWithLifecycle()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { model.readFlowKit(intake, it) } }
    val context = LocalContext.current
    // as the sign-in: offered while the device may have a camera; a scan that finds none drops the entry (the scanner is
    // drawn once over the flow, AccountFlowDialog)
    val noCamera = cameraMissing || !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
    val kit = state.kit
    LaunchedEffect(kit) { if (kit != null) onValue("") }
    if (kit != null) {
        OutlinedTextField(
            stringResource(R.string.signin_kit_held), {}, readOnly = true, singleLine = true, label = { Text(label) },
            supportingText = { Text(kit.email) },
            trailingIcon = {
                IconButton(onClick = { intake.clear() }, enabled = enabled, modifier = Modifier.testTag("$tag-kit-clear")) {
                    Icon(Icons.Filled.Close, stringResource(R.string.signin_kit_clear))
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("$tag-kit"),
        )
    } else {
        // a typo is said as it is typed (core's `KeyIntake.problem`, the web's SecretKeyField); a malformed key says nothing
        val typo = remember(value) { intake.problem(value) == me.raddatz.veyq.mobile.crypto.SecretKeyProblem.TYPO }
        SecretField(value, onValue, label, tag, enabled && !state.reading, hint, notice = if (typo) stringResource(R.string.secret_key_typo) else null, warn = typo)
    }
    if (state.reading) LinearProgressIndicator(Modifier.fillMaxWidth())
    Refusal(state.problem, "$tag-kit-problem")
    kit?.otherAccount?.let { expected ->
        Text(
            say(Msg.KIT_EMAIL_MISMATCH, kit.email, expected), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("$tag-kit-other-account"),
        )
    }
    kit?.otherServer?.let { here ->
        Text(
            stringResource(R.string.signin_kit_other_server, kit.server.orEmpty(), here),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!noCamera) {
        OutlinedButton(onClick = { model.scanFlowKit(intake) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("$tag-kit-scan")) {
            Icon(Icons.Outlined.QrCodeScanner, null, Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.signin_kit_scan))
        }
    }
    OutlinedButton(
        onClick = { pick.launch(arrayOf("application/pdf", "image/*")) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("$tag-kit-open"),
    ) {
        Icon(Icons.Outlined.FileOpen, null, Modifier.padding(end = 8.dp))
        Text(stringResource(R.string.signin_kit_open))
    }
}

/**
 * The new password with the web's breached-password check (ADR 0151 decision 5): checked when the field is left, never
 * while typing; any edit drops the verdict; a hit says so under the field (the count) with *Use it anyway* as a checkbox
 * row; a check that could not run says that and stops nothing. No range service configured: nothing is drawn.
 */
@Composable
internal fun NewPasswordField(
    breach: BreachCheck, value: String, onValue: (String) -> Unit, label: String, tag: String, enabled: Boolean,
    check: suspend (String) -> Unit, model: AppModel,
) {
    val state by breach.state.collectAsStateWithLifecycle()
    val notice = when (state.kind) {
        BreachCheck.BreachKind.HIT, BreachCheck.BreachKind.ACCEPTED -> say(Msg.PASSWORD_PWNED_WARNING, state.count)
        BreachCheck.BreachKind.UNAVAILABLE -> say(Msg.PASSWORD_PWNED_UNAVAILABLE)
        else -> null
    }
    SecretField(
        value, {
            onValue(it)
            breach.edited()
        }, label, tag, enabled,
        onLeave = { model.checkBreach { check(value) } },
        notice = notice, warn = state.kind == BreachCheck.BreachKind.HIT,
    )
    if (state.kind == BreachCheck.BreachKind.HIT || state.kind == BreachCheck.BreachKind.ACCEPTED) {
        CheckRow(state.kind == BreachCheck.BreachKind.ACCEPTED, enabled = enabled, label = stringResource(R.string.password_pwned_anyway), tag = "$tag-anyway") {
            breach.useAnyway(it)
        }
    }
}


// ---- the password (uc.account.change-password) ----

@Composable
internal fun PasswordFlow(m: PasswordChange, model: AppModel) {
    val state by m.state.collectAsStateWithLifecycle()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    val intake by m.intake.state.collectAsStateWithLifecycle()
    FlowDialog(stringResource(R.string.password_change_title), secure = true, tag = "password-change", onClose = model::closeAccountFlow) {
        if (state.done) {
            Text(say(Msg.PASSWORD_CHANGE_DONE), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("password-change-done"))
            Body(say(Msg.PASSWORD_CHANGE_DONE_NOTE), muted = true)
            Button(onClick = model::closeAccountFlow) { Text(stringResource(R.string.ok)) }
            return@FlowDialog
        }
        Body(stringResource(R.string.password_change_sub), muted = true)
        val enabled = !state.working
        SecretField(current, { current = it }, stringResource(R.string.password_change_current), "password-change-current", enabled)
        NewPasswordField(m.breach, next, { next = it }, stringResource(R.string.password_change_new), "password-change-new", enabled, m::check, model)
        SecretField(confirm, { confirm = it }, stringResource(R.string.password_change_confirm), "password-change-confirm", enabled)
        FlowKeyField(
            m.intake, secretKey, { secretKey = it }, stringResource(R.string.password_change_secret_key), "password-change-secret-key", enabled,
            hint = stringResource(R.string.password_change_secret_key_hint), model = model,
        )
        if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        Refusal(state.refusal, "password-change-refusal")
        Button(
            onClick = { model.changePassword(m, current, next, confirm, secretKey) },
            enabled = enabled && current.isNotEmpty() && next.isNotEmpty() && confirm.isNotEmpty() && (secretKey.isNotBlank() || intake.kit != null),
            modifier = Modifier.testTag("password-change-submit"),
        ) { Text(stringResource(R.string.password_change_submit)) }
    }
}

// ---- the address (uc.account.change-email): the request here, the confirm on the web; a new kit with the address ----

@Composable
internal fun AddressFlow(m: AddressChange, model: AppModel) {
    val state by m.state.collectAsStateWithLifecycle()
    var newEmail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var kitPassword by remember { mutableStateOf("") }
    var kitSecretKey by remember { mutableStateOf("") }
    // which half acted last says the model's one refusal
    var kitAsked by remember { mutableStateOf(false) }
    var kit by remember { mutableStateOf<KitStep?>(null) }
    val intake by m.intake.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { kit?.close() } }
    FlowDialog(stringResource(R.string.email_change_action), secure = true, tag = "address-change", onClose = model::closeAccountFlow) {
        val enabled = !state.working
        Body(stringResource(R.string.email_change_form_sub), muted = true)
        val sent = state.sentTo
        if (sent != null) {
            Text(say(Msg.EMAIL_CHANGE_SENT, sent), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("address-change-sent"))
        } else {
            OutlinedTextField(
                newEmail, { newEmail = it }, label = { Text(stringResource(R.string.email_change_new_email)) }, singleLine = true, enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("address-change-email"),
            )
            SecretField(password, { password = it }, stringResource(R.string.rotation_password), "address-change-password", enabled)
            FlowKeyField(m.intake, secretKey, { secretKey = it }, stringResource(R.string.rotation_secret_key), "address-change-secret-key", enabled, stringResource(R.string.rotation_secret_key_hint), model)
            if (!kitAsked) Refusal(state.refusal, "address-change-refusal")
            Button(
                onClick = {
                    kitAsked = false
                    model.requestAddressChange(m, newEmail, password, secretKey)
                },
                enabled = enabled && newEmail.isNotBlank() && password.isNotEmpty() && (secretKey.isNotBlank() || intake.kit != null),
                modifier = Modifier.testTag("address-change-submit"),
            ) { Text(stringResource(R.string.email_change_submit)) }
        }
        if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        // the new kit follows the confirm (D6, inbox #1010): offered once this sign-in took the new address, never right
        // after the request — the account's address is still the old one then
        if (state.kitOffered) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Heading(stringResource(R.string.email_change_kit_title))
            val step = kit
            if (step == null) {
                Body(stringResource(R.string.email_change_kit_sub), muted = true)
                SecretField(kitPassword, { kitPassword = it }, stringResource(R.string.rotation_password), "address-kit-password", enabled)
                FlowKeyField(m.intake, kitSecretKey, { kitSecretKey = it }, stringResource(R.string.rotation_secret_key), "address-kit-secret-key", enabled, stringResource(R.string.rotation_secret_key_hint), model)
                if (kitAsked) Refusal(state.refusal, "address-kit-refusal")
                OutlinedButton(
                    onClick = {
                        kitAsked = true
                        model.addressKit(m, kitPassword, kitSecretKey) { made ->
                            kit = made
                            if (made != null) {
                                kitPassword = ""
                                kitSecretKey = ""
                            }
                        }
                    },
                    enabled = enabled && kitPassword.isNotEmpty() && (kitSecretKey.isNotBlank() || intake.kit != null),
                    modifier = Modifier.testTag("address-kit-check"),
                ) { Text(stringResource(R.string.email_change_kit_check)) }
            } else {
                KitSave(step, model)
            }
        }
    }
}

// ---- a clearer Emergency Kit (ADR 0157 decision 6, uc.account.renew-emergency-kit) ----

/**
 * *Make a new kit*: the password and the Secret Key (typed, the old kit read or scanned) checked against the floor by the
 * core ([KitRenewal]), then the kit saved as every kit is ([KitSave]); its record written by the core once it is kept.
 */
@Composable
internal fun KitRenewalFlow(m: me.raddatz.veyq.mobile.session.KitRenewal, model: AppModel) {
    val state by m.state.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var kit by remember { mutableStateOf<KitStep?>(null) }
    val intake by m.intake.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { kit?.close() } }
    FlowDialog(stringResource(R.string.kit_renew_title), secure = true, tag = "kit-renew", onClose = model::closeAccountFlow) {
        val enabled = !state.working
        val step = kit
        if (step == null) {
            Body(stringResource(R.string.kit_renew_sub), muted = true)
            SecretField(password, { password = it }, stringResource(R.string.rotation_password), "kit-renew-password", enabled)
            FlowKeyField(m.intake, secretKey, { secretKey = it }, stringResource(R.string.rotation_secret_key), "kit-renew-secret-key", enabled, stringResource(R.string.rotation_secret_key_hint), model)
            Refusal(state.refusal, "kit-renew-refusal")
            Button(
                onClick = {
                    model.renewKit(m, password, secretKey) { made ->
                        kit = made
                        if (made != null) {
                            password = ""
                            secretKey = ""
                        }
                    }
                },
                enabled = enabled && password.isNotEmpty() && (secretKey.isNotBlank() || intake.kit != null),
                modifier = Modifier.testTag("kit-renew-check"),
            ) { Text(stringResource(R.string.kit_renew_check)) }
            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            KitSave(step, model)
        }
    }
}

// ---- the Emergency Kit's save (ADR 0148 decision 5) ----

/**
 * *Save your Emergency Kit* → the system's save dialog (`CreateDocument`, Downloads by default) → the core writes the PDF
 * straight into the document's stream ([KitStep.saveInto]); a write that fails deletes the document it made, so a failed
 * save leaves no file. "I saved it" takes only once a file was written. The kit is never drawn on screen.
 */
@Composable
internal fun KitSave(step: KitStep, model: AppModel) {
    val state by step.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val create = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        saving = true
        scope.launch {
            val ok = saveKit(context.contentResolver, uri, step, model.kitLanguage())
            android.util.Log.i("veyq-kit", "kit saved: $ok")
            saving = false
        }
    }
    FilledTonalButton(
        onClick = { create.launch(state.fileName) }, enabled = !saving,
        modifier = Modifier.testTag("kit-save"),
    ) {
        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.kit_save_title))
    }
    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
    Refusal(state.refusal, "kit-refusal")
    CheckRow(state.confirmed, enabled = state.saved, label = stringResource(R.string.kit_saved_confirm), tag = "kit-confirm") { step.confirm(it) }
}

/**
 * Writes [step]'s kit into the document at [uri] (the save dialog's); the stream is opened inside the core's write, so a
 * document that does not open is the core's failed save too. A failed save deletes the document the dialog made.
 */
internal suspend fun saveKit(resolver: ContentResolver, uri: Uri, step: KitStep, language: String): Boolean = withContext(Dispatchers.IO) {
    val day = java.time.LocalDate.now().toEpochDay()
    val ok = step.saveInto(language, day) { bytes ->
        val out = resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("the document did not open")
        out.use {
            it.write(bytes)
            it.flush()
        }
    }
    if (!ok) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    ok
}

// ---- replacing the keys (uc.account.rotate-keys) ----

@Composable
internal fun RotationFlow(m: KeyRotation, model: AppModel) {
    val state by m.state.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    val intake by m.intake.state.collectAsStateWithLifecycle()
    // a link's share dialog opened from *Links to renew* (inbox #977); its links read anew once it closed
    var renewing by remember { mutableStateOf<me.raddatz.veyq.mobile.navigation.NodeAt?>(null) }
    val scope = rememberCoroutineScope()
    FlowDialog(stringResource(R.string.rotation_title), secure = true, tag = "rotation", onClose = model::closeAccountFlow) {
        when (state.step) {
            KeyRotation.Step.LOADING, KeyRotation.Step.WORKING -> {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.testTag("rotation-working")) }
            }
            KeyRotation.Step.ON_THE_WEB -> {
                Body(stringResource(R.string.rotation_moderator_web))
                Text(say(Msg.ACCOUNT_ON_THE_WEB, state.server), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("rotation-on-the-web"))
            }
            KeyRotation.Step.GATE -> {
                Body(stringResource(R.string.rotation_sub), muted = true)
                Heading(stringResource(R.string.rotation_after_title))
                for (fact in listOf(
                    R.string.rotation_fact_shared_spaces, R.string.rotation_fact_signs_out, R.string.rotation_fact_new_kit,
                    R.string.rotation_fact_password, R.string.rotation_fact_not_repair, R.string.rotation_fact_links, R.string.rotation_store_wiped,
                )) Body("• " + stringResource(fact))
                SecretField(password, { password = it }, stringResource(R.string.rotation_password), "rotation-password")
                FlowKeyField(m.intake, secretKey, { secretKey = it }, stringResource(R.string.rotation_secret_key), "rotation-secret-key", true, stringResource(R.string.rotation_secret_key_hint), model)
                CheckRow(state.newPassword, enabled = true, label = stringResource(R.string.rotation_new_password), tag = "rotation-choose-new-password") { m.setNewPassword(it) }
                if (state.newPassword) {
                    NewPasswordField(m.breach, newPassword, { newPassword = it }, stringResource(R.string.password_change_new), "rotation-new-password", true, m::check, model)
                    SecretField(confirm, { confirm = it }, stringResource(R.string.password_change_confirm), "rotation-confirm-password")
                }
                Refusal(state.refusal, "rotation-refusal")
                Button(
                    onClick = {
                        model.proveRotation(m, password, secretKey, newPassword, confirm)
                        secretKey = ""
                    },
                    enabled = password.isNotEmpty() && (secretKey.isNotBlank() || intake.kit != null) && (!state.newPassword || (newPassword.isNotEmpty() && confirm.isNotEmpty())),
                    modifier = Modifier.testTag("rotation-next"),
                ) { Text(stringResource(R.string.rotation_next)) }
            }
            KeyRotation.Step.KIT -> {
                Heading(stringResource(R.string.rotation_kit_title))
                Body(stringResource(R.string.rotation_kit_sub), muted = true)
                m.kit?.let { KitSave(it, model) }
                Body(stringResource(R.string.rotation_kit_keep_old), muted = true)
                Refusal(state.refusal, "rotation-refusal")
                val ready = m.kit?.state?.collectAsStateWithLifecycle()?.value?.ready == true
                Button(
                    onClick = { asking = true }, enabled = ready,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.testTag("rotation-action"),
                ) { Text(stringResource(R.string.rotation_action)) }
                if (asking) DangerConfirm(
                    stringResource(R.string.rotation_confirm_title), stringResource(R.string.rotation_confirm_body), stringResource(R.string.rotation_action),
                    onConfirm = {
                        asking = false
                        model.runRotation(m)
                    },
                    onDismiss = { asking = false },
                )
            }
            KeyRotation.Step.DONE -> {
                if (state.refusal != null) {
                    Refusal(state.refusal, "rotation-refusal")
                } else {
                    Text(stringResource(R.string.rotation_done_old_kit), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("rotation-done"))
                    Body(stringResource(R.string.rotation_store_wiped), muted = true)
                    Body(stringResource(R.string.rotation_done_spaces), muted = true)
                    for (s in state.scopes) {
                        Row(Modifier.fillMaxWidth().testTag("rotation-scope-${s.scopeId}"), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(rotationScopeName(s), modifier = Modifier.weight(1f))
                            Text(say(s.status), color = if (s.blocked != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        s.blocked?.let { Body(say(it), muted = true) }
                    }
                    // the links made before, which stay open until renewed (the web's decision 13): the person's own
                    if (state.links.isNotEmpty()) Heading(stringResource(R.string.rotation_links_title))
                    for (l in state.links) {
                        Column(Modifier.fillMaxWidth().testTag("rotation-link-${l.shareId}")) {
                            Text(l.node?.name ?: stringResource(R.string.rotation_link_name_unavailable))
                            Body(l.scopeName ?: l.scopeWord?.let { say(it) } ?: stringResource(R.string.rotation_scope_name_unavailable), muted = true)
                            val node = l.node
                            when {
                                node != null -> OutlinedButton(onClick = { renewing = node }, modifier = Modifier.testTag("rotation-renew-${l.shareId}")) {
                                    Text(stringResource(if (l.upload) R.string.rotation_link_open else R.string.rotation_renew))
                                }
                                l.tenant != null -> Body(say(Msg.ROTATION_LINK_ELSEWHERE, l.tenant!!), muted = true)
                            }
                            if (node != null && l.upload) Body(stringResource(R.string.rotation_link_upload), muted = true)
                        }
                    }
                }
                Button(onClick = model::closeAccountFlow) { Text(stringResource(R.string.ok)) }
                renewing?.let { node ->
                    ShareSheet(node, model) {
                        renewing = null
                        scope.launch { m.refreshLinks() }
                    }
                }
            }
        }
    }
}

/** A space of the rotation's status list as core names it: its own name, else the personal space or one not opened here. */
@Composable
private fun rotationScopeName(s: KeyRotation.ScopeStatus): String =
    s.name ?: s.nameWord?.let { say(it) } ?: stringResource(R.string.rotation_scope_name_unavailable)

// ---- erasing the account (uc.account.erase-account) ----

@Composable
internal fun ErasureFlow(m: AccountErasure, model: AppModel) {
    val state by m.state.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    FlowDialog(stringResource(R.string.erasure_title), secure = true, tag = "erasure", onClose = model::closeAccountFlow) {
        when {
            !state.loaded -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.serverOwner -> Text(say(Msg.ERASURE_SERVER_OWNER), modifier = Modifier.testTag("erasure-server-owner"))
            state.onTheWeb -> {
                Body(say(Msg.ERASURE_MODERATOR_WEB))
                Text(say(Msg.ACCOUNT_ON_THE_WEB, state.server), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("erasure-on-the-web"))
            }
            state.erased -> Text(say(Msg.ERASURE_ENDED))
            else -> {
                Body(stringResource(R.string.erasure_sub), muted = true)
                for (fact in listOf(
                    R.string.erasure_fact_goes, R.string.erasure_fact_shared_stays, R.string.erasure_fact_kept,
                    R.string.erasure_fact_backups, R.string.erasure_fact_window,
                )) Body("• " + stringResource(fact))
                val shared = state.tenants.filter { it.members.isNotEmpty() }
                if (shared.isNotEmpty()) {
                    Heading(stringResource(R.string.erasure_choice_title))
                    Body(stringResource(R.string.erasure_choice_sub), muted = true)
                    for (t in shared) LastOwnerChoice(t, enabled = !state.working) { owner -> m.choose(t.tenantId, owner) }
                }
                val sole = state.tenants.filter { it.members.isEmpty() }
                if (sole.isNotEmpty()) {
                    Heading(stringResource(R.string.erasure_sole_title))
                    Body(stringResource(R.string.erasure_sole_sub), muted = true)
                    for (t in sole) Text("• " + t.name, modifier = Modifier.testTag("erasure-sole-${t.tenantId}"))
                }
                SecretField(password, { password = it }, stringResource(R.string.erasure_password), "erasure-password", enabled = !state.working)
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Refusal(state.refusal, "erasure-refusal")
                Button(
                    onClick = { asking = true }, enabled = !state.working && password.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.testTag("erasure-action"),
                ) { Text(stringResource(R.string.erasure_action)) }
                if (asking) DangerConfirm(
                    stringResource(R.string.erasure_confirm_title), stringResource(R.string.erasure_confirm_body), stringResource(R.string.erasure_action),
                    onConfirm = {
                        asking = false
                        model.erase(m, password)
                    },
                    onDismiss = { asking = false },
                )
            }
        }
    }
}

/** One space this account is the last owner of: delete it along, or one of its members as owner — Material's radio rows. */
@Composable
private fun LastOwnerChoice(t: AccountErasure.Tenant, enabled: Boolean, onChoose: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("erasure-choice-${t.tenantId}")) {
        Text(t.name, style = MaterialTheme.typography.titleSmall)
        if (!t.chosen) Text(stringResource(R.string.erasure_choice_placeholder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val options = listOf<Pair<String?, String>>(null to stringResource(R.string.erasure_delete_along)) +
            t.members.map { it.userId to stringResource(R.string.erasure_make_owner, it.name) }
        for ((owner, label) in options) {
            val selected = t.chosen && t.newOwnerId == owner
            Row(
                Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton) { onChoose(owner) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected, null, enabled = enabled)
                Spacer(Modifier.size(12.dp))
                Text(label)
            }
        }
    }
}
