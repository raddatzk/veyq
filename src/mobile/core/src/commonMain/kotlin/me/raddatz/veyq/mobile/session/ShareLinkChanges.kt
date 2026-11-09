package me.raddatz.veyq.mobile.session

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.SharingApi
import me.raddatz.veyq.mobile.api.models.ReissueShareRequest
import me.raddatz.veyq.mobile.api.models.ShareRole
import me.raddatz.veyq.mobile.api.models.ShareSummary
import me.raddatz.veyq.mobile.api.models.UpdateShareRequest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Purposes
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.b64url
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8

/**
 * A member's own link changed after its mint (the web's `manageShares.ts` `updateShare`, `remintShare`, `deleteShare`;
 * ADR 0036, 0037, 0075, 0114): edited in place — its label, password, end and role, `K_share` and the address kept —,
 * renewed — a new id and a fresh `K_share` carrying its label, note, password and end, the old link closed in the same
 * server transaction —, or deleted once it is off. A link that closes (renewed while open, or deleted while open) has the
 * node below it re-keyed on this device ([NodeRekey]), since the old `K_share` opened the node's key.
 *
 * Moving a past end into the future (the web's reopen by date) and switching a link on again are not offered here: renewing
 * gives a closed link a working address again.
 */
object ShareLinkChanges {
    enum class Outcome {
        DONE,
        /** It is not this member's, not open, or gone from the list. */
        NOT_ALLOWED,
        /** Its password could not be read back, so a renew would drop the link's protection: refused (`share_password_not_recoverable`). */
        PASSWORD_LOST,
        /** The tenant's rules refuse what was asked (a password, an end). */
        RULES,
        /** Another renew or a delete got there first (`409 share_deleted`): read the list again. */
        GONE,
        /** Done; the re-key of what the closed link opened is left to a member's web device. */
        DONE_REKEY_PENDING,
        BUSY,
        FAILED,
    }

    class Changed(val outcome: Outcome, val url: String? = null)

    /** What an edit sets: the label (blank: none), the password (null: untouched; blank: removed), the end (null: none), the role. */
    class Edit(val label: String, val password: String?, val expiresAt: String?, val role: String)

    private suspend fun row(tenant: TenantSession, nodeId: String, shareId: String): ShareSummary? = runCatching {
        tenant.account.call { SharingApi(tenant.base, tenant.account.http).listShares(nodeId) }.firstOrNull { it.shareId == shareId }
    }.getOrNull()

    /** Edits link [shareId] on [nodeId] of [scopeId] in place: its own and open links only (`canEdit`). */
    @Throws(Exception::class)
    suspend fun edit(tenant: TenantSession, scopeId: String, nodeId: String, shareId: String, edit: Edit, nowMs: Long): Changed {
        val account = tenant.account
        val suite = account.suite
        val s = row(tenant, nodeId, shareId) ?: return Changed(Outcome.NOT_ALLOWED)
        if (!s.mine || !ShareNames.isOpen(s, nowMs) || s.role == ShareRole.WRITE_ONLY) return Changed(Outcome.NOT_ALLOWED)
        val (scopeKey, scopeEpoch) = tenant.scopeKey(scopeId) ?: return Changed(Outcome.FAILED)
        val kShare = ShareLinks.recover(suite, JoinRecords(suite), s, scopeId, nodeId, scopeKey, scopeEpoch) ?: return Changed(Outcome.NOT_ALLOWED)
        val request = try {
            val pw = edit.password?.takeIf { it.isNotBlank() }?.let {
                val salt = suite.p.randomBytes(16)
                Triple(b64(salt), b64(suite.p.sha256(suite.sharePasswordToken(it, salt))), b64(suite.seal(Context("share-password"), kShare, utf8(it))))
            }
            UpdateShareRequest(
                // a full replace: the note as it is (the app does not edit it), the label as typed or none
                sealedNote = s.sealedNote,
                sealedShareName = edit.label.takeIf { it.isNotBlank() }?.let { b64(suite.sealField(Context("share-name"), kShare, utf8(it))) },
                role = if (edit.role == ShareLinks.READ_WRITE) ShareRole.READ_WRITE else ShareRole.READ,
                expiresAt = edit.expiresAt,
                // the tri-state: null leaves it, true sets it (with its fields), false removes it
                passwordRequired = when {
                    edit.password == null -> null
                    pw != null -> true
                    else -> false
                },
                pwSalt = pw?.first, pwVerifier = pw?.second, sealedPassword = pw?.third,
            )
        } finally {
            kShare.fill(0)
        }
        val r = account.respond { SharingApi(tenant.base, account.http).updateShare(shareId, request) }
        if (!r.success) return Changed(refusal(r.status, runCatching { r.response.bodyAsText() }.getOrDefault("")))
        return Changed(Outcome.DONE)
    }

    /**
     * Renews link [shareId] on [nodeId] of [scopeId] (`remintShare`): a new id and `K_share`, its label, note, password and
     * end carried, the old link closed; the node below re-keyed if the old one was open. On success the new address, once.
     */
    @Throws(Exception::class)
    suspend fun renew(
        tenant: TenantSession, scopeId: String, nodeId: String, shareId: String, content: NodeRekey.ContentRekey?,
        tokenInput: (kind: String, name: String) -> String?, uuid: () -> String, nowMs: Long,
        waitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS,
    ): Changed {
        val account = tenant.account
        val suite = account.suite
        val joins = JoinRecords(suite)
        val old = row(tenant, nodeId, shareId) ?: return Changed(Outcome.NOT_ALLOWED)
        // renew where the link is this member's or already off (`canRenew`)
        if (!(old.mine || !old.enabled) || old.role == ShareRole.WRITE_ONLY) return Changed(Outcome.NOT_ALLOWED)
        val wasOpen = ShareNames.isOpen(old, nowMs)
        val (scopeKey, scopeEpoch) = tenant.scopeKey(scopeId) ?: return Changed(Outcome.FAILED)
        val oldKey = ShareLinks.recover(suite, joins, old, scopeId, nodeId, scopeKey, scopeEpoch) ?: return Changed(Outcome.NOT_ALLOWED)
        // the old link's settings, read through its recovery copy (`carriedSettings`)
        val carried = try {
            val label = old.sealedShareName?.let { runCatching { suite.openField(Context("share-name"), oldKey, unb64(it)).decodeToString() }.getOrNull() }.orEmpty()
            val note = old.sealedNote?.let { runCatching { suite.openField(Context("share-note"), oldKey, unb64(it)) }.getOrNull() }
            val password = old.sealedPassword?.let { runCatching { suite.open(Context("share-password"), oldKey, unb64(it)).decodeToString() }.getOrNull() }.orEmpty()
            if (old.passwordRequired == true && password.isBlank()) return Changed(Outcome.PASSWORD_LOST)
            Triple(label, note, password)
        } finally {
            oldKey.fill(0)
        }
        val (label, note, password) = carried
        var node = tenant.trees()[scopeId]?.firstOrNull { it.id == nodeId } ?: return Changed(Outcome.FAILED)
        // never the old K_share: the old one is what a departed member may hold
        val kShare = suite.p.randomBytes(32)
        val newId = b64url(suite.p.randomBytes(32))
        var code: String? = null
        try {
            val pw = password.takeIf { it.isNotBlank() }?.let {
                val salt = suite.p.randomBytes(16)
                Triple(b64(salt), b64(suite.p.sha256(suite.sharePasswordToken(it, salt))), b64(suite.seal(Context("share-password"), kShare, utf8(it))))
            }
            val result = NodeEpochWrite.run(
                waitsMs = waitsMs,
                belowFloor = { node.scopeEpoch < scopeEpoch },
                reread = {
                    tenant.forgetTree()
                    node = tenant.trees()[scopeId]?.firstOrNull { it.id == nodeId } ?: return@run false
                    true
                },
                attempt = {
                    val recovery = Context("share-key-recovery", "scopeId" to scopeId, "nodeId" to nodeId, "shareId" to newId, "epoch" to scopeEpoch.toLong())
                    val valueKey = suite.p.randomBytes(32)
                    val request = try {
                        ReissueShareRequest(
                            nodeEpoch = node.epoch,
                            shareId = newId,
                            sealedName = b64(suite.sealField(Context("share-memory-name"), kShare, utf8(node.name))),
                            sealedShareKey = b64(suite.seal(recovery, valueKey, kShare)),
                            wrappedRecoveryValueKey = b64(suite.seal(joins.valueKeyContext(scopeId, scopeEpoch.toLong(), joins.valueAddress(Purposes.SEAL, recovery)), scopeKey, valueKey)),
                            sealedShareKeyEpoch = scopeEpoch,
                            writeVerifier = b64(suite.p.sha256(suite.shareWriteToken(kShare, newId))),
                            wrap = b64(suite.seal(Context("share-key"), kShare, node.key)),
                            sealedDropPublicKey = null,
                            expiresAt = old.expiresAt,
                            sealedNote = note?.let { b64(suite.sealField(Context("share-note"), kShare, it)) },
                            sealedShareName = label.takeIf { it.isNotBlank() }?.let { b64(suite.sealField(Context("share-name"), kShare, utf8(it))) },
                            passwordRequired = pw != null,
                            pwSalt = pw?.first, pwVerifier = pw?.second, sealedPassword = pw?.third,
                        )
                    } finally {
                        valueKey.fill(0)
                    }
                    val r = account.respond { SharingApi(tenant.base, account.http).reissueShare(shareId, request) }
                    code = if (r.success) null else codeOf(runCatching { r.response.bodyAsText() }.getOrDefault(""))
                    val answer = when {
                        r.success -> NodeEpochWrite.Answer.DONE
                        r.status == 409 && code == "node_rekey_pending" -> NodeEpochWrite.Answer.REKEY_PENDING
                        r.status == 409 && code == "stale_epoch" -> NodeEpochWrite.Answer.STALE_EPOCH
                        else -> NodeEpochWrite.Answer.OTHER
                    }
                    answer to r.status
                },
            )
            val status = (result as? NodeEpochWrite.Result.Answered)?.value
            if (result == NodeEpochWrite.Result.Busy) return Changed(Outcome.BUSY)
            if (status == null || status !in 200..299) {
                return Changed(if (code == "share_deleted") Outcome.GONE else refusal(status ?: 0, "\"code\":\"$code\""))
            }
            val url = ShareLinks.url(tenant.base, newId, kShare)
            if (!wasOpen) return Changed(Outcome.DONE, url)
            val rekeyed = runCatching { NodeRekey.rekey(tenant, scopeId, nodeId, content, tokenInput, uuid) }.getOrDefault(NodeRekey.Outcome.FAILED)
            return Changed(if (rekeyed == NodeRekey.Outcome.REKEYED || rekeyed == NodeRekey.Outcome.NOT_DUE) Outcome.DONE else Outcome.DONE_REKEY_PENDING, url)
        } finally {
            kShare.fill(0)
        }
    }

    /** Deletes link [shareId] for good (`deleteShare`, a tombstone); one that was still open has the node below re-keyed. */
    @Throws(Exception::class)
    suspend fun delete(
        tenant: TenantSession, scopeId: String, nodeId: String, shareId: String, content: NodeRekey.ContentRekey?,
        tokenInput: (kind: String, name: String) -> String?, uuid: () -> String, nowMs: Long,
    ): Changed {
        val s = row(tenant, nodeId, shareId) ?: return Changed(Outcome.GONE)
        if (s.deletable != true) return Changed(Outcome.NOT_ALLOWED)
        val wasOpen = ShareNames.isOpen(s, nowMs)
        val r = tenant.account.respond { SharingApi(tenant.base, tenant.account.http).deleteShare(shareId) }
        if (!r.success) return Changed(if (r.status == 404) Outcome.GONE else Outcome.FAILED)
        if (!wasOpen) return Changed(Outcome.DONE)
        val rekeyed = runCatching { NodeRekey.rekey(tenant, scopeId, nodeId, content, tokenInput, uuid) }.getOrDefault(NodeRekey.Outcome.FAILED)
        return Changed(if (rekeyed == NodeRekey.Outcome.REKEYED || rekeyed == NodeRekey.Outcome.NOT_DUE) Outcome.DONE else Outcome.DONE_REKEY_PENDING)
    }

    private fun refusal(status: Int, body: String): Outcome {
        val code = codeOf(body)
        return when {
            code == "link_password_required" || code == "link_expiry_required" || code == "date_time_out_of_range" -> Outcome.RULES
            code == "share_deleted" || status == 404 -> Outcome.GONE
            status == 403 || code == "invalid_role_change" -> Outcome.NOT_ALLOWED
            else -> Outcome.FAILED
        }
    }

    private fun codeOf(body: String): String? = runCatching { (Json.parseToJsonElement(body) as JsonObject)["code"]?.jsonPrimitive?.content }.getOrNull()
}
