package me.raddatz.veyq.mobile.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.map.Viewport
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.memories.tracks.TrackFacts
import me.raddatz.veyq.mobile.memories.tracks.TrackList
import me.raddatz.veyq.mobile.words.Msg

/**
 * What the document picker offers for a track: GPX's own type, and the ones a phone's files app gives a `.gpx` it does not
 * know (the core reads every picked file and refuses one that is no GPX, with its words).
 */
private val GPX_TYPES = arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream")

/**
 * A Memory's or a chapter's **Map** view (the web's `MemoryTracks.vue`, ADR 0143 D8; ADR 0130): the core's raster map with
 * the photos' dots and the node's GPX tracks, each in its colour with its activity's dash, the selected one heavier and
 * framed; the list under it — shown or hidden, the activity above the name, distance, duration, climb, descent — and per
 * track an overflow menu (Material's row actions): Edit, Download GPX, Remove (the gallery's trash with its Undo), Report.
 * *Add GPX track* is the view's one creating action, the document picker; each accepted file's name and activity are asked
 * before anything is uploaded. Offline there is no map (D9): the list shows from the kept originals.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MemoryMapView(gallery: GalleryModel, tracks: TrackList, model: AppModel, snackbar: SnackbarHostState, viewSwitch: @Composable () -> Unit) {
    val state by tracks.state.collectAsStateWithLifecycle()
    val g by gallery.state.collectAsStateWithLifecycle()
    val maps by model.maps.collectAsStateWithLifecycle()
    val hero = LocalNodeHero.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // the tracks are read only while this view shows (one at a time, the core's)
    DisposableEffect(tracks) {
        tracks.active(true)
        onDispose { tracks.active(false) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.addTracks(tracks, uris)
    }
    val said = state.said
    LaunchedEffect(said) {
        if (said == null) return@LaunchedEffect
        snackbar.showSnackbar(context.text(said))
        tracks.seen()
    }
    var editing by remember { mutableStateOf<TrackList.Entry?>(null) }
    var reporting by remember { mutableStateOf<me.raddatz.veyq.mobile.navigation.ReportSheet?>(null) }
    var saving by remember { mutableStateOf<String?>(null) }
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val id = saving
        saving = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        val save = model.saveGpx(tracks, id, uri) ?: return@rememberLauncherForActivityResult
        scope.launch { snackbar.showSnackbar(context.text(save.state.first { it.end != null }.notice)) }
    }
    val points = g.gpsPoints
    val entries = state.entries
    val camera = remember(gallery) { MapCamera(::worldView) }
    val dots = remember(points) { projected(points) }
    // every drawn track's segments, projected once
    val lines = remember(state.drawn) { state.drawn.associate { e -> e.mediaId to e.lines.map(::projected) } }
    // frame the photos and the tracks once they are read, unless the member moved the map
    val everything = remember(points, state.drawn) { points + state.drawn.flatMap { e -> e.lines.flatten() } }
    LaunchedEffect(everything) {
        if (camera.userMoved || everything.isEmpty()) return@LaunchedEffect
        val v = kotlinx.coroutines.flow.flow { while (true) { camera.view?.let { emit(it) }; kotlinx.coroutines.delay(50) } }.first()
        Viewport.fit(everything, v.width, v.height)?.let { camera.frame(it, animated = false) }
    }
    // a track selected is framed
    LaunchedEffect(state.selected) {
        val e = state.entries?.firstOrNull { it.mediaId == state.selected } ?: return@LaunchedEffect
        val v = camera.view ?: return@LaunchedEffect
        Viewport.fit(e.lines.flatten(), v.width, v.height)?.let { camera.frame(it) }
    }
    val accent = role(palette.accent)
    CreateAction(say(Msg.TRACK_ADD), shown = state.rights.add, onClick = { pick.launch(GPX_TYPES) }) { list, bottom ->
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottom)) {
            item(key = "hero") { hero(16.dp) }
            item(key = "facts") { MemoryFactsLine(gallery, model) }
            stickyHeader(key = "views") { viewSwitch() }
            if (state.checking) item(key = "checking") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item(key = "map") {
                if (points.isEmpty() && entries.orEmpty().isEmpty() && entries != null) {
                    MapNote(say(Msg.TRACK_NOTHING_ON_MAP))
                } else MapPlace(maps, Modifier.fillMaxWidth().height(MAP_HEIGHT)) {
                    val services = maps ?: return@MapPlace
                    RasterMap(
                        services, camera, Modifier.fillMaxWidth().height(MAP_HEIGHT),
                        overlay = { _, t ->
                            for (e in state.drawn.sortedBy { it.mediaId == state.selected }) {
                                trackLine(t, lines[e.mediaId].orEmpty(), Color(e.color.toInt()), e.dash, e.mediaId == state.selected)
                            }
                            for (p in dots) photoDot(t, p, accent)
                        },
                    )
                }
            }
            item(key = "tracks-title") {
                Text(say(Msg.TRACK_TITLE), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
            }
            when {
                entries == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                entries.isEmpty() -> item(key = "empty") {
                    Text(say(Msg.TRACK_EMPTY_HINT), color = role(palette.muted), modifier = Modifier.padding(16.dp))
                }
                else -> items(entries, key = { "track:" + it.mediaId }) { e ->
                    TrackEntryRow(
                        e, tracks, state,
                        onEdit = { editing = e },
                        onSave = {
                            saving = e.mediaId
                            saveTo.launch(e.fileName ?: ((e.name ?: context.words(Msg.TRACK_UNNAMED, e.number)) + ".gpx"))
                        },
                        onRemove = { model.galleryAct { tracks.remove(e.mediaId)?.let { snackbar.showSnackbar(context.text(it)) } } },
                        onReport = { reporting = tracks.reportSheet(e.mediaId) },
                    )
                }
            }
        }
    }
    if (state.asking.isNotEmpty()) TracksAskDialog(tracks, state.asking)
    editing?.let { e -> TrackEditDialog(e, tracks) { editing = null } }
    reporting?.let { sheet -> ReportSheet(sheet) { reporting = null } }
}

/** The Map view's map height: room for the tracks' list under it on a phone. */
private val MAP_HEIGHT = 320.dp

/** An activity token in the person's language. */
@Composable
private fun activityWords(token: String?): String = say(
    when (token) {
        "hiking" -> Msg.TRACK_ACTIVITY_HIKING
        "walking" -> Msg.TRACK_ACTIVITY_WALKING
        "running" -> Msg.TRACK_ACTIVITY_RUNNING
        "cycling" -> Msg.TRACK_ACTIVITY_CYCLING
        "mountain-biking" -> Msg.TRACK_ACTIVITY_MOUNTAIN_BIKING
        "road-cycling" -> Msg.TRACK_ACTIVITY_ROAD_CYCLING
        "gravel-cycling" -> Msg.TRACK_ACTIVITY_GRAVEL_CYCLING
        "skiing" -> Msg.TRACK_ACTIVITY_SKIING
        "water" -> Msg.TRACK_ACTIVITY_WATER
        "other" -> Msg.TRACK_ACTIVITY_OTHER
        else -> Msg.TRACK_ACTIVITY_NONE
    },
)

/** A track's facts as the list says them: the distance in the device's number format, the rest the core's words. */
@Composable
private fun factsLine(facts: List<TrackFacts.Fact>): String {
    val context = LocalContext.current
    return facts.joinToString(" · ") { f ->
        when (f) {
            is TrackFacts.Fact.Distance -> android.icu.text.MeasureFormat.getInstance(java.util.Locale.getDefault(), android.icu.text.MeasureFormat.FormatWidth.SHORT,
                android.icu.text.NumberFormat.getInstance().apply { minimumFractionDigits = if (f.kilometres) 1 else 0; maximumFractionDigits = if (f.kilometres) 1 else 0 })
                .format(android.icu.util.Measure(f.value, if (f.kilometres) android.icu.util.MeasureUnit.KILOMETER else android.icu.util.MeasureUnit.METER))
            is TrackFacts.Fact.Words -> context.text(f.notice)
        }
    }
}

/** A track's swatch: a short stroke in its colour with its activity's dash, as its line is drawn on the map. */
@Composable
private fun TrackSwatch(color: Color, dash: List<Int>?) {
    Canvas(Modifier.size(width = 32.dp, height = 24.dp)) {
        val effect = dash?.takeIf { it.isNotEmpty() }?.let { d -> PathEffect.dashPathEffect(FloatArray(d.size) { d[it].dp.toPx() }) }
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 4.dp.toPx(), cap = if (effect != null) StrokeCap.Butt else StrokeCap.Round, pathEffect = effect)
    }
}

/** One track of the list: its swatch, its activity above its name, its facts (or why none), shown or hidden, its menu; a press selects it. */
@Composable
private fun TrackEntryRow(
    e: TrackList.Entry, tracks: TrackList, state: TrackList.State,
    onEdit: () -> Unit, onSave: () -> Unit, onRemove: () -> Unit, onReport: () -> Unit,
) {
    val palette = LocalPalette.current
    val name = e.name ?: say(Msg.TRACK_UNNAMED, e.number)
    val rights = state.rights
    val removes = tracks.removes(e.mediaId)
    val hasMenu = rights.edit || (rights.download && !e.blocked) || removes || rights.report
    var menu by remember { mutableStateOf(false) }
    val selected = state.selected == e.mediaId
    val shown = e.mediaId !in state.hidden
    ListItem(
        overlineContent = e.activity?.let { a -> { Text(activityWords(a)) } },
        headlineContent = { Text(name, fontWeight = if (selected) FontWeight.Bold else null, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val line = when {
                e.blocked -> stringResource(R.string.memory_blocked)
                e.status == TrackList.Status.LOADING -> say(Msg.TRACK_LOADING)
                e.status == TrackList.Status.FAILED -> say(Msg.TRACK_UNREADABLE)
                else -> factsLine(e.facts)
            }
            Text(line, color = role(if (e.status == TrackList.Status.FAILED && !e.blocked) palette.danger else palette.muted))
        },
        leadingContent = { TrackSwatch(Color(e.color.toInt()), e.dash) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = say(Msg.TRACK_SHOW, name)
                Switch(shown, { tracks.setShown(e.mediaId, it) }, modifier = Modifier.semantics { contentDescription = label })
                if (hasMenu) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, say(Msg.TRACK_ACTIONS, name)) }
                    DropdownMenu(menu, { menu = false }) {
                        if (rights.edit) DropdownMenuItem(text = { Text(say(Msg.TRACK_EDIT)) }, onClick = { menu = false; onEdit() })
                        if (rights.download && !e.blocked) DropdownMenuItem(text = { Text(say(Msg.TRACK_DOWNLOAD)) }, onClick = { menu = false; onSave() })
                        if (removes) DropdownMenuItem(text = { Text(say(Msg.TRACK_REMOVE)) }, onClick = { menu = false; onRemove() })
                        if (rights.report) DropdownMenuItem(text = { Text(stringResource(R.string.report_action)) }, onClick = { menu = false; onReport() })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = if (selected) role(palette.accentSoft) else MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable { tracks.select(if (selected) null else e.mediaId) },
    )
}

/** The activities offered: none, then the core's list. */
private val ACTIVITY_CHOICES: List<String?> = listOf<String?>(null) + TrackLabel.ACTIVITIES

/** An activity picked from Material's exposed dropdown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityPicker(activity: String?, enabled: Boolean = true, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { if (enabled) open = it }) {
        OutlinedTextField(
            activityWords(activity), {}, readOnly = true, enabled = enabled, singleLine = true,
            label = { Text(say(Msg.TRACK_ACTIVITY_LABEL)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(open, { open = false }) {
            for (a in ACTIVITY_CHOICES) DropdownMenuItem(text = { Text(activityWords(a)) }, onClick = { open = false; onPick(a) })
        }
    }
}

/**
 * The picked files' labels, asked before anything is uploaded (ADR 0143 D8; uc.memory.tracks A9): per accepted file its
 * name (empty: named by its file) and its activity; *Add* queues them with their labels sealed at birth, *Cancel* uploads
 * nothing.
 */
@Composable
private fun TracksAskDialog(tracks: TrackList, asking: List<TrackList.Asked>) {
    val names = remember(asking) { mutableStateListOf(*Array(asking.size) { "" }) }
    val activities = remember(asking) { mutableStateListOf<String?>(*arrayOfNulls(asking.size)) }
    AlertDialog(
        onDismissRequest = { tracks.cancel() },
        title = { Text(say(if (asking.size == 1) Msg.TRACK_ADD_TITLE else Msg.TRACK_ADD_TITLE_MANY)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(say(Msg.TRACK_ADD_HINT), style = MaterialTheme.typography.bodyMedium)
                asking.forEachIndexed { i, a ->
                    Text(a.fileName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    OutlinedTextField(
                        names[i], { names[i] = it }, singleLine = true, label = { Text(say(Msg.TRACK_NAME_LABEL)) },
                        placeholder = a.fileTrackName?.let { h -> { Text(h) } }, modifier = Modifier.fillMaxWidth(),
                    )
                    Box(Modifier.padding(top = 8.dp)) { ActivityPicker(activities[i]) { activities[i] = it } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tracks.confirm(asking.indices.map { i -> TrackLabel.Label(names[i].trim().ifEmpty { null }, activities[i]) })
            }) { Text(say(if (asking.size == 1) Msg.TRACK_ADD_CONFIRM else Msg.TRACK_ADD_CONFIRM_MANY)) }
        },
        dismissButton = { TextButton(onClick = { tracks.cancel() }) { Text(stringResource(R.string.trash_cancel)) } },
    )
}

/** A track's label edited (the web's): its own name, or empty to name it by its file again, and its activity. */
@Composable
private fun TrackEditDialog(e: TrackList.Entry, tracks: TrackList, onClose: () -> Unit) {
    var name by remember(e.mediaId) { mutableStateOf(e.ownName.orEmpty()) }
    var activity by remember(e.mediaId) { mutableStateOf(e.activity) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text(say(Msg.TRACK_EDIT_TITLE)) },
        text = {
            Column {
                OutlinedTextField(
                    name, { name = it }, singleLine = true, enabled = !saving, label = { Text(say(Msg.TRACK_NAME_LABEL)) },
                    placeholder = e.fileTrackName?.let { h -> { Text(h) } }, supportingText = { Text(say(Msg.TRACK_NAME_HINT)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.padding(top = 8.dp)) { ActivityPicker(activity, enabled = !saving) { activity = it } }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    tracks.edit(e.mediaId, TrackLabel.Label(name.trim().ifEmpty { null }, activity))
                    onClose()
                }
            }) { Text(say(Msg.ENTRY_SAVE)) }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !saving) { Text(stringResource(R.string.trash_cancel)) } },
    )
}
