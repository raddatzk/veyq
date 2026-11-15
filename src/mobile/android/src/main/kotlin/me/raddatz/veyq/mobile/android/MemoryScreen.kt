package me.raddatz.veyq.mobile.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Slideshow as SlideshowIcon
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaGridLayout

import me.raddatz.veyq.mobile.navigation.ObjectRef

/** Core's lightbox, named apart from this file's composable of the same name. */
private typealias MemoryLightbox = me.raddatz.veyq.mobile.memories.Lightbox

/** What full media access is on this Android: the photos and videos themselves, and their locations. */
private fun mediaAccessPermissions(): Array<String> =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.ACCESS_MEDIA_LOCATION)
    } else {
        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

/**
 * A Memory's or a chapter's page (Memories' `MemoryDetail.vue`, its gallery), drawn from core's `GalleryModel`: the chapters
 * a Memory holds, then every medium as a tile of its thumbnail in the justified grid (core's `GalleryModel.layout`: its
 * aspect kept, each row filled to the width), in the grid's order (newest first by capture time), a video marked as one; a
 * tile opens the lightbox ([Lightbox]). When it reads anew is the core's.
 */
@Composable
internal fun MemoryScreen(ref: ObjectRef, model: AppModel) {
    val gallery = remember(ref.at) { model.galleryModel(ref.at) }
    if (gallery == null) {
        CircularProgressIndicator(Modifier.padding(16.dp))
        return
    }
    MemoryGallery(gallery, model)
}

/** [gallery]'s page as [MemoryScreen] draws it (a debug build's `DevMemory` draws it over a played gallery). */
@Composable
internal fun MemoryGallery(gallery: GalleryModel, model: AppModel) {
    val palette = LocalPalette.current
    val state by gallery.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<MemoryLightbox?>(null) }
    val hero = LocalNodeHero.current
    val rows = state.rows
    // the system's photo picker: photos and videos, several at once
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) model.uploadMedia(gallery, uris)
    }
    fun pickMedia() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    // the picker hands a photo over with its location zeroed and under a number: full media access keeps both, so it is
    // asked for once, before the first pick; whatever the answer, the picker opens
    val askAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { pickMedia() }
    fun add() = if (model.mediaAccessAsked()) pickMedia() else askAccess.launch(mediaAccessPermissions())
    // live while the page is on screen and the app started: what another device or a guest adds shows up (core's poll)
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // (in the gallery's own scope, off the main thread: a moved gallery is read and opened there)
    LaunchedEffect(gallery) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val live = gallery.startLive()
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                live.stop()
            }
        }
    }
    // what the page adds: photos and videos where the rights let whoever looks upload, a chapter where it makes one (core's)
    val chapterHere = me.raddatz.veyq.mobile.memories.MemoryCreate.CHAPTER in gallery.creates
    var namingChapter by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    Box(Modifier.fillMaxSize()) {
    CreateAction(
        stringResource(if (state.rights.upload) R.string.memory_add else R.string.memory_add_chapter),
        shown = state.rights.upload || chapterHere,
        onClick = { if (state.rights.upload) add() else namingChapter = true },
        menu = if (state.rights.upload && chapterHere) { close ->
            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.memory_add)) }, onClick = { close(); add() })
            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.memory_add_chapter)) }, onClick = { close(); namingChapter = true })
        } else null,
    ) { list, bottom ->
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // the justified grid (core's `GalleryModel.layout`, the web's `MediaGrid.vue`): laid out for the width between the
    // page's margins, each row an item of the lazy list, so only the rows near the viewport are composed (ADR 0105)
    val gridWidth = (maxWidth - GRID_MARGIN * 2).value.toDouble()
    val layout = remember(rows, gridWidth) { rows?.let { GalleryModel.layout(it, gridWidth) } }
    val byId = remember(rows) { rows.orEmpty().withIndex().associateBy({ it.value.mediaId }, { it }) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottom)) {
        item(key = "hero") { hero(16.dp) }
        if (state.chapters.isNotEmpty()) {
            item(key = "chapters") {
                Text(stringResource(R.string.memory_chapters), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            }
            items(state.chapters, key = { "chapter:" + it.nodeId }) { c ->
                ListItem(
                    headlineContent = { Text(c.name) },
                    leadingContent = { ChapterCover(gallery, c) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickableRow { GalleryModel.rowPage(c)?.let(model::open) },
                )
            }
        }
        when {
            state.failed -> item(key = "failed") {
                Text(stringResource(R.string.memory_gallery_failed), color = role(palette.danger), modifier = Modifier.padding(16.dp))
            }
            rows == null || layout == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            rows.isEmpty() -> item(key = "empty") {
                Text(stringResource(R.string.memory_gallery_empty), color = role(palette.muted), modifier = Modifier.padding(16.dp))
            }
            else -> {
                item(key = "count") {
                    Text(
                        pluralStringResource(R.plurals.memory_items, rows.size, rows.size), style = MaterialTheme.typography.bodySmall,
                        color = role(palette.muted), modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                val last = layout.rows.lastIndex
                items(layout.rows, key = { "row:" + it.key }) { r ->
                    // a row's height is its tiles'; the gap below it is the layout's, but after the last row
                    Box(
                        Modifier.padding(horizontal = GRID_MARGIN).padding(bottom = if (r.index < last) MediaGridLayout.GAP.dp else 0.dp)
                            .fillMaxWidth().height(r.height.dp),
                    ) {
                        for (t in r.tiles) {
                            val at = byId[t.id] ?: continue
                            MediaTile(at.value, gallery, model, Modifier.offset(x = t.left.dp).size(t.width.dp, t.height.dp)) {
                                open = gallery.lightbox(at.index)
                            }
                        }
                    }
                }
            }
        }
    }
    }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }
    val box = open
    if (box != null) {
        Lightbox(box, gallery, model) {
            box.close()
            open = null
        }
    } else {
        GalleryNotices(gallery, model, snackbar)
    }
    if (namingChapter) NameDialog(stringResource(R.string.memory_create_chapter_title), onClose = { namingChapter = false }) { name ->
        model.createChapter(gallery, name)
    }
    state.purging?.let { PurgeQuestion(gallery, model) }
}

/**
 * A chapter's row picture (ADR 0136's Consequences, Memories): its cover, the stored picture core reads for one of this Memory's chapters
 * (`GalleryModel.chapterCover`), decoded small and cropped to the tile; the kind's icon where it has none.
 */
@Composable
private fun ChapterCover(gallery: GalleryModel, chapter: me.raddatz.veyq.mobile.navigation.NodeRef) {
    val palette = LocalPalette.current
    val cover by produceState<ImageBitmap?>(null, chapter.nodeId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            gallery.chapterCover(chapter.nodeId)?.let { bytes ->
                runCatching {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_PIXELS) sample *= 2
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val p = cover
    if (p != null) Image(p, null, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
    else Icon(kindIcon(chapter.kind), null, tint = role(palette.accent))
}

/** A row's cover is decoded at about this many pixels on its short edge (it is drawn 40 dp wide). */
private const val COVER_PIXELS = 160

/**
 * What [gallery] says in [snackbar]: a trashed medium's "Moved to trash" with its Undo for as long as core offers it
 * (`State.undo`, the node trash's window — the snackbar keeps no clock), and what a refused restore or a failed purge said
 * (`State.said`), once.
 */
@Composable
private fun GalleryNotices(gallery: GalleryModel, model: AppModel, snackbar: SnackbarHostState) {
    val state by gallery.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val undo = state.undo
    // core ends the offer: the effect restarts and the snackbar it showed goes with it
    LaunchedEffect(undo) {
        if (undo == null) return@LaunchedEffect
        val answer = snackbar.showSnackbar(context.text(undo), actionLabel = context.getString(R.string.trash_undo), duration = SnackbarDuration.Indefinite)
        if (answer == SnackbarResult.ActionPerformed) model.galleryAct { gallery.undo() }
    }
    val said = state.said
    LaunchedEffect(said) {
        if (said == null) return@LaunchedEffect
        snackbar.showSnackbar(context.text(said))
        gallery.seen()
    }
}

/** A guest's own medium's delete, asked first (core's `GalleryModel.purging`): it is purged for good, with no Undo. */
@Composable
private fun PurgeQuestion(gallery: GalleryModel, model: AppModel) {
    AlertDialog(
        onDismissRequest = { gallery.cancelAsk() },
        title = { Text(stringResource(R.string.memory_delete_own_title)) },
        text = { Text(say(gallery.purgeQuestion)) },
        confirmButton = {
            TextButton(onClick = { model.galleryAct { gallery.purge() } }) {
                Text(stringResource(R.string.memory_delete_own_accept), color = role(LocalPalette.current.danger))
            }
        },
        dismissButton = { TextButton(onClick = { gallery.cancelAsk() }) { Text(stringResource(R.string.trash_cancel)) } },
    )
}

/** The room between the justified grid and the screen's edges: the grid's own gap, so every side of a tile breathes alike. */
private val GRID_MARGIN = MediaGridLayout.GAP.dp

/**
 * One tile at the frame the justified grid gave it ([modifier]): the medium's grid tier cropped to fill (its aspect is the
 * tile's, bounded), a video marked; an icon where it has no tile to show.
 */
@Composable
private fun MediaTile(row: GalleryModel.Row, gallery: GalleryModel, model: AppModel, modifier: Modifier, onOpen: () -> Unit) {
    val palette = LocalPalette.current
    val picture by produceState<ImageBitmap?>(null, row.mediaId) {
        value = row.tile?.let { blob -> model.mediaPicture(blob) { gallery.tile(row.mediaId) } }
    }
    Box(modifier.background(role(palette.line)).clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
        val p = picture
        if (p != null) Image(p, row.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.Image, row.name, tint = role(palette.muted))
        if (row.isVideo) Icon(Icons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.size(32.dp))
    }
}

/**
 * The lightbox (the web's `MediaLightbox.vue`), drawn from core's `Lightbox`: the gallery paged through on black, each
 * medium's preview fitted, a video's poster with a play button that streams the original as the player reads it
 * ([MediaVideo]); its bar closes, names the place in the gallery, opens the details ([MediaDetailsSheet]) and saves the
 * original where the person picks, streamed (core's `FileSave`).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Lightbox(box: MemoryLightbox, gallery: GalleryModel, model: AppModel, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val state by box.state.collectAsStateWithLifecycle()
        val rows = state.rows
        if (rows.isEmpty()) {
            LaunchedEffect(Unit) { onClose() }
            return@Dialog
        }
        val pager = rememberPagerState(state.index.coerceAtLeast(0)) { rows.size }
        // the person's paging pages the model (and pauses its slideshow); the model's own step — the slideshow's, a
        // re-read that moved the medium — moves the pager, which then asks nothing back
        LaunchedEffect(pager.currentPage) { if (pager.currentPage != box.current.index) box.go(pager.currentPage) }
        LaunchedEffect(state.index) {
            if (state.index >= 0 && pager.currentPage != state.index) {
                if (state.slideshow == me.raddatz.veyq.mobile.memories.Lightbox.Slideshow.PLAYING) pager.animateScrollToPage(state.index) else pager.scrollToPage(state.index)
            }
        }
        // a picture zoomed in takes the swipe for its own pan (the platform's zoom, ADR 0136's Consequences): paging waits until it is back
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(state.index) { zoomed = false }
        val row = state.row ?: rows.first()
        val context = LocalContext.current
        val snackbar = remember { SnackbarHostState() }
        GalleryNotices(gallery, model, snackbar)
        var details by remember { mutableStateOf(false) }
        val save = state.save
        val saving = save?.state?.collectAsStateWithLifecycle()?.value?.let { it.end == null } == true
        // how a save ended, said once
        LaunchedEffect(save) {
            val ended = save?.state?.first { it.end != null } ?: return@LaunchedEffect
            snackbar.showSnackbar(context.text(ended.notice))
        }
        // the original goes where the person picks (the system's "create document"), streamed there as it is read
        val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) model.saveMedium(box, uri)
        }
        Scaffold(
            containerColor = Color.Black,
            contentColor = Color.White,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.memory_item_position, pager.currentPage + 1, rows.size)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.memory_close)) }
                    },
                    actions = {
                        // the slideshow is core's (its clock, its end on the last medium); here only its switch
                        val playing = state.slideshow == me.raddatz.veyq.mobile.memories.Lightbox.Slideshow.PLAYING
                        if (rows.size > 1) {
                            IconButton(onClick = { box.slideshow(!playing) }, enabled = playing || state.index < rows.size - 1) {
                                if (playing) Icon(Icons.Filled.Pause, stringResource(R.string.memory_slideshow_pause))
                                else Icon(Icons.Outlined.SlideshowIcon, stringResource(R.string.memory_slideshow_start))
                            }
                        }
                        IconButton(onClick = { details = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.memory_details)) }
                        if (!row.blocked) {
                            IconButton(onClick = { box.downloadName()?.let { saveTo.launch(it) } }, enabled = !saving) {
                                if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.Download, stringResource(R.string.memory_download))
                            }
                        }
                        if (gallery.deletes(row.mediaId)) {
                            IconButton(onClick = { model.galleryAct { box.trash() } }) { Icon(Icons.Outlined.Delete, stringResource(R.string.memory_trash)) }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black, titleContentColor = Color.White,
                        navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
                    ),
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                // a zoomed picture stays inside its page: the caption below is never drawn over
                // a trash shrinks the rows under the pager, which may ask for a page of its last count once more
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().clipToBounds(), key = { rows.getOrNull(it)?.mediaId ?: "gone:$it" }, userScrollEnabled = !zoomed) { page ->
                    val shown = rows.getOrNull(page) ?: return@HorizontalPager
                    val here = state.row?.mediaId == shown.mediaId
                    LightboxPage(shown, box, model, reader = state.reader.takeIf { here }, current = here, onZoom = { if (here) zoomed = it })
                }
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(row.name ?: "", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    row.capturedAt?.let {
                        Text(capturedText(it), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (details) {
            ModalBottomSheet(onDismissRequest = { details = false }) { MediaDetailsSheet(row, box) }
        }
    }
}

private fun capturedText(ms: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))

/** A picture being read: not yet, or read (null where it is no picture this device decodes). */
private class Read(val picture: ImageBitmap?)

/** One medium of the lightbox: its preview fitted, a video's poster with its play button, a blocked one said so. */
@Composable
private fun LightboxPage(row: GalleryModel.Row, box: MemoryLightbox, model: AppModel, reader: RangeReader?, current: Boolean, onZoom: (Boolean) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val said = row.said
        when {
            said != null -> Text(say(said), color = Color.White, modifier = Modifier.padding(24.dp))
            reader != null -> MediaVideo(row, reader, box)
            else -> {
                val read by produceState<Read?>(null, row.mediaId) {
                    // a video without a poster has none to show: its original is no picture
                    value = Read(row.picture?.let { blob -> model.mediaPicture(blob) { box.picture(row.mediaId) } })
                }
                val r = read
                when {
                    r == null -> CircularProgressIndicator(color = Color.White)
                    r.picture != null -> ZoomablePicture(r.picture, row.name, box, current, onZoom)
                    !row.isVideo -> Icon(Icons.Outlined.Image, row.name, tint = Color.White, modifier = Modifier.size(64.dp))
                }
                if (row.isVideo) {
                    FilledIconButton(onClick = { box.play() }, modifier = Modifier.size(72.dp)) {
                        Icon(Icons.Filled.PlayArrow, stringResource(R.string.memory_play), Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

/** The most a picture is zoomed in (pinch), and what a double tap zooms it to. */
private const val ZOOM_MAX = 5f
private const val ZOOM_TAP = 2.5f

/**
 * A lightbox's picture fitted, zoomed by the platform (ADR 0136's Consequences: a pinch, a double tap; the model knows nothing of it): zoomed in,
 * one finger pans it and the pager waits ([onZoom]); a page left is back at its fit. A touch pauses the slideshow (the web
 * pauses on any pointer).
 */
@Composable
private fun ZoomablePicture(picture: ImageBitmap, name: String?, box: MemoryLightbox, current: Boolean, onZoom: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    fun zoom(to: Float) {
        scale = to.coerceIn(1f, ZOOM_MAX)
        if (scale == 1f) offset = Offset.Zero
        onZoom(scale > 1f)
    }
    LaunchedEffect(current) { if (!current) zoom(1f) }
    val transform = rememberTransformableState { change, pan, _ ->
        box.slideshow(false)
        zoom(scale * change)
        if (scale > 1f) offset += pan
    }
    Image(
        picture, name,
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { box.slideshow(false) },
                    onDoubleTap = {
                        box.slideshow(false)
                        zoom(if (scale > 1f) 1f else ZOOM_TAP)
                    },
                )
            }
            .transformable(transform, canPan = { scale > 1f })
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        contentScale = ContentScale.Fit,
    )
}

/**
 * A video played as it is read (the web's range playback, ADR 0047): core's range reader under the shared player; the
 * lightbox's model holds the reader and closes it when it pages away or closes.
 */
@Composable
private fun MediaVideo(row: GalleryModel.Row, reader: RangeReader, box: MemoryLightbox) {
    val w = row.width
    val h = row.height
    val aspect = if (w != null && h != null) w.toFloat() / h else 16f / 9f
    // the player's view left (the page recomposed away): the model's reader goes with it
    DisposableEffect(reader) { onDispose { if (box.current.reader === reader) box.stop() } }
    ReaderPlayer(reader, row.mediaId, aspect) {
        Text(stringResource(R.string.memory_video_failed), color = Color.White, modifier = Modifier.padding(24.dp))
    }
}

/**
 * A medium's details (the web's details panel, core's `Lightbox.details`): the capture time and the file's facts at once,
 * the camera's once its EXIF tier is read, who added it (read once for the gallery), its place and position; "no details"
 * only where nothing is known.
 */
@Composable
private fun MediaDetailsSheet(row: GalleryModel.Row, box: MemoryLightbox) {
    val details by produceState<me.raddatz.veyq.mobile.memories.Lightbox.Details?>(null, row.mediaId) { value = box.details(row.mediaId) }
    val d = details
    val rows = d?.rows.orEmpty()
    val uploaderName = d?.uploader?.let { piece(MediaDetails.uploaderWords(it)) }
    // who added it stands before the place, as on the web
    val placeAt = d?.uploaderAt ?: 0
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.memory_details), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (d != null && rows.isEmpty() && uploaderName == null) {
            Text(stringResource(R.string.memory_no_detail), color = role(LocalPalette.current.muted), modifier = Modifier.padding(16.dp))
        }
        rows.forEachIndexed { i, r ->
            if (i == placeAt && uploaderName != null) DetailRow(stringResource(R.string.memory_field_uploaded_by), uploaderName)
            DetailRow(detailLabel(r.field), detailValue(r))
        }
        if (placeAt == rows.size && uploaderName != null) DetailRow(stringResource(R.string.memory_field_uploaded_by), uploaderName)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(value) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun detailLabel(field: MediaDetails.Field): String = say(MediaDetails.label(field))

@Composable
private fun detailValue(row: MediaDetails.Row): String {
    val context = LocalContext.current
    return when (val v = MediaDetails.shown(row)) {
        is MediaDetails.Shown.Words -> context.text(v.notice)
        is MediaDetails.Shown.Size -> android.text.format.Formatter.formatShortFileSize(context, v.bytes)
        is MediaDetails.Shown.Moment -> capturedText(v.epochMs)
        is MediaDetails.Shown.Plain -> v.text
    }
}
