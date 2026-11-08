package me.raddatz.veyq.mobile.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ArrangedList
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.ObjectRef

/**
 * The shell (ADR 0122): the drawer chooses what the app is in; the bar at the bottom belongs to the chosen
 * application — its own places, or those of its open object — and ends with Search everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(s: Screen.Signed, model: AppModel) {
    val drawer = rememberDrawerState(if (s.shell.application == null && s.shell.openObject == null) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val shell = s.shell
    fun closeDrawerThen(f: () -> Unit) { f(); scope.launch { drawer.close() } }

    BackHandler(enabled = shell.searching || shell.openObject != null) {
        if (shell.searching) model.search(false) else model.close()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = { Drawer(s, onChoose = { closeDrawerThen { model.choose(it) } }, onOpen = { closeDrawerThen { model.open(it) } }, onSignOut = model::signOut) },
    ) {
        Scaffold(
            topBar = {
                if (!shell.searching) {
                    TopAppBar(
                        title = { Text(shell.openObject?.name ?: shell.application?.let { appName(it) } ?: stringResource(R.string.drawer_spaces)) },
                        navigationIcon = {
                            if (shell.openObject != null && shell.shape?.barLevel != null) {
                                IconButton(onClick = model::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                            } else {
                                IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, stringResource(R.string.drawer_open)) }
                            }
                        },
                    )
                }
            },
            bottomBar = {
                NavigationBar {
                    for (tab in shell.barTabs) {
                        NavigationBarItem(
                            selected = !shell.searching && tab == shell.selectedTab,
                            onClick = { model.select(tab) },
                            icon = { Icon(tabIcon(tab), null) },
                            label = { Text(tabLabel(tab)) },
                        )
                    }
                    NavigationBarItem(
                        selected = shell.searching,
                        onClick = { model.search(true) },
                        icon = { Icon(Icons.Filled.Search, null) },
                        label = { Text(stringResource(R.string.tab_search)) },
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
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(12.dp))
            NavigationDrawerItem(label = { Text(stringResource(R.string.drawer_new)) }, icon = { Icon(Icons.Filled.Add, null) }, selected = false, onClick = {})
            Section(stringResource(R.string.drawer_favorites))
            ObjectItems(s.drawer.favorites, s.shell.openObject, onOpen)
            Section(stringResource(R.string.drawer_recent))
            ObjectItems(s.drawer.recent, s.shell.openObject, onOpen)
            Section(stringResource(R.string.drawer_applications))
            for (app in s.applications) {
                NavigationDrawerItem(
                    label = { Text(appName(app)) }, icon = { Icon(appIcon(app), null) },
                    selected = s.shell.application == app && s.shell.openObject == null, onClick = { onChoose(app) },
                )
            }
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.drawer_spaces)) }, icon = { Icon(Icons.Filled.Workspaces, null) },
                selected = s.shell.application == null && s.shell.openObject == null, onClick = { onChoose(null) },
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            NavigationDrawerItem(label = { Text(stringResource(R.string.drawer_settings)) }, icon = { Icon(Icons.Filled.Settings, null) }, selected = false, onClick = {})
            NavigationDrawerItem(label = { Text(stringResource(R.string.drawer_sign_out)) }, icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) }, selected = false, onClick = onSignOut)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun ObjectItems(refs: List<ObjectRef>, open: ObjectRef?, onOpen: (ObjectRef) -> Unit) {
    if (refs.isEmpty()) {
        Text(stringResource(R.string.drawer_nothing_yet), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
    }
    for (ref in refs) {
        NavigationDrawerItem(
            label = { Text(ref.name) }, icon = { Icon(appIcon(ref.application), null) },
            selected = open?.nodeId == ref.nodeId && open.scopeId == ref.scopeId, onClick = { onOpen(ref) },
        )
    }
}

@Composable
private fun ArrangedListScreen(list: ArrangedList, onOpen: (ObjectRef) -> Unit) {
    if (list.favorites.isEmpty() && list.recent.isEmpty() && list.all.isEmpty()) {
        Empty()
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        group(R.string.drawer_favorites, list.favorites, onOpen)
        group(R.string.drawer_recent, list.recent, onOpen)
        group(null, list.all, onOpen)
    }
}

private fun LazyListScope.group(title: Int?, refs: List<ObjectRef>, onOpen: (ObjectRef) -> Unit) {
    if (refs.isEmpty()) return
    if (title != null) item { Section(stringResource(title)) }
    items(refs, key = { "${title}-${it.scopeId}-${it.nodeId}" }) { ref ->
        ListItem(headlineContent = { Text(ref.name) }, leadingContent = { Icon(appIcon(ref.application), null) }, modifier = Modifier.clickableRow { onOpen(ref) })
    }
}

@Composable
private fun SpacesScreen(s: Screen.Signed, onOpen: (ObjectRef) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        for (space in s.spaces) {
            item(key = space.scopeId) { Section(space.name) }
            val inSpace = s.objects.filter { it.scopeId == space.scopeId }
            if (inSpace.isEmpty()) item(key = space.scopeId + "-empty") {
                Text(stringResource(R.string.list_empty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
            }
            items(inSpace, key = { it.scopeId + it.nodeId }) { ref ->
                ListItem(headlineContent = { Text(ref.name) }, leadingContent = { Icon(appIcon(ref.application), null) }, modifier = Modifier.clickableRow { onOpen(ref) })
            }
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
        LazyColumn {
            items(hits, key = { it.scopeId + it.nodeId }) { ref ->
                ListItem(headlineContent = { Text(ref.name) }, leadingContent = { Icon(appIcon(ref.application), null) }, modifier = Modifier.clickableRow { onOpen(ref) })
            }
        }
    }
}

@Composable
private fun ObjectScreen(ref: ObjectRef, tab: BarTab?) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(ref.name, style = MaterialTheme.typography.headlineSmall)
        tab?.let { Text(tabLabel(it), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        Text(stringResource(R.string.not_built), modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Empty() {
    Box(Modifier.fillMaxSize().padding(24.dp)) { Text(stringResource(R.string.list_empty)) }
}

@Composable
private fun appName(app: ApplicationId): String = stringResource(
    when (app) {
        ApplicationId.MEMORIES -> R.string.app_memories
        ApplicationId.DEPOT -> R.string.app_depot
        ApplicationId.EXPENSES -> R.string.app_expenses
    },
)

private fun appIcon(app: ApplicationId): ImageVector = when (app) {
    ApplicationId.MEMORIES -> Icons.Filled.PhotoLibrary
    ApplicationId.DEPOT -> Icons.Filled.Folder
    ApplicationId.EXPENSES -> Icons.Filled.Receipt
}

private fun tabIcon(tab: BarTab): ImageVector = when (tab.key) {
    "memories.map" -> Icons.Filled.Map
    "expenses.overview" -> Icons.Outlined.BarChart
    "expenses.settings" -> Icons.Filled.Settings
    "depot.files" -> Icons.Outlined.Description
    else -> Icons.AutoMirrored.Filled.List
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
