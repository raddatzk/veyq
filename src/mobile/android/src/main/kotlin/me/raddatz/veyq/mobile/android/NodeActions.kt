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
    canDelete: Boolean,
    deleteTitle: String,
    deleteQuestion: String,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    if (pinned != null) {
        IconButton(onClick = onPin) {
            if (pinned) Icon(Icons.Filled.Favorite, stringResource(R.string.node_unfavorite), tint = role(palette.favorite))
            else Icon(Icons.Outlined.FavoriteBorder, stringResource(R.string.node_favorite))
        }
    }
    if (canDelete) {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.node_more)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
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
}
