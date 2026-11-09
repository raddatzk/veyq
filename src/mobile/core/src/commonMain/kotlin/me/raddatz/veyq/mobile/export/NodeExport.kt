package me.raddatz.veyq.mobile.export

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A node and everything under it downloaded as a plaintext ZIP (ADR 0082, uc.platform.export-import), the web's
 * `core/export/exportSubtree.ts`, in format version 2 (`contracts/export/veyq-subtree.schema.json`): each node a directory
 * named after it under its parent's, its payloads files in it under their own safe names ([ArchiveName]), and the manifest
 * beside the tree at `.veyq/manifest.json` — the directories first, then the payloads in walk order, the manifest last,
 * once every payload's SHA-256 is known. What a node holds is its kind's to say ([Content], the web's `exportContent` hook;
 * a folder holds none); core owns the envelope and never reads the content but its payload references. A kind this build
 * cannot export is left out with what is under it and said ([Skipped]), as the web's walk does. The archive leaves the
 * end-to-end encryption by design (ADR 0082 decision 2): it is what the person saves, never sent anywhere.
 */
object NodeExport {
    const val FORMAT = "veyq-subtree"
    const val VERSION = 2
    const val MANIFEST_PATH = "${ArchiveName.MANIFEST_DIR}/manifest.json"

    /** The web's skip reasons (`export.reason.*`): a kind this build does not export, or a node that holds nothing to. */
    const val UNSUPPORTED_KIND = "unsupported_kind"
    const val NOTHING_TO_EXPORT = "nothing_to_export"

    /**
     * One file the content refers to: its name in the node's directory, its size, its type, and its bytes, [stream]ed into
     * the archive piece by piece (a Depot file of any size is never held whole); [whole] for a small one read at once.
     */
    class Payload(
        val fileName: String,
        val size: Long,
        val mediaType: String,
        val modifiedAt: Long? = null,
        val stream: suspend (emit: suspend (ByteArray) -> Unit) -> Unit,
    ) {
        companion object {
            /** A payload read whole (a receipt, a CSV: capped and small). */
            fun whole(fileName: String, size: Long, mediaType: String, modifiedAt: Long? = null, read: suspend () -> ByteArray) =
                Payload(fileName, size, mediaType, modifiedAt) { emit -> emit(read()) }
        }
    }

    /** What a kind exports of one node: its content type (`<application>.<kind>`) and schema version, its payloads, and the
     *  content itself, built from their manifest references (`{path, size, mediaType, modifiedAt?, sha256}`, payload order).
     *  [asFile]: the node IS its one payload (the web's `downloadsAsFile`, a Depot file) — written at the node's own path,
     *  no directory of its own. */
    class Content(
        val type: String, val schema: Int, val payloads: List<Payload>, val asFile: Boolean = false,
        val build: (refs: List<JsonObject>) -> JsonObject,
    ) {
        init {
            require(!asFile || payloads.size == 1) { "a node that is its payload has exactly one" }
        }
    }

    /** One node of an archive: its kind, its name, what it holds (null: nothing, a folder) and the nodes under it. */
    class Entry(val kind: String, val name: String, val content: Content?, val children: List<Entry> = emptyList())

    /** A node left out of an archive, with everything under it, and why ([UNSUPPORTED_KIND], [NOTHING_TO_EXPORT]). */
    data class Skipped(val name: String, val kind: String, val reason: String)

    /** What a walk found: the tree to write and what it left out. */
    class Walked(val root: Entry, val skipped: List<Skipped>)

    /**
     * How the build's kinds export (the composition root's, the web's registry): which kinds export ([exports]; a folder
     * always does, holding nothing) and what one node holds ([content]; null: nothing to export).
     */
    class Kinds(val exports: (String) -> Boolean, val content: suspend (TenantSession, OpenNode) -> Content?)

    class Written(val payloads: Int, val bytes: Long)

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** The file a download of node [name] is saved as (the web's `${archiveName(name)}.zip`). */
    fun fileName(name: String): String = ArchiveName.of(name, mutableSetOf()) + ".zip"

    /** The application a content type belongs to (`expenses.pot` → `expenses`). */
    fun applicationOf(type: String): String = type.substringBefore('.')

    /** An ISO-8601 instant as JavaScript's `toISOString` writes it: always milliseconds, always `Z`. */
    fun isoMs(ms: Long): String {
        val s = kotlin.time.Instant.fromEpochMilliseconds(ms - ms % 1000).toString().removeSuffix("Z")
        return "$s.${(ms % 1000).toString().padStart(3, '0')}Z"
    }

    /**
     * The tree under node [nodeId] of [scopeId] as the web's `walkSubtree` reads it: depth first, each node's children in
     * name order (numbers by value, case ignored), a kind [kinds] does not export left out with its subtree, a node whose
     * kind returns nothing left out too. Null where the node is not in the tenant's trees or holds nothing itself.
     */
    @Throws(Exception::class)
    suspend fun walk(tenant: TenantSession, scopeId: String, nodeId: String, kinds: Kinds): Walked? {
        val nodes = tenant.trees()[scopeId] ?: return null
        val root = nodes.firstOrNull { it.id == nodeId } ?: return null
        val children = nodes.groupBy { it.parentId }
        val skipped = mutableListOf<Skipped>()
        suspend fun build(n: OpenNode): Entry? {
            val folder = n.kind == NodeRef.FOLDER
            if (!folder && !kinds.exports(n.kind)) {
                skipped += Skipped(n.name, n.kind, UNSUPPORTED_KIND)
                return null
            }
            val content = if (folder) null else kinds.content(tenant, n) ?: run {
                skipped += Skipped(n.name, n.kind, NOTHING_TO_EXPORT)
                return null
            }
            val under = children[n.id].orEmpty().sortedWith { a, b -> naturalCompare(a.name, b.name) }.mapNotNull { build(it) }
            return Entry(n.kind, n.name, content, under)
        }
        return build(root)?.let { Walked(it, skipped) }
    }

    /** Names in the order a person reads them: case ignored, a run of digits by its value (`Foto 2` before `Foto 10`). */
    internal fun naturalCompare(a: String, b: String): Int {
        val x = a.lowercase()
        val y = b.lowercase()
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            if (x[i].isDigit() && y[j].isDigit()) {
                val si = i
                val sj = j
                while (i < x.length && x[i].isDigit()) i++
                while (j < y.length && y[j].isDigit()) j++
                val da = x.substring(si, i).trimStart('0')
                val db = y.substring(sj, j).trimStart('0')
                if (da.length != db.length) return da.length - db.length
                val c = da.compareTo(db)
                if (c != 0) return c
            } else {
                if (x[i] != y[j]) return x[i].compareTo(y[j])
                i++
                j++
            }
        }
        return (x.length - i).compareTo(y.length - j).takeIf { it != 0 } ?: a.compareTo(b)
    }

    /**
     * Writes the ZIP of node [name] of [kind], holding [content], with nothing under it, into [sink] — a Pot's download.
     * [sha256] is the suite's.
     */
    suspend fun write(
        name: String, kind: String, content: Content, appVersion: String, nowMs: Long, sink: (ByteArray) -> Unit,
    ): Written = write(Entry(kind, name, content), appVersion, nowMs, sink)

    /**
     * Writes the ZIP of the tree [root] into [sink]: every directory, then each payload in walk order — streamed through and
     * hashed as it passes ([Sha256]), never held whole —, then the manifest. A payload that streams another size than it
     * declared fails the archive (`ZipWriter.ZipFailure`).
     */
    suspend fun write(root: Entry, appVersion: String, nowMs: Long, sink: (ByteArray) -> Unit): Written {
        class Placed(val entry: Entry, val id: String, val path: String, val payloadPaths: List<String>, val children: List<Placed>)
        var next = 0
        fun place(e: Entry, dir: String?, taken: MutableSet<String>): Placed {
            val id = "n${++next}"
            val segment = ArchiveName.of(e.name, taken)
            val path = if (dir == null) segment else "$dir/$segment"
            val inside = mutableSetOf<String>()
            val payloadPaths = if (e.content?.asFile == true) listOf(path) else e.content?.payloads.orEmpty().map { "$path/${ArchiveName.of(it.fileName, inside)}" }
            // the children's names told apart in a set of the directory's own (the web's `names`, fresh per directory)
            val names = mutableSetOf<String>()
            return Placed(e, id, path, payloadPaths, e.children.map { place(it, path, names) })
        }
        val tree = place(root, null, mutableSetOf(ArchiveName.MANIFEST_DIR))
        fun all(p: Placed): List<Placed> = listOf(p) + p.children.flatMap { all(it) }
        val walk = all(tree)

        val zip = ZipWriter(sink, nowMs)
        for (p in walk) if (p.entry.content?.asFile != true) zip.addDirectory(p.path)
        var bytes = 0L
        var count = 0
        val refs = HashMap<String, List<JsonObject>>()
        val applications = LinkedHashMap<String, Int>()
        for (p in walk) {
            val content = p.entry.content ?: continue
            refs[p.id] = content.payloads.mapIndexed { i, payload ->
                val hasher = Sha256()
                // the writer counts what passes and refuses another size than declared
                zip.addFile(p.payloadPaths[i], payload.size) { put -> payload.stream { chunk -> hasher.update(chunk); put(chunk) } }
                val digest = hasher.digest()
                bytes += payload.size
                count++
                buildJsonObject {
                    put("path", p.payloadPaths[i]); put("size", payload.size); put("mediaType", payload.mediaType)
                    payload.modifiedAt?.let { put("modifiedAt", it) }
                    put("sha256", digest.joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') })
                }
            }
            val app = applicationOf(content.type)
            applications[app] = maxOf(applications[app] ?: 0, content.schema)
        }
        fun node(p: Placed): JsonObject = buildJsonObject {
            put("id", p.id); put("kind", p.entry.kind); put("name", p.entry.name); put("path", p.path)
            p.entry.content?.let { put("content", it.build(refs.getValue(p.id))) }
            if (p.children.isNotEmpty()) put("children", JsonArray(p.children.map { node(it) }))
        }
        val manifest = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", isoMs(nowMs))
            put("appVersion", appVersion)
            put("applications", JsonArray(applications.map { (id, schema) -> buildJsonObject { put("id", id); put("contentSchema", schema) } }))
            put("root", node(tree))
        }
        zip.addBytes(MANIFEST_PATH, pretty.encodeToString(JsonObject.serializer(), manifest).encodeToByteArray())
        zip.finish()
        return Written(count, bytes)
    }
}
