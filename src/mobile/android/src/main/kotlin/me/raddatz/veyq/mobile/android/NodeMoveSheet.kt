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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.session.NodeMove

/**
 * Core's move browser (the web's `MoveNodeDialog.vue` and `MoveBrowser.vue`, owner decision 2026-10-14): it opens at the
 * node's place, shows one place at a time under a trail up to the space's root ([rootName]), lists the places one may go
 * into or move into — the node itself and what lies below it listed but not to be opened — and offers "Move here" where the
 * place takes it, else says why. [onMoved] gets the place's name and where the node was (for its undo).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NodeMoveSheet(
    ref: me.raddatz.veyq.mobile.navigation.NodeAt, rootName: String, model: AppModel, onMoved: (to: String, from: String?) -> Unit,
    /** The browser as "Copy to…" (the web's `CopyNodesDialog`): copies to the place chosen, answers why not or null once
     *  copied; a copy may go where its original is. Null: the move. */
    copy: (suspend (to: String?) -> String?)? = null,
    /** What the copy leaves behind and out, read before anything is written (the kind's; null: core's line alone). */
    notes: (suspend () -> CopyNotes)? = null,
    onClose: () -> Unit,
) {
    var read by remember { mutableStateOf<CopyNotes?>(null) }
    LaunchedEffect(ref, notes) { read = notes?.invoke() }
    // a copy the tenant has no room for is said before anything is written (the web's `CopyNodesDialog` storage check)
    var noRoom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ref, copy != null) { if (copy != null) noRoom = model.copyNoRoom(ref) }
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf<List<NodeRef>?>(null) }
    var at by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // moving a guest's folder out ends it (the web's `endsIdentity`): asked first, there is no way back
    var confirming by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(ref) {
        val read = model.scopeNodes(ref.scopeId)
        tree = read
        read.firstOrNull { it.nodeId == ref.nodeId }?.let { at = NodeMove.start(read, it) }
    }
    ModalBottomSheet(onDismissRequest = { if (!moving) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(if (copy != null) R.string.copy_title else R.string.move_title, ref.name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(4.dp))
            Text(stringResource(if (copy != null) R.string.copy_hint else R.string.move_hint), style = MaterialTheme.typography.bodyMedium, color = role(palette.muted))
            if (copy != null) {
                // what a copy leaves behind, in the web's words (core's, then the kind's `copyLosses`)
                Text(stringResource(R.string.copy_losses), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                (listOf(stringResource(R.string.copy_loss_always)) + read?.losses.orEmpty()).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
                }
                val left = read?.leftOut.orEmpty()
                if (left.isNotEmpty()) {
                    Text(stringResource(R.string.copy_left_out), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    left.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = role(palette.muted)) }
                }
            }
            val nodes = tree
            val moved = nodes?.firstOrNull { it.nodeId == ref.nodeId }
            if (nodes == null || moved == null) {
                CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                return@Column
            }
            // The trail: the space's root, then each folder down to the place on screen.
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val trail = NodeMove.trail(nodes, at)
                trail.forEachIndexed { i, place ->
                    if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = role(palette.muted))
                    TextButton(onClick = { at = place?.nodeId; problem = null }, enabled = i < trail.size - 1 && !moving) {
                        Text(place?.name ?: rootName, fontWeight = if (i == trail.size - 1) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            HorizontalDivider()
            val places = NodeMove.places(nodes, moved, at, model.placement)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (places.isEmpty()) item {
                    Text(stringResource(R.string.move_empty, ref.name), color = role(palette.muted), modifier = Modifier.padding(vertical = 14.dp))
                }
                items(places, key = { it.node.nodeId }) { place ->
                    Row(
                        Modifier.fillMaxWidth().alpha(if (place.blocked == null) 1f else 0.45f)
                            .clickable(enabled = place.blocked == null && !moving) { at = place.node.nodeId; problem = null }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(kindIcon(place.node.kind), null, tint = role(palette.accent))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(place.node.name)
                            place.blocked?.let {
                                Text(
                                    say(NodeMove.placeWord(it)),
                                    style = MaterialTheme.typography.bodySmall, color = role(palette.muted),
                                )
                            }
                        }
                        if (place.blocked == null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = role(palette.muted))
                    }
                    HorizontalDivider()
                }
            }
            val block = NodeMove.hereBlock(nodes, moved, at, model.placement, copy = copy != null)
            val shown = problem ?: noRoom ?: block?.let { say(NodeMove.hereWord(it, copy != null)) }
            shown?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = role(if (problem != null || noRoom != null) palette.danger else palette.muted), modifier = Modifier.padding(top = 10.dp)) }
            val send = {
                moving = true
                problem = null
                val to = at
                val from = moved.parentId
                scope.launch {
                    val toName = to?.let { t -> nodes.firstOrNull { it.nodeId == t }?.name } ?: rootName
                    if (copy != null) {
                        val why = copy(to)
                        moving = false
                        if (why == null) {
                            onMoved(toName, null)
                            onClose()
                        } else {
                            problem = why
                        }
                        return@launch
                    }
                    val r = model.move(ref, to)
                    moving = false
                    if (r.outcome == NodeMove.Outcome.MOVED) {
                        onMoved(to?.let { t -> nodes.firstOrNull { it.nodeId == t }?.name } ?: rootName, from)
                        onClose()
                    } else {
                        problem = moveProblem(context, r)
                    }
                }
                Unit
                Unit
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = onClose, enabled = !moving) { Text(stringResource(R.string.trash_cancel)) }
                Button(
                    onClick = { if (copy == null && NodeMove.endsIdentity(moved)) confirming = true else send() },
                    enabled = block == null && !moving && noRoom == null,
                ) { Text(stringResource(if (copy != null) R.string.copy_submit else R.string.move_submit)) }
            }
            if (confirming) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirming = false },
                    title = { Text(stringResource(R.string.move_identity_title)) },
                    text = { Text(stringResource(R.string.move_identity_confirm, ref.name)) },
                    confirmButton = { TextButton(onClick = { confirming = false; send() }) { Text(stringResource(R.string.move_identity_accept)) } },
                    dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.trash_cancel)) } },
                )
            }
        }
    }
}

/** Why a move did not go through, in the web's words (`node.move.refused.*`). */
internal fun moveProblem(context: android.content.Context, r: NodeMove.Result): String =
    context.text(me.raddatz.veyq.mobile.words.Notices.moveRefused(r.outcome) ?: me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.MOVE_REFUSED_FAILED))
