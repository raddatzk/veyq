package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.depot.DepotFiles
import me.raddatz.veyq.mobile.depot.FileView
import me.raddatz.veyq.mobile.navigation.ObjectRef

/** What a file's page shows, once read: the decision and what it needs (the web's `FileView`). */
private sealed interface Shown {
    data object Loading : Shown
    data object Failed : Shown
    data class Message(val kind: FileView.Kind) : Shown
    data class Picture(val image: ImageBitmap) : Shown
    data class Text(val text: String, val truncated: Boolean) : Shown
    /** A PDF: its decrypted copy and the renderer over it, which draws a page when it scrolls into view. */
    class Pdf(val file: java.io.File, val pfd: android.os.ParcelFileDescriptor, val renderer: android.graphics.pdf.PdfRenderer) : Shown {
        val lock = kotlinx.coroutines.sync.Mutex()
    }
    data class Media(val file: java.io.File) : Shown
}

/**
 * A file's page (Depot's `FileDetail` and `FileViewer`, `docs/apps/depot.md` § The viewer): its facts — size, type,
 * modified — and the file shown IN THE APP by what its first frame's bytes are (core's `FileView`): an image, a PDF drawn
 * page by page as pictures (no links, no forms), a text as plain text (the first MiB), an audio or video played here; a
 * file the bytes do not name, or one too large, is offered as a download. Nothing is handed to another app: a decrypted
 * PDF or media file lives in the app's cache, named by its SNIFFED type, only while the page is open.
 */
@Composable
internal fun FileScreen(ref: ObjectRef, model: AppModel) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val files by model.files.collectAsStateWithLifecycle()
    val file = files[ref.nodeId]
    var shown by remember(file?.blobId) { mutableStateOf<Shown>(Shown.Loading) }
    LaunchedEffect(file?.blobId) {
        val f = file ?: return@LaunchedEffect
        if (!f.hasContent) return@LaunchedEffect
        shown = runCatching { show(context, model, f, ref.name) }.getOrDefault(Shown.Failed)
    }
    // the decrypted copy lives only while the page is open
    DisposableEffect(shown) {
        val s = shown
        onDispose {
            when (s) {
                is Shown.Pdf -> {
                    runCatching { s.renderer.close() }
                    runCatching { s.pfd.close() }
                    s.file.delete()
                }
                is Shown.Media -> s.file.delete()
                else -> Unit
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // the facts (Depot's `detail`): size, the sealed type (a claim, shown as such), modified
        if (file != null && file.hasContent) {
            val facts = listOfNotNull(
                file.size?.let { android.text.format.Formatter.formatShortFileSize(context, it) },
                file.type ?: stringResource(R.string.file_type_unknown),
                file.modifiedAt?.let { stringResource(R.string.depot_modified, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it))) },
            )
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
            // who uploaded it (Depot's header fact): a member by name, one who left, a guest by the name it gave itself
            var uploader by remember(file.blobId, file.uploaderKey) { mutableStateOf<me.raddatz.veyq.mobile.depot.Uploaders.Uploader?>(null) }
            LaunchedEffect(file.blobId, file.uploaderKey) { uploader = model.uploader(file) }
            uploader?.let { u ->
                val who = when (u.kind) {
                    me.raddatz.veyq.mobile.depot.Uploaders.Kind.MEMBER -> u.name.orEmpty()
                    me.raddatz.veyq.mobile.depot.Uploaders.Kind.FORMER -> stringResource(R.string.people_former_member)
                    me.raddatz.veyq.mobile.depot.Uploaders.Kind.GUEST -> u.name?.let { stringResource(R.string.depot_guest_name, it) } ?: stringResource(R.string.depot_a_guest)
                }
                Text(stringResource(R.string.depot_uploaded_by, who), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            when {
                file == null -> CircularProgressIndicator()
                !file.hasContent -> Note(stringResource(R.string.depot_not_uploaded))
                else -> when (val s = shown) {
                    Shown.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.file_opening), color = role(palette.muted), modifier = Modifier.padding(top = 8.dp))
                    }
                    Shown.Failed -> Note(stringResource(R.string.file_open_failed))
                    is Shown.Message -> Note(
                        stringResource(
                            when (s.kind) {
                                FileView.Kind.TOO_LARGE -> R.string.file_too_large
                                FileView.Kind.EMPTY -> R.string.file_empty
                                else -> R.string.file_unsupported
                            },
                        ),
                    )
                    is Shown.Picture -> Image(s.image, ref.name, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    is Shown.Text -> Column(Modifier.fillMaxSize()) {
                        if (s.truncated) Text(stringResource(R.string.file_text_truncated), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
                        LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                            item {
                                SelectionContainer {
                                    Text(s.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    is Shown.Pdf -> {
                        val width = context.resources.displayMetrics.widthPixels
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(s.renderer.pageCount) { i ->
                                // a page drawn when it scrolls into view, one at a time (the renderer draws one page at once)
                                val page by androidx.compose.runtime.produceState<ImageBitmap?>(null, s, i) {
                                    value = withContext(Dispatchers.IO) { s.lock.withLockPage { renderPage(s.renderer, i, width) } }
                                }
                                page?.let { Image(it, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                                    ?: Box(Modifier.fillMaxWidth().heightIn(min = 400.dp))
                            }
                        }
                    }
                    is Shown.Media -> AndroidView(
                        factory = { c ->
                            android.widget.VideoView(c).apply {
                                setVideoPath(s.file.path)
                                setMediaController(android.widget.MediaController(c).also { it.setAnchorView(this) })
                                setOnPreparedListener { start() }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = role(LocalPalette.current.muted), modifier = Modifier.padding(top = 24.dp))
}

/** Reads [f] as far as its view needs and makes it showable (off the main thread). */
private suspend fun show(context: android.content.Context, model: AppModel, f: DepotFiles.File, nodeName: String): Shown {
    // core decides and reads what the view needs (`FileView.open`); a PDF or a medium is streamed into a file here
    val opened = model.openFileView(f, nodeName) ?: return Shown.Failed
    val d = opened.decision
    suspend fun toCache(): java.io.File {
        val file = java.io.File(context.cacheDir, "view-${java.util.UUID.randomUUID()}.${d.fileExtension}")
        file.outputStream().buffered().use { out -> model.readFile(f) { out.write(it) } }
        return file
    }
    return when (d.kind) {
        FileView.Kind.IMAGE -> withContext(Dispatchers.Default) {
            val bytes = opened.bytes ?: return@withContext Shown.Message(FileView.Kind.UNSUPPORTED)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { Shown.Picture(it.asImageBitmap()) } ?: Shown.Message(FileView.Kind.UNSUPPORTED)
        }
        FileView.Kind.TEXT -> Shown.Text(opened.text.orEmpty(), d.truncated)
        FileView.Kind.PDF -> withContext(Dispatchers.IO) {
            val file = toCache()
            runCatching {
                val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                Shown.Pdf(file, pfd, android.graphics.pdf.PdfRenderer(pfd))
            }.getOrElse {
                file.delete()
                Shown.Message(FileView.Kind.UNSUPPORTED)
            }
        }
        FileView.Kind.MEDIA -> withContext(Dispatchers.IO) { Shown.Media(toCache()) }
        else -> Shown.Message(d.kind)
    }
}

private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockPage(f: () -> T): T = withLock { f() }

/** Page [i] drawn [width] pixels wide on white: a picture only (no links, no forms, no script). */
private fun renderPage(r: android.graphics.pdf.PdfRenderer, i: Int, width: Int): ImageBitmap? = runCatching {
    r.openPage(i).use { page ->
        val h = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, 4 * width)
        val bmp = android.graphics.Bitmap.createBitmap(width, h, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp.asImageBitmap()
    }
}.getOrNull()
