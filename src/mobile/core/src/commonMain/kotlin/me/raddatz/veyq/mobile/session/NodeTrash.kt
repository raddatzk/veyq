package me.raddatz.veyq.mobile.session

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.ContentApi
import me.raddatz.veyq.mobile.api.models.RestoreNodeRequest
import me.raddatz.veyq.mobile.crypto.b64

/**
 * A node to the space's trash and back, core's for every kind (the web's `deleteNode` and `restoreNode`,
 * `core/node/tree.ts`, `deleteSelection.ts`, `trashUndo.ts`). A delete is committed at once — a soft delete of the whole
 * subtree that frees the name — so its undo is a restore: the node comes back under the parent it left, its key wrap
 * untouched, with a slug token derived anew under the LIVE parent's key (the scope key at the root), since the delete
 * consumed the old one; a restore moves no placement revision and no epoch.
 */
object NodeTrash {
    enum class Deleted {
        /** It is in the trash now. */
        TRASHED,
        /** It was no longer there (`404 node_not_found`). */
        GONE,
        /** The space is being deleted (`409 scope_deleting`): nothing is changed in it. */
        SCOPE_DELETING,
        FAILED,
    }

    /** Moves node [nodeId] of [scopeId] with everything under it to the trash; the tree is read anew after it. */
    @Throws(Exception::class)
    suspend fun delete(tenant: TenantSession, scopeId: String, nodeId: String): Deleted {
        val account = tenant.account
        val r = runCatching { account.respond { ContentApi(tenant.base, account.http).deleteNode(scopeId, nodeId) } }.getOrElse { return Deleted.FAILED }
        tenant.forgetTree()
        return when {
            r.success -> Deleted.TRASHED
            r.status == 404 -> Deleted.GONE
            r.status == 409 && codeOf(r.response.bodyAsText()) == "scope_deleting" -> Deleted.SCOPE_DELETING
            else -> Deleted.FAILED
        }
    }

    enum class Restored {
        RESTORED,
        /** Something created meanwhile holds its name (`409 slug_taken`): it can be restored under another from the trash. */
        NAME_TAKEN,
        /** The parent is not re-keyed since its space's key changed, and still was not after waiting. */
        BUSY,
        BLOCKED,
        /** Its parent is gone, the space is being deleted, or the server refused otherwise. */
        REFUSED,
    }

    /**
     * Brings [node] — as the tree held it before its delete — back from the trash under its name, folded to [tokenInput]
     * by its kind (an application's answer, `registry.tokenInputFor`). The own epoch the request states is the live
     * parent's, or at the root the node's own; never under a key from before the space's last rotation (the floor).
     */
    @Throws(Exception::class)
    suspend fun restore(tenant: TenantSession, node: OpenNode, tokenInput: String, waitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS): Restored {
        val account = tenant.account
        val scopeId = node.scopeId
        val (scopeKey, scopeEpoch) = tenant.scopeKey(scopeId) ?: return Restored.REFUSED
        var parent: OpenNode? = null
        suspend fun findParent(): Boolean {
            if (node.parentId == null) return true
            parent = tenant.trees()[scopeId]?.firstOrNull { it.id == node.parentId }
            return parent != null
        }
        if (!findParent()) return Restored.REFUSED
        var restored = false
        val result = NodeEpochWrite.run(
            waitsMs = waitsMs,
            belowFloor = { (parent ?: node).scopeEpoch < scopeEpoch },
            reread = {
                tenant.forgetTree()
                findParent()
            },
            attempt = {
                val p = parent
                val request = RestoreNodeRequest(
                    slugToken = b64(account.suite.slugToken(p?.key ?: scopeKey, p?.id ?: scopeId, tokenInput)),
                    parentEpoch = (p ?: node).epoch,
                )
                val r = account.respond { ContentApi(tenant.base, account.http).restoreNode(scopeId, node.id, request) }
                val code = if (r.success) null else codeOf(runCatching { r.response.bodyAsText() }.getOrDefault(""))
                val answer = when {
                    r.success -> {
                        restored = true
                        NodeEpochWrite.Answer.DONE
                    }
                    r.status == 409 && code == "node_rekey_pending" -> NodeEpochWrite.Answer.REKEY_PENDING
                    r.status == 409 && code == "stale_epoch" -> NodeEpochWrite.Answer.STALE_EPOCH
                    else -> NodeEpochWrite.Answer.OTHER
                }
                answer to (r.status to code)
            },
        )
        tenant.forgetTree()
        return when (result) {
            NodeEpochWrite.Result.Busy -> Restored.BUSY
            NodeEpochWrite.Result.Gone, NodeEpochWrite.Result.StaleAgain -> Restored.REFUSED
            is NodeEpochWrite.Result.Answered -> when {
                restored -> Restored.RESTORED
                result.value.first == 409 && result.value.second == "slug_taken" -> Restored.NAME_TAKEN
                result.value.first == 451 -> Restored.BLOCKED
                else -> Restored.REFUSED
            }
        }
    }

    private fun codeOf(body: String): String? = runCatching { (Json.parseToJsonElement(body) as JsonObject)["code"]?.jsonPrimitive?.content }.getOrNull()
}
