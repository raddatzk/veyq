package me.raddatz.veyq.mobile.export

import me.raddatz.veyq.mobile.json.JsonAccess

/**
 * Why an archive cannot be imported (ADR 0082 decision 10): stable for callers, [Refusal.wire] the code the web, the words
 * and the bridge carry; [detail] (a path, a node's name) may be shown beside the explanation.
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
 * The manifest's check (`contracts/export/veyq-subtree.schema.json`, ADR 0082 decision 10), every client's, the web's
 * through `@veyq/core-js/export`: versions 1 and 2 read, a newer one refused; the shape and every field's length against the
 * contract, ids unique, depth and node count bounded, every path made of safe names ([ArchiveName.isPath]) and none reaching
 * into `.veyq/`; unknown fields ignored, as the schema says. It reads the parsed document where it lies ([JsonAccess]), so the
 * web checks its own `JSON.parse` result and the apps their kotlinx tree ([Manifest.parse]). A node's `content` is its
 * kind's own: the check reads in it only the payload references ([payloadsOf]) and its `type` and `schema`. Held by
 * `contracts/platform/export/v1-vectors.json` (`manifest`, `payloads`).
 */
object ManifestRules {
    const val FORMAT = "veyq-subtree"
    /** The version this build writes; it reads every version up to it. */
    const val VERSION = 2
    /** Where version 2 keeps the manifest: beside the tree, in a directory no node may take. */
    const val PATH = ".veyq/manifest.json"
    /** Where version 1 kept it. */
    const val PATH_V1 = "manifest.json"
    /** The largest manifest read into memory. A subtree of 100,000 nodes is a few tens of MB. */
    const val MAX_BYTES = 128L * 1024 * 1024
    const val MAX_DEPTH = 100
    const val MAX_NODES = 200_000

    // The contract's `maxLength` (and `maxItems`) of every field core reads; `ManifestTest` and the web's
    // `importHardening.test.ts` pin each to the schema.
    const val MAX_APP_VERSION = 64
    const val MAX_APPLICATIONS = 64
    const val MAX_KIND = 64
    const val MAX_NAME = 1024
    const val MAX_PATH = 65535
    const val MAX_CONTENT_TYPE = 64
    const val MAX_MEDIA_TYPE = 255

    private const val MAX_SAFE = 9007199254740991.0

    private fun fail(refusal: ImportRefused.Refusal, message: String, detail: String? = null): Nothing =
        throw ImportRefused(refusal, message, detail)

    private fun bad(message: String, detail: String? = null): Nothing = fail(ImportRefused.Refusal.INVALID_MANIFEST, message, detail)

    private fun isSafeInteger(d: Double) = d == kotlin.math.floor(d) && kotlin.math.abs(d) <= MAX_SAFE

    /** `isCount`: a safe integer of at least 1. */
    private fun isCount(d: Double?) = d != null && isSafeInteger(d) && d >= 1

    private fun idChar(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'
    private fun lower(c: Char) = c in 'a'..'z'
    private fun lowerOrDigitOrDash(c: Char) = lower(c) || c in '0'..'9' || c == '-'

    /** `^[A-Za-z0-9_-]+$` */
    private fun isId(s: String) = s.isNotEmpty() && s.all(::idChar)

    /** `^[a-z][a-z0-9-]{0,63}$` */
    private fun isApplicationId(s: String) = s.length in 1..64 && lower(s[0]) && (1 until s.length).all { lowerOrDigitOrDash(s[it]) }

    /** `^[a-z][a-z0-9-]*\.[a-z][a-z0-9-]*$` */
    private fun isContentType(s: String): Boolean {
        val dot = s.indexOf('.')
        if (dot < 1 || dot == s.length - 1 || !lower(s[0]) || !lower(s[dot + 1])) return false
        return (1 until dot).all { lowerOrDigitOrDash(s[it]) } && (dot + 2 until s.length).all { lowerOrDigitOrDash(s[it]) }
    }

    /** `^[0-9a-f]{64}$` */
    private fun isSha256(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** A bounded string field: not a string is the shape's fault, too long a cap. */
    private fun text(j: JsonAccess, v: Any?, max: Int, what: String, detail: String? = null, min: Int = 0): String {
        val s = j.string(v)
        if (s == null || s.length < min) bad("$what: not a string", detail)
        if (s.length > max) fail(ImportRefused.Refusal.TOO_LARGE, "$what: longer than $max", detail)
        return s
    }

    /** Field [key] of object [o], or null where it has none (JSON `null` is a value, [JsonAccess.isNull]). */
    private fun opt(j: JsonAccess, o: Any?, key: String): Any? = if (j.has(o, key)) j.field(o, key) else null

    /** Whether [path] is a version 1 payload path an importer may open: `data/<id>/<name>`, no `.`/`..`, no control characters. */
    fun isSafePayloadPathV1(path: String): Boolean {
        if (!path.startsWith("data/")) return false
        val slash = path.indexOf('/', 5)
        if (slash < 6 || !(5 until slash).all { idChar(path[it]) }) return false
        val name = path.substring(slash + 1)
        if (name.isEmpty() || name.any { it == '/' || it == '\\' }) return false
        return name != "." && name != ".." && name.none { it.code < 0x20 }
    }

    /** The application a content type belongs to: the part before the dot. */
    fun applicationOf(contentType: String): String = contentType.substringBefore('.')

    /** Whether a version 2 payload [path] lies where its node at [nodePath] keeps one: its own path or a file in it. */
    private fun payloadOfNode(path: String, nodePath: String): Boolean =
        path == nodePath || (path.startsWith("$nodePath/") && path.indexOf('/', nodePath.length + 1) < 0)

    private fun checkPayload(j: JsonAccess, v: Any?, version: Int, nodePath: String?, where: String) {
        if (!j.isObject(v)) bad("$where: not a payload", where)
        val path = text(j, opt(j, v, "path"), MAX_PATH, "$where path", where, 1)
        val safe = if (version == 1) isSafePayloadPathV1(path) else ArchiveName.isPath(path) && payloadOfNode(path, nodePath!!)
        if (!safe) fail(ImportRefused.Refusal.UNSAFE_PATH, "$where: unsafe payload path", path)
        val size = j.number(opt(j, v, "size"))
        if (size == null || !isSafeInteger(size) || size < 0) bad("$where: bad size", path)
        text(j, opt(j, v, "mediaType"), MAX_MEDIA_TYPE, "$where mediaType", path)
        if (j.has(v, "sha256") && j.string(j.field(v, "sha256"))?.let(::isSha256) != true) bad("$where: bad sha256", path)
        if (j.has(v, "modifiedAt") && j.number(j.field(v, "modifiedAt"))?.isFinite() != true) bad("$where: bad modifiedAt", path)
    }

    /** The applications a version 2 manifest names, each once. */
    private fun checkApplications(j: JsonAccess, v: Any?): Set<String> {
        if (!j.isArray(v)) bad("applications is not a list")
        val n = j.size(v)
        if (n > MAX_APPLICATIONS) fail(ImportRefused.Refusal.TOO_LARGE, "too many applications")
        val out = HashSet<String>()
        for (i in 0 until n) {
            val a = j.item(v, i)
            val id = if (j.isObject(a)) j.string(opt(j, a, "id")) else null
            if (id == null || !isApplicationId(id) || !isCount(j.number(opt(j, a, "contentSchema")))) bad("bad application entry")
            if (!out.add(id)) bad("application listed twice: $id")
        }
        return out
    }

    /**
     * Checks a parsed manifest of any version this build reads; its version. Throws [ImportRefused]: `not_an_export` (not
     * our format), `newer_version`, `invalid_manifest`, `too_large`, `unsafe_path`.
     */
    fun check(j: JsonAccess, json: Any?): Int {
        if (!j.isObject(json) || j.string(opt(j, json, "format")) != FORMAT) fail(ImportRefused.Refusal.NOT_AN_EXPORT, "not a veyq download")
        val v = j.number(opt(j, json, "version"))
        if (!isCount(v)) bad("bad version")
        if (v!! > VERSION) fail(ImportRefused.Refusal.NEWER_VERSION, "version ${v.toLong()} is newer than this build reads")
        val version = v.toInt()
        text(j, opt(j, json, "exportedAt"), MAX_APP_VERSION, "exportedAt", null, 1)
        text(j, opt(j, json, "appVersion"), MAX_APP_VERSION, "appVersion")
        val applications = if (version >= 2) checkApplications(j, opt(j, json, "applications")) else null
        val ids = HashSet<String>()
        var count = 0
        fun walk(n: Any?, depth: Int, parentPath: String?) {
            if (depth > MAX_DEPTH) fail(ImportRefused.Refusal.TOO_LARGE, "tree too deep")
            if (++count > MAX_NODES) fail(ImportRefused.Refusal.TOO_LARGE, "too many nodes")
            if (!j.isObject(n)) bad("node is not an object")
            val id = j.string(opt(j, n, "id"))
            if (id == null || !isId(id) || id.length > MAX_KIND) bad("bad node id")
            if (!ids.add(id)) bad("node id used twice: $id")
            val name = text(j, opt(j, n, "name"), MAX_NAME, "$id name", null, 1)
            text(j, opt(j, n, "kind"), MAX_KIND, "$id kind", name, 1)
            var path: String? = null
            if (version >= 2) {
                val p = text(j, opt(j, n, "path"), MAX_PATH, "$id path", name, 1)
                // the exported node is one safe name; every other node is one segment below its parent
                val fits = if (parentPath == null) '/' !in p else p.startsWith("$parentPath/") && p.indexOf('/', parentPath.length + 1) < 0
                if (!ArchiveName.isPath(p) || !fits) fail(ImportRefused.Refusal.UNSAFE_PATH, "$id: unsafe node path", p)
                path = p
            }
            val cover = opt(j, n, "cover")
            if (cover != null && !j.isNull(cover)) checkPayload(j, cover, version, path, "$name (cover)")
            if (j.has(n, "content")) {
                val content = j.field(n, "content")
                if (!j.isObject(content)) bad("$id: bad content", name)
                val type = text(j, opt(j, content, "type"), MAX_CONTENT_TYPE, "$id content type", name, 1)
                if (!isContentType(type)) bad("$id: bad content type", name)
                if (j.has(content, "schema") && !isCount(j.number(j.field(content, "schema")))) bad("$id: bad content schema", name)
                if (applications != null && applicationOf(type) !in applications) bad("$id: application ${applicationOf(type)} is not listed", name)
                for (p in payloadsOf(j, content)) checkPayload(j, p, version, path, name)
            }
            if (j.has(n, "children")) {
                val children = j.field(n, "children")
                if (!j.isArray(children)) bad("$id: children is not a list", name)
                for (i in 0 until j.size(children)) walk(j.item(children, i), depth + 1, path)
            }
        }
        walk(opt(j, json, "root"), 0, null)
        return version
    }

    /**
     * Every payload reference inside [value]: an object with a string `path` and a numeric `size`, in walk order (an array's
     * items in order, an object's values in order), never walked into. How core finds a kind's bytes without knowing the
     * kind — and why every one is judged by where it points. The answers are [value]'s own objects.
     */
    fun payloadsOf(j: JsonAccess, value: Any?, out: MutableList<Any?> = ArrayList()): MutableList<Any?> {
        if (j.isArray(value)) {
            for (i in 0 until j.size(value)) payloadsOf(j, j.item(value, i), out)
        } else if (j.isObject(value)) {
            if (j.string(opt(j, value, "path")) != null && j.number(opt(j, value, "size")) != null) out += value
            else for (v in j.values(value)) payloadsOf(j, v, out)
        }
        return out
    }
}
