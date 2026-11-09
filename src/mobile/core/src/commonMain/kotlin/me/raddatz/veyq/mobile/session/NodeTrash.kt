package me.raddatz.veyq.mobile.session

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.ContentApi
import me.raddatz.veyq.mobile.api.models.RestoreNodeRequest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.OpenRefused
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8

/**
 * A node to the space's trash and back, core's for every kind (the web's `deleteNode` and `restoreNode`,
 * `core/node/tree.ts`, `deleteSelection.ts`, `trashUndo.ts`). A delete is committed at once — a soft delete of the whole
 * subtree that frees the name — so its undo is a restore: the node comes back under the parent it left, its key wrap
 * untouched, with a slug token derived anew under the LIVE parent's key (the scope key at the root), since the delete
 * consumed the old one; a restore moves no placement revision and no epoch. The space's trash lists the root of each
 * deleted subtree, restores one (under a new name where something took its own meanwhile) and purges one now
 * (`SpaceTrashDialog.vue`, `listTrash`, `purgeNode`).
 */
object NodeTrash {
    /**
     * The root of a deleted subtree as the space's trash lists it: its name opened under the key its entry lives under —
     * its LIVE parent's (a trashed root's parent is always live) or, at the root, the scope key. [deletedAt] and
     * [purgeAfter] are the server's instants (ISO 8601); [attached] whether the node itself holds a blob in one of core's
     * attachment slots (what an application's empty-in-trash note reads).
     */
    class Trashed(
        val scopeId: String,
        val id: String,
        val parentId: String?,
        val kind: String,
        val name: String,
        val epoch: Int,
        val scopeEpoch: Int,
        val placementRevision: Long,
        val deletedAt: String,
        val purgeAfter: String,
        val attached: Boolean,
    )

    /**
     * The trash of [scopeId], in the server's order; null when it could not be read. An entry whose name does not open
     * (a guest-sealed one under an identity folder, a parent this device does not hold) is left out, as the web does.
     * Read it against the tree read after a restore: a child that stays in the trash opens only under its parent now live.
     */
    @Throws(Exception::class)
    suspend fun list(tenant: TenantSession, scopeId: String): List<Trashed>? {
        val account = tenant.account
        val (scopeKey, _) = tenant.scopeKey(scopeId) ?: return null
        val r = runCatching { account.respond { ContentApi(tenant.base, account.http).listTrashedNodes(scopeId) } }.getOrNull() ?: return null
        if (!r.success) return null
        val live = tenant.trees()[scopeId].orEmpty().associateBy { it.id }
        return r.body().mapNotNull { row ->
            if (row.shareIdentityId != null) return@mapNotNull null
            val entryKey = row.parentId?.let { live[it]?.key ?: return@mapNotNull null } ?: scopeKey
            val name = try {
                account.suite.openField(nameContext(scopeId, row.id, row.scopeEpoch, row.placementRevision), entryKey, unb64(row.encryptedName)).decodeToString()
            } catch (e: OpenRefused) {
                return@mapNotNull null
            } catch (e: IllegalArgumentException) {
                return@mapNotNull null
            }
            Trashed(
                scopeId, row.id, row.parentId, row.kind, name, row.epoch, row.scopeEpoch, row.placementRevision,
                row.deletedAt, row.purgeAfter, row.attached,
            )
        }
    }

    enum class Purged {
        PURGED,
        /** It is no longer in the trash (`404 node_not_found`): restored or purged meanwhile — read the list again. */
        GONE,
        FAILED,
    }

    /** Erases [nodeId] of [scopeId] and everything under it now, before its scheduled purge: irreversible. */
    @Throws(Exception::class)
    suspend fun purge(tenant: TenantSession, scopeId: String, nodeId: String): Purged {
        val account = tenant.account
        val r = runCatching { account.respond { ContentApi(tenant.base, account.http).purgeNode(scopeId, nodeId) } }.getOrElse { return Purged.FAILED }
        return when {
            r.success -> Purged.PURGED
            r.status == 404 -> Purged.GONE
            else -> Purged.FAILED
        }
    }

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
    suspend fun restore(tenant: TenantSession, node: OpenNode, tokenInput: String, waitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS): Restored =
        restoreEntry(tenant, Entry(node.scopeId, node.id, node.parentId, node.epoch, node.scopeEpoch, node.placementRevision), tokenInput, null, waitsMs, null)

    /**
     * Brings [node] back from the space's trash. [newName] is the retry after a [Restored.NAME_TAKEN]: the name sealed anew
     * travels in the same request as the slug token derived from it ([tokenInput] is then the new name's fold), so the node
     * is never live with a token its own name does not produce. A re-key meanwhile reads the trash again for the node.
     */
    @Throws(Exception::class)
    suspend fun restoreFromTrash(
        tenant: TenantSession,
        node: Trashed,
        tokenInput: String,
        newName: String? = null,
        waitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS,
    ): Restored = restoreEntry(
        tenant, Entry(node.scopeId, node.id, node.parentId, node.epoch, node.scopeEpoch, node.placementRevision), tokenInput,
        newName?.trim()?.takeIf { it.isNotEmpty() }, waitsMs,
    ) { list(tenant, node.scopeId)?.firstOrNull { it.id == node.id }?.let { Entry(it.scopeId, it.id, it.parentId, it.epoch, it.scopeEpoch, it.placementRevision) } }

    /** What a restore reads of the node: where its entry lives and what its name is bound to. */
    private class Entry(val scopeId: String, val id: String, val parentId: String?, val epoch: Int, val scopeEpoch: Int, val placementRevision: Long)

    private fun nameContext(scopeId: String, nodeId: String, scopeEpoch: Int, placementRevision: Long) = Context(
        "node-name", "scopeId" to scopeId, "nodeId" to nodeId, "epoch" to scopeEpoch.toLong(), "placementRevision" to placementRevision,
    )

    private suspend fun restoreEntry(
        tenant: TenantSession,
        first: Entry,
        tokenInput: String,
        newName: String?,
        waitsMs: List<Long>,
        refresh: (suspend () -> Entry?)?,
    ): Restored {
        val account = tenant.account
        val scopeId = first.scopeId
        val (scopeKey, scopeEpoch) = tenant.scopeKey(scopeId) ?: return Restored.REFUSED
        var node = first
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
            belowFloor = { (parent?.scopeEpoch ?: node.scopeEpoch) < scopeEpoch },
            reread = {
                tenant.forgetTree()
                if (refresh != null) node = refresh() ?: return@run false
                findParent()
            },
            attempt = {
                val p = parent
                val entryKey = p?.key ?: scopeKey
                // A restore is no placement: a name sealed anew binds the revision the node already has (ADR 0080 decision 3).
                val request = RestoreNodeRequest(
                    slugToken = b64(account.suite.slugToken(entryKey, p?.id ?: scopeId, tokenInput)),
                    parentEpoch = p?.epoch ?: node.epoch,
                    encryptedName = newName?.let {
                        b64(account.suite.sealField(nameContext(scopeId, node.id, node.scopeEpoch, node.placementRevision), entryKey, utf8(it)))
                    },
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
