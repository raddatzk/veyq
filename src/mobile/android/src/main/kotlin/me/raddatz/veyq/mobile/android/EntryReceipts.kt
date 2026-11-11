package me.raddatz.veyq.mobile.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.expenses.EntryEditor
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.expenses.Receipts

/** A picked file as the editor judges it: at most [Receipts.READ_LIMIT] of its bytes (core refuses a larger one), its
 *  type and name; no bytes where it did not read. */
private fun readPicked(context: Context, uri: Uri): EntryEditor.Picked {
    val resolver = context.contentResolver
    var name: String? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
    }
    val bytes = runCatching { resolver.openInputStream(uri)?.use { readAtMost(it, Receipts.READ_LIMIT.toInt()) } }.getOrNull()
    return EntryEditor.Picked(bytes, resolver.getType(uri).orEmpty(), name, null)
}

/** At most [limit] bytes of [input] (`readNBytes` is API 33's). */
private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(1 shl 16)
    while (out.size() < limit) {
        val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/**
 * An entry's receipts in the editor (`EntryReceipts.vue`): the ones the entry keeps ([kept]), each opened by its bytes or
 * taken off (the save drops it), and those picked meanwhile ([state]'s pending), uploaded only when the entry is saved.
 * Images and PDFs are offered; which are taken, and how many, is core's [EntryEditor]; what a receipt IS, the bytes decide
 * when it is opened.
 */
@Composable
internal fun EntryReceipts(view: PotView, model: AppModel, state: EntryEditor.State, onPick: (List<EntryEditor.Picked>) -> Unit, onDrop: (String) -> Unit) {
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPick(uris.map { readPicked(context, it) })
    }
    val palette = LocalPalette.current
    val editable = state.editable
    val kept = state.form.receipts
    Text(stringResource(R.string.receipts_title), fontWeight = FontWeight.SemiBold)
    if (kept.isEmpty() && state.pending.isEmpty()) Text(stringResource(R.string.receipts_none), color = role(palette.muted))
    for (id in kept) {
        val r = view.receipt(id)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewing = id }, enabled = r != null && !r.blocked, modifier = Modifier.weight(1f)) {
                Text(Receipts.downloadName(r?.name, r?.type), Modifier.fillMaxWidth())
            }
            if (editable) IconButton(onClick = { onDrop(id) }) { Icon(Icons.Outlined.Close, stringResource(R.string.receipts_remove)) }
        }
    }
    for (p in state.pending) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, Modifier.weight(1f).padding(start = 12.dp), color = role(palette.muted))
            if (editable) IconButton(onClick = { onDrop(p.id) }) { Icon(Icons.Outlined.Close, stringResource(R.string.receipts_remove)) }
        }
    }
    if (state.refusedPick) Text(stringResource(R.string.receipts_refused), color = MaterialTheme.colorScheme.error)
    if (state.canAddReceipt) {
        TextButton(onClick = { pick.launch(arrayOf("image/*", "application/pdf")) }) { Text(stringResource(R.string.receipts_add)) }
    }
    viewing?.let { id -> ReceiptViewer(view, model, id) { viewing = null } }
}

/** One receipt opened: an image drawn from its bytes, a PDF's pages rendered, anything else a download only. */
@Composable
private fun ReceiptViewer(view: PotView, model: AppModel, receiptId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val receipt = view.receipt(receiptId)
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(receiptId) {
        val b = model.openReceipt(receiptId)
        if (b == null) failed = true else bytes = b
    }
    val name = Receipts.downloadName(receipt?.name, receipt?.type)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val savedToGallery = stringResource(R.string.receipts_saved_gallery)
    val notSaved = stringResource(R.string.receipts_not_saved_gallery)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val b = bytes
        if (uri != null && b != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(b) } }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val b = bytes
                when {
                    failed -> Text(stringResource(R.string.receipts_unreadable), color = MaterialTheme.colorScheme.error)
                    b == null -> CircularProgressIndicator()
                    else -> when (Receipts.kindOf(b)) {
                        Receipts.Kind.IMAGE -> BitmapFactory.decodeByteArray(b, 0, b.size)?.let {
                            Image(it.asImageBitmap(), name, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                        } ?: Text(stringResource(R.string.receipts_download_only))
                        Receipts.Kind.PDF -> PdfPages(context, b)
                        Receipts.Kind.OTHER -> Text(stringResource(R.string.receipts_download_only))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.receipts_close)) } },
        dismissButton = {
            val b = bytes
            if (b != null) Row {
                // a picture (as its bytes say) also goes straight to the device's gallery
                val picture = me.raddatz.veyq.mobile.blob.Sniff.image(b)
                if (picture != null) TextButton(onClick = {
                    scope.launch {
                        val ok = saveToGallery(context, b, name, picture.mime)
                        android.widget.Toast.makeText(context, if (ok) savedToGallery else notSaved, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.receipts_save_gallery)) }
                TextButton(onClick = { save.launch(name) }) { Text(stringResource(R.string.receipts_save)) }
            }
        },
    )
}

/**
 * A PDF's pages, drawn by the platform's renderer. It reads a seekable file only, so the decrypted bytes go to a file in
 * the app's own cache for the time of the rendering and are deleted right after; nothing else of the PDF runs.
 */
@Composable
private fun PdfPages(context: Context, bytes: ByteArray) {
    var pages by remember(bytes) { mutableStateOf<List<Bitmap>?>(null) }
    LaunchedEffect(bytes) {
        pages = withContext(Dispatchers.IO) {
            val file = java.io.File.createTempFile("receipt", ".pdf", context.cacheDir)
            try {
                file.writeBytes(bytes)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        (0 until minOf(pdf.pageCount, 20)).map { i ->
                            pdf.openPage(i).use { page ->
                                val scale = 1080f / page.width
                                val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                emptyList()
            } finally {
                file.delete()
            }
        }
    }
    val p = pages
    when {
        p == null -> CircularProgressIndicator()
        p.isEmpty() -> Text(stringResource(R.string.receipts_download_only))
        else -> p.forEach { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
    }
}

/**
 * A picture saved to the device's gallery (Pictures/veyq) through the media store: no permission on the API levels the app
 * runs on, and pending until every byte is written, so the gallery never shows half a picture. False when it did not land.
 */
private suspend fun saveToGallery(context: Context, bytes: ByteArray, name: String, mime: String): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/veyq")
        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: return@withContext false
    val written = runCatching { resolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
    if (!written) {
        resolver.delete(uri, null, null)
        return@withContext false
    }
    resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    true
}
