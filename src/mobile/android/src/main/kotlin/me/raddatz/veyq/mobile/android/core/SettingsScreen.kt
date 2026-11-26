package me.raddatz.veyq.mobile.android.core

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.session.AccountSettings
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange

/**
 * What the account's Settings page asks of whoever holds its model (`uc.account.sessions`; ADR 0161 decision 2): each a call of core's
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

    /** "Storage on this device" (ADR 0140 decision 10): the limit typed as [amount] in [unit]; core refuses what is no size. */
    fun setOfflineLimit(amount: String, unit: me.raddatz.veyq.mobile.offline.LimitUnit) {}
    fun turnOfflineLimitOff() {}
    fun clearOfflineCache() {}
    fun unpinOffline(node: String) {}

    /** *Sync in the background* turned on or off for this account on this device (ADR 0153 decision 8). */
    fun setBackgroundSync(on: Boolean) {}

    /** "Remove all offline content", asked first (core's `askingRemoveAll`), then [removeAllOffline] or the cancel. */
    fun askRemoveAllOffline() {}
    fun cancelRemoveAllOffline() {}
    fun removeAllOffline() {}

    /** Renames the device of session family [familyId] (the web's `renameDeviceOfFamily`, ADR 0148 decision 1). */
    fun renameFamily(familyId: String, label: String) {}

    /** The account's flows (ADR 0148 decision 10), each its own full-screen dialog over the page. */
    fun openProfile() {}
    fun openPasswordChange() {}
    fun openAddressChange() {}
    fun openKeyRotation() {}
    fun openErasure() {}

    /** The open space's terms, read-only (core's `SettingsSection.TERMS`, uc.account.accept-terms). */
    fun openTerms() {}
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
fun SettingsScreen(
    state: AccountSettings.State, actions: SettingsActions, maps: me.raddatz.veyq.mobile.map.MapServices? = null,
    at: String? = null, onAt: () -> Unit = {},
) {
    val context = LocalContext.current
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // a settings result of the search opens the page at its section (ADR 0146 decision 9) once its first read is in (the
    // sessions above would push the section down after): the anchor is looked for page by page, since a lazy list knows an
    // item's place only once it was laid out; a section the page does not show leaves it where it is
    val read by androidx.compose.runtime.rememberUpdatedState(state.families != null && !state.working)
    LaunchedEffect(at) {
        val id = at ?: return@LaunchedEffect
        val key = sectionKey(id)
        kotlinx.coroutines.withTimeoutOrNull(5_000) { androidx.compose.runtime.snapshotFlow { read }.first { it } }
        var tries = 0
        while (tries++ < 64) {
            val info = list.layoutInfo
            info.visibleItemsInfo.firstOrNull { it.key == key }?.let {
                list.animateScrollToItem(it.index)
                break
            }
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (info.totalItemsCount == 0) {
                kotlinx.coroutines.delay(50)
                continue
            }
            // at the end without it: a section the page's first read adds (add a device) may still come
            if (last >= info.totalItemsCount - 1) kotlinx.coroutines.delay(100) else list.scrollToItem(last.coerceAtLeast(0))
        }
        onAt()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var renamingFamily by remember { mutableStateOf<AccountSettings.Family?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    BackHandler { actions.close() }
    val trustDone: (TrustChange) -> Unit = {
        if (it == TrustChange.FAILED) scope.launch { snackbar.show(context, me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.TRUST_FAILED)) }
    }

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
    renamingFamily?.let { f ->
        RenameDialog(f.label.orEmpty(), onRename = { renamingFamily = null; actions.renameFamily(f.id, it) }, onClose = { renamingFamily = null })
    }
    if (askPassword) PasswordDialog(state.working, actions) { askPassword = false }
    var limiting by remember { mutableStateOf(false) }
    state.storage?.let { storage -> if (limiting) LimitDialog(storage.field, actions) { limiting = false } }
    if (state.askingRemoveAll) AlertDialog(
        onDismissRequest = actions::cancelRemoveAllOffline,
        title = { Text(stringResource(R.string.account_settings_storage_remove_all)) },
        text = { Text(stringResource(R.string.account_settings_storage_remove_all_question)) },
        confirmButton = {
            TextButton(onClick = actions::removeAllOffline, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.account_settings_storage_remove_all_confirm))
            }
        },
        dismissButton = { TextButton(onClick = actions::cancelRemoveAllOffline) { Text(stringResource(R.string.signin_cancel)) } },
    )

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
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("settings"), state = list) {
            state.refusal?.let { why ->
                item(key = "refusal") {
                    Text(
                        say(why), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("settings-refusal"),
                    )
                }
            }
            // the page's sections, every one core offers and in its order (`AccountSettings.State.sections`, ADR 0154
            // decision 2: the web's order, the app-only ones beside their kin, Sign out last); only their look is Android's
            for (section in state.sections(maps = maps != null, appearance = true)) when (section) {
                me.raddatz.veyq.mobile.search.SettingsSection.APPEARANCE -> appearance(context)
                me.raddatz.veyq.mobile.search.SettingsSection.MAPS -> maps?.let { mapServices(it) }
                me.raddatz.veyq.mobile.search.SettingsSection.STORAGE -> storage(state, context, onLimit = { limiting = true }, actions)
                me.raddatz.veyq.mobile.search.SettingsSection.SESSIONS -> sessions(state, context, ask = { confirm = it }, actions, onRename = { renamingFamily = it })
                me.raddatz.veyq.mobile.search.SettingsSection.DEVICE -> thisDevice(state, onRename = { renaming = true }, onStay = { actions.stay(it, trustDone) }, onPassword = { askPassword = true }) {
                    confirm = Confirm(
                        context.getString(R.string.account_settings_untrust_confirm_title), context.getString(R.string.account_settings_untrust_confirm),
                        context.getString(R.string.manage_forget_device), actions::stopTrusting,
                    )
                }
                me.raddatz.veyq.mobile.search.SettingsSection.SAVED_KEYS -> savedKeys(state, context, ask = { confirm = it }, actions)
                me.raddatz.veyq.mobile.search.SettingsSection.ACCOUNT -> account(state)
                me.raddatz.veyq.mobile.search.SettingsSection.EMAIL -> email(state, actions)
                me.raddatz.veyq.mobile.search.SettingsSection.PASSWORD -> password(state, actions)
                me.raddatz.veyq.mobile.search.SettingsSection.ADD_DEVICE -> {
                    val camera = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
                    addDevice(camera, !state.working, actions)
                }
                me.raddatz.veyq.mobile.search.SettingsSection.SECURITY -> security(state, actions)
                me.raddatz.veyq.mobile.search.SettingsSection.PROFILE -> profile(state, actions)
                me.raddatz.veyq.mobile.search.SettingsSection.ERASE -> erase(state, actions)
                me.raddatz.veyq.mobile.search.SettingsSection.TERMS -> terms(state, actions)
                me.raddatz.veyq.mobile.search.SettingsSection.SIGN_OUT -> signOut(state, actions)
            }
        }
    }
}

/** Signing out on this device: the page's last section (core's `SettingsSection.SIGN_OUT`). */
private fun LazyListScope.signOut(state: AccountSettings.State, actions: SettingsActions) {
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

/**
 * The list key a section starts at, by its id (core's `SettingsSection.id`, ADR 0146 decision 9): its header's; the
 * appearance section is its one row, the address's change its row in the account section.
 */
private fun sectionKey(id: String): String = when (id) {
    "appearance" -> "appearance"
    // the address's change is a row of the account section, beside the address (ADR 0148 decision 10)
    "email" -> "email-change"
    else -> "header-$id"
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

/** The address changed beside the address shown (ADR 0148 decision 10); the mailed link confirms it on the web. */
private fun LazyListScope.email(state: AccountSettings.State, actions: SettingsActions) {
    item(key = "email-change") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.email_change_action)) },
            leadingContent = { Icon(Icons.Outlined.Edit, null) },
            colors = listColors(),
            modifier = Modifier.clickable(enabled = !state.working, onClick = actions::openAddressChange).testTag("settings-email-change"),
        )
    }
}

/** *Your profile* (the web's rubric): one's username, name, picture and the group-add consent in this space. */
private fun LazyListScope.profile(state: AccountSettings.State, actions: SettingsActions) {
    header("profile", { stringResource(R.string.profile_title) }, { stringResource(R.string.profile_sub) })
    item(key = "profile") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.profile_title)) },
            leadingContent = { Icon(Icons.Outlined.AccountCircle, null) },
            colors = listColors(),
            modifier = Modifier.clickable(enabled = !state.working, onClick = actions::openProfile).testTag("settings-profile"),
        )
    }
}

/** The open space's terms and the operator section, read-only (uc.account.accept-terms; the web's tenant section). */
private fun LazyListScope.terms(state: AccountSettings.State, actions: SettingsActions) {
    header(me.raddatz.veyq.mobile.search.SettingsSection.TERMS.id, { stringResource(R.string.terms_settings_title) })
    item(key = "terms") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.terms_settings_title)) },
            supportingContent = { Text(stringResource(R.string.terms_readonly_note)) },
            leadingContent = { Icon(Icons.Outlined.Gavel, null) },
            colors = listColors(),
            modifier = Modifier.clickable(enabled = !state.working, onClick = actions::openTerms).testTag("settings-terms"),
        )
    }
}

/** *Change password*: the current password and the Secret Key prove the account, the new one replaces it. */
private fun LazyListScope.password(state: AccountSettings.State, actions: SettingsActions) {
    header("password", { stringResource(R.string.password_change_title) }, { stringResource(R.string.password_change_sub) })
    item(key = "password") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.password_change_title)) },
            leadingContent = { Icon(Icons.Outlined.Password, null) },
            colors = listColors(),
            modifier = Modifier.clickable(enabled = !state.working, onClick = actions::openPasswordChange).testTag("settings-password"),
        )
    }
}

/** *Secure your account*: the keys replaced, a new Emergency Kit saved first (ADR 0148 decision 7). */
private fun LazyListScope.security(state: AccountSettings.State, actions: SettingsActions) {
    item(key = "security-divider") { HorizontalDivider(Modifier.padding(top = 16.dp)) }
    header("security", { stringResource(R.string.rotation_title) }, { stringResource(R.string.rotation_sub) })
    item(key = "security") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.rotation_action)) },
            leadingContent = { Icon(Icons.Outlined.Shield, null) },
            colors = listColors(),
            modifier = Modifier.clickable(enabled = !state.working, onClick = actions::openKeyRotation).testTag("settings-security"),
        )
    }
}

/** *Erase your account*: what goes and what stays, then the password (ADR 0148 decision 8). */
private fun LazyListScope.erase(state: AccountSettings.State, actions: SettingsActions) {
    item(key = "erase-divider") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
    header("erase", { stringResource(R.string.erasure_title) }, { stringResource(R.string.erasure_sub) })
    item(key = "erase") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.erasure_action), color = MaterialTheme.colorScheme.error) },
            leadingContent = { Icon(Icons.Outlined.PersonRemove, null, tint = MaterialTheme.colorScheme.error) },
            colors = listColors(),
            modifier = Modifier.clickable(enabled = !state.working, onClick = actions::openErasure).testTag("settings-erase"),
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
    // how it stays signed in: the changes "Manage servers" offers, the same core state (D2) and the core's pick — a device
    // with no saved unlock (a password change took its saved key, ADR 0148 decision 6) is offered each level and the password trust
    val changes = trust.changes
    run {
        for (change in changes) {
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
                    modifier = Modifier.clickable(enabled = !state.working) { if (change == null) onPassword() else onStay(change) }.testTag("settings-trust-${change ?: "password"}"),
                )
            }
        }
        if (trust.method != null) item(key = "stop-trusting") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.manage_forget_device), color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Outlined.NoEncryption, null, tint = MaterialTheme.colorScheme.error) },
                colors = listColors(),
                modifier = Modifier.clickable(enabled = !state.working, onClick = onStopTrusting).testTag("settings-stop-trusting"),
            )
        }
    }
}

private fun LazyListScope.sessions(state: AccountSettings.State, context: Context, ask: (Confirm) -> Unit, actions: SettingsActions, onRename: (AccountSettings.Family) -> Unit) {
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
        else -> items(families, key = { "family-${it.id}" }) { f -> FamilyRow(f, state.working, context, ask, actions, onRename) }
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
private fun FamilyRow(f: AccountSettings.Family, working: Boolean, context: Context, ask: (Confirm) -> Unit, actions: SettingsActions, onRename: (AccountSettings.Family) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val name = f.label ?: stringResource(if (f.current) R.string.account_settings_this_device else R.string.account_settings_other_device)
    ListItem(
        headlineContent = { Text(name, fontWeight = if (f.current) FontWeight.SemiBold else FontWeight.Normal) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val dates = listOfNotNull(
                    f.lastUsedAt?.let { stringResource(R.string.account_settings_last_used, context.moment(it)) },
                    f.createdAt?.let { stringResource(R.string.account_settings_signed_in, context.moment(it)) },
                )
                if (dates.isNotEmpty()) Text(dates.joined())
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
                    // the device a register names is renamed, every session of it (the web's rename); one it cannot name offers none
                    if (f.label != null) DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_settings_rename)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = {
                            menu = false
                            onRename(f)
                        },
                        modifier = Modifier.testTag("settings-family-rename-${f.id}"),
                    )
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
    header("add-device", { stringResource(R.string.account_settings_add_device) }, { stringResource(R.string.account_settings_add_device_sub) })
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

/**
 * "Storage on this device" (ADR 0140 decision 10), as Android's own Settings shows an app's storage: the limit (a free
 * size, or Off) in a dialog behind its row, what the recently viewed content and the items available offline use and the
 * volume's free space as rows with their sizes, each item available offline with its state and its removal, then
 * "Clear cache" and "Remove all offline content" (asked first). Nothing where the device keeps no store of the account but
 * the reason core gives.
 */
private fun LazyListScope.storage(state: AccountSettings.State, context: Context, onLimit: () -> Unit, actions: SettingsActions) {
    val storage = state.storage
    val unavailable = state.storageUnavailable
    if (storage == null && unavailable == null) return
    item(key = "storage-divider") { HorizontalDivider(Modifier.padding(top = 16.dp)) }
    header(me.raddatz.veyq.mobile.search.SettingsSection.STORAGE.id, { stringResource(R.string.account_settings_storage) })
    if (storage == null) {
        item(key = "storage-unavailable") {
            Text(
                say(unavailable!!), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("settings-storage-unavailable"),
            )
        }
        return
    }
    fun size(bytes: Long) = me.raddatz.veyq.mobile.words.Shown.size(bytes, context.language)
    item(key = "storage-limit") {
        val field = storage.field
        ListItem(
            headlineContent = { Text(stringResource(R.string.account_settings_storage_limit)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (field.off) stringResource(R.string.account_settings_storage_limit_off) else "${field.amount} ${say(field.unit.word)}",
                        modifier = Modifier.testTag("settings-storage-limit-value"),
                    )
                    if (state.refusal == me.raddatz.veyq.mobile.words.Msg.ACCOUNT_SETTINGS_STORAGE_LIMIT_INVALID) {
                        Text(say(state.refusal!!), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            leadingContent = { Icon(Icons.Outlined.Storage, null) },
            colors = listColors(),
            modifier = Modifier.clickable(enabled = !state.working, onClick = onLimit).testTag("settings-storage-limit"),
        )
    }
    item(key = "storage-hint") {
        Text(
            stringResource(R.string.account_settings_storage_limit_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    item(key = "storage-automatic") { SizeRow(stringResource(R.string.account_settings_storage_automatic), size(storage.automatic), Icons.Outlined.History, "settings-storage-automatic") }
    item(key = "storage-pinned") { SizeRow(stringResource(R.string.account_settings_storage_pinned), size(storage.pinned), Icons.Outlined.OfflinePin, "settings-storage-pinned") }
    storage.free?.let { free -> item(key = "storage-free") { SizeRow(stringResource(R.string.account_settings_storage_free), size(free), Icons.Outlined.SdStorage, "settings-storage-free") } }
    backgroundSync(state, actions)
    if (storage.items.isEmpty()) item(key = "storage-items-empty") {
        Text(
            stringResource(R.string.account_settings_storage_items_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    items(storage.items, key = { "pinned-${it.tenant}-${it.node}" }) { item ->
        ListItem(
            headlineContent = { Text(say(item.title)) },
            supportingContent = { Text(listOfNotNull(item.said?.let { say(it) }, size(item.bytes)).joined()) },
            leadingContent = { Icon(kindIcon(item.kind), null) },
            trailingContent = {
                IconButton(onClick = { actions.unpinOffline(item.node) }, enabled = !state.working, modifier = Modifier.testTag("settings-storage-remove-${item.node}")) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.account_settings_storage_remove_item))
                }
            },
            colors = listColors(),
        )
    }
    item(key = "storage-actions") {
        Column(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = actions::clearOfflineCache, enabled = !state.working, modifier = Modifier.testTag("settings-storage-clear-cache")) {
                Text(stringResource(R.string.account_settings_storage_clear_cache))
            }
            TextButton(
                onClick = actions::askRemoveAllOffline,
                enabled = !state.working,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("settings-storage-remove-all"),
            ) { Text(stringResource(R.string.account_settings_storage_remove_all)) }
        }
    }
}

/**
 * *Sync in the background* (ADR 0153 decision 8): the core's switch where the account stays signed in at the login level,
 * as Material 3's switch list item — the whole row the one toggle —; where the core says why it is missing (the presence
 * level, the password), that line instead. Nothing on a device the core gives neither.
 */
private fun LazyListScope.backgroundSync(state: AccountSettings.State, actions: SettingsActions) {
    val on = state.backgroundSync
    if (on != null) item(key = "storage-background") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.account_settings_background_sync)) },
            supportingContent = { Text(stringResource(R.string.account_settings_background_sync_hint)) },
            leadingContent = { Icon(Icons.Outlined.Sync, null) },
            trailingContent = { Switch(on, null, enabled = !state.working) },
            colors = listColors(),
            modifier = Modifier.testTag("settings-background-sync")
                .toggleable(value = on, enabled = !state.working, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = actions::setBackgroundSync),
        )
    }
    state.backgroundSyncMissing?.let { why ->
        item(key = "storage-background-missing") {
            Text(
                say(why), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("settings-background-sync-missing"),
            )
        }
    }
}

/** One size of the storage section: what it is, its size at the end. */
@Composable
private fun SizeRow(label: String, size: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Text(size, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(tag)) },
        colors = listColors(),
    )
}

/**
 * The storage limit as a free size (ADR 0140 decision 8: any size, not a list of steps): its number and its unit as
 * Material's segmented buttons; "Off" turns the automatic tier off. Core reads the number (a point or a comma).
 */
@Composable
private fun LimitDialog(field: me.raddatz.veyq.mobile.offline.LimitField, actions: SettingsActions, onClose: () -> Unit) {
    var amount by remember { mutableStateOf(field.amount) }
    var unit by remember { mutableStateOf(field.unit) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.account_settings_storage_limit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.account_settings_storage_limit_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    amount, { amount = it }, singleLine = true,
                    label = { Text(stringResource(R.string.account_settings_storage_limit)) },
                    suffix = { Text(say(unit.word)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("settings-storage-limit-field"),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val units = me.raddatz.veyq.mobile.offline.LimitUnit.entries
                    units.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = unit == u, onClick = { unit = u },
                            shape = SegmentedButtonDefaults.itemShape(i, units.size),
                            modifier = Modifier.testTag("settings-storage-unit-${u.name}"),
                        ) { Text(say(u.word)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onClose(); actions.setOfflineLimit(amount, unit) }, enabled = amount.isNotBlank(), modifier = Modifier.testTag("settings-storage-limit-set")) {
                Text(stringResource(R.string.account_settings_storage_limit_set))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onClose(); actions.turnOfflineLimitOff() }, modifier = Modifier.testTag("settings-storage-limit-off")) {
                    Text(stringResource(R.string.account_settings_storage_limit_off))
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.signin_cancel)) }
            }
        },
    )
}

/**
 * "Map services" (uc.memory.place A5, A6; ADR 0143 D2): this device's one consent, worded as a setting — which services the
 * server names (the map's host, the place names' host), what they receive, the switch that gives or withdraws the consent
 * for exactly those hosts (off: every map and lookup stops at once), and where an earlier consent named other hosts, which.
 * A server that shows no maps has nothing to consent to; offline the hosts are unknown, so only a consent given can be
 * withdrawn.
 */
private fun LazyListScope.mapServices(maps: me.raddatz.veyq.mobile.map.MapServices) {
    item(key = "maps-divider") { HorizontalDivider(Modifier.padding(top = 16.dp)) }
    header("maps", { say(me.raddatz.veyq.mobile.words.Msg.SETTINGS_MAPS) }, { say(me.raddatz.veyq.mobile.words.Msg.SETTINGS_MAPS_SUB) })
    item(key = "maps") { MapServicesRows(maps) }
}

@Composable
private fun MapServicesRows(maps: me.raddatz.veyq.mobile.map.MapServices) {
    val state by maps.state.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val note: @Composable (String) -> Unit = { Text(it, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
    when (state) {
        me.raddatz.veyq.mobile.map.MapState.NotOffered -> note(say(me.raddatz.veyq.mobile.words.Msg.SETTINGS_MAPS_NOT_OFFERED))
        me.raddatz.veyq.mobile.map.MapState.Offline -> {
            note(say(me.raddatz.veyq.mobile.words.Msg.MAP_NEEDS_CONNECTION))
            val given = maps.consentGivenFor != null
            MapsSwitch(checked = given, enabled = given) { if (!it) maps.turnOff() }
        }
        else -> Column {
            val none = say(me.raddatz.veyq.mobile.words.Msg.SETTINGS_MAPS_NONE)
            ListItem(
                overlineContent = { Text(say(me.raddatz.veyq.mobile.words.Msg.SETTINGS_MAPS_TILES)) },
                headlineContent = { Text(maps.currentHosts.tiles ?: none) }, colors = listColors(),
            )
            ListItem(
                overlineContent = { Text(say(me.raddatz.veyq.mobile.words.Msg.SETTINGS_MAPS_PLACES)) },
                headlineContent = { Text(maps.currentHosts.places ?: none) }, colors = listColors(),
            )
            note(say(me.raddatz.veyq.mobile.words.Msg.SETTINGS_MAPS_RECEIVE))
            MapsSwitch(checked = state == me.raddatz.veyq.mobile.map.MapState.Shown, enabled = true) { on -> if (on) maps.turnOn() else maps.turnOff() }
            if (maps.renewal != null) {
                val given = maps.consentGivenFor
                if (given == null || (given.tiles == null && given.places == null)) note(say(me.raddatz.veyq.mobile.words.Msg.SETTINGS_MAPS_GIVEN_FOR_UNRECORDED))
                else note(say(me.raddatz.veyq.mobile.words.Msg.SETTINGS_MAPS_GIVEN_FOR, given.tiles ?: none, given.places ?: none))
            }
        }
    }
}

@Composable
private fun MapsSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is the one toggle, as Material 3's switch list item is (#944): the label toggles too, and a screen
    // reader meets one switch named by its headline. The switch only draws the state (`onCheckedChange` null).
    ListItem(
        headlineContent = { Text(say(me.raddatz.veyq.mobile.words.Msg.SETTINGS_MAPS_SWITCH)) },
        supportingContent = { Text(say(me.raddatz.veyq.mobile.words.Msg.SETTINGS_MAPS_SWITCH_HINT)) },
        leadingContent = { Icon(Icons.Outlined.Map, null) },
        trailingContent = { Switch(checked, null, enabled = enabled) },
        colors = listColors(),
        modifier = Modifier.testTag("settings-maps-switch")
            .toggleable(value = checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
    )
}

/** "Appearance and language" (D3): the app follows the system's theme; its language is the system's per-app setting. */
private fun LazyListScope.appearance(context: Context) {
    item(key = "appearance") {
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
