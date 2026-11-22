package me.raddatz.veyq.mobile.android.apps.depot

import me.raddatz.veyq.mobile.depot.DepotGuest

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import me.raddatz.veyq.mobile.android.core.SendCopy
import me.raddatz.veyq.mobile.android.core.show
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.ApplicationUi
import me.raddatz.veyq.mobile.android.core.GuestWriteColumn
import me.raddatz.veyq.mobile.android.core.HeaderContribution
import me.raddatz.veyq.mobile.android.core.InlineNaming
import me.raddatz.veyq.mobile.android.core.KindPage
import me.raddatz.veyq.mobile.android.core.LocalPalette
import me.raddatz.veyq.mobile.android.core.NotYou
import me.raddatz.veyq.mobile.android.core.OwnAction
import me.raddatz.veyq.mobile.android.core.PrimaryButton
import me.raddatz.veyq.mobile.android.core.RecordNotice
import me.raddatz.veyq.mobile.android.core.Registry
import me.raddatz.veyq.mobile.android.core.joined
import me.raddatz.veyq.mobile.android.core.role
import me.raddatz.veyq.mobile.android.core.say
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.sharing.GuestAccess
import me.raddatz.veyq.mobile.sharing.GuestPage

@Composable
private fun fileHeader(node: NodeAt, model: AppModel): HeaderContribution? {
    // what the header offers on a file is core's (`FileModel`'s header: its facts, Replace, Send a copy, its download name)
    val file = androidx.compose.runtime.remember(node) { model.depot.fileModel(node) } ?: return null
    val state by file.state.collectAsStateWithLifecycle()
    val h = state.header ?: return null
    // the replace: the system's picker hands over one document, which replaces the content shown
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.depot.replaceFile(file, it) }
    }
    return HeaderContribution(
        facts = h.facts,
        download = { out -> model.downloadNode(node, out) },
        downloadName = h.downloadName,
        downloadMime = "application/octet-stream",
        // a file's own entries (core's `FileModel.Header.own`), each drawn
        actions = h.own.map { a ->
            when (a) {
                me.raddatz.veyq.mobile.navigation.HeaderAction.REPLACE ->
                    OwnAction(a, say(h.label(a)!!), androidx.compose.material.icons.Icons.Outlined.Upload) {
                        // the content shown now is what the replace expects (core's choice), whatever a reload shows while picking
                        if (file.chooseReplace()) pick.launch(arrayOf("*/*"))
                    }
                // a decrypted copy to another app the person picks (the system's share sheet), after the shell's notice
                me.raddatz.veyq.mobile.navigation.HeaderAction.SEND ->
                    OwnAction(a, say(h.label(a)!!), androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Send) {
                        model.askToSend(SendCopy(node) { model.depot.fileModel(node)?.current?.header?.takeIf { it.facts.hasContent }?.let { it.downloadName to it.mediaType } })
                    }
                // core's own, or another page's: never a file's (`FileModel.Header.own` holds none of these)
                me.raddatz.veyq.mobile.navigation.HeaderAction.FAVORITE, me.raddatz.veyq.mobile.navigation.HeaderAction.SHARE,
                me.raddatz.veyq.mobile.navigation.HeaderAction.RENAME, me.raddatz.veyq.mobile.navigation.HeaderAction.MOVE,
                me.raddatz.veyq.mobile.navigation.HeaderAction.COPY, me.raddatz.veyq.mobile.navigation.HeaderAction.COVER,
                me.raddatz.veyq.mobile.navigation.HeaderAction.DOWNLOAD, me.raddatz.veyq.mobile.navigation.HeaderAction.OFFLINE,
                me.raddatz.veyq.mobile.navigation.HeaderAction.REPORT, me.raddatz.veyq.mobile.navigation.HeaderAction.DELETE,
                me.raddatz.veyq.mobile.navigation.HeaderAction.UPLOAD, me.raddatz.veyq.mobile.navigation.HeaderAction.MUSIC,
                me.raddatz.veyq.mobile.navigation.HeaderAction.BANNER, me.raddatz.veyq.mobile.navigation.HeaderAction.OPEN_OWN_PAGE,
                me.raddatz.veyq.mobile.navigation.HeaderAction.REPORT_SHARED,
                -> error("a file offers no own ${a.name}")
            }
        },
    )
}

/**
 * A drop box's WRITE_ONLY link (the web's `DropBoxShareBody`): the guest names itself — the record's notice before —, then
 * sends files, each into its own folder; what it sent while the link is open here is listed, nothing else of the drop box.
 */
@Composable
private fun GuestDropBoxPage(drop: me.raddatz.veyq.mobile.depot.GuestDropBox, access: GuestAccess?, model: AppModel) {
    val session by drop.session.collectAsStateWithLifecycle()
    val sent by drop.sent.collectAsStateWithLifecycle()
    val queue by model.uploads.state.collectAsStateWithLifecycle()
    val days = access?.guestWriteRecordDays
    val origin = access?.origin.orEmpty()
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val sources = withContext(Dispatchers.IO) { uris.mapNotNull { model.picked(it) }.map { model.source(it) } }
            drop.upload(sources)
        }
    }
    GuestWriteColumn {
        Text(stringResource(R.string.share_dropbox_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.share_dropbox_hint, drop.node.name), color = role(LocalPalette.current.muted))
        val named = session
        if (named == null) {
            InlineNaming(stringResource(R.string.share_dropbox_name_hint), days, origin) { drop.name(it) }
        } else {
            NotYou(named, drop::forget)
            RecordNotice(days, origin, named.identityId)
            PrimaryButton(stringResource(R.string.share_dropbox_pick), Modifier.testTag("guest-drop-pick")) { pick.launch(arrayOf("*/*")) }
            val mine = queue.batches.filter { it.label == drop.node.name }.flatMap { it.items }
            if (sent.isNotEmpty() || mine.isNotEmpty()) {
                Text(stringResource(R.string.share_dropbox_session), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                for (item in mine) Text(
                    listOf(
                        item.name,
                        stringResource(
                            when {
                                item.active -> R.string.share_dropbox_sending
                                item.status == me.raddatz.veyq.mobile.blob.UploadQueue.Status.FAILED -> R.string.share_dropbox_failed
                                else -> R.string.share_dropbox_sent
                            },
                        ),
                    ).joined(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                for (name in sent.filter { n -> mine.none { it.name == n } }) Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("guest-drop-sent"))
            }
        }
    }
}

/**
 * Depot's UI as it registers into the core UI (ADR 0154 decision 3; the web's `registerDepot`): a file's page — its
 * download under its own name and its replace in core's header, its viewer (`FileScreen`), a link's file — and a drop
 * box's — its guests' folders (`DropBoxScreen`), a link's drop page.
 */
internal object DepotUi {
    /** Depot's file: its download under its own name, its replace (or first upload), its page (`FileScreen`). */
    private val file = KindPage(
        header = { node, model -> fileHeader(node, model) },
        body = { ref, _, model -> FileScreen(ref, model) },
        // a file's download and a READ_WRITE guest's replace on the link's bar
        guestActions = { page, screen, model, snackbar -> if (page is DepotGuest.File) GuestFileActions(page, screen.view.access, model, snackbar) },
        guest = { page, _, _, _ ->
            if (page is DepotGuest.File) {
                DisposableEffect(page.model) { onDispose { page.model.close() } }
                FilePage(page.model, page.node.name)
            }
        },
    )

    /** Depot's drop box: its guests' folders (`DropBoxScreen`); never renamed; its cover; its ZIP and its copy (a plain folder). */
    private val dropBox = KindPage(
        header = { node, model ->
            HeaderContribution(
                download = { out -> model.downloadNode(node, out) },
            )
        },
        body = { ref, _, model -> DropBoxScreen(ref, model) },
        guest = { page, screen, model, _ -> if (page is DepotGuest.DropBox) GuestDropBoxPage(page.model, screen.view.access, model) },
    )

    fun register(registry: Registry) {
        registry.kind(file, me.raddatz.veyq.mobile.depot.DepotFiles.FILE)
        registry.kind(dropBox, me.raddatz.veyq.mobile.depot.DepotFiles.DROPBOX)
        registry.application(ApplicationUi(ApplicationId.DEPOT, holding = DepotHeld))
        // an SVG is drawn by the WebView's image mode, closed around it (SvgPicture.kt, inbox #1059)
        me.raddatz.veyq.mobile.depot.FileView.drawSvgAsImage()
    }
}

/**
 * A file's own actions on the link's bar: its download (a copy where the person picks) and, where core offers it
 * (`FileRights.replace`), a READ_WRITE guest's replace — a guest not named yet asked its name at the write.
 */
@Composable
private fun GuestFileActions(page: DepotGuest.File, access: GuestAccess?, model: AppModel, snackbar: SnackbarHostState) {
    val file = page.model
    val state by file.state.collectAsStateWithLifecycle()
    val h = state.header ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val run = file.save { produce ->
            val out = withContext(Dispatchers.IO) { resolver.openOutputStream(uri) } ?: return@save false
            out.use { o -> produce { o.write(it) } }
        } ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ended = run.state.first { it.end != null }
            if (ended.end != me.raddatz.veyq.mobile.blob.FileSave.End.SAVED) {
                withContext(Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) } }
            }
            snackbar.show(context, ended.notice)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val a = access ?: return@rememberLauncherForActivityResult
        uri?.let { model.depot.guestReplace(a, file, it) }
    }
    IconButton(onClick = { save.launch(h.downloadName) }) { Icon(Icons.Outlined.Download, stringResource(R.string.node_download)) }
    if (access != null && h.own.any { it == me.raddatz.veyq.mobile.navigation.HeaderAction.REPLACE }) {
        IconButton(onClick = { if (file.chooseReplace()) pick.launch(arrayOf("*/*")) }) {
            Icon(Icons.Outlined.Upload, say(h.label(me.raddatz.veyq.mobile.navigation.HeaderAction.REPLACE)!!))
        }
    }
}
