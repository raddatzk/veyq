package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.CancellationException
import me.raddatz.veyq.mobile.api.memories.apis.ItemFavoritesApi
import me.raddatz.veyq.mobile.api.memories.apis.ItemFavoritesRekeyApi
import me.raddatz.veyq.mobile.api.memories.apis.OwnItemFavoritesApi
import me.raddatz.veyq.mobile.api.memories.models.ItemFavoritesRekeyWrap
import me.raddatz.veyq.mobile.api.memories.models.OwnItemFavoritesEntry
import me.raddatz.veyq.mobile.api.memories.models.PutItemFavoritesRequest
import me.raddatz.veyq.mobile.api.memories.models.ScopeItemFavoritesRekeyStage
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.ItemMarks
import me.raddatz.veyq.mobile.crypto.ValueKeys
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.rotation.ScopeRekeyParticipant

/**
 * Memories' item marks under a rotation (ADR 0068 §9, ADR 0115 decision 2), the port of `features/memories/itemFavorites.ts`'
 * two rotation halves: a scope rotation re-wraps every member's marks' value keys ([participant], `stageItemMarksRekey`),
 * and an account rotation re-tags the account's own marks under its new Master Key ([retag],
 * `retagItemMarksUnderNewMasterKey`). Reached only through `AppKinds`.
 */
object ItemMarksRotation {
    /** The contract's cap per page of the scope re-key listing and per stage. */
    private const val REKEY_BATCH = 256

    /**
     * Memories' part of a rotation's scope-level move: every member's marked set of every Memory in the scope, listed page
     * by page as its value key's wrap — never its ciphertext —, that wrap moved to the new key by core's re-wrap (a wrap
     * that does not open staged as one nobody opens) and staged under the rotation's re-key id beside the wrap it replaces.
     * The set itself stays byte-identical, its self-tag its marker's.
     */
    val participant = ScopeRekeyParticipant { ctx ->
        val api = ItemFavoritesRekeyApi(ctx.base, ctx.account.http)
        var since: String? = null
        while (true) {
            val page = ctx.send { api.listScopeItemFavoritesRekey(ctx.scopeId, since, REKEY_BATCH) }.body()
            val marks = page.marks.map { m -> ItemFavoritesRekeyWrap(m.markId, ctx.rewrap(me.raddatz.veyq.mobile.memories.ItemMarks.context(ctx.scopeId, m.nodeId), m.wrappedValueKey), m.wrappedValueKey) }
            if (marks.isNotEmpty()) ctx.send { api.stageScopeItemFavoritesRekey(ctx.scopeId, ctx.rekeyId, ScopeItemFavoritesRekeyStage(ctx.fromEpoch, marks)) }
            if (!page.more || page.cursor == null) return@ScopeRekeyParticipant
            since = page.cursor
        }
    }

    /**
     * Re-tags the account's own marks from [oldMk] to [newMk] (an account rotation, ADR 0060): of the rows the server lists
     * as the account's own, each whose tag verifies under the OLD key — the listing is a hint, the old key the licence — is
     * sealed again with its tag under the new one, at the scope's current epoch (once more under the scope opened again on
     * `409 stale_epoch`). Every listed scope, as the web's `retagItemMarksUnderNewMasterKey`: the old Master Key tagged the
     * account's marks wherever it marked, not only in the rotation's plan, and a mark left under the old tag would read as
     * someone else's for good. Best effort per Memory: a scope that does not open or a write that fails leaves that
     * Memory's set reading as someone else's until its next toggle, and every other is still re-tagged.
     */
    suspend fun retag(account: VeyqAccount, oldMk: ByteArray, newMk: ByteArray) {
        val marks = me.raddatz.veyq.mobile.crypto.ItemMarks(account.suite)
        // the rows of every tenant (the listing answers on a tenant's origin), each row once, with the tenant it came from
        val rows = LinkedHashMap<Pair<String, String>, Pair<OwnItemFavoritesEntry, me.raddatz.veyq.mobile.navigation.TenantRef>>()
        for (tenant in account.tenants) {
            val listed = try {
                account.call { OwnItemFavoritesApi(account.originOf(tenant), account.http).listOwnItemFavorites() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            for (row in listed) rows.getOrPut(row.scopeId to row.nodeId) { row to tenant }
        }
        val oldKey = marks.selfKey(oldMk)
        val newKey = marks.selfKey(newMk)
        try {
            for ((scopeId, scopeRows) in rows.values.groupBy { it.first.scopeId }) {
                val tenant = scopeRows.first().second
                val opened = try {
                    account.reopenScope(tenant, scopeId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: continue
                var held: Pair<ByteArray, Long> = opened
                try {
                    for ((row, _) in scopeRows) {
                        val (tag, ids) = me.raddatz.veyq.mobile.memories.ItemMarks.open(account.suite, scopeId, row.nodeId, held.first, held.second, row.ciphertext, row.wrappedValueKey) ?: continue
                        if (!marks.selfTagVerifies(oldKey, scopeId, row.nodeId, ids, tag)) continue
                        held = putAtCurrentEpoch(account, tenant, scopeId, row.nodeId, held, newKey, ids) ?: held
                    }
                } finally {
                    held.first.fill(0)
                    if (held !== opened) opened.first.fill(0)
                }
            }
        } finally {
            oldKey.fill(0)
            newKey.fill(0)
        }
    }

    /**
     * The set sealed with its tag under [selfKey] at [held]'s epoch and stored; on `409 stale_epoch` once more under the
     * scope opened again. The scope key now held where it was opened again (the caller zeroes it), else null. Best effort:
     * any other failure leaves the row.
     */
    private suspend fun putAtCurrentEpoch(
        account: VeyqAccount, tenant: me.raddatz.veyq.mobile.navigation.TenantRef, scopeId: String, nodeId: String,
        held: Pair<ByteArray, Long>, selfKey: ByteArray, ids: List<String>,
    ): Pair<ByteArray, Long>? {
        val api = ItemFavoritesApi(account.originOf(tenant), account.http)
        suspend fun put(key: ByteArray, epoch: Long): Int? {
            val request = me.raddatz.veyq.mobile.memories.ItemMarks.seal(account.suite, account.primitives, scopeId, nodeId, key, epoch, selfKey, ids) ?: return null
            val r = account.respond { api.putItemFavorites(scopeId, nodeId, request) }
            return if (r.success) 0 else if (r.status == 409 && account.errorCode(r) == "stale_epoch") 409 else null
        }
        return try {
            if (put(held.first, held.second) != 409) return null
            val again = account.reopenScope(tenant, scopeId) ?: return null
            put(again.first, again.second)
            held.first.fill(0)
            again
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
