package me.raddatz.veyq.mobile.android.apps.depot

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.LocalPalette
import me.raddatz.veyq.mobile.android.core.OpenPdf
import me.raddatz.veyq.mobile.android.core.ReaderPlayer
import me.raddatz.veyq.mobile.android.core.joined
import me.raddatz.veyq.mobile.android.core.piece
import me.raddatz.veyq.mobile.android.core.role
import me.raddatz.veyq.mobile.android.core.say
import me.raddatz.veyq.mobile.android.core.text
import me.raddatz.veyq.mobile.android.words
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
    val node = ref.at
    val file = remember(node) { model.depot.fileModel(node) }
    if (file == null) {
        CircularProgressIndicator(Modifier.padding(16.dp))
        return
    }
    FilePage(file, ref.name)
}

/** [file]'s page as [FileScreen] draws it — a link's guest's too (ADR 0150 decision 5: the member's page, a guest's viewer). */
@Composable
internal fun FilePage(file: FileModel, name: String) {
    val palette = LocalPalette.current
    val state by file.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // the facts (Depot's `detail`): size, the sealed type (a claim, shown as such), modified; who uploaded it
        if (state.facts.isNotEmpty()) {
            Text(state.facts.map { piece(it) }.joined(), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
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
                is FileModel.Shown.Image -> ShownImage(s, name)
                is FileModel.Shown.Text -> ShownText(s)
                is FileModel.Shown.Pdf -> ShownPdf(s, name)
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

/**
 * A text as plain text, selectable, with the web's two conveniences (`FileViewer.vue`): *Copy* puts exactly what is shown on
 * the clipboard (a cut text its shown part, which the button and its note say), and *Wrap lines* wraps long lines instead
 * of scrolling sideways (on at first, as on the web).
 */
@Composable
private fun ShownText(s: FileModel.Shown.Text) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var wrap by rememberSaveable { mutableStateOf(true) }
    var copied by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            kotlinx.coroutines.delay(me.raddatz.veyq.mobile.words.Notice.COPIED_MS)
            copied = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (s.truncated) Text(stringResource(R.string.file_text_truncated), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("veyq", s.text))
                // Android 13 confirms a copy itself; below it the page says it (Android's own guidance: never twice)
                if (android.os.Build.VERSION.SDK_INT < 33) copied = if (s.truncated) R.string.file_text_copied_shown else R.string.file_text_copied
            }) { Text(stringResource(if (s.truncated) R.string.file_text_copy_shown else R.string.file_text_copy)) }
            FilterChip(selected = wrap, onClick = { wrap = !wrap }, label = { Text(stringResource(R.string.file_text_wrap)) })
        }
        copied?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = role(palette.muted)) }
        val sideways = if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())
        LazyColumn(Modifier.fillMaxSize().then(sideways)) {
            item {
                SelectionContainer {
                    Text(s.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = wrap)
                }
            }
        }
    }
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

/**
 * A PDF's pages, each drawn when it scrolls into view, at most core's bound (`FileView.PDF_PAGES`), the rest said. A
 * password-protected PDF asks for its password in the page first, as the web's does (`FileViewer.vue`) — where this
 * device's renderer takes one (Android 15); the password goes to the renderer and nowhere else, kept only while the field
 * holds it. Cancelled, or on an older Android, it says so and the download stays.
 */
@Composable
private fun ShownPdf(s: FileModel.Shown.Pdf, name: String) {
    val context = LocalContext.current
    var password by remember(s) { mutableStateOf<String?>(null) }
    var declined by remember(s) { mutableStateOf(false) }
    var opening by remember(s) { mutableStateOf<OpenPdf.Opening?>(null) }
    LaunchedEffect(s, password) {
        val tried = password
        opening = null
        opening = withContext(Dispatchers.IO) { OpenPdf.open(s.bytes, tried) }
    }
    DisposableEffect(opening) {
        val held = (opening as? OpenPdf.Opening.Open)?.pdf
        onDispose { held?.close() }
    }
    val open = when (val o = opening) {
        null -> return CircularProgressIndicator()
        OpenPdf.Opening.Failed -> return Note(say(FileView.message(FileView.Kind.UNSUPPORTED)))
        OpenPdf.Opening.Locked -> {
            if (declined || !OpenPdf.takesPassword) return Note(stringResource(R.string.file_pdf_password_declined))
            return PdfPassword(name, wrong = password != null, onOpen = { password = it }, onCancel = { declined = true })
        }
        is OpenPdf.Opening.Open -> o.pdf
    }
    val total = open.renderer.pageCount
    val width = context.resources.displayMetrics.widthPixels
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(FileView.pagesShown(total).coerceAtMost(s.pages)) { i ->
            // a page drawn when it scrolls into view, one at a time (the renderer draws one page at once)
            val page by produceState<ImageBitmap?>(null, open, i) {
                value = withContext(Dispatchers.IO) { open.lock.withLock { renderPage(open.renderer, i, width) } }
            }
            page?.let { PdfPage(it, open, i) } ?: Box(Modifier.fillMaxWidth().heightIn(min = 400.dp))
        }
        FileView.pagesCut(total)?.let { cut ->
            item { Text(say(cut), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted)) }
        }
    }
}

/** What a drag selected on a page: its text and its boxes in the page's points. */
private class PdfSelection(val text: String, val boxes: List<android.graphics.RectF>)

/**
 * Page [i] of [open] as drawn, its text selectable where this device's renderer reads it (Android 15: the web's text layer,
 * `PdfRenderer.Page.selectContent`): a long press and a drag select from where it began to where it is, drawn over the
 * page, and *Copy* puts the selected text on the clipboard; a tap lets the selection go. Nothing else of the page acts.
 */
@Composable
private fun PdfPage(image: ImageBitmap, open: OpenPdf, i: Int) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var selection by remember { mutableStateOf<PdfSelection?>(null) }
    var pageSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val highlight = role(LocalPalette.current.accent).copy(alpha = 0.3f)
    val selectable = OpenPdf.takesPassword
    Box(Modifier.fillMaxWidth()) {
        var start by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val gestures = if (!selectable) Modifier else Modifier
            .pointerInput(open, i) {
                detectTapGestures(onTap = { selection = null })
            }
            .pointerInput(open, i) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start = it },
                    onDrag = { change, _ ->
                        val (from, to) = start to change.position
                        val w = size.width.toFloat()
                        scope.launch {
                            val picked = withContext(Dispatchers.IO) {
                                open.lock.withLock {
                                    runCatching {
                                        open.renderer.openPage(i).use { p ->
                                            pageSize = p.width to p.height
                                            val k = p.width / w
                                            fun at(o: androidx.compose.ui.geometry.Offset) =
                                                android.graphics.pdf.models.selection.SelectionBoundary(android.graphics.Point((o.x * k).toInt(), (o.y * k).toInt()))
                                            p.selectContent(at(from), at(to))?.selectedTextContents.orEmpty()
                                        }
                                    }.getOrNull()
                                }
                            } ?: return@launch
                            selection = PdfSelection(picked.joinToString("\n") { it.text }, picked.flatMap { it.bounds }).takeIf { it.text.isNotEmpty() }
                        }
                    },
                )
            }
        Image(
            image, null,
            Modifier.fillMaxWidth().then(gestures).drawWithContent {
                drawContent()
                val sel = selection ?: return@drawWithContent
                val (pw, _) = pageSize ?: return@drawWithContent
                val k = size.width / pw
                for (b in sel.boxes) {
                    drawRect(highlight, androidx.compose.ui.geometry.Offset(b.left * k, b.top * k), androidx.compose.ui.geometry.Size(b.width() * k, b.height() * k))
                }
            },
            contentScale = ContentScale.FillWidth,
        )
        selection?.let { sel ->
            Button(
                onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("veyq", sel.text))
                    selection = null
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) { Text(stringResource(R.string.file_text_copy)) }
        }
    }
}

/**
 * The prompt of a password-protected PDF (the web's `FileViewer.vue`): its name, the field — a password field, never kept
 * —, *Open* and *Cancel*, the hint that it is not saved, and a wrong one said.
 */
@Composable
private fun PdfPassword(name: String, wrong: Boolean, onOpen: (String) -> Unit, onCancel: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val submit = { if (typed.isNotEmpty()) onOpen(typed).also { typed = "" } }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.file_pdf_password_title, name), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            typed, { typed = it },
            label = { Text(stringResource(R.string.file_pdf_password_label)) },
            supportingText = { Text(stringResource(if (wrong) R.string.file_pdf_password_wrong else R.string.file_pdf_password_hint)) },
            isError = wrong,
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = androidx.compose.ui.text.input.ImeAction.Go, autoCorrectEnabled = false,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { submit() }, enabled = typed.isNotEmpty()) { Text(stringResource(R.string.file_pdf_password_open)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.file_pdf_password_cancel)) }
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
