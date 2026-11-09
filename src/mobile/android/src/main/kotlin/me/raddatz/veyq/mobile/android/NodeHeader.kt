package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeHeader as Plan
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.NodeTrash

/**
 * What a kind's page hands core's header (the web's `contributeNodeHeader`, its rights function and the page's doors): a
 * subtitle under the name, the application's narrowing of core's rights, its doors (a copy, a download — absent: not
 * offered) and actions of its own. Core builds the rest, the same for every kind (`NodeHeader`).
 */
internal class HeaderContribution(
    /** The line under the node's name (a Pot's currency); null: none. */
    val subtitle: String? = null,
    /** Whether the application lets this node's cover change here (a Pot of a switched-off Expenses: no). */
    val cover: Boolean = true,
    /** The delete's title and question in the kind's words (`deleteTitle`, `deleteQuestion.ts`). */
    val deleteTitle: String,
    val deleteQuestion: String,
    /** The copy door: copies to the folder chosen (null: the space's top), answers why not, or null once copied. */
    val copy: (suspend (to: String?) -> String?)? = null,
    /** The download door: writes the node's ZIP to the stream, true once whole. */
    val download: (suspend (java.io.OutputStream) -> Boolean)? = null,
    /** The application's own actions, merged into core's plan where the kind's order puts them. */
    val actions: List<OwnAction> = emptyList(),
)

/** An action of the application's own on its node's header. */
internal class OwnAction(val id: String, val label: String, val icon: ImageVector, val slot: Plan.Slot = Plan.Slot.ANY, val onClick: () -> Unit)

/**
 * Core's header actions on a node of any kind (the web's `NodeHeader` with `coreNodeActions.ts` and `standardNodeActions`,
 * ADR 0110): core's `NodeHeader.plan` decides what is offered and where, from the node's rights, the kind's order
 * (`AppKinds.headerOrder`) and [contribution]; up to [maxButtons] are icon buttons in the bar (Material 3: a top app bar
 * holds a few actions, the rest in its overflow), the rest one "more" menu whose last group, after a divider, holds the move,
 * the copy, the report and the delete — the delete last, in the danger role, asked first. Every dialog behind an action is
 * core's one: the share sheet, the move and copy browser, the cover sheet, the rename, the report, the delete and its undo.
 */
@Composable
internal fun NodeHeaderActions(
    node: NodeAt, contribution: HeaderContribution, model: AppModel, snackbar: SnackbarHostState, scope: CoroutineScope,
    /** The name of the node's space, where the move and copy browser starts its path. */
    rootName: String,
    maxButtons: Int = 2,
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val reportOffered by model.reportOffered.collectAsStateWithLifecycle()
    val covers by model.covers.collectAsStateWithLifecycle()
    // The heart turns at once (the web's optimistic toggle) and turns back when the write did not land.
    var pinned by remember(node.nodeId) { mutableStateOf(model.isFavorite(node)) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember(node.nodeId) { mutableStateOf<String?>(null) }
    val rights = Plan.Rights(
        favorite = pinned != null,
        share = KindPages.of(node.kind)?.shareable == true && model.linkRules().on,
        rename = true,
        move = true,
        cover = KindPages.of(node.kind)?.takesCover == true && contribution.cover,
        copy = contribution.copy != null,
        download = contribution.download != null,
        report = reportOffered,
        delete = true,
    )
    val plan = Plan.plan(rights, me.raddatz.veyq.mobile.AppKinds.headerOrder(node.kind), maxButtons, contribution.actions.map { Plan.Action(it.id, it.slot) })
    val own = contribution.actions.associateBy { it.id }

    // the download goes where the person picks (the system's "create document"), streamed there as it is written
    val downloadFailed = stringResource(R.string.download_failed, node.name)
    val downloaded = stringResource(R.string.download_done, node.name)
    val saveTo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val door = contribution.download
        if (uri == null || door == null) return@rememberLauncherForActivityResult
        scope.launch {
            val out = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
            val ok = out != null && out.use { door(it) }
            // a half archive is no download: the document goes again
            if (!ok) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            snackbar.showSnackbar(if (ok) downloaded else downloadFailed)
        }
    }
    val favoriteFailed = stringResource(R.string.node_favorite_failed)
    fun run(id: String) {
        when (id) {
            Plan.FAVORITE -> {
                pinned = pinned?.not()
                scope.launch {
                    if (!model.toggleFavorite(node)) {
                        pinned = model.isFavorite(node)
                        snackbar.showSnackbar(favoriteFailed)
                    }
                }
            }
            Plan.DOWNLOAD -> saveTo.launch(me.raddatz.veyq.mobile.export.NodeExport.fileName(node.name))
            in own -> own.getValue(id).onClick()
            else -> dialog = id
        }
    }

    for (id in plan.buttons) {
        IconButton(onClick = { run(id) }) {
            when (id) {
                Plan.FAVORITE ->
                    if (pinned == true) Icon(Icons.Filled.Favorite, stringResource(R.string.node_unfavorite), tint = role(palette.favorite))
                    else Icon(Icons.Outlined.FavoriteBorder, stringResource(R.string.node_favorite))
                else -> Icon(iconOf(id, own), labelOf(id, node, contribution, covers.containsKey(node.nodeId), pinned))
            }
        }
    }
    if (plan.hasMenu) {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.node_more)) }
        DropdownMenu(menu, { menu = false }) {
            for (id in plan.menu) DropdownMenuItem(
                text = { Text(labelOf(id, node, contribution, covers.containsKey(node.nodeId), pinned)) },
                onClick = { menu = false; run(id) },
            )
            if (plan.menu.isNotEmpty() && plan.last.isNotEmpty()) HorizontalDivider()
            for (id in plan.last) DropdownMenuItem(
                text = {
                    val label = labelOf(id, node, contribution, covers.containsKey(node.nodeId), pinned)
                    if (id == Plan.DELETE) Text(label, color = MaterialTheme.colorScheme.error) else Text(label)
                },
                onClick = { menu = false; run(id) },
            )
        }
    }

    val close = { dialog = null }
    when (dialog) {
        Plan.SHARE -> ShareSheet(node, model, close)
        Plan.REPORT -> ReportSheet(node, model, close)
        Plan.COVER -> CoverSheet(node, model, close)
        Plan.RENAME -> RenameDialog(node.name, onRename = { name ->
            val r = model.rename(node, name)
            val problem = renameProblem(context, r)
            // renamed; a link that kept the old name is said (the rename stands)
            if (problem == null && r.links?.failed?.isNotEmpty() == true) scope.launch { snackbar.showSnackbar(context.getString(R.string.share_name_reseal_failed)) }
            problem
        }, onClose = close)
        Plan.MOVE -> NodeMoveSheet(node, rootName, model, onMoved = { to, from ->
            scope.launch {
                // the web's undo: the node moved back the same way, to where it was
                val answer = snackbar.showSnackbar(context.getString(R.string.move_moved, node.name, to), actionLabel = context.getString(R.string.trash_undo), duration = SnackbarDuration.Long)
                if (answer == SnackbarResult.ActionPerformed && model.move(node, from).outcome != NodeMove.Outcome.MOVED) {
                    snackbar.showSnackbar(context.getString(R.string.move_undo_failed, node.name))
                }
            }
        }, onClose = close)
        Plan.COPY -> NodeMoveSheet(node, rootName, model, onMoved = { to, _ ->
            scope.launch { snackbar.showSnackbar(context.getString(R.string.copy_done, node.name, to)) }
        }, copy = contribution.copy, onClose = close)
        Plan.DELETE -> AlertDialog(
            onDismissRequest = close,
            title = { Text(contribution.deleteTitle) },
            text = { Text(contribution.deleteQuestion) },
            confirmButton = {
                TextButton(onClick = { close(); trash(node, model, snackbar, scope, context) }) {
                    Text(stringResource(R.string.entry_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.entry_cancel)) } },
        )
    }
}

/** The delete into the trash, with the web's undo for as long as the web offers it (`TRASH_UNDO_MS`), or a refusal. */
private fun trash(node: NodeAt, model: AppModel, snackbar: SnackbarHostState, scope: CoroutineScope, context: android.content.Context) {
    scope.launch {
        val (deleted, before) = model.trash(node)
        if (deleted != NodeTrash.Deleted.TRASHED) {
            snackbar.showSnackbar(
                context.getString(
                    when (deleted) {
                        NodeTrash.Deleted.GONE -> R.string.delete_refused_gone
                        NodeTrash.Deleted.SCOPE_DELETING -> R.string.delete_refused_scope_deleting
                        else -> R.string.delete_refused_failed
                    },
                ),
            )
            return@launch
        }
        val answer = kotlinx.coroutines.withTimeoutOrNull(6_000) {
            snackbar.showSnackbar(context.getString(R.string.trash_moved, node.name), actionLabel = context.getString(R.string.trash_undo), duration = SnackbarDuration.Indefinite)
        }
        if (answer == SnackbarResult.ActionPerformed && (before == null || !model.restore(before))) {
            snackbar.showSnackbar(context.getString(R.string.trash_undo_failed, node.name))
        }
    }
}

private fun iconOf(id: String, own: Map<String, OwnAction>): ImageVector = when (id) {
    Plan.SHARE -> Icons.Outlined.Share
    Plan.DOWNLOAD -> Icons.Outlined.Download
    else -> own[id]?.icon ?: Icons.Outlined.MoreVert
}

@Composable
private fun labelOf(id: String, node: NodeAt, contribution: HeaderContribution, hasCover: Boolean, pinned: Boolean?): String = when (id) {
    Plan.FAVORITE -> stringResource(if (pinned == true) R.string.node_unfavorite else R.string.node_favorite)
    Plan.SHARE -> stringResource(shareWords(node.kind).title)
    Plan.RENAME -> stringResource(R.string.node_rename)
    Plan.MOVE -> stringResource(R.string.node_move)
    Plan.COPY -> stringResource(R.string.copy_action)
    Plan.COVER -> stringResource(if (hasCover) R.string.cover_change else R.string.cover_set)
    Plan.DOWNLOAD -> stringResource(R.string.node_download)
    Plan.REPORT -> stringResource(R.string.report_action)
    Plan.DELETE -> contribution.deleteTitle
    else -> contribution.actions.firstOrNull { it.id == id }?.label ?: id
}

/**
 * Core's head of a node's page (the web's `NodeHeader` leading visual, ADR 0110): what the shell hands the body of the node on
 * screen, which places it first in its scroll so it moves with the page as the web's header does, with the side inset its
 * scroll does not already give. Outside a node's page, nothing.
 */
internal val LocalNodeHero = androidx.compose.runtime.staticCompositionLocalOf<@Composable (inset: androidx.compose.ui.unit.Dp) -> Unit> { {} }

/**
 * A node's cover across the page's width, 16:9, framed as stored (core's covers); where it may be changed ([canSet]), a tap
 * opens core's cover sheet. A node without a cover shows nothing here: its cover is set from the header's menu (a phone
 * keeps the room the web's empty frame would take).
 */
@Composable
internal fun NodeCoverHero(node: NodeAt, model: AppModel, canSet: Boolean, inset: androidx.compose.ui.unit.Dp = 16.dp) {
    val palette = LocalPalette.current
    val key = model.covers.collectAsStateWithLifecycle().value[node.nodeId]?.first ?: return
    var image by remember(node.nodeId, key) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(node.nodeId, key) { image = model.coverHero(node) }
    var editing by remember { mutableStateOf(false) }
    val change = stringResource(R.string.cover_change)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 8.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
            .background(role(palette.surface))
            .then(if (canSet) Modifier.clickable(onClickLabel = change) { editing = true } else Modifier),
    ) {
        image?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
    if (editing) CoverSheet(node, model) { editing = false }
}

/**
 * Core's cover dialog (the web's `CoverDialog` around its 16:9 `ImageCropper`): the one place a node's cover is chosen, framed
 * and removed. Its source is the stored cover, framed anew (only the crop moves: the web's `keep`), or a picture picked here
 * from the system's photo picker (no permission: it hands over the one picture chosen), uploaded with its crop; a drag pans
 * it and a pinch or the slider zooms it (core's `CoverFrame`, the web's math, so a cover framed here reopens as framed on
 * the web and back).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoverSheet(node: NodeAt, model: AppModel, onClose: () -> Unit) {
    val palette = LocalPalette.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val hasCover = model.covers.collectAsStateWithLifecycle().value.containsKey(node.nodeId)
    var image by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var picked by remember { mutableStateOf<android.net.Uri?>(null) }
    var storedCrop by remember { mutableStateOf<me.raddatz.veyq.mobile.session.NodeCover.Crop?>(null) }
    var pose by remember { mutableStateOf<me.raddatz.veyq.mobile.session.CoverFrame.Pose?>(null) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var loading by remember { mutableStateOf(hasCover) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.cover_failed)
    val unreadable = stringResource(R.string.cover_unreadable)
    val zoomLabel = stringResource(R.string.cover_zoom)
    androidx.compose.runtime.LaunchedEffect(node.nodeId) {
        if (hasCover) model.coverSource(node)?.let { (picture, crop) -> image = picture; storedCrop = crop }
        loading = false
    }
    val frame = remember(image, size) {
        val img = image
        if (img == null || size.width == 0) null
        else me.raddatz.veyq.mobile.session.CoverFrame(img.width.toDouble(), img.height.toDouble(), size.width.toDouble(), size.height.toDouble())
    }
    // a source shows framed as stored (or centred, a fresh one) once the frame's size is known
    androidx.compose.runtime.LaunchedEffect(frame) { if (frame != null) pose = storedCrop?.let(frame::pose) ?: frame.centred() }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        problem = null
        scope.launch {
            val picture = model.coverPicture(uri)
            if (picture == null) problem = unreadable
            else {
                picked = uri
                storedCrop = null
                pose = null
                image = picture
            }
        }
    }
    fun done(ok: Boolean) {
        saving = false
        if (ok) onClose() else problem = failed
    }
    ModalBottomSheet(onDismissRequest = { if (!saving) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.cover_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.cover_hint), style = MaterialTheme.typography.bodyMedium, color = role(palette.muted))
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(role(palette.surface))
                    .onSizeChanged { size = it }
                    .pointerInput(frame) {
                        val f = frame ?: return@pointerInput
                        detectTransformGestures { _, pan, zoom, _ ->
                            pose = pose?.let { f.zoom(f.pan(it, pan.x.toDouble(), pan.y.toDouble()), zoom.toDouble()) }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val img = image
                val p = pose
                if (img != null && p != null && frame != null) {
                    androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                        val scale = frame.baseScale * p.zoom
                        drawImage(
                            img,
                            dstOffset = androidx.compose.ui.unit.IntOffset(p.x.toInt(), p.y.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize((img.width * scale).toInt(), (img.height * scale).toInt()),
                        )
                    }
                } else if (loading) {
                    androidx.compose.material3.CircularProgressIndicator()
                } else {
                    Icon(kindIcon(node.kind), null, tint = role(palette.muted))
                }
            }
            val p = pose
            if (p != null && frame != null) {
                androidx.compose.material3.Slider(
                    value = p.zoom.toFloat(),
                    onValueChange = { z -> pose = frame.zoom(p, z / p.zoom) },
                    valueRange = me.raddatz.veyq.mobile.session.CoverFrame.MIN_ZOOM.toFloat()..me.raddatz.veyq.mobile.session.CoverFrame.MAX_ZOOM.toFloat(),
                    enabled = !saving,
                    modifier = Modifier.semantics { contentDescription = zoomLabel },
                )
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !saving, onClick = {
                    pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(stringResource(R.string.cover_choose)) }
                if (hasCover) TextButton(enabled = !saving, onClick = {
                    saving = true
                    problem = null
                    scope.launch { done(model.removeCover(node)) }
                }) { Text(stringResource(R.string.cover_remove), color = MaterialTheme.colorScheme.error) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f))
                TextButton(onClick = onClose, enabled = !saving) { Text(stringResource(R.string.entry_cancel)) }
                Button(enabled = !saving && p != null && frame != null, onClick = {
                    val f = frame ?: return@Button
                    val at = pose ?: return@Button
                    val crop = f.crop(at)
                    saving = true
                    problem = null
                    val uri = picked
                    scope.launch { done(if (uri != null) model.setCover(node, uri, crop) else model.recropCover(node, crop)) }
                }) { Text(stringResource(R.string.cover_save)) }
            }
        }
    }
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
