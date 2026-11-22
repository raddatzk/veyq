package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.api.expenses.apis.ExpensesApi
import me.raddatz.veyq.mobile.api.expenses.models.PotRekeyStage
import me.raddatz.veyq.mobile.api.expenses.models.PotRekeyWrap
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.NodeRekey

/**
 * A Pot's own content moved to its new key (the web's `rekeyPotContent`, `features/expenses/rekey.ts`): every revision's
 * `K_rev`, read page by page and moved from the Pot's old key to its new one, bound to the revision's address as before —
 * bodies and digests never change. Staged in slices of 256 under core's rekey id, before core's commit; an empty Pot
 * stages nothing. Receipts are core's application blobs and the cover is core's meta: core moves those.
 */
object PotRekey {
    private const val PAGE = 256

    val content = NodeRekey.ContentRekey { ctx ->
        val account = ctx.tenant.account
        val api = ExpensesApi(ctx.tenant.base, account.http)
        val codec = RevisionCodec(account.suite)
        var since: String? = null
        while (true) {
            val page = account.call { api.listPotRekey(ctx.scopeId, ctx.nodeId, since, PAGE) }
            val moved = page.revisions.map { r ->
                val address = RevisionAddress(ctx.scopeId, ctx.nodeId, r.entryId, r.revision)
                PotRekeyWrap(r.entryId, r.revision, b64(codec.rewrapRevisionKey(address, ctx.oldKey, ctx.newKey, unb64(r.wrappedRevisionKey))))
            }
            if (moved.isNotEmpty()) {
                val r = account.respond { api.stagePotRekey(ctx.scopeId, ctx.nodeId, ctx.rekeyId, PotRekeyStage(ctx.fromEpoch, moved)) }
                check(r.success) { "stage_pot_rekey_${r.status}" }
            }
            if (!page.more || page.cursor == null) return@ContentRekey
            since = page.cursor
        }
    }
}
