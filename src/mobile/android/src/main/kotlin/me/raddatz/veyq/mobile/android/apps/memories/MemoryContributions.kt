package me.raddatz.veyq.mobile.android.apps.memories

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.border
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.CreateAction
import me.raddatz.veyq.mobile.android.core.LocalNodeHero
import me.raddatz.veyq.mobile.android.core.LocalPalette
import me.raddatz.veyq.mobile.android.core.NameDialog
import me.raddatz.veyq.mobile.android.core.moved
import me.raddatz.veyq.mobile.android.core.piece
import me.raddatz.veyq.mobile.android.core.role
import me.raddatz.veyq.mobile.android.core.show
import me.raddatz.veyq.mobile.android.core.text
import me.raddatz.veyq.mobile.memories.Buckets
import me.raddatz.veyq.mobile.memories.Contributions
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaGridLayout

/**
 * A Memory's Contributions view (`uc.memory.buckets`, ADR 0149 decision 6; the web's `MemoryBuckets.vue`), drawn from core's
 * `Contributions`: the uploader picker — every contributor's bucket with the sub-buckets curated below it, each with its
 * count — and the chosen bucket's media in the justified grid; a tile opens the lightbox over that bucket ([onOpen]). Online,
 * what the server takes on the chosen bucket (core's `Contributions.State`): *New sub-bucket* under a primary as the view's
 * creating action, a sub-bucket's options rename and delete it, and a long press moves a medium into another bucket of the
 * same root. What a curation refused is said once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContributionsView(
    gallery: GalleryModel, contributions: Contributions, model: AppModel, snackbar: SnackbarHostState,
    viewSwitch: @Composable () -> Unit, onOpen: (String) -> Unit,
) {
    val state by contributions.state.collectAsStateWithLifecycle()
    val galleryState by gallery.state.collectAsStateWithLifecycle()
    val hero = LocalNodeHero.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val online = galleryState.offline == null
    // the counts and the chosen bucket's media follow the gallery's rows while the view shows (core's `Contributions.follow`,
    // #96/#99: counted from the rows, the list read again only for a bucket it lacks)
    LaunchedEffect(contributions) { withContext(Dispatchers.Default) { contributions.follow() } }
    var moved by remember { mutableStateOf(false) }
    val refused = state.refused
    LaunchedEffect(refused) {
        if (refused == null) return@LaunchedEffect
        contributions.seen()
        snackbar.show(context, me.raddatz.veyq.mobile.words.Notice(if (moved) me.raddatz.veyq.mobile.words.Msg.MEMORY_BUCKETS_MOVE_FAILED else me.raddatz.veyq.mobile.words.Msg.MEMORY_BUCKETS_SAVE_FAILED))
    }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    // a selection of the chosen bucket's media (core's `Contributions.picked`): tap toggles while it is on, its bar offers the shift
    var selecting by remember { mutableStateOf(false) }
    var shifting by remember { mutableStateOf<me.raddatz.veyq.mobile.memories.SelectionShift?>(null) }
    LaunchedEffect(state.selected) { selecting = false }
    val row = state.row
    val curates = row != null && online
    val subBucket = state.renames && online
    CreateAction(stringResource(R.string.memory_buckets_create_bucket), shown = state.createsSub && online, onClick = { naming = true }) { list, bottom ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gridWidth = (maxWidth - GRID_MARGIN * 2).value.toDouble()
            val media = state.media
            val layout = remember(media, gridWidth) { GalleryModel.layout(media, gridWidth) }
            val byId = remember(media) { media.associateBy { it.mediaId } }
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottom)) {
                item(key = "banner") { MemoryBannerFrame(gallery, model) }
                item(key = "hero") { hero(16.dp) }
                item(key = "facts") { MemoryFactsLine(gallery, model) }
                item(key = "people") { MemoryPeopleLine(gallery, model) }
                stickyHeader(key = "views") { viewSwitch() }
                when {
                    state.loading && state.rows.isEmpty() -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    state.failed -> item(key = "failed") {
                        Text(stringResource(R.string.memory_gallery_failed), color = role(palette.danger), modifier = Modifier.padding(16.dp))
                    }
                    else -> {
                        item(key = "picker") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                BucketPicker(state, Modifier.weight(1f)) { contributions.pick(it) }
                                if (subBucket) BucketOptions(onRename = { renaming = true }, onDelete = { deleting = true })
                                if (!selecting && curates && media.size >= 2) {
                                    TextButton(onClick = { selecting = true }, modifier = Modifier.testTag("contributions-select")) { Text(stringResource(R.string.select_action)) }
                                }
                            }
                        }
                        if (selecting) item(key = "selection") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("contributions-selection"), verticalAlignment = Alignment.CenterVertically) {
                                Text(context.text(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.SELECT_COUNT, state.picked.size)), Modifier.weight(1f))
                                TextButton(enabled = state.shifts, onClick = { shifting = contributions.shift() }, modifier = Modifier.testTag("contributions-shift")) {
                                    Text(stringResource(R.string.memory_shift_action))
                                }
                                TextButton(onClick = { selecting = false; contributions.clearPicked() }) { Text(stringResource(R.string.trash_cancel)) }
                            }
                        }
                        if (media.isEmpty()) item(key = "empty") {
                            Text(stringResource(R.string.memory_buckets_empty_bucket), color = role(palette.muted), modifier = Modifier.padding(16.dp))
                        }
                        val last = layout.rows.lastIndex
                        items(layout.rows.size, key = { "row:" + layout.rows[it].key }) { i ->
                            val r = layout.rows[i]
                            Box(
                                Modifier.padding(horizontal = GRID_MARGIN).padding(bottom = if (r.index < last) MediaGridLayout.GAP.dp else 0.dp)
                                    .fillMaxWidth().height(r.height.dp),
                            ) {
                                for (t in r.tiles) {
                                    val at = byId[t.id] ?: continue
                                    val move = if (curates && !selecting) stringResource(R.string.memory_buckets_move_media) to { moving = at.mediaId } else null
                                    val picked = at.mediaId in state.picked
                                    Box(Modifier.offset(x = t.left.dp).size(t.width.dp, t.height.dp)) {
                                        MediaTile(
                                            at, galleryState.marks[at.mediaId], gallery, model,
                                            Modifier.fillMaxSize().then(if (picked) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier), longPress = move,
                                        ) { if (selecting) contributions.toggle(at.mediaId) else onOpen(at.mediaId) }
                                        if (picked) Icon(
                                            Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (naming) NameDialog(stringResource(R.string.memory_buckets_create_bucket), onClose = { naming = false }) { name ->
        moved = false
        // a refusal is said by the snackbar (core's `refused`); the dialog closes either way
        withContext(Dispatchers.Default) { contributions.create(name) }
        null
    }
    if (renaming && row != null) RenameBucketDialog(piece(row.label), onClose = { renaming = false }) { name ->
        moved = false
        model.memories.curate { contributions.rename(name) }
    }
    if (deleting && row != null) DeleteBucketDialog(piece(row.label), onClose = { deleting = false }) {
        moved = false
        model.memories.curate { contributions.delete() }
    }
    shifting?.let { shift -> CaptureTimeShiftDialog(shift) { shifting = null } }
    moving?.let { id ->
        MoveDialog(state.moveTargets, onClose = { moving = null }) { target ->
            moved = true
            model.memories.curate { contributions.move(listOf(id), target) }
        }
    }
}

/** The uploader picker (Material's exposed dropdown): the chosen bucket and its count; the menu every bucket, a sub-bucket indented under its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BucketPicker(state: Contributions.State, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = state.row
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField(
            chosen?.let { "${piece(it.label)} (${it.mediaCount})" }.orEmpty(), {}, readOnly = true, singleLine = true,
            label = { Text(stringResource(R.string.memory_buckets_title)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(open, { open = false }) {
            for (r in state.rows) {
                DropdownMenuItem(
                    text = { Text("${piece(r.label)} (${r.mediaCount})", Modifier.padding(start = (r.depth * 16).dp)) },
                    onClick = {
                        open = false
                        onPick(r.bucketId)
                    },
                )
            }
        }
    }
}

/** A sub-bucket's options (Material's overflow): rename, delete. */
@Composable
private fun BucketOptions(onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.memory_buckets_options)) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.memory_buckets_rename)) }, onClick = { open = false; onRename() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.memory_buckets_delete), color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

/** A sub-bucket's new name, starting from its current one. */
@Composable
private fun RenameBucketDialog(current: String, onClose: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.memory_buckets_bucket_dialog_rename)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.memory_buckets_name)) }) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && name.trim() != current, onClick = { onRename(name); onClose() }) {
                Text(stringResource(R.string.memory_buckets_rename))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.trash_cancel)) } },
    )
}

/** A sub-bucket's delete, confirmed by its name typed (the web's): its media go back to the main bucket, nothing is deleted. */
@Composable
private fun DeleteBucketDialog(name: String, onClose: () -> Unit, onDelete: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.memory_buckets_delete_bucket_title)) },
        text = {
            Column {
                Text(stringResource(R.string.memory_buckets_delete_bucket_copy, name))
                OutlinedTextField(
                    typed, { typed = it }, singleLine = true, label = { Text(stringResource(R.string.memory_buckets_delete_bucket_name_label)) },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = typed.trim() == name.trim(), onClick = { onDelete(); onClose() }) {
                Text(stringResource(R.string.memory_buckets_delete_bucket_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.trash_cancel)) } },
    )
}

/** A medium moved into another bucket of its root: the targets as radio rows, none said so. */
@Composable
private fun MoveDialog(targets: List<Buckets.Row>, onClose: () -> Unit, onMove: (String) -> Unit) {
    var chosen by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.memory_buckets_move_media)) },
        text = {
            Column {
                if (targets.isEmpty()) Text(stringResource(R.string.memory_buckets_move_empty))
                else Text(stringResource(R.string.memory_buckets_move_target), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
                for (t in targets) {
                    Row(
                        Modifier.fillMaxWidth().selectable(chosen == t.bucketId, role = Role.RadioButton) { chosen = t.bucketId }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(chosen == t.bucketId, onClick = null)
                        Text(piece(t.label), Modifier.padding(start = 12.dp + (t.depth * 16).dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen != null, onClick = { chosen?.let(onMove); onClose() }) { Text(stringResource(R.string.memory_buckets_move_media)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.trash_cancel)) } },
    )
}
