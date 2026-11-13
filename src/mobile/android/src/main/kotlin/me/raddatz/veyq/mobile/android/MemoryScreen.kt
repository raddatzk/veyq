package me.raddatz.veyq.mobile.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.GalleryModel

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
 * a Memory holds, then every medium as a square tile of its thumbnail in the grid's order (newest first by capture time), a
 * video marked as one; a tile opens the lightbox ([Lightbox]). When it reads anew is the core's.
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
    CreateGridAction(stringResource(R.string.memory_add), shown = true, onClick = {
        if (model.mediaAccessAsked()) pickMedia() else askAccess.launch(mediaAccessPermissions())
    }) { grid, bottom ->
    LazyVerticalGrid(
        GridCells.Adaptive(112.dp), Modifier.fillMaxSize(), state = grid, contentPadding = PaddingValues(bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) { hero(16.dp) }
        if (state.chapters.isNotEmpty()) {
            item(key = "chapters", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.memory_chapters), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            }
            items(state.chapters, key = { "chapter:" + it.nodeId }, span = { GridItemSpan(maxLineSpan) }) { c ->
                ListItem(
                    headlineContent = { Text(c.name) },
                    leadingContent = { Icon(kindIcon(c.kind), null, tint = role(palette.accent)) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickableRow { c.asObject()?.let(model::open) },
                )
            }
        }
        when {
            state.failed -> item(key = "failed", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.memory_gallery_failed), color = role(palette.danger), modifier = Modifier.padding(16.dp))
            }
            rows == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            rows.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.memory_gallery_empty), color = role(palette.muted), modifier = Modifier.padding(16.dp))
            }
            else -> {
                item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        pluralStringResource(R.plurals.memory_items, rows.size, rows.size), style = MaterialTheme.typography.bodySmall,
                        color = role(palette.muted), modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(rows, key = { it.mediaId }) { row ->
                    MediaTile(row, gallery, model) { open = gallery.lightbox(rows.indexOf(row)) }
                }
            }
        }
    }
    }
    val box = open
    if (box != null) {
        Lightbox(box, model) {
            box.close()
            open = null
        }
    }
}

/** One square tile: the medium's grid tier cropped to fill, a video marked; an icon where it has no tile to show. */
@Composable
private fun MediaTile(row: GalleryModel.Row, gallery: GalleryModel, model: AppModel, onOpen: () -> Unit) {
    val palette = LocalPalette.current
    val picture by produceState<ImageBitmap?>(null, row.mediaId) {
        value = row.tile?.let { blob -> model.mediaPicture(blob) { gallery.tile(row.mediaId) } }
    }
    Box(Modifier.aspectRatio(1f).background(role(palette.line)).clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
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
private fun Lightbox(box: MemoryLightbox, model: AppModel, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val state by box.state.collectAsStateWithLifecycle()
        val rows = state.rows
        if (rows.isEmpty()) {
            LaunchedEffect(Unit) { onClose() }
            return@Dialog
        }
        val pager = rememberPagerState(state.index.coerceAtLeast(0)) { rows.size }
        // the pager pages the model; a re-read that moved the medium moves the pager
        LaunchedEffect(pager.currentPage) { box.go(pager.currentPage) }
        LaunchedEffect(state.index) { if (state.index >= 0 && pager.currentPage != state.index) pager.scrollToPage(state.index) }
        val row = state.row ?: rows.first()
        val context = LocalContext.current
        val snackbar = remember { SnackbarHostState() }
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
                        IconButton(onClick = { details = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.memory_details)) }
                        if (!row.blocked) {
                            IconButton(onClick = { box.downloadName()?.let { saveTo.launch(it) } }, enabled = !saving) {
                                if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.Download, stringResource(R.string.memory_download))
                            }
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
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { rows[it].mediaId }) { page ->
                    val shown = rows[page]
                    LightboxPage(shown, box, model, reader = state.reader.takeIf { state.row?.mediaId == shown.mediaId })
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
private fun LightboxPage(row: GalleryModel.Row, box: MemoryLightbox, model: AppModel, reader: RangeReader?) {
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
                    r.picture != null -> Image(r.picture, row.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
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
