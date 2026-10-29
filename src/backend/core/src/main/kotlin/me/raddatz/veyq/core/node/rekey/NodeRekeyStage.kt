package me.raddatz.veyq.core.node.rekey

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The staged parts of a node's re-key (ADR 0109 point 2): a node with more fields than one request carries — a folder of
 * many children, a Memory of many photos, a link with many guests — sends them here first, at most the contracts'
 * caps per call, and `commitNodeRekey` swaps them in in one transaction. Core's own parts (children, attachment blobs,
 * shares, identities) and each application's ([NodeRekeyParticipant.part]) share the table, keyed by the client-minted
 * re-key id. A value staged twice for the same row and column replaces the first.
 */
@ApplicationScoped
class NodeRekeyStage {

    /** Which row and column a staged value is for. */
    data class Key(val itemId: String, val field: String)

    /** A staged value: the ciphertext and, where the row has one, the revision it was staged against. */
    data class Value(val value: String, val revision: Long?)

    /** Stage [values] of [part] for [nodeId] under [rekeyId]; first deletes every stage older than [TTL] (any scope's —
     *  the one reader of `created_at`). */
    @Transactional
    fun put(rekeyId: String, scopeId: String, nodeId: String, part: String, values: Map<Key, Value>, now: Instant = Instant.now()) {
        NodeRekeyStageEntity.delete("createdAt < ?1", now.minus(TTL))
        if (values.isEmpty()) return
        values.keys.map { it.itemId }.distinct().chunked(CHUNK).forEach { items ->
            NodeRekeyStageEntity.delete("rekeyId = ?1 and part = ?2 and itemId in ?3", rekeyId, part, items)
        }
        for ((key, v) in values) {
            NodeRekeyStageEntity().apply {
                id = UUID.randomUUID().toString()
                this.rekeyId = rekeyId
                this.scopeId = scopeId
                this.nodeId = nodeId
                this.part = part
                itemId = key.itemId
                field = key.field
                stageValue = v.value
                revision = v.revision
                createdAt = now
            }.persist()
        }
    }

    /** What [rekeyId] staged of [part] for [nodeId] — a stage under another node's id is not this node's. */
    fun read(rekeyId: String, nodeId: String, part: String): Map<Key, Value> =
        NodeRekeyStageEntity.find("rekeyId = ?1 and nodeId = ?2 and part = ?3", rekeyId, nodeId, part).list()
            .associate { Key(it.itemId, it.field) to Value(it.stageValue, it.revision) }

    /** Drop everything [rekeyId] staged — the commit took it. */
    fun clear(rekeyId: String) {
        NodeRekeyStageEntity.delete("rekeyId", rekeyId)
    }

    companion object {
        /** How long a stage no commit took is kept: a re-keyer that stops halfway starts the node over with a new id. */
        val TTL: Duration = Duration.ofDays(1)
        private const val CHUNK = 500
        const val CORE = "core"
    }
}
