package me.raddatz.veyq.mobile.session

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.ContentApi
import me.raddatz.veyq.mobile.api.models.UpdateNodeRequest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.utf8

/**
 * A node's placement, core's one write for a rename and a move (the web's `placeNode` → `sendPlacement`, `core/node/tree.ts`,
 * ADR 0080 decision 3): its own content key unchanged but re-wrapped, and its name sealed, under the key of the parent it
 * goes under (the scope key at the root), both bound to (scope, node, the node's scope epoch, its placement revision + 1),
 * with the blinded slug token of its name as its application folds it — stating the revision it was built on, its own
 * epoch and the parent's. `409 stale_placement` (another device placed it meanwhile) reads the tree again and builds once
 * more; a re-key in flight waits and `stale_epoch` re-reads ([NodeEpochWrite]). Nothing below the node changes: its
 * children's wraps are under its own key.
 */
internal object NodePlacement {
    sealed interface Ended {
        data object Placed : Ended
        /** Not re-keyed since its space's key changed, and still not after waiting. */
        data object Busy : Ended
        /** The node or the parent it goes under is no longer in the tree read. */
        data object Gone : Ended
        /** `stale_epoch` after the re-read too, or `stale_placement` after the second build. */
        data class Refused(val status: Int, val code: String?) : Ended
    }

    /**
     * Places node [nodeId] of [scopeId] under [parentOf] (of the node as read; null: the scope root), named [nameOf], its
     * token's input folded by [tokenInput] (null: nothing is sent, [Ended.Refused] 0).
     */
    suspend fun place(
        tenant: TenantSession,
        scopeId: String,
        nodeId: String,
        parentOf: (OpenNode) -> String?,
        nameOf: (OpenNode) -> String,
        tokenInput: (kind: String, name: String) -> String?,
        waitsMs: List<Long>,
    ): Ended {
        val account = tenant.account
        val suite = account.suite
        val (scopeKey, scopeEpoch) = tenant.scopeKey(scopeId) ?: return Ended.Gone
        var node: OpenNode? = null
        var parent: OpenNode? = null
        suspend fun read(): Boolean {
            val tree = tenant.trees()[scopeId].orEmpty()
            node = tree.firstOrNull { it.id == nodeId } ?: return false
            parent = parentOf(node!!)?.let { p -> tree.firstOrNull { it.id == p } ?: return false }
            return true
        }
        if (!read()) return Ended.Gone
        var placements = 0
        while (true) {
            var placed = false
            val result = NodeEpochWrite.run(
                waitsMs = waitsMs,
                // never seal under a key from before the space's last rotation, nor re-wrap one: the parent's (at the root
                // the scope key, held at the floor) and the node's own
                belowFloor = { (parent?.scopeEpoch ?: scopeEpoch) < scopeEpoch || node!!.scopeEpoch < scopeEpoch },
                reread = {
                    tenant.forgetTree()
                    read()
                },
                attempt = {
                    val n = node!!
                    val p = parent
                    val name = nameOf(n)
                    val input = tokenInput(n.kind, name) ?: return@run NodeEpochWrite.Answer.OTHER to (0 to "no_token_input")
                    val entryKey = p?.key ?: scopeKey
                    val binding = arrayOf("scopeId" to scopeId, "nodeId" to n.id, "epoch" to n.scopeEpoch.toLong(), "placementRevision" to n.placementRevision + 1)
                    val request = UpdateNodeRequest(
                        encryptedName = b64(suite.sealField(Context("node-name", *binding), entryKey, utf8(name))),
                        slugToken = b64(suite.slugToken(entryKey, p?.id ?: scopeId, input)),
                        parentId = p?.id,
                        wrappedContentKey = b64(suite.seal(Context("content-key", *binding), entryKey, n.key)),
                        epoch = n.epoch,
                        placementRevision = n.placementRevision,
                        parentEpoch = p?.epoch,
                    )
                    val r = account.respond { ContentApi(tenant.base, account.http).updateNode(scopeId, n.id, request) }
                    val code = if (r.success) null else codeOf(runCatching { r.response.bodyAsText() }.getOrDefault(""))
                    val answer = when {
                        r.success -> {
                            placed = true
                            NodeEpochWrite.Answer.DONE
                        }
                        r.status == 409 && code == "node_rekey_pending" -> NodeEpochWrite.Answer.REKEY_PENDING
                        r.status == 409 && code == "stale_epoch" -> NodeEpochWrite.Answer.STALE_EPOCH
                        else -> NodeEpochWrite.Answer.OTHER
                    }
                    answer to (r.status to code)
                },
            )
            val answered = result as? NodeEpochWrite.Result.Answered
            if (!placed && answered != null && answered.value.first == 409 && answered.value.second == "stale_placement" && placements++ < PLACEMENT_RETRIES) {
                // another device placed it meanwhile: the tree read again, the placement built on what it holds now
                tenant.forgetTree()
                if (!read()) return Ended.Gone
                continue
            }
            tenant.forgetTree()
            if (placed) return Ended.Placed
            return when (result) {
                NodeEpochWrite.Result.Busy -> Ended.Busy
                NodeEpochWrite.Result.Gone -> Ended.Gone
                NodeEpochWrite.Result.StaleAgain -> Ended.Refused(409, "stale_epoch")
                is NodeEpochWrite.Result.Answered -> Ended.Refused(result.value.first, result.value.second)
            }
        }
    }

    /** How often a `409 stale_placement` is built again on the tree read anew (`PLACEMENT_RETRIES`). */
    private const val PLACEMENT_RETRIES = 1

    fun codeOf(body: String): String? = runCatching { (Json.parseToJsonElement(body) as JsonObject)["code"]?.jsonPrimitive?.content }.getOrNull()
}
