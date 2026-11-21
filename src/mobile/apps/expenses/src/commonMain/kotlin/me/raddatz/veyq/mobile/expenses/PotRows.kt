package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.session.TenantSession

/**
 * What a Pot's row in a list says beside its name, a port of the web's `levelRows.ts`: its base currency as its one
 * detail, left out where the Pot's meta carries none or does not open here — never a placeholder. Expenses' answer for
 * the shell's rows; core's lists know no application, so the app's composition root asks this and hands it on.
 */
object PotRows {
    /** Each Pot of [tenant] by node id, with its details (empty: nothing to say). */
    @Throws(Exception::class)
    suspend fun details(tenant: TenantSession): Map<String, List<String>> =
        tenant.trees().values.flatten().filter { it.kind == PotSession.KIND }.associate { node ->
            node.id to listOfNotNull(PotMeta.opened(tenant.account.suite, node)?.base?.currency)
        }

    /** The details of a Pot whose settings are [meta]. */
    fun detailsOf(meta: PotMeta?): List<String> = listOfNotNull(meta?.base?.currency)
}
