package me.raddatz.veyq.mobile.android

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.navigation.ObjectRef

/**
 * Core's actions on an open node's header (the web's `coreNodeActions.ts` and `standardNodeActions`, ADR 0110): the heart
 * as a button, first, while the space's pin set is loaded ([pinned] null: no heart); the rest in one "more" menu, the
 * delete last and in the danger role, asked first in its kind's words. Every action is the page's door and absent when
 * its right is ([canDelete]).
 */
@Composable
internal fun NodeHeaderActions(
    ref: ObjectRef,
    pinned: Boolean?,
    canRename: Boolean,
    canDelete: Boolean,
    deleteTitle: String,
    deleteQuestion: String,
    onPin: () -> Unit,
    /** Renames to the name typed; answers why not (the words to show), or null once it is renamed. */
    onRename: suspend (String) -> String?,
    onDelete: () -> Unit,
    /** "Move to…" (menu-only), between the rename and the delete; null: not offered. */
    onMove: (() -> Unit)? = null,
    /** Report (menu-only, before the delete: the web's menu-only group); null: not offered. */
    onReport: (() -> Unit)? = null,
    /** "Download" (the web's ZIP of the node), after the move; null: not offered. */
    onDownload: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    if (pinned != null) {
        IconButton(onClick = onPin) {
            if (pinned) Icon(Icons.Filled.Favorite, stringResource(R.string.node_unfavorite), tint = role(palette.favorite))
            else Icon(Icons.Outlined.FavoriteBorder, stringResource(R.string.node_favorite))
        }
    }
    if (canRename || canDelete || onMove != null || onReport != null || onDownload != null) {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.node_more)) }
        DropdownMenu(menu, { menu = false }) {
            // The rename first, the delete last and in the danger role (`splitRowActions`).
            if (canRename) DropdownMenuItem(
                text = { Text(stringResource(R.string.node_rename)) },
                onClick = {
                    menu = false
                    renaming = true
                },
            )
            if (onMove != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.node_move)) },
                onClick = {
                    menu = false
                    onMove()
                },
            )
            if (onDownload != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.node_download)) },
                onClick = {
                    menu = false
                    onDownload()
                },
            )
            if (onReport != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.report_action)) },
                onClick = {
                    menu = false
                    onReport()
                },
            )
            if (canDelete) DropdownMenuItem(
                text = { Text(deleteTitle, color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menu = false
                    asking = true
                },
            )
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(deleteTitle) },
            text = { Text(deleteQuestion) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    onDelete()
                }) { Text(stringResource(R.string.entry_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.entry_cancel)) } },
        )
    }
    if (renaming) RenameDialog(ref.name, onRename) { renaming = false }
}

/** Core's name dialog for a rename (`FolderNameDialog`): the name as it is, trimmed on save; unchanged or empty writes nothing. */
@Composable
private fun RenameDialog(current: String, onRename: suspend (String) -> String?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text(stringResource(R.string.node_rename)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                name, { name = it; problem = null }, singleLine = true, label = { Text(stringResource(R.string.node_name)) },
                isError = problem != null, supportingText = problem?.let { { Text(it) } }, enabled = !saving,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    saving = true
                    scope.launch {
                        problem = onRename(name)
                        saving = false
                        if (problem == null) onClose()
                    }
                },
                enabled = !saving && name.isNotBlank(),
            ) { Text(stringResource(R.string.entry_save)) }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !saving) { Text(stringResource(R.string.entry_cancel)) } },
    )
}
