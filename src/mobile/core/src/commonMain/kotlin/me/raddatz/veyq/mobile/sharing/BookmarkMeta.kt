package me.raddatz.veyq.mobile.sharing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.OpenNode

/**
 * A bookmark's sealed meta (ADR 0120 decision 3, `contracts/platform/share-bookmark-meta.v1.schema.json`; the web's
 * `bookmarkMeta.ts` ported): the `bookmark` sub-object of core's node-meta envelope under the bookmark's own `K_node`,
 * holding the kept link and, where one was given, the share's password, padded so the sealed envelope's plaintext is a
 * multiple of [PAD] bytes — the operator sees a node of this kind and a meta of one of a few sizes, nothing of the host or
 * of a password.
 */
object BookmarkMeta {
    const val KEY = "bookmark"
    const val MAX_PASSWORD_LENGTH = 256
    const val PAD = 512

    /** What a bookmark keeps, opened. Its [toString] names the host alone. */
    class Bookmark(val link: ReceivedLink.ShareLink, val password: String?) {
        override fun toString(): String = "Bookmark(${link.host})"
    }

    /** The node-meta envelope of a new bookmark, padded: `pad` holds as many spaces as round its JSON — exactly what
     *  [NodeMetaWrite.seal] encodes — up to a multiple of [PAD] bytes. */
    fun envelope(link: ReceivedLink.ShareLink, password: String?): JsonObject {
        fun of(pad: String) = JsonObject(
            linkedMapOf(
                "v" to JsonPrimitive(1),
                KEY to JsonObject(
                    buildMap {
                        put("v", JsonPrimitive(1))
                        put("link", JsonPrimitive(link.link))
                        if (!password.isNullOrEmpty()) put("password", JsonPrimitive(password))
                        put("pad", JsonPrimitive(pad))
                    },
                ),
            ),
        )
        val length = plaintextOf(of("")).size
        return of(" ".repeat((PAD - length % PAD) % PAD))
    }

    /** What [NodeMetaWrite.seal] encodes of [meta]: its JSON with `v` set, as UTF-8. */
    internal fun plaintextOf(meta: JsonObject): ByteArray = JsonObject(meta + ("v" to JsonPrimitive(1))).toString().encodeToByteArray()

    /** [envelope] sealed under the new bookmark's key [key], bound to its scope and node (`node-meta`). */
    fun seal(suite: V1, scopeId: String, nodeId: String, key: ByteArray, link: ReceivedLink.ShareLink, password: String?): String =
        NodeMetaWrite.seal(suite, scopeId, nodeId, key, envelope(link, password))

    /** The bookmark an opened envelope holds, or null when it holds none this build reads (fail closed). */
    fun of(meta: JsonObject?): Bookmark? {
        val sub = meta?.get(KEY) as? JsonObject ?: return null
        val link = (sub["link"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val kept = ReceivedLink.kept(link) ?: return null
        val password = sub["password"]
        if (password != null) {
            val p = (password as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (p.isEmpty() || p.length > MAX_PASSWORD_LENGTH) return null
            return Bookmark(kept, p)
        }
        return Bookmark(kept, null)
    }

    /** The bookmark node [node] keeps, opened under its key; null where its meta does not read. */
    fun open(suite: V1, node: OpenNode): Bookmark? =
        runCatching { of(NodeMetaWrite.openForWrite(suite, node.scopeId, node.id, node.key, node.wrappedMeta)) }.getOrNull()
}
