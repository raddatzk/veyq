package me.raddatz.veyq.mobile.android.core




import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.sharing.BookmarkCreate
import me.raddatz.veyq.mobile.sharing.GuestAccess
import me.raddatz.veyq.mobile.sharing.GuestPage
import me.raddatz.veyq.mobile.sharing.GuestReport
import me.raddatz.veyq.mobile.sharing.GuestScreen
import me.raddatz.veyq.mobile.sharing.OpenLink
import me.raddatz.veyq.mobile.sharing.ShareGates
import me.raddatz.veyq.mobile.sharing.ShareInstance

/**
 * *Open a link* (ADR 0150 decisions 4 and 8): the received link pasted — Material's text field with a Paste button that
 * reads the clipboard on its tap only, never on its own —, checked as it is typed (core's `OpenLink`), opened as its
 * guest, or refused with its reason and, for a moderator's review, *Open in the browser* (the link as received, handed to
 * the system's browser). A window of its own flagged secure: the pasted link holds its key, and Recents' snapshot keeps
 * no frame of it (`src/mobile/CLAUDE.md` § Secrets on screen).
 */
@Composable
internal fun OpenLinkDialog(field: OpenLink, onOpen: () -> Unit, onCancel: () -> Unit) {
    val state by field.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(android.content.ClipboardManager::class.java) }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        icon = { Icon(Icons.Outlined.Link, null) },
        title = { Text(stringResource(R.string.link_open)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    state.text, field::text, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("open-link-field"),
                    label = { Text(stringResource(R.string.link_paste_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = state.problem != null,
                    supportingText = state.problem?.let { p -> { Text(say(p)) } },
                    trailingIcon = {
                        TextButton(onClick = {
                            // the clipboard read on this tap only (Android 12+ says so with its own notice)
                            val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (text != null) field.text(text)
                        }) { Text(stringResource(R.string.link_paste)) }
                    },
                )
                state.browser?.let { link ->
                    OutlinedButton(onClick = { openInBrowser(context, link) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.OpenInBrowser, null, Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.link_open_in_browser))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpen, enabled = state.canOpen, modifier = Modifier.testTag("open-link-open")) { Text(stringResource(R.string.link_open)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.entry_cancel)) } },
    )
}

/** [link] handed to the system's browser, as a tap on it in a chat would have (ADR 0150 decision 8). */
internal fun openInBrowser(context: android.content.Context, link: String) {
    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))) }
}

/**
 * The link open as its guest, above the sign-in or the shell (ADR 0150 decision 4): its own top app bar — the page's name,
 * the link's host under it, *Back* inside the link —, no drawer and no bar. *Back* at its root asks first (the key lives
 * only in memory: leaving drops the link unless it was kept) and then returns where the person was. Its actions: *Keep*
 * for a signed-in person, the file's download and a READ_WRITE guest's replace, and, on an upload link, *Report* to the share's own operator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GuestLinkScreen(screen: GuestScreen, model: AppModel) {
    val view = screen.view
    val s by screen.state.collectAsStateWithLifecycle()
    val gate by view.gates.state.collectAsStateWithLifecycle()
    val page by view.page.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.left) { if (s.left) model.guests.closed(screen) }
    BackHandler { scope.launch { screen.back() } }
    var keeping by remember(screen) { mutableStateOf(false) }
    var reporting by remember(screen) { mutableStateOf(false) }
    val ready = gate is ShareGates.State.Ready
    Surface(Modifier.fillMaxSize().testTag("guest-screen"), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(((page as? me.raddatz.veyq.mobile.sharing.GuestPage.Level)?.model?.title ?: page?.node?.name)?.takeIf { ready } ?: screen.host, fontWeight = FontWeight.Bold, maxLines = 1)
                            if (ready) Text(screen.host, style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { screen.back() } }, modifier = Modifier.testTag("guest-back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    },
                    actions = {
                        // the page's own actions as its kind's application registered them (a file's download and
                        // replace, a Memory's music: the registry's `guestActions`)
                        page?.let { p -> KindPages.of(p.node.kind)?.guestActions?.invoke(p, screen, model, snackbar) }
                        if (screen.keepOffered) {
                            IconButton(onClick = { keeping = true }, modifier = Modifier.testTag("guest-keep")) { Icon(Icons.Outlined.BookmarkAdd, stringResource(R.string.link_keep)) }
                        }
                        // an upload link's guest sees no node, so it reports the link itself; a viewing guest reports the
                        // page on screen from the views (#1033: the web's `reportNodes`; the backend refuses a LINK item
                        // of a READ or READ_WRITE link) — while the link's instance takes reports
                        val access = view.access
                        val offered by produceState(false, access) { value = access != null && withContext(Dispatchers.IO) { GuestReport.offered(access) } }
                        if (ready && offered) {
                            IconButton(onClick = { reporting = true }, modifier = Modifier.testTag("guest-report")) { Icon(Icons.Outlined.Flag, stringResource(R.string.report_action)) }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            // the music bar while a queue plays, as under the shell (a guest's Memory's music, #1034)
            bottomBar = { MusicBar(model) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                GuestContent(screen, model, snackbar)
            }
        }
    }
    if (s.leaving) AlertDialog(
        onDismissRequest = screen::stay,
        title = { Text(stringResource(R.string.link_leave_title)) },
        text = { Text(stringResource(R.string.link_leave_body)) },
        confirmButton = { TextButton(onClick = screen::leave, modifier = Modifier.testTag("guest-leave")) { Text(stringResource(R.string.link_leave_accept)) } },
        dismissButton = { TextButton(onClick = screen::stay) { Text(stringResource(R.string.entry_cancel)) } },
    )
    // the report to the share's own operator: asked by the link (`&report`, a bookmark's) or by its action
    if (s.reporting || reporting) {
        val sheet = remember(screen, s.reporting, reporting) { screen.reportSheet() }
        if (sheet != null) ReportSheet(sheet) {
            reporting = false
            screen.reportShown()
        }
    }
    if (keeping) KeepFlow(screen, model, snackbar) { keeping = false }
}

/**
 * The link behind its gates (ADR 0150 decision 2; the web's share page): the instance, the password, the terms, the note,
 * each before any content, then the page of the node on screen as its kind's member page with a guest's rights. Drawn on
 * the link's own screen and inside a bookmark's page (in place).
 */
@Composable
internal fun GuestContent(screen: GuestScreen, model: AppModel, snackbar: SnackbarHostState) {
    val view = screen.view
    val gate by view.gates.state.collectAsStateWithLifecycle()
    val page by view.page.collectAsStateWithLifecycle()
    val busy by view.gates.busy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // live while the link shows and the app started: what others change in the link, or the link gone, shows up (core's
    // poll, the web's live refresh; inbox #1017)
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(view) {
        owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            val live = view.startLive()
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                live.stop()
            }
        }
    }
    when (val g = gate) {
        ShareGates.State.Loading -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(stringResource(R.string.share_loading), color = role(LocalPalette.current.muted), modifier = Modifier.padding(top = 12.dp))
        }
        is ShareGates.State.Refused -> GateColumn {
            Text(
                stringResource(
                    when (g.refusal) {
                        ShareInstance.Refusal.UNREACHABLE -> R.string.link_refused_unreachable
                        ShareInstance.Refusal.OTHER_VERSION -> R.string.link_refused_other_version
                        ShareInstance.Refusal.NO_LINKS -> R.string.link_refused_no_links
                    },
                    screen.host,
                ),
                modifier = Modifier.testTag("guest-refused"),
            )
            screen.browserLink?.let { link ->
                OutlinedButton(onClick = { openInBrowser(context, link) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.OpenInBrowser, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.link_open_in_browser))
                }
            }
        }
        is ShareGates.State.Locked -> PasswordGate(g.wrong, busy) { password ->
            scope.launch(Dispatchers.IO) {
                view.gates.submitPassword(password)
                screen.enter()
            }
        }
        is ShareGates.State.Terms -> GateColumn {
            Text(stringResource(R.string.share_terms_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.share_terms_intro), color = role(LocalPalette.current.muted))
            if (g.markdown.isNotBlank()) MarkdownText(g.markdown)
            if (g.operatorSection.isNotBlank()) {
                HorizontalDivider()
                MarkdownText(g.operatorSection)
            }
            if (g.record.isNotBlank()) MarkdownText(g.record)
            PrimaryButton(stringResource(R.string.share_accept_continue), Modifier.testTag("guest-accept-terms")) {
                view.gates.acceptTerms()
                scope.launch(Dispatchers.IO) { screen.enter() }
            }
        }
        is ShareGates.State.Note -> GateColumn {
            Text(stringResource(R.string.share_note_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.share_note_intro), color = role(LocalPalette.current.muted))
            MarkdownText(g.markdown)
            PrimaryButton(stringResource(R.string.share_accept_continue), Modifier.testTag("guest-accept-note")) {
                view.gates.acceptNote()
                scope.launch(Dispatchers.IO) { screen.enter() }
            }
        }
        is ShareGates.State.Unavailable -> GateColumn {
            Text(stringResource(R.string.share_unavailable), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("guest-unavailable"))
            Text(stringResource(R.string.share_unavailable_hint), color = role(LocalPalette.current.muted))
        }
        is ShareGates.State.Ready -> Column(Modifier.fillMaxSize()) {
            if (g.note.isNotBlank()) Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { MarkdownText(g.note) }
            val access = view.access
            // a READ_WRITE link's guest (core's `GuestNaming.offered`): who this device is here as, or the way to name itself
            val said: (me.raddatz.veyq.mobile.words.Notice) -> Unit = { n -> scope.launch { snackbar.show(context, n) } }
            if (access != null && access.naming.offered) GuestLine(access, said) {
                // a Pot's page reads its write right anew: a named guest adds entries (the web's `requestGuest`, then the write)
                guestNamed(view, model)
            }
            // a write offered to a guest not named yet asks its name at the write (core's one naming flow, the web's)
            if (access != null) NamingAsked(access, said) { guestNamed(view, model) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                page?.let { GuestPageBody(it, screen, model, snackbar) } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

/** One node's page behind the gates: the member's page of its kind (share view = member view), a guest's rights. */
@Composable
private fun GuestPageBody(page: GuestPage, screen: GuestScreen, model: AppModel, snackbar: SnackbarHostState) {
    val view = screen.view
    when (page) {
        is GuestPage.Level -> GuestLevelPage(page.model, view, model, snackbar)
        is GuestPage.NotShown -> NotShownText()
        // the kind's page as its application registered it for a guest (the registry's `guest`)
        else -> KindPages.of(page.node.kind)?.guest?.invoke(page, screen, model, snackbar) ?: NotShownText()
    }
}

@Composable
private fun NotShownText() =
    Text(stringResource(R.string.share_not_shown), color = role(LocalPalette.current.muted), modifier = Modifier.padding(16.dp).testTag("guest-not-shown"))

/**
 * A folder of the link (core's level over the guest's tree): its rows, each opened as its kind's page, and — for a
 * READ_WRITE guest outside an identity folder (`GuestLevel.writes`, #1034; the web's `ShareTreePage`) — the level's New:
 * a folder, files or a folder uploaded here. The link is read anew after each write, so what was added is listed.
 */
@Composable
private fun GuestLevelPage(level: me.raddatz.veyq.mobile.sharing.GuestLevel, view: me.raddatz.veyq.mobile.sharing.GuestView, model: AppModel, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var naming by remember(level) { mutableStateOf(false) }
    // the row whose menu is open, and the move sheet shown (a row's or the selection's, #1063)
    var menuOf by remember(level) { mutableStateOf<String?>(null) }
    // not keyed on the level: the link read anew after the move draws a new one while the sheet still says what it did
    var moving by remember { mutableStateOf<me.raddatz.veyq.mobile.navigation.MoveSheet?>(null) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.guestLevelUpload(level, uris)
    }
    // a folder uploaded whole as the guest (owner 2026-11-22): what it says, each line, in the snackbar
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) model.levelUploads?.guestUploadFolder(level, tree) { said -> said.forEach { snackbar.show(context, it) } }
    }
    val rows = level.rows
    // what a guest adds here (core's `GuestLevel.creates`, a plan drawn whole): offered where core's level writes; a guest
    // not named yet is asked its name at the write (core's naming flow)
    val creates = level.creates
    CreateAction(stringResource(R.string.create_new), shown = creates.isNotEmpty(), onClick = {}, menu = { close ->
        creates.forEach { c ->
            androidx.compose.material3.DropdownMenuItem(text = { Text(say(c.word)) }, modifier = Modifier.testTag("guest-create-${c.name.lowercase()}"), onClick = {
                close()
                when (c) {
                    me.raddatz.veyq.mobile.AppKinds.Create.FOLDER -> naming = true
                    me.raddatz.veyq.mobile.AppKinds.Create.UPLOAD_FILES -> pickFiles.launch(arrayOf("*/*"))
                    me.raddatz.veyq.mobile.AppKinds.Create.UPLOAD_FOLDER -> pickFolder.launch(null)
                    // never a guest's (core's `GuestLevel.creates` holds none of these): each would need its member's dialog
                    me.raddatz.veyq.mobile.AppKinds.Create.POT, me.raddatz.veyq.mobile.AppKinds.Create.MEMORY, me.raddatz.veyq.mobile.AppKinds.Create.CHAPTER,
                    me.raddatz.veyq.mobile.AppKinds.Create.BOOKMARK -> error("a guest's level offers no ${c.name}")
                }
            })
        }
    }) { list, bottom ->
        // the level's move (#1063, core's `GuestLevelMoves`): Select where some row moves, each row's menu, the member's sheet
        val move = level.move
        val selection by move.selection.collectAsStateWithLifecycle()
        val picking = selection
        Column(Modifier.fillMaxSize()) {
            if (me.raddatz.veyq.mobile.navigation.LevelTool.SELECT in move.tools) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                // the member's Select tool (core's `LevelTool.SELECT`), pressed while the level picks
                androidx.compose.material3.IconToggleButton(
                    checked = picking != null, onCheckedChange = { if (it) move.selectStart() else move.selectCancel() },
                    modifier = Modifier.testTag("level-select"),
                ) { Icon(Icons.Outlined.CheckBox, say(me.raddatz.veyq.mobile.navigation.LevelSelection.word)) }
            }
            if (rows.isEmpty()) Text(stringResource(R.string.list_empty), color = role(LocalPalette.current.muted), modifier = Modifier.padding(16.dp))
            else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("guest-level"), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottom)) {
                items(rows, key = { it.nodeId }) { r ->
                    val plan = remember(level, r.nodeId) { move.rowPlan(r.nodeId) }
                    Box {
                        ListItem(
                            headlineContent = { Text(r.displayName) },
                            leadingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (picking != null) androidx.compose.material3.Checkbox(picking.has(r.nodeId), onCheckedChange = null)
                                    Icon(kindIcon(r.kind), null, tint = role(LocalPalette.current.accent))
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                            modifier = if (picking != null) {
                                // Material's selection mode: the whole row one toggle
                                Modifier.testTag("select-row").toggleable(value = picking.has(r.nodeId), role = androidx.compose.ui.semantics.Role.Checkbox) { move.selectToggle(r.nodeId) }
                            } else {
                                // the row's menu (core's plan) on a long press, Material's gesture for an item's actions
                                Modifier.clickableRow(stringResource(R.string.node_more), if (plan.isEmpty) null else ({ menuOf = r.nodeId })) {
                                    scope.launch(Dispatchers.IO) { view.open(r.nodeId) }
                                }
                            },
                        )
                        DropdownMenu(menuOf == r.nodeId, { menuOf = null }) {
                            for (id in plan.menu + plan.last) DropdownMenuItem(
                                text = { Text(me.raddatz.veyq.mobile.navigation.NodeHeader.label(id, r.kind, false, hasCover = false)?.let { say(it) } ?: id.name) },
                                onClick = {
                                    menuOf = null
                                    if (id == me.raddatz.veyq.mobile.navigation.HeaderAction.MOVE) moving = move.sheet(r.nodeId)
                                },
                            )
                        }
                    }
                }
            }
            // the member's selection bar, its calls the guest level's: a guest's plan holds a move only
            if (picking != null) SelectionBarBody(picking, move::selectAll, move::selectNone, move::selectCancel) { a ->
                if (a == me.raddatz.veyq.mobile.navigation.SelectionAction.MOVE) moving = move.selectionSheet()
            }
        }
    }
    moving?.let { sheet -> NodeMoveSheet(sheet, onDone = { done -> moved(done, snackbar, scope, context) }, onClose = { moving = null }) }
    if (naming) NameAnswerDialog(
        stringResource(R.string.folder_new), onClose = { naming = false },
        onSaid = { n -> scope.launch { snackbar.show(context, n) } },
    ) { name -> withContext(Dispatchers.IO) { level.createFolder(name) } }
}

/** The guest named itself (or was forgotten): the page on screen follows, as its application registered (the registry's `guestNamed`). */
private fun guestNamed(view: me.raddatz.veyq.mobile.sharing.GuestView, model: AppModel) {
    val page = view.page.value ?: return
    KindPages.of(page.node.kind)?.guestNamed?.invoke(page, model)
}

/**
 * Whose session this device holds on a READ_WRITE link (the web's `GuestNaming`): "You are here as …" with *Not you?*, or
 * the way to name itself, which a guest's first write needs; the record kept of a guest's writes said in both (#1018).
 */
@Composable
private fun GuestLine(access: GuestAccess, said: (me.raddatz.veyq.mobile.words.Notice) -> Unit, onNamed: () -> Unit) {
    val session by access.session.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val named = session
        if (named == null) {
            TextButton(onClick = { naming = true }, modifier = Modifier.testTag("guest-name-ask")) { Text(stringResource(R.string.share_guest_name_title)) }
        } else {
            NotYou(named) { access.forgetGuest(); onNamed() }
        }
        RecordNotice(access.guestWriteRecordDays, access.origin, named?.identityId)
    }
    if (naming) NamingDialog(
        title = stringResource(R.string.share_guest_name_title), hint = stringResource(R.string.share_guest_name_hint),
        days = access.guestWriteRecordDays, origin = access.origin, onClose = { naming = false }, onSaid = said,
    ) { name -> access.naming.name(name).also { if (it.notice == null && !it.keepsOpen) onNamed() } }
}

/**
 * The name a waiting write asks of a guest not named yet (core's `GuestNaming.asking`, the web's `requestGuest`): the
 * naming dialog while it asks; its save answers core's `name`, its cancel `decline` (the write is not made).
 */
@Composable
private fun NamingAsked(access: GuestAccess, said: (me.raddatz.veyq.mobile.words.Notice) -> Unit, onNamed: () -> Unit) {
    val asking by access.naming.asking.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (asking) NamingDialog(
        title = stringResource(R.string.share_guest_name_title), hint = stringResource(R.string.share_guest_name_hint),
        days = access.guestWriteRecordDays, origin = access.origin,
        onClose = { scope.launch { access.naming.decline() } }, onSaid = said,
    ) { name -> access.naming.name(name).also { if (it.notice == null && !it.keepsOpen) onNamed() } }
}

/** "You are here as …" and *Not you?*, which asks first and then forgets this device's session (the web's words). */
@Composable
internal fun NotYou(session: me.raddatz.veyq.mobile.sharing.GuestSession, onForget: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val name = session.displayName
        Text(
            if (name != null) stringResource(R.string.share_guest_as, name) else stringResource(R.string.share_guest_as_unnamed),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false).testTag("guest-as"),
        )
        TextButton(onClick = { asking = true }) { Text(stringResource(R.string.share_guest_not_you)) }
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(stringResource(R.string.share_guest_not_you_title)) },
        text = {
            val name = session.displayName
            Text(if (name != null) stringResource(R.string.share_guest_not_you_confirm, name) else stringResource(R.string.share_guest_not_you_confirm_unnamed))
        },
        confirmButton = { TextButton(onClick = { asking = false; onForget() }) { Text(stringResource(R.string.share_guest_not_you_accept)) } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.entry_cancel)) } },
    )
}

/**
 * The guest-write record's notice (ADR 0093 § 10, GDPR Art. 13; the web's `GuestWriteRecordNotice`): before a guest's
 * first write and beside its session, what the share's instance keeps of each write and for how long ([days], the N its
 * resolve served), the right to object, and its privacy notice's passage, opened in the browser; with a session, the
 * guest id to name in an objection. [days] null (nothing recorded) shows nothing.
 */
@Composable
internal fun RecordNotice(days: Int?, origin: String, guestId: String? = null) {
    if (days == null) return
    val context = LocalContext.current
    val muted = role(LocalPalette.current.muted)
    Column(Modifier.fillMaxWidth().testTag("guest-record-notice"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            pluralStringResource(R.plurals.share_guest_record_kept, days, days) + " " + stringResource(R.string.share_guest_record_objection),
            style = MaterialTheme.typography.bodySmall, color = muted,
        )
        TextButton(onClick = { openInBrowser(context, "$origin/privacy#guest-write-record") }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
            Text(stringResource(R.string.share_guest_record_more), style = MaterialTheme.typography.bodySmall)
        }
        if (guestId != null) {
            SelectionContainer { Text(stringResource(R.string.share_guest_id) + " " + guestId, style = MaterialTheme.typography.bodySmall, color = muted) }
        }
    }
}

/** A guest naming itself (the web's naming dialog): its name, who sees it, the record's notice, *Continue*. */
@Composable
private fun NamingDialog(
    title: String, hint: String, days: Int?, origin: String, onClose: () -> Unit,
    onSaid: (me.raddatz.veyq.mobile.words.Notice) -> Unit, onName: suspend (String) -> me.raddatz.veyq.mobile.navigation.NameAnswer,
) {
    var name by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    // core's answer: a problem keeps the dialog open with it shown, a notice closes it and is said
    var problem by remember { mutableStateOf<me.raddatz.veyq.mobile.words.Notice?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint)
                NameField(name) { name = it; problem = null }
                Text(stringResource(R.string.share_name_visibility), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted))
                RecordNotice(days, origin)
                problem?.let { Text(say(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !working, modifier = Modifier.testTag("guest-name-continue"), onClick = {
                working = true
                scope.launch {
                    val answer = withContext(Dispatchers.IO) { onName(name) }
                    working = false
                    problem = answer.problem
                    answer.notice?.let(onSaid)
                    if (!answer.keepsOpen) onClose()
                }
            }) { Text(stringResource(R.string.share_join)) }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !working) { Text(stringResource(R.string.entry_cancel)) } },
    )
}

@Composable
private fun NameField(name: String, onName: (String) -> Unit) {
    OutlinedTextField(
        name, onName, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("guest-name-field"),
        label = { Text(stringResource(R.string.share_your_name)) },
        placeholder = { Text(stringResource(R.string.share_your_name_placeholder)) },
    )
}

/** *Keep*: the places the link may be kept — the personal space first —, then the bookmark's dialog filled with the link. */
@Composable
private fun KeepFlow(screen: GuestScreen, model: AppModel, snackbar: SnackbarHostState, onClose: () -> Unit) {
    val places by produceState<List<SpaceRef>?>(null, screen) { value = withContext(Dispatchers.IO) { runCatching { screen.keepPlaces() }.getOrDefault(emptyList()) } }
    var chosen by remember { mutableStateOf<SpaceRef?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val space = chosen
    if (space == null) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.link_keep_where)) },
            text = {
                val list = places
                if (list == null) CircularProgressIndicator()
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (p in list) ListItem(
                        headlineContent = { Text(spaceName(p)) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickableRow { chosen = p }.testTag("guest-keep-place"),
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.entry_cancel)) } },
        )
    } else {
        val create = remember(space) { screen.keep(space) }
        if (create == null) {
            onClose()
            return
        }
        val name = spaceName(space)
        BookmarkCreateDialog(create, onClose = onClose) {
            model.bookmarkMade()
            scope.launch { snackbar.show(context, me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.LINK_KEPT, name)) }
        }
    }
}

/**
 * The bookmark's dialog (ADR 0150 decision 6; the web's `BookmarkCreateDialog`): the link checked as it is typed, its
 * password, its name (the host until one is typed), the group space's line that everyone who opens the place opens the
 * link. A window of its own flagged secure: it shows the link, which holds its key.
 */
@Composable
internal fun BookmarkCreateDialog(create: BookmarkCreate, onClose: () -> Unit, onMade: () -> Unit) {
    val state by create.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!state.saving) onClose() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.bookmark_create_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.bookmark_create_intro), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    state.text, create::link, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("bookmark-link"),
                    label = { Text(stringResource(R.string.bookmark_link_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = state.problem != null,
                    supportingText = state.problem?.let { p -> { Text(say(p)) } },
                )
                OutlinedTextField(
                    state.password, create::password, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.bookmark_password_label)) },
                    supportingText = { Text(stringResource(R.string.bookmark_password_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    state.name, create::name, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("bookmark-name"),
                    label = { Text(stringResource(R.string.node_name)) },
                    placeholder = state.host?.let { h -> { Text(h) } },
                )
                if (create.groupSpace) Text(stringResource(R.string.bookmark_who_can_open), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted))
                if (state.failed) Text(stringResource(R.string.bookmark_create_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = state.canSave, modifier = Modifier.testTag("bookmark-save"), onClick = {
                scope.launch {
                    if (withContext(Dispatchers.IO) { create.save() }) {
                        onMade()
                        onClose()
                    }
                }
            }) { Text(stringResource(R.string.bookmark_save)) }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !state.saving) { Text(stringResource(R.string.entry_cancel)) } },
    )
}

/** A gate's column: a form's width, centred, scrolled (the sign-in's measure). */
@Composable
private fun GateColumn(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** A writing page's column (a drop box's, an uploader's): scrolled, at a form's width. */
@Composable
internal fun GuestWriteColumn(content: @Composable () -> Unit) = GateColumn(content)

@Composable
private fun PasswordGate(wrong: Boolean, busy: Boolean, onUnlock: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    GateColumn {
        Text(stringResource(R.string.share_password_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.share_password_intro), color = role(LocalPalette.current.muted))
        OutlinedTextField(
            password, { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("guest-password"),
            label = { Text(stringResource(R.string.share_password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = wrong,
            supportingText = if (wrong) ({ Text(stringResource(R.string.share_password_wrong)) }) else null,
        )
        PrimaryButton(stringResource(R.string.share_password_unlock), Modifier.testTag("guest-unlock"), enabled = password.isNotEmpty() && !busy, working = busy) {
            onUnlock(password)
        }
    }
}

/** A gate's or a page's one confirming action: the web's green role (`--app-primary`). */
@Composable
internal fun PrimaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, working: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Button(
        onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(containerColor = role(palette.primary), contentColor = role(palette.onFill)),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (working) CircularProgressIndicator(Modifier.padding(2.dp)) else Text(label)
    }
}

/**
 * A text the share's owner or its operator wrote in Markdown (the terms, the note): its paragraphs, a heading's line bold,
 * a list's items with a bullet — the platform draws no Markdown of its own, and the text is shown, never followed.
 */
@Composable
private fun MarkdownText(markdown: String) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (block in markdown.trim().split(Regex("\\n\\s*\\n"))) {
                val line = block.trim()
                when {
                    line.startsWith("#") -> Text(line.trimStart('#').trim(), fontWeight = FontWeight.Bold)
                    else -> Text(line.lines().joinToString("\n") { l -> l.trim().replace(Regex("^[-*] "), "• ") }.replace("**", ""))
                }
            }
        }
    }
}
