package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A list view with its one creating action — "Add entry", "New pot" — the way Material 3 has it: an extended floating
 * action button at the bottom end that collapses to its icon once the list has scrolled, over a list that keeps room
 * below its last row so the button never covers it. The app's only floating action button: Material 3 gives a screen at
 * most one, for its primary action, and `scripts/validate_mobile_platform_guidelines.py` refuses one built anywhere else.
 *
 * The block owns the list's state and that room and hands both to [content], so a list under the button cannot leave
 * either out. Its label shows only while the list is at its very top (`!canScrollBackward`), not the Compose samples'
 * first-item test, which a short list never leaves. Collapsed, the icon alone names the button for a screen reader.
 * Creating is the web's green role (`--app-primary`).
 *
 * @param shown whether the action is offered at all (a reader of a Pot adds nothing); without it the list keeps no room.
 */
@Composable
fun CreateAction(
    label: String,
    shown: Boolean,
    onClick: () -> Unit,
    /** Where a level creates more than one kind (the web's "New" menu): the button opens this menu, its entries each
     *  closing it with the first argument. Null: the button is the one action. */
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
    content: @Composable (list: LazyListState, bottom: Dp) -> Unit,
) {
    val list = rememberLazyListState()
    val expanded by remember { derivedStateOf { !list.canScrollBackward } }
    val palette = LocalPalette.current
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        content(list, if (shown) CLEARANCE else 0.dp)
        if (shown) Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ExtendedFloatingActionButton(
                onClick = { if (menu != null) open = true else onClick() },
                icon = { Icon(Icons.Outlined.Add, if (expanded) null else label) },
                text = { Text(label) },
                expanded = expanded,
                containerColor = role(palette.primary),
                contentColor = role(palette.onFill),
            )
            if (menu != null) androidx.compose.material3.DropdownMenu(open, { open = false }) { menu { open = false } }
        }
    }
}

/** The room below a list's last row: the button's 56dp, its 16dp margin, and a row's breath. */
private val CLEARANCE = 96.dp
