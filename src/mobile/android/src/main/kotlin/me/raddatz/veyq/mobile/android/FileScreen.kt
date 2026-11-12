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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.depot.FileModel
import me.raddatz.veyq.mobile.depot.FileView
import me.raddatz.veyq.mobile.navigation.ObjectRef

/**
 * A file's page (Depot's `FileDetail` and `FileViewer`, `docs/apps/depot.md` § The viewer), drawn from core's `FileModel`:
 * its facts and uploader, and the file shown IN THE APP as core decided by its bytes — an image (decoded to core's edge), a
 * PDF drawn page by page as pictures (no links, no forms), a text as plain text, an audio or video played here through
 * core's range reader; a file the bytes do not name, or one too large, is offered as a download. Nothing decrypted is
 * written to show it (ADR 0047): the PDF is handed to the renderer through an anonymous in-memory file (`memfd`), the
 * medium is read in ranges.
 */
@Composable
internal fun FileScreen(ref: ObjectRef, model: AppModel) {
    val palette = LocalPalette.current
    val node = ref.at
    val file = remember(node) { model.fileModel(node) }
    if (file == null) {
        CircularProgressIndicator(Modifier.padding(16.dp))
        return
    }
    val state by file.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // the facts (Depot's `detail`): size, the sealed type (a claim, shown as such), modified; who uploaded it
        if (state.facts.isNotEmpty()) {
            Text(state.facts.map { piece(it) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
        }
        state.uploader?.let { Text(say(it), style = MaterialTheme.typography.bodySmall, color = role(palette.muted)) }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            when (val s = state.shown) {
                FileModel.Shown.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.file_opening), color = role(palette.muted), modifier = Modifier.padding(top = 8.dp))
                }
                FileModel.Shown.Failed -> Note(stringResource(R.string.file_open_failed))
                FileModel.Shown.NotUploaded -> Note(stringResource(R.string.depot_not_uploaded))
                is FileModel.Shown.Message -> Note(say(s.words))
                is FileModel.Shown.Image -> ShownImage(s, ref.name)
                is FileModel.Shown.Text -> Column(Modifier.fillMaxSize()) {
                    if (s.truncated) Text(stringResource(R.string.file_text_truncated), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
                    LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        item {
                            SelectionContainer {
                                Text(s.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                is FileModel.Shown.Pdf -> ShownPdf(s)
                is FileModel.Shown.Media -> ReaderPlayer(s.reader, s, 16f / 9f, Modifier.fillMaxWidth().heightIn(min = 240.dp)) {
                    Note(say(FileView.message(FileView.Kind.UNSUPPORTED)))
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = role(LocalPalette.current.muted), modifier = Modifier.padding(top = 24.dp))
}

/** An image decoded off the main thread to core's edge at most (`FileView.IMAGE_EDGE`), from the bytes in memory. */
@Composable
private fun ShownImage(s: FileModel.Shown.Image, name: String) {
    val image by produceState<ImageBitmap?>(null, s) {
        value = withContext(Dispatchers.Default) {
            val bytes = s.bytes
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > s.edge) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
        }
    }
    image?.let { Image(it, name, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
        ?: CircularProgressIndicator()
}

/** A PDF opened over an anonymous in-memory file and the renderer over it; both closed with the page. */
private class OpenPdf(val fd: android.os.ParcelFileDescriptor, val renderer: android.graphics.pdf.PdfRenderer) {
    val lock = Mutex()

    fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        /**
         * [bytes] written into a `memfd` (anonymous memory, no path, gone with its last descriptor: no plaintext at rest,
         * ADR 0047) and handed to `PdfRenderer`; null where the renderer refuses it.
         */
        fun of(bytes: ByteArray): OpenPdf? {
            val fd = runCatching { android.system.Os.memfd_create("veyq-pdf", 0) }.getOrNull() ?: return null
            return runCatching {
                java.io.FileOutputStream(fd).let { out ->
                    out.write(bytes)
                    out.flush()
                }
                android.system.Os.lseek(fd, 0, android.system.OsConstants.SEEK_SET)
                val pfd = android.os.ParcelFileDescriptor.dup(fd)
                runCatching { android.system.Os.close(fd) }
                runCatching { OpenPdf(pfd, android.graphics.pdf.PdfRenderer(pfd)) }.getOrElse {
                    runCatching { pfd.close() }
                    null
                }
            }.getOrElse {
                runCatching { android.system.Os.close(fd) }
                null
            }
        }
    }
}

/** A PDF's pages, each drawn when it scrolls into view, at most core's bound (`FileView.PDF_PAGES`), the rest said. */
@Composable
private fun ShownPdf(s: FileModel.Shown.Pdf) {
    val context = LocalContext.current
    val pdf by produceState<OpenPdf?>(null, s) { value = withContext(Dispatchers.IO) { OpenPdf.of(s.bytes) } }
    DisposableEffect(pdf) {
        val held = pdf
        onDispose { held?.close() }
    }
    val open = pdf ?: return CircularProgressIndicator()
    val total = open.renderer.pageCount
    val width = context.resources.displayMetrics.widthPixels
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(FileView.pagesShown(total).coerceAtMost(s.pages)) { i ->
            // a page drawn when it scrolls into view, one at a time (the renderer draws one page at once)
            val page by produceState<ImageBitmap?>(null, open, i) {
                value = withContext(Dispatchers.IO) { open.lock.withLock { renderPage(open.renderer, i, width) } }
            }
            page?.let { Image(it, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                ?: Box(Modifier.fillMaxWidth().heightIn(min = 400.dp))
        }
        FileView.pagesCut(total)?.let { cut ->
            item { Text(say(cut), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted)) }
        }
    }
}

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
