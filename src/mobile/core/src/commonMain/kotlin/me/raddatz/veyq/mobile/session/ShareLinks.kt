package me.raddatz.veyq.mobile.session

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.SharingApi
import me.raddatz.veyq.mobile.api.models.CreateShareRequest
import me.raddatz.veyq.mobile.api.models.ShareRole
import me.raddatz.veyq.mobile.api.models.ShareSummary
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Purposes
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.b64url
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8

/**
 * A node's guest links, core's for every kind that shares (the web's `core/sharing/manageShares.ts` `listSharesFor` and
 * `mintShare`, `shareLink.ts`; ADR 0072, 0076, 0115): READ and READ_WRITE links only — an "add files only" link belongs to
 * an application's drop box. Closing one (switched off, deleted, renewed) re-keys the subtree below it and is not here.
 *
 * A link is `/s/<shareId>#k=<K_share>`: the fragment never reaches a server. `K_share` (32 random bytes) opens the node's
 * key through the link's `wrap` (`share-key`, no binding) and the root's name (`share-memory-name`); a recovery copy — `K_share`
 * under a fresh value key bound to (scope, node, share, the scope's epoch), that value key under the scope key — lets every
 * member who holds the scope rebuild the link and re-seal under it later.
 */
object ShareLinks {
    const val READ = "READ"
    const val READ_WRITE = "READ_WRITE"

    /**
     * The tenant's links as its capability set has them (`core.public-shares` and its children, `linkRules.ts`): whether
     * links are offered at all ([on]) and with guests who add ([contribute]), a password required, the most days a link may
     * last. A set that did not read is [UNKNOWN]: everything offered, the server decides.
     */
    class Rules(val on: Boolean, val contribute: Boolean, val passwordRequired: Boolean, val maxDays: Int?) {
        companion object {
            val UNKNOWN = Rules(on = true, contribute = true, passwordRequired = false, maxDays = null)

            fun of(capabilities: List<me.raddatz.veyq.mobile.api.models.Capability>?): Rules {
                if (capabilities == null) return UNKNOWN
                val links = capabilities.firstOrNull { it.id == "core.public-shares" }
                    ?: return Rules(on = false, contribute = false, passwordRequired = false, maxDays = null)
                return Rules(true, capabilities.any { it.id == "core.public-shares.contribute" }, links.linkPassword == true, links.linkMaxDays)
            }
        }
    }

    /** One link as the dialog lists it; [url] null where its recovery copy does not open here (it cannot be copied). */
    class Link(
        val shareId: String,
        val role: String,
        val enabled: Boolean,
        /** Minted by this member: only its own links are edited and switched off. */
        val mine: Boolean,
        val createdAt: String,
        val expiresAt: String?,
        /** The member's own name for it (`share-name`), empty when none. */
        val label: String,
        val hasNote: Boolean,
        val passwordRequired: Boolean,
        val url: String?,
        /** The server closed it for good (a rule tightened): it can only be renewed. */
        val reissueRequired: Boolean,
        /** Guests are stopped by a rule (a capability switched off, an expiry cap). */
        val stopped: Boolean,
    )

    /** A link's address: the tenant's origin, its id, `K_share` in the fragment (base64url, no padding). */
    fun url(origin: String, shareId: String, kShare: ByteArray): String = "${origin.trimEnd('/')}/s/$shareId#k=${b64url(kShare)}"

    /**
     * The READ and READ_WRITE links on node [nodeId] of [scopeId], newest first; null when they do not list. Each row is
     * opened at the address of THIS node (never the row's claim): its label and link rebuilt from the recovery copy.
     */
    @Throws(Exception::class)
    suspend fun list(tenant: TenantSession, scopeId: String, nodeId: String): List<Link>? {
        val account = tenant.account
        val suite = account.suite
        val (scopeKey, scopeEpoch) = tenant.scopeKey(scopeId) ?: return null
        val r = runCatching { account.respond { SharingApi(tenant.base, account.http).listShares(nodeId) } }.getOrNull() ?: return null
        if (!r.success) return null
        val joins = JoinRecords(suite)
        return r.body().filter { it.role != ShareRole.WRITE_ONLY }.sortedByDescending { it.createdAt }.map { s ->
            val kShare = recover(suite, joins, s, scopeId, nodeId, scopeKey, scopeEpoch)
            try {
                val label = kShare?.let { k ->
                    s.sealedShareName?.let { runCatching { suite.openField(Context("share-name"), k, unb64(it)).decodeToString() }.getOrNull() }
                }.orEmpty()
                Link(
                    shareId = s.shareId, role = s.role.value, enabled = s.enabled, mine = s.mine, createdAt = s.createdAt,
                    expiresAt = s.expiresAt, label = label, hasNote = s.sealedNote != null, passwordRequired = s.passwordRequired == true,
                    url = kShare?.let { url(tenant.base, s.shareId, it) }, reissueRequired = s.reissueRequired == true,
                    stopped = !s.guestStops.isNullOrEmpty(),
                )
            } finally {
                kShare?.fill(0)
            }
        }
    }

    private fun recover(
        suite: me.raddatz.veyq.mobile.crypto.V1, joins: JoinRecords, s: ShareSummary, scopeId: String, nodeId: String,
        scopeKey: ByteArray, scopeEpoch: Int,
    ): ByteArray? {
        val sealed = s.sealedShareKey ?: return null
        val copyEpoch = s.sealedShareKeyEpoch ?: return null
        val wrapped = s.wrappedRecoveryValueKey ?: return null
        // the address of the node the dialog is on, never the row's own claim (`FolderShareDialog`)
        val at = s.copy(scopeId = scopeId, nodeId = nodeId)
        return ShareNames.recoverShareKey(suite, joins, at, copyEpoch, scopeKey, scopeEpoch.toLong(), wrapped, sealed)
    }

    enum class Outcome {
        CREATED,
        /** The tenant's rules require a password (`422 link_password_required`). */
        PASSWORD_REQUIRED,
        /** The tenant's rules require an end, or the end is past their cap (`422 link_expiry_required`, `date_time_out_of_range`). */
        EXPIRY_REJECTED,
        /** Links are switched off here, or not allowed on this node (`422 capability_disabled`, `illegal_scope`). */
        NOT_ALLOWED,
        /** The space is being deleted. */
        SCOPE_DELETING,
        /** The node is not re-keyed since its space's key changed, and still was not after waiting. */
        BUSY,
        FAILED,
    }

    /** What a mint did: on [Outcome.CREATED] the link to hand out, once ([url]). */
    class Minted(val outcome: Outcome, val url: String? = null, /** The server's refusal (status and code), for a log. */ val refusal: String? = null)

    /**
     * Mints a [role] link on node [nodeId] of [scopeId]: [label] (blank: none) the member's own name for it, [password]
     * (blank: none) what its guests must give, [expiresAt] (ISO 8601, null: no end) when it closes. Presence is decided on
     * the trimmed text, the text as typed is what is sealed (`mintShare`). A `stale_epoch` re-reads and re-seals both the wrap
     * and the recovery copy once; a re-key in flight waits ([NodeEpochWrite]). The id and `K_share` stay across retries;
     * a mint is never resent after an answer was lost (the server would answer `id_taken`): the list is read again.
     */
    @Throws(Exception::class)
    suspend fun mint(
        tenant: TenantSession, scopeId: String, nodeId: String, role: String, label: String, password: String, expiresAt: String?,
        waitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS,
    ): Minted {
        if (role != READ && role != READ_WRITE) return Minted(Outcome.NOT_ALLOWED)
        val account = tenant.account
        val suite = account.suite
        val joins = JoinRecords(suite)
        val (scopeKey, scopeEpoch) = tenant.scopeKey(scopeId) ?: return Minted(Outcome.FAILED)
        var node = tenant.trees()[scopeId]?.firstOrNull { it.id == nodeId } ?: return Minted(Outcome.FAILED)
        val kShare = suite.p.randomBytes(32)
        val shareId = b64url(suite.p.randomBytes(32))
        // What does not depend on the node's key or the scope's epoch is sealed once.
        val sealedShareName = label.takeIf { it.isNotBlank() }?.let { b64(suite.sealField(Context("share-name"), kShare, utf8(it))) }
        val writeVerifier = b64(suite.p.sha256(suite.shareWriteToken(kShare, shareId)))
        val pw = password.takeIf { it.isNotBlank() }?.let {
            val salt = suite.p.randomBytes(16)
            Triple(b64(salt), b64(suite.p.sha256(suite.sharePasswordToken(it, salt))), b64(suite.seal(Context("share-password"), kShare, utf8(it))))
        }
        var code: String? = null
        var lastBody = ""
        try {
            val result = NodeEpochWrite.run(
                waitsMs = waitsMs,
                // never wrap a node key from before the space's last rotation
                belowFloor = { node.scopeEpoch < scopeEpoch },
                reread = {
                    tenant.forgetTree()
                    node = tenant.trees()[scopeId]?.firstOrNull { it.id == nodeId } ?: return@run false
                    true
                },
                attempt = {
                    val recovery = Context("share-key-recovery", "scopeId" to scopeId, "nodeId" to nodeId, "shareId" to shareId, "epoch" to scopeEpoch.toLong())
                    val valueKey = suite.p.randomBytes(32)
                    val request = try {
                        CreateShareRequest(
                            nodeEpoch = node.epoch,
                            shareId = shareId,
                            nodeId = nodeId,
                            role = if (role == READ) ShareRole.READ else ShareRole.READ_WRITE,
                            sealedName = b64(suite.sealField(Context("share-memory-name"), kShare, utf8(node.name))),
                            sealedShareKey = b64(suite.seal(recovery, valueKey, kShare)),
                            wrappedRecoveryValueKey = b64(
                                suite.seal(joins.valueKeyContext(scopeId, scopeEpoch.toLong(), joins.valueAddress(Purposes.SEAL, recovery)), scopeKey, valueKey),
                            ),
                            sealedShareKeyEpoch = scopeEpoch,
                            writeVerifier = writeVerifier,
                            wrap = b64(suite.seal(Context("share-key"), kShare, node.key)),
                            sealedDropPublicKey = null,
                            sealedDropPrivateKey = null,
                            expiresAt = expiresAt,
                            sealedNote = null,
                            sealedShareName = sealedShareName,
                            passwordRequired = pw != null,
                            pwSalt = pw?.first,
                            pwVerifier = pw?.second,
                            sealedPassword = pw?.third,
                        )
                    } finally {
                        valueKey.fill(0)
                    }
                    val r = account.respond { SharingApi(tenant.base, account.http).createShare(request) }
                    val body = if (r.success) "" else runCatching { r.response.bodyAsText() }.getOrDefault("")
                    code = if (r.success) null else codeOf(body)
                    if (!r.success) lastBody = body.take(300)
                    val answer = when {
                        r.success -> NodeEpochWrite.Answer.DONE
                        r.status == 409 && code == "node_rekey_pending" -> NodeEpochWrite.Answer.REKEY_PENDING
                        r.status == 409 && code == "stale_epoch" -> NodeEpochWrite.Answer.STALE_EPOCH
                        else -> NodeEpochWrite.Answer.OTHER
                    }
                    answer to r.status
                },
            )
            return when (result) {
                NodeEpochWrite.Result.Busy -> Minted(Outcome.BUSY)
                is NodeEpochWrite.Result.Answered -> when {
                    result.value in 200..299 -> Minted(Outcome.CREATED, url(tenant.base, shareId, kShare))
                    code == "link_password_required" -> Minted(Outcome.PASSWORD_REQUIRED)
                    code == "link_expiry_required" || code == "date_time_out_of_range" -> Minted(Outcome.EXPIRY_REJECTED)
                    code == "capability_disabled" || code == "illegal_scope" -> Minted(Outcome.NOT_ALLOWED)
                    code == "scope_deleting" -> Minted(Outcome.SCOPE_DELETING)
                    else -> Minted(Outcome.FAILED, refusal = "${result.value} $code $lastBody")
                }
                else -> Minted(Outcome.FAILED, refusal = result.toString())
            }
        } finally {
            kShare.fill(0)
        }
    }

    private fun codeOf(body: String): String? = runCatching { (Json.parseToJsonElement(body) as JsonObject)["code"]?.jsonPrimitive?.content }.getOrNull()
}
