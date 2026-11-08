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
    BackHandler { model.closeManage() }
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
                                supportingContent = { Text(if (t.current) stringResource(R.string.manage_current) else t.tenant.domain, color = role(palette.muted)) },
                                trailingContent = {
                                    Switch(
                                        checked = !t.hidden || t.current, enabled = !t.current,
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

@Composable
private fun managedSpaceName(space: me.raddatz.veyq.mobile.navigation.SpaceRef): String = when (space.kind) {
    "PERSONAL" -> stringResource(R.string.space_personal)
    "GROUP" -> stringResource(R.string.space_group)
    else -> space.name
}
