package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.blob.DirectoryWalk.Found
import me.raddatz.veyq.mobile.blob.DirectoryWalk.Kind
import me.raddatz.veyq.mobile.blob.UploadQueue
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
        val jobs: List<UploadQueue.Job> = emptyList(),
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

    /** What making one folder came to ([Maker.folder]). */
    sealed class Made<out P> {
        class Done<P>(val folder: P) : Made<P>()
        /** A sibling holds the name already: the top takes the next number. */
        object Taken : Made<Nothing>()
        object Failed : Made<Nothing>()
    }

    /**
     * Where a planned directory is made: a member's space ([prepare], a folder by its id) or a link guest's folder
     * ([prepareGuest], a folder by its opened node) — the plan, the top's numbering and the order are one rule for both.
     */
    interface Maker<P> {
        suspend fun folder(parent: P, name: String): Made<P>
        /** The job uploading [entry] as file [name] into [parent]. */
        fun job(parent: P, name: String, entry: Entry): UploadQueue.Job
        /** The node id of a folder made. */
        fun id(folder: P): String?
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
        val siblings = tenant.trees()[scopeId].orEmpty().filter { it.parentId == parentId }.mapNotNull { tokenInput(it.kind, it.name) }
        val report = made(plan, parentId, siblings, tokenInput, object : Maker<String?> {
            override suspend fun folder(parent: String?, name: String): Made<String?> =
                when (val r = runCatching { FolderCreate.create(tenant, scopeId, parent, name) }.getOrNull()) {
                    is NodeCreate.Result.Done -> Made.Done(r.created.nodeId)
                    is NodeCreate.Result.Refused -> if (r.reason == NodeCreate.Reason.NAME_TAKEN) Made.Taken else Made.Failed
                    null -> Made.Failed
                }
            override fun job(parent: String?, name: String, entry: Entry): UploadQueue.Job =
                DepotFiles.UploadJob(tenant, scopeId, parent, entry.source, tokenInput, name)
            override fun id(folder: String?): String? = folder
        })
        tenant.forgetTree()
        return report
    }

    /**
     * [plan] made under [parent] through [maker]: the top takes the directory's name numbered past the level's [siblings]
     * (their slug inputs) by slug, never merged into one of them; then every folder depth first, each folder's files queued
     * before its subfolders are made (`createFolders`); a folder not made is said with everything in it left out.
     */
    suspend fun <P> made(plan: Plan, parent: P, siblings: List<String>, tokenInput: (String, String) -> String?, maker: Maker<P>): Report {
        val roots = Namer(tokenInput, siblings)
        var top: P? = null
        var made = false
        var name = plan.root.name
        for (i in 1..ROOT_TRIES) {
            name = roots.next(FOLDER, plan.root.name)
            when (val r = maker.folder(parent, name)) {
                is Made.Done -> { top = r.folder; made = true }
                Made.Taken -> continue
                Made.Failed -> return Report(Outcome.ROOT_FAILED, name, plan.bytes)
            }
            break
        }
        if (!made) return Report(Outcome.ROOT_FAILED, name, plan.bytes)
        @Suppress("UNCHECKED_CAST") val root = top as P

        val jobs = ArrayList<UploadQueue.Job>()
        val failedFolders = ArrayList<String>()
        suspend fun make(dir: Dir, at: P, path: String) {
            dir.files.forEach { (n, e) -> jobs += maker.job(at, n, e) }
            for (sub in dir.dirs) {
                val r = maker.folder(at, sub.name)
                if (r is Made.Done) make(sub, r.folder, "$path/${sub.name}") else failedFolders += "$path/${sub.name}"
            }
        }
        make(plan.root, root, name)
        return Report(Outcome.READY, name, plan.bytes, maker.id(root), failedFolders = failedFolders, jobs = jobs)
    }
}
