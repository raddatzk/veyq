package me.raddatz.veyq.mobile.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.audio.NowPlaying
import me.raddatz.veyq.mobile.audio.PlayerCommands
import me.raddatz.veyq.mobile.audio.PlayerNotice
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.music.MusicModel
import me.raddatz.veyq.mobile.memories.music.MusicQueue
import me.raddatz.veyq.mobile.words.Msg

/** How long the bar says that none of a queue's tracks played (the web's 5 s). */
private const val NOTHING_PLAYS_MS = 5_000L

/**
 * The music bar (the web's `AudioBar.vue`, ADR 0142 decision 5), at the shell's bottom above the application's bar — the
 * mini-player Material's own music apps draw there — while the account's player holds a queue: the title, the node's name
 * (it opens the node that started the queue), previous, play/pause, next, close (the queue stops), and the position over
 * the trimmed span, which seeks when it is let go, with the time played and the span's length. What it shows is the core's
 * `NowPlaying`; it decides nothing. When none of a queue played on this device it says so for 5 s (ADR 0142 decision 9).
 */
@Composable
internal fun MusicBar(model: AppModel) {
    val player = model.music.collectAsStateWithLifecycle().value ?: return
    val state by player.state.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    val notice = state.notice
    var saying by remember { mutableStateOf(false) }
    LaunchedEffect(notice) {
        saying = notice == PlayerNotice.UNPLAYABLE
        if (saying) {
            delay(NOTHING_PLAYS_MS)
            saying = false
        }
    }
    val now = state.nowPlaying
    if (now == null && !saying) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (saying) Text(say(Msg.MUSIC_NOTHING_PLAYS), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            if (now == null) return@Column
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MusicNote, null, tint = role(palette.accent), modifier = Modifier.padding(horizontal = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(now.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val album = now.album
                    val route = now.route
                    if (album != null) {
                        val opens = say(Msg.MUSIC_OPEN_NODE, album)
                        Text(
                            album, style = MaterialTheme.typography.bodySmall, color = role(palette.accent), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = if (route != null) Modifier.clickable(onClickLabel = opens) { model.open(route) } else Modifier,
                        )
                    }
                }
                MusicButtons(now, player)
                IconButton(onClick = { player.stop() }) { Icon(Icons.Filled.Close, say(Msg.MUSIC_CLOSE)) }
            }
            MusicPosition(now, player)
        }
    }
}

/** Previous, play/pause, next over the core's player ([commands]): the bar's and the lightbox's compact controls'. */
@Composable
private fun MusicButtons(now: NowPlaying, commands: PlayerCommands, tint: Color = Color.Unspecified) {
    IconButton(onClick = commands::previous) { Icon(Icons.Filled.SkipPrevious, say(Msg.MUSIC_PREVIOUS), tint = tint) }
    if (now.playing) IconButton(onClick = commands::pause) { Icon(Icons.Filled.Pause, say(Msg.MUSIC_PAUSE), tint = tint) }
    else IconButton(onClick = commands::resume) { Icon(Icons.Filled.PlayArrow, say(Msg.MUSIC_RESUME), tint = tint) }
    IconButton(onClick = commands::next) { Icon(Icons.Filled.SkipNext, say(Msg.MUSIC_NEXT), tint = tint) }
}

/** Where the track stands in its span: a slider that seeks once let go (the web's range), the times as `m:ss`. */
@Composable
private fun MusicPosition(now: NowPlaying, commands: PlayerCommands) {
    var dragged by remember(now.trackId) { mutableStateOf<Float?>(null) }
    val total = now.durationMs.coerceAtLeast(1).toFloat()
    val at = dragged ?: now.positionMs.toFloat().coerceIn(0f, total)
    val label = say(Msg.MUSIC_PROGRESS, now.title)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(MusicQueue.formatLength(at.toLong()), style = MaterialTheme.typography.labelSmall)
        Slider(
            value = at,
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let { commands.seek(it.toLong()) }
                dragged = null
            },
            valueRange = 0f..total,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = label },
        )
        Text(if (now.durationMs > 0) MusicQueue.formatLength(now.durationMs) else "", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The lightbox's compact controls (the web's `AudioMiniControls.vue`, ADR 0142 decision 5): the title, previous,
 * play/pause, next — a row of its own under the lightbox's bar on a phone. Closing the lightbox stops nothing.
 */
@Composable
internal fun MusicCompactControls(now: NowPlaying, commands: PlayerCommands) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.MusicNote, null, tint = Color.White, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
        Text(now.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        MusicButtons(now, commands, tint = Color.White)
    }
}

/**
 * Which of a Memory's views shows: its photos and videos, its music (ADR 0142 decision 6), its map (ADR 0143 D8), its
 * contributions (ADR 0149 decision 6).
 */
internal enum class MemoryView { PHOTOS, MUSIC, MAP, CONTRIBUTIONS }

/**
 * The view switch inside a Memory's screen (Material's primary tabs): Photos | Music | Map | Contributions — Music where the
 * node has music or the viewer may add some ([music]), Map always, as the web's tab is, Contributions where core offers them
 * to whoever looks ([contributions]: a member's, `MemoryRights.buckets` and `Contributions.OFFERED`).
 */
@Composable
internal fun MemoryViewSwitch(view: MemoryView, music: Boolean, contributions: Boolean, onView: (MemoryView) -> Unit) {
    val views = MemoryView.entries.filter { (it != MemoryView.MUSIC || music) && (it != MemoryView.CONTRIBUTIONS || contributions) }
    val selected = views.indexOf(view).coerceAtLeast(0)
    // four views' words do not fit a phone's fixed tabs ("Contributions" broke mid-word, 2026-11-19): Material's scrollable tabs then
    val tabs: @Composable (@Composable () -> Unit) -> Unit = { content ->
        if (views.size > 3) PrimaryScrollableTabRow(selectedTabIndex = selected, containerColor = MaterialTheme.colorScheme.background, edgePadding = 0.dp) { content() }
        else PrimaryTabRow(selectedTabIndex = selected, containerColor = MaterialTheme.colorScheme.background) { content() }
    }
    tabs {
        for (v in views) {
            val words = when (v) {
                MemoryView.PHOTOS -> Msg.MUSIC_VIEW_PHOTOS
                MemoryView.MUSIC -> Msg.MUSIC_VIEW_MUSIC
                MemoryView.MAP -> Msg.MEMORY_VIEW_MAP
                MemoryView.CONTRIBUTIONS -> Msg.MEMORY_BUCKETS_TITLE
            }
            Tab(selected = view == v, onClick = { onView(v) }, text = { Text(say(words)) })
        }
    }
}

/**
 * A Memory's or a chapter's Music view (the web's `MemoryMusic.vue`, ADR 0142 decisions 6–11), drawn from core's
 * `MusicModel`: the tracks with their title and length, the one playing marked, one that did not play on this device said
 * so; a press plays it first, then the rest shuffled. Each track's overflow menu (Material's row actions): rename, download,
 * remove (the gallery's trash with its Undo, said by the page's snackbar), report (an ITEM). "Add music" is the view's one
 * creating action, the system's document picker filtered to audio (no media permission: the picker grants each file).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MusicView(gallery: GalleryModel, music: MusicModel, model: AppModel, snackbar: SnackbarHostState, viewSwitch: @Composable () -> Unit) {
    val state by music.state.collectAsStateWithLifecycle()
    val hero = LocalNodeHero.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.addMusic(music, uris)
    }
    // what an add or a rename left to say, once
    val said = state.said
    LaunchedEffect(said) {
        if (said == null) return@LaunchedEffect
        snackbar.showSnackbar(context.text(said))
        music.seen()
    }
    var renaming by remember { mutableStateOf<MusicModel.Shown?>(null) }
    var reporting by remember { mutableStateOf<me.raddatz.veyq.mobile.navigation.ReportSheet?>(null) }
    var saving by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = saving
        saving = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        val save = model.saveTrack(music, id, uri) ?: return@rememberLauncherForActivityResult
        scope.launch { snackbar.showSnackbar(context.text(save.state.first { it.end != null }.notice)) }
    }
    CreateAction(say(Msg.MUSIC_ADD), shown = state.rights.add, onClick = { pick.launch(arrayOf("audio/*")) }) { list, bottom ->
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottom)) {
            // the banner above the header, on every view (ADR 0144 decision 4)
            item(key = "banner") { MemoryBannerFrame(gallery, model) }
            item(key = "hero") { hero(16.dp) }
            item(key = "facts") { MemoryFactsLine(gallery, model) }
            item(key = "people") { MemoryPeopleLine(gallery, model) }
            stickyHeader(key = "views") { viewSwitch() }
            if (state.checking) item(key = "checking") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            val tracks = state.tracks
            when {
                tracks == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                tracks.isEmpty() -> item(key = "empty") {
                    Text(say(Msg.MUSIC_EMPTY_HINT), color = role(palette.muted), modifier = Modifier.padding(16.dp))
                }
                else -> items(tracks, key = { "track:" + it.mediaId }) { t ->
                    TrackRow(
                        t, music,
                        onRename = { renaming = t },
                        onSave = {
                            saving = t.mediaId
                            saveTo.launch(t.track.fileName ?: t.title ?: context.words(Msg.MUSIC_UNTITLED, t.number))
                        },
                        onRemove = { model.galleryAct { music.remove(t.mediaId)?.let { snackbar.showSnackbar(context.text(it)) } } },
                        onReport = { reporting = music.reportSheet(t.mediaId) },
                    )
                }
            }
        }
    }
    renaming?.let { t -> TrackRenameDialog(t, music) { renaming = null } }
    reporting?.let { sheet -> ReportSheet(sheet) { reporting = null } }
}

/** One track: its title (or "Track n"), its length, the playing one marked, one that does not play here said so; its menu. */
@Composable
private fun TrackRow(t: MusicModel.Shown, music: MusicModel, onRename: () -> Unit, onSave: () -> Unit, onRemove: () -> Unit, onReport: () -> Unit) {
    val palette = LocalPalette.current
    val state by music.state.collectAsStateWithLifecycle()
    val title = t.title ?: say(Msg.MUSIC_UNTITLED, t.number)
    var menu by remember { mutableStateOf(false) }
    val rights = state.rights
    val removes = music.removes(t.mediaId)
    val hasMenu = rights.rename || (rights.download && !t.blocked) || removes || rights.report
    ListItem(
        headlineContent = {
            Text(title, fontWeight = if (t.playing) FontWeight.Bold else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = when {
            t.blocked -> { { Text(stringResource(R.string.memory_blocked), color = role(palette.muted)) } }
            t.unplayable -> { { Text(say(Msg.MUSIC_TRACK_UNPLAYABLE), color = role(palette.danger)) } }
            else -> null
        },
        leadingContent = {
            Icon(if (t.playing) Icons.Outlined.GraphicEq else Icons.Outlined.MusicNote, null, tint = role(if (t.playing) palette.accent else palette.muted))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                t.length?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = role(palette.muted)) }
                if (hasMenu) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, say(Msg.MUSIC_ACTIONS, title)) }
                    DropdownMenu(menu, { menu = false }) {
                        if (rights.rename) DropdownMenuItem(text = { Text(say(Msg.MUSIC_RENAME)) }, onClick = { menu = false; onRename() })
                        if (rights.download && !t.blocked) DropdownMenuItem(text = { Text(say(Msg.MUSIC_DOWNLOAD)) }, onClick = { menu = false; onSave() })
                        if (removes) DropdownMenuItem(text = { Text(say(Msg.MUSIC_REMOVE)) }, onClick = { menu = false; onRemove() })
                        if (rights.report) DropdownMenuItem(text = { Text(stringResource(R.string.report_action)) }, onClick = { menu = false; onReport() })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = if (t.blocked) Modifier else Modifier.clickable(onClickLabel = say(Msg.MUSIC_PLAY_FROM, title)) { music.play(t.mediaId) },
    )
}

/** A track's rename (the web's): its own name, or empty to name it by its file again; what a conflict said, the view says. */
@Composable
private fun TrackRenameDialog(t: MusicModel.Shown, music: MusicModel, onClose: () -> Unit) {
    var name by remember(t.mediaId) { mutableStateOf(t.track.ownName.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text(say(Msg.MUSIC_RENAME_TITLE)) },
        text = {
            OutlinedTextField(
                name, { name = it }, singleLine = true, label = { Text(say(Msg.MUSIC_NAME_LABEL)) },
                supportingText = { Text(say(Msg.MUSIC_NAME_HINT)) }, enabled = !saving,
            )
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    music.rename(t.mediaId, name.trim().ifEmpty { null })
                    onClose()
                }
            }) { Text(say(Msg.MUSIC_RENAME)) }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !saving) { Text(stringResource(R.string.trash_cancel)) } },
    )
}
