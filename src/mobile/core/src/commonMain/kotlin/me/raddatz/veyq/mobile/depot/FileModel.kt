package me.raddatz.veyq.mobile.depot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeHeader
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece

/**
 * What the opened file may do, the opened-file rows of Depot's rights matrix (`docs/apps/depot.md` § Guests, the web's
 * `depotRights` at a `FILE`): its download, the replace of its content, its copy, and the "Send a copy" a phone adds where
 * the platform has a share sheet. Fail closed: a viewer the matrix does not name gets nothing.
 */
object FileRights {
    /** Who looks: a member, or a link's guest by the link's role. */
    enum class Viewer { MEMBER, READ, READ_WRITE }

    data class Rights(val download: Boolean, val replace: Boolean, val copy: Boolean, val send: Boolean)

    /**
     * The opened file's rights for [viewer]: a member downloads, copies and replaces — but replaces nothing in a guest's
     * identity folder (nothing is placed there); a READ guest only downloads; a READ_WRITE guest downloads and replaces
     * (not in an identity folder) and copies nothing. "Send a copy" is a download the platform hands on ([shares]), only of
     * a file that holds content.
     */
    fun of(viewer: Viewer, inIdentityFolder: Boolean, hasContent: Boolean, shares: Boolean): Rights {
        val download = true
        val replace = when (viewer) {
            Viewer.MEMBER, Viewer.READ_WRITE -> !inIdentityFolder
            Viewer.READ -> false
        }
        val copy = viewer == Viewer.MEMBER
        return Rights(download = download, replace = replace, copy = copy, send = download && hasContent && shares)
    }
}

/**
 * A file's page (Depot's `FileDetail` and `FileViewer`, ADR 0127 decision 2; `docs/apps/depot.md` § The viewer): what it
 * shows — decided by its first frame's bytes ([FileView]) — and its facts, its uploader, and what its header offers.
 *
 * Nothing decrypted is written to show it (ADR 0047, the CR's D4): an image, a text and a PDF are held in memory, each
 * capped ([FileView.IMAGE_CAP], [FileView.TEXT_CAP], [FileView.PDF_CAP]); a medium is a [RangeReader] the platform's player
 * reads in ranges. A platform decodes an image to [Shown.Image.edge] at most and draws at most [Shown.Pdf.pages] pages.
 *
 * Each app draws [state] (or [watch]); [reload] after the shell's lists changed reads the file anew (a replaced blob shown),
 * [close] when the page goes.
 */
class FileModel(
    val node: NodeAt,
    private val door: Door,
    /** Whether the platform hands a copy on through a share sheet ("Send a copy": every Android, iOS). */
    private val shares: Boolean,
    private val viewer: FileRights.Viewer = FileRights.Viewer.MEMBER,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** What the page reads ([me.raddatz.veyq.mobile.navigation.ShellModel.fileModel] over a tenant, a test over its own). */
    interface Door {
        /** The file as it is listed now, or null where it is not (gone, or its listing did not read). */
        suspend fun file(node: NodeAt): DepotFiles.File?
        fun inIdentityFolder(node: NodeAt): Boolean
        /** [file]'s frames `first until until`, each to [sink] in order. */
        suspend fun read(file: DepotFiles.File, first: Int, until: Int, sink: suspend (ByteArray) -> Unit)
        /** [file]'s content read at any offset (a medium played). */
        fun reader(file: DepotFiles.File): RangeReader
        /** Who uploaded [file]'s content; null where nobody is to be named. */
        suspend fun uploader(file: DepotFiles.File): Uploaders.Uploader?
        /** The upload-queue job that replaces [file]'s content with [source] over [expectedBlobId]. */
        fun replaceJob(file: DepotFiles.File, source: UploadSource, expectedBlobId: String?): DepotFiles.ReplaceJob
    }

    /** What the page shows of the file. */
    sealed class Shown {
        object Loading : Shown()
        /** It did not read: the page says so. */
        object Failed : Shown()
        /** A file whose upload has not finished: no content yet. */
        object NotUploaded : Shown()
        /** Nothing shown here (too large, empty, unsupported): its [words], and the download offered. */
        class Message(val kind: FileView.Kind) : Shown() {
            val words: Msg get() = FileView.message(kind)
        }
        /** An image's [bytes] (≤ [FileView.IMAGE_CAP]), decoded by the platform to [edge] pixels on its longest side at most. */
        class Image(val bytes: ByteArray, val mediaType: String, val edge: Int = FileView.IMAGE_EDGE) : Shown()
        /** A text's first [FileView.TEXT_CAP] bytes decoded; [truncated] where there is more ([Msg.FILE_TEXT_TRUNCATED]). */
        class Text(val text: String, val truncated: Boolean) : Shown()
        /** A PDF's [bytes] (≤ [FileView.PDF_CAP]); a viewer draws its pages as they come into view, at most [pages]. */
        class Pdf(val bytes: ByteArray, val pages: Int = FileView.PDF_PAGES) : Shown()
        /** An audio or video file, played through [reader] (its [size] the file's), of the sniffed [mediaType]. */
        class Media(val reader: RangeReader, val mediaType: String) : Shown() {
            val size: Long get() = reader.size
        }
    }

    /** What the file contributes to its header: the doors' facts, its own actions in plan order, their words, its name. */
    data class Header(
        val facts: AppKinds.Facts,
        /** The file's own actions, Replace and Send a copy where offered ([REPLACE], [SEND]). */
        val own: List<NodeHeader.Action>,
        val replaceWord: Msg,
        /** What its download (the file itself) is saved as. */
        val downloadName: String,
        /** The sealed type of its content (a claim), what a copy handed on is typed as. */
        val mediaType: String,
    ) {
        /** An own action's words; null for another id. */
        fun label(id: String): Msg? = when (id) {
            REPLACE -> replaceWord
            SEND -> Msg.FILE_SEND
            else -> null
        }
    }

    data class State(
        val shown: Shown = Shown.Loading,
        /** Its size, the sealed type (a claim) and when it was modified (`DepotFiles.facts`); empty while not read. */
        val facts: List<Piece> = emptyList(),
        /** "Uploaded by …", or null where nobody is to be named. */
        val uploader: Notice? = null,
        /** What the header offers; null while the file is not read. */
        val header: Header? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    /** The file as last read: what is shown, and what a replace is expected over. */
    private var file: DepotFiles.File? = null
    private val reading = Mutex()

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    init {
        scope.launch { read() }
    }

    /** The shell's lists changed: the file read anew — a replaced blob shown, its facts and header with it. */
    fun reload() {
        scope.launch { read() }
    }

    /** The page went: nothing more is read or watched. */
    fun close() = scope.cancel()

    /**
     * The replace of the content with [source] as an upload-queue job, expected over the content shown now (the web's
     * "the blob the member saw"); null while nothing is read or where the replace is not offered.
     */
    fun replace(source: UploadSource): DepotFiles.ReplaceJob? {
        val f = file ?: return null
        if (current.header?.own?.any { it.id == REPLACE } != true) return null
        return door.replaceJob(f, source, f.blobId)
    }

    private suspend fun read() = reading.withLock {
        val f = runCatching { door.file(node) }.getOrNull()
        if (f == null) {
            if (file == null) _state.update { it.copy(shown = Shown.Failed) }
            return@withLock
        }
        val before = file
        file = f
        val rights = FileRights.of(viewer, door.inIdentityFolder(node), f.hasContent, shares)
        val header = Header(
            facts = AppKinds.Facts(canWrite = rights.replace, hasContent = f.hasContent && rights.download),
            own = listOfNotNull(
                NodeHeader.Action(REPLACE).takeIf { rights.replace },
                NodeHeader.Action(SEND, NodeHeader.Slot.MENU).takeIf { rights.send },
            ),
            replaceWord = DepotFiles.replaceWord(f),
            downloadName = DepotFiles.downloadName(f, node.name),
            mediaType = f.type ?: "application/octet-stream",
        )
        val facts = if (f.hasContent) DepotFiles.facts(f) else emptyList()
        _state.update { it.copy(facts = facts, header = header) }
        // the same content shown already: nothing to read again
        val same = before != null && before.blobId == f.blobId && current.shown !is Shown.Failed && current.shown !is Shown.Loading
        if (!same) {
            _state.update { it.copy(shown = Shown.Loading, uploader = null) }
            _state.update { it.copy(shown = show(f)) }
        }
        if (!same || before?.uploaderKey != f.uploaderKey) {
            val who = runCatching { door.uploader(f) }.getOrNull()
            _state.update { it.copy(uploader = who?.let(Uploaders::words)) }
        }
    }

    private suspend fun show(f: DepotFiles.File): Shown {
        if (!f.hasContent) return Shown.NotUploaded
        val opened = runCatching { FileView.open(f, node.name) { first, until, sink -> door.read(f, first, until, sink) } }.getOrNull()
            ?: return Shown.Failed
        val d = opened.decision
        return when (d.kind) {
            FileView.Kind.IMAGE -> opened.bytes?.let { Shown.Image(it, d.mime ?: "application/octet-stream") } ?: Shown.Failed
            FileView.Kind.TEXT -> Shown.Text(opened.text.orEmpty(), d.truncated)
            FileView.Kind.PDF -> opened.bytes?.let { Shown.Pdf(it) } ?: Shown.Failed
            FileView.Kind.MEDIA -> Shown.Media(door.reader(f), d.mime ?: "application/octet-stream")
            else -> Shown.Message(d.kind)
        }
    }

    companion object {
        /** The file's own header action: replace its content (or the first upload into an empty file). */
        const val REPLACE = "replace"
        /** The file's own header action on a phone: a copy handed to another app through the system's share sheet. */
        const val SEND = "send"
    }
}
