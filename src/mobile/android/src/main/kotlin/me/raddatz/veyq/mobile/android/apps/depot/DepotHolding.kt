package me.raddatz.veyq.mobile.android.apps.depot

import me.raddatz.veyq.mobile.blob.DirectoryWalk
import android.app.Application
import android.provider.DocumentsContract
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.android.core.AddedHere
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.Holding
import me.raddatz.veyq.mobile.android.core.HoldingKey
import me.raddatz.veyq.mobile.android.core.LevelUploads
import me.raddatz.veyq.mobile.android.core.AppModel.Notice
import me.raddatz.veyq.mobile.android.core.ShareIn
import me.raddatz.veyq.mobile.android.core.TrayShown
import me.raddatz.veyq.mobile.android.core.text
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.navigation.AddHere
import me.raddatz.veyq.mobile.navigation.DropOutcome
import me.raddatz.veyq.mobile.navigation.DropTarget
import me.raddatz.veyq.mobile.navigation.Dropped
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.depot.DepotUploads
import me.raddatz.veyq.mobile.depot.DropBoxPage
import me.raddatz.veyq.mobile.depot.FileModel
import me.raddatz.veyq.mobile.depot.replaceAsking
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.sharing.GuestAccess
import me.raddatz.veyq.mobile.words.NoticeKind

/** The key Depot's UI reaches its [DepotHolding] by (registered in [DepotUi.register]). */
internal val DepotHeld = HoldingKey(::DepotHolding)

/** What Depot holds in the app's model: the shell's uploads and the file page on screen ([DepotHolding]). */
internal val AppModel.depot: DepotHolding get() = held(DepotHeld)

/**
 * What Depot holds in the app's model for the open tenant (ADR 0154 decision 3): the shell's uploads (core's
 * `DepotUploads` over the app's one queue, one per open tenant) — the tray draws them, New's *Upload files* and *Upload
 * folder* and a level's drop and *Add here* go through them, and what they say is the shell's snackbar —, and the file
 * page on screen.
 */
internal class DepotHolding(private val model: AppModel) : Holding(), LevelUploads {
    private val scope get() = model.viewModelScope
    private val app get() = model.getApplication<Application>()

    private val uploads = MutableStateFlow<DepotUploads?>(null)
    private val _tray = MutableStateFlow<TrayShown?>(null)
    private var trayWatch: Job? = null

    override val tray: StateFlow<TrayShown?> = _tray.asStateFlow()
    override val levelUploads: LevelUploads get() = this

    /** What the uploads say, each once, as the shell's snackbar: its action takes the offer, else it is seen. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sayUploads = scope.launch {
        uploads.flatMapLatest { d -> d?.state?.map { d to it.said } ?: flowOf(null) }
            .distinctUntilChanged { a, b -> a?.second?.id == b?.second?.id }
            .collect { pair ->
                val (d, said) = pair ?: return@collect
                said ?: return@collect
                // an offer waits for its answer; a line said with a refusal stays as the refusal does
                val kind = if (said.action != null || said.notices.any { it.kind != NoticeKind.SAID }) NoticeKind.HELD else NoticeKind.SAID
                model.notify(Notice(said.notices.joinToString("\n") { app.text(it) }, kind, said.action?.let { app.words(it) }, run = { d.take(said) }) { d.seen(said.id) })
            }
    }

    override fun leave() {
        openFile?.close()
        openFile = null
    }

    override fun shown(shell: ShellModel, tenant: TenantSession, switched: Boolean) {
        uploads.value?.close()
        val d = shell.depotUploads(model.uploads) { scope.launch { model.refreshLists(tenant) } }
        uploads.value = d
        trayWatch?.cancel()
        trayWatch = scope.launch { d.state.collect { _tray.value = TrayShown(it.queue, it.preparing) } }
    }

    override fun signedOut() {
        trayWatch?.cancel()
        trayWatch = null
        _tray.value = null
        uploads.value?.close()
        uploads.value = null
    }

    /** The file on screen reads itself anew: a replaced blob is shown. */
    override fun listsRead() {
        openFile?.reload()
    }

    /** *Add here* on the level shown: its files into the queue under [label]. Any file is taken (`FolderShare`), none left out. */
    override fun addHere(target: AddHere, label: String): AddedHere? {
        if (target !is AddHere.Level) return null
        return target.take(model.shareInbox, label, uploads.value, ShareIn::release)?.let { AddedHere(null) }
    }

    /** Files dropped onto the level shown or a folder's row: uploaded under it (core's one drop). */
    override suspend fun drop(shell: ShellModel, target: DropTarget, items: List<Dropped>): DropOutcome? {
        if (target !is DropTarget.Level && target !is DropTarget.Row) return null
        return shell.drop(target, items, uploads.value, ShareIn::release)
    }

    override fun cancelAll() { uploads.value?.cancelAll() }
    override fun acknowledge(batchId: String) { uploads.value?.acknowledge(batchId) }
    override fun cancel(id: String) { uploads.value?.cancel(id) }
    override fun retry(id: String) { uploads.value?.retry(id) }

    /**
     * Queues the picked [uris] as new files under the level shown ([label] its name; core's `DepotUploads.upload`): a name a
     * file already holds offers its replace.
     */
    override fun upload(scopeId: String, parentId: String?, label: String, uris: List<android.net.Uri>) {
        val d = uploads.value ?: return
        scope.launch {
            val sources = withContext(Dispatchers.IO) { uris.mapNotNull { model.picked(it) }.map { model.source(it) } }
            d.upload(scopeId, parentId, label, sources)
        }
    }

    /** The header's "Replace…": the picked document replaces the content [file] showed when Replace was chosen. */
    fun replaceFile(file: FileModel, uri: android.net.Uri) {
        val d = uploads.value ?: return
        scope.launch {
            val p = withContext(Dispatchers.IO) { model.picked(uri) } ?: return@launch
            d.replace(file, model.source(p))
        }
    }

    /**
     * Depot's "Upload folder…": the directory the system's picker granted ([tree], `OpenDocumentTree`) walked through its
     * documents provider, each entry handed to core's `DepotUploads.uploadFolder`, which keeps, prepares, queues and says.
     */
    override fun uploadFolder(scopeId: String, parentId: String?, tree: android.net.Uri) {
        val d = uploads.value ?: return
        scope.launch {
            val walk = withContext(Dispatchers.IO) { treeWalk(tree) }
            d.uploadFolder(scopeId, parentId, walk.shown, walk.path, walk.found)
        }
    }

    /** A guest's *Upload folder*: the directory walked as a member's is, handed to core's `DepotGuest.uploadFolder`. */
    override fun guestUploadFolder(
        level: me.raddatz.veyq.mobile.sharing.GuestLevel, tree: android.net.Uri, say: suspend (List<me.raddatz.veyq.mobile.words.Notice>) -> Unit,
    ) {
        scope.launch {
            val walk = withContext(Dispatchers.IO) { treeWalk(tree) }
            val r = withContext(Dispatchers.IO) { me.raddatz.veyq.mobile.depot.DepotGuest.uploadFolder(level, walk.shown, walk.path, walk.found) }
            say(r.said)
        }
    }

    /** What the documents provider lists of a picked directory: its shown name (if it gives one), its id, every entry below. */
    private class TreeWalk(val shown: String?, val path: String, val found: List<DirectoryWalk.Found>)

    /** The directory [tree] names walked: each entry by its path there (`/`-separated) and what the provider says it is. */
    private fun treeWalk(tree: android.net.Uri): TreeWalk {
        val resolver = app.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val shown = runCatching {
            resolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        val out = ArrayList<DirectoryWalk.Found>()
        fun walk(docId: String, path: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val rows = ArrayList<Array<Any?>>()
            val listed = runCatching {
                resolver.query(children, cols, null, null, null)?.use { q ->
                    while (q.moveToNext()) rows += arrayOf(q.getString(0), q.getString(1), q.getString(2), if (q.isNull(3)) 0L else q.getLong(3), if (q.isNull(4)) null else q.getLong(4))
                }
            }.getOrNull()
            // a directory the provider does not list is said, never silently skipped
            if (listed == null) { out += DirectoryWalk.Found(path.ifEmpty { shown ?: rootId }, DirectoryWalk.Kind.UNREADABLE); return }
            for (row in rows) {
                val id = row[0] as String
                val name = row[1] as String
                val mime = row[2] as String?
                val at = if (path.isEmpty()) name else "$path/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    out += DirectoryWalk.Found(at, DirectoryWalk.Kind.DIRECTORY)
                    walk(id, at)
                    continue
                }
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                out += DirectoryWalk.Found(at, DirectoryWalk.Kind.FILE, UploadSource.of(name, row[3] as Long, mime ?: "application/octet-stream", row[4] as Long?) { from, length ->
                    // opened per frame: the upload asks one frame after another and never holds the file
                    val pfd = resolver.openFileDescriptor(uri, "r") ?: throw java.io.IOException("unreadable")
                    pfd.use { d ->
                        java.io.FileInputStream(d.fileDescriptor).channel.use { ch ->
                            val buf = java.nio.ByteBuffer.allocate(length)
                            while (buf.hasRemaining()) if (ch.read(buf, from + buf.position()) < 0) break
                            buf.array().copyOf(buf.position())
                        }
                    }
                })
            }
        }
        walk(rootId, "")
        return TreeWalk(shown, rootId, out)
    }

    /** The file page on screen (core's `FileModel`): one at a time, read anew whenever the lists are. */
    private var openFile: FileModel? = null

    /** The page of file [node] (core's `FileModel` over the shell; Android has a share sheet, so it sends a copy). */
    fun fileModel(node: NodeAt): FileModel? {
        openFile?.takeIf { it.node == node }?.let { return it }
        val m = model.shellModel ?: return null
        openFile?.close()
        return m.fileModel(node, shares = true).also { openFile = it }
    }

    /** Drop box [ref]'s page (core's `DropBoxPage`: its guests' folders read anew with the lists, a delete asked first). */
    fun dropBoxPage(ref: NodeAt): DropBoxPage? = model.shellModel?.dropBoxPage(ref)

    /**
     * A READ_WRITE guest's replace of [file]'s content with the picked document (ADR 0150 decision 5, #1035): core's
     * `GuestAccess.replaceAsking`, on the link's own queue (the app's one, handed to the link) — signed in or out; the page reads
     * the file anew once it settled.
     */
    fun guestReplace(access: GuestAccess, file: FileModel, uri: android.net.Uri) {
        scope.launch {
            val p = withContext(Dispatchers.IO) { model.picked(uri) } ?: return@launch
            val source = model.source(p)
            // a guest not named yet is asked its name first (core's naming flow), declined nothing is replaced
            if (!access.replaceAsking(file, source)) source.close()
        }
    }
}
