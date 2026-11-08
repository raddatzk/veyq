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
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.expenses.EntryForm
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.expenses.Receipts

/** A picked file read whole (a receipt is at most 10 MiB): its bytes, type, name and modification; null when unreadable. */
private fun readPicked(context: Context, uri: Uri): PotSession.PendingReceipt? = runCatching {
    val resolver = context.contentResolver
    var name: String? = null
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            name = c.getString(0)
            size = if (c.isNull(1)) -1 else c.getLong(1)
        }
    }
    if (size > Receipts.MAX_BYTES) return null
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    PotSession.PendingReceipt(java.util.UUID.randomUUID().toString(), bytes, resolver.getType(uri).orEmpty(), name, null)
}.getOrNull()

/**
 * An entry's receipts in the editor (`EntryReceipts.vue`): the ones the entry keeps, each opened by its bytes or taken
 * off (the save drops it), and those picked meanwhile, uploaded only when the entry is saved. Images and PDFs are
 * offered; what a receipt IS, the bytes decide when it is opened.
 */
@Composable
internal fun EntryReceipts(view: PotView, model: AppModel, form: EntryForm, pending: List<PotSession.PendingReceipt>, onForm: (EntryForm) -> Unit, onPending: (List<PotSession.PendingReceipt>) -> Unit) {
    val context = LocalContext.current
    var refused by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        var held = form.receipts.size + pending.size
        val added = ArrayList<PotSession.PendingReceipt>()
        refused = false
        for (uri in uris) {
            val r = readPicked(context, uri)
            if (r == null || !view.receiptAllowed(r.size, r.type, r.name, held)) {
                refused = true
                continue
            }
            added += r
            held++
        }
        onPending(pending + added)
    }
    val palette = LocalPalette.current
    val editable = LocalEditable.current
    Text(stringResource(R.string.receipts_title), fontWeight = FontWeight.SemiBold)
    if (form.receipts.isEmpty() && pending.isEmpty()) Text(stringResource(R.string.receipts_none), color = role(palette.muted))
    for (id in form.receipts) {
        val r = view.receipt(id)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewing = id }, enabled = r != null && !r.blocked, modifier = Modifier.weight(1f)) {
                Text(Receipts.downloadName(r?.name, r?.type), Modifier.fillMaxWidth())
            }
            if (editable) IconButton(onClick = { onForm(form.copy(receipts = form.receipts - id)) }) { Icon(Icons.Outlined.Close, stringResource(R.string.receipts_remove)) }
        }
    }
    for (p in pending) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Receipts.downloadName(p.name, p.type), Modifier.weight(1f).padding(start = 12.dp), color = role(palette.muted))
            if (editable) IconButton(onClick = { onPending(pending - p) }) { Icon(Icons.Outlined.Close, stringResource(R.string.receipts_remove)) }
        }
    }
    if (refused) Text(stringResource(R.string.receipts_refused), color = MaterialTheme.colorScheme.error)
    if (editable && form.receipts.size + pending.size < Receipts.MAX_PER_ENTRY) {
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
        dismissButton = { if (bytes != null) TextButton(onClick = { save.launch(name) }) { Text(stringResource(R.string.receipts_save)) } },
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
