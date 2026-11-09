package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef

/**
 * A Memory's or a chapter's page (Memories' `MemoryDetail.vue`, its gallery): the chapters a Memory holds, then every
 * medium as a square tile of its thumbnail in the grid's order (newest first by capture time), a video marked as one; a
 * tile opens the lightbox, which pages through the gallery showing each medium's preview with its name and capture time.
 */
@Composable
internal fun MemoryScreen(ref: ObjectRef, model: AppModel) {
    val palette = LocalPalette.current
    val details by model.rowDetails.collectAsStateWithLifecycle()
    // read again whenever the lists are
    var gallery by remember { mutableStateOf<MemoryMedia.Gallery?>(null) }
    var failed by remember { mutableStateOf(false) }
    var chapters by remember { mutableStateOf<List<NodeRef>>(emptyList()) }
    LaunchedEffect(ref.nodeId, details) {
        val read = model.memoryGallery(ref.scopeId, ref.nodeId)
        if (read == null) failed = true else { gallery = read; failed = false }
        chapters = if (ref.kind == "MEMORY") model.scopeNodes(ref.scopeId).filter { it.parentId == ref.nodeId && it.kind == "CHAPTER" } else emptyList()
    }
    var open by remember { mutableStateOf<Int?>(null) }
    val hero = LocalNodeHero.current
    val items = gallery?.items
    LazyVerticalGrid(GridCells.Adaptive(112.dp), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    val at = open
    if (at != null && items != null) Lightbox(items, at, model) { open = null }
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

/** The lightbox: the gallery paged through, each medium's preview fitted on black with its name and capture time. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Lightbox(items: List<MemoryMedia.Item>, start: Int, model: AppModel, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(start) { items.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].mediaId }) { page ->
                val item = items[page]
                val picture by produceState<ImageBitmap?>(null, item.mediaId) { value = model.mediaPicture(item, item.lightboxBlob) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val p = picture
                    if (p != null) Image(p, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else CircularProgressIndicator()
                    if (item.isVideo) Icon(Icons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.size(64.dp))
                }
            }
            val item = items[pager.currentPage]
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(16.dp)) {
                Text(item.name ?: "", color = Color.White, style = MaterialTheme.typography.titleSmall)
                item.meta?.effectiveCapturedAt?.let {
                    Text(
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it)),
                        color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
