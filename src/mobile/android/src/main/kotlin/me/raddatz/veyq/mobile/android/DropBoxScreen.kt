package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.depot.DropBoxes
import me.raddatz.veyq.mobile.navigation.ObjectRef

/**
 * A drop box's page for a member (Depot's `DropBoxDetail.vue`): every guest's folder in the order they came, a name more
 * than one gave numbered as the web numbers it, with the files in it — each opens its file's page (download, delete) —
 * the folder's move out (core's move browser, which asks first: the folder becomes an ordinary one) and its delete, asked
 * first, into the trash. Nobody's yet: the web's sentence pointing at its links.
 */
@Composable
internal fun DropBoxScreen(ref: ObjectRef, model: AppModel) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val details by model.rowDetails.collectAsStateWithLifecycle()
    // read again whenever the lists are (the row details move with every refresh)
    var folders by remember { mutableStateOf<List<DropBoxes.GuestFolder>?>(null) }
    LaunchedEffect(ref.nodeId, details) { folders = model.dropBoxFolders(ref.scopeId, ref.nodeId) }
    var deleting by remember { mutableStateOf<DropBoxes.GuestFolder?>(null) }
    var movingOut by remember { mutableStateOf<DropBoxes.GuestFolder?>(null) }
    val screen by model.screen.collectAsStateWithLifecycle()
    val hero = LocalNodeHero.current
    val list = folders
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "hero") { hero(16.dp) }
        when {
            list == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            list.isEmpty() -> item { Text(stringResource(R.string.dropbox_empty), color = role(palette.muted), modifier = Modifier.padding(16.dp)) }
            else -> {
                item { Text(stringResource(R.string.dropbox_hint), style = MaterialTheme.typography.bodySmall, color = role(palette.muted), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                for (f in list) {
                    item(key = "folder:" + f.node.id) {
                        var menu by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.label, fontWeight = FontWeight.Bold)
                                Text(pluralStringResource(R.plurals.dropbox_guest_files, f.files.size, f.files.size), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.node_more)) }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.dropbox_move_folder, f.label)) },
                                        onClick = { menu = false; movingOut = f },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.dropbox_delete_folder, f.label), color = MaterialTheme.colorScheme.error) },
                                        onClick = { menu = false; deleting = f },
                                    )
                                }
                            }
                        }
                    }
                    items(f.files, key = { "file:" + it.id }) { file ->
                        ListItem(
                            headlineContent = { Text(file.name) },
                            supportingContent = details[file.id]?.takeIf { it.isNotEmpty() }?.let { { Text(it.joinToString(" · "), color = role(palette.muted)) } },
                            leadingContent = { Icon(kindIcon(file.kind), null, tint = role(palette.muted)) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                            modifier = Modifier.clickableRow { file.asObject()?.let(model::open) },
                        )
                    }
                    item(key = "divider:" + f.node.id) { HorizontalDivider(color = role(palette.line)) }
                }
            }
        }
    }
    movingOut?.let { f ->
        val spaces = (screen as? Screen.Signed)?.spaces.orEmpty()
        NodeMoveSheet(
            me.raddatz.veyq.mobile.navigation.NodeAt(f.node.scopeId, f.node.id, f.node.kind, f.label),
            spaceName(spaces.firstOrNull { it.scopeId == f.node.scopeId }), model,
            onMoved = { _, _ -> folders = null },
            onClose = { movingOut = null },
        )
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.dropbox_delete_folder, f.label)) },
            text = { Text(stringResource(R.string.dropbox_delete_folder_confirm, f.label)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { model.trash(me.raddatz.veyq.mobile.navigation.NodeAt(f.node.scopeId, f.node.id, f.node.kind, f.label)) }
                }) { Text(stringResource(R.string.entry_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.entry_cancel)) } },
        )
    }
}
