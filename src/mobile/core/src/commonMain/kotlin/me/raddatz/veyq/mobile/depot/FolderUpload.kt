package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.nfc
import me.raddatz.veyq.mobile.export.ArchiveName
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A directory from the device uploaded into a level (Depot's "Upload folder…", the web's `features/depot/uploadFolder.ts`
 * and `levelRows.ts` `uploadFolder`): only its files make nodes — an empty directory makes none. Planned first ([plan]):
 * each directory's subfolders named before its files through one namer — the archive's safe name (`ArchiveName`, a clash
 * numbered `Name (2).ext`), numbered on while its kind's slug is taken there. Then, for a member, the tenant is asked whether
 * the files' bytes fit its storage (`checkTenantStorageFit`, advisory: a check that does not answer lets the upload go on,
 * the upload's own `quota_exceeded` decides). The top folder takes the directory's name numbered past the level's siblings
 * (`Name (2)`, never merged into an existing folder), then every folder is made depth first — one that cannot be made is
 * said with everything in it left out — and every file becomes one job for the app's upload queue
 * ([DepotFiles.UploadJob]), which uploads them one after another and retries each once but a refusal for room. Nothing
 * is rolled back: what went up stays.
 */
object FolderUpload {
    /** One file of the directory: its path below the directory (`/`-separated) and the file itself. */
    class Entry(val path: String, val source: UploadSource) {
        val size get() = source.size
    }

    /** What a walk of a picked directory met at one path: a file, a directory, a link, or an entry it could not read. */
    enum class Kind { FILE, DIRECTORY, LINK, UNREADABLE, OTHER }

    /**
     * One entry a platform's walk met (its own door: Android's documents provider, Apple's enumerator, the desktops'
     * `listDirectory`): its path below the picked directory (`/`-separated), what it is, and for a file its [source].
     */
    class Found(val path: String, val kind: Kind, val source: UploadSource? = null)

    /**
     * Whether a walk keeps [found] for the upload (the web's directory input, `docs/apps/depot.md`): a regular file that
     * reads — hidden ones too (a name starting with a dot is kept, as the web's input and Apple's enumerator hold them);
     * never a link (a walk does not follow one, so nothing outside the directory goes up), a directory (only files make
     * nodes), or an entry that did not read.
     */
    fun keeps(found: Found): Boolean = found.kind == Kind.FILE && found.source != null

    /** What a walk hands the upload: the [entries] kept, and the paths left out and said ([links], [unreadable]). */
    class Walked(val entries: List<Entry>, val links: List<String>, val unreadable: List<String>)

    /** [found] split by [keeps]: the files to upload, each link and each entry that did not read reported by its path. */
    fun walked(found: List<Found>): Walked = Walked(
        entries = found.filter(::keeps).map { Entry(it.path, it.source!!) },
        links = found.filter { it.kind == Kind.LINK }.map { it.path },
        // a file that did not open is as unreadable as an entry the walk could not list
        unreadable = found.filter { it.kind == Kind.UNREADABLE || (it.kind == Kind.FILE && it.source == null) }.map { it.path },
    )

    /**
     * The name the uploaded folder takes: the picked directory's own ([shown], what the platform's picker names it), else
     * the last part of its [path] (a path's or a document id's, parted at `/`, a backslash and `:`) — never a word of the app's own.
     */
    fun rootName(shown: String?, path: String): String {
        shown?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return path.split('/', '\\', ':').lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    /** A directory of the plan: its name as it will be made, its subdirectories and its files (each its name and entry). */
    class Dir(val name: String, val dirs: List<Dir>, val files: List<Pair<String, Entry>>)

    class Plan(val root: Dir, val files: Int, val bytes: Long)

    enum class Outcome {
        /** The tenant has no room for it ([Report.bytes]): nothing was written. */
        NO_ROOM,
        /** The top folder could not be made: nothing of it was uploaded. */
        ROOT_FAILED,
        /** The directory holds no file. */
        NOTHING,
        /** The folders are made: the files are [Report.jobs], for the upload queue. */
        READY,
    }

    class Report(
        val outcome: Outcome,
        val name: String,
        val bytes: Long = 0,
        val rootId: String? = null,
        /** The folders not made, as `root/sub/…`: nothing in them went up. */
        val failedFolders: List<String> = emptyList(),
        /** One job per file whose folder was made, in the order the web queues them. */
        val jobs: List<DepotFiles.UploadJob> = emptyList(),
    )

    const val ROOT_TRIES = 20
    private const val NAME_TRIES = 200
    private const val FOLDER = "FOLDER"

    /** The names one directory gives out: safe and told apart by name, and by slug within its kind's group. */
    private class Namer(private val tokenInput: (String, String) -> String?, tokens: Collection<String> = emptyList()) {
        private val taken = HashSet<String>()
        private val slugs = HashSet(tokens)
        fun next(kind: String, name: String): String {
            repeat(NAME_TRIES) {
                val candidate = ArchiveName.of(name, taken)
                val slug = tokenInput(kind, candidate) ?: return candidate
                if (slugs.add(slug)) return candidate
            }
            throw IllegalStateException("no free name for $name")
        }
    }

    /** [entries] (paths below a directory named [rootName]) as the tree to make; the top's name not yet numbered. */
    fun plan(rootName: String, entries: List<Entry>, tokenInput: (String, String) -> String?): Plan {
        class Building {
            val dirs = LinkedHashMap<String, Building>()
            val files = ArrayList<Pair<String, Entry>>()
        }
        val top = Building()
        for (e in entries) {
            val parts = nfc(e.path).split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            var at = top
            for (segment in parts.dropLast(1)) at = at.dirs.getOrPut(segment) { Building() }
            at.files += parts.last() to e
        }
        fun name(b: Building, as_: String): Dir {
            val namer = Namer(tokenInput)
            // the subfolders first, then the files, through one namer (`planFolderUpload`)
            val dirNames = b.dirs.keys.map { it to namer.next(FOLDER, it) }
            val files = b.files.map { (n, e) -> namer.next(DepotFiles.FILE, n) to e }
            return Dir(as_, dirNames.map { (orig, made) -> name(b.dirs.getValue(orig), made) }, files)
        }
        val root = name(top, nfc(rootName))
        val count = entries.size
        return Plan(root, count, entries.sumOf { it.size })
    }

    /**
     * Makes the folders of the directory named [rootName] ([entries]) under [parentId] (null: the space's top) of [scopeId]
     * and hands back its files as upload jobs ([Report.jobs]): the queue sends them.
     */
    @Throws(Exception::class)
    suspend fun prepare(
        tenant: TenantSession, scopeId: String, parentId: String?, rootName: String, entries: List<Entry>,
        tokenInput: (String, String) -> String?,
    ): Report {
        val plan = plan(rootName, entries, tokenInput)
        if (plan.files == 0) return Report(Outcome.NOTHING, rootName)
        if (me.raddatz.veyq.mobile.session.StorageFit.fits(tenant, plan.bytes) == false) return Report(Outcome.NO_ROOM, plan.root.name, plan.bytes)

        // the top: the directory's name, numbered past the level's siblings by slug, never merged into one of them
        val siblings = tenant.trees()[scopeId].orEmpty().filter { it.parentId == parentId }.mapNotNull { tokenInput(it.kind, it.name) }
        val roots = Namer(tokenInput, siblings)
        var rootId: String? = null
        var name = plan.root.name
        for (i in 1..ROOT_TRIES) {
            name = roots.next(FOLDER, plan.root.name)
            when (val r = FolderCreate.create(tenant, scopeId, parentId, name)) {
                is NodeCreate.Result.Done -> rootId = r.created.nodeId
                is NodeCreate.Result.Refused -> if (r.reason == NodeCreate.Reason.NAME_TAKEN) continue else return Report(Outcome.ROOT_FAILED, name, plan.bytes)
            }
            break
        }
        val top = rootId ?: return Report(Outcome.ROOT_FAILED, name, plan.bytes)

        // every folder depth first, each folder's files queued before its subfolders are made (`createFolders`)
        val jobs = ArrayList<DepotFiles.UploadJob>()
        val failedFolders = ArrayList<String>()
        suspend fun make(dir: Dir, id: String, path: String) {
            dir.files.forEach { (n, e) -> jobs += DepotFiles.UploadJob(tenant, scopeId, id, e.source, tokenInput, n) }
            for (sub in dir.dirs) {
                val made = runCatching { FolderCreate.create(tenant, scopeId, id, sub.name) }.getOrNull()
                if (made is NodeCreate.Result.Done) make(sub, made.created.nodeId, "$path/${sub.name}")
                else failedFolders += "$path/${sub.name}"
            }
        }
        make(plan.root, top, name)
        tenant.forgetTree()
        return Report(Outcome.READY, name, plan.bytes, top, failedFolders = failedFolders, jobs = jobs)
    }
}
