package me.raddatz.veyq.mobile.android

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Map
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
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
    // on an expanded one the drawer stays open as the sidebar.
    val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val rail = widthDp >= MEDIUM_WIDTH_DP
    val wide = widthDp >= EXPANDED_WIDTH_DP
    val drawer = rememberDrawerState(if (s.shell.drawerOpensAtStart && !wide) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    val shell = s.shell
    val potState by model.pot.collectAsStateWithLifecycle()
    val rowDetails by model.rowDetails.collectAsStateWithLifecycle()
    val avatars by model.avatars.collectAsStateWithLifecycle()
    val incoming by model.incoming.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    /** The space whose trash is open. */
    var trashOf by remember { mutableStateOf<String?>(null) }
    var uploadShareAt by remember { mutableStateOf<String?>(null) }
    fun closeDrawerThen(f: () -> Unit) { f(); scope.launch { drawer.close() } }

    // what the model says (an upload's refusal, its "Replace anyway"): one snackbar each
    androidx.compose.runtime.LaunchedEffect(model) {
        model.notices.collect { n ->
            val answer = snackbar.showSnackbar(n.text, actionLabel = n.action, withDismissAction = n.action != null, duration = androidx.compose.material3.SnackbarDuration.Long)
            if (answer == androidx.compose.material3.SnackbarResult.ActionPerformed) n.run?.let { scope.launch { it() } }
        }
    }
    // "Send a copy…": what happens is said first — the copy leaves veyq decrypted —, then the system's share sheet
    val sending by model.sending.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val sendFailed = stringResource(R.string.download_failed, sending?.name ?: "")
    sending?.let { node ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { model.askToSend(null) },
            title = { Text(stringResource(R.string.file_send_title)) },
            text = { Text(stringResource(R.string.file_send_notice, node.name)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    model.askToSend(null)
                    scope.launch {
                        val copy = model.copyForSending(node)
                        if (copy == null) {
                            snackbar.showSnackbar(sendFailed)
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

    BackHandler(enabled = shell.searching || shell.canGoBack) {
        if (shell.searching) model.search(false) else model.goBack()
    }

    val covers by model.covers.collectAsStateWithLifecycle()
    androidx.compose.runtime.CompositionLocalProvider(LocalRowDetails provides rowDetails, LocalAvatars provides avatars, LocalPicking provides (incoming != null), LocalCovers provides covers, LocalShellNotices provides ShellNotices(snackbar, scope)) {
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
            permanent = wide,
        )
    }
    // the bar's places, drawn as a bar at the bottom or a rail at the side
    val places = shell.barTabs.map { tab ->
        Place(!shell.searching && tab == shell.selectedTab, { model.select(tab) }, tabIcon(tab, shell.openObject?.kind), tabLabel(tab, shell.openObject?.kind))
    } + Place(shell.searching, { model.search(true) }, Icons.Outlined.Search, stringResource(R.string.tab_search))
    val body: @Composable () -> Unit = {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
            topBar = {
                Column {
                if (!shell.searching) {
                    // The node on screen — an open object, or the folder Home shows — headed by core for every kind (the
                    // web's `NodePage` + `NodeHeader`): its name, what its kind's page contributes, core's actions.
                    val space = shell.space
                    val headed = shell.openObject?.at
                        ?: if (shell.application == null && space != null) shell.folderAt(space) else null
                    val contribution = headed?.let { KindPages.of(it.kind)?.header?.invoke(it, model) }
                    TopAppBar(
                        title = {
                            when {
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
                            if (headed != null && contribution != null) {
                                NodeHeaderActions(headed, contribution, model, snackbar, scope, spaceName(s.spaces.firstOrNull { it.scopeId == headed.scopeId }))
                            }
                            // The space's trash, from its Home (the web's level toolbar): every application's nodes.
                            val open = shell.openObject
                            if (open == null && shell.application == null && !shell.searching && space != null) {
                                // the top level is no link's root: its share offers "Add files only" alone (the web's level share)
                                val rules = model.linkRules()
                                if (shell.folder == null && rules.on && rules.dropBox) {
                                    IconButton(onClick = { uploadShareAt = space }) { Icon(Icons.Outlined.Share, stringResource(R.string.share_role_uploader)) }
                                }
                                IconButton(onClick = { trashOf = space }) { Icon(Icons.Outlined.Delete, stringResource(R.string.trash_title)) }
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
                incoming?.let { ShareBanner(it, onCancel = model::cancelShare) }
                }
            },
            bottomBar = {
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
            },
        ) { padding ->
            // the node on screen's head, which its body places first (core's, for every kind)
            val paged = shell.openObject?.at
                ?: shell.space?.takeIf { shell.application == null && !shell.searching }?.let { shell.folderAt(it) }
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
            Box(Modifier.weight(1f).fillMaxSize()) {
              androidx.compose.runtime.CompositionLocalProvider(LocalNodeHero provides hero) {
                when {
                    // a wide window: the application's list and its open object side by side (Material 3's list-detail)
                    wide && shell.showsListBeside -> ListBeside(s, model)
                    shell.searching -> SearchScreen(s.objects, onOpen = model::open, onClose = { model.search(false) })
                    shell.openObject != null -> KindPages.of(shell.openObject!!.kind)?.body?.invoke(shell.openObject!!, shell.selectedTab, model)
                        ?: ObjectScreen(shell.openObject!!, shell.selectedTab)
                    shell.application == null -> HomeScreen(s, model, onEnter = model::enter, onOpen = model::open, onFilter = model::filter)
                    shell.application == ApplicationId.EXPENSES -> ExpensesList(s, model)
                    else -> ArrangedListScreen(s.applicationList(shell.application!!), onOpen = model::open)
                }
              }
                if (s.switching) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                UploadTray(model)
            }
            }
        }
        trashOf?.let { SpaceTrashSheet(it, model) { trashOf = null } }
        uploadShareAt?.let { TopLevelUploadSheet(it, model) { uploadShareAt = null } }
    }
    if (wide) androidx.compose.material3.PermanentNavigationDrawer(drawerContent = drawerContent) { body() }
    else ModalNavigationDrawer(drawerState = drawer, drawerContent = drawerContent) { body() }
    }
}

/** Material 3's window width classes (dp): a medium window from 600, an expanded one from 840. */
private const val MEDIUM_WIDTH_DP = 600
private const val EXPANDED_WIDTH_DP = 840

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
    var creatingPot by remember { mutableStateOf(false) }
    // what the drawer's "New" asked for
    val asked by model.newRequest.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(asked) {
        when (asked) {
            "folder" -> naming = true
            "pot" -> creatingPot = true
        }
        if (asked != null) model.newRequestTaken()
    }
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
    // The level's "New" (the web's menu): a folder here, a Pot here, or files uploaded here.
    // nothing is created in a guest's folder (the web's `folderRights`: no "New" there)
    CreateAction(stringResource(R.string.create_new), shown = model.canCreateHere, onClick = {}, menu = { close ->
        me.raddatz.veyq.mobile.AppKinds.homeCreates.forEach { c ->
            androidx.compose.material3.DropdownMenuItem(text = { Text(say(c.word)) }, onClick = {
                close()
                when (c) {
                    me.raddatz.veyq.mobile.AppKinds.Create.FOLDER -> naming = true
                    me.raddatz.veyq.mobile.AppKinds.Create.POT -> creatingPot = true
                    me.raddatz.veyq.mobile.AppKinds.Create.UPLOAD_FILES -> pickFiles.launch(arrayOf("*/*"))
                    me.raddatz.veyq.mobile.AppKinds.Create.UPLOAD_FOLDER -> pickFolder.launch(null)
                }
            })
        }
    }) { list, bottom ->
    Column(Modifier.fillMaxSize()) {
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
        val groups = s.levelGroups
        val hero = LocalNodeHero.current
        run {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottom)) {
                item(key = "hero") { hero(8.dp) }
                if (groups.isEmpty()) item(key = "empty") { Empty() }
                // the folders apart from the rest, as the web's level view (core's `Home.groups`)
                for (group in groups) {
                    val heading = group.heading
                    if (heading != null) item(key = "group-${group.folders}") { Box(Modifier.padding(horizontal = 8.dp)) { Section(say(heading)) } }
                    items(group.nodes, key = { it.scopeId + it.nodeId }) { node ->
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
                                modifier = Modifier.clip(MaterialTheme.shapes.medium).clickableRow { onEnter(folder) },
                            )
                        } else {
                            node.asObject()?.let { ObjectRow(it, onOpen) }
                        }
                    }
                }
            }
        }
    }
    }
    if (naming) FolderNameDialog(onClose = { naming = false }) { name -> model.createFolder(name) }
    if (creatingPot) PotCreateSheet(s, model) { creatingPot = false }
}

/** Core's name dialog for a new folder (`FolderNameDialog`): the name, trimmed on save; a refusal says why and stays. */
@Composable
private fun FolderNameDialog(onClose: () -> Unit, onCreate: suspend (String) -> String?) {
    var name by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text(stringResource(R.string.folder_new)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                name, { name = it; problem = null }, singleLine = true, label = { Text(stringResource(R.string.folder_name)) },
                isError = problem != null, supportingText = problem?.let { { Text(it) } },
            )
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
            for (server in s.servers) {
                Text(server.label, style = VeyqType.sectionLabel, color = role(palette.muted), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                for (tenant in server.tenants) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(tenant.name) },
                        leadingIcon = { TenantAvatar(tenant.name, small = true) },
                        trailingIcon = { if (tenant == s.tenant && server.label == s.server) Icon(Icons.Filled.Check, null) },
                        onClick = { open = false; onSwitch(server.account, tenant) },
                    )
                }
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
    /** The sidebar's "New": what a level creates ("folder", "pot"), opened on Home. */
    onNew: (String) -> Unit,
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
                // the web's sidebar "New": what a level creates, opened on Home
                var newMenu by remember { mutableStateOf(false) }
                Box {
                    Button(
                        onClick = { newMenu = true },
                        colors = ButtonDefaults.buttonColors(containerColor = role(palette.primary), contentColor = role(palette.onFill)),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.drawer_new))
                    }
                    androidx.compose.material3.DropdownMenu(newMenu, { newMenu = false }) {
                        me.raddatz.veyq.mobile.AppKinds.drawerCreates.forEach { c ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(say(c.word)) }, onClick = {
                                newMenu = false
                                onNew(if (c == me.raddatz.veyq.mobile.AppKinds.Create.POT) "pot" else "folder")
                            })
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(app = Icons.Outlined.Home, label = stringResource(R.string.drawer_home), selected = s.shell.isHome) { onChoose(null) }
                Section(stringResource(R.string.drawer_favorites))
                ObjectItems(s.drawer.favorites, s.shell.openObject, onOpen)
                Section(stringResource(R.string.drawer_recent))
                ObjectItems(s.drawer.recent, s.shell.openObject, onOpen)
                Section(stringResource(R.string.drawer_applications))
                for (app in s.applications) {
                    Row(app = appIcon(app), label = appName(app), selected = s.shell.application == app && s.shell.openObject == null) { onChoose(app) }
                }
                Spacer(Modifier.height(12.dp))
            }
            Column(Modifier.padding(horizontal = 14.dp)) {
                HorizontalDivider(Modifier.padding(bottom = 8.dp), color = role(palette.line))
                Row(app = Icons.Outlined.Settings, label = stringResource(R.string.drawer_settings), selected = false) {}
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
    if (refs.isEmpty()) {
        Text(stringResource(R.string.drawer_nothing_yet), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted),
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
    }
    for (ref in refs) {
        Row(app = kindIcon(ref.kind), label = ref.name, selected = open?.nodeId == ref.nodeId && open.scopeId == ref.scopeId) { onOpen(ref) }
    }
}

/** Expenses' own list (its Pots), with "New pot" (uc.expenses.create-pot). */
@Composable
private fun ExpensesList(s: Screen.Signed, model: AppModel, onOpen: (ObjectRef) -> Unit = model::open) {
    var creating by remember { mutableStateOf(false) }
    CreateAction(stringResource(R.string.create_pot), shown = s.spaces.isNotEmpty(), onClick = { creating = true }) { list, bottom ->
        ArrangedListScreen(s.applicationList(ApplicationId.EXPENSES), onOpen = onOpen, state = list, bottom = bottom)
    }
    if (creating) PotCreateSheet(s, model) { creating = false }
}

@Composable
private fun ArrangedListScreen(
    list: ArrangedList,
    onOpen: (ObjectRef) -> Unit,
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    /** Room below the last row: a floating button over the list keeps it clear (Material 3). */
    bottom: androidx.compose.ui.unit.Dp = 0.dp,
) {
    if (list.favorites.isEmpty() && list.recent.isEmpty() && list.all.isEmpty()) {
        Empty()
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottom)) {
        group(R.string.drawer_favorites, list.favorites, onOpen)
        group(R.string.drawer_recent, list.recent, onOpen)
        // the rest has no heading: under Favorites or Recent a divider sets it apart, else its rows ran on as theirs
        if (list.all.isNotEmpty() && (list.favorites.isNotEmpty() || list.recent.isNotEmpty())) {
            item { androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
        group(null, list.all, onOpen)
    }
}

/** Each listed node's cover, by node id (the model's), as the rows draw them. */
private val LocalCovers = androidx.compose.runtime.staticCompositionLocalOf<Map<String, Pair<String, androidx.compose.ui.graphics.ImageBitmap>>> { emptyMap() }

@Composable
private fun ObjectRow(ref: ObjectRef, onOpen: (ObjectRef) -> Unit) {
    val details = LocalRowDetails.current[ref.nodeId].orEmpty()
    // Picking a place for what was shared: only a kind that takes it can be chosen (ADR 0129 decision 1).
    val takes = !LocalPicking.current || me.raddatz.veyq.mobile.AppKinds.takesShared(ref.kind)
    ListItem(
        headlineContent = { Text(ref.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        // The application's facts about the object (a Pot: its currency), left out when it has none.
        supportingContent = details.takeIf { it.isNotEmpty() }?.let { { Text(it.joinToString(" · "), color = role(LocalPalette.current.muted)) } },
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
        modifier = Modifier.clip(MaterialTheme.shapes.medium).alpha(if (takes) 1f else 0.38f).let { if (takes) it.clickableRow { onOpen(ref) } else it },
    )
}

private fun LazyListScope.group(title: Int?, refs: List<ObjectRef>, onOpen: (ObjectRef) -> Unit) {
    if (refs.isEmpty()) return
    if (title != null) item { Section(stringResource(title)) }
    items(refs, key = { "${title}-${it.scopeId}-${it.nodeId}" }) { ref -> ObjectRow(ref, onOpen) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(objects: List<ObjectRef>, onOpen: (ObjectRef) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(expanded) { if (!expanded) onClose() }
    val hits = remember(query, objects) { me.raddatz.veyq.mobile.navigation.ShellWords.search(objects, query) }
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query, onQueryChange = { query = it }, onSearch = {}, expanded = expanded, onExpandedChange = { expanded = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.search_close)) } },
            )
        },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        LazyColumn { items(hits, key = { it.scopeId + it.nodeId }) { ref -> ObjectRow(ref, onOpen) } }
    }
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
    "depot.file" -> if (kind == me.raddatz.veyq.mobile.depot.DepotFiles.DROPBOX) Icons.Outlined.Inbox else Icons.Outlined.InsertDriveFile
    else -> Icons.AutoMirrored.Outlined.List
}

@Composable
private fun tabLabel(tab: BarTab, kind: String?): String = say(me.raddatz.veyq.mobile.AppKinds.tabWord(tab, kind))

/** What each object's row says beside its name, by node id (`AppModel.rowDetails`). */
internal val LocalRowDetails = androidx.compose.runtime.compositionLocalOf<Map<String, List<String>>> { emptyMap() }

/** The shell's snackbar and its scope, where a page's action says what it did after the page itself may have left. */
internal class ShellNotices(val snackbar: androidx.compose.material3.SnackbarHostState, val scope: kotlinx.coroutines.CoroutineScope)

internal val LocalShellNotices = androidx.compose.runtime.staticCompositionLocalOf<ShellNotices?> { null }

/** Whether the shell is picking a place for what another app shared (ADR 0129). */
internal val LocalPicking = androidx.compose.runtime.compositionLocalOf { false }

/** The pick mode's banner: what waits and where it can go, with its cancel. */
@Composable
private fun ShareBanner(incoming: me.raddatz.veyq.mobile.expenses.ShareInbox.Incoming, onCancel: () -> Unit) {
    val palette = LocalPalette.current
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
            if (incoming.refused > 0) {
                Text(pluralStringResource(R.plurals.share_refused, incoming.refused, incoming.refused), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
            }
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.entry_cancel)) }
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
            androidx.compose.runtime.CompositionLocalProvider(LocalOpenObject provides shell.openObject?.nodeId) {
                if (app == ApplicationId.EXPENSES) ExpensesList(s, model, onOpen = model::openBeside)
                else ArrangedListScreen(s.applicationList(app), onOpen = model::openBeside)
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
