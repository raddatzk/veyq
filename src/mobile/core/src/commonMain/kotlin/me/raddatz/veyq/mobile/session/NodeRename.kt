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
 * A node renamed, core's for every kind (the web's `renameMemberNode`, `core/node/nodeName.ts`, and `placeNode` in
 * `tree.ts`, ADR 0080 decision 3): a placement to the parent it is under — its own key re-wrapped and its name sealed
 * under the parent's key (the scope key at the root), both bound to (scope, node, the node's scope epoch, its placement
 * revision + 1), with the blinded slug token of the name its application folds — stating the revision it was built on.
 * `409 stale_placement` (another device placed it meanwhile) reads the tree again and builds once more; a re-key in
 * flight waits and `stale_epoch` re-reads ([NodeEpochWrite]). Then the name is sealed anew into the node's open links
 * ([ShareNames]); a link that fails there is said, the rename stands.
 */
object NodeRename {
    enum class Outcome {
        RENAMED,
        /** A sibling already folds to this name — before sending (the loaded tree), or as the server answers any `409`. */
        NAME_TAKEN,
        /** Not allowed here (`422`: a structure rule, the application switched off). */
        NOT_ALLOWED,
        /** The parent is not re-keyed since its space's key changed, and still was not after waiting. */
        BUSY,
        FAILED,
    }

    /** What came of it: [taken] is the sibling the name collides with when the loaded tree shows one; [links] the re-seal. */
    class Result(val outcome: Outcome, val taken: OpenNode? = null, val links: ShareNames.Report? = null)

    /**
     * Renames node [nodeId] of [scopeId] to [name] (trimmed; empty or unchanged writes nothing). [tokenInput] folds a name
     * of a kind to its slug token's input, as its application folds it (`registry.tokenInputFor`; null: a kind this
     * client does not know, whose collision only the server sees).
     */
    @Throws(Exception::class)
    suspend fun rename(
        tenant: TenantSession, scopeId: String, nodeId: String, name: String, tokenInput: (kind: String, name: String) -> String?,
        nowMs: Long, waitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS,
    ): Result {
        val trimmed = name.trim()
        val account = tenant.account
        val suite = account.suite
        val (scopeKey, scopeEpoch) = tenant.scopeKey(scopeId) ?: return Result(Outcome.FAILED)
        var node: OpenNode? = null
        var parent: OpenNode? = null
        suspend fun read(): Boolean {
            val tree = tenant.trees()[scopeId].orEmpty()
            node = tree.firstOrNull { it.id == nodeId } ?: return false
            parent = node!!.parentId?.let { p -> tree.firstOrNull { it.id == p } ?: return false }
            return true
        }
        if (!read()) return Result(Outcome.FAILED)
        if (trimmed.isEmpty() || trimmed == node!!.name) return Result(Outcome.RENAMED)
        val input = tokenInput(node!!.kind, trimmed) ?: return Result(Outcome.FAILED)
        // The loaded tree's sibling that folds to the same input: said by name before anything is sent (`collidingSibling`).
        tenant.trees()[scopeId].orEmpty()
            .firstOrNull { it.parentId == node!!.parentId && it.id != nodeId && tokenInput(it.kind, it.name) == input }
            ?.let { return Result(Outcome.NAME_TAKEN, taken = it) }
        var placements = 0
        while (true) {
            var renamed = false
            val result = NodeEpochWrite.run(
                waitsMs = waitsMs,
                // never re-seal under a key from before the space's last rotation: the parent's, at the root the node's own
                belowFloor = { (parent ?: node!!).scopeEpoch < scopeEpoch },
                reread = {
                    tenant.forgetTree()
                    read()
                },
                attempt = {
                    val n = node!!
                    val p = parent
                    val entryKey = p?.key ?: scopeKey
                    val binding = arrayOf("scopeId" to scopeId, "nodeId" to n.id, "epoch" to n.scopeEpoch.toLong(), "placementRevision" to n.placementRevision + 1)
                    val request = UpdateNodeRequest(
                        encryptedName = b64(suite.sealField(Context("node-name", *binding), entryKey, utf8(trimmed))),
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
                            renamed = true
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
            if (!renamed && answered != null && answered.value.first == 409 && answered.value.second == "stale_placement" && placements++ < PLACEMENT_RETRIES) {
                // another device placed it meanwhile: the tree read again, the placement built on what it holds now
                tenant.forgetTree()
                if (!read()) return Result(Outcome.FAILED)
                continue
            }
            tenant.forgetTree()
            if (renamed) {
                val links = runCatching { ShareNames.reseal(tenant, scopeId, nodeId, trimmed, nowMs) }.getOrNull()
                return Result(Outcome.RENAMED, links = links ?: ShareNames.Report(emptyList(), emptyList(), emptyList(), listOf("list")))
            }
            return Result(
                when (result) {
                    NodeEpochWrite.Result.Busy -> Outcome.BUSY
                    is NodeEpochWrite.Result.Answered -> when (result.value.first) {
                        // every 409 is a name already taken, whatever its code (`nodeNameErrorText`)
                        409 -> Outcome.NAME_TAKEN
                        422 -> Outcome.NOT_ALLOWED
                        else -> Outcome.FAILED
                    }
                    else -> Outcome.FAILED
                },
            )
        }
    }

    /** How often a `409 stale_placement` is built again on the tree read anew (`PLACEMENT_RETRIES`). */
    private const val PLACEMENT_RETRIES = 1

    private fun codeOf(body: String): String? = runCatching { (Json.parseToJsonElement(body) as JsonObject)["code"]?.jsonPrimitive?.content }.getOrNull()
}
