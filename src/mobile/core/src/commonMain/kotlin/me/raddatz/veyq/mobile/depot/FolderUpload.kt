package me.raddatz.veyq.mobile.depot

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
 * said with everything in it left out — and then the files are uploaded one after another, each tried twice but a refusal
 * for room. Nothing is rolled back: what went up stays.
 */
object FolderUpload {
    /** One file of the directory: its path below the directory (`/`-separated), size, type, modified time, and its bytes. */
    class Entry(val path: String, val size: Long, val type: String, val modifiedAt: Long?, val read: suspend (from: Long, length: Int) -> ByteArray)

    /** A directory of the plan: its name as it will be made, its subdirectories and its files (each its name and entry). */
    class Dir(val name: String, val dirs: List<Dir>, val files: List<Pair<String, Entry>>)

    class Plan(val root: Dir, val files: Int, val bytes: Long)

    enum class Outcome {
        /** Every folder made and every file uploaded. */
        DONE,
        /** The top folder is made, but a folder or a file of it was not ([Report.failedFolders], [Report.failedFiles]). */
        PARTIAL,
        /** The tenant has no room for it ([Report.bytes]): nothing was written. */
        NO_ROOM,
        /** The top folder could not be made: nothing of it was uploaded. */
        ROOT_FAILED,
        /** The directory holds no file. */
        NOTHING,
    }

    class Report(
        val outcome: Outcome,
        val name: String,
        val bytes: Long = 0,
        val rootId: String? = null,
        val uploaded: Int = 0,
        /** The folders not made, as `root/sub/…`: nothing in them went up. */
        val failedFolders: List<String> = emptyList(),
        /** The files not uploaded, by name, with why. */
        val failedFiles: List<Pair<String, DepotFiles.Outcome>> = emptyList(),
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
     * Uploads [entries] of the directory named [rootName] under [parentId] (null: the space's top) of [scopeId]; [onFile] is
     * told each file as it starts (its name, how many went before, of how many).
     */
    @Throws(Exception::class)
    suspend fun upload(
        tenant: TenantSession, scopeId: String, parentId: String?, rootName: String, entries: List<Entry>,
        tokenInput: (String, String) -> String?, onFile: (name: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
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
        val jobs = ArrayList<Triple<String, String, Entry>>()
        val failedFolders = ArrayList<String>()
        suspend fun make(dir: Dir, id: String, path: String) {
            dir.files.forEach { (n, e) -> jobs += Triple(id, n, e) }
            for (sub in dir.dirs) {
                val made = runCatching { FolderCreate.create(tenant, scopeId, id, sub.name) }.getOrNull()
                if (made is NodeCreate.Result.Done) make(sub, made.created.nodeId, "$path/${sub.name}")
                else failedFolders += "$path/${sub.name}"
            }
        }
        make(plan.root, top, name)

        // the files one after another, each tried twice but a refusal for room (the web's queue, `MAX_ATTEMPTS`)
        val failedFiles = ArrayList<Pair<String, DepotFiles.Outcome>>()
        var uploaded = 0
        for ((i, job) in jobs.withIndex()) {
            val (folderId, fileName, e) = job
            onFile(fileName, i, jobs.size)
            var outcome = DepotFiles.Outcome.FAILED
            for (attempt in 1..2) {
                outcome = runCatching {
                    DepotFiles.upload(tenant, scopeId, folderId, fileName, e.size, e.type, e.modifiedAt, tokenInput, e.read).outcome
                }.getOrDefault(DepotFiles.Outcome.FAILED)
                if (outcome == DepotFiles.Outcome.DONE || outcome == DepotFiles.Outcome.NO_ROOM) break
            }
            if (outcome == DepotFiles.Outcome.DONE) uploaded++ else failedFiles += fileName to outcome
        }
        tenant.forgetTree()
        val whole = failedFolders.isEmpty() && failedFiles.isEmpty()
        return Report(if (whole) Outcome.DONE else Outcome.PARTIAL, name, plan.bytes, top, uploaded, failedFolders, failedFiles)
    }
}
