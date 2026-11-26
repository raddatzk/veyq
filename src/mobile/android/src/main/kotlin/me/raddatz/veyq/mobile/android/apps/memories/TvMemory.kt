package me.raddatz.veyq.mobile.android.apps.memories

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.TvGrid
import me.raddatz.veyq.mobile.android.core.TvReaderPlayer
import me.raddatz.veyq.mobile.android.core.TvSection
import me.raddatz.veyq.mobile.android.core.TvTile
import me.raddatz.veyq.mobile.android.core.kindIcon
import me.raddatz.veyq.mobile.android.core.moment
import me.raddatz.veyq.mobile.android.core.say
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.Lightbox
import me.raddatz.veyq.mobile.navigation.ObjectRef

/**
 * A Memory's or a chapter's page on a TV (ADR 0159 decision 3): its chapters, then its photos and videos as tiles in the
 * gallery's order (core's `GalleryModel`, its rights a TV's — read only), *Start slideshow* first; a tile opens the
 * lightbox ([TvLightbox]). Not its map, its music list or its contributions: a TV views the pictures, the slideshow
 * plays the Memory's music. Live while it is on screen, as the phone's page.
 */
@Composable
internal fun TvMemoryPage(ref: ObjectRef, model: AppModel) {
    val gallery = remember(ref.at) { model.memories.galleryModel(ref.at) }
    if (gallery == null) {
        CircularProgressIndicator(Modifier.padding(16.dp))
        return
    }
    val state by gallery.state.collectAsStateWithLifecycle()
    var open by remember(gallery) { mutableStateOf(gallery.openLightbox) }
    // a photo the search opened (ADR 0146 decision 8): the gallery opened its lightbox on it
    LaunchedEffect(state.searchOpened) {
        if (state.searchOpened == null) return@LaunchedEffect
        open = gallery.openLightbox
        gallery.searchOpenedSeen()
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
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
    val rows = state.shown
    val slideshowFocus = remember { FocusRequester() }
    LaunchedEffect(gallery, rows.isNullOrEmpty()) { if (!rows.isNullOrEmpty()) runCatching { slideshowFocus.requestFocus() } }
    TvGrid {
        item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!rows.isNullOrEmpty()) {
                    Button(onClick = { open = slideshow(gallery, gallery.lightbox(0)) }, modifier = Modifier.focusRequester(slideshowFocus).testTag("tv-slideshow")) {
                        Icon(Icons.Outlined.Slideshow, null, Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.memory_slideshow_start))
                    }
                    Text(pluralStringResource(R.plurals.memory_items, rows.size, rows.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.chapters.isNotEmpty()) {
            item(key = "chapters", span = { GridItemSpan(maxLineSpan) }) { TvSection(stringResource(R.string.memory_chapters)) }
            items(state.chapters, key = { "chapter:" + it.nodeId }) { c ->
                val cover by produceState<ImageBitmap?>(null, c.nodeId) { value = decoded(gallery.chapterCover(c.nodeId)) }
                TvTile(c.name, null, cover, kindIcon(c.kind)) { GalleryModel.rowPage(c)?.let(model::open) }
            }
        }
        val failure = state.failure
        when {
            failure != null -> item(key = "failed", span = { GridItemSpan(maxLineSpan) }) { Text(say(failure), color = MaterialTheme.colorScheme.error) }
            rows == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            rows.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.memory_gallery_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> itemsIndexed(rows, key = { _, r -> "media:" + r.mediaId }) { i, r ->
                MediaCard(r, gallery, model) { open = gallery.lightbox(i) }
            }
        }
    }
    open?.let { box ->
        TvLightbox(box, gallery, model) {
            box.close()
            open = null
        }
    }
}

/** The slideshow started on [box] with the Memory's music (ADR 0159 decision 3: "the slideshow with the Memory's music"). */
private fun slideshow(gallery: GalleryModel, box: Lightbox): Lightbox {
    if (gallery.current.hasMusic && !gallery.current.musicHere) gallery.playMusic()
    box.slideshow(true)
    return box
}

/** A medium's tile: its thumbnail, a video marked as one. */
@Composable
private fun MediaCard(row: GalleryModel.Row, gallery: GalleryModel, model: AppModel, onOpen: () -> Unit) {
    val picture by produceState<ImageBitmap?>(null, row.mediaId) {
        value = row.tile?.let { blob -> model.memories.mediaPicture(blob) { gallery.tile(row.mediaId) } }
    }
    Card(onClick = onOpen, modifier = Modifier.testTag("tv-media")) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            val p = picture
            if (p != null) Image(p, row.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Outlined.PhotoLibrary, row.name, Modifier.size(40.dp))
            if (row.isVideo) Icon(Icons.Outlined.PlayCircle, stringResource(R.string.memory_play), Modifier.size(40.dp), tint = Color.White)
        }
    }
}

/**
 * The lightbox on a TV, drawn from core's [Lightbox]: one medium on black, the remote paging it — left and right step
 * (pausing the slideshow, as the person's paging does on every app), the centre key plays a video or starts and pauses
 * the slideshow, the media key does the same —, the close and the slideshow's switch above it, its name and time under
 * it. A video plays in [TvReaderPlayer] (its keys its own); Back leaves the video first, then the lightbox. The slideshow
 * and a video keep the idle lock waiting.
 */
@Composable
private fun TvLightbox(box: Lightbox, gallery: GalleryModel, model: AppModel, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = { if (box.current.reader != null) box.stop() else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val state by box.state.collectAsStateWithLifecycle()
        val rows = state.rows
        if (rows.isEmpty()) {
            LaunchedEffect(Unit) { onClose() }
            return@Dialog
        }
        val row = state.row ?: rows.first()
        val playing = state.slideshow == Lightbox.Slideshow.PLAYING
        DisposableEffect(playing) {
            model.playing(SLIDESHOW, playing)
            onDispose { model.playing(SLIDESHOW, false) }
        }
        DisposableEffect(box) { onDispose { model.playing(VIDEO, false) } }
        val picture = remember { FocusRequester() }
        LaunchedEffect(state.reader == null) { if (state.reader == null) runCatching { picture.requestFocus() } }
        fun toggleSlideshow() {
            if (box.current.slideshow == Lightbox.Slideshow.PLAYING) box.slideshow(false) else slideshow(gallery, box)
        }
        Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp).testTag("tv-lightbox")) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onClose, modifier = Modifier.testTag("tv-lightbox-close")) { Icon(Icons.Outlined.Close, stringResource(R.string.memory_close)) }
                if (state.canPage) {
                    Button(onClick = ::toggleSlideshow, modifier = Modifier.testTag("tv-lightbox-slideshow")) {
                        if (playing) Icon(Icons.Filled.Pause, stringResource(R.string.memory_slideshow_pause))
                        else Icon(Icons.Outlined.Slideshow, stringResource(R.string.memory_slideshow_start))
                    }
                }
                // the shuffle beside it (the web's, from three media on): a TV's selectable button, selected while on
                if (state.canShuffle) {
                    val words = stringResource(R.string.memory_slideshow_shuffle)
                    androidx.tv.material3.IconButton(
                        onClick = { box.shuffle(!state.shuffled) },
                        colors = if (state.shuffled) androidx.tv.material3.IconButtonDefaults.colors(containerColor = Color.White, contentColor = Color.Black)
                        else androidx.tv.material3.IconButtonDefaults.colors(),
                        modifier = Modifier.testTag("tv-lightbox-shuffle").semantics {
                            toggleableState = androidx.compose.ui.state.ToggleableState(state.shuffled)
                        },
                    ) { Icon(Icons.Outlined.Shuffle, words) }
                }
                Text(stringResource(R.string.memory_item_position, state.index + 1, rows.size), color = Color.White, modifier = Modifier.testTag("tv-lightbox-position"))
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                val said = row.said
                val reader = state.reader
                if (reader != null) {
                    TvReaderPlayer(reader, row.mediaId, Modifier.fillMaxSize(), onPlaying = { on ->
                        box.videoPlaying(on)
                        model.playing(VIDEO, on)
                    }) { Text(stringResource(R.string.memory_video_failed), color = Color.White) }
                } else {
                    var focused by remember { mutableStateOf(false) }
                    val label = row.name ?: stringResource(R.string.memory_item_position, state.index + 1, rows.size)
                    Box(
                        Modifier.fillMaxSize()
                            .focusRequester(picture)
                            .onFocusChanged { focused = it.isFocused }
                            .onKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                                // the model's state now, not the one this frame was drawn with: presses come faster than frames
                                val now = box.current
                                when (e.key) {
                                    // round the ends (core's `next` / `previous`)
                                    Key.DirectionLeft -> { box.previous(); true }
                                    Key.DirectionRight -> { box.next(); true }
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                        val shown = now.row
                                        if (shown != null && shown.isVideo && shown.said == null) box.play() else toggleSlideshow()
                                        true
                                    }
                                    Key.MediaPlayPause -> { toggleSlideshow(); true }
                                    else -> false
                                }
                            }
                            .focusable()
                            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White.copy(alpha = 0.4f) else Color.Transparent)
                            .semantics { contentDescription = label }
                            .testTag("tv-lightbox-medium"),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            said != null -> Text(say(said), color = Color.White)
                            else -> {
                                val shown by produceState<ImageBitmap?>(null, row.mediaId) {
                                    value = row.picture?.let { blob -> model.memories.mediaPicture(blob) { box.picture(row.mediaId) } }
                                }
                                val s = shown
                                if (s != null) Image(s, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                else if (!row.isVideo) CircularProgressIndicator(color = Color.White)
                                if (row.isVideo) Icon(Icons.Outlined.PlayCircle, stringResource(R.string.memory_play), Modifier.size(72.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }
            row.name?.let { Text(it, color = Color.White, style = MaterialTheme.typography.titleSmall) }
            row.capturedAt?.let { Text(moment(it), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** A chapter's cover decoded small for its tile; null where it has none or it does not decode. */
private suspend fun decoded(bytes: ByteArray?): ImageBitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    bytes?.let {
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_PIXELS) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }
}

/** A chapter tile's cover is decoded at about this many pixels on its short edge. */
private const val COVER_PIXELS = 270

/** The sources of what plays here, as the app model's idle lock counts them. */
private const val SLIDESHOW = "slideshow"
private const val VIDEO = "video"
