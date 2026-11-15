package me.raddatz.veyq.mobile.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.session.AccountSettings
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange

/**
 * What the account's Settings page asks of whoever holds its model (`uc.account.sessions`; ADR 0136 decision 2): each a call of core's
 * [AccountSettings], so the page decides nothing. [AppModel] holds the real one; a debug build's `DevSettings` plays one.
 */
interface SettingsActions {
    fun close()
    fun rename(label: String)
    fun signOut(familyId: String)
    fun signOutOthers()
    fun signOutAndRemoveKey(familyId: String)
    fun removeSavedKey(deviceId: String)
    fun removeEveryOtherSavedKey()

    /** Staying signed in at [level]; [done] gets the outcome. */
    fun stay(level: StaySignedIn, done: (TrustChange) -> Unit)

    /** Back to the password trust with [password]; [done] gets the outcome (a wrong one changes nothing). */
    fun backToPassword(password: String, done: (TrustChange) -> Unit)
    fun stopTrusting()
    fun signOutHere()

    /** "Add a device"'s "Show a code": this device shows its code, the new device scans (ADR 0059 §1). */
    fun linkShow() {}

    /** "Add a device"'s "Scan a code": the new device shows its sign-in code, this device scans. */
    fun linkScan() {}
}

/** A step the page asks to be confirmed first, as the web's `SessionsPanel` does: what it says and what it does. */
private class Confirm(val title: String, val text: String, val action: String, val run: () -> Unit)

/**
 * The account's Settings (`uc.account.sessions`): the address it signs in with, this device — its name, how it stays
 * signed in, "Stop trusting this device" —, every session of the account and the devices with a saved key, the entry to
 * "Add a device" while the instance links devices, the system's page for the app's language, and signing out. A
 * full-screen destination with Material's lists, as Android's own Settings; every row is core's state, every action a
 * call of [actions]. The page shows no secret (inbox #886): an address, device names and dates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: AccountSettings.State, actions: SettingsActions) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    BackHandler { actions.close() }
    val trustFailed = stringResource(R.string.trust_failed)
    val trustDone: (TrustChange) -> Unit = { if (it == TrustChange.FAILED) scope.launch { snackbar.showSnackbar(trustFailed) } }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = {
                TextButton(onClick = { confirm = null; c.run() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(c.action)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.signin_cancel)) } },
        )
    }
    if (renaming) RenameDialog(state.device.label, onRename = { renaming = false; actions.rename(it) }, onClose = { renaming = false })
    if (askPassword) PasswordDialog(state.working, actions) { askPassword = false }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.drawer_settings), fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = { IconButton(onClick = actions::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                )
                // the page's one sign of work under way; its rows stay readable meanwhile
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("settings-working"))
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("settings")) {
            state.refusal?.let { why ->
                item(key = "refusal") {
                    Text(
                        say(why), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("settings-refusal"),
                    )
                }
            }
            account(state)
            thisDevice(state, onRename = { renaming = true }, onStay = { actions.stay(it, trustDone) }, onPassword = { askPassword = true }) {
                confirm = Confirm(
                    context.getString(R.string.account_settings_untrust_confirm_title), context.getString(R.string.account_settings_untrust_confirm),
                    context.getString(R.string.manage_forget_device), actions::stopTrusting,
                )
            }
            sessions(state, context, ask = { confirm = it }, actions)
            savedKeys(state, context, ask = { confirm = it }, actions)
            if (state.addDevice) {
                val camera = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
                addDevice(camera, !state.working, actions)
            }
            appearance(context)
            item(key = "sign-out") {
                Column(Modifier.padding(16.dp)) {
                    OutlinedButton(
                        onClick = actions::signOutHere,
                        enabled = !state.working,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("settings-sign-out"),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.drawer_sign_out))
                    }
                    Text(
                        stringResource(R.string.account_settings_sign_out_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** A section's title, as Android's Settings sets one: small, in the accent, above its rows. */
private fun LazyListScope.header(key: String, title: @Composable () -> String, sub: (@Composable () -> String)? = null) {
    item(key = "header-$key") {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp)) {
            Text(title(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            sub?.let { Text(it(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

@Composable
private fun listColors() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)

private fun LazyListScope.account(state: AccountSettings.State) {
    header("account", { stringResource(R.string.account_settings_account) })
    item(key = "email") {
        ListItem(
            headlineContent = { Text(state.email) },
            supportingContent = state.emailConfirmed?.let { confirmed ->
                {
                    Text(stringResource(if (confirmed) R.string.account_settings_email_confirmed else R.string.account_settings_email_unconfirmed))
                }
            },
            leadingContent = { Icon(Icons.Outlined.AlternateEmail, null) },
            colors = listColors(),
        )
    }
}

private fun LazyListScope.thisDevice(
    state: AccountSettings.State,
    onRename: () -> Unit,
    onStay: (StaySignedIn) -> Unit,
    onPassword: () -> Unit,
    onStopTrusting: () -> Unit,
) {
    val trust = state.device.trust
    header("device", { stringResource(R.string.account_settings_this_device) })
    item(key = "device") {
        ListItem(
            headlineContent = { Text(state.device.label, fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(stringResource(trust.method?.let { trustedLine(it, trust.level) } ?: R.string.account_settings_device_untrusted))
            },
            leadingContent = { Icon(Icons.Outlined.PhoneAndroid, null) },
            trailingContent = {
                IconButton(onClick = onRename, enabled = !state.working, modifier = Modifier.testTag("settings-rename")) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.account_settings_rename))
                }
            },
            colors = listColors(),
        )
    }
    // how it stays signed in: the changes "Manage servers" offers, the same core state (D2)
    trust.method?.let { method ->
        for (change in trustChanges(method, trust.level, trust.levels)) {
            item(key = "trust-$change") {
                ListItem(
                    headlineContent = {
                        Text(stringResource(when (change) {
                            StaySignedIn.PRESENCE -> R.string.offer_presence
                            StaySignedIn.LOGIN -> R.string.signin_remember
                            null -> R.string.manage_password_trust
                        }))
                    },
                    leadingContent = {
                        Icon(when (change) {
                            StaySignedIn.PRESENCE -> Icons.Outlined.Fingerprint
                            StaySignedIn.LOGIN -> Icons.Outlined.LockOpen
                            null -> Icons.Outlined.Key
                        }, null)
                    },
                    colors = listColors(),
                    modifier = Modifier.clickable(enabled = !state.working) { if (change == null) onPassword() else onStay(change) },
                )
            }
        }
        item(key = "stop-trusting") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.manage_forget_device), color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Outlined.NoEncryption, null, tint = MaterialTheme.colorScheme.error) },
                colors = listColors(),
                modifier = Modifier.clickable(enabled = !state.working, onClick = onStopTrusting).testTag("settings-stop-trusting"),
            )
        }
    }
}

private fun LazyListScope.sessions(state: AccountSettings.State, context: Context, ask: (Confirm) -> Unit, actions: SettingsActions) {
    header("sessions", { stringResource(R.string.account_settings_sessions) }, { stringResource(R.string.account_settings_sessions_sub) })
    state.registerRefusal?.let { why ->
        item(key = "register-refusal") {
            Text(say(why), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
    val families = state.families
    when {
        families == null -> item(key = "sessions-none") {
            if (state.refusal == null) Text(
                stringResource(R.string.account_settings_sessions_loading), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        families.isEmpty() -> item(key = "sessions-empty") {
            Text(stringResource(R.string.account_settings_sessions_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        else -> items(families, key = { "family-${it.id}" }) { f -> FamilyRow(f, state.working, context, ask, actions) }
    }
    if (state.hasOthers) item(key = "sign-out-others") {
        TextButton(
            onClick = {
                ask(Confirm(
                    context.getString(R.string.account_settings_revoke_others), context.getString(R.string.account_settings_revoke_others_confirm),
                    context.getString(R.string.account_settings_revoke_others), actions::signOutOthers,
                ))
            },
            enabled = !state.working,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(horizontal = 8.dp).testTag("settings-sign-out-others"),
        ) { Text(stringResource(R.string.account_settings_revoke_others)) }
    }
}

/** One session: its device's name (or what it is, where the register names none), its dates, its actions in an overflow menu. */
@Composable
private fun FamilyRow(f: AccountSettings.Family, working: Boolean, context: Context, ask: (Confirm) -> Unit, actions: SettingsActions) {
    var menu by remember { mutableStateOf(false) }
    val name = f.label ?: stringResource(if (f.current) R.string.account_settings_this_device else R.string.account_settings_other_device)
    ListItem(
        headlineContent = { Text(name, fontWeight = if (f.current) FontWeight.SemiBold else FontWeight.Normal) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val dates = listOfNotNull(
                    f.lastUsedAt?.let { stringResource(R.string.account_settings_last_used, moment(it)) },
                    f.createdAt?.let { stringResource(R.string.account_settings_signed_in, moment(it)) },
                )
                if (dates.isNotEmpty()) Text(dates.joinToString(" · "))
                // a badge, not a control: the web's "You are here", in the accent's soft container
                if (f.current) Text(
                    stringResource(R.string.account_settings_current), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        },
        leadingContent = { Icon(Icons.Outlined.Devices, null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, enabled = !working, modifier = Modifier.testTag("settings-family-menu-${f.id}")) {
                    Icon(Icons.Outlined.MoreVert, stringResource(if (f.current) R.string.account_settings_revoke_self else R.string.account_settings_revoke))
                }
                DropdownMenu(menu, { menu = false }) {
                    if (f.current) DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_settings_revoke_self)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                        onClick = {
                            menu = false
                            ask(Confirm(
                                context.getString(R.string.account_settings_revoke_self), context.getString(R.string.account_settings_revoke_self_confirm),
                                context.getString(R.string.account_settings_revoke_self),
                            ) { actions.signOut(f.id) })
                        },
                    ) else DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_settings_revoke)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                        onClick = {
                            menu = false
                            ask(Confirm(
                                context.getString(R.string.account_settings_revoke), context.getString(R.string.account_settings_revoke_confirm),
                                context.getString(R.string.account_settings_revoke),
                            ) { actions.signOut(f.id) })
                        },
                    )
                    if (f.removesKey) DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_settings_escalate)) },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                        onClick = {
                            menu = false
                            ask(Confirm(
                                context.getString(R.string.account_settings_escalate), context.getString(R.string.account_settings_escalate_confirm),
                                context.getString(R.string.account_settings_escalate_accept),
                            ) { actions.signOutAndRemoveKey(f.id) })
                        },
                    )
                }
            }
        },
        colors = listColors(),
    )
}

private fun LazyListScope.savedKeys(state: AccountSettings.State, context: Context, ask: (Confirm) -> Unit, actions: SettingsActions) {
    if (state.savedKeyDevices.isEmpty() && !state.otherSavedKeys) return
    header("saved-keys", { stringResource(R.string.account_settings_saved_keys) }, { stringResource(R.string.account_settings_saved_keys_sub) })
    items(state.savedKeyDevices, key = { "saved-${it.deviceId}" }) { d ->
        ListItem(
            headlineContent = { Text(d.label) },
            supportingContent = { Text(stringResource(R.string.account_settings_saved_key_row)) },
            leadingContent = { Icon(Icons.Outlined.Key, null) },
            trailingContent = {
                IconButton(
                    onClick = {
                        ask(Confirm(
                            context.getString(R.string.account_settings_remove_key), context.getString(R.string.account_settings_remove_key_confirm, d.label),
                            context.getString(R.string.account_settings_remove_key),
                        ) { actions.removeSavedKey(d.deviceId) })
                    },
                    enabled = !state.working,
                    modifier = Modifier.testTag("settings-remove-key-${d.deviceId}"),
                ) { Icon(Icons.Outlined.Delete, stringResource(R.string.account_settings_remove_key)) }
            },
            colors = listColors(),
        )
    }
    if (state.otherSavedKeys) item(key = "remove-other-keys") {
        TextButton(
            onClick = {
                ask(Confirm(
                    context.getString(R.string.account_settings_remove_other_keys), context.getString(R.string.account_settings_remove_other_keys_confirm),
                    context.getString(R.string.account_settings_remove_other_keys), actions::removeEveryOtherSavedKey,
                ))
            },
            enabled = !state.working,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(horizontal = 8.dp).testTag("settings-remove-other-keys"),
        ) { Text(stringResource(R.string.account_settings_remove_other_keys)) }
    }
    item(key = "saved-keys-hint") {
        Text(
            stringResource(R.string.account_settings_saved_keys_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * "Add a device" (ADR 0059 §1), offered while the instance links devices: its two halves, so whichever device has a
 * camera scans — "Show a code", the new device scanning; and, where this device has a camera, "Scan a code", the new
 * device showing its sign-in code. Each opens the ceremony's own screen (`DeviceLinkScreen`).
 */
private fun LazyListScope.addDevice(camera: Boolean, enabled: Boolean, actions: SettingsActions) {
    item(key = "add-device-divider") { HorizontalDivider(Modifier.padding(top = 16.dp)) }
    header("add-device-header", { stringResource(R.string.account_settings_add_device) }, { stringResource(R.string.account_settings_add_device_sub) })
    item(key = "add-device") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.link_show)) },
            supportingContent = { Text(stringResource(R.string.link_intro)) },
            leadingContent = { Icon(Icons.Outlined.QrCode2, null) },
            colors = listColors(),
            modifier = Modifier.clickable(enabled = enabled) { actions.linkShow() }.testTag("settings-add-device"),
        )
    }
    if (camera) {
        item(key = "add-device-scan") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.link_scan)) },
                supportingContent = { Text(stringResource(R.string.link_scan_intro)) },
                leadingContent = { Icon(Icons.Outlined.QrCodeScanner, null) },
                colors = listColors(),
                modifier = Modifier.clickable(enabled = enabled) { actions.linkScan() }.testTag("settings-add-device-scan"),
            )
        }
    }
}

/** "Appearance and language" (D3): the app follows the system's theme; its language is the system's per-app setting. */
private fun LazyListScope.appearance(context: Context) {
    item(key = "appearance") {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        ListItem(
            headlineContent = { Text(stringResource(R.string.account_settings_appearance)) },
            supportingContent = { Text(stringResource(R.string.account_settings_appearance_sub)) },
            leadingContent = { Icon(Icons.Outlined.Language, null) },
            colors = listColors(),
            modifier = Modifier.clickable { openAppearanceAndLanguage(context) }.testTag("settings-appearance"),
        )
    }
}

/** The system's page for this app's language (Android 13+, with the app's `locales_config`), else its app info. */
internal fun openAppearanceAndLanguage(context: Context) {
    val uri = Uri.fromParts("package", context.packageName, null)
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, uri)
    else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** A moment the server gave, as the system writes a date and time in the person's language. */
private fun moment(epochMs: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(epochMs))

/** "Rename this device": its own entry in the register only (the single-writer rule); a blank name changes nothing. */
@Composable
private fun RenameDialog(label: String, onRename: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(label) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.account_settings_rename)) },
        text = {
            OutlinedTextField(
                name, { name = it },
                placeholder = { Text(stringResource(R.string.account_settings_rename_placeholder)) },
                supportingText = { Text(stringResource(R.string.account_settings_rename_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings-rename-field"),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("settings-rename-save")) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.signin_cancel)) } },
    )
}

/** "Back to your password": the password, checked by the core before the trust changes (a wrong one says so). */
@Composable
private fun PasswordDialog(working: Boolean, actions: SettingsActions, onClose: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = { Text(stringResource(R.string.manage_password_trust)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.manage_password_trust_hint))
                OutlinedTextField(
                    password, { password = it; wrong = false },
                    label = { Text(stringResource(R.string.signin_password)) }, singleLine = true,
                    isError = wrong,
                    supportingText = if (wrong) ({ Text(stringResource(R.string.manage_wrong_password)) }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { actions.backToPassword(password) { if (it == TrustChange.WRONG_PASSWORD) wrong = true else onClose() } },
                enabled = password.isNotEmpty() && !working,
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !working) { Text(stringResource(R.string.signin_cancel)) } },
    )
}
