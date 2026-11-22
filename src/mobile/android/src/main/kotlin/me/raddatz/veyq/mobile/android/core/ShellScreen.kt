package me.raddatz.veyq.mobile.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.material3.AlertDialog
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ArrangedList
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.ObjectRef

/**
 * The shell (ADR 0122): the drawer chooses what the app is in; the bar at the bottom belongs to the chosen
 * application — its own places, or those of its open object — and ends with Search everywhere. Its look is the web's
 * (ADR 0123 decision 7): the accent for selection, the green for creating, the web's rows, tiles and section labels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(s: Screen.Signed, model: AppModel) {
    // Material 3's window classes (ADR 0122 decision 2): from a medium window on, a rail at the side takes the bar's place;
    // on an expanded one the drawer stays open as the sidebar and the list sits beside the open object — unless the window's
    // height is compact: a phone in landscape (about 914 × 411 dp) left the open Memory a strip of 120 dp beside the drawer,
    // the rail and the list (#951), so it keeps the rail and one pane
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    val rail = widthDp >= MEDIUM_WIDTH_DP
    val wide = widthDp >= EXPANDED_WIDTH_DP && configuration.screenHeightDp >= COMPACT_HEIGHT_BELOW_DP
    val drawer = rememberDrawerState(if (s.shell.drawerOpensAtStart && !wide) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    val shell = s.shell
    val rowDetails by model.rowDetails.collectAsStateWithLifecycle()
    val avatars by model.avatars.collectAsStateWithLifecycle()
    val incoming by model.incoming.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    // each list's scroll, kept while an object opened from it is on screen (`ListStates`)
    val listStates = remember { ListStates() }
    /** The space whose trash is open. */
    var trashOf by remember { mutableStateOf<String?>(null) }
    var uploadShareAt by remember { mutableStateOf<String?>(null) }
    fun closeDrawerThen(f: () -> Unit) { f(); scope.launch { drawer.close() } }

    // what the model says (an upload's refusal, its "Replace anyway"): one snackbar each
    androidx.compose.runtime.LaunchedEffect(model) {
        model.notices.collect { n ->
            val answer = snackbar.show(n.text, n.kind, n.action)
            if (answer == androidx.compose.material3.SnackbarResult.ActionPerformed) n.run?.let { scope.launch { it() } }
            n.done?.invoke()
        }
    }
    // "Send a copy…": what happens is said first — the copy leaves veyq decrypted —, then the system's share sheet
    val sending by model.sending.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    sending?.let { send ->
        val node = send.node
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { model.askToSend(null) },
            title = { Text(stringResource(R.string.file_send_title)) },
            text = { Text(stringResource(R.string.file_send_notice, node.name)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    model.askToSend(null)
                    scope.launch {
                        val copy = model.copyForSending(send)
                        if (copy == null) {
                            snackbar.show(context, me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.DOWNLOAD_FAILED, node.name))
                            return@launch
                        }
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = copy.second
                            putExtra(android.content.Intent.EXTRA_STREAM, copy.first)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(send, null))
                    }
                }) { Text(stringResource(R.string.file_send_confirm)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { model.askToSend(null) }) { Text(stringResource(R.string.entry_cancel)) } },
        )
    }

    // items made available offline that left this device (core's, named where the shell held their name): one snackbar
    // each, then core does not say them again
    androidx.compose.runtime.LaunchedEffect(s.gone) {
        if (s.gone.isEmpty()) return@LaunchedEffect
        for (n in s.gone) snackbar.show(context, n)
        model.goneSeen()
    }

    BackHandler(enabled = shell.searching || shell.canGoBack) {
        if (shell.searching) model.search(false) else model.goBack()
    }

    val covers by model.covers.collectAsStateWithLifecycle()
    // how every level is drawn: the account's one view choice on this device (ADR 0149 decision 8)
    val levelView by model.levelView.collectAsStateWithLifecycle()
    androidx.compose.runtime.CompositionLocalProvider(LocalRowDetails provides rowDetails, LocalAvatars provides avatars, LocalPicking provides (incoming != null), LocalTakesShare provides model::takesShare, LocalCovers provides covers, LocalShellNotices provides ShellNotices(snackbar, scope), LocalLevelView provides levelView) {
    val drawerContent: @Composable () -> Unit = {
        Drawer(
            s,
            onChoose = { closeDrawerThen { model.choose(it) } },
            // the sidebar an expanded window keeps open chooses a top-level destination, no back (`Shell.openAtTop`);
            // the modal drawer opens as a list does
            onOpen = { closeDrawerThen { if (wide) model.openAtTop(it) else model.open(it) } },
            onSwitch = { account, tenant -> closeDrawerThen { model.switchTo(account, tenant) } },
            onSpace = { closeDrawerThen { model.switchSpace(it) } },
            onAddServer = { closeDrawerThen { model.openManage() } },
            onNew = { kind -> closeDrawerThen { model.requestNew(kind) } },
            creates = model.drawerCreates(),
            onSettings = { closeDrawerThen { model.openSettings() } },
            permanent = wide,
        )
    }
    // the bar's places, drawn as a bar at the bottom or a rail at the side
    val places = shell.barTabs.map { tab ->
        Place(!shell.searching && tab == shell.selectedTab, { model.select(tab) }, tabIcon(tab, shell.openObject?.kind), tabLabel(tab, shell.openObject?.kind))
    } + Place(shell.searching, { model.search(true) }, Icons.Outlined.Search, stringResource(R.string.tab_search))
    val body: @Composable () -> Unit = {
        Scaffold(
            // a drop onto the app is a share (ADR 0138 decision 9); a Memory's gallery takes its own
            modifier = Modifier.sharedFilesDrop { model.receiveDrop(it.incoming) },
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
            topBar = {
                Column {
                if (!shell.searching) {
                    // The node on screen — an open object, or the folder Home shows — headed by core for every kind (the
                    // web's `NodePage` + `NodeHeader`): its name, what its kind's page contributes, core's actions.
                    val space = shell.space
                    // a kept link's page is core's own (#1032): headed as every node, its body the bookmark's page
                    val headed = if (shell.person != null) null else shell.bookmark ?: shell.openObject?.at
                        ?: if (shell.application == null && space != null) shell.folderAt(space) else null
                    val contribution = headed?.let { KindPages.of(it.kind)?.header?.invoke(it, model) }
                    TopAppBar(
                        title = {
                            when {
                                // a person's profile (ADR 0144 decision 11): their name, as core's frame names them
                                shell.person != null -> Text(personTitle(model, shell.person!!), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                shell.bookmark != null -> Text(shell.bookmark!!.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                shell.openObject != null -> Column {
                                    Text(shell.openObject!!.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    contribution?.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted)) }
                                }
                                shell.application != null -> Text(appName(shell.application!!), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                shell.folder != null -> Text(shell.folder!!.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                // the space's name; its switcher is the drawer's, with the tenants (owner 2026-11-10)
                                else -> Text(spaceName(s.space), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        actions = {
                            // one's own profile: *Edit profile* opens the same model as Settings' *Your profile* (ADR 0084 decision 6)
                            if (shell.person != null && shell.person == model.ownUserId()) {
                                IconButton(onClick = model::openProfile, modifier = Modifier.testTag("person-edit-profile")) {
                                    Icon(Icons.Outlined.Edit, stringResource(R.string.profile_edit))
                                }
                            }
                            if (headed != null && contribution != null) {
                                NodeHeaderActions(headed, contribution, model, snackbar, scope)
                            }
                            // the top level is no link's root: its share offers "Add files only" alone (the web's level
                            // share), where core says — the top level's header, as a folder's share is its header's
                            if (space != null && shell.openObject == null && shell.person == null && shell.bookmark == null && shell.application == null &&
                                !shell.searching && model.offersTopLevelShare(shell)
                            ) {
                                IconButton(onClick = { uploadShareAt = space }) { Icon(Icons.Outlined.Share, stringResource(R.string.share_role_uploader)) }
                            }
                            // the level's tools (core's `LevelPlan.tools`, ADR 0154 decision 2; the web's tools row): every
                            // one, in its order, as toolbar icons. A wide window's list beside its object has its own pane.
                            val level = if (shell.searching || (wide && shell.showsListBeside)) null else model.levelPlan(shell)
                            for (tool in level?.tools.orEmpty()) when (tool) {
                                // Select (core's `LevelSelection`, inbox #1049): the level's pick mode on and off, pressed while on
                                me.raddatz.veyq.mobile.navigation.LevelTool.SELECT -> {
                                    val on = s.selection != null
                                    androidx.compose.material3.IconToggleButton(
                                        checked = on, onCheckedChange = { if (it) model.selectStart() else model.selectCancel() },
                                        modifier = Modifier.testTag("level-select"),
                                    ) { Icon(Icons.Outlined.CheckBox, say(me.raddatz.veyq.mobile.navigation.LevelSelection.word)) }
                                }
                                // the space's trash: every application's nodes
                                me.raddatz.veyq.mobile.navigation.LevelTool.TRASH -> if (space != null) {
                                    IconButton(onClick = { trashOf = space }, modifier = Modifier.testTag("level-trash")) {
                                        Icon(Icons.Outlined.Delete, stringResource(R.string.trash_title))
                                    }
                                }
                                // the view switch, rows or tiles (ADR 0149 decision 8): one choice for every level
                                me.raddatz.veyq.mobile.navigation.LevelTool.VIEW -> {
                                    val tiles = levelView == me.raddatz.veyq.mobile.navigation.ViewMode.TILES
                                    IconButton(
                                        onClick = { model.chooseLevelView(if (tiles) me.raddatz.veyq.mobile.navigation.ViewMode.LIST else me.raddatz.veyq.mobile.navigation.ViewMode.TILES) },
                                        modifier = Modifier.testTag("level-view"),
                                    ) {
                                        if (tiles) Icon(Icons.AutoMirrored.Outlined.ViewList, stringResource(R.string.node_view_list_label))
                                        else Icon(Icons.Outlined.GridView, stringResource(R.string.node_view_grid_label))
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            if (shell.canGoBack) {
                                IconButton(onClick = model::goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                            } else if (!wide) {
                                IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, stringResource(R.string.drawer_open)) }
                            }
                        },
                    )
                }
                // core's banner: the open page's own kept time where it has one (a Pot's ledger), else the lists'
                val waitingCount by model.queuedBanner.collectAsStateWithLifecycle()
                s.banner?.let { OfflineBanner(it, waitingCount) }
                incoming?.let { ShareBanner(it, s, model, snackbar, onCancel = model::cancelShare) }
                }
            },
            bottomBar = {
                Column {
                // the music bar while a queue is loaded: above the application's bar on a phone, along the window's
                // bottom where a rail holds the places (ADR 0142 decision 5)
                MusicBar(model)
                if (!rail) NavigationBar(containerColor = role(palette.surface)) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = role(palette.accent),
                        selectedTextColor = role(palette.accent),
                        indicatorColor = role(palette.accentSoft),
                        unselectedIconColor = role(palette.muted),
                        unselectedTextColor = role(palette.muted),
                    )
                    for (p in places) {
                        NavigationBarItem(selected = p.selected, onClick = p.onClick, icon = { Icon(p.icon, null) }, label = { Text(p.label) }, colors = colors)
                    }
                }
                }
            },
        ) { padding ->
            // the node on screen's head, which its body places first (core's, for every kind)
            val paged = shell.openObject?.at
                ?: shell.space?.takeIf { shell.application == null && shell.bookmark == null && !shell.searching }?.let { shell.folderAt(it) }
            val pagedContribution = paged?.let { KindPages.of(it.kind)?.header?.invoke(it, model) }
            val hero: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { inset ->
                if (paged != null) NodeCoverHero(paged, model, canSet = me.raddatz.veyq.mobile.AppKinds.coverSettable(paged.kind, pagedContribution?.facts), inset = inset)
            }
            androidx.compose.foundation.layout.Row(Modifier.padding(padding).fillMaxSize()) {
            if (rail) {
                androidx.compose.material3.NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                    val colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                        selectedIconColor = role(palette.accent),
                        selectedTextColor = role(palette.accent),
                        indicatorColor = role(palette.accentSoft),
                        unselectedIconColor = role(palette.muted),
                        unselectedTextColor = role(palette.muted),
                    )
                    for (p in places) {
                        androidx.compose.material3.NavigationRailItem(selected = p.selected, onClick = p.onClick, icon = { Icon(p.icon, null) }, label = { Text(p.label) }, colors = colors)
                    }
                }
            }
            // a pull to refresh reads the lists anew (#953): on a list, and on a page drawn from the tree — a Memory's, a
            // chapter's, a drop box's (a guest's new folder) —; a Pot has its own, a file's page none
            val refreshable = !shell.searching && shell.person == null && shell.bookmark == null && shell.openObject?.kind.let { it == null || it in TREE_PAGES }
            var refreshing by remember { mutableStateOf(false) }
            val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
            Box(
                Modifier.weight(1f).fillMaxSize().pullToRefresh(refreshing, pull, enabled = refreshable) {
                    refreshing = true
                    model.refreshLists { refreshing = false }
                },
            ) {
              androidx.compose.runtime.CompositionLocalProvider(LocalNodeHero provides hero, LocalListStates provides listStates) {
                when {
                    // a wide window: the application's list and its open object side by side (Material 3's list-detail)
                    shell.searching -> SearchScreen(model, onClose = { model.search(false) })
                    shell.person != null -> PersonScreen(shell.person!!, model)
                    // a kept link's page (ADR 0150 decision 6, #1032): the share in place, or the link on its own page
                    shell.bookmark != null -> BookmarkScreen(shell.bookmark!!, model)
                    wide && shell.showsListBeside -> ListBeside(s, model)
                    shell.openObject != null -> KindPages.of(shell.openObject!!.kind)?.body?.invoke(shell.openObject!!, shell.selectedTab, model)
                        ?: ObjectScreen(shell.openObject!!, shell.selectedTab)
                    shell.application == null -> HomeScreen(s, model, onEnter = model::enter, onOpen = model::open, onFilter = model::filter)
                    // an application's own list where it registered one (the registry's `list`), else core's arranged list
                    else -> Registry.application(shell.application!!)?.list?.let { list -> list(s, model, model::open) }
                        ?: ArrangedListScreen(s.applicationList(shell.application!!), model, onOpen = model::open, state = listStates.of("app:${shell.application}"))
                }
              }
                if (s.switching) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                if (refreshable) androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(pull, refreshing, Modifier.align(Alignment.TopCenter))
                Column(Modifier.fillMaxWidth()) {
                    UploadTray(model)
                    // the writes made offline, each under where it goes (core's `QueuedTray`, ADR 0153 decision 5)
                    QueuedTrayRow(model)
                }
            }
            }
        }
        trashOf?.let { SpaceTrashSheet(it, model) { trashOf = null } }
        // a held rename's *Open* from the tray (core's `QueuedOpen.Rename`)
        HeldRenameDialog(model)
        uploadShareAt?.let { TopLevelUploadSheet(it, model) { uploadShareAt = null } }
    }
    if (wide) androidx.compose.material3.PermanentNavigationDrawer(drawerContent = drawerContent) { body() }
    else ModalNavigationDrawer(drawerState = drawer, drawerContent = drawerContent) { body() }
    }
}

/** Material 3's window width classes (dp): a medium window from 600, an expanded one from 840. */
private const val MEDIUM_WIDTH_DP = 600
private const val EXPANDED_WIDTH_DP = 840

/** The kinds whose page is drawn from the tree, which a pull to refresh reads anew (#953). */
private val TREE_PAGES = setOf("MEMORY", "CHAPTER", "DEPOT_DROPBOX")

/** Material 3's window height classes (dp): a compact height below 480 (a phone in landscape). */
private const val COMPACT_HEIGHT_BELOW_DP = 480

/** One place of the bar: a tab of the chosen application, or Search. */
private class Place(val selected: Boolean, val onClick: () -> Unit, val icon: ImageVector, val label: String)

@Composable
internal fun spaceName(space: me.raddatz.veyq.mobile.navigation.SpaceRef?): String = piece(me.raddatz.veyq.mobile.navigation.ShellWords.space(space))

private fun spaceIcon(kind: String): ImageVector = when (kind) {
    "PERSONAL" -> Icons.Outlined.Person
    "GROUP" -> Icons.Outlined.Groups
    else -> Icons.Outlined.Home
}

/**
 * Home (ADR 0122): the shown space's tree, one level at a time, as the web's level view lists it — the folders, then
 * every application's objects mixed; the filters at the top keep one application's objects, the folders stay.
 */
@Composable
private fun HomeScreen(s: Screen.Signed, model: AppModel, onEnter: (me.raddatz.veyq.mobile.navigation.FolderRef) -> Unit, onOpen: (ObjectRef) -> Unit, onFilter: (ApplicationId?) -> Unit) {
    val palette = LocalPalette.current
    var naming by remember { mutableStateOf(false) }
    // what an application creates here, through the dialog it registered (the registry's `creates`: a Pot, a Memory)
    var creating by remember { mutableStateOf<me.raddatz.veyq.mobile.AppKinds.Create?>(null) }
    var keepingLink by remember { mutableStateOf(false) }
    // Depot's "Upload files": the system's picker hands over the documents chosen, queued for this level
    val levelLabel = s.shell.folder?.name ?: spaceName(s.space)
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val space = s.shell.space
        if (uris.isNotEmpty() && space != null) model.uploadPicked(space, s.shell.folder?.nodeId, levelLabel, uris)
    }
    // Depot's "Upload folder…": the system's folder picker grants one directory, uploaded here whole (`FolderUpload`)
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { tree ->
        val space = s.shell.space
        if (tree != null && space != null) model.uploadFolderPicked(space, s.shell.folder?.nodeId, tree)
    }
    // what each entry of a New starts: its dialog or the system's picker
    fun start(c: me.raddatz.veyq.mobile.AppKinds.Create) { when (c) {
        me.raddatz.veyq.mobile.AppKinds.Create.FOLDER -> naming = true
        me.raddatz.veyq.mobile.AppKinds.Create.POT -> creating = c
        me.raddatz.veyq.mobile.AppKinds.Create.UPLOAD_FILES -> pickFiles.launch(arrayOf("*/*"))
        me.raddatz.veyq.mobile.AppKinds.Create.UPLOAD_FOLDER -> pickFolder.launch(null)
        me.raddatz.veyq.mobile.AppKinds.Create.MEMORY -> creating = c
        // a Memory's page's own New, never a level's (`createsWhere` holds none)
        me.raddatz.veyq.mobile.AppKinds.Create.CHAPTER -> error("a level offers no chapter")
        // a link kept here (ADR 0150 decision 6): the web's bookmark dialog
        me.raddatz.veyq.mobile.AppKinds.Create.BOOKMARK -> keepingLink = true
    } }
    // what the drawer's "New" asked for (core's `ShellModel.drawerCreates`)
    val asked by model.newRequest.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(asked) {
        val c = asked ?: return@LaunchedEffect
        model.newRequestTaken()
        start(c)
    }
    // The level's "New" in its head (core's `LevelPlan.creates`, ADR 0154 decision 2; the web's `LevelView` menu): every
    // entry, in its order — online all a level makes, offline only what is queued, none in a guest's folder. Material 3
    // draws a screen's one primary action as its floating action button.
    val creates = model.levelPlan(s.shell)?.creates.orEmpty()
    CreateAction(stringResource(R.string.create_new), shown = creates.isNotEmpty(), onClick = {}, state = LocalListStates.current.of("home:${s.shell.space}/${s.shell.folder?.nodeId}"), menu = { close ->
        creates.forEach { c ->
            androidx.compose.material3.DropdownMenuItem(text = { Text(say(c.word)) }, onClick = { close(); start(c) })
        }
    }) { list, bottom ->
    // a file dropped onto the level goes through core's one drop (ADR 0154 decision 1): uploaded here where it takes it, else a share
    Column(Modifier.fillMaxSize().sharedFilesDrop { drop -> model.drop(me.raddatz.veyq.mobile.composition.DropTarget.Level(levelLabel), drop) }) {
        if (s.filters.size > 1) {
            val selected = s.filters.indexOf(s.shell.filter).coerceAtLeast(0)
            androidx.compose.material3.PrimaryScrollableTabRow(
                selectedTabIndex = selected,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = role(palette.accent),
                edgePadding = 16.dp,
                divider = { HorizontalDivider(color = role(palette.line)) },
            ) {
                s.filters.forEachIndexed { i, f ->
                    androidx.compose.material3.Tab(
                        selected = i == selected,
                        onClick = { onFilter(f) },
                        selectedContentColor = role(palette.accent),
                        unselectedContentColor = role(palette.muted),
                        text = { Text(f?.let { appName(it) } ?: stringResource(R.string.filter_all), fontWeight = if (i == selected) androidx.compose.ui.text.font.FontWeight.ExtraBold else androidx.compose.ui.text.font.FontWeight.SemiBold) },
                    )
                }
            }
        }
        // the selection's bar while the level picks (core's `LevelSelection.Picking`, the web's selection bar): the count, All,
        // None, every action of its plan in order (enabled where some selected row allows it, its hint what it cannot
        // reach), Cancel
        s.selection?.let { SelectionBar(it, model) }
        val groups = s.levelGroups
        val queuedHere by model.queuedHere.collectAsStateWithLifecycle()
        val hero = LocalNodeHero.current
        val tiles = LocalLevelView.current == me.raddatz.veyq.mobile.navigation.ViewMode.TILES
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = tileColumns(maxWidth)
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottom)) {
                item(key = "hero") { hero(8.dp) }
                // what was made here offline and is not sent yet, marked (core's `ShellModel.queuedHere`, ADR 0153 decision 5)
                items(queuedHere, key = { "queued:" + it.id }) { QueuedLevelRow(it, model::open) }
                if (groups.isEmpty() && queuedHere.isEmpty()) item(key = "empty") { Empty() }
                // the folders apart from the rest, as the web's level view (core's `Home.groups`)
                for (group in groups) {
                    val heading = group.heading
                    if (heading != null) item(key = "group-${group.folders}") { Box(Modifier.padding(horizontal = 8.dp)) { Section(say(heading)) } }
                    // as tiles: every kind a square tile, a folder its cover or the folder symbol (ADR 0149 decision 8)
                    // each row's and tile's menu a long press opens (core's `ShellModel.rowPlan`, [LevelRowMenu])
                    if (tiles) tileRows(group.nodes, columns, key = { it.scopeId + it.nodeId }, inset = 8.dp) { node, modifier ->
                        val folder = node.asFolder()
                        val obj = node.asObject()
                        val bookmark = node.asBookmark()
                        WithRowMenu(node.at(), model, modifier, s.selection) { menu ->
                            if (folder != null) {
                                LevelTile(node.name, null, LocalCovers.current[node.nodeId]?.second, Icons.Outlined.Folder, folder = true, modifier = Modifier.rowDrop(node, model), onMenu = menu) { onEnter(folder) }
                            } else if (obj != null) ObjectTile(obj, Modifier, onOpen, menu)
                            else if (bookmark != null) LevelTile(bookmark.name, null, null, kindIcon(bookmark.kind), folder = false, modifier = Modifier, onMenu = menu) { model.openBookmark(bookmark) }
                        }
                    } else items(group.nodes, key = { it.scopeId + it.nodeId }) { node -> WithRowMenu(node.at(), model, Modifier, s.selection) { menu ->
                        val folder = node.asFolder()
                        if (folder != null) {
                            ListItem(
                                headlineContent = { Text(node.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                                leadingContent = {
                                    val cover = LocalCovers.current[node.nodeId]?.second
                                    if (cover != null) androidx.compose.foundation.Image(
                                        cover, null, Modifier.size(34.dp).clip(MaterialTheme.shapes.medium), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    ) else FolderTile()
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                                modifier = Modifier.rowDrop(node, model).clip(MaterialTheme.shapes.medium)
                                    .clickableRow(stringResource(R.string.node_more), menu) { onEnter(folder) },
                            )
                        } else {
                            node.asObject()?.let { ObjectRow(it, onOpen, menu) }
                            // a kept link (core's, #1032): its row opens the bookmark's page
                            node.asBookmark()?.let { b -> BookmarkRow(b, menu) { model.openBookmark(b) } }
                        }
                    } }
                }
            }
        }
    }
    }
    if (naming) NameAnswerDialog(stringResource(R.string.folder_new), onClose = { naming = false }, onSaid = model::said) { name -> model.createFolder(name) }
    val space = s.shell.space
    creating?.let { c -> Registry.create(c)?.invoke(s, model, space, s.shell.folder?.nodeId) { creating = null } }
    if (keepingLink && space != null) {
        val create = remember(space, s.shell.folder?.nodeId) { model.bookmarkCreate(space, s.shell.folder?.nodeId) }
        if (create == null) keepingLink = false
        else BookmarkCreateDialog(create, onClose = { keepingLink = false }, onMade = model::bookmarkMade)
    }
}

/**
 * A level's or an application list's row or tile with its menu (core's `ShellModel.rowPlan`, [LevelRowMenu]): [content]
 * gets the long press that opens it, none while a share picks its place (a pick only chooses); the menu anchors at the row.
 */
@Composable
private fun WithRowMenu(
    node: me.raddatz.veyq.mobile.navigation.NodeAt, model: AppModel, modifier: Modifier,
    /** The level's selection while it picks: a tap turns the row instead of opening it, and a check shows whether it is selected. */
    selection: me.raddatz.veyq.mobile.navigation.LevelSelection.Picking? = null,
    content: @Composable (onMenu: (() -> Unit)?) -> Unit,
) {
    var open by remember(node.scopeId, node.nodeId) { mutableStateOf(false) }
    val picking = LocalPicking.current
    if (selection != null) {
        // Material's selection mode: the row drawn as it is, a checkbox over its start, the whole row one toggle
        val checked = selection.has(node.nodeId)
        Box(modifier.clip(MaterialTheme.shapes.medium).let { if (checked) it.background(role(LocalPalette.current.accentSoft)) else it }) {
            content(null)
            Box(
                Modifier.matchParentSize().testTag("select-row")
                    .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Checkbox) { model.selectToggle(node.nodeId) },
            ) {
                androidx.compose.material3.Checkbox(checked, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        return
    }
    Box(modifier) {
        content(if (picking) null else ({ open = true }))
        LevelRowMenu(node, model, open) { open = false }
    }
}

/**
 * The selection's bar (core's `LevelSelection.Picking`, the web's `LevelView` selection bar): the count, *All* and *None*,
 * every action of its plan in its order — each enabled where some selected row allows it, with what it cannot reach under
 * it — and *Cancel*. Each action opens core's own sheet over the selected rows it reaches.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(sel: me.raddatz.veyq.mobile.navigation.LevelSelection.Picking, model: AppModel) {
    val palette = LocalPalette.current
    val notices = LocalShellNotices.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by remember { mutableStateOf<me.raddatz.veyq.mobile.navigation.SelectionAction?>(null) }
    val label = say(sel.label)
    androidx.compose.material3.Surface(
        color = role(palette.accentSoft), shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("selection-bar")
            .semantics { paneTitle = label },
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            androidx.compose.foundation.layout.FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    say(sel.count), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = model::selectAll) { Text(say(sel.all)) }
                TextButton(onClick = model::selectNone) { Text(say(sel.none)) }
                for (a in sel.entries) {
                    val danger = a.action == me.raddatz.veyq.mobile.navigation.SelectionAction.DELETE
                    TextButton(onClick = { open = a.action }, enabled = a.enabled, modifier = Modifier.testTag("select-${a.action.name.lowercase()}")) {
                        Text(say(a.word), color = if (danger && a.enabled) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                    }
                }
                TextButton(onClick = model::selectCancel) { Text(say(sel.cancel)) }
            }
            for (a in sel.entries) a.cannot?.let { Text(say(it), style = MaterialTheme.typography.bodySmall, color = role(palette.muted)) }
        }
    }
    val close = { open = null }
    when (open) {
        me.raddatz.veyq.mobile.navigation.SelectionAction.MOVE, me.raddatz.veyq.mobile.navigation.SelectionAction.COPY ->
            remember(open) { model.selectionMoveSheet(open == me.raddatz.veyq.mobile.navigation.SelectionAction.COPY) }?.let { sheet ->
                NodeMoveSheet(sheet, onDone = { done -> notices?.let { moved(done, it.snackbar, it.scope, context) } }, onClose = close)
            } ?: close()
        me.raddatz.veyq.mobile.navigation.SelectionAction.REPORT ->
            remember(open) { model.selectionReportSheet() }?.let { ReportSheet(it, close) } ?: close()
        me.raddatz.veyq.mobile.navigation.SelectionAction.DELETE -> remember(open) { model.selectionTrash() }?.let { delete ->
            AlertDialog(
                onDismissRequest = close,
                title = { Text(say(delete.title)) },
                text = { Text(say(delete.question)) },
                confirmButton = {
                    TextButton(onClick = {
                        close()
                        notices?.let { n ->
                            n.scope.launch {
                                val said = delete.trash()
                                offerUndo(said, delete.current.undo != null, { delete.state.first { it.undo == null } }, { delete.undo() }, n.snackbar, context)
                            }
                        }
                    }) { Text(stringResource(R.string.entry_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.entry_cancel)) } },
            )
        } ?: close()
        null -> {}
    }
}

private fun me.raddatz.veyq.mobile.navigation.NodeRef.at() = me.raddatz.veyq.mobile.navigation.NodeAt(scopeId, nodeId, kind, name)

/**
 * A folder's row as a drop target (ADR 0154 decision 1): only where core's `ShellModel.dropTarget` offers it — never by its
 * kind (a guest's identity folder takes none) —, the drop handed to core's one drop onto that folder.
 */
@Composable
private fun Modifier.rowDrop(node: me.raddatz.veyq.mobile.navigation.NodeRef, model: AppModel): Modifier {
    val at = me.raddatz.veyq.mobile.navigation.NodeAt(node.scopeId, node.nodeId, node.kind, node.name)
    return if (model.dropTarget(at)) sharedFilesDrop { drop -> model.drop(me.raddatz.veyq.mobile.composition.DropTarget.Row(at), drop) } else this
}

/**
 * A folder's name dialog answered by core's typed [me.raddatz.veyq.mobile.navigation.NameAnswer] (ADR 0154 decision 1):
 * a problem keeps it open with the word under the field, a notice closes it and is said ([onSaid]), anything else closes it.
 */
@Composable
internal fun NameAnswerDialog(
    title: String, onClose: () -> Unit, onSaid: (me.raddatz.veyq.mobile.words.Notice) -> Unit,
    onName: suspend (String) -> me.raddatz.veyq.mobile.navigation.NameAnswer,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    NameDialog(title, onClose) { name ->
        val answer = onName(name)
        answer.notice?.let(onSaid)
        answer.problem?.let { context.text(it) }
    }
}

/**
 * A new node's name ([title]: a folder's, core's `FolderNameDialog`; a Memory's or a chapter's, the web's
 * `MemoryNodeDialog`): the name, trimmed on save; a refusal says why and stays.
 */
@Composable
internal fun NameDialog(title: String, onClose: () -> Unit, extra: (@Composable () -> Unit)? = null, onCreate: suspend (String) -> String?) {
    var name by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text(title) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    name, { name = it; problem = null }, singleLine = true, label = { Text(stringResource(R.string.folder_name)) },
                    isError = problem != null, supportingText = problem?.let { { Text(it) } },
                )
                extra?.invoke()
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = name.isNotBlank() && !saving, onClick = {
                saving = true
                scope.launch {
                    problem = onCreate(name)
                    saving = false
                    if (problem == null) onClose()
                }
            }) { Text(stringResource(R.string.folder_create)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose, enabled = !saving) { Text(stringResource(R.string.trash_cancel)) } },
    )
}

/** The web's folder tile: the info role's soft fill and text (`ui.css` § folder). */
@Composable
private fun FolderTile() {
    val palette = LocalPalette.current
    Box(
        Modifier.size(34.dp).clip(MaterialTheme.shapes.medium).background(role(palette.infoSoft)),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = role(palette.infoText)) }
}

/**
 * The switcher at the drawer's top (owner 2026-11-10: spaces and tenants in one): the shown space, its tenant and server
 * under it; the tenant's spaces first — a space chosen opens its Home —, then every server's tenants, and managing servers.
 */
@Composable
private fun Switcher(
    s: Screen.Signed,
    onSpace: (String) -> Unit,
    onSwitch: (me.raddatz.veyq.mobile.session.VeyqAccount, me.raddatz.veyq.mobile.navigation.TenantRef) -> Unit,
    onAddServer: () -> Unit,
) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(role(palette.hover)).clickableRow { open = true }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TenantAvatar(s.tenant.name)
            Column(Modifier.weight(1f)) {
                Text(spaceName(s.space), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = role(palette.text), maxLines = 1)
                Text(me.raddatz.veyq.mobile.navigation.ShellWords.switcherSubtitle(s.space, s.tenant.name, s.server), style = MaterialTheme.typography.bodySmall, color = role(palette.muted), maxLines = 1)
            }
            Icon(Icons.Filled.UnfoldMore, stringResource(R.string.switcher_open), tint = role(palette.muted))
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(s.tenant.name, style = VeyqType.sectionLabel, color = role(palette.muted), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            for (space in s.spaces) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(spaceName(space)) },
                    leadingIcon = { Icon(spaceIcon(space.kind), null, tint = role(palette.accent)) },
                    trailingIcon = { if (space.scopeId == s.shell.space) Icon(Icons.Filled.Check, null) },
                    onClick = { open = false; onSpace(space.scopeId) },
                )
            }
            HorizontalDivider()
            // every signed-in account's tenants, each row saying where it is (core's `ShellWords.switcher`: its domain, and
            // the account's address where two accounts list the same tenant; owner 2026-11-19)
            for (row in s.servers) {
                val e = row.entry
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(e.name, maxLines = 1)
                            Text(e.detail, style = MaterialTheme.typography.bodySmall, color = role(palette.muted), maxLines = 1)
                        }
                    },
                    leadingIcon = { TenantAvatar(e.name, small = true) },
                    trailingIcon = { if (e.current) Icon(Icons.Filled.Check, null) },
                    onClick = { open = false; onSwitch(row.account, e.tenant) },
                )
            }
            HorizontalDivider()
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.switcher_manage)) },
                leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                onClick = { open = false; onAddServer() },
            )
        }
    }
}

/** The web's tenant avatar: the initial on the accent's gradient (`.sidebar-tenant-avatar`). */
@Composable
private fun TenantAvatar(name: String, small: Boolean = false) {
    val palette = LocalPalette.current
    val size = if (small) 26.dp else 34.dp
    Box(
        Modifier.size(size).clip(MaterialTheme.shapes.medium)
            .background(Brush.linearGradient(listOf(role(palette.accent), role(palette.accent).copy(alpha = 0.72f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).uppercase(), color = role(palette.onFill), fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
    }
}

@Composable
private fun Drawer(
    s: Screen.Signed,
    onChoose: (ApplicationId?) -> Unit,
    onOpen: (ObjectRef) -> Unit,
    onSwitch: (me.raddatz.veyq.mobile.session.VeyqAccount, me.raddatz.veyq.mobile.navigation.TenantRef) -> Unit,
    /** A space chosen in the switcher: its Home. */
    onSpace: (String) -> Unit,
    onAddServer: () -> Unit,
    /** The sidebar's "New": what a level creates, opened on Home. */
    onNew: (me.raddatz.veyq.mobile.AppKinds.Create) -> Unit,
    /** What its "New" offers (core's `ShellModel.drawerCreates`: offline only what is queued). */
    creates: List<me.raddatz.veyq.mobile.AppKinds.Create>,
    /** The drawer's Settings: the account's page (`uc.account.sessions`). */
    onSettings: () -> Unit,
    /** Open for good beside the content (an expanded window), not over it. */
    permanent: Boolean = false,
) {
    val palette = LocalPalette.current
    // The web's sidebar: the surface, washed with a trace of the accent from the top (`--app-chrome-pattern`).
    val chrome = Brush.verticalGradient(listOf(role(palette.accentSoft), role(palette.surface)), endY = 900f)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
    DrawerSheet(permanent, chrome, shape) {
        // Settings pinned at the foot, below what scrolls (owner 2026-11-10)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                Brand(Modifier.padding(start = 8.dp, top = 20.dp, bottom = 14.dp))
                Switcher(s, onSpace, onSwitch, onAddServer)
                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.height(6.dp))
                Row(app = Icons.Outlined.Home, label = stringResource(R.string.drawer_home), selected = s.shell.isHome) { onChoose(null) }
                // only the sections with something in them (core's `Lists.drawerSections`; owner 2026-11-19)
                for (section in s.drawerSections) when (section) {
                    // the web's sidebar "New" below the switcher (ADR 0122 decision 2): what it makes lands at the space's
                    // top, opened on Home
                    me.raddatz.veyq.mobile.navigation.DrawerSection.NEW -> DrawerNew(creates, onNew)
                    me.raddatz.veyq.mobile.navigation.DrawerSection.FAVORITES -> {
                        Section(stringResource(R.string.drawer_favorites))
                        ObjectItems(s.drawer.favorites, s.shell.openObject, onOpen)
                    }
                    me.raddatz.veyq.mobile.navigation.DrawerSection.RECENT -> {
                        Section(stringResource(R.string.drawer_recent))
                        ObjectItems(s.drawer.recent, s.shell.openObject, onOpen)
                    }
                    me.raddatz.veyq.mobile.navigation.DrawerSection.APPLICATIONS -> {
                        Section(stringResource(R.string.drawer_applications))
                        for (app in s.applications) {
                            Row(app = appIcon(app), label = appName(app), selected = s.shell.application == app && s.shell.openObject == null) { onChoose(app) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Column(Modifier.padding(horizontal = 14.dp)) {
                HorizontalDivider(Modifier.padding(bottom = 8.dp), color = role(palette.line))
                Row(app = Icons.Outlined.Settings, label = stringResource(R.string.drawer_settings), selected = false, onClick = onSettings)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** The web's brand: the mark and the wordmark beside it. */
@Composable
fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Image(painterResource(R.drawable.veyq_mark), null, Modifier.size(width = 30.dp, height = 32.dp))
        Text(stringResource(R.string.app_name), style = VeyqType.wordmark, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun Section(title: String) {
    Text(
        title.uppercase(), style = VeyqType.sectionLabel, color = role(LocalPalette.current.muted),
        modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** The web's leading tile: a 2.1rem square, `--app-radius-sm`, the accent on its soft fill. */
@Composable
private fun Tile(icon: ImageVector) {
    val palette = LocalPalette.current
    Box(
        Modifier.size(34.dp).clip(MaterialTheme.shapes.medium).background(role(palette.accentSoft)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(18.dp), tint = role(palette.accent)) }
}

/** A sidebar row as the web draws it: hover and selection are only the `--app-hover` fill, never bold, never a bar. */
@Composable
private fun Row(app: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Tile(app) },
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = role(palette.hover),
            unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            selectedTextColor = role(palette.text),
            unselectedTextColor = role(palette.controlText),
        ),
    )
}

@Composable
private fun ObjectItems(refs: List<ObjectRef>, open: ObjectRef?, onOpen: (ObjectRef) -> Unit) {
    for (ref in refs) {
        Row(app = kindIcon(ref.kind), label = ref.name, selected = open?.nodeId == ref.nodeId && open.scopeId == ref.scopeId) { onOpen(ref) }
    }
}

@Composable
internal fun ArrangedListScreen(
    list: ArrangedList,
    model: AppModel,
    onOpen: (ObjectRef) -> Unit,
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    /** Room below the last row: a floating button over the list keeps it clear (Material 3). */
    bottom: androidx.compose.ui.unit.Dp = 0.dp,
) {
    if (list.favorites.isEmpty() && list.recent.isEmpty() && list.all.isEmpty()) {
        Empty()
        return
    }
    // as tiles where the account chose them (ADR 0149 decision 8)
    val tiles = LocalLevelView.current == me.raddatz.veyq.mobile.navigation.ViewMode.TILES
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val columns = if (tiles) tileColumns(maxWidth) else 0
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottom)) {
        // each row's and tile's menu a long press opens, as a level's (core's `ShellModel.rowPlan`, [LevelRowMenu])
        group(R.string.drawer_favorites, list.favorites, model, onOpen, columns)
        group(R.string.drawer_recent, list.recent, model, onOpen, columns)
        // the rest has no heading: under Favorites or Recent a divider sets it apart, else its rows ran on as theirs
        if (list.all.isNotEmpty() && (list.favorites.isNotEmpty() || list.recent.isNotEmpty())) {
            item { androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
        group(null, list.all, model, onOpen, columns)
    }
    }
}

/**
 * The lists' scroll states by list (Home's level by space and folder, an application's list), kept by the shell while an
 * object opened from them is on screen: a list composed anew on the way back starts where it was, not at its top (the
 * owner's report, 2026-11-12: a Pot opened near the bottom came back to a list at the top).
 */
internal class ListStates {
    private val states = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    fun of(key: String): androidx.compose.foundation.lazy.LazyListState = states.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() }
}

internal val LocalListStates = androidx.compose.runtime.staticCompositionLocalOf { ListStates() }

/** Each listed node's cover, by node id (the model's), as the rows draw them. */
internal val LocalCovers = androidx.compose.runtime.staticCompositionLocalOf<Map<String, Pair<String, androidx.compose.ui.graphics.ImageBitmap>>> { emptyMap() }

@Composable
private fun ObjectRow(ref: ObjectRef, onOpen: (ObjectRef) -> Unit, onMenu: (() -> Unit)? = null) {
    val details = LocalRowDetails.current[ref.nodeId].orEmpty()
    // Picking a place for what was shared: only a kind that takes it can be chosen (ADR 0138 decision 1).
    val takes = !LocalPicking.current || LocalTakesShare.current(ref)
    ListItem(
        headlineContent = { Text(ref.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        // The application's facts about the object (a Pot: its currency), left out when it has none.
        supportingContent = details.takeIf { it.isNotEmpty() }?.let { { Text(it.joined(), color = role(LocalPalette.current.muted)) } },
        // the node's cover where it has one (core's row visual), else its kind's tile
        leadingContent = {
            val cover = LocalCovers.current[ref.nodeId]?.second
            if (cover != null) androidx.compose.foundation.Image(
                cover, null, Modifier.size(34.dp).clip(MaterialTheme.shapes.medium), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            ) else Tile(kindIcon(ref.kind))
        },
        // the object open beside the list (a wide window) is marked in it
        colors = ListItemDefaults.colors(
            containerColor = if (LocalOpenObject.current == ref.nodeId) role(LocalPalette.current.accentSoft) else MaterialTheme.colorScheme.background,
        ),
        modifier = Modifier.clip(MaterialTheme.shapes.medium).alpha(if (takes) 1f else 0.38f)
            .let { if (takes) it.clickableRow(stringResource(R.string.node_more), onMenu) { onOpen(ref) } else it },
    )
}

/**
 * One group of an arranged list: its heading, then its objects as rows — or as [columns] tiles a row where above zero —,
 * each with its row menu ([WithRowMenu]).
 */
private fun LazyListScope.group(title: Int?, refs: List<ObjectRef>, model: AppModel, onOpen: (ObjectRef) -> Unit, columns: Int = 0) {
    if (refs.isEmpty()) return
    if (title != null) item { Section(stringResource(title)) }
    if (columns > 0) tileRows(refs, columns, key = { "${title}-${it.scopeId}-${it.nodeId}" }, inset = 8.dp) { ref, modifier ->
        WithRowMenu(ref.at, model, modifier) { menu -> ObjectTile(ref, Modifier, onOpen, menu) }
    }
    else items(refs, key = { "${title}-${it.scopeId}-${it.nodeId}" }) { ref -> WithRowMenu(ref.at, model, Modifier) { menu -> ObjectRow(ref, onOpen, menu) } }
}

/** An object as a level's tile ([LevelTile]): its cover, its name, the application's facts; a pick dims what does not take it. */
/** A kept link's row on a level (ADR 0150 decision 6): its name and the bookmark's tile; a tap opens its page. */
@Composable
private fun BookmarkRow(node: me.raddatz.veyq.mobile.navigation.NodeAt, onMenu: (() -> Unit)? = null, onOpen: () -> Unit) {
    // picking a place for what was shared: a kept link takes nothing (ADR 0138 decision 1)
    val takes = !LocalPicking.current
    ListItem(
        headlineContent = { Text(node.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        leadingContent = { Tile(kindIcon(node.kind)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clip(MaterialTheme.shapes.medium).alpha(if (takes) 1f else 0.38f).testTag("bookmark-row").let { if (takes) it.clickableRow(stringResource(R.string.node_more), onMenu, onOpen) else it },
    )
}

@Composable
private fun ObjectTile(ref: ObjectRef, modifier: Modifier, onOpen: (ObjectRef) -> Unit, onMenu: (() -> Unit)? = null) {
    val details = LocalRowDetails.current[ref.nodeId].orEmpty()
    val takes = !LocalPicking.current || LocalTakesShare.current(ref)
    LevelTile(
        ref.name, details.takeIf { it.isNotEmpty() }?.joined(), LocalCovers.current[ref.nodeId]?.second, kindIcon(ref.kind),
        folder = false, modifier = modifier, enabled = takes, selected = LocalOpenObject.current == ref.nodeId, onMenu = onMenu,
    ) { onOpen(ref) }
}

@Composable
private fun ObjectScreen(ref: ObjectRef, tab: BarTab?) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Tile(kindIcon(ref.kind))
        Text(ref.name, style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        tab?.let { Text(tabLabel(it, ref.kind), style = MaterialTheme.typography.titleSmall, color = role(LocalPalette.current.muted), modifier = Modifier.padding(top = 4.dp)) }
        Text(stringResource(R.string.not_built), color = role(LocalPalette.current.muted), modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Empty() {
    Box(Modifier.fillMaxSize().padding(24.dp)) { Text(stringResource(R.string.list_empty), color = role(LocalPalette.current.muted)) }
}

@Composable
private fun appName(app: ApplicationId): String = say(me.raddatz.veyq.mobile.AppKinds.applicationWord(app))

/** The web's motifs (src/web/src/shared/ui/icons.ts) in Material Symbols: images, file, wallet. */
private fun appIcon(app: ApplicationId): ImageVector = when (app) {
    ApplicationId.MEMORIES -> Icons.Outlined.PhotoLibrary
    ApplicationId.DEPOT -> Icons.Outlined.InsertDriveFile
    ApplicationId.EXPENSES -> Icons.Outlined.AccountBalanceWallet
}

/** Each kind's motif as the web registers it: MEMORY images, FILE file, DEPOT_DROPBOX inbox, POT wallet, FOLDER folder. */
internal fun kindIcon(kind: String): ImageVector = when (kind) {
    "MEMORY", "CHAPTER" -> Icons.Outlined.PhotoLibrary
    "FILE" -> Icons.Outlined.InsertDriveFile
    "DEPOT_DROPBOX" -> Icons.Outlined.Inbox
    "EXPENSES_POT" -> Icons.Outlined.AccountBalanceWallet
    "SHARE_BOOKMARK" -> Icons.Outlined.Link
    else -> Icons.Outlined.Folder
}

/** A tab's icon; Depot's one tab names the object open, a file or a drop box ([kind]). */
private fun tabIcon(tab: BarTab, kind: String?): ImageVector = when (tab.key) {
    "memories.all" -> Icons.Outlined.PhotoLibrary
    "memories.map" -> Icons.Outlined.Map
    "expenses.overview" -> Icons.Outlined.BarChart
    "expenses.settings" -> Icons.Outlined.Settings
    "depot.file" -> if (kind == "DEPOT_DROPBOX") Icons.Outlined.Inbox else Icons.Outlined.InsertDriveFile
    else -> Icons.AutoMirrored.Outlined.List
}

@Composable
private fun tabLabel(tab: BarTab, kind: String?): String = say(me.raddatz.veyq.mobile.AppKinds.tabWord(tab, kind))

/** What each object's row says beside its name, by node id (`AppModel.rowDetails`). */
internal val LocalRowDetails = androidx.compose.runtime.compositionLocalOf<Map<String, List<String>>> { emptyMap() }

/** The shell's snackbar and its scope, where a page's action says what it did after the page itself may have left. */
internal class ShellNotices(val snackbar: androidx.compose.material3.SnackbarHostState, val scope: kotlinx.coroutines.CoroutineScope)

internal val LocalShellNotices = androidx.compose.runtime.staticCompositionLocalOf<ShellNotices?> { null }

/** Whether the shell is picking a place for what another app shared (ADR 0138). */
internal val LocalPicking = androidx.compose.runtime.compositionLocalOf { false }

/** Whether the pick mode lets an object be chosen (core's answer of its kind and this member's right there, `AppModel.takesShare`). */
internal val LocalTakesShare = androidx.compose.runtime.compositionLocalOf<(ObjectRef) -> Boolean> { { false } }

/**
 * The shell's banner while the server is out of reach (ADR 0140 decision 10, core's words: "Offline — content as of …"):
 * a strip under the top app bar, as Material shows a lasting state of the whole screen; every write is gone meanwhile.
 */
@Composable
private fun OfflineBanner(banner: me.raddatz.veyq.mobile.words.Notice, waiting: me.raddatz.veyq.mobile.words.Notice?) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(say(banner), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // how many writes made offline wait to be sent (core's `ShellModel.queuedBanner`)
            waiting?.let { Text(say(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("offline-waiting-count")) }
        }
    }
}

/**
 * The pick mode's banner: what waits and where it can go, with its cancel — and on a Memory's or a chapter's page or a
 * level that takes it, *Add here* (ADR 0138 decision 3), that place's own save (core's `AddHere`, which picks it).
 */
@Composable
private fun ShareBanner(
    incoming: me.raddatz.veyq.mobile.navigation.ShareInbox.Incoming, s: Screen.Signed, model: AppModel,
    snackbar: androidx.compose.material3.SnackbarHostState, onCancel: () -> Unit,
) {
    val palette = LocalPalette.current
    val page by model.addHerePage.collectAsStateWithLifecycle()
    // the page's rights and reach move with its state, the level's with the screen: asked again on each
    page?.changes?.collectAsStateWithLifecycle()?.value
    val addHere = model.addHereTarget(s.shell, page)
    val label = s.shell.folder?.name ?: spaceName(s.space)
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().background(role(palette.accentSoft)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (incoming.files.isEmpty()) stringResource(R.string.share_nothing)
                else pluralStringResource(R.plurals.share_pick, incoming.files.size, incoming.files.size),
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            if (incoming.refused > 0 && incoming.files.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.share_unread, incoming.refused, incoming.refused), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
            }
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.entry_cancel)) }
        if (addHere != null) {
            androidx.compose.material3.FilledTonalButton(onClick = {
                model.addHere(addHere, label)?.let { n -> scope.launch { snackbar.show(context, n) } }
            }) { Text(stringResource(R.string.share_add_here)) }
        }
    }
}

/** The drawer's sheet: over the content (a modal drawer), or beside it for good (the sidebar of an expanded window). */
@Composable
private fun DrawerSheet(
    permanent: Boolean, chrome: Brush, shape: androidx.compose.ui.graphics.Shape,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (permanent) {
        androidx.compose.material3.PermanentDrawerSheet(
            drawerContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.width(300.dp).background(chrome),
            content = content,
        )
    } else {
        ModalDrawerSheet(
            drawerContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            drawerShape = shape,
            modifier = Modifier.background(chrome, shape),
            content = content,
        )
    }
}

/** The object open in the list beside it, marked there (a wide window's list-detail). */
private val LocalOpenObject = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

/**
 * A wide window's list and detail (Material 3, ADR 0122): the chosen application's list on the left, a choice there taking
 * the open object's place on the right; with none open, the right side says to choose.
 */
@Composable
private fun ListBeside(s: Screen.Signed, model: AppModel) {
    val shell = s.shell
    val app = shell.application ?: return
    val palette = LocalPalette.current
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(LIST_PANE_WIDTH).fillMaxSize()) {
            // the list pane beside an open object stays rows (a 360 dp pane of tiles would be a strip)
            androidx.compose.runtime.CompositionLocalProvider(LocalOpenObject provides shell.openObject?.nodeId, LocalLevelView provides me.raddatz.veyq.mobile.navigation.ViewMode.LIST) {
                Registry.application(app)?.list?.let { list -> list(s, model, model::openBeside) }
                    ?: ArrangedListScreen(s.applicationList(app), model, onOpen = model::openBeside)
            }
        }
        androidx.compose.material3.VerticalDivider(color = role(palette.line))
        Box(Modifier.weight(1f).fillMaxSize()) {
            val open = shell.openObject
            if (open == null) {
                Text(say(me.raddatz.veyq.mobile.words.Msg.LIST_BESIDE_EMPTY), color = role(palette.muted), modifier = Modifier.align(Alignment.Center))
            } else {
                KindPages.of(open.kind)?.body?.invoke(open, shell.selectedTab, model) ?: ObjectScreen(open, shell.selectedTab)
            }
        }
    }
}

/** The list pane's width beside an object (Material 3's list-detail: a fixed list, the detail takes the rest). */
private val LIST_PANE_WIDTH = 360.dp


/** The drawer's "New" (core's `DrawerSection.NEW`): a button whose menu holds every entry of [creates], in its order. */
@Composable
private fun DrawerNew(creates: List<me.raddatz.veyq.mobile.AppKinds.Create>, onNew: (me.raddatz.veyq.mobile.AppKinds.Create) -> Unit) {
    val palette = LocalPalette.current
    var newMenu by remember { mutableStateOf(false) }
    Box(Modifier.padding(vertical = 6.dp)) {
        Button(
            onClick = { newMenu = true },
            colors = ButtonDefaults.buttonColors(containerColor = role(palette.primary), contentColor = role(palette.onFill)),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().testTag("drawer-new"),
        ) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.drawer_new))
        }
        androidx.compose.material3.DropdownMenu(newMenu, { newMenu = false }) {
            creates.forEach { c ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(say(c.word)) }, onClick = { newMenu = false; onNew(c) })
            }
        }
    }
}
