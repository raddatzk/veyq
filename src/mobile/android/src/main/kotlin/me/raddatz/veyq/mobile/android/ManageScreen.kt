package me.raddatz.veyq.mobile.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "Manage servers" (owner 2026-11-08): every server the app is signed in to, with its tenants and their spaces — each
 * shown or hidden on this device —, signing out of one server, and adding another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreen(servers: List<ManagedServer>, model: AppModel) {
    val palette = LocalPalette.current
    val trustWorking by model.trustWorking.collectAsStateWithLifecycle()
    // the server whose way back to the password trust asks for the password now
    var askPassword by remember { mutableStateOf<me.raddatz.veyq.mobile.session.VeyqAccount?>(null) }
    BackHandler { model.closeManage() }
    askPassword?.let { account -> PasswordTrustDialog(account, model) { askPassword = null } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage_title), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = model::closeManage) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for (server in servers) {
                item(key = server.account.key) {
                    Column(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(role(palette.surface)).padding(vertical = 8.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Dns, null, tint = role(palette.accent))
                            Spacer(Modifier.size(10.dp))
                            Text(server.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        }
                        for (t in server.tenants) {
                            ListItem(
                                headlineContent = { Text(t.tenant.name, fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text(piece(t.subtitle), color = role(palette.muted)) },
                                trailingContent = {
                                    Switch(
                                        checked = t.shown, enabled = t.switchable,
                                        onCheckedChange = { model.setTenantHidden(server.account, t.tenant, !it) },
                                        colors = SwitchDefaults.colors(checkedTrackColor = role(palette.accent)),
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = role(palette.surface)),
                            )
                            when (val spaces = t.spaces) {
                                null -> CircularProgressIndicator(Modifier.padding(start = 32.dp, bottom = 8.dp).size(18.dp), strokeWidth = 2.dp)
                                else -> for (s in spaces) {
                                    Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(managedSpaceName(s.space), Modifier.weight(1f), color = role(palette.controlText))
                                        Switch(
                                            checked = !s.hidden, enabled = s.canHide,
                                            onCheckedChange = { model.setSpaceHidden(s.space.scopeId, !it) },
                                            colors = SwitchDefaults.colors(checkedTrackColor = role(palette.accent)),
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = role(palette.line))
                        }
                        OutlinedButton(
                            onClick = { model.signOut(server.account) },
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = role(palette.danger)),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.manage_sign_out))
                        }
                        // How this device trusts the account, the changes to it (ADR 0123 decision 8: the other level, back to the
                        // password), and the way to end it (the next sign-in asks for the Secret Key).
                        server.trusted?.let { method ->
                            Text(
                                stringResource(trustedLine(method, server.level)),
                                style = MaterialTheme.typography.bodySmall, color = role(palette.muted),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            for (change in trustChanges(method, server.level, server.levels)) {
                                androidx.compose.material3.TextButton(
                                    onClick = { if (change == null) askPassword = server.account else model.stayAt(server.account, change) },
                                    enabled = !trustWorking,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                ) {
                                    Text(stringResource(when (change) {
                                        me.raddatz.veyq.mobile.session.StaySignedIn.PRESENCE -> R.string.offer_presence
                                        me.raddatz.veyq.mobile.session.StaySignedIn.LOGIN -> R.string.signin_remember
                                        null -> R.string.manage_password_trust
                                    }))
                                }
                            }
                            androidx.compose.material3.TextButton(
                                onClick = { model.forgetDevice(server.account) },
                                enabled = !trustWorking,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) { Text(stringResource(R.string.manage_forget_device), color = role(palette.danger)) }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = model::addServer,
                    colors = ButtonDefaults.buttonColors(containerColor = role(palette.primary), contentColor = role(palette.onFill)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.switcher_add_server))
                }
                Text(stringResource(R.string.manage_hint), style = MaterialTheme.typography.bodySmall, color = role(palette.muted), modifier = Modifier.padding(vertical = 12.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** "Manage servers"' line for how this device trusts a server, by its method and, staying signed in, its level. */
private fun trustedLine(method: me.raddatz.veyq.mobile.session.TrustedDevice.Method, level: me.raddatz.veyq.mobile.session.StaySignedIn?): Int = when {
    method != me.raddatz.veyq.mobile.session.TrustedDevice.Method.DEVICE_KEY -> R.string.manage_trusted_password
    level == me.raddatz.veyq.mobile.session.StaySignedIn.LOGIN -> R.string.manage_trusted_remembered
    else -> R.string.manage_trusted_presence
}

/**
 * The changes "Manage servers" lists for a server trusted by [method] at [level], the device offering [levels]: each
 * offered level it is not at, then the way back to the password (null) from staying signed in — the desktops' pick
 * (Windows' `TrustWords.Changes`, Linux's `manage::changes`). No rule: the levels are the core's.
 */
internal fun trustChanges(
    method: me.raddatz.veyq.mobile.session.TrustedDevice.Method,
    level: me.raddatz.veyq.mobile.session.StaySignedIn?,
    levels: List<me.raddatz.veyq.mobile.session.StaySignedIn>,
): List<me.raddatz.veyq.mobile.session.StaySignedIn?> {
    val remembered = method == me.raddatz.veyq.mobile.session.TrustedDevice.Method.DEVICE_KEY
    return levels.filter { !(remembered && it == level) } + if (remembered) listOf(null) else emptyList()
}

/**
 * "Back to your password": the password, checked by the core before the trust changes (a wrong one says so and changes
 * nothing). A Material dialog with its field, as Android asks for a password to confirm a change.
 */
@Composable
private fun PasswordTrustDialog(account: me.raddatz.veyq.mobile.session.VeyqAccount, model: AppModel, onClose: () -> Unit) {
    val trustWorking by model.trustWorking.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf(model.takeDevPasswordTrust().orEmpty()) }
    var wrong by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!trustWorking) onClose() },
        title = { Text(stringResource(R.string.manage_password_trust)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.manage_password_trust_hint))
                androidx.compose.material3.OutlinedTextField(
                    password, { password = it; wrong = false },
                    label = { Text(stringResource(R.string.signin_password)) }, singleLine = true,
                    isError = wrong,
                    supportingText = if (wrong) ({ Text(stringResource(R.string.manage_wrong_password)) }) else null,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    model.backToPassword(account, password) { change ->
                        when (change) {
                            me.raddatz.veyq.mobile.session.TrustChange.WRONG_PASSWORD -> wrong = true
                            else -> onClose()
                        }
                    }
                },
                enabled = password.isNotEmpty() && !trustWorking,
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onClose, enabled = !trustWorking) { Text(stringResource(R.string.signin_cancel)) }
        },
    )
}

@Composable
private fun managedSpaceName(space: me.raddatz.veyq.mobile.navigation.SpaceRef): String = spaceName(space)
