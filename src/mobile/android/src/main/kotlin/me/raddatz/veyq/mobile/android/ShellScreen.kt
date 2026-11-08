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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.material3.TopAppBarDefaults
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
    val drawer = rememberDrawerState(if (s.shell.application == null && s.shell.openObject == null) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val shell = s.shell
    val palette = LocalPalette.current
    fun closeDrawerThen(f: () -> Unit) { f(); scope.launch { drawer.close() } }

    BackHandler(enabled = shell.searching || shell.openObject != null) {
        if (shell.searching) model.search(false) else model.close()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = { Drawer(s, onChoose = { closeDrawerThen { model.choose(it) } }, onOpen = { closeDrawerThen { model.open(it) } }, onSignOut = model::signOut) },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (!shell.searching) {
                    TopAppBar(
                        title = { Text(shell.openObject?.name ?: shell.application?.let { appName(it) } ?: stringResource(R.string.drawer_spaces), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        navigationIcon = {
                            if (shell.openObject != null) {
                                IconButton(onClick = model::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                            } else {
                                IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, stringResource(R.string.drawer_open)) }
                            }
                        },
                    )
                }
            },
            bottomBar = {
                NavigationBar(containerColor = role(palette.surface)) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = role(palette.accent),
                        selectedTextColor = role(palette.accent),
                        indicatorColor = role(palette.accentSoft),
                        unselectedIconColor = role(palette.muted),
                        unselectedTextColor = role(palette.muted),
                    )
                    for (tab in shell.barTabs) {
                        NavigationBarItem(
                            selected = !shell.searching && tab == shell.selectedTab,
                            onClick = { model.select(tab) },
                            icon = { Icon(tabIcon(tab), null) },
                            label = { Text(tabLabel(tab)) },
                            colors = colors,
                        )
                    }
                    NavigationBarItem(
                        selected = shell.searching,
                        onClick = { model.search(true) },
                        icon = { Icon(Icons.Outlined.Search, null) },
                        label = { Text(stringResource(R.string.tab_search)) },
                        colors = colors,
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    shell.searching -> SearchScreen(s.objects, onOpen = model::open, onClose = { model.search(false) })
                    shell.openObject != null -> ObjectScreen(shell.openObject!!, shell.selectedTab)
                    shell.application == null -> SpacesScreen(s, onOpen = model::open)
                    else -> ArrangedListScreen(s.applicationList(shell.application!!), onOpen = model::open)
                }
            }
        }
    }
}

@Composable
private fun Drawer(s: Screen.Signed, onChoose: (ApplicationId?) -> Unit, onOpen: (ObjectRef) -> Unit, onSignOut: () -> Unit) {
    val palette = LocalPalette.current
    // The web's sidebar: the surface, washed with a trace of the accent from the top (`--app-chrome-pattern`).
    val chrome = Brush.verticalGradient(listOf(role(palette.accentSoft), role(palette.surface)), endY = 900f)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
    ModalDrawerSheet(
        drawerContainerColor = androidx.compose.ui.graphics.Color.Transparent,
        drawerShape = shape,
        modifier = Modifier.background(chrome, shape),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Brand(Modifier.padding(start = 8.dp, top = 20.dp, bottom = 16.dp))
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = role(palette.primary), contentColor = role(palette.onFill)),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.drawer_new))
            }
            Section(stringResource(R.string.drawer_favorites))
            ObjectItems(s.drawer.favorites, s.shell.openObject, onOpen)
            Section(stringResource(R.string.drawer_recent))
            ObjectItems(s.drawer.recent, s.shell.openObject, onOpen)
            Section(stringResource(R.string.drawer_applications))
            for (app in s.applications) {
                Row(app = appIcon(app), label = appName(app), selected = s.shell.application == app && s.shell.openObject == null) { onChoose(app) }
            }
            Row(app = Icons.Outlined.Layers, label = stringResource(R.string.drawer_spaces), selected = s.shell.application == null && s.shell.openObject == null) { onChoose(null) }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = role(palette.line))
            Row(app = Icons.Outlined.Settings, label = stringResource(R.string.drawer_settings), selected = false) {}
            Row(app = Icons.AutoMirrored.Outlined.Logout, label = stringResource(R.string.drawer_sign_out), selected = false, onClick = onSignOut)
            Spacer(Modifier.height(12.dp))
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
private fun Section(title: String) {
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

@Composable
private fun ArrangedListScreen(list: ArrangedList, onOpen: (ObjectRef) -> Unit) {
    if (list.favorites.isEmpty() && list.recent.isEmpty() && list.all.isEmpty()) {
        Empty()
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        group(R.string.drawer_favorites, list.favorites, onOpen)
        group(R.string.drawer_recent, list.recent, onOpen)
        group(null, list.all, onOpen)
    }
}

@Composable
private fun ObjectRow(ref: ObjectRef, onOpen: (ObjectRef) -> Unit) {
    ListItem(
        headlineContent = { Text(ref.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        leadingContent = { Tile(kindIcon(ref.kind)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clip(MaterialTheme.shapes.medium).clickableRow { onOpen(ref) },
    )
}

private fun LazyListScope.group(title: Int?, refs: List<ObjectRef>, onOpen: (ObjectRef) -> Unit) {
    if (refs.isEmpty()) return
    if (title != null) item { Section(stringResource(title)) }
    items(refs, key = { "${title}-${it.scopeId}-${it.nodeId}" }) { ref -> ObjectRow(ref, onOpen) }
}

@Composable
private fun SpacesScreen(s: Screen.Signed, onOpen: (ObjectRef) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        for (space in s.spaces) {
            item(key = space.scopeId) { Section(space.name) }
            val inSpace = s.objects.filter { it.scopeId == space.scopeId }
            if (inSpace.isEmpty()) item(key = space.scopeId + "-empty") {
                Text(stringResource(R.string.list_empty), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted),
                    modifier = Modifier.padding(start = 8.dp))
            }
            items(inSpace, key = { it.scopeId + it.nodeId }) { ref -> ObjectRow(ref, onOpen) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(objects: List<ObjectRef>, onOpen: (ObjectRef) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(expanded) { if (!expanded) onClose() }
    val hits = remember(query, objects) { if (query.isBlank()) emptyList() else objects.filter { it.name.contains(query.trim(), ignoreCase = true) } }
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
        tab?.let { Text(tabLabel(it), style = MaterialTheme.typography.titleSmall, color = role(LocalPalette.current.muted), modifier = Modifier.padding(top = 4.dp)) }
        Text(stringResource(R.string.not_built), color = role(LocalPalette.current.muted), modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Empty() {
    Box(Modifier.fillMaxSize().padding(24.dp)) { Text(stringResource(R.string.list_empty), color = role(LocalPalette.current.muted)) }
}

@Composable
private fun appName(app: ApplicationId): String = stringResource(
    when (app) {
        ApplicationId.MEMORIES -> R.string.app_memories
        ApplicationId.DEPOT -> R.string.app_depot
        ApplicationId.EXPENSES -> R.string.app_expenses
    },
)

/** The web's motifs (src/web/src/shared/ui/icons.ts) in Material Symbols: images, file, wallet. */
private fun appIcon(app: ApplicationId): ImageVector = when (app) {
    ApplicationId.MEMORIES -> Icons.Outlined.PhotoLibrary
    ApplicationId.DEPOT -> Icons.Outlined.InsertDriveFile
    ApplicationId.EXPENSES -> Icons.Outlined.AccountBalanceWallet
}

/** Each kind's motif as the web registers it: MEMORY images, FILE file, DEPOT_DROPBOX inbox, POT wallet, FOLDER folder. */
private fun kindIcon(kind: String): ImageVector = when (kind) {
    "MEMORY", "CHAPTER" -> Icons.Outlined.PhotoLibrary
    "FILE" -> Icons.Outlined.InsertDriveFile
    "DEPOT_DROPBOX" -> Icons.Outlined.Inbox
    "EXPENSES_POT" -> Icons.Outlined.AccountBalanceWallet
    "SHARE_BOOKMARK" -> Icons.Outlined.Link
    else -> Icons.Outlined.Folder
}

private fun tabIcon(tab: BarTab): ImageVector = when (tab.key) {
    "memories.all" -> Icons.Outlined.PhotoLibrary
    "memories.map" -> Icons.Outlined.Map
    "expenses.overview" -> Icons.Outlined.BarChart
    "expenses.settings" -> Icons.Outlined.Settings
    "depot.files" -> Icons.Outlined.InsertDriveFile
    else -> Icons.AutoMirrored.Outlined.List
}

@Composable
private fun tabLabel(tab: BarTab): String = stringResource(
    when (tab.key) {
        "memories.all" -> R.string.tab_memories_all
        "memories.map" -> R.string.tab_memories_map
        "depot.files" -> R.string.tab_depot_files
        "expenses.entries" -> R.string.tab_expenses_entries
        "expenses.overview" -> R.string.tab_expenses_overview
        "expenses.settings" -> R.string.tab_expenses_settings
        else -> R.string.not_built
    },
)
