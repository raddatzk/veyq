package me.raddatz.veyq.mobile.export

import me.raddatz.veyq.mobile.PlatformApi
import me.raddatz.veyq.mobile.json.KotlinxJsonAccess
import kotlin.native.HiddenFromObjC
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The manifest of a download (`contracts/export/veyq-subtree.schema.json`) as the apps read it: checked by [ManifestRules]
 * (every client's check, the web's too) over kotlinx's tree, then read into [Node]s. A node's `content` is its kind's own:
 * core reads in it only the payload references ([payloadsOf]) and its `type` and `schema`; each kind checks the rest
 * (`NodeImport.Hook.validate`).
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
        val payloads: List<Payload> get() = listOfNotNull(cover) + content?.let { c -> payloadsOf(c).map(::payloadOf) }.orEmpty()

        /** This node and everything under it. */
        val count: Int get() = 1 + children.sumOf { it.count }
    }

    companion object {
        const val FORMAT = ManifestRules.FORMAT
        /** The version this build writes; it reads every version up to it. */
        const val VERSION = ManifestRules.VERSION
        const val PATH = ManifestRules.PATH
        const val PATH_V1 = ManifestRules.PATH_V1
        /** The largest manifest read into memory. */
        const val MAX_BYTES = ManifestRules.MAX_BYTES
        const val MAX_DEPTH = ManifestRules.MAX_DEPTH
        const val MAX_NODES = ManifestRules.MAX_NODES

        /** The contract's `maxLength` (and `maxItems`) of every field core reads; `ManifestTest` pins each to the schema. */
        val LIMITS = mapOf(
            "appVersion" to ManifestRules.MAX_APP_VERSION, "applications" to ManifestRules.MAX_APPLICATIONS, "kind" to ManifestRules.MAX_KIND,
            "name" to ManifestRules.MAX_NAME, "path" to ManifestRules.MAX_PATH, "contentType" to ManifestRules.MAX_CONTENT_TYPE,
            "mediaType" to ManifestRules.MAX_MEDIA_TYPE,
        )

        /** A JSON number as JavaScript reads it (`typeof v === 'number'`) and finite; null for anything else. */
        @PlatformApi
        @HiddenFromObjC
        fun number(v: JsonElement?): Double? = KotlinxJsonAccess.number(v)?.takeIf { it.isFinite() }

        private fun string(v: JsonElement?): String? = KotlinxJsonAccess.string(v)

        /** Whether [path] is a version 1 payload path an importer may open: `data/<id>/<name>`, no `.`/`..`, no control characters. */
        fun isSafePayloadPathV1(path: String): Boolean = ManifestRules.isSafePayloadPathV1(path)

        /** The application a content type belongs to: the part before the dot. */
        fun applicationOf(contentType: String): String = ManifestRules.applicationOf(contentType)

        /** Every payload reference inside a value, in walk order ([ManifestRules.payloadsOf]). */
        fun payloadsOf(value: JsonElement): List<JsonObject> = ManifestRules.payloadsOf(KotlinxJsonAccess, value).map { it as JsonObject }

        /** A reference already checked by [parse], read as a [Payload]. */
        fun payloadOf(o: JsonObject): Payload =
            Payload(string(o["path"])!!, number(o["size"])!!.toLong(), string(o["mediaType"]).orEmpty(), string(o["sha256"]), number(o["modifiedAt"])?.toLong())

        /**
         * Checks a parsed manifest of any version this build reads ([ManifestRules.check]) and reads it. Throws
         * [ImportRefused]: `not_an_export` (not our format), `newer_version`, `invalid_manifest`, `too_large`, `unsafe_path`.
         */
        fun parse(json: JsonElement): Manifest {
            val v = ManifestRules.check(KotlinxJsonAccess, json)
            val o = json as JsonObject
            val applications = if (v >= 2) {
                (o["applications"] as JsonArray).associate { a ->
                    val e = a as JsonObject
                    string(e["id"])!! to number(e["contentSchema"])!!.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
                }
            } else null
            fun read(n: JsonElement): Node {
                val node = n as JsonObject
                val cover = node["cover"]
                return Node(
                    string(node["id"])!!, string(node["kind"])!!, string(node["name"])!!, if (v >= 2) string(node["path"]) else null,
                    if (cover == null || cover is JsonNull) null else payloadOf(cover as JsonObject),
                    node["content"] as JsonObject?, (node["children"] as JsonArray?)?.map(::read).orEmpty(),
                )
            }
            return Manifest(v, applications, read(o["root"]!!))
        }
    }
}
