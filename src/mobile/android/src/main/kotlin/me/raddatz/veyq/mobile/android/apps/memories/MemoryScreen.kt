package me.raddatz.veyq.mobile.android.apps.memories

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Slideshow as SlideshowIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.CreateAction
import me.raddatz.veyq.mobile.android.core.EdgeToEdgeDialogWindow
import me.raddatz.veyq.mobile.android.core.LocalNodeHero
import me.raddatz.veyq.mobile.android.core.LocalPalette
import me.raddatz.veyq.mobile.android.core.MusicCompactControls
import me.raddatz.veyq.mobile.android.core.NameDialog
import me.raddatz.veyq.mobile.android.core.ReaderPlayer
import me.raddatz.veyq.mobile.android.core.ReportSheet
import me.raddatz.veyq.mobile.android.core.clickableRow
import me.raddatz.veyq.mobile.android.core.kindIcon
import me.raddatz.veyq.mobile.android.core.moment
import me.raddatz.veyq.mobile.android.core.piece
import me.raddatz.veyq.mobile.android.core.role
import me.raddatz.veyq.mobile.android.core.say
import me.raddatz.veyq.mobile.android.core.sharedFilesDrop
import me.raddatz.veyq.mobile.android.core.show
import me.raddatz.veyq.mobile.android.core.size
import me.raddatz.veyq.mobile.android.core.text
import me.raddatz.veyq.mobile.android.core.trash
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MediaGridLayout
import me.raddatz.veyq.mobile.navigation.ObjectRef

/** Core's lightbox, named apart from this file's composable of the same name. */
private typealias MemoryLightbox = me.raddatz.veyq.mobile.memories.Lightbox

/** What full media access is on this Android: the photos and videos themselves, and their locations. */
internal fun mediaAccessPermissions(): Array<String> =
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
    val gallery = remember(ref.at) { model.memories.galleryModel(ref.at) }
    if (gallery == null) {
        CircularProgressIndicator(Modifier.padding(16.dp))
        return
    }
    MemoryGallery(gallery, model)
}

/**
 * [gallery]'s page as [MemoryScreen] draws it (a debug build's `DevMemory` draws it over a played gallery). A link's guest
 * ([guest], ADR 0150 decision 5) sees the member's page with a guest's rights: its chapters open inside the link
 * ([onChapter]), it adds once it named itself ([canAdd]), and what is the account's — the map's tracks, the music player,
 * the pick mode of a share from another app — is not offered.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MemoryGallery(
    gallery: GalleryModel, model: AppModel, guest: Boolean = false, canAdd: Boolean = true,
    onChapter: ((me.raddatz.veyq.mobile.navigation.NodeRef) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val state by gallery.state.collectAsStateWithLifecycle()
    // the lightbox is the gallery's (core's, held by the app model): an activity a rotation recreated shows it again (#934)
    var open by remember(gallery) { mutableStateOf(gallery.openLightbox) }
    // a photo the search opened (ADR 0146 decision 8): the gallery opened its lightbox on it; shown, then said seen
    LaunchedEffect(state.searchOpened) {
        if (state.searchOpened == null) return@LaunchedEffect
        open = gallery.openLightbox
        gallery.searchOpenedSeen()
    }
    val hero = LocalNodeHero.current
    // the rows the filter shows (ADR 0149 decision 5): the grid's, and the lightbox's pages
    val rows = state.shown
    // the system's photo picker: photos and videos, several at once
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) model.memories.uploadMedia(gallery, uris)
    }
    fun pickMedia() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    // the picker hands a photo over with its location zeroed and under a number: full media access keeps both, so it is
    // asked for once, before the first pick; whatever the answer, the picker opens
    val askAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { pickMedia() }
    fun add() = if (model.memories.mediaAccessAsked()) pickMedia() else askAccess.launch(mediaAccessPermissions())
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
    // in the pick mode the banner offers Add here on this page (ADR 0138 decision 3)
    if (!guest) androidx.compose.runtime.DisposableEffect(gallery) {
        model.memories.shareTarget(gallery, true)
        onDispose { model.memories.shareTarget(gallery, false) }
    }
    // what the page adds: a chapter where it makes one (core's `GalleryModel.creates`), and a guest's photos and videos
    val chapterHere = me.raddatz.veyq.mobile.memories.MemoryCreate.CHAPTER in gallery.creates
    var namingChapter by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // photos or music (ADR 0142 decision 6): the switch shows where the node has music or whoever looks may add some
    var view by androidx.compose.runtime.saveable.rememberSaveable(gallery) { mutableStateOf(MemoryView.PHOTOS) }
    val musicOffered = !guest && me.raddatz.veyq.mobile.memories.music.MusicModel.shown(state)
    // Photos | Music | Map: Map always, as the web's tab is (ADR 0143 D8)
    // Photos | Music | Map | Contributions: the contributions where core offers them to whoever looks (ADR 0149 decision 6)
    val contributionsOffered = state.rights.buckets && me.raddatz.veyq.mobile.memories.Contributions.OFFERED
    val viewSwitch: @Composable () -> Unit = { MemoryViewSwitch(view, musicOffered, contributionsOffered, map = !guest) { view = it } }
    val music = if (musicOffered && view == MemoryView.MUSIC) remember(gallery) { model.memories.musicModel(gallery) } else null
    val tracks = if (view == MemoryView.MAP) remember(gallery) { model.memories.trackList(gallery) } else null
    val contributions = if (contributionsOffered && view == MemoryView.CONTRIBUTIONS) remember(gallery) { model.memories.contributions(gallery) } else null
    // photos and videos dropped from another app onto the gallery: core's one drop judges and sorts (ADR 0154 decision 1).
    // Only where the gallery's media show: the Music and Map views take no drop, as the web's tabs take none (#1054)
    val dropped = Modifier.sharedFilesDrop { drop -> model.drop(me.raddatz.veyq.mobile.composition.DropTarget.Gallery(gallery), drop) }
    // a member adds photos through the header's Upload (core's `GalleryModel.headerActions`); a guest's link page has no
    // core header, so its page offers them here
    val adds = guest && state.rights.upload && canAdd
    Box(Modifier.fillMaxSize().then(if (!guest && music == null && tracks == null) dropped else Modifier)) {
    if (tracks != null) MemoryMapView(gallery, tracks, model, snackbar, viewSwitch) else
    if (music != null) MusicView(gallery, music, model, snackbar, viewSwitch) else
    if (contributions != null) ContributionsView(gallery, contributions, model, snackbar, viewSwitch) { id -> contributions.lightbox(id)?.let { open = it } } else
    CreateAction(
        stringResource(if (adds) R.string.memory_add else R.string.memory_add_chapter),
        shown = adds || chapterHere,
        onClick = { if (adds) add() else namingChapter = true },
        menu = if (adds && chapterHere) { close ->
            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.memory_add)) }, onClick = { close(); add() })
            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.memory_add_chapter)) }, onClick = { close(); namingChapter = true })
        } else null,
    ) { list, bottom ->
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // the justified grid (core's `GalleryModel.layout`, the web's `MediaGrid.vue`): laid out for the width between the
    // page's margins, each row an item of the lazy list, so only the rows near the viewport are composed (ADR 0105)
    val gridWidth = (maxWidth - GRID_MARGIN * 2).value.toDouble()
    // what this device sends here leads the grid as placeholder tiles (ADR 0145 decision 3): laid out anew when one comes or
    // goes, never on a progress step
    val uploading = state.uploading
    val uploadIds = uploading.map { it.id }
    val layout = remember(rows, uploadIds, gridWidth) { rows?.let { GalleryModel.layout(it, uploading, gridWidth) } }
    val byId = remember(rows) { rows.orEmpty().withIndex().associateBy({ it.value.mediaId }, { it }) }
    val uploadById = uploading.associateBy { it.id }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottom)) {
        // the banner above the header, on every view (ADR 0144 decision 4)
        item(key = "banner") { MemoryBannerFrame(gallery, model) }
        item(key = "hero") { hero(16.dp) }
        // the date and the place under the header (ADR 0143 D5), and who was there (ADR 0144 decision 1)
        item(key = "facts") { MemoryFactsLine(gallery, model) }
        item(key = "people") { MemoryPeopleLine(gallery, model) }
        stickyHeader(key = "views") {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                viewSwitch()
                // the filter in the Photos view's own toolbar, where the facts may be filtered and something is there to filter
                if (state.rights.filterFacts && (!state.rows.isNullOrEmpty() || state.filter.narrows)) GalleryFilterBar(gallery, state)
            }
        }
        if (state.chapters.isNotEmpty()) {
            item(key = "chapters") {
                Text(stringResource(R.string.memory_chapters), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            }
            items(state.chapters, key = { "chapter:" + it.nodeId }) { c ->
                ListItem(
                    headlineContent = { Text(c.name) },
                    leadingContent = { ChapterCover(gallery, c) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickableRow { if (onChapter != null) onChapter(c) else GalleryModel.rowPage(c)?.let(model::open) },
                )
            }
        }
        val failure = state.failure
        when {
            // offline, what the store does not hold says so (muted: no failure), else the load failed (core's words)
            failure != null -> item(key = "failed") {
                Text(say(failure), color = role(if (state.offline != null) palette.muted else palette.danger), modifier = Modifier.padding(16.dp))
            }
            rows == null || layout == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            rows.isEmpty() && uploading.isEmpty() -> item(key = "empty") {
                // a filter that keeps nothing names every filter in force (E5): never read as an empty Memory
                val said = if (state.rows.isNullOrEmpty()) stringResource(R.string.memory_gallery_empty) else filterEmptyText(state.filter)
                Text(said, color = role(palette.muted), modifier = Modifier.padding(16.dp))
            }
            else -> {
                if (rows.isNotEmpty()) item(key = "count") {
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
                            me.raddatz.veyq.mobile.memories.UploadTile.idOf(t.id)?.let { id ->
                                uploadById[id]?.let { UploadPlaceholder(it, Modifier.offset(x = t.left.dp).size(t.width.dp, t.height.dp)) }
                            }
                            val at = byId[t.id] ?: continue
                            MediaTile(at.value, state.marks[at.value.mediaId], gallery, model, Modifier.offset(x = t.left.dp).size(t.width.dp, t.height.dp), actions = gallery.tileActions(at.value)) {
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
        model.memories.createChapter(gallery, name)
    }
    state.purging?.let { PurgeQuestion(gallery, model) }
    // a Memory's curation (ADR 0144): the picker and the banner's dialog are the app model's (a rotation keeps them)
    val picker by model.memories.participants.collectAsStateWithLifecycle()
    picker?.let { ParticipantsPickerSheet(it, model) }
    val bannerEditor by model.memories.banner.collectAsStateWithLifecycle()
    bannerEditor?.let { BannerDialog(it, gallery, model) }
    // trashing the banner's picture asks first (A2), over the lightbox where it was asked from
    state.bannerPicture?.let { BannerPictureQuestion(gallery, model) }
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
        val answer = snackbar.show(context.text(undo), me.raddatz.veyq.mobile.words.NoticeKind.UNDO, context.getString(R.string.trash_undo))
        if (answer == SnackbarResult.ActionPerformed) model.memories.galleryAct { gallery.undo() }
    }
    val said = state.said
    LaunchedEffect(said) {
        if (said == null) return@LaunchedEffect
        snackbar.show(context, said)
        gallery.seen()
    }
    // a mark that did not land sprang back (core's), and this says why (too many, not saved)
    val markSaid = state.markSaid
    LaunchedEffect(markSaid) {
        if (markSaid == null) return@LaunchedEffect
        gallery.markSeen()
        snackbar.show(context, markSaid)
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
            TextButton(onClick = { model.memories.galleryAct { gallery.purge() } }) {
                Text(stringResource(R.string.memory_delete_own_accept), color = role(LocalPalette.current.danger))
            }
        },
        dismissButton = { TextButton(onClick = { gallery.cancelAsk() }) { Text(stringResource(R.string.trash_cancel)) } },
    )
}

/** The room between the justified grid and the screen's edges: the grid's own gap, so every side of a tile breathes alike. */
internal val GRID_MARGIN = MediaGridLayout.GAP.dp

/**
 * One tile at the frame the justified grid gave it ([modifier]): the medium's grid tier cropped to fill (its aspect is the
 * tile's, bounded), a video marked; an icon where it has no tile to show.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaTile(
    row: GalleryModel.Row, mark: GalleryModel.Mark?, gallery: GalleryModel, model: AppModel, modifier: Modifier,
    /** A long press's action and what a screen reader calls it (the Contributions view's move); null: none. */
    longPress: Pair<String, () -> Unit>? = null,
    /**
     * What the tile offers beside opening it (core's `GalleryModel.tileActions`, ADR 0154 decision 2): every entry in a menu
     * a long press opens, Material's gesture for a tile's actions; none: no menu.
     */
    actions: List<me.raddatz.veyq.mobile.memories.TileAction> = emptyList(),
    onOpen: () -> Unit,
) {
    val palette = LocalPalette.current
    val picture by produceState<ImageBitmap?>(null, row.mediaId) {
        value = row.tile?.let { blob -> model.memories.mediaPicture(blob) { gallery.tile(row.mediaId) } }
    }
    var menu by remember { mutableStateOf(false) }
    val tileMenu = stringResource(R.string.node_more)
    val hold = if (actions.isNotEmpty()) tileMenu to { menu = true } else longPress
    val tap = if (hold == null) Modifier.clickable(onClick = onOpen)
    else Modifier.combinedClickable(onClick = onOpen, onLongClickLabel = hold.first, onLongClick = hold.second)
    Box(modifier.background(role(palette.line)).then(tap), contentAlignment = Alignment.Center) {
        if (actions.isNotEmpty()) androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
            for (a in actions) when (a) {
                // the caller's mark set or taken away (the lightbox's heart, from the grid)
                me.raddatz.veyq.mobile.memories.TileAction.MARK -> androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(if (mark?.mine == true) R.string.media_marks_unmark else R.string.media_marks_mark)) },
                    onClick = { menu = false; model.memories.galleryAct { gallery.toggleMark(row.mediaId) } },
                )
            }
        }
        val p = picture
        if (p != null) Image(p, row.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.Image, row.name, tint = role(palette.muted))
        if (row.isVideo) Icon(Icons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.size(32.dp))
        // the marks' badge (ADR 0149 decision 4): no button — the tile is one target, the mark is toggled in the lightbox
        mark?.let { MarkBadge(it, Modifier.align(Alignment.BottomEnd)) }
    }
}

/**
 * A photo or video on its way into this page (ADR 0145 decision 3): its kind's glyph on the tile's ground, how far it is
 * along the bottom (a running bar while its tiers are made on the device), or that it failed — the uploads list retries
 * it. A screen reader hears its name and what is happening to it.
 */
@Composable
private fun UploadPlaceholder(tile: me.raddatz.veyq.mobile.memories.UploadTile, modifier: Modifier) {
    val palette = LocalPalette.current
    val label = when {
        tile.failed -> stringResource(R.string.memory_upload_tile_failed, tile.name)
        tile.preparing -> stringResource(R.string.memory_upload_tile_preparing, tile.name)
        else -> stringResource(R.string.memory_upload_tile, tile.name)
    }
    // added offline, sealed on this device: core's *Waiting to send* under the glyph, no progress (ADR 0153 decision 5)
    val waiting = tile.said?.let { say(it) }
    Box(modifier.background(role(palette.line)).semantics(mergeDescendants = true) { contentDescription = waiting?.let { "$label. $it" } ?: label }, contentAlignment = Alignment.Center) {
        Icon(
            if (tile.failed) Icons.Outlined.ErrorOutline else if (tile.isVideo) Icons.Outlined.Movie else Icons.Outlined.Image, null,
            tint = role(if (tile.failed) palette.danger else palette.muted),
        )
        if (waiting != null) {
            Text(
                waiting, style = MaterialTheme.typography.labelSmall, color = role(palette.muted), maxLines = 2,
                modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp).testTag("gallery-waiting"),
            )
        } else if (!tile.failed) {
            val bar = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp)
            if (tile.preparing) LinearProgressIndicator(bar) else LinearProgressIndicator({ tile.progress.toFloat() }, bar)
        }
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
    // the whole screen, edge to edge like the activity: a dialog's window otherwise keeps out of the system bars and the
    // display cutout, so in landscape the lightbox sat shifted right by the cutout with the page showing beside it (#931)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        EdgeToEdgeDialogWindow(darkIcons = false)
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
        // the marks are the gallery's: its state drawn here, so the heart turns as the gallery's marks do
        val galleryState by gallery.state.collectAsStateWithLifecycle()
        val mark = galleryState.marks[row.mediaId]
        val canMark = galleryState.marking && galleryState.marksLoaded
        // a medium's report (an ITEM, ADR 0149 decision 6) where the server takes reports and the medium is readable, online
        // the server whoever looks reports to: the tenant's, or a guest's link's own instance (#1033)
        val reportOffered by produceState(false, gallery) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gallery.reportOffered() } }
        val reports = reportOffered && !row.blocked && galleryState.offline == null
        var reporting by remember { mutableStateOf<me.raddatz.veyq.mobile.navigation.ReportSheet?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var more by remember { mutableStateOf(false) }
        val save = state.save
        val saving = save?.state?.collectAsStateWithLifecycle()?.value?.let { it.end == null } == true
        // how a save ended, said once
        LaunchedEffect(save) {
            val ended = save?.state?.first { it.end != null } ?: return@LaunchedEffect
            snackbar.show(context, ended.notice)
        }
        // the original goes where the person picks (the system's "create document"), streamed there as it is read
        val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) model.memories.saveMedium(box, uri)
        }
        Scaffold(
            containerColor = Color.Black,
            contentColor = Color.White,
            snackbarHost = { SnackbarHost(snackbar) },
            // black behind the bars and the cutout, nothing drawn under them
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Column {
                TopAppBar(
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
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
                        // the heart (ADR 0149 decision 4): the caller's mark, with how many marked it where a count is kept
                        if (canMark || mark != null) MarkButton(mark, enabled = canMark) { model.memories.galleryAct { box.toggleMark() } }
                        IconButton(onClick = { details = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.memory_details)) }
                        if (saving) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        }
                        // the rest in the bar's overflow (Material 3: a top app bar holds a few actions): a phone's bar
                        // held the heart, the details, the download and the trash beside the position no more
                        val downloads = !row.blocked && !saving
                        val trashes = gallery.deletes(row.mediaId)
                        if (downloads || trashes || reports) {
                            Box {
                                IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.node_more)) }
                                androidx.compose.material3.DropdownMenu(more, { more = false }) {
                                    if (downloads) androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(stringResource(R.string.memory_download)) },
                                        leadingIcon = { Icon(Icons.Outlined.Download, null) },
                                        onClick = { more = false; box.downloadName()?.let { saveTo.launch(it) } },
                                    )
                                    if (trashes) androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(stringResource(R.string.memory_trash)) },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                        onClick = { more = false; model.memories.galleryAct { box.trash() } },
                                    )
                                    if (reports) androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(stringResource(R.string.report_action)) },
                                        leadingIcon = { Icon(Icons.Outlined.Flag, null) },
                                        onClick = { more = false; scope.launch { reporting = box.reportSheet() } },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black, titleContentColor = Color.White,
                        navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
                    ),
                )
                // the music's compact controls while a queue is loaded, a row of their own (ADR 0142 decision 5)
                val now = state.music
                val commands = box.musicControls
                if (now != null && commands != null) MusicCompactControls(now, commands)
                }
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
                        Text(moment(it), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        reporting?.let { sheet -> ReportSheet(sheet) { reporting = null } }
        if (details) {
            ModalBottomSheet(onDismissRequest = { details = false }) {
                MediaDetailsSheet(row, box, model, onProfile = { id ->
                    // the profile opens over the Memory; back returns to it and its lightbox (the gallery's)
                    details = false
                    model.openPerson(id)
                })
            }
        }
    }
}

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
                    value = Read(row.picture?.let { blob -> model.memories.mediaPicture(blob) { box.picture(row.mediaId) } })
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
    // its play state interrupts the music while it plays, and the music plays again after (ADR 0142 decision 13)
    ReaderPlayer(reader, row.mediaId, aspect, onPlaying = box::videoPlaying) {
        Text(stringResource(R.string.memory_video_failed), color = Color.White, modifier = Modifier.padding(24.dp))
    }
}

/**
 * A medium's details (the web's details panel, core's `Lightbox.details`): the capture time and the file's facts at once,
 * the camera's once its EXIF tier is read, who added it (read once for the gallery), its place and position; "no details"
 * only where nothing is known.
 */
@Composable
private fun MediaDetailsSheet(row: GalleryModel.Row, box: MemoryLightbox, model: AppModel, onProfile: (String) -> Unit) {
    // read anew when the gallery re-lists the medium (its place name sealed, below, shows then)
    val details by produceState<me.raddatz.veyq.mobile.memories.Lightbox.Details?>(null, row.mediaId, row) {
        value = box.details(row.mediaId)
    }
    // the photo's place name, looked up once ever and sealed where the core's gates allow it (ADR 0143 D7)
    LaunchedEffect(row.mediaId) { box.resolvePlace(row.mediaId) }
    val d = details
    val rows = d?.rows.orEmpty()
    val uploaderName = d?.uploader?.let { piece(MediaDetails.uploaderWords(it)) }
    // who added it stands before the place, as on the web
    val placeAt = d?.uploaderAt ?: 0
    // a current member's name opens their profile (ADR 0144 decision 11); a former member's and a guest's open nothing
    val profile = d?.uploader?.profile
    val uploaded: @Composable () -> Unit = {
        DetailRow(stringResource(R.string.memory_field_uploaded_by), uploaderName.orEmpty(), onClick = profile?.let { id -> { onProfile(id) } },
            onClickLabel = stringResource(R.string.people_open, uploaderName.orEmpty()))
    }
    // the capture time corrected where whoever looks may write the medium's meta, online (ADR 0144 decision 3)
    val timeEditor = remember(row.mediaId, row) { box.captureTimeEditor(row.mediaId) }
    var editingTime by remember(row.mediaId) { mutableStateOf<me.raddatz.veyq.mobile.memories.CaptureTimeEditor?>(null) }
    val editTime: (() -> Unit)? = timeEditor?.let { { editingTime = box.captureTimeEditor(row.mediaId) } }
    editingTime?.let { e -> CaptureTimeDialog(e) { editingTime = null } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.memory_details), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        // the photo's small map at its point, with its direction where the camera recorded one (ADR 0143 D7)
        d?.map?.let { PhotoMap(it, model) }
        if (d != null && rows.isEmpty() && uploaderName == null) {
            Text(stringResource(R.string.memory_no_detail), color = role(LocalPalette.current.muted), modifier = Modifier.padding(16.dp))
        }
        rows.forEachIndexed { i, r ->
            if (i == placeAt && uploaderName != null) uploaded()
            if (r.field == MediaDetails.Field.CAPTURED && editTime != null) {
                DetailRow(detailLabel(r.field), detailValue(r), trailing = {
                    IconButton(onClick = editTime) { Icon(Icons.Outlined.Edit, stringResource(R.string.media_edit_captured_at)) }
                })
            } else DetailRow(detailLabel(r.field), detailValue(r))
        }
        if (placeAt == rows.size && uploaderName != null) uploaded()
        // a medium without a capture time can be given one
        if (editTime != null && rows.none { it.field == MediaDetails.Field.CAPTURED }) {
            TextButton(onClick = editTime, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.media_edit_captured_at)) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, onClick: (() -> Unit)? = null, onClickLabel: String? = null, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(value, color = if (onClick != null) MaterialTheme.colorScheme.primary else Color.Unspecified) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier,
    )
}

@Composable
private fun detailLabel(field: MediaDetails.Field): String = say(MediaDetails.label(field))

@Composable
private fun detailValue(row: MediaDetails.Row): String {
    val context = LocalContext.current
    return when (val v = MediaDetails.shown(row)) {
        is MediaDetails.Shown.Words -> context.text(v.notice)
        is MediaDetails.Shown.Size -> context.size(v.bytes)
        is MediaDetails.Shown.Moment -> context.moment(v.epochMs)
        is MediaDetails.Shown.Plain -> v.text
    }
}
