package me.raddatz.veyq.mobile.android.apps.memories

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.LocalCovers
import me.raddatz.veyq.mobile.android.core.LocalPalette
import me.raddatz.veyq.mobile.android.core.MapCamera
import me.raddatz.veyq.mobile.android.core.MapPlace
import me.raddatz.veyq.mobile.android.core.RasterMap
import me.raddatz.veyq.mobile.android.core.Screen
import me.raddatz.veyq.mobile.android.core.ScreenTransform
import me.raddatz.veyq.mobile.android.core.clickableRow
import me.raddatz.veyq.mobile.android.core.role
import me.raddatz.veyq.mobile.android.core.say
import me.raddatz.veyq.mobile.android.core.worldView
import me.raddatz.veyq.mobile.map.Cluster
import me.raddatz.veyq.mobile.map.Mercator
import me.raddatz.veyq.mobile.memories.place.MemoriesMap
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.words.Msg

/** How near a tap must land to a pin to be its (dp): about half a pin and a finger's slack. */
private const val PIN_HIT_DP = 28.0

/**
 * The Memories bar's **Map** tab (uc.memory.place A3; ADR 0143 D6): every Memory of the application's list (the All tab's
 * set) whose place has a position, as a pin over the core's raster map, its cover where its row has one — unsealed on the
 * device from the meta the tree already holds. The pins are grouped by the core at the zoom shown; a tap on a pin opens its
 * Memory, on a group frames it, and where the group shares one spot at the top zoom lists it in a sheet. The map opens
 * framing every pin; with none it shows the world and says how a Memory gets onto it.
 */
@Composable
internal fun MemoriesMapTab(s: Screen.Signed, model: AppModel) {
    val list = s.applicationList(ApplicationId.MEMORIES)
    val refs = remember(list) { list.favorites + list.recent + list.all }
    val pins by produceState<MemoriesMap?>(null, refs) { value = runCatching { model.memoriesMap(refs) }.getOrNull() }
    MemoriesPinMap(pins, model, Modifier.fillMaxSize()) { pin ->
        val ref = refs.firstOrNull { it.nodeId == pin.nodeId && it.scopeId == pin.scopeId } ?: ObjectRef(pin.scopeId, pin.nodeId, "MEMORY", ApplicationId.MEMORIES, pin.name)
        model.open(ref)
    }
}

/**
 * Memories as pins over the core's raster map (ADR 0143 D6): the Map tab's and a person's profile's (ADR 0144 decision 13),
 * the same map either way. [pins] null while they read; a tap on a pin [open]s its Memory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoriesPinMap(pins: MemoriesMap?, model: AppModel, modifier: Modifier, open: (MemoriesMap.Pin) -> Unit) {
    val maps by model.maps.collectAsStateWithLifecycle()
    val covers = LocalCovers.current
    // no view while the pins read: the map opens on their framing, never on the world first (#945)
    val pinsNow by rememberUpdatedState(pins)
    val camera = remember { MapCamera { w, h -> pinsNow?.let { it.framing(w, h) ?: worldView(w, h) } } }
    val scope = rememberCoroutineScope()
    var listed by remember { mutableStateOf<List<MemoriesMap.Pin>?>(null) }
    val accent = role(LocalPalette.current.accent)
    MapPlace(maps, modifier) {
        val services = maps ?: return@MapPlace
        val mm = pins
        // the pins read: frame them all, unless the member already moved the map
        // (derived: the view moving every frame must not recompose the tab)
        val ready by remember { androidx.compose.runtime.derivedStateOf { camera.view != null } }
        LaunchedEffect(mm, ready) {
            if (mm != null) camera.opened()
            val v = camera.view ?: return@LaunchedEffect
            if (mm == null || camera.userMoved) return@LaunchedEffect
            camera.frame(mm.framing(v.width, v.height) ?: worldView(v.width, v.height), animated = false)
        }
        Box(Modifier.fillMaxSize()) {
            RasterMap(
                services, camera, Modifier.fillMaxSize(),
                onTap = { x, y, view ->
                    val m = mm ?: return@RasterMap
                    val hit = m.clusters(view.zoom).minByOrNull { c ->
                        val (cx, cy) = view.toScreen(Mercator.project(c.center))
                        (cx - x) * (cx - x) + (cy - y) * (cy - y)
                    } ?: return@RasterMap
                    val (cx, cy) = view.toScreen(Mercator.project(hit.center))
                    if ((cx - x) * (cx - x) + (cy - y) * (cy - y) > PIN_HIT_DP * PIN_HIT_DP) return@RasterMap
                    when (val t = m.tap(hit, view)) {
                        is MemoriesMap.Tap.Open -> open(t.pin)
                        is MemoriesMap.Tap.Frame -> scope.launch { camera.frame(t.view) }
                        is MemoriesMap.Tap.List -> listed = t.pins
                    }
                },
                overlay = { view, t ->
                    val m = mm ?: return@RasterMap
                    for (c in m.clusters(view.zoom)) memoryPin(t, c, covers[c.members.first().nodeId]?.second, accent)
                },
            )
            if (mm != null && mm.pins.isEmpty()) {
                Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(say(Msg.MEMORIES_MAP_EMPTY), style = MaterialTheme.typography.titleSmall)
                        Text(say(Msg.MEMORIES_MAP_EMPTY_HINT), style = MaterialTheme.typography.bodyMedium, color = role(LocalPalette.current.muted))
                    }
                }
            }
        }
    }
    listed?.let { pinsHere ->
        ModalBottomSheet(onDismissRequest = { listed = null }) {
            Text(
                androidx.compose.ui.res.pluralStringResource(R.plurals.map_cluster_title, pinsHere.size, pinsHere.size), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                items(pinsHere, key = { it.scopeId + "/" + it.nodeId }) { pin ->
                    ListItem(
                        headlineContent = { Text(pin.name) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickableRow {
                            listed = null
                            open(pin)
                        },
                    )
                }
            }
        }
    }
}

/**
 * One group of Memories' pins at its place: a single Memory as a round pin showing its [cover] (else the accent with a
 * white centre), several as a round badge with their count; both ringed in white.
 */
private fun DrawScope.memoryPin(t: ScreenTransform, c: Cluster<MemoriesMap.Pin>, cover: ImageBitmap?, accent: Color) {
    val o = t.at(Mercator.project(c.center))
    val ring = 2.dp.toPx()
    if (c.members.size > 1) {
        val r = 18.dp.toPx()
        drawCircle(Color.White, r + ring, o)
        drawCircle(accent, r, o)
        val paint = android.graphics.Paint().apply {
            color = Color.White.toArgb()
            textSize = 14.dp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        drawContext.canvas.nativeCanvas.drawText(c.members.size.toString(), o.x, o.y - (paint.ascent() + paint.descent()) / 2, paint)
        return
    }
    val r = 20.dp.toPx()
    drawCircle(Color.White, r + ring, o)
    if (cover != null) {
        val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(o, r)) }
        clipPath(circle) {
            // the cover cropped to fill the circle, its short edge across it
            val side = minOf(cover.width, cover.height)
            drawImage(
                cover,
                srcOffset = IntOffset((cover.width - side) / 2, (cover.height - side) / 2), srcSize = IntSize(side, side),
                dstOffset = IntOffset((o.x - r).toInt(), (o.y - r).toInt()), dstSize = IntSize((2 * r).toInt(), (2 * r).toInt()),
            )
        }
    } else {
        drawCircle(accent, r, o)
        drawCircle(Color.White, r / 3, o)
    }
    drawCircle(Color.Black.copy(alpha = 0.15f), r + ring, o, style = Stroke(1.dp.toPx()))
}
