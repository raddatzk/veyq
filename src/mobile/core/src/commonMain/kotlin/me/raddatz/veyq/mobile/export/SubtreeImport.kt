package me.raddatz.veyq.mobile.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.StorageFit
import me.raddatz.veyq.mobile.session.TenantSession
import kotlinx.serialization.json.Json

/**
 * Import a download (ADR 0082 decisions 6–10, ADR 0152 decision 2), the web's `core/export/importSubtree.ts`: a member's
 * flow, and everything lands under the importer. Nothing of the source is carried but names, order, content and plaintext
 * metadata: ids, keys and wraps are made fresh under the target's keys, and a name taken among the target's siblings gets
 * `Name (2)`. Before anything is written, [open] and [plan] — the DRY RUN — refuse an archive that is not sound (the ZIP's
 * structure, the manifest against the contract and its caps, every payload present at its declared size, each kind's own
 * content check) and list per application what this build cannot import (decision 9: an application it does not have, or
 * content newer than it reads). [run] then imports the rest — exactly what the plan does not leave out — top-down, each
 * payload checked before its kind's hook takes it ([Payloads]). The server offers no multi-node atomicity, so an import is a
 * sequence of ordinary writes; a failure or a cancel trashes the top-level node it created (its subtree goes with it) and
 * reports how far it got ([Failed]).
 */
object SubtreeImport {
    /** An opened download: where its files are read and its checked manifest. */
    class Opened(val source: ImportSource, val manifest: Manifest)

    /** Any refusal of the ZIP layer as the import names it: a structure the reader does not accept is a damaged archive. */
    internal fun refusalOf(e: ZipReader.ZipError, detail: String? = null): ImportRefused = when (e.code) {
        ZipReader.ZipError.Code.CRC_MISMATCH -> ImportRefused(ImportRefused.Refusal.CHECKSUM, e.message ?: "checksum", detail)
        ZipReader.ZipError.Code.MISSING -> ImportRefused(ImportRefused.Refusal.PAYLOAD_MISSING, e.message ?: "missing", detail)
        else -> ImportRefused(ImportRefused.Refusal.DAMAGED_ARCHIVE, e.message ?: "damaged", detail)
    }

    /**
     * Recognise and open a download by its CONTENT (never its name): its source read and checked ([ZipReader.open]), the
     * manifest found (`.veyq/manifest.json`; version 1's `manifest.json` in a ZIP only), held to its cap while read, parsed
     * ([Manifest.parse]), and every payload it names present at the size it says. Throws [ImportRefused].
     */
    @Throws(Exception::class)
    suspend fun open(input: ImportInput): Opened {
        val source = when (input) {
            is ImportInput.Zip -> try {
                ImportSource.zip(ZipReader.open(input.file.size, input.file::read))
            } catch (e: ZipReader.ZipError) {
                if (e.code == ZipReader.ZipError.Code.NOT_ZIP) throw ImportRefused(ImportRefused.Refusal.NOT_AN_EXPORT, "not a ZIP archive")
                throw refusalOf(e)
            }
            is ImportInput.Folder -> ImportSource.folder(input.files)
        }
        // version 2 keeps it beside the tree, version 1 at the root (a ZIP only: version 1 was never unpacked by veyq)
        val at = when {
            source.has(Manifest.PATH) -> Manifest.PATH
            source.isZip && source.has(Manifest.PATH_V1) -> Manifest.PATH_V1
            else -> throw ImportRefused(ImportRefused.Refusal.NOT_AN_EXPORT, "no manifest")
        }
        if ((source.size(at) ?: 0) > Manifest.MAX_BYTES) throw ImportRefused(ImportRefused.Refusal.TOO_LARGE, "the manifest is too large")
        val bytes = try {
            val parts = ArrayList<ByteArray>()
            var length = 0L
            source.stream(at) { chunk ->
                length += chunk.size
                if (length > Manifest.MAX_BYTES) throw ImportRefused(ImportRefused.Refusal.TOO_LARGE, "the manifest is too large")
                parts += chunk
            }
            val out = ByteArray(length.toInt())
            var o = 0
            for (p in parts) {
                p.copyInto(out, o)
                o += p.size
            }
            out
        } catch (e: ZipReader.ZipError) {
            throw refusalOf(e)
        }
        val text = bytes.decodeToString()
        if (!text.encodeToByteArray().contentEquals(bytes)) throw ImportRefused(ImportRefused.Refusal.INVALID_MANIFEST, "the manifest is not UTF-8")
        val json = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw ImportRefused(ImportRefused.Refusal.INVALID_MANIFEST, "the manifest is not JSON")
        }
        val manifest = Manifest.parse(json)
        if ((at == Manifest.PATH) != (manifest.version >= 2)) {
            throw ImportRefused(ImportRefused.Refusal.INVALID_MANIFEST, "a version ${manifest.version} manifest at $at")
        }
        // every file the manifest names is there, at the size it says - before the dry run
        fun walk(n: Manifest.Node) {
            for (p in n.payloads) {
                val size = source.size(p.path) ?: throw ImportRefused(ImportRefused.Refusal.PAYLOAD_MISSING, "the download lacks ${p.path}", p.path)
                if (size != p.size) throw ImportRefused(ImportRefused.Refusal.SIZE_MISMATCH, "${p.path} is not the size the manifest says", p.path)
            }
            n.children.forEach(::walk)
        }
        walk(manifest.root)
        return Opened(source, manifest)
    }

    /** A payload whose bytes were read once and found to be what the manifest says: its digest, and its bytes by offset. */
    class Checked(val ref: Manifest.Payload, val sha256: ByteArray, private val source: ImportSource) {
        /** Up to [length] bytes from [from] (fewer only at its end), as an upload reads a picked file. */
        suspend fun read(from: Long, length: Int): ByteArray = source.read(ref.path, from, length)
    }

    /**
     * The payloads of a download, each checked before a hook takes it (decision 10; the web's `archivePayload`): its path
     * safe, its size the manifest's, and its bytes read once — the SHA-256 the manifest names compared, a ZIP entry's CRC-32
     * checked by the reader at the end ([ZipReader.stream]). One payload ahead (the web's inbox #557): once a payload's own
     * check is done, the check of the payload after it in the walk's order starts on [scope], so it runs while the hook
     * uploads this one. Only ever one ahead; a refusal found ahead is thrown when — and only if — a hook asks for that payload,
     * after that request's own path and size checks, so what an import refuses and in which order is unchanged.
     */
    class Payloads(private val opened: Opened, private val scope: CoroutineScope? = null) {
        private val source = opened.source
        private val order: List<Manifest.Payload> = run {
            val out = ArrayList<Manifest.Payload>()
            fun walk(n: Manifest.Node) {
                out += n.payloads
                n.children.forEach(::walk)
            }
            walk(opened.manifest.root)
            out
        }
        private val ahead = HashMap<String, Deferred<Checked>>()

        private fun refusal(ref: Manifest.Payload): ImportRefused? {
            val safe = if (opened.manifest.version == 1) Manifest.isSafePayloadPathV1(ref.path) else ArchiveName.isPath(ref.path)
            if (!safe) return ImportRefused(ImportRefused.Refusal.UNSAFE_PATH, "unsafe payload path", ref.path)
            val size = source.size(ref.path) ?: return ImportRefused(ImportRefused.Refusal.PAYLOAD_MISSING, "the download lacks ${ref.path}", ref.path)
            if (size != ref.size) return ImportRefused(ImportRefused.Refusal.SIZE_MISMATCH, "${ref.path} is not the size the manifest says", ref.path)
            return null
        }

        /** One pass over [ref]'s bytes: its SHA-256, and a ZIP entry's CRC-32 checked by the reader at the end. */
        private suspend fun check(ref: Manifest.Payload): Checked {
            val hasher = Sha256()
            try {
                source.stream(ref.path) { hasher.update(it) }
            } catch (e: ZipReader.ZipError) {
                throw refusalOf(e, ref.path)
            }
            val digest = hasher.digest()
            if (ref.sha256 != null && hex(digest) != ref.sha256) {
                throw ImportRefused(ImportRefused.Refusal.CHECKSUM, "${ref.path} does not match its checksum", ref.path)
            }
            return Checked(ref, digest, source)
        }

        /** The check of the payload after [ref] in the walk's order, started once [ref]'s own is done; a sound one only. */
        private fun checkNext(ref: Manifest.Payload) {
            val s = scope ?: return
            val next = order.getOrNull(order.indexOfFirst { it.path == ref.path } + 1) ?: return
            if (next.path in ahead || refusal(next) != null) return
            ahead[next.path] = s.async(start = CoroutineStart.DEFAULT) { check(next) }
        }

        /** [ref] checked, for a hook to take: throws [ImportRefused] where it is not what the manifest says. */
        suspend fun take(ref: Manifest.Payload): Checked {
            refusal(ref)?.let { throw it }
            val pending = ahead.remove(ref.path)
            val checked = pending?.await() ?: check(ref)
            checkNext(ref)
            return checked
        }

        /** Stops a check under way ahead (a cancel, a failure): nobody will ask for it. */
        fun drop() {
            ahead.values.forEach { it.cancel() }
            ahead.clear()
        }
    }


    /** Why the dry run leaves a node (and its subtree) out: the web's `SkipReason`s an import meets. */
    const val UNKNOWN_APPLICATION = "unknown_application"
    const val NEWER_CONTENT = "newer_content"

    /** A subtree the import leaves out: its top node, its application, why, and how many nodes it holds. */
    data class Skipped(val name: String, val kind: String, val application: String, val reason: String, val nodes: Int)

    /** Per application and reason, how many nodes the import leaves out (what the dialog lists). */
    data class Group(val application: String, val reason: String, val nodes: Int)

    /** What an import would create ([nodes]), upload ([bytes]) and leave out ([skipped], in tree order). */
    class Plan(val nodes: Int, val bytes: Long, val skipped: List<Skipped>) {
        val groups: List<Group>
            get() = skipped.groupBy { it.application to it.reason }.map { (k, v) -> Group(k.first, k.second, v.sumOf { it.nodes }) }
    }

    /** How the build's kinds import (the composition root's: `AppKinds.imports`); null for a kind it has no import of. */
    fun interface Kinds {
        fun hook(kind: String): NodeImport.Hook?
    }

    /** Why this build cannot import [n] (with its subtree), or null where it can (the web's `skipReason`). */
    private fun leftOut(n: Manifest.Node, manifest: Manifest, kinds: Kinds): Pair<String, String>? {
        if (n.kind == NodeRef.FOLDER) return null
        val application = n.contentType?.let(Manifest::applicationOf) ?: n.kind
        val hook = kinds.hook(n.kind)
        if (hook == null || (hook.import == null && !hook.asFolder) || hook.application != application) return application to UNKNOWN_APPLICATION
        val written = n.contentSchema ?: manifest.applications?.get(application) ?: 1
        if (written > hook.schema) return application to NEWER_CONTENT
        return null
    }

    /**
     * The dry run (the web's `planImport`): what an import would create and how many payload bytes it would upload, from the
     * manifest alone, with every subtree this build cannot import listed. Each importable node's content is checked by its
     * kind, so a content that is not its kind's shape is refused here, before anything is written. Throws [ImportRefused].
     */
    fun plan(manifest: Manifest, kinds: Kinds): Plan {
        var nodes = 0
        var bytes = 0L
        val skipped = ArrayList<Skipped>()
        fun walk(n: Manifest.Node) {
            leftOut(n, manifest, kinds)?.let { (application, reason) ->
                skipped += Skipped(n.name, n.kind, application, reason, n.count)
                return
            }
            if (n.kind != NodeRef.FOLDER) {
                val content = n.content
                val problem = if (content != null) kinds.hook(n.kind)?.validate?.invoke(content) else null
                if (problem != null) throw ImportRefused(problem.refusal, "${n.id}: ${problem.message}", n.name)
            }
            nodes++
            bytes += n.payloads.sumOf { it.size }
            n.children.forEach(::walk)
        }
        walk(manifest.root)
        return Plan(nodes, bytes, skipped)
    }

    /** Where an import writes ([Server]: the importer's session; a test's own): the folders, the rollback, the quota. */
    interface Target {
        /** The importer's session, handed to each kind's hook (null only where a test plays the kinds too). */
        val tenant: TenantSession?

        /** The kinds and names of what lies under [parentId] (null: the space's top) now: the names an import numbers past. */
        suspend fun siblings(parentId: String?): List<Pair<String, String>>

        /** A plain folder [name] under [parentId]: core's own create. */
        suspend fun folder(parentId: String?, name: String): NodeImport.Made

        /** Node [nodeId] moved to the trash with everything under it; false where it could not be. */
        suspend fun trash(nodeId: String): Boolean

        /** Whether [bytes] still fit the tenant's storage: null where the server did not say (it never blocks). */
        suspend fun fits(bytes: Long): Boolean?
    }

    /** The importer's session as the [Target]: core's folder create and trash, the tenant's storage question. */
    class Server(override val tenant: TenantSession, private val scopeId: String) : Target {
        override suspend fun siblings(parentId: String?) =
            tenant.trees()[scopeId].orEmpty().filter { it.parentId == parentId }.map { it.kind to it.name }

        override suspend fun folder(parentId: String?, name: String): NodeImport.Made =
            when (val r = FolderCreate.create(tenant, scopeId, parentId, name)) {
                is NodeCreate.Result.Done -> NodeImport.Made(r.created.nodeId)
                is NodeCreate.Result.Refused -> NodeImport.Made(null, nameTaken = r.reason == NodeCreate.Reason.NAME_TAKEN, failure = r.reason.name.lowercase())
            }

        override suspend fun trash(nodeId: String): Boolean {
            val r = NodeTrash.delete(tenant, scopeId, nodeId)
            tenant.forgetTree()
            return r == NodeTrash.Deleted.TRASHED || r == NodeTrash.Deleted.GONE
        }

        override suspend fun fits(bytes: Long): Boolean? = StorageFit.fits(tenant, bytes)
    }

    /** How far an import got: nodes created of the plan's, payload bytes uploaded of its. */
    data class Progress(val nodesDone: Int, val nodesTotal: Int, val bytesDone: Long, val bytesTotal: Long)

    /** What an import created: its top node, how many nodes, and what it left out. */
    class Result(val nodeId: String, val name: String, val nodes: Int, val skipped: List<Skipped>)

    /** The tenant has no room for the plan's bytes: nothing was written (the web's `importNoRoom`). */
    class NoRoom(val bytes: Long) : Exception("no room for $bytes bytes")

    /**
     * An import that stopped part-way: [done] of [total] nodes were created, and what it created was trashed ([rolledBack];
     * false where even that failed — the top-level node is then still in the target, [leftover]). [cause] says why.
     */
    class Failed(val done: Int, val total: Int, val rolledBack: Boolean, val leftover: String?, override val cause: Throwable) :
        Exception("import stopped after $done of $total", cause)

    /** The web's `MAX_NAME_TRIES`: the exported name, then `(2)` up to `(50)`. */
    const val MAX_NAME_TRIES = 50

    /**
     * Imports the rest of [opened] — everything [plan] does not leave out — under [parentId] (null: the space's top) of
     * [scopeId] through [target] and [kinds] (the web's `importSubtree`/`replaySubtrees`). [checkRoom]: the tenant is asked
     * first whether the plan's bytes fit, and a no writes nothing ([NoRoom]). Each node is created top-down under the name
     * it was exported with, made free among its siblings (a name taken locally or by the server's own answer gets `Name (2)`
     * … `Name (50)`, folded as its kind folds names, [tokenInput]); each payload is checked one ahead ([Payloads]) before
     * its hook takes it. Throws [ImportRefused] (`nothing_to_import`, nothing written), [NoRoom], or [Failed] part-way — after
     * trashing what it made; a cancel trashes it too and rethrows.
     */
    @Throws(Exception::class)
    suspend fun run(
        opened: Opened, scopeId: String, parentId: String?, target: Target, kinds: Kinds,
        tokenInput: (kind: String, name: String) -> String?, checkRoom: Boolean = true, progress: (Progress) -> Unit = {},
    ): Result = supervisorScope {
        val manifest = opened.manifest
        val plan = plan(manifest, kinds)
        if (plan.nodes == 0) throw ImportRefused(ImportRefused.Refusal.NOTHING_TO_IMPORT, "this build can import nothing in this download")
        if (checkRoom && target.fits(plan.bytes) == false) throw NoRoom(plan.bytes)
        val payloads = Payloads(opened, this)
        val taken = HashMap<String?, MutableSet<String>>()
        suspend fun takenIn(at: String?, fresh: Boolean): MutableSet<String> = taken.getOrPut(at) {
            if (fresh) HashSet() else target.siblings(at).mapNotNull { (kind, name) -> tokenInput(kind, name) }.toHashSet()
        }
        var nodesDone = 0
        var bytesDone = 0L
        fun report() = progress(Progress(nodesDone, plan.nodes, bytesDone, plan.bytes))
        val cache = HashMap<String, Any>()
        var top: String? = null
        var topName: String? = null
        val made = HashSet<String>() // the parents this run created: their siblings are only what it puts there

        fun createdKind(n: Manifest.Node): String =
            if (n.kind != NodeRef.FOLDER && kinds.hook(n.kind)?.asFolder == true) NodeRef.FOLDER else n.kind

        suspend fun create(n: Manifest.Node, at: String?): Pair<String, String> {
            val used = takenIn(at, at != null && at in made)
            val kind = createdKind(n)
            val nodeBytes = n.payloads.sumOf { it.size }
            var last: String? = null
            for (i in 1..MAX_NAME_TRIES) {
                val name = if (i == 1) n.name else "${n.name} ($i)"
                val slug = tokenInput(kind, name) ?: throw NodeImport.Failed("unknown_kind")
                if (slug in used) continue
                val before = bytesDone
                val r = if (kind == NodeRef.FOLDER) {
                    target.folder(at, name)
                } else {
                    val hook = kinds.hook(n.kind)?.import ?: throw NodeImport.Failed("no_import")
                    val ctx = NodeImport.Context(target.tenant, scopeId, at, name, n, payloads, { f ->
                        bytesDone = before + (f.coerceIn(0.0, 1.0) * nodeBytes).toLong()
                        report()
                    }, cache)
                    hook(ctx)
                }
                if (r.nameTaken) {
                    // the server's own answer to a name it holds that the local tree did not know: the next suffix
                    used += slug
                    last = r.failure
                    continue
                }
                val id = r.nodeId ?: throw NodeImport.Failed(r.failure ?: "not_created")
                bytesDone = before + nodeBytes
                used += slug
                return id to name
            }
            throw NodeImport.Failed(last ?: "no_free_name")
        }

        suspend fun walk(n: Manifest.Node, at: String?) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val (id, name) = create(n, at)
            if (top == null) {
                top = id
                topName = name
            }
            made += id
            nodesDone++
            report()
            for (c in n.children) {
                if (leftOut(c, manifest, kinds) != null) continue // listed in the plan, named in the result
                walk(c, id)
            }
        }

        try {
            walk(manifest.root, parentId)
            Result(top!!, topName!!, nodesDone, plan.skipped)
        } catch (e: Throwable) {
            payloads.drop()
            val made = top
            val rolledBack = made == null || withContext(NonCancellable) { runCatching { target.trash(made) }.getOrDefault(false) }
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw Failed(nodesDone, plan.nodes, rolledBack, if (rolledBack) null else made, e)
        }
    }

    internal fun hex(b: ByteArray): String = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
