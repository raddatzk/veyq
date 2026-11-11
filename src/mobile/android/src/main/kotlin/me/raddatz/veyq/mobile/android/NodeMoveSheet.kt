package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.navigation.MoveSheet
import me.raddatz.veyq.mobile.session.NodeMove

/**
 * Core's move and copy sheet drawn (core's `MoveSheet`: the web's `MoveNodeDialog.vue`, `MoveBrowser.vue` and
 * `CopyNodesDialog`, owner decision 2026-10-14): one place at a time under a trail up to the space's root, the places one
 * may go into or place into — the node itself and what lies below it listed but not to be opened —, "Move here" / "Copy
 * here" where the place takes it, else why. Everything it shows and decides is [sheet]'s; [onDone] once it moved or copied
 * (the sheet closes, the shell says it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NodeMoveSheet(sheet: MoveSheet, onDone: (MoveSheet) -> Unit, onClose: () -> Unit) {
    val state by sheet.state.collectAsStateWithLifecycle()
    LaunchedEffect(sheet) { sheet.load() }
    LaunchedEffect(state.done) {
        if (state.done != null) {
            onDone(sheet)
            onClose()
        }
    }
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val busy = state.busy
    ModalBottomSheet(onDismissRequest = { if (!busy) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(say(sheet.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(4.dp))
            Text(say(sheet.hint), style = MaterialTheme.typography.bodyMedium, color = role(palette.muted))
            if (sheet.copying) {
                // what a copy leaves behind, in the web's words (core's, then the kinds' `copyLosses`)
                Text(stringResource(R.string.copy_losses), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                state.losses.forEach { Text("• ${say(it)}", style = MaterialTheme.typography.bodySmall, color = role(palette.muted)) }
                if (state.leftOut.isNotEmpty()) {
                    Text(stringResource(R.string.copy_left_out), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    state.leftOut.forEach { Text("• ${say(it)}", style = MaterialTheme.typography.bodySmall, color = role(palette.muted)) }
                }
            }
            if (!state.loaded) {
                CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                return@Column
            }
            // The trail: the space's root, then each folder down to the place on screen.
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val trail = state.trail
                trail.forEachIndexed { i, crumb ->
                    if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = role(palette.muted))
                    TextButton(onClick = { sheet.go(crumb.nodeId) }, enabled = i < trail.size - 1 && !busy) {
                        Text(piece(crumb.name), fontWeight = if (i == trail.size - 1) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (state.places.isEmpty()) item {
                    Text(say(sheet.empty), color = role(palette.muted), modifier = Modifier.padding(vertical = 14.dp))
                }
                items(state.places, key = { it.node.nodeId }) { place ->
                    Row(
                        Modifier.fillMaxWidth().alpha(if (place.blocked == null) 1f else 0.45f)
                            .clickable(enabled = place.blocked == null && !busy) { sheet.go(place.node.nodeId) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(kindIcon(place.node.kind), null, tint = role(palette.accent))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(place.node.name)
                            place.blocked?.let {
                                Text(say(NodeMove.placeWord(it)), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
                            }
                        }
                        if (place.blocked == null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = role(palette.muted))
                    }
                    HorizontalDivider()
                }
            }
            val danger = state.problem ?: state.noRoom
            val shown = danger?.let { say(it) } ?: sheet.hereWord(state)?.let { say(it) }
            shown?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = role(if (danger != null) palette.danger else palette.muted), modifier = Modifier.padding(top = 10.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = onClose, enabled = !busy) { Text(stringResource(R.string.trash_cancel)) }
                Button(onClick = { scope.launch { sheet.choose() } }, enabled = state.canChoose) { Text(say(sheet.submit)) }
            }
            if (state.confirming) {
                // moving a guest's folder out ends it (the web's `endsIdentity`): core asks first, there is no way back
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { sheet.cancelConfirm() },
                    title = { Text(stringResource(R.string.move_identity_title)) },
                    text = { Text(say(sheet.confirmQuestion)) },
                    confirmButton = { TextButton(onClick = { scope.launch { sheet.confirm() } }) { Text(stringResource(R.string.move_identity_accept)) } },
                    dismissButton = { TextButton(onClick = { sheet.cancelConfirm() }) { Text(stringResource(R.string.trash_cancel)) } },
                )
            }
        }
    }
}
