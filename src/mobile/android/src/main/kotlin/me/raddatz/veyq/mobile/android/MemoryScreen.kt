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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef

/** What full media access is on this Android: the photos and videos themselves, and their locations. */
private fun mediaAccessPermissions(): Array<String> =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.ACCESS_MEDIA_LOCATION)
    } else {
        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

/**
 * A Memory's or a chapter's page (Memories' `MemoryDetail.vue`, its gallery): the chapters a Memory holds, then every
 * medium as a square tile of its thumbnail in the grid's order (newest first by capture time), a video marked as one; a
 * tile opens the lightbox ([Lightbox]).
 */
@Composable
internal fun MemoryScreen(ref: ObjectRef, model: AppModel) {
    val palette = LocalPalette.current
    val details by model.rowDetails.collectAsStateWithLifecycle()
    val uploaded by model.galleryRevision.collectAsStateWithLifecycle()
    // read again whenever the lists are
    var gallery by remember { mutableStateOf<MemoryMedia.Gallery?>(null) }
    var failed by remember { mutableStateOf(false) }
    var chapters by remember { mutableStateOf<List<NodeRef>>(emptyList()) }
    LaunchedEffect(ref.nodeId, details, uploaded) {
        val read = model.memoryGallery(ref.scopeId, ref.nodeId)
        if (read == null) failed = true else { gallery = read; failed = false }
        chapters = if (ref.kind == "MEMORY") model.scopeNodes(ref.scopeId).filter { it.parentId == ref.nodeId && it.kind == "CHAPTER" } else emptyList()
    }
    var open by remember { mutableStateOf<Int?>(null) }
    val hero = LocalNodeHero.current
    val items = gallery?.items
    // the system's photo picker: photos and videos, several at once
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) model.uploadMedia(ref.scopeId, ref.nodeId, ref.name, uris)
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
        if (chapters.isNotEmpty()) {
            item(key = "chapters", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.memory_chapters), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            }
            items(chapters, key = { "chapter:" + it.nodeId }, span = { GridItemSpan(maxLineSpan) }) { c ->
                ListItem(
                    headlineContent = { Text(c.name) },
                    leadingContent = { Icon(kindIcon(c.kind), null, tint = role(palette.accent)) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickableRow { c.asObject()?.let(model::open) },
                )
            }
        }
        when {
            failed -> item(key = "failed", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.memory_gallery_failed), color = role(palette.danger), modifier = Modifier.padding(16.dp))
            }
            items == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            items.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.memory_gallery_empty), color = role(palette.muted), modifier = Modifier.padding(16.dp))
            }
            else -> {
                item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        pluralStringResource(R.plurals.memory_items, items.size, items.size), style = MaterialTheme.typography.bodySmall,
                        color = role(palette.muted), modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(items, key = { it.mediaId }) { item ->
                    MediaTile(item, model) { open = items.indexOf(item) }
                }
            }
        }
    }
    }
    val at = open
    if (at != null && items != null) Lightbox(items, at, ref.name, model) { open = null }
}

/** One square tile: the medium's grid tier cropped to fill, a video marked; an icon where it has no tile to show. */
@Composable
private fun MediaTile(item: MemoryMedia.Item, model: AppModel, onOpen: () -> Unit) {
    val palette = LocalPalette.current
    val picture by produceState<ImageBitmap?>(null, item.mediaId) { value = item.gridBlob?.let { model.mediaPicture(item, it) } }
    Box(Modifier.aspectRatio(1f).background(role(palette.line)).clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
        val p = picture
        if (p != null) Image(p, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.Image, item.name, tint = role(palette.muted))
        if (item.isVideo) Icon(Icons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.size(32.dp))
    }
}

/**
 * The lightbox (the web's `MediaLightbox.vue`): the gallery paged through on black, each medium's preview fitted, a video's
 * poster with a play button that streams the original as the player reads it ([MediaVideo]); its bar closes, names the
 * place in the gallery, opens the details ([MediaDetailsSheet]) and saves the original where the person picks, streamed.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Lightbox(items: List<MemoryMedia.Item>, start: Int, memoryName: String, model: AppModel, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(start) { items.size }
        val item = items[pager.currentPage.coerceIn(0, items.size - 1)]
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var details by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf<MemoryMedia.Item?>(null) }
        val uploaders by produceState<Map<String, MediaDetails.Uploader>>(emptyMap(), items) { value = model.mediaUploaders(items.first().scopeId, items) }
        // the original goes where the person picks (the system's "create document"), streamed there as it is read
        val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val medium = saving
            if (uri == null || medium == null) {
                saving = null
                return@rememberLauncherForActivityResult
            }
            val name = MediaDetails.downloadName(medium, memoryName, items.indexOf(medium))
            scope.launch {
                val out = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
                val done = out?.use { model.downloadMedium(medium, it) } == true
                // half an original is no download: the document goes again
                if (!done) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                saving = null
                snackbar.showSnackbar(context.getString(if (done) R.string.download_done else R.string.download_failed, name))
            }
        }
        Scaffold(
            containerColor = Color.Black,
            contentColor = Color.White,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.memory_item_position, pager.currentPage + 1, items.size)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.memory_close)) }
                    },
                    actions = {
                        IconButton(onClick = { details = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.memory_details)) }
                        if (!item.blocked) {
                            IconButton(
                                onClick = {
                                    saving = item
                                    saveTo.launch(MediaDetails.downloadName(item, memoryName, pager.currentPage))
                                },
                                enabled = saving == null,
                            ) {
                                if (saving != null) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
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
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { items[it].mediaId }) { page ->
                    LightboxPage(items[page], model, current = pager.currentPage == page)
                }
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(item.name ?: "", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    item.meta?.effectiveCapturedAt?.let {
                        Text(capturedText(it), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (details) {
            ModalBottomSheet(onDismissRequest = { details = false }) { MediaDetailsSheet(item, model, uploaders) }
        }
    }
}

private fun capturedText(ms: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))

/** A picture being read: not yet, or read (null where it is no picture this device decodes). */
private class Read(val picture: ImageBitmap?)

/** One medium of the lightbox: its preview fitted, a video's poster with its play button, a blocked one said so. */
@Composable
private fun LightboxPage(item: MemoryMedia.Item, model: AppModel, current: Boolean) {
    var playing by remember(item.mediaId) { mutableStateOf(false) }
    // a video stops when it is paged away from
    LaunchedEffect(current) { if (!current) playing = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            item.blocked -> Text(stringResource(R.string.memory_blocked), color = Color.White, modifier = Modifier.padding(24.dp))
            playing -> MediaVideo(item, model)
            else -> {
                val read by produceState<Read?>(null, item.mediaId) {
                    // a video without a poster has none to show: its original is no picture
                    value = Read(if (item.isVideo && item.preview == null) null else model.mediaPicture(item, item.lightboxBlob))
                }
                val r = read
                when {
                    r == null -> CircularProgressIndicator(color = Color.White)
                    r.picture != null -> Image(r.picture, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    !item.isVideo -> Icon(Icons.Outlined.Image, item.name, tint = Color.White, modifier = Modifier.size(64.dp))
                }
                if (item.isVideo) {
                    FilledIconButton(onClick = { playing = true }, modifier = Modifier.size(72.dp)) {
                        Icon(Icons.Filled.PlayArrow, stringResource(R.string.memory_play), Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

/**
 * A video played as it is read (the web's range playback, ADR 0047): the platform's player reads the original through a
 * [MediaDataSource] over core's [RangeReader] — each range from the frames that hold it, opened on the device —, so the
 * plaintext is never held whole nor written anywhere. The platform's media controls; a tap shows or hides them.
 */
@Composable
private fun MediaVideo(item: MemoryMedia.Item, model: AppModel) {
    val context = LocalContext.current
    val reader = remember(item.mediaId) { model.mediaReader(item) }
    var failed by remember(item.mediaId) { mutableStateOf(reader == null) }
    var aspect by remember(item.mediaId) {
        val m = item.meta
        mutableStateOf(if (m?.width != null && m.height != null) m.width!!.toFloat() / m.height!! else 16f / 9f)
    }
    if (failed || reader == null) {
        Text(stringResource(R.string.memory_video_failed), color = Color.White, modifier = Modifier.padding(24.dp))
        return
    }
    val player = remember(item.mediaId) { android.media.MediaPlayer() }
    val controller = remember(item.mediaId) { android.widget.MediaController(context) }
    val prepared = remember(item.mediaId) { booleanArrayOf(false) }
    DisposableEffect(player) {
        onDispose {
            controller.hide()
            player.release()
        }
    }
    Box(
        Modifier.aspectRatio(aspect).clickable(interactionSource = null, indication = null) {
            if (controller.isShowing) controller.hide() else controller.show()
        },
    ) {
        AndroidView(
            factory = { c ->
                // a texture, not a surface view: it is drawn in the dialog's window, so it pages and fades with it
                android.view.TextureView(c).also { view ->
                    player.setDataSource(ReaderSource(reader))
                    player.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h }
                    player.setOnErrorListener { _, _, _ ->
                        failed = true
                        true
                    }
                    player.setOnPreparedListener {
                        it.start()
                        controller.show()
                    }
                    view.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            runCatching { player.setSurface(android.view.Surface(texture)) }
                            if (!prepared[0]) {
                                prepared[0] = true
                                player.prepareAsync()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                            runCatching { player.setSurface(null) }
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit
                    }
                    controller.setMediaPlayer(PlayerControl(player))
                    controller.setAnchorView(view)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The player reads its ranges from [reader] (on its own thread, which waits for the frames). */
private class ReaderSource(private val reader: RangeReader) : android.media.MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= reader.size) return -1
        val bytes = try {
            runBlocking { reader.read(position, size) }
        } catch (e: Exception) {
            throw java.io.IOException(e)
        }
        if (bytes.isEmpty()) return -1
        bytes.copyInto(buffer, offset)
        return bytes.size
    }

    override fun getSize(): Long = reader.size
    override fun close() = Unit
}

/** The platform's media controls over [player]; a call after it was released does nothing. */
private class PlayerControl(private val player: android.media.MediaPlayer) : android.widget.MediaController.MediaPlayerControl {
    override fun start() { runCatching { player.start() } }
    override fun pause() { runCatching { player.pause() } }
    override fun getDuration(): Int = runCatching { player.duration }.getOrDefault(0)
    override fun getCurrentPosition(): Int = runCatching { player.currentPosition }.getOrDefault(0)
    override fun seekTo(pos: Int) { runCatching { player.seekTo(pos) } }
    override fun isPlaying(): Boolean = runCatching { player.isPlaying }.getOrDefault(false)
    override fun getBufferPercentage(): Int = 0
    override fun canPause(): Boolean = true
    override fun canSeekBackward(): Boolean = true
    override fun canSeekForward(): Boolean = true
    override fun getAudioSessionId(): Int = runCatching { player.audioSessionId }.getOrDefault(0)
}

/**
 * A medium's details (the web's details panel): the capture time and the file's facts at once, the camera's once its
 * EXIF tier is read, who added it, its place and position; "no details" only where nothing is known.
 */
@Composable
private fun MediaDetailsSheet(item: MemoryMedia.Item, model: AppModel, uploaders: Map<String, MediaDetails.Uploader>) {
    val exif by produceState<MediaDetails.Exif?>(null, item.mediaId) { value = model.mediaExif(item) }
    val rows = MediaDetails.rows(item, exif)
    val uploader = item.ownerKey?.let { uploaders[it] }
    val uploaderName = when (uploader?.kind) {
        MediaDetails.UploaderKind.MEMBER -> uploader.name
        MediaDetails.UploaderKind.FORMER -> stringResource(R.string.memory_uploader_former)
        MediaDetails.UploaderKind.GUEST -> stringResource(R.string.memory_uploader_guest)
        null -> null
    }
    // who added it stands before the place, as on the web
    val placeAt = rows.indexOfFirst { it.field == MediaDetails.Field.PLACE || it.field == MediaDetails.Field.LOCATION }.let { if (it < 0) rows.size else it }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.memory_details), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (rows.isEmpty() && uploaderName == null) {
            Text(stringResource(R.string.memory_no_detail), color = role(LocalPalette.current.muted), modifier = Modifier.padding(16.dp))
        }
        rows.forEachIndexed { i, row ->
            if (i == placeAt && uploaderName != null) DetailRow(stringResource(R.string.memory_field_uploaded_by), uploaderName)
            DetailRow(detailLabel(row.field), detailValue(row))
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
private fun detailLabel(field: MediaDetails.Field): String = stringResource(
    when (field) {
        MediaDetails.Field.CAPTURED -> R.string.memory_field_captured
        MediaDetails.Field.FILE_NAME -> R.string.memory_field_file_name
        MediaDetails.Field.FILE_SIZE -> R.string.memory_field_file_size
        MediaDetails.Field.FILE_FORMAT -> R.string.memory_field_file_format
        MediaDetails.Field.CAMERA -> R.string.memory_field_camera
        MediaDetails.Field.LENS -> R.string.memory_field_lens
        MediaDetails.Field.FOCAL_LENGTH -> R.string.memory_field_focal_length
        MediaDetails.Field.FOCAL_LENGTH_35 -> R.string.memory_field_focal_length_35
        MediaDetails.Field.APERTURE -> R.string.memory_field_aperture
        MediaDetails.Field.EXPOSURE -> R.string.memory_field_exposure
        MediaDetails.Field.EXPOSURE_COMPENSATION -> R.string.memory_field_exposure_compensation
        MediaDetails.Field.ISO -> R.string.memory_field_iso
        MediaDetails.Field.FLASH -> R.string.memory_field_flash
        MediaDetails.Field.DIMENSIONS -> R.string.memory_field_dimensions
        MediaDetails.Field.TITLE -> R.string.memory_field_title
        MediaDetails.Field.DESCRIPTION -> R.string.memory_field_description
        MediaDetails.Field.RATING -> R.string.memory_field_rating
        MediaDetails.Field.SOFTWARE -> R.string.memory_field_software
        MediaDetails.Field.ARTIST -> R.string.memory_field_artist
        MediaDetails.Field.COPYRIGHT -> R.string.memory_field_copyright
        MediaDetails.Field.ALTITUDE -> R.string.memory_field_altitude
        MediaDetails.Field.DIRECTION_TRUE, MediaDetails.Field.DIRECTION_MAGNETIC -> R.string.memory_field_direction
        MediaDetails.Field.GPS_ACCURACY -> R.string.memory_field_gps_accuracy
        MediaDetails.Field.CORRECTED -> R.string.memory_field_status
        MediaDetails.Field.PLACE -> R.string.memory_field_place
        MediaDetails.Field.LOCATION -> R.string.memory_field_location
    },
)

@Composable
private fun detailValue(row: MediaDetails.Row): String = when (row.field) {
    MediaDetails.Field.CAPTURED -> capturedText(row.number ?: 0)
    MediaDetails.Field.FILE_SIZE -> android.text.format.Formatter.formatShortFileSize(LocalContext.current, row.number ?: 0)
    MediaDetails.Field.FLASH -> stringResource(if (row.number == 1L) R.string.memory_flash_fired else R.string.memory_flash_not_fired)
    MediaDetails.Field.DIRECTION_TRUE -> stringResource(R.string.memory_direction_true, (row.number ?: 0).toInt())
    MediaDetails.Field.DIRECTION_MAGNETIC -> stringResource(R.string.memory_direction_magnetic, (row.number ?: 0).toInt())
    MediaDetails.Field.CORRECTED -> stringResource(R.string.memory_corrected)
    else -> row.value
}
