package me.raddatz.veyq.mobile.memories.place

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.testPrimitives

/** A node's sealed meta and a medium's listed tier under real seals, and a server the tests play (each send answered in turn). */
internal class PlayedMeta {
    val suite = V1(testPrimitives)
    val key = ByteArray(32) { 7 }
    val scopeId = "11111111-1111-1111-1111-111111111111"
    val nodeId = "5c09e000-0000-4000-8000-0000000000d1"

    fun seal(json: String, nodeId: String = this.nodeId, key: ByteArray = this.key): String =
        b64(suite.sealField(NodeMetaWrite.context(scopeId, nodeId), key, json.encodeToByteArray()))

    fun opened(wrapped: String): JsonObject =
        Json.parseToJsonElement(suite.openField(NodeMetaWrite.context(scopeId, nodeId), key, unb64(wrapped)).decodeToString()) as JsonObject

    val open: (String?) -> JsonObject = { NodeMetaWrite.openForWrite(suite, scopeId, nodeId, key, it) }
    val sealMeta: (JsonObject) -> String = { NodeMetaWrite.seal(suite, scopeId, nodeId, key, it) }

    /** What each send carried, answered in turn by [answers] (then taken). */
    class Server(vararg answers: NodeMetaWrite.Answer) {
        val sent = mutableListOf<Pair<String, Long>>()
        private val queue = ArrayDeque(answers.toList())
        suspend fun send(wrapped: String, revision: Long): NodeMetaWrite.Answer {
            sent += wrapped to revision
            return queue.removeFirstOrNull() ?: NodeMetaWrite.Answer.Taken
        }
    }

    /** A medium whose listed tier is [tier] (JSON, sealed under [key] for it), or none. */
    fun item(tier: String?, mediaId: String = randomId(), type: String = "image/jpeg", name: String? = "$mediaId.jpg"): MemoryMedia.Item {
        val wrapped = tier?.let { b64(suite.sealField(Context("media-meta", "scopeId" to scopeId, "mediaId" to mediaId), key, it.encodeToByteArray())) }
        return MemoryMedia.Item(
            scopeId, nodeId, mediaId, MemoryMedia.Blob("o-$mediaId", 1, 100, "", null), null, null,
            MemoryMedia.openMeta(suite, scopeId, mediaId, key, wrapped), type, name, "user:u1", false, key, 3, wrapped,
        )
    }

    private var n = 0
    private fun randomId() = "00000000-0000-4000-8000-" + (n++).toString().padStart(12, '0')
}
