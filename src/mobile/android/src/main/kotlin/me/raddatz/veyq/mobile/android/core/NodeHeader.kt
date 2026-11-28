package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.OfflinePin
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.HeaderAction as Plan

/**
 * What a kind's page hands core's header (the web's `contributeNodeHeader` and the page's doors): a subtitle under the name,
 * what the page knows of its object that the kind's doors wait on ([facts]: core's `AppKinds.doors` decides), the doors
 * themselves (a copy, a download; core's rights say whether each is offered) and actions of its own. Core's
 * `NodeHeaderModel` composes the rest, the same for every kind.
 */
internal class HeaderContribution(
    /** The line under the node's name (a Pot's currency); null: none. */
    val subtitle: String? = null,
    /** What the page knows of its object (a Pot's write right, a file's content): the kind's doors follow it in core. */
    val facts: me.raddatz.veyq.mobile.AppKinds.Facts = me.raddatz.veyq.mobile.AppKinds.Facts(),
    /** The download door: writes the node's ZIP to the stream; what it left out, or null when not whole. */
    val download: (suspend (java.io.OutputStream) -> List<me.raddatz.veyq.mobile.export.NodeExport.Skipped>?)? = null,
    /** What the download is saved as (its name and type): the node's ZIP by default, a file its own name and bytes. */
    val downloadName: String? = null,
    val downloadMime: String = "application/zip",
    /** The application's own actions, merged into core's plan where the kind's order puts them. */
    val actions: List<OwnAction> = emptyList(),
)

/** An action of the application's own on its node's header. */
internal class OwnAction(val id: Plan, val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * Core's header actions on a node of any kind (the web's `NodeHeader` with `coreNodeActions.ts` and `standardNodeActions`,
 * ADR 0110): core's `NodeHeaderModel` composes the rights and plans what is offered and where, from the node, the kind's
 * order and [contribution]; up to [maxButtons] are icon buttons in the bar (Material 3: a top app bar holds a few actions,
 * the rest in its overflow), the rest one "more" menu whose last group, after a divider, holds the move, the copy, the
 * report and the delete — the delete last, in the danger role, asked first. Every dialog behind an action is core's one:
 * the share sheet, the move and copy browser, the cover sheet, the rename, the report, the delete and its undo.
 */
@Composable
internal fun NodeHeaderActions(
    node: NodeAt, contribution: HeaderContribution, model: AppModel, snackbar: SnackbarHostState, scope: CoroutineScope,
    maxButtons: Int = 2,
) {
    val palette = LocalPalette.current
    val covers by model.covers.collectAsStateWithLifecycle()
    val header = remember(node) { model.header(node, contribution.facts) } ?: return
    androidx.compose.runtime.SideEffect { header.update(contribution.facts) }
    val state by header.state.collectAsStateWithLifecycle()
    val pinned = state.pinned
    var menu by remember { mutableStateOf(false) }
    val plan = header.plan(maxButtons, contribution.actions.map { it.id }) ?: return
    val own = contribution.actions.associateBy { it.id }
    val hasCover = covers.containsKey(node.nodeId)
    val run = nodeActions(node, header, contribution, model, snackbar, scope)

    for (id in plan.buttons) {
        IconButton(onClick = { run(id) }) {
            if (id == Plan.FAVORITE && pinned == true) Icon(Icons.Filled.Favorite, labelOf(id, header, contribution, hasCover), tint = role(palette.favorite))
            else Icon(iconOf(id, own), labelOf(id, header, contribution, hasCover))
        }
    }
    if (plan.hasMenu) {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.node_more)) }
        DropdownMenu(menu, { menu = false }) {
            ActionMenuItems(plan.menu, plan.last, { labelOf(it, header, contribution, hasCover) }, state.offline) { menu = false; run(it) }
        }
    }
}

/**
 * A node's actions as one menu's items (a header's overflow, a row's long press): [menu], then after a divider [last] —
 * the delete in the danger role; "Available offline" a checkable item with its state ([offline]).
 */
@Composable
private fun ActionMenuItems(
    menu: List<Plan>, last: List<Plan>, label: @Composable (Plan) -> String, offline: me.raddatz.veyq.mobile.offline.OfflineToggle?, onClick: (Plan) -> Unit,
) {
    for (id in menu) if (id == Plan.OFFLINE) {
        OfflineMenuItem(label(id), offline) { onClick(id) }
    } else DropdownMenuItem(text = { Text(label(id)) }, onClick = { onClick(id) })
    if (menu.isNotEmpty() && last.isNotEmpty()) HorizontalDivider()
    for (id in last) DropdownMenuItem(
        text = {
            val text = label(id)
            if (id == Plan.DELETE) Text(text, color = MaterialTheme.colorScheme.error) else Text(text)
        },
        onClick = { onClick(id) },
    )
}

/**
 * Runs a node's actions — every [Plan] entry — and draws what each opens: core's dialogs (the share sheet, the move and copy
 * browser, the cover sheet, the rename, the report, the delete and its undo), the offline pin's question, the download's
 * "save to". The header's buttons and menu and a level row's menu ([LevelRowMenu]) run their entries through it.
 */
@Composable
private fun nodeActions(
    node: NodeAt, header: me.raddatz.veyq.mobile.navigation.NodeHeaderModel, contribution: HeaderContribution, model: AppModel,
    snackbar: SnackbarHostState, scope: CoroutineScope,
): (Plan) -> Unit {
    val context = LocalContext.current
    val state by header.state.collectAsStateWithLifecycle()
    var dialog by remember(node.nodeId) { mutableStateOf<Plan?>(null) }
    val own = contribution.actions.associateBy { it.id }

    // the download goes where the person picks (the system's "create document"), streamed there as it is written
    val saveTo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(contribution.downloadMime),
    ) { uri ->
        val door = contribution.download
        if (uri == null || door == null) return@rememberLauncherForActivityResult
        scope.launch {
            val out = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
            val left = out?.use { door(it) }
            // a half archive is no download: the document goes again
            if (left == null) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            // what it says is core's: downloaded or failed, then what it left out
            val said = listOfNotNull(
                me.raddatz.veyq.mobile.words.Notices.downloaded(node.name, left), me.raddatz.veyq.mobile.words.Notices.downloadLeftOut(left),
            )
            // a download said briefly; one that failed or left something out stays until closed
            val kind = if (said.all { it.kind == me.raddatz.veyq.mobile.words.NoticeKind.SAID }) me.raddatz.veyq.mobile.words.NoticeKind.SAID else me.raddatz.veyq.mobile.words.NoticeKind.HELD
            snackbar.show(said.joinToString(" ") { context.text(it) }, kind)
        }
    }
    val run: (Plan) -> Unit = { id ->
        when (id) {
            // the heart turns at once and back when the write did not land (core's)
            Plan.FAVORITE -> { scope.launch { header.toggleFavorite()?.let { snackbar.show(context, it) } } }
            Plan.DOWNLOAD -> saveTo.launch(contribution.downloadName ?: me.raddatz.veyq.mobile.export.NodeExport.fileName(node.name))
            // on: off at once; off: core reads the size it keeps and asks first (`State.offline.asking`, the dialog below)
            Plan.OFFLINE -> { scope.launch { header.toggleOffline()?.let { snackbar.show(context, it) } } }
            // core's dialogs, drawn below
            Plan.SHARE, Plan.RENAME, Plan.MOVE, Plan.COPY, Plan.COVER, Plan.REPORT, Plan.DELETE -> dialog = id
            // the page's own: what it handed with the entry (`HeaderContribution.actions`)
            Plan.UPLOAD, Plan.MUSIC, Plan.BANNER, Plan.REPLACE, Plan.SEND, Plan.OPEN_OWN_PAGE, Plan.REPORT_SHARED ->
                own[id]?.onClick?.invoke() ?: error("the page handed no ${id.name}")
        }
    }

    // a pin asks the size it keeps before its first byte (core's question); where the pin starts, Android 13+ is asked for
    // its notifications — the fetch runs without them, its progress notification is then not shown
    val notifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {}
    state.offline?.let { offline ->
        val question = offline.question ?: return@let
        AlertDialog(
            onDismissRequest = header::cancelOffline,
            title = { Text(stringResource(R.string.offline_pin_ask_title)) },
            text = { Text(say(question)) },
            confirmButton = {
                TextButton(onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    scope.launch { header.confirmOffline()?.let { snackbar.show(context, it) } }
                }) { Text(stringResource(R.string.offline_pin_confirm)) }
            },
            dismissButton = { TextButton(onClick = header::cancelOffline) { Text(stringResource(R.string.entry_cancel)) } },
        )
    }

    val close = { dialog = null }
    when (dialog) {
        Plan.SHARE -> ShareSheet(node, model, close)
        Plan.REPORT -> ReportSheet(node, model, close)
        Plan.COVER -> CoverSheet(node, model, close)
        Plan.RENAME -> NodeRenameDialog(node.name, onRename = { name ->
            val r = header.rename(name)
            // renamed; a link that kept the old name is said (the rename stands)
            r.notice?.let { scope.launch { snackbar.show(context, it) } }
            r.problem?.let { context.text(it) }
        }, onClose = close)
        Plan.MOVE, Plan.COPY -> remember(node, dialog) { model.moveSheet(node, dialog == Plan.COPY) }?.let { sheet ->
            // what a move or copy says is core's, and a move's undo is offered for core's window (the header CR's D4)
            NodeMoveSheet(sheet, onDone = { done -> moved(done, snackbar, scope, context) }, onClose = close)
        }
        Plan.DELETE -> AlertDialog(
            onDismissRequest = close,
            title = { Text(say(header.deleteTitle)) },
            text = { Text(context.text(header.deleteQuestion())) },
            confirmButton = {
                TextButton(onClick = { close(); trash(header, snackbar, scope, context) }) {
                    Text(stringResource(R.string.entry_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.entry_cancel)) } },
        )
        // no dialog of core's: these run at once ([run])
        null, Plan.FAVORITE, Plan.DOWNLOAD, Plan.OFFLINE, Plan.UPLOAD, Plan.MUSIC, Plan.BANNER, Plan.REPLACE, Plan.SEND, Plan.OPEN_OWN_PAGE,
        Plan.REPORT_SHARED -> {}
    }
    return run
}

/**
 * A level row's menu (core's `ShellModel.rowPlan`, ADR 0154 decision 2; the web's `levelRow.ts`): rename and move first,
 * the rest, then after a divider report and the delete — every entry, in its order, run as the node's header runs it.
 * Android opens it with a long press on the row (Material's gesture for an item's actions), anchored at the row; read when
 * opened, as the rights it holds may have changed since the list was read.
 */
@Composable
internal fun LevelRowMenu(node: NodeAt, model: AppModel, open: Boolean, onClose: () -> Unit) {
    if (!open) return
    val notices = LocalShellNotices.current ?: return
    val header = remember(node) { model.header(node, null) } ?: return
    val state by header.state.collectAsStateWithLifecycle()
    var plan by remember(node) { mutableStateOf<me.raddatz.veyq.mobile.navigation.LevelRow.Plan?>(null) }
    androidx.compose.runtime.LaunchedEffect(node) { plan = model.rowPlan(node) }
    // a kept link's row offers its share's report where the link reads (core's plan): no page is open, so it runs here
    val reportShared = stringResource(R.string.bookmark_report_share)
    val contribution = remember(node) {
        HeaderContribution(
            download = { out -> model.downloadNode(node, out) },
            actions = listOf(OwnAction(Plan.REPORT_SHARED, reportShared, Icons.Outlined.Flag) { model.rowReportShared(node) }),
        )
    }
    val run = nodeActions(node, header, contribution, model, notices.snackbar, notices.scope)
    val p = plan ?: return
    DropdownMenu(!p.isEmpty, onClose) {
        ActionMenuItems(p.menu, p.last, { id -> header.label(id)?.let { say(it) } ?: id.name }, state.offline) { onClose(); run(it) }
    }
}

/**
 * The delete into the trash (core's), its undo shown for as long as core offers it (`TRASH_UNDO_MS`), or a refusal. It runs
 * in the shell's [scope]: the page has left by the time the trash answered.
 */
internal fun trash(header: me.raddatz.veyq.mobile.navigation.NodeHeaderModel, snackbar: SnackbarHostState, scope: CoroutineScope, context: android.content.Context) {
    scope.launch {
        offerUndo(header.trash(), header.current.undo != null, { header.state.first { it.undo == null } }, { header.undoTrash() }, snackbar, context)
    }
}

/** What a move or copy said once [sheet] is done, a move's undo shown for as long as core offers it (the header CR's D4). */
internal fun moved(sheet: me.raddatz.veyq.mobile.navigation.MoveSheet, snackbar: SnackbarHostState, scope: CoroutineScope, context: android.content.Context) {
    val done = sheet.current.done ?: return
    scope.launch {
        offerUndo(done, sheet.current.undo != null, { sheet.state.first { it.undo == null } }, { sheet.undo() }, snackbar, context)
    }
}

/**
 * [said] in the snackbar; while core offers an undo ([offered]), with its Undo until core ends the offer ([ended]: the
 * snackbar goes with it), the undo's failure said after.
 */
internal suspend fun offerUndo(
    said: me.raddatz.veyq.mobile.words.Notice, offered: Boolean, ended: suspend () -> Unit, undo: suspend () -> me.raddatz.veyq.mobile.words.Notice?,
    snackbar: SnackbarHostState, context: android.content.Context,
) {
    if (!offered) {
        snackbar.show(context, said)
        return
    }
    val answer = snackbar.show(context.text(said), me.raddatz.veyq.mobile.words.NoticeKind.UNDO, context.getString(R.string.trash_undo), ended)
    if (answer == SnackbarResult.ActionPerformed) undo()?.let { snackbar.show(context, it) }
}

/**
 * "Available offline" in the header's menu (ADR 0140 decision 10): a checkable item, as Material's menus mark a chosen
 * one, its state's line under it (core's: "Downloading 3 MB of 12 MB", "On this device", "Paused: …") and, while it
 * fetches, how far.
 */
@Composable
private fun OfflineMenuItem(label: String, offline: me.raddatz.veyq.mobile.offline.OfflineToggle?, onClick: () -> Unit) {
    val on = offline?.on == true
    DropdownMenuItem(
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label)
                offline?.said?.let { Text(say(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val done = offline?.done
                val total = offline?.total
                if (offline?.status == me.raddatz.veyq.mobile.offline.OfflineStatus.FETCHING && done != null && total != null && total > 0) {
                    LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        },
        // the check at the end, as Material marks a chosen item; no leading icon, as the menu's other items have none
        trailingIcon = if (on) ({ Icon(Icons.Filled.Check, null) }) else null,
        onClick = onClick,
        // read out as a checkable item, as a switch would be
        modifier = Modifier.semantics { toggleableState = androidx.compose.ui.state.ToggleableState(on) },
    )
}

/** An entry's icon where it is a button: core's own Material symbols, a page's own the one it handed. */
private fun iconOf(id: Plan, own: Map<Plan, OwnAction>): ImageVector = when (id) {
    Plan.FAVORITE -> Icons.Outlined.FavoriteBorder
    Plan.SHARE -> Icons.Outlined.Share
    Plan.DOWNLOAD -> Icons.Outlined.Download
    // menu entries of core's (their slot never makes them a button): their word stands for them
    Plan.RENAME -> Icons.Outlined.Edit
    Plan.MOVE -> Icons.AutoMirrored.Outlined.DriveFileMove
    Plan.COPY -> Icons.Outlined.ContentCopy
    Plan.COVER -> Icons.Outlined.Image
    Plan.OFFLINE -> Icons.Outlined.OfflinePin
    Plan.REPORT -> Icons.Outlined.Flag
    Plan.DELETE -> Icons.Outlined.Delete
    Plan.UPLOAD, Plan.MUSIC, Plan.BANNER, Plan.REPLACE, Plan.SEND, Plan.OPEN_OWN_PAGE, Plan.REPORT_SHARED ->
        own[id]?.icon ?: error("the page handed no ${id.name}")
}

@Composable
private fun labelOf(id: Plan, header: me.raddatz.veyq.mobile.navigation.NodeHeaderModel, contribution: HeaderContribution, hasCover: Boolean): String =
    header.label(id, hasCover)?.let { say(it) } ?: contribution.actions.firstOrNull { it.id == id }?.label ?: id.name

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
    // a picked SVG's drawing (core's `SvgCover`): saved as its pixels, which the core encodes as the cover's WebP
    var drawn by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val window = androidx.compose.ui.platform.LocalView.current.rootView as? android.view.ViewGroup
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
                drawn = null
                storedCrop = null
                pose = null
                image = picture
            }
        }
    }
    // "Choose a file": any image file the system's document picker hands over, an SVG drawn here at the core's size
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        problem = null
        scope.launch {
            val svg = window?.let { model.coverSvg(uri, it) }
            val picture = svg?.asImageBitmap() ?: model.coverPicture(uri)
            if (picture == null) problem = unreadable
            else {
                picked = uri
                drawn = svg
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
                TextButton(enabled = !saving, onClick = { pickFile.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.cover_choose_file)) }
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
                    val svg = drawn
                    scope.launch {
                        done(
                            when {
                                svg != null -> model.setCoverDrawn(node, svg, crop)
                                uri != null -> model.setCover(node, uri, crop)
                                else -> model.recropCover(node, crop)
                            },
                        )
                    }
                }) { Text(stringResource(R.string.cover_save)) }
            }
        }
    }
}

/** Core's name dialog for a rename (`FolderNameDialog`): the name as it is, trimmed on save; unchanged or empty writes nothing. */
@Composable
internal fun NodeRenameDialog(current: String, onRename: suspend (String) -> String?, onClose: () -> Unit) {
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
