package me.raddatz.veyq.mobile.session

import io.ktor.client.statement.bodyAsText
import me.raddatz.veyq.mobile.api.apis.SharingApi
import me.raddatz.veyq.mobile.api.models.SetShareSealedNameRequest
import me.raddatz.veyq.mobile.api.models.ShareSummary
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Purposes
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8

/**
 * A renamed node's name sealed anew into every OPEN link rooted at it (the web's `resealShareNames`,
 * `core/sharing/manageShares.ts`; ADR 0080 decision 14), so no link shows its guests the old title. Each link's `K_share`
 * is opened through its recovery copy — its value key wrapped under the scope key (ADR 0115), `K_share` under it, bound to
 * (scope, node, share, the copy's epoch) — and the new name sealed under it as a mint does (`share-memory-name`), then
 * stored by `setShareSealedName`, which any member who may write may call. A closed link (switched off, past its end)
 * is left alone: nothing new is sealed under it, and its reopen seals the root's name then. Each link is its own write;
 * a failed one is counted and the next still tried; the rename itself is never undone.
 */
object ShareNames {
    /** What a re-seal did, by share id. [skipped] carry no recovery copy (a tombstone, or a link minted before copies). */
    class Report(val resealed: List<String>, val closed: List<String>, val skipped: List<String>, val failed: List<String>)

    /** Re-seals [name] into every open link rooted at [nodeId] of [scopeId]; throws only when the links do not list. */
    @Throws(Exception::class)
    suspend fun reseal(tenant: TenantSession, scopeId: String, nodeId: String, name: String, nowMs: Long): Report {
        val account = tenant.account
        val api = SharingApi(tenant.base, account.http)
        val (scopeKey, scopeEpoch) = tenant.scopeKey(scopeId) ?: throw IllegalStateException("scope_not_open")
        val resealed = ArrayList<String>()
        val closed = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<String>()
        for (share in account.call { api.listShares(nodeId) }) {
            if (!isOpen(share, nowMs)) {
                closed += share.shareId
                continue
            }
            val sealedKey = share.sealedShareKey
            val copyEpoch = share.sealedShareKeyEpoch
            val wrapped = share.wrappedRecoveryValueKey
            if (sealedKey == null || copyEpoch == null || wrapped == null) {
                skipped += share.shareId
                continue
            }
            val kShare = recoverShareKey(account.suite, JoinRecords(account.suite), share, copyEpoch, scopeKey, scopeEpoch.toLong(), wrapped, sealedKey)
            if (kShare == null) {
                failed += share.shareId
                continue
            }
            val sealedName = try {
                b64(account.suite.sealField(Context("share-memory-name"), kShare, utf8(name)))
            } finally {
                kShare.fill(0)
            }
            val r = runCatching { account.respond { api.setShareSealedName(share.shareId, SetShareSealedNameRequest(sealedName)) } }.getOrNull()
            when {
                r == null -> failed += share.shareId
                r.success -> resealed += share.shareId
                // closed since the list was read: the server refuses a re-seal under a closed link, as this loop skips one
                r.status == 422 && "link_closed" in runCatching { r.response.bodyAsText() }.getOrDefault("") -> closed += share.shareId
                else -> failed += share.shareId
            }
        }
        return Report(resealed, closed, skipped, failed)
    }

    /** Switched on and not past its end (`isOpenLink`). */
    internal fun isOpen(share: ShareSummary, nowMs: Long): Boolean =
        share.enabled && (share.expiresAt == null || (runCatching { kotlin.time.Instant.parse(share.expiresAt!!).toEpochMilliseconds() }.getOrNull() ?: 0) > nowMs)

    /** `K_share` from its recovery copy (`recoverShareKey`), or null when the wrap or the copy does not open here. */
    internal fun recoverShareKey(
        suite: me.raddatz.veyq.mobile.crypto.V1, joins: JoinRecords, share: ShareSummary, copyEpoch: Int,
        scopeKey: ByteArray, scopeEpoch: Long, wrappedValueKey: String, sealedShareKey: String,
    ): ByteArray? = runCatching {
        val context = Context("share-key-recovery", "scopeId" to share.scopeId, "nodeId" to share.nodeId, "shareId" to share.shareId, "epoch" to copyEpoch.toLong())
        val valueKey = suite.open(joins.valueKeyContext(share.scopeId, scopeEpoch, joins.valueAddress(Purposes.SEAL, context)), scopeKey, unb64(wrappedValueKey))
        try {
            suite.open(context, valueKey, unb64(sealedShareKey))
        } finally {
            valueKey.fill(0)
        }
    }.getOrNull()
}
