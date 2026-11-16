package me.raddatz.veyq.mobile.android

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.map.Viewport
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.place.DateEditor
import me.raddatz.veyq.mobile.memories.place.EditorSave
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.memories.place.PlaceEditor
import me.raddatz.veyq.mobile.words.Msg
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A day of a Memory's date (`yyyy-MM-dd`) as the device writes it. */
private fun day(iso: String): String = localDate(iso)

/**
 * A Memory's date as a list row and its header say it (ADR 0143 D5): a single day, a range the device's way
 * (`DateUtils.formatDateRange`: "3–5 Jan 2020"), an ongoing one "Since …"; null where it has no day yet.
 */
internal fun Context.memoryDateText(d: MemoryDate): String? {
    val from = d.from ?: return null
    if (d.mode == MemoryDate.Mode.ONGOING) return words(Msg.MEMORY_DATE_SINCE, day(from))
    val to = d.to
    if (to == null || to == from) return day(from)
    return runCatching {
        // noon of each day in UTC: the range is days, never shifted by a zone
        fun ms(iso: String) = LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 12 * 3_600_000L
        android.text.format.DateUtils.formatDateRange(
            this, ms(from), ms(to),
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_YEAR or
                android.text.format.DateUtils.FORMAT_ABBREV_MONTH or android.text.format.DateUtils.FORMAT_UTC,
        )
    }.getOrElse { "${day(from)} – ${day(to)}" }
}

/**
 * The facts line under a Memory's header (ADR 0143 D5; the web's `MemoryDetail` facts): its date and its place name,
 * each a chip that opens its editor where whoever looks may edit the node's meta online — a date not yet set and a place
 * not yet named offered as such —, read-only text otherwise. Nothing while the facts are unread.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryFactsLine(gallery: GalleryModel, model: AppModel) {
    val state by gallery.state.collectAsStateWithLifecycle()
    // a node the tree did not hold yet when the page read (one just made) has no facts read: none, once its media are listed
    val facts = state.facts ?: if (state.rows != null) me.raddatz.veyq.mobile.memories.place.MemoryFacts.Facts.NONE else return
    val context = LocalContext.current
    val editable = state.rights.editMeta && state.offline == null
    val dateText = context.memoryDateText(state.date)
    val location = facts.location
    val placeText = when {
        location == null -> null
        location.name.isNotEmpty() -> location.name
        else -> say(Msg.MEMORY_PLACE_PENDING)
    }
    var dates by remember { mutableStateOf<DateEditor?>(null) }
    var places by remember { mutableStateOf<PlaceEditor?>(null) }
    if (!editable && dateText == null && placeText == null) return
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (editable) {
            AssistChip(
                onClick = { dates = model.dateEditor(gallery) },
                label = { Text(dateText ?: say(Msg.MEMORY_DATE_NONE)) },
                leadingIcon = { Icon(Icons.Outlined.CalendarMonth, say(Msg.MEMORY_DATE_EDIT), Modifier.size(18.dp)) },
            )
            AssistChip(
                onClick = { places = model.placeEditor(gallery) },
                label = { Text(placeText ?: say(Msg.MEMORY_PLACE_SET)) },
                leadingIcon = { Icon(Icons.Outlined.Place, say(Msg.MEMORY_PLACE_EDIT), Modifier.size(18.dp)) },
            )
        } else {
            dateText?.let { FactText(Icons.Outlined.CalendarMonth, it) }
            placeText?.let { FactText(Icons.Outlined.Place, it) }
        }
    }
    dates?.let { e -> DateEditorSheet(e) { dates = null } }
    places?.let { e -> PlaceEditorDialog(e, model, hasPlace = location != null) { places = null } }
}

@Composable
private fun FactText(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = role(LocalPalette.current.muted))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = role(LocalPalette.current.muted), modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * The date editor (the web's `MemoryDateEditor.vue`, core's `DateEditor`) as a bottom sheet: the four modes as radio rows,
 * `auto` previewing the range the photos give, the days picked with Material's date picker; a range ending before it starts
 * is said and not saved. A peer's date written meanwhile re-opens the fields on it (the core's).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateEditorSheet(editor: DateEditor, onClose: () -> Unit) {
    val s by editor.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!saving) onClose() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(say(Msg.MEMORY_DATE_EDIT), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            for (mode in MemoryDate.Mode.entries) {
                Row(
                    Modifier.fillMaxWidth().selectable(s.mode == mode, role = Role.RadioButton) { editor.mode(mode) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.mode == mode, onClick = null)
                    Text(say(modeWords(mode)), modifier = Modifier.padding(start = 12.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            when (s.mode) {
                MemoryDate.Mode.AUTO -> {
                    Text(context.memoryDateText(editor.autoRange) ?: say(Msg.MEMORY_DATE_AUTO_EMPTY), style = MaterialTheme.typography.bodyLarge)
                    Text(say(Msg.MEMORY_DATE_AUTO_HINT), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted))
                }
                MemoryDate.Mode.SINGLE -> DayField(say(Msg.MEMORY_DATE_DAY), s.from) { editor.from(it) }
                MemoryDate.Mode.ONGOING -> DayField(say(Msg.MEMORY_DATE_START), s.from) { editor.from(it) }
                MemoryDate.Mode.RANGE -> {
                    DayField(say(Msg.MEMORY_DATE_FROM), s.from) { editor.from(it) }
                    DayField(say(Msg.MEMORY_DATE_TO), s.to) { editor.to(it) }
                    if (s.rangeInvalid) Text(say(Msg.MEMORY_DATE_INVALID_RANGE), color = role(LocalPalette.current.danger), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (failed) Text(say(Msg.NODE_SAVE_FAILED), color = role(LocalPalette.current.danger), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose, enabled = !saving) { Text(stringResource(R.string.trash_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = s.canSave && !saving, onClick = {
                    saving = true
                    failed = false
                    scope.launch {
                        val r = editor.save()
                        saving = false
                        when (r) {
                            EditorSave.Saved -> onClose()
                            EditorSave.Failed -> failed = true
                            is EditorSave.Reopen, null -> Unit // the fields show the peer's date now
                        }
                    }
                }) { Text(say(Msg.ENTRY_SAVE)) }
            }
        }
    }
}

private fun modeWords(mode: MemoryDate.Mode): Msg = when (mode) {
    MemoryDate.Mode.AUTO -> Msg.MEMORY_DATE_MODE_AUTO
    MemoryDate.Mode.SINGLE -> Msg.MEMORY_DATE_MODE_SINGLE
    MemoryDate.Mode.RANGE -> Msg.MEMORY_DATE_MODE_RANGE
    MemoryDate.Mode.ONGOING -> Msg.MEMORY_DATE_MODE_ONGOING
}

/** One day of the date, picked with Material's date picker (`yyyy-MM-dd` in, out). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayField(label: String, iso: String, onDay: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(if (iso.isNotEmpty()) day(iso) else stringResource(R.string.problem_date)) },
        leadingContent = { Icon(Icons.Outlined.CalendarMonth, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickableRow { picking = true },
    )
    if (picking) {
        val initial = runCatching { LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms -> onDay(LocalDate.ofEpochDay(Math.floorDiv(ms, 86_400_000L)).format(DateTimeFormatter.ISO_LOCAL_DATE)) }
                    picking = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.trash_cancel)) } },
        ) { DatePicker(state) }
    }
}

/**
 * The place editor (the web's `MemoryLocationEditor.vue`, core's `PlaceEditor`) as Material's full-screen dialog: the map
 * with a fixed pin at its centre, the photos' dots on it, panned under the pin — a pan by the member takes the place
 * over from the photos (`fixed`), *Follow photos* gives it back —; the name the map gives at the level picked, or one typed
 * (then the member's); *Remove location*. Without a map (none served, or this device's consent not given) it is the name
 * field alone, the consent's offer where the map would stand.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlaceEditorDialog(editor: PlaceEditor, model: AppModel, hasPlace: Boolean, onClose: () -> Unit) {
    val s by editor.state.collectAsStateWithLifecycle()
    val maps by model.maps.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(editor) {
        editor.open()
        onDispose { editor.close() }
    }
    val camera = remember(editor) { MapCamera { w, h -> editor.initialView(w, h) } }
    val dots = remember(editor) { projected(editor.points) }
    val accent = role(LocalPalette.current.accent)
    fun finish(r: EditorSave?) {
        saving = false
        when (r) {
            EditorSave.Failed -> failed = true
            null -> Unit
            else -> onClose() // saved, or a peer's place won: the facts line shows what holds now
        }
    }
    Dialog(onDismissRequest = { if (!saving) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        EdgeToEdgeDialogWindow(darkIcons = !androidx.compose.foundation.isSystemInDarkTheme())
        Scaffold(
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text(say(Msg.MEMORY_PLACE_EDIT)) },
                    navigationIcon = { IconButton(onClick = onClose, enabled = !saving) { Icon(Icons.Filled.Close, stringResource(R.string.trash_cancel)) } },
                    actions = {
                        TextButton(enabled = s.canSave && !saving, onClick = {
                            saving = true
                            failed = false
                            scope.launch { finish(editor.save()) }
                        }) { Text(say(Msg.ENTRY_SAVE)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                MapPlace(maps, Modifier.fillMaxWidth().height(320.dp), onTurnedOn = { editor.mapsTurnedOn() }) {
                    if (!s.withMap) {
                        // the consent came from elsewhere (Settings) while the editor was open: the map takes its place
                        LaunchedEffect(Unit) { editor.mapsTurnedOn() }
                    }
                    val services = maps ?: return@MapPlace
                    RasterMap(
                        services, camera, Modifier.fillMaxWidth().height(320.dp),
                        onSettled = { view, user -> editor.frame(view.center, user) },
                        overlay = { _, t -> for (p in dots) photoDot(t, p, accent) },
                    ) {
                        // the fixed pin: its tip on the map's centre; greyed while the place has no point (E3)
                        val tint = if (s.derived == null) Color.Gray else accent
                        Icon(Icons.Filled.Place, null, tint = tint, modifier = Modifier.align(Alignment.Center).offset(y = (-18).dp).size(36.dp))
                    }
                }
                Column(Modifier.padding(16.dp)) {
                    if (s.withMap && s.derived == null) {
                        Text(say(Msg.MEMORY_PLACE_MOVE_TO_SET), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted))
                    }
                    if (s.withMap && editor.medoid != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                say(if (s.derivedMode == MemoryLocation.DerivedMode.AUTO) Msg.MEMORY_PLACE_FOLLOWS_PHOTOS else Msg.MEMORY_PLACE_CUSTOM),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            if (s.derivedMode == MemoryLocation.DerivedMode.FIXED) TextButton(onClick = {
                                editor.followAgain()
                                val m = editor.medoid ?: return@TextButton
                                scope.launch {
                                    val v = camera.view ?: return@launch
                                    camera.frame(Viewport.fit(editor.points, v.width, v.height) ?: Viewport(m, PlaceEditor.DEFAULT_ZOOM, v.width, v.height))
                                }
                            }) { Text(say(Msg.MEMORY_PLACE_FOLLOW_AGAIN)) }
                        }
                    }
                    if (!s.withMap && editor.medoid == null) {
                        Text(say(Msg.MEMORY_PLACE_NAME_ONLY), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted))
                    }
                    if (s.showLevelPicker) {
                        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (level in s.levels) {
                                FilterChip(selected = level.key == s.chosenLevel, onClick = { editor.pickLevel(level.key) }, label = { Text(level.name) })
                            }
                        }
                    }
                    OutlinedTextField(
                        s.name, { editor.typeName(it) }, singleLine = true,
                        label = { Text(say(Msg.MEMORY_PLACE_NAME)) }, placeholder = { Text(say(Msg.MEMORY_PLACE_NAME_PLACEHOLDER)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    if (s.showLevelPicker || s.levels.isNotEmpty()) {
                        TextButton(onClick = { editor.toggleNameMode() }) {
                            Text(say(if (s.showLevelPicker) Msg.MEMORY_PLACE_NAME_OWN else Msg.MEMORY_PLACE_NAME_FROM_MAP))
                        }
                    }
                    if (failed) Text(say(Msg.NODE_SAVE_FAILED), color = role(LocalPalette.current.danger), style = MaterialTheme.typography.bodySmall)
                    if (hasPlace) Box(Modifier.padding(top = 16.dp)) {
                        TextButton(enabled = !saving, onClick = {
                            saving = true
                            scope.launch { finish(editor.remove()) }
                        }) { Text(say(Msg.MEMORY_PLACE_REMOVE), color = role(LocalPalette.current.danger)) }
                    }
                }
            }
        }
    }
}

/**
 * A photo's small map in its details (ADR 0143 D7): the core's raster map at zoom 15 centred on the photo's point, its dot,
 * and the direction the camera looked where its EXIF gives a true one (a cone towards it); a still map — the sheet
 * scrolls — with the attribution and *Turn map services off* along its edge, the consent's offer where it has none.
 */
@Composable
internal fun PhotoMap(map: me.raddatz.veyq.mobile.memories.place.PhotoPlace.PhotoMap, model: AppModel) {
    val maps by model.maps.collectAsStateWithLifecycle()
    val camera = remember(map) { MapCamera { w, h -> Viewport(map.at, map.zoom, w, h) } }
    val at = remember(map) { me.raddatz.veyq.mobile.map.Mercator.project(map.at) }
    val accent = role(LocalPalette.current.accent)
    MapPlace(maps, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(PHOTO_MAP_HEIGHT)) {
        val services = maps ?: return@MapPlace
        RasterMap(
            services, camera, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(PHOTO_MAP_HEIGHT)
                .clip(MaterialTheme.shapes.medium),
            interactive = false,
            overlay = { _, t ->
                map.directionTrue?.let { deg ->
                    val o = t.at(at)
                    val r = Math.toRadians(deg.toDouble())
                    val reach = 36.dp.toPx()
                    val half = Math.toRadians(22.0)
                    val cone = androidx.compose.ui.graphics.Path().apply {
                        moveTo(o.x, o.y)
                        lineTo(o.x + (reach * kotlin.math.sin(r - half)).toFloat(), o.y - (reach * kotlin.math.cos(r - half)).toFloat())
                        lineTo(o.x + (reach * kotlin.math.sin(r + half)).toFloat(), o.y - (reach * kotlin.math.cos(r + half)).toFloat())
                        close()
                    }
                    drawPath(cone, accent.copy(alpha = 0.35f))
                }
                photoDot(t, at, accent)
            },
        )
    }
}

private val PHOTO_MAP_HEIGHT = 200.dp
