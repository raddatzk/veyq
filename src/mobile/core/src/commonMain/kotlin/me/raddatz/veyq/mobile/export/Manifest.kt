package me.raddatz.veyq.mobile.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Why an archive cannot be imported (the web's `ExportErrorCode`, ADR 0082 decision 10): stable for callers, [wire] the
 * web's code, which the words and the bridge carry; [detail] (a path, a node's name) may be shown beside the explanation.
 */
class ImportRefused(val refusal: Refusal, message: String, val detail: String? = null) : Exception(message) {
    enum class Refusal(val wire: String) {
        /** No veyq manifest where one belongs (an ordinary ZIP or folder). */
        NOT_AN_EXPORT("not_an_export"),
        /** A manifest version this build does not read. */
        NEWER_VERSION("newer_version"),
        /** The manifest is not the contract's shape. */
        INVALID_MANIFEST("invalid_manifest"),
        /** Over a cap: node count, depth, a name's or a field's length, the manifest's size. */
        TOO_LARGE("too_large"),
        /** A path that is not made of safe names, leaves its node or reaches into `.veyq/`. */
        UNSAFE_PATH("unsafe_path"),
        /** The ZIP's own structure ([ZipReader]'s refusals but a checksum). */
        DAMAGED_ARCHIVE("damaged_archive"),
        /** A file the manifest names is not in the download. */
        PAYLOAD_MISSING("payload_missing"),
        /** A file is not the size the manifest says. */
        SIZE_MISMATCH("size_mismatch"),
        /** A file's bytes do not match its SHA-256 (or the ZIP's CRC-32). */
        CHECKSUM("checksum"),
        /** Nothing in it can be imported by this build. */
        NOTHING_TO_IMPORT("nothing_to_import"),
    }
}

/**
 * The manifest of a download (`contracts/export/veyq-subtree.schema.json`), a port of the web's `core/export/manifest.ts`
 * check for check: versions 1 and 2 read, a newer one refused; the shape and every field's length against the contract,
 * ids unique, depth and node count bounded, every path made of safe names ([ArchiveName.isPath]) and none reaching into
 * `.veyq/`; unknown fields ignored, as the schema says. A node's `content` is its kind's own: core reads in it only the
 * payload references ([payloadsOf]) and its `type` and `schema`; each kind checks the rest (`NodeImport.Hook.validate`).
 */
class Manifest private constructor(
    val version: Int,
    /** Version 2: each application the tree carries with its content-schema version; null in version 1. */
    val applications: Map<String, Int>?,
    val root: Node,
) {
    /** One payload reference: an archive path, its size and type, and optionally its SHA-256 and modified time. */
    class Payload(val path: String, val size: Long, val mediaType: String, val sha256: String?, val modifiedAt: Long?)

    class Node(
        val id: String, val kind: String, val name: String,
        /** Version 2: where the node sits in the archive. */
        val path: String?,
        val cover: Payload?,
        /** The kind's own content; null for a folder. */
        val content: JsonObject?,
        val children: List<Node>,
    ) {
        /** The content's type (`<application>.<kind>`), or null without content. */
        val contentType: String? get() = (content?.get("type") as? JsonPrimitive)?.content

        /** The content's own schema version, where it names one. */
        val contentSchema: Int? get() = (content?.get("schema") as? JsonPrimitive)?.let { number(it) }?.toInt()

        /** All payloads of this node (its cover and everything in its content), not its children's. */
        val payloads: List<Payload> get() = listOfNotNull(cover) + content?.let { payloadsOf(it) }.orEmpty()

        /** This node and everything under it. */
        val count: Int get() = 1 + children.sumOf { it.count }
    }

    companion object {
        const val FORMAT = NodeExport.FORMAT
        /** The version this build writes; it reads every version up to it. */
        const val VERSION = NodeExport.VERSION
        const val PATH = NodeExport.MANIFEST_PATH
        const val PATH_V1 = "manifest.json"
        /** The largest manifest read into memory. */
        const val MAX_BYTES = 128L * 1024 * 1024
        const val MAX_DEPTH = 100
        const val MAX_NODES = 200_000

        /** The contract's `maxLength` (and `maxItems`) of every field core reads; `ManifestTest` pins each to the schema. */
        val LIMITS = mapOf(
            "appVersion" to 64, "applications" to 64, "kind" to 64, "name" to 1024, "path" to 65535, "contentType" to 64, "mediaType" to 255,
        )
        private fun limit(name: String) = LIMITS.getValue(name)

        private val ID = Regex("^[A-Za-z0-9_-]+$")
        private val APPLICATION_ID = Regex("^[a-z][a-z0-9-]{0,63}$")
        private val CONTENT_TYPE = Regex("^[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*$")
        private val PATH_V1_RE = Regex("^data/[A-Za-z0-9_-]+/[^/\\\\]+$")
        private val SHA = Regex("^[0-9a-f]{64}$")
        private const val MAX_SAFE = 9007199254740991.0

        private fun fail(refusal: ImportRefused.Refusal, message: String, detail: String? = null): Nothing =
            throw ImportRefused(refusal, message, detail)

        private fun bad(message: String, detail: String? = null): Nothing = fail(ImportRefused.Refusal.INVALID_MANIFEST, message, detail)

        /** A JSON number as JavaScript reads it (`typeof v === 'number'`); null for anything else. */
        internal fun number(v: JsonElement?): Double? {
            val p = v as? JsonPrimitive ?: return null
            if (p.isString || p is JsonNull) return null
            if (p.content == "true" || p.content == "false") return null
            return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        private fun safeInteger(v: JsonElement?): Long? = number(v)?.takeIf { it == kotlin.math.floor(it) && kotlin.math.abs(it) <= MAX_SAFE }?.toLong()

        /** `isCount`: a safe integer of at least 1. */
        private fun count(v: JsonElement?): Long? = safeInteger(v)?.takeIf { it >= 1 }

        private fun string(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** A bounded string field: not a string is the shape's fault, too long a cap. */
        private fun text(v: JsonElement?, max: Int, what: String, detail: String? = null, min: Int = 0): String {
            val s = string(v)
            if (s == null || s.length < min) bad("$what: not a string", detail)
            if (s.length > max) fail(ImportRefused.Refusal.TOO_LARGE, "$what: longer than $max", detail)
            return s
        }

        /** Whether [path] is a version 1 payload path an importer may open: `data/<id>/<name>`, no `.`/`..`, no control characters. */
        fun isSafePayloadPathV1(path: String): Boolean {
            if (!PATH_V1_RE.matches(path)) return false
            val name = path.substringAfterLast('/')
            return name != "." && name != ".." && name.none { it.code < 0x20 }
        }

        /** The application a content type belongs to: the part before the dot. */
        fun applicationOf(contentType: String): String = contentType.substringBefore('.')

        /** Whether a version 2 payload [path] lies where its node at [nodePath] keeps one: its own path or a file in it. */
        private fun payloadOfNode(path: String, nodePath: String): Boolean =
            path == nodePath || (path.startsWith("$nodePath/") && '/' !in path.substring(nodePath.length + 1))

        private fun checkPayload(v: JsonElement, version: Int, nodePath: String?, where: String): Payload {
            val o = v as? JsonObject ?: bad("$where: not a payload", where)
            val path = text(o["path"], limit("path"), "$where path", where, 1)
            val safe = if (version == 1) isSafePayloadPathV1(path) else ArchiveName.isPath(path) && payloadOfNode(path, nodePath!!)
            if (!safe) fail(ImportRefused.Refusal.UNSAFE_PATH, "$where: unsafe payload path", path)
            val size = safeInteger(o["size"])?.takeIf { it >= 0 } ?: bad("$where: bad size", path)
            val mediaType = text(o["mediaType"], limit("mediaType"), "$where mediaType", path)
            val sha = o["sha256"]
            if (sha != null && !(string(sha)?.let(SHA::matches) ?: false)) bad("$where: bad sha256", path)
            val modified = o["modifiedAt"]
            if (modified != null && number(modified) == null) bad("$where: bad modifiedAt", path)
            return Payload(path, size, mediaType, string(sha), number(modified)?.toLong())
        }

        private fun checkApplications(v: JsonElement?): Map<String, Int> {
            val list = v as? JsonArray ?: bad("applications is not a list")
            if (list.size > limit("applications")) fail(ImportRefused.Refusal.TOO_LARGE, "too many applications")
            val out = LinkedHashMap<String, Int>()
            for (a in list) {
                val o = a as? JsonObject ?: bad("bad application entry")
                val id = string(o["id"])?.takeIf(APPLICATION_ID::matches) ?: bad("bad application entry")
                val schema = count(o["contentSchema"]) ?: bad("bad application entry")
                if (id in out) bad("application listed twice: $id")
                out[id] = schema.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            return out
        }

        /**
         * Every payload reference inside a value: an object with a string `path` and a numeric `size`. How core finds a
         * kind's bytes without knowing the kind — and why every one is judged by where it points.
         */
        fun payloadsOf(value: JsonElement): List<JsonObject> {
            val out = ArrayList<JsonObject>()
            fun walk(v: JsonElement) {
                when (v) {
                    is JsonArray -> v.forEach(::walk)
                    is JsonObject -> if (string(v["path"]) != null && number(v["size"]) != null) out += v else v.values.forEach(::walk)
                    else -> {}
                }
            }
            walk(value)
            return out
        }

        private fun payloadsOf(content: JsonObject): List<Payload> = payloadsOf(content as JsonElement).map(::payloadOf)

        /** A reference already checked by [parse], read as a [Payload]. */
        fun payloadOf(o: JsonObject): Payload =
            Payload(string(o["path"])!!, number(o["size"])!!.toLong(), string(o["mediaType"]).orEmpty(), string(o["sha256"]), number(o["modifiedAt"])?.toLong())

        /**
         * Validates a parsed manifest of any version this build reads. Throws [ImportRefused]: `not_an_export` (not our
         * format), `newer_version`, `invalid_manifest`, `too_large`, `unsafe_path`.
         */
        fun parse(json: JsonElement): Manifest {
            val o = json as? JsonObject
            if (o == null || string(o["format"]) != FORMAT) fail(ImportRefused.Refusal.NOT_AN_EXPORT, "not a veyq download")
            val version = count(o["version"]) ?: bad("bad version")
            if (version > VERSION) fail(ImportRefused.Refusal.NEWER_VERSION, "version $version is newer than this build reads")
            val v = version.toInt()
            text(o["exportedAt"], limit("appVersion"), "exportedAt", null, 1)
            text(o["appVersion"], limit("appVersion"), "appVersion")
            val applications = if (v >= 2) checkApplications(o["applications"]) else null
            val ids = HashSet<String>()
            var nodes = 0
            fun walk(n: JsonElement, depth: Int, parentPath: String?): Node {
                if (depth > MAX_DEPTH) fail(ImportRefused.Refusal.TOO_LARGE, "tree too deep")
                if (++nodes > MAX_NODES) fail(ImportRefused.Refusal.TOO_LARGE, "too many nodes")
                val node = n as? JsonObject ?: bad("node is not an object")
                val id = string(node["id"])?.takeIf { ID.matches(it) && it.length <= limit("kind") } ?: bad("bad node id")
                if (!ids.add(id)) bad("node id used twice: $id")
                val name = text(node["name"], limit("name"), "$id name", null, 1)
                val kind = text(node["kind"], limit("kind"), "$id kind", name, 1)
                var path: String? = null
                if (v >= 2) {
                    val p = text(node["path"], limit("path"), "$id path", name, 1)
                    // the exported node is one safe name; every other node is one segment below its parent
                    val fits = if (parentPath == null) '/' !in p else p.startsWith("$parentPath/") && '/' !in p.substring(parentPath.length + 1)
                    if (!ArchiveName.isPath(p) || !fits) fail(ImportRefused.Refusal.UNSAFE_PATH, "$id: unsafe node path", p)
                    path = p
                }
                val coverJson = node["cover"]
                val cover = if (coverJson == null || coverJson is JsonNull) null else checkPayload(coverJson, v, path, "$name (cover)")
                var content: JsonObject? = null
                val c = node["content"]
                if (c != null) {
                    content = c as? JsonObject ?: bad("$id: bad content", name)
                    val type = text(content["type"], limit("contentType"), "$id content type", name, 1)
                    if (!CONTENT_TYPE.matches(type)) bad("$id: bad content type", name)
                    if (content["schema"] != null && count(content["schema"]) == null) bad("$id: bad content schema", name)
                    if (applications != null && applicationOf(type) !in applications) bad("$id: application ${applicationOf(type)} is not listed", name)
                    for (p in payloadsOf(content as JsonElement)) checkPayload(p, v, path, name)
                }
                val children = when (val ch = node["children"]) {
                    null -> emptyList()
                    is JsonArray -> ch.map { walk(it, depth + 1, path) }
                    else -> bad("$id: children is not a list", name)
                }
                return Node(id, kind, name, path, cover, content, children)
            }
            val root = walk(o["root"] ?: JsonNull, 0, null)
            return Manifest(v, applications, root)
        }
    }
}
