package me.raddatz.veyq.mobile.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.navigation.ArrangedList
import me.raddatz.veyq.mobile.navigation.DrawerSection
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.words.Msg

/**
 * The shell on a TV (ADR 0159 decision 6): the core's drawer content — Home, the sections with something in them
 * (`Lists.drawerSections`), Search, Settings, and *Lock* in the switcher's place (a TV opens one account at a time, ADR
 * 0159 decision 4) — in Compose for TV's navigation drawer, and the screen it chose beside it: Home's level and every
 * application's list as tiles, an open object as its kind's TV page (the registry's `tv`) or its page, a person's page,
 * a kept link's. Everything is the core's plans, which on a TV hold no authoring and no download (decision 2); nothing is
 * filtered here.
 */
@Composable
internal fun TvShell(s: Screen.Signed, model: AppModel) {
    val shell = s.shell
    val scope = rememberCoroutineScope()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    var settings by remember { mutableStateOf(false) }
    val rowDetails by model.rowDetails.collectAsStateWithLifecycle()
    val avatars by model.avatars.collectAsStateWithLifecycle()
    val covers by model.covers.collectAsStateWithLifecycle()
    val levelView by model.levelView.collectAsStateWithLifecycle()
    val listStates = remember { ListStates() }
    // what the model says: one snackbar each, as on the phone
    LaunchedEffect(model) {
        model.notices.collect { n ->
            val answer = snackbar.show(n.text, n.kind, n.action)
            if (answer == androidx.compose.material3.SnackbarResult.ActionPerformed) n.run?.let { scope.launch { it() } }
            n.done?.invoke()
        }
    }
    BackHandler(enabled = settings || shell.searching || shell.canGoBack) {
        when {
            settings -> settings = false
            shell.searching -> model.search(false)
            else -> model.goBack()
        }
    }
    fun go(f: () -> Unit) {
        settings = false
        if (shell.searching) model.search(false)
        f()
    }
    CompositionLocalProvider(
        LocalRowDetails provides rowDetails, LocalAvatars provides avatars, LocalCovers provides covers, LocalLevelView provides levelView,
        LocalShellNotices provides ShellNotices(snackbar, scope), LocalListStates provides listStates,
    ) {
        NavigationDrawer(
            modifier = Modifier.testTag("tv-shell"),
            drawerContent = {
                Column(
                    Modifier.fillMaxHeight().padding(12.dp).verticalScroll(rememberScrollState()).testTag("tv-drawer"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DrawerItem(Icons.Outlined.Home, stringResource(R.string.drawer_home), !settings && !shell.searching && shell.isHome) { go { model.choose(null) } }
                    DrawerItem(Icons.Outlined.Search, stringResource(R.string.tab_search), shell.searching, "tv-drawer-search") { settings = false; model.search(true) }
                    // only the sections with something in them (core's `Lists.drawerSections`), every one drawn
                    for (section in s.drawerSections) when (section) {
                        DrawerSection.NEW -> for (c in model.drawerCreates()) DrawerItem(Icons.Outlined.Folder, say(c.word), false) { go { model.requestNew(c) } }
                        DrawerSection.FAVORITES -> for (ref in s.drawer.favorites) DrawerItem(kindIcon(ref.kind), ref.name, shell.openObject == ref) { go { model.open(ref) } }
                        DrawerSection.RECENT -> for (ref in s.drawer.recent) DrawerItem(kindIcon(ref.kind), ref.name, shell.openObject == ref) { go { model.open(ref) } }
                        DrawerSection.APPLICATIONS -> for (app in s.applications) {
                            DrawerItem(appIcon(app), appName(app), !settings && shell.application == app && shell.openObject == null, "tv-drawer-app") { go { model.choose(app) } }
                        }
                    }
                    DrawerItem(Icons.Outlined.Settings, stringResource(R.string.drawer_settings), settings, "tv-drawer-settings") { settings = true }
                    // the switcher's place on a TV: the account closes and the picker shows (ADR 0159 decision 4)
                    DrawerItem(Icons.Outlined.Lock, stringResource(R.string.tv_lock), false, "tv-drawer-lock", onClick = model::lock)
                }
            },
        ) {
            Column(Modifier.fillMaxSize().padding(start = 24.dp, top = 24.dp, end = 48.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        settings -> TvSettings(model)
                        shell.searching -> TvSearch(model)
                        shell.person != null -> PersonScreen(shell.person!!, model)
                        shell.bookmark != null -> BookmarkScreen(shell.bookmark!!, model)
                        shell.openObject != null -> TvObject(shell.openObject!!, s, model, snackbar)
                        shell.application == null -> TvHome(s, model)
                        else -> TvArranged(appName(shell.application!!), s.applicationList(shell.application!!), model)
                    }
                    androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                }
                // the music bar while a queue is loaded (ADR 0142 decision 5): it plays on beside every page
                TvMusicBar(model)
            }
        }
    }
}

/**
 * The music bar on a TV (ADR 0142 decision 5, the phone's [MusicBar] for the remote): the title, previous, play/pause,
 * next and close as buttons the focus reaches, the position shown, not a slider — a slider takes the remote's left and
 * right (and up and down) for itself, and the focus could not leave it (measured on the TV emulator).
 */
@Composable
private fun TvMusicBar(model: AppModel) {
    val player = model.music.collectAsStateWithLifecycle().value ?: return
    val state by player.state.collectAsStateWithLifecycle()
    val now = state.nowPlaying ?: return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("tv-music"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.MusicNote, null)
        Column(Modifier.weight(1f)) {
            Text(now.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            val total = now.durationMs.coerceAtLeast(1)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { (now.positionMs.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        androidx.tv.material3.IconButton(onClick = player::previous) { Icon(Icons.Filled.SkipPrevious, say(Msg.MUSIC_PREVIOUS)) }
        if (now.playing) androidx.tv.material3.IconButton(onClick = player::pause) { Icon(Icons.Filled.Pause, say(Msg.MUSIC_PAUSE)) }
        else androidx.tv.material3.IconButton(onClick = player::resume) { Icon(Icons.Filled.PlayArrow, say(Msg.MUSIC_RESUME)) }
        androidx.tv.material3.IconButton(onClick = player::next) { Icon(Icons.Filled.SkipNext, say(Msg.MUSIC_NEXT)) }
        androidx.tv.material3.IconButton(onClick = player::stop) { Icon(Icons.Filled.Close, say(Msg.MUSIC_CLOSE)) }
    }
}

/** One entry of the drawer: its icon always, its label while the drawer is open (Compose for TV's own behaviour). */
@Composable
private fun androidx.tv.material3.NavigationDrawerScope.DrawerItem(
    icon: ImageVector, label: String, selected: Boolean, tag: String = "tv-drawer-item", onClick: () -> Unit,
) {
    NavigationDrawerItem(
        selected = selected, onClick = onClick, leadingContent = { Icon(icon, null) },
        modifier = Modifier.testTag(tag),
    ) { Text(label, maxLines = 1) }
}

/**
 * An open object: its kind's TV page where the application registered one (a Memory, a file), else its page, under its
 * name and the actions core's header plan holds on a TV (the plan drawn whole by [NodeHeaderActions]) and its bar's
 * places as tabs (a Pot's entries and overview).
 */
@Composable
private fun TvObject(ref: ObjectRef, s: Screen.Signed, model: AppModel, snackbar: androidx.compose.material3.SnackbarHostState) {
    val shell = s.shell
    val page = KindPages.of(ref.kind)
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(ref.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).testTag("tv-title"), maxLines = 1)
            val contribution = page?.header?.invoke(ref.at, model)
            if (contribution != null) NodeHeaderActions(ref.at, contribution, model, snackbar, scope)
        }
        val tv = page?.tv
        if (tv != null) {
            tv(ref, model)
            return@Column
        }
        val tabs = shell.barTabs
        if (tabs.size > 1) {
            val selected = tabs.indexOf(shell.selectedTab).coerceAtLeast(0)
            TabRow(selectedTabIndex = selected, modifier = Modifier.padding(bottom = 8.dp)) {
                tabs.forEachIndexed { i, tab ->
                    Tab(selected = i == selected, onFocus = { model.select(tab) }, onClick = { model.select(tab) }) {
                        Text(tabLabel(tab, ref.kind), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }
            }
        }
        // the phone's page, whose plans already hold only what a TV may do (decision 2)
        page?.body?.invoke(ref, shell.selectedTab, model) ?: Empty()
    }
}

/** Home on a TV: the space's level under its filters (one per application, core's `Screen.Signed.filters`), as tiles. */
@Composable
private fun TvHome(s: Screen.Signed, model: AppModel) {
    val shell = s.shell
    Column(Modifier.fillMaxSize()) {
        Text(shell.folder?.displayName ?: spaceName(s.space), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp).testTag("tv-title"))
        if (s.filters.size > 1) {
            val selected = s.filters.indexOf(shell.filter).coerceAtLeast(0)
            TabRow(selectedTabIndex = selected, modifier = Modifier.padding(bottom = 8.dp)) {
                s.filters.forEachIndexed { i, f ->
                    Tab(selected = i == selected, onFocus = {}, onClick = { model.filter(f) }) {
                        Text(f?.let { appName(it) } ?: stringResource(R.string.filter_all), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }
            }
        }
        val groups = s.levelGroups
        if (groups.isEmpty()) {
            Empty()
            return@Column
        }
        TvGrid {
            for (group in groups) {
                group.heading?.let { h -> item(span = { GridItemSpan(maxLineSpan) }) { TvSection(say(h)) } }
                items(group.nodes, key = { it.scopeId + it.nodeId }) { node ->
                    val folder = node.asFolder()
                    val obj = node.asObject()
                    val bookmark = node.asBookmark()
                    when {
                        folder != null -> TvTile(node.displayName, null, LocalCovers.current[node.nodeId]?.second, Icons.Outlined.Folder) { model.enter(folder) }
                        obj != null -> ObjectCard(obj, model::open)
                        bookmark != null -> TvTile(bookmark.name, null, null, kindIcon(bookmark.kind)) { model.openBookmark(bookmark) }
                    }
                }
            }
        }
    }
}

/**
 * An application's list on a TV (ADR 0122 decision 5): core's arranged list — Favorites, Recent, then the rest — as tiles;
 * the same list for every application, its tiles opening the object.
 */
@Composable
private fun TvArranged(title: String, list: ArrangedList, model: AppModel) {
    Column(Modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp).testTag("tv-title"))
        if (list.favorites.isEmpty() && list.recent.isEmpty() && list.all.isEmpty()) {
            Empty()
            return@Column
        }
        TvGrid {
            group(R.string.drawer_favorites, list.favorites, "fav", model)
            group(R.string.drawer_recent, list.recent, "recent", model)
            group(if (list.favorites.isEmpty() && list.recent.isEmpty()) null else R.string.filter_all, list.all, "all", model)
        }
    }
}

private fun LazyGridScope.group(title: Int?, refs: List<ObjectRef>, key: String, model: AppModel) {
    if (refs.isEmpty()) return
    if (title != null) item(key = "head-$key", span = { GridItemSpan(maxLineSpan) }) { TvSection(stringResource(title)) }
    items(refs, key = { "$key-${it.scopeId}-${it.nodeId}" }) { ref -> ObjectCard(ref, model::open) }
}

/** A TV's grid of tiles: as many columns of about [TILE_WIDTH] as fit, the rows scrolled by focus. */
@Composable
internal fun TvGrid(content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        GridCells.Adaptive(TILE_WIDTH), Modifier.fillMaxSize().testTag("tv-grid"),
        contentPadding = PaddingValues(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), content = content,
    )
}

/** A heading over a group of tiles. */
@Composable
internal fun TvSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

/** An object's tile: its cover, its name, what its application says of it (a Pot: its currency). */
@Composable
private fun ObjectCard(ref: ObjectRef, onOpen: (ObjectRef) -> Unit) {
    val details = LocalRowDetails.current[ref.nodeId].orEmpty()
    TvTile(ref.name, details.takeIf { it.isNotEmpty() }?.joined(), LocalCovers.current[ref.nodeId]?.second, kindIcon(ref.kind)) { onOpen(ref) }
}

/** A TV tile (Compose for TV's card, its focus look the platform's): a 16:9 picture or the kind's icon, a name, a line. */
@Composable
internal fun TvTile(name: String, line: String?, cover: ImageBitmap?, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.testTag("tv-tile")) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/** A tile's width on a TV: about five to a row on a 1080p screen beside the closed drawer. */
internal val TILE_WIDTH = 240.dp
