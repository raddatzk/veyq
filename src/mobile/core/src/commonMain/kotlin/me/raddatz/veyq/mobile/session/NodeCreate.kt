package me.raddatz.veyq.mobile.session

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.ContentApi
import me.raddatz.veyq.mobile.api.models.CreateNodeRequest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.utf8

/**
 * A node created in a scope, core's for every kind — a port of `createContentNode` (`src/web/src/core/node/tree.ts`,
 * ADR 0080 decisions 1 and 2): a fresh random content key wrapped under the PARENT's key (the scope key at the root),
 * the name and the blinded slug token sealed under that same key, all bound to the id the client mints, at placement
 * revision 0 and the scope epoch the key is minted under. The new key is at its own epoch 0. An application brings its
 * kind, the token input its kind folds the name to ([tokenInput], `registry.tokenInputFor`: its route slug and
 * [me.raddatz.veyq.mobile.crypto.Naming]), and optionally the node's first sealed meta.
 */
object NodeCreate {
    class Created(val nodeId: String, internal val key: ByteArray, val epoch: Int, val scopeEpoch: Int)

    sealed interface Result {
        data class Done(val created: Created) : Result
        data class Refused(val reason: Reason) : Result
    }

    enum class Reason {
        /** A sibling of the same kind's group already folds to this name (`409 slug_taken`). */
        NAME_TAKEN,
        /** The parent is not re-keyed since its space's key changed, and still was not after waiting. */
        BUSY,
        BLOCKED,
        /** The parent is not there to create in, or the server refused otherwise. */
        REFUSED,
    }

    /**
     * Creates a node of [kind] named [name] in [scopeId], under [parentId] (null: the scope's root). [sealMeta] seals the
     * node's first meta under its new key and id, sent in the same request.
     */
    suspend fun create(
        tenant: TenantSession, scopeId: String, parentId: String?, kind: String, name: String, tokenInput: String,
        sealMeta: ((key: ByteArray, nodeId: String) -> String)? = null,
        waitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS,
    ): Result {
        val account = tenant.account
        val suite = account.suite
        var parent: OpenNode? = null
        suspend fun findParent(): Boolean {
            if (parentId == null) return true
            parent = tenant.trees()[scopeId]?.firstOrNull { it.id == parentId }
            return parent != null
        }
        if (!findParent()) return Result.Refused(Reason.REFUSED)
        // The scope key this session opened, and its epoch: what a new key is minted under (the server refuses another).
        val (scopeKey, scopeEpoch) = tenant.scopeKey(scopeId) ?: return Result.Refused(Reason.REFUSED)
        var created: Created? = null
        val result = NodeEpochWrite.run(
            waitsMs = waitsMs,
            // a parent that lags is pulled forward first: the new node's entry is sealed under its key
            pull = { parentId?.let { NodeRekey.pull(tenant, scopeId, listOf(it)) } ?: false },
            belowFloor = { parent?.let { it.scopeEpoch < scopeEpoch } ?: false },
            reread = {
                tenant.forgetTree()
                findParent()
            },
            attempt = {
                val p = parent
                val entryKey = p?.key ?: scopeKey
                val nodeId = uuid(account)
                val binding = arrayOf("scopeId" to scopeId, "nodeId" to nodeId, "epoch" to scopeEpoch.toLong(), "placementRevision" to 0L)
                val key = account.primitives.randomBytes(32)
                val request = CreateNodeRequest(
                    nodeId = nodeId,
                    kind = kind,
                    encryptedName = b64(suite.sealField(Context("node-name", *binding), entryKey, utf8(name))),
                    slugToken = b64(suite.slugToken(entryKey, p?.id ?: scopeId, tokenInput)),
                    wrappedContentKey = b64(suite.seal(Context("content-key", *binding), entryKey, key)),
                    parentId = p?.id,
                    orderIndex = 0,
                    scopeEpoch = scopeEpoch,
                    wrappedMeta = sealMeta?.invoke(key, nodeId),
                    parentEpoch = p?.epoch,
                )
                val r = account.respond { ContentApi(tenant.base, account.http).createNode(scopeId, request) }
                val code = if (r.success) null else codeOf(runCatching { r.response.bodyAsText() }.getOrDefault(""))
                val answer = when {
                    r.success -> {
                        // The server echoes the id it stored: anything else is an id substituted under the seals.
                        if (r.body().nodeId != nodeId) throw IllegalStateException("id_substituted")
                        created = Created(nodeId, key, 0, scopeEpoch)
                        NodeEpochWrite.Answer.DONE
                    }
                    r.status == 409 && code == "node_rekey_pending" -> NodeEpochWrite.Answer.REKEY_PENDING
                    r.status == 409 && code == "stale_epoch" -> NodeEpochWrite.Answer.STALE_EPOCH
                    else -> NodeEpochWrite.Answer.OTHER
                }
                answer to (r.status to code)
            },
        )
        return when (result) {
            NodeEpochWrite.Result.Busy -> Result.Refused(Reason.BUSY)
            NodeEpochWrite.Result.Gone, NodeEpochWrite.Result.StaleAgain -> Result.Refused(Reason.REFUSED)
            is NodeEpochWrite.Result.Answered -> {
                val (status, code) = result.value
                created?.let { Result.Done(it) } ?: Result.Refused(
                    when {
                        status == 409 && code == "slug_taken" -> Reason.NAME_TAKEN
                        status == 451 -> Reason.BLOCKED
                        else -> Reason.REFUSED
                    },
                )
            }
        }
    }

    private fun codeOf(body: String): String? = runCatching { (Json.parseToJsonElement(body) as JsonObject)["code"]?.jsonPrimitive?.content }.getOrNull()

    /** A fresh UUID v4 from the suite's randomness. */
    internal fun uuid(account: VeyqAccount): String {
        val b = account.primitives.randomBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = hex(b)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}
