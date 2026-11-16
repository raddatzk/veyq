package me.raddatz.veyq.mobile.android

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.map.MapPoint
import me.raddatz.veyq.mobile.map.MapServices
import me.raddatz.veyq.mobile.map.MapState
import me.raddatz.veyq.mobile.map.Mercator
import me.raddatz.veyq.mobile.map.TileKey
import me.raddatz.veyq.mobile.map.Viewport
import me.raddatz.veyq.mobile.words.Msg
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Where a map looks (ADR 0143 D1): the core's [Viewport] in layout dp, set by the gestures the map reports and by the app
 * framing it. [userMoved] says whether the member moved it since the app last framed it (the place editor's `auto` →
 * `fixed`, a Map view keeping the member's view when its tracks arrive).
 */
@Stable
internal class MapCamera(private val initial: (width: Double, height: Double) -> Viewport) {
    var view by mutableStateOf<Viewport?>(null)
        private set

    var userMoved by mutableStateOf(false)
        private set

    /** The map's size is known (or changed): the first one frames it by [initial]. */
    fun sized(width: Double, height: Double) {
        val v = view
        view = if (v == null) initial(width, height) else if (v.width != width || v.height != height) v.copy(width = width, height = height) else v
    }

    /** The member's gesture moved the view. */
    fun moved(next: Viewport) {
        view = next
        userMoved = true
    }

    /** The app frames the map at [target] (its size kept), animated. */
    suspend fun frame(target: Viewport, animated: Boolean = true) {
        val from = view
        userMoved = false
        if (from == null || !animated) {
            view = from?.let { target.copy(width = it.width, height = it.height) } ?: target
            return
        }
        animateBetween(from, target.copy(width = from.width, height = from.height)) { view = it }
    }

    /** The member's double tap: one level in about [x], [y] (dp), animated. */
    suspend fun zoomIn(x: Double, y: Double) {
        val from = view ?: return
        userMoved = true
        animateBetween(from, from.zoomAbout(1.0, x, y)) { view = it }
    }

    private suspend fun animateBetween(from: Viewport, to: Viewport, set: (Viewport) -> Unit) {
        val a = Mercator.project(from.center)
        val b = Mercator.project(to.center)
        animate(0f, 1f, animationSpec = tween(ANIMATION_MS)) { t, _ ->
            val p = MapPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            set(from.copy(center = Mercator.unproject(p), zoom = from.zoom + (to.zoom - from.zoom) * t))
        }
        set(to)
    }

    companion object {
        private const val ANIMATION_MS = 300
    }
}

/**
 * The one affine formula from normalised Mercator coordinates to a view's pixels (core's `Viewport.toScreen`), with the
 * view's centre projected once per frame instead of once per point: an overlay of thousands of points is drawn each frame.
 */
internal class ScreenTransform(view: Viewport, private val density: Float) {
    private val world = Mercator.worldPx(view.zoom)
    private val c = Mercator.project(view.center)
    private val hw = view.width / 2
    private val hh = view.height / 2

    fun x(p: MapPoint): Float = (((p.x - c.x) * world + hw) * density).toFloat()
    fun y(p: MapPoint): Float = (((p.y - c.y) * world + hh) * density).toFloat()
    fun at(p: MapPoint): Offset = Offset(x(p), y(p))
}

/** The tiles a map keeps decoded for drawing (in memory only, ADR 0143 D4): the shown ones and a few under them while a pinch moves levels. */
private const val DECODED_TILES = 96

/**
 * The core's raster map drawn by Compose (ADR 0143 D1: no map library): the tiles [maps]' tile view hands for [camera]'s
 * view, drawn at their places from the floor level scaled (`Viewport.tiles`), tiles of other levels already decoded drawn
 * under them while a pinch crosses a level; [overlay] draws the overlays over them (the platform draws what it is handed).
 * A drag pans, a pinch zooms about its centre, a double tap zooms in; a tap goes to [onTap] (dp in the view, with the view
 * it hit). [onSettled] hears the view once it rests, and whether the member moved it. Every map shows the provider's
 * attribution and *Turn map services off* along its bottom edge (OSM's policy: never behind a toggle; GDPR Art. 7(3)).
 * The map asks for nothing unless the consent holds: the core's tile view refuses every tile otherwise.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun RasterMap(
    maps: MapServices,
    camera: MapCamera,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    onTap: ((x: Double, y: Double, view: Viewport) -> Unit)? = null,
    onSettled: ((view: Viewport, user: Boolean) -> Unit)? = null,
    overlay: DrawScope.(view: Viewport, transform: ScreenTransform) -> Unit = { _, _ -> },
    foreground: @Composable BoxScope.() -> Unit = {},
) {
    val tileView = remember(maps) { maps.tiles?.view() }
    DisposableEffect(tileView) { onDispose { tileView?.close() } }
    val images = remember(tileView) { mutableStateMapOf<TileKey, ImageBitmap>() }
    val asked = remember(tileView) { HashSet<TileKey>() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val settled by rememberUpdatedState(onSettled)
    val tapped by rememberUpdatedState(onTap)
    LaunchedEffect(camera) {
        snapshotFlow { camera.view }.debounce(SETTLE_MS).collect { v -> if (v != null) settled?.invoke(v, camera.userMoved) }
    }
    // the tiles in view, asked as the view moves: read in a flow, so a pan redraws the canvas without recomposing the map
    val placedNow = remember(tileView) { mutableStateOf<List<me.raddatz.veyq.mobile.map.PlacedTile>>(emptyList()) }
    LaunchedEffect(tileView, camera) {
        val tv = tileView ?: return@LaunchedEffect
        snapshotFlow { camera.view }.collect { v ->
            if (v == null) return@collect
            val placed = tv.show(v)
            placedNow.value = placed
            val level = placed.firstOrNull()?.key?.z ?: return@collect
            for (p in placed) {
                if (p.key in images || !asked.add(p.key)) continue
                scope.launch {
                    val image = tv.tile(p.key)?.let { bytes ->
                        withContext(Dispatchers.Default) {
                            // decoded straight into GPU memory: a new tile costs the frame that first draws it no upload
                            val hardware = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.HARDWARE }
                            runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, hardware)?.asImageBitmap() }.getOrNull()
                        }
                    }
                    asked.remove(p.key)
                    if (image != null) {
                        images[p.key] = image
                        if (images.size > DECODED_TILES) {
                            val shown = placedNow.value.mapTo(HashSet()) { it.key }
                            images.keys.filter { it !in shown }.sortedByDescending { kotlin.math.abs(it.z - level) }
                                .take(images.size - DECODED_TILES).forEach { images.remove(it) }
                        }
                    }
                }
            }
        }
    }
    BoxWithConstraints(modifier.clipToBounds().background(TILE_GROUND)) {
        val w = maxWidth.value.toDouble()
        val h = maxHeight.value.toDouble()
        LaunchedEffect(w, h) { camera.sized(w, h) }
        val gestures = if (!interactive) Modifier else Modifier
            .pointerInput(camera) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val v = camera.view ?: return@detectTransformGestures
                    val d = density.toDouble()
                    camera.moved(v.zoomAbout(log2(zoom.toDouble()), centroid.x / d, centroid.y / d).panBy(pan.x / d, pan.y / d))
                }
            }
            .pointerInput(camera) {
                detectTapGestures(
                    onTap = { o ->
                        val v = camera.view ?: return@detectTapGestures
                        tapped?.invoke(o.x / density.toDouble(), o.y / density.toDouble(), v)
                    },
                    onDoubleTap = { o -> scope.launch { camera.zoomIn(o.x / density.toDouble(), o.y / density.toDouble()) } },
                )
            }
        Canvas(Modifier.fillMaxSize().then(gestures)) {
            // read here, in the draw phase: a moving view redraws, nothing recomposes
            val v = camera.view ?: return@Canvas
            val placed = placedNow.value
            val t = ScreenTransform(v, density)
            val level = placed.firstOrNull()?.key?.z
            // where a tile of the level drawn is not decoded yet, what a pinch left decoded at other levels stands in,
            // coarsest first — only there, so a settled map draws each pixel once
            val missing = placed.filter { it.key !in images }
            if (level != null && missing.isNotEmpty()) {
                val shown = placed.mapTo(HashSet()) { it.key }
                images.entries.filter { it.key.z != level && it.key !in shown }
                    .sortedBy { -kotlin.math.abs(it.key.z - level) }
                    .forEach { (k, img) ->
                        val n = (1 shl k.z).toDouble()
                        val x = k.x / n
                        val y = k.y / n
                        val size = 1.0 / n
                        // (a missing tile's x may lie a world away from its key's: compare within the world)
                        val covers = missing.any { m ->
                            val mx = ((m.x % 1.0) + 1.0) % 1.0
                            mx < x + size && x < mx + m.size && m.y < y + size && y < m.y + m.size
                        }
                        if (covers) drawTile(img, t, MapPoint(x, y), size)
                    }
            }
            for (p in placed) images[p.key]?.let { drawTile(it, t, MapPoint(p.x, p.y), p.size) }
            overlay(v, t)
        }
        foreground()
        MapFooter(maps, Modifier.align(Alignment.BottomCenter))
    }
}

/** How long a view rests before it counts as settled. */
private const val SETTLE_MS = 250L

/** Behind the tiles while they load: OSM's land colour, so a map never flashes white or black. */
private val TILE_GROUND = Color(0xFFF2EFE9)

/** A tile's image at its place, its edges rounded to whole pixels so neighbours meet without a seam. */
private fun DrawScope.drawTile(image: ImageBitmap, t: ScreenTransform, at: MapPoint, size: Double) {
    val x0 = t.x(at).roundToInt()
    val y0 = t.y(at).roundToInt()
    val far = MapPoint(at.x + size, at.y + size)
    val x1 = t.x(far).roundToInt()
    val y1 = t.y(far).roundToInt()
    if (x1 <= 0 || y1 <= 0 || x0 >= this.size.width || y0 >= this.size.height) return
    drawImage(image, dstOffset = IntOffset(x0, y0), dstSize = IntSize(x1 - x0, y1 - y0), filterQuality = FilterQuality.Low)
}

/**
 * The map's bottom edge: *Turn map services off* (withdraws this device's consent at once, every map torn down with it)
 * and the provider's attribution, both always visible (ADR 0143 D2; the OSM tile policy).
 */
@Composable
private fun MapFooter(maps: MapServices, modifier: Modifier) {
    Surface(color = Color.White.copy(alpha = 0.85f), contentColor = Color(0xFF333333), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = maps::turnOff, modifier = Modifier.weight(1f, fill = false)) {
                Text(say(Msg.MAP_OFF), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            maps.attribution?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2, modifier = Modifier.padding(horizontal = 8.dp).weight(1f, fill = false))
            }
        }
    }
}

/**
 * A map's place (ADR 0143 D2, D9; uc.memory.place A4–A6): what [maps] lets it show now — no map where none is served,
 * none offline, the consent's offer naming the hosts where this device has not agreed to them, else [shown]. The state
 * moves at once with the consent: a withdrawal anywhere takes every map down. Without map services at all (no tenant
 * open) it says the map needs a connection.
 */
@Composable
internal fun MapPlace(maps: MapServices?, modifier: Modifier = Modifier, onTurnedOn: () -> Unit = {}, shown: @Composable () -> Unit) {
    if (maps == null) {
        MapNote(say(Msg.MAP_NEEDS_CONNECTION), modifier)
        return
    }
    val state by maps.state.collectAsStateWithLifecycle()
    when (val s = state) {
        MapState.NotOffered -> MapNote(say(Msg.MAP_NOT_OFFERED), modifier)
        MapState.Offline -> MapNote(say(Msg.MAP_NEEDS_CONNECTION), modifier)
        is MapState.Consent -> MapConsentOffer(maps, s, modifier, onTurnedOn)
        MapState.Shown -> shown()
    }
}

/** A line where the map would stand: no map is served, or none can be shown offline. */
@Composable
internal fun MapNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = role(LocalPalette.current.muted))
    }
}

/**
 * The consent's offer where a map would stand (uc.memory.place A6): worded as a setting, not as a cookie banner (ADR
 * 0016), saying what the services receive and naming the hosts — asked again, naming the new ones, where an earlier
 * consent named others. *Turn map services on* gives this device's consent for exactly those hosts.
 */
@Composable
internal fun MapConsentOffer(maps: MapServices, s: MapState.Consent, modifier: Modifier = Modifier, onTurnedOn: () -> Unit = {}) {
    OutlinedCard(modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Map, null, tint = role(LocalPalette.current.accent))
                Text(say(Msg.MAP_CONSENT_TITLE), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
            }
            Text(say(Msg.MAP_CONSENT_BODY), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Text(consentHosts(s), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Button(onClick = { maps.turnOn(); onTurnedOn() }, modifier = Modifier.padding(top = 12.dp)) { Text(say(Msg.MAP_CONSENT_ENABLE)) }
        }
    }
}

@Composable
private fun consentHosts(s: MapState.Consent): String {
    val tiles = s.hosts.tiles.orEmpty()
    val places = s.hosts.places
    return when {
        s.renewal && places != null -> say(Msg.MAP_CONSENT_RENEW_BOTH, tiles, places)
        s.renewal -> say(Msg.MAP_CONSENT_RENEW_TILES, tiles)
        places != null -> say(Msg.MAP_CONSENT_HOSTS, tiles, places)
        else -> say(Msg.MAP_CONSENT_HOSTS_TILES, tiles)
    }
}

// ---- overlays: what the core hands, drawn ----

/** A photo's place on a map: a small dot ringed in white (the web's photo markers). */
internal fun DrawScope.photoDot(t: ScreenTransform, at: MapPoint, color: Color) {
    val o = t.at(at)
    drawCircle(Color.White, radius = 6.dp.toPx(), center = o)
    drawCircle(color, radius = 4.dp.toPx(), center = o)
}

/**
 * A track's segments ([lines], projected) in its [color] with its activity's [dash] (on-off lengths in px at the web's
 * scale, here dp), heavier when [selected] (the web's weight 4, 6 selected), over a white halo that keeps it readable on
 * any tile.
 */
internal fun DrawScope.trackLine(t: ScreenTransform, lines: List<List<MapPoint>>, color: Color, dash: List<Int>?, selected: Boolean) {
    val width = (if (selected) 6 else 4).dp.toPx()
    val effect = dash?.takeIf { it.isNotEmpty() }?.let { d -> PathEffect.dashPathEffect(FloatArray(d.size) { d[it].dp.toPx() }) }
    for (line in lines) {
        if (line.size < 2) continue
        val path = Path()
        line.forEachIndexed { i, p -> if (i == 0) path.moveTo(t.x(p), t.y(p)) else path.lineTo(t.x(p), t.y(p)) }
        drawPath(path, Color.White.copy(alpha = 0.8f), style = Stroke(width + 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color, style = Stroke(width, cap = if (effect != null) StrokeCap.Butt else StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    }
}

/** [points] projected once (they are drawn every frame). */
internal fun projected(points: List<LatLng>): List<MapPoint> = points.map(Mercator::project)

/** The zoom a [Viewport] framing nothing opens at, and its centre: the world. */
internal fun worldView(width: Double, height: Double): Viewport =
    Viewport(me.raddatz.veyq.mobile.memories.place.MemoryLocation.WORLD_CENTER, me.raddatz.veyq.mobile.memories.place.MemoryLocation.WORLD_ZOOM, width, height)
